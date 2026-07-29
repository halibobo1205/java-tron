# From-0 archive-node production validation runbook

**Purpose:** the go/no-go gate before running an **archive-ON** node syncing from genesis in production.
**Scope:** archive-nodes-only. **Archive-OFF is out of scope** — source invariants and regression
tests through Round 16 preserve the normal-node mutation path when no capture engine is installed;
the scale and fault gates below apply to archive-ON resources and persistence.

Every file:line in this document was re-verified against the working tree on 2026-07-29. Where a
gate is **not measurable with what exists today**, it says so instead of giving an instruction that
would silently produce no data.

## Why this gate exists

The archive code has completed sixteen adversarial source-review rounds; those rounds found and
fixed high-severity archive-ON defects, and the current focused/aggregate regression matrix is green.
Since then `docs/archiveV3/harness/` has added a committed private-chain fault harness that runs
7/7 green (README §10). That evidence still does **not** demonstrate behavior **at mainnet scale**:
the design doc's own hard acceptance gates
(`20260713-archive-performance-hardening-plan.md:334-347`) have not been measured on real data, and
the harness explicitly excludes mainnet-scale sync, multi-day soak and sustained query load
(`harness/README.md:30-33`). A from-0 mainnet sync with archive ON is exactly the workload those
gates target — sustained capture write-throughput, publisher catch-up, multi-day soak, disk/heap
growth — and the **fail-stop design** means an archive error (disk full, corruption, an untested
edge) halts the node mid-sync. So: validate the scale profile on non-critical infra first.

## Config to enable archive (the ON path under test)

`storage.archive.*` keys are **whitelisted**: an unknown, renamed or stale key aborts startup with
`IllegalArgumentException` before the node ever opens a database
(`StorageConfig.java:521-570` → `requireOnlyKeys`, `StorageConfig.java:572-585`). Copy the block
below verbatim; do not invent keys.

**This block is valid HOCON and was parse-checked.** Field separators inside `{ }` must be commas
or newlines — **a semicolon is a parse error** (`Expecting close brace } or a comma, got '='`), and
so is a literal `...`. Earlier revisions of this runbook printed both and would not have booted.

`config.conf`:
```hocon
storage {
  archive {
    enable = true
    db { directory = "archive", fullScrubOnStartup = false }
    # true ONLY on the very first boot of a NEW empty canonical+archive data dir.
    identity { initialize = true }
    txnum { enable = true }
    temporal { enable = true }
    publisher { async = true, backpressure = true }
    query {
      jsonRpcWorkerThreads = 2
      maxConcurrentQueries = 8
      maxPendingQueries = 16
      maxOpenSnapshots = 8
      deadlineMs = 30000
    }
    debug { enable = false }
  }
}

node.metrics.prometheus { enable = true, port = 9527 }
```

Every value above is the shipped default (`reference.conf:142-219`, mirrored in
`config.conf:45-101`), so the block is a *declaration of intent*, not a tuning change. The one
exception is `enable`/`identity.initialize`. Anything not listed keeps its `reference.conf` default;
in particular the publisher watermarks (`soft/hardInFlightBlocks|Bytes|Records`,
`softMinFreeBytes = 5 GiB`, `hardMinFreeBytes = 1 GiB` — `reference.conf:167-174`) are production
values and should **not** be lowered on real infra. The harness lowers them
(`harness/lib.sh:892-898`) only because its test volume is a few hundred MB.

Constraints the parser enforces, so you cannot "simplify" the block:
- `txnum.enable` and `temporal.enable` **cannot be false** while `enable = true`
  (`StorageConfig.java:202-207`).
- `coverage` must stay `"TVM_STATE_ONLY"` and `warnUnclassifiedStoreWrites` must stay `true`
  (`StorageConfig.java:190-201`).
- `commitment.enable` / `commitment.persistTxRoots` are rejected while archive is enabled
  (`StorageConfig.java:227-234`).
- `debug.enable = true` requires `enable = true` (`StorageConfig.java:223-226`).
- There is **no** persistence/format key. UNIFIED_V1 is the only backend; nothing selects it.

Two operational rules:
- `identity.initialize = true` **only** for the very first boot of a fresh empty DB (it claims the
  anchor/root). Normal restarts must run with it `false` (they validate the existing pair; auto-claim
  is off by design). `reference.conf:150-154`; the harness flips it automatically on every restart
  (`harness/README.md:486-488`).
- `publisher.async = true` + `backpressure = true` (both already default) keep solidified journal
  drain off the block thread and make block mutation wait rather than OOM when a soft limit is hit.
  Watch the watermark gauges listed under § Watch these metrics.

### Keys this runbook deliberately does NOT set

The harness discovered four mandatory settings the hard way. Three of them are **private-chain
isolation knobs and must not be copied into a real deployment**:

| Setting | Harness needs it because | Real network? |
|---|---|---|
| `node.rpc.minEffectiveConnection = 0` | the default `1` makes `Wallet.broadcastTransaction` return `NO_CONNECTION` on a peerless node (`harness/README.md:439-441`) | **No.** A node with real peers must keep the default; setting 0 disables a broadcast safety check. |
| `node.fastForward = [ ]` | the default is two **public mainnet** addresses (`reference.conf:341-345`); an isolated node dials them forever (`harness/README.md:443-445`) | **No.** On mainnet the default is the intended behavior. |
| `committee { … }` startup governance flags | a private chain has no on-chain proposals, so TVM stays pre-Constantinople and contract code is never persisted (`harness/README.md:449-459`) | **No.** A real chain gets these from proposals already in its history. |
| `node.minParticipationRate = 0` | DPoS refuses to produce on a private chain | **Already the default** — `reference.conf:289` ships `0`. Neither the harness nor this runbook changes it. Nothing to do. |

The fourth — the `identity.initialize` true-only-on-first-boot rule — **does** apply to a real
deployment and is stated above.

## Staged plan (do NOT skip to production)

### Stage A — archive-OFF baseline (sanity, ~hours)
Sync (or fast-forward) the same target with `archive.enable = false`. Record block-push p50/p95/p99
from `tron:block_push_latency_seconds` (histogram, **no labels** —
`MetricsHistogram.java:32`, emitted at `Manager.java:1901`), plus peak heap and disk. This is the
**regression baseline** the gates compare against.

### Stage B — archive-ON from-0 sync on **testnet / non-critical infra** (the core, days)
Fresh empty DB, `enable = true`, `identity.initialize = true`. Persistent storage is always
UNIFIED_V1. Sync from genesis to head. Measure continuously (§ Watch these metrics). Pass criteria:
- Sync **completes** to head without a fail-stop (any `TronError` exit or a persisted
  `repair-required` marker → capture the log + DB state, treat as a **blocker**, do not proceed).
- Publisher keeps up: lag bounded, `oldest_inflight_block` advances, and the 5-minute published
  rate is ≥ 2× the journal production rate while catching up (gate 5).
- Archive-ON block-push overhead vs Stage A recorded as a **number** — see gate 3 for why this is
  not the same thing as the design doc's 5% gate.
- Disk growth is linear and within budget. Note that `tron:db_size_bytes` does **not** cover the
  archive directory (`DbStatService` is registered only from `TronDatabase.java:53` and
  `TronStoreWithRevoking.java:89`, i.e. canonical stores, and polls every 6 h —
  `DbStatService.java:21`). Measure the archive directory on the filesystem, and watch
  `tron:archive_state{type="disk_free_bytes"}`.
- Retained heap flat over the run (no leak — gate 7).
- Restart mid-sync (`kill -9` at a random point) → node reopens cleanly **without**
  `fullScrubOnStartup` and **without** a brick. Caveat: this is not universal by design — a kill in
  the genesis-marker window is *supposed* to fail-stop (harness window w6 records
  `FAILSTOP`, exit 1, `repair=true` — `harness/README.md:622`). "Full recovery **or** an explicit
  fail-stop" is the contract; silent acceptance of a half state is the failure.

### Stage C — query correctness + soak (days)
- **Correctness:** against an independent full/archive node, diff the **four methods that are
  actually served from the archive** at a sample of historical blocks:
  `eth_getBalance`, `eth_getCode`, `eth_getStorageAt` (`ArchiveJsonRpcStateAdapter.java:20-24`,
  `:45`, `:72`, `:97`) and `eth_call` (`HistoricalEthCallSupport`, dispatched at
  `TronJsonRpcImpl.java:1036-1046`). Include an SSTORE-heavy contract, a delete-recreate account, a
  TRC10 asset, and a block just before a fork-flag activation. Any mismatch = blocker.
  **Do not test `eth_getTransactionCount`** — java-tron does not implement it at all, historical or
  latest; it always throws `-32601` (`TronJsonRpcImpl.java:1612-1617`). A historical diff of it
  would be a test of nothing.
  Use explicit hex block numbers, never `"finalized"` (it resolves to the solid block, which may not
  be published yet and fails closed — `harness/README.md:478-480`).
- **Debug trace:** a default-off node must return JSON-RPC `-32601` for `debug_traceCall` and
  `debug_traceTransaction` (`TronJsonRpcImpl.java:1133-1137` → `TronJsonRpc.java` `-32601`
  mapping). On a **dedicated node started from genesis with `storage.archive.debug.enable = true`**,
  compare both `structLogs` (the default) and `callTracer` output — those are the only two tracers
  accepted; anything else is rejected as `unsupported tracer` (`DebugTraceOptions.java:55-65`), and
  `callTracer` `withLog=true` is rejected (`:103-104`). Spot-diff `debug_traceTransaction` against
  an independent node.
  Blocks captured while `debug.enable` was false have no VM pre-state position allocated
  (`DefaultArchiveService.java:836-838` gates `beginUserVmTx` on `captureVmPreState`, wired from
  `config.getDebug().isEnable()` at `ArchiveServiceFactory.java:221`). Tracing them fails closed:
  internally `UnsupportedHistoricalStateException`, surfaced to the operator as **JSON-RPC `-32000`**
  carrying that message (`HistoricalDebugTraceSupport.java:209-213`, `:474-477`; `-32000` mapping in
  `TronJsonRpc.java:95`). A partial trace is never returned. Enabling debug on an existing archive
  does **not** retroactively make older blocks traceable.
- **Soak:** run the §4 mixed query load for ≥72 h against a finality-stall/catch-up cycle.
  The design doc's fixed load is **32 concurrent getters / 8 concurrent `eth_call` / 2 concurrent
  traces**, plus a separate 2-concurrent deadline-bound mid-chain profile
  (`20260713-archive-performance-hardening-plan.md:331-332`) — not the 40/8 figure this runbook
  previously printed.
  **Read this before running that load:** the shipped admission limits are `maxConcurrentQueries = 8`
  / `maxPendingQueries = 16` and `debug.maxConcurrentTraces = 1` / `maxPendingTraces = 1`
  (`reference.conf:185-186`, `:212-213`). At 32 concurrent getters and 2 concurrent traces against
  defaults, the excess is **rejected by design** with `-32005`, not queued. Either raise the limits
  for the soak and say so in the report, or record the rejection rate as part of the result. Do not
  report a soak run at default limits as if it had applied 32-way concurrency.
  Gate 7's pass criterion is the numeric one, not "looks flat" — see the gate table.

### Stage D — production go/no-go
Only after Stage B + C pass on non-critical infra **and** the remaining UNIFIED activation gates
pass. The private-chain fault harness (`docs/archiveV3/harness/`, suite green at
`PRIVATE_CHAIN_FAULT_SUITE_OK scenarios=7 passed=7`, `harness/README.md:620`) now discharges part
of that list. Be precise about which part — it is a single-host private chain whose runs reach head
heights in the tens of blocks (highest recorded: `publishedHead=77`, `harness/README.md:616`) and
says nothing about scale:

| Activation gate | Status | Evidence / why not |
|---|---|---|
| Independent-oracle historical conformance | **Discharged at private-chain scale.** `scenario-history-accuracy.sh`, 27-SR chain, `HISTORY_ACCURACY_OK checks=61 pass=57 fail=0 info=4` against five non-circular oracle kinds (`harness/README.md:568-595`, `:616`). | Not mainnet corpus, not mainnet block shapes. |
| Kill matrix (journal / canonical commit / ack / publish / genesis marker) | **Discharged at private-chain scale.** `scenario-kill-matrix.sh` 6 deterministic JDWP windows, `KILL_MATRIX_OK checks=6 windows=6` (`harness/README.md:622`); plus `scenario-catchup-batch-flush-kill.sh` for a kill inside a batched `SnapshotManager.flush`. | Single host, small DB, no compaction backlog at kill time. |
| ENOSPC / read-only dir / device disappearance | **Discharged at private-chain scale.** `scenario-resource-faults.sh` (`harness/README.md:618`). Note the harness's own finding: ENOSPC correctly does **not** set `repair-required`, because the failure precedes the canonical commit (`harness/README.md:657`). | The mid-run permission fault is inconclusive by construction (RocksDB keeps writing through already-open fds); only the read-only *restart* is load-bearing. |
| Fork / reorg with archive enabled | **Discharged at private-chain scale.** `scenario-fork-reorg.sh`, `FORK_E2E_OK checks=20 depth=6 switches=1`, incl. the multi-witness restart regression (`harness/README.md:617`, `:523-543`). | "Unwind exactly once" is asserted from log lines, not a counter — `ArchiveMetrics` exposes none (`harness/README.md:649-651`). |
| Historical queries concurrent with unwind and with fail-stop | **Discharged at private-chain scale.** `scenario-concurrency-under-fault.sh`, 5 phases incl. a real 7-block reorg (`harness/README.md:619`). | |
| **Corrupt-CF / byte-level SST scrub coverage** | **NOT discharged.** The harness's only corruption case truncates the newest `MANIFEST` (`scenario-resource-faults.sh:510-549`); byte-level SST corruption is explicitly out of scope (`harness/README.md:30-31`). No scenario exercises the corrupt-CF scrub path. | Still open. |
| **Wrong / missing / partial archive-root rejection** | **Partially discharged.** `validateArchiveRootBeforeOpen` (`ArchiveServiceFactory.java:263-300`) is exercised for a missing/unopenable root by the resource-fault restarts; a *wrong* root (archive from another chain paired with this canonical DB) has no scenario. | Still open for the wrong-pairing case. |
| **Mainnet-scale sync, 72 h soak, disk/heap growth, catch-up throughput** | **NOT discharged — this is exactly what Stages B and C exist to measure.** `harness/README.md:30-33` states it. | Still open. |

For production, use a **fresh from-0 archive sync on standby infra**, promote after it reaches head
and passes Stage C spot-checks. Keep a non-archive node as the consensus fallback.

## §4 acceptance gates → actionable pass/fail

Source of truth: `20260713-archive-performance-hardening-plan.md:336-347`.

| # | Gate | How to check | Blocker if… |
|---|------|--------------|-------------|
| 1 | archive-OFF adds exactly 0 extra DB read/write; write bytes/hash == baseline | **Not measurable from metrics** — there is no write-bytes series, and `tron:db_size_bytes` is a 6-hourly size gauge over canonical stores only (`DbStatService.java:21`). The available evidence is the source invariant plus `chainbase/src/test/java/org/tron/core/db/TronStoreWithRevokingArchiveOffTest.java`. To measure it on real infra you need an offline byte/checksum diff of two DB directories after identical replay. | any archive-off delta |
| 2 | metrics-only archive-off block-push p99 regression ≤ 1% | Stage A twice: `node.metrics.prometheus.enable` on vs off, `tron:block_push_latency_seconds` p99. When metrics are off the histogram does not exist, so the "off" arm must be timed outside Prometheus. | > 1% |
| 3 | archive-ON block-push p95/p99 regression ≤ 5% **vs the same config before the optimization** | **The design gate's baseline does not exist for a from-0 deployment** — it compares archive-ON to a *pre-optimization archive-ON* build, not to archive-OFF. Stage B vs Stage A measures a **different and stricter** quantity: total archive-ON overhead vs a stock node. Record that number; do **not** report it as gate 3 discharged. Gate 3 is only measurable by rebuilding a pre-opt archive-ON binary. | (gate 3 unmeasurable here; report the Stage B/A overhead as its own line) |
| 4 | finality lag 1→10k: single-block maintenance cost does not grow *linearly* with lag | Force a stall and watch the per-block stage quantiles: `tron:archive_stage_latency_seconds_bucket{stage="journal"}` and `{stage="previous_value_reads_block"}` while `tron:archive_state{type="publisher_lag_blocks"}` climbs. Label key is `stage` (`MetricsHistogram.java:51-52`); valid values are `journal`, `journal_ack`, `publish`, `publish_failed`, `previous_value_reads_block`, `account_asset_diff_block` (`ArchiveMetrics.java:65,72,89,104,129`). | latency scales with lag |
| 5 | publisher catch-up ≥ 2× peak journal produce rate | The PromQL below; watch `tron:archive_state{type="publisher_lag_blocks"}` alongside. | < 2× |
| 6 | after a 10k stall clears, lag → 0 within the time to make 5k blocks; catch-up p99 regression ≤ 5% (genesis-complete) / ≤ 10% (mid-chain) | Stage B stall/recover | exceeds |
| 7 | 72 h soak: after a 6 h warmup, the retained-heap linear-regression slope is ≤ **0.5 %/hour of allocated heap**; `active_snapshots` stays ≤ the configured cap; and while input rate is below 50 % of catch-up capacity, lag must not increase for 10 consecutive minutes | Stage C heap trend + `tron:archive_state{type="active_snapshots"}` vs `query.maxOpenSnapshots` + `{type="publisher_lag_blocks"}` | slope > 0.5 %/h, snapshots pinned at cap, or 10 min of monotonic lag growth |

## Watch these metrics (Prometheus)

All series below were verified to exist **and be emitted**. Counters carry the `_total` suffix in
the exposition format; the gauge and histograms do not.

| Series | Type | Label | Registered | Emitted |
|---|---|---|---|---|
| `tron:archive_work_total` | counter | `type` | `MetricsCounter.java:22` | `ArchiveMetrics.java:231-235` |
| `tron:archive_queries_total` | counter | `result` | `MetricsCounter.java:23` | `ArchiveMetrics.java:185`, `:215` |
| `tron:archive_query_resources_total` | counter | `type` | `MetricsCounter.java:24` | `ArchiveMetrics.java:237-241` |
| `tron:archive_state` | gauge | `type` | `MetricsGauge.java:22` | `ArchiveMetrics.java:243-255` |
| `tron:archive_stage_latency_seconds_*` | histogram | `stage` | `MetricsHistogram.java:51` | `ArchiveMetrics.java:265-268` |
| `tron:archive_query_latency_seconds_*` | histogram | `result` | `MetricsHistogram.java:53` | `ArchiveMetrics.java:223-224` |

`tron:archive_state` `type` values in use: `repair_required`, `publisher_lag_blocks`,
`oldest_inflight_block`, `disk_free_bytes`, `active_snapshots`, `metrics_dropped_reports`,
`inflight_blocks`, `inflight_records`, `inflight_bytes`, `inflight_resource_bytes`,
`active_queries`, `pending_queries` (`ArchiveMetrics.java:146-176`, `:391`, `:508-514`). All six
gauges the alerts below depend on have live emission sites:
`repair_required` (`DefaultArchiveService.java:332`, `:733`, `:2571`, `:3633`),
`publisher_lag_blocks` (`:1875`), `oldest_inflight_block` (`:1864`),
`disk_free_bytes` (`:2295`), `active_snapshots` (`ArchiveQueryCoordinator.java:49`),
`metrics_dropped_reports` (`ArchiveMetrics.java:388-393`).

Alert on:
- `tron:archive_state{type="repair_required"} != 0`.
- `tron:archive_state{type="publisher_lag_blocks"}` climbing while
  `tron:archive_state{type="oldest_inflight_block"}` remains fixed.
- `tron:archive_state{type="disk_free_bytes"} < hardMinFreeBytes` or
  `tron:archive_state{type="active_snapshots"}` pinned at `query.maxOpenSnapshots`.
- `tron:archive_state{type="metrics_dropped_reports"} > 0`; this is a sticky
  process-lifetime signal that archive metrics were dropped and may have been stale. Investigate
  the reporter backlog; the signal clears only when the process restarts.
- `tron:archive_queries_total` spiking on the failure/rejection values of its **`result`** label:
  `failed`, or any `rejected_<reason>` / terminal `<reason>` value (`ArchiveMetrics.java:183`,
  `:199-205`). `completed` is the success value.

Archive does not poll RocksDB property/statistics JNI at runtime because those calls have no
enforceable deadline (R19-05,
`20260718-archive-round19-publisher-query-observability-review.md:60`). There is deliberately **no**
`rocksdb_*` series to alert on. During a validation run, inspect the archive database's RocksDB
`LOG` and host disk-latency/throughput metrics for compaction backlog and write stalls. Use archive
query latency and process RSS when evaluating the fixed archive cache budget. These are tuning
inputs, not pass criteria by themselves.

Derive catch-up from counters over the same window; do not compare cumulative totals:
```promql
rate(tron:archive_work_total{type="published_blocks"}[5m])
/
ignoring(type)
clamp_min(rate(tron:archive_work_total{type="journal_blocks"}[5m]), 0.000001)
```
`ignoring(type)` is required because the two sides differ only in that label; `rate()` already drops
`__name__`, so the remaining `job`/`instance` labels match one-to-one. Both counters exist
(`ArchiveMetrics.java:128` `published_blocks`, `:87` `journal_blocks`). **A Prometheus counter does
not exist until first incremented**, so before the first publish this expression returns *no data*,
not `0` — treat an empty result as "publication has never happened", which is itself a finding
(`harness/README.md:465-468`).

## Fail-stop playbook (operators)

Archive errors are **deliberately fail-stop**: `markFatal` → node exits, and a `repair-required`
marker is persisted first (`DefaultArchiveService.java:3631-3642`).

**Exit codes.** `TronError` is routed to `System.exit(errCode)` by the default uncaught-exception
handler (`ExitManager.java:23-52`).
- **1** — the archive fail-stop path. Two distinct `ErrCode`s both map to 1
  (`TronError.java:39,42`): `ARCHIVE_RUNTIME` (runtime capture/publish failures, and startup
  reconciliation wrapped at `Manager.java:926-930`) and `GENESIS_BLOCK_INIT` (the genesis
  atomicity fence and genesis-coverage validation — `Manager.java:789-805`, `:933-975`). Read the
  `Shutting down with code: <ERRCODE>` line to tell them apart.
  Caveat: `ARCHIVE_RUNTIME` is **also** used for two non-archive reorg-rewind failures that can fire
  on an archive-OFF node (`Manager.java:1624-1627`, `:1701-1703`). Seeing `ARCHIVE_RUNTIME` does not
  by itself prove the archive sidecar failed.
- **70** — archive fatal handling exceeded its 30 s watchdog deadline (including a stuck
  repair-marker write or shutdown callback) and forced `Runtime.halt`
  (`ArchiveFatalController.java:9-10`, `:190-232`). A breadcrumb —
  `archive fatal watchdog timeout; halting with exit status 70: <detail>` — goes to **stderr**
  immediately before the halt (`:224-226`) and never reaches `tron.log`. Capture stderr.
- **143** is a normal `SIGTERM` shutdown, not a fault. **0** after an archive failure would itself
  be a bug.
- **Do not judge fail-stop by exit code alone.** A process that stays alive while HTTP never
  becomes ready is a real failure mode the harness has observed and named `HUNG`
  (`harness/README.md:219-233`, `:523-533`).

**What `repair-required` looks like on disk.** It is **not a file**. It is a key in the `META`
column family of the archive's UNIFIED_V1 RocksDB (`ArchiveBlockRangeCodec.java:48`
`REPAIR_REQUIRED_KEY`, written durably at `UnifiedArchiveTxNumIndex.java:398-409`). While the node
is running, the only observation point is `tron:archive_state{type="repair_required"}`. Offline, use
the harness's `ArchiveProbe` (`docs/archiveV3/harness/ArchiveProbe.java`), which reports the key.

**There is no operator command to clear it, and you must not try.** `clearRepairRequired` requires
an `ArchiveRepairClearPermit` whose constructor is package-private and is instantiated only inside
`DefaultArchiveService` (`ArchiveRepairClearPermit.java:4-7`, `DefaultArchiveService.java:144`).
The node clears it itself, and only after a successful recovery
(`DefaultArchiveService.java:729-734`).

**What the next startup actually does.** A persisted marker sets
`recoveryScrub = fullScrubOnStartup || hasRepairRequired()`
(`ArchiveServiceFactory.java:200-203`), which forces the **full** startup scrub
(`UnifiedArchiveBackend.java:195-222`: whole-keyspace meta validation, every committed block's
temporal rows, txNum coverage, domain rows). That is **O(database size)**. On a mainnet-sized
archive, plan for a very long first restart after any fail-stop, and do not mistake it for a hang.
If the scrub passes, the marker is cleared automatically and the node comes up. If it fails,
startup throws `archive repair required: <reason>`
(`UnifiedArchiveTxNumIndex.java:537-540`) and the node stays down.

When a fail-stop happens:
1. Capture the node log (the original error is logged with stack before exit), **stderr** (the only
   place an exit-70 breadcrumb appears), and the archive DB dir.
2. Diagnose before restarting: disk full? corruption? an untested edge? Check
   `tron:archive_state{type="disk_free_bytes"}` history first — a disk-space fail-stop is
   environmental and the recovery is "add space, restart, let the scrub run".
3. Do not delete only the archive directory and try to reuse the existing canonical DB: identity
   binding rejects that pairing (`ArchiveServiceFactory.java:263-300`,
   `Manager.java:789-805`), and there is no in-place historical backfill tool. Keep or copy the
   failed directory for diagnosis, switch that node to archive-off if a consensus fallback is
   needed, and rebuild on standby from a **new empty full data directory** so canonical + archive
   sync together from genesis. The archive identity records an **absolute** path, so a data
   directory cannot be moved or copied to a different path (`harness/README.md:490-491`).
   Promote the standby only after the activation gates pass.
4. File the edge case — a fail-stop during a from-0 sync that is *not* environmental is a code bug.

## Bottom line

Archive-OFF: production-ready now (byte-identical). **Archive-ON from-0: gate on Stage B + C and the
remaining UNIFIED activation gates in Stage D before production.** The private-chain harness has
closed the correctness-under-fault gaps at its own scale; **scale, soak, disk/heap growth and
catch-up-throughput evidence are still entirely missing**, and gate 1 and gate 3 are not measurable
with what exists today (see the gate table). Neither can be replaced by unit review alone.
