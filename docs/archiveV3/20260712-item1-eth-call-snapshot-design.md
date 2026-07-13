# Design — decouple historical eth_call/trace from the archive write lock (item 1)

**Status:** ✅ Phase 1 IMPLEMENTED (5 steps, committed) · **Scope:** archive-nodes-only, read path.

> **Implemented (Phase 1, genesis-complete):** `ArchiveTemporalReadView` + `openReadView()` (RocksDB
> snapshot / InMemory copy) → reader reads via the view → `DefaultArchiveService.openReader(point)`
> branches genesis-complete (snapshot, lock released) vs mid-chain (lock held until close, via the
> reader's `onClose`) → the 3 adapters call `openReader`. **Simplification found during
> implementation:** for genesis-complete the whole read-through (in-flight + live) is gated on
> `firstArchivedBlock > 0`, so it never runs — a **temporal snapshot alone is complete**, and the
> in-flight snapshot (§4 item B) is NOT needed for Phase 1. Tests: view isolation (RocksDB+InMemory
> differential), genesis-complete lock-release (single- and multi-threaded), mid-chain
> commit-blocked-until-close. Phase 2 (mid-chain, needs a main-DB snapshot) remains future work.

## 1. Problem

A historical `eth_call` / `debug_trace*` / archive `eth_get*` runs the whole request while holding the
archive **read** lock:

```
// HistoricalEthCallSupport:86, HistoricalTraceSupport:104, ArchiveJsonRpcStateAdapter:65/85/104
try (ArchiveService.ReadGuard ignored = readGuard()) {
    ... resolve point, open reader, run the ENTIRE VM execution ...
}
```

`ArchiveService.acquireReadGuard()` takes `consistencyLock.readLock()`. Block application takes the
**write** lock via `commitBlock` → and crucially, `Manager.pushBlock` commits the archive **before**
the canonical session (`commitArchiveBlockOnlyOrFailStop` at `Manager.java:1180`, then
`tmpSession.commit()`), so a held archive read lock stalls the *entire* block-processing pipeline —
archive **and** main DB. That is what makes the read consistent today, and also what makes a long
eth_call stall consensus block commit. For a public-RPC archive node under heavy/adversarial call
load this is an availability problem (note: on a trust node the eth_call CPU limit is bypassed by
design, so a single call can run long).

**Goal:** run the VM execution *without* holding the write-blocking lock, against a consistent
point-in-time view captured cheaply under the lock.

## 2. What an eth_call actually reads (consistency domains)

| # | Source | Via | Frozen today by | Snapshot need |
|---|--------|-----|-----------------|---------------|
| A | archive **temporal** RocksDB | `ArchiveTemporalStore.getAsOf` / `latest` (the reader uses ONLY these two) | archive read lock | RocksDB snapshot |
| B | archive **in-flight** buffer (in-memory) | `readThroughInFlight` (now the per-key index) | archive read lock | immutable copy of in-flight blocks |
| C | **live main-DB** stores (account/code/storage/contract/dynprop) | `ChainBaseArchiveReadThrough` — **mid-chain only** (`canUseLiveReadThrough` needs `firstArchivedBlock > 0`) | transitively (pushBlock stalls) | main-DB snapshot — **hard** |
| D | block store / `latestStore` | `wallet.getBlockByNum`, `DynamicPropertiesStore` | immutable / not result-affecting for genesis-complete | none |

The reader's temporal surface is only **`getAsOf` + `latest`** — the iterator-heavy methods
(`hasHistory*`, `validate*`, `unwind`) are internal and are NOT on the read path. So A is a small,
snapshot-able surface. B is a bounded in-memory copy. **C is the hard part**: for a *mid-chain*
archive the read-through reads the live main DB, which is only consistent because the lock stalls
pushBlock. For a **genesis-complete** archive (`firstArchivedBlock == 0`) `canUseLiveReadThrough`
is false, so C never runs and A+B fully determine the result.

## 3. Scope decision (the key review question)

- **Phase 1 (recommended, this project): genesis-complete archives only.** Capture (A) a RocksDB
  temporal snapshot + (B) an immutable in-flight copy under the lock, release the lock, run the VM
  against that snapshot. Fully consistent because C never runs for genesis-complete. Mid-chain
  archives keep today's lock-held behaviour (correct, unchanged). A genesis-anchored full archive is
  exactly the deployment that serves public historical RPC, so this covers the important case.
- **Phase 2 (optional, larger, separate): mid-chain too.** Also snapshot the main DB (a RocksDB
  snapshot / revoking view shared by `ChainBaseArchiveReadThrough`). Much bigger blast radius (main
  DB, not archive-owned) and higher risk; deferred unless mid-chain public-RPC nodes need it.

The rest of this doc specifies Phase 1.

## 4. Object model (Phase 1)

New, in `org.tron.core.archive.temporal`:

```java
public interface ArchiveTemporalReadView extends AutoCloseable {
  Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum);
  Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey);
  @Override void close();               // releases the RocksDB snapshot; no-op for a copy
}

interface ArchiveTemporalStore {        // add:
  ArchiveTemporalReadView openReadView();
}
```

- **RocksDb impl:** `Snapshot s = db.getSnapshot(); ReadOptions ro = new ReadOptions().setSnapshot(s);`
  Refactor the existing `getAsOf` / `latest` to a private form parameterised by `ReadOptions` (live =
  a shared default RO; view = `ro`), so **no logic is duplicated** — the live methods and the view
  both call the same seek/get code. `close()` → `db.releaseSnapshot(s); ro.close();`.
- **InMemory impl:** return a view over a **deep copy** of `byDomain` taken at `openReadView()` time
  (test-only store; copy cost irrelevant, correctness under concurrent mutation preserved).

In-flight snapshot (B): `List<ArchiveInFlightBlock> inFlightSnapshot = List.copyOf(inFlightBlocks.values())`
captured under the read lock. `ArchiveInFlightBlock` is immutable after commit, so this shallow copy
is a valid isolated snapshot. The snapshot-bound read-through builds a per-key `NavigableMap<txNum,
prevValue>` index from this list **once** (O(N) build, O(log) per read — matching item 3's live index).

## 5. API + flow

Single entry point that hides the branch, so the 3 adapters change uniformly:

```java
// DefaultArchiveService
public ArchiveStateReader openReader(ArchiveStatePoint point) throws ArchiveReaderException {
  readLock.lock();
  try {
    validateAvailable();
    if (canSnapshotReads()) {                       // genesis-complete (firstArchivedBlock == 0)
      ArchiveTemporalReadView view = temporalStore.openReadView();
      List<ArchiveInFlightBlock> inFlight = List.copyOf(inFlightBlocks.values());
      // reader bound to (view, inFlight, point); its close() closes `view`. LOCK RELEASED below.
      return snapshotReader(point, view, inFlight);
    }
    // mid-chain: keep the current semantics — reader holds the read lock until closed.
    return lockHoldingReader(point, readLock);      // close() unlocks
  } catch (RuntimeException e) {
    readLock.unlock();                              // only on the snapshot/validate failure path
    throw e;
  } finally {
    if (snapshotPathTaken) readLock.unlock();       // released right after capture
  }
}
```

Adapters (all three) collapse to:

```java
// resolve point / fetch immutable block first (no archive lock needed)
try (ArchiveStateReader reader = archiveService.openReader(point)) {
  ... run the VM / read balance ...                 // genesis-complete: NO write-blocking lock held
}
```

`ArchiveStateReader.close()` (genesis-complete path) closes the temporal read-view → releases the
RocksDB snapshot. The `ReadGuard` API is retained for the mid-chain path (or folded into the
lock-holding reader). Snapshot lifetime == reader lifetime == the try-with-resources block, so a
leaked snapshot (which would pin SST files) is structurally impossible as long as callers use
try-with-resources (they do).

## 6. Implementation steps (each independently compilable + testable)

1. **`ArchiveTemporalReadView` + `openReadView()`** on the interface; RocksDb impl (refactor
   getAsOf/latest to a ReadOptions-parameterised private form; add snapshot view); InMemory impl
   (deep-copy view). Unit test: view is isolated from post-snapshot writes (differential vs live).
2. **Snapshot-bound read-through + reader wiring:** a read-through backed by the immutable in-flight
   list (builds the per-key index once); `snapshotReader(point, view, inFlight)` factory path.
3. **`DefaultArchiveService.openReader(point)`** with the genesis-complete/mid-chain branch; keep
   `acquireReadGuard`/`ReadGuard` for the mid-chain path.
4. **Adapters** (`HistoricalEthCallSupport`, `HistoricalTraceSupport`, `ArchiveJsonRpcStateAdapter`):
   replace `readGuard() + factory.open()` with `archiveService.openReader(point)`.
5. **Snapshot lifecycle hardening:** ensure `close()` releases on every path incl. VM exception;
   a metric/counter for open snapshots (leak canary).

## 7. Test plan

- **Isolation (unit):** open a read-view, then commit/publish/unwind on the store; the view still
  returns the pre-snapshot values. RocksDb + InMemory differential.
- **Concurrency (the point):** thread A opens a genesis-complete reader and blocks mid-read; thread B
  commits + publishes several blocks; assert (a) B does not block on A (no write-lock contention),
  and (b) A's reads are the consistent pre-snapshot values. Contrast with a mid-chain reader, where B
  *does* wait (lock-held path).
- **Snapshot leak:** N open/close cycles leave 0 open snapshots (metric) and do not grow pinned SSTs.
- **Regression:** the full historical eth_call / trace integration suites are byte-identical results.

## 8. Risks / open decisions

1. **Correctness of releasing the lock (highest):** relies on the claim that for genesis-complete,
   A+B fully determine the result and C/D never affect it. Verified from code (canUseLiveReadThrough
   requires `firstArchivedBlock > 0`; the VM reads only via the archive reader — asset/delegation/etc.
   are explicitly unsupported, not live-read). **Review focus:** confirm no genesis-complete read path
   touches a live mutable store.
2. **RocksDB snapshot cost:** snapshots pin SST files from compaction while held. Long-running
   eth_calls hold snapshots longer → some space amplification. Bounded by call duration + a max-open
   guard if needed.
3. **Scope:** ship Phase 1 (genesis-complete) only? Or invest in Phase 2 (main-DB snapshot for
   mid-chain)? **Recommend Phase 1 now**, revisit Phase 2 if a mid-chain public-RPC deployment needs it.
4. **InMemory deep-copy** per view is wasteful but test-only; acceptable. (Alternative: version the
   InMemory store — more code, no production value.)

## 9. Recommendation

Proceed with **Phase 1** in the 5 steps above. It removes the write-lock stall for the deployment
that matters (genesis-anchored public archive RPC), is provably consistent (C never runs), and leaves
mid-chain on today's correct lock-held path. Phase 2 is a separate, larger effort.

## 10. Phase 2 — assessed and DECLINED (2026-07-12)

Investigated the main-DB read path for Phase 2. The mid-chain read-through reads ~7 live stores
through TRON's `db2` revoking layer (`Chainbase.get → head() → SnapshotRoot → raw DB`); there is **no
external read-snapshot API** (`getFromRoot` is still live). Delivering a lock-free mid-chain view
would require **adding a snapshot read path into the consensus-critical `db2` MVCC layer** and
capturing all ~7 stores atomically under the archive lock — a large, hard-to-verify change to code
used by all block application. Weighed against a **bounded benefit** (mid-chain public-RPC is less
common than a genesis-anchored full archive; on a trust node the eth_call CPU is bypassed by design),
the risk/reward is poor. **Decision: do not implement Phase 2.** Mid-chain keeps today's correct
lock-held path (preserved by Phase 1). Revisit only if a concrete mid-chain public-RPC node is
demonstrably stalled.
