#!/usr/bin/env bash
#
# scenario-fork-reorg.sh — archive behaviour across a real Manager.switchFork (target A).
#
# =============================================================================================
# WHY TWO NODES (and why "fork on one node" is not implementable from outside)
# =============================================================================================
# A single-witness private chain can never reorg:
#   * DposService computes the solid head from the active witnesses' latest block numbers
#     (consensus/src/main/java/org/tron/consensus/dpos/DposService.java:159-176).  With one witness
#     solid == head.
#   * Manager sizes khaosDb / revokingStore to `head - solid + 1`
#     (framework/src/main/java/org/tron/core/db/Manager.java:2097-2105), so the fork buffer is 1.
#   * Manager.pushBlock early-returns for `newBlock.getNum() <= headerNumber`
#     (Manager.java:1936), and there is no HTTP/gRPC "submit competing block" API.
# So there is no external way to hand a heavier branch to a lone node.  This scenario therefore
# runs TWO witness nodes and partitions them.  While partitioned each side's solid head stalls at
# the absent witness's last block, the fork buffer grows with partition depth, and healing the
# partition drives a genuine, arbitrarily deep `Manager.switchFork`.
#
# The partition is a plain userspace TCP relay (no root, no pfctl, no socat): node B dials the
# relay, the relay forwards to node A.  Killing the relay partitions; restarting it heals.  No node
# is ever restarted mid-scenario, which keeps the reorg itself the only variable.
#
# =============================================================================================
# WHAT IS ASSERTED
# =============================================================================================
#   fork.switch_fork_logged        Manager logged `Switch fork! new head num = N` (Manager.java:1945)
#   fork.erase_blocks_logged       Manager logged `Start to erase block` (Manager.java:1367), the
#                                  path that calls unwindArchiveBlockOrFailStop (Manager.java:1371)
#   fork.unwind_exactly_once       no block number was erased twice — the orphan journal is rolled
#                                  back once, not repeatedly
#   fork.block_fork_metric         tron:block_fork_total{type="all"} incremented, {type="fail"} == 0
#   fork.no_repair_required        tron:archive_state{type="repair_required"} == 0 on both nodes
#   fork.no_archive_failstop       no ArchiveException / ARCHIVE_RUNTIME breadcrumb in either log
#   fork.prefork_history_stable    historical eth_getBalance at pre-fork heights is byte-identical
#                                  before and after the reorg
#   fork.prefork_cross_node        node A and node B return the same historical values there
#   fork.orphan_height_fail_closed while partitioned, a query at an orphan height fails closed with
#                                  -32000 "archive history unavailable" (unsolidified blocks are
#                                  never published, so orphan state is never queryable)
#   fork.orphan_state_dropped      the orphan-only account is empty on the healed canonical chain
#   fork.orphan_content_not_served after the reorg, no re-captured height serves the orphan value
#   fork.recaptured_cross_node     A and B agree at every re-captured height
#   fork.beyond_range_fail_closed  a height above the published range fails closed
#   fork.txnum_gap_free            offline probe: block ranges contiguous, txNums contiguous
#   fork.no_stale_journal          offline probe: no journal row survives at an already-published
#                                  height (publication deletes it, so one that is still there is an
#                                  orphan journal that was never rolled back)
#   fork.probe_no_repair_marker    offline probe: the `repair-required` META key is absent
#
# The orphan-only state is created with a transfer whose TAPOS reference is an orphan block
# (`trx.reference.block = "head"`, so the reference is node A's partitioned head).  After the
# reorg that reference block no longer exists, the transaction can never be replayed onto the
# canonical branch, and the recipient account is a clean "this value existed only on the orphan
# branch" oracle.
#
# =============================================================================================
# USAGE
# =============================================================================================
#   docs/archiveV3/harness/scenario-fork-reorg.sh
#
#   Environment overrides (all optional):
#     ARCHIVE_HARNESS_JAR        path to FullNode.jar (default framework/build/libs/FullNode.jar)
#     ARCHIVE_HARNESS_BUILD=1    build the jar if it is missing
#     ARCHIVE_HARNESS_RUN_ROOT   where run directories are created
#     ARCHIVE_HARNESS_PORT_OFFSET shift every listening port (run two harnesses side by side)
#     FORK_BOOT_HEAD             head height both nodes must reach before partitioning (default 10)
#     FORK_DIVERGE_BLOCKS        orphan blocks to produce on A while partitioned (default 4)
#     FORK_LEAD_BLOCKS           how far B must get ahead of A before healing (default 8)
#     FORK_ASSERT_RESTART=1      additionally restart node A after the reorg and grade the restart
#                                (off by default because restart durability is target B's kill
#                                matrix; enable it for the multi-witness restart regression)
#
#   Exit 0 + `FORK_E2E_OK checks=N ...`; exit 1 + `FORK_E2E_FAIL checks=N failures=M ...`;
#   exit 2 + `HARNESS_ERROR` (the scenario could not run — no verdict about the product).
#
# REGRESSION TARGET:
#   A published archive head may legitimately be ahead of the recovered solidified metadata after
#   batched canonical snapshots are flushed. The restart must validate that published block at its
#   own canonical height, not require equality with the recovered solidified height. Any genuine
#   startup mismatch must exit through ARCHIVE_RUNTIME rather than leave a live-but-dead JVM.

set -uo pipefail

AH_HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# lib.sh is the shared harness library; scenario-common.sh only fills in helpers lib.sh does not
# already define, so either file may own any given helper.
# shellcheck source=/dev/null
[ -f "$AH_HARNESS_DIR/lib.sh" ] && . "$AH_HARNESS_DIR/lib.sh"
# shellcheck source=/dev/null
. "$AH_HARNESS_DIR/scenario-common.sh"

FORK_BOOT_HEAD="${FORK_BOOT_HEAD:-10}"
FORK_DIVERGE_BLOCKS="${FORK_DIVERGE_BLOCKS:-4}"
FORK_LEAD_BLOCKS="${FORK_LEAD_BLOCKS:-8}"
FORK_ASSERT_RESTART="${FORK_ASSERT_RESTART:-0}"

READY_TIMEOUT=180
CONVERGE_TIMEOUT=240
PUBLISH_TIMEOUT=180

# Deterministic private-chain keys (addresses verified with HarnessSigner at authoring time).
KEY_W1=1234567890123456789012345678901234567890123456789012345678901234
KEY_W2=5555555555555555555555555555555555555555555555555555555555555555
KEY_P1=1111111111111111111111111111111111111111111111111111111111111111
KEY_ORPHAN=7777777777777777777777777777777777777777777777777777777777777777

A_P2P=$(( 16666 + AH_PORT_OFFSET )); A_HTTP=$(( 8090 + AH_PORT_OFFSET ))
A_RPC=$(( 50051 + AH_PORT_OFFSET )); A_JSONRPC=$(( 8545 + AH_PORT_OFFSET ))
A_METRICS=$(( 9527 + AH_PORT_OFFSET ))
B_P2P=$(( 16667 + AH_PORT_OFFSET )); B_HTTP=$(( 8190 + AH_PORT_OFFSET ))
B_RPC=$(( 50151 + AH_PORT_OFFSET )); B_JSONRPC=$(( 8645 + AH_PORT_OFFSET ))
B_METRICS=$(( 9627 + AH_PORT_OFFSET ))
RELAY_PORT=$(( 26666 + AH_PORT_OFFSET ))

RUN_DIR=""
RELAY_PID=""
A_PID=""
B_PID=""

cleanup() {
  local rc=$?
  [ -n "$RELAY_PID" ] && kill -CONT "$RELAY_PID" 2>/dev/null
  [ -n "$A_PID" ] && kill -CONT "$A_PID" 2>/dev/null
  [ -n "$RELAY_PID" ] && kill -TERM "$RELAY_PID" 2>/dev/null
  [ -n "$B_PID" ] && ah_alive "$B_PID" && kill -TERM "$B_PID" 2>/dev/null
  [ -n "$A_PID" ] && ah_alive "$A_PID" && kill -TERM "$A_PID" 2>/dev/null
  sleep 2
  [ -n "$B_PID" ] && ah_alive "$B_PID" && kill -KILL "$B_PID" 2>/dev/null
  [ -n "$A_PID" ] && ah_alive "$A_PID" && kill -KILL "$A_PID" 2>/dev/null
  # stderr, not stdout: the scenario marker must stay the last stdout line.
  [ -n "$RUN_DIR" ] && printf 'artifacts: %s\n' "$RUN_DIR" >&2
  return $rc
}
trap cleanup EXIT INT TERM

# --------------------------------------------------------------------------- helpers

write_relay() {
  cat >"$1" <<'PY'
# Minimal killable TCP relay: the harness's partition switch.
# usage: relay.py <listen_port> <target_port>
import socket
import sys
import threading


def pipe(src, dst):
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


def handle(client, target_port):
    try:
        upstream = socket.create_connection(("127.0.0.1", target_port), 10)
    except OSError:
        client.close()
        return
    threading.Thread(target=pipe, args=(client, upstream), daemon=True).start()
    threading.Thread(target=pipe, args=(upstream, client), daemon=True).start()


def main():
    listen_port = int(sys.argv[1])
    target_port = int(sys.argv[2])
    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", listen_port))
    server.listen(64)
    while True:
        client, _ = server.accept()
        threading.Thread(target=handle, args=(client, target_port), daemon=True).start()


main()
PY
}

start_relay() {
  python3 "$RUN_DIR/relay.py" "$RELAY_PORT" "$A_P2P" >"$RUN_DIR/relay.log" 2>&1 &
  RELAY_PID=$!
  sleep 1
  ah_alive "$RELAY_PID" || ah_fatal "relay failed to start (see $RUN_DIR/relay.log)"
  ah_log "relay up pid=$RELAY_PID 127.0.0.1:$RELAY_PORT -> 127.0.0.1:$A_P2P"
}

stop_relay() {
  [ -n "$RELAY_PID" ] || return 0
  kill -TERM "$RELAY_PID" 2>/dev/null
  wait "$RELAY_PID" 2>/dev/null
  ah_log "relay pid=$RELAY_PID stopped (partition open)"
  RELAY_PID=""
}

# eth address for a private key, cached in a file so we do not fork a JVM repeatedly.
eth_addr() { ah_signer addr "$1" | cut -f3; }
b58_addr() { ah_signer addr "$1" | cut -f1; }

hex_height() { printf '0x%x' "$1"; }

# send_transfer PORT FROM_B58 TO_B58 AMOUNT PRIVHEX -> prints the txID, or empty on failure
send_transfer() {
  local port="$1" from="$2" to="$3" amount="$4" priv="$5" tx txid sig signed result
  tx="$(curl -s --max-time 20 -XPOST -H 'Content-Type: application/json' \
        --data "{\"owner_address\":\"$from\",\"to_address\":\"$to\",\"amount\":$amount,\"visible\":true}" \
        "http://127.0.0.1:$port/wallet/createtransaction")"
  txid="$(printf '%s' "$tx" | jq -r '.txID // ""' 2>/dev/null)"
  if [ -z "$txid" ]; then
    ah_warn "createtransaction returned no txID: $tx"
    return 1
  fi
  sig="$(ah_signer sign "$priv" "$txid")" || return 1
  signed="$(printf '%s' "$tx" | jq -c --arg s "$sig" '. + {signature: [$s]}' 2>/dev/null)"
  result="$(curl -s --max-time 20 -XPOST -H 'Content-Type: application/json' \
            --data "$signed" "http://127.0.0.1:$port/wallet/broadcasttransaction")"
  if [ "$(printf '%s' "$result" | jq -r '.result // false' 2>/dev/null)" != "true" ]; then
    ah_warn "broadcast rejected: $result"
    return 1
  fi
  printf '%s' "$txid"
}

# tx_block PORT TXID -> block number the tx was mined in, or empty
tx_block() {
  curl -s --max-time 20 -XPOST -H 'Content-Type: application/json' \
    --data "{\"value\":\"$2\"}" "http://127.0.0.1:$1/wallet/gettransactioninfobyid" 2>/dev/null \
    | jq -r '.blockNumber // ""' 2>/dev/null
}

# wait_served_height JSONRPC_PORT HTTP_PORT TIMEOUT_S -> prints the highest height the archive
# actually answers for.  The publisher is asynchronous, so the first heights become queryable a
# little after they solidify; this retries instead of giving up on the first miss, and records the
# last error in SERVED_LAST_ERR so a failure is diagnosable.
wait_served_height() {
  local rpc="$1" http="$2" timeout="$3" deadline published height tries value
  deadline=$(( $(date +%s) + timeout ))
  SERVED_LAST_ERR="no query attempted"
  while [ "$(date +%s)" -lt "$deadline" ]; do
    published="$(ah_published_head "$http")"
    [ -n "$published" ] || published=1
    height="$published"
    tries=0
    while [ "$height" -ge 1 ] && [ "$tries" -lt 8 ]; do
      value="$(ah_eth_balance "$rpc" "$ETH_P1" "$(hex_height "$height")")"
      case "$value" in
        0x*) printf '%s' "$height"; return 0 ;;
      esac
      SERVED_LAST_ERR="height $height -> $value"
      height=$(( height - 1 ))
      tries=$(( tries + 1 ))
    done
    sleep 5
  done
  return 1
}

# is_value V   -> 0 when V is a real JSON-RPC quantity the archive actually served.
# is_failclosed V -> 0 when V is an EXPLICIT archive refusal (a JSON-RPC error object).
#
# `ERR:transport` and `ERR:unparsable` are neither: they mean the node did not answer at
# all. Folding them into the fail-closed bucket would turn "the node is dead" into a
# passing fail-closed assertion, which is the classic vacuous pass -- a dead node
# trivially "fails closed" for every query ever made.
is_value()      { case "$1" in 0x*) return 0 ;; *) return 1 ;; esac; }
is_failclosed() {
  case "$1" in
    ERR:transport|ERR:unparsable|ERR:?:*|'') return 1 ;;
    ERR:-*) return 0 ;;
    ERR:*) return 0 ;;
    *) return 1 ;;
  esac
}

# wait_published PORT MIN_HEIGHT TIMEOUT — waits until MIN_HEIGHT is inside the published range.
wait_published() {
  local port="$1" want="$2" timeout="$3" deadline
  deadline=$(( $(date +%s) + timeout ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    case "$(ah_eth_balance "$port" "$ETH_P1" "$(hex_height "$want")")" in
      0x*) return 0 ;;
    esac
    sleep 3
  done
  return 1
}

# --------------------------------------------------------------------------- preflight

ah_require_cmds java javac curl jq python3 awk sed
ah_require_jar
ah_require_free_ports "$A_P2P" "$A_HTTP" "$A_RPC" "$A_JSONRPC" "$A_METRICS" \
                      "$B_P2P" "$B_HTTP" "$B_RPC" "$B_JSONRPC" "$B_METRICS" "$RELAY_PORT"

RUN_DIR="$(ah_run_dir fork-reorg)"
ah_log "run directory: $RUN_DIR"
ah_compile_helpers "$RUN_DIR/classes"

ETH_W1="$(eth_addr "$KEY_W1")"
ETH_P1="$(eth_addr "$KEY_P1")"
B58_P1="$(b58_addr "$KEY_P1")"
ETH_ORPHAN="$(eth_addr "$KEY_ORPHAN")"
B58_ORPHAN="$(b58_addr "$KEY_ORPHAN")"
ah_log "orphan-only recipient: $B58_ORPHAN / $ETH_ORPHAN"

GENESIS_WITNESSES='    { address: TEDapYSVvAZ3aYH7w8N9tMEEFKaNKUD5Bp, url = "http://sr1.local", voteCount = 100 },
    { address: TWa5cxQFesyCQUm17usvHrVkKce6rMCV4H, url = "http://sr2.local", voteCount = 99 }'

ah_conf_reset
AH_CONF_P2P_PORT=$A_P2P; AH_CONF_HTTP_PORT=$A_HTTP; AH_CONF_RPC_PORT=$A_RPC
AH_CONF_JSONRPC_PORT=$A_JSONRPC; AH_CONF_METRICS_PORT=$A_METRICS
AH_CONF_WITNESS_KEY=$KEY_W1
AH_CONF_GENESIS_WITNESSES="$GENESIS_WITNESSES"
AH_CONF_ACTIVE_LIST='[]'
# "head" (not the "solid" default) pins the divergence transaction's TAPOS reference to an orphan
# block so it can never be replayed onto the canonical branch after the reorg.
AH_CONF_TRX_REFERENCE=head
ah_write_node_conf "$RUN_DIR/a/node.conf"

ah_conf_reset
AH_CONF_P2P_PORT=$B_P2P; AH_CONF_HTTP_PORT=$B_HTTP; AH_CONF_RPC_PORT=$B_RPC
AH_CONF_JSONRPC_PORT=$B_JSONRPC; AH_CONF_METRICS_PORT=$B_METRICS
AH_CONF_WITNESS_KEY=$KEY_W2
AH_CONF_GENESIS_WITNESSES="$GENESIS_WITNESSES"
AH_CONF_ACTIVE_LIST="[\"127.0.0.1:$RELAY_PORT\"]"
AH_CONF_TRX_REFERENCE=head
ah_write_node_conf "$RUN_DIR/b/node.conf"

write_relay "$RUN_DIR/relay.py"

# --------------------------------------------------------------------------- phase 1: boot

ah_log "phase 1: boot both witnesses and let them agree"
start_relay
ah_start_node "$RUN_DIR/a" "$RUN_DIR/a/node.conf" "$RUN_DIR/a/data"; A_PID=$AH_LAST_PID
ah_start_node "$RUN_DIR/b" "$RUN_DIR/b/node.conf" "$RUN_DIR/b/data"; B_PID=$AH_LAST_PID

ah_wait_ready "$A_HTTP" "$READY_TIMEOUT" || ah_fatal "node A never became ready (see $RUN_DIR/a)"
ah_wait_ready "$B_HTTP" "$READY_TIMEOUT" || ah_fatal "node B never became ready (see $RUN_DIR/b)"
ah_wait_head "$A_HTTP" "$FORK_BOOT_HEAD" "$READY_TIMEOUT" \
  || ah_fatal "node A never reached head $FORK_BOOT_HEAD"
ah_wait_head "$B_HTTP" "$FORK_BOOT_HEAD" "$READY_TIMEOUT" \
  || ah_fatal "node B never reached head $FORK_BOOT_HEAD"

PEERS_A="$(ah_peer_count "$A_HTTP")"
[ "${PEERS_A:-0}" -ge 1 ] 2>/dev/null \
  || ah_fatal "nodes never peered through the relay (A peers=${PEERS_A:-?}); the branches would be independent chains, not a fork"
ah_log "both witnesses up and peered; A head=$(ah_head_num "$A_HTTP") solid=$(ah_solid_num "$A_HTTP")"

# --------------------------------------------------------------------------- phase 2: pre-fork oracle

ah_log "phase 2: snapshot historical values at pre-fork heights"
H_PRE="$(wait_served_height "$A_JSONRPC" "$A_HTTP" "$PUBLISH_TIMEOUT")"
if [ -z "$H_PRE" ]; then
  ah_fatal "no historical height is being served on node A within ${PUBLISH_TIMEOUT}s (last attempt: $SERVED_LAST_ERR); the archive has published nothing to compare against"
fi
H_PRE_TAG="$(hex_height "$H_PRE")"
PRE_W1="$(ah_eth_balance "$A_JSONRPC" "$ETH_W1" "$H_PRE_TAG")"
PRE_P1="$(ah_eth_balance "$A_JSONRPC" "$ETH_P1" "$H_PRE_TAG")"
PRE_BLOCKID="$(ah_block_id "$A_HTTP" "$H_PRE")"
ah_log "pre-fork oracle at height $H_PRE ($H_PRE_TAG): W1=$PRE_W1 P1=$PRE_P1 blockID=${PRE_BLOCKID:0:16}..."

# --------------------------------------------------------------------------- phase 3: partition

ah_log "phase 3: partition the witnesses"
COMMON_HEAD="$(ah_head_num "$A_HTTP")"
stop_relay

PARTITION_DEADLINE=$(( $(date +%s) + 120 ))
while [ "$(date +%s)" -lt "$PARTITION_DEADLINE" ]; do
  [ "$(ah_peer_count "$A_HTTP")" = "0" ] && [ "$(ah_peer_count "$B_HTTP")" = "0" ] && break
  sleep 3
done
ah_log "partitioned at common head $COMMON_HEAD (A peers=$(ah_peer_count "$A_HTTP"), B peers=$(ah_peer_count "$B_HTTP"))"

ah_wait_head "$A_HTTP" $(( COMMON_HEAD + FORK_DIVERGE_BLOCKS )) 180 \
  || ah_fatal "node A did not produce $FORK_DIVERGE_BLOCKS orphan blocks while partitioned"

# --------------------------------------------------------------------------- phase 4: orphan-only state

ah_log "phase 4: create orphan-only state on node A"
ORPHAN_TXID="$(send_transfer "$A_HTTP" "$B58_P1" "$B58_ORPHAN" 1000000 "$KEY_P1")"
ORPHAN_TX_BLOCK=""
if [ -n "$ORPHAN_TXID" ]; then
  TX_DEADLINE=$(( $(date +%s) + 90 ))
  while [ "$(date +%s)" -lt "$TX_DEADLINE" ]; do
    ORPHAN_TX_BLOCK="$(tx_block "$A_HTTP" "$ORPHAN_TXID")"
    [ -n "$ORPHAN_TX_BLOCK" ] && break
    sleep 3
  done
fi

ORPHAN_LIVE_A="$(ah_eth_balance "$A_JSONRPC" "$ETH_ORPHAN" latest)"
ORPHAN_LIVE_B="$(ah_eth_balance "$B_JSONRPC" "$ETH_ORPHAN" latest)"
if [ -z "$ORPHAN_TX_BLOCK" ]; then
  ah_check fork.orphan_state_created SKIP \
    "the divergence transfer never confirmed on node A; orphan-content oracles are unavailable"
  ORPHAN_ORACLE=0
elif [ "$ORPHAN_LIVE_A" = "0x0" ] || [ "$ORPHAN_LIVE_A" = "ERR:transport" ]; then
  ah_check fork.orphan_state_created SKIP \
    "orphan recipient shows $ORPHAN_LIVE_A on node A even though the tx landed in block $ORPHAN_TX_BLOCK"
  ORPHAN_ORACLE=0
else
  ah_check fork.orphan_state_created PASS \
    "orphan-only transfer mined on A at block $ORPHAN_TX_BLOCK (A=$ORPHAN_LIVE_A, B=$ORPHAN_LIVE_B)"
  ORPHAN_ORACLE=1
fi

# While partitioned the solid head stalls, so orphan blocks are never published to the archive.
# A historical query at an orphan height must therefore fail closed, never serve unsolidified state.
if [ -n "$ORPHAN_TX_BLOCK" ]; then
  ORPHAN_HIST="$(ah_eth_balance "$A_JSONRPC" "$ETH_ORPHAN" "$(hex_height "$ORPHAN_TX_BLOCK")")"
  if is_value "$ORPHAN_HIST"; then
    ah_check fork.orphan_height_fail_closed FAIL \
      "unsolidified orphan height $ORPHAN_TX_BLOCK served a value: $ORPHAN_HIST"
  elif is_failclosed "$ORPHAN_HIST"; then
    ah_check fork.orphan_height_fail_closed PASS \
      "height $ORPHAN_TX_BLOCK failed closed with $ORPHAN_HIST"
  else
    # The node did not answer. Nothing about fail-closed behaviour was observed, and calling
    # that a pass would let a dead node satisfy the assertion.
    ah_check fork.orphan_height_fail_closed FAIL \
      "node did not answer the orphan-height query ($ORPHAN_HIST); fail-closed behaviour was never observed"
  fi
else
  ah_check fork.orphan_height_fail_closed SKIP "no orphan block to query"
fi

A_FROZEN_HEAD="$(ah_head_num "$A_HTTP")"

# --------------------------------------------------------------------------- phase 5: make B heavier

ah_log "phase 5: freeze node A so node B builds the heavier branch"
kill -STOP "$A_PID" || ah_fatal "could not SIGSTOP node A"
TARGET_B=$(( A_FROZEN_HEAD + FORK_LEAD_BLOCKS ))
if ! ah_wait_head "$B_HTTP" "$TARGET_B" 300; then
  kill -CONT "$A_PID" 2>/dev/null
  ah_fatal "node B never reached head $TARGET_B while node A was frozen"
fi
B_HEAD="$(ah_head_num "$B_HTTP")"
kill -CONT "$A_PID" || ah_fatal "could not SIGCONT node A"
ah_log "A frozen at $A_FROZEN_HEAD, B advanced to $B_HEAD; expected reorg depth $(( A_FROZEN_HEAD - COMMON_HEAD ))"

# --------------------------------------------------------------------------- phase 6: heal

# BASELINE BEFORE HEALING -- do not remove.
#
# A 2-witness chain logs a `Switch fork!` (and its erase lines) during INITIAL peer sync,
# long before any partition is induced. Counting matches over the WHOLE log therefore lets
# fork.switch_fork_logged and fork.erase_blocks_logged both PASS on a run where the intended
# reorg never happened at all -- a vacuous pass that was observed in practice. Every reorg
# verdict below is computed as a DELTA against these baselines.
SWITCH_BASELINE="$(ah_count_logs "$RUN_DIR/a" 'Switch fork!')"
ERASE_BASELINE="$(awk '/Start to erase block/{f=1; next} f && /^number=/{sub(/^number=/,""); print; f=0}' \
                  "$RUN_DIR/a/logs/tron.log" 2>/dev/null | grep -c '[0-9]' || true)"
FORK_ALL_BASELINE="$(ah_metric_fork "$A_METRICS" all)"
case "$SWITCH_BASELINE" in ''|*[!0-9]*) SWITCH_BASELINE=0 ;; esac
case "$ERASE_BASELINE" in ''|*[!0-9]*) ERASE_BASELINE=0 ;; esac
case "$FORK_ALL_BASELINE" in ''|*[!0-9]*) FORK_ALL_BASELINE=0 ;; esac
ah_log "pre-heal baselines on node A: switch-fork lines=$SWITCH_BASELINE erased-block lines=$ERASE_BASELINE block_fork_total=$FORK_ALL_BASELINE"

ah_log "phase 6: heal the partition and wait for convergence"
start_relay
CONVERGE_DEADLINE=$(( $(date +%s) + CONVERGE_TIMEOUT ))
CONVERGED=0
while [ "$(date +%s)" -lt "$CONVERGE_DEADLINE" ]; do
  HA="$(ah_head_num "$A_HTTP")"; HB="$(ah_head_num "$B_HTTP")"
  if [ -n "$HA" ] && [ -n "$HB" ] && [ "$HA" -ge "$B_HEAD" ] 2>/dev/null; then
    IDA="$(ah_block_id "$A_HTTP" "$B_HEAD")"; IDB="$(ah_block_id "$B_HTTP" "$B_HEAD")"
    if [ -n "$IDA" ] && [ "$IDA" = "$IDB" ]; then
      CONVERGED=1
      break
    fi
  fi
  sleep 4
done
ah_check_bool fork.converged "$(( 1 - CONVERGED ))" \
  "A head=$(ah_head_num "$A_HTTP") B head=$(ah_head_num "$B_HTTP") agree at block $B_HEAD"
[ "$CONVERGED" -eq 1 ] || ah_log "nodes did not converge; the remaining checks describe the state as found"

# --------------------------------------------------------------------------- phase 7: reorg evidence

ah_log "phase 7: grade the reorg"
SWITCH_LINES="$(ah_grep_logs "$RUN_DIR/a" 'Switch fork! new head num')"
SWITCH_TOTAL="$(printf '%s' "$SWITCH_LINES" | grep -c 'Switch fork' || true)"
case "$SWITCH_TOTAL" in ''|*[!0-9]*) SWITCH_TOTAL=0 ;; esac
SWITCH_COUNT=$(( SWITCH_TOTAL - SWITCH_BASELINE ))
[ "$SWITCH_COUNT" -ge 0 ] || SWITCH_COUNT=0
if [ "$SWITCH_COUNT" -ge 1 ]; then
  ah_check fork.switch_fork_logged PASS \
    "$SWITCH_COUNT NEW switch(es) after healing (baseline $SWITCH_BASELINE, total $SWITCH_TOTAL): $(printf '%s' "$SWITCH_LINES" | sed -n 's/.*new head num = \([0-9]*\).*/\1/p' | tr '\n' ' ')"
else
  ah_check fork.switch_fork_logged FAIL \
    "node A logged no NEW 'Switch fork!' after healing (baseline $SWITCH_BASELINE, total $SWITCH_TOTAL); the induced reorg did not happen, so nothing about archive unwind was exercised"
fi

# Only the erase events AFTER the pre-heal baseline belong to the induced reorg.
ALL_ERASED_NUMS="$(awk '/Start to erase block/{f=1; next} f && /^number=/{sub(/^number=/,""); print; f=0}' \
               "$RUN_DIR/a/logs/tron.log" 2>/dev/null)"
ERASED_NUMS="$(printf '%s\n' "$ALL_ERASED_NUMS" | grep '[0-9]' | tail -n +$(( ERASE_BASELINE + 1 )) || true)"
ERASE_COUNT="$(printf '%s\n' "$ERASED_NUMS" | grep -c '[0-9]' || true)"
case "$ERASE_COUNT" in ''|*[!0-9]*) ERASE_COUNT=0 ;; esac
if [ "$ERASE_COUNT" -ge 1 ]; then
  ah_check fork.erase_blocks_logged PASS \
    "$ERASE_COUNT block(s) erased by the induced reorg through the archive unwind lease (baseline $ERASE_BASELINE)"
else
  ah_check fork.erase_blocks_logged FAIL \
    "node A logged no NEW 'Start to erase block' after healing (baseline $ERASE_BASELINE)"
fi

DUP_ERASE="$(printf '%s\n' "$ERASED_NUMS" | grep '[0-9]' | sort | uniq -d | tr '\n' ' ' || true)"
if [ "$ERASE_COUNT" -lt 1 ]; then
  # No erase events means the invariant was never exercised. That is already reported as a
  # FAIL above, so recording SKIP here is honest -- but it must never read as "proved once".
  ah_check fork.unwind_exactly_once SKIP "no erase events from the induced reorg to check"
elif [ -z "$DUP_ERASE" ]; then
  ah_check fork.unwind_exactly_once PASS \
    "every one of the $ERASE_COUNT erased block numbers appears exactly once"
else
  ah_check fork.unwind_exactly_once FAIL "block number(s) erased more than once: $DUP_ERASE"
fi

# Also a DELTA: block_fork_total is a lifetime counter that the initial peer sync already
# incremented, so "all >= 1" would pass without the induced reorg.
FORK_ALL="$(ah_metric_fork "$A_METRICS" all)"
FORK_FAIL="$(ah_metric_fork "$A_METRICS" fail)"
if [ -z "$FORK_ALL" ]; then
  ah_check fork.block_fork_metric FAIL "tron:block_fork_total{type=\"all\"} is not exported (metrics unreachable?)"
elif [ "$(( FORK_ALL - FORK_ALL_BASELINE ))" -ge 1 ] 2>/dev/null && [ "${FORK_FAIL:-0}" -eq 0 ] 2>/dev/null; then
  ah_check fork.block_fork_metric PASS \
    "block_fork_total all $FORK_ALL_BASELINE -> $FORK_ALL (delta $(( FORK_ALL - FORK_ALL_BASELINE ))) fail=${FORK_FAIL:-0}"
else
  ah_check fork.block_fork_metric FAIL \
    "block_fork_total all $FORK_ALL_BASELINE -> $FORK_ALL (delta $(( FORK_ALL - FORK_ALL_BASELINE ))) fail=${FORK_FAIL:-<absent>}"
fi

REPAIR_A="$(ah_metric_state "$A_METRICS" repair_required)"
REPAIR_B="$(ah_metric_state "$B_METRICS" repair_required)"
if [ "${REPAIR_A:-1}" -eq 0 ] 2>/dev/null && [ "${REPAIR_B:-1}" -eq 0 ] 2>/dev/null; then
  ah_check fork.no_repair_required PASS "repair_required A=$REPAIR_A B=$REPAIR_B"
else
  ah_check fork.no_repair_required FAIL "repair_required A=${REPAIR_A:-<absent>} B=${REPAIR_B:-<absent>}"
fi

FAILSTOP="$(ah_failstop_evidence "$RUN_DIR/a"; ah_failstop_evidence "$RUN_DIR/b")"
ARCHIVE_ERRORS="$(ah_archive_error_evidence "$RUN_DIR/a"; ah_archive_error_evidence "$RUN_DIR/b")"
# An absent log trivially contains no errors, so "no error in the log" is only meaningful
# once both nodes have actually written one.
if ! ah_logs_present "$RUN_DIR/a" || ! ah_logs_present "$RUN_DIR/b"; then
  ah_check fork.no_archive_failstop FAIL \
    "one or both node logs are missing/empty, so the absence of archive errors proves nothing"
elif [ -z "$FAILSTOP" ] && [ -z "$ARCHIVE_ERRORS" ]; then
  ah_check fork.no_archive_failstop PASS "no ArchiveException or fail-stop breadcrumb in either node log"
else
  ah_check fork.no_archive_failstop FAIL \
    "archive error during a reorg: $(printf '%s' "$FAILSTOP$ARCHIVE_ERRORS" | head -1)"
fi

# --------------------------------------------------------------------------- phase 8: history oracles

ah_log "phase 8: re-read history through the reorg"
POST_W1="$(ah_eth_balance "$A_JSONRPC" "$ETH_W1" "$H_PRE_TAG")"
POST_P1="$(ah_eth_balance "$A_JSONRPC" "$ETH_P1" "$H_PRE_TAG")"
# The equality must be between REAL served values on both sides. Two identical error
# strings ("ERR:transport" before and after, say) satisfy `=` while proving nothing about
# history stability, so the shape of the pre- and post-values is checked first.
if ! is_value "$PRE_W1" || ! is_value "$PRE_P1"; then
  ah_check fork.prefork_history_stable FAIL \
    "the pre-fork oracle at height $H_PRE is not a served value (W1=$PRE_W1 P1=$PRE_P1); there is nothing to compare against"
elif ! is_value "$POST_W1" || ! is_value "$POST_P1"; then
  ah_check fork.prefork_history_stable FAIL \
    "height $H_PRE stopped serving after the reorg (W1 $PRE_W1 -> $POST_W1, P1 $PRE_P1 -> $POST_P1)"
elif [ "$POST_W1" = "$PRE_W1" ] && [ "$POST_P1" = "$PRE_P1" ]; then
  ah_check fork.prefork_history_stable PASS \
    "height $H_PRE unchanged across the reorg (W1=$POST_W1 P1=$POST_P1)"
else
  ah_check fork.prefork_history_stable FAIL \
    "height $H_PRE changed: W1 $PRE_W1 -> $POST_W1, P1 $PRE_P1 -> $POST_P1"
fi

B_W1="$(ah_eth_balance "$B_JSONRPC" "$ETH_W1" "$H_PRE_TAG")"
POST_BLOCKID="$(ah_block_id "$A_HTTP" "$H_PRE")"
if [ "$B_W1" = "$POST_W1" ] && [ "$POST_BLOCKID" = "$PRE_BLOCKID" ]; then
  ah_check fork.prefork_cross_node PASS "A and B agree at height $H_PRE and the block id is unchanged"
elif [ "$B_W1" = "ERR:transport" ]; then
  ah_check fork.prefork_cross_node SKIP "node B did not answer the cross-check query"
else
  ah_check fork.prefork_cross_node FAIL \
    "A=$POST_W1 B=$B_W1 blockID $PRE_BLOCKID -> $POST_BLOCKID"
fi

FAR_HEIGHT=$(( $(ah_head_num "$A_HTTP") + 100000 ))
FAR="$(ah_eth_balance "$A_JSONRPC" "$ETH_P1" "$(hex_height "$FAR_HEIGHT")")"
if is_value "$FAR"; then
  ah_check fork.beyond_range_fail_closed FAIL "height $FAR_HEIGHT served $FAR"
elif is_failclosed "$FAR"; then
  ah_check fork.beyond_range_fail_closed PASS "height $FAR_HEIGHT failed closed: $FAR"
else
  ah_check fork.beyond_range_fail_closed FAIL \
    "node did not answer the beyond-range query ($FAR); fail-closed behaviour was never observed"
fi

# Re-captured window: every height the orphan branch also claimed must now answer with canonical
# state and must agree with the independent node.
RECAPTURE_LO=$(( COMMON_HEAD + 1 ))
RECAPTURE_HI="$A_FROZEN_HEAD"
if [ "$RECAPTURE_HI" -lt "$RECAPTURE_LO" ]; then
  RECAPTURE_HI="$RECAPTURE_LO"
fi
if wait_published "$A_JSONRPC" "$RECAPTURE_HI" "$PUBLISH_TIMEOUT"; then
  MISMATCH=""
  ORPHAN_LEAK=""
  COMPARED=0
  UNANSWERED=""
  ORPHAN_PROBED=0
  H="$RECAPTURE_LO"
  while [ "$H" -le "$RECAPTURE_HI" ]; do
    TAG="$(hex_height "$H")"
    VA="$(ah_eth_balance "$A_JSONRPC" "$ETH_P1" "$TAG")"
    VB="$(ah_eth_balance "$B_JSONRPC" "$ETH_P1" "$TAG")"
    # Only a comparison of two REAL values proves agreement. Two matching error strings
    # (both nodes unreachable, say) would otherwise satisfy `=` at every height and make
    # the whole cross-node check pass without a single value ever being served.
    if is_value "$VA" && is_value "$VB"; then
      COMPARED=$(( COMPARED + 1 ))
      [ "$VA" = "$VB" ] || MISMATCH="$MISMATCH $H(A=$VA,B=$VB)"
    else
      UNANSWERED="$UNANSWERED $H(A=$VA,B=$VB)"
    fi
    if [ "$ORPHAN_ORACLE" -eq 1 ]; then
      VZ="$(ah_eth_balance "$A_JSONRPC" "$ETH_ORPHAN" "$TAG")"
      if is_value "$VZ"; then
        ORPHAN_PROBED=$(( ORPHAN_PROBED + 1 ))
        [ "$VZ" = "0x0" ] || ORPHAN_LEAK="$ORPHAN_LEAK $H=$VZ"
      elif is_failclosed "$VZ"; then
        ORPHAN_PROBED=$(( ORPHAN_PROBED + 1 ))
      fi
    fi
    H=$(( H + 1 ))
  done
  if [ "$COMPARED" -eq 0 ]; then
    ah_check fork.recaptured_cross_node FAIL \
      "no re-captured height produced a value on BOTH nodes, so agreement was never tested:$UNANSWERED"
  elif [ -n "$MISMATCH" ]; then
    ah_check fork.recaptured_cross_node FAIL "re-captured heights disagree:$MISMATCH"
  else
    ah_check fork.recaptured_cross_node PASS \
      "$COMPARED height(s) in $RECAPTURE_LO..$RECAPTURE_HI agree between the re-captured node and the independent node${UNANSWERED:+ (unanswered:$UNANSWERED)}"
  fi
  if [ "$ORPHAN_ORACLE" -ne 1 ]; then
    ah_check fork.orphan_content_not_served SKIP "no orphan-only value was established"
  elif [ "$ORPHAN_PROBED" -eq 0 ]; then
    ah_check fork.orphan_content_not_served FAIL \
      "the orphan account was never successfully probed at any re-captured height, so a leak could not have been detected"
  elif [ -z "$ORPHAN_LEAK" ]; then
    ah_check fork.orphan_content_not_served PASS \
      "none of the $ORPHAN_PROBED probed heights in $RECAPTURE_LO..$RECAPTURE_HI serves the orphan-branch value"
  else
    ah_check fork.orphan_content_not_served FAIL "orphan-branch state served after the reorg:$ORPHAN_LEAK"
  fi
else
  ah_check fork.recaptured_cross_node SKIP \
    "height $RECAPTURE_HI was never published within ${PUBLISH_TIMEOUT}s after the reorg"
  ah_check fork.orphan_content_not_served SKIP "re-captured heights were not published in time"
fi

if [ "$ORPHAN_ORACLE" -ne 1 ]; then
  ah_check fork.orphan_state_dropped SKIP "no orphan-only value was established"
else
  FINAL_ORPHAN_A="$(ah_eth_balance "$A_JSONRPC" "$ETH_ORPHAN" latest)"
  FINAL_ORPHAN_B="$(ah_eth_balance "$B_JSONRPC" "$ETH_ORPHAN" latest)"
  if [ "$FINAL_ORPHAN_A" = "$FINAL_ORPHAN_B" ] && [ "$FINAL_ORPHAN_A" = "0x0" ]; then
    ah_check fork.orphan_state_dropped PASS \
      "the TAPOS-pinned orphan transfer is gone from the canonical chain on both nodes"
  else
    ah_check fork.orphan_state_dropped SKIP \
      "orphan transfer survived the reorg (A=$FINAL_ORPHAN_A B=$FINAL_ORPHAN_B); it was replayable, so it is not an orphan-only oracle"
  fi
fi

# --------------------------------------------------------------------------- phase 9: offline probe

ah_log "phase 9: stop the nodes and probe the archive offline"
ah_stop_node "$B_PID" 120; B_PID=""
ah_stop_node "$A_PID" 120
A_EXIT="${AH_LAST_EXIT:-unknown}"
A_PID=""
stop_relay

PROBE_JSON="$(ah_probe_archive "$RUN_DIR/a/data/database/archive/unified")"
PROBE_RC=$?
printf '%s\n' "$PROBE_JSON" >"$RUN_DIR/a/archive-probe.json"

# The probe's own output must be usable before any of its fields can be believed. A missing
# or unparsable JSON document yields empty strings from jq, and "empty gaps list" would then
# read as a clean archive -- a vacuous pass produced by a broken probe rather than a healthy
# database.
if ! printf '%s' "$PROBE_JSON" | jq -e '.opened == true' >/dev/null 2>&1; then
  ah_check fork.txnum_gap_free FAIL \
    "archive database could not be opened or the probe produced no usable JSON (rc=$PROBE_RC): $(printf '%s' "$PROBE_JSON" | jq -r '.openError // "no output"' 2>/dev/null || printf 'no output')"
  ah_check fork.no_stale_journal FAIL "no usable probe output (rc=$PROBE_RC)"
  ah_check fork.probe_no_repair_marker FAIL "no usable probe output (rc=$PROBE_RC)"
elif [ "$PROBE_RC" -eq 3 ]; then
  ah_check fork.txnum_gap_free FAIL "archive database could not be opened: $(printf '%s' "$PROBE_JSON" | jq -r '.openError // "?"')"
  ah_check fork.no_stale_journal FAIL "archive database could not be opened"
  ah_check fork.probe_no_repair_marker FAIL "archive database could not be opened"
else
  GAPS="$(printf '%s' "$PROBE_JSON" | jq -r '((.blockGaps // []) + (.txNumGaps // []) + (.spanViolations // [])) | join("; ")')"
  RANGES="$(printf '%s' "$PROBE_JSON" | jq -r '.rangeCount // 0')"
  SOURCES="$(printf '%s' "$PROBE_JSON" | jq -c '.sourceHistogram // {}')"
  STALE="$(printf '%s' "$PROBE_JSON" | jq -r '(.staleInFlightBlocks // []) | join(",")')"
  MARKER="$(printf '%s' "$PROBE_JSON" | jq -r '.repairRequired // false')"
  if [ -z "$GAPS" ] && [ "$RANGES" -gt 0 ] 2>/dev/null; then
    ah_check fork.txnum_gap_free PASS \
      "$RANGES contiguous ranges, blocks $(printf '%s' "$PROBE_JSON" | jq -r '.minBlock')..$(printf '%s' "$PROBE_JSON" | jq -r '.maxBlock'), sources $SOURCES"
  else
    ah_check fork.txnum_gap_free FAIL "rangeCount=$RANGES gaps: ${GAPS:-<none but no ranges>}"
  fi
  # Journal rows for blocks that are not published yet are expected to survive shutdown; a row at or
  # below the published head is not, because publication deletes it.  Such a row is exactly what an
  # orphan journal that was never rolled back looks like on disk.
  if [ -z "$STALE" ]; then
    ah_check fork.no_stale_journal PASS \
      "no journal row at or below the published head (in-flight $(printf '%s' "$PROBE_JSON" | jq -r '.inFlightMinBlock')..$(printf '%s' "$PROBE_JSON" | jq -r '.inFlightMaxBlock') is above it)"
  else
    ah_check fork.no_stale_journal FAIL \
      "orphan journal row(s) survived at already-published height(s): $STALE"
  fi
  if [ "$MARKER" = "false" ]; then
    ah_check fork.probe_no_repair_marker PASS "no repair-required META key on disk"
  else
    ah_check fork.probe_no_repair_marker FAIL \
      "repair-required is set: $(printf '%s' "$PROBE_JSON" | jq -r '.repairReason // "?"')"
  fi
fi
ah_check fork.node_a_shutdown INFO "node A exited with status $A_EXIT after the reorg"

# --------------------------------------------------------------------------- phase 10: optional restart

if [ "$FORK_ASSERT_RESTART" = "1" ]; then
  ah_log "phase 10: restart node A after the reorg (identity.initialize=false)"
  ah_conf_reset
  AH_CONF_P2P_PORT=$A_P2P; AH_CONF_HTTP_PORT=$A_HTTP; AH_CONF_RPC_PORT=$A_RPC
  AH_CONF_JSONRPC_PORT=$A_JSONRPC; AH_CONF_METRICS_PORT=$A_METRICS
  AH_CONF_WITNESS_KEY=$KEY_W1
  AH_CONF_GENESIS_WITNESSES="$GENESIS_WITNESSES"
  AH_CONF_ACTIVE_LIST='[]'
  AH_CONF_IDENTITY_INIT=false
  AH_CONF_TRX_REFERENCE=head
  ah_write_node_conf "$RUN_DIR/a/node-restart.conf"

  ah_start_node "$RUN_DIR/a-restart" "$RUN_DIR/a/node-restart.conf" "$RUN_DIR/a/data"
  A_PID=$AH_LAST_PID
  ah_wait_ready_or_exit "$A_PID" "$A_HTTP" 180
  RESTART_RC=$?
  if [ "$RESTART_RC" -eq 0 ]; then
    ah_check fork.restart_after_reorg PASS "node A restarted cleanly at head $(ah_head_num "$A_HTTP")"
    ah_stop_node "$A_PID" 120; A_PID=""
  elif [ "$RESTART_RC" -eq 1 ]; then
    RESTART_EXIT="$AH_LAST_EXIT"; A_PID=""
    if [ "$RESTART_EXIT" -eq 1 ] || [ "$RESTART_EXIT" -eq 70 ]; then
      ah_check fork.restart_after_reorg PASS \
        "node A refused to start with an explicit archive fail-stop (exit $RESTART_EXIT): $(ah_failstop_evidence "$RUN_DIR/a-restart" | head -1)"
    else
      ah_check fork.restart_after_reorg FAIL "node A exited $RESTART_EXIT without an archive fail-stop code"
    fi
  else
    ah_check fork.restart_after_reorg FAIL \
      "HUNG: node A is alive, never served HTTP, and never exited after archive startup validation; $(ah_archive_error_summary "$RUN_DIR/a-restart")"
    kill -KILL "$A_PID" 2>/dev/null; A_PID=""
  fi
fi

ah_finish FORK_E2E "depth=${ERASE_COUNT:-0}" "switches=${SWITCH_COUNT:-0}"
