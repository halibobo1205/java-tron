#!/usr/bin/env bash
#
# scenario-smoke.sh -- foundation self-test AND the worked template for the
# fault scenarios (A fork/reorg, B SIGKILL matrix, C resource faults,
# D concurrency-under-fault).
#
# It exercises every lib.sh primitive those scenarios depend on:
#   jar reuse/build, key derivation, config materialization, node start,
#   the three-way readiness verdict, block production, a transfer, a contract
#   deploy plus SSTORE calls, live oracle capture, the archive publication
#   gate, historical eth_getBalance / eth_getStorageAt / eth_call, a
#   fail-closed shape, metrics, a clean stop, a restart, and post-restart
#   oracle replay.
#
# Marker: SMOKE_E2E_OK  /  SMOKE_E2E_FAIL
# Exit  : 0 pass, 1 product failure, 2 harness/environment error.
#
# Usage:
#   ./scenario-smoke.sh
#   HS_KEEP_WORKDIR=1 ./scenario-smoke.sh
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)/lib.sh"

hs_init smoke

hs_step "materialize a single-SR archive node"
node="$(hs_new_node a 0)"

hs_step "start and wait for readiness"
hs_node_start "$node"
hs_node_wait_ready "$node" 240

hs_step "wait for block production"
hs_wait_height "$node" 3 180 >/dev/null
hs_assert_repair_not_required "$node" "fresh chain"

zion_eth="$(hs_eth_of_priv "$HS_KEY_ZION")"
sun_eth="$(hs_eth_of_priv "$HS_KEY_SUN")"
hs_log "zion=$zion_eth sun=$sun_eth"

hs_step "submit a transfer"
hs_tx_transfer_confirmed "$node" "$HS_KEY_ZION" "$HS_ADDR_SUN" 1000000
xfer_block="$HS_LAST_BLOCK"

hs_step "capture live oracles at the current head"
hs_oracle_capture "$node" zion_balance balance "$zion_eth"
hs_oracle_capture "$node" sun_balance balance "$sun_eth"

hs_step "deploy the harness storage contract"
hs_contract_deploy "$node" "$HS_KEY_ZION"
contract="$HS_LAST_CONTRACT"
contract_eth="$(hs_eth_of_hex41 "$contract")"

hs_step "drive slot 0 through 0 -> 111 -> 222"
value_a="000000000000000000000000000000000000000000000000000000000000006f"  # 111
value_b="00000000000000000000000000000000000000000000000000000000000000de"  # 222

hs_contract_set "$node" "$HS_KEY_ZION" "$contract" "$value_a"
set_a_block="$HS_LAST_BLOCK"
hs_assert_eq "0x$value_a" "$(hs_live_storage_at "$node" "$contract_eth" 0x0)" \
  "live slot0 after set(111)"
hs_oracle_capture "$node" slot0_after_111 storage "$contract_eth" 0x0

hs_contract_set "$node" "$HS_KEY_ZION" "$contract" "$value_b"
set_b_block="$HS_LAST_BLOCK"
hs_assert_eq "0x$value_b" "$(hs_live_storage_at "$node" "$contract_eth" 0x0)" \
  "live slot0 after set(222)"
hs_oracle_capture "$node" slot0_after_222 storage "$contract_eth" 0x0
hs_oracle_capture "$node" contract_code code "$contract_eth"

hs_log "set(111) in block $set_a_block; set(222) in block $set_b_block"

hs_step "let the archive publish past the last oracle height"
head_now="$(hs_head_num "$node")"
hs_wait_height "$node" "$((head_now + 3))" 180 >/dev/null
hs_wait_hist_available "$node" "$zion_eth" "$head_now" 300

hs_step "historical reads through the archive"
hs_assert_ne "0x0" "$(hs_hist_balance "$node" "$zion_eth" "$xfer_block")" \
  "archive eth_getBalance(zion) at the transfer height"
hs_assert_eq "0x$value_a" "$(hs_hist_storage_at "$node" "$contract_eth" 0x0 "$set_a_block")" \
  "archive eth_getStorageAt slot0 at the set(111) height"
hs_assert_eq "0x$value_b" "$(hs_hist_storage_at "$node" "$contract_eth" 0x0 "$set_b_block")" \
  "archive eth_getStorageAt slot0 at the set(222) height"
hs_assert_eq "0x$value_a" "$(hs_hist_contract_get "$node" "$contract" "$set_a_block")" \
  "archive eth_call get() at the set(111) height"

hs_step "fail-closed shape"
hs_assert_hist_fails_closed "$node" eth_getBalance \
  "[\"$zion_eth\",\"0xffff\"]" "-32000" "archive history unavailable" \
  "query far above the published head"

hs_step "metrics snapshot"
hs_metrics_summary "$node"
hs_assert_repair_not_required "$node" "before the clean stop"
hs_assert_eq "0" "$(hs_metric_work_int "$node" publish_failures)" "publish_failures"

hs_step "clean stop"
if ! hs_node_stop "$node" 120; then
  hs_abort "the node ignored SIGTERM (shutdown brick)"
fi
hs_assert_clean_stop "$node" "SIGTERM shutdown"

hs_step "restart and replay every oracle through the archive"
verdict="$(hs_node_restart "$node" 300)"
hs_assert_startup_verdict "$node" READY "$verdict" "restart after a clean stop"
hs_assert_repair_not_required "$node" "after restart"

hs_oracle_replay "$node"
hs_assert_eq "5" "$HS_ORACLE_REPLAYED" "oracle count replayed after restart"

hs_finish SMOKE_E2E_OK \
  "contract=$contract" \
  "oracles=$HS_ORACLE_REPLAYED" \
  "finalHeight=$(hs_head_num "$node")"
