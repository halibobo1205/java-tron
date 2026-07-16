# From-0 archive-node production validation runbook

**Purpose:** the go/no-go gate before running an **archive-ON** node syncing from genesis in production.
**Scope:** archive-nodes-only. **Archive-OFF is out of scope** — it is byte-identical to a normal node
(verified across three review rounds: `capturesStore` short-circuits on `engine==null`), so an
archive-OFF from-0 sync carries no archive risk and needs no validation here.

## Why this gate exists

The archive code is functionally reviewed (3 adversarial rounds, no confirmed high/critical) and unit
+ integration green. What is **not** yet demonstrated is behavior **at mainnet scale**: the design
doc's own hard acceptance gates (`20260713-archive-performance-hardening-plan.md` §4) have not been
measured on real data. A from-0 mainnet sync with archive ON is exactly the workload those gates
target — sustained capture write-throughput, publisher catch-up, multi-day soak, disk/heap growth —
and the **fail-stop design** means any archive error (disk full, corruption, an untested edge) halts
the node mid-sync. So: validate the scale profile on non-critical infra first.

## Config to enable archive (the ON path under test)

`config.conf` → `storage.archive`:
```
archive {
  enable = true
  db { directory = "archive"; fullScrubOnStartup = false }
  identity { initialize = true }   # first boot of a NEW empty canonical+archive DB only
  txnum.enable = true
  temporal.enable = true
  publisher { async = true; backpressure = true; ... }   # async worker + backpressure ON for a real sync
  query { maxConcurrentQueries = 8; maxOpenSnapshots = 8; deadlineMs = 30000; ... }
}
```
Also enable the exporter used by every metric gate:
```
node.metrics.prometheus { enable = true; port = 9527 }
```
- `identity.initialize = true` **only** for the very first boot of a fresh empty DB (it claims the
  anchor/root). Normal restarts must run with it `false` (they validate the existing pair; auto-claim
  is off by design).
- Turn `publisher.async = true` + `backpressure = true` for a from-0 sync so solidified journal drain
  runs off the block thread and block mutation waits (rather than OOMs) when the in-flight hard cap
  is hit. Watch the watermarks (`soft/hardInFlight{Blocks,Bytes,Records}`, `soft/hardMinFreeBytes`).

## Staged plan (do NOT skip to production)

### Stage A — archive-OFF baseline (sanity, ~hours)
Sync (or fast-forward) the same target with `archive.enable = false`. Record: block-push p50/p95/p99,
peak heap, disk. This is the **regression baseline** the gates compare against. Expectation: identical
to a stock node (gate 1 below).

### Stage B — archive-ON from-0 sync on **testnet / non-critical infra** (the core, days)
Fresh empty DB, `enable = true`, `identity.initialize = true`. Persistent storage is always
UNIFIED_V1. Sync from genesis to head. Measure
continuously (§ Watch metrics). Pass criteria:
- Sync **completes** to head without a fail-stop (any `TronError`/`markRepairRequired` → capture the
  log + DB state, treat as a **blocker**, do not proceed).
- Block-push p95/p99 regression vs Stage A within gate 3 (≤5%).
- Publisher keeps up: lag bounded, `oldest_inflight_block` advances, and the 5-minute published
  rate is ≥ 2× the journal production rate while catching up (gate 5).
- Disk growth is linear and within budget; **retained heap flat over the run** (no leak — gate 7).
- Restart mid-sync (kill -9 at a random point) → node reopens cleanly (identity/schema validate,
  reconcile publishes solidified in-flight) **without** `fullScrubOnStartup` and **without** a brick.

### Stage C — query correctness + soak (days)
- **Correctness:** against an independent full/archive node, diff `eth_call` / `debug_traceTransaction`
  / `eth_getBalance` / `eth_getStorageAt` at a **sample of historical blocks** (include an SSTORE-heavy
  contract, a delete-recreate account, a TRC10 asset, and a block just before a fork-flag activation).
  Any mismatch = blocker.
- **Soak:** run the §4 mixed query load (32 getters / 8 eth_call / 2 trace concurrent) for ≥72h against
  a finality-stall/catch-up cycle; retained heap flat after a 6h warmup (gate 7).

### Stage D — production go/no-go
Only after Stage B + C pass on non-critical infra **and** the remaining UNIFIED activation gates
pass: independent-oracle conformance, the real kill/ENOSPC/WAL/CF fault matrix, corrupt-CF scrub
coverage, and wrong/missing/partial archive-root rejection. For
production, use a **fresh from-0 archive sync on standby infra**, promote after it reaches head and
passes Stage C spot-checks. Keep a non-archive node as the consensus fallback.

## §4 acceptance gates → actionable pass/fail

| # | Gate | How to check | Blocker if… |
|---|------|--------------|-------------|
| 1 | archive-OFF adds exactly 0 extra DB read/write; write bytes/hash == baseline | Stage A vs a stock build; diff write metrics | any archive-off delta |
| 2 | metrics-only archive-off block-push p99 regression ≤ 1% | Stage A with metrics on vs off | > 1% |
| 3 | archive-ON block-push p95/p99 regression ≤ 5% vs same config pre-opt | Stage B vs Stage A | > 5% |
| 4 | finality lag 1→10k: single-block maintenance cost does not grow with lag | force a stall, watch `ARCHIVE_STAGE_LATENCY` per block | latency scales with lag |
| 5 | publisher catch-up ≥ 2× peak journal produce rate | compare 5m rates of `published_blocks` / `journal_blocks`; watch `publisher_lag_blocks` | < 2× |
| 6 | after a 10k stall clears, lag → 0 within the time to make 5k blocks; catch-up p99 regression ≤5% (genesis) / ≤10% (mid) | Stage B stall/recover | exceeds |
| 7 | 72h soak: retained heap flat after 6h warmup | Stage C heap trend | monotonic growth |

## Watch these metrics (Prometheus)

Scrape `tron:archive_work_total`, `tron:archive_queries_total`,
`tron:archive_query_resources_total`, `tron:archive_state`, and the
`tron:archive_stage_latency_seconds_*` / `tron:archive_query_latency_seconds_*` histogram series.
Alert on:
- `tron:archive_state{type="repair_required"} != 0`.
- `tron:archive_state{type="publisher_lag_blocks"}` climbing while
  `tron:archive_state{type="oldest_inflight_block"}` remains fixed.
- `tron:archive_state{type="disk_free_bytes"} < hardMinFreeBytes` or
  `tron:archive_state{type="active_snapshots"}` pinned at `maxOpenSnapshots`.
- `tron:archive_state{type="retained_trace_bytes"}` approaching
  `storage.archive.query.maxRetainedTraceBytes`.
- `tron:archive_state{type="rocksdb_pending_compaction_bytes"}` rising without recovery, or a
  sustained increase in `tron:archive_work_total{type="rocksdb_stall_micros"}`.
- `tron:archive_queries_total` failure/rejection labels spiking.

Track `rocksdb_bloom_filter_useful`, `rocksdb_block_cache_hit`, and
`rocksdb_block_cache_miss` as rates over the same interval. They are measurement inputs for cache
and compaction sizing, not pass criteria by themselves.

Derive catch-up from counters over the same window; do not compare cumulative totals:
```
rate(tron:archive_work_total{type="published_blocks"}[5m])
/
ignoring(type)
clamp_min(rate(tron:archive_work_total{type="journal_blocks"}[5m]), 0.000001)
```

## Fail-stop playbook (operators)

Archive errors are **deliberately fail-stop** (markFatal → node exits; `markRepairRequired` persists).
Exit code `1` is the normal archive `TronError.ARCHIVE_RUNTIME` fail-stop path. Exit code `70` means
archive fatal handling exceeded its watchdog deadline (including a stuck repair-marker write or
shutdown callback) and forced `Runtime.halt`; stderr emits a breadcrumb immediately before that halt.
When it happens:
1. Capture the node log (the original error is logged with stack before exit) + the archive DB dir.
2. Do **not** clear `repair-required` blindly. Diagnose: disk full? corruption? an untested edge?
3. Do not delete only the archive directory and try to reuse the existing canonical DB: identity
   binding rejects that pairing, and there is no in-place historical backfill tool. Keep or copy the
   failed directory for diagnosis, switch that node to archive-off if a consensus fallback is needed,
   and rebuild on standby from a **new empty full data directory** so canonical + archive sync together
   from genesis. Promote the standby only after the activation gates pass.
4. File the edge case — a fail-stop during a from-0 sync that is *not* environmental is a code bug.

## Bottom line

Archive-OFF: production-ready now (byte-identical). **Archive-ON from-0: gate on Stage B + C and the
remaining UNIFIED M3 fault/differential checks above before production.** Scale/soak evidence and
real durability-fault evidence are still missing; neither can be replaced by unit review alone.
