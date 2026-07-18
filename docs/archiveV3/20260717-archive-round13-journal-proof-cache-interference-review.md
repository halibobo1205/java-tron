# Archive round-13 journal proof, cache, and interference review

- Date: 2026-07-17
- Branch: `feat/archive-node`
- Review base: `7e0fb04aa3`
- Layout: `UNIFIED_V1` only
- Status: IMPLEMENTED; FOCUSED, AGGREGATE, INTEGRATION, AND CHECKSTYLE GATES PASSED

## 1. Objective

Round 13 continued the adversarial review with three independent lenses:

1. in-flight journal crash evidence, publication comparison, and startup reconciliation;
2. HISTORY/CHANGESET large values, native materialization, publication copying, and block-cache
   pollution;
3. query/publication lock interference, RocksDB lifecycle serialization, and shared physical IO.

The coordinator reproduced each implemented finding before changing behavior. This round closes the
journal evidence gap and several Java-lock/cache interference paths. It does not claim that current
variable-sized temporal rows provide a strict native-memory or physical-IO boundary.

## 2. Required invariants

1. A durable journal proof must bind the exact encoded payload, journal key, generation token,
   range, positions, records, values, and codec version.
2. Startup must verify that proof before decoding or exposing any journal block to a consumer.
3. Publication must compare the current journal under the same mutation lock as its atomic batch;
   startup validation alone is not sufficient.
4. Proof, acknowledgement, payload, and publication deletion must preserve the existing WAL/sync
   and cross-column-family atomicity rules.
5. `published range + in-flight journal` is impossible after a successful unified atomic batch and
   must be preserved as corruption evidence rather than silently cleaned.
6. Journal corruption discovered before service fatal-control construction must still persist a
   forced-sync repair-required marker.
7. Query snapshot creation must not wait for an unrelated low-level publication monitor.
8. A from-genesis reader may use one immutable unified snapshot without retaining the service
   consistency lock for its lifetime. Mid-chain read-through remains protected.
9. Journal comparison, startup scans, and temporal publication validation must not admit large
   payload blocks into the shared archive block cache.
10. Archive-off and canonical consensus state transitions must remain unchanged.

## 3. Confirmed findings

| ID | Severity | Finding | Consequence |
|---|---|---|---|
| R13-1 | High | The durable lifecycle token did not authenticate journal content. Startup decoded a payload and then used bytes re-encoded from that same payload as the publication expectation. | A same-length, semantically valid payload rewrite could survive restart and publish incorrect archive state while retaining the original token and acknowledgement. |
| R13-2 | High | Unified startup accepted `published range + journal` and later deleted the journal even though unified publication writes rows and deletes the journal in one atomic batch. | An impossible/corrupt state lost its evidence and could hide a writer or disk-integrity failure. |
| R13-3 | Medium | Every `UnifiedArchiveDb` operation synchronized on one Java monitor; forced-sync publication held it across complete journal reads and the native batch write. | Query snapshot creation could wait behind an arbitrarily slow publication even though RocksDB snapshots are safe to create concurrently. |
| R13-4 | Medium | Service-level point resolution always acquired the consistency read lock before opening a unified snapshot, including a complete from-genesis archive. | A genesis-complete query could still wait behind publication/fsync before obtaining its immutable snapshot. |
| R13-5 | Medium | Journal compare reads used default cache-filling RocksDB Gets. Publication then deleted the values immediately. | A large flushed journal could evict useful index/latest blocks from the shared block cache during publish or rollback. |
| R13-6 | Medium | `loadInFlightBlocks()` ran before the fatal controller existed and the factory only closed resources when it failed. | Structural journal corruption reported on one startup did not leave a durable repair-required marker for the next startup or operator. |
| R13-7 | Low | Temporal publication validation and direct maintenance reads opened cache-filling views. | Large HISTORY/LATEST/CHANGESET predecessors could pollute the block cache even though query views themselves used `fillCache=false`. |

## 4. Red-light evidence

The pre-fix implementation failed the following adversarial cases:

- `journalProofRejectsSemanticallyValidSameLengthPayloadRewrite`: rewrote an Account balance from
  1 to 2 without changing encoded length, token, range, or acknowledgement; the old loader accepted
  it.
- `publishedAndUnacknowledgedJournalCoexistenceFailsClosed`: the old service accepted an impossible
  unacknowledged journal beside an already published range.
- `publishedAndAcknowledgedJournalCoexistenceFailsClosed`: the old service also accepted an
  acknowledged conflicting journal beside the published range.
- `factoryPersistsRepairMarkerForJournalCorruption`: the old factory failed startup without writing
  repair-required.
- `blockedPublicationDoesNotBlockQuerySnapshotCreation`: the old database monitor prevented a
  query snapshot from opening while an injected batch writer was blocked.
- `genesisCompleteUnifiedReaderOpensDuringConsistencyWrite`: the old service waited for the
  consistency writer even though the complete archive snapshot needed no live read-through.

The low-level cache regression records zero data-block bytes inserted by journal publication
comparison. The high-level temporal regression publishes a 128 KiB account value after reopen and
allows only the small index metadata block, not the payload block, to enter cache.

## 5. Implementation

### 5.1 Versioned journal proof

- The unified layout schema is now 4. There is no deployed older archive layout to migrate; a
  mismatched development database fails explicitly and must be rebuilt.
- The token lifecycle row is now a fixed-size versioned proof containing the generation token,
  payload length, and SHA-256 digest. The acknowledgement copies that complete proof.
- The digest uses the independent domain
  `tron-archive-unified/inflight-journal-proof/v1` and binds the journal key, payload length, and
  complete encoded payload. The payload already contains codec version, token, complete range,
  positions, records, keys, previous values, and current values.
- Startup performs a bounded payload read, validates length/digest using constant-time digest
  comparison, and only then decodes or invokes a consumer.
- Publication still performs the original byte-for-byte journal comparison under the database
  mutation lock. The proof closes the restart circularity; it does not replace exact publication
  comparison with an O(1) metadata check.
- A post-startup payload rewrite test confirms publication rechecks current bytes and leaves the
  journal and unpublished index intact on failure.

SHA-256 supplies computational integrity rather than mathematical collision impossibility. Exact
publication comparison is retained for deterministic same-process evidence. Verifying a durable
payload after restart necessarily remains O(J); metadata cannot prove unexamined bytes.

### 5.2 Acknowledgement and atomic lifecycle

- Initial payload and proof remain one forced-sync, WAL-enabled batch.
- Acknowledgement remains WAL-enabled and WAL-only, and copies the exact proof after comparing its
  generation token. It reads only the compact proof and no longer re-encodes, hashes, or reads the
  complete journal payload.
- Publication and rollback compare the current payload/proof/acknowledgement and delete all three
  rows in their existing forced-sync atomic batch.
- Failure injection coverage continues to prove all-or-nothing initial write, acknowledgement,
  publication, and rollback behavior.

The acknowledgement byte counter test uses a 128 KiB journal and observes less than 1 KiB of
logical RocksDB value reads. Missing or altered payload bytes remain detectable by startup and the
mandatory publication comparison.

### 5.3 Impossible state and durable repair evidence

- Unified service construction now rejects any in-flight journal whose range is already published.
  It does not reconcile or delete the journal.
- The in-memory oracle path retains its old stale-journal behavior for targeted unit fault models;
  production factory output is unified only.
- Structural journal failures use `ArchiveJournalCorruptionException`.
- The factory catches that typed failure while the unified index is still open, forced-syncs the
  repair-required reason, closes resources, and rethrows the original failure. Marker failure is
  attached as suppressed evidence rather than replacing the primary cause.

### 5.4 Database and service lock isolation

- `UnifiedArchiveDb` now uses a lifecycle read/write lock plus a fair mutation lock. Mutations are
  serialized with each other, close waits for an active mutation, and query snapshot creation is no
  longer serialized behind the complete publication operation.
- Active snapshot accounting is atomic and close failure remains sticky.
- A genesis-complete unified reader opens one snapshot, resolves the floor and requested point in
  that snapshot, then releases mutation protection without waiting for the consistency lock.
- A paired mid-chain test confirms the read-through path still waits for consistency protection.

### 5.5 Cache isolation

- Every journal compare/read inside mutation operations uses one `ReadOptions(fillCache=false)`.
- In-flight startup already uses a non-cache-filling scan snapshot; proof reads retain that policy.
- Temporal publication preflight, direct point helpers, and startup temporal views now use
  non-cache-filling snapshots.
- Query snapshots continue to use both `fillCache=false` and their remaining native deadline.

This isolates RocksDB block-cache admission. It does not isolate the OS page cache, filesystem,
device queue, compaction workers, or sync latency.

## 6. Adversarial exclusions

The review explicitly rejected the following tempting but unsafe optimizations:

1. **Token-only publication.** The generation token does not authenticate range/records/values.
2. **Length-only validation.** Same-length protobuf rewrites are valid and were reproduced.
3. **Digest without reading payload.** A stored digest cannot validate bytes that publication or
   startup never reads.
4. **Deleting `published + journal`.** Unified atomic publication cannot create that state; cleanup
   would destroy evidence.
5. **Applying `noSlowdown` to journal commit.** Turning a required canonical-side durability write
   stall into an immediate archive failure changes failure shape but does not make commit safe.
6. **Releasing mid-chain reader locks.** Its live/in-flight shield is not yet an immutable snapshot.

## 7. Residual risks and next hard gates

The following confirmed architecture risks remain open:

1. **Native large-value materialization.** `getBounded` limits the final Java result, but both
   supported RocksJava generations may materialize the complete native value before returning its
   length. HISTORY iterator seek may also read/decompress the data block before Java checks the
   request budget.
2. **Lifecycle scans of variable payloads.** Normal tail validation, full scrub, and unwind still
   materialize HISTORY/CHANGESET/LATEST/COMMITMENT values. Unwind also retains restored values for
   the batch.
3. **Publication allocation before a format limit.** Temporal encoding and integrity envelopes can
   create several payload copies before the publication builder enforces its retained-byte limit.
   There is no immutable per-row on-disk format maximum yet.
4. **Required format direction.** A strict boundary needs a fixed-size locator in each logical
   temporal CF plus a separate payload CF, all read from one snapshot and written/deleted in one
   batch. Splitting HISTORY alone is insufficient because startup, scrub, and unwind read the other
   families.
5. **Mid-chain reader interference.** `firstArchivedBlock > 0` readers retain service consistency
   and mutation protection until close. A slow RPC can therefore delay commit/publication. The
   supported production target remains from-empty; a general solution needs an immutable in-flight
   overlay generation.
6. **Canonical-store bypasses.** Point hash validation and historical VM `BLOCKHASH` still access
   canonical stores whose Gets do not inherit archive query cache/deadline options.
7. **Write stalls and physical resources.** Forced-sync journal/publication writes may stall, and
   archive/canonical databases still share default Env background pools, filesystem/page cache, and
   possibly the same device. Dedicated Env/cache/rate/background-job configuration and workload
   measurements remain required.
8. **Publication expansion.** The Java retained estimate is conservative but not an exact native
   `WriteBatch.getDataSize()` cap. Maximum-block RSS/JFR and native batch measurements remain a
   release gate.

## 8. Verification

Focused red/green tests for every implemented item passed. The first 571-test aggregate run found
one stale Mockito binding after temporal internal reads moved from a cache-filling view to a scan
view; the test now binds the actual non-cache-filling method, and the complete aggregate rerun
passed. The actuator historical VM set, focused framework integration set, StorageConfig test, and
both framework checkstyle gates also passed.

Final gates are recorded after completion:

```text
./gradlew :chainbase:test -x generateGitProperties \
  --tests 'org.tron.core.archive.*'

./gradlew :actuator:test -x generateGitProperties \
  --tests 'org.tron.core.vm.archive.*'

./gradlew :framework:test -x generateGitProperties \
  --tests 'org.tron.core.archive.*' \
  --tests 'org.tron.core.db.ManagerArchiveLifecycleTest' \
  --tests 'org.tron.core.db.ManagerGenesisArchiveLifecycleTest' \
  --tests 'org.tron.core.db.ManagerGenesisArchiveTest' \
  --tests 'org.tron.core.db.ManagerMockTest' \
  --tests 'org.tron.core.services.jsonrpc.CallArgumentsTest' \
  --tests 'org.tron.core.services.jsonrpc.Historical*' \
  --tests 'org.tron.core.services.jsonrpc.TronJsonRpcArchive*' \
  --tests 'org.tron.core.vm.archive.*'

./gradlew :common:test -x generateGitProperties \
  --tests 'org.tron.core.config.args.StorageConfigTest'

./gradlew :framework:checkstyleMain :framework:checkstyleTest \
  -x generateGitProperties

git diff --check
```

No confirmed journal-integrity, impossible-state cleanup, startup repair-marker, Java database
monitor, genesis-complete consistency-lock, or archive block-cache poisoning defect remains from
the Round 13 implemented set. Section 7 is an explicit next-round architecture and production
measurement boundary, not a production-readiness waiver.
