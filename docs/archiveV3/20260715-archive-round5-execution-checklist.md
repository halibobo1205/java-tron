# Archive round-5 execution checklist (for codex)

**Base:** `feat/archive-node` @ `6667ab8c5f` (post round-4 digest fix `301dfd9991`).
**Provenance:** round-5 full-stack adversarial review — 9 finders (4 functional, 3 performance, 1 code-quality, 1 config/operability) → per-finding 2-3-lens adversarial verification → completeness critic; 132 agents. 58 raw findings → 51 survived verification → critic: **50 REAL, 1 false-alarm**. Four duplicate pairs collapsed into single work items below.

**Bottom line:** no data-corruption or wrong-answer bug anywhere on the LEGACY block-ingest/publish path. Every correctness survivor is an edge/latent defect or a metrics/diagnostics defect. The stack is ready to sync from 0 on LEGACY_V1 — **but do NOT start the multi-day Stage-B run until Tier-1 lands** (~1 day of S-sized fixes): the runbook's own instrumentation is currently broken (3 of 5 prescribed alerts reference nonexistent metrics, the query counter mislabels failures as completed, the latency histogram is blind between 10s and the 30s deadline), so a sync started today produces ungateable data.

**Execution order:** Tier-1 (all, ~1 day) → start Stage B sync → Tier-2 items 2.1/2.2 ideally BEFORE the sync (they move gate-3/gate-5 numbers; re-measuring later costs days) → 2.3/2.4 before the soak's query-load phase → 2.5-2.8 as scheduled work → UNIFIED bundle (2.9) gates UNIFIED_V1 activation, not Stage B → Tier-3 opportunistic.

---

## Settled decisions — do NOT "fix" these

Fail-stop is binding; TOMBSTONE config-default resolution is deliberate; mid-chain readers hold the read lock (Phase 2 declined); journal-leads-canonical by 1 block is deliberate; High-1 power-loss deferred; trust-node CPU bypass is design. **False alarm from this round:** the flat 2× backend-read charge (`DefaultArchiveStateReader.java:276`) is documented deliberate-conservative design — leave it.

---

## Tier-1 — before Stage B (all S, ~1 day total)

### 1.1 close() wakes backpressure waiters before beginDrain — up-to-30s shutdown hang
`DefaultArchiveService.java:2065-2071`. `close()` calls `backlogMonitor.notifyAll()` **before** `lifecycle.beginDrain()`, so a backpressured block-push thread wakes, re-checks a still-undrained lifecycle, and re-parks for the full `backpressureTimeoutMs` (default 30s), then exits with a spurious `ArchiveException` on every clean restart under backlog. `markFatal` already has the correct order — mirror it (beginDrain first, then notifyAll).
**Verify:** test — park a push thread on backpressure, call close(), assert it wakes promptly without the spurious exception. **Risk:** none; that monitor has no other semantics.

### 1.2 Metrics correctness set (Stage-B gates read these)
- `DefaultArchiveService.java:1903` — `releaseLease` unconditionally emits `queryFinished`, so post-admission failures are labeled `result=completed` AND double-counted. Route failure results to a distinct label; count each query exactly once.
- Implement the 3 missing operational gauges the runbook alerts on, or provide equivalents: **repair-required** (the #1 blocker signal), **oldest in-flight journal age**, **publisher catch-up rate**. Then update `docs/archiveV3/20260714-archive-from0-production-validation-runbook.md` §Watch metrics to match the real names.
- `MetricsHistogram.java:53` — default Prometheus buckets top out at 10s vs the 30s query deadline; add explicit buckets covering 10-30s for `ARCHIVE_QUERY_LATENCY` so the near-deadline population is visible.
- `UnifiedArchiveInFlightStore.java:105` — `journal_ack_bytes`/ack stage latency measure different things on UNIFIED vs LEGACY under the same label; document the semantic difference (or split the label) so cross-layout comparison isn't silently misleading.
**Verify:** unit-assert the counter labels; scrape test for new gauges. **Risk:** metrics/doc only.

### 1.3 Batch-deadline −1 sentinel misread as UNLIMITED
`ArchiveQueryCoordinator.java:368-376`. `deadlineConstraint` samples the clock twice; remaining can compute to exactly −1, which `minimumTimeout` treats as the UNLIMITED sentinel → untimed park. ~1e-9 probability and non-default config, but the fix is pure tightening: single clock sample, clamp `remaining <= 0` → throw deadline-exceeded; harden `minimumTimeout` against non-sentinel negatives.
**Verify:** unit test with a fake clock pinned to the −1 case. **Risk:** none (fail-closed path only).

### 1.4 UNIFIED reader branch: latent double-unlock + symmetric close
`DefaultArchiveService.java:1946-1966`. In the unified branch of `openResolvedReader`, a failure after `readerOwnsSession=true` but before `lockTransferred=true` lets both the branch handler and the outer `finally` unlock the consistency read lock — the `IllegalMonitorStateException` would mask the original `Error` and leak snapshot permit + leases. Set `lockTransferred` at the same point as `readerOwnsSession` (genesis branch: at the unlock; mid-chain: at ownership transfer). Companion: `resolveReader`'s `ArchiveReaderException` catch arm (framework jsonrpc) skips `closeAfterFailure` — add the symmetric no-op call as insurance.
**Verify:** existing reader tests stay green; add a ctor-throw injection if cheap. **Risk:** none; both are no-ops today.

### 1.5 TX_BEFORE point off-by-one for fork-boundary replay
`HistoricalArchiveVmDynamicProperties.java:200`. The constructor uses `reader.getPoint().getBlockNum()` (= N) for `latestBlockHeaderNumber` on ALL point kinds, but a TX_BEFORE-traced tx actually executed with head N−1 — energy-limit hard-fork evaluation is wrong for txs in exactly the fork block. Latent today (trace endpoint not yet registered) but a genuine replay-fidelity defect. Derive from point kind: TX_BEFORE → blockNum−1; BLOCK_END → blockNum.
**Verify:** fork-boundary trace test pinning both kinds. **Risk:** none for BLOCK_END.

### 1.6 Fail-stop diagnosability trio + config validation
- `DefaultArchiveService.java:2181` — second concurrent fatal failure is dropped without logging (CAS loser's stack vanishes exactly in correlated-fault scenarios like disk-full). `logger.warn` or `addSuppressed` the loser.
- `ArchiveFatalController.java:221` — watchdog `Runtime.halt(70)` with zero log line; emit a `System.err` breadcrumb before halt, and document exit codes (70 = archive fatal watchdog, 1 = clean fail-stop) in the runbook's fail-stop playbook.
- `StorageConfig.java:384` — query-limit values of 0 pass validation but brick the entire historical query surface (healthy-looking node, dead queries, from a one-character typo). Tighten the five query keys to require positive-or-unlimited.
**Verify:** config test for the zero-reject; log assertions where practical. **Risk:** rejects previously-bootable zero configs that were already non-functional.

---

## Tier-2 — performance (gate-3 ≤5% block-push / gate-5 catch-up)

### 2.1 [M — do before Stage B if possible] Split putBlock's deep codec validation out of the write lock
`RocksDbArchiveInFlightStore.java:268`. `putBlock` re-runs full `validateBlock` (proto parse + deterministic re-serialize per value) on every block commit **inside the consistency write lock** — ~1-6ms typical, up to ~30-40ms on transfer-heavy blocks; the single largest avoidable slice of the gate-3 budget. Keep structural checks (range/positions/dup keys) on the produce path; the values are outputs of `normalizePut` in the same process. Keep deep codec validation at every **disk-read** boundary (startup load, reconcile) — fail-stop there is the actual corruption guard.
**Verify:** existing suites + one test proving disk-load still deep-validates. **Risk:** low, but state clearly in the commit message that produce-path trust is process-internal.

### 2.2 [S — needs explicit USER SIGN-OFF, durability relaxation] Publish-path journal delete → WAL-only
`RocksDbArchiveInFlightStore.java:585`. The journal delete at publish is forced-sync although the delete is explicitly recoverable — startup reconcile (`pendingPublishedJournals`) idempotently re-deletes resurrected journals. Making it WAL-only removes 1 of 4 per-block fsyncs (~25% of legacy fsync cost, ~+33% fsync-bound catch-up ceiling for the from-0 sync). Keep forced-sync on all **rollback** deletes.
**⚠ Gate: do not land without the user approving the durability change.**
**Verify:** crash-after-publish-before-delete test → journal resurrects → reconcile re-deletes. **Risk:** the relaxation is exactly what reconcile was built for, but it must be a conscious decision.

### 2.3 [S — before soak query phase] Stride the per-opcode deadline clock check
`QueryContext.java:278` + `actuator/.../vm/VM.java:39,98,108`. Historical execution samples `System.nanoTime` **3× per EVM opcode** (recordVmStep → deadlineFailureIfExpired, plus VM.play's pre/post checkDeadline), ~26ns/op measured on ARM, 60-80ns est. on x86 — 10-40% overhead on VM-bound eth_call/debug_trace. Fix: keep exact budget counting per call; run `deadlineFailureIfExpired` every N=256 calls; keep the per-call `terminal.get()` rethrow (ordering contract at QueryContext.java:261-268 survives). Also delete the redundant pre-execute `checkDeadline` at VM.java:98 (recordVmStep ran ~100ns earlier in the same iteration); the post-execute check at VM.java:106-109 is deliberately immediate (non-interruptible precompile guard) — keep it per-op.
**Verify:** fake-clock deadline tests updated for stride; assert overshoot bound. **Risk:** deadline overshoot bounded by N opcodes (microseconds vs 30s deadline).

### 2.4 [M — before soak query phase] Memoize decoded capsules per address in the reader
`DefaultArchiveStateReader.java:224` / `ArchiveRepositoryAdapter.java:196` (duplicate pair — one work item). The memo caches raw bytes only, so every SLOAD re-parses Account AND SmartContract protos (+2 copies) — tens of ms typical, seconds on ABI-embedded/asset-heavy rows, all while holding the archive read lock. Memoize the decoded capsule (or parsed Message) alongside the raw-bytes memo, bounded by the same maxMemoEntries/Bytes accounting; return fresh capsules wrapping the shared parsed Message to avoid aliasing.
**Verify:** trace/eth_call integration suites; memo-bound test. **Risk:** capsule aliasing — covered by wrap-fresh rule.

### 2.5 [S] Legacy putBlockChanges: single-encode + gated baseline tombstones
`RocksDbArchiveTemporalStore.java:261`. Every record's changeset key and both values are encoded twice (once for the WriteBatch, once for BlockCommitRow digest), and a blind baseline-delete tombstone is written per record (~2,000 pure tombstones per busy block → 100-250KB WAL/memtable churn per block, GB-scale daily compaction input). Build BlockCommitRow once, feed both consumers; gate the baseline delete on an in-memory baseline-presence counter. Digest bytes must remain identical.
**Verify:** digest-parity test (same input → same marker digest before/after) + unwind baseline-counter parity test. **Risk:** low; unwind must keep the counter exact.

### 2.6 [M] Per-reader reusable RocksIterator for mid-chain LEGACY reads
`RocksDbArchiveTemporalStore.java:691`. The mid-chain (locked passThrough) path creates a fresh native RocksIterator per getAsOf (~1-5µs each, up to 50-250ms per budget-maxed request) while holding the consistency read lock that block commit queues behind. Open one iterator at reader-open, reuse across getAsOf calls, close in view.close(). Safe because the reader pins the read lock — the DB cannot advance beneath it.
**Verify:** mid-chain read tests + close-exactly-once assertion. **Risk:** iterator lifecycle; single-owner-thread contract already enforced.

### 2.7 [M — design review first] Move validatePrevValueChain reads off the write lock
`RocksDbArchiveTemporalStore.java:286`. One RocksDB point read per unique key per block inside the write lock (~1-10ms warm, worse cold). This read IS the cross-layout mis-point fail-stop guard — the check must remain hard. Proposed: pre-read under a snapshot before lock acquisition; re-check in-flight head staleness under the lock before writing. **Do after 2.1-2.6; needs a short design note.**
**Risk:** medium — weakening this check trades away a corruption guard; that's why it's sequenced last of the write-path items.

### 2.8 [M] Allocation-hygiene bundle (one pass, profile before/after)
Sub-1% items individually, a few MB/block of transient garbage together: presize `ArchiveInFlightCodec.encodeBlock` BAOS from estimatedRetainedBytes (`ArchiveInFlightCodec.java:174`); `WrappedByteArray.of` (no copy) for fresh concat results + reuse the latestKey across validate/remove (`DefaultArchiveService.java:1460` — two duplicate findings, one fix); single history-prefix build per getAsOf (`ArchiveTemporalReadSupport.java:22`); trusted package-private no-copy accessors where the defensive copy is provably redundant (`DomainValue.java:34` — only within the encode pipeline, keep public API copying). Also: cache the `Files.getFileStore()` FileStore per store instead of per block commit (`RocksDbArchiveInFlightStore.java:598`).
**Verify:** allocation profile before/after; full suites. **Risk:** low; skip any item whose test churn exceeds its win. `inFlightVersions` HashMap never-shrink: **accept and document** (realistic ceiling ~4-8MB once; the rebuild would add 100-300ms under the write lock).

### 2.9 [M — gates UNIFIED_V1 activation, NOT Stage B] UNIFIED ack/publish rework
Merges 5 survivor findings (ack rewrite ×2 duplicate, publish 3× re-encode, 8-10× transient garbage, journal round-trips). `UnifiedArchiveInFlightStore.java:86` — acknowledgeBlock does get/decode/validateBlock/re-encode/`replaceJournalDurably` (full payload + **second forced fsync**) to flip one state byte, vs legacy's ~100-byte WAL-only ack marker the interface javadoc explicitly blesses. `UnifiedArchiveBackend.java:31` — publish re-encodes the full block ~3× for the journal compare-and-delete. Fix shape: compact CANONICAL_COMMITTED marker row folded at load (mirroring legacy ACK_PREFIX), and let the publish batch expect the JOURNALED encoding + marker delete inside the same atomic batch (crash consistency preserved — the legacy store proves the pattern).
**Add this item to `20260714-unified-v1-wiring-requirements.md` M3 list.**

---

## Tier-3 — quality (opportunistic; two items unblock M3)

- **3.1 [S — do before M3 differential tests]** Restore the two-message `validateRecordInRange` distinction in `UnifiedArchiveTemporalStore` and standardize error-prefix/precondition conventions with the legacy twin — the collapsed diagnostics will directly obstruct the planned LEGACY-vs-UNIFIED shared assertions. Grep message-asserting tests first.
- **3.2 [L — sequence after M3 differential tests exist]** Extract a shared temporal-keyspace engine (SPI: `get(family,key)` / `newIterator(family)`) absorbing the ~300 duplicated validate/unwind/digest/comparator lines between `RocksDbArchiveTemporalStore` and `UnifiedArchiveTemporalStore`. This twin-divergence class already shipped one real bug (digest fold order, fixed in `301dfd9991`) and ≥4 live micro-divergences. Port validators first (cold path), unwind last, pinned by the differential tests.
- **3.3 [M]** Extract `HistoricalReaderSupport` for the 3×-duplicated selector→reader→canonical-recheck wiring across the JSON-RPC entry classes (4 divergences already crept in). Preserve error text verbatim — integration tests assert it.
- **3.4 [S] Dead-surface sweep (~half a day):** delete one-shot `commitArchiveBlockOrFailStop`/`commitArchiveBlockOnlyOrFailStop` (`Manager.java:1315/1337`, rewire `ManagerArchiveLifecycleTest.java:103` to the split helpers; add a javadoc warning on interface-level `ArchiveService.commitBlock`); delete `UnifiedArchiveTemporalStore.hasCommitMarker` (zero callers); jsonrpc dead cluster (two unused methods, one unused overload, one dead parameter + rewrite the stale `tombstoneDefault` comment); move `ChainBaseArchiveReadThrough` under test sources or add a factory guard (its name invites exactly the mid-chain misuse the design fail-stops); fix `StructLogReconstructor.java:301` — either catch `NumberFormatException` for numeric params or correct the "malformed deltas are ignored" javadoc; delete dead `resolveHistoricalEnergyFee` + fix the misleading energy-fee javadoc (`HistoricalVmDynamicProperties.java:39`).
- **3.5 [S]** `Math` → `StrictMathWrapper` sweep (~19 sites, repo convention — note: checkstyle does not actually ban Math, this is consistency); `ArchiveServiceFactory` extract `openLegacyArchive` to mirror the UNIFIED method + merge the twin `requireXxxDirectory` helpers; extract the in-flight head-drop sequence helper using the **rollback** step order (steps 5/6 currently silently swapped between `rollbackJournaledBlockLocked` and `unwindBlock`); javadoc-mark `ArchiveTemporalStore`'s production-unreachable write/unwind surface as differential-test-only (the default-throw variant is unworkable — ~110 test call sites).
- **3.6 [defer]** 12 telescoping `DefaultArchiveService` constructors: real debt, but the last dependency addition touched only 2 of them and a Builder migration costs ~77 test call sites — do opportunistically, not as scheduled work. `ByteArrayKey`/`ChangeKey` vs `WrappedByteArray` consolidation: lowest priority. `ArchiveQueryCoordinator` constructor gauge-stomp before the enabled check: hygiene one-liner, fold into any coordinator touch. Capture-path double binding/policy resolution: <1% of the unavoidable prev-value read on the same path — fold into a future touch.

---

## Round-6 candidate probes (no finder covered these)

Capture-domain semantic coverage (does the allowlist/domain catalog cover every consensus-relevant store — TRC10 issuance, delegation/vote/witness/exchange — with value semantics verified against an independent oracle); fork-switch/unwind behavior tests (replay with archive leading by one block, unwind to coverage floor with baseline rows, unwind racing an open mid-chain reader); startup-recovery state-machine breadth (crash during reconcile itself, ack-then-crash-then-unwind orderings); adversarial query inputs (malformed selectors, hash-vs-number ambiguity, oversized batches — calibrated to trust-node); **archive RocksDB/CF tuning fitness** (bloom filters for history-prefix seeks, compaction style, block cache — potentially larger than all Java-side wins combined); `estimatedRetainedBytes` calibration vs actual retained heap (it gates the backpressure/OOM boundary); era/maintenance-boundary historical queries.
