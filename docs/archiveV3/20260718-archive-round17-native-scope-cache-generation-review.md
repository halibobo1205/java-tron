# Archive round-17 native ownership, scope, and failure-classification review

- Date: 2026-07-18
- Branch: `feat/archive-node`
- Review base: current uncommitted Round 10-16 worktree
- Layout: `UNIFIED_V1` only, schema 5, from-genesis public reads
- Status: SOURCE/TEST FIXES COMPLETE; RUNTIME RELEASE GATES REMAIN

## 1. Objective

Round 17 attacks the boundaries that can turn a correct immutable snapshot into a poisoned process
or an execution/query interference channel:

1. prove every native snapshot, iterator, `ReadOptions`, Java memo, and thread-local scope has one
   owner and one terminal close state;
2. distinguish persistent structure/corruption failures from transient online I/O and query
   deadline failures without allowing one class to mask another;
3. prove composite txNum/index reads and historical VM state use one snapshot generation;
4. compare historical repository overlay behavior with the live repository for account existence,
   contract creation/deletion, storage, code, and token balances;
5. attack historical precompile worker pools, VM catch-all behavior, and deadline propagation for
   ways a query can affect latest calls, canonical execution, or process memory.

## 2. Required invariants

1. One transient RocksDB `IOError` must fail one request and remain observable, but must not write
   `repair-required`. A native `Corruption` status or a decoded cross-row invariant failure must
   still fail-stop and persist repair state.
2. An expired Java query deadline may replace a native RocksDB failure only when the native status
   itself is `TimedOut`; it must never hide `IOError` or `Corruption`.
3. Native owners are terminal before native close begins. A close failure is sticky, repeated close
   reports the same first failure, and later operations cannot touch abandoned native ownership.
4. Every cleanup attempts all remaining releases even if an earlier close or `addSuppressed` fails.
5. Shared lock acquisition must roll back if lease construction fails after the lock is acquired.
6. Every composite selector observes one bound read view; internal thread-local binding restores
   the exact previous value on every `Throwable` path.
7. Historical VM overlays copy mutable capsules/byte arrays and preserve live repository account
   gates, create/new-contract state, child commit, and SELFDESTRUCT deletion semantics.
8. Archive-only VM hard failures and query limits cannot be converted to a successful zero result.
   Archive-off and canonical VM catch behavior must remain byte/behavior compatible.
9. Historical Sapling proof tasks cannot consume the latest constant-call or canonical pools, wait
   beyond the minimum VM/query deadline, or leave an unbounded canceled-task queue.

## 3. Adversarial workflow

1. Inject native marker, point-read, iterator-status, snapshot-acquisition, and close failures at
   exact post-open/pre-close boundaries.
2. Force deadline expiry adjacent to native `TimedOut`, `IOError`, and `Corruption` statuses and
   assert both the public reason and retained original cause.
3. Throw `Error` from a real historical SLOAD repository read so the test crosses `VM.play` and
   `VMActuator`, rather than mocking the actuator boundary.
4. Fill or stall historical proof workers, expire request deadlines, cancel futures, and inspect
   which executor and queue retain each task.
5. Exercise root/child repository account, contract, code, storage, token, creation, commit, and
   SELFDESTRUCT state while mutating caller-owned capsule wrappers after writes.
6. Run two independent post-fix skeptic reviews, then the complete archive and checkstyle matrix.

## 4. Confirmed findings and fixes

| ID | Severity | Confirmed finding | Fix and direct evidence |
|---|---|---|---|
| R17-01 | Low | Round 16 inferred that target-marker RocksDB failures shared the opening classifier, but had no direct marker-handle injection. | Added a `BLOCK_MARKER` handle failure test. It returns `INTERNAL_IO`, writes no repair marker, and leaves the service available. |
| R17-02 | Medium | Several production txNum composite helpers opened or bound read views independently, leaving room for mixed snapshot generations and fragile manual thread-local cleanup. | Composite selectors, head-range lookup, store delegation, and publication staging now use one owner-bound scope/read view. Snapshot-count regressions cover every unbound selector. |
| R17-03 | Medium | Nested historical VM/config/query scopes could overwrite an outer local view or leak a partially installed thread-local if installation itself failed. | Added nested snapshot preservation and allocation-safe install rollback to VMConfig, lifecycle, execution, request, transport, and query-context scopes. Owner/LIFO tests cover null, nested, failure, and restoration paths. |
| R17-04 | Medium | A null RocksDB snapshot, partial read-option configuration, or failed native open/close could leave ambiguous ownership/accounting. | Null snapshot acquisition fails closed; partial setup directly releases configured resources; DB close uses `closeE`, sticky terminal failure, lifecycle exclusion, and active-view accounting. |
| R17-05 | Medium | Raw Rocks iterators escaped their read-view owner; iterator/read-view close failure could leave wrappers apparently usable and repeated close could silently succeed. | Added owner-thread `UnifiedArchiveIterator`, terminal-before-close state, sticky failure, and direct non-lambda cleanup of iterators, read options, snapshot, and active-view accounting. |
| R17-06 | Medium | Historical repository reads/writes did not fully match live account-gating and deletion semantics; mutable capsule wrappers could alias caller or reader state. | Root and overlay values are defensively copied. Account-gated storage/token reads, new-contract propagation, child commit, and SELFDESTRUCT account/code/contract/storage/token deletion are directly tested. |
| R17-07 | High | `ArchiveReaderException.isIntegrityFailure()` treated every `INTERNAL_IO` as persistent corruption. One transient RocksDB read after a reader opened therefore called `markFatal`, wrote `repair-required`, and poisoned a healthy archive. | `INTERNAL_IO` is request-local. Temporal and index structure failures map explicitly to `CORRUPT_VALUE`/`CORRUPT_INDEX`; native Rocks `Corruption` maps to integrity failure, while ordinary `IOError` remains local. Post-open I/O, native corruption, and structural tamper tests cover all three outcomes. |
| R17-08 | Medium | Iterator and point-read catches checked the Java deadline for every RocksDB exception. A just-expired request could hide a real `IOError` or `Corruption` as a timeout. | Only native `Status.Code.TimedOut` is eligible for deadline mapping. The original native timeout is retained as suppressed evidence; expired-deadline tests prove `IOError` remains the primary cause. |
| R17-09 | Low | Shared mutation leases acquired the read lock before allocating the lease object, without rollback if allocation failed. | Shared and interruptible acquisition now mirror exclusive acquisition with a success/finally unlock guard. |
| R17-10 | Medium | `VMActuator` caught every `Throwable`; an `AssertionError` from a real archive SLOAD became `Unknown Throwable`, while the previous test mocked past the catch-all. | Only when an archive root repository is injected, `Error` and typed historical query limits are rethrown. A real SLOAD reader `AssertionError` traverses VM execution and returns by identity; canonical behavior is unchanged. |
| R17-11 | Medium | `VerifyTransferProof` ignored a false latch wait and then called unbounded `Future.get()`. Historical and latest constant calls shared the same five workers, so an archive query could outlive its deadline and occupy latest-call workers. | Historical calls use a separate pool. Every historical latch/future wait uses the minimum VM and archive-query remaining time; unfinished tasks are canceled, and worker `Error`/query-limit causes are restored. Legacy latest/non-constant behavior remains on its original pools. |
| R17-12 | Medium | Isolating the historical pool alone still left `newFixedThreadPool`'s unbounded queue. If five native proof tasks ignored interruption, repeated timed-out queries could retain canceled queued tasks without bound. | The historical pool now has a 64-task bounded queue. Cleanup removes canceled `RunnableFuture` instances from the queue, and capacity rejection becomes a typed query resource failure instead of a false proof result. |
| R17-13 | Medium | Repeated cleanup used method references/lambdas and unguarded suppression, so an allocation failure while building cleanup actions, response settlement, or suppressed failures could skip later native releases and query leases. | Managed reader, transport scope, factory, and service close paths invoke owned resources directly, settle the query lease even after validator/context failure, and make suppression best-effort. Idempotent public close behavior remains unchanged. |
| R17-14 | Medium | `DefaultArchiveStateReaderFactory` independently mapped every block-range/coverage `ArchiveException` to `CORRUPT_INDEX`, so a transient RocksDB I/O failure during compatibility/startup reads could persist a false repair requirement. | Storage classification is shared by reader, factory, and service: explicit/structural/native corruption remains integrity failure; ordinary RocksDB I/O remains request-local `INTERNAL_IO`. Both factory index entry points have direct regressions. |
| R17-15 | Medium | Historical proof-pool capacity rejection produced a typed query limit, but `VM.play` converted it into `ProgramResult.runtimeFailure` before `VMActuator` could restore it. | `VM.play` rethrows `HistoricalQueryLimitException` only for an archive repository. A direct VM operation test proves historical propagation by identity and unchanged non-historical catch behavior. |
| R17-16 | Medium | A timed `Future.get` did not recheck the query deadline, while an interrupted historical proof wait restored the interrupt and returned a normal zero proof result. | Historical timeout paths recheck the archive deadline when it supplied the wait bound. Historical interruption maps to typed `INTERRUPTED`, while legacy latest/canonical behavior is unchanged. |
| R17-17 | Medium | Remembering only `min(VM, archive)` lost both the limiting source and a comparable sampling instant. Scheduler delay could retroactively replace an earlier VM timeout with a later archive deadline. | `QueryContext` now exposes one immutable monotonic deadline sample. VM and archive remaining time are derived from that exact tick, the original limiter is retained for timeout settlement, and simultaneous expiry is resolved before recording either terminal failure. |
| R17-18 | Medium | Snapshot ownership counters called allocating metrics after reserve/increment and before owner release. Metric `OutOfMemoryError` could permanently leak a permit/query lease and make drain time out. | Snapshot ownership transitions complete outside metric control flow; reporting is injected best-effort and occurs after deferred owner release. An injected metric OOME proves permit, active snapshot, active lease, and drain all settle to zero. |
| R17-19 | Low | Unified reader-open cleanup used a raw `finally` close. If index corruption and native snapshot cleanup failed together, cleanup could replace `CORRUPT_INDEX` and bypass repair fail-stop. | Reader/session cleanup now uses primary-preserving suppression. A direct malformed-index plus Rocks snapshot-release failure retains `CORRUPT_INDEX`, preserves cleanup evidence, writes repair-required, and fails stop. |
| R17-20 | Low | Snapshot counts were captured under the coordinator lock but reported after unlock. Concurrent close reports could therefore publish an older nonzero gauge after the final zero. | Snapshot reporting now uses a monotonic latest-generation single-drainer. A blocked count-one reporter and concurrent count-zero close deterministically finish with gauge zero; follower reports never wait on a metric lock. |
| R17-21 | Medium | The first ordering fix used a second `ReentrantLock`. OOME while that lock allocated a contended waiter happened after snapshot ownership committed but before the permit returned, making the ownership unreachable. | The metric lock was removed. An allocation-free atomic work counter elects one reporter, later transitions only publish their latest version/count, and reporter failure cannot affect permit return or release. |
| R17-22 | Medium | Permit/lease close became terminal before coordinator-lock entry, while a post-commit `unlock()` failure was indistinguishable from a pre-commit failure. Sequential or concurrent retry could either leak ownership or double-release into an underflow. | Close uses waitable in-progress state for concurrent coordination, coordinator commit markers are written before unlock, and unlock is retried without reopening committed ownership. Entry-failure, concurrent-close, and fail-after-release tests cover both lease and permit. |
| R17-23 | Low | Moving query-admission metrics outside the coordinator lock during R17-22 allowed a later release report to overwrite a newer acquire report. | Admission metrics remain ordered under the coordinator lock but run through a non-throwing observational wrapper. Query-finished and snapshot metrics remain outside ownership mutation. |
| R17-24 | Medium | Although low-level close became retryable, managed reader, transport scope, and reader-open cleanup are deliberately one-shot owners. A pre-commit coordinator-lock OOME could therefore leave their child permit/lease retryable but unreachable after the owner became terminal. | Release lock entry now falls back to allocation-free `tryLock` spin/yield, commits ownership, then rethrows the original OOME as evidence. Real managed-reader and transport-scope one-shot tests prove final snapshot/query counts are zero and later scope use remains available. |

## 5. Negative results

1. No query-controlled Java cache reaches canonical execution. Reader memo, block-hash cache,
   overlays, bound index view, and VMConfig snapshot are request-owned and released with the reader.
2. Query RocksDB views retain `fillCache=false`; scan/validation views also avoid admitting archive
   payloads into the execution-critical block cache.
3. Canonical execution does not perform query counters, deadline clock reads, or historical pool
   work. The archive VM `Error`/limit rethrow is gated by a non-null injected repository.
4. Publication, unwind, reorg, and transaction-location selectors use one unified snapshot/batch;
   no partial publication or mixed range/position generation was reproduced.
5. Archive repository SELFDESTRUCT now removes every state family visible to the historical VM and
   propagates the same tombstones through child commit. No latest/live fallback remains.
6. Native proof work may remain non-interruptible inside the Rust library. The bounded isolated
   pool converts that from cross-path/unbounded-memory interference into bounded archive-query
   degradation; it does not make native cancellation cooperative.

## 6. Verification evidence

Focused gates completed before and during final aggregate execution:

- direct target-marker I/O, post-open RocksDB I/O, native corruption, and structure-tamper tests;
- native timeout-versus-I/O precedence tests;
- snapshot/read-view/iterator sticky-close and active-view accounting tests;
- txNum single-snapshot, ThreadLocal owner/nesting, lifecycle, and mutation barrier tests;
- historical repository alias, account gate, create/commit, and SELFDESTRUCT tests;
- real VM SLOAD hard-error propagation and VMConfig restoration tests;
- Sapling historical timed-wait, same-tick deadline precedence, interruption, cancellation, and
  queue-rejection tests;
- transient factory I/O classification, snapshot-metric OOME ownership, and corruption-plus-close
  double-failure tests;
- blocked metric ordering, commit-through lock-entry OOME, concurrent close waiting,
  fail-after-unlock commit, upper-owner one-shot settlement, and post-commit gauge tests;
- `git diff --check`.

Final aggregate evidence:

- `:chainbase:test` over `org.tron.core.archive.*` plus account capture: green after the final
  storage fixes;
- `:actuator:test` over archive repository/capture, proof deadline, VM propagation, and actuator
  boundaries: green after the final same-tick deadline fix;
- `:framework:test` over archive JSON-RPC, lifecycle, reorg, dynamic-property reconstruction, and
  historical VM: green; the full matrix completed in 1m35s and the final affected historical-VM
  rerun completed in 40s;
- `:framework:checkstyleMain`, `:framework:checkstyleTest`, compilation, and `git diff --check`:
  green;
- two independent storage/resource and VM/Sapling adversarial reviewers were rerun after each
  finding. VM/Sapling closed with no confirmed actionable finding. Storage/resource closed after
  R17-24 with `No confirmed actionable findings in the current implementation.`

## 7. Runtime release gates

The source changes close confirmed ownership and poisoning defects, but they do not close the
production evidence gates:

1. sustained from-zero sync with production heap, RSS, native-memory, JFR, and RocksDB statistics;
2. real ENOSPC/EIO/permission/WAL/SST/MANIFEST corruption and restart matrix;
3. maximum admitted block/query payloads under compaction and device contention;
4. long-running archive query concurrency with deliberately stalled native proof calls;
5. external authenticated state oracle/root comparison, including coherent multi-row deletion;
6. restart and full-scrub SLO measurements at production archive size.

No production-readiness claim is made by this source/test round alone.
