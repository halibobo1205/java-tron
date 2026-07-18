# Archive round-23 bidirectional interference and persistence review

- Date: 2026-07-18
- Branch: `feat/archive-node`
- Review base: current uncommitted Round 10-22 worktree
- Layout: `UNIFIED_V1` only, schema 5, from-genesis public reads
- Status: CONFIRMED SOURCE/TEST ISSUES FIXED; RUNTIME RELEASE GATES REMAIN

## 1. Objective

Round 23 repeated the adversarial workflow over four boundaries that can fail independently:

1. canonical execution and archive publication versus hostile historical queries;
2. Java, RocksJava, and JNI ownership during snapshot construction and close;
3. fatal-state publication, repair-marker durability, watchdog timeout, and shutdown;
4. identity-lock durability and JSON-RPC batch settlement.

Independent tracks reviewed execution hot paths, query toxic-state propagation, and persistent
recovery. A separate conflict review adjudicated the native-cleanup contract, and the original
reviewers then attempted to falsify the completed fixes. The final post-fix reviews found no
remaining confirmed source issue in this round's scope.

## 2. Required invariants

1. Historical queries never populate canonical/live caches, hold the canonical mutation barrier
   while executing the VM, or retain a writer lock after their Unified snapshot is captured.
2. Native owners close in strict order: iterator, ReadOptions, snapshot, Java view accounting,
   SnapshotUse, snapshot permit, and query lease.
3. A native iterator or ReadOptions close failure has an unknown release result. Snapshot release,
   DB close, SnapshotUse release, and query-permit release must stop until restart.
4. An ordinary reader callback or post-release close failure is not native snapshot uncertainty.
   Only the typed `ArchiveSnapshotReleaseException` marker may retain the snapshot permit and arm
   archive fatal handling.
5. An iterator that fails before registration is still an owner. If its cleanup is uncertain, the
   parent read view becomes terminal even though the iterator never entered the owner list.
6. Fatal state, timeout generation, and counted transitions cannot be committed before allocating
   the token needed to finish or release them.
7. Publisher `Error` values remain exact and admission reaches FAILED before failure reporting.
8. Fatal callback delivery remains behind the durable repair-marker barrier.
9. A JSON-RPC batch stops after `INTERRUPTED`, including when jsonrpc4j serializes the application
   exception instead of rethrowing it. The thread interrupt flag remains set.
10. Cleanup `Error` outranks an earlier checked transport failure; the transport failure remains
    diagnostic only.
11. An exclusive identity operation starts only after the lock file and its parent directory entry
    have crossed their durability barriers.

## 3. Confirmed findings and fixes

| ID | Severity | Confirmed finding | Fix and direct evidence |
|---|---|---|---|
| R23-01 | High | `UnifiedArchiveReadView.close` continued to `releaseSnapshot` after iterator or ReadOptions close failed. RocksJava clears Java ownership before JNI dispose, so the native result could not be retried or inferred. | Iterator cleanup is attempted completely, ReadOptions closes only after every iterator succeeds, and any owner-close failure creates a sticky typed uncertainty. Snapshot/view/use accounting remains pinned; second close rethrows the same primary. |
| R23-02 | Medium | `ManagedArchiveStateReader` treated every delegate close failure as unknown snapshot release, retaining permits and fail-stopping on unrelated post-release failures. | Permit retention and fatal signaling now require `ArchiveSnapshotReleaseException.contains(failure)`. Ordinary and suppression-disabled close failures still release permit/lifecycle/query leases and do not invoke fatal handling. |
| R23-03 | High | Iterator wrapper allocation or owner-list insertion could fail after native iterator creation. If cleanup also failed, the iterator was unregistered and the view later released its snapshot. | Registration cleanup failure now becomes the view's sticky typed uncertainty, makes the view terminal, skips ReadOptions/snapshot release, and pins DB close. A regression injects list-registration and native-close failure together. |
| R23-04 | High | Snapshot construction had the same gap: configuration or later construction could fail, ReadOptions cleanup could fail, and the outer cleanup still released the snapshot and view count. | `configureSnapshotReadOptions` promotes close failure to typed uncertainty. `openReadViewLocked` stops before snapshot release on that marker or any later ReadOptions cleanup failure. Query SnapshotUse ownership and service-level fatal detection follow the marker through wrapped causes. |
| R23-05 | High | The watchdog committed `timedOutGeneration` before constructing its timeout exception. OOME could kill the worker before fatal arming and leave Scope.close and watchdog.close waiting forever. | One no-stack timeout token is created with the watchdog. Each arm allocates its Scope and prepares the diagnostic message before generation commit; timeout claim performs no allocation. Normal disarm clears the active reference, while the first timeout permanently seals re-arm. |
| R23-06 | Medium | `BoundedArchivePublisher` wrapped an `Error` before committing FAILED. Wrapper allocation failure could leave no failure value and let the worker report CLOSED. | The exact `Throwable` is retained, FAILED is committed first, and the handler accepts `Throwable`. Handler failure is secondary and cannot change publisher state. |
| R23-07 | Medium | `DefaultArchiveService` incremented its fatal-transition count before allocating the transition, then allocated `FatalClaim` and watchdog continuation objects after lifecycle fatal commit. | The transition is allocated before the count increment and also serves as the watchdog Runnable. Normalization and bounded repair reason are prepared before lifecycle commit; completion uses direct guarded calls without per-step lambdas. Count release is idempotent. |
| R23-08 | High | Servlet-level handling stopped only on batch deadline. Direct `INTERRUPTED` failures continued to later elements, and real jsonrpc4j hid the exception by serializing it with `rethrowExceptions=false`. | Both direct failures and the still-set thread interrupt flag terminate the batch. Checks run before each element, immediately after jsonrpc4j returns, and after transport settlement. A real JsonRpcServer regression proves the second method is never invoked. |
| R23-09 | Medium | A checked `IOException` from request execution remained primary when transport settlement then threw `Error`; the Error was only suppressed. | Cleanup Error now becomes primary unless an earlier Error is already primary. The IOException is attached best-effort and the Error escapes the servlet. |
| R23-10 | Medium | The identity lock file was created and locked but neither the file nor its parent directory entry was forced before durable identity operations ran. A power loss could preserve ACTIVE identity/data while losing the required lock path. | Exclusive lock acquisition now forces the lock file and fsyncs its parent directory before invoking the operation. Tests verify both ordering and the file-channel force call. |

## 4. Native ownership adjudication

The initial query review proposed retaining a snapshot permit after any delegate close failure. The
conflict review rejected that rule. RocksJava iterator and ReadOptions failures are uncertain because
their Java ownership bit is cleared before JNI dispose. By contrast, a higher-level reader may close
its native snapshot successfully and then fail in an unrelated callback or validator.

The final contract is therefore layered:

1. `UnifiedArchiveReadView` and `UnifiedArchiveDb` are the native ownership authorities. Every
   uncertain iterator, ReadOptions, or snapshot cleanup produces a primary typed marker and pins
   all lower owners.
2. `DefaultArchiveStateReader` preserves that marker through close composition.
3. `ManagedArchiveStateReader` retains the permit and invokes fatal handling only when the marker
   is present in the failure graph.
4. Ordinary close failures remain visible and are recorded on the query, but they do not invent
   native uncertainty or leak coordinator capacity.

This covers normal close, iterator registration failure, ReadOptions configuration failure,
read-view construction failure, snapshot release failure, and repeated close.

## 5. Fatal and timeout proof

Publisher failure first closes admission by committing FAILED and only then reports the exact
Throwable. Service fatal handling allocates a counted transition and prepares normalization and the
repair reason before lifecycle fatal commit. The same transition object is the watchdog continuation,
so no post-commit lambda or claim object is required.

The watchdog prepares its Scope, diagnostic text, and reusable no-stack timeout token before making
the generation visible. Timeout claim only moves scalar state and notifies waiters. The worker marks
the generation armed even if the handler itself fails, so Scope.close and watchdog.close cannot wait
forever on an unreachable arming state. The fatal controller is armed before slow repair persistence;
delivery opens only after `markRepairRequired` succeeds. A failed marker write remains fail-stop and
does not expose the application callback past the durability barrier.

The timeout token adds no exception or stack-trace allocation to each successful operation. The
only new successful-path allocation is the bounded diagnostic string prepared once per watchdog
operation, which occurs at block journal/publication granularity rather than per transaction.

## 6. Query and execution interference result

No new source-level execution/query poisoning path was confirmed:

- Unified historical reads remain cacheless and do not populate canonical/live state caches.
- Query code releases the Java mutation lease after snapshot capture and validates the canonical
  epoch again before response commit.
- Writer-capacity waits occur before the canonical mutation lease is admitted.
- Archive-off store writes retain their existing byte path and do not execute archive capture work.
- Normal snapshot close adds no new database operation; the new branches affect only failed native
  cleanup.
- Identity file fsync is startup/identity-protocol work, not block or transaction execution.
- JSON-RPC interruption checks are thread-local scalar reads and do not touch execution state.

Shared device queues, page cache, compaction, WAL, and JNI scheduling can still couple latencies at
runtime. This is a deployment/soak gate, not a Java cache- or lock-ownership defect.

## 7. Verification evidence

- Red/green regressions reproduced every confirmed ownership, interruption, error-priority, and
  watchdog failure before the production fix.
- Final complete chainbase suite: 824 tests green.
- Final framework archive/JSON-RPC/Manager/historical-VM/RocksDB matrix: green, including the full
  `JsonRpcServletTest` and a real jsonrpc4j server.
- Complete actuator and common suites: green.
- Framework main/test Checkstyle and `git diff --check`: green.
- The first complete chainbase run exposed a timeout diagnostic regression and one concurrent-test
  timing miss. Restoring the diagnostic closed six failures; all seven failed tests passed in
  isolation, and the complete 824-test rerun passed.
- Two post-fix adversarial reviewers found no remaining confirmed owner-release, fatal-transition,
  watchdog, JSON-RPC, or identity-lock issue in this round's scope.

## 8. Residual runtime and release gates

1. Real filesystem power-cut testing must prove lock-directory-entry, identity, WAL, MANIFEST, SST,
   journal ACK, marker, and cursor durability at each kill point.
2. Native iterator, ReadOptions, or snapshot close failure intentionally pins the DB and query
   accounting until restart. JNI fault injection must verify RSS/SST behavior and operator alarms.
3. A permanently wedged JNI call still requires an external supervisor. Java can arm fail-stop from
   another thread but cannot safely destroy a thread executing unknown native code.
4. Total VM failure such as unrecoverable OOME, ThreadDeath, or process corruption cannot guarantee
   Java repair-marker persistence. The source changes remove explicit allocation-after-commit
   windows; they do not claim recovery from a non-functioning VM.
5. ENOSPC, EIO, permission loss, torn files, delayed fsync, shared-disk saturation, page-cache and
   compaction pressure, from-zero sync, restart scrub SLO, and maximum-cost concurrent historical
   query soak remain mandatory production gates.
6. External state/root oracle comparison across transactions, contracts, TRC10, freeze/unfreeze,
   reorg, normal shutdown, crash/restart, and archive threshold boundaries remains required.

No production-readiness claim is made by this source/test round alone.
