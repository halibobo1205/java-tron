# Archive round-6 probe results (for codex)

**Base:** `feat/archive-node` (round-5 Tier-1 + item 2.9 already implemented by codex; UNIFIED_V1 now the default layout when archive is enabled — greenfield decision).
**Provenance:** 4 targeted probe areas rounds 1-5 never covered — capture-domain semantic completeness (read-side + write-side matrices), fork/unwind behavior (4 hard scenarios), RocksDB tuning fitness, `estimatedRetainedBytes` calibration. 5 probes → 2-3-lens adversarial verification → critic; 58 agents. 21 raw → 16 survived → critic: 15 REAL, 1 REJECTED-as-unsafe. The HIGH finding was independently source-verified by claude (all four links of the chain).

**⚠ Scope-change ripple (act on this):** with UNIFIED_V1 now the **Stage-B layout**, the M3 gate tests (LEGACY↔UNIFIED differential, crash/ENOSPC injection, corrupt-CF scrubs, mis-point) move from "activation gate" to **before-Stage-B** priority — round-5's "UNIFIED items gate activation, not Stage B" ordering is superseded.

---

## Coverage verdicts (the probes' primary deliverable)

| Area | Verdict |
|---|---|
| **capture read-side** | **NOT airtight — one real breach** (§1.1 below). Everything else proven sound: all non-P0 Repository reads fail closed explicitly; all 43 VmDynamicProperties getters archive-resolved; VMConfig snapshot isolation; storage-key parity byte-for-byte; block-context reads canonical-immutable; top-level (depth-0) propagation chain correct. |
| **capture write-side** | **Airtight for P0 scope.** 41 dbNames + 9 TronDatabase stores all explicitly bound; no TVM-relevant store uncaptured; store hooks bypass-free; ACCOUNT_ASSET ordering safe; maintenance-cycle writes correctly attributed (BLOCK_FINALIZE); gap-writes fail-stop; startup migration guards hold. Two peripheral INTERNAL_ONLY leaks (§2.1, §3.2). |
| **fork/unwind** | **Sound in logic at every layer** (S1 orphan-journal-once + gap-free fork txNums; S2 published-never-unwound in production + baseline edges differential-tested; S3 reader races excluded by snapshot/lock, UNIFIED ReadSession equivalent verified; S4 head-first forced structurally, single-durable-store crash order safe). **One scenario has NO test anywhere: S1 Manager-level switchFork with archive enabled** (§2.2). |
| **RocksDB tuning** | **NOT fit for production scale** — all archive stores/CFs run stock defaults vs canonical's tuned settings. No bloom filter, no shared cache, 2 background jobs, no stats. Top-3 levers in §2.3. (Verified sound: WAL never disabled; BE key encodings need no custom comparator; iterator/Options lifecycle airtight; strict-open discipline; in-flight tombstone pressure fine at defaults.) |
| **estimatedRetainedBytes** | **Honest in the default regime** (compressed oops ≤31GB heap): uniformly conservative, real/estimator ≈ 0.55-0.88 (typical ~0.72) — `hardInFlightBytes=256MB` ≈ ~184MB real heap at fail-stop. Payload/key double-count accounting exactly matches real retention. Two qualified exceptions: >32GB-heap restart-load inverts to ~1.1-1.25x (doc the ≤31GB assumption); the real hole is the budget bypass in §2.4. |

---

## 1. MUST-FIX before review-cycle close (the only settled-invariant breach)

### 1.1 [HIGH — owner: codex; test: claude] Nested CALL/CREATE swallows `UnsupportedHistoricalStateException` → silent wrong answer
`Program.java:1148-1167` + `VM.java:130-141` + `HistoricalConstantCallExecutor.java:70-72` / `HistoricalTraceCallExecutor.java:119-121`.
The fail-closed boundary holds **only at call depth 0**. A CHILD program hitting an unarchived read (RewardBalance precompile → `getDynamicPropertiesStore()`; CALLTOKEN endowment validation → `getAssetIssue`; SELFDESTRUCT with ALLOW_TVM_VOTE → withdrawReward) throws the plain-RuntimeException `UnsupportedHistoricalStateException`; the child's `VM.play` catches it into the **child's** result (`setRuntimeFailure`, no rethrow); `callToAddress`/`createContractImpl` then treat it as an ordinary failed sub-call — `internalTx.reject()` + `stackPushZero()` — and the parent **continues**. `ProgramResult.merge` propagates no exception. Top-level completes "successfully" with a historically wrong outcome (on-chain the sub-call succeeded). The executors' escape hatch is typed exclusively for budget errors (`QueryContext.getRecordedTerminalException()` returns `HistoricalQueryLimitException`, `QueryContext.java:267`) — no slot exists for unsupported-state. Violates the exception class's own javadoc contract ("must never silently … return a wrong answer").
**Reachability:** any composed contract (router/proxy/multicall) whose depth ≥1 touches vote/reward precompiles (mainnet-active for years), CALLTOKEN, or SELFDESTRUCT-with-vote. Invisible to depth-0 tests by construction.
**Fix (M):** mirror the budget mechanism — when `ArchiveRepositoryAdapter` throws, ALSO record the exception terminally (widen the QueryContext terminal slot to a general terminal Throwable, or add a dedicated slot; `QueryContextHolder` is already attached around the whole execution), and rethrow in **both** executors' finally blocks before inspecting the program result, exactly like `getRecordedTerminalException`. Archive-only blast radius; latest path untouched.
**Test (claude):** nested CALL to a contract invoking the RewardBalance precompile (+ a CALLTOKEN variant) at a historical point must surface the explicit unsupported error, not success.

### 1.2 [MEDIUM — companion; owner: codex] TRC10 sweep + `getTokenBalance` overlay divergence
`ArchiveRepositoryAdapter.java:135`. `getTokenBalance` ignores asset maps written into overlay accounts via `putAccountValue` — diverges from `RepositoryImpl`. Concrete wrong-balance window: pre-ALLOW_TVM_VOTE mainnet (~2019-2021) SELFDESTRUCT `transferAllToken` sweeps. Cheapest correct fix: **fail-close the SELFDESTRUCT TRC10 sweep (`MUtil.transferAllToken`) on the archive historical path**, which makes the divergence unreachable; revisit full overlay-asset support only if that window's traffic matters later. Lands naturally with 1.1 (same boundary, same test harness).

---

## 2. Before production from-0 sync (slots into round-5 Tier-2)

### 2.1 [S — codex] Refuse `storage.archive.enable` on SolidityNode
`SolidityNode.java:160` writes `LATEST_SOLIDIFIED_BLOCK_NUM` outside capture phases with no guard — silent-skip contradicting fail-stop, confined to the deprecated node type (key is HISTORY_ONLY+INTERNAL_ONLY, readers fail closed). Guard in `ArchiveServiceFactory`/`DefaultConfig`: archive.enable + `Args.isSolidityNode()` → refuse to boot. Lower-risk than reclassifying the key (which would change the policy checksum).

### 2.2 [M — claude] S1 switchFork-with-archive Manager-level test — the one scenario with zero coverage
`Manager.java:1449-1631`. Push a competing heavier branch to trigger a real switchFork with archive enabled; assert: orphan journal dropped exactly once (through `eraseBlock` with a CANONICAL_COMMITTED journal), fork branch re-captured with fresh gap-free txNums, and the RECOVERY re-apply finally block (`:1574-1631`) drives correctly. Shallow reorgs execute this path routinely on any archive node; latent-bug consequence is fail-stop (not wrong answers), which is why it's test-tier not code-tier.

### 2.3 RocksDB tuning ladder (measure → safe wins → sized decisions)
1. **[S — codex, FIRST]** Add `rocksdb.stats` + write-stall counters to `ArchiveMetrics` — the measurement prerequisite; gate-5 runs must prove the binding constraint before/after each change.
2. **[S — codex, on-disk safe both directions]** `BloomFilter(10, false)` via `BlockBasedTableConfig` on temporal/blockrange/inflight + per-CF on `UnifiedArchiveDb` (INDEX/LATEST/INFLIGHT/META minimum). Biggest from-0 sync lever: kills the per-tx negative dup-txId point-get probing all levels of an eventually-billions-key space.
3. **[S — ⚠ USER-DECISION on size]** One shared `LRUCache` (256MB-1GB) + `cacheIndexAndFilterBlocks(true)` + `pinL0FilterAndIndexBlocksInCache(true)` on all stores/CFs; `ReadOptions.setFillCache(false)` on scrub/unwind scans. Biggest query-latency lever. Native memory must be budgeted against node heap — operator sizes it.
4. **[S — ⚠ USER-DECISION on migration]** `setMaxBackgroundJobs(4-8)` on the temporal store (plain codex work) ± `level_compaction_dynamic_level_bytes=true` — the latter does NOT auto-migrate an existing x86_64/5.15.10 DB (fresh-DB or manual CompactRange required): needs a migration-story sign-off.
5. **[S — codex]** Bound `hasRowsInRange` to the changeset-family probe (document the atomic-WriteBatch invariant), or keep the history scan with readahead + `fillCache(false)` — `RocksDbArchiveTemporalStore.java:378-388` + the sibling anchoring pass at `:322`. Today it full-scans the entire history family on the crash-reconcile path (hours at mature scale, presents as a startup hang).
6. **[REJECTED — do not re-propose]** `setRecycleLogFileNum` (WAL recycling): on the production x86_64 RocksDB 5.15.10, recycled WALs sit on the pre-#7252 recovery-hole bug — risk of silent truncation of fsynced commits, a durability hazard violating fail-stop. Benefit was arm64-only anyway.
7. **[DEFER — ⚠ USER-DECISION go/no-go]** Prefix extractor (capped 15) + prefix/memtable bloom for history getAsOf seeks: the largest potential read-latency lever, but ~10 cross-prefix scans (incl. `getLastRange`'s backward step) have undefined prefix-mode semantics on 5.15.10 — a skipped total-order-seek audit converts a perf tweak into silent unwind/recovery corruption. Only after item-1/2 measurements show seek cost still dominates.

### 2.4 [S — codex] Startup stale published journals bypass the in-flight byte budget
`DefaultArchiveService.java:306-327`. Stale published journals retained at startup sit **outside** `inFlightRetainedBytes` with a separate hard budget — combined worst case ~2× `hardInFlightBytes`. Check `staleBytes + loadedBytes` against the single budget in `loadInFlightBlocks`; only behavior change is an intended fail-stop on a pathological journal set.

---

## 3. Opportunistic / docs

- **3.1 [S — claude]** Heap-sizing docs: estimator-bytes semantics, the 0.55-0.88 real-multiplier table, "reserve `hardInFlightBytes` on top of base heap", and the ≤31GB compressed-oops assumption — into `reference.conf`/`config.conf` comments + the runbook. Consolidates four retained-bytes survivors; zero risk. (Record-watermark note: soft 1M/hard 2M records are unreachable at default byte watermarks — comment, don't retune.)
- **3.2 [S — ⚠ USER-DECISION: bundle with next schema bump only]** `TOKEN_UPDATE_DONE` migration marker unclassified in `DynamicKeyPolicy.java:158` (falls to unknown/FULL_HISTORY; its exact siblings are excluded). Omission, not design — but the fix changes the policy checksum → existing archive DBs fail-closed-reject until rebuilt. Must ride a planned schema bump, never standalone.
- **3.3 [S — codex]** `AccountStore.put` archive branch drops the base null-item guard (`AccountStore.java:95`) — parity nit; place the guard **after** the historyBalanceLookup block (top-of-method placement would swallow an upstream-identical NPE sub-path).

---

## Closure statement

The review cycle (rounds 1-6) can close once **1.1 + 1.2 + the S1 test (2.2)** land. Everything else is production-readiness engineering (RocksDB tuning gated on measurements) or docs. Named residual risks that survive closure without blocking it: High-1 cross-DB WAL power-loss ordering (user-deferred); RocksDB physical tuning impact unmeasured until stall counters land (gates gate-5 sign-off, not correctness); prefix-extractor lever unexploited pending user risk decision; unknown future dynamic keys default to FULL_HISTORY+INTERNAL_ONLY (data-preserving, needs promotion discipline). No survivor was mislabeled deliberate design; one item (WAL recycling) is formally rejected as unsafe rather than deferred.
