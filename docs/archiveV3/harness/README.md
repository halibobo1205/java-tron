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
  README.md                     this file
  run-all.sh                    suite driver (discovery, ordering, per-scenario timeout, roll-up)

  scenario-smoke.sh             foundation self-test AND the worked template for A-D
  scenario-fork-reorg.sh        (A) fork / reorg across a real Manager.switchFork
  scenario-kill-matrix.sh       (B) the six SIGKILL durability windows
  scenario-resource-faults.sh   (C) ENOSPC, read-only archive dir, truncated MANIFEST
  scenario-concurrency-under-fault.sh  (D) query storm across clean stop / SIGKILL / reorg

  ArchiveProbe.java             offline RocksDB probe: ranges, txNum gaps, span violations,
                                stale in-flight rows, the repair-required META key.
                                *At the harness ROOT, not under java/* -- lib.sh and three
                                scenarios all compile it from here.
  HarnessSigner.java            address derivation + txID signing for the `ah_*` scenarios
  java/Addr.java                private key -> TRON address (used to verify the key table)
  java/Sign.java                txID -> 65-byte signature (there is NO server-side signing servlet)
```

`run-all.sh` treats the **executable bit** as what distinguishes a scenario from a sourced
library, so `scenario-common.sh` must stay non-executable and every real scenario must stay `+x`.

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
* `curl`, `jq`, `python3`, `shasum`, `awk`, `sed`, `grep`, `df`, `dd`. `lsof` is used for the
  port preflight when present. `jdb` is needed only for the scenario-B durability windows.
* **Free TCP ports.** Node slot *N* uses `16666+100N` (p2p), `8090+100N` (HTTP),
  `50051+100N` (gRPC), `8545+100N` (JSON-RPC), `9527+100N` (Prometheus). Every port is
  preflighted; a busy port is a harness error, never a silent reassignment.
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
```

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
| **2** | **Harness / environment error** — inconclusive, no verdict about the product. Missing tool, wrong JDK, busy port, build failure, unreachable jar. | `hs_die` |

Never treat exit 2 as a pass or a fail. Fix the environment and re-run.

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
| `scenario-fork-reorg.sh` | `FORK_E2E_OK` / `_FAIL` | `CHECK [PASS] fork.<name>` lines |
| `scenario-kill-matrix.sh` | `KILL_MATRIX_OK` / `_FAIL` | `PHASE_VERDICT window=… mode=… verdict=…` |
| `scenario-resource-faults.sh` | `FAULT_E2E_OK` / `_FAIL` | `CHECK [PASS] fault.<case>.<name>` lines |
| `scenario-concurrency-under-fault.sh` | `CONCURRENCY_E2E_OK` / `_FAIL` | `PHASE_VERDICT phase=… verdict=…` |
| `run-all.sh` | `PRIVATE_CHAIN_FAULT_SUITE_OK` / `_FAIL` | the per-scenario summary table |

Grep for `_OK$`/`_FAIL` or just check the exit code — they always agree. Two `_FAIL` reasons mean
*"the run proved nothing"* rather than *"the product is broken"*, and both are still failures:

* `reason=no-checks-ran` — the scenario recorded zero checks.
* `reason=nothing-proven` — every verdict was `SKIP` or `INFO`; nothing was actually observed.

`run-all.sh` applies the same rule at suite level: **exit 0 with no `_OK` marker is a FAIL**, and an
all-skipped suite is `reason=nothing-validated`.

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
```

**Reach**: the knob lives in `hs_write_node_config`, so it applies to the scenarios that
materialize nodes through `hs_new_node` — `scenario-smoke.sh` and `scenario-kill-matrix.sh`.
`scenario-resource-faults.sh`, `scenario-fork-reorg.sh` and `scenario-concurrency-under-fault.sh`
emit their own `node.conf` from the `ah_*` templates (`scenario-common.sh:327`,
`scenario-concurrency-under-fault.sh:824`) and are **not** affected; the fork scenario deliberately
runs its own two-witness topology anyway.

Witness 1 is `HS_KEY_WITNESS1` verbatim and `HS_CFG_WITNESS_COUNT=1` regenerates a
**byte-identical** `node.conf`, so existing scenarios are untouched. Witnesses 2..N come from
`HS_WITNESS_KEY_PREFIX` + the 8-hex index (`hs_witness_key_at` / `hs_witness_base58_at`).

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
| `scenario-fork-reorg.sh` | **Executed green after the multi-witness restart fix** with strict reorg deltas and `FORK_ASSERT_RESTART=1`: `FORK_E2E_OK checks=20 passed=19 depth=6 switches=1`; node A restarted cleanly at head 24. |
| `scenario-resource-faults.sh` | **Executed green** for `enospc` + `permission` and for opt-in `truncation`. **Re-run required**: fail-stop now demands a breadcrumb, and the probe now demands `opened == true` and `rangeCount > 0`. |
| `scenario-concurrency-under-fault.sh` | **Executed green twice**, all 5 phases including a real 7-block reorg. The only change from review is the new "no value oracles" gate, which only fires on a chain that never published. |
| `run-all.sh` | Verdict matrix exercised with fake scenarios; driven a real scenario to `PRIVATE_CHAIN_FAULT_SUITE_OK`. Discovery and `--list` re-verified after this review. |
| `scenario-kill-matrix.sh` | **NEVER EXECUTED END TO END.** Anchor resolution, the `javap` jar/source guard, the `jdb` attach→breakpoint→kill mechanism and the probe JSON parsing were each tested in isolation; the full boot → kill → restart → classify cycle has not run. |
| `ArchiveProbe.java`, `HarnessSigner.java`, `java/{Addr,Sign}.java` | Compile cleanly against `framework/build/libs/FullNode.jar` (re-verified). `ArchiveProbe` verified against a synthetic RocksDB with the real CF layout. |
| All eight shell files | `bash -n` clean on bash 3.2 (macOS system bash); no bash-4 constructs. |

### Do this on the first real run, in this order

1. **Rebuild the jar.** `./gradlew :framework:buildFullNodeJar`. The checked-in jar is *stale*:
   `pushBlock()` in it spans source lines 1815–1986 while the current source puts the w2/w3/w4
   anchors at 1994/2010/2013. The `javap` guard correctly rejects this and silently degrades
   w2–w5 to the probabilistic path — a correct outcome that proves far less than intended.
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

The anchors below are documentation only — `scenario-kill-matrix.sh` re-resolves every one of them
from the current source at run time and never places a breakpoint on a hardcoded line
(java-tron @ `3ae4d79f5c`):

| Window | Breakpoint |
|---|---|
| after journal put / before canonical commit | `org.tron.core.db.Manager:1994` |
| after canonical commit / before ack | `org.tron.core.db.Manager:2010` |
| after ack / before publish | `org.tron.core.db.Manager:2013` |
| mid publish batch | `org.tron.core.archive.UnifiedArchiveBackend:120` |
| genesis: after `commitToRoot`, before the COMMITTED marker | `org.tron.core.db.Manager:754` |
| fork-replay journal/commit/ack | `org.tron.core.db.Manager:1657` |

Startup validation a recovery step should assert (`Manager.java:545-575`):
`validateArchiveGenesisCommitMarkerPresence` (:547) · `reconcilePublishedHeadOnStartup` (:552) ·
`reconcileInFlightOnStartup` (:553) · `validateCanonicalHead` (:569) ·
`validateGenesisArchiveCoverage` (:572).

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
GET  :9527/metrics                  Prometheus
```

There is **no** `/wallet/gettransactionsign` servlet — signing is client side via `java/Sign.java`.
`visible:true` ⇒ base58 addresses; omit it ⇒ hex41. Never mix the two in one request, and never
send an empty `function_selector` (`JsonFormat$ParseException`).
