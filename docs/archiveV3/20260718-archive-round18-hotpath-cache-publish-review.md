# Archive round-18 hot-path, cache, publication, and recovery review

- Date: 2026-07-18
- Branch: `feat/archive-node`
- Review base: current uncommitted Round 10-17 worktree
- Layout: `UNIFIED_V1` only, schema 5, from-genesis public reads
- Status: SOURCE/TEST FIXES COMPLETE; RUNTIME RELEASE GATES REMAIN

## 1. Objective

Round 18 repeats the adversarial workflow across the boundaries most likely to let archive reads
affect block execution or let canonical execution expose a false historical result:

1. canonical block/index reads, shared caches, native deadlines, and physical I/O accounting;
2. fork replay, recovery rewind, journal rollback, primary-failure preservation, and lease release;
3. persistent publication ownership, direct maintenance APIs, repair evidence, and startup scrub;
4. historical VM overlay allocation, protobuf payload accounting, and request-local ownership;
5. production constructor/getter visibility and accidental composition of incompatible stores.

Three independent skeptic tracks reviewed fork recovery, query/cache/budget behavior, and persistent
publication/API boundaries. Every confirmed production-reachable finding was fixed and reviewed
again after the final patch.

## 2. Required invariants

1. A historical selector must not admit canonical block/index rows into the shared execution cache.
2. Every physical canonical root read is deadline-bounded where the native engine supports it;
   probe and materialization I/O are separately counted before the second allocation/read.
3. Query budgets are charged from actual providers and storage hooks, never duplicated by a service
   that guesses how many physical reads a provider performs.
4. Fork rewind must either advance the canonical head or throw. Missing/corrupt head data cannot be
   logged and retried forever while writer and mutation leases remain held.
5. Replay/recovery cleanup preserves the original `Throwable`, attempts the required abort or
   journal rollback exactly once, and cannot be derailed by metrics, logging, `getMessage()`, or
   suppression failures.
6. A production `UNIFIED_V1` service does not expose mutable capture, temporal, txNum, or backend
   collaborators. Persistent construction goes through the factory-owned composition.
7. Direct temporal maintenance cannot publish a subset of unified state. Any package-owned fault
   injection writes repair evidence atomically, and only validated startup recovery can clear it.
8. Historical VM overlay memory limits include serialized protobuf payloads before map insertion.
9. Archive-off canonical mutation bytes and exception ordering remain unchanged.

## 3. Confirmed findings and fixes

| ID | Severity | Confirmed finding | Fix and direct evidence |
|---|---|---|---|
| R18-01 | Medium | The public two-argument service constructor could accept a persistent temporal store without the matching unified index/backend, creating mixed ownership and non-atomic publication. | The constructor accepts only enabled in-memory composition. Persistent services must use the factory. A direct mixed-store construction regression rejects the configuration. |
| R18-02 | Medium | Production service getters exposed mutable capture/temporal/txNum collaborators, allowing in-process callers to bypass publication and lifecycle rules. | Unified production getters fail closed. Read-only first-block/range accessors cover lifecycle callers; package-private test access remains isolated to test bridges. |
| R18-03 | High | Public direct unified temporal maintenance could stage latest/history/changeset state separately from range, marker, and cursor publication. A self-consistent partial rewrite could evade an ordinary tail-only startup check. | Direct persistent temporal put/unwind operations are unsupported. Package-owned maintenance writes atomically persist `repair-required`; production factory sealing rejects them, and startup performs the full scrub before recovery may clear evidence. Oracle and direct-rejection tests are green. |
| R18-04 | Medium | Generic durable meta deletion could target repair evidence, and the index clear operation had no proof that startup validation had completed. | Generic meta deletion rejects the repair key. Clearing repair state requires an unforgeable package-owned `ArchiveRepairClearPermit`, held privately by the service and used only after successful startup recovery. |
| R18-05 | High | Fork replay/recovery handled selected checked/runtime failures but left ordinary `Error`, recovery-prelude failure, and cleanup-failure combinations able to skip restoration or replace the primary cause. | Replay/recovery now covers ordinary `Error`, preserves existing `TronError` identity/code, performs identity-safe best-effort cleanup, and treats logging/metrics as observational. Tests cover malicious `getMessage()`, same-object abort failure, recovery-prelude failure, and double-failure preservation. |
| R18-06 | High | Rewind loaded the canonical head inside a catch that logged `ItemNotFoundException`/`BadItemException` and returned. Both fork loops then retried the unchanged head indefinitely while holding manager, writer, and mutation locks. | Missing/corrupt canonical head data immediately raises `ARCHIVE_RUNTIME` fail-stop. A one-shot injected lookup proves the old retry behavior cannot recur and the original replay failure remains suppressed evidence. |
| R18-07 | Medium | Historical height/hash resolution used normal block/index reads, allowing untrusted queries to populate canonical execution caches and repeatedly materialize full block bodies during revalidation. | Block and block-index stores expose durable-root cacheless reads. Height resolves index then body, hash resolves body then index, and each revalidation rereads only the index ID. RocksDB and LevelDB disable cache admission; integration tests assert one body load and two ID validations. |
| R18-08 | Medium | Cacheless canonical RocksDB reads enforced Java limits only after native I/O returned. A stalled query could retain its permit/thread and compete for device bandwidth beyond its deadline. | Shared `ArchiveRocksReadOptions` propagates remaining query time into native deadline and I/O timeout for both unified snapshots and canonical cacheless reads. Native timeout classification remains typed and request-local. |
| R18-09 | Low | Values larger than the 64 KiB RocksDB probe caused a second native get that was not counted and allocated before a second-read budget check. | The second physical read is charged before allocation. A large-value regression observes two native reads, one shared native deadline, and two backend-read charges. |
| R18-10 | Low | The transaction-provider selector pre-recorded two canonical reads, then an attached cacheless provider recorded its own actual reads, producing false budget exhaustion. | Fixed provider guesses were removed. The attached provider accounts actual I/O; the regression records one provider read and observes no service-side double count. |
| R18-11 | Medium | Historical repository overlays reserved map/key overhead but not serialized account, contract, and contract-state protobuf payloads before retaining them. | Serialized sizes are reserved before each map insertion and values remain defensively copied. Oversized payload tests reject before retention. |
| R18-12 | Low | Genesis lifecycle tests depended on production mutable getters; replacing those calls with the public reader accidentally attempted to read intentionally `INTERNAL_ONLY` price-history keys. | Public/state assertions stay on `ArchiveStateReader`; a test-source-only package bridge performs the two internal capture assertions without widening the production reader or service API. |

## 4. Adversarial negative results

1. No production RPC, Spring bean, or bundled plugin exposes `UnifiedArchiveDb`, the backend, or a
   mutable unified service collaborator. The low-level raw write classes remain public Java API,
   but are not production-entry reachable. This is P3 encapsulation debt, not a current remote or
   configured-node exploit.
2. Code that can add arbitrary in-process bytecode or write the stopped archive directory can
   coordinate a valid-looking multi-row rewrite. Local checksums and startup scrub are not an
   authentication boundary against the directory owner; the external authenticated oracle gate is
   required to detect that class of rewrite.
3. Canonical revalidation no longer loads a full block body. Query rows use `fillCache=false`, and
   backend read/value-byte accounting is sourced at the real canonical storage boundary.
4. LevelDB also disables cache admission and charges returned bytes, but its Java API cannot probe
   a value length or cancel the native allocation before return. The configured canonical maximum
   row/block size bounds the value; runtime contention remains a release test item.
5. Query and execution still share the filesystem, OS page cache, device bandwidth, and RocksDB
   background work. Native deadlines bound cooperative RocksDB reads but cannot prove that every
   kernel/filesystem stall is interruptible.
6. No new archive-off write, consensus-validation, or canonical VM branch was introduced by the
   cacheless query path. Query context checks run only while an admitted historical query is
   attached.
7. Unified production publication remains one atomic cross-column-family batch. Maintenance and
   temporal direct-write paths cannot publish a reader-visible half state.

## 5. Performance conclusion

The source-level hot path is bounded, but production throughput is not yet measured:

- archive-off block execution performs no historical root read, query clock check, or cacheless
  lookup;
- archive-on canonical capture performs bounded previous-value work per changed key and uses
  admission/backpressure before retained block state grows;
- solidified publication is serialized and atomically batched; it can still create device and
  compaction contention, which requires sustained-sync measurements rather than another unit test;
- historical canonical validation performs constant point reads and one block-body load per
  resolution, with no shared block-cache admission;
- VM overlays, reader memoization, and decoded values are request-owned and byte-budgeted.

No source path found in this round permits a query to mutate canonical state, reuse query data in
block execution, or silently fall back from missing/corrupt archive state to latest state.

## 6. Verification evidence

- `:chainbase:test`: **705 tests**, green, including unified publication, journal, temporal oracle,
  reader, codec, query budget, repair capability, and archive-off invariants.
- focused actuator archive/VM matrix: **56 tests**, green, including repository overlay, historical
  hard failures, proof deadlines, contract-state capture, and VM actuator boundaries.
- focused framework archive/cross-layer matrix: **188 tests**, green in 1m29s, including restart,
  fork replay/recovery, shutdown/publication, JSON-RPC, dynamic-property reconstruction, canonical
  cacheless reads, native deadline propagation, and historical VM execution.
- focused `StorageConfigTest`: green, preserving archive defaults and query-limit parsing.
- `:framework:checkstyleMain`, `:framework:checkstyleTest`, compilation, and `git diff --check`:
  green. Chainbase and actuator do not define module-local checkstyle tasks.
- Fork/recovery post-fix reviewer: no additional exception swallowing, no-progress loop, lease
  leak, double rollback, or primary replacement found.
- Publication/API reviewer: the original public-API concern was downgraded from P1 to P3 after a
  production call-graph review found no current wiring/RPC/plugin reachability.

## 7. Remaining optimization and release gates

One optional source-level cleanup remains: introduce a production-write capability at the raw
unified DB boundary so future wiring cannot accidentally use the public low-level publication API.
It does not protect against a process/filesystem owner and is not a substitute for authenticated
state verification.

The production evidence gates remain open:

1. sustained from-zero sync with production heap/RSS/native-memory/JFR and RocksDB statistics;
2. real ENOSPC/EIO/permission/WAL/SST/MANIFEST corruption and restart matrices;
3. maximum admitted block/query payloads under compaction and shared-device contention;
4. long-running concurrent archive queries with deliberately stalled native calls;
5. external authenticated state oracle/root comparison, including coherent multi-row deletion;
6. restart, full-scrub, repair, and graceful-shutdown SLO measurements at production archive size.

No production-readiness claim is made by this source/test round alone.
