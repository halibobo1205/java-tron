#!/usr/bin/env bash
#
# scenario-history-accuracy.sh -- does the archive return the RIGHT historical
# value for real transactions?
#
# Every other scenario in this directory asks "does the archive survive a
# fault".  This one asks the orthogonal question: when it DOES answer, is the
# answer correct?  A silently-wrong historical value is the failure mode the
# fault scenarios cannot see.
#
# ---------------------------------------------------------------------------
# THE ONE RULE: NO CIRCULAR ORACLES
# ---------------------------------------------------------------------------
# An assertion that compares one archive answer against another archive answer
# proves nothing.  Every check below names, in its table row and in the comment
# above it, which INDEPENDENT source its expected value comes from.  There are
# exactly five legal sources, and no sixth:
#
#   tx        the transaction's own semantics -- the amount we chose to send,
#             the 32-byte word we chose to SSTORE, the runtime bytecode we
#             chose to deploy.  Known before the node ever ran.
#   receipt   /wallet/gettransactioninfobyid -- .fee and .withdraw_amount, the
#             node's own accounting, produced by the canonical (non-archive)
#             execution path.
#   live@H    the LIVE, non-archive answer (block tag "latest", so
#             ArchiveJsonRpcStateAdapter.shouldUseArchive is false) recorded at
#             the instant head was exactly H, long before H was historical at
#             all.  See ha_sample_now for the head-value-head stability guard
#             that makes "exactly H" true.
#   arith     arithmetic over the three above (pre + delta == post).
#   probe     ONE structural check reads the committed txNum index off disk
#             with ArchiveProbe.  That is archive storage, but it is not the
#             query path under test and it is read with the node stopped.
#
# ---------------------------------------------------------------------------
# WHAT IS COVERED
# ---------------------------------------------------------------------------
#   (1) NORMAL      TRX transfers: sender delta == amount + fee, recipient
#                   delta == amount, an address queried BEFORE it existed, and
#                   two deltas on one address so an intermediate height must
#                   not answer with the final value.
#   (2) CONTRACT    deploy, slot 0 driven 0 -> 111 -> 222 -> 0, eth_getCode
#                   before/after the deploy block, eth_call at four heights,
#                   and a SELFDESTRUCT with code/storage checked at the exact
#                   heights either side.
#   (3) SYSTEM      SR rewards.  Read the source, not the folklore:
#                     Manager.java:2577  opens ArchivePhase.BLOCK_FINALIZE
#                     Manager.java:2588  payReward(block)          <- EVERY block
#                     Manager.java:2640  committee.allowChangeDelegation == 0
#                                        => account.setAllowance(+witnessPayPerBlock)
#                                           accountStore.put(...)
#                   so the producing witness's ALLOWANCE (not its balance) is
#                   rewritten on every block it signs, with no user transaction
#                   anywhere near it.  This scenario
#                     (3a) proves the accrual exists and steps EXACTLY at the
#                          heights that witness signed (live, independent), and
#                     (3b) converts that opaque field into an observable
#                          balance delta with a real WithdrawBalanceContract,
#                          then checks that delta through the archive.
#
#                   (3b) cannot use a genesis witness:
#                     WithdrawBalanceActuator.java:112-120 rejects any owner in
#                     CommonParameter.getGenesisBlock().getWitnesses() with
#                     "is a guard representative and is not allowed to withdraw
#                     Balance", and every harness SR is a genesis witness.
#                   So the scenario grows a 28th, NON-genesis witness at
#                   runtime (WitnessCreateContract + freeze + vote) and
#                   withdraws from that one.  Promoting it into the active set
#                   needs MaintenanceManager.doMaintenance
#                   (MaintenanceManager.java:103-129 only reshuffles when votes
#                   changed), which is why -- and ONLY why -- this scenario
#                   shortens block.maintenanceTimeInterval in its OWN node.conf.
#                   lib.sh's shared default is untouched.
#
# ---------------------------------------------------------------------------
# PHASES
# ---------------------------------------------------------------------------
#   Phase 1 "record"  drive the chain; at every height sample the LIVE value of
#                     every tracked item into $HS_RUN_DIR/live-ledger.tsv, and
#                     persist every receipt into tx-ledger.tsv.
#   Phase 2 "replay"  once those heights are solid and published, run the
#                     historical queries and compare them against the ledger
#                     AND against the arithmetic expectations.
#
# Marker: HISTORY_ACCURACY_OK / HISTORY_ACCURACY_FAIL
# Exit  : 0 pass, 1 product failure, 2 harness/environment error.
#
# Usage:
#   HS_CFG_WITNESS_COUNT=27 ./scenario-history-accuracy.sh
#   HS_KEEP_WORKDIR=1 HS_CFG_WITNESS_COUNT=27 ./scenario-history-accuracy.sh
#
# The witness count defaults to 27 here (every other scenario defaults to 1):
# a one-SR chain has solid == head, so the in-flight window degenerates to ~1
# block and "historical" barely means anything.  At 27 SRs solid == head - 18
# and roughly 20 blocks are in flight at any moment.
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)/lib.sh"

: "${HS_CFG_WITNESS_COUNT:=27}"

# Only reason this is not lib.sh's 21600000: a new witness cannot join the
# active set without a maintenance round (MaintenanceManager.java:103-129), and
# a 6-hour interval never fires inside a test run. Block rewards themselves are
# NOT maintenance-gated -- they are paid per block in BLOCK_FINALIZE.
HA_MAINTENANCE_INTERVAL_MS="${HA_MAINTENANCE_INTERVAL_MS:-30000}"

# ===========================================================================
# Verdict table
#
# hs_pass / hs_fail drive the exit code (and hs_finish's anti-vacuity gate);
# the table rows are the human-readable record of WHICH oracle each check used.
# INFO rows deliberately do NOT touch the hs_* counters: an INFO says "this is
# unproven", and unproven must never contribute to a green run.
# ===========================================================================
HA_VERDICT_FILE=""
HA_PASS=0
HA_FAIL=0
HA_INFO=0
HA_TAB="$(printf '\t')"

ha_row() {
  local verdict="$1" name="$2" height="$3" source="$4" detail="$5"
  printf '%s\t%s\t%s\t%s\t%s\n' "$verdict" "$name" "$height" "$source" "$detail" \
    >>"$HA_VERDICT_FILE"
  printf 'CHECK [%-4s] %-46s h=%-13s src=%-8s %s\n' \
    "$verdict" "$name" "$height" "$source" "$detail"
}

ha_ok() {
  HA_PASS=$((HA_PASS + 1))
  ha_row PASS "$1" "$2" "$3" "$4"
  hs_pass "$1 @$2 via $3"
}

ha_bad() {
  HA_FAIL=$((HA_FAIL + 1))
  ha_row FAIL "$1" "$2" "$3" "$4"
  hs_fail "$1 @$2 via $3: $4"
}

# ha_eq <name> <height> <source> <expected> <actual> [note]
ha_eq() {
  local name="$1" height="$2" source="$3" expected="$4" actual="$5" note="${6:-}"
  if [ -z "$actual" ]; then
    ha_bad "$name" "$height" "$source" "expected=$expected got=<no answer> $note"
    return 0
  fi
  if [ "$expected" = "$actual" ]; then
    ha_ok "$name" "$height" "$source" "= $actual $note"
  else
    ha_bad "$name" "$height" "$source" "expected=$expected got=$actual $note"
  fi
}

# ha_ne <name> <height> <source> <unexpected> <actual> [note]
ha_ne() {
  local name="$1" height="$2" source="$3" unexpected="$4" actual="$5" note="${6:-}"
  if [ -z "$actual" ]; then
    ha_bad "$name" "$height" "$source" "no answer (must differ from $unexpected) $note"
    return 0
  fi
  if [ "$unexpected" != "$actual" ]; then
    ha_ok "$name" "$height" "$source" "$actual != $unexpected $note"
  else
    ha_bad "$name" "$height" "$source" "must not equal $unexpected $note"
  fi
}

# ha_info <name> <height> <what...> -- an explicitly UNPROVEN statement.
ha_info() {
  local name="$1" height="$2"
  shift 2
  HA_INFO=$((HA_INFO + 1))
  ha_row INFO "$name" "$height" "none" "$*"
}

ha_print_table() {
  printf '\n'
  printf 'VERDICT TABLE -- scenario-history-accuracy (the independent oracle is the "ORACLE" column)\n'
  awk -F'\t' '
    BEGIN {
      printf "%-6s %-46s %-13s %-8s %s\n", "RESULT", "CHECK", "HEIGHT", "ORACLE", "DETAIL"
      printf "%-6s %-46s %-13s %-8s %s\n", "------", \
        "----------------------------------------------", "-------------", "--------", "------"
    }
    { printf "%-6s %-46s %-13s %-8s %s\n", $1, $2, $3, $4, $5 }
  ' "$HA_VERDICT_FILE"
  printf '\n'
}

# ===========================================================================
# Non-aborting RPC/HTTP getters
#
# lib.sh's hs_jsonrpc_result aborts the scenario on any JSON-RPC error, which
# is right for a scenario step but wrong for a sampler that runs thousands of
# times. These return non-zero and an empty string instead; the caller decides.
# ===========================================================================
ha_rpc() {
  local node="$1" method="$2" params="$3" url body
  url="$(hs_jsonrpc_url "$node")"
  body="$(curl -s --max-time "${HS_JSONRPC_TIMEOUT:-40}" -X POST \
    -H 'Content-Type: application/json' \
    --data "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$method\",\"params\":$params}" \
    "$url" 2>/dev/null)" || body=""
  printf '%s' "$body"
}

ha_rpc_result() {
  local body
  body="$(ha_rpc "$@")"
  [ -n "$body" ] || return 1
  if [ "$(printf '%s' "$body" | jq -r 'has("error")' 2>/dev/null || printf 'true')" = "true" ]; then
    return 1
  fi
  printf '%s' "$body" | jq -r '.result // empty' 2>/dev/null || return 1
}

ha_rpc_error() {
  local body
  body="$(ha_rpc "$@")"
  [ -n "$body" ] || { printf 'transport failure'; return 0; }
  printf '%s' "$body" | jq -r '.error.message // "no error"' 2>/dev/null || printf 'unparsable'
}

ha_http_post() {
  local node="$1" path="$2" data="$3" url
  url="$(hs_http_url "$node")"
  curl -s --max-time "${HS_HTTP_TIMEOUT:-15}" -X POST -H 'Content-Type: application/json' \
    --data "$data" "$url$path" 2>/dev/null || printf ''
}

# ha_base58_to_hex41 <base58check> -- the 21-byte 41.. form.
# Needed for the fee sink (blackhole), whose private key the harness does not
# have, so hs_hex41_of_priv cannot reach it. Pure address decoding, no node.
ha_base58_to_hex41() {
  python3 - "$1" <<'PY'
import sys, hashlib
ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
s = sys.argv[1]
n = 0
for ch in s:
    n = n * 58 + ALPHABET.index(ch)
raw = n.to_bytes(25, "big")
payload, checksum = raw[:21], raw[21:]
digest = hashlib.sha256(hashlib.sha256(payload).digest()).digest()[:4]
if digest != checksum:
    raise SystemExit("base58check checksum mismatch for " + s)
print(payload.hex())
PY
}

ha_hex_of_string() {
  printf '%s' "$1" | od -An -v -tx1 | tr -d ' \n'
}

# ha_hex_to_dec <0x...> -- decimal, or "ERR". Balances are < 2^63 so bash
# arithmetic is exact; 32-byte storage WORDS are compared as strings, never here.
ha_hex_to_dec() {
  local v="$1"
  case "$v" in
    0x*|0X*) : ;;
    *) printf 'ERR\n'; return 0 ;;
  esac
  if [ "$v" = "0x" ] || [ "${#v}" -gt 18 ]; then
    printf 'ERR\n'
    return 0
  fi
  printf '%s\n' "$(( v ))" 2>/dev/null || printf 'ERR\n'
}

# ===========================================================================
# The live ledger -- phase 1's record of "what the chain said at height H"
# ===========================================================================
HA_TRACK_FILE=""
HA_LIVE_FILE=""
HA_TX_FILE=""
HA_LAST_SAMPLE=""

# ha_track <label> <kind> <arg1> [arg2]
#   kind: balance | code | storage | call   -> has a historical counterpart
#         allowance                         -> live-only, replayed as INFO
ha_track() {
  local label="$1" kind="$2" a1="$3" a2="${4:-}"
  [ -n "$a2" ] || a2="-"
  printf '%s\t%s\t%s\t%s\n' "$label" "$kind" "$a1" "$a2" >>"$HA_TRACK_FILE"
  hs_log "tracking live item '$label' ($kind $a1 $a2)"
}

# ha_live_value <node> <kind> [a2] -- the LIVE (tag "latest") answer.
#
# a2 (the storage slot) defaults to empty ON PURPOSE: under `set -u` a bare
# "$4" makes every 3-argument call -- ha_live_value "$node" allowance "$addr"
# -- die inside its own command substitution, and the caller's `|| fallback`
# then swallows the diagnosis. That silently turned the whole withdrawal
# sub-phase into a 420 s no-op on the first real run.
ha_live_value() {
  local node="$1" kind="$2" a1="$3" a2="${4:-}" body
  case "$kind" in
    balance) ha_rpc_result "$node" eth_getBalance "[\"$a1\",\"latest\"]" ;;
    code) ha_rpc_result "$node" eth_getCode "[\"$a1\",\"latest\"]" ;;
    storage) ha_rpc_result "$node" eth_getStorageAt "[\"$a1\",\"$a2\",\"latest\"]" ;;
    call) ha_rpc_result "$node" eth_call "[{\"to\":\"$a1\",\"data\":\"0x\"},\"latest\"]" ;;
    allowance)
      # AccountCapsule.allowance -- where MortgageService/Manager put the SR
      # reward. /wallet/getReward reports the DelegationStore accrual instead,
      # which is empty while committee.allowChangeDelegation == 0.
      body="$(ha_http_post "$node" /wallet/getaccount "{\"address\":\"$a1\",\"visible\":true}")"
      [ -n "$body" ] || return 1
      printf '%s\n' "$body" | jq -r '.allowance // 0' 2>/dev/null || return 1 ;;
    *) return 1 ;;
  esac
}

# ha_hist_value <node> <kind> <a1> <a2> <height> -- the ARCHIVE answer at H.
ha_hist_value() {
  local node="$1" kind="$2" a1="$3" a2="$4" height="$5" tag
  tag="$(hs_dec_to_hexblock "$height")"
  case "$kind" in
    balance) ha_rpc_result "$node" eth_getBalance "[\"$a1\",\"$tag\"]" ;;
    code) ha_rpc_result "$node" eth_getCode "[\"$a1\",\"$tag\"]" ;;
    storage) ha_rpc_result "$node" eth_getStorageAt "[\"$a1\",\"$a2\",\"$tag\"]" ;;
    call) ha_rpc_result "$node" eth_call "[{\"to\":\"$a1\",\"data\":\"0x\"},\"$tag\"]" ;;
    *) return 1 ;;
  esac
}

ha_hist_error() {
  local node="$1" kind="$2" a1="$3" a2="$4" height="$5" tag
  tag="$(hs_dec_to_hexblock "$height")"
  case "$kind" in
    balance) ha_rpc_error "$node" eth_getBalance "[\"$a1\",\"$tag\"]" ;;
    code) ha_rpc_error "$node" eth_getCode "[\"$a1\",\"$tag\"]" ;;
    storage) ha_rpc_error "$node" eth_getStorageAt "[\"$a1\",\"$a2\",\"$tag\"]" ;;
    call) ha_rpc_error "$node" eth_call "[{\"to\":\"$a1\",\"data\":\"0x\"},\"$tag\"]" ;;
    *) printf 'unknown kind' ;;
  esac
}

# ha_pending_size <node> -- Manager.getPendingSize() (pending + rePush +
# popped), or -1 when it cannot be read.
ha_pending_size() {
  local body n
  body="$(ha_http_post "$1" /wallet/getpendingsize '{}')"
  [ -n "$body" ] || { printf -- '-1\n'; return 0; }
  n="$(printf '%s' "$body" | jq -r '.pendingSize // empty' 2>/dev/null || printf '')"
  case "$n" in
    ''|*[!0-9]*) printf -- '-1\n' ;;
    *) printf '%s\n' "$n" ;;
  esac
}

# ha_sample_now <node>
#
# Record every tracked item's LIVE value, pinned to the current head.
#
# TWO GUARDS, and both are load-bearing.
#
# 1. HEAD STABILITY. Head is read BEFORE and AFTER the item loop and the sample
#    is thrown away unless the two agree. Without it a block produced mid-loop
#    would file "latest" values under the previous height.
#
# 2. EMPTY PENDING POOL. `latest` is NOT "the state at the end of the head
#    block": Manager.pushTransaction (Manager.java:1264-1270) executes a
#    broadcast transaction in a session and `tmpSession.merge()`s it into the
#    head snapshot immediately, so eth_getBalance("latest") already reflects
#    transactions that no block contains yet. A sample taken then records a
#    value that was never in force at ANY height, and the replay would blame
#    the archive for the difference. MEASURED: with this guard absent, a run
#    filed the post-transfer balances of block 72 under height 71 and produced
#    two spurious FAILs -- the archive had been right both times.
#    So: refuse to sample unless /wallet/getpendingsize reports 0 both before
#    and after the item loop. Heights covered by a pending window simply get no
#    live sample; they are still pinned by the tx/receipt/arith checks, which
#    are stronger oracles anyway.
ha_sample_now() {
  local node="$1" tries=0 h1 h2 p1 p2 label kind a1 a2 value ok tmp
  tmp="$HS_RUN_DIR/.sample.$$"
  while [ "$tries" -lt 3 ]; do
    tries=$((tries + 1))
    p1="$(ha_pending_size "$node")"
    [ "$p1" = "0" ] || return 1
    h1="$(hs_head_num "$node")"
    case "$h1" in ''|-1) return 1 ;; esac
    ok=1
    : >"$tmp"
    while IFS="$HA_TAB" read -r label kind a1 a2; do
      [ -n "$label" ] || continue
      if grep -q "^$h1$HA_TAB$label$HA_TAB" "$HA_LIVE_FILE" 2>/dev/null; then
        continue
      fi
      value="$(ha_live_value "$node" "$kind" "$a1" "$a2")" || { ok=0; break; }
      case "$value" in
        0x*|41*|[0-9]*) : ;;
        *) ok=0; break ;;
      esac
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$h1" "$label" "$kind" "$a1" "$a2" "$value" >>"$tmp"
    done <"$HA_TRACK_FILE"
    h2="$(hs_head_num "$node")"
    p2="$(ha_pending_size "$node")"
    if [ "$ok" = "1" ] && [ "$h1" = "$h2" ] && [ "$p2" = "0" ]; then
      cat "$tmp" >>"$HA_LIVE_FILE"
      rm -f "$tmp"
      HA_LAST_SAMPLE="$h1"
      return 0
    fi
  done
  rm -f "$tmp"
  return 1
}

# ha_ledger_value <height> <label> -- the recorded live value, "" when absent.
ha_ledger_value() {
  awk -F'\t' -v h="$1" -v l="$2" '$1==h && $2==l { print $6; exit }' "$HA_LIVE_FILE"
}

# ha_sample_at_or_below <label> <height> -- "<height> <value>" of the NEWEST
# sample at or below <height>; "" when there is none.
#
# The pending-pool guard means a height inside a transaction's pending window
# has no live sample at all, so anything anchored on "the value just before
# block N" must anchor on the nearest CLEAN height and carry the arithmetic
# forward. That is still an independent oracle; it is just an honest one.
ha_sample_at_or_below() {
  awk -F'\t' -v l="$1" -v h="$2" '
    $2==l && ($1+0)<=(h+0) { if (best=="" || ($1+0)>(best+0)) { best=$1; val=$6 } }
    END { if (best!="") print best, val }' "$HA_LIVE_FILE"
}

# ha_sample_at_or_above <label> <height> -- "<height> <value>" of the OLDEST
# sample at or above <height>; "" when there is none.
ha_sample_at_or_above() {
  awk -F'\t' -v l="$1" -v h="$2" '
    $2==l && ($1+0)>=(h+0) { if (best=="" || ($1+0)<(best+0)) { best=$1; val=$6 } }
    END { if (best!="") print best, val }' "$HA_LIVE_FILE"
}

# ha_block_producer <node> <num> -- the hex41 witness that signed block <num>.
#
# Read from the canonical block header (/wallet/getblockbynum), not from the
# live sample ledger and not from the archive: block headers are immutable, are
# unaffected by the pending pool, and never gap. Cached, because the reward
# arithmetic walks the whole window.
HA_PRODUCER_CACHE=""
ha_block_producer() {
  local node="$1" num="$2" hit body who
  if [ -n "$HA_PRODUCER_CACHE" ] && [ -f "$HA_PRODUCER_CACHE" ]; then
    hit="$(awk -F'\t' -v n="$num" '$1==n { print $2; exit }' "$HA_PRODUCER_CACHE")"
    if [ -n "$hit" ]; then
      printf '%s\n' "$hit"
      return 0
    fi
  fi
  body="$(ha_http_post "$node" /wallet/getblockbynum "{\"num\":$num}")"
  [ -n "$body" ] || return 1
  who="$(printf '%s' "$body" \
    | jq -r '.block_header.raw_data.witness_address // empty' 2>/dev/null || printf '')"
  [ -n "$who" ] || return 1
  [ -z "$HA_PRODUCER_CACHE" ] || printf '%s\t%s\n' "$num" "$who" >>"$HA_PRODUCER_CACHE"
  printf '%s\n' "$who"
}

# ha_signed_between <node> <fromExclusive> <toInclusive> <signerHex41>
# Echoes how many blocks in (from, to] that signer produced, or "ERR".
ha_signed_between() {
  local node="$1" from="$2" to="$3" signer="$4" h count=0 who
  h="$from"
  while [ "$h" -lt "$to" ]; do
    h=$(( h + 1 ))
    who="$(ha_block_producer "$node" "$h")" || { printf 'ERR\n'; return 0; }
    [ "$who" = "$signer" ] && count=$(( count + 1 ))
  done
  printf '%s\n' "$count"
}

# ha_pump_to <node> <target> [timeout] -- advance to <target>, sampling every
# new height on the way. This is what makes a live sample exist at EVERY height
# the replay later asks about.
ha_pump_to() {
  local node="$1" target="$2" timeout="${3:-900}" head
  local deadline=$(( $(date +%s) + timeout ))
  while :; do
    head="$(hs_head_num "$node")"
    if [ "$head" != "-1" ] && [ "$head" != "$HA_LAST_SAMPLE" ]; then
      ha_sample_now "$node" || hs_log "sample at head=$head discarded (head moved mid-sample)"
      head="$(hs_head_num "$node")"
    fi
    if [ "$head" != "-1" ] && [ "$head" -ge "$target" ] 2>/dev/null; then
      return 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      hs_abort "chain did not reach height $target within ${timeout}s (head=$head)"
    fi
    sleep 0.2
  done
}

# ---------------------------------------------------------------------------
# Transaction plumbing.
#
# ha_try_* NEVER abort: the system-transaction sub-phase builds a non-genesis
# witness through five chain operations, any of which a future consensus rule
# could refuse, and "the scenario exploded" is a much worse outcome than "the
# scenario reported exactly which rule blocked it".
# ---------------------------------------------------------------------------
HA_TRY_TXID=""
HA_TRY_ERR=""
HA_RECEIPT=""
HA_TX_BLOCK=""
HA_TX_FEE=""

ha_try_build_and_send() {
  local node="$1" priv="$2" path="$3" body="$4" url tx txid sig signed result
  HA_TRY_TXID=""
  HA_TRY_ERR=""
  url="$(hs_http_url "$node")"
  tx="$(ha_http_post "$node" "$path" "$body")"
  [ -n "$tx" ] || { HA_TRY_ERR="empty response from $path"; return 1; }
  if [ "$(printf '%s' "$tx" | jq -r 'has("Error") or has("code")' 2>/dev/null || printf 'true')" = "true" ]; then
    HA_TRY_ERR="$path rejected: $(printf '%s' "$tx" | tr -d '\n' | head -c 300)"
    return 1
  fi
  tx="$(printf '%s' "$tx" | jq -c 'if has("transaction") then .transaction else . end' 2>/dev/null || printf '')"
  [ -n "$tx" ] || { HA_TRY_ERR="$path returned unparsable JSON"; return 1; }
  txid="$(printf '%s' "$tx" | jq -r '.txID // empty' 2>/dev/null || true)"
  [ -n "$txid" ] || { HA_TRY_ERR="$path returned no txID: $(printf '%s' "$tx" | head -c 200)"; return 1; }
  sig="$(java -cp "$HS_CLASSES:$HS_JAR" Sign "$priv" "$txid" 2>&1)" \
    || { HA_TRY_ERR="Sign helper failed: $sig"; return 1; }
  signed="$(printf '%s' "$tx" | jq -c --arg s "$sig" '.signature = [$s]' 2>/dev/null || printf '')"
  [ -n "$signed" ] || { HA_TRY_ERR="could not attach the signature"; return 1; }
  result="$(curl -s --max-time 20 -X POST -H 'Content-Type: application/json' \
    --data "$signed" "$url/wallet/broadcasttransaction" 2>/dev/null || printf '')"
  if [ "$(printf '%s' "$result" | jq -r '.result // false' 2>/dev/null || printf 'false')" != "true" ]; then
    HA_TRY_ERR="broadcast rejected: $(printf '%s' "$result" | tr -d '\n' | head -c 300)"
    return 1
  fi
  HA_TRY_TXID="$txid"
  return 0
}

# ha_try_await <node> <txid> [timeout] -- pump-and-poll, non-aborting.
ha_try_await() {
  local node="$1" txid="$2" timeout="${3:-240}" head info
  local deadline=$(( $(date +%s) + timeout ))
  HA_RECEIPT=""
  HA_TX_BLOCK=""
  HA_TX_FEE=""
  while :; do
    head="$(hs_head_num "$node")"
    if [ "$head" != "-1" ] && [ "$head" != "$HA_LAST_SAMPLE" ]; then
      ha_sample_now "$node" || true
    fi
    info="$(ha_http_post "$node" /wallet/gettransactioninfobyid "{\"value\":\"$txid\"}")"
    if [ -n "$info" ] \
        && [ "$(printf '%s' "$info" | jq -r 'has("id")' 2>/dev/null || printf 'false')" = "true" ]; then
      HA_RECEIPT="$info"
      HA_TX_BLOCK="$(printf '%s' "$info" | jq -r '.blockNumber // empty' 2>/dev/null || true)"
      HA_TX_FEE="$(printf '%s' "$info" | jq -r '.fee // 0' 2>/dev/null || printf 0)"
      ha_sample_now "$node" || true
      [ -n "$HA_TX_BLOCK" ] || { HA_TRY_ERR="receipt has no blockNumber"; return 1; }
      return 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      HA_TRY_ERR="not mined within ${timeout}s"
      return 1
    fi
    sleep 0.2
  done
}

# ha_await_tx -- the aborting form, for the steps the scenario cannot continue
# without. Sets HA_RECEIPT / HA_TX_BLOCK / HA_TX_FEE.
ha_await_tx() {
  ha_try_await "$@" || hs_abort "transaction $2 failed: $HA_TRY_ERR"
}

# ha_record_tx <label> <txid> -- persist the receipt facts for phase 2.
ha_record_tx() {
  local label="$1" txid="$2" result
  result="$(printf '%s' "$HA_RECEIPT" | jq -r '.receipt.result // "NONE"' 2>/dev/null || printf '?')"
  case "$result" in
    SUCCESS|NONE) : ;;
    *) hs_abort "$label ($txid) did not succeed: receipt.result=$result $(printf '%s' "$HA_RECEIPT" | jq -c '.resMessage // ""' 2>/dev/null || true)" ;;
  esac
  printf '%s\t%s\t%s\t%s\n' "$label" "$txid" "$HA_TX_BLOCK" "$HA_TX_FEE" >>"$HA_TX_FILE"
  hs_log "$label: tx $txid mined in block $HA_TX_BLOCK, fee=$HA_TX_FEE sun (result=$result)"
}

# ha_deploy <node> <priv> <bytecodeHex> <name> -- echo the txID.
ha_deploy() {
  local node="$1" priv="$2" code="$3" name="$4" url owner tx
  url="$(hs_http_url "$node")"
  owner="$(hs_base58_of_priv "$priv")"
  tx="$(hs_curl_json -X POST -H 'Content-Type: application/json' --data "$(cat <<EOF
{"owner_address":"$owner","abi":"[]","bytecode":"$code",
 "name":"$name","fee_limit":1000000000,
 "consume_user_resource_percent":100,"origin_energy_limit":10000000,"visible":true}
EOF
)" "$url/wallet/deploycontract")" || hs_abort "deploycontract HTTP call failed for $name"
  if [ "$(printf '%s' "$tx" | jq -r 'has("Error") or has("code")' 2>/dev/null || true)" = "true" ]; then
    hs_abort "deploycontract returned an error for $name: $tx"
  fi
  hs_broadcast "$node" "$priv" "$tx"
}

# ha_trigger <node> <priv> <contractHex41> <dataHex> -- echo the txID.
ha_trigger() {
  local node="$1" priv="$2" contract="$3" data="$4" url owner wrapper tx
  url="$(hs_http_url "$node")"
  owner="$(hs_hex41_of_priv "$priv")"
  wrapper="$(hs_curl_json -X POST -H 'Content-Type: application/json' --data "$(cat <<EOF
{"owner_address":"$owner","contract_address":"$contract","data":"$data",
 "fee_limit":1000000000,"call_value":0}
EOF
)" "$url/wallet/triggersmartcontract")" || hs_abort "triggersmartcontract HTTP call failed"
  tx="$(printf '%s' "$wrapper" | jq -c '.transaction // empty' 2>/dev/null || true)"
  [ -n "$tx" ] || hs_abort "triggersmartcontract returned no .transaction: $wrapper"
  hs_broadcast "$node" "$priv" "$tx"
}

# ===========================================================================
# The SELFDESTRUCT victim contract.
#
# lib.sh's HS_CONTRACT_* has no kill path, so this scenario ships a second
# hand-assembled contract. Frontier-era opcodes only, same as lib.sh's, so no
# TVM proposal beyond the harness default committee is required.
#
#   init (11 bytes): PUSH1 0x26 DUP1 PUSH1 0x0b PUSH1 0x00 CODECOPY
#                    PUSH1 0x00 RETURN
#   runtime (38 bytes = 0x26):
#     0x00  36        CALLDATASIZE
#     0x01  15        ISZERO
#     0x02  600f      PUSH1 0x0f
#     0x04  57        JUMPI              ; no calldata   -> getter
#     0x05  36        CALLDATASIZE
#     0x06  6020      PUSH1 0x20
#     0x08  14        EQ
#     0x09  601b      PUSH1 0x1b
#     0x0b  57        JUMPI              ; 32 bytes      -> setter
#     0x0c  6023      PUSH1 0x23
#     0x0e  56        JUMP               ; anything else -> kill
#     0x0f  5b        JUMPDEST           ; getter
#     0x10  600054    PUSH1 0x00 SLOAD
#     0x13  600052    PUSH1 0x00 MSTORE
#     0x16  6020 6000 f3   RETURN 32 bytes
#     0x1b  5b        JUMPDEST           ; setter
#     0x1c  6000 35   PUSH1 0x00 CALLDATALOAD
#     0x1f  6000 55   PUSH1 0x00 SSTORE
#     0x22  00        STOP
#     0x23  5b        JUMPDEST           ; kill
#     0x24  33        CALLER
#     0x25  ff        SELFDESTRUCT
#
# The runtime hex is an INDEPENDENT oracle for eth_getCode: we chose these
# bytes; the chain never invented them.
# ===========================================================================
HA_VICTIM_RUNTIME_HEX="3615600f5736602014601b576023565b60005460005260206000f35b600035600055005b33ff"
HA_VICTIM_INIT_HEX="602680600b6000396000f3"
HA_VICTIM_DEPLOY_HEX="${HA_VICTIM_INIT_HEX}${HA_VICTIM_RUNTIME_HEX}"

# The 32-byte words we choose to store. These ARE the independent oracle for
# every storage assertion below.
HA_WORD_ZERO="0000000000000000000000000000000000000000000000000000000000000000"
HA_WORD_111="000000000000000000000000000000000000000000000000000000000000006f"
HA_WORD_222="00000000000000000000000000000000000000000000000000000000000000de"
HA_WORD_VICTIM="0000000000000000000000000000000000000000000000000000000000000abc"

# Transfer amounts (sun). Distinct and non-round, so a wrong-height answer
# cannot coincidentally match.
HA_AMOUNT_WITNESS_1=5000000
HA_AMOUNT_B_1=1234567
HA_AMOUNT_C_1=7654321
HA_AMOUNT_B_2=2000000
HA_AMOUNT_WITNESS_2=3000000
# WitnessCreateActuator.calcFee() == getAccountUpgradeCost() == 9_999_000_000
# (DynamicPropertiesStore.java:408), so the new witness needs more than that.
HA_AMOUNT_NEWSR=12000000000
HA_FREEZE_TRX=1000
HA_VOTE_COUNT=500

# Fresh keys: these addresses have never existed on any chain this harness
# builds, so their pre-transfer state is known a priori (absent).
HA_KEY_B="3333333333333333333333333333333333333333333333333333333333333333"
HA_KEY_C="4444444444444444444444444444444444444444444444444444444444444444"
# The 28th, NON-genesis witness. Distinct from HS_KEY_WITNESS1/2, ZION, SUN and
# from every HS_WITNESS_KEY_PREFIX-derived key.
HA_KEY_NEWSR="6666666666666666666666666666666666666666666666666666666666666666"

# ===========================================================================
# Cleanup: keep the run dir on failure (post-mortem), drop it on a clean pass.
# ===========================================================================
ha_on_exit() {
  hs_on_exit || true
  if [ "${HS_KEEP_WORKDIR:-0}" != "1" ] && [ "$HS_FAILURES" -eq 0 ] \
      && [ -n "$HS_RUN_DIR" ] && [ -d "$HS_RUN_DIR" ]; then
    case "$HS_RUN_DIR" in
      */java-tron-archive-harness/*) rm -rf "$HS_RUN_DIR" ;;
      *) hs_log "not removing an unexpected run dir: $HS_RUN_DIR" ;;
    esac
  fi
  return 0
}

# ===========================================================================
# PHASE 0 -- bring up a realistic 27-SR archive chain (+ one dormant SR key)
# ===========================================================================
hs_init history-accuracy
trap 'ha_on_exit' EXIT

HA_VERDICT_FILE="$HS_RUN_DIR/verdicts.tsv"
HA_TRACK_FILE="$HS_RUN_DIR/tracked.tsv"
HA_LIVE_FILE="$HS_RUN_DIR/live-ledger.tsv"
HA_TX_FILE="$HS_RUN_DIR/tx-ledger.tsv"
HA_PRODUCER_CACHE="$HS_RUN_DIR/producers.tsv"
: >"$HA_VERDICT_FILE"
: >"$HA_TRACK_FILE"
: >"$HA_LIVE_FILE"
: >"$HA_TX_FILE"
: >"$HA_PRODUCER_CACHE"

hs_step "materialize a ${HS_CFG_WITNESS_COUNT}-SR archive node"
if [ "$HS_CFG_WITNESS_COUNT" -lt 2 ]; then
  hs_die "this scenario needs a multi-SR chain (solid < head); HS_CFG_WITNESS_COUNT=$HS_CFG_WITNESS_COUNT"
fi
node="$(hs_new_node a 0)"

# --- scenario-local node.conf patch --------------------------------------
# 1. maintenanceTimeInterval: see the header. Needed ONLY so a new witness can
#    join the active set inside a test run.
# 2. a 28th localwitness key for a witness that does not exist yet.
#    ConsensusService.java:52-62 builds a Miner for every configured private
#    key and merely logs "Witness ... is not in witnessStore" for unknown ones,
#    so the key can lie dormant until WitnessCreateContract + a vote promote it
#    -- no restart, no config divergence for the other 27.
python3 - "$node/node.conf" "$HA_MAINTENANCE_INTERVAL_MS" "$HA_KEY_NEWSR" <<'PY'
import re, sys
path, interval, newkey = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(path).read()
patched, n = re.subn(r'maintenanceTimeInterval\s*=\s*\d+',
                     'maintenanceTimeInterval = ' + interval, text)
if n != 1:
    raise SystemExit('expected exactly one maintenanceTimeInterval, found %d' % n)
m = re.search(r'localwitness = \[\n(.*?)\n\]', patched, re.S)
if not m:
    raise SystemExit('no localwitness block in ' + path)
if newkey in m.group(1):
    raise SystemExit('the extra witness key collides with a generated one')
patched = patched[:m.end(1)] + ',\n  ' + newkey + patched[m.end(1):]
open(path, 'w').write(patched)
PY
hs_log "patched node.conf: maintenanceTimeInterval=$HA_MAINTENANCE_INTERVAL_MS ms," \
  "localwitness has $((HS_CFG_WITNESS_COUNT + 1)) keys (the 28th is dormant)"

hs_step "start and wait for readiness"
hs_node_start "$node"
hs_node_wait_ready "$node" 300
hs_wait_height "$node" 3 300 >/dev/null
hs_assert_repair_not_required "$node" "fresh ${HS_CFG_WITNESS_COUNT}-SR chain"

zion_eth="$(hs_eth_of_priv "$HS_KEY_ZION")"
b_eth="$(hs_eth_of_priv "$HA_KEY_B")"
b_base58="$(hs_base58_of_priv "$HA_KEY_B")"
c_eth="$(hs_eth_of_priv "$HA_KEY_C")"
c_base58="$(hs_base58_of_priv "$HA_KEY_C")"
w1_key="$(hs_witness_key_at 1)"
w1_base58="$(hs_witness_base58_at 1)"
w1_hex41="$(hs_hex41_of_priv "$w1_key")"
w1_eth="$(hs_eth_of_hex41 "$w1_hex41")"
nsr_base58="$(hs_base58_of_priv "$HA_KEY_NEWSR")"
nsr_hex41="$(hs_hex41_of_priv "$HA_KEY_NEWSR")"
nsr_eth="$(hs_eth_of_hex41 "$nsr_hex41")"
blackhole_hex41="$(ha_base58_to_hex41 "$HS_ADDR_BLACKHOLE")"
blackhole_eth="$(hs_eth_of_hex41 "$blackhole_hex41")"
hs_log "zion=$zion_eth B=$b_eth C=$c_eth"
hs_log "witness1(genesis)=$w1_eth newSR(non-genesis)=$nsr_eth blackhole=$blackhole_eth"

hs_step "read the chain parameters the reward arithmetic depends on"
chain_params="$(ha_http_post "$node" /wallet/getchainparameters '{}')"
ha_param() {
  printf '%s' "$chain_params" \
    | jq -r --arg k "$1" '(.chainParameter // [])[] | select(.key==$k) | .value // 0' \
    2>/dev/null || printf ''
}
witness_pay="$(ha_param getWitnessPayPerBlock)"
fee_pool_flag="$(ha_param getAllowTransactionFeePool)"
change_deleg="$(ha_param getChangeDelegation)"
[ -n "$witness_pay" ] || witness_pay=0
[ -n "$fee_pool_flag" ] || fee_pool_flag=0
[ -n "$change_deleg" ] || change_deleg=0
maint_start="$(ha_http_post "$node" /wallet/getnextmaintenancetime '{}' \
  | jq -r '.num // 0' 2>/dev/null || printf 0)"
hs_log "witnessPayPerBlock=$witness_pay sun, allowTransactionFeePool=$fee_pool_flag," \
  "changeDelegation=$change_deleg, nextMaintenanceTime=$maint_start"
if [ "$witness_pay" -le 0 ] 2>/dev/null; then
  hs_die "witnessPayPerBlock is $witness_pay -- block production pays nothing, so class (3) has nothing to observe"
fi

# ===========================================================================
# PHASE 1 -- record
# ===========================================================================
hs_step "PHASE 1 (record): start the live sampler"

ha_track zion balance "$zion_eth"
ha_track recipientB balance "$b_eth"
ha_track recipientC balance "$c_eth"
ha_track blackhole balance "$blackhole_eth"
ha_track witness1 balance "$w1_eth"
ha_track newSR balance "$nsr_eth"
# Live-only: the SR reward lands in AccountCapsule.allowance (Manager.java:2642),
# which no eth_* method projects. Sampled so the LIVE side of class (3) can be
# proved arithmetically, and so the withdrawal's expected delta is independent.
ha_track witness1Allow allowance "$w1_base58"
ha_track newSRAllow allowance "$nsr_base58"
# NOTE: the block signer is deliberately NOT a sampled item. It comes from
# ha_block_producer (the canonical block header), which never gaps when the
# pending guard suppresses a sample and cannot go stale.

ha_sample_now "$node" || hs_die "the very first live sample failed -- nothing can be recorded"
baseline_height="$HA_LAST_SAMPLE"
hs_log "baseline live sample taken at height $baseline_height"
baseline_b_live="$(ha_ledger_value "$baseline_height" recipientB)"
hs_log "live eth_getBalance(B) at the baseline height $baseline_height = $baseline_b_live"

hs_step "PHASE 1: fund the genesis SR so its historical balance is non-vacuous"
# A zero balance would make "archive == live" true for the trivial reason that
# both are 0x0. One transfer NOW gives witness1 a distinctive non-zero balance;
# every later height in the reward window is then a real value to get wrong.
txid="$(hs_tx_transfer "$node" "$HS_KEY_ZION" "$w1_base58" "$HA_AMOUNT_WITNESS_1")"
ha_await_tx "$node" "$txid"
ha_record_tx witness_fund_1 "$txid"

hs_step "PHASE 1: two TRX transfers out of one account, into two fresh accounts"
txid="$(hs_tx_transfer "$node" "$HS_KEY_ZION" "$b_base58" "$HA_AMOUNT_B_1")"
ha_await_tx "$node" "$txid"
ha_record_tx transfer_b1 "$txid"
h1_block="$HA_TX_BLOCK"; h1_fee="$HA_TX_FEE"

txid="$(hs_tx_transfer "$node" "$HS_KEY_ZION" "$c_base58" "$HA_AMOUNT_C_1")"
ha_await_tx "$node" "$txid"
ha_record_tx transfer_c1 "$txid"
h2_block="$HA_TX_BLOCK"; h2_fee="$HA_TX_FEE"

hs_step "PHASE 1: a SECOND transfer into B, so B has two deltas"
txid="$(hs_tx_transfer "$node" "$HS_KEY_ZION" "$b_base58" "$HA_AMOUNT_B_2")"
ha_await_tx "$node" "$txid"
ha_record_tx transfer_b2 "$txid"
h3_block="$HA_TX_BLOCK"; h3_fee="$HA_TX_FEE"

hs_step "PHASE 1: deploy the storage contract"
txid="$(ha_deploy "$node" "$HS_KEY_ZION" "$HS_CONTRACT_DEPLOY_HEX" ArchiveHistoryStorage)"
ha_await_tx "$node" "$txid"
ha_record_tx deploy_storage "$txid"
deploy_block="$HA_TX_BLOCK"
contract41="$(printf '%s' "$HA_RECEIPT" | jq -r '.contract_address // empty' 2>/dev/null || true)"
[ -n "$contract41" ] || hs_abort "the storage deploy produced no contract_address"
contract_eth="$(hs_eth_of_hex41 "$contract41")"
hs_log "storage contract at $contract41 ($contract_eth), deployed in block $deploy_block"

# ---------------------------------------------------------------------------
# LIVE PRECONDITION -- the trap a previous attempt fell into.
#
# Hand-rolled bytecode is worthless as a test subject if the chain never
# persists it: eth_getCode then answers "0x" (or 32 zero bytes) at every
# height, storage never changes, and the whole contract section "passes"
# vacuously. So before ANY historical claim, prove on the LIVE chain that the
# deployed runtime code is exactly what we asked for and that the getter works.
# These are hs_die (exit 2, inconclusive), not hs_fail: a chain that cannot run
# the contract has told us nothing about the archive.
# ---------------------------------------------------------------------------
live_code="$(ha_live_value "$node" code "$contract_eth" -)" \
  || hs_die "live eth_getCode on the fresh contract failed"
case "$live_code" in
  0x|0x0000000000000000000000000000000000000000000000000000000000000000|'')
    hs_die "the deployed contract has EMPTY runtime code on the LIVE chain ($live_code) -- the chain never persisted it, so no historical contract claim could mean anything" ;;
esac
if [ "$live_code" != "0x$HS_CONTRACT_RUNTIME_HEX" ]; then
  hs_die "live runtime code [$live_code] is not the code we deployed [0x$HS_CONTRACT_RUNTIME_HEX]"
fi
hs_log "LIVE precondition ok: runtime code is exactly the bytes we deployed"
live_get="$(ha_live_value "$node" call "$contract_eth" -)" || hs_die "live eth_call getter failed"
[ "$live_get" = "0x$HA_WORD_ZERO" ] \
  || hs_die "the fresh contract's getter returned [$live_get], expected a zero word"
hs_log "LIVE precondition ok: getter answers a zero word before any SSTORE"

ha_track storageCode code "$contract_eth"
ha_track storageSlot0 storage "$contract_eth" 0x0
ha_track storageGet call "$contract_eth"
ha_sample_now "$node" || true

hs_step "PHASE 1: drive slot 0 through 0 -> 111 -> 222 -> 0"
txid="$(ha_trigger "$node" "$HS_KEY_ZION" "$contract41" "$HA_WORD_111")"
ha_await_tx "$node" "$txid"
ha_record_tx set_111 "$txid"
s1_block="$HA_TX_BLOCK"
live_now="$(ha_live_value "$node" storage "$contract_eth" 0x0)" || live_now=""
[ "$live_now" = "0x$HA_WORD_111" ] \
  || hs_die "LIVE slot0 after set(111) is [$live_now]; the contract does not SSTORE, so nothing historical could be proved"

txid="$(ha_trigger "$node" "$HS_KEY_ZION" "$contract41" "$HA_WORD_222")"
ha_await_tx "$node" "$txid"
ha_record_tx set_222 "$txid"
s2_block="$HA_TX_BLOCK"

txid="$(ha_trigger "$node" "$HS_KEY_ZION" "$contract41" "$HA_WORD_ZERO")"
ha_await_tx "$node" "$txid"
ha_record_tx set_zero "$txid"
s3_block="$HA_TX_BLOCK"
live_now="$(ha_live_value "$node" storage "$contract_eth" 0x0)" || live_now=""
[ "$live_now" = "0x$HA_WORD_ZERO" ] \
  || hs_die "LIVE slot0 after set(0) is [$live_now]; the 0-transition never happened"
hs_log "slot0 transitions: 0@$deploy_block -> 111@$s1_block -> 222@$s2_block -> 0@$s3_block"

hs_step "PHASE 1: deploy the SELFDESTRUCT victim"
txid="$(ha_deploy "$node" "$HS_KEY_ZION" "$HA_VICTIM_DEPLOY_HEX" ArchiveHistoryVictim)"
ha_await_tx "$node" "$txid"
ha_record_tx deploy_victim "$txid"
victim41="$(printf '%s' "$HA_RECEIPT" | jq -r '.contract_address // empty' 2>/dev/null || true)"
[ -n "$victim41" ] || hs_abort "the victim deploy produced no contract_address"
victim_eth="$(hs_eth_of_hex41 "$victim41")"
victim_live_code="$(ha_live_value "$node" code "$victim_eth" -)" || victim_live_code=""
if [ "$victim_live_code" != "0x$HA_VICTIM_RUNTIME_HEX" ]; then
  hs_die "victim runtime code [$victim_live_code] is not the code we deployed [0x$HA_VICTIM_RUNTIME_HEX]"
fi
hs_log "victim contract at $victim41, runtime code verified LIVE"

ha_track victimCode code "$victim_eth"
ha_track victimSlot0 storage "$victim_eth" 0x0
ha_sample_now "$node" || true

txid="$(ha_trigger "$node" "$HS_KEY_ZION" "$victim41" "$HA_WORD_VICTIM")"
ha_await_tx "$node" "$txid"
ha_record_tx victim_set "$txid"
live_now="$(ha_live_value "$node" storage "$victim_eth" 0x0)" || live_now=""
[ "$live_now" = "0x$HA_WORD_VICTIM" ] \
  || hs_die "LIVE victim slot0 is [$live_now] after the set; the victim does not SSTORE"

hs_step "PHASE 1: SELFDESTRUCT the victim (1 byte of calldata takes the kill path)"
txid="$(ha_trigger "$node" "$HS_KEY_ZION" "$victim41" "ff")"
ha_await_tx "$node" "$txid"
ha_record_tx victim_kill "$txid"
victim_kill_block="$HA_TX_BLOCK"
victim_code_after="$(ha_live_value "$node" code "$victim_eth" -)" || victim_code_after=""
victim_slot_after="$(ha_live_value "$node" storage "$victim_eth" 0x0)" || victim_slot_after=""
hs_log "after SELFDESTRUCT in block $victim_kill_block: LIVE code=[$victim_code_after]" \
  "LIVE slot0=[$victim_slot_after]"
if [ "$victim_code_after" = "0x$HA_VICTIM_RUNTIME_HEX" ]; then
  hs_die "SELFDESTRUCT did not remove the runtime code on the LIVE chain -- this TVM configuration does not support the opcode, so the historical before/after claim is untestable"
fi

# ---------------------------------------------------------------------------
# PHASE 1 -- the SR reward window.
#
# No user transaction touches witness1 between here and the closing transfer,
# so every change to its account in this range comes from BLOCK_FINALIZE. Two
# full rotations guarantee witness1 signs at least twice.
#
# It matters that this window ends BEFORE the vote below: once votes exist,
# MaintenanceManager.java:131 calls IncentiveManager.reward(), which adds a
# standby allowance to every witness at each maintenance and would blur the
# "allowance steps only on signed blocks" signature.
# ---------------------------------------------------------------------------
hs_step "PHASE 1: SR reward window -- no user tx touches witness1"
reward_window_start="$(hs_head_num "$node")"
reward_target=$(( reward_window_start + 2 * HS_CFG_WITNESS_COUNT + 2 ))
hs_log "pumping from $reward_window_start to $reward_target (sampling every height)"
ha_pump_to "$node" "$reward_target" 900
reward_window_end="$HA_LAST_SAMPLE"

hs_step "PHASE 1: close the reward window with a second transfer to witness1"
txid="$(hs_tx_transfer "$node" "$HS_KEY_ZION" "$w1_base58" "$HA_AMOUNT_WITNESS_2")"
ha_await_tx "$node" "$txid"
ha_record_tx witness_fund_2 "$txid"
w_fund_2_block="$HA_TX_BLOCK"

# ---------------------------------------------------------------------------
# PHASE 1 -- turn the opaque allowance into an observable balance delta.
#
# Every step is best-effort and reports the exact blocking rule on failure;
# once the withdrawal lands, the historical assertions are hard PASS/FAIL.
# ---------------------------------------------------------------------------
hs_step "PHASE 1: grow a NON-genesis witness so a WithdrawBalanceContract is legal"
HA_WITHDRAW_OK=0
HA_WITHDRAW_BLOCK=""
HA_WITHDRAW_FEE=0
HA_WITHDRAW_AMOUNT=0
HA_NSR_BAL_BEFORE=""
HA_NSR_ALLOW_BEFORE=""
HA_NSR_ALLOW_AFTER=""
HA_WITHDRAW_BLOCKER=""

ha_grow_and_withdraw() {
  local url_hex reward_deadline allow nsr_account nsr_is_witness

  # 1. fund it (also creates the account)
  txid="$(hs_tx_transfer "$node" "$HS_KEY_ZION" "$nsr_base58" "$HA_AMOUNT_NEWSR")"
  ha_try_await "$node" "$txid" 240 || { HA_WITHDRAW_BLOCKER="funding the new SR failed: $HA_TRY_ERR"; return 1; }
  ha_record_tx newsr_fund "$txid"

  # 2. WitnessCreateContract -- WitnessCreateActuator.java:105 requires
  #    balance >= getAccountUpgradeCost() (9_999_000_000 sun) and burns it.
  url_hex="$(ha_hex_of_string "http://archive-history-harness.local")"
  if ! ha_try_build_and_send "$node" "$HA_KEY_NEWSR" /wallet/createwitness \
      "{\"owner_address\":\"$nsr_base58\",\"url\":\"$url_hex\",\"visible\":true}"; then
    HA_WITHDRAW_BLOCKER="WitnessCreateContract refused: $HA_TRY_ERR"
    return 1
  fi
  ha_try_await "$node" "$HA_TRY_TXID" 240 \
    || { HA_WITHDRAW_BLOCKER="WitnessCreateContract not mined: $HA_TRY_ERR"; return 1; }
  ha_record_tx newsr_create "$HA_TRY_TXID"

  # 3. TRON Power, then a vote big enough to outrank the genesis witnesses
  #    (HS_WITNESS_VOTES == 100 each).
  if ! ha_try_build_and_send "$node" "$HS_KEY_ZION" /wallet/freezebalance \
      "{\"owner_address\":\"$HS_ADDR_ZION\",\"frozen_balance\":$(( HA_FREEZE_TRX * 1000000 )),\"frozen_duration\":3,\"resource\":\"BANDWIDTH\",\"visible\":true}"; then
    HA_WITHDRAW_BLOCKER="FreezeBalanceContract refused: $HA_TRY_ERR"
    return 1
  fi
  ha_try_await "$node" "$HA_TRY_TXID" 240 \
    || { HA_WITHDRAW_BLOCKER="FreezeBalanceContract not mined: $HA_TRY_ERR"; return 1; }
  ha_record_tx zion_freeze "$HA_TRY_TXID"

  if ! ha_try_build_and_send "$node" "$HS_KEY_ZION" /wallet/votewitnessaccount \
      "{\"owner_address\":\"$HS_ADDR_ZION\",\"votes\":[{\"vote_address\":\"$nsr_base58\",\"vote_count\":$HA_VOTE_COUNT}],\"visible\":true}"; then
    HA_WITHDRAW_BLOCKER="VoteWitnessContract refused: $HA_TRY_ERR"
    return 1
  fi
  ha_try_await "$node" "$HA_TRY_TXID" 240 \
    || { HA_WITHDRAW_BLOCKER="VoteWitnessContract not mined: $HA_TRY_ERR"; return 1; }
  ha_record_tx zion_vote "$HA_TRY_TXID"

  # 4. wait for a maintenance round to promote it and for a system phase to
  #    credit its allowance (block reward when it signs, and/or
  #    IncentiveManager.reward at maintenance). Both are BLOCK_FINALIZE writes
  #    with no user transaction involved.
  hs_log "waiting for the new SR's allowance to become positive (maintenance every ${HA_MAINTENANCE_INTERVAL_MS}ms)"
  reward_deadline=$(( $(date +%s) + 420 ))
  while :; do
    ha_pump_to "$node" "$(( $(hs_head_num "$node") + 1 ))" 120
    # A READ failure is a different fact from "still zero", and conflating the
    # two is how this loop once span for its whole 420 s budget while the chain
    # had in fact promoted the witness. Distinguish them.
    if ! allow="$(ha_live_value "$node" allowance "$nsr_base58")"; then
      HA_WITHDRAW_BLOCKER="cannot read the new SR's allowance from /wallet/getaccount"
      return 1
    fi
    [ -n "$allow" ] || allow=0
    if [ "$allow" -gt 0 ] 2>/dev/null; then
      # Compute the diagnostic on its own line. Inlining a $( ) that itself
      # contains \"-escaped quotes into a double-quoted hs_log argument splits
      # the word and sends a malformed body, which then reports is_witness=false
      # for an account that IS a witness -- a misleading log is worse than none.
      nsr_account="$(ha_http_post "$node" /wallet/getaccount \
        "{\"address\":\"$nsr_base58\",\"visible\":true}")"
      nsr_is_witness="$(printf '%s' "$nsr_account" | jq -r '.is_witness // false' 2>/dev/null | head -1)"
      hs_log "the new SR has accrued $allow sun of allowance (is_witness=$nsr_is_witness)"
      break
    fi
    if [ "$(date +%s)" -ge "$reward_deadline" ]; then
      HA_WITHDRAW_BLOCKER="the new SR never accrued any allowance within 420s (never promoted or never signed)"
      return 1
    fi
  done

  # 5. WithdrawBalanceContract. WithdrawBalanceActuator.java:
  #      :112-120  rejects genesis ("guard representative") witnesses -- this
  #                one is not in genesis.block.witnesses, so it passes
  #      :122-128  requires now - latestWithdrawTime >= witnessAllowanceFrozenTime
  #                * FROZEN_PERIOD; latestWithdrawTime is 0 for an account that
  #                has never withdrawn, so the 24h rule passes exactly once
  #      :130-133  requires allowance > 0 (step 4)
  #      :151      calcFee() == 0
  #      :63-70    execute: balance += allowance; allowance = 0
  HA_NSR_BAL_BEFORE="$(ha_live_value "$node" balance "$nsr_eth")" || HA_NSR_BAL_BEFORE=""
  HA_NSR_ALLOW_BEFORE="$(ha_live_value "$node" allowance "$nsr_base58")" || HA_NSR_ALLOW_BEFORE=""
  if ! ha_try_build_and_send "$node" "$HA_KEY_NEWSR" /wallet/withdrawbalance \
      "{\"owner_address\":\"$nsr_base58\",\"visible\":true}"; then
    HA_WITHDRAW_BLOCKER="WithdrawBalanceContract refused: $HA_TRY_ERR"
    return 1
  fi
  ha_try_await "$node" "$HA_TRY_TXID" 240 \
    || { HA_WITHDRAW_BLOCKER="WithdrawBalanceContract not mined: $HA_TRY_ERR"; return 1; }
  ha_record_tx newsr_withdraw "$HA_TRY_TXID"
  HA_WITHDRAW_BLOCK="$HA_TX_BLOCK"
  HA_WITHDRAW_FEE="$HA_TX_FEE"
  HA_WITHDRAW_AMOUNT="$(printf '%s' "$HA_RECEIPT" | jq -r '.withdraw_amount // 0' 2>/dev/null || printf 0)"
  HA_NSR_ALLOW_AFTER="$(ha_live_value "$node" allowance "$nsr_base58")" || HA_NSR_ALLOW_AFTER=""
  hs_log "withdrawal in block $HA_WITHDRAW_BLOCK: receipt.withdraw_amount=$HA_WITHDRAW_AMOUNT sun," \
    "fee=$HA_WITHDRAW_FEE, live allowance $HA_NSR_ALLOW_BEFORE -> $HA_NSR_ALLOW_AFTER"
  HA_WITHDRAW_OK=1
  return 0
}

ha_grow_and_withdraw || hs_log "the withdrawal path stopped: $HA_WITHDRAW_BLOCKER"

last_interesting="$(hs_head_num "$node")"
maint_end="$(ha_http_post "$node" /wallet/getnextmaintenancetime '{}' \
  | jq -r '.num // 0' 2>/dev/null || printf 0)"

hs_step "PHASE 1: let the archive solidify and publish past the last interesting height"
solid_lag=$(( $(hs_head_num "$node") - $(hs_solid_num "$node") ))
hs_log "current head/solid lag = $solid_lag blocks"
ha_pump_to "$node" "$(( last_interesting + solid_lag + 6 ))" 900
hs_wait_solidified "$node" "$last_interesting" 600 >/dev/null
# DO NOT call hs_wait_archive_drained here. It waits for
# tron:archive_state{type="oldest_inflight_block"} == -1, which on a live
# multi-SR chain never happens: solid == head - 18, so ~19-20 blocks are
# journalled-but-unpublished at all times (measured on this run:
# inflight_blocks=19, oldest_inflight_block=55 at head 74). Draining is only
# meaningful once production has stopped. The authoritative "is this height
# queryable" gate is the read path's own range check, which is what
# hs_wait_hist_available probes.
hs_wait_hist_available "$node" "$zion_eth" "$last_interesting" 600
published_head="$last_interesting"
hs_log "phase 1 complete: $(wc -l <"$HA_LIVE_FILE" | tr -d ' ') live samples," \
  "$(wc -l <"$HA_TX_FILE" | tr -d ' ') transactions, replayable up to height $published_head"

# ===========================================================================
# PHASE 2 -- replay
# ===========================================================================
ha_bal() { ha_hist_value "$node" balance "$1" - "$2" || printf ''; }
ha_stor() { ha_hist_value "$node" storage "$1" 0x0 "$2" || printf ''; }
ha_code() { ha_hist_value "$node" code "$1" - "$2" || printf ''; }
ha_get() { ha_hist_value "$node" call "$1" - "$2" || printf ''; }

# ha_delta_check <name> <addr> <hHigh> <hLow> <expected> <source> <note>
# Asserts bal(hHigh) - bal(hLow) == expected. Both operands come from the
# archive, but the EXPECTED VALUE never does: it is amount / fee /
# withdraw_amount, i.e. tx semantics or the node's own receipt.
ha_delta_check() {
  local name="$1" addr="$2" hhigh="$3" hlow="$4" want="$5" source="$6" note="$7"
  local hi lo hidec lodec got
  hi="$(ha_bal "$addr" "$hhigh")"
  lo="$(ha_bal "$addr" "$hlow")"
  hidec="$(ha_hex_to_dec "$hi")"
  lodec="$(ha_hex_to_dec "$lo")"
  if [ "$hidec" = "ERR" ] || [ "$lodec" = "ERR" ]; then
    ha_bad "$name" "$hlow->$hhigh" "$source" "unusable archive answers [$lo] [$hi]"
    return 0
  fi
  got=$(( hidec - lodec ))
  ha_eq "$name" "$hlow->$hhigh" "$source" "$want" "$got" "$note"
}

hs_step "PHASE 2 (replay): class (1) NORMAL transactions"

# (1a) sender delta == amount + fee.
# INDEPENDENT SOURCE tx+receipt: the amount is a constant this script chose;
# the fee is the node's own receipt from the canonical execution path.
ha_delta_check normal.zion.debit.transferB1 "$zion_eth" \
  "$((h1_block - 1))" "$h1_block" "$(( HA_AMOUNT_B_1 + h1_fee ))" "tx+rcpt" \
  "amount=$HA_AMOUNT_B_1 fee=$h1_fee"
ha_delta_check normal.zion.debit.transferC1 "$zion_eth" \
  "$((h2_block - 1))" "$h2_block" "$(( HA_AMOUNT_C_1 + h2_fee ))" "tx+rcpt" \
  "amount=$HA_AMOUNT_C_1 fee=$h2_fee"
ha_delta_check normal.zion.debit.transferB2 "$zion_eth" \
  "$((h3_block - 1))" "$h3_block" "$(( HA_AMOUNT_B_2 + h3_fee ))" "tx+rcpt" \
  "amount=$HA_AMOUNT_B_2 fee=$h3_fee"

# (1b) recipient delta == amount (the recipient pays no fee).
# INDEPENDENT SOURCE tx.
ha_delta_check normal.recipientB.credit.transferB1 "$b_eth" \
  "$h1_block" "$((h1_block - 1))" "$HA_AMOUNT_B_1" "tx" "amount=$HA_AMOUNT_B_1"
ha_delta_check normal.recipientC.credit.transferC1 "$c_eth" \
  "$h2_block" "$((h2_block - 1))" "$HA_AMOUNT_C_1" "tx" "amount=$HA_AMOUNT_C_1"
ha_delta_check normal.recipientB.credit.transferB2 "$b_eth" \
  "$h3_block" "$((h3_block - 1))" "$HA_AMOUNT_B_2" "tx" "amount=$HA_AMOUNT_B_2"

# (1c) B's ABSOLUTE balance. B did not exist, so after the first transfer its
# balance IS the amount and after the second it is the sum.
# INDEPENDENT SOURCE tx.
ha_eq normal.recipientB.absolute.afterFirst "$h1_block" "tx" \
  "$(printf '0x%x' "$HA_AMOUNT_B_1")" "$(ha_bal "$b_eth" "$h1_block")" \
  "a fresh account credited exactly once"
ha_eq normal.recipientB.absolute.afterSecond "$h3_block" "tx" \
  "$(printf '0x%x' "$(( HA_AMOUNT_B_1 + HA_AMOUNT_B_2 ))")" "$(ha_bal "$b_eth" "$h3_block")" \
  "two credits, no debits"

# (1d) THE FALL-TO-LATEST TRAP: an intermediate height must not answer with the
# final value.  INDEPENDENT SOURCE arith.
ha_ne normal.recipientB.intermediateNotFinal "$h1_block" "arith" \
  "$(printf '0x%x' "$(( HA_AMOUNT_B_1 + HA_AMOUNT_B_2 ))")" "$(ha_bal "$b_eth" "$h1_block")" \
  "H$h1_block must not answer with the H$h3_block value"
ha_ne normal.zion.intermediateNotFinal "$h1_block" "arith" \
  "$(ha_bal "$zion_eth" "$h3_block")" "$(ha_bal "$zion_eth" "$h1_block")" \
  "three debits => three distinct balances"

# (1e) an address queried BEFORE it ever existed.
# INDEPENDENT SOURCE tx: HA_KEY_B is generated by this harness and was never
# funded before block $h1_block. ArchiveJsonRpcStateAdapter.getBalance:60 maps
# an absent account to 0x0 ("missing account = zero balance, not an archive
# gap") and getCode:85 maps it to "0x". Both are RESULTS, not errors.
pre_exist_bal="$(ha_bal "$b_eth" "$baseline_height")"
pre_exist_code="$(ha_code "$b_eth" "$baseline_height")"
ha_eq normal.recipientB.beforeExistence.balance "$baseline_height" "tx" \
  "0x0" "$pre_exist_bal" "an absent account renders as 0x0, not an error"
ha_eq normal.recipientB.beforeExistence.code "$baseline_height" "tx" \
  "0x" "$pre_exist_code" "absent code renders as 0x"
ha_eq normal.recipientB.beforeExistence.matchesLive "$baseline_height" "live@H" \
  "$baseline_b_live" "$pre_exist_bal" "live answer recorded when head was exactly $baseline_height"

# (1f) the fee sink: every fee-bearing transaction credits the blackhole
# account (Manager.java:1302) and no user transaction names it.
# INDEPENDENT SOURCE receipt.
ha_delta_check system.blackhole.credit.transferB1 "$blackhole_eth" \
  "$h1_block" "$((h1_block - 1))" "$h1_fee" "receipt" "fee=$h1_fee sun"
ha_delta_check system.blackhole.credit.transferC1 "$blackhole_eth" \
  "$h2_block" "$((h2_block - 1))" "$h2_fee" "receipt" "fee=$h2_fee sun"

hs_step "PHASE 2 (replay): class (2) CONTRACT transactions"

# (2a) eth_getCode before and after the deploy block.
# INDEPENDENT SOURCE tx: HS_CONTRACT_RUNTIME_HEX is the constant we deployed;
# the pre-deploy answer must be empty because the address did not exist.
ha_eq contract.code.beforeDeploy "$((deploy_block - 1))" "tx" \
  "0x" "$(ha_code "$contract_eth" "$((deploy_block - 1))")" \
  "the contract address did not exist yet"
ha_eq contract.code.atDeploy "$deploy_block" "tx" \
  "0x$HS_CONTRACT_RUNTIME_HEX" "$(ha_code "$contract_eth" "$deploy_block")" \
  "the exact runtime bytes this script deployed"
ha_eq contract.code.stillPresentAtLastSet "$s3_block" "tx" \
  "0x$HS_CONTRACT_RUNTIME_HEX" "$(ha_code "$contract_eth" "$s3_block")" \
  "code survives the storage transitions"

# (2b) slot 0 at every height of the 0 -> 111 -> 222 -> 0 walk.
# INDEPENDENT SOURCE tx: each expected word is the 32-byte calldata we sent.
ha_eq contract.slot0.atDeploy "$deploy_block" "tx" \
  "0x$HA_WORD_ZERO" "$(ha_stor "$contract_eth" "$deploy_block")" "never written yet"
ha_eq contract.slot0.at111 "$s1_block" "tx" \
  "0x$HA_WORD_111" "$(ha_stor "$contract_eth" "$s1_block")" "SSTORE(111)"
ha_eq contract.slot0.justBefore222 "$((s2_block - 1))" "tx" \
  "0x$HA_WORD_111" "$(ha_stor "$contract_eth" "$((s2_block - 1))")" "111 still in force"
ha_eq contract.slot0.at222 "$s2_block" "tx" \
  "0x$HA_WORD_222" "$(ha_stor "$contract_eth" "$s2_block")" "SSTORE(222)"
ha_eq contract.slot0.justBeforeZero "$((s3_block - 1))" "tx" \
  "0x$HA_WORD_222" "$(ha_stor "$contract_eth" "$((s3_block - 1))")" "222 still in force"
ha_eq contract.slot0.backToZero "$s3_block" "tx" \
  "0x$HA_WORD_ZERO" "$(ha_stor "$contract_eth" "$s3_block")" "SSTORE(0)"

# (2c) the final 0 must be distinguishable from "missing".
# ArchiveJsonRpcStateAdapter.getStorageAt:110 renders BOTH present-zero and
# absent as the zero word, so the zero alone proves nothing. What distinguishes
# them is that the archive tracked the TRANSITION: 222 at s3-1, 0 at s3, with
# the contract still alive at s3. A dropped key would read zero at s3-1 too.
# INDEPENDENT SOURCE arith over the two adjacent tx-derived values.
zero_pre="$(ha_stor "$contract_eth" "$((s3_block - 1))")"
zero_post="$(ha_stor "$contract_eth" "$s3_block")"
zero_code="$(ha_code "$contract_eth" "$s3_block")"
if [ "$zero_pre" = "0x$HA_WORD_222" ] && [ "$zero_post" = "0x$HA_WORD_ZERO" ] \
    && [ "$zero_code" = "0x$HS_CONTRACT_RUNTIME_HEX" ]; then
  ha_ok contract.slot0.zeroIsPresentNotMissing "$((s3_block - 1))->$s3_block" "arith" \
    "222 -> 0 transition recorded while the contract is still alive"
else
  ha_bad contract.slot0.zeroIsPresentNotMissing "$((s3_block - 1))->$s3_block" "arith" \
    "pre=$zero_pre post=$zero_post code=$zero_code"
fi

# (2d) eth_call the getter at four heights: same expectations, different read
# path -- the archive must build a whole execution context at height H.
# INDEPENDENT SOURCE tx.
ha_eq contract.call.atDeploy "$deploy_block" "tx" \
  "0x$HA_WORD_ZERO" "$(ha_get "$contract_eth" "$deploy_block")" "getter before any SSTORE"
ha_eq contract.call.at111 "$s1_block" "tx" \
  "0x$HA_WORD_111" "$(ha_get "$contract_eth" "$s1_block")" "getter sees 111"
ha_eq contract.call.at222 "$s2_block" "tx" \
  "0x$HA_WORD_222" "$(ha_get "$contract_eth" "$s2_block")" "getter sees 222"
ha_eq contract.call.atZero "$s3_block" "tx" \
  "0x$HA_WORD_ZERO" "$(ha_get "$contract_eth" "$s3_block")" "getter sees 0 again"

# (2e) SELFDESTRUCT, code and storage at the exact heights either side.
# INDEPENDENT SOURCES: before -> tx (the runtime hex and the word we stored);
# after -> live@H (what the canonical chain itself reported the instant head
# was the kill height). TransactionTrace.deleteContract:373 removes abi, code,
# account and contract but NOT the storage rows, so the post-kill storage
# semantics are the chain's to define; the archive's job is to reproduce them.
ha_eq selfdestruct.code.before "$((victim_kill_block - 1))" "tx" \
  "0x$HA_VICTIM_RUNTIME_HEX" "$(ha_code "$victim_eth" "$((victim_kill_block - 1))")" \
  "the runtime bytes this script deployed"
ha_eq selfdestruct.slot0.before "$((victim_kill_block - 1))" "tx" \
  "0x$HA_WORD_VICTIM" "$(ha_stor "$victim_eth" "$((victim_kill_block - 1))")" \
  "the word this script stored"
ha_eq selfdestruct.code.after "$victim_kill_block" "live@H" \
  "$victim_code_after" "$(ha_code "$victim_eth" "$victim_kill_block")" \
  "live eth_getCode captured at the kill height"
ha_eq selfdestruct.slot0.after "$victim_kill_block" "live@H" \
  "$victim_slot_after" "$(ha_stor "$victim_eth" "$victim_kill_block")" \
  "live eth_getStorageAt captured at the kill height"
ha_ne selfdestruct.code.changed "$victim_kill_block" "arith" \
  "0x$HA_VICTIM_RUNTIME_HEX" "$(ha_code "$victim_eth" "$victim_kill_block")" \
  "the archive must not answer with the pre-kill code"

hs_step "PHASE 2 (replay): class (3) SYSTEM transactions -- SR rewards"

# ---------------------------------------------------------------------------
# (3a) THE PREMISE, established entirely WITHOUT the archive: block production
# writes reward state to witness1's account, per produced block, and NOTHING
# else in the window does.
#
# INDEPENDENT SOURCES: live@H (the allowance sampled while head was exactly
# that height) and the block header's witness_address (also sampled live).
# ---------------------------------------------------------------------------
allow_start_pair="$(ha_sample_at_or_above witness1Allow "$reward_window_start")"
allow_end_pair="$(ha_sample_at_or_below witness1Allow "$reward_window_end")"
allow_start_h="${allow_start_pair%% *}"; allow_start="${allow_start_pair##* }"
allow_end_h="${allow_end_pair%% *}"; allow_end="${allow_end_pair##* }"
if [ -z "$allow_start_pair" ] || [ -z "$allow_end_pair" ] \
    || [ "$allow_start_h" -ge "$allow_end_h" ] 2>/dev/null; then
  ha_info system.witness1.liveRewardAccrues "$reward_window_start-$reward_window_end" \
    "no usable pair of clean allowance samples in the window (start=[$allow_start_pair] end=[$allow_end_pair])"
elif [ "$allow_end" -gt "$allow_start" ] 2>/dev/null; then
  ha_ok system.witness1.liveRewardAccrues "$allow_start_h-$allow_end_h" "live@H" \
    "allowance $allow_start -> $allow_end sun with no user tx touching the account"
else
  ha_bad system.witness1.liveRewardAccrues "$allow_start_h-$allow_end_h" "live@H" \
    "allowance did not grow ($allow_start -> $allow_end): there is no reward write to archive"
fi

# ---------------------------------------------------------------------------
# (3b) THE STEP SHAPE. The allowance must be flat at every height witness1 did
# NOT sign and must step by exactly witnessPayPerBlock at every height it did.
# A BLOCK_FINALIZE write that was dropped, or attributed to the wrong block,
# shows up here as a missing or misplaced step.
# INDEPENDENT SOURCES: arith over witnessPayPerBlock (/wallet/getchainparameters)
# and the block header producer. Neither comes from the archive.
# ---------------------------------------------------------------------------
step_total=0
step_bad=0
step_signed=0
step_first_bad=""
h="$reward_window_start"
while [ "$h" -lt "$reward_window_end" ]; do
  prev_allow="$(ha_ledger_value "$h" witness1Allow)"
  h=$(( h + 1 ))
  cur_allow="$(ha_ledger_value "$h" witness1Allow)"
  who="$(ha_block_producer "$node" "$h")" || who=""
  if [ -z "$prev_allow" ] || [ -z "$cur_allow" ] || [ -z "$who" ]; then
    continue
  fi
  step_total=$(( step_total + 1 ))
  if [ "$who" = "$w1_hex41" ]; then
    step_signed=$(( step_signed + 1 ))
    want_allow=$(( prev_allow + witness_pay ))
  else
    want_allow="$prev_allow"
  fi
  if [ "$cur_allow" != "$want_allow" ]; then
    step_bad=$(( step_bad + 1 ))
    [ -n "$step_first_bad" ] || \
      step_first_bad="h=$h signer=$( [ "$who" = "$w1_hex41" ] && printf witness1 || printf other ) allowance $prev_allow -> $cur_allow, expected $want_allow"
  fi
done
if [ "$step_total" -eq 0 ] || [ "$step_signed" -eq 0 ]; then
  ha_info system.witness1.allowanceStepsMatchSignedBlocks "$reward_window_start-$reward_window_end" \
    "only $step_total consecutive sampled pairs and $step_signed signed by witness1 -- too few to assert the step shape"
elif [ "$step_bad" -eq 0 ]; then
  ha_ok system.witness1.allowanceStepsMatchSignedBlocks "$reward_window_start-$reward_window_end" \
    "arith" "$step_total heights: flat except the $step_signed witness1 signed, each +$witness_pay sun"
else
  ha_bad system.witness1.allowanceStepsMatchSignedBlocks "$reward_window_start-$reward_window_end" \
    "arith" "$step_bad/$step_total heights wrong; first: $step_first_bad"
fi

# ---------------------------------------------------------------------------
# (3c) THE ARCHIVE SIDE of the genesis SR. witness1's account is rewritten by
# BLOCK_FINALIZE on every block it signs, and its balance must stay exactly
# what the funding transfer left. Read at the SIGNING heights, this is the
# sharpest available form: a finalize-phase capture that recorded a wrong
# previous value breaks precisely here.
# INDEPENDENT SOURCE arith: HA_AMOUNT_WITNESS_1 is a constant we chose.
# ---------------------------------------------------------------------------
expected_w1_hex="$(printf '0x%x' "$HA_AMOUNT_WITNESS_1")"
signed_checked=0
signed_bad=0
first_bad=""
h="$reward_window_start"
while [ "$h" -lt "$reward_window_end" ]; do
  h=$(( h + 1 ))
  who="$(ha_block_producer "$node" "$h")" || who=""
  if [ "$who" = "$w1_hex41" ]; then
    signed_checked=$(( signed_checked + 1 ))
    got="$(ha_bal "$w1_eth" "$h")"
    if [ "$got" != "$expected_w1_hex" ]; then
      signed_bad=$(( signed_bad + 1 ))
      [ -n "$first_bad" ] || first_bad="h=$h got=[$got]"
    fi
  fi
done
if [ "$signed_checked" -eq 0 ]; then
  ha_info system.witness1.balanceAtSigningHeights "$reward_window_start-$reward_window_end" \
    "witness1 signed no sampled block in the window, so no finalize-height read could be made"
elif [ "$signed_bad" -eq 0 ]; then
  ha_ok system.witness1.balanceAtSigningHeights "$reward_window_start-$reward_window_end" "arith" \
    "$signed_checked signing height(s) all read $expected_w1_hex (= the funded amount)"
else
  ha_bad system.witness1.balanceAtSigningHeights "$reward_window_start-$reward_window_end" "arith" \
    "$signed_bad/$signed_checked signing heights wrong; first $first_bad, expected $expected_w1_hex"
fi

ha_eq system.witness1.balanceAfterSecondFund "$w_fund_2_block" "arith" \
  "$(printf '0x%x' "$(( HA_AMOUNT_WITNESS_1 + HA_AMOUNT_WITNESS_2 ))")" \
  "$(ha_bal "$w1_eth" "$w_fund_2_block")" "both funding transfers applied"
ha_eq system.witness1.balanceBeforeSecondFund "$((w_fund_2_block - 1))" "arith" \
  "$expected_w1_hex" "$(ha_bal "$w1_eth" "$((w_fund_2_block - 1))")" \
  "only the first transfer applied, despite the finalize writes in between"

# ---------------------------------------------------------------------------
# (3d) THE CLOSING OF THE LOOP: the withdrawal turns BLOCK_FINALIZE-written
# allowance into balance, which eth_getBalance CAN see.
#
# INDEPENDENT SOURCES:
#   receipt  .withdraw_amount (WithdrawBalanceActuator:70 ret.setWithdrawAmount)
#            and .fee
#   live@H   the allowance sampled the instant head was the pre-withdrawal
#            height -- an entirely separate observation of the same number
#   arith    balance(WB) - balance(WB-1) == withdraw_amount - fee
# ---------------------------------------------------------------------------
if [ "$HA_WITHDRAW_OK" = "1" ]; then
  wb="$HA_WITHDRAW_BLOCK"
  # THE balance-delta proof. Neither operand's EXPECTED value comes from the
  # archive: the amount is the node's own receipt field, the fee likewise.
  ha_delta_check system.newSR.withdrawBalanceDelta "$nsr_eth" \
    "$wb" "$((wb - 1))" "$(( HA_WITHDRAW_AMOUNT - HA_WITHDRAW_FEE ))" "rcpt+ari" \
    "withdraw_amount=$HA_WITHDRAW_AMOUNT fee=$HA_WITHDRAW_FEE (calcFee()==0 unless bandwidth was burnt)"
  # the credited balance must not appear one block early
  ha_ne system.newSR.creditNotEarly "$((wb - 1))" "arith" \
    "$(ha_bal "$nsr_eth" "$wb")" "$(ha_bal "$nsr_eth" "$((wb - 1))")" \
    "the withdrawal must be invisible at WB-1"

  # Cross-check the receipt against a SECOND, independent observation of the
  # same number, and confirm the accrual field drained.
  #
  # Anchored on the nearest CLEAN sample heights P <= WB-1 and S >= WB rather
  # than on WB-1/WB themselves: the withdrawal is pending during WB-1, so the
  # pending guard suppresses that sample by design. Between two clean heights
  # the allowance can only move by block rewards, so
  #     allowance(S) - allowance(P) == pay * signedBlocks(P, S] - withdraw_amount
  # with pay from /wallet/getchainparameters, the signer from the block header,
  # and withdraw_amount from the receipt. Nothing archive-derived.
  pre_pair="$(ha_sample_at_or_below newSRAllow "$((wb - 1))")"
  post_pair="$(ha_sample_at_or_above newSRAllow "$wb")"
  if [ -n "$pre_pair" ] && [ -n "$post_pair" ]; then
    pre_h="${pre_pair%% *}"; pre_v="${pre_pair##* }"
    post_h="${post_pair%% *}"; post_v="${post_pair##* }"
    nsr_signed="$(ha_signed_between "$node" "$pre_h" "$post_h" "$nsr_hex41")"
    if [ "$nsr_signed" = "ERR" ]; then
      ha_info system.newSR.allowanceDrainedLive "$pre_h-$post_h" \
        "could not read every block header in ($pre_h,$post_h], so the allowance arithmetic is unproven"
    else
      ha_eq system.newSR.allowanceDrainedLive "$pre_h->$post_h" "rcpt+ari" \
        "$(( pre_v + nsr_signed * witness_pay - HA_WITHDRAW_AMOUNT ))" "$post_v" \
        "allowance($pre_h)=$pre_v + $nsr_signed signed block(s) x $witness_pay - withdraw_amount"
    fi
    # The withdrawn amount must equal what had accrued by WB-1, carried from
    # the last clean sample by the same block-reward arithmetic.
    nsr_signed_pre="$(ha_signed_between "$node" "$pre_h" "$((wb - 1))" "$nsr_hex41")"
    if [ "$nsr_signed_pre" = "ERR" ]; then
      ha_info system.newSR.receiptMatchesLiveAllowance "$pre_h-$((wb - 1))" \
        "could not read every block header in ($pre_h,$((wb - 1))], so the accrual arithmetic is unproven"
    else
      ha_eq system.newSR.receiptMatchesLiveAllowance "$pre_h->$((wb - 1))" "live@H" \
        "$(( pre_v + nsr_signed_pre * witness_pay ))" "$HA_WITHDRAW_AMOUNT" \
        "receipt.withdraw_amount vs the live allowance carried forward from height $pre_h"
    fi
  else
    ha_info system.newSR.allowanceDrainedLive "$wb" \
      "no clean allowance sample either side of the withdrawal (pre=[$pre_pair] post=[$post_pair])"
  fi
else
  ha_info system.newSR.withdrawBalanceDelta "-" \
    "the balance-delta proof was NOT achievable: $HA_WITHDRAW_BLOCKER"
fi

# ---------------------------------------------------------------------------
# (3e) what is still NOT proven. Say it out loud rather than dressing it as a
# pass.
# ---------------------------------------------------------------------------
ha_info system.witness1.allowanceValueThroughArchive "$reward_window_start-$reward_window_end" \
  "the archive read surface is eth_getBalance/eth_getCode/eth_getStorageAt only (ArchiveJsonRpcStateAdapter), so the ARCHIVED allowance value itself cannot be read back at a historical height; only its withdrawn balance effect can"
ha_info system.dynamicProperties "$reward_window_start-$reward_window_end" \
  "reward-relevant dynamic properties (witnessPayPerBlock, currentCycleNumber, nextMaintenanceTime) are not exposed by any historical RPC method, so their archived values are unproven"

# (3f) maintenance boundary
if [ "$maint_end" != "$maint_start" ]; then
  ha_eq system.maintenanceBoundary.crossed "$baseline_height-$last_interesting" "live@H" \
    "$(ha_ledger_value "$last_interesting" zion)" "$(ha_bal "$zion_eth" "$last_interesting")" \
    "nextMaintenanceTime moved $maint_start -> $maint_end during the run, so history spans >=1 maintenance"
else
  ha_info system.maintenanceBoundary.crossed "$baseline_height-$last_interesting" \
    "NO maintenance boundary occurred in this window (nextMaintenanceTime stayed $maint_start), so cross-maintenance history is NOT covered"
fi

# ===========================================================================
# PHASE 2 -- the mass replay: every live sample, every height
# ===========================================================================
hs_step "PHASE 2 (replay): every recorded live sample vs the archive"

ha_replay_label() {
  local label="$1" kind a1 a2 total bad first_bad minh maxh h l k want got skipped extra
  kind="$(awk -F'\t' -v L="$label" '$2==L { print $3; exit }' "$HA_LIVE_FILE")"
  a1="$(awk -F'\t' -v L="$label" '$2==L { print $4; exit }' "$HA_LIVE_FILE")"
  a2="$(awk -F'\t' -v L="$label" '$2==L { print $5; exit }' "$HA_LIVE_FILE")"
  case "$kind" in
    balance|code|storage|call) : ;;
    *)
      ha_info "replay.$label" "-" \
        "kind '$kind' is live-only: no historical RPC method projects it, so its samples are unproven"
      return 0 ;;
  esac
  total=0; bad=0; skipped=0; first_bad=""; minh=""; maxh=""
  while IFS="$HA_TAB" read -r h l k _a1 _a2 want; do
    [ "$l" = "$label" ] || continue
    if [ "$h" -gt "$published_head" ] 2>/dev/null; then
      skipped=$(( skipped + 1 ))
      continue
    fi
    got="$(ha_hist_value "$node" "$kind" "$a1" "$a2" "$h" || printf '')"
    total=$(( total + 1 ))
    [ -n "$minh" ] || minh="$h"
    maxh="$h"
    if [ "$got" != "$want" ]; then
      bad=$(( bad + 1 ))
      if [ -z "$first_bad" ]; then
        if [ -z "$got" ]; then
          first_bad="h=$h archive refused: $(ha_hist_error "$node" "$kind" "$a1" "$a2" "$h")"
        else
          first_bad="h=$h live=[$want] archive=[$got]"
        fi
      fi
    fi
  done <"$HA_LIVE_FILE"
  extra=""
  [ "$skipped" -eq 0 ] || extra=" (+$skipped above the published head)"
  if [ "$total" -eq 0 ]; then
    ha_info "replay.$label" "-" "no replayable sample; all $skipped are above the published head $published_head"
  elif [ "$bad" -eq 0 ]; then
    ha_ok "replay.$label" "$minh-$maxh" "live@H" \
      "$total height(s) match the live value recorded at that height$extra"
  else
    ha_bad "replay.$label" "$minh-$maxh" "live@H" \
      "$bad/$total heights disagree with the live record; first: $first_bad"
  fi
}

for label in $(cut -f2 "$HA_LIVE_FILE" | sort -u); do
  ha_replay_label "$label"
done

# ===========================================================================
# PHASE 2 -- fail-closed shape (a correct archive must also refuse correctly)
# ===========================================================================
hs_step "PHASE 2: the archive still fails closed above the published head"
before_failclosed="$HS_FAILURES"
hs_assert_hist_fails_closed "$node" eth_getBalance \
  "[\"$zion_eth\",\"0xffffff\"]" "-32000" "archive history unavailable" \
  "a height far above the published head"
if [ "$HS_FAILURES" -eq "$before_failclosed" ]; then
  HA_PASS=$((HA_PASS + 1))
  ha_row PASS archive.failsClosedAboveHead "0xffffff" "adapter" \
    "-32000 archive history unavailable (a wrong answer would be worse than none)"
else
  HA_FAIL=$((HA_FAIL + 1))
  ha_row FAIL archive.failsClosedAboveHead "0xffffff" "adapter" \
    "the archive answered a height it has not published"
fi

# ===========================================================================
# PHASE 2 -- structural: were BLOCK_FINALIZE positions journaled at all?
# ===========================================================================
hs_step "PHASE 2: stop the node and read the committed txNum index off disk"
hs_metrics_summary "$node"
hs_assert_repair_not_required "$node" "before the clean stop"
if ! hs_node_stop "$node" 180; then
  hs_abort "the node ignored SIGTERM"
fi
hs_assert_clean_stop "$node" "SIGTERM shutdown"
hs_assert_probe_clean "$node" "committed archive index after the run"

# INDEPENDENT SOURCE probe: the on-disk txNum index, not the query path.
# UnifiedArchiveTxNumIndex.validatePositionShape requires every committed block
# range to carry a BLOCK_PREPARE position and a BLOCK_FINALIZE position, so at
# least 2 txNums per published block is the disk-level signature of "the
# finalize phase was journaled for every block, including empty ones".
probe_json="$(hs_offline_probe "$node" || true)"
probe_ranges="$(printf '%s' "$probe_json" | jq -r '.rangeCount // 0' 2>/dev/null || printf 0)"
probe_first="$(printf '%s' "$probe_json" | jq -r '.firstTxNum // -1' 2>/dev/null || printf -- '-1')"
probe_last="$(printf '%s' "$probe_json" | jq -r '.lastTxNum // -1' 2>/dev/null || printf -- '-1')"
if [ "$probe_ranges" -gt 0 ] 2>/dev/null && [ "$probe_first" -ge 0 ] 2>/dev/null \
    && [ "$probe_last" -ge "$probe_first" ] 2>/dev/null; then
  probe_span=$(( probe_last - probe_first + 1 ))
  probe_min=$(( 2 * probe_ranges ))
  if [ "$probe_span" -ge "$probe_min" ]; then
    ha_ok system.finalizePhaseJournaled "1-$probe_ranges" "probe" \
      "txNum span $probe_span >= 2 per published block ($probe_ranges blocks): BLOCK_PREPARE + BLOCK_FINALIZE positions exist"
  else
    ha_bad system.finalizePhaseJournaled "1-$probe_ranges" "probe" \
      "txNum span $probe_span < $probe_min: some published block carries no finalize position"
  fi
else
  ha_info system.finalizePhaseJournaled "-" \
    "the offline probe returned no usable txNum range (rangeCount=$probe_ranges first=$probe_first last=$probe_last), so the finalize-phase journal shape is unproven"
fi

# ===========================================================================
# Verdict
# ===========================================================================
ha_print_table

hs_finish HISTORY_ACCURACY_OK \
  "pass=$HA_PASS" \
  "fail=$HA_FAIL" \
  "info=$HA_INFO" \
  "witnesses=$HS_CFG_WITNESS_COUNT" \
  "samples=$(wc -l <"$HA_LIVE_FILE" | tr -d ' ')" \
  "txs=$(wc -l <"$HA_TX_FILE" | tr -d ' ')" \
  "withdrawProof=$HA_WITHDRAW_OK" \
  "publishedHead=$published_head"
