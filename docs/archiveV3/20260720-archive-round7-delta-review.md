# Archive round-7 delta review (for codex)

**Delta reviewed:** `717d0ea06c..HEAD` (`3e8c875d4c`) — 233 files, +32108/−7469, 10 commits: debug_trace* scope cut, temporal optimization, publication simplification, schema-6 finalization, self-hardening rounds 8-24, RocksDB tuning.
**Provenance:** 8 finders → 3-lens adversarial verification (incl. INTENT lens vs deliberate scope cuts) → critic; 66 agents. 19 raw → 13 survived → dedup to **~10 real issues, all operability/documentation — zero correctness bugs survived triple review**. claude independently re-verified the top regression. Local suites green: chainbase `archive.*` + framework jsonrpc/vm.archive.

## Dimension verdicts

| Dimension | Verdict |
|---|---|
| trace removal | **CLEAN** — method-absent (−32601) not stubbed; 19 files deleted with zero dangling refs; trace config keys fail loudly; actuator trace pkg restored to merge-base except the deliberately-kept MSTORE8 fix; nested-swallow mechanism survives on the remaining executor. Residue: 2 stale comments. |
| temporal optimization | **SAFE** — inclusive-after getAsOf (seek C>T + MAX_VALUE guard) preserved; digest fold still changeset-key-ordered with regression test passing; prev-value chain + duplicate rejection intact; oracle parity green. |
| publication contract | **INTACT** — journal-first→canonical→ack→atomic-publish at all 4 call sites; compact-ACK; 2 fsyncs/block; reconcile re-ack before publish; drain-before-notify. Cosmetic: dead duplicate throw. |
| schema finalization | **SOUND with one process caveat** (see decision 2). Fail-closed via two independent layers; 5 newly-excluded dynamic keys verified non-TVM; no VM-visible reclassification. |
| self-hardening rounds 8-24 | **TRUSTWORTHY with one medium regression** (P1 below). All sampled fixes genuine; all 8 protected anchors pass. |
| RocksDB tuning | **PARTIALLY LANDED** (see scoreboard delta). |
| regression sweep | **PASS — all 14 protected fixes intact at HEAD** with file:line evidence. |

## P1 — fix before any validation run

### P1.1 [codex, S] Failure-path logging degraded to class name only (medium regression, EVERY node)
`Manager.java:1729-1746` — `logArchiveWarningBestEffort`/`logArchiveErrorBestEffort` log `safeFailureType(failure)` = exception **class name only**; message and stack trace are lost. Callers cover push-block failure paths (`:1407,1418,1444`) and every switch-fork replay failure (`:1621,1633,1646,1686,1725`) — **including ordinary ContractValidateException on archive-off nodes**. A production-validation run depends on readable failure logs; this is the one item that blocks starting it.
**Fix:** `logger.warn(message, failure)` inside the existing try/catch (full throwable), class-name fallback only if that itself throws. Also collapse the dead duplicate throw at `:1634-1637` (`if (errCode != ARCHIVE_RUNTIME) { throw e; } throw e;` — conditional provably dead).

### P1.2 [⚠ USER DECISION → then codex, S] RocksDB metrics: runbook alerts on series no code publishes
R6-2.3.1 stats wiring landed in `1f0f197f93`, then runtime export was **deliberately removed** in `8e1602cc1c` (R19-05, no-JNI-on-hot-paths). But the runbook (`20260714-…-runbook.md:103-108`) still gates tuning validation on `rocksdb_pending_compaction_bytes` / `rocksdb_stall_micros` / bloom/cache series that can never fire. Pick one:
- (a) off-path daemon poller (e.g. 10s interval, own thread) feeding the existing fail-isolated `addRocksDbCounter`/`setRocksDbState` hooks — keeps the tuning-validation loop; or
- (b) drop the four rocksdb_* series from the runbook (repoint at RocksDB LOG stats dump) and delete the dead ArchiveMetrics hooks.

### P1.3 [codex, S — time-sensitive] `level_compaction_dynamic_level_bytes=true` NOW
Still unset anywhere in the archive module. The scoreboard's own constraint: set it **from day one on the fresh UNIFIED format** — the window closes as schema-6 archives with real data are produced. One line in `UnifiedArchiveDb` CF options; bundle with the scoreboard line updates (below).

## P2

### P2.1 [⚠ USER DECISION → then codex] Schema-6 ambiguity
`fae9c26c83` rotated the on-disk archive-schema **checksum** after the "schema 6 complete E2E" run (doc `d8aad6aec7` pins `c5cef4add5`), without a layout bump — two incompatible on-disk contracts both self-describe as schema 6, and HEAD's finalized contract has not been E2E-exercised. Either **bump layout-schema to 7** for the rotation, or **rerun** at least the private-chain oracle matrix at HEAD and note it in the E2E doc.

### P2.2 [codex, S] Config artifact parity (dual-compare rule)
- `config.conf:56` pins `publisher.async=false` while this delta flipped the `reference.conf:160` default to `true` — a user copying the shipped example silently reverts to synchronous publication on the block thread. Flip or delete the key.
- New `query.jsonRpcWorkerThreads=2` exists only in `reference.conf:178`; add to `config.conf`'s query block.

## P3 — one hygiene commit
Two stale trace comments (`QueryContextHolder.java:3` "and trace code"; `HistoricalArchiveVmDynamicProperties.java:367` "eth_call/trace"); E2E doc `20260719-…-e2e-results.md:270` cites deleted `StructLogReconstructorTest` (phantom coverage claim); add rebuild-remedy text to both schema/checksum mismatch fail-closed messages (`UnifiedArchiveManifest.java:65` area — the missing-identity path already has it); `eraseBlock` fail-stop uses `ErrCode.ARCHIVE_RUNTIME` even on archive-off nodes (`Manager.java:1319-1323`) — misleading classification, consider a neutral code or comment.

## Scoreboard delta (update `20260716-audit-implementation-scoreboard.md`)
- R6-2.3.2 bloom filters: MISSING → **DONE** (BloomFilter(10, full) + BlockBasedTableConfig on all 10 CFs, optimizeFiltersForHits on temporal payload, `UnifiedArchiveDb.java:1075-1095`).
- R6-2.3.3 shared cache: MISSING → **PARTIAL** (shared 72MiB LRUCache landed `:72,:1037`; config key never surfaced — hardcoded).
- R6-2.3.4 compaction: MISSING → **PARTIAL** (`setMaxBackgroundJobs(2)` landed `:1024`; dynamic-level-bytes unset → P1.3).
- R6-2.3.1 stats: MISSING → **LANDED-THEN-REMOVED-BY-DESIGN** (→ P1.2 decision).
- Trace budget keys (maxTraceSteps/…): **CLOSED-BY-SCOPE-CUT** (rejected loudly at startup, tested).
- Also note: format_version=0 + kCRC32c pinned for dual-rocksdbjni compat; streaming journal load; gated full-scrub — all landed in this delta.

## Bottom line
**No correctness bug in +32k lines; all protected fixes intact; the four big refactors (trace cut / temporal / publication / schema) are clean.** The whole surviving set is operability: fix P1.1 logging before any validation run, resolve the two user decisions (RocksDB metrics side; schema-6 bump-vs-rerun), set dynamic-level-bytes while the day-one window is open, and sync the config pair. Nothing here blocks continued schema-6 soak testing except P1.1.
