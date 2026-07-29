#!/usr/bin/env bash
#
# Exercise a 27-SR chain with one archive SR catching up under
# storage.snapshot.maxFlushCount > 1. The target is killed after the batched
# checkpoint is durable but before SnapshotManager.refresh() applies it.
#
# The scenario proves:
#   * genesis has 27 SRs while the catch-up node owns exactly one SR key;
#   * the target is behind the source when the kill lands;
#   * JDWP observes flushCount >= maxFlushCount > 1 in SnapshotManager.flush();
#   * restart preserves the canonical chain and historical state values;
#   * the unified archive has no range/txNum gaps, stale journal, or repair marker.
#
# Environment overrides:
#   CFK_SOURCE_HEIGHT       backlog height before target starts (default 50)
#   CFK_MAX_FLUSH_COUNT     configured batch size (default 5, must be >1)
#   CFK_BP_TIMEOUT          seconds to wait for the flush breakpoint (default 300)
#   CFK_JDWP_PORT           debugger port (default "auto": the target's own +5 port)
#   CFK_CATCHUP_TIMEOUT     restart/catch-up timeout (default 360)
#   HS_FORCE_BUILD=1        rebuild FullNode.jar
#   HS_KEEP_WORKDIR=1       retain all node data and transcripts

set -uo pipefail

CFK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
# shellcheck source=lib.sh disable=SC1091
. "$CFK_DIR/lib.sh"
# shellcheck source=anchor.sh disable=SC1091
. "$CFK_DIR/anchor.sh"

CFK_SOURCE_HEIGHT="${CFK_SOURCE_HEIGHT:-50}"
CFK_MAX_FLUSH_COUNT="${CFK_MAX_FLUSH_COUNT:-5}"
CFK_BP_TIMEOUT="${CFK_BP_TIMEOUT:-300}"
CFK_JDWP_PORT="${CFK_JDWP_PORT:-auto}"
CFK_CATCHUP_TIMEOUT="${CFK_CATCHUP_TIMEOUT:-360}"

cfk_positive_int() {
  case "$2" in
    ''|*[!0-9]*) hs_die "$1 must be a positive integer, got '$2'" ;;
  esac
  [ "$2" -gt 0 ] || hs_die "$1 must be positive, got '$2'"
}

cfk_local_witness_count() {
  awk '
    /^localwitness[[:space:]]*=/ { inside = 1; next }
    inside && /^[[:space:]]*\]/ { inside = 0; next }
    inside && /^[[:space:]]*[0-9a-fA-F]+,?[[:space:]]*$/ { count++ }
    END { print count + 0 }
  ' "$1/node.conf"
}

# The flush anchor is a SEMANTIC descriptor (`cfk.flush` in anchor.sh), not a line number and not
# an awk scan pinned to one signature spelling: "the refresh() that follows the durable
# createCheckpoint(), inside SnapshotManager.flush()". anchor.sh resolves it against the current
# source, cross-checks it against the current jar's LineNumberTable, and returns the breakpoint in
# JAR coordinates, so an edit above flush() does not move it. An ambiguous or missing descriptor
# is a hard error there, which becomes hs_die here -- this scenario has no probabilistic fallback
# and must never pretend it observed a batch it could not stop inside.

# cfk_break_inspect_kill <node> <class:jarline> <expected-max> <timeout> <owners-csv>
#
# Uses a FIFO so commands can be sent after the breakpoint. This gives direct
# runtime evidence that the effective SnapshotManager batch is greater than 1;
# config-file evidence alone is insufficient because Manager temporarily
# forces maxFlushCount back to 1 when processing near-head blocks.
# <owners-csv> is the set of jar methods that own <jarline>, straight from the anchor resolver.
CFK_OBSERVED_FLUSH_COUNT=""
CFK_OBSERVED_MAX_FLUSH_COUNT=""
CFK_JDB_LOG=""
cfk_break_inspect_kill() {
  local node_dir="$1" location="$2" expected_max="$3" timeout="$4" owners="$5"
  local jdb_bin fifo jdb_pid deadline hit_why jdwp_port
  jdb_bin="$(command -v jdb 2>/dev/null || true)"
  [ -n "$jdb_bin" ] || hs_die "jdb is required for deterministic batch-flush injection"
  # The port hs_node_start actually used for THIS node, never a scenario-wide constant.
  jdwp_port="$(hs_jdwp_port "$node_dir")" \
    || hs_die "target was not started under JDWP (no $node_dir/jdwp.port)"

  CFK_JDB_LOG="$(hs_jdb_log_path "$node_dir" "$location")"
  fifo="$node_dir/jdb-input.fifo"
  rm -f "$fifo"
  mkfifo "$fifo" || hs_die "cannot create jdb FIFO at $fifo"

  "$jdb_bin" -attach "127.0.0.1:${jdwp_port}" \
    <"$fifo" >"$CFK_JDB_LOG" 2>&1 &
  jdb_pid=$!
  exec 9>"$fifo"
  printf 'stop at %s\n' "$location" >&9
  printf 'cont\n' >&9

  deadline=$(( $(date +%s) + timeout ))
  while ! grep -q 'Breakpoint hit' "$CFK_JDB_LOG" 2>/dev/null; do
    if ! kill -0 "$jdb_pid" 2>/dev/null; then
      exec 9>&-
      rm -f "$fifo"
      hs_log "jdb exited before the breakpoint; transcript: $CFK_JDB_LOG"
      return 1
    fi
    if ! hs_node_alive "$node_dir"; then
      exec 9>&-
      kill "$jdb_pid" 2>/dev/null || true
      rm -f "$fifo"
      hs_log "target exited before the breakpoint: $(hs_node_exit_reason "$node_dir")"
      return 1
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      exec 9>&-
      kill "$jdb_pid" 2>/dev/null || true
      rm -f "$fifo"
      hs_log "flush breakpoint was not hit within ${timeout}s"
      return 1
    fi
    sleep 0.25
  done

  printf 'print this.flushCount\n' >&9
  printf 'print this.maxFlushCount\n' >&9
  deadline=$(( $(date +%s) + 15 ))
  while :; do
    CFK_OBSERVED_FLUSH_COUNT="$(
      sed -n 's/.*this\.flushCount[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' \
        "$CFK_JDB_LOG" | tail -1
    )"
    CFK_OBSERVED_MAX_FLUSH_COUNT="$(
      sed -n 's/.*this\.maxFlushCount[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' \
        "$CFK_JDB_LOG" | tail -1
    )"
    if [ -n "$CFK_OBSERVED_FLUSH_COUNT" ] \
        && [ -n "$CFK_OBSERVED_MAX_FLUSH_COUNT" ]; then
      break
    fi
    [ "$(date +%s)" -lt "$deadline" ] || break
    sleep 0.25
  done

  # RUNTIME anchor guard: require the method AND the line jdb reported to be the ones the
  # resolved descriptor asked for. `owners` comes from the jar's own line table, so a statement
  # that the compiler split into a lambda is still accepted while anything else is not.
  hit_why="$(hs_anchor_assert_hit "$CFK_JDB_LOG" "$owners" "${location##*:}")"
  if [ -n "$hit_why" ]; then
    exec 9>&-
    kill "$jdb_pid" 2>/dev/null || true
    rm -f "$fifo"
    hs_log "flush anchor (cfk.flush) did not fire where it was resolved: $hit_why"
    return 1
  fi

  if [ "$CFK_OBSERVED_MAX_FLUSH_COUNT" != "$expected_max" ] \
      || [ -z "$CFK_OBSERVED_FLUSH_COUNT" ] \
      || [ "$CFK_OBSERVED_FLUSH_COUNT" -lt "$expected_max" ] 2>/dev/null; then
    exec 9>&-
    kill "$jdb_pid" 2>/dev/null || true
    rm -f "$fifo"
    hs_log "unexpected runtime batch: flushCount=${CFK_OBSERVED_FLUSH_COUNT:-?}" \
      "maxFlushCount=${CFK_OBSERVED_MAX_FLUSH_COUNT:-?}"
    return 1
  fi

  hs_log "breakpoint hit with flushCount=$CFK_OBSERVED_FLUSH_COUNT" \
    "maxFlushCount=$CFK_OBSERVED_MAX_FLUSH_COUNT; sending SIGKILL"
  hs_node_kill9 "$node_dir"
  exec 9>&-
  kill "$jdb_pid" 2>/dev/null || true
  rm -f "$fifo"
  return 0
}

cfk_positive_int CFK_SOURCE_HEIGHT "$CFK_SOURCE_HEIGHT"
cfk_positive_int CFK_MAX_FLUSH_COUNT "$CFK_MAX_FLUSH_COUNT"
cfk_positive_int CFK_BP_TIMEOUT "$CFK_BP_TIMEOUT"
[ "$CFK_JDWP_PORT" = auto ] || cfk_positive_int CFK_JDWP_PORT "$CFK_JDWP_PORT"
cfk_positive_int CFK_CATCHUP_TIMEOUT "$CFK_CATCHUP_TIMEOUT"
[ "$CFK_SOURCE_HEIGHT" -ge 35 ] \
  || hs_die "CFK_SOURCE_HEIGHT must be >=35 so catch-up blocks are older than 60 seconds"
[ "$CFK_MAX_FLUSH_COUNT" -gt 1 ] \
  || hs_die "CFK_MAX_FLUSH_COUNT must be >1"

hs_init "catchup-batch-flush-kill"

hs_step "materializing a shared 27-SR genesis with a 26+1 key split"
export HS_CFG_WITNESS_COUNT=27
export HS_CFG_LOCAL_WITNESS_FIRST=1
export HS_CFG_LOCAL_WITNESS_LAST=26
export HS_CFG_MAX_FLUSH_COUNT=1
export HS_CFG_ARCHIVE_ENABLE=false
export HS_CFG_ARCHIVE_IDENTITY_INIT=false
export HS_CFG_ACTIVE_PEERS=""
SOURCE_NODE="$(hs_new_node source 0)"

export HS_CFG_LOCAL_WITNESS_FIRST=27
export HS_CFG_LOCAL_WITNESS_LAST=27
export HS_CFG_MAX_FLUSH_COUNT="$CFK_MAX_FLUSH_COUNT"
export HS_CFG_ARCHIVE_ENABLE=true
export HS_CFG_ARCHIVE_IDENTITY_INIT=true
export HS_CFG_ACTIVE_PEERS="127.0.0.1:$(hs_p2p_port "$SOURCE_NODE")"
TARGET_NODE="$(hs_new_node archive-sr27 1)"

hs_assert_eq "27" "$(grep -c 'url = \"http://' "$TARGET_NODE/node.conf")" \
  "target genesis witness count"
hs_assert_eq "26" "$(cfk_local_witness_count "$SOURCE_NODE")" \
  "source local witness count"
hs_assert_eq "1" "$(cfk_local_witness_count "$TARGET_NODE")" \
  "catch-up node local witness count"
hs_assert_contains "$(grep 'snapshot.maxFlushCount' "$TARGET_NODE/node.conf")" \
  "= $CFK_MAX_FLUSH_COUNT" "target maxFlushCount config"

hs_step "building a canonical backlog and recording changing-state oracles"
hs_node_start "$SOURCE_NODE"
hs_node_wait_ready "$SOURCE_NODE" 180
hs_wait_height "$SOURCE_NODE" 8 180 >/dev/null

ZION_ETH="$(hs_eth_of_priv "$HS_KEY_ZION")"
SUN_ETH="$(hs_eth_of_priv "$HS_KEY_SUN")"
hs_oracle_capture "$SOURCE_NODE" before-zion balance "$ZION_ETH"
hs_oracle_capture "$SOURCE_NODE" before-sun balance "$SUN_ETH"

hs_tx_transfer_confirmed "$SOURCE_NODE" "$HS_KEY_ZION" "$HS_ADDR_SUN" 101
hs_oracle_capture "$SOURCE_NODE" after-transfer-1-zion balance "$ZION_ETH"
hs_oracle_capture "$SOURCE_NODE" after-transfer-1-sun balance "$SUN_ETH"

hs_wait_height "$SOURCE_NODE" 28 180 >/dev/null
hs_tx_transfer_confirmed "$SOURCE_NODE" "$HS_KEY_SUN" "$HS_ADDR_ZION" 37
hs_oracle_capture "$SOURCE_NODE" after-transfer-2-zion balance "$ZION_ETH"
hs_oracle_capture "$SOURCE_NODE" after-transfer-2-sun balance "$SUN_ETH"

hs_wait_height "$SOURCE_NODE" "$CFK_SOURCE_HEIGHT" 300 >/dev/null
SOURCE_HEAD_BEFORE="$(hs_head_num "$SOURCE_NODE")"
SOURCE_SOLID_BEFORE="$(hs_solid_num "$SOURCE_NODE")"
if [ "$SOURCE_SOLID_BEFORE" -gt 0 ] \
    && [ "$SOURCE_SOLID_BEFORE" -lt "$SOURCE_HEAD_BEFORE" ]; then
  hs_pass "27-SR source has a real unsolidified tail (solid=$SOURCE_SOLID_BEFORE head=$SOURCE_HEAD_BEFORE)"
else
  hs_fail "expected 27-SR solidified head behind canonical head, got solid=$SOURCE_SOLID_BEFORE head=$SOURCE_HEAD_BEFORE"
fi

hs_step "starting SR27 in catch-up mode and killing inside a confirmed batch flush"
FLUSH_ANCHOR="$(hs_anchor_resolve cfk.flush "$HS_JAR")" \
  || hs_die "flush anchor unresolvable: ${FLUSH_ANCHOR#FAIL }"
FLUSH_LINE="$(hs_anchor_field "$FLUSH_ANCHOR" jarline)"
FLUSH_OWNERS="$(hs_anchor_field "$FLUSH_ANCHOR" owners)"
FLUSH_LOCATION="$(hs_anchor_field "$FLUSH_ANCHOR" class):$FLUSH_LINE"
hs_log "flush anchor cfk.flush -> $FLUSH_LOCATION" \
  "(src $(hs_anchor_field "$FLUSH_ANCHOR" src):$(hs_anchor_field "$FLUSH_ANCHOR" srcline)," \
  "jar-vs-source line offset $(hs_anchor_field "$FLUSH_ANCHOR" delta))" \
  "-- $(hs_anchor_describe cfk.flush)"

export HS_JDWP_PORT="$CFK_JDWP_PORT"
export HS_JDWP_SUSPEND=y
hs_node_start "$TARGET_NODE"
if ! cfk_break_inspect_kill "$TARGET_NODE" "$FLUSH_LOCATION" \
    "$CFK_MAX_FLUSH_COUNT" "$CFK_BP_TIMEOUT" "$FLUSH_OWNERS"; then
  hs_abort "could not prove and kill the target inside a maxFlushCount=$CFK_MAX_FLUSH_COUNT batch"
fi
unset HS_JDWP_PORT HS_JDWP_SUSPEND

hs_assert_eq "137" "$(hs_node_exit_code "$TARGET_NODE")" \
  "target was terminated by SIGKILL"
hs_assert_eq "$CFK_MAX_FLUSH_COUNT" "$CFK_OBSERVED_MAX_FLUSH_COUNT" \
  "runtime maxFlushCount at kill"
if [ "$CFK_OBSERVED_FLUSH_COUNT" -gt 1 ] 2>/dev/null; then
  hs_pass "runtime flushCount is batched ($CFK_OBSERVED_FLUSH_COUNT)"
else
  hs_fail "runtime flushCount was not batched (${CFK_OBSERVED_FLUSH_COUNT:-?})"
fi

SOURCE_HEAD_AT_KILL="$(hs_head_num "$SOURCE_NODE")"
PRE_PROBE="$(hs_offline_probe "$TARGET_NODE")"
PRE_PROBE_RC=$?
hs_assert_eq "0" "$PRE_PROBE_RC" "post-kill archive probe exit"
PRE_MAX_BLOCK="$(printf '%s' "$PRE_PROBE" | jq -r '.maxBlock // -1')"
PRE_INFLIGHT_MAX="$(printf '%s' "$PRE_PROBE" | jq -r '.inFlightMaxBlock // -1')"
PRE_TARGET_TAIL="$PRE_MAX_BLOCK"
[ "$PRE_INFLIGHT_MAX" -gt "$PRE_TARGET_TAIL" ] 2>/dev/null \
  && PRE_TARGET_TAIL="$PRE_INFLIGHT_MAX"
if [ "$PRE_TARGET_TAIL" -ge 0 ] \
    && [ "$PRE_TARGET_TAIL" -lt "$SOURCE_HEAD_AT_KILL" ]; then
  hs_pass "kill landed while SR27 was catching up (archive-tail=$PRE_TARGET_TAIL source-head=$SOURCE_HEAD_AT_KILL)"
else
  hs_fail "kill did not demonstrate catch-up state (archive-tail=$PRE_TARGET_TAIL source-head=$SOURCE_HEAD_AT_KILL)"
fi
hs_assert_eq "0" "$(printf '%s' "$PRE_PROBE" | jq -r '(.violations // []) | length')" \
  "post-kill archive structural violations"

hs_step "restarting SR27 and validating canonical plus historical state"
STARTUP_VERDICT="$(hs_node_restart "$TARGET_NODE" "$CFK_CATCHUP_TIMEOUT")"
hs_assert_eq "READY" "$STARTUP_VERDICT" "target restart verdict"
[ "$STARTUP_VERDICT" = "READY" ] \
  || hs_abort "target did not recover after the batch-flush kill"

hs_wait_height "$TARGET_NODE" "$SOURCE_HEAD_AT_KILL" "$CFK_CATCHUP_TIMEOUT" >/dev/null
SOURCE_KILL_BLOCK_ID="$(hs_block_id_at "$SOURCE_NODE" "$SOURCE_HEAD_AT_KILL")"
TARGET_KILL_BLOCK_ID="$(hs_block_id_at "$TARGET_NODE" "$SOURCE_HEAD_AT_KILL")"
hs_assert_ne "" "$SOURCE_KILL_BLOCK_ID" "source canonical block id at kill height"
hs_assert_eq "$SOURCE_KILL_BLOCK_ID" "$TARGET_KILL_BLOCK_ID" \
  "canonical block id after restart and catch-up"

ORACLE_MAX_HEIGHT="$(
  awk -F '	' 'NF >= 6 && $5 ~ /^[0-9]+$/ && $5 > max { max = $5 } END { print max + 0 }' \
    "$(hs_oracle_file)"
)"
hs_wait_hist_available "$TARGET_NODE" "$ZION_ETH" "$ORACLE_MAX_HEIGHT" \
  "$CFK_CATCHUP_TIMEOUT"
hs_oracle_replay "$TARGET_NODE"
hs_assert_repair_not_required "$TARGET_NODE" "recovered target"
if hs_node_has_archive_failstop "$TARGET_NODE"; then
  hs_fail "recovered target logged an archive fail-stop"
else
  hs_pass "recovered target has no archive fail-stop breadcrumb"
fi

SOURCE_SETTLE_HEAD="$(hs_wait_blocks "$SOURCE_NODE" 5 180)"
hs_wait_height "$TARGET_NODE" "$SOURCE_SETTLE_HEAD" "$CFK_CATCHUP_TIMEOUT" >/dev/null
hs_assert_eq "$(hs_block_id_at "$SOURCE_NODE" "$SOURCE_SETTLE_HEAD")" \
  "$(hs_block_id_at "$TARGET_NODE" "$SOURCE_SETTLE_HEAD")" \
  "canonical block id after continued 27-SR production"

hs_step "cleanly stopping SR27 for final unified archive inspection"
if hs_node_stop "$TARGET_NODE" 120; then
  hs_assert_clean_stop "$TARGET_NODE" "recovered target shutdown"
else
  hs_fail "recovered target did not stop cleanly"
  hs_node_stop_force "$TARGET_NODE" 10
fi
hs_assert_probe_clean "$TARGET_NODE" "final unified archive"

FINAL_PROBE="$(hs_offline_probe "$TARGET_NODE")"
FINAL_PROBE_RC=$?
hs_assert_eq "0" "$FINAL_PROBE_RC" "final archive probe exit"
FINAL_MAX_BLOCK="$(printf '%s' "$FINAL_PROBE" | jq -r '.maxBlock // -1')"
if [ "$FINAL_MAX_BLOCK" -ge "$ORACLE_MAX_HEIGHT" ] 2>/dev/null; then
  hs_pass "archive covers every replayed oracle (max=$FINAL_MAX_BLOCK oracle-max=$ORACLE_MAX_HEIGHT)"
else
  hs_fail "archive max block $FINAL_MAX_BLOCK is below oracle max $ORACLE_MAX_HEIGHT"
fi
hs_assert_eq "0" "$(printf '%s' "$FINAL_PROBE" | jq -r '(.blockGaps // []) | length')" \
  "final archive block gaps"
hs_assert_eq "0" "$(printf '%s' "$FINAL_PROBE" | jq -r '(.txNumGaps // []) | length')" \
  "final archive txNum gaps"
hs_assert_eq "false" "$(
  printf '%s' "$FINAL_PROBE" \
    | jq -r 'if has("repairRequired") then .repairRequired else true end'
)" \
  "final archive repair marker"

hs_finish CATCHUP_BATCH_FLUSH_KILL_OK \
  "witnesses=27" \
  "split=26+1" \
  "flushCount=$CFK_OBSERVED_FLUSH_COUNT" \
  "maxFlushCount=$CFK_OBSERVED_MAX_FLUSH_COUNT" \
  "killSourceHead=$SOURCE_HEAD_AT_KILL" \
  "finalArchiveHead=$FINAL_MAX_BLOCK"
