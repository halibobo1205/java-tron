#!/usr/bin/env bash
# shellcheck shell=bash
#
# Shared plumbing for the archive-node fault-injection scenarios.
#
# This file is sourced *after* lib.sh.  Every helper is defined behind a `declare -F` guard, so a
# richer implementation supplied by lib.sh always wins and this file only fills in what is missing.
# That keeps each scenario runnable on its own while still integrating with the shared library.
#
# Everything is written for bash 3.2 (the macOS system bash): no associative arrays, no `mapfile`,
# no `declare -g`.
#
# Naming contract (all helpers are prefixed `ah_`, all globals `AH_`):
#   ah_log/ah_warn/ah_err/ah_fatal  - output; ah_fatal aborts with AH_EXIT_HARNESS
#   ah_check NAME VERDICT DETAIL    - record one machine-checkable verdict
#   ah_finish MARKER                - print the verdict table + OK/FAILED marker, then exit
#   ah_conf_reset / ah_write_node_conf FILE      - generate a private-chain node config
#   ah_start_node / ah_stop_node / ah_wait_exit  - node lifecycle
#   ah_wait_ready / ah_head_num / ah_solid_num / ah_peer_count  - chain observation
#   ah_metric_state / ah_metric_work             - Prometheus observation
#   ah_jsonrpc / ah_eth_balance                  - historical JSON-RPC oracles
#   ah_probe_archive DIR [--require-genesis-complete]  - offline RocksDB probe
#
# Exit codes used by every scenario:
#   0  every gating check passed        (marker: <SCENARIO>_OK)
#   1  at least one gating check failed (marker: <SCENARIO>_FAILED)
#   2  the harness could not run the scenario at all (environment/setup problem)

if [ -n "${AH_COMMON_SOURCED:-}" ]; then
  return 0
fi
AH_COMMON_SOURCED=1

AH_EXIT_PASS=0
AH_EXIT_FAIL=1
AH_EXIT_HARNESS=2

AH_HARNESS_DIR="${AH_HARNESS_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
AH_REPO_ROOT="${AH_REPO_ROOT:-$(cd "$AH_HARNESS_DIR/../../.." && pwd)}"
AH_JAR="${ARCHIVE_HARNESS_JAR:-$AH_REPO_ROOT/framework/build/libs/FullNode.jar}"
AH_JVM_OPTS="${ARCHIVE_HARNESS_JVM_OPTS:--Xms1g -Xmx2g}"
AH_CLASSES_DIR=""

# Every listening port a scenario uses is shifted by this offset, so two harness runs (or a harness
# run and an unrelated local node) can coexist on one machine.
AH_PORT_OFFSET="${ARCHIVE_HARNESS_PORT_OFFSET:-0}"

# Pre-declared so `set -u` never trips on a caller that reads them before a node has been started.
AH_LAST_PID=""
AH_LAST_EXIT=-1

# ---------------------------------------------------------------------------- output

if ! declare -F ah_log >/dev/null 2>&1; then
  ah_log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
fi
if ! declare -F ah_warn >/dev/null 2>&1; then
  ah_warn() { printf '[%s] WARN  %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
fi
if ! declare -F ah_err >/dev/null 2>&1; then
  ah_err() { printf '[%s] ERROR %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
fi
if ! declare -F ah_fatal >/dev/null 2>&1; then
  ah_fatal() {
    ah_err "$*"
    printf 'HARNESS_ERROR %s\n' "$*"
    exit "$AH_EXIT_HARNESS"
  }
fi

# ---------------------------------------------------------------------------- verdicts

AH_CHECK_NAMES=()
AH_CHECK_VERDICTS=()
AH_CHECK_DETAILS=()

# ah_check NAME VERDICT DETAIL
#   VERDICT is PASS, FAIL, SKIP or INFO.  Only FAIL gates the scenario marker; SKIP and INFO are
#   recorded verbatim so a reader can tell "we proved it" from "we could not reach that window".
if ! declare -F ah_check >/dev/null 2>&1; then
  ah_check() {
    local name="$1" verdict="$2" detail="${3:-}"
    AH_CHECK_NAMES+=("$name")
    AH_CHECK_VERDICTS+=("$verdict")
    AH_CHECK_DETAILS+=("$detail")
    printf 'CHECK [%-4s] %-46s %s\n' "$verdict" "$name" "$detail"
  }
fi

if ! declare -F ah_check_bool >/dev/null 2>&1; then
  # ah_check_bool NAME CONDITION_RC DETAIL — 0 => PASS, anything else => FAIL
  ah_check_bool() {
    if [ "$2" -eq 0 ]; then
      ah_check "$1" PASS "$3"
    else
      ah_check "$1" FAIL "$3"
    fi
  }
fi

if ! declare -F ah_failed_count >/dev/null 2>&1; then
  ah_failed_count() {
    local i failed=0
    for i in $(seq 0 $(( ${#AH_CHECK_VERDICTS[@]} - 1 )) ); do
      [ "${AH_CHECK_VERDICTS[$i]}" = "FAIL" ] && failed=$(( failed + 1 ))
    done
    printf '%s' "$failed"
  }
fi

if ! declare -F ah_passed_count >/dev/null 2>&1; then
  ah_passed_count() {
    local i passed=0
    for i in $(seq 0 $(( ${#AH_CHECK_VERDICTS[@]} - 1 )) ); do
      [ "${AH_CHECK_VERDICTS[$i]}" = "PASS" ] && passed=$(( passed + 1 ))
    done
    printf '%s' "$passed"
  }
fi

# ah_finish MARKER_BASE [key=value ...]
#   Prints the verdict table, then the suite-standard marker as the LAST stdout line:
#     <MARKER_BASE>_OK   checks=N [extras]
#     <MARKER_BASE>_FAIL checks=N failures=M [extras]
#   and exits 0 or 1.  Scenarios call this as their last statement.  Anything a scenario still
#   wants to say afterwards (an artifacts path, say) must go to stderr so the marker stays last.
if ! declare -F ah_finish >/dev/null 2>&1; then
  ah_finish() {
    local marker="$1" total="${#AH_CHECK_NAMES[@]}" failed passed i
    shift
    failed="$(ah_failed_count)"
    passed="$(ah_passed_count)"
    echo
    echo "================ ${marker} verdicts ================"
    if [ "$total" -gt 0 ]; then
      for i in $(seq 0 $(( total - 1 )) ); do
        printf '  %-4s %-46s %s\n' \
          "${AH_CHECK_VERDICTS[$i]}" "${AH_CHECK_NAMES[$i]}" "${AH_CHECK_DETAILS[$i]}"
      done
    fi
    echo "==================================================="
    if [ "$total" -eq 0 ]; then
      printf '%s_FAIL checks=0 failures=1 reason=no-checks-ran\n' "$marker"
      exit "$AH_EXIT_FAIL"
    fi
    # ANTI-VACUITY GATE. A run whose every verdict is SKIP or INFO demonstrated nothing --
    # e.g. the ENOSPC case on a host without hdiutil, or a fork run where every oracle
    # degraded to SKIP. Without this gate such a run prints its _OK marker and exits 0,
    # which is indistinguishable from a real pass in CI.
    if [ "$failed" -eq 0 ] && [ "$passed" -eq 0 ]; then
      printf '%s_FAIL checks=%s failures=0 passed=0 reason=nothing-proven%s\n' \
        "$marker" "$total" "$( [ $# -gt 0 ] && printf ' %s' "$*" )"
      exit "$AH_EXIT_FAIL"
    fi
    if [ "$failed" -eq 0 ]; then
      printf '%s_OK checks=%s passed=%s%s\n' \
        "$marker" "$total" "$passed" "$( [ $# -gt 0 ] && printf ' %s' "$*" )"
      exit "$AH_EXIT_PASS"
    fi
    printf '%s_FAIL checks=%s failures=%s passed=%s%s\n' \
      "$marker" "$total" "$failed" "$passed" "$( [ $# -gt 0 ] && printf ' %s' "$*" )"
    exit "$AH_EXIT_FAIL"
  }
fi

# ---------------------------------------------------------------------------- preflight

if ! declare -F ah_require_cmds >/dev/null 2>&1; then
  ah_require_cmds() {
    local cmd missing=""
    for cmd in "$@"; do
      command -v "$cmd" >/dev/null 2>&1 || missing="$missing $cmd"
    done
    [ -z "$missing" ] || ah_fatal "missing required command(s):$missing"
  }
fi

if ! declare -F ah_require_jar >/dev/null 2>&1; then
  ah_require_jar() {
    if [ -f "$AH_JAR" ]; then
      ah_log "using jar $AH_JAR"
      return 0
    fi
    if [ "${ARCHIVE_HARNESS_BUILD:-0}" = "1" ]; then
      ah_log "jar missing; building with ./gradlew :framework:buildFullNodeJar"
      ( cd "$AH_REPO_ROOT" && ./gradlew --quiet :framework:buildFullNodeJar ) \
        || ah_fatal "gradle build failed"
      [ -f "$AH_JAR" ] || ah_fatal "build finished but $AH_JAR is still missing"
      return 0
    fi
    ah_fatal "FullNode jar not found at $AH_JAR (build it with
  ./gradlew :framework:buildFullNodeJar
or re-run with ARCHIVE_HARNESS_BUILD=1, or point ARCHIVE_HARNESS_JAR at an existing jar)"
  }
fi

# Compiles ArchiveProbe/HarnessSigner once per run into $1.
if ! declare -F ah_compile_helpers >/dev/null 2>&1; then
  ah_compile_helpers() {
    local outdir="$1"
    AH_CLASSES_DIR="$outdir"
    mkdir -p "$outdir" || ah_fatal "cannot create $outdir"
    javac -nowarn -cp "$AH_JAR" -d "$outdir" \
      "$AH_HARNESS_DIR/ArchiveProbe.java" "$AH_HARNESS_DIR/HarnessSigner.java" \
      || ah_fatal "failed to compile the harness java helpers"
  }
fi

if ! declare -F ah_signer >/dev/null 2>&1; then
  # ah_signer addr <privHex> | ah_signer sign <privHex> <txIdHex>
  ah_signer() {
    java -cp "$AH_JAR:$AH_CLASSES_DIR" HarnessSigner "$@"
  }
fi

# ah_probe_archive <unified-dir> [--require-genesis-complete]
#   Prints the probe JSON on stdout; returns the probe's exit code (0 clean, 1 violation, 3 unopenable).
if ! declare -F ah_probe_archive >/dev/null 2>&1; then
  ah_probe_archive() {
    java -cp "$AH_JAR:$AH_CLASSES_DIR" ArchiveProbe "$@"
  }
fi

# ---------------------------------------------------------------------------- node config
#
# Key facts baked into this template, each verified against source:
#   * node.rpc.minEffectiveConnection = 0 — an isolated node otherwise answers broadcastTransaction
#     with NO_CONNECTION (framework/src/main/java/org/tron/core/Wallet.java:532-546, default 1 at
#     common/src/main/resources/reference.conf:400).
#   * node.minParticipationRate = 0 — otherwise DPoS refuses to produce on a private chain.
#   * storage.archive keys are whitelisted at
#     common/src/main/java/org/tron/core/config/args/StorageConfig.java:521-570; an unknown key is a
#     startup IllegalArgumentException, so this template only emits allowed keys.
#   * archive.identity.initialize must be true on the FIRST boot of an empty data dir and false on
#     every restart (auto-claim is off by design).

if ! declare -F ah_conf_reset >/dev/null 2>&1; then
  ah_conf_reset() {
    AH_CONF_P2P_PORT=16666
    AH_CONF_HTTP_PORT=8090
    AH_CONF_RPC_PORT=50051
    AH_CONF_JSONRPC_PORT=8545
    AH_CONF_METRICS_PORT=9527
    AH_CONF_P2P_VERSION=20260728
    AH_CONF_WITNESS_KEY=1234567890123456789012345678901234567890123456789012345678901234
    AH_CONF_GENESIS_WITNESSES='    { address: TEDapYSVvAZ3aYH7w8N9tMEEFKaNKUD5Bp, url = "http://sr1.local", voteCount = 100 }'
    AH_CONF_ACTIVE_LIST='[]'
    AH_CONF_ARCHIVE_DIR='archive'
    AH_CONF_IDENTITY_INIT=true
    AH_CONF_ARCHIVE_ENABLE=true
    AH_CONF_SOFT_MIN_FREE=33554432
    AH_CONF_HARD_MIN_FREE=16777216
    AH_CONF_TRX_REFERENCE=solid
    AH_CONF_DEBUG_TRACE=true
    # Multi-SR chains: a no-op at the default count of 1, so the two literals
    # above stay the generated config's only witness source in that case.
    ah_apply_witness_count
  }
fi

# ah_apply_witness_count -- honour HS_CFG_WITNESS_COUNT (default 1) in this template.
#
# WHY: a one-witness chain has solid == head (DposService.updateSolidBlock takes
# index (int)(1 * 0.3) == 0), so the archive's in-flight window -- blocks
# journaled but not yet solidified -- degenerates to ~2 blocks and a fault
# injected here lands on an almost empty window. With 27 SRs on the same single
# node solid trails head by 18 and the window is ~20 blocks, which is the state
# these scenarios are meant to exercise.
#
# Key derivation and address rendering are NOT repeated here: lib.sh's
# hs_witness_conf_blocks (which is hs_witness_key_at + hs_base58_of_priv) owns
# both, so this template and hs_new_node build the same chain from the same keys.
if ! declare -F ah_apply_witness_count >/dev/null 2>&1; then
  ah_apply_witness_count() {
    local count="${HS_CFG_WITNESS_COUNT:-1}"
    if [ "$count" = "1" ]; then
      return 0
    fi
    declare -F hs_witness_conf_blocks >/dev/null 2>&1 \
      || ah_fatal "HS_CFG_WITNESS_COUNT=$count needs lib.sh, which is not sourced"
    # AH_CLASSES_DIR is set by ah_compile_helpers and is inside the run directory;
    # before that call there is nowhere run-local to put Addr.class, and every
    # scenario compiles its helpers during preflight.
    [ -n "${AH_CLASSES_DIR:-}" ] \
      || ah_fatal "ah_apply_witness_count: call ah_compile_helpers before requesting a multi-SR chain"
    hs_witness_conf_blocks "$AH_JAR" "$AH_CLASSES_DIR"
    AH_CONF_WITNESS_KEY="$HS_WITNESS_LOCAL_BLOCK"
    AH_CONF_GENESIS_WITNESSES="$HS_WITNESS_GENESIS_BLOCK"
  }
fi

if ! declare -F ah_write_node_conf >/dev/null 2>&1; then
  ah_write_node_conf() {
    local file="$1"
    mkdir -p "$(dirname "$file")"
    cat >"$file" <<EOF
# Generated by the archive fault harness. Do not edit by hand; regenerate from the scenario script.
net { }

storage {
  db.engine = "LEVELDB"
  db.directory = "database"

  archive {
    enable = ${AH_CONF_ARCHIVE_ENABLE}
    db { directory = "${AH_CONF_ARCHIVE_DIR}", fullScrubOnStartup = false }
    identity { initialize = ${AH_CONF_IDENTITY_INIT} }
    txnum { enable = true }
    temporal { enable = true }
    publisher {
      async = true
      backpressure = true
      softMinFreeBytes = ${AH_CONF_SOFT_MIN_FREE}
      hardMinFreeBytes = ${AH_CONF_HARD_MIN_FREE}
    }
    query {
      jsonRpcWorkerThreads = 4
      maxConcurrentQueries = 8
      maxPendingQueries = 16
      maxOpenSnapshots = 8
      deadlineMs = 30000
    }
    debug { enable = ${AH_CONF_DEBUG_TRACE} }
  }
}

node.discovery = { enable = false, persist = false, external.ip = "127.0.0.1" }

node {
  listen.port = ${AH_CONF_P2P_PORT}
  minParticipationRate = 0
  maxConnectionsWithSameIp = 10
  p2p { version = ${AH_CONF_P2P_VERSION} }
  active = ${AH_CONF_ACTIVE_LIST}
  passive = []

  http    { fullNodeEnable = true, fullNodePort = ${AH_CONF_HTTP_PORT}, solidityEnable = false, PBFTEnable = false }
  rpc     { enable = true, port = ${AH_CONF_RPC_PORT}, minEffectiveConnection = 0, solidityEnable = false, PBFTEnable = false }
  jsonrpc { httpFullNodeEnable = true, httpFullNodePort = ${AH_CONF_JSONRPC_PORT}, httpSolidityEnable = false, httpPBFTEnable = false }
}

node.metrics = { prometheus { enable = true, port = ${AH_CONF_METRICS_PORT} } }

seed.node = { ip.list = [] }

genesis.block = {
  assets = [
    { accountName = "Zion",      accountType = "AssetIssue", address = "TCLBgkbfVkJroVBJVqBEsxtPNQEQMTQCLQ", balance = "90000000000000000" },
    { accountName = "Sun",       accountType = "AssetIssue", address = "TBvJUBXorwBPzqvV38vjDgegj5Eh6g2Tsq", balance = "10000000000000000" },
    { accountName = "Blackhole", accountType = "AssetIssue", address = "TDvSsdrNM5eeXNL3czpa6AxLDHZA9nwe9K", balance = "-9223372036854775808" }
  ]

  witnesses = [
${AH_CONF_GENESIS_WITNESSES}
  ]

  timestamp = "0"
  parentHash = "0x0000000000000000000000000000000000000000000000000000000000000000"
}

localwitness = [
  ${AH_CONF_WITNESS_KEY}
]

block = { needSyncCheck = false, maintenanceTimeInterval = 21600000, proposalExpireTime = 259200000 }
trx = { reference.block = "${AH_CONF_TRX_REFERENCE}" }
vm = { supportConstant = true, saveInternalTx = true }
committee = { allowCreationOfContracts = 1 }
event.subscribe = { enable = false }
EOF
  }
fi

# ---------------------------------------------------------------------------- node lifecycle

# ah_start_node WORKDIR CONF DATADIR [extra java args...]
#   Sets AH_LAST_PID.  The node is launched with CWD=WORKDIR because logback writes logs/tron.log
#   relative to the working directory.
if ! declare -F ah_start_node >/dev/null 2>&1; then
  ah_start_node() {
    local workdir="$1" conf="$2" datadir="$3"
    shift 3
    mkdir -p "$workdir" "$datadir"
    (
      cd "$workdir" || exit 127
      # shellcheck disable=SC2086
      exec java $AH_JVM_OPTS "$@" -jar "$AH_JAR" -c "$conf" -d "$datadir" --witness
    ) >"$workdir/stdout.log" 2>"$workdir/stderr.log" &
    AH_LAST_PID=$!
    ah_log "started node pid=$AH_LAST_PID workdir=$workdir"
  }
fi

if ! declare -F ah_alive >/dev/null 2>&1; then
  ah_alive() { [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null; }
fi

# ah_wait_exit PID TIMEOUT_S — returns 0 when the process exited (AH_LAST_EXIT holds the status),
# 1 when it is still alive at the deadline.
if ! declare -F ah_wait_exit >/dev/null 2>&1; then
  ah_wait_exit() {
    local pid="$1" timeout="$2" deadline
    deadline=$(( $(date +%s) + timeout ))
    while ah_alive "$pid"; do
      if [ "$(date +%s)" -ge "$deadline" ]; then
        return 1
      fi
      sleep 1
    done
    wait "$pid" 2>/dev/null
    AH_LAST_EXIT=$?
    return 0
  }
fi

# ah_stop_node PID [TIMEOUT_S] — graceful SIGTERM, SIGKILL fallback.  Always sets AH_LAST_EXIT,
# including when the process had already exited on its own (a fail-stop, say) before we asked.
if ! declare -F ah_stop_node >/dev/null 2>&1; then
  ah_stop_node() {
    local pid="$1" timeout="${2:-90}"
    [ -n "$pid" ] || return 0
    if ! ah_alive "$pid"; then
      wait "$pid" 2>/dev/null
      AH_LAST_EXIT=$?
      ah_log "node pid=$pid had already exited with status $AH_LAST_EXIT"
      return 0
    fi
    kill -TERM "$pid" 2>/dev/null
    if ah_wait_exit "$pid" "$timeout"; then
      ah_log "node pid=$pid exited with status $AH_LAST_EXIT"
      return 0
    fi
    ah_warn "node pid=$pid ignored SIGTERM for ${timeout}s; sending SIGKILL"
    kill -KILL "$pid" 2>/dev/null
    ah_wait_exit "$pid" 30
    return 0
  }
fi

# ---------------------------------------------------------------------------- chain observation

if ! declare -F ah_node_info >/dev/null 2>&1; then
  ah_node_info() { curl -s --max-time 5 "http://127.0.0.1:$1/wallet/getnodeinfo" 2>/dev/null; }
fi

if ! declare -F ah_head_num >/dev/null 2>&1; then
  ah_head_num() {
    ah_node_info "$1" | jq -r '.block // ""' 2>/dev/null \
      | sed -n 's/^Num:\([0-9][0-9]*\).*/\1/p'
  }
fi

if ! declare -F ah_solid_num >/dev/null 2>&1; then
  ah_solid_num() {
    ah_node_info "$1" | jq -r '.solidityBlock // ""' 2>/dev/null \
      | sed -n 's/^Num:\([0-9][0-9]*\).*/\1/p'
  }
fi

if ! declare -F ah_peer_count >/dev/null 2>&1; then
  ah_peer_count() { ah_node_info "$1" | jq -r '(.peerList // []) | length' 2>/dev/null; }
fi

if ! declare -F ah_block_id >/dev/null 2>&1; then
  ah_block_id() {
    curl -s --max-time 5 -XPOST -H 'Content-Type: application/json' \
      --data "{\"num\":$2}" "http://127.0.0.1:$1/wallet/getblockbynum" 2>/dev/null \
      | jq -r '.blockID // ""' 2>/dev/null
  }
fi

# ah_wait_ready PORT TIMEOUT_S — waits until the HTTP API answers and the chain has a head.
if ! declare -F ah_wait_ready >/dev/null 2>&1; then
  ah_wait_ready() {
    local port="$1" timeout="$2" deadline head
    deadline=$(( $(date +%s) + timeout ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
      head="$(ah_head_num "$port")"
      if [ -n "$head" ] && [ "$head" -ge 0 ] 2>/dev/null; then
        return 0
      fi
      sleep 2
    done
    return 1
  }
fi

# ah_wait_ready_or_exit PID PORT TIMEOUT_S — the startup grader every fault scenario needs.
#   returns 0 the node became ready
#           1 the node exited (AH_LAST_EXIT holds the status)
#           2 neither happened before the deadline — the HUNG verdict
# The third outcome is not hypothetical: an archive startup-validation failure raised inside
# Manager.initInternal throws a plain ArchiveException rather than a TronError, so
# ExitManager.findTronError returns empty, no System.exit runs, and the JVM stays alive on the
# non-daemon Prometheus server started at FullNode.java:51.  Grading a restart by exit code alone
# would hang forever or silently mis-score that case.
if ! declare -F ah_wait_ready_or_exit >/dev/null 2>&1; then
  ah_wait_ready_or_exit() {
    local pid="$1" port="$2" timeout="$3" deadline head
    deadline=$(( $(date +%s) + timeout ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
      if ! ah_alive "$pid"; then
        wait "$pid" 2>/dev/null
        AH_LAST_EXIT=$?
        return 1
      fi
      head="$(ah_head_num "$port")"
      if [ -n "$head" ] && [ "$head" -ge 0 ] 2>/dev/null; then
        return 0
      fi
      sleep 2
    done
    return 2
  }
fi

# ah_wait_head PORT MIN_HEAD TIMEOUT_S
if ! declare -F ah_wait_head >/dev/null 2>&1; then
  ah_wait_head() {
    local port="$1" want="$2" timeout="$3" deadline head
    deadline=$(( $(date +%s) + timeout ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
      head="$(ah_head_num "$port")"
      if [ -n "$head" ] && [ "$head" -ge "$want" ] 2>/dev/null; then
        return 0
      fi
      sleep 2
    done
    return 1
  }
fi

# ---------------------------------------------------------------------------- metrics
#
# The exporter renders labels with a trailing comma, e.g.
#   tron:archive_state{type="repair_required",} 0.0
# so the matcher tolerates the comma and strips the ".0" float suffix.

if ! declare -F ah_metric >/dev/null 2>&1; then
  ah_metric() {
    local port="$1" family="$2" type="$3"
    curl -s --max-time 5 "http://127.0.0.1:$port/metrics" 2>/dev/null \
      | awk -v pat="^${family}\\\\{type=\"${type}\",?\\\\}" '$0 ~ pat { print $NF; exit }' \
      | sed 's/\.0*$//'
  }
fi

if ! declare -F ah_metric_state >/dev/null 2>&1; then
  ah_metric_state() { ah_metric "$1" 'tron:archive_state' "$2"; }
fi

if ! declare -F ah_metric_work >/dev/null 2>&1; then
  ah_metric_work() { ah_metric "$1" 'tron:archive_work_total' "$2"; }
fi

if ! declare -F ah_metric_fork >/dev/null 2>&1; then
  ah_metric_fork() { ah_metric "$1" 'tron:block_fork_total' "$2"; }
fi

# Best-effort published archive head: the block below the oldest in-flight block, or the solid head
# when nothing is in flight.  Used only to pick heights that are safe to query.
if ! declare -F ah_published_head >/dev/null 2>&1; then
  ah_published_head() {
    local port="$1" inflight oldest solid
    inflight="$(ah_metric_state "$port" inflight_blocks)"
    oldest="$(ah_metric_state "$port" oldest_inflight_block)"
    solid="$(ah_solid_num "$port")"
    if [ -n "$inflight" ] && [ "$inflight" -gt 0 ] 2>/dev/null \
       && [ -n "$oldest" ] && [ "$oldest" -gt 0 ] 2>/dev/null; then
      printf '%s' $(( oldest - 1 ))
      return 0
    fi
    printf '%s' "${solid:-0}"
  }
fi

# ---------------------------------------------------------------------------- JSON-RPC oracles
#
# Routing gate: ArchiveJsonRpcStateAdapter.shouldUseArchive() == archive enabled AND tag != latest.
# "finalized" is NOT usable as a historical selector here — it resolves to the solid head, which on
# a private chain is typically not published yet.  Always pass an explicit hex height.

if ! declare -F ah_jsonrpc >/dev/null 2>&1; then
  ah_jsonrpc() {
    local port="$1" method="$2" params="$3"
    curl -s --max-time 40 -XPOST -H 'Content-Type: application/json' \
      --data "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"${method}\",\"params\":${params}}" \
      "http://127.0.0.1:${port}/jsonrpc" 2>/dev/null
  }
fi

# ah_eth_balance PORT ETH_ADDRESS HEX_TAG
#   Prints either the 0x result, or "ERR:<code>:<message>" so the caller can assert fail-closed
#   behaviour, or "ERR:transport" when the node did not answer at all.
if ! declare -F ah_eth_balance >/dev/null 2>&1; then
  ah_eth_balance() {
    local body result
    body="$(ah_jsonrpc "$1" eth_getBalance "[\"$2\",\"$3\"]")"
    if [ -z "$body" ]; then
      printf 'ERR:transport'
      return 0
    fi
    result="$(printf '%s' "$body" | jq -r '
      if .result != null then .result
      else "ERR:" + ((.error.code // "?") | tostring) + ":" + (.error.message // "?")
      end' 2>/dev/null)"
    printf '%s' "${result:-ERR:unparsable}"
  }
fi

# ---------------------------------------------------------------------------- log forensics
#
# There is no separate archive log file: logback.xml declares CONSOLE/DB/GRPC_FILE only, so the
# archive logger topic lands in <workdir>/logs/tron.log.  The watchdog breadcrumb for exit 70 is
# written to stderr and never reaches tron.log, hence stderr.log is grepped too.

if ! declare -F ah_log_files >/dev/null 2>&1; then
  ah_log_files() { printf '%s %s %s' "$1/logs/tron.log" "$1/stderr.log" "$1/stdout.log"; }
fi

if ! declare -F ah_grep_logs >/dev/null 2>&1; then
  # ah_grep_logs WORKDIR PATTERN — prints matching lines from every log the node writes.
  # The `|| true` is load-bearing under `set -e`: grep exits 1 when a pattern is absent,
  # which is the NORMAL case for every "no error in the log" check, and an unguarded
  # failure inside `$( )` would abort the caller instead of yielding an empty string.
  ah_grep_logs() {
    local workdir="$1" pattern="$2" f
    for f in "$workdir/logs/tron.log" "$workdir/stderr.log" "$workdir/stdout.log"; do
      [ -f "$f" ] || continue
      grep -a -- "$pattern" "$f" || true
    done
    return 0
  }
fi

# ah_logs_present WORKDIR — 0 when the node actually wrote a log.
# Guards the "no error in the log" family of checks: a node that never started writes no
# log at all, and an absent log trivially contains no errors.
if ! declare -F ah_logs_present >/dev/null 2>&1; then
  ah_logs_present() {
    [ -s "$1/logs/tron.log" ] || [ -s "$1/stderr.log" ] || [ -s "$1/stdout.log" ]
  }
fi

if ! declare -F ah_count_logs >/dev/null 2>&1; then
  ah_count_logs() { ah_grep_logs "$1" "$2" | grep -c . || true; }
fi

# The three explicit archive fail-stop breadcrumbs, in one matcher:
#   ExitManager.logAndExit         -> "Shutting down with code: ARCHIVE_RUNTIME(1)"
#   ArchiveFatalController         -> "archive fatal watchdog timeout; halting with exit status 70"
#   startup repair gate            -> "archive repair required: ..."
if ! declare -F ah_failstop_evidence >/dev/null 2>&1; then
  ah_failstop_evidence() {
    ah_grep_logs "$1" 'Shutting down with code: ARCHIVE_RUNTIME'
    ah_grep_logs "$1" 'archive fatal watchdog timeout'
    ah_grep_logs "$1" 'archive repair required:'
    return 0
  }
fi

# Every archive message that attributes a stop to storage space.  Two shapes exist and both matter:
#   awaitWriterCapacity      (DefaultArchiveService.java:659-666, 680-683) — the branch a filling
#                            volume actually takes; carries ", diskFree=<bytes>"
#   the reserve/preflight    (DefaultArchiveService.java:1166-1172, 1923-1929) — "archive journal
#                            filesystem is below hard free-space watermark" / "... cannot reserve
#                            the next durable write"
if ! declare -F ah_disk_fault_evidence >/dev/null 2>&1; then
  ah_disk_fault_evidence() {
    ah_grep_logs "$1" 'diskFree='
    ah_grep_logs "$1" 'archive journal filesystem'
    return 0
  }
fi

if ! declare -F ah_archive_error_evidence >/dev/null 2>&1; then
  ah_archive_error_evidence() {
    ah_grep_logs "$1" 'ArchiveException'
    ah_grep_logs "$1" 'archive journal filesystem'
    ah_grep_logs "$1" 'fatal archive sidecar initialization failure'
    return 0
  }
fi

# A one-line, verdict-table-sized version of the above.  A Spring startup failure nests the archive
# cause inside a multi-hundred-character UnsatisfiedDependencyException chain, so pull out just the
# ArchiveException message and cap its length.
if ! declare -F ah_archive_error_summary >/dev/null 2>&1; then
  ah_archive_error_summary() {
    ah_archive_error_evidence "$1" \
      | sed -n 's/.*\(org\.tron\.core\.archive\.[A-Za-z]*Exception: [^;]*\).*/\1/p' \
      | tail -1 | cut -c1-200
  }
fi

# ---------------------------------------------------------------------------- misc

# A busy port is not a harmless nuisance: a node whose Prometheus port is taken dies immediately
# with PROMETHEUS_INIT, while the scenario's HTTP probes happily talk to whatever else owns the
# port — every subsequent verdict would describe someone else's node.  lsof is preferred; the
# /dev/tcp fallback keeps the check working when lsof is absent.
if ! declare -F ah_free_port >/dev/null 2>&1; then
  ah_free_port() {
    local port="$1"
    if command -v lsof >/dev/null 2>&1; then
      ! lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
      return $?
    fi
    ! (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null
  }
fi

if ! declare -F ah_require_free_ports >/dev/null 2>&1; then
  ah_require_free_ports() {
    local port busy=""
    for port in "$@"; do
      ah_free_port "$port" || busy="$busy $port"
    done
    [ -z "$busy" ] || ah_fatal "port(s) already in use:$busy — another node (or an earlier harness
run) owns them. Stop it, or re-run with ARCHIVE_HARNESS_PORT_OFFSET set to shift every port."
  }
fi

if ! declare -F ah_run_dir >/dev/null 2>&1; then
  # ah_run_dir NAME — creates and prints a fresh run directory.
  ah_run_dir() {
    local base="${ARCHIVE_HARNESS_RUN_ROOT:-${TMPDIR:-/tmp}/archive-harness}"
    local dir="$base/$1-$(date +%Y%m%d-%H%M%S)-$$"
    mkdir -p "$dir" || ah_fatal "cannot create run directory $dir"
    printf '%s' "$dir"
  }
fi
