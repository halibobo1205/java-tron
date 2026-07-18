# Archive round-21 drain, resolver, repair, and cache-isolation review

- Date: 2026-07-18
- Branch: `feat/archive-node`
- Review base: current uncommitted Round 10-20 worktree
- Layout: `UNIFIED_V1` only, schema 5, from-genesis public reads
- Status: CONFIRMED SOURCE/TEST ISSUES FIXED; RUNTIME RELEASE GATES REMAIN

## 1. Objective

Round 21 repeated the adversarial workflow across shutdown/recovery races, publisher dependency
teardown, external canonical resolvers, Unified snapshot ownership, repair evidence, historical VM
thread reuse, and query-to-execution cache/context pollution.

The review used independent lifecycle, query, and persistent-storage tracks. Each confirmed issue
was checked against the opposite failure mode: healthy shutdown must not create repair evidence,
request-local I/O must not brick the archive, a query must not lend its snapshot or execution
budget to canonical work, and canonical resolution must still remain cacheless and bounded.

## 2. Required invariants

1. Journal payload, proof, acknowledgement, and in-memory lifecycle state must describe the same
   phase before a row can be deleted or published.
2. Service close is a dependency barrier. Downstream index, temporal, journal, and DB resources
   cannot close while the publisher or sampler can still touch them.
3. DRAINING and CLOSED are ordinary lifecycle outcomes. They clean request-local capture and
   allocation state without writing repair-required evidence.
4. Startup recovery cannot reactivate a service after shutdown has won the race.
5. Application canonical resolvers run without archive mutation/consistency locks and without an
   execution-visible `QueryContext` or Unified snapshot binding.
6. A selector result spanning a canonical epoch change fails closed. The epoch is sampled before
   the resolver and rechecked after it with two short, deadline-aware leases.
7. Cacheless canonical storage reads inside a resolver retain only the historical request's
   storage budget and deadline. VM, publication, and canonical execution still see no query
   context.
8. Unbound Unified selector reads use query snapshots with `fillCache=false`; random historical
   selectors cannot admit INDEX blocks into the shared RocksDB cache.
9. Runtime I/O failure is request-local unless persistent structural corruption is proven.
   Repair evidence is bounded, UTF-8 safe, durable, and itself read with a hard bound.
10. A primary decode/corruption failure remains primary when snapshot cleanup also fails.

## 3. Confirmed findings and fixes

| ID | Severity | Confirmed finding | Fix and direct evidence |
|---|---|---|---|
| R21-01 | High | Loaded journal delete accepted impossible acknowledgement combinations: a committed block could lack ACK, or a journaled block could have one. | `UnifiedArchiveInFlightStore` now validates ACK presence against the decoded lifecycle state before delete. Both mismatches retain all rows and fail closed. |
| R21-02 | High | Service close could continue destroying index/temporal/DB dependencies after publisher stop timed out. | `BoundedArchivePublisher.close(timeout, unit)` is now a hard barrier. `DefaultArchiveService.close` stops teardown immediately on failure, preserves dependencies for retry/diagnosis, and rejects worker self-close. |
| R21-03 | Medium | Clean DRAINING/CLOSED validation could enter fatal capture boundaries, leave execution allocation/capture residue, or write false repair evidence. | Lifecycle availability is separated from capture availability. Clean commit/abort drain paths always clear capture/context and abort the execution allocator without `markFatal`. |
| R21-04 | High | Resolver callbacks could run while a query-owned context/snapshot was active. Reentrant canonical work could inherit query accounting or a Unified index binding. | All selector callbacks run before mutation/consistency locks and before the final Unified session. `QueryContextHolder.suspend()` hides the execution context, and callbacks are wrapped by `resolveExternal`. Reentrant publication succeeds without inheriting the query. |
| R21-05 | High | Moving resolvers outside the mutation lease initially sampled the epoch too late. A fork completed during a blocked resolver and the query accepted the post-fork epoch as its baseline. | The service takes a short deadline-aware shared lease before the selector, releases it around external code, then reacquires and compares epochs. A cross-fork selector now throws `ArchiveSnapshotInvalidatedException` without blocking fork progress. |
| R21-06 | High | Recovery completion could reactivate the service after close entered DRAINING. | `ArchiveLifecycle.completeRecovery` now returns whether activation won. Recovery activation/validation/publisher start execute only inside the successful lifecycle transition; shutdown cancellation closes the recovery lease cleanly. |
| R21-07 | Medium | Close used one shrinking timeout across independent subsystems, and sampler timeout incorrectly marked durable repair. | Lifecycle, query coordinator, sampler, and publisher each receive the full configured close budget. Sampler stop failure is retryable terminal-close evidence, not archive corruption. |
| R21-08 | Medium | Arbitrarily long fatal/startup messages could make repair evidence itself unpersistable; startup probing read a corrupted oversized marker without a Java bound. | Internal reasons are UTF-8 truncated to 4096 bytes, direct operator writes remain strict, and `hasRepairRequired` uses a bounded Unified read. Oversized runtime failure evidence survives restart in bounded form. |
| R21-09 | High | A failed historical VM call could leave thread-local VM/query state visible to a canonical call or the next historical call on the same worker. | Failure cleanup restores VM config and query context on every exit. Same-thread historical-failure, canonical-call, and second-historical-call tests prove no poison survives. |
| R21-10 | Medium | `RocksDB.getSnapshot()==null` was an untyped `ArchiveException`. Selector opening classified it as `CORRUPT_INDEX`, wrote repair-required, and fail-stopped a healthy archive on a request-local resource failure. | `ArchiveStorageAccessException` explicitly identifies non-corruption storage access failure and maps to `INTERNAL_IO`. Raw point/query/scan view tests pin the type; service tests prove no repair marker is written. |
| R21-11 | Medium | Unbound range read closed its snapshot before decoding. If cleanup failed, the cleanup error escaped before malformed bytes could be classified as the primary corruption. | `getBlockRange` now reads and decodes inside one bound view. Opening classification carries cleanup failures as suppressed evidence while retaining `CORRUPT_INDEX` as primary. |
| R21-12 | Medium | Fully suspending `QueryContext` fixed execution pollution but also removed deadline/value budgets from cacheless canonical block reads. Separately, unbound Unified selector reads still used `fillCache=true`. | The holder now exposes a storage-only context used only by durable-root/cacheless canonical readers. VM/publication code still uses `current()` and sees null. Unified unbound selectors choose `openQueryReadView(context)`. RocksDB/LevelDB budget tests and direct `ReadOptions.fillCache()` assertions cover both boundaries. |

## 4. Resolver and snapshot proof

The final query-open sequence is:

1. admit the query and acquire a lifecycle lease;
2. take and release a short mutation read lease to sample `selectorEpoch`;
3. resolve archive index selectors under the query context, while each application callback runs
   under an execution-suspended, storage-budget-only scope;
4. reacquire the short mutation lease and reject if its epoch differs;
5. acquire the snapshot permit, release the mutation lease, and open one cacheless Unified snapshot;
6. re-read complete coverage, target range, and independent temporal marker in that snapshot;
7. validate the epoch before exposing the reader and again after response serialization.

No application callback, RocksDB Get, VM execution, or response serialization holds the mutation
barrier. An exclusive fork therefore progresses, while any selector/snapshot that spans it fails
closed. Epochs are monotonic, so an ABA hash or branch change cannot restore validity.

Transaction selector opening now performs one unbound three-row `txId -> position -> range`
resolution, then revalidates the target range and marker in the final snapshot. The extra range Get
is intentional correctness work, not an execution-path regression.

## 5. Shutdown and repair proof

Shutdown first rejects new query/lifecycle work, then independently drains lifecycle work, query
serialization, disk sampling, and publisher work. A failed stop keeps all dependencies alive and
returns the same terminal failure on retry. A publisher cannot declare itself closed from its own
worker thread.

Recovery completion is one lifecycle transition with an activation callback. If DRAINING wins,
the recovery lease closes and activation does not run. Clean drain still clears in-memory capture
and allocation state but never writes repair evidence.

Internal fatal/startup repair reasons are bounded before encoding. Direct `markRepairRequired`
remains strict so callers cannot silently lose operator text. The startup presence probe rejects an
oversized on-disk value without returning it to higher layers. Ordinary snapshot acquisition
failure maps to request-local I/O; only explicit persistent corruption causes repair/fail-stop.

## 6. Performance and interference result

No new canonical transaction/block execution read or lock was added. Round 21 costs apply only to
historical query opening:

- two short fair mutation read-lease acquisitions around selector resolution;
- one cacheless unbound selector snapshot where a selector needs INDEX data;
- one final snapshot range recheck for transaction selectors.

The resolver never holds these locks while invoking application code. Query selector reads and
canonical block identity reads use `fillCache=false`, so random historical traffic does not evict
execution/publication cache entries through these paths. Storage-only context preserves backend
read/value budgets and native deadlines without enabling VM step checks, VM trace suppression, or
archive publication accounting in reentrant canonical work.

These are fixed per-query costs. Target-hardware QPS, p99 snapshot-open latency, cache hit ratio,
and compaction interference remain runtime measurements.

## 7. Verification evidence

- Complete chainbase archive suite plus LevelDB cacheless tests: green after all Round 21 fixes.
- Focused ACK-state, publisher barrier, clean-drain cleanup, recovery-vs-shutdown, sampler close,
  bounded repair marker, missing snapshot, cleanup precedence, epoch race, storage-only context,
  and cacheless selector tests: green.
- Actuator archive repository, historical VM failure, transfer-proof deadline, and VM actuator
  matrix: green in the final run.
- Framework archive, JSON-RPC serialization/budget, Manager genesis/fork/recovery, historical VM,
  VM-config isolation, and RocksDB cacheless-read matrix: green in 1m37s.
- Repository Checkstyle tasks and `git diff --check`: green.

## 8. Residual runtime gates

1. RocksDB JNI may materialize/decompress an out-of-band oversized value before Java observes the
   bound. Real oversized SST/value corruption with RSS/NMT observation remains required.
2. A native `releaseSnapshot` fault is surfaced and dependent teardown stops, but the JNI/native
   snapshot may remain pinned. This requires native fault injection and restart validation.
3. Arbitrary application callbacks cannot be forcibly preempted. Only known cacheless canonical
   storage waits/reads inherit the storage-only deadline; the query deadline is rechecked when an
   arbitrary callback returns.
4. ENOSPC, EIO, permission changes, torn WAL/MANIFEST/SST, and kill points around journal ACK,
   loaded delete, and cross-CF publication remain disk/process fault-injection gates.
5. Shared filesystem, page cache, compaction, WAL, and device queues can still couple historical
   query latency to publication latency even when Java locks and RocksDB cache admission are
   isolated.
6. Sustained from-zero sync, external state oracle/root comparison, production heap/RSS/native
   memory, restart scrub SLO, and maximum-cost concurrent historical query tests remain open.

No production-readiness claim is made by this source/test round alone.
