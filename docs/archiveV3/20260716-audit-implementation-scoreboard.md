# Audit implementation scoreboard — unified-only rework verification (for codex)

**Verified commit:** `54b3be8ae0 feat(archive): harden unified-only implementation` (119 files, +4051/−8368; legacy stores removed, persistence Unified-only).
**Method:** 6 verification agents + synthesis critic (7 agents total) checked every round-5/round-6 audit item against CODE (not docs); claude independently re-verified the two new HIGH concerns and the four headline items (nested-swallow fix chain, TRC10 overlay, SolidityNode guard, digest-test survival). chainbase `archive.*` suite green.

## Score: 26 IMPLEMENTED · 3 PARTIAL · 6 MISSING · 0 REGRESSED (35 items)

**Every correctness item from rounds 5 and 6 is implemented and tested.** All remaining open items are performance/observability. The central removal worry is cleared: the InMemory↔Unified differential oracle survived (`ArchiveTemporalStoreConsistencyTest` renamed → `UnifiedArchiveTemporalStoreOracleTest`, all 5 scenarios incl. both head-guard parity tests), the digest-order regression test survived, fsyncs/block cut 4→2, and legacy roots/configs fail closed.

### IMPLEMENTED (26) — no action, evidence recorded
R5-1.1 close() drain-before-notify (+prompt-wake test) · R5-1.2a queryFinished failure labels · R5-1.2b all 3 operational gauges (repair_required / oldest_inflight_block / published-vs-journal counters + publisher_lag_blocks) · R5-1.2c latency buckets through 30s+ · R5-1.3 deadline single-sample + throw-at-source + clamped minimumTimeout · R5-1.4 double-unlock closed (reader-close in catch + hold-count baseline guard) + jsonrpc closeAfterFailure symmetric · R5-1.5 TX_BEFORE → blockNum−1 (+fork-boundary test) · R5-1.6a suppressed+logged second fatal · R5-1.6b watchdog stderr breadcrumb before halt(70) · R5-1.6c query-limit zero rejection (reasoned allowlist where 0=disable) · R5-2.2 fsyncs/block 4→2 (journal put sync + publish batch; ack WAL-only) · R5-2.7 validation off the write lock (unified architecture) · R5-2.9 compact ACK (immutable JOURNALED payload, token/ack rows, 3-row atomic publish delete) · R6-1.1 nested-swallow fix (recordVmTerminalFailure dual-slot first-wins, BOTH executors rethrow in finally, nested RewardBalance tests both executors) · R6-1.2 TRC10 (sweep fail-closed AND getTokenBalance reads the copy-on-write token overlay — fuller than asked) · R6-2.1 SolidityNode boot refusal (+test) · R6-2.4 stale+loaded journal single budget · R6-2.3.5 bounded changeset probe replaces full history scan · R6-2.3.6 WAL-recycling rejection held · R6-2.3.7 prefix-extractor deferral held · R6-3.3 AccountStore null-guard (correct placement after historyBalanceLookup) · REM-1 zero dangling legacy references · REM-2 unified-only factory, layout key deleted, legacy roots fail closed · REM-3 identity ladder intact, LEGACY_V1 identity fails closed · REM-4 differential oracle + regression tests survived · REM-6 config keys removed cleanly, stale configs fail at boot with clear message.

### PARTIAL (3)
- **R5-2.1 putBlock deep validation** — ack path no longer deep-reads and deep decode+validate runs at disk-read boundaries, but `putBlock` still runs full deep codec validation (proto parse + deterministic re-serialize) on the produce path under the write lock. Finish: reduce to structural checks on `commitBlockLocked` produce path.
- **R5-2.8 allocation bundle** — only `Files.getFileStore` partially done (1s value cache, but bypassed with `force=true` on every block append; FileStore object never cached). Remaining: presize `encodeBlock` BAOS, build latestKey once per record in `prepare()` (currently 2×), single getAsOf prefix materialization.
- **REM-5 archive-off untouched** — byte-identity and capture short-circuit HOLD, but "Manager hooks unchanged" is false: see Bundled Changes below.

### MISSING (6) — all perf/observability, docs honestly list them as open activation gates
- **R5-2.3** nanoTime stride (still 3 clock samples/opcode; redundant `VM.java` pre-execute checkDeadline not deleted).
- **R5-2.4** decoded-capsule memoization (getStorage re-parses the contract proto per SLOAD from the raw-byte memo).
- **R6-2.3.1** RocksDB statistics + write-stall counters in ArchiveMetrics (prerequisite for all tuning).
- **R6-2.3.2** BloomFilter(10) on unified CFs (all 8 CFs are stock `new ColumnFamilyOptions()`).
- **R6-2.3.3** shared LRUCache + index/filter caching (user still owes the SIZE decision — surface a config key, don't block).
- **R6-2.3.4** maxBackgroundJobs (default 2) + `level_compaction_dynamic_level_bytes` — note: the old needs-migration caveat is MOOT now (UNIFIED_V1 is a fresh format; set dynamic-level from day one before real data exists).

## Two new concerns — claude-verified status

1. **[verified REAL — review debt, needs action] Non-archive-gated runtime changes bundled in the archive commit.** `SnapshotManager.add()` now `synchronized` + late-added-DB head-advance, and root commit gained a NEW global fail-fast `RevokingStoreIllegalStateException` for DBs outside the top-level snapshot — **this throw executes on every node, archive-off included**; plus ApplicationImpl shutdown-stage reorder, DposTask isRunning breaks, AssetUpdateHelper reset. Plausibly correct hardening, but consensus-adjacent and unreviewed as standalone changes. **Ask: separate commit(s) or an explicit justification note per change + targeted tests; at minimum confirm a stock archive-off node's behavior is unchanged for previously-tolerated states.**
2. **[verified ALREADY HANDLED — test gap only] WAL-only ACK crash window.** Concern was: crash between WAL-only ack and publish loses the ack row while the fsynced journal survives → publish requires the ack row. Verified: startup reconcile hash-verifies each in-flight block against canonical (`DefaultArchiveService.java:778-784`), rolls back mismatches, **re-acknowledges every retained block (`:792-794`) before resuming publish (`:798`)** — the ack is re-derived from chain state. Open item is only the **crash-window test** (fsynced journal present, ack row lost → assert recovery re-acks and publishes; no fail-stop).

## Priority order for codex

1. Justify/split the bundled non-archive changes (concern 1) — this is the only item touching non-archive nodes.
2. Crash-window test for WAL-only ack recovery (concern 2) + the UnifiedArchiveTxNumIndex dedicated suite (thinnest coverage spot post-removal: bounds, duplicate/gap txNum rejection, restart reconciliation).
3. RocksDB baseline before any activation run: stats/stall gauges → BloomFilter(10) → shared cache behind a config key (surface SIZE to user) → maxBackgroundJobs + dynamic-level-bytes (fresh format, no migration concern).
4. Historical-query hot path: R5-2.4 decoded memo, then R5-2.3 nanoTime stride (+delete redundant pre-execute checkDeadline).
5. Finish R5-2.1 (structural-only putBlock produce validation) and the R5-2.8 remainder (presize/latestKey-once/prefix-once/FileStore cache or justify force=true).
6. Operator polish: legacy-root failure should say "LEGACY_V1 archives are no longer supported; rebuild required" on BOTH failure paths; document the two config-compat breaks (layout/adoptLegacy removal; zero query limits now rejected at boot).

## Minor riders (from verification, no urgency)
`normalizeTimeout` still aliases −1 to UNLIMITED (safe only because deadlineConstraint throws at source — comment it); SolidityNode guard lives only in DefaultConfig's @Bean (factory itself unguarded); `publishSync` param of `publishBlockAtomically` is dead config (sole callsite hardcodes true); `validateTxNumsCovered` full-keyspace mode is O(archive size) at startup when enabled — will hurt until bloom/cache lands; Manager publish clamp can trail solidification by revoking-size+pending-flush on large-window nodes — measure steady-state lag in the e2e run.
