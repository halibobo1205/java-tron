#!/usr/bin/env bash
# Bounded, isolated real-TVM trace oracles.
# Uses a prebuilt FullNode.jar by default. No solc, public peers, or production data.
# Default: one local process signing for all 27 genesis SRs. Count may be overridden.
# Wire contract: HistoricalDebugTraceSupport, ArchiveStructLogCollector, CallTraceFrame.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)/lib.sh"

HS_SKIP_BUILD="${HS_SKIP_BUILD:-1}"
HS_KEEP_WORKDIR=1
HS_HTTP_TIMEOUT=15
HS_JSONRPC_TIMEOUT=40
HS_CFG_WITNESS_COUNT="${HS_CFG_WITNESS_COUNT:-27}"
HS_CFG_LOCAL_WITNESS_FIRST=1
HS_CFG_LOCAL_WITNESS_LAST="$HS_CFG_WITNESS_COUNT"
HS_CFG_ACTIVE_PEERS=''
HS_CFG_ARCHIVE_ENABLE=true
HS_CFG_ARCHIVE_DEBUG=true
HS_CFG_ARCHIVE_IDENTITY_INIT=true
HS_CFG_ARCHIVE_DB_DIR=archive
unset HS_CFG_WITNESS_KEY HS_CFG_GENESIS_WITNESSES HS_JDWP_PORT HS_JDWP_SUSPEND
hs_init debug-trace
shasum -a 256 "$HS_JAR" "$HS_HARNESS_DIR/scenario-debug-trace.sh" >"$HS_RUN_DIR/artifacts.sha256"

# Returns the OLD slot0 word followed by byte ab; nonempty calldata sets slot0.
# 00:PUSH1(0)/SLOAD -> MSTORE(0); 06:CALLDATASIZE/ISZERO -> JUMPI(0x11);
# 0b:CALLDATALOAD(0) -> SSTORE(0); 11:JUMPDEST; 16:MSTORE8(32,ab); 1b:RETURN(0,33).
runtime=60005460005236156011576000356000555b60ab60205360216000f3
zero="$(printf '%064x' 0)"; a="$(printf '%064x' 111)"; b="$(printf '%064x' 222)"
byte_word="$(printf '%064x' 171)"; offset_word="$(printf '%064x' 32)"
memory_tail="ab${zero:2}"

dt_rpc() {
  local label="$1" method="$2" params="$3"
  DT_RESPONSE="$HS_RUN_DIR/$label.response.json"
  jq -nc --arg method "$method" --argjson params "$params" \
    '{jsonrpc:"2.0",id:1,method:$method,params:$params}' >"$HS_RUN_DIR/$label.request.json"
  hs_jsonrpc "$node" "$method" "$params" >"$DT_RESPONSE" \
    || hs_abort "$label: transport failure"
  dt_expect '.jsonrpc == "2.0" and .id == 1 and (has("result") != has("error"))' "$label envelope"
}

dt_expect() {
  jq -e "$1" "$DT_RESPONSE" >/dev/null || hs_abort "$2: see $DT_RESPONSE"
  hs_pass "$2"
}

dt_value() {
  dt_rpc "$1" "$2" "$3"
  dt_expect "has(\"error\") | not" "$1 no RPC error"
  dt_expect ".result == \"$4\"" "$1 exact value"
}

dt_receipt() {
  DT_RESPONSE="$HS_RUN_DIR/$1.receipt.json"
  hs_tx_wait_receipt "$node" "$HS_LAST_TXID" 120 >"$DT_RESPONSE" \
    || hs_abort "$1: receipt unavailable"
  dt_expect ".id == \"$HS_LAST_TXID\" and .receipt.result == \"SUCCESS\"
    and (.blockNumber | type == \"number\") and .blockNumber == $HS_LAST_BLOCK
    and .contractResult == [\"$2\"]" "$1 canonical receipt/output"
}

# Both tracers must succeed. Expected output/storage comes from chosen bytecode,
# with receipts independently checked before any historical query is made.
dt_trace() {
  local label="$1" method="$2" base="$3" to="$4" input="$5" old="$6" mode="$7"
  local opts params output="0x${old}ab" ops tracer
  [ "$mode" != destroy ] || output=0x
  for tracer in struct call; do
    opts='{"enableMemory":true,"disableStack":false,"disableStorage":false,"limit":128}'
    [ "$tracer" != call ] || opts='{"tracer":"callTracer"}'
    params="$(jq -nc --argjson base "$base" --argjson opts "$opts" '$base + [$opts]')"
    dt_rpc "$label.$tracer" "$method" "$params"
    dt_expect 'has("error") | not' "$label.$tracer no RPC error"
    if [ "$tracer" = struct ]; then
      dt_expect ".result | .failed == false and .returnValue == \"$output\"
        and (.gas | type == \"number\" and . > 0)
        and (.structLogs | type == \"array\" and length > 0 and length < 128)
        and all(.structLogs[]; .depth == 1 and (has(\"error\") | not)
          and (.pc | type == \"number\") and (.gas | type == \"number\")
          and (.gasCost | type == \"number\") and (.stack | type == \"array\"))" "$label structured shape"
      if [ "$mode" = destroy ]; then
        dt_expect '.result.structLogs | map(.op) == ["CALLER","SELFDESTRUCT"]' "$label destruction opcodes"
      else
        ops='["PUSH1","SLOAD","PUSH1","MSTORE","CALLDATASIZE","ISZERO","PUSH1","JUMPI"]'
        [ "$mode" != write ] || ops="${ops%]} ,\"PUSH1\",\"CALLDATALOAD\",\"PUSH1\",\"SSTORE\"]"
        dt_expect ".result.structLogs | map(.op) == ($ops +
          [\"JUMPDEST\",\"PUSH1\",\"PUSH1\",\"MSTORE8\",\"PUSH1\",\"PUSH1\",\"RETURN\"])" "$label opcode sequence"
        dt_expect "[.result.structLogs[] | select(.op == \"SLOAD\") | .storage] ==
          [{\"0x$zero\":\"0x$old\"}]" "$label old slot0 before execution"
        dt_expect "[.result.structLogs[] | select(.op == \"MSTORE8\") | {pc,stack,memory}] ==
          [{pc:22,stack:[\"0x$byte_word\",\"0x$offset_word\"],memory:[\"0x$old\"]}]" "$label pre-MSTORE8 state"
        dt_expect ".result.structLogs[-1] | .op == \"RETURN\" and .pc == 27
          and .memory == [\"0x$old\",\"0x$memory_tail\"]" "$label full memory after MSTORE8"
        if [ "$mode" = write ]; then
          dt_expect "[.result.structLogs[] | select(.op == \"SSTORE\") | .storage] ==
            [{\"0x$zero\":\"$input\"}]" "$label pending SSTORE value"
        fi
      fi
    else
      dt_expect ".result | .type == \"CALL\" and .from == \"$owner\" and .to == \"$to\"
        and .input == \"$input\" and .value == \"0x0\" and (has(\"error\") | not)
        and (.gas | test(\"^0x[0-9a-f]+$\")) and (.gasUsed | test(\"^0x[0-9a-f]+$\"))" "$label call frame"
      if [ "$mode" = destroy ]; then
        dt_expect ".result | (has(\"output\") | not) and (.calls | length == 1)
          and (.calls[0] | .type == \"SELFDESTRUCT\" and .from == \"$to\"
            and .to == \"$owner\" and .value == \"0x0\" and (has(\"error\") | not))" "$label destruction frame"
      else
        dt_expect ".result | .output == \"$output\" and (has(\"calls\") | not)" "$label call output"
      fi
    fi
  done
}

dt_replay() {
  local prefix="$1" h value tag base
  for h in "$((ha - 1))" "$ha" "$hb"; do
    value="$zero"; [ "$h" != "$ha" ] || value="$a"; [ "$h" != "$hb" ] || value="$b"
    tag="$(hs_dec_to_hexblock "$h")"
    base="[{\"from\":\"$owner\",\"to\":\"$contract_eth\",\"data\":\"0x\"},\"$tag\"]"
    dt_value "$prefix.slot.$h" eth_getStorageAt "[\"$contract_eth\",\"0x0\",\"$tag\"]" "0x$value"
    dt_trace "$prefix.call.$h" debug_traceCall "$base" "$contract_eth" 0x "$value" read
  done
  dt_trace "$prefix.tx.a" debug_traceTransaction "[\"0x$ta\"]" "$contract_eth" "0x$a" "$zero" write
  dt_trace "$prefix.tx.b" debug_traceTransaction "[\"0x$tb\"]" "$contract_eth" "0x$b" "$a" write
  dt_trace "$prefix.tx.destroy" debug_traceTransaction "[\"0x$td\"]" "$destroy_eth" "0x$zero" '' destroy
  dt_value "$prefix.code.beforeDestroy" eth_getCode \
    "[\"$destroy_eth\",\"$(hs_dec_to_hexblock "$((hd - 1))")\"]" 0x33ff
  dt_value "$prefix.code.afterDestroy" eth_getCode \
    "[\"$destroy_eth\",\"$(hs_dec_to_hexblock "$hd")\"]" 0x
  # A fallback to live is observable: latest is 222, whereas TX_BEFORE(b) is 111.
  dt_value "$prefix.live.unchanged" eth_getStorageAt "[\"$contract_eth\",\"0x0\",\"latest\"]" "0x$b"
  dt_value "$prefix.live.stillDeleted" eth_getCode "[\"$destroy_eth\",\"latest\"]" 0x
  for tag in latest 0xffffff; do
    dt_rpc "$prefix.reject.$tag" debug_traceCall \
      "[{\"from\":\"$owner\",\"to\":\"$contract_eth\",\"data\":\"0x\"},\"$tag\"]"
    if [ "$tag" = latest ]; then
      dt_expect '.error.code == -32602 and (.error.message | contains("committed historical blocks"))' "$prefix reject latest"
    else
      dt_expect '.error.code == -32000 and (.error.message | contains("archive history unavailable"))' "$prefix reject unavailable height"
    fi
  done
}

hs_step "start fresh debug-enabled 27-SR chain (count override supported)"
node="$(hs_new_node trace 0)"
owner="$(hs_eth_of_priv "$HS_KEY_ZION")"
hs_node_start "$node"
hs_node_wait_ready "$node" 240
hs_wait_height "$node" 3 180 >/dev/null
hs_assert_eq 0 "$(hs_peer_count "$node")" "isolated node has no peers"

hs_step "deploy fixed bytecode and record two separate storage transitions"
HS_CONTRACT_DEPLOY_HEX="601c80600b6000396000f3$runtime"
hs_contract_deploy "$node" "$HS_KEY_ZION"
dt_receipt deploy "$runtime"
contract="$HS_LAST_CONTRACT"; contract_eth="$(hs_eth_of_hex41 "$contract")"
dt_value live.code eth_getCode "[\"$contract_eth\",\"latest\"]" "0x$runtime"
hs_wait_blocks "$node" 1 180 >/dev/null
hs_contract_set "$node" "$HS_KEY_ZION" "$contract" "$a"
ta="$HS_LAST_TXID"; ha="$HS_LAST_BLOCK"; dt_receipt set-a "${zero}ab"
dt_value live.slot.a eth_getStorageAt "[\"$contract_eth\",\"0x0\",\"latest\"]" "0x$a"
hs_wait_height "$node" "$((ha + 1))" 180 >/dev/null
hs_contract_set "$node" "$HS_KEY_ZION" "$contract" "$b"
tb="$HS_LAST_TXID"; hb="$HS_LAST_BLOCK"; dt_receipt set-b "${a}ab"
[ "$hb" -gt "$ha" ] || hs_abort "storage writes must occupy different block heights"
dt_value live.slot.b eth_getStorageAt "[\"$contract_eth\",\"0x0\",\"latest\"]" "0x$b"

hs_step "deploy and destroy a zero-balance, no-assets contract"
HS_CONTRACT_DEPLOY_HEX=600280600b6000396000f333ff
hs_contract_deploy "$node" "$HS_KEY_ZION"
dt_receipt deploy-destroy 33ff
destroy="$HS_LAST_CONTRACT"; destroy_eth="$(hs_eth_of_hex41 "$destroy")"
dt_value live.destroy.code eth_getCode "[\"$destroy_eth\",\"latest\"]" 0x33ff
dt_value live.destroy.balance eth_getBalance "[\"$destroy_eth\",\"latest\"]" 0x0
hs_wait_blocks "$node" 1 180 >/dev/null
hs_contract_set "$node" "$HS_KEY_ZION" "$destroy" "$zero"
td="$HS_LAST_TXID"; hd="$HS_LAST_BLOCK"; dt_receipt destroy ''
dt_value live.destroy.deleted eth_getCode "[\"$destroy_eth\",\"latest\"]" 0x
jq -n --arg runtime "$runtime" --arg ta "$ta" --arg tb "$tb" --arg td "$td" \
  --argjson ha "$ha" --argjson hb "$hb" --argjson hd "$hd" \
  '{runtime:$runtime,a:{tx:$ta,height:$ha},b:{tx:$tb,height:$hb},destroy:{tx:$td,height:$hd}}' \
  >"$HS_RUN_DIR/coordinates.json"

hs_step "wait for finality/publication, then validate both trace surfaces"
hs_wait_solidified "$node" "$hd" 300 >/dev/null
hs_wait_hist_available "$node" "$owner" "$hd" 300
dt_replay before-restart
hs_assert_repair_not_required "$node" "before restart"
hs_node_stop "$node" 120 || hs_abort "clean shutdown timed out"
hs_assert_clean_stop "$node" "first SIGTERM"
verdict="$(hs_node_restart "$node" 300)"
hs_assert_startup_verdict "$node" READY "$verdict" "clean restart"
hs_wait_hist_available "$node" "$owner" "$hd" 300
dt_replay after-restart
hs_assert_repair_not_required "$node" "after repeated traces"
hs_assert_eq 0 "$(hs_metric_work_int "$node" publish_failures)" "no publication failures"
hs_node_stop "$node" 120 || hs_abort "final shutdown timed out"
hs_assert_clean_stop "$node" "final SIGTERM"
hs_finish DEBUG_TRACE_OK "witnesses=$HS_CFG_WITNESS_COUNT" "storageHeights=$ha,$hb" "destroyHeight=$hd"
