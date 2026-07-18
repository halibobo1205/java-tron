# Archive round-22 lifecycle, snapshot ownership, and account-delta review

- Date: 2026-07-18
- Branch: `feat/archive-node`
- Review base: current uncommitted Round 10-21 worktree
- Layout: `UNIFIED_V1` only, schema 5, from-genesis public reads
- Status: CONFIRMED SOURCE/TEST ISSUES FIXED; RUNTIME RELEASE GATES REMAIN

## 1. Objective

Round 22 repeated the adversarial workflow across recovery activation, fatal publication, shutdown
dependency barriers, query admission deadlines, native snapshot ownership, RocksDB error precedence,
and optimized account-asset capture. Three independent review tracks attempted to falsify the fixes
after implementation: lifecycle/fatal/close, query/snapshot ownership, and account delta/performance.

This round distinguishes three claims:

1. source-level lock, ownership, and failure-ordering invariants covered by deterministic tests;
2. steady-state execution/query performance properties visible from the call graph;
3. native process, filesystem, and long-run behavior that still requires fault injection or soak.

Only the first claim is closed by this document.

## 2. Required invariants

1. Recovery validation and repair clear cannot make fatal publication or shutdown wait forever on
   the lifecycle mutex.
2. Recovery activation is committed once. Drain before that point cancels all activation work;
   drain after that point waits for the recovery lease and converges to DRAINING.
3. Repair evidence is cleared only after validation and publisher activation. A fatal before clear
   prevents it; a fatal during clear restores the marker before fatal delivery.
4. Close cannot destroy publisher, watchdog, controller, index, journal, temporal, or shared DB
   resources while recovery, a fatal transition, a query lease, or a snapshot permit still owns
   them.
5. Every service shutdown lock wait is bounded. A timed transition is successful only after the
   coordinator has actually reached CLOSED.
6. One historical request reserves its snapshot slot before any selector snapshot. Every native
   query view atomically claims one idle permit before waiting for the DB lock; one permit cannot
   back concurrent selector/final views or bypass `maxOpenSnapshots` accounting.
7. Native snapshot ownership is released only after `releaseSnapshot` returns successfully. An
   unknown outcome pins the DB view, permit, and query lease and makes the archive fail-stop.
8. Safety decisions cannot depend on `Throwable.addSuppressed()` succeeding under hostile errors
   or memory pressure.
9. A native RocksDB IOError or Corruption remains primary even if the Java query deadline expires
   at the same boundary. A real native TimedOut status still maps to the historical deadline.
10. A stale optimized-account capsule cannot overwrite a newer logical or physical asset value
    without recording the effective `ACCOUNT_ASSET` transition.
11. Archive-off execution performs no archive hashing, asset-map traversal, or extra previous-state
    read. Archive-on no-change account puts avoid a full asset-map diff.

## 3. Confirmed findings and fixes

| ID | Severity | Confirmed finding | Fix and direct evidence |
|---|---|---|---|
| R22-01 | Medium | Storage-only canonical reads lost the historical context when RocksDB returned native TimedOut, so the timeout could escape as generic storage failure. | Storage-only context is preserved through native read classification. Tests distinguish TimedOut from IOError/Corruption. |
| R22-02 | High | Unified selector snapshots opened before snapshot accounting and could bypass `maxOpenSnapshots`. | The query lease reserves one snapshot permit before selector resolution. Selector snapshots and the final reader share that request ownership boundary. |
| R22-03 | High | Unified iterator deadline checks could mask a native IOError/Corruption observed at the same iterator boundary. | Iterator status is classified before the Java post-read deadline check. Structural/native failures remain primary. |
| R22-04 | Medium | Query deadline could expire while waiting for the Unified DB snapshot lock or inside `getSnapshot`, without a post-lock/post-native check. | Lock acquisition consumes the remaining query budget and the context is rechecked immediately before and after native snapshot creation. |
| R22-05 | High | Recovery callback, fatal CAS, repair clear, and shutdown could interleave so repair evidence was cleared after a fatal or publisher activation was silently skipped. | Recovery activation now validates `activateForRecovery`, uses a two-phase lifecycle commit, and orders clear against fatal persistence with `repairStateMutex`. Publisher activation failure occurs before clear and fail-stops. |
| R22-06 | High | Long startup validation and forced-sync repair clear ran while holding the lifecycle mutex. Fatal publication and close could therefore stall before their bounded barriers. | Startup validation moved outside the lifecycle mutex and has independent `recoveryTimeoutMs`. Activation runs outside the mutex after a locked commit decision; repair clear uses the journal watchdog without the former lifecycle/watchdog deadlock. |
| R22-07 | Medium | Fatal persistence could remain in progress while close destroyed its controller or storage dependencies. | Every synchronous and watchdog fatal path owns a counted transition. Close seals new transitions and waits for all active transitions before watchdog/controller/storage teardown. |
| R22-08 | Medium | `awaitWriterCapacity` called `markFatal` while holding `backlogMonitor`; a stalled repair-marker write made close block before any timed drain. | The monitor now captures only the watermark snapshot/failure. Fatal publication and durable marker work run after releasing it. |
| R22-09 | Medium | Lifecycle/query drain requests and the coordinator's final close could wait indefinitely for internal locks; merely acquiring the final lock was also reported as close success. | Drain requests publish atomically without blocking. Timed waits include lock acquisition, and timed final close succeeds only in coordinator state CLOSED. Service stops teardown on failure. |
| R22-10 | Medium | A native snapshot owner could fail to close before release, but the permit was retained only if a marker survived insertion into the exception's suppressed graph. Suppression-disabled or allocation-failing errors could release accounting unsafely. | Managed readers decide uncertainty directly from owner-close failure. Reader-open cleanup carries an explicit `SnapshotCleanupTracker`; suppressed exceptions are diagnostic only. |
| R22-11 | High | Unknown native `releaseSnapshot` results could decrement Java view/permit accounting and allow DB close while JNI ownership was unknowable. | Unified views decrement their owner only after native release returns. Unknown outcome keeps the view, permit, query lease, and DB close barrier pinned and invokes fatal handling. |
| R22-12 | High | Two store-loaded account capsules could share the same old hint; after one updated an asset, a balance-only put from the other could restore the old asset without archive capture. | Tracking is bound to the exact account key and SHA-256 digest/length of the actual previous row. A mismatch falls back to the complete value diff. Successful puts rebase the hint for repeated incremental writes. |
| R22-13 | High | Account-row bytes can stay identical while optimized physical asset rows change. A capsule that lazily imported an old physical value could pass the row digest and overwrite the new physical value without a record. | `importAsset` and `importAllAsset` mark every hydrated ID while tracking is active. A same-row/different-physical-value test proves capture of `9 -> 1`. Unrelated physical IDs are not overwritten. |
| R22-14 | Medium | The asset no-change fast path parsed the complete old account and materialized its asset map before checking a complete empty delta hint. | Hint validation and the empty-delta return now run inside the planning watermark but before protobuf parsing. Archive-off bulk tracking returns before iterating IDs. |
| R22-15 | Low | `addAssetV2` decoded the asset ID twice even when archive tracking was disabled. | The canonical mutation and optional tracking reuse one decoded ID. |
| R22-16 | Medium | Watchdog self-close and publisher recovery/drain tests did not fully model production ownership, allowing teardown behavior to remain under-specified. | Watchdog self-close is rejected, publisher activation is explicit, and close joins publisher work before dependent resources. Focused tests cover retryable timeout and committed recovery/drain ordering. |
| R22-17 | Low | Archive debug configuration could enable a removed/unsupported historical trace surface. | Factory/config validation rejects the unsupported combination rather than exposing a partial RPC path. |
| R22-18 | High | A recovery activation callback failure could close the recovery lease before the service published fatal state. Concurrent close could then seal fatal transitions and lose the durable repair marker. | The activation callback catches `RuntimeException` and `Error`, calls `markFatal` while the recovery lease is still active, then rethrows. A blocked-marker/close regression proves the lease remains a teardown barrier until fatal evidence is durable. |
| R22-19 | Medium | Read-session and index pre-return cleanup attached release uncertainty only to the original throwable. A suppression-disabled primary could hide the marker and let callers release accounting after an unknown close. | Cleanup failure now creates a new primary `ArchiveSnapshotReleaseException`; the original operation failure is diagnostic only. Suppression-disabled regressions cover both read-session setup and index-owned views. |
| R22-20 | Medium | Query contexts proved only that some permit existed. One permit could back multiple simultaneous native views, or close while a claimed opener waited for the DB lock, leaving an unaccounted snapshot. Public production calls could also bypass the owning service coordinator. | Production-sealed DBs require their identity-bound owner permit. Each native query view atomically claims an exclusive `SnapshotUse` before DB lock wait, releases it only after positive native/view cleanup, and permit close rejects an active claim. Selector snapshots reuse the permit only sequentially. |
| R22-21 | Medium | `SnapshotUse` originally committed `useClaimed=true` before allocating the token. Allocation OOME would leave a permanent claim with no object capable of releasing it. | The token is allocated first and the claim is committed only after allocation succeeds. Final adversarial review found no later allocation or external call after the corresponding release commits. |

## 4. Recovery and fatal proof

Recovery now has three phases:

1. Under the recovery lease and consistency write lock, storage validation runs with the independent
   recovery watchdog but without the lifecycle mutex. A fatal or drain can therefore publish while
   a scrub is slow. The default recovery timeout is 24 hours and is explicitly configurable as
   `storage.archive.publisher.recoveryTimeoutMs`.
2. `ArchiveLifecycle.completeRecovery` takes the lifecycle mutex only to validate the current
   recovery lease, fatal state, and drain state, then marks activation in progress. It releases the
   mutex before invoking component activation.
3. Publisher activation happens before repair clear. The clear runs under `repairStateMutex` and a
   bounded journal watchdog. Final lifecycle settlement reacquires the mutex: fatal rejects RUNNING;
   a post-commit drain retains DRAINING; otherwise the phase becomes RUNNING. Every exit closes the
   recovery lease.

Activation callback failures publish fatal state before they escape the callback, while that
recovery lease is still counted. Close therefore cannot seal fatal transitions or destroy repair
storage in the gap between activation failure and the outer service handler.

This ordering removes the former lifecycle/watchdog cycle. Fatal can acquire the lifecycle mutex
while activation is blocked. Fatal before the repair mutex prevents clear. Fatal during clear is
visible immediately; durable marker continuation waits for the mutex and restores repair evidence
before normal fatal delivery. Close waits on the recovery lease and cannot tear down dependencies
while either path remains active.

## 5. Snapshot and query proof

The query sequence reserves a coordinator permit before selector resolution. The query context
keeps the exact permit identities, and every native view atomically claims one idle `SnapshotUse`
before waiting for the Unified DB lock. One permit supports selector snapshots and the final reader
sequentially, never concurrently; two live views require two coordinator slots. Production-sealed
DBs also require their identity-bound owner capability, so an unrelated coordinator cannot mint an
accepted production query. All Unified query reads are cacheless. Deadline checks cover initial
admission lock wait, pending wait, snapshot-permit lock wait, mutation locks, Unified lifecycle and
snapshot locks, and post-native snapshot creation.

Native release has a positive-proof rule: only successful return from RocksDB release permits the
Unified view count and `SnapshotUse` claim to fall. Any owner-close failure is conservatively treated
as unknown even when its diagnostic marker cannot be attached to the primary throwable. Unknown
ownership keeps the view, use claim, query permit, and lease pinned, stops DB teardown, and requires
restart.

Shutdown first rejects admission and drains lifecycle/query owners. It then stops sampler and
publisher work, seals and drains fatal transitions, and performs a separately timed final query
close. Only a real CLOSED coordinator allows watchdog/controller/storage teardown.

## 6. Account capture and performance proof

Store-loaded capsules bind their incremental hint to the canonical row version. Touched asset IDs
are copied into the sorted planner only after the planner watermark is admitted. A stale row digest
falls back to the full logical diff. Lazy physical hydration marks the imported IDs, covering the
case where the optimized account row is byte-identical but an underlying `account-asset` row has
changed.

Steady-state impact is limited to archive-enabled canonical account writes:

- `AccountStore.get` hashes the serialized account once when transaction capture is active;
- `AccountStore.put` hashes the actual previous row to validate the hint;
- a healthy store-loaded capsule with no asset mutation returns before old-account parsing;
- a touched-only capsule sorts only touched IDs;
- stale, externally built, layout-changing, delete, and unknown capsules deliberately use the full
  safe diff or physical-prefix scan.

Archive-off does not compute these hashes, allocate the touched-ID set, iterate asset IDs for
tracking, or add a previous-state read. No new archive query lock is taken by transaction execution.

## 7. Verification evidence

- Focused lifecycle, service, query coordinator, managed reader, Unified DB, and account-capture
  matrices: green.
- Complete chainbase suite including archive, LevelDB, and archive-off coverage: 818 tests green.
- Actuator archive/VM matrix: 47 tests green.
- Framework Manager, JSON-RPC, historical VM, VM-config isolation, and RocksDB matrix: 273 tests
  green.
- Common archive configuration tests, repository Checkstyle tasks, and `git diff --check`: green.
- Three post-fix adversarial tracks found no remaining source-level issue in lifecycle/fatal,
  query/snapshot ownership, or account delta handling after the final fixes.

## 8. Residual runtime and hardening gates

1. RocksDB JNI `getSnapshot`, sync write, and release calls cannot be forcibly preempted safely.
   Java can detect a deadline before/after the call and fail-stop from a watchdog, but a permanently
   wedged native call still requires an external process supervisor.
2. If forced-sync repair clear or repair-marker persistence itself never returns, in-process fatal
   delivery may wait behind the durability barrier. The delayed-return path is tested; permanent
   EIO/device-hang behavior remains a real fault-injection and supervisor-kill gate.
3. An unknown native snapshot release intentionally pins native/Java accounting until restart.
   Native fault injection must verify RSS, SST pinning, restart cleanup, and operator observability.
4. Arbitrary canonical resolver callbacks cannot be forcibly preempted. Known storage waits inherit
   the storage-only deadline; the request deadline is rechecked after callback return.
5. ENOSPC, EIO, permission loss, WAL/MANIFEST/SST damage, kill points around journal ACK/publish,
   delayed or failed repair metadata, and unknown snapshot release remain the required disk/process
   fault matrix.
6. Shared filesystem, page cache, compaction, WAL, and device queues can still couple historical
   query latency to publication and canonical execution despite Java lock/cache isolation.
7. From-zero sync, external state/root oracle comparison, restart scrub SLO, production heap/RSS/
   native-memory observation, and maximum-cost concurrent historical queries remain open soak gates.

No production-readiness claim is made by this source/test round alone.
