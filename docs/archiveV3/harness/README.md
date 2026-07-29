# Archive node fault-injection harness

A committed, re-runnable harness that drives a **real java-tron private chain with the archive
sidecar enabled** and injects faults at the points where the archive's durability and fail-stop
design is supposed to hold.

Everything here is test tooling. **No production source is modified.** The harness only writes
config files, starts and signals node processes, and talks to the node's own HTTP / JSON-RPC /
Prometheus endpoints.

---

## 1. What this harness proves (and what it does not)

Prior archive E2E runs (`20260719-archive-schema6-complete-e2e-results.md`,
`20260728-archive-schema7-round8-e2e-results.md`) covered the **happy path**: normal transactions,
historical getters, debug traces, one clean restart, one opportunistic `SIGKILL`, and an offline
probe. Those scripts were ephemeral under `/tmp` and were never committed.

This harness exists to close the four gaps every prior audit round flagged as "no test anywhere":

| | Scenario | Core question |
|---|---|---|
| **A** | Fork / reorg with archive enabled | Is the orphan journal rolled back **exactly once**, is the winning branch re-captured with fresh gap-free txNums, do pre-fork historical queries still return pre-fork values, and do orphaned heights **fail closed**? |
| **B** | `SIGKILL` durability matrix | At each durability window (journal put / canonical commit / ack / publish / genesis marker), does restart give **full recovery** or an **explicit fail-stop**? Silent acceptance of a half state is a FAILURE. |
| **C** | Resource faults | ENOSPC, read-only archive dir, truncated files, device disappearance: fail-stop, never silent divergence. |
| **D** | Concurrency under fault | Historical queries in flight during a reorg unwind and during a fail-stop. |
| **E** | Historical **accuracy** on a healthy chain | When the archive *does* answer, is the answer **right**? A–D all ask whether the archive survives; E asks whether a surviving archive returns the value that was actually in force at height H, for normal transfers, contract state and system (SR-reward) writes — checked only against **independent** oracles (see §9). |

**Not proven here:** mainnet-scale sync, multi-day soak, sustained production query load, or
byte-level SST corruption. This is correctness-under-fault on a local private chain — it is not a
production release gate. See `20260714-archive-from0-production-validation-runbook.md` for the
deployment qualification work that remains.

---

## 2. Layout

```
docs/archiveV3/harness/
  lib.sh                        the shared foundation -- all `hs_*` helpers
  scenario-common.sh            companion `ah_*` layer, sourced AFTER lib.sh; every helper is
                                guarded by `declare -F`, so a lib.sh implementation always wins
  ports.sh                      THE port map -- one 400-port band per scenario, one 10-port
                                block per node. Sourced by lib.sh, scenario-common.sh, the
                                standalone concurrency scenario and run-all.sh, so there is
                                exactly one table and no scenario can invent its own. See §12.
  anchor.sh                     THE anchor registry -- every JDWP breakpoint as a SEMANTIC
                                descriptor (class + method + "the statement matching R"),
                                resolved at run time against the current source AND jar.
                                Both a sourced library (`hs_anchor_*`) and a CLI
                                (`./anchor.sh`, `--list`, `--selftest`). See §10.
  anchor_resolve.py             the resolver anchor.sh shells out to: stdlib-only Python 3,
                                masks comments/literals, brace-matches the method body,
                                cross-checks javap's line table and solves the jar-vs-source
                                line offset. The harness's only `python3` dependency.
  README.md                     this file
  run-all.sh                    suite driver (discovery, ordering, per-scenario timeout,
                                port pre-flight, roll-up)

  scenario-smoke.sh             foundation self-test AND the worked template for A-D
  scenario-history-accuracy.sh  (E) historical ACCURACY on a healthy 27-SR chain: normal
                                transfers, contract storage/code/eth_call, SELFDESTRUCT and
                                SR block rewards, each checked against an independent oracle
  scenario-fork-reorg.sh        (A) fork / reorg across a real Manager.switchFork
  scenario-kill-matrix.sh       (B) the six SIGKILL durability windows
  scenario-resource-faults.sh   (C) ENOSPC, read-only archive dir, truncated MANIFEST
  scenario-concurrency-under-fault.sh  (D) query storm across clean stop / SIGKILL / reorg
  scenario-catchup-batch-flush-kill.sh (F) SIGKILL inside a BATCHED SnapshotManager.flush on a
                                26+1 split chain, then catch-up: the batch window is entered
                                deterministically via the `cfk.flush` anchor

  ArchiveProbe.java             offline RocksDB probe: ranges, txNum gaps, span violations,
                                stale in-flight rows, the repair-required META key.
                                *At the harness ROOT, not under java/* -- lib.sh and three
                                scenarios all compile it from here.
  HarnessSigner.java            address derivation + txID signing for the `ah_*` scenarios
  java/Addr.java                private key -> TRON address (used to verify the key table)
  java/Sign.java                txID -> 65-byte signature (there is NO server-side signing servlet)
```

`run-all.sh` discovers scenarios with **two** filters, and both matter: the filename must match
`scenario-*.sh`, *and* the file must be executable. The glob is what keeps the sourced libraries
out of the suite regardless of their mode bits — which is why `anchor.sh` can be `+x` (it is also
a CLI) without ever being mistaken for a scenario. The executable bit is the second filter, and it
is what separates a real scenario from shared plumbing *within* that glob: `scenario-common.sh`
must stay non-executable and every real scenario must stay `+x`. A `scenario-*.sh` without the bit
is reported as "not run", never silently dropped.

`HarnessSigner.java` and `java/{Addr,Sign}.java` overlap: the `ah_*` scenarios use the former,
`lib.sh` the latter. Both are kept because collapsing them would mean re-verifying the address
derivation used by scenarios that have already been run green. Worth consolidating later.

`lib.sh` is the foundation this README documents in detail. It is written for **bash 3.2** (the
macOS system bash): no associative arrays, no `mapfile`, no `declare -g`. Every helper is safe
under `set -euo pipefail`.

---

## 3. Prerequisites

* **macOS or Linux.** The ENOSPC / device-disappearance helpers (`hs_make_small_volume`,
  `hs_detach_volume`) are `hdiutil`-based and macOS-only; everything else is portable.
* **JDK 17+** on `PATH` (`java`, `javac`). Verified on Temurin `17.0.17+10`, arm64.
* `curl`, `jq`, `python3`, `shasum`, `awk`, `sed`, `grep`, `df`, `dd`. `lsof` and `netstat` are
  used for the port preflight when present. `jdb` is needed only for the scenario-B windows.
* **Free TCP ports in 21000–24199.** Each scenario owns a 400-port band and each node a 10-port
  block inside it — `+0` p2p, `+1` HTTP, `+2` gRPC, `+3` JSON-RPC, `+4` Prometheus, `+5` JDWP.
  Every port is preflighted; a busy port is a harness error, never a silent reassignment. The
  full map, and why nothing may live above 49152, is §12.
* **Disk**: a few hundred MB per node under `$TMPDIR`.
* No `sudo`, no root, no network access. The generated config pins
  `node.fastForward = [ ]` and `seed.node.ip.list = [ ]` so nodes never dial the internet.

The jar is reused if present at `framework/build/libs/FullNode.jar`, otherwise built with
`./gradlew :framework:buildFullNodeJar`. Control this with:

* `HS_FORCE_BUILD=1` — always rebuild.
* `HS_SKIP_BUILD=1` — refuse to build; fail if no jar exists (fastest for iterating).

---

## 4. Running

```bash
cd docs/archiveV3/harness

./scenario-smoke.sh                  # foundation self-test, ~3 minutes
HS_SKIP_BUILD=1 ./scenario-smoke.sh  # reuse the existing jar
HS_KEEP_WORKDIR=1 ./scenario-smoke.sh  # keep the run dir for post-mortem

HS_CFG_WITNESS_COUNT=27 ./scenario-history-accuracy.sh   # (E) accuracy, ~12 minutes
```

`scenario-history-accuracy.sh` is the one scenario that defaults `HS_CFG_WITNESS_COUNT` to **27**
rather than 1 (a one-SR chain has `solid == head`, so "historical" barely means anything), and the
one that patches its OWN generated `node.conf` — `block.maintenanceTimeInterval` down to 30 s and a
28th, initially dormant `localwitness` key. Both are needed only so a **non-genesis** witness can
exist: `WithdrawBalanceActuator.java:112-120` refuses to let a genesis ("guard representative")
witness withdraw, and `MaintenanceManager.java:103-129` only promotes a newly voted witness at a
maintenance round. `lib.sh`'s shared defaults are untouched, so every other scenario is unaffected.
It is also the only scenario that **deletes its run directory on a clean pass** (failures keep it).

Each scenario is standalone and creates its own fresh run directory under
`${HS_WORK_ROOT:-$TMPDIR/java-tron-archive-harness}/<scenario>-<timestamp>-<pid>/`, containing per
node: `node.conf`, `ports.env`, `data/`, `logs/tron.log`, `stdout.log`, `stderr.log`, `node.pid`,
`node.exit`.

**Human-readable progress goes to stderr. Machine-checkable markers go to stdout.** So

```bash
./scenario-smoke.sh 2>/dev/null
```

prints only the verdict line.

---

## 5. The exit-code contract

Three outcomes, deliberately distinct — "the harness could not run" must never be mistaken for
"the product is fine" or "the product is broken":

| Exit | Meaning | Emitted by |
|-----:|---------|------------|
| **0** | Scenario passed; every gating check held. | `hs_finish` with zero failures |
| **1** | **The product misbehaved.** At least one check failed. | `hs_finish` with failures, or `hs_abort` |
| **2** | **Harness / environment error** — inconclusive, no verdict about the product. Missing tool, wrong JDK, busy port, build failure, unreachable jar. | `hs_die` / `ah_fatal` |
| **77** | Scenario decided it is not applicable on this host. | scenario-specific |

Never treat exit 2 as a pass or a fail. Fix the environment and re-run. A scenario exiting 2 also
prints a `HARNESS_ERROR <reason>` line, which is deliberately **not** in the `_OK`/`_FAIL`/`_SKIPPED`
marker grammar: it is not a verdict about the product. `run-all.sh` maps exit 2 (and a
`HARNESS_ERROR` line without any verdict marker) to the suite verdict `INCONCLUSIVE` — see §13.

---

## 6. Reading the markers

The last stdout line is always a single marker, in codex's established style:

```
SMOKE_E2E_OK checks=23 contract=41c8d6e5...928a oracles=5 finalHeight=8
```

On failure the same marker is emitted with `_OK` replaced by `_FAIL`, plus a failure count:

```
FORK_E2E_FAIL checks=17 failures=2 depth=12
```

| Scenario | Marker | Sub-verdicts |
|---|---|---|
| `scenario-smoke.sh` | `SMOKE_E2E_OK` / `_FAIL` | — |
| `scenario-history-accuracy.sh` | `HISTORY_ACCURACY_OK` / `_FAIL` | `CHECK [PASS\|FAIL\|INFO] <check> h=… src=…` lines, then a `VERDICT TABLE` |
| `scenario-fork-reorg.sh` | `FORK_E2E_OK` / `_FAIL` | `CHECK [PASS] fork.<name>` lines |
| `scenario-kill-matrix.sh` | `KILL_MATRIX_OK` / `_FAIL` | `PHASE_VERDICT window=… mode=… verdict=…` |
| `scenario-resource-faults.sh` | `FAULT_E2E_OK` / `_FAIL` | `CHECK [PASS] fault.<case>.<name>` lines |
| `scenario-concurrency-under-fault.sh` | `CONCURRENCY_E2E_OK` / `_FAIL` | `PHASE_VERDICT phase=… verdict=…` |
| `scenario-catchup-batch-flush-kill.sh` | `CATCHUP_BATCH_FLUSH_KILL_OK` / `_FAIL` | `CHECK …` lines |
| `run-all.sh` | `PRIVATE_CHAIN_FAULT_SUITE_OK` / `_FAIL` | the per-scenario summary table |

Grep for `_OK$`/`_FAIL` or just check the exit code — they always agree. Two `_FAIL` reasons mean
*"the run proved nothing"* rather than *"the product is broken"*, and both are still failures:

* `reason=no-checks-ran` — the scenario recorded zero checks.
* `reason=nothing-proven` — every verdict was `SKIP` or `INFO`; nothing was actually observed.

`run-all.sh` applies the same rule at suite level: **exit 0 with no `_OK` marker is a FAIL**, and an
all-skipped suite is `reason=nothing-validated`. Its final line always carries all four counters:

```
PRIVATE_CHAIN_FAULT_SUITE_OK   scenarios=7 passed=7 failed=0 inconclusive=0 skipped=0 elapsed=…s
PRIVATE_CHAIN_FAULT_SUITE_FAIL scenarios=7 passed=5 failed=1 inconclusive=1 skipped=0 elapsed=…s
```

`failed` and `inconclusive` mean different things and are never merged — see §13.

### The three-way startup verdict — do not judge fail-stop by exit code alone

`hs_node_await_startup` returns exactly one of:

| Verdict | Meaning |
|---|---|
| `READY` | HTTP answered; the node came up. |
| `EXIT:<code>` | The JVM terminated. `1` = `TronError.ARCHIVE_RUNTIME`; `70` = archive fatal watchdog `halt`; `143` = a normal `SIGTERM` shutdown; `137` = `SIGKILL`. |
| `HUNG` | **The process is alive but HTTP never became ready.** |

`HUNG` is a real product state, not harness flake — see the blocker in §8. Any scenario that
asserts fail-stop must use `hs_assert_fail_stop`, which accepts only exit `1` (with the
`Shutting down with code: ARCHIVE_RUNTIME` breadcrumb in `tron.log`) or exit `70` (with
`archive fatal watchdog timeout; halting with exit status 70` on **stderr** — it never reaches
`tron.log`). `HUNG` and exit `0` are failures.

---

## 7. `lib.sh` API

Source it, then call `hs_init <name>` first. `hs_init` resolves paths, checks tools, builds/locates
the jar, compiles the Java helpers, **asserts the hard-coded key table still derives the expected
addresses**, and installs the cleanup trap.

> **Calling convention — this bites.** Helpers that *assert* (or can abort) must be called
> **directly**, never inside `$( )`. A command substitution runs in a subshell, so `hs_fail`'s
> counter increment is lost and `hs_abort`'s `exit 1` kills only the subshell. Those helpers
> publish their results in globals instead: `HS_LAST_TXID`, `HS_LAST_BLOCK`, `HS_LAST_CONTRACT`,
> `HS_ORACLE_REPLAYED`. Pure getters (`hs_head_num`, `hs_hist_balance`, `hs_metric_*`, …) perform
> no assertions and are safe in `$( )`.

### Verdicts

`hs_log` `hs_step` `hs_pass` `hs_fail` `hs_abort` `hs_die`
`hs_assert_eq` `hs_assert_ne` `hs_assert_contains` `hs_marker` `hs_finish`

### Node materialization

* `hs_new_node <name> [slot]` — fresh data dir + config; echoes the node dir.
* `hs_write_node_config <node_dir>` — regenerate the config (safe before a restart).
* `hs_config_set_identity_init <node_dir> <true|false>`
* `hs_archive_dir <node_dir>` / `hs_archive_identity_file <node_dir>`

Config knobs, all optional, set before `hs_new_node`:

| Variable | Default | Notes |
|---|---|---|
| `HS_CFG_WITNESS_COUNT` | `1` | SRs held by this **one** node, `1..27` — see “Multi-SR chains” below. Mutually exclusive with the next two knobs (the harness dies rather than pick a winner) |
| `HS_CFG_WITNESS_KEY` | witness 1 | |
| `HS_CFG_GENESIS_WITNESSES` | `<W1>:100` | `addr:votes[,addr:votes...]` — two entries for the fork topology |
| `HS_CFG_ACTIVE_PEERS` | empty | `ip:port[,...]` → `node.active` |
| `HS_CFG_ARCHIVE_ENABLE` | `true` | `false` also forces `debug=false`, `identity.initialize=false` (`StorageConfig.java:222` rejects the mismatch) |
| `HS_CFG_ARCHIVE_IDENTITY_INIT` | `true` | **first boot of an empty data dir only** |
| `HS_CFG_ARCHIVE_DB_DIR` | `archive` | absolute paths allowed — needed to put the archive on a small volume |
| `HS_CFG_ARCHIVE_DEBUG` | `true` | `false` ⇒ `debug_trace*` returns `-32601` |
| `HS_CFG_SOFT_MIN_FREE_BYTES` | 32 MiB | production default is 5 GiB |
| `HS_CFG_HARD_MIN_FREE_BYTES` | 16 MiB | production default is 1 GiB |
| `HS_CFG_QUERY_WORKERS` | `2` | the batch admission limit behind `-32005 … limit=2` |
| `HS_CFG_COMMITTEE` | see §8 | startup governance flags |

#### Multi-SR chains (`HS_CFG_WITNESS_COUNT`)

A one-witness chain makes `solid == head`, which collapses the archive's whole reason to exist:
the in-flight window (blocks journaled but not yet published) never grows, so every durability
window a kill test aims at is measured in a degenerate state. Measured on this repo, same node,
same jar, steady state:

| | `head` vs `solid` | `inflight_blocks` | `inflight_records` | `captured` / `published` |
|---|---|---|---|---|
| `HS_CFG_WITNESS_COUNT=1` | `solid == head`, lag **0** | **2** | 14 | 57 / 55 |
| `HS_CFG_WITNESS_COUNT=27` | `solid == head - 18` | **20** | 140 | 57 / 37 |

(Both rows are the same node, same jar, `head=56` after the same wall time — the extra SRs cost
nothing in block rate, they only stop `solid` from chasing `head`.)

`DposService.updateSolidBlock()` (`consensus/src/main/java/org/tron/consensus/dpos/DposService.java:159`)
sorts each active witness's `latestBlockNum` and takes index `P = (int)(N * (1 - 70/100))`. For
`N = 1` that index is `0`, i.e. head itself. For `N = 27` it is `8`; in steady state the sorted
list runs `head-26 … head`, so solid settles on `head - (N - 1 - P)` = **`head - 18`**.

One node can hold every SR — `localwitness` is a **list**: `Args.java:919` →
`WitnessInitializer.initFromCFGPrivateKey()` keeps all keys, and
`ConsensusService.java:56-69` turns each into its own `Miner`, so `DposTask.java:116` always finds
a local miner for the scheduled slot. No second process is needed.

```bash
HS_CFG_WITNESS_COUNT=27 ./scenario-kill-matrix.sh
HS_CFG_WITNESS_COUNT=27 ./scenario-resource-faults.sh
HS_CFG_WITNESS_COUNT=27 ./scenario-concurrency-under-fault.sh
```

**Reach**: every single-node scenario honours the knob.

* `scenario-smoke.sh` and `scenario-kill-matrix.sh` materialize nodes through `hs_new_node` →
  `hs_write_node_config`.
* `scenario-resource-faults.sh` renders its own `node.conf` from the `ah_*` template
  (`ah_write_node_conf` in `scenario-common.sh`), which takes its witness set from
  `ah_apply_witness_count`.
* `scenario-concurrency-under-fault.sh` renders its own `write_node_conf`, whose single-node
  phases (setup / baseline / clean-stop / sigkill) take theirs from `apply_witness_count`.

The two scenario-local templates share `hs_witness_conf_blocks`, and all three paths bottom out in
the same `hs_witness_key_at` (key scheme) and `hs_base58_of_priv` (address rendering), so neither
is written twice anywhere in the harness. Only the *formatting* differs: `hs_write_node_config`
labels each SR `http://<addr>.local`, the scenario templates `http://sr<N>.local`. Both are
cosmetic `url` fields the node never dials.

Two places deliberately ignore the knob, because their fork comes from a *different* mechanism —
two separately stalled nodes make `solid` the MIN of both `latestBlockNum`s, which is what makes
an arbitrarily deep fork legal: all of `scenario-fork-reorg.sh`, and the `reorg` phase of
`scenario-concurrency-under-fault.sh` (`fork_write_conf` → `dual_witness_block`). Both keep their
2-node / 2-witness partition topology at any `HS_CFG_WITNESS_COUNT`.

Witness 1 is `HS_KEY_WITNESS1` verbatim and `HS_CFG_WITNESS_COUNT=1` regenerates a
**byte-identical** `node.conf` on all three paths, so existing scenarios are untouched. Witnesses
2..N come from `HS_WITNESS_KEY_PREFIX` + the 8-hex index (`hs_witness_key_at` /
`hs_witness_base58_at`).

What changes for a caller at `N = 27`:

* **Block rate is unchanged** — still one block per 3 s slot, because every miner is local.
* **`solid` trails `head` by ~18 blocks (~54 s).** Anything gated on `hs_wait_solidified`,
  `hs_wait_archive_drained`, or `hs_wait_hist_available` needs that much more headroom; the
  240 s defaults still fit, but tight per-step timeouts do not.
* **Longer warmup.** Index 8 of the sorted list is still `0` until `27 - 8 = 19` *distinct*
  witnesses have produced, so **solid is pinned at 0 until block 19** (~1 min after readiness) and
  only then locks onto `head - 18`. Measured: `head=18 solid=0`, then `head=20 solid=2`. A scenario
  that captures oracles immediately after `hs_node_wait_ready` is reading a chain whose solid
  pointer has not started moving yet.
* **First config generation costs one JVM per new key** (`Addr`, ~0.3 s each, ~8 s for 27). The
  result is cached in `$HS_RUN_DIR/addr.cache`, so restarts and extra nodes are free.
* `HS_CFG_WITNESS_COUNT > 27` is refused: `MAX_ACTIVE_WITNESS_NUM` is 27
  (`common/src/main/java/org/tron/core/config/Parameter.java:66`) and
  `DposService.updateWitness()` would silently truncate the set.

### Lifecycle

`hs_node_start` `hs_node_wait_ready` `hs_node_await_startup` `hs_node_restart`
`hs_node_stop` `hs_node_stop_force` `hs_node_kill9` `hs_node_suspend` `hs_node_resume`
`hs_node_alive` `hs_node_pid` `hs_node_exit_code` `hs_node_wait_exit` `hs_stop_all_nodes`

`hs_node_stop` sends `SIGTERM` and **returns non-zero without escalating** if the JVM does not
exit — escalating would mask a shutdown brick. `HS_JDWP_PORT` / `HS_JDWP_SUSPEND` attach a JDWP
agent for the scenario-B breakpoints. `HS_JVM_OPTS` overrides the heap flags.

### Forensics

`hs_node_logs` `hs_log_has` `hs_log_count` `hs_node_exit_reason`
`hs_node_has_archive_failstop` `hs_node_has_watchdog_halt`
`hs_assert_fail_stop` `hs_assert_clean_stop` `hs_assert_startup_verdict`

### Chain observation

`hs_head_num` `hs_solid_num` `hs_peer_count` `hs_block_id_at`
`hs_wait_height` `hs_wait_blocks` `hs_wait_solidified`
`hs_wait_archive_drained` `hs_wait_hist_available`

`hs_wait_hist_available` is the reliable "is this height queryable yet" gate: it probes the real
read path until it stops answering `-32000 archive history unavailable`. Metric arithmetic is a
poor substitute — the adapter's own range check (`DefaultArchiveService.java:3236`) is the
authority.

### Metrics

`hs_metrics_raw` `hs_metric_state` `hs_metric_work` `hs_metric_fork_total`
`hs_metric_int` `hs_metric_work_int` `hs_metrics_summary`
`hs_assert_repair_not_required` `hs_assert_repair_required`

`repair-required` is a **RocksDB META key** (`ArchiveBlockRangeCodec.java:48`), not a file. Live,
the only observation point is `tron:archive_state{type="repair_required"}`.

### Transactions

`hs_tx_transfer` `hs_tx_transfer_confirmed` `hs_tx_wait_receipt` `hs_tx_block_num`
`hs_tx_assert_success` `hs_broadcast` `hs_sign`
`hs_contract_deploy` `hs_contract_set` `hs_contract_get_live`

Sender addresses are always **derived from the signing key**, so the owner can never disagree with
the signature.

### Historical queries and oracles

`hs_jsonrpc` `hs_jsonrpc_result` `hs_jsonrpc_error_code` `hs_jsonrpc_error_message`
`hs_hist_balance` `hs_hist_code` `hs_hist_storage_at` `hs_hist_call` `hs_hist_contract_get`
`hs_live_balance` `hs_live_code` `hs_live_storage_at` `hs_live_block_number`
`hs_assert_hist_fails_closed`
`hs_oracle_capture` `hs_oracle_record` `hs_oracle_replay` `hs_oracle_file`

**Oracle discipline:** capture the *live* answer while the head is at height *H*, then later assert
the *archive* answer at *H* equals it. Never assert against a hard-coded literal.

### Faults and concurrency

`hs_disk_free_bytes` `hs_make_small_volume` `hs_detach_volume` `hs_fill_volume`
`hs_make_readonly` `hs_make_writable` `hs_truncate_file`
`hs_query_loop_start` `hs_query_loop_stop` `hs_query_loop_tally`
`hs_query_loop_distinct_results` `hs_query_loop_error_codes`
`hs_relay_start` `hs_relay_stop`
`hs_jdb_break_and_kill` `hs_jdb_log_path` *(mechanism smoke-tested; the full window cycle has
never run — see §10)*

### Offline probe

`hs_probe_build` `hs_offline_probe` `hs_assert_probe_clean`

`hs_offline_probe <node_dir>` echoes the `ArchiveProbe` JSON and **returns** the probe's exit
status — `0` clean, `1` structural violation, `3` database unopenable, `99` the probe could not be
built or run. The status is returned rather than published in a global precisely because callers
write `json="$(hs_offline_probe dir)"`, and a global assigned inside that subshell is invisible to
them (§9). `hs_assert_probe_clean` turns "the probe did not run" and "the index is empty" into
failures, so absent evidence can never read as a clean archive.

---

## 8. Verified facts and known traps

Everything below was **executed against this repo** (`3ae4d79f5c`, `FullNode.jar` sha256
`17d43213…1a2b58`). Each is encoded in `lib.sh`; they are documented because they are exactly the
things that silently produce a wrong verdict.

1. **`node.rpc.minEffectiveConnection = 0` is mandatory.** The default of `1`
   (`reference.conf:400`) makes `Wallet.broadcastTransaction` return `NO_CONNECTION`
   (`Wallet.java:532`) on an isolated node. Every transaction would fail.

2. **`node.fastForward` defaults to two PUBLIC mainnet addresses** (`reference.conf:342`).
   Unset, every harness node repeatedly dials the internet — observed 330 outbound connection
   attempts in one 20-minute run. The generated config pins it to `[ ]`. Verified: 0 dials.

3. **`node.minParticipationRate = 0`** or DPoS refuses to produce on a private chain.

4. **The TVM proposal set is load-bearing for contract oracles.** `reference.conf:905` defaults
   every `committee.*` flag to `0`, leaving the chain on pre-Constantinople rules. A deploy still
   executes and returns the correct runtime code (`contractResult` is right, energy is consumed),
   but **the code is never persisted**: `eth_getCode` answers 32 zero bytes and every later call
   executes nothing, so no `SSTORE` lands and storage oracles read `0` forever.
   *Verified A/B on this repo with archive **disabled**: byte-identical behaviour — this is a
   chain-configuration artifact, never an archive defect.* `lib.sh` therefore activates
   `allowCreationOfContracts`, `allowTvmTransferTrc10`, `allowTvmConstantinople`,
   `allowTvmSolidity059`, `allowTvmIstanbul`, `allowTvmCompatibleEvm`, `allowTvmLondon`
   (`HS_DEFAULT_COMMITTEE`; override with `HS_CFG_COMMITTEE`). With those on, the harness contract
   deploys, persists its runtime, and drives slot 0 through `0 → 111 → 222`.

5. **A clean `SIGTERM` stop is observed as exit `143`, not `0`.** java-tron runs its shutdown hooks
   and the JVM then terminates from the signal. Use `hs_assert_clean_stop` (accepts `0` or `143`),
   not `assert_eq 0`.

6. **Prometheus counters are absent until first incremented.** An absent
   `tron:archive_work_total{type="publish_failures"}` means "never happened" == 0, whereas an absent
   `tron:archive_state` *gauge* means "not reported". `hs_metric_work_int` encodes that asymmetry
   (0 for an absent counter, empty only when the endpoint is unreachable); `hs_metric_int` does not.

7. **Backgrounded node processes must not inherit the caller's stdout.** The wrapper subshell in
   `hs_node_start` redirects to `/dev/null`; without it, calling a start/restart helper inside
   `$( )` blocks forever waiting for the node to close the pipe. Observed and fixed.

8. **Tab is an IFS *whitespace* character**, so `read` coalesces consecutive tabs. The oracle ledger
   writes `-` for an unused column rather than leaving it empty — an empty column silently shifts
   every later field (observed: a balance was parsed as a block number).

9. **`"finalized"` is not a usable historical selector.** It resolves to `wallet.getSolidBlockNum()`
   (`ArchiveJsonRpcStateAdapter.java:138`), which on this chain is not yet published and fails
   closed with `-32000`. Always use explicit hex block numbers ≤ the published head.

10. **`eth_getCode` returns `getRuntimecode()`**, not TRON's `bytecode` field
    (`TronJsonRpcImpl.java:662`). Compare archive-vs-live at the same height, never against an
    expected literal.

11. **`identity.initialize` must be `true` only on the first boot** of an empty data directory;
    normal restarts validate the existing ACTIVE pair and never auto-claim. `hs_node_restart`
    flips it to `false` automatically.

12. **`archive.identity`'s `finalPath` is absolute**, so a data directory can never be moved or
    copied to another path. After `hdiutil detach -force`, re-attach at the *same* mount point.

13. **Fail-closed shapes** (all confirmed live): `-32000 archive history unavailable for block N`
    (outside the published range), `-32000 archive <domain> is unknown before mid-chain coverage`,
    `-32000 archive history hash mismatch for block N` (orphaned height), `-32005` admission /
    worker saturation, `-32601` for `debug_trace*` with `archive.debug.enable = false`.

14. **Fork depth is bounded by `head - solid + 1`** (`Manager.java:2097`). A single-SR chain has
    `solid == head`, so it **can never reorg** — scenario A requires the two-witness topology, where
    each side's solid stalls during a partition (`DposService.java:159`). Heal the partition before
    the branches re-solidify. Partition with `hs_relay_start`/`hs_relay_stop` (kill the relay) and
    deepen a branch with `hs_node_suspend` — never by restarting a node (see the blocker below).

15. **`latest` is NOT "the state at the end of the head block" — it includes the PENDING pool.**
    `Manager.pushTransaction` (`Manager.java:1264-1270`) executes a freshly broadcast transaction
    inside a session and `tmpSession.merge()`s it into the head snapshot immediately, so
    `eth_getBalance`/`eth_getStorageAt` at tag `latest` already reflect transactions that **no
    block contains yet**. Any harness that records "the live value while head was H" is therefore
    recording a value that was never in force at H, unless it first checks that the pool is empty.
    *Measured:* `scenario-history-accuracy.sh` filed the post-transfer balances of block 72 under
    height 71 and reported two FAILs against a **correct** archive. The fix is the pending guard in
    `ha_sample_now`: refuse to sample unless `/wallet/getpendingsize` reports `0` both before and
    after the read. Coverage cost is about one height per transaction, and those heights are still
    pinned by the `tx`/`receipt` oracles.

16. **`hs_wait_archive_drained` is meaningless while a multi-SR chain is producing.** It waits for
    `tron:archive_state{type="oldest_inflight_block"} == -1`, i.e. an empty in-flight set — but at
    27 SRs `solid == head - 18`, so ~19-20 blocks are journalled-but-unpublished at every instant
    (measured: `inflight_blocks=19`, `oldest_inflight_block=55` at head 74). Draining only means
    something once production has stopped. To gate on "is height H queryable", probe the read
    path's own range check with `hs_wait_hist_available`.

### Multi-witness restart regression

The first two-witness run exposed a startup brick: the published archive head could legitimately be
one block ahead of the solidified metadata recovered from the canonical root, but startup required
both heights to be equal. The resulting plain `ArchiveException` also left the JVM alive behind the
non-daemon Prometheus server.

The remediation validates the published tail's hash against the canonical block at that tail's own
height, retains strict coverage and parent-link checks for every in-flight block, and wraps startup
archive failures in `TronError(ARCHIVE_RUNTIME)`. `FORK_ASSERT_RESTART=1` is the permanent regression
gate for both the clean-restart and live-but-dead failure modes.

Consequences the harness encodes:

* `hs_node_await_startup` has the `HUNG` verdict and a readiness timeout — never judge fail-stop
  by exit code alone.
* On a ≥2-witness chain `solid < head`, a published archive block can remain ahead of the recovered
  solidified metadata while still matching the durable canonical chain. This is a valid restart
  state, not corruption.
* On the single-witness chain (`solid == head`) a `SIGTERM` restart is clean — verified by
  `scenario-smoke.sh`.

---

## 9. The anti-vacuity contract

A fault harness fails in a uniquely dangerous way: **it goes green because it never observed
anything.** Every assertion here is written so that "no evidence" is a FAILURE, never a pass.
The rules, and where each is enforced:

| Rule | Enforced in |
|---|---|
| A run that recorded **zero checks** cannot be green. | `hs_finish`, `ah_finish` |
| A run whose every verdict is `SKIP`/`INFO` cannot be green (`reason=nothing-proven`). | `ah_finish` |
| An oracle is recorded **only** when the node returned a real `0x` quantity; an erroring archive yields zero oracles, never a ledger of empty strings that later "replays identically". | `hs_oracle_capture`, `km_oracle_capture`, the Python `cmd_capture` |
| Replaying **zero** oracles is a failure, not a clean sheet. | `hs_oracle_replay`, `km_oracle_replay` (returns 2 = no evidence), `km_classify` |
| An oracle set containing **no served value** at all fails setup — the storm could then only prove that errors stay errors. | `phase_setup`, `phase_reorg` |
| `ERR:transport` / `ERR:unparsable` are **not** fail-closed. A dead node trivially "fails closed" for every query ever made. | `is_failclosed` in `scenario-fork-reorg.sh` |
| An equality assertion between two oracle answers requires **both sides to be real values**. Two identical error strings satisfy `=` and prove nothing. | `fork.prefork_history_stable`, `fork.recaptured_cross_node` |
| Reorg evidence is a **delta against a pre-heal baseline**. A 2-witness chain logs `Switch fork!` during initial peer sync, so counting over the whole log passes without the induced reorg. | `SWITCH_BASELINE` / `ERASE_BASELINE` / `FORK_ALL_BASELINE`, `wait_for_new_switch_fork` |
| "No error in the log" requires the log to **exist and be non-empty**. | `ah_logs_present` |
| A probe result is believed only when the JSON parses **and** reports `opened == true`; an empty index (`rangeCount == 0`) has no gaps by definition and is not evidence of integrity. | `hs_assert_probe_clean`, `probe_case`, the fork phase-9 block |
| An archive fail-stop requires the **breadcrumb**, not just exit 1 — a port clash exits 1 too. | `hs_assert_fail_stop`, `grade_failstop`, `grade_restart` |
| An injected fault must be **confirmed injected** before its outcome is graded. | truncation size check; `storm_saw_fault` |

### The no-circular-oracle contract (`scenario-history-accuracy.sh`)

A previous audit round rejected a "differential" accuracy test whose expected values were rebuilt
from the system under test's own output. Comparing one archive answer against another archive
answer is vacuity in a different costume: a uniformly wrong archive passes it.

`scenario-history-accuracy.sh` therefore admits exactly five oracle kinds, and every verdict row
names the one it used in its `src=` column:

| `src` | What the expected value is derived from |
|---|---|
| `tx` | The transaction's own semantics — the amount this script chose to send, the 32-byte word it chose to `SSTORE`, the runtime bytecode it chose to deploy. Known before the node ever ran. |
| `receipt` / `rcpt` | `/wallet/gettransactioninfobyid`: `.fee`, `.withdraw_amount`. The node's own accounting from the **canonical** execution path, not the archive. |
| `live@H` | The **live** (`"latest"`) answer recorded at the instant head was exactly `H`, long before `H` was historical. `shouldUseArchive` is false for `"latest"` (`ArchiveJsonRpcStateAdapter.java:41`), so this never touches the code under test. `ha_sample_now` guards it twice: head is read **before and after** each sample (a block produced mid-sample would otherwise file values under the wrong height), and the sample is refused unless the **pending pool is empty** at both ends — see trap 15 in §8, which cost two spurious FAILs against a correct archive before it was added. |
| `arith` | Arithmetic over the three above (`pre − amount − fee == post`). |
| `probe` | One structural check reads the committed txNum index off disk with `ArchiveProbe`, node stopped. Archive storage, but not the query path under test. |

Two further rules the scenario enforces:

* **A live precondition gates every contract claim.** A prior attempt used hand-rolled bytecode
  whose runtime code was never persisted, so `eth_getCode` answered empty at every height and the
  whole contract section passed vacuously. The scenario now asserts on the **live** chain that the
  deployed runtime code equals the bytes it deployed and that the getter returns the expected word
  *before* any historical claim — and fails with **exit 2 (inconclusive)**, not exit 1, if it does
  not: a chain that cannot run the contract has said nothing about the archive.
* **`INFO` is not `PASS`.** Anything the RPC surface cannot express is emitted as an `INFO` row
  that states exactly what is unproven, and `INFO` rows never touch the `hs_pass`/`hs_fail`
  counters, so they can never make a run green.

### The subshell trap that defeats all of this

`$( )` runs a function in a **subshell**: globals it assigns are discarded, `hs_fail`'s counter
increment is lost, and `hs_abort`'s `exit 1` kills only the subshell. Any helper that asserts or
publishes a global **must be called directly**. Two live bugs of exactly this shape were found and
fixed during review — `km_probe` and (initially) `hs_offline_probe` both published their status in
a global that every caller read through `$( )`, so the status was permanently stuck at its initial
value and the entire structural-violation branch of the kill matrix was dead code. Both now
**return** the probe's exit status, which is the one channel that survives command substitution.

---

## 10. Execution status — what has actually been run

Be precise about this when reporting results. "Written and reviewed" is not "executed".

| Component | Status |
|---|---|
| `scenario-smoke.sh` | **Executed green** against a real archive chain: `SMOKE_E2E_OK checks=23 oracles=5 finalHeight=8`. |
| `scenario-history-accuracy.sh` | **Executed green** on a real 27-SR chain: `HISTORY_ACCURACY_OK checks=61 pass=57 fail=0 info=4 witnesses=27 samples=1280 txs=17 withdrawProof=1 publishedHead=77`. All three transaction classes proved against independent oracles, including the full SR-reward loop (`WitnessCreateContract` → freeze → vote → promotion at maintenance → `WithdrawBalanceContract`), whose `eth_getBalance` delta across the withdrawal block matched `receipt.withdraw_amount` (18 000 000 000 sun) exactly. The 4 `INFO` rows are RPC-surface limits (`AccountCapsule.allowance` and the reward dynamic properties are not projected by any historical method), not skipped assertions. **The archive produced no wrong answer in any run**; the two FAILs seen along the way were both traced to the harness's own live oracle (see the pending-pool trap in §8). |
| `scenario-fork-reorg.sh` | **Executed green after the multi-witness restart fix** with strict reorg deltas and `FORK_ASSERT_RESTART=1`: `FORK_E2E_OK checks=20 passed=19 depth=6 switches=1`; node A restarted cleanly at head 24. |
| `scenario-resource-faults.sh` | **Executed green** for `enospc` + `permission` and for opt-in `truncation`. **Re-run required**: fail-stop now demands a breadcrumb, and the probe now demands `opened == true` and `rangeCount > 0`. |
| `scenario-concurrency-under-fault.sh` | **Executed green twice**, all 5 phases including a real 7-block reorg. The only change from review is the new "no value oracles" gate, which only fires on a chain that never published. |
| `run-all.sh` | **Whole suite executed green after the port-isolation fix**: `PRIVATE_CHAIN_FAULT_SUITE_OK scenarios=7 passed=7 failed=0 inconclusive=0 skipped=0 elapsed=1940s`, with `fork-reorg` passing *in suite position 3* — the position it used to die in (§12). Both new mechanisms were also exercised for real, not just with fakes: an earlier run of the same suite hit a genuinely busy `kill-matrix` band (a concurrent session in the same worktree), waited the full 120s, named the holding PID, and recorded `INCONCLUSIVE` — final line `PRIVATE_CHAIN_FAULT_SUITE_FAIL scenarios=7 passed=6 failed=0 inconclusive=1 skipped=0`, exit 1. The verdict matrix (PASS / FAIL / INCONCLUSIVE / OK-line / exit codes) is additionally covered with fake scenarios. |
| `ports.sh` | Band, block, aux and role arithmetic verified for all seven scenarios; the slot>38 overflow guard, the unregistered-scenario sandbox band, and `ah_port_space_check`'s refusal of an offset that reaches the ephemeral range all verified to fail closed. Generated `node.conf` and `ports.env` confirmed to carry the derived ports on a live run. |
| `scenario-kill-matrix.sh` | **Executed green end to end** on a 27-SR chain (`HS_CFG_WITNESS_COUNT=27`): `KILL_MATRIX_OK checks=6 windows=6`. All five JDWP windows ran `mode=deterministic` — w2/w3/w4/w5 `RECOVERED`, w6 `FAILSTOP` (exit 1 `ARCHIVE_RUNTIME`, probe `repair=true`), w1 `RECOVERED`. **No window degraded to the probabilistic path.** w5 previously degraded on every run: its descriptor named `publishBlock()` while the statement lives in `publishBlockLocked()`, so the jar guard refused it. It now breaks at `UnifiedArchiveBackend:120` on the `archive-publisher` thread, which the jdb transcript confirms (`...publishBlockLocked(), line=120 bci=285`). |
| `ArchiveProbe.java`, `HarnessSigner.java`, `java/{Addr,Sign}.java` | Compile cleanly against `framework/build/libs/FullNode.jar` (re-verified). `ArchiveProbe` verified against a synthetic RocksDB with the real CF layout. |
| Every shell file in this directory | `bash -n` clean on bash 3.2 (macOS system bash); no bash-4 constructs. `anchor.sh` is both a sourced library and a CLI; its companion `anchor_resolve.py` is stdlib-only Python 3 (the anchor resolver is the only `python3` dependency, and a missing `python3` degrades a window rather than failing the run). |

### Do this on the first real run, in this order

1. **Check the anchors first — `./anchor.sh`.** It resolves every descriptor against the current
   source *and* the current jar in about a second, and exits non-zero if any fails. A non-zero
   `delta` column just means the working tree has moved on from the jar and the resolver corrected
   for it; the breakpoint still lands on the same compiled statement. Only a `FAIL` line needs
   action, and it names the descriptor. If the method body itself was rewritten, no consistent
   offset exists and the resolver says so — then rebuild with
   `./gradlew :framework:buildFullNodeJar`.
2. **Smoke-test exactly one kill-matrix window** before trusting the matrix:
   `./scenario-kill-matrix.sh --windows w3 --prob-iters 1`. `w3` has the simplest anchor.
   If `jdb` proves unworkable, the fallback is the JUnit driver
   `framework/src/test/java/org/tron/core/archive/ManagerArchiveSwitchForkTest.java`.
3. **Re-run fork-reorg and resource-faults** and read the verdict table, not just the marker.
   The new gates are stricter; a check that flips PASS→FAIL is a finding to investigate, not
   necessarily a harness bug.
4. **Watch for one specific false positive.** `km_classify`'s first probe runs against a *live*
   RocksDB via `openReadOnly`, which can lag the writer. A live violation is therefore only a
   suspicion; the code stops the node and re-probes before declaring `SILENT_HALF_STATE`. If you
   see that verdict, confirm the *quiesced* probe JSON in the run directory before filing a bug.

### Known gaps, deliberately not closed

* **"Exactly once" is asserted from log lines**, not from an unwind counter — `ArchiveMetrics`
  exposes none. `fork.unwind_exactly_once` checks that no block number appears twice in the erase
  log of the induced reorg. That is the strongest available signal, and it is weaker than a counter.
* **The permission fault mid-run is inconclusive by construction.** RocksDB keeps writing through
  descriptors opened before the `chmod`, so a running node may legitimately not notice. The
  load-bearing assertion is the deterministic read-only *restart*.
* **ENOSPC does not set `repair-required`, and that is correct** — the failure is raised before the
  canonical commit, so nothing is half-written. The scenario asserts explicit fail-stop plus a
  restart that recovers or refuses explicitly, plus no index gaps.
* **`hs_make_small_volume` mounts under `/Volumes/`**, outside the run directory. It is unused by
  the committed scenarios (`scenario-resource-faults.sh` mounts inside its own run dir with
  `-mountpoint`); prefer that pattern for anything new.
* **`hs_archive_dir` reads the *current* `HS_CFG_ARCHIVE_DB_DIR`**, not the value in force when the
  node was created. Do not change that variable between creating nodes with different archive
  directories in one scenario.

### Semantic anchors (`anchor.sh`)

Breakpoints are placed by `CLASS:LINE`, but **a line number is not a name for a statement**. One
inserted line above a method moves every anchor inside it, and the harness would then suspend the
JVM at the wrong statement — or, with the old `javap` membership guard, degrade the window to the
probabilistic path *exactly when the code under test changed*, which is the worst possible moment
to stop testing it deterministically.

`anchor.sh` therefore holds ONE registry of **semantic descriptors**, shared by
`scenario-kill-matrix.sh` and `scenario-catchup-batch-flush-kill.sh`. A descriptor names

> class · source method · *(optionally)* “after this statement” · “the statement matching *R*”

and it is resolved at run time in two stages, with a third guard at runtime:

1. **Source.** The current working-tree file is parsed with comments and string/char literals
   masked, the named method’s body is brace-matched (all overloads), and the descriptor must
   select **exactly one** statement. Zero or two matches is a **hard error** — an ambiguous anchor
   is never silently resolved to “the first one”, and a match can never land inside a comment.
2. **Jar.** `javap -p -l` on the jar under test supplies the `LineNumberTable` for that method
   *and for its compiler-synthesized `lambda$<method>$N` bodies* — a statement containing a lambda
   compiles into two methods and both are legitimate locations for it. The resolver then solves
   for the single line offset `delta` between working-tree source and jar
   (`jarline = srcline − delta`). A `delta` is accepted only when every compiled method is
   cleanly on one side of the line: each maps **either** entirely onto real code lines of this
   source body **or** entirely outside it (that second case is a different overload — overloads
   share a name and their lambdas are numbered class-wide, so `kin` legitimately spans several
   bodies). A method that *partially* overlaps the body means the body was rewritten rather than
   shifted, and no offset can be right — refuse. The anchor itself must land on an actual table
   entry of a method that belongs to this body. `delta = 0` always wins; two viable offsets is a
   hard error.
   **The breakpoint is emitted in jar coordinates**, the only coordinate system `jdb` understands
   — that is what makes an anchor survive an edit above the method with no rebuild.
3. **Runtime.** `hs_anchor_assert_hit()` requires the method *and* the line `jdb` reports to match
   the resolved anchor before a kill is credited.

Degradation to the probabilistic path now happens **only** when a descriptor genuinely cannot be
resolved (method renamed or gone, statement deleted or duplicated, body rewritten so no consistent
offset exists, `python3`/`javap` missing), and the note **names the descriptor that failed**.

Re-check every anchor without running a scenario — this is the drift check:

```
./anchor.sh              # resolve all; exit 1 if any failed
./anchor.sh km.w5        # just one
./anchor.sh --list       # names + what each points at
./anchor.sh --selftest   # prove the resolver still REFUSES what it must refuse
```

`--selftest` is the guard on the guard. A resolver that quietly picked the first of two candidates
would still make every window pass, so “ambiguous is a hard error” is asserted against the real
production sources rather than assumed: zero matches, two matches in one method, an ambiguous
`after`, a missing method, a match that exists only inside a comment, and a class absent from the
jar must each be refused — then every registered descriptor must resolve. Ends
`ANCHOR_SELFTEST_OK` / `ANCHOR_SELFTEST_FAIL`.

| Descriptor | Window | Anchored statement (the breakpoint suspends *before* it runs) |
|---|---|---|
| `km.w2` | after journal put / before canonical commit | `Manager.pushBlock`: the `tmpSession.commit()` **after** the archive journal write |
| `km.w3` | after canonical commit / before ack | `Manager.pushBlock`: the `acknowledgeArchiveJournalOrFailStop(` **after** that commit |
| `km.w4` | after ack / before publish | `Manager.pushBlock`: the `publishArchiveSolidifiedOrFailStop(` **after** that ack |
| `km.w5` | mid publish batch | `UnifiedArchiveBackend.publishBlockLocked`: the call site of `db.publishBlockAtomically(` |
| `km.w6` | genesis: after `commitToRoot`, before the COMMITTED marker | `Manager.initGenesis`: the `saveArchiveGenesisCommitComplete(` **after** `genesisSession.commitToRoot()` |
| `cfk.flush` | catch-up batch flush | `SnapshotManager.flush`: the `refresh()` **after** the durable `createCheckpoint()` |

`km.w5` is why the *enclosing method* matters: the statement lives in `publishBlockLocked()`, while
`publishBlock()` only takes the publication lock and delegates. Naming `publishBlock()` kept that
window **permanently degraded** — the jar guard correctly saw the resolved line belonged to a
different method and refused the anchor rather than breaking in the wrong place.

Not migrated: the fork-replay journal/commit/ack site in `Manager.switchFork` — the
`archiveService.beginBlock(item.getBlk(), ArchiveSource.REPLAY)` that opens each replayed block —
has no descriptor, because no scenario currently arms it. Add a `km.fork*` descriptor to
`anchor.sh` if one ever does; do not reintroduce a line number.

**Startup validation a recovery step should assert.** Named by method, not by line, for the reason
this whole section exists — these are prose references, but a stale line number is misleading in a
document that tells you not to trust them. There are **two** distinct paths and they are easy to
confuse:

* `Manager.initInternal()` — the *empty-archive* branch, taken when the canonical store has no
  blocks: `validateArchiveGenesisCommitMarkerPresence(canonicalHasBlocks)`, then
  `archiveService.reconcilePublishedHeadOnStartup(-1L)` and
  `archiveService.reconcileInFlightOnStartup(-1L, -1L, …)` with sentinel arguments.
* `Manager.reconcileArchiveOnStartup(canonicalHead)` — the *real* recovery path on a chain that
  has blocks: `reconcilePublishedHeadOnStartup(canonicalHead.getNum())`,
  `reconcileInFlightOnStartup(solidifiedNum, canonicalHead.getNum(), …)`, then
  `validateGenesisArchiveCoverage()`.

`validateCanonicalHead` is **not** called from `Manager` at all — it is an `ArchiveService` method
(`ArchiveService` default / `DefaultArchiveService`, which delegates to
`ArchiveTxNumIndex.validateCanonicalHead(headNum, headHash)`). An earlier version of this README
listed all five as consecutive lines of one `Manager` block; they are not.

---

## 11. Reference

Config block source: `20260714-archive-from0-production-validation-runbook.md` §"Config to enable
archive". Allowed `storage.archive.*` keys are whitelisted at
`common/src/main/java/org/tron/core/config/args/StorageConfig.java:521` — an unknown key aborts
startup with `IllegalArgumentException`. Defaults documented at
`common/src/main/resources/reference.conf:142-219`.

Endpoints used (all verified live; none invented):

```
POST /wallet/getnowblock            head block
GET  /wallet/getnodeinfo            .block, .solidityBlock ("Num:<n>,ID:<hash>"), .peerList
POST /wallet/getblockbynum          {"num":N}
POST /wallet/createtransaction      transfer
POST /wallet/broadcasttransaction   signed tx
POST /wallet/gettransactioninfobyid {"value":"<txid>"}
POST /wallet/deploycontract         contract deploy
POST /wallet/triggersmartcontract   contract call (sign .transaction.txID, broadcast .transaction)
POST /wallet/getcontractinfo        runtimecode
POST /jsonrpc                       JSON-RPC (POST only)
GET  :<metrics-port>/metrics        Prometheus (the node's block base +4 — see §12)
```

There is **no** `/wallet/gettransactionsign` servlet — signing is client side via `java/Sign.java`.
`visible:true` ⇒ base58 addresses; omit it ⇒ hex41. Never mix the two in one request, and never
send an empty `function_selector` (`JsonFormat$ParseException`).

---

## 12. The port map (`ports.sh`)

`ports.sh` is the **single source of truth** for every TCP port the harness binds. `lib.sh`,
`scenario-common.sh`, `scenario-concurrency-under-fault.sh` and `run-all.sh` all source it, so no
scenario can invent a private layout and half-collide with its neighbour.

### The two rules

1. **Nothing lives in the kernel's ephemeral range.** The whole space sits below
   `AH_PORT_EPHEMERAL_FLOOR = 49152` (macOS `net.inet.ip.portrange.first`; Linux never allocates
   below 32768 either). `ah_port_space_check` refuses to run if an offset would push it up there.
2. **Every port of a node derives from one per-node base**, so a node can never get four of its
   five listeners from one family and the fifth from another.

### Layout

Space base `21000` (`ARCHIVE_HARNESS_PORT_SPACE_BASE`), shifted by
`ARCHIVE_HARNESS_PORT_OFFSET` (default `0`) so two harness runs can coexist on one machine.

One **band** per scenario, 400 ports = 40 node blocks:

| Band | Scenario | Range |
|---:|---|---|
| 0 | `smoke` | 21000–21399 |
| 1 | `history-accuracy` | 21400–21799 |
| 2 | `fork-reorg` | 21800–22199 |
| 3 | `kill-matrix` | 22200–22599 |
| 4 | `resource-faults` | 22600–22999 |
| 5 | `concurrency-under-fault` | 23000–23399 |
| 6 | `catchup-batch-flush-kill` | 23400–23799 |
| 7 | any unregistered scenario (with a warning) | 23800–24199 |

One **block** per node inside a band, 10 ports wide, slot `0..38`:

```
node_base = band + 10 * slot
  +0  p2p listen     node.listen.port
  +1  http fullnode  node.http.fullNodePort
  +2  rpc (gRPC)     node.rpc.port
  +3  jsonrpc        node.jsonrpc.httpFullNodePort
  +4  prometheus     node.metrics.prometheus.port
  +5  JDWP agent     -agentlib:jdwp address
  +6..+9  reserved (never bound)
```

Slot **39** is the band's **aux** block, for listeners that belong to no node — `+0` is the TCP
relay used by `fork-reorg` and `concurrency-under-fault`. A slot above 38 is a hard error, not a
silent spill into the next scenario's band.

The nodes each scenario actually creates:

| Scenario | Node | Slot | p2p / http / rpc / jsonrpc / metrics / jdwp | Relay |
|---|---|---:|---|---|
| `smoke` | `a` | 0 | 21000 / 21001 / 21002 / 21003 / 21004 / 21005 | — |
| `history-accuracy` | `a` | 0 | 21400 / 21401 / 21402 / 21403 / 21404 / 21405 | — |
| `fork-reorg` | A | 0 | 21800 / 21801 / 21802 / 21803 / 21804 / 21805 | 22190 |
| `fork-reorg` | B | 1 | 21810 / 21811 / 21812 / 21813 / 21814 / 21815 | |
| `kill-matrix` | `km-*` | 1..N | 22210, 22220, … one block per booted node | — |
| `resource-faults` | N | 0 | 22600 / 22601 / 22602 / 22603 / 22604 / 22605 | — |
| `concurrency-under-fault` | primary | 0 | 23000 / 23001 / 23002 / 23003 / 23004 / 23005 | 23390 |
| `concurrency-under-fault` | fork-a | 1 | 23010 / 23011 / 23012 / 23013 / 23014 / 23015 | |
| `concurrency-under-fault` | fork-b | 2 | 23020 / 23021 / 23022 / 23023 / 23024 / 23025 | |
| `catchup-batch-flush-kill` | source | 0 | 23400 / 23401 / 23402 / 23403 / 23404 / 23405 | — |
| `catchup-batch-flush-kill` | archive-sr27 | 1 | 23410 / 23411 / 23412 / 23413 / 23414 / 23415 | |

`kill-matrix` boots one node per window plus up to three per degraded window, taking slots
`1, 2, 3, …`; the worst case is ~25 of the 39 available blocks.

### JDWP is per node, not per scenario

`kill-matrix --jdwp-port` and `CFK_JDWP_PORT` default to `auto`: `hs_node_start` takes the debugger
port from **that node's own** block (`+5`) and records it in `<node_dir>/jdwp.port`.
`hs_jdwp_port <node_dir>` is what every attacher reads, so two debugged nodes in one scenario can
never fight over one hard-coded port. An explicit number still works for attaching an IDE.

### `run-all.sh` pre-flight

Before starting a scenario the driver asserts that its **entire band** is free — not merely
"nothing is listening", but no socket in any state:

* `lsof -nP -iTCP:<lo>-<hi>` finds process-owned sockets and names the holding PID;
* `netstat -an -p tcp` finds orphaned `TIME_WAIT` / `FIN_WAIT_2` entries, which have no owning
  process and are invisible to `lsof` yet still make `bind()` fail.

A busy band is **waited out** (`ARCHIVE_HARNESS_PORT_WAIT_SECS`, default 120s, polled every 3s)
rather than failing on sight, because `TIME_WAIT` is transient. Only if it is still busy at the
deadline is the scenario recorded `INCONCLUSIVE` — never started, with the holder named:

```
PORT PRE-FLIGHT FAILED for fork-reorg (band 21800-22199)
still busy after 120s:
  held  Python pid=62616 127.0.0.1:21902 (LISTEN)
HARNESS_ERROR ports 21800-22199 not free; the scenario was never started
```

### Resolved: fork-reorg failing only inside `run-all.sh`

`scenario-fork-reorg.sh` used to pass standalone but exit 2 with
`HARNESS_ERROR node B never became ready` as the third scenario of the suite. Node B's
`logs/tron.log` showed `RpcApiService starting on 50151`, then
`Failed to bind to address 0.0.0.0/0.0.0.0:50151` → `java.net.BindException: Address already in use`
→ `ExitManager: API_SERVER_INIT(1)`. Two independent harness-side defects:

1. **The old gRPC ports were inside the ephemeral range.** The map put node rpc at
   `50051 + 100*slot` — 50051, 50151, 50251 — all inside macOS's 49152–65535 allocation window.
   The scenario's own preflight saw 50151 free at `14:54:43`; ten seconds later the kernel had
   handed 50151 to another process's *outbound* connection and the bind failed. No preflight can
   close that race — the port must simply not be in the ephemeral range. This was the real cause,
   and it is why the collision landed on rpc rather than p2p: p2p (16667) is outside the window.
2. **Node B was only partially derived.** Its p2p came from a hand-picked `16666+1` while its
   http/rpc/jsonrpc/metrics came from the `+100` family (8190/50151/8645/9627), so its port set was
   neither disjoint from nor aligned with anyone else's.

Both are fixed by the map above: every node's six ports come from one base, and the whole space is
below 49152. The driver's band pre-flight is the backstop for a genuinely stale process, and
`INCONCLUSIVE` (§13) is how such a run is now reported.

One more gap was closed while auditing: `scenario-concurrency-under-fault.sh` bound `A_RPC` and
`B_RPC` for its two fork nodes but never included them in its own `check_ports` call.

---

## 13. Suite grading: PASS / FAIL / INCONCLUSIVE / SKIPPED / TIMEOUT

`run-all.sh` keeps "the product is broken" and "we could not look" in **separate counters**,
because conflating them turns every flaky environment into a false regression report.

| Verdict | When | Counter | Blocks a green suite? |
|---|---|---|---|
| `PASS` | exit 0 **and** an `<NAME>_OK` marker | `passed` | — |
| `FAIL` | exit 1 (or any other nonzero that is not a harness error), or exit 0 with no marker | `failed` | yes |
| `INCONCLUSIVE` | exit 2; or a *nonzero* exit after a `HARNESS_ERROR` line with no verdict marker; or the port pre-flight refused to start it | `inconclusive` | yes |
| `SKIPPED` | exit 77 or an `<NAME>_SKIPPED` marker | `skipped` | no |
| `TIMEOUT` | exceeded `--timeout` and was terminated | `failed` | yes |

`TIMEOUT` counts as `failed`, **not** `inconclusive`, on purpose: a hang is one of the product
defects this suite exists to catch (see the `HUNG` startup verdict in §6), so it must never be
excused as "could not run".

Exit **0** is graded before the `HARNESS_ERROR` check, so a scenario that prints `HARNESS_ERROR`
and then exits 0 anyway is `FAIL` ("exit 0 but no `_OK` marker"), not `INCONCLUSIVE` — claiming
success while reporting it could not run is itself a harness bug worth failing on. The exit
contract in §5 is what keeps this from mattering: a harness error must exit 2.

`INCONCLUSIVE` keeps the suite non-green — the final line is `PRIVATE_CHAIN_FAULT_SUITE_FAIL` and
the exit code is 1, so CI still notices — but it never reads as a product defect. It has its own
column in the summary table and its own counter in the tally line and the final marker:

```
SCENARIO                   VERDICT       SECONDS  MARKER
-------------------------- ------------ --------  --------------------
smoke                      PASS               55  SMOKE_E2E_OK
fork-reorg                 INCONCLUSIVE      120  -
kill-matrix                FAIL              521  KILL_MATRIX_FAIL
-------------------------- ------------ --------  --------------------
TALLY                      passed=1 failed=1 inconclusive=1 skipped=0

PRIVATE_CHAIN_FAULT_SUITE_FAIL scenarios=3 passed=1 failed=1 inconclusive=1 skipped=0 elapsed=696s
```

**Reading the result:** `failed>0` means a regression to investigate. `failed=0` with
`inconclusive>0` means the suite never reached a verdict about the product — re-run it, do not file
a bug. A green suite requires `failed=0` **and** `inconclusive=0` **and** `passed>0`.
