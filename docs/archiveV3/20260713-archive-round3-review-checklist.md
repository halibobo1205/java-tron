# Archive review — round-3 checklist (for codex)

**Base:** `feat/archive-node` @ `0958102b8d` (`feat(archive): harden lifecycle, recovery and queries`, 156 files, +19187/−1350).
**Provenance:** adversarial review (7-dimension finders → 3-lens verification → completeness critic, 50 agents). Test suites re-run green: chainbase `archive.*` **600/0**, framework historical eth_call/trace/StateRead + `JsonRpcServletTest` + genesis/shutdown lifecycle + framework checkstyle.

**Bottom line:** no confirmed high/critical. The commit is solid and **all six prior-round fixes survived** (critic verified — see §Verified sound). Two MEDIUM findings are worth acting on (only the JSON-RPC one has real runtime impact); the rest are LOW error-message / test-quality / doc-drift items. **One loud "regression" is a false alarm — do NOT revert it (§Do not change).**

---

## 1. [SHOULD-FIX — only item with real runtime impact] G1 — JSON-RPC global response-byte budget collapses to 2× per-response and is held through the blocking network write

**Severity:** medium · **Blast radius:** public-RPC-exposed nodes (archive JSON-RPC responses); graceful failure, not a crash.

**Where:** `framework/src/main/java/org/tron/core/services/jsonrpc/JsonRpcServlet.java`
- `PENDING_RESPONSES` is a **shared static singleton** (`:43`).
- `PendingResponseLimiter.open(perResponseLimit)` (`:653-660`) sets each reservation's `capacity = perResponseLimit * 2` when `perResponseLimit > 0`; the `DEFAULT_GLOBAL_CAPACITY_BYTES = 128MB` branch (`:651`) is therefore **dead code whenever a per-response limit is configured** (the normal case).
- `tryReserve` (`:662-678`) checks the **shared** `retainedBytes` against the **per-reservation** `capacity`, so the effective global budget is `2 × perResponseLimit` — i.e. ~2 full-size responses across all clients.
- The reservation is held through `writeRetainedResponse(resp, output)` (`:211`), a **blocking network write**.

**Mechanism:** two (or a few) slow/large clients each retaining ~`perResponseLimit` bytes exhaust the shared `2 × perResponseLimit` budget for the duration of their slow network drain → every other client's `tryReserve` returns false and its response fails.

**Fix:** decouple the global budget from the per-response limit — use a genuine global capacity (the intended 128MB, or a dedicated `jsonRpcMaxPendingResponseBytes` config) for `retainedBytes`, and keep the per-response cap separate. Ideally release the reservation once the response is fully buffered / after headers are written, rather than holding it across the drain. (Refute lens caveat: failures are graceful 5xx/empty, not crashes, and the trust-node model bounds exposure — so medium, not high.)

---

## 2. [OPTIONAL — diagnostic quality, no consensus impact] G2 — `switchFork` now catches `TronError`, runs switch-back recovery after a fatal error and masks the root cause

**Severity:** low-medium · **Blast radius:** archive-off consensus fork path (diagnostic only).

**Where:** `framework/src/main/java/org/tron/core/db/Manager.java:1552` and `:1607` (the two replay-loop multi-catches now include `| TronError e`; `exception` widened to `Throwable` at `:1511`).

**Mechanism (and why it's necessary + why it's only low-medium):** the `TronError` catch is *required* — `journalArchiveBlockOnlyOrFailStop` throws `TronError` and the catch must run the journal rollback. Side effect: a **non-archive** `TronError` (e.g. `SnapshotManager` flush → `TronError(DB_FLUSH)`, reachable **archive-off** via `revokingStore.buildSession()` at `:1516`) now also sets `exception`, so the `finally` (`:1562`) runs `eraseBlock()` + old-branch re-application, and on a second failure `archiveRuntimeError(...)` (`:1616`, thrown from the `finally`) **discards the original `TronError`** and surfaces `ARCHIVE_RUNTIME` even with archive disabled.
Mitigations (verified) that keep this low-medium, not a bug: `hitDown=true` after `DB_FLUSH` blocks all further disk flushes (recovery writes stay in-memory, discarded at exit → **no persistent canonical divergence**); the original error is logged with stack twice before masking; both codes exit 1 → **fail-stop end state unchanged**; and the finally-masks-in-flight pattern already existed for `RuntimeException`. The code's own `// todo process the exception carefully later` acknowledges it.

**Fix:** in both catches, after the journal bookkeeping, **rethrow a non-archive-origin `TronError` before setting `exception`** (or gate the switch-back on `archiveService.isEnabled()`); alternatively have the `finally`-block recovery `addSuppressed(original)` instead of discarding it.

---

## 3. [LOW — batch: error messages, test assertions, doc drift]

**Error messages (chainbase):**
- `ArchiveServiceFactory.java:111-112` — every identity-protocol failure surfaces as `"failed to create archive directory"`; propagate the real `ArchiveIdentityException` message.
- `PersistentArchiveTxNumIndex.java:88` — storage-schema v1 rejection lacks the design-doc-promised rebuild hint (delete-and-restart guidance), unlike the other fail-stop messages.
- `DefaultArchiveService.java:1893` (`lifecycleLease.start()`) — a mid-chain reader-open failure escapes `openResolvedReader` as a raw `ArchiveException`, unlike the wrapped/​mapped paths beside it.

**Test quality (chainbase):**
- `NoopArchiveServiceTest.java:669` and `:848` — two recovery-failure tests weakened their exception-message pins; restore the specific message asserts.
- `DefaultArchiveServiceIncrementalDifferentialTest.java:546-572` — the differential oracle is partially circular (expected values rebuilt from the SUT's own output); anchor at least the spot-checks to independently-computed values.
- `DefaultArchiveServiceAsyncPublisherTest.java` — no test for the "one block at a time" contract or the soft-watermark backpressure path; add one (with a latch/barrier, not sequential calls).

**Docs (`docs/archiveV3/20260713-archive-performance-hardening-plan.md`):**
- 3.3 references a `storage.archive.db.publishSync` config key that does not exist (`:150-154`); reconcile with `ArchiveRocksDbWriteOptions` + `reference.conf`.
- 3.1 "schema v2" naming / v1-detection wording drifts from the implemented schema-7 checksum (`ArchiveTemporalCodec.java:47-49`).

---

## Do NOT change — TOMBSTONE resolution (false-alarm "regression")

Multiple finders flagged `HistoricalArchiveVmDynamicProperties` as reverting round-2's "TOMBSTONE → per-flag config default" fix into fail-closed, calling it a regression. **This is a deliberate, correct improvement — leave it as codex wrote it.** The round-2 version resolved a tombstoned config-backed flag using the **current process** `CommonParameter` — which is silently WRONG if the node's config changed across restarts (replays the target block under the wrong VM rules). The correct behavior (implemented here): `resolveArchivedConfigDefault` **fails closed** on mid-chain (the archive cannot reconstruct the historical config default), while `saveGenesisArchiveVmProperties` (`DynamicPropertiesStore.java:1124`) seeds these keys with the deployment's config value at genesis, so a **genesis-complete** node always has them PRESENT and unaffected. The finders lacked this context; the refute-lens and several INFO verdicts correctly identified it as intentional.

---

## Verified sound — no action needed (critic + finders)

- **All six prior-round fixes survived** (critic PASS): #2 iterator `requireOk` after every archive RocksIterator seek/loop; #3 InMemory `unwindBlock` head guard incl. empty blocks; #4 `validateGenesisArchiveCoverage` runs after `reconcileInFlightOnStartup`; #5 in-flight buffer cap fail-stop + per-key read-through index; #6 Phase-1 `openReader` semantics (genesis-complete snapshots then releases the lock; mid-chain holds it).
- **Archive-off byte-identity** clean (`capturesStore` short-circuits on `engine==null`).
- **MSTORE8 half-length-hex trace crash FIXED** in this commit (`OpActions.addMemoryWrite` → `toHexPrefix` with size validation).
- **Inclusive-after read contract preserved** (`getAsOf` seeks `historyKey(key, txNum+1)`, C>T, in both the reusable-snapshot-iterator and fresh-iterator paths).
- snapshot-permit × async-publisher resource interaction is bounded.

## INFO (optional polish, no action required)

Snapshot-owning reader leak if `ManagedArchiveStateReader` ctor throws (`DefaultArchiveService.java:1914` — the lifecycle leak-canary from the item-1 design §8); `UnifiedArchiveDb` (UNIFIED_V1) shipped but unwired; genesis `commitToRoot` merges per-store roots non-atomically (first-boot crash window, `SnapshotManager.java:221`); `identity.adoptLegacy=true` forces a full scrub every startup; `BufferedResponseWrapper` new ctor / `getBufferedSize()` dead code; dead `inFlightBlocks.isEmpty()` guard in `unwindBlock` (`:1558`); `AssetUpdateHelper`/`MoveAbiHelper` leak store iterators (JNI handles) on early-return; async-publisher + query-concurrency + RocksDB-store internals are the largest untested surface; the 156-file single commit violates the doc's own commit-boundary principle (acknowledged in-doc).

---

## Codex remediation

- G1: introduced an independent process-wide `node.jsonrpc.maxPendingResponseBytes` budget
  (default 128 MB). `maxResponseSize` remains a per-response cap and no longer determines global
  capacity. Reservations intentionally remain held through a blocking network write because the
  backing response array is still retained until that write completes.
- G2: fork replay now runs archive journal cleanup for every `TronError`, but only
  `ARCHIVE_RUNTIME` enters switch-back. Other fatal codes such as `DB_FLUSH` are rethrown unchanged;
  a switch-back failure preserves the original failure as suppressed evidence.
- LOW: identity failures preserve their protocol reason; lifecycle start failures map to
  `ArchiveReaderException`; schema mismatch includes rebuild/resync guidance; recovery assertions,
  differential oracle, one-block-at-a-time publisher and soft-watermark tests were strengthened.
- Docs: removed the nonexistent `storage.archive.db.publishSync` key and aligned physical storage
  naming with temporal manifest `schema=7`.
- Deliberately unchanged: TOMBSTONE historical-config resolution and slow-network reservation
  lifetime.

---

## Follow-up (claude, 2026-07-14) — new docs + INFO status

- **Snapshot-leak canary: FIXED** (`fix(archive): close snapshot reader if managed-reader ctor fails`,
  commit `27c41ffc37`). The genesis-complete `openResolvedReader` path now `reader.close()`s in a catch
  if the `ManagedArchiveStateReader` ctor throws, so a wrapper-ctor failure can no longer leak the
  RocksDB snapshot. The INFO leak-canary item above is closed — no further action.
- **UNIFIED_V1 wiring now has a spec:** the `UnifiedArchiveDb ... shipped but unwired` INFO item is
  addressed by a full requirements doc → **`docs/archiveV3/20260714-unified-v1-wiring-requirements.md`**.
  It is codex-verifiable (every FR/INV/GATE cites `file:line`): FR-1..12 (layout config, factory branch,
  3 interface adapters, atomic-publish re-plumb, single-snapshot reader, UNIFIED identity payload,
  durability invariants, downgrade/mis-point guard, offline migrator, bridge), milestones M1–M6 with a
  gating order, and 6 open questions (OQ-1..6) codex must resolve. **Landing rule: M1–M2 may land
  off-by-default; activation is blocked until the downgrade matrix + migrator (digest parity, ≥3 real
  datasets) + soak/perf gates 8/9/10/3/7 pass** — none of that machinery exists yet.
- **From-0 production validation runbook:** **`docs/archiveV3/20260714-archive-from0-production-validation-runbook.md`**
  — the go/no-go gate for archive-ON from-0 sync (Stage A–D, §4 gates as pass/fail, fail-stop playbook).
- **Remaining INFO items left for codex** (intricate code you own; low priority): `BufferedResponseWrapper`
  dead ctor/`getBufferedSize()`; dead `inFlightBlocks.isEmpty()` guard in `unwindBlock` (`:1558`);
  `AssetUpdateHelper`/`MoveAbiHelper` iterator (JNI handle) leaks on early-return.
