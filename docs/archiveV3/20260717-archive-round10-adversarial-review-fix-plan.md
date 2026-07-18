# Archive round-10 adversarial review and fix plan

- Date: 2026-07-17
- Branch: `feat/archive-node`
- Review base: `7e0fb04aa3`
- Layout: `UNIFIED_V1` only
- Status: IMPLEMENTED, FOCUSED/AGGREGATE REGRESSION AND CHECKSTYLE PASSED

## 1. Objective

Re-audit archive startup, publication, historical reads, query resource ownership, corruption
handling, and block-path memory admission after round 9. Four independent adversarial reviewers
covered lifecycle/concurrency, unified persistence, historical VM/RPC, and capture/performance. Every
reported chain was then rechecked against the current source before implementation.

## 2. Required invariants

1. A historical query must never return a value from a malformed or unpaired temporal row.
2. A native snapshot, iterator, query lease, or journal buffer must be released on every
   `RuntimeException` and `Error` path.
3. An archive failure after canonical commit must arm fail-stop, persist repair evidence, and reach
   the process-exit boundary.
4. Configured capture and journal limits must reject work before the corresponding large object
   graph is materialized.
5. RPC limits must run before expensive decoding or archive query admission.
6. Archive-off and canonical consensus behavior must remain unchanged.

## 3. Confirmed findings

| ID | Severity | Finding | Consequence |
|---|---|---|---|
| R10-1 | Medium | HISTORY lookup accepted a prefix match without validating the exact key or matching CHANGESET; latest fallback was not checked against its last change/baseline. | RocksDB-valid logical corruption could silently return a wrong historical value when full startup scrub was disabled. |
| R10-2 | Medium | Publication and Manager fail-stop boundaries caught `RuntimeException` but not `Error`. | An `AssertionError`, JNI linkage error, or similar post-commit failure could bypass repair marking and terminate only the worker thread. |
| R10-3 | Medium | Historical `eth_call` validated both hex fields by fully decoding them before applying the historical call-data limit. | Concurrent oversized requests caused avoidable CPU and allocation amplification before archive admission. |
| R10-4 | Medium | Capture charged `256 + key + prev + value`, while the retained in-flight model charged `640 + 2*key + prev + value`. | A high-cardinality block could allocate substantially beyond the configured hard byte watermark before final admission rejected it. |
| R10-5 | Medium | Unified journal startup scanning materialized the complete RocksDB value before applying service backlog limits. | An oversized but structurally valid journal could repeatedly OOM or brick startup instead of producing a bounded repair-required failure. |
| R10-6 | Low | Direct temporal `openReadView()` did not close its unified snapshot if HISTORY iterator construction failed. | The generic adapter path could pin a native snapshot and prevent clean database shutdown. The production unified read-session wrapper already had an outer cleanup guard. |
| R10-7 | Low | Transport-scope `ArrayList.add()` failure left the already-closed reader's `QueryLease` unowned. | An allocator `Error` could leak query accounting and keep shutdown drain waiting. |
| R10-8 | Low | Maintenance unwind retained one HISTORY iterator per restored key until the shared view closed. | Native iterator peak was O(unique keys) for a high-cardinality maintenance unwind. |

## 4. Implementation

### Temporal query integrity

- Validate the exact HISTORY key shape and txNum before accepting a prefix match.
- Require every selected HISTORY row to have a valid matching CHANGESET.
- On latest fallback, verify latest equals the last CHANGESET value or, when history is absent, its
  persisted baseline.
- Raise the conservative backend-read charge from 2 to 4 operations.
- Close per-key maintenance probe iterators immediately.

### Failure and resource boundaries

- Extend archive mutation/publication catches through `RuntimeException | Error`.
- Normalize an `Error` into the runtime failure stored by lifecycle/fatal-controller state while
  rethrowing the original `Error` to the Manager boundary.
- Make Manager convert post-canonical archive `Error` values to `TronError(ARCHIVE_RUNTIME)`.
- Close a query lease immediately when transport deferral fails and preserve cleanup failure as
  suppressed evidence.
- Close the unified read view if snapshot adapter construction fails, preserving the original
  throwable.

### Pre-allocation admission

- Share the in-flight retained-record estimator with capture admission so the stricter model runs
  before key/value normalization and record construction.
- Probe a RocksDB journal value's size with the bounded-buffer iterator API before allocating its
  byte array; production and identity inspection use `hardInFlightBytes` as the encoded limit.
- Check both `input` and legacy `data` lengths before historical hex validation.
- Replace allocation-based hex validation with a zero-allocation character scan; actual execution
  decodes the selected value once.

## 5. Added fault coverage

- malformed-prefix and well-formed orphan HISTORY rows;
- latest/last-CHANGESET disagreement;
- snapshot iterator-construction failure plus snapshot-close failure suppression;
- encoded journal just over its configured pre-decode limit;
- capture limit between the old and retained-object estimates;
- transport deferral throwing a synthetic `AssertionError`;
- temporal publication throwing `AssertionError`, including index cleanup, repair marker, and
  subsequent availability rejection;
- Manager acknowledgement throwing `AssertionError` after canonical commit;
- oversized malformed loser field in historical `eth_call`, proving size rejection precedes hex
  validation and executor entry.

## 6. Rejected or downgraded candidates

- No lock-order cycle was found across lifecycle, mutation barrier, consistency lock, unified DB,
  query drain, or publisher shutdown.
- Unified publication remains one cross-column-family RocksDB `WriteBatch`; no normal crash sequence
  exposing a torn block survived review.
- Published solidified state cannot be changed by ordinary fork switching; canonical-hash rechecks
  and mutation epochs close the reviewed stale-publication paths.
- Historical VM dynamic properties implement the narrow interface directly; no inherited latest
  property fallback remains.
- Direct unified read-session construction already closed a failed temporal wrapper. R10-6 was kept
  as a generic adapter-contract fix, not presented as an active production leak.

## 7. Benchmark-gated residuals

These are not proven correctness defects and were deliberately not changed without measurements:

1. Default synchronous publication performs a durable unified write on the block thread and can
   expose filesystem/compaction latency during a solidified backlog jump.
2. Optimized TRC10 account deletion scans the physical asset prefix in both archive capture and
   canonical deletion paths.
3. Decoded protobuf memo accounting is a conservative encoded-size formula, not an instrumented
   deep-heap measurement; entry count remains independently bounded.
4. Publication temporarily holds encoded journal and batch mutation copies in addition to retained
   in-flight objects; target-heap headroom still needs JFR validation.
5. Query-time row checks reject single-row and latest corruption. Coordinated valid HISTORY and
   CHANGESET tampering remains a full-scrub/operational-integrity concern, not a normal write path.

## 8. Verification

Passed:

```text
:chainbase:test
  ArchiveCaptureEngineTest
  ArchiveQueryCoordinatorTest
  UnifiedArchiveTemporalStoreOracleTest
  UnifiedArchiveBackendTest
  DefaultArchiveServiceTest

:framework:test
  ManagerArchiveLifecycleTest
  TronJsonRpcArchiveRoutingTest
  CallArgumentsTest

:framework:checkstyleMain
:framework:checkstyleTest
git diff --check
```

```text
:chainbase:test :actuator:test :framework:test
  org.tron.core.archive.*
  org.tron.core.vm.archive.*
  StructLogReconstructorTest
  ArchiveJsonRpcStateAdapterTest
```

All round-10 focused and aggregate gates completed successfully.

## 9. Next adversarial pass

Round 11 treats interference as the primary threat model:

1. historical query CPU, heap, native memory, cache, lock, and serialization pressure against block
   execution;
2. execution/publication/reconcile activity against snapshot consistency and cross-request caches;
3. toxic-cache attempts using malformed state, failed decodes, deadlines, and partial responses;
4. benchmark-backed review of synchronous publication, copy amplification, duplicate scans, and
   implementation redundancy.
