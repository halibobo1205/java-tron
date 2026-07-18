# Archive round-11 interference, toxic-cache, and continuity review

- Date: 2026-07-17
- Branch: `feat/archive-node`
- Review base: `7e0fb04aa3`
- Layout: `UNIFIED_V1` only
- Status: IMPLEMENTED; FOCUSED, AGGREGATE, AND CHECKSTYLE GATES PASSED

## 1. Objective

Round 11 treated archive queries and block execution as mutually hostile workloads. Three
independent adversarial passes reviewed temporal continuity, query/execution isolation, and
resource/cache behavior. The coordinator rechecked every reported chain against the current
source, added reproducing tests for confirmed defects, and then implemented the fixes.

The review concentrated on four questions:

1. Can a locally well-formed but incomplete temporal chain silently return the wrong state?
2. Can a historical query poison process health, shared state, admission counters, or caches?
3. Can execution, publication, fork switching, or startup recovery invalidate a live query?
4. Can query or journal work allocate or wait beyond its configured resource limits?

## 2. Required invariants

1. A temporal row is trustworthy only when its key, value envelope, changeset, physical
   predecessor, anchor, latest row, and block marker agree.
2. Query deadlines bound admission waits as well as VM execution and response construction.
3. Caller errors and normal reader-open I/O errors must not be misclassified as persistent index
   corruption.
4. Confirmed index/temporal corruption must fail-stop and persist repair-required evidence.
5. Snapshot index values must be size-checked before Java allocation and charged to the request.
6. A historical query may not observe a fork generation change during VM execution.
7. Archive-off and canonical block execution remain byte-for-byte behaviorally unchanged.

## 3. Confirmed findings

| ID | Severity | Finding | Consequence |
|---|---|---|---|
| R11-1 | High | Per-row integrity envelopes did not prove that the selected HISTORY row was linked to its physical predecessor. A validly re-encoded row could reset its link to the root value. | A historical read could silently return a value from the wrong version chain without full startup scrub. |
| R11-2 | High | Deleting a complete key-local temporal chain left no independent evidence that the key had ever entered archive history. | Coordinated loss of HISTORY, CHANGESET, LATEST, and baseline rows could be rendered as a normal missing value. |
| R11-3 | Medium | `latest()` and some tail checks did less integrity work than `getAsOf()`. | Startup/maintenance callers could accept damage that a point read rejected. |
| R11-4 | Medium | Query admission waited on the fork mutation barrier without consuming the request deadline. | A fork/reorg could hold historical request threads beyond configured wall-clock limits. |
| R11-5 | Medium | Unified snapshot index reads materialized values without a per-value allocation bound and did not consistently attach the query accounting context. | A malformed index value could allocate before rejection, and index bytes were missing from request budgets. |
| R11-6 | Medium | Index corruption during reader opening was surfaced as generic I/O, while an initial fix treated every open failure as fatal. | The first behavior missed repair evidence; the broad behavior could brick a healthy node on an ordinary reader lifecycle failure. |
| R11-7 | Medium | Transaction/opening index operation counts omitted internal validation reads; the missing-range coverage-floor fallback was also outside the corruption wrapper. | Requests could exceed configured backend-read limits, and a damaged floor row could escape as a raw exception. |
| R11-8 | Medium | `openReader(point)` allowed the reserved `Long.MAX_VALUE` coordinate to reach the index codec. | A malformed internal caller request was misclassified as corrupt storage and could poison archive health. |
| R11-9 | Medium | A VM `Error` could be overwritten in `finally` by a previously recorded query-limit failure. | The primary hard failure and its diagnostic stack could be hidden by a secondary budget signal. |
| R11-10 | Low | Fork switching re-entered the archive exclusive mutation lease for every erased block. | Reentrant epoch increments added redundant synchronization and obscured the single-generation fork invariant. |
| R11-11 | Low | Journal startup limits were individually bounded but not all enforced as one retained backlog budget before handing rows to reconciliation. | A valid multi-block journal could exceed configured aggregate records/bytes during startup. |

## 4. Implementation

### 4.1 Temporal continuity and independent anchors

- Added one key-bound anchor row in the `COMMITMENT` column family when a logical key first enters
  archive history. The anchor stores the first observed pre-value and its origin txNum.
- Kept the anchor across partial unwind and replacement, and removed it only on full unwind to zero.
- Included root anchors in block-marker digests and published anchors atomically with index,
  HISTORY, CHANGESET, LATEST, marker, cursor, and journal deletion.
- Required every selected HISTORY row to have an exact matching CHANGESET.
- Verified the selected row against the physical previous HISTORY key, not merely the txNum named
  inside its envelope. Root rows must link to `NO_HISTORY_TX_NUM` and match the anchor.
- Made append validation perform the same physical predecessor check before accepting a new tail.
- Routed `latest()` through the same complete `getAsOf(MAX)` integrity path.
- Extended full scrub to validate anchor keyspace, domain values, first-history continuity,
  latest/baseline agreement, changeset continuity, marker coverage, and unknown commitment keys.
- Raised the conservative temporal backend-read charge to 10 operations.

### 4.2 Query/execution isolation

- A managed reader owns a shared mutation lease for its lifetime. Fork switching owns the exclusive
  lease, so canonical BLOCKHASH reads and the final canonical-hash recheck belong to one fork
  generation.
- Shared mutation and consistency-lock acquisition now use the remaining monotonic request deadline
  and preserve interrupt status on cancellation.
- Genesis-complete readers still release the consistency lock after capturing the unified snapshot,
  so ordinary block commit can proceed while a historical VM call runs.
- Manager fork switching now reuses its existing exclusive lease for internal erases/unwinds rather
  than reacquiring it per block.

### 4.3 Bounded index reads and precise failure classification

- Unified snapshot index point reads use a 4096-byte native size probe before allocation.
- Added configured limits for one backend value and aggregate materialized backend bytes. Defaults
  are 4 MiB per value and 32 MiB per request.
- Attached `QueryContext` while resolving snapshot floor, range, position, and txId rows so bounded
  reads charge actual materialized bytes.
- Added `CORRUPT_INDEX` as an explicit reader reason. Only this reason during reader opening arms
  repair-required; generic lifecycle/open I/O remains request-local.
- Corrected transaction-reader accounting to 10 index operations, generic mid-chain reader opening
  to four operations, and charged the missing-range floor lookup before touching storage.
- Routed the missing-range floor lookup through the same corruption classification and fail-stop
  path as every other opening index read.
- Rejected externally supplied reserved block/tx coordinates as `HISTORY_UNAVAILABLE` before index
  access, so malformed requests cannot mark healthy storage corrupt.

### 4.4 Failure priority and startup memory

- Historical constant-call and trace executors preserve a primary `Error`; an already-recorded
  query failure is attached as suppressed evidence rather than replacing it.
- Journal decoding performs count, shape, and retained-byte preflight before constructing records.
- Startup reconciliation validates all journal rows first, enforces aggregate block/record/byte
  limits, then exposes the validated list to the consumer. This prevents partial reconciliation
  before a later oversized or malformed row is found.

## 5. Added adversarial coverage

- missing all temporal rows for a previously archived key;
- missing anchor, latest, baseline, HISTORY, or CHANGESET independently;
- valid-envelope history link rebased to the anchor while skipping a physical predecessor;
- append tail link that names a non-physical predecessor;
- full unwind, close, reopen, and clean startup with anchor removal;
- replacement history reusing the original anchor;
- block marker digest disagreement after anchor/history corruption;
- oversized unified index value rejected before materialization;
- exact index read and byte accounting for block and transaction readers;
- missing-range floor corruption classification and backend-read admission ordering;
- malformed external point coordinate unable to poison archive health;
- query deadline while blocked behind an exclusive fork mutation;
- generic reader-start failure remaining nonfatal;
- hard VM `Error` retaining priority over a recorded query limit;
- real Manager fork failure, switch-back recovery, journal rollback, and fresh txNum replay.

## 6. Interference conclusions

### Query impact on execution

- From-genesis historical reads use a unified RocksDB snapshot and do not hold the archive
  consistency lock during VM execution. They do not block normal archive commit.
- A query does hold the shared fork mutation lease until its result has passed canonical recheck.
  Fork/reorg waits for that query, now bounded from the request side by its deadline.
- Mid-chain activation still holds the consistency read lock for reader lifetime because live
  read-through and the in-flight earliest-prev shield must stay in one generation. Such a query can
  delay normal archive commit. This is an intentional correctness tradeoff, not closed as a
  performance claim.
- Historical VM writes remain in a per-request copy-on-write repository and cannot mutate canonical
  ChainBase or archive storage.

### Execution impact on queries

- Normal block execution cannot alter a captured genesis-complete unified snapshot.
- Fork switching cannot cross a live reader's mutation generation.
- Publication, marker, index, temporal rows, cursor, and journal deletion remain one RocksDB
  `WriteBatch`, so a reader sees the old or new generation, not a mixed publication.
- Reader-local decoded memo state is discarded at close; malformed typed values are removed before
  propagating codec errors. No cross-request Java object cache was found.

## 7. Residual risks and optimization gates

These are not closed by unit/integration correctness tests and must not be presented as measured
production performance:

1. **Fork latency under long queries.** Historical BLOCKHASH still reads canonical block data from
   live ChainBase. Removing the reader-lifetime mutation lease requires an archived block-hash view
   or another immutable canonical-hash oracle.
2. **Mid-chain commit latency.** Mid-chain read-through intentionally blocks archive commit. A
   production deployment intended for unrestricted historical RPC should synchronize from genesis
   or first eliminate live read-through.
3. **RocksDB cache/IO contention.** Query snapshots currently use cache-filling point reads. Random
   historical workloads may evict useful archive index/filter/data blocks; disabling fill-cache may
   instead amplify disk IO. Select a separate query cache or read policy only after A/B workload
   measurements.
4. **Synchronous publication tail latency.** The default publisher can perform durable unified
   publication on the block thread. Measure block-apply p50/p95/p99, fsync/compaction stalls, and
   backlog jumps before changing the default.
5. **Copy amplification.** Publication temporarily retains decoded journal objects, encoded rows,
   digest inputs, and RocksDB batch copies. JFR/native-memory profiling is still required at maximum
   block cardinality and configured journal limits.
6. **Corrupt iterator keys.** The common RocksDB Java API used by supported builds materializes
   `iterator.key()` before Java can check its length. A deliberately forged enormous SST key is an
   external-disk-corruption boundary; closing it portably requires a bounded native key API or a
   different fixed-key journal index.
7. **Global malicious rewrite.** Local envelopes, anchors, markers, index coverage, and full scrub
   detect reviewed partial corruption. An attacker able to coherently rewrite all related rows and
   metadata still requires an external authenticated commitment to detect.
8. **Deep-chain proof cost.** Point reads validate the selected row and one physical predecessor in
   constant work. Full transitive continuity remains a startup full-scrub responsibility.

## 8. Verification

Passed after the final fixes:

```text
./gradlew :chainbase:test -x generateGitProperties \
  --tests 'org.tron.core.archive.*'

./gradlew :actuator:test -x generateGitProperties \
  --tests 'org.tron.core.vm.archive.*'

./gradlew :framework:test -x generateGitProperties \
  --tests 'org.tron.core.archive.*' \
  --tests 'org.tron.core.db.ManagerArchiveLifecycleTest' \
  --tests 'org.tron.core.archive.ManagerArchiveSwitchForkTest' \
  --tests 'org.tron.core.db.ManagerMockTest' \
  --tests 'org.tron.core.services.jsonrpc.CallArgumentsTest' \
  --tests 'org.tron.core.services.jsonrpc.Historical*' \
  --tests 'org.tron.core.services.jsonrpc.TronJsonRpcArchive*' \
  --tests 'org.tron.core.vm.archive.*'

./gradlew :framework:checkstyleMain :framework:checkstyleTest \
  -x generateGitProperties

git diff --check
```

Round 11 found no remaining directly reproducible archive correctness defect after these fixes. The
items in section 7 remain explicit benchmark, architecture, or hostile-disk boundaries; they are
not evidence that production latency or capacity has already been proven.
