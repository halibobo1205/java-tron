# UNIFIED_V1 archive layout — wiring requirements (codex-verifiable)

**Base:** `feat/archive-node` @ `daae901b7c`.
**Author:** claude (recon-grounded; every requirement cites `file:line` so codex can verify implementation against it).
**Status (2026-07-14):** M1-M2 are implemented in the working tree: explicit layout selection,
identity binding, typed adapters, one-batch publication, shared-snapshot reads, and unified startup
validation are wired into `ArchiveServiceFactory`/`DefaultArchiveService`. UNIFIED_V1 remains
off-by-default while fault/soak gates are completed.

**Deployment scope decision:** this is a greenfield implementation. No previous archive binary or
deployed LEGACY dataset exists, so an offline migrator and previous-binary downgrade matrix are not
current delivery requirements. They become mandatory again before any real LEGACY dataset is
created or an older archive-aware binary is released.

**Review outcome (round-4, claude, 2026-07-14):** adversarial review of the M1-M2 commit
(`bcd128fb3a`, 35 agents: 7 finders → 3-lens verify → completeness critic). Verdict: **structurally
sound; GO to keep off-by-default, NO-GO for activation.** One real HIGH correctness bug was found,
independently source-verified, and **fixed** — `fix(archive): fold UNIFIED block digest in
changeset-key order` (`301dfd9991`): `UnifiedArchiveTemporalStore.prepare()` folded the block-commit
digest in `RECORD_ORDER` (canonicalKey lex-then-length) while `readBlockRows()` recomputes in
physical CHANGESET order (keyLen-then-canonicalKey), so any block with ≥2 changes in one
(txNum, domain) where the shorter key is lex-greater fail-stopped `validateCommittedBlock` /
`unwindBlock` / startup validation and broke LEGACY↔UNIFIED marker parity. Now mirrors
`RocksDbArchiveTemporalStore.putBlockChanges` (`BY_CHANGESET_KEY` sort); regression test
`commitMarkerMatchesForMultipleVariableLengthKeysInOneTx` added (proven to fail without the fix).

The remaining review findings are all **test-coverage gaps = the M3 activation-gate deliverables
below**, not code defects. Verified sound (no action): atomic single-batch publish incl.
cursor/marker/journal-delete; WAL forced-sync on every new write path; `resumeEmptyManifestIfMissing`
won't clobber existing rows; single-snapshot reader; legacy path + archive-off untouched.

**M3 must-do before activation (mapped to the gates in this doc):**
- TEST-2 — LEGACY↔UNIFIED **differential parity** (would have caught the digest bug): missing.
- TEST-1 — run the 3 unified adapters through the **legacy store test suites** (sole coverage today
  is self-referential round-trip).
- TEST-3 / gate 8 — real **kill / ENOSPC / WAL-rotation / CF-flush** injection (only a synchronous
  batch-write exception is injected today); non-negotiable.
- FR-7 — **corrupt-CF scrub tests** for BLOCK_MARKER / position-coverage / orphan-HISTORY (only INDEX
  + COMMITMENT are covered).
- TEST-4 / FR-9 — **cross-layout mis-point fail-closed** assertion. NOTE: under the greenfield scope
  the migrator + previous-binary downgrade matrix are deferred, but this test is cheap (the guard
  already exists and fails closed) and worth adding now.

**Completeness probes no finder covered (do before flipping the default):** schema-checksum
equivalence (OQ-6); concurrent-reader-vs-publish MVCC/sequence isolation under a real snapshot;
the actual ENOSPC-mid-`publishBlockAtomically` fail path (markFatal, not half-published); txnum
position-coverage / txId-uniqueness / append-only decode vs an independent oracle; metrics/counter
parity with the LEGACY backend; a direct unwind-correctness test. INFO-only: `SnapshotView.getAsOf`
seeks before its owner-thread check (asymmetry with Rocks) — defensive nit, follow-up.

> Scope note / recommendation: this is a **multi-milestone effort**, not a quick switch. The perf-hardening plan
> (`20260713-archive-performance-hardening-plan.md` §10) deliberately keeps UNIFIED_V1 *outside* the factory
> until bridge/migrator/downgrade-matrix/soak land. This doc is the spec for that whole body of work, structured
> as milestones (M1–M6) so it can be scoped and gated. **Off-by-default and never auto-migrating** are hard
> requirements, not options.

---

## 0. Current state (ground truth)

**What exists and is DONE (the physical primitive):**
- `UnifiedArchiveDb` — one RocksDB, 8 column families (`META, INFLIGHT, INDEX, LATEST, HISTORY, CHANGESET, BLOCK_MARKER, COMMITMENT` — `unified/UnifiedArchiveColumnFamily.java:8-15`). Public API: `initialize(Path, checksum)` / `open(Path, checksum)` (`UnifiedArchiveDb.java:66,83`), `putJournalDurably` (`:104`), `deleteJournalDurably` (`:128`), `putMetaDurably` (`:145`), `publishBlockAtomically(UnifiedArchivePublish, publishSync)` (`:167`), `openReadView()→UnifiedArchiveReadView` (`:194`), `close()` (`:222`, refuses while a read view is active).
- `UnifiedArchivePublish` (Builder) — one atomic block description: exactly one journal compare-and-delete, one reserved-key cursor row, one block-marker, ≥1 INDEX row, arbitrary puts restricted to `INDEX/LATEST/HISTORY/CHANGESET/COMMITMENT` (`UnifiedArchivePublish.java:71,80-90,93,117-149`).
- `UnifiedArchiveReadView` — single RocksDB snapshot across all CFs, single-owner-thread: `get(cf,key)`, `newIterator(cf)`, `getSequenceNumber()`, `close()` (`UnifiedArchiveReadView.java:38-82`).
- `UnifiedArchiveManifest` — on-disk identity value `layout=UNIFIED_V1|layout-schema=1|column-families=…|archive-schema=<hex 32B>` + reserved `published-cursor` key + `validate()` (`UnifiedArchiveManifest.java:12-63`).
- WAL rule already enforced in code: `createWriteOptions(sync) = setDisableWAL(false).setSync(sync)` (`UnifiedArchiveDb.java:235`).

**What is MISSING (the semantic + integration layer this spec covers):**
- The primitive treats every key/value as **opaque bytes**. None of the store *semantics* exist on it: txNum allocation & block-range index, temporal `getAsOf`/`latest`/`unwind`, journal replay/`loadBlocks`, and all codecs (`ArchiveTemporalCodec`, block-range codec, in-flight codec).
- **Zero production references** to `org.tron.core.archive.unified` outside its own package + `UnifiedArchiveDbTest` (grep-confirmed). The factory unconditionally builds the legacy split trio (`ArchiveServiceFactory.java:117-161`).
- No layout selection: `ArchiveServiceFactory` has only `LEGACY_LAYOUT="LEGACY_V1"` (`:49`), no `UNIFIED_V1`/`AUTO` constant, no layout config key (`StorageConfig.DbConfig` has only `directory`+`fullScrubOnStartup`, `:235-239`).
- No `UnifiedArchiveIdentityPayload`; only `LegacyArchiveIdentityPayload` is wired (`ArchiveServiceFactory.java:199,217,231`).
- No migrator, bridge, downgrade-guard, `AUTO` token, or `MIGRATING` identity state (`ArchiveIdentityState = {PREPARED,BOUND,ACTIVE}`, `identity/ArchiveIdentityState.java:5-7`). No old-binary/mis-point integration test.

**Good news that lowers cost:** the service/reader talk to stores **only through interfaces** (`DefaultArchiveService.java:79-85` fields are `ArchiveTxNumIndex`/`ArchiveTemporalStore`/`ArchiveInFlightStore`; reader depends on `ArchiveTemporalReadView` — `DefaultArchiveStateReader.java:44`). The identity `layout` field is already a **free-form string** (`ArchiveIdentityClaim.java:16-40`), so identity persistence needs no format change — only the factory's hardcoded `expectedLayout` gate rejects UNIFIED today (`ArchiveIdentityProtocol.java:316`).

---

## 1. Goals & non-goals

**Goals (this body of work):**
- G1. Make UNIFIED_V1 an **openable, correct** archive backend, selectable by an **explicit, off-by-default** config, with **byte/logical parity** to the LEGACY archive for identical block input.
- G2. Deliver the **atomic publish** benefit: index+temporal+marker+cursor+journal-delete in one WriteBatch, removing the legacy compensating-unwind window.
- G3. Deliver the **single-snapshot read** guarantee: point/index/temporal resolved from one `UnifiedArchiveReadView`.
- G4. Deliver the **safety machinery** the plan requires before any activation: downgrade/mis-point fail-closed, offline migrator with digest parity, crash/ENOSPC recovery-or-failstop.

**Non-goals (explicitly out of scope for the wiring milestone):**
- N1. **Default-unified.** UNIFIED stays off by default; flipping the default is a *later* decision gated on soak (`plan §10:544-545`).
- N2. **Auto-migration of existing archives.** Existing LEGACY archives are **never** auto-migrated (`plan:722`); migration is an explicit offline tool.
- N3. **Auto-interpreting an empty dir as a UNIFIED new deployment** (`plan:542-543`). New UNIFIED creation must be an explicit one-time opt-in, mirroring `identity.initialize`.
- N4. **Cross-DB 2PC** between canonical and archive — single-DB atomicity does not claim to solve it (`plan:568-569`).
- N5. Changing archive coverage/semantics — still `TVM_STATE_ONLY` P0; `COMMITMENT` CF stays unwritten/rejected (`ArchiveServiceFactory.java:354`).

---

## 2. Functional requirements

Each FR is written so codex can check an implementation against it. **"Verify"** = the concrete artifact/behavior that proves it.

### FR-1 — Explicit layout selection (no silent AUTO)
Add a layout config key (proposed `storage.archive.db.layout`, enum `LEGACY_V1 | UNIFIED_V1 | AUTO`, default `LEGACY_V1`) to `reference.conf` + `StorageConfig.DbConfig` + thread through `DefaultConfig.archiveService`. `AUTO` must **never** interpret an empty directory as a new UNIFIED deployment (N3); it may only adopt an *already-registered* on-disk layout by reading the authoritative signal (see OQ-2).
**Verify:** new key present with default `LEGACY_V1`; a fresh empty dir + `AUTO` does **not** create a UNIFIED store (test); unchanged configs still open LEGACY byte-identically.

### FR-2 — Factory branch to open UNIFIED
`ArchiveServiceFactory.create(...)` gains a UNIFIED branch that opens **one** `UnifiedArchiveDb.open/initialize` instead of the three RocksDb stores (`:117-140`), builds the three adapters (FR-3), and replaces the legacy preflight `validateArchiveRootBeforeOpen`/`requireLegacyDirectory(temporal/index/inflight)` (`:262-307`) with a UNIFIED manifest/CF-shape preflight.
**Verify:** with `layout=UNIFIED_V1`, the node constructs `DefaultArchiveService` over unified-backed adapters and opens/closes cleanly; with `layout=LEGACY_V1`, code path is unchanged (diff shows legacy branch untouched at runtime).

### FR-3 — Three interface adapters over `UnifiedArchiveDb`
Provide implementations of the exact existing interfaces so the service/reader are **unmodified structurally**:
- **`UnifiedArchiveTemporalStore implements ArchiveTemporalStore`** over `LATEST/HISTORY/CHANGESET/BLOCK_MARKER` CFs. Its `openReadView()` must return an **`ArchiveTemporalReadView` adapter** wrapping `UnifiedArchiveReadView`, implementing typed `getAsOf(domain,key,txNum)` / `latest(domain,key)` / `getAsOfBackendReadCost` / `close` (`ArchiveTemporalReadView.java:14-56`). **The Erigon prev-value byte layout / `getAsOf` seek logic must be shared** with `RocksDbArchiveTemporalStore` (extract to a common helper), not re-implemented divergently.
- **`UnifiedArchiveInFlightStore implements ArchiveInFlightStore`** over `putJournalDurably`/`deleteJournalDurably` + INFLIGHT-CF iteration for `loadBlocks`/`forEachBlock`/`acknowledgeBlock`; `usableSpaceBytes` from the unified DB path (`ArchiveInFlightStore.java:10-54`).
- **`UnifiedArchiveTxNumIndex implements ArchiveTxNumIndex`** over the INDEX CF + META cursor, replacing `PersistentArchiveTxNumIndex`/`RocksDbArchiveBlockRangeStore`. Its `commitBlock`/`unwindBlock` must **contribute to the atomic publish** (FR-4), not write the cursor independently (`PersistentArchiveTxNumIndex.java:109` today writes `commitRange` on its own).
**Verify:** each adapter passes the *same* unit-test suites the legacy stores pass (parameterize existing tests over both impls), plus the differential parity test (TEST-2).

### FR-4 — Atomic publish re-plumb (core mismatch)
Re-plumb the publish path so a UNIFIED-backed service builds **one** `UnifiedArchivePublish` (journal compare-and-delete + reserved cursor + block marker + `INDEX/LATEST/HISTORY/CHANGESET` mutations) and calls `publishBlockAtomically` **once**, replacing the legacy three-staged writes + compensating unwind (`DefaultArchiveService.java:1044-1071`). The published cursor moves into the atomic batch (reserved META key, `UnifiedArchivePublish.java:85`), so the legacy "journal delete may lag / be tolerated non-fatally" model (`DefaultArchiveService.java:1074-1087`) is replaced by "journal delete rides the same batch."
**Verify:** publish is atomic under crash injection (TEST-3) — no state shows index/temporal committed while journal survives *or* cursor advanced without index; the compensating-unwind branch is provably dead on the UNIFIED path.

### FR-5 — Single-snapshot reader
Under UNIFIED_V1, point (meta/index) **and** temporal rows must be resolved from **one** `UnifiedArchiveReadView` snapshot — not "read live index, then open a temporal snapshot" (`plan:561-562,182`). Thread one read view through both point/coverage validation and temporal reads in the reader factory (`DefaultArchiveStateReaderFactory.java:86-142` currently opens temporal snapshot at `:116` and reads `txNumIndex.getBlockRange/getFirstArchivedBlock` separately at `:142,:87`).
**Verify:** a concurrent publish during an open read view cannot make the reader observe a half-updated point vs temporal (test with a barrier); `getSequenceNumber()` of the view bounds all reads.

### FR-6 — Identity for UNIFIED_V1
- Pass `UNIFIED_V1` (not `LEGACY_LAYOUT`) to the identity protocol calls (`ArchiveServiceFactory.java:206,210,212,240,244,246`) on the UNIFIED branch; keep the crash-resumable `PREPARED→BOUND→ACTIVE` protocol unchanged (identity `layout` is already a free-form string — no codec change, `ArchiveIdentityClaim.java`, `ArchiveIdentityCodec.java`).
- Add **`UnifiedArchiveIdentityPayload implements ArchiveIdentityPayload`** whose `bindAndSync`/`verifyForActivation`/`inspectFloor` open the UNIFIED DB + validate the manifest/CF shape + floor, replacing `LegacyArchiveIdentityPayload.verifyLegacyLayout`'s temporal/index/inflight checks (`LegacyArchiveIdentityPayload.java:39-163`).
- New UNIFIED creation is a one-time explicit opt-in (mirror `identity.initialize`; N3). Resume on a `UNIFIED_V1`-labeled active identity must load the UNIFIED payload; a `LEGACY_V1` label must never load the UNIFIED store, and vice-versa.
**Verify:** cross-layout mis-point (LEGACY dir opened as UNIFIED or reverse) fails closed at identity validation (TEST-4); crash between PREPARED/BOUND/ACTIVE resumes correctly (existing identity tests, re-run over the unified payload).

### FR-7 — Startup validation ported to intra-DB invariants
The legacy `startupValidator` cross-store checks — `validateStartupTail`, `validateCommitMarkersCovered`, `validateCommittedBlock`, `validateTxNumsCovered`, `validateDomainRows`, contiguous position coverage (`ArchiveServiceFactory.java:146-157`) — must be re-expressed as **intra-DB** invariants read from the UNIFIED snapshot/CF scans, preserving the same fail-stop outcomes.
**Verify:** for each legacy startup check there is a UNIFIED equivalent with a test that corrupts the relevant CF and asserts the same fail-stop.

### FR-8 — Durability invariants preserved (non-negotiable)
- **WAL forced on:** all UNIFIED WriteOptions `disableWAL=false`, non-configurable; `publishSync=false` omits only the fsync, never disables WAL (`plan:564-566`; code `UnifiedArchiveDb.java:235`). No config key may turn WAL off.
- **Journal atomicity:** journal delete rides the same cross-CF WriteBatch as the published cursor, or occurs only after provable publish; a failed forced-sync step **retains the journal for reconcile** (`plan:560-561,157`).
- **PUBLISHED-rollback is FATAL:** rolling back a PUBLISHED block is ordering corruption → immediate FATAL, never idempotent success (`plan:137,140`). Canonical height/hash is authoritative at startup; journals past canonical head roll back.
- **Fail-stop, not silent-skip**, on every archive error (existing binding decision).
**Verify:** fault-injection tests (TEST-3) cover per-CF flush/compaction, WAL rotation, and before/after the published batch, proving "batch lost ⇒ already-synced journal reappears and replays" (`plan:565-566`).

### FR-9 — Downgrade / mis-point guard (fail-closed), proven by integration test
The **real previous-version binary** opening a UNIFIED_V1 directory must fail **explicitly** (not silently corrupt), and a UNIFIED-aware binary pointed at the wrong layout must fail closed (`plan:539,587; §4 gate 10:345`).
**Verify:** an integration test with the designated previous release tag (OQ-5) asserts explicit open-failure; a UNIFIED binary opening a LEGACY dir (and reverse) asserts fail-closed. **This test does not exist today and is a required deliverable.**

### FR-10 — Offline migrator LEGACY_V1 → UNIFIED_V1 (separate tool)
A standalone offline migrator that reads a LEGACY archive and produces a UNIFIED archive with **digest parity**: source digest unchanged; target all-CF digests and historical query results identical (`§4 gate 9:344`). Blue-green migration semantics per `plan:571-616`; resolve whether it needs a persisted `MIGRATING` identity state (OQ-3).
**Verify:** migrator run on **≥3 real-scale legacy datasets** → target passes the differential parity test (TEST-2) at sampled historical points; source is read-only/untouched.

### FR-11 — Bridge recognition (scope decision required)
The plan requires a **bridge release** that recognizes `LEGACY_V1 / UNIFIED_V1 / AUTO` to ship *before* UNIFIED activation (`plan:538`). Decide whether the bridge recognition logic is part of this milestone (recommended: yes, since FR-1/FR-6 already add the tokens) or a separate release train.
**Verify:** an older-but-bridge-aware binary correctly identifies an on-disk UNIFIED archive and refuses/defers rather than mishandling it.

### FR-12 — Config surface honesty
Do **not** surface `publishSync` as a supported config key unless this spec defines it and its default; the plan explicitly forbids presenting the prototype param as a config key (`plan:154-156`). Any new keys must land in both `reference.conf` and `config.conf` with accurate comments (units, propagation).
**Verify:** config docs match code; no dead/undocumented key; `./gradlew lint` clean.

---

## 3. Correctness invariants (must hold; independently verifiable)

- **INV-1 Archive-data parity.** For an identical sequence of blocks, the UNIFIED archive's logical content (per-CF, decoded) equals the LEGACY archive's (index/txnum, latest/history/changeset, markers). Proven by digest equality (TEST-2, gate 9).
- **INV-2 Inclusive-after read contract.** The UNIFIED temporal adapter's `getAsOf` seeks `historyKey(key, txNum+1)` (C>T) and falls to `latest`, identical to `RocksDbArchiveTemporalStore` — shared helper (FR-3).
- **INV-3 Atomic publish visibility.** index+temporal+marker+cursor become visible at one sequence; the journal disappears in the same batch; no interleaving exposes a partial publish (FR-4, TEST-3).
- **INV-4 Fail-stop preserved.** Every archive error still halts the node; no new silent-skip path (FR-8).
- **INV-5 Archive-off untouched.** With `archive.enable=false` the UNIFIED code path is never constructed; byte-identity to a stock node is preserved (`capturesStore` short-circuit unaffected).
- **INV-6 No auto-migrate / no empty-dir-as-unified.** Existing LEGACY archives never auto-convert; empty dir + AUTO never births a UNIFIED store (N2, N3).

---

## 4. Acceptance gates (pass/fail — from `plan §4`)

| Gate | Requirement | Blocker if… |
|------|-------------|-------------|
| **8** | SIGKILL/ENOSPC in any journal / published-batch / marker / migration-rename window → **full recovery OR explicit fail-stop only** (`:342`) | any half-published or silently-corrupt outcome |
| **9** | Unified migration validated on **≥3 real-scale legacy datasets**; source digest unchanged; target all-CF digests + historical queries consistent (`:344`) | any digest/query mismatch |
| **10** | **Real previous-version binary** startup/rollback/mis-point drills pass before default-enabling (`:345`) | old binary opens UNIFIED without explicit failure |
| **3** | archive-ON block-push p95/p99 regression ≤ 5% vs pre-opt (`:334`) | > 5% |
| **7** | 72h soak retained-heap slope ≤ 0.5%/hour (`:340`) | monotonic growth |

Gates 3/7 currently **unrun** for UNIFIED (`plan:886`); they gate *activation*, not the code landing off-by-default.

---

## 5. Test requirements (codex must deliver green)

- **TEST-1 Adapter conformance.** Parameterize the existing temporal/inflight/txnum store test suites over both LEGACY and UNIFIED adapters; both pass.
- **TEST-2 Differential parity.** Drive the *same* op sequence through a LEGACY service and a UNIFIED service; assert (a) all-CF logical digest equality and (b) `getAsOf`/`latest`/historical `eth_call`/trace equality at sampled points (SSTORE-heavy contract, delete-recreate account, TRC10 asset, block before a fork-flag activation). Anchor at least the spot-checks to independently-computed values (not the SUT's own output).
- **TEST-3 Crash/ENOSPC injection.** SIGKILL/ENOSPC in each durability window (journal put, published batch pre/post, marker, WAL rotation, CF flush/compaction) → recover-or-failstop; "batch lost ⇒ synced journal replays."
- **TEST-4 Downgrade / mis-point integration.** Real previous release tag opens UNIFIED dir → explicit failure; cross-layout mis-point → fail-closed (FR-9, gate 10).
- **TEST-5 Migrator parity.** ≥3 real-scale datasets → target passes TEST-2 (FR-10, gate 9).
- **TEST-6 Reader shared-snapshot consistency.** Concurrent publish during an open UNIFIED read view never exposes a partial point/temporal (FR-5).

---

## 6. Milestones (gating order)

1. **M1 — COMPLETE.** Config + factory branch + identity (FR-1, FR-2, FR-6, FR-12), off-by-default.
2. **M2 — COMPLETE.** Typed adapters + atomic publish + single-snapshot reader + full-scrub fail-stop checks (FR-3, FR-4, FR-5, FR-7, FR-8).
3. **M3 — IN PROGRESS.** Batch-failure atomicity, restart, service E2E, corruption, and concurrent-snapshot tests exist; process-crash/ENOSPC and broader differential runs remain.
4. **M4 — NOT CURRENTLY APPLICABLE.** There is no previous archive-aware binary. Current-binary cross-layout mis-point rejection remains required.
5. **M5 — NOT CURRENTLY APPLICABLE.** There is no deployed LEGACY dataset to migrate.
6. **M6 — PENDING.** Soak + perf (gates 3/7) remain required before changing the default.

**Landing rule:** M1–M2 may land **off-by-default**; **activation** (any non-LEGACY default, or documenting UNIFIED as production-ready) is blocked until M3–M6 pass.

---

## 7. Open questions codex must resolve (decide + record in the doc)

- **OQ-1 RESOLVED.** `UnifiedArchiveBackend` coordinates a dedicated service publish branch; adapters stage rows into one `UnifiedArchivePublish` rather than writing independently.
- **OQ-2 RESOLVED.** The ACTIVE root `archive.identity` selects AUTO. The selected payload must then pass the UNIFIED manifest/CF validation; AUTO never classifies an empty directory.
- **OQ-3 DEFERRED/NOT APPLICABLE.** No migration exists in the greenfield scope, so no `MIGRATING` state is introduced.
- **OQ-4 RESOLVED FOR M1-M2.** No `publishSync` setting is exposed. Production publication is forced-sync and WAL is always enabled.
- **OQ-5 DEFERRED/NOT APPLICABLE.** No previous archive-aware binary exists. Record the first released archive binary before reviving this gate.
- **OQ-6 RESOLVED.** Factory and identity payload pass the same `ArchiveSchemaChecksum.of(registry, catalog)` bytes to `UnifiedArchiveDb` manifest validation.

---

## 8. Definition of done (checklist)

- [ ] FR-1..FR-8 implemented; LEGACY path byte-identical when `layout=LEGACY_V1` (INV-5).
- [ ] INV-1..INV-6 hold, each with a test.
- [ ] TEST-1..TEST-6 green; `./gradlew lint` clean; commit boundaries per CONTRIBUTING.
- [ ] FR-9 downgrade/mis-point integration test proves explicit failure (gate 10).
- [ ] FR-10 migrator passes on ≥3 real-scale datasets (gate 9).
- [ ] Gate 8 crash/ENOSPC matrix passes.
- [x] OQ-1..OQ-6 resolved or explicitly deferred for the recorded greenfield scope.
- [ ] Off-by-default preserved; no auto-migration; empty-dir+AUTO never births UNIFIED.
- [ ] Gates 3/7 (soak/perf) scheduled as the activation gate (may post-date the off-by-default landing).

**Bottom line for the reviewer:** M1-M2 wiring is implemented and remains opt-in. The remaining
near-term risk is operational proof: real process-crash/ENOSPC recovery, differential workload
coverage, and soak/performance. Migration/downgrade machinery is intentionally deferred because
there is no prior binary or deployed archive dataset.
