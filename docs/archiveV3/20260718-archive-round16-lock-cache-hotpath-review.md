# Archive round-16 lock, cache, and hot-path adversarial review

- Date: 2026-07-18
- Branch: `feat/archive-node`
- Review base: current uncommitted Round 15 worktree
- Layout: `UNIFIED_V1` only, schema 5
- Status: SOURCE REVIEW COMPLETE; RUNTIME RELEASE GATES OPEN

## 1. Objective

Round 16 attacks the remaining correctness and performance boundaries from four independent angles:

1. derive the complete lock and lifecycle graph for commit, journal ACK, asynchronous publication,
   reorg, query response settlement, fatal transition, and close;
2. prove that every Java memo, journal proof, RocksDB snapshot/cache entry, and thread-local belongs
   to one immutable generation and cannot be reused after failure or canonical epoch change;
3. count synchronous database calls, parsing, copies, scans, and durable barriers on canonical and
   historical-query hot paths, including execution/query interference through shared resources;
4. remove only production-unreachable duplicate APIs or state machines whose behavior is already
   represented by the `UNIFIED_V1`, from-genesis, `ArchiveStatePoint` path.

## 2. Required invariants

1. No code path acquires the mutation barrier, publication lock, consistency lock, lifecycle
   mutex, query coordinator, unified DB lifecycle lock, or DB mutation lock in a cycle.
2. Normal drain lets already-admitted writers, publishers, and serialized historical responses
   finish; fatal drain rejects buffered responses before commit; close never destroys a DB with an
   active read view.
3. A query snapshot is accepted only if its final response seal observes the same canonical epoch
   and no fatal state. Shutdown alone must not turn an otherwise valid admitted response into a
   false corruption or availability failure.
4. Proof and decoded-value caches are capabilities for a specific validated persistent generation.
   Failed scans, delete/publish, abort, reorg, fatal transition, and close must revoke them before
   reuse.
5. Query-controlled reads do not populate execution-critical caches, mutate shared archive state,
   retain data after response settlement, or hold a Java lock across unbounded VM/serialization
   work.
6. Canonical execution performs bounded work per changed key and block. Any forced sync, full scan,
   protobuf parse, temporal encode, or native batch expansion on that path must be explicit,
   admitted, and documented.
7. Every thread-local scope restores the exact previous value on all `Throwable` paths and rejects
   cross-thread or out-of-order close.
8. An API removal requires a repository-wide production call-graph proof and must preserve
   archive-off byte identity and the supported from-zero archive behavior.

## 3. Lock graph under review

Expected outer-to-inner order:

1. service lifecycle admission;
2. mutation barrier shared/exclusive lease;
3. publication serialization lock, when publishing;
4. service consistency lock;
5. in-flight journal monitor or unified adapter monitor;
6. unified DB lifecycle read lock;
7. unified DB mutation lock;
8. RocksDB write or snapshot creation.

Query admission and snapshot permits are acquired before the service consistency lock. A
genesis-complete query releases the mutation lease and consistency lock after snapshot capture,
then performs a deadline-bounded epoch seal after response serialization. Close begins every
admission drain before waiting and acquires no service/DB write lock until admitted work settles.

## 4. Adversarial workflow

1. Build an acquisition/release table from source for every public service operation and inject
   barriers at each lock handoff.
2. Interleave close/fatal/reorg with reader open, snapshot capture, serialization settlement,
   journal write, ACK, and asynchronous publication.
3. Corrupt or replace proof-bound rows around cache population and prove stale capabilities are
   cleared rather than reused.
4. Drive repeated reads, large values, BLOCKHASH, duplicate writes, and long publication queues;
   compare backend-read, allocation, and retained-resource counters with expected constant bounds.
5. Search the production call graph for superseded interfaces, duplicate validation, and parallel
   state machines; delete only after focused behavior tests exist at the surviving entry point.
6. Run two independent post-fix adversarial reviews and the aggregate archive/checkstyle matrix.

## 5. Confirmed findings and decisions

| ID | Severity | Finding | Status |
|---|---|---|---|
| R16-01 | Medium | Final response epoch validation used general lifecycle availability after releasing the reader lifecycle lease. A normal drain could reject an already-admitted, unchanged response solely because the phase became `DRAINING`. | Fixed with an admitted-response validator that accepts normal drain but still rejects fatal, recovery, and closed phases. Red/green lifecycle and service tests pass. |
| R16-02 | Medium | The production-unreachable historical trace stack installed a `ThreadLocal.get()` in `VMConfig.vmTrace()` on every opcode, including archive-off canonical execution. Authority decision 7 excludes historical `debug_traceCall`/`vmTrace`. | Removed the historical trace stack and per-opcode thread-local override after a repository-wide production call-graph proof. Common, actuator, and framework compilation passes. |
| R16-03 | High | Production `AccountStore` asset planning parsed raw accounts, built sorted asset sets, and could scan a large physical prefix before entering the capture engine's transient resource watermark. The already-budgeted byte-array helper had no production caller. | Fixed by an engine-owned planning scope admitted from raw address/old/new byte lengths before parsing, allocation, or physical reads. Admission failure records the first archive failure, skips planning, and leaves canonical write isolation unchanged. All focused account-asset tests pass. |
| R16-04 | Medium | Selector resolvers loaded and validated an `ArchiveBlockRange`, then the reader factory immediately loaded the same range again from the same protected snapshot. | Fixed by passing the already-resolved committed range as an explicit in-memory validation witness. Arbitrary externally supplied `ArchiveStatePoint` values resolve their range once before reader construction. |
| R16-05 | Low | Extending an existing temporal chain fully read the absent latest-baseline row, then issued a second point read only to test the same absence. | Fixed by reusing the decoded/null result. The exact RocksDB key-read regression drops from 23 to 22 while baseline/anchor corruption tests remain fail-stop. |
| R16-06 | Medium | Historical VM `CHAINID`/`BLOCKHASH` loaded and parsed a full live canonical block for each distinct cache miss, and those bytes were outside archive payload budgeting. A direct live block-index replacement would also have weakened corruption detection. | Fixed with a same-`UNIFIED_V1`-snapshot RANGE + BLOCK_MARKER lookup. `ManagedArchiveStateReader` forwards the operation through its fatal-aware guard, missing/gapped/mismatched rows fail as `CORRUPT_INDEX`, and every physical read/value byte is request-budgeted. |
| R16-07 | Low | `DefaultArchiveStateReaderFactory.openLocked` had no caller and exposed a pass-through, non-snapshot reader construction path that production no longer uses. | Removed after zero-call proof. Test-only pass-through temporal views remain where explicitly constructed. |
| R16-08 | High | A shape-valid ancestor RANGE rewrite could preserve height while substitute a different block hash; historical `BLOCKHASH` would have returned the forged hash because only RANGE was read. | Fixed by validating every archive block-hash RANGE against its independently stored BLOCK_MARKER in the same snapshot. Online tamper and gap tests prove managed-reader fail-stop and persistent repair marking. |
| R16-09 | Medium | The first block-hash implementation updated the concrete reader but omitted forwarding in `ManagedArchiveStateReader`, so production callers received the interface default instead of the archive result. | Fixed with guarded forwarding and a production-service integration test. The wrapper now applies owner, lifecycle, fatal, and response-settlement behavior to block-hash reads. |
| R16-10 | Medium | A 256-entry block-hash LRU could not hold genesis for `CHAINID` plus all 256 EVM-reachable ancestors, causing deterministic eviction/thrash at the boundary. | Raised the request-local bound to 257. A regression fills genesis plus the entire reachable ancestor window and proves no repeated backend read. |
| R16-11 | Low | Foreign-thread close of a unified index scope or read session could mutate local closed/binding state before the underlying owner check, poisoning owner cleanup. | Both wrappers now capture their owner and reject foreign close before state mutation. Owner reuse and subsequent cleanup are covered. |
| R16-12 | Medium | Historical trace RPCs were removed, but dead trace byte/reservation limits and `ProgramTrace` query coupling remained; global live `vmTrace` could still allocate an unused trace during historical `eth_call`. | Removed the dead limits, reservations, metrics, and coupling. Historical programs suppress global live trace capture at construction; canonical live VM tracing remains unchanged and tested. |
| R16-13 | Medium | The selected query target RANGE itself was not checked against BLOCK_MARKER before reader construction. A validly encoded coordinate rewrite preserving block height/hash could redirect block N to an older txNum and silently return stale state. | Every selected RANGE, including an arbitrary external `ArchiveStatePoint`, is now marker-validated in the same read session before a reader is exposed. The already-resolved RANGE is reused, so this adds one independent marker read without restoring the duplicate range read. |
| R16-14 | Low | `UnifiedArchiveTemporalStore.SnapshotView.close()` set `closed` before delegating to the owner-bound native view. A foreign close therefore leaked the native snapshot and prevented owner retry. | The wrapper now checks owner first and marks closed only after successful delegate close. Foreign-close/owner-recovery regression passes. |
| R16-15 | Low | Transaction selection repeated txId/position/range/reverse-txId validation, then loaded RANGE again: eight index reads on a successful historical query. | Added one validated transaction-location result. UNIFIED resolves txId, position, and RANGE once in the same bound snapshot (three index reads), then independently validates BLOCK_MARKER before reader exposure. |
| R16-16 | Low | With both `historyBalanceLookup` and archive enabled, `AccountStore.put/delete` loaded the same previous account twice; a production private overload existed only to support reflective tests. | The canonical history hook and archive capture now share one raw-byte read while retaining prior exception ordering and byte semantics. The reflective test targets the real planner signature and the bridge overload was removed. |
| R16-17 | Low | Extending the external-point path to resolve and marker-check its RANGE dereferenced a null point before the reader factory could preserve its typed error contract. | `requireHistoricalPoint` now rejects null as `HISTORY_UNAVAILABLE` before index access. The UNIFIED service regression proves no NPE, repair marker, or lifecycle poison. |
| R16-18 | High | Reader-opening index and target-marker helpers classified every `ArchiveException` as persistent `CORRUPT_INDEX`; an ordinary RocksDB read failure could therefore write repair-required and permanently brick a healthy archive. | Opening storage failures now inspect the cause chain: RocksDB operation failures remain request-local `INTERNAL_IO`, while codec/shape/cross-row inconsistencies remain `CORRUPT_INDEX` and fail-stop. Both classifications and lease cleanup are tested. |
| R16-19 | Low | Every canonical VM `Program` field-initialized a default `ProgramTrace`, then its only constructor immediately replaced it with the configured trace. | The field is final and initialized exactly once by the constructor, removing one guaranteed short-lived object from every VM program creation. |
| R16-20 | Medium | The production runbook still claimed only three reviews, no High findings, and blanket unit/integration completion, contradicting the current sixteen-round record and open release gates. | Rewritten to state that High archive-on defects were found and fixed, distinguish current source regressions from production evidence, and keep all scale/fault/oracle gates explicit. |

## 6. Adversarial negative results

1. No additional ABBA cycle, lifecycle lease leak, or close/fatal/reorg race was confirmed after
   R16-01. The backend publication lock is currently retained as a cheap defensive boundary around
   its mutable staging allocator; removing it would weaken the backend API for negligible savings
   relative to RocksDB publication.
2. No proof-cache poison was confirmed. Failed scans clear validation capabilities, ACK revalidates
   persistent payload/proof bytes, and publish/delete revoke cached proofs.
3. Query RocksDB views use snapshot-bound `ReadOptions` with `fillCache=false`; reader memoization,
   VM overlays, and block-hash caches are request-local and cleared with their owner.
4. Query/request/transport thread-local scopes restore prior state and the final availability,
   deadline, canonical-epoch, and fatal-state seals prevent a stale response from escaping.
5. Repeated account writes still repeat asset-map comparison work (`O(W*A)` for unchanged maps and
   worse when representation changes). End-of-transaction aggregation was deliberately deferred:
   it requires retained-input accounting, system-phase coverage, exception-preserving `finally`
   behavior, and abort/replay tests. R16-03 bounds the work before that larger optimization.
6. RANGE-to-marker validation proves that independently stored publication metadata agrees inside
   one snapshot. It is not an external authenticated commitment and does not detect a coordinated
   rewrite of both rows; the independent-oracle/root runtime gate remains mandatory.

## 7. Verification gates

- Complete chainbase archive matrix, including AccountStore capture, unified corruption, lifecycle,
  publication, query-budget, snapshot-owner, and composite transaction-selector regressions: green.
- Complete actuator archive matrix and focused Program/VM trace-isolation regressions: green.
- Framework archive/JSON-RPC/historical-VM/lifecycle/reorg matrix: green.
- Forced `StorageConfigTest`, framework main/test checkstyle, and `git diff --check`: green.
- Two independent post-fix adversarial reviewers reported no confirmed source-level or active-doc
  defect. Round 17 added the residual direct RocksDB failure injection at the target marker read
  and proved that ordinary marker I/O remains request-local.

## 8. Runtime release gates

Source review cannot replace maximum-block RSS/JFR/native-memory measurements, sustained from-zero
sync, real filesystem fault injection, compaction/device-contention measurements, or an external
authenticated state commitment. Those Round 15 release gates remain open unless this round records
new runtime evidence.
