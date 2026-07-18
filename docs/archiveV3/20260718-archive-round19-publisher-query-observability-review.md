# Archive round-19 publisher, query, observability, and startup review

- Date: 2026-07-18
- Branch: `feat/archive-node`
- Review base: current uncommitted Round 10-18 worktree
- Layout: `UNIFIED_V1` only, schema 5, from-genesis public reads
- Status: SOURCE/TEST FIXES COMPLETE; RUNTIME RELEASE GATES REMAIN

## 1. Objective

Round 19 repeats the adversarial workflow at the boundaries where a slow or failed archive
operation can interfere with canonical execution, or where startup recovery can silently combine
state from different persistent generations:

1. durable journal append/ack/delete and solidified publication lock ownership, timeout, and
   fail-stop ordering;
2. filesystem-capacity probes, shutdown, native-resource lifetime, and stale sample generations;
3. metrics/reporting behavior under queue saturation, sink failure, disabled metrics, and OOME;
4. exact historical-query I/O/deadline charging without service-layer guesses;
5. fork recovery, published-tail/journal continuity, and identity-authenticated corruption repair;
6. retained-memory estimation and allocator/query ownership on long-running nodes.

Three skeptic tracks reviewed the write/publisher, query/accounting, and startup/cache paths. The
final startup findings were reproduced from source, fixed, and challenged again after the patch.

## 2. Required invariants

1. A blocked archive native write must not silently let canonical execution continue. The process
   must enter fail-stop within a finite configured interval, retain repair evidence, and arm fatal
   delivery before slower persistence or callback work.
2. Journal and publication have independent Java serialization domains and independent watchdogs.
   They may still contend in RocksDB/WAL/device internals, but neither path may wait forever without
   fail-stop evidence.
3. A watchdog scope cannot disarm an operation merely because its worker was scheduled after the
   deadline. Scope close and service close must wait until the fatal state is armed.
4. Filesystem-capacity probes never execute on the canonical block thread. A stale or delayed probe
   cannot replace a newer sample, and close cannot destroy storage while the sampler may still use
   it.
5. Metrics are observational: disabled metrics do no traversal/timer work, and enabled reporting,
   allocation failure, queue saturation, or sink failure cannot alter journal/publication/query
   semantics. Final gauges must converge to the latest state.
6. Historical query budgets are charged by the actual storage/provider boundary. A selector must
   not invent reads for a supplier, nor double-count a read already charged by the unified store.
7. Startup must authenticate the published archive tail against canonical state before accepting
   the first retained journal. It must never publish a chain formed by joining two forks.
8. A production identity path is authenticated before repair evidence is written. Once
   authenticated, persistent journal corruption discovered during identity floor inspection must
   retain its original exception and force-sync `repair-required`.
9. Archive-off consensus behavior remains unchanged; every new timeout, sampler, metric, and
   reconciliation branch is archive-enabled only.

## 3. Confirmed findings and fixes

| ID | Severity | Confirmed finding | Fix and direct evidence |
|---|---|---|---|
| R19-01 | High | Journal mutation and solidified publication shared one Java serialization path. A stalled publication could hold the service write path while a new canonical block tried to append or acknowledge its journal. | Journal and publication now use independent Java locks. A blocked-publication regression proves the journal Java lock remains available. The shared RocksDB/WAL limitation is documented rather than hidden. |
| R19-02 | High | Publication and journal native writes had no enforceable upper wait. A RocksDB/WAL stall could retain a canonical or publisher thread indefinitely without arming fail-stop. | Separate one-operation publication and journal watchdogs cover append, ACK, rollback delete, unwind delete, and publication. Timeouts arm lifecycle/fatal state synchronously, then persist repair evidence and deliver the callback. Stalled-operation tests cover every journal mutation and publication. |
| R19-03 | High | The first watchdog could miss an elapsed deadline when scope close raced ahead of the watchdog worker, and close could return before fatal arming completed. | Scope close independently checks the monotonic deadline, claims the timeout generation, waits for fatal arming, and throws the timeout failure. Watchdog close has the same arm barrier and permanently rejects rearm after timeout. |
| R19-04 | Medium | Filesystem free-space was sampled through a caller-visible probe. A blocked filesystem call could stall block admission, while a detached sampler could outlive service storage during close. | A single-flight daemon sampler bounds callers, assigns monotonic sample generations, rejects stale completion, and has a bounded join. Service close drains admitted work, closes the sampler before storage, and marks repair/fatal if it cannot stop; retry succeeds after the probe releases. |
| R19-05 | Medium | Synchronous RocksDB statistics/property polling had no native deadline and could add an unrelated unbounded JNI call to write/query/close paths. | Runtime paths no longer poll native properties. Diagnostics remain available only through explicitly invoked tooling; correctness and admission do not depend on the counters. |
| R19-06 | Medium | Metrics could throw from preparation/dispatch, traverse capture records while disabled, or drop final gauges behind a saturated FIFO. That allowed observability to affect durable semantics and leave misleading non-zero state. | Metrics startup is lazy, disabled timers use the no-work sentinel, public hooks catch all failures, events use a bounded queue, and gauges use latest-state coalescing. OOME, blocked reporter, full queue, and disabled traversal regressions are green. |
| R19-07 | Medium | Repeated journal resource estimates traversed immutable positions/records and re-encoded size components on hot admission/publication paths. | `ArchiveInFlightBlock` caches immutable encoded/retained/resource estimates. Codec and temporal estimators are byte-exact against actual encoding, and journal-state replacement changes only its fixed state byte. |
| R19-08 | Low | BLOCKHASH lookup was charged once by the reader and again for its real INDEX and BLOCK_MARKER reads. Dynamic block selectors also pre-charged an arbitrary `LongSupplier`. Tight budgets therefore rejected valid requests. | Reader/service guesses were removed. Unified storage charges the two physical BLOCKHASH reads; arbitrary suppliers charge only through their attached providers. Exact-read regressions cover both paths. |
| R19-09 | Low | The allocation-failure release fallback repeatedly tried the coordinator lock with a hot spin/yield loop. Under a deliberately stalled owner, an already-failing historical query could consume a CPU while trying to settle its permit. | The allocation-free fallback uses capped `parkNanos` backoff, still commits ownership before rethrowing the original OOME, and leaves one-shot reader/transport settlement intact. |
| R19-10 | Medium | `Manager.switchFork` assumed the old branch was non-empty while selecting rewind and recovery heads. An empty-old-branch result dereferenced a missing entry before signature validation/replay. | The pre-switch canonical head is captured explicitly, common ancestor/recovery head are non-null for both branch shapes, and rewind compares against the stable ancestor. All parameterized `ManagerTest.pushSwitchFork` cases and archive fork recovery tests pass in isolation. |
| R19-11 | High | Startup validated parent links only between retained journals. It did not authenticate the published archive tail as the parent anchor, so a published old-fork block followed by current-fork empty journals could be acknowledged and published as a cross-fork chain. A pending-cleanup-only restart could also delete its evidence before the later tail mismatch surfaced. | Before retained journal ACK/rollback/publication or pending-journal cleanup, the published tail range is resolved at the same canonical height and its hash is compared. The canonical tail becomes the first parent anchor. Regressions preserve both unacknowledged and pending-cleanup journal evidence on mismatch. |
| R19-12 | Medium | The production four-argument factory scanned the journal to derive its floor before validating the root/anchor identity pair. Corruption failed closed but escaped before the normal startup marker path. The first fix reopened by path, so a replacement DB could receive evidence for corruption detected in another DB. | Both ACTIVE identity copies and expected fields are authenticated first. Their shared locks cover floor inspection, same-handle forced-sync `repair-required`, and the actual-floor comparison. The original corruption type remains primary through marker/close failure. |
| R19-13 | High | Even after ACTIVE identity validation, the factory chose open-versus-initialize from a fresh path-existence check. If the registered mount disappeared in that interval, a non-empty canonical node could create a new empty archive in the exposed mountpoint. | Startup now carries an explicit `OPEN_EXISTING` or `INITIALIZE_NEW` decision. Every anchored ACTIVE/resumed path and every pre-existing unanchored path strict-opens; only an explicit fresh, empty-canonical, unanchored initialization may create a DB. Missing strict-open targets fail without creating files. |
| R19-14 | Low | Publisher/watchdog durations accepted values so large that an operator could effectively disable fail-stop despite choosing a finite number. | Backpressure, publication, and journal timeouts now have a 24-hour operational ceiling in both parsed configuration and runtime publisher configuration. Boundary/overflow tests are green. |

## 4. Post-fix adversarial results

1. The published-tail check runs after read-only startup validation and pending-journal canonical
   checks, but before retained journal ACK, rollback, pending cleanup, or publication. A mismatch
   therefore leaves durable journal evidence intact and marks the service fatal.
2. Loaded journal block numbers and txNum ranges are already validated as contiguous with the
   published index. The new canonical tail anchor adds hash/parent continuity; it does not replace
   structural journal validation.
3. Identity corruption marking occurs only after a matching ACTIVE root/anchor pair has validated
   chain ID, schema, layout, final path, UUID, nonce, and declared floor. Missing, malformed,
   mismatched, or unclaimed roots remain read-only failures and are never marked. Shared identity
   locks remain held through payload inspection and floor comparison.
4. The original `ArchivePersistentStateCorruptionException` remains primary if marker write or DB
   close also fails; secondary failures are suppressed evidence. Detection and forced-sync marker
   use one open DB handle, so a later path replacement cannot redirect the marker.
5. An ACTIVE or resumed identity cannot silently downgrade to initialization. A missing target at
   the strict-open boundary fails startup and leaves the exposed directory without a new DB.
6. Metrics-disabled capture does not read the monotonic clock or traverse the change list. Metrics
   queue saturation can drop event samples but cannot lose the latest lifecycle/query/in-flight
   gauge state.
7. No query-controlled object, metric event, disk sample, or historical memo is consumed by
   canonical VM execution. Archive-off stores do not start archive samplers, watchdogs, publisher,
   query coordinator work, or archive metrics traversal.

## 5. Verification evidence

- Focused common/chainbase core matrix covering configuration, operation watchdog, disk sampler,
  metrics, service startup/close, async publisher, factory identity, unified DB/backend, and state
  reader: green.
- Complete `:chainbase:test --tests 'org.tron.core.archive.*'`: green after the final startup fixes.
- Complete actuator archive/historical-VM matrix, including repository behavior, hard VM failure,
  and proof deadlines: green.
- Framework archive, JSON-RPC, historical VM/dynamic properties, lifecycle, genesis, and fork matrix:
  green in 1m35s after the final fixes.
- Isolated upstream `ManagerTest.pushSwitchFork`: green, including all parameterized cases.
- `:framework:checkstyleMain`, `:framework:checkstyleTest`, compilation, and `git diff --check`:
  green. Common, chainbase, and actuator do not define module-local checkstyle tasks.
- An accidental broad framework-suite run was stopped after a thread dump identified unrelated
  suite-global VM/static-state pollution and a runaway test miner. It is not counted as archive
  evidence and did not leave a required process running.

## 6. Residual engine and test limitations

1. LevelDB exposes neither a cancellable point read nor a length probe before value allocation. A
   permanently blocked native `get` can retain a query permit and delay close until the engine call
   returns. Java-side deadline and byte checks can only classify it after return.
2. Journal and publication have independent Java locks and watchdogs, but use one RocksDB instance,
   WAL, filesystem, and device. A native engine stall may block both operations; the guarantee is
   bounded fail-stop, not independent physical progress.
3. The normal RocksDB JNI build used here does not expose native SyncPoint fault injection. Current
   tests prove Java lock/watchdog behavior with controlled adapters, not a real in-WAL concurrent
   stall.
4. Query and execution still share device bandwidth, OS page cache, RocksDB background work, and
   filesystem behavior. Deadlines and cacheless reads bound cooperative paths but cannot cancel
   every kernel or hardware stall.
5. Local identity and cross-row checks are not an authenticated state commitment against a process
   or filesystem owner capable of coherently rewriting every related row. The external oracle/root
   gate remains required.

## 7. Performance conclusion

The reviewed source paths now have finite Java-side admission and retained-memory bounds:

- canonical archive-on commit performs capture, immutable estimate lookup, one durable journal
  append, and one compact ACK; it does not wait for solidified publication's Java lock;
- disk capacity and metrics work are off-thread and bounded from the caller's perspective;
- publication remains one atomic cross-column-family batch and is serialized per archive;
- historical selectors charge actual physical reads and retain request-local caches/overlays only;
- no synchronous native statistics polling remains on block, query, publication, or close paths.

This does not establish production throughput or latency. Forced-sync journal/ACK cost, compaction,
shared-device contention, and maximum-block memory must be measured on the target filesystem and
hardware.

## 8. Runtime release gates

The production evidence gates remain open:

1. sustained from-zero sync with production heap/RSS/native-memory/JFR and block-latency data;
2. real ENOSPC/EIO/permission/WAL/SST/MANIFEST corruption and restart matrices;
3. maximum admitted block and query payloads under compaction and shared-device contention;
4. long-running concurrent archive queries with deliberately stalled LevelDB/RocksDB calls;
5. external authenticated state oracle/root comparison, including coherent multi-row deletion;
6. restart, full scrub, repair, and graceful-shutdown SLO measurements at production archive size.

No production-readiness claim is made by this source/test round alone.
