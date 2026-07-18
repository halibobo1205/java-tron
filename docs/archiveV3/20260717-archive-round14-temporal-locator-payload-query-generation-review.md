# Archive round-14 temporal locator, query generation, and persistence review

- Date: 2026-07-17
- Branch: `feat/archive-node`
- Review base: `7e0fb04aa3`
- Layout: `UNIFIED_V1` only, schema 5
- Status: IMPLEMENTED; FOCUSED, AGGREGATE, AND POST-FIX ADVERSARIAL GATES PASSED

## 1. Objective

Round 14 continued the adversarial workflow across four coupled boundaries:

1. large temporal values and native/Java allocation before query limits;
2. query snapshots, canonical fork generation, response serialization, and publisher locks;
3. in-flight journal startup evidence, schema identity, resource bounds, and repair markers;
4. maintenance/unwind memory, marker consistency, RocksDB cache/resource configuration, and
   cross-version assumptions.

The review used independent concurrency, persistence, functional-oracle, and coordinator lenses.
Every confirmed finding below has a focused regression. This document does not treat unit-test
success as proof of device, kernel, or production workload behavior.

## 2. Required invariants

1. A public historical response is valid only if its unified snapshot still belongs to the
   canonical mutation epoch when the serialized response is accepted.
2. External canonical-store callbacks must not hold fork exclusion or publication locks.
3. Query limits must be enforced before allocating or copying an attacker-controlled native value
   whenever RocksJava exposes a bounded read primitive.
4. Temporal locator, payload, latest, history, changeset, anchor, marker, index, and journal deletes
   remain one atomic RocksDB publication or maintenance batch where required.
5. A journal bundle is exposed to no startup callback until proof, codec, state, key/value
   alignment, schema identity, and aggregate resource bounds all pass.
6. Persistent journal corruption writes repair-required; configured capacity exhaustion does not.
7. Direct maintenance and unwind have finite retained-byte and mutation bounds, checked before
   batch copies.
8. Complete-history semantics require a floor of block 0, a physically first range at block 0,
   and that range beginning at txNum 0 in the same snapshot.
9. A generic temporal unwind cannot leave a committed block marker describing removed rows.
10. Archive-off and canonical consensus writes remain behaviorally unchanged.

## 3. Confirmed findings and disposition

| ID | Severity | Finding | Disposition |
|---|---|---|---|
| R14-1 | High | Variable-size temporal values could be materialized by native Gets before Java query budgets, and logical rows mixed integrity metadata with the payload. | Schema 5 stores fixed 45-byte locators in logical CFs and payloads in `TEMPORAL_PAYLOAD`; payload size is budgeted before the second native Get. |
| R14-2 | High | Reader-close epoch validation ended before JSON serialization, allowing a fork between close and response commit. | The response validator now travels with the query lease through `ArchiveQueryTransportScope`; single and batch servlet paths discard serialized orphan results. |
| R14-3 | Medium | Unified reader opening held the mutation shared lease across an external canonical resolver. | Opening is optimistic: capture epoch, release shared mutation protection, resolve/open the snapshot, then perform deadline-aware epoch seals before exposure and after serialization. |
| R14-4 | Medium | The final epoch seal could wait uninterruptibly behind a fork past the query deadline. | `ArchiveMutationBarrier` supports timed interruptible epoch validation; timeout/interruption fail closed and release lifecycle/query accounting. |
| R14-5 | Medium | Async publication held the service consistency writer across atomic RocksDB IO. | Unified publication serializes with a dedicated fair publication lock but releases the broad consistency lock during the atomic native batch; mutation generation still excludes fork/unwind. |
| R14-6 | High | A block could be journaled durably even when its worst-case unified publication could never fit the configured builder. | Exact journal size plus the shared temporal preparation estimate, index allowance, and mutation cardinality are admitted before the durable journal write and rechecked at startup. |
| R14-7 | High | A self-consistent journal encoded under another archive schema could pass startup validation. | Proof token and decoded range checksums are compared with the DB schema before any consumer callback. |
| R14-8 | Medium | Invalid journal codec/state/lifecycle values were not uniformly typed as corruption, so factory startup could miss repair-required. | Structural/semantic failures are wrapped as `ArchiveJournalCorruptionException`; configured block/record/byte limits use a separate non-corruption limit type. |
| R14-9 | Medium | Unwind retained one copied predecessor payload per unique key outside the bounded maintenance batch. | First-key restore rows are validated and copied directly into the same bounded batch; only txNum chain heads remain in the scan map. |
| R14-10 | High | Temporal-only unwind could change committed state without atomically updating the txNum index, published markers, and manifest. | The production unified temporal store rejects every unwind that touches committed data, including `unwindBlock`; committed repair/rebuild requires one future cross-CF backend transaction. The in-memory test store retains local unwind only as an oracle utility. |
| R14-11 | Medium | Public complete-history gating trusted only the mutable first-block marker. | Normal startup and every unified public reader bind floor, physical first range, first txNum, and schema in one snapshot; inconsistency is `CORRUPT_INDEX`, not `MISSING`. |
| R14-12 | Medium | Reusing `iterator.value()` avoided a point Get but removed the fixed-size materialization bound for corrupt locator rows. | Scrub, validation, unwind, and commit-marker scans use an exact 45-byte point read from the same snapshot. The extra key read is intentional; oversized locator values fail before Java materialization. |
| R14-13 | Low | Temporal encoding copied a large `DomainValue` twice before checking the format ceiling; direct maintenance batches were unbounded. | The encoded size is checked before exposing/copying bytes, encoding performs one copy, and maintenance defaults to 256 MiB/4,000,000 mutations. |
| R14-14 | Low | Empty VM traces did not account for the fixed JSON response envelope. | VM and non-VM trace paths reserve 64 response bytes before return data and struct-log accounting. |
| R14-15 | Test correctness | Several from-genesis RPC fixtures started at block 1 and therefore exercised the coverage gate instead of the intended race/error path. | Fixtures now start at block 0 and validate the originally intended behavior. |
| R14-16 | Low | A deferred final epoch/deadline failure discarded the response but was not recorded in `QueryContext` before lease metrics settled. | Transport settlement records the primary failure before closing the query lease; metrics now settle as failed. |
| R14-17 | High | A fatal archive transition after a reader result but before transport completion could pass an epoch-only final seal. | Response validation now checks service availability both before and after the deadline-aware epoch seal; fatal state always rejects the buffered response and releases all leases. |
| R14-18 | High | Proof-valid journal rows with an invalid cross-block sequence or predecessor chain could fail startup without a durable repair marker. | Durable semantic journal failures now use the shared persistent-corruption base type, and the factory marks repair-required before failing startup. Capacity/resource failures remain non-corruption. |
| R14-19 | Medium | A query waiting behind an exclusive mutation could consume a scarce snapshot permit before it could open a snapshot. | Reader opening now waits for mutation admission first and acquires the snapshot permit only when snapshot construction can proceed. |
| R14-20 | High | Normal startup validated only the range tail, so deletion of a middle block range could remain latent until a query crossed the gap. | Every startup scans block ranges in one snapshot and enforces contiguous block and txNum progression. Full startup validation additionally scans transaction positions. |
| R14-21 | Medium | State RPC resolution fetched and hashed full canonical blocks even though the unified published range already authenticates the block hash. | Block-end readers resolve the expected hash from the archive range snapshot; balance/code/storage queries no longer read or populate the canonical `Wallet` block cache. |
| R14-22 | Medium | Query budgets accounted estimates but did not reconcile the final JSON wire size, allowing underestimation to escape the configured response ceiling. | The servlet records actual serialized bytes before terminal settlement. Accounting reconciles with the prior estimate instead of double charging and rejects oversized single or batch responses. |
| R14-23 | Medium | A deferred batch failure could lose already completed elements, emit `id:null`, or trigger Java self-suppression when body and close threw the same terminal exception instance. | Each element is settled explicitly, preserves its request ID, retains prior responses, and deduplicates identical terminal exceptions before adding suppressed cleanup failures. |
| R14-24 | Low | Journal encoding used growable streams and a final `toByteArray`, multiplying peak allocation for a block already admitted by retained-byte limits. | The codec computes the exact length and writes once into a fixed `ByteBuffer`; domain values copy directly into the destination. |
| R14-25 | Medium | Temporal publication could construct many row/value arrays before the unified builder discovered its mutation or retained-byte ceiling. | A conservative preparation preflight reserves worst-case mutations and bytes before row encoding. Admission failure leaves the database untouched. |
| R14-26 | High | A checked `IOException` from a batch RPC handler bypassed manual transport settlement, leaking the thread-local scope and every deferred query lease. | Batch settlement now handles checked IO, runtime, and error exits, always closes the transport, and preserves primary/suppressed exception identity without self-suppression. |
| R14-27 | Medium | Batch deadline validation occurred after copying a successful element, so failure could append an error after retained success bytes and produce duplicate IDs or malformed JSON. | The deadline check immediately before append is the element commit linearization point. Failure leaves no success bytes and emits exactly one error with the original ID. |
| R14-28 | Medium | A deadline observed at the next batch element discarded every already completed per-ID response; the non-object path had the same destructive post-append check. | Deadline termination preserves committed elements, appends one bounded error for the current ID, and stops. Non-object elements now use the same pre-append commit check. |
| R14-29 | High | Journal admission estimated temporal values at 2x while actual preparation reserved 8x, so a durable journal could be guaranteed to fail every publication and restart. | Backend admission and temporal staging now consume one shared `PublicationEstimate`; retained bytes and mutations are rejected before any journal/proof/ACK row is written. |
| R14-30 | Medium | Startup bounded the payload before reading its fixed proof, so a one-byte payload enlargement looked like a configuration limit and skipped repair-required. | Startup reads and decodes the fixed proof first. A proof-declared length above configuration is a resource limit; an actual payload length/digest mismatch is persistent corruption. |
| R14-31 | Medium | Middle-range corruption found in the index constructor could not write repair-required because the Factory had not yet received the index instance. | Structural startup failures are typed as persistent corruption while RocksDB IO remains ordinary; the Factory can write repair metadata through the opened DB before index construction completes. |
| R14-32 | Medium | Direct `unwind(0)` could scan an empty-marker snapshot, race the first publication, then apply a stale temporal deletion batch after the commit. | Both production Unified unwind entry points now fail immediately for every state. The obsolete temporal-only implementation was removed. |
| R14-33 | Low | Canonical ACK validated only the proof row, so a missing or modified payload could still receive an ACK and remain latent until publish/restart. | ACK validates proof, exact payload length/digest, and token under the DB mutation lock before writing ACK. |
| R14-34 | High | A corrupt proof could claim a payload near the 256 MiB production ceiling and make recovery allocate that claimed size before discovering that the actual journal was tiny. The resulting OOM could precede corruption classification and the repair marker. | Recovery uses a fixed bounded probe before exact payload allocation. Actual length mismatch is persistent corruption and the Factory persists repair-required without materializing the proof-claimed size. |
| R14-35 | High | Canonical ACK read and retained the full journal once outside the DB lock and then read it again under the mutation lock. A maximum-sized block could retain roughly two payload copies and double the synchronous read cost on block commit. | The store retains only proofs established by durable write or a complete bounded startup scan. ACK rechecks the compact proof under the mutation lock, performs one exact-sized payload Get, validates its key-bound digest, and writes the compact marker. Failed scans never publish a partial proof set. |
| R14-36 | Medium | The new validated-proof cache survived successful atomic publication even though the DB batch deleted the corresponding journal rows, causing one proof object to leak per historical block. | The in-flight store has an explicit post-publication metadata hook. The service invokes it only after successful unified publication; it removes the proof under the journal lock. A 128-block regression proves the cache follows live journal rows rather than chain height. |

### Confirmed costs and architectural residuals

1. A coherent deletion of every temporal evidence row for one logical key cannot be distinguished
   from a key that never existed by local point-read integrity. Default rendering can therefore
   return zero/empty for that corruption. A block/state commitment or independent key-membership
   index is required to close it; full scrub can detect the deletion only while independent
   changeset/marker evidence remains.
2. Archive-on execution has real synchronous cost. Captured writes read previous values, rare
   account-asset representation changes scan physical TRC10 rows, and the in-flight journal uses a
   forced-sync write before canonical commit. These are correctness/durability costs, not zero-cost
   sidecar work; fsync P99, large-asset-account, and sustained-sync benchmarks remain release gates.
3. Complete range continuity is checked on every startup and is therefore `O(blocks)` in archive
   history. Authenticated range checkpoints could reduce restart time, but a tail-only scan would
   reintroduce the middle-deletion blind spot fixed in this round.
4. `JsonRpcArchiveStatePointResolver`, archive read-through bridges, generic `ReadGuard`, and the
   currently unbound historical trace surface remain removal candidates. They are not used by the
   production state-query path; deletion should be a separate compatibility-focused change.

## 4. Temporal schema 5

Each logical temporal row now contains only:

```text
version(1) || linkedTxNum(8) || payloadBytes(4) || SHA-256(32)
```

The corresponding payload key binds the logical table and exact logical key. Locator integrity
binds table, row key, predecessor link, payload length, and payload bytes. Publication and unwind
write/delete both halves in one atomic batch.

Query flow is:

1. read the exact 45-byte locator;
2. validate version, link, and payload size;
3. reserve per-value and cumulative query budgets;
4. issue a bounded payload Get from the same snapshot;
5. validate length and digest before decoding the domain value.

This removes the known large point-Get materialization path. It does not prevent an iterator seek
from reading or decompressing a large SST data block internally, nor does it isolate OS page cache
or device bandwidth.

## 5. Canonical generation and transport seal

The unified query lifecycle is now:

1. acquire query and lifecycle admission;
2. wait for mutation admission before consuming a snapshot permit;
3. briefly capture the mutation epoch and release mutation sharing before external canonical
   resolution;
4. acquire the snapshot permit, open one unified snapshot, and resolve floor/range/state inside it;
5. seal availability and epoch with the remaining request deadline;
6. compute and serialize the response;
7. reconcile the actual wire bytes and run the deferred availability/epoch validator;
8. settle every transport exit, including checked IO, then validate the batch deadline at the
   element commit linearization point;
9. append the element and release query, snapshot, and lifecycle leases before network write.

Single-request and batch regressions force a generation change after reader close but before
transport completion. Both discard the stale serialized bytes. A blocked canonical resolver no
longer blocks an exclusive fork, and a blocked exclusive fork cannot retain query lifecycle state
past the query deadline.

## 6. Journal and startup fail-stop

The startup scanner now performs a complete pre-callback pass and retains no unbounded validated
prefix. It reads the fixed proof before the variable payload, rejects proof-declared capacity above
configuration as a limit, and probes the actual payload length before allocating the authenticated
size. The durable proof authenticates the immutable journal key and exact payload. The decoded
proof token and block range must both match the DB schema checksum.

After a complete successful scan, the store retains only the compact validated proof for each
bounded in-flight block. Newly written journals install the same proof only after the durable write
succeeds. Canonical ACK uses that proof as an allocation capability, rechecks the durable proof row,
reads the payload once under the DB mutation lock, validates its key-bound digest, and writes the
WAL-only acknowledgement. The full payload is neither cached nor held twice. A failed complete
scan clears every cached capability, and successful atomic publication evicts the corresponding
proof immediately.

Failure classes are intentionally separate:

- malformed keys, codec versions, lifecycle proof, journal state, key/value alignment, digest,
  acknowledgement, or schema identity: persistent corruption and repair-required;
- configured aggregate block, record, retained-byte, or encoded-byte ceiling: resource/configuration
  rejection without a repair marker;
- RocksDB IO failure: IO/fatal evidence, not automatically mislabeled as byte corruption.

## 7. Unwind and maintenance

Unwind builds one bounded atomic batch. For the first removed version of each logical key it:

1. validates the physical predecessor and anchor;
2. stages restored latest/baseline rows immediately;
3. copies the payload only into the bounded batch;
4. retains only the last removed txNum for subsequent chain-gap validation.

A small-budget regression fails on the second key and proves the database remains unchanged. The
direct-unwind marker probe and changeset scan use the same RocksDB snapshot. Production
`UNIFIED_V1` rejects every temporal-only unwind unconditionally, including empty-marker,
block-zero, and `unwindBlock` calls. No partial rollback is exposed until the backend can update
temporal rows, the txNum index, publication markers, and manifest in one atomic cross-CF
transaction.

## 8. RocksDB configuration and compatibility

- DB write buffer: 128 MiB.
- WAL size bound: 256 MiB.
- Open files: 512.
- Background jobs: 2.
- Bloom filters are enabled for every archive CF, including temporal payloads.
- Query and scan views remain non-cache-filling.
- SST table format is fixed to format 0 with CRC32c for the supported archive runtime.

The SST setting is not a RocksDB-version downgrade guarantee. A local real-runtime probe produced:

1. RocksDB 9.7.4 ARM write, RocksDB 5.15.10 x86/JDK8 read: failed on an unknown MANIFEST tag;
2. RocksDB 5.15.10 x86 write, RocksDB 9.7.4 ARM read: succeeded;
3. RocksDB 9.7.4 ARM write, RocksDB 9.7.4 x86/JDK8 read: succeeded.

Therefore compatibility is one-directional across the tested old/new pair. The production factory
currently rejects unsupported non-ARM archive operation. Changing the repository-wide RocksDB
dependency would also affect canonical databases and is outside this archive-only change. See the
official RocksDB compatibility notes:
`https://github.com/facebook/rocksdb/wiki/RocksDB-Compatibility-Between-Different-Releases`.

## 9. Verification matrix

Focused tests passed for:

- timed epoch seal and lifecycle drain;
- deferred single/batch response validation;
- external canonical resolver versus exclusive fork;
- pre-journal publication admission and startup replay admission;
- journal schema mismatch before consumer callback;
- journal codec/lifecycle corruption and durable repair marker;
- bounded unwind atomic failure and committed-marker rejection;
- runtime coverage-floor corruption mapping to `CORRUPT_INDEX`;
- exact-size locator point reads, including oversized-corruption rejection before materialization;
- empty VM trace response budget;
- historical block-hash race fixtures;
- final fatal-state response rejection and lease release;
- semantic journal corruption repair marking;
- snapshot-permit admission ordering;
- normal-startup middle-range gap rejection;
- state RPC resolution without canonical `Wallet` reads;
- actual serialized response-byte enforcement;
- batch ID/result preservation and terminal-exception deduplication;
- exact-size journal encoding and temporal preparation preflight;
- checked batch-IO lease cleanup and same-thread scope reuse;
- pre-append batch deadline failure with one valid response for the original ID;
- preservation of completed batch elements when the next element reaches its deadline;
- shared pre-journal publication bytes/mutations admission;
- proof-first journal limit/corruption classification and Factory repair persistence;
- constructor-time middle-range repair marking;
- unconditional Unified unwind rejection for marker-free and committed states;
- payload-bound canonical acknowledgement and mutation-lock revalidation;
- inflated proof-length recovery rejection before claimed-size allocation;
- one-full-payload canonical ACK after durable-write or startup proof validation;
- fail-closed proof-cache invalidation after scan failure;
- bounded proof-cache lifetime across 128 sequential published blocks.

Final aggregate commands are:

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
  --tests 'org.tron.core.services.jsonrpc.JsonRpcServletTest' \
  --tests 'org.tron.core.services.jsonrpc.TronJsonRpcArchive*' \
  --tests 'org.tron.core.vm.archive.*'

./gradlew :common:test -x generateGitProperties \
  --tests 'org.tron.core.config.args.StorageConfigTest'

./gradlew checkstyleMain checkstyleTest -x generateGitProperties

git diff --check
```

Final rerun on 2026-07-18:

- chainbase full archive suite: passed;
- framework archive, manager lifecycle, JSON-RPC, historical call/trace, and VM suite: passed;
- actuator historical VM suite: passed;
- common archive configuration suite: passed;
- main/test checkstyle: passed;
- `git diff --check`: clean.

The first independent post-fix persistence review found R14-36. After its fix, a separate
concurrency/performance review found no reproducible defect in ACK read count, trusted proof
lifetime, scan-failure invalidation, lock ordering, query cache isolation, or publication eviction.

## 10. Residual risks and next hard gates

1. If every anchor/latest/history/changeset/locator/payload row for one key is deleted as one
   coherent set, local per-row integrity has no remaining membership evidence and a complete-history
   read can report missing. Detecting that class requires an independent key-membership commitment
   or block/state root, not another local row check.
2. Every startup validates the complete block-range chain. With `fullScrubOnStartup=false`, deep
   temporal rows and transaction positions are still validated by full scrub or when affected data
   is read.
3. A single admitted temporal or journal payload may still approach 256 MiB. ACK now materializes
   one full journal value rather than two, but low-heap JVM/JNI behavior still needs maximum-value
   RSS/JFR testing.
4. Full scrub remains O(database size), and reverse payload-owner validation performs physical IO.
5. RocksDB snapshots, checksums, and atomic batches do not isolate page cache, filesystem, device
   queue, compaction, or power-loss behavior. The real crash/restart and disk-fault matrix remains a
   release gate.
6. Old RocksDB readers may reject a newer MANIFEST even when SST table format is stable. Cross-CPU
   deployment must use the supported archive RocksDB generation or rebuild the development DB.
7. Native read deadlines are best-effort on runtimes that expose them; older RocksJava cannot
   interrupt every in-progress native read.
8. Maintenance reads and the final atomic write assume the service-level exclusive mutation
   protocol. Future online repair APIs must acquire that protocol rather than call the backend
   directly.
9. `ArchiveReadThrough`, `ChainBaseArchiveReadThrough`, and the generic `ReadGuard` surface remain
   candidates for removal now that production public reads require from-genesis unified snapshots.
   They are not used to relax production correctness in this round.
10. Production unified committed rollback is deliberately fail-stop. An online rollback or repair
    feature must be designed as one atomic backend operation across every committed archive CF;
    re-enabling temporal-only unwind would recreate the consistency defect closed in this round.
11. Archive-on block execution pays synchronous previous-value reads and journal fsync; rare
    account-asset representation changes can also scan all physical assets for the account. The
    design is durability-first, but production latency claims require the workload gates above.

No confirmed defect from sections 3 through 7 remains after the focused regressions. Production
readiness still depends on completing the physical fault, maximum-resource, and long-running
workload gates above.
