# shellcheck shell=bash
#
# lib.sh -- shared foundation for the java-tron archive fault-injection harness.
#
# Source this from a scenario script:
#
#   #!/usr/bin/env bash
#   set -euo pipefail
#   . "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
#   hs_init my-scenario
#   node="$(hs_new_node a 0)"
#   hs_node_start "$node"; hs_node_wait_ready "$node"
#   ...
#   hs_finish MY_E2E_OK "cases=3"
#
# Contract (see README.md):
#   exit 0 -- scenario passed
#   exit 1 -- the PRODUCT misbehaved (a check failed)
#   exit 2 -- the HARNESS/environment could not run the scenario (inconclusive)
#
# Nothing here modifies java-tron production source.
#
# Every helper is written to be safe under `set -euo pipefail`: pipelines whose
# components legitimately return non-zero (grep with no match) are guarded.
#
# Compatible with bash 3.2 (the macOS system bash): no associative arrays, no
# `readarray`, no `${var,,}`.

if [ "${HS_LIB_SOURCED:-0}" = "1" ]; then
  return 0
fi
HS_LIB_SOURCED=1

# ---------------------------------------------------------------------------
# Exit codes
# ---------------------------------------------------------------------------
HS_EXIT_PASS=0
HS_EXIT_SCENARIO_FAIL=1
HS_EXIT_HARNESS_ERROR=2

# ---------------------------------------------------------------------------
# Deterministic keys for the private chain.
#
# Every key/address binding is ASSERTED at hs_init time (hs_verify_key_table),
# so drift aborts instead of funding the wrong account.
# ---------------------------------------------------------------------------
HS_KEY_WITNESS1="1234567890123456789012345678901234567890123456789012345678901234"
HS_ADDR_WITNESS1="TEDapYSVvAZ3aYH7w8N9tMEEFKaNKUD5Bp"

HS_KEY_WITNESS2="5555555555555555555555555555555555555555555555555555555555555555"
HS_ADDR_WITNESS2="TWa5cxQFesyCQUm17usvHrVkKce6rMCV4H"

# Genesis-funded "Zion" -- the harness funding source.
HS_KEY_ZION="1111111111111111111111111111111111111111111111111111111111111111"
HS_ADDR_ZION="TCLBgkbfVkJroVBJVqBEsxtPNQEQMTQCLQ"

# Genesis-funded "Sun" -- the default transfer sink.
HS_KEY_SUN="2222222222222222222222222222222222222222222222222222222222222222"
HS_ADDR_SUN="TBvJUBXorwBPzqvV38vjDgegj5Eh6g2Tsq"

# Blackhole (genesis-required; never a sender).
HS_ADDR_BLACKHOLE="TDvSsdrNM5eeXNL3czpa6AxLDHZA9nwe9K"

# ---------------------------------------------------------------------------
# Multi-SR key scheme (HS_CFG_WITNESS_COUNT).
#
# WHY THIS EXISTS: on a one-witness chain DposService.updateSolidBlock()
# (consensus/src/main/java/org/tron/consensus/dpos/DposService.java:159) sorts
# the ONE active witness's latestBlockNum and takes index
# (int)(1 * (1 - 70/100)) == 0 -- so solid == head and the archive's in-flight
# window (blocks journaled but not yet solidified) degenerates to ~1 block.
# With N witnesses the index is P = (int)(N * 0.3) and, in steady state, the
# sorted latestBlockNum list is head-(N-1) .. head, so solid lands on
# head - (N - 1 - P). MEASURED at N=27: P=8, lag=18 (~54 s at the 3 s slot).
#
# ONE node can hold every witness: `localwitness` is a LIST, Args.java:919 ->
# WitnessInitializer.initFromCFGPrivateKey() keeps all of them, and
# ConsensusService.java:56-69 turns each private key into its own Miner, so
# DposTask.java:116 finds a local miner for every scheduled slot.
#
# Witness 1 is HS_KEY_WITNESS1 VERBATIM, so a 1-witness chain -- i.e. every
# pre-existing scenario -- keeps a byte-identical node.conf. Witness i > 1 is
# this 56-hex-char prefix plus the 8-hex-char index: distinct by construction,
# always a valid secp256k1 scalar (0 < 0xa5a5... < curve order n, whose top
# limb is 0xffffffff), and -- because it starts with a letter -- always
# tokenized by HOCON as unquoted text rather than a number.
HS_WITNESS_KEY_PREFIX="a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5"

# Vote count handed to every generated witness. Equal votes are fine: ties in
# ConsensusDelegate.sortWitness() break on the address bytes, so the schedule
# is still deterministic across restarts.
HS_WITNESS_VOTES="100"

# MAX_ACTIVE_WITNESS_NUM (common/src/main/java/org/tron/core/config/Parameter.java:66).
# DposService.updateWitness() truncates anything past this, so a larger count
# would silently produce a chain whose real SR set is 27.
HS_MAX_WITNESSES=27

# ---------------------------------------------------------------------------
# Minimal storage contract used by the state oracles.
#
# Hand-assembled so the harness needs no solc. Uses only frontier-era opcodes,
# so no TVM proposal has to be active on the private chain (selector dispatch
# via SHR is deliberately avoided).
#
#   init (11 bytes):  6017 80 600b 6000 39 6000 f3
#                     PUSH1 0x17 DUP1 PUSH1 0x0b PUSH1 0x00 CODECOPY
#                     PUSH1 0x00 RETURN
#   runtime (23 bytes):
#     0x00  36          CALLDATASIZE
#     0x01  600f        PUSH1 0x0f            ; setter entry
#     0x03  57          JUMPI                 ; calldata present => write path
#     0x04  6000 54     PUSH1 0x00 SLOAD
#     0x07  6000 52     PUSH1 0x00 MSTORE
#     0x0a  6020 6000 f3  RETURN 32 bytes of slot 0
#     0x0f  5b          JUMPDEST
#     0x10  6000 35     PUSH1 0x00 CALLDATALOAD
#     0x13  6000 55     PUSH1 0x00 SSTORE
#     0x16  00          STOP
#
# Semantics: a call carrying 32 bytes of data does SSTORE(slot 0, word) -- a
# real state transition the archive must capture; a call with empty data
# returns slot 0, which is what the eth_call oracle exercises.
#
# ORACLE DISCIPLINE: compare archive-vs-live AT THE SAME HEIGHT, never against
# a hard-coded literal. eth_getCode returns SmartContractDataWrapper#
# getRuntimecode(), whose value on a bare private chain is a chain-config
# artifact (README "Known traps").
# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# Startup governance flags written into every generated config.
#
# reference.conf:905 defaults every one of these to 0, which leaves the private
# chain on pre-Constantinople TVM rules. That is not a cosmetic difference: a
# deploy still executes and returns the correct runtime code, but the code is
# never persisted, so eth_getCode answers with 32 zero bytes and every later
# call executes nothing -- no SSTORE lands and storage oracles read 0 forever.
# Verified A/B on this repo with archive DISABLED: byte-identical behaviour, so
# this is a chain-configuration artifact, never an archive defect.
#
# Override wholesale with HS_CFG_COMMITTEE.
# ---------------------------------------------------------------------------
HS_DEFAULT_COMMITTEE='  allowCreationOfContracts = 1
  allowMultiSign = 1
  allowAdaptiveEnergy = 0
  allowDelegateResource = 1
  allowSameTokenName = 1
  allowTvmTransferTrc10 = 1
  allowTvmConstantinople = 1
  allowTvmSolidity059 = 1
  allowTvmIstanbul = 1
  allowTvmCompatibleEvm = 1
  allowTvmLondon = 1'

HS_CONTRACT_INIT_HEX="601780600b6000396000f3"
HS_CONTRACT_RUNTIME_HEX="36600f5760005460005260206000f35b60003560005500"
HS_CONTRACT_DEPLOY_HEX="${HS_CONTRACT_INIT_HEX}${HS_CONTRACT_RUNTIME_HEX}"

# ---------------------------------------------------------------------------
# Internal state
# ---------------------------------------------------------------------------
HS_SCENARIO_NAME=""
HS_FAILURES=0
HS_CHECKS=0
HS_HARNESS_DIR=""
HS_REPO_ROOT=""
HS_JAR=""
HS_CLASSES=""
HS_RUN_DIR=""
HS_STARTED_NODES=""
HS_ADDR_CACHE=""

# ===========================================================================
# Logging and verdicts
# ===========================================================================

hs_ts() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

hs_log() {
  printf '[%s] %s\n' "$(hs_ts)" "$*" >&2
}

hs_step() {
  printf '\n[%s] == %s\n' "$(hs_ts)" "$*" >&2
}

# hs_die <msg...> -- the harness cannot run. NOT a product verdict.
hs_die() {
  printf '[%s] HARNESS-ERROR: %s\n' "$(hs_ts)" "$*" >&2
  exit "$HS_EXIT_HARNESS_ERROR"
}

# hs_abort <msg...> -- unrecoverable product failure; stop the scenario now.
hs_abort() {
  printf '[%s] FAIL: %s\n' "$(hs_ts)" "$*" >&2
  HS_FAILURES=$((HS_FAILURES + 1))
  HS_CHECKS=$((HS_CHECKS + 1))
  hs_dump_context
  exit "$HS_EXIT_SCENARIO_FAIL"
}

# hs_fail <msg...> -- record a product failure, keep going. Always returns 0 so
# `set -e` callers never need `|| true`.
hs_fail() {
  printf '[%s] FAIL: %s\n' "$(hs_ts)" "$*" >&2
  HS_FAILURES=$((HS_FAILURES + 1))
  HS_CHECKS=$((HS_CHECKS + 1))
  return 0
}

# hs_pass <msg...> -- record a satisfied check.
hs_pass() {
  printf '[%s] ok: %s\n' "$(hs_ts)" "$*" >&2
  HS_CHECKS=$((HS_CHECKS + 1))
  return 0
}

hs_assert_eq() {
  local expected="$1" actual="$2"
  shift 2
  if [ "$expected" = "$actual" ]; then
    hs_pass "$* (= $actual)"
  else
    hs_fail "$*: expected [$expected], got [$actual]"
  fi
}

hs_assert_ne() {
  local unexpected="$1" actual="$2"
  shift 2
  if [ "$unexpected" != "$actual" ]; then
    hs_pass "$* (got $actual)"
  else
    hs_fail "$*: value must not be [$unexpected]"
  fi
}

hs_assert_contains() {
  local haystack="$1" needle="$2"
  shift 2
  case "$haystack" in
    *"$needle"*) hs_pass "$* (contains '$needle')" ;;
    *) hs_fail "$*: [$haystack] does not contain [$needle]" ;;
  esac
}

# hs_dump_context -- tail every started node's logs, for post-mortem.
hs_dump_context() {
  local node
  for node in $HS_STARTED_NODES; do
    [ -d "$node" ] || continue
    printf '\n----- %s : tail logs/tron.log -----\n' "$node" >&2
    tail -n 40 "$node/logs/tron.log" 2>/dev/null >&2 || true
    if [ -s "$node/stderr.log" ]; then
      printf -- '----- %s : tail stderr.log -----\n' "$node" >&2
      tail -n 20 "$node/stderr.log" >&2 || true
    fi
  done
  return 0
}

# hs_marker <NAME> [k=v ...] -- machine-checkable marker on STDOUT.
hs_marker() {
  local name="$1"
  shift || true
  if [ "$#" -gt 0 ]; then
    printf '%s %s\n' "$name" "$*"
  else
    printf '%s\n' "$name"
  fi
}

# hs_finish <OK_MARKER> [k=v ...] -- final verdict and exit.
# Prints <OK_MARKER> and exits 0 when at least one check ran and none failed;
# otherwise prints the _FAIL form and exits 1.
#
# ANTI-VACUITY GATE: a run that recorded ZERO checks proved nothing, so it can never
# be green. Without this, any scenario whose setup silently short-circuited would
# print its _OK marker and exit 0.
hs_finish() {
  local ok_marker="$1"
  shift || true
  hs_stop_all_nodes
  local fail_marker_early
  case "$ok_marker" in
    *_OK) fail_marker_early="${ok_marker%_OK}_FAIL" ;;
    *) fail_marker_early="${ok_marker}_FAIL" ;;
  esac
  if [ "$HS_CHECKS" -eq 0 ]; then
    hs_log "no check was recorded -- the scenario proved nothing"
    hs_dump_context
    hs_marker "$fail_marker_early" "checks=0" "failures=1" "reason=no-checks-ran" "$@"
    exit "$HS_EXIT_SCENARIO_FAIL"
  fi
  if [ "$HS_FAILURES" -eq 0 ]; then
    hs_marker "$ok_marker" "checks=$HS_CHECKS" "$@"
    exit "$HS_EXIT_PASS"
  fi
  local fail_marker
  case "$ok_marker" in
    *_OK) fail_marker="${ok_marker%_OK}_FAIL" ;;
    *) fail_marker="${ok_marker}_FAIL" ;;
  esac
  hs_dump_context
  hs_marker "$fail_marker" "checks=$HS_CHECKS" "failures=$HS_FAILURES" "$@"
  exit "$HS_EXIT_SCENARIO_FAIL"
}

# ===========================================================================
# Environment and build
# ===========================================================================

hs_require_tools() {
  local missing="" tool
  for tool in curl jq java javac python3 shasum awk sed grep df dd; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      missing="$missing $tool"
    fi
  done
  [ -z "$missing" ] || hs_die "missing required tools:$missing"

  local banner major
  banner="$(java -version 2>&1 || true)"
  major="$(printf '%s\n' "$banner" | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')"
  case "$major" in
    17|18|19|2[0-9]) : ;;
    *) hs_die "java 17+ required on PATH; found: $(printf '%s\n' "$banner" | head -1)" ;;
  esac
}

# hs_repo_root -- the java-tron checkout that owns this harness.
hs_repo_root() {
  if [ -n "$HS_REPO_ROOT" ]; then
    printf '%s\n' "$HS_REPO_ROOT"
    return 0
  fi
  [ -n "$HS_HARNESS_DIR" ] || hs_die "hs_repo_root before hs_init"
  local root
  root="$(cd "$HS_HARNESS_DIR/../../.." >/dev/null 2>&1 && pwd)" \
    || hs_die "cannot resolve repo root from $HS_HARNESS_DIR"
  [ -f "$root/gradlew" ] \
    || hs_die "no gradlew at $root -- the harness must live at docs/archiveV3/harness"
  [ -d "$root/framework" ] || hs_die "no framework module at $root"
  HS_REPO_ROOT="$root"
  printf '%s\n' "$HS_REPO_ROOT"
}

# hs_jar_path -- the jar under test.
#
# An explicitly supplied jar wins so run-all.sh's --jar reaches these scenarios too; both
# spellings are honoured because the ah_* scenarios use ARCHIVE_HARNESS_JAR and the
# concurrency scenario uses FULLNODE_JAR.
hs_jar_path() {
  local override="${ARCHIVE_HARNESS_JAR:-${FULLNODE_JAR:-}}"
  if [ -n "$override" ]; then
    printf '%s\n' "$override"
    return 0
  fi
  printf '%s/framework/build/libs/FullNode.jar\n' "$(hs_repo_root)"
}

# hs_build_jar -- ensure FullNode.jar exists. Reuses an existing jar unless
# HS_FORCE_BUILD=1; HS_SKIP_BUILD=1 forbids building.
hs_build_jar() {
  local jar digest
  jar="$(hs_jar_path)"
  if [ -f "$jar" ] && [ "${HS_FORCE_BUILD:-0}" != "1" ]; then
    digest="$(shasum -a 256 "$jar" | awk '{print $1}')"
    hs_log "reusing jar: $jar"
    hs_log "jar sha256 : $digest"
    HS_JAR="$jar"
    return 0
  fi
  if [ "${HS_SKIP_BUILD:-0}" = "1" ]; then
    hs_die "HS_SKIP_BUILD=1 but there is no jar at $jar"
  fi
  hs_step "building FullNode.jar (./gradlew :framework:buildFullNodeJar)"
  ( cd "$(hs_repo_root)" && ./gradlew --console=plain :framework:buildFullNodeJar ) \
    || hs_die "gradle build failed"
  [ -f "$jar" ] || hs_die "gradle finished but $jar is missing"
  HS_JAR="$jar"
  digest="$(shasum -a 256 "$jar" | awk '{print $1}')"
  hs_log "built jar : $jar"
  hs_log "jar sha256: $digest"
}

# hs_build_java_helpers -- compile Addr/Sign against the fat jar.
hs_build_java_helpers() {
  [ -n "$HS_JAR" ] || hs_die "hs_build_java_helpers before hs_build_jar"
  [ -n "$HS_RUN_DIR" ] || hs_die "hs_build_java_helpers before hs_init"
  HS_CLASSES="$HS_RUN_DIR/classes"
  mkdir -p "$HS_CLASSES"
  javac -nowarn -cp "$HS_JAR" -d "$HS_CLASSES" \
    "$HS_HARNESS_DIR/java/Sign.java" "$HS_HARNESS_DIR/java/Addr.java" \
    || hs_die "cannot compile the harness java helpers against $HS_JAR"
  HS_ADDR_CACHE="$HS_RUN_DIR/addr.cache"
  : >"$HS_ADDR_CACHE"
  hs_log "compiled java helpers into $HS_CLASSES"
}

# hs_addr_of_priv <privHex> -- echo "<base58> <hex41>" (JVM result is cached).
hs_addr_of_priv() {
  local priv="$1" cached out
  if [ -n "$HS_ADDR_CACHE" ] && [ -f "$HS_ADDR_CACHE" ]; then
    cached="$(grep "^$priv " "$HS_ADDR_CACHE" 2>/dev/null | head -1 || true)"
    if [ -n "$cached" ]; then
      printf '%s\n' "${cached#* }"
      return 0
    fi
  fi
  out="$(java -cp "$HS_CLASSES:$HS_JAR" Addr "$priv" 2>&1)" \
    || hs_die "Addr helper failed for key $(printf '%s' "$priv" | cut -c1-8)...: $out"
  if [ -n "$HS_ADDR_CACHE" ]; then
    printf '%s %s\n' "$priv" "$out" >>"$HS_ADDR_CACHE"
  fi
  printf '%s\n' "$out"
}

hs_base58_of_priv() {
  hs_addr_of_priv "$1" | awk '{print $1}'
}

hs_hex41_of_priv() {
  hs_addr_of_priv "$1" | awk '{print $2}'
}

# hs_eth_of_hex41 <hex41> -- 41xx.. -> 0xxx.. (the JSON-RPC address form).
hs_eth_of_hex41() {
  local hex="$1"
  case "$hex" in
    41*) printf '0x%s\n' "${hex#41}" ;;
    0x*) printf '%s\n' "$hex" ;;
    *) hs_die "not a hex41 address: $hex" ;;
  esac
}

hs_eth_of_priv() {
  hs_eth_of_hex41 "$(hs_hex41_of_priv "$1")"
}

# hs_witness_key_at <index> -- the deterministic private key of the <index>'th
# harness witness (1-based). Index 1 is HS_KEY_WITNESS1, so the single-witness
# configuration is unchanged; see the HS_WITNESS_KEY_PREFIX block above.
hs_witness_key_at() {
  local idx="$1"
  case "$idx" in
    ''|*[!0-9]*) hs_die "hs_witness_key_at: not a non-negative integer: '$idx'" ;;
  esac
  [ "$idx" -ge 1 ] || hs_die "hs_witness_key_at: index is 1-based, got $idx"
  if [ "$idx" -eq 1 ]; then
    printf '%s\n' "$HS_KEY_WITNESS1"
    return 0
  fi
  [ "${#HS_WITNESS_KEY_PREFIX}" -eq 56 ] \
    || hs_die "HS_WITNESS_KEY_PREFIX must be 56 hex chars, is ${#HS_WITNESS_KEY_PREFIX}"
  printf '%s%08x\n' "$HS_WITNESS_KEY_PREFIX" "$idx"
}

# hs_witness_base58_at <index> -- base58check address of hs_witness_key_at.
hs_witness_base58_at() {
  hs_base58_of_priv "$(hs_witness_key_at "$1")"
}

# hs_verify_key_table -- assert every hard-coded key/address pair still binds.
hs_verify_key_table() {
  local pair got want
  for pair in \
    "$HS_KEY_WITNESS1:$HS_ADDR_WITNESS1" \
    "$HS_KEY_WITNESS2:$HS_ADDR_WITNESS2" \
    "$HS_KEY_ZION:$HS_ADDR_ZION" \
    "$HS_KEY_SUN:$HS_ADDR_SUN"; do
    want="${pair##*:}"
    got="$(hs_base58_of_priv "${pair%%:*}")"
    [ "$got" = "$want" ] \
      || hs_die "key table drift: ${pair%%:*} derives $got, expected $want"
  done
  hs_log "key table verified (4 keys)"
}

# ===========================================================================
# Init and cleanup
# ===========================================================================

# hs_init <scenario-name>
#
# HS_WORK_ROOT     parent of the run directory
#                  (default ${TMPDIR}/java-tron-archive-harness)
# HS_KEEP_WORKDIR  1 to print (and keep) the run directory on exit
# HS_FORCE_BUILD   1 to rebuild the jar
# HS_SKIP_BUILD    1 to require a prebuilt jar
hs_init() {
  HS_SCENARIO_NAME="${1:-scenario}"
  HS_HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)" \
    || hs_die "cannot resolve the harness directory"

  hs_require_tools
  hs_repo_root >/dev/null

  local work_root
  work_root="${HS_WORK_ROOT:-${TMPDIR:-/tmp}/java-tron-archive-harness}"
  HS_RUN_DIR="$work_root/${HS_SCENARIO_NAME}-$(date -u '+%Y%m%d-%H%M%S')-$$"
  mkdir -p "$HS_RUN_DIR" || hs_die "cannot create the run directory $HS_RUN_DIR"

  hs_build_jar
  hs_build_java_helpers
  hs_verify_key_table

  trap 'hs_on_exit' EXIT
  trap 'hs_log interrupted; exit '"$HS_EXIT_HARNESS_ERROR" INT TERM

  hs_log "scenario: $HS_SCENARIO_NAME"
  hs_log "repo    : $(hs_repo_root)"
  hs_log "run dir : $HS_RUN_DIR"
}

hs_on_exit() {
  hs_stop_all_nodes >/dev/null 2>&1 || true
  if [ "${HS_KEEP_WORKDIR:-0}" = "1" ]; then
    hs_log "run dir kept: $HS_RUN_DIR"
  fi
  return 0
}

# ===========================================================================
# Ports
# ===========================================================================

# hs_port_free <port> -- 0 when nothing is listening.
hs_port_free() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 && return 1
    return 0
  fi
  nc -z 127.0.0.1 "$port" >/dev/null 2>&1 && return 1
  return 0
}

hs_require_free_ports() {
  local port
  for port in "$@"; do
    if ! hs_port_free "$port"; then
      hs_die "port $port is already in use (stale node? try: lsof -nP -iTCP:$port -sTCP:LISTEN)"
    fi
  done
}

# hs_assign_ports <node_dir> <slot>
#
# slot 0 -> the documented defaults; slot N -> every port +100*N so a
# multi-node topology never collides. Writes <node_dir>/ports.env.
hs_assign_ports() {
  local node_dir="$1" slot="${2:-0}"
  local off=$((slot * 100))
  HS_PORT_P2P=$((16666 + off))
  HS_PORT_HTTP=$((8090 + off))
  HS_PORT_RPC=$((50051 + off))
  HS_PORT_JSONRPC=$((8545 + off))
  HS_PORT_PROM=$((9527 + off))
  mkdir -p "$node_dir"
  cat >"$node_dir/ports.env" <<EOF
HS_PORT_P2P=$HS_PORT_P2P
HS_PORT_HTTP=$HS_PORT_HTTP
HS_PORT_RPC=$HS_PORT_RPC
HS_PORT_JSONRPC=$HS_PORT_JSONRPC
HS_PORT_PROM=$HS_PORT_PROM
EOF
  hs_require_free_ports "$HS_PORT_P2P" "$HS_PORT_HTTP" "$HS_PORT_RPC" \
    "$HS_PORT_JSONRPC" "$HS_PORT_PROM"
}

hs_load_ports() {
  local node_dir="$1"
  [ -f "$node_dir/ports.env" ] || hs_die "no ports.env in $node_dir"
  # shellcheck disable=SC1090,SC1091
  . "$node_dir/ports.env"
}

hs_http_url() {
  hs_load_ports "$1"
  printf 'http://127.0.0.1:%s\n' "$HS_PORT_HTTP"
}

hs_jsonrpc_url() {
  hs_load_ports "$1"
  printf 'http://127.0.0.1:%s/jsonrpc\n' "$HS_PORT_JSONRPC"
}

hs_metrics_url() {
  hs_load_ports "$1"
  printf 'http://127.0.0.1:%s/metrics\n' "$HS_PORT_PROM"
}

hs_p2p_port() {
  hs_load_ports "$1"
  printf '%s\n' "$HS_PORT_P2P"
}

# ===========================================================================
# Node materialization
# ===========================================================================

# hs_new_node <name> [slot] -- fresh data dir + config. Echoes the node dir.
#
# Overridable before the call (all optional):
#   HS_CFG_WITNESS_COUNT          SRs on this ONE node, 1..27     (default 1)
#                                 >1 derives the whole set and drives BOTH
#                                 genesis.block.witnesses and localwitness;
#                                 mutually exclusive with the two knobs below
#   HS_CFG_WITNESS_KEY            witness private key            (default W1)
#   HS_CFG_GENESIS_WITNESSES      "addr:votes[,addr:votes...]"   (default W1:100)
#   HS_CFG_ACTIVE_PEERS           "ip:port[,ip:port...]" -> node.active
#   HS_CFG_ARCHIVE_ENABLE         true|false                     (default true)
#   HS_CFG_ARCHIVE_IDENTITY_INIT  true|false                     (default true)
#   HS_CFG_ARCHIVE_DB_DIR         relative name or ABSOLUTE path (default archive)
#   HS_CFG_ARCHIVE_DEBUG          true|false                     (default true)
#   HS_CFG_ARCHIVE_FULL_SCRUB     true|false                     (default false)
#   HS_CFG_SOFT_MIN_FREE_BYTES    default 33554432 (32 MiB; production 5 GiB)
#   HS_CFG_HARD_MIN_FREE_BYTES    default 16777216 (16 MiB; production 1 GiB)
#   HS_CFG_QUERY_WORKERS          default 2
#   HS_CFG_MAX_CONCURRENT_QUERIES default 8
#   HS_CFG_P2P_VERSION            default 20260728
hs_new_node() {
  local name="$1" slot="${2:-0}"
  [ -n "$HS_RUN_DIR" ] || hs_die "hs_new_node before hs_init"
  local node_dir="$HS_RUN_DIR/$name"
  if [ -e "$node_dir" ]; then
    hs_die "node dir already exists: $node_dir"
  fi
  mkdir -p "$node_dir/data" "$node_dir/logs"
  hs_assign_ports "$node_dir" "$slot"
  hs_write_node_config "$node_dir"
  printf '%s\n' "$node_dir"
}

# hs_write_node_config <node_dir> -- (re)generate node.conf from HS_CFG_*.
# Safe to call again before a restart.
hs_write_node_config() {
  local node_dir="$1"
  hs_load_ports "$node_dir"

  local witness_count="${HS_CFG_WITNESS_COUNT:-1}"
  case "$witness_count" in
    ''|*[!0-9]*) hs_die "HS_CFG_WITNESS_COUNT must be an integer, got '$witness_count'" ;;
  esac
  [ "$witness_count" -ge 1 ] \
    || hs_die "HS_CFG_WITNESS_COUNT must be >= 1, got $witness_count"
  [ "$witness_count" -le "$HS_MAX_WITNESSES" ] \
    || hs_die "HS_CFG_WITNESS_COUNT=$witness_count exceeds MAX_ACTIVE_WITNESS_NUM=$HS_MAX_WITNESSES (Parameter.java:66); DposService.updateWitness() would silently truncate the set"

  local witness_key="${HS_CFG_WITNESS_KEY:-$HS_KEY_WITNESS1}"
  local genesis_witnesses="${HS_CFG_GENESIS_WITNESSES:-$HS_ADDR_WITNESS1:100}"
  # Single line, two-space indent -- the historical shape of the localwitness
  # body. Multi-SR replaces it below; count==1 must stay byte-identical.
  local witness_key_block="  $witness_key"
  local active_peers="${HS_CFG_ACTIVE_PEERS:-}"
  local archive_enable="${HS_CFG_ARCHIVE_ENABLE:-true}"
  local identity_init="${HS_CFG_ARCHIVE_IDENTITY_INIT:-true}"
  local archive_db_dir="${HS_CFG_ARCHIVE_DB_DIR:-archive}"
  local archive_debug="${HS_CFG_ARCHIVE_DEBUG:-true}"
  # StorageConfig.java:222 rejects debug.enable while archive.enable is false.
  if [ "$archive_enable" != "true" ]; then
    archive_debug=false
    identity_init=false
  fi
  local full_scrub="${HS_CFG_ARCHIVE_FULL_SCRUB:-false}"
  local soft_free="${HS_CFG_SOFT_MIN_FREE_BYTES:-33554432}"
  local hard_free="${HS_CFG_HARD_MIN_FREE_BYTES:-16777216}"
  local query_workers="${HS_CFG_QUERY_WORKERS:-2}"
  local max_concurrent="${HS_CFG_MAX_CONCURRENT_QUERIES:-8}"
  local p2p_version="${HS_CFG_P2P_VERSION:-20260728}"

  # Governance flags. HS_CFG_COMMITTEE overrides the whole block; the default
  # activates the TVM proposal chain the contract oracles depend on.
  local committee_block="${HS_CFG_COMMITTEE:-$HS_DEFAULT_COMMITTEE}"
  local HS_COMMITTEE_BLOCK="$committee_block"

  # Multi-SR: derive the whole set and feed it to BOTH lists. The genesis list
  # is built in the same "addr:votes,addr:votes" form the loop below already
  # consumes, so there is exactly one place that renders a witness entry.
  if [ "$witness_count" -gt 1 ]; then
    if [ -n "${HS_CFG_WITNESS_KEY:-}" ] || [ -n "${HS_CFG_GENESIS_WITNESSES:-}" ]; then
      hs_die "HS_CFG_WITNESS_COUNT=$witness_count cannot be combined with an explicit HS_CFG_WITNESS_KEY / HS_CFG_GENESIS_WITNESSES -- pick one witness source"
    fi
    local w_i=1 w_priv w_addr
    genesis_witnesses=""
    witness_key_block=""
    while [ "$w_i" -le "$witness_count" ]; do
      w_priv="$(hs_witness_key_at "$w_i")"
      w_addr="$(hs_base58_of_priv "$w_priv")"
      if [ "$w_i" -eq 1 ]; then
        genesis_witnesses="$w_addr:$HS_WITNESS_VOTES"
        witness_key_block="  $w_priv"
      else
        genesis_witnesses="$genesis_witnesses,$w_addr:$HS_WITNESS_VOTES"
        witness_key_block="$witness_key_block,
  $w_priv"
      fi
      w_i=$((w_i + 1))
    done
    hs_log "multi-SR chain: $witness_count witnesses on one node (expect solid to trail head by $((witness_count - 1 - witness_count * 30 / 100)) blocks once every SR has produced)"
  fi

  local old_ifs="$IFS"
  local witness_block="" active_block="" entry addr votes

  IFS=','
  for entry in $genesis_witnesses; do
    addr="${entry%%:*}"
    votes="${entry##*:}"
    witness_block="$witness_block
    { address: $addr, url = \"http://$addr.local\", voteCount = $votes },"
  done
  IFS="$old_ifs"
  [ -n "$witness_block" ] || hs_die "empty genesis witness list"

  if [ -n "$active_peers" ]; then
    IFS=','
    for entry in $active_peers; do
      active_block="$active_block\"$entry\","
    done
    IFS="$old_ifs"
  fi

  cat >"$node_dir/node.conf" <<EOF
# Generated by docs/archiveV3/harness/lib.sh -- do not edit by hand.
# scenario=$HS_SCENARIO_NAME node=$(basename "$node_dir") generated=$(hs_ts)

net { }

storage {
  db.engine = "LEVELDB"
  db.directory = "database"

  # Archive block per
  # docs/archiveV3/20260714-archive-from0-production-validation-runbook.md.
  # Key names are whitelisted at
  # common/src/main/java/org/tron/core/config/args/StorageConfig.java:521 --
  # an unknown key aborts startup with IllegalArgumentException.
  archive {
    enable = $archive_enable
    db { directory = "$archive_db_dir", fullScrubOnStartup = $full_scrub }
    # true ONLY on the very first boot of an empty data directory; normal
    # restarts validate the existing ACTIVE anchor/root pair.
    identity { initialize = $identity_init }
    txnum { enable = true }
    temporal { enable = true }
    publisher {
      async = true
      backpressure = true
      # Lowered from the 5 GiB / 1 GiB production defaults so a small test
      # volume (scenario C) does not fail-stop on the very first preflight.
      softMinFreeBytes = $soft_free
      hardMinFreeBytes = $hard_free
    }
    query {
      jsonRpcWorkerThreads = $query_workers
      maxConcurrentQueries = $max_concurrent
      maxPendingQueries = 16
      maxOpenSnapshots = 8
      deadlineMs = 30000
    }
    debug { enable = $archive_debug }
  }
}

node.discovery = { enable = false, persist = false, external.ip = "127.0.0.1" }

node {
  listen.port = $HS_PORT_P2P
  # DPoS refuses to produce on a private chain unless this is 0.
  minParticipationRate = 0
  maxConnectionsWithSameIp = 10
  p2p { version = $p2p_version }
  active = [ $active_block ]
  passive = [ ]
  # MANDATORY isolation: reference.conf:342 defaults node.fastForward to two
  # PUBLIC mainnet addresses, and an unset value here makes every harness node
  # repeatedly dial the internet. Keep this empty.
  fastForward = [ ]
  http {
    fullNodeEnable = true
    fullNodePort = $HS_PORT_HTTP
    solidityEnable = false
    PBFTEnable = false
  }
  rpc {
    enable = true
    port = $HS_PORT_RPC
    solidityEnable = false
    PBFTEnable = false
    # MANDATORY for an isolated node: the default of 1 makes
    # Wallet.broadcastTransaction return NO_CONNECTION (Wallet.java:532).
    minEffectiveConnection = 0
  }
  jsonrpc {
    httpFullNodeEnable = true
    httpFullNodePort = $HS_PORT_JSONRPC
    httpSolidityEnable = false
    httpPBFTEnable = false
  }
  metrics.prometheus { enable = true, port = $HS_PORT_PROM }
}

seed.node = { ip.list = [ ] }

genesis.block = {
  assets = [
    { accountName = "Zion",      accountType = "AssetIssue", address = "$HS_ADDR_ZION",      balance = "90000000000000000" },
    { accountName = "Sun",       accountType = "AssetIssue", address = "$HS_ADDR_SUN",       balance = "10000000000000000" },
    { accountName = "Blackhole", accountType = "AssetIssue", address = "$HS_ADDR_BLACKHOLE", balance = "-9223372036854775808" }
  ]
  witnesses = [$witness_block
  ]
  timestamp = "0"
  parentHash = "0x0000000000000000000000000000000000000000000000000000000000000000"
}

localwitness = [
$witness_key_block
]

block = {
  needSyncCheck = false
  maintenanceTimeInterval = 21600000
  proposalExpireTime = 259200000
}

vm = {
  supportConstant = true
  saveInternalTx = true
}

# Startup-time governance flags (reference.conf:905). On a private chain these
# replace on-chain proposals. Without the TVM set below the chain runs
# pre-Constantinople rules: a deploy still executes and returns the right
# runtime code, but the code is NOT persisted -- eth_getCode answers with 32
# zero bytes and every later call executes nothing, so no SSTORE ever lands.
# Verified A/B on this repo: the same thing happens with archive DISABLED, so
# it is a chain-configuration artifact and never an archive defect.
committee = {
$HS_COMMITTEE_BLOCK
}

event.subscribe = { enable = false }
EOF
  hs_log "wrote $node_dir/node.conf (archive=$archive_enable identity.initialize=$identity_init witnesses=$witness_count)"
}

# hs_config_set_identity_init <node_dir> <true|false>
hs_config_set_identity_init() {
  local node_dir="$1" value="$2" conf="$1/node.conf"
  [ -f "$conf" ] || hs_die "no node.conf in $node_dir"
  case "$value" in
    true|false) : ;;
    *) hs_die "hs_config_set_identity_init expects true|false, got '$value'" ;;
  esac
  sed -i.bak "s/identity { initialize = true }/identity { initialize = $value }/; \
              s/identity { initialize = false }/identity { initialize = $value }/" "$conf" \
    || hs_die "failed to rewrite identity.initialize in $conf"
  rm -f "$conf.bak"
  grep -q "identity { initialize = $value }" "$conf" \
    || hs_die "identity.initialize rewrite did not take effect in $conf"
}

# hs_archive_dir <node_dir> -- the on-disk archive database directory.
hs_archive_dir() {
  local node_dir="$1" dir="${HS_CFG_ARCHIVE_DB_DIR:-archive}"
  case "$dir" in
    /*) printf '%s\n' "$dir" ;;
    *) printf '%s/data/database/%s\n' "$node_dir" "$dir" ;;
  esac
}

# hs_archive_identity_file <node_dir> -- the JSON root identity written by
# ArchiveIdentityProtocol (state/layout/finalPath; finalPath is ABSOLUTE, so
# a data directory can never be moved to another path).
hs_archive_identity_file() {
  printf '%s/archive.identity\n' "$(hs_archive_dir "$1")"
}

# ===========================================================================
# Node lifecycle
# ===========================================================================

# hs_node_start <node_dir> [extra jvm args...]
#
# Records:
#   <node_dir>/node.pid    the real java PID (the kill -9 target)
#   <node_dir>/node.exit   the wait() status, written when the JVM dies
#   <node_dir>/stdout.log, <node_dir>/stderr.log
#   <node_dir>/logs/tron.log   (the node writes this relative to its CWD)
#
# HS_JVM_OPTS  overrides the heap flags (default: -Xms1g -Xmx2g)
# HS_JDWP_PORT if set, attaches a JDWP agent (scenario B durability windows)
# HS_JDWP_SUSPEND  y|n (default n); use y to break inside genesis
hs_node_start() {
  local node_dir="$1"
  shift || true
  [ -f "$node_dir/node.conf" ] || hs_die "no node.conf in $node_dir"
  if hs_node_alive "$node_dir"; then
    hs_die "node already running: $node_dir (pid $(cat "$node_dir/node.pid"))"
  fi
  rm -f "$node_dir/node.pid" "$node_dir/node.exit"

  local jvm_opts="${HS_JVM_OPTS:--Xms1g -Xmx2g}"
  local jdwp=""
  if [ -n "${HS_JDWP_PORT:-}" ]; then
    jdwp="-agentlib:jdwp=transport=dt_socket,server=y,suspend=${HS_JDWP_SUSPEND:-n},address=127.0.0.1:${HS_JDWP_PORT}"
    hs_log "JDWP on 127.0.0.1:${HS_JDWP_PORT} (suspend=${HS_JDWP_SUSPEND:-n})"
  fi

  # The wrapper subshell owns the JVM so its exit status lands in a FILE.
  # (`wait` only works in the exact shell that forked the child, which breaks
  # as soon as a helper is used inside command substitution.)
  #
  # The `>/dev/null 2>&1` on the wrapper is LOAD-BEARING, not tidiness: a
  # background job inherits the caller's stdout, so if a scenario ever calls a
  # start/restart helper inside `$( )` the command substitution would block
  # forever waiting for the node to close that pipe. The JVM's own streams are
  # already redirected to files inside the subshell.
  (
    cd "$node_dir" || exit 127
    # shellcheck disable=SC2086
    java $jvm_opts $jdwp "$@" -jar "$HS_JAR" \
      -c node.conf -d data --witness \
      >stdout.log 2>stderr.log &
    java_pid=$!
    echo "$java_pid" >node.pid
    rc=0
    wait "$java_pid" || rc=$?
    echo "$rc" >node.exit
  ) >/dev/null 2>&1 &

  local waited=0
  while [ ! -s "$node_dir/node.pid" ]; do
    sleep 0.2
    waited=$((waited + 1))
    [ "$waited" -lt 100 ] || hs_die "node.pid never appeared for $node_dir"
  done
  case " $HS_STARTED_NODES " in
    *" $node_dir "*) : ;;
    *) HS_STARTED_NODES="$HS_STARTED_NODES $node_dir" ;;
  esac
  hs_log "started $(basename "$node_dir") pid=$(cat "$node_dir/node.pid") http=$(hs_http_url "$node_dir")"
}

hs_node_pid() {
  local node_dir="$1"
  [ -s "$node_dir/node.pid" ] || hs_die "no node.pid in $node_dir"
  cat "$node_dir/node.pid"
}

# hs_node_alive <node_dir> -- 0 when the JVM process still exists.
hs_node_alive() {
  local node_dir="$1"
  [ -s "$node_dir/node.pid" ] || return 1
  kill -0 "$(cat "$node_dir/node.pid")" 2>/dev/null
}

# hs_node_exit_code <node_dir> -- the exit status, or "-" while still running.
# 128+N when the JVM died from signal N (SIGKILL -> 137).
hs_node_exit_code() {
  local node_dir="$1"
  if [ -s "$node_dir/node.exit" ]; then
    tr -d '[:space:]' <"$node_dir/node.exit"
    printf '\n'
    return 0
  fi
  printf '%s\n' '-'
}

# hs_node_wait_exit <node_dir> [timeout_s] -- block until node.exit exists.
#
# Echoes the exit code and returns 0 on success; echoes "-" and returns 1 when the status
# could not be established. It deliberately does NOT hs_die: this runs on the teardown
# path, and aborting the whole script with exit 2 from inside cleanup would replace a real
# product verdict with a harness error.
#
# node.exit is written by the start wrapper after wait(); if the wrapper itself was killed
# (run-all.sh terminates a timed-out scenario by process GROUP) the file never appears even
# though the JVM is long gone. Once the process is no longer alive there is nothing further
# to wait for, so the loop stops rather than burning the full timeout.
hs_node_wait_exit() {
  local node_dir="$1" timeout="${2:-120}"
  local deadline=$(( $(date +%s) + timeout ))
  while [ ! -s "$node_dir/node.exit" ]; do
    if ! hs_node_alive "$node_dir"; then
      sleep 0.5
      if [ -s "$node_dir/node.exit" ]; then
        break
      fi
      hs_log "$(basename "$node_dir") is gone but wrote no node.exit (wrapper killed?)"
      printf '%s\n' '-'
      return 1
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      hs_log "$(basename "$node_dir") did not exit within ${timeout}s"
      printf '%s\n' '-'
      return 1
    fi
    sleep 0.5
  done
  hs_node_exit_code "$node_dir"
}

# hs_node_http_ready <node_dir> -- 0 when the HTTP API answers.
hs_node_http_ready() {
  local url
  url="$(hs_http_url "$1")"
  curl -s -f --max-time 3 -X POST "$url/wallet/getnowblock" >/dev/null 2>&1
}

# hs_node_await_startup <node_dir> [timeout_s]
#
# THE three-way verdict the KILL_MATRIX scenario needs. Echoes exactly one of:
#
#   READY        HTTP answered -- the node came up
#   EXIT:<code>  the JVM terminated -- fail-stop
#                (1 = TronError ARCHIVE_RUNTIME, 70 = archive fatal watchdog)
#   HUNG         the process is alive but HTTP never became ready
#
# HUNG is a real, observed product state, not harness flake: an
# ArchiveException thrown from Manager.initInternal is a plain
# RuntimeException, so ExitManager.findTronError returns empty, no System.exit
# runs, and the non-daemon Prometheus HTTPServer keeps the JVM alive forever.
# NEVER judge fail-stop by exit code alone.
hs_node_await_startup() {
  local node_dir="$1" timeout="${2:-180}"
  local deadline=$(( $(date +%s) + timeout ))
  while :; do
    if [ -s "$node_dir/node.exit" ]; then
      printf 'EXIT:%s\n' "$(hs_node_exit_code "$node_dir")"
      return 0
    fi
    if hs_node_http_ready "$node_dir"; then
      printf 'READY\n'
      return 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      if hs_node_alive "$node_dir"; then
        printf 'HUNG\n'
      else
        sleep 1
        printf 'EXIT:%s\n' "$(hs_node_exit_code "$node_dir")"
      fi
      return 0
    fi
    sleep 1
  done
}

# hs_node_wait_ready <node_dir> [timeout_s] -- abort unless the verdict is READY.
hs_node_wait_ready() {
  local node_dir="$1" timeout="${2:-180}" verdict
  verdict="$(hs_node_await_startup "$node_dir" "$timeout")"
  if [ "$verdict" != "READY" ]; then
    hs_abort "node $(basename "$node_dir") did not become ready: $verdict -- $(hs_node_exit_reason "$node_dir")"
  fi
  hs_log "$(basename "$node_dir") ready (head=$(hs_head_num "$node_dir"))"
}

# hs_node_stop <node_dir> [timeout_s]
#
# Clean stop: SIGTERM then wait. Returns non-zero WITHOUT killing when the JVM
# does not exit, because escalating would mask a shutdown brick. Use
# hs_node_stop_force for teardown where you do not care.
hs_node_stop() {
  local node_dir="$1" timeout="${2:-90}" pid
  if ! hs_node_alive "$node_dir"; then
    return 0
  fi
  pid="$(hs_node_pid "$node_dir")"
  hs_log "SIGTERM $(basename "$node_dir") pid=$pid"
  kill -TERM "$pid" 2>/dev/null || true
  local deadline=$(( $(date +%s) + timeout ))
  while hs_node_alive "$node_dir"; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      hs_log "$(basename "$node_dir") ignored SIGTERM for ${timeout}s"
      return 1
    fi
    sleep 0.5
  done
  hs_node_wait_exit "$node_dir" 30 >/dev/null || true
  hs_log "$(basename "$node_dir") stopped, exit=$(hs_node_exit_code "$node_dir")"
  return 0
}

# hs_node_stop_force <node_dir> [timeout_s] -- teardown only.
hs_node_stop_force() {
  local node_dir="$1"
  if hs_node_stop "$node_dir" "${2:-45}"; then
    return 0
  fi
  hs_log "escalating to SIGKILL for $(basename "$node_dir")"
  hs_node_kill9 "$node_dir"
}

# hs_node_kill9 <node_dir> -- the durability-window primitive (scenario B).
hs_node_kill9() {
  local node_dir="$1" pid
  if ! hs_node_alive "$node_dir"; then
    hs_log "kill9: $(basename "$node_dir") already dead"
    return 0
  fi
  pid="$(hs_node_pid "$node_dir")"
  hs_log "SIGKILL $(basename "$node_dir") pid=$pid"
  kill -9 "$pid" 2>/dev/null || true
  hs_node_wait_exit "$node_dir" 30 >/dev/null || true
}

# hs_node_suspend / hs_node_resume -- SIGSTOP/SIGCONT. Freezes block production
# without touching DB or socket state (used to deepen one fork branch).
hs_node_suspend() {
  kill -STOP "$(hs_node_pid "$1")" 2>/dev/null || hs_die "cannot SIGSTOP $1"
  hs_log "SIGSTOP $(basename "$1")"
}

hs_node_resume() {
  kill -CONT "$(hs_node_pid "$1")" 2>/dev/null || hs_die "cannot SIGCONT $1"
  hs_log "SIGCONT $(basename "$1")"
}

# hs_stop_all_nodes -- tear down EVERY node this run created.
#
# HS_STARTED_NODES alone is not enough: a scenario that calls a start helper inside
# `$( )` runs it in a SUBSHELL, so the variable append is lost in the parent and that
# node would survive the run holding its ports. The run directory is therefore also
# swept for node.pid files, which are written to disk by the start wrapper and so
# survive any subshell.
hs_stop_all_nodes() {
  local node seen=""
  for node in $HS_STARTED_NODES; do
    [ -d "$node" ] || continue
    seen="$seen $node"
    # The subshell is a guard, not style: anything in the teardown chain that calls hs_die
    # would otherwise exit(2) the whole script from inside cleanup and replace a real
    # product verdict with a harness error. Signals still land -- kill(2) does not care
    # which shell sent it.
    ( hs_node_stop_force "$node" ) >/dev/null 2>&1 || true
  done
  [ -n "$HS_RUN_DIR" ] && [ -d "$HS_RUN_DIR" ] || return 0
  local pidfile
  for pidfile in "$HS_RUN_DIR"/*/node.pid; do
    [ -f "$pidfile" ] || continue
    node="$(dirname "$pidfile")"
    case " $seen " in
      *" $node "*) continue ;;
    esac
    seen="$seen $node"
    ( hs_node_stop_force "$node" ) >/dev/null 2>&1 || true
  done
  return 0
}

# hs_node_restart <node_dir> [timeout_s]
#
# The canonical restart used by every recovery assertion: flips
# identity.initialize to false (auto-claim is off by design on normal starts)
# and echoes the three-way startup verdict.
hs_node_restart() {
  local node_dir="$1" timeout="${2:-180}"
  if hs_node_alive "$node_dir"; then
    hs_die "hs_node_restart called while the node is still alive"
  fi
  hs_config_set_identity_init "$node_dir" false
  hs_node_start "$node_dir"
  hs_node_await_startup "$node_dir" "$timeout"
}

# ===========================================================================
# Log and exit forensics
# ===========================================================================

hs_node_logs() {
  local node_dir="$1" f
  for f in "$node_dir/logs/tron.log" "$node_dir/stdout.log" "$node_dir/stderr.log"; do
    if [ -f "$f" ]; then
      printf '%s\n' "$f"
    fi
  done
  return 0
}

# hs_log_has <node_dir> <extended-regex>
hs_log_has() {
  local node_dir="$1" pattern="$2" f
  for f in $(hs_node_logs "$node_dir"); do
    if grep -Eq -- "$pattern" "$f" 2>/dev/null; then
      return 0
    fi
  done
  return 1
}

# hs_log_count <node_dir> <extended-regex> -- total matches across all logs.
hs_log_count() {
  local node_dir="$1" pattern="$2" f total=0 n
  for f in $(hs_node_logs "$node_dir"); do
    n="$(grep -Ec -- "$pattern" "$f" 2>/dev/null || true)"
    case "$n" in
      ''|*[!0-9]*) n=0 ;;
    esac
    total=$((total + n))
  done
  printf '%s\n' "$total"
}

# hs_node_exit_reason <node_dir> -- best-effort one-line cause of death.
hs_node_exit_reason() {
  local node_dir="$1" line=""
  line="$(grep -hE 'Shutting down with code:' "$node_dir/logs/tron.log" 2>/dev/null | tail -1 || true)"
  if [ -z "$line" ]; then
    line="$(grep -hE 'archive fatal watchdog timeout' "$node_dir/stderr.log" 2>/dev/null | tail -1 || true)"
  fi
  if [ -z "$line" ]; then
    line="$(grep -hE 'ArchiveException|TronError' "$node_dir/logs/tron.log" 2>/dev/null | tail -1 || true)"
  fi
  [ -n "$line" ] || line="(no exit reason found in the logs)"
  printf '%s\n' "$line"
}

# hs_node_has_archive_failstop <node_dir> -- ExitManager.java:49 breadcrumb.
#
# ARCHIVE_RUNTIME is the general archive fail-stop code, but an archive-caused refusal may carry a
# more specific pre-existing TronError classification. The genesis fence is the known case: a
# canonical genesis without a matching COMMITTED marker exits as GENESIS_BLOCK_INIT (see
# Manager.archiveGenesisCanonicalStateError). Grading only ARCHIVE_RUNTIME scores that correct,
# well-classified fail-stop as a harness ERROR, so accept either code and require the reason text
# to name the archive.
hs_node_has_archive_failstop() {
  hs_log_has "$1" 'Shutting down with code: ARCHIVE_RUNTIME' && return 0
  hs_log_has "$1" 'Shutting down with code: GENESIS_BLOCK_INIT' \
    && hs_log_has "$1" 'archive'
}

# hs_node_has_watchdog_halt <node_dir> -- ArchiveFatalController.java:224
# breadcrumb (stderr only; it never reaches tron.log).
hs_node_has_watchdog_halt() {
  grep -Eq 'archive fatal watchdog timeout; halting with exit status 70' \
    "$1/stderr.log" 2>/dev/null
}

# hs_assert_fail_stop <node_dir> <what...>
#
# Assert the node stopped the DESIGNED way instead of silently accepting a
# half state: process gone, exit 1 (TronError ARCHIVE_RUNTIME) or exit 70
# (watchdog halt), with the matching breadcrumb. Still-alive (HUNG) and exit 0
# are both failures.
hs_assert_fail_stop() {
  local node_dir="$1"
  shift
  local code
  code="$(hs_node_exit_code "$node_dir")"
  if [ "$code" = "-" ]; then
    hs_fail "$*: expected fail-stop but the process is still alive (HUNG) -- $(hs_node_exit_reason "$node_dir")"
    return 0
  fi
  case "$code" in
    1)
      if hs_node_has_archive_failstop "$node_dir"; then
        hs_pass "$*: fail-stop exit 1 with the ARCHIVE_RUNTIME breadcrumb"
      else
        hs_fail "$*: exit 1 without a 'Shutting down with code: ARCHIVE_RUNTIME' breadcrumb -- $(hs_node_exit_reason "$node_dir")"
      fi
      ;;
    70)
      if hs_node_has_watchdog_halt "$node_dir"; then
        hs_pass "$*: fail-stop exit 70 with the archive fatal watchdog breadcrumb"
      else
        hs_fail "$*: exit 70 without the watchdog breadcrumb on stderr"
      fi
      ;;
    *)
      hs_fail "$*: expected fail-stop exit 1 or 70, got exit $code -- $(hs_node_exit_reason "$node_dir")"
      ;;
  esac
  return 0
}

# hs_assert_clean_stop <node_dir> <what...>
#
# A normal SIGTERM shutdown is OBSERVED as wait() status 143 (128 + SIGTERM):
# java-tron runs its shutdown hooks and the JVM then terminates from the
# signal rather than calling System.exit(0). Exit 0 also counts (an explicit
# clean exit). Anything else -- especially 1 or 70 -- is a fail-stop, not a
# clean stop.
hs_assert_clean_stop() {
  local node_dir="$1"
  shift
  local code
  code="$(hs_node_exit_code "$node_dir")"
  case "$code" in
    0|143) hs_pass "$*: clean stop (exit $code)" ;;
    -) hs_fail "$*: expected a clean stop but the process is still alive" ;;
    *) hs_fail "$*: expected a clean stop (0 or 143), got exit $code -- $(hs_node_exit_reason "$node_dir")" ;;
  esac
  return 0
}

# hs_assert_startup_verdict <node_dir> <expected: READY|EXIT:n|HUNG> <actual> <what...>
hs_assert_startup_verdict() {
  local node_dir="$1" expected="$2" actual="$3"
  shift 3
  if [ "$expected" = "$actual" ]; then
    hs_pass "$* (startup verdict $actual)"
  else
    hs_fail "$*: expected startup verdict [$expected], got [$actual] -- $(hs_node_exit_reason "$node_dir")"
  fi
}

# ===========================================================================
# Chain observation
# ===========================================================================

hs_curl_json() {
  local out
  out="$(curl -s --max-time "${HS_HTTP_TIMEOUT:-15}" "$@")" || return 1
  [ -n "$out" ] || return 1
  printf '%s\n' "$out"
}

# hs_head_num <node_dir> -- head block number, or -1 when unavailable.
hs_head_num() {
  local node_dir="$1" url body num
  url="$(hs_http_url "$node_dir")"
  body="$(hs_curl_json -X POST "$url/wallet/getnowblock")" || { printf '%s\n' '-1'; return 0; }
  # protobuf JSON omits `number` for block 0, hence the `// 0`.
  num="$(printf '%s' "$body" | jq -r '.block_header.raw_data.number // 0' 2>/dev/null || true)"
  case "$num" in
    ''|*[!0-9]*) num='-1' ;;
  esac
  printf '%s\n' "$num"
}

# hs_solid_num <node_dir> -- solidified block number, or -1.
# Parsed from getnodeinfo's "Num:<n>,ID:<hash>" string.
hs_solid_num() {
  local node_dir="$1" url body raw num
  url="$(hs_http_url "$node_dir")"
  body="$(hs_curl_json "$url/wallet/getnodeinfo")" || { printf '%s\n' '-1'; return 0; }
  raw="$(printf '%s' "$body" | jq -r '.solidityBlock // ""' 2>/dev/null || true)"
  case "$raw" in
    Num:*)
      num="$(printf '%s' "$raw" | sed -e 's/^Num:\([0-9][0-9]*\).*/\1/')"
      case "$num" in
        ''|*[!0-9]*) num='-1' ;;
      esac
      ;;
    *) num='-1' ;;
  esac
  printf '%s\n' "$num"
}

# hs_peer_count <node_dir>
hs_peer_count() {
  local node_dir="$1" url body n
  url="$(hs_http_url "$node_dir")"
  body="$(hs_curl_json "$url/wallet/getnodeinfo")" || { printf '%s\n' '-1'; return 0; }
  n="$(printf '%s' "$body" | jq -r '(.peerList // []) | length' 2>/dev/null || true)"
  case "$n" in
    ''|*[!0-9]*) n='-1' ;;
  esac
  printf '%s\n' "$n"
}

# hs_block_id_at <node_dir> <num> -- canonical blockID, "" when absent.
hs_block_id_at() {
  local node_dir="$1" num="$2" url body
  url="$(hs_http_url "$node_dir")"
  body="$(hs_curl_json -X POST -H 'Content-Type: application/json' \
    --data "{\"num\":$num}" "$url/wallet/getblockbynum")" || { printf '\n'; return 0; }
  printf '%s\n' "$(printf '%s' "$body" | jq -r '.blockID // ""' 2>/dev/null || true)"
}

# hs_wait_height <node_dir> <target> [timeout_s] -- echoes the reached height.
hs_wait_height() {
  local node_dir="$1" target="$2" timeout="${3:-180}" head
  local deadline=$(( $(date +%s) + timeout ))
  while :; do
    head="$(hs_head_num "$node_dir")"
    if [ "$head" -ge "$target" ] 2>/dev/null && [ "$head" != "-1" ]; then
      hs_log "$(basename "$node_dir") head=$head (>= $target)"
      printf '%s\n' "$head"
      return 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      hs_abort "$(basename "$node_dir") did not reach height $target within ${timeout}s (head=$head) -- $(hs_node_exit_reason "$node_dir")"
    fi
    sleep 1
  done
}

# hs_wait_blocks <node_dir> <n> [timeout_s] -- wait for n further blocks.
hs_wait_blocks() {
  local node_dir="$1" n="$2" timeout="${3:-180}" head
  head="$(hs_head_num "$node_dir")"
  [ "$head" != "-1" ] || hs_abort "$(basename "$node_dir") head unavailable"
  hs_wait_height "$node_dir" "$((head + n))" "$timeout"
}

# hs_wait_solidified <node_dir> <target> [timeout_s]
hs_wait_solidified() {
  local node_dir="$1" target="$2" timeout="${3:-240}" solid
  local deadline=$(( $(date +%s) + timeout ))
  while :; do
    solid="$(hs_solid_num "$node_dir")"
    if [ "$solid" -ge "$target" ] 2>/dev/null && [ "$solid" != "-1" ]; then
      hs_log "$(basename "$node_dir") solid=$solid (>= $target)"
      printf '%s\n' "$solid"
      return 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      hs_abort "$(basename "$node_dir") did not solidify block $target within ${timeout}s (solid=$solid)"
    fi
    sleep 1
  done
}

# hs_wait_archive_drained <node_dir> [timeout_s]
#
# Wait until nothing is in flight: tron:archive_state{type="oldest_inflight_block"}
# is -1 exactly when inFlightBlocks is empty (DefaultArchiveService.java:1853).
hs_wait_archive_drained() {
  local node_dir="$1" timeout="${2:-240}" oldest lag
  local deadline=$(( $(date +%s) + timeout ))
  while :; do
    oldest="$(hs_metric_int "$node_dir" oldest_inflight_block)"
    lag="$(hs_metric_int "$node_dir" publisher_lag_blocks)"
    if [ "$oldest" = "-1" ]; then
      hs_log "$(basename "$node_dir") archive drained (publisher_lag=$lag)"
      return 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      hs_abort "$(basename "$node_dir") archive did not drain within ${timeout}s (oldest_inflight=$oldest lag=$lag)"
    fi
    sleep 1
  done
}

# hs_wait_hist_available <node_dir> <addr0x> <blockDec> [timeout_s]
#
# The reliable "is this height queryable yet" gate: probe the real read path
# and wait until it stops answering -32000 "archive history unavailable".
# Metric arithmetic is a poor substitute -- the adapter's own range check is
# the authority (DefaultArchiveService.java:3236 requireBlockRange).
hs_wait_hist_available() {
  local node_dir="$1" addr="$2" block="$3" timeout="${4:-240}" body msg
  local deadline=$(( $(date +%s) + timeout ))
  while :; do
    body="$(hs_jsonrpc "$node_dir" eth_getBalance \
      "[\"$addr\",\"$(hs_dec_to_hexblock "$block")\"]")"
    if [ "$(printf '%s' "$body" | jq -r 'has("error")')" != "true" ]; then
      hs_log "$(basename "$node_dir") archive height $block is queryable"
      return 0
    fi
    msg="$(printf '%s' "$body" | jq -r '.error.message // ""')"
    case "$msg" in
      *"archive history unavailable"*) : ;;
      *)
        hs_abort "unexpected archive error while waiting for block $block: $(printf '%s' "$body" | jq -c '.error')"
        ;;
    esac
    if [ "$(date +%s)" -ge "$deadline" ]; then
      hs_abort "archive height $block never became queryable within ${timeout}s ($msg)"
    fi
    sleep 1
  done
}

# ===========================================================================
# Metrics
# ===========================================================================

hs_metrics_raw() {
  local url
  url="$(hs_metrics_url "$1")"
  curl -s --max-time "${HS_HTTP_TIMEOUT:-15}" "$url" 2>/dev/null || true
}

# hs_metric_state <node_dir> <type> -- tron:archive_state{type="<type>",}.
# Echoes "" when the series is absent; callers MUST distinguish "" from 0.
hs_metric_state() {
  local node_dir="$1" type="$2" line
  line="$(hs_metrics_raw "$node_dir" | { grep -F "tron:archive_state{type=\"$type\"," || true; } | tail -1)"
  [ -n "$line" ] || { printf '\n'; return 0; }
  printf '%s\n' "$line" | awk '{print $NF}'
}

# hs_metric_work <node_dir> <type> -- tron:archive_work_total{type="<type>",}.
hs_metric_work() {
  local node_dir="$1" type="$2" line
  line="$(hs_metrics_raw "$node_dir" | { grep -F "tron:archive_work_total{type=\"$type\"," || true; } | tail -1)"
  [ -n "$line" ] || { printf '\n'; return 0; }
  printf '%s\n' "$line" | awk '{print $NF}'
}

# hs_metric_fork_total <node_dir> [label] -- tron:block_fork_total{type="..."}.
# Labels seen live: "all" (every fork) and "fail".
hs_metric_fork_total() {
  local node_dir="$1" label="${2:-all}" line
  line="$(hs_metrics_raw "$node_dir" | { grep -F "tron:block_fork_total{type=\"$label\"," || true; } | tail -1)"
  [ -n "$line" ] || { printf '\n'; return 0; }
  printf '%s\n' "$line" | awk '{print $NF}'
}

# hs_metric_int <node_dir> <state-type> -- integer form; "" stays "".
hs_metric_int() {
  local value
  value="$(hs_metric_state "$1" "$2")"
  [ -n "$value" ] || { printf '\n'; return 0; }
  printf '%s\n' "${value%%.*}"
}

# hs_metric_work_int <node_dir> <work-type>
#
# Counters are only EXPORTED once they have been incremented at least once, so
# an absent tron:archive_work_total series means "never happened" == 0. That is
# different from the gauge case (hs_metric_int), where an absent series means
# "not reported" and must stay empty. Echoes "" only when the whole metrics
# endpoint is unreachable.
hs_metric_work_int() {
  local value raw
  value="$(hs_metric_work "$1" "$2")"
  if [ -n "$value" ]; then
    printf '%s\n' "${value%%.*}"
    return 0
  fi
  raw="$(hs_metrics_raw "$1")"
  if [ -z "$raw" ]; then
    printf '\n'
    return 0
  fi
  printf '0\n'
}

# hs_assert_repair_not_required <node_dir> <what...>
#
# repair-required is a RocksDB META key (ArchiveBlockRangeCodec.java:48), NOT
# a file. While the node is live this gauge is the only observation point.
hs_assert_repair_not_required() {
  local node_dir="$1"
  shift
  local value
  value="$(hs_metric_int "$node_dir" repair_required)"
  if [ -z "$value" ]; then
    hs_fail "$*: tron:archive_state{type=\"repair_required\"} is not exported (metrics down?)"
    return 0
  fi
  hs_assert_eq "0" "$value" "$*: repair_required"
}

hs_assert_repair_required() {
  local node_dir="$1"
  shift
  local value
  value="$(hs_metric_int "$node_dir" repair_required)"
  if [ -z "$value" ]; then
    hs_fail "$*: the repair_required gauge is unavailable"
    return 0
  fi
  hs_assert_eq "1" "$value" "$*: repair_required"
}

# hs_metrics_summary <node_dir> -- human-readable archive snapshot on stderr.
hs_metrics_summary() {
  local node_dir="$1" type
  {
    printf 'archive state (%s):\n' "$(basename "$node_dir")"
    for type in repair_required publisher_lag_blocks oldest_inflight_block \
      inflight_blocks inflight_records inflight_bytes inflight_resource_bytes \
      disk_free_bytes active_snapshots active_queries pending_queries; do
      printf '  %-24s %s\n' "$type" "$(hs_metric_state "$node_dir" "$type")"
    done
    for type in captured_blocks journal_blocks published_blocks publish_failures \
      raw_records merged_records previous_value_read_failures; do
      printf '  %-24s %s\n' "$type" "$(hs_metric_work "$node_dir" "$type")"
    done
  } >&2
}

# ===========================================================================
# Transactions
#
# There is NO /wallet/gettransactionsign servlet: signing is client side, via
# java/Sign.java. The txID returned by /wallet/createtransaction IS the raw
# hash that must be signed.
# ===========================================================================

# hs_sign <privHex> <txIdHex> -- 65-byte signature hex.
hs_sign() {
  local priv="$1" txid="$2" sig
  sig="$(java -cp "$HS_CLASSES:$HS_JAR" Sign "$priv" "$txid" 2>&1)" \
    || hs_die "Sign helper failed for txID $txid: $sig"
  printf '%s\n' "$sig"
}

# hs_broadcast <node_dir> <privHex> <unsigned-tx-json> -- sign and broadcast.
# Echoes the txID; aborts when the node rejects the transaction.
hs_broadcast() {
  local node_dir="$1" priv="$2" tx="$3"
  local url txid sig signed result
  url="$(hs_http_url "$node_dir")"

  txid="$(printf '%s' "$tx" | jq -r '.txID // empty' 2>/dev/null || true)"
  [ -n "$txid" ] \
    || hs_abort "unsigned transaction has no txID: $(printf '%s' "$tx" | head -c 400)"

  sig="$(hs_sign "$priv" "$txid")"
  signed="$(printf '%s' "$tx" | jq -c --arg s "$sig" '.signature = [$s]')" \
    || hs_die "failed to attach the signature to $txid"

  result="$(hs_curl_json -X POST -H 'Content-Type: application/json' \
    --data "$signed" "$url/wallet/broadcasttransaction")" \
    || hs_abort "broadcast HTTP call failed for $txid"

  if [ "$(printf '%s' "$result" | jq -r '.result // false' 2>/dev/null || true)" != "true" ]; then
    hs_abort "broadcast rejected for $txid: $result"
  fi
  printf '%s\n' "$txid"
}

# hs_tx_transfer <node_dir> <fromPrivHex> <toBase58> <amountSun>
# The sender address is derived from the key, so it can never disagree with
# the signature. Echoes the txID.
hs_tx_transfer() {
  local node_dir="$1" priv="$2" to="$3" amount="$4"
  local url from tx
  url="$(hs_http_url "$node_dir")"
  from="$(hs_base58_of_priv "$priv")"
  tx="$(hs_curl_json -X POST -H 'Content-Type: application/json' \
    --data "{\"owner_address\":\"$from\",\"to_address\":\"$to\",\"amount\":$amount,\"visible\":true}" \
    "$url/wallet/createtransaction")" \
    || hs_abort "createtransaction failed ($from -> $to, $amount sun)"
  if [ "$(printf '%s' "$tx" | jq -r 'has("Error") or has("code")' 2>/dev/null || true)" = "true" ]; then
    hs_abort "createtransaction returned an error: $tx"
  fi
  hs_broadcast "$node_dir" "$priv" "$tx"
}

# hs_tx_wait_receipt <node_dir> <txid> [timeout_s]
# Echoes the /wallet/gettransactioninfobyid JSON once the tx is mined.
hs_tx_wait_receipt() {
  local node_dir="$1" txid="$2" timeout="${3:-90}"
  local url info deadline
  url="$(hs_http_url "$node_dir")"
  deadline=$(( $(date +%s) + timeout ))
  while :; do
    info="$(hs_curl_json -X POST -H 'Content-Type: application/json' \
      --data "{\"value\":\"$txid\"}" "$url/wallet/gettransactioninfobyid")" || info='{}'
    if [ "$(printf '%s' "$info" | jq -r 'has("id")' 2>/dev/null || true)" = "true" ]; then
      printf '%s\n' "$info"
      return 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      hs_abort "transaction $txid was not mined within ${timeout}s"
    fi
    sleep 1
  done
}

# hs_tx_block_num <receipt-json>
hs_tx_block_num() {
  printf '%s' "$1" | jq -r '.blockNumber // empty' 2>/dev/null || true
}

# hs_tx_assert_success <receipt-json> <what...>
# Contract transactions must carry receipt.result=SUCCESS; plain transfers
# carry no receipt.result at all, which is fine.
hs_tx_assert_success() {
  local info="$1"
  shift
  local result
  result="$(printf '%s' "$info" | jq -r '.receipt.result // "NONE"' 2>/dev/null || true)"
  case "$result" in
    SUCCESS|NONE) hs_pass "$* (receipt.result=$result)" ;;
    *) hs_fail "$*: receipt.result=$result $(printf '%s' "$info" | jq -c '.resMessage // ""' 2>/dev/null || true)" ;;
  esac
}

# ---------------------------------------------------------------------------
# IMPORTANT -- why the helpers below return through GLOBALS instead of stdout.
#
# Anything a scenario runs inside `$( ... )` executes in a SUBSHELL: hs_fail's
# counter increment is lost, and hs_abort's `exit 1` only kills the subshell.
# So every helper that performs an assertion (or can abort) must be called
# DIRECTLY, and publishes its outputs in these variables:
#
#   HS_LAST_TXID      the transaction id
#   HS_LAST_BLOCK     the block that included it
#   HS_LAST_CONTRACT  the deployed contract address (hex41)
#
# Pure getters (hs_head_num, hs_hist_balance, hs_metric_*, ...) perform no
# assertions and are safe inside command substitution.
# ---------------------------------------------------------------------------
HS_LAST_TXID=""
HS_LAST_BLOCK=""
HS_LAST_CONTRACT=""

# hs_tx_transfer_confirmed <node_dir> <privHex> <toBase58> <amountSun>
# Broadcast, wait for the receipt, assert success.
# Sets HS_LAST_TXID / HS_LAST_BLOCK. Call directly, never in `$( )`.
hs_tx_transfer_confirmed() {
  local node_dir="$1" priv="$2" to="$3" amount="$4" info
  HS_LAST_TXID=""
  HS_LAST_BLOCK=""
  HS_LAST_TXID="$(hs_tx_transfer "$node_dir" "$priv" "$to" "$amount")"
  info="$(hs_tx_wait_receipt "$node_dir" "$HS_LAST_TXID")"
  hs_tx_assert_success "$info" "transfer $amount sun -> $to"
  HS_LAST_BLOCK="$(hs_tx_block_num "$info")"
  [ -n "$HS_LAST_BLOCK" ] \
    || hs_abort "transfer $HS_LAST_TXID has no blockNumber in its receipt"
  hs_log "transfer $HS_LAST_TXID mined in block $HS_LAST_BLOCK"
}

# hs_contract_deploy <node_dir> <privHex> [feeLimitSun]
#
# Deploys HS_CONTRACT_DEPLOY_HEX from the key's own address.
# Sets HS_LAST_TXID / HS_LAST_CONTRACT / HS_LAST_BLOCK.
# Call directly, never in `$( )`.
hs_contract_deploy() {
  local node_dir="$1" priv="$2" fee="${3:-1000000000}"
  local url owner tx info
  HS_LAST_TXID=""
  HS_LAST_BLOCK=""
  HS_LAST_CONTRACT=""
  url="$(hs_http_url "$node_dir")"
  owner="$(hs_base58_of_priv "$priv")"
  tx="$(hs_curl_json -X POST -H 'Content-Type: application/json' --data "$(cat <<EOF
{"owner_address":"$owner","abi":"[]","bytecode":"$HS_CONTRACT_DEPLOY_HEX",
 "name":"ArchiveHarnessStorage","fee_limit":$fee,
 "consume_user_resource_percent":100,"origin_energy_limit":10000000,"visible":true}
EOF
)" "$url/wallet/deploycontract")" || hs_abort "deploycontract HTTP call failed"

  if [ "$(printf '%s' "$tx" | jq -r 'has("Error") or has("code")' 2>/dev/null || true)" = "true" ]; then
    hs_abort "deploycontract returned an error: $tx"
  fi
  HS_LAST_TXID="$(hs_broadcast "$node_dir" "$priv" "$tx")"
  info="$(hs_tx_wait_receipt "$node_dir" "$HS_LAST_TXID")"
  hs_tx_assert_success "$info" "contract deploy $HS_LAST_TXID"
  HS_LAST_CONTRACT="$(printf '%s' "$info" | jq -r '.contract_address // empty' 2>/dev/null || true)"
  [ -n "$HS_LAST_CONTRACT" ] \
    || hs_abort "deploy $HS_LAST_TXID produced no contract_address: $(printf '%s' "$info" | jq -c . 2>/dev/null || true)"
  HS_LAST_BLOCK="$(hs_tx_block_num "$info")"
  hs_log "deployed the harness storage contract at $HS_LAST_CONTRACT (block $HS_LAST_BLOCK)"
}

# hs_contract_set <node_dir> <privHex> <contractHex41> <valueHex64>
#
# Calls the harness contract with 32 raw calldata bytes, which its runtime
# SSTOREs into slot 0. Sets HS_LAST_TXID / HS_LAST_BLOCK.
# Call directly, never in `$( )`.
#
# Address form: hex41 everywhere with "visible" OMITTED. Never mix base58 and
# hex41 in one request, and never send an empty "function_selector" (the
# servlet answers with JsonFormat$ParseException).
hs_contract_set() {
  local node_dir="$1" priv="$2" contract="$3" value="$4"
  local url owner wrapper tx info fee="${HS_CONTRACT_FEE_LIMIT:-1000000000}"
  case "${#value}" in
    64) : ;;
    *) hs_die "hs_contract_set expects a 64-hex-char word, got '${value}'" ;;
  esac
  case "$contract" in
    41*) : ;;
    *) hs_die "hs_contract_set expects a hex41 contract address, got '$contract'" ;;
  esac
  HS_LAST_TXID=""
  HS_LAST_BLOCK=""
  url="$(hs_http_url "$node_dir")"
  owner="$(hs_hex41_of_priv "$priv")"
  wrapper="$(hs_curl_json -X POST -H 'Content-Type: application/json' --data "$(cat <<EOF
{"owner_address":"$owner","contract_address":"$contract","data":"$value",
 "fee_limit":$fee,"call_value":0}
EOF
)" "$url/wallet/triggersmartcontract")" || hs_abort "triggersmartcontract HTTP call failed"

  tx="$(printf '%s' "$wrapper" | jq -c '.transaction // empty' 2>/dev/null || true)"
  [ -n "$tx" ] || hs_abort "triggersmartcontract returned no .transaction: $wrapper"
  HS_LAST_TXID="$(hs_broadcast "$node_dir" "$priv" "$tx")"
  info="$(hs_tx_wait_receipt "$node_dir" "$HS_LAST_TXID")"
  hs_tx_assert_success "$info" "contract set slot0=0x${value} ($HS_LAST_TXID)"
  HS_LAST_BLOCK="$(hs_tx_block_num "$info")"
  [ -n "$HS_LAST_BLOCK" ] || hs_abort "contract call $HS_LAST_TXID has no blockNumber"
}

# hs_contract_get_live <node_dir> <contractHex41> -- eth_call at "latest",
# returning the 32-byte word the runtime reads out of slot 0.
hs_contract_get_live() {
  local node_dir="$1" contract="$2" to
  to="$(hs_eth_of_hex41 "$contract")"
  hs_jsonrpc_result "$node_dir" eth_call \
    "[{\"to\":\"$to\",\"data\":\"0x\"},\"latest\"]"
}

# ===========================================================================
# Historical (archive) queries -- JSON-RPC
#
# Routing gate: archive enabled AND the block tag is not "latest"
# (ArchiveJsonRpcStateAdapter.shouldUseArchive, :41).
# ===========================================================================

# hs_jsonrpc <node_dir> <method> <paramsJsonArray> -- the raw response body.
hs_jsonrpc() {
  local node_dir="$1" method="$2" params="$3" url body
  url="$(hs_jsonrpc_url "$node_dir")"
  body="$(curl -s --max-time "${HS_JSONRPC_TIMEOUT:-40}" -X POST \
    -H 'Content-Type: application/json' \
    --data "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$method\",\"params\":$params}" \
    "$url" 2>/dev/null)" || hs_abort "JSON-RPC transport failure: $method $params"
  [ -n "$body" ] || hs_abort "JSON-RPC empty response: $method $params"
  printf '%s\n' "$body"
}

# hs_jsonrpc_result <node_dir> <method> <params> -- .result, aborting on error.
hs_jsonrpc_result() {
  local body
  body="$(hs_jsonrpc "$@")"
  if [ "$(printf '%s' "$body" | jq -r 'has("error")' 2>/dev/null || true)" = "true" ]; then
    hs_abort "JSON-RPC $2 returned an error: $(printf '%s' "$body" | jq -c '.error' 2>/dev/null || true)"
  fi
  printf '%s' "$body" | jq -r '.result'
}

hs_jsonrpc_error_code() {
  local body
  body="$(hs_jsonrpc "$@")"
  printf '%s' "$body" | jq -r '.error.code // empty' 2>/dev/null || true
}

hs_jsonrpc_error_message() {
  local body
  body="$(hs_jsonrpc "$@")"
  printf '%s' "$body" | jq -r '.error.message // empty' 2>/dev/null || true
}

# hs_dec_to_hexblock <n> -- 12 -> 0xc, the block tag the adapter expects.
#
# NEVER use "finalized" as a historical selector: it resolves to
# wallet.getSolidBlockNum() (ArchiveJsonRpcStateAdapter.java:138), which on
# this chain is not yet published and fails closed with -32000.
hs_dec_to_hexblock() {
  printf '0x%x\n' "$1"
}

# hs_hist_balance <node_dir> <addr0x> <blockDec>
hs_hist_balance() {
  hs_jsonrpc_result "$1" eth_getBalance "[\"$2\",\"$(hs_dec_to_hexblock "$3")\"]"
}

# hs_hist_code <node_dir> <addr0x> <blockDec>
hs_hist_code() {
  hs_jsonrpc_result "$1" eth_getCode "[\"$2\",\"$(hs_dec_to_hexblock "$3")\"]"
}

# hs_hist_storage_at <node_dir> <addr0x> <slotHex> <blockDec>
hs_hist_storage_at() {
  hs_jsonrpc_result "$1" eth_getStorageAt "[\"$2\",\"$3\",\"$(hs_dec_to_hexblock "$4")\"]"
}

# hs_hist_call <node_dir> <callObjectJson> <blockDec>
hs_hist_call() {
  hs_jsonrpc_result "$1" eth_call "[$2,\"$(hs_dec_to_hexblock "$3")\"]"
}

# hs_hist_contract_get <node_dir> <contractHex41> <blockDec>
hs_hist_contract_get() {
  local to
  to="$(hs_eth_of_hex41 "$2")"
  hs_hist_call "$1" "{\"to\":\"$to\",\"data\":\"0x\"}" "$3"
}

# Live (canonical, tag "latest") counterparts -- the oracle baselines.
hs_live_balance() {
  hs_jsonrpc_result "$1" eth_getBalance "[\"$2\",\"latest\"]"
}

hs_live_code() {
  hs_jsonrpc_result "$1" eth_getCode "[\"$2\",\"latest\"]"
}

hs_live_storage_at() {
  hs_jsonrpc_result "$1" eth_getStorageAt "[\"$2\",\"$3\",\"latest\"]"
}

hs_live_block_number() {
  hs_jsonrpc_result "$1" eth_blockNumber '[]'
}

# hs_assert_hist_eq <node_dir> <expected> <actual> <what...>
hs_assert_hist_eq() {
  local node_dir="$1" expected="$2" actual="$3"
  shift 3
  hs_assert_eq "$expected" "$actual" "$*"
}

# hs_assert_hist_fails_closed <node_dir> <method> <params> <code> <msg_substr> <what...>
#
# The fail-closed contract. Verified shapes:
#   -32000  "archive history unavailable for block N"   (outside the published range)
#   -32000  "archive <domain> is unknown before mid-chain coverage"
#   -32000  "archive history hash mismatch for block N" (orphaned height)
#   -32005  historical worker / admission limit reached
#   -32601  debug_trace* when storage.archive.debug.enable = false
# Pass "" as <msg_substr> to check the code only.
hs_assert_hist_fails_closed() {
  local node_dir="$1" method="$2" params="$3" want_code="$4" want_msg="$5"
  shift 5
  local body code msg
  body="$(hs_jsonrpc "$node_dir" "$method" "$params")"
  if [ "$(printf '%s' "$body" | jq -r 'has("error")' 2>/dev/null || true)" != "true" ]; then
    hs_fail "$*: expected fail-closed $want_code, got a RESULT: $(printf '%s' "$body" | jq -c '.result' 2>/dev/null || true)"
    return 0
  fi
  code="$(printf '%s' "$body" | jq -r '.error.code' 2>/dev/null || true)"
  msg="$(printf '%s' "$body" | jq -r '.error.message // ""' 2>/dev/null || true)"
  if [ "$code" != "$want_code" ]; then
    hs_fail "$*: expected error code $want_code, got $code ($msg)"
    return 0
  fi
  if [ -n "$want_msg" ]; then
    case "$msg" in
      *"$want_msg"*) : ;;
      *) hs_fail "$*: error $code message [$msg] does not contain [$want_msg]"; return 0 ;;
    esac
  fi
  hs_pass "$*: fail-closed $code ($msg)"
}

# ---------------------------------------------------------------------------
# Oracles
#
# ORACLE DISCIPLINE (from the recon notes): record the LIVE answer while the
# head is at height H, then later assert the ARCHIVE answer at height H equals
# it. Never assert against a hard-coded literal -- eth_getCode in particular
# returns runtimecode, whose value on a bare private chain is a chain-config
# artifact rather than an archive property.
# ---------------------------------------------------------------------------

# hs_oracle_file -- the default oracle ledger for this run.
hs_oracle_file() {
  printf '%s/oracles.tsv\n' "$HS_RUN_DIR"
}

# hs_oracle_record <name> <kind> <arg1> <arg2> <block> <value>
#   kind: balance | code | storage
#   arg1: address (0x form); arg2: slot for storage, "" otherwise
#
# An unused arg2 is written as "-", never as an empty column: tab is an IFS
# WHITESPACE character, so `read` coalesces consecutive tabs and an empty
# column would silently shift every later field.
hs_oracle_record() {
  local name="$1" kind="$2" a1="$3" a2="$4" block="$5" value="$6"
  [ -n "$a2" ] || a2="-"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$name" "$kind" "$a1" "$a2" "$block" "$value" \
    >>"$(hs_oracle_file)"
  hs_log "oracle recorded: $name $kind @ block $block = $value"
}

# hs_oracle_capture <node_dir> <name> <kind> <arg1> [arg2]
# Reads the LIVE value, pins it to the current head, and records it.
#
# The captured value is REQUIRED to be a real 0x quantity. Without that check an
# unreachable / erroring node records an EMPTY oracle, and the later replay then
# compares empty against empty and "passes" -- the exact vacuous-assertion shape this
# harness exists to avoid. An unusable capture is a harness error (exit 2), not a
# product verdict: nothing has been observed about the archive yet.
hs_oracle_capture() {
  local node_dir="$1" name="$2" kind="$3" a1="$4" a2="${5:-}"
  local block value
  block="$(hs_head_num "$node_dir")"
  [ "$block" != "-1" ] || hs_abort "cannot capture oracle $name: head unavailable"
  case "$kind" in
    balance) value="$(hs_live_balance "$node_dir" "$a1")" ;;
    code) value="$(hs_live_code "$node_dir" "$a1")" ;;
    storage) value="$(hs_live_storage_at "$node_dir" "$a1" "$a2")" ;;
    *) hs_die "hs_oracle_capture: unsupported kind '$kind'" ;;
  esac
  case "$value" in
    0x*) : ;;
    *) hs_die "oracle $name captured an unusable live value [$value]; an empty or non-hex oracle would replay vacuously" ;;
  esac
  hs_oracle_record "$name" "$kind" "$a1" "$a2" "$block" "$value"
}

# hs_oracle_replay <node_dir> [oracle_file]
#
# Replays every recorded oracle through the ARCHIVE path. Sets
# HS_ORACLE_REPLAYED. Call directly, never in `$( )` -- it asserts.
HS_ORACLE_REPLAYED=0
hs_oracle_replay() {
  local node_dir="$1" file="${2:-}"
  [ -n "$file" ] || file="$(hs_oracle_file)"
  [ -f "$file" ] || hs_die "no oracle ledger at $file"
  local name kind a1 a2 block want got
  HS_ORACLE_REPLAYED=0
  while IFS="$(printf '\t')" read -r name kind a1 a2 block want; do
    [ -n "$name" ] || continue
    [ "$a2" != "-" ] || a2=""
    if [ -z "$block" ] || [ -z "$want" ]; then
      hs_fail "oracle ledger line for '$name' is malformed (kind=$kind a1=$a1 a2=$a2 block=$block want=$want)"
      continue
    fi
    case "$want" in
      0x*) : ;;
      *)
        # Belt and braces: hs_oracle_capture already refuses these, but a ledger
        # written by hand or by an older run must not replay vacuously.
        hs_fail "oracle $name recorded an unusable value [$want]; it cannot prove anything"
        continue ;;
    esac
    case "$kind" in
      balance) got="$(hs_hist_balance "$node_dir" "$a1" "$block")" ;;
      code) got="$(hs_hist_code "$node_dir" "$a1" "$block")" ;;
      storage) got="$(hs_hist_storage_at "$node_dir" "$a1" "$a2" "$block")" ;;
      *) hs_fail "oracle $name has an unsupported kind '$kind'"; continue ;;
    esac
    hs_assert_eq "$want" "$got" "oracle $name ($kind @ block $block)"
    HS_ORACLE_REPLAYED=$((HS_ORACLE_REPLAYED + 1))
  done <"$file"
  # An empty ledger replays zero oracles and would otherwise look like a clean pass.
  if [ "$HS_ORACLE_REPLAYED" -eq 0 ]; then
    hs_fail "oracle replay compared 0 oracles ($file is empty or entirely unusable)"
  fi
  hs_log "replayed $HS_ORACLE_REPLAYED oracles through the archive"
}

# ===========================================================================
# Filesystem and resource faults (scenario C)
# ===========================================================================

hs_disk_free_bytes() {
  local path="$1"
  [ -e "$path" ] || hs_die "hs_disk_free_bytes: no such path $path"
  df -k "$path" | awk 'NR==2 {print $4 * 1024}'
}

# hs_make_small_volume <image_base_path> <size_mb> <volname> -- echo the mount
# point. macOS only, no sudo required. The image lands at <image_base>.dmg.
hs_make_small_volume() {
  local image="$1" size_mb="$2" volname="$3"
  command -v hdiutil >/dev/null 2>&1 || hs_die "hdiutil is not available (macOS only)"
  if [ -e "${image}.dmg" ]; then
    hs_die "disk image already exists: ${image}.dmg"
  fi
  hdiutil create -size "${size_mb}m" -fs HFS+ -volname "$volname" -quiet "$image" \
    || hs_die "hdiutil create failed for $image"
  hdiutil attach "${image}.dmg" -quiet >/dev/null 2>&1 \
    || hs_die "hdiutil attach failed for ${image}.dmg"
  local mount="/Volumes/$volname"
  [ -d "$mount" ] || hs_die "expected mount point $mount is missing after attach"
  hs_log "mounted ${size_mb} MiB volume at $mount (free $(hs_disk_free_bytes "$mount") bytes)"
  printf '%s\n' "$mount"
}

# hs_detach_volume <mount_point> [--force]
# --force simulates device disappearance: RocksDB then sees Input/output error,
# the repair marker itself cannot be persisted, and the watchdog halts with 70.
hs_detach_volume() {
  local mount="$1" force="${2:-}"
  if [ "$force" = "--force" ]; then
    hdiutil detach -force "$mount" -quiet >/dev/null 2>&1 || true
    hs_log "force-detached $mount (device disappearance)"
  else
    hdiutil detach "$mount" -quiet >/dev/null 2>&1 || true
    hs_log "detached $mount"
  fi
  return 0
}

# hs_fill_volume <mount_point> <leave_free_bytes> -- drive the volume toward
# ENOSPC without unmounting it.
hs_fill_volume() {
  local mount="$1" leave="${2:-0}" free fill_bytes
  free="$(hs_disk_free_bytes "$mount")"
  fill_bytes=$((free - leave))
  if [ "$fill_bytes" -le 0 ]; then
    hs_log "$mount is already at or below the target free bytes"
    return 0
  fi
  dd if=/dev/zero of="$mount/hs-fill.bin" bs=1048576 \
    count=$((fill_bytes / 1048576)) >/dev/null 2>&1 || true
  hs_log "filled $mount; free is now $(hs_disk_free_bytes "$mount") bytes"
}

hs_make_readonly() {
  chmod -R a-w "$1" || hs_die "chmod -R a-w failed for $1"
  hs_log "made read-only: $1"
}

hs_make_writable() {
  chmod -R u+w "$1" 2>/dev/null || true
  return 0
}

# hs_truncate_file <path> <bytes> -- partial/truncated file visibility.
# Typical targets: an SST or the active MANIFEST under <archive>/unified.
hs_truncate_file() {
  local path="$1" bytes="$2"
  [ -f "$path" ] || hs_die "hs_truncate_file: no such file $path"
  python3 -c 'import os,sys; os.truncate(sys.argv[1], int(sys.argv[2]))' "$path" "$bytes" \
    || hs_die "failed to truncate $path to $bytes bytes"
  hs_log "truncated $path to $bytes bytes"
}

# ===========================================================================
# Concurrency support (scenario D)
# ===========================================================================

# hs_query_loop_start <node_dir> <addr0x> <blockDec> <out_prefix>
#
# Hammers a PINNED historical eth_getBalance in the background, appending one
# JSON line per attempt to <out_prefix>.jsonl:
#   {"t":<epoch>,"kind":"result"|"error"|"transport","value":...}
# Echoes the loop PID; stop it with hs_query_loop_stop.
hs_query_loop_start() {
  local node_dir="$1" addr="$2" block="$3" prefix="$4" url tag
  url="$(hs_jsonrpc_url "$node_dir")"
  tag="$(hs_dec_to_hexblock "$block")"
  : >"$prefix.jsonl"
  (
    while :; do
      body="$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' \
        --data "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_getBalance\",\"params\":[\"$addr\",\"$tag\"]}" \
        "$url" 2>/dev/null)" || body=""
      if [ -z "$body" ]; then
        printf '{"t":%s,"kind":"transport","value":null}\n' "$(date +%s)" >>"$prefix.jsonl"
      elif printf '%s' "$body" | jq -e 'has("error")' >/dev/null 2>&1; then
        printf '{"t":%s,"kind":"error","value":%s}\n' "$(date +%s)" \
          "$(printf '%s' "$body" | jq -c '.error')" >>"$prefix.jsonl"
      else
        printf '{"t":%s,"kind":"result","value":%s}\n' "$(date +%s)" \
          "$(printf '%s' "$body" | jq -c '.result')" >>"$prefix.jsonl"
      fi
      sleep 0.1
    done
  ) >/dev/null 2>&1 &
  # The redirect above is load-bearing: without it this background loop holds
  # the caller's stdout open and any `$( )` around this helper would hang.
  printf '%s\n' "$!"
}

hs_query_loop_stop() {
  local pid="$1"
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  return 0
}

# hs_query_loop_tally <prefix> -- "results=<n> errors=<n> transport=<n>".
hs_query_loop_tally() {
  local prefix="$1" f="$1.jsonl"
  [ -f "$f" ] || hs_die "no query-loop output at $f"
  printf 'results=%s errors=%s transport=%s\n' \
    "$(grep -c '"kind":"result"' "$f" || true)" \
    "$(grep -c '"kind":"error"' "$f" || true)" \
    "$(grep -c '"kind":"transport"' "$f" || true)"
}

# hs_query_loop_distinct_results <prefix> -- distinct successful values.
# A query pinned to a fixed PRE-fork height must yield exactly ONE distinct
# value across a reorg; more than one is silent divergence.
hs_query_loop_distinct_results() {
  local prefix="$1"
  { grep '"kind":"result"' "$prefix.jsonl" 2>/dev/null || true; } \
    | { jq -r '.value' 2>/dev/null || true; } | sort -u
}

# hs_query_loop_error_codes <prefix> -- distinct JSON-RPC error codes seen.
hs_query_loop_error_codes() {
  local prefix="$1"
  { grep '"kind":"error"' "$prefix.jsonl" 2>/dev/null || true; } \
    | { jq -r '.value.code' 2>/dev/null || true; } | sort -u
}

# ===========================================================================
# TCP relay -- the partition primitive for the fork scenario
# ===========================================================================

# hs_relay_start <listen_port> <target_host> <target_port> <out_dir>
#
# A killable stdlib-python TCP relay. Node B dials the relay; the relay
# forwards to node A. Killing the relay partitions the two witnesses WITHOUT
# restarting either node -- a restart would hit the archive/canonical head
# mismatch brick documented in the README. Echoes the relay PID.
hs_relay_start() {
  local listen="$1" target_host="$2" target_port="$3" out_dir="$4"
  mkdir -p "$out_dir"
  local script="$out_dir/relay.py"
  cat >"$script" <<'PYEOF'
import socket
import sys
import threading

listen_port = int(sys.argv[1])
target_host = sys.argv[2]
target_port = int(sys.argv[3])


def pump(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        for sock in (src, dst):
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass


def handle(client):
    try:
        upstream = socket.create_connection((target_host, target_port), timeout=10)
    except OSError:
        try:
            client.close()
        except OSError:
            pass
        return
    threading.Thread(target=pump, args=(client, upstream), daemon=True).start()
    threading.Thread(target=pump, args=(upstream, client), daemon=True).start()


server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(("127.0.0.1", listen_port))
server.listen(64)
sys.stderr.write("relay %d -> %s:%d\n" % (listen_port, target_host, target_port))
sys.stderr.flush()
while True:
    conn, _ = server.accept()
    threading.Thread(target=handle, args=(conn,), daemon=True).start()
PYEOF
  hs_require_free_ports "$listen"
  python3 "$script" "$listen" "$target_host" "$target_port" \
    >"$out_dir/relay.out" 2>"$out_dir/relay.err" &
  local pid=$! waited=0
  while ! grep -q 'relay ' "$out_dir/relay.err" 2>/dev/null; do
    sleep 0.2
    waited=$((waited + 1))
    [ "$waited" -lt 50 ] || hs_die "the relay on port $listen never started (see $out_dir/relay.err)"
  done
  hs_log "relay up: 127.0.0.1:$listen -> $target_host:$target_port (pid $pid)"
  printf '%s\n' "$pid"
}

# hs_relay_stop <pid> -- partition the topology.
hs_relay_stop() {
  local pid="$1"
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  hs_log "relay $pid stopped (partitioned)"
  return 0
}

# ===========================================================================
# Durability windows (scenario B)
#
# EXECUTION STATUS (see README section 10): the jdb attach/breakpoint/kill
# mechanism has been smoke-tested in isolation, but a full
# "suspend at breakpoint -> SIGKILL -> restart -> classify" cycle has NOT been
# run end to end against a real chain. Smoke-test ONE window (w3 is the
# simplest anchor) before trusting the whole matrix. If jdb proves unworkable,
# the fallback is the existing JUnit driver
# framework/src/test/java/org/tron/core/archive/ManagerArchiveSwitchForkTest.java.
#
# The anchors below are RESOLVED FROM SOURCE at run time by
# scenario-kill-matrix.sh; the line numbers here are documentation only and are
# never used to place a breakpoint.
#
# Anchors (java-tron @ 3ae4d79f5c):
#   org.tron.core.db.Manager:1994               after journal put / before canonical commit
#   org.tron.core.db.Manager:2010               after canonical commit / before ack
#   org.tron.core.db.Manager:2013               after ack / before publish
#   org.tron.core.archive.UnifiedArchiveBackend:120   mid publish batch
#   org.tron.core.db.Manager:754                genesis: after commitToRoot,
#                                               before the COMMITTED marker
#   org.tron.core.db.Manager:1657               fork-replay journal/commit/ack
# ===========================================================================

# hs_jdb_log_path <node_dir> <class:line> -- THE canonical jdb transcript path.
#
# Callers must use this rather than reconstructing the name: the transcript is what
# proves the breakpoint fired in the intended METHOD, and a caller that guesses a
# different filename silently loses that guard.
hs_jdb_log_path() {
  printf '%s/jdb-%s.log\n' "$1" "$(printf '%s' "$2" | tr ':.' '__')"
}

# hs_jdb_break_and_kill <node_dir> <class:line> [timeout_s]
#
# Attaches jdb to a node started with HS_JDWP_PORT set, arms one breakpoint,
# resumes, and SIGKILLs the JVM as soon as the breakpoint is hit (jdb suspends
# all threads there, freezing durable state inside the window).
# Echoes "HIT" or "MISS"; the transcript is at hs_jdb_log_path <node_dir> <location>.
hs_jdb_break_and_kill() {
  local node_dir="$1" location="$2" timeout="${3:-180}"
  [ -n "${HS_JDWP_PORT:-}" ] \
    || hs_die "hs_jdb_break_and_kill needs HS_JDWP_PORT set before hs_node_start"
  local jdb_bin=""
  jdb_bin="$(command -v jdb 2>/dev/null || true)"
  if [ -z "$jdb_bin" ] && [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jdb" ]; then
    jdb_bin="$JAVA_HOME/bin/jdb"
  fi
  [ -n "$jdb_bin" ] || hs_die "jdb not found on PATH or under JAVA_HOME"

  local out
  out="$(hs_jdb_log_path "$node_dir" "$location")"

  (
    printf 'stop at %s\n' "$location"
    printf 'cont\n'
    i=0
    while [ "$i" -lt "$timeout" ]; do
      sleep 1
      i=$((i + 1))
    done
  ) | "$jdb_bin" -attach "127.0.0.1:${HS_JDWP_PORT}" >"$out" 2>&1 &
  local jdb_pid=$!

  local deadline=$(( $(date +%s) + timeout ))
  while :; do
    if grep -q 'Breakpoint hit' "$out" 2>/dev/null; then
      hs_log "breakpoint hit at $location -- SIGKILL inside the window"
      hs_node_kill9 "$node_dir"
      kill "$jdb_pid" 2>/dev/null || true
      printf 'HIT\n'
      return 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      kill "$jdb_pid" 2>/dev/null || true
      hs_log "breakpoint $location was never hit within ${timeout}s (see $out)"
      printf 'MISS\n'
      return 0
    fi
    sleep 0.5
  done
}

# ===========================================================================
# Offline archive probe
#
# This repo has NO offline archive-DB inspection CLI (plugins/.../ArchiveManifest.java
# and DbArchive.java are unrelated LevelDB manifest tools), and ArchiveMetrics exposes
# no unwind/rollback counter, so "the orphan journal was rolled back" and "txNums are
# gap-free after re-capture" cannot be asserted from metrics alone. The gap is closed
# by ArchiveProbe.java, which lives at the HARNESS ROOT (not under java/) because
# scenario-kill-matrix.sh and scenario-fork-reorg.sh both compile it from there.
#
# Probe exit codes: 0 clean, 1 structural violation, 2 usage, 3 database unopenable.
# ===========================================================================

HS_PROBE_READY=0
HS_PROBE_RC=99

# hs_probe_build -- compile ArchiveProbe into the run's class dir. 0 on success.
hs_probe_build() {
  [ "$HS_PROBE_READY" -eq 1 ] && return 0
  [ -n "$HS_JAR" ] && [ -n "$HS_CLASSES" ] || return 1
  [ -f "$HS_HARNESS_DIR/ArchiveProbe.java" ] || return 1
  javac -nowarn -cp "$HS_JAR" -d "$HS_CLASSES" "$HS_HARNESS_DIR/ArchiveProbe.java" \
    >/dev/null 2>&1 || return 1
  HS_PROBE_READY=1
  return 0
}

# hs_offline_probe <node_dir> -- echo the probe JSON and RETURN the probe's exit code
# (0 clean, 1 violation, 3 unopenable, 99 the probe could not be built or run).
#
# The status is returned rather than published in a global ON PURPOSE. Callers naturally
# write `json="$(hs_offline_probe dir)"`, which runs the function in a SUBSHELL: a global
# assigned in there is invisible to the caller, so a global-based status would read 99
# ("no evidence") on every single call and quietly disable every check built on it. The
# exit status is the one channel that survives command substitution.
# HS_PROBE_RC is still set for callers that invoke this directly.
hs_offline_probe() {
  local node_dir="$1" unified out rc
  HS_PROBE_RC=99
  if ! hs_probe_build; then
    hs_log "offline probe unavailable (ArchiveProbe.java missing or did not compile)"
    printf '{}\n'
    return 99
  fi
  unified="$(hs_archive_dir "$node_dir")/unified"
  out="$(java -cp "$HS_JAR:$HS_CLASSES" ArchiveProbe "$unified" 2>/dev/null)"
  rc=$?
  HS_PROBE_RC="$rc"
  [ -n "$out" ] || out='{}'
  printf '%s\n' "$out"
  return "$rc"
}

# hs_assert_probe_clean <node_dir> <what...>
#
# The structural half of "no silent half state". Requires the probe to have RUN
# (HS_PROBE_RC != 99) and to have OPENED the database -- an absent probe is a failed
# check, never a silent pass.
hs_assert_probe_clean() {
  local node_dir="$1"
  shift
  local json opened viol ranges rc
  json="$(hs_offline_probe "$node_dir")"
  rc=$?
  if [ "$rc" = "99" ]; then
    hs_fail "$*: the offline archive probe could not run, so no structural evidence exists"
    return 0
  fi
  opened="$(printf '%s' "$json" | jq -r '.opened // false' 2>/dev/null || printf 'false')"
  if [ "$opened" != "true" ]; then
    hs_fail "$*: the archive database could not be opened: $(printf '%s' "$json" | jq -r '.openError // "?"' 2>/dev/null || true)"
    return 0
  fi
  ranges="$(printf '%s' "$json" | jq -r '.rangeCount // 0' 2>/dev/null || printf 0)"
  viol="$(printf '%s' "$json" | jq -r '(.violations // []) | join("; ")' 2>/dev/null || true)"
  if [ -n "$viol" ]; then
    hs_fail "$*: probe found structural violations: $viol"
    return 0
  fi
  if [ "$ranges" -le 0 ] 2>/dev/null; then
    hs_fail "$*: probe reports rangeCount=$ranges -- an empty index cannot demonstrate integrity"
    return 0
  fi
  hs_pass "$*: probe clean ($ranges contiguous range(s), no violations)"
}
