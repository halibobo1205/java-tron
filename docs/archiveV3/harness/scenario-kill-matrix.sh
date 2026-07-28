#!/usr/bin/env bash
#
# scenario-kill-matrix.sh -- archive fault-injection harness, target (B):
#                            the SIGKILL durability matrix.
#
# ==============================================================================================
# WHAT THIS PROVES
# ==============================================================================================
# For each durability window we drive a private chain to that exact moment, SIGKILL the node,
# restart it, and require the outcome to be one of the DESIGNED outcomes. Silent acceptance of a
# half state is the failure this scenario exists to catch.
#
#   RECOVERED       node restarts, the archive keeps publishing, every historical oracle
#                   captured before the kill replays IDENTICALLY afterwards, AND the offline
#                   probe of the quiesced database finds no structural violation.
#   FAILSTOP        node refuses to serve and says so: exit 1 (TronError ARCHIVE_RUNTIME) or
#                   exit 70 (ArchiveFatalController watchdog halt), with the matching breadcrumb.
#   REPAIR_FLAGGED  node serves but has persisted repair-required. Explicit, so it is accepted.
#
# Failing verdicts:
#   SILENT_HALF_STATE  node came up "healthy" but a pre-kill oracle changed its answer, or the
#                      offline probe found structural damage (gaps / leftover in-flight /
#                      repair-required) that the node did not act on. THE headline failure.
#   HUNG               process alive, HTTP never ready, no exit. See KNOWN DEFECT below.
#                      Fails unless --allow-hung.
#   ERROR              the harness could not carry the window out correctly -- including
#                      "too few usable oracles were captured to demonstrate recovery".
#   INCONCLUSIVE       a probabilistic window never provably landed in its target window, OR
#                      the node recovered but the offline probe could not run, so structural
#                      integrity is unverified. Not a product failure; fails only under --strict.
#
# EVIDENCE RULES (why a window cannot pass vacuously)
#   * An oracle is recorded only when the archive returned a real 0x quantity. An erroring or
#     unreachable archive therefore yields ZERO oracles rather than a ledger of empty strings
#     that would later "replay identically" against an equally empty answer.
#   * A window needs at least KM_MIN_ORACLES usable oracles or it is ERROR, never RECOVERED.
#   * RECOVERED additionally requires a clean probe of the QUIESCED database. The probe run
#     against the live node uses RocksDB.openReadOnly and can lag the writer, so a live
#     violation is only a suspicion; it is confirmed after a clean stop before being called
#     SILENT_HALF_STATE, and "the probe never ran" is INCONCLUSIVE, never RECOVERED.
#
# ==============================================================================================
# WINDOWS -- DETERMINISTIC vs PROBABILISTIC (read this before interpreting any result)
# ==============================================================================================
#   w1  steady-state publishing      ALWAYS DETERMINISTIC-WINDOW. Any kill during steady
#                                    production is by definition inside this window; only the
#                                    instruction reached is random.
#   w2  journal put -> canonical commit   DETERMINISTIC via JDWP, else PROBABILISTIC
#   w3  canonical commit -> ack           DETERMINISTIC via JDWP, else PROBABILISTIC
#   w4  ack -> publish                    DETERMINISTIC via JDWP, else PROBABILISTIC
#   w5  mid publish batch                 DETERMINISTIC via JDWP, else PROBABILISTIC
#   w6  genesis commitToRoot -> COMMITTED marker  DETERMINISTIC via JDWP, else PROBABILISTIC.
#                                    First boot of an empty data dir only.
#
# w2..w6 are hit by suspending the JVM on a breakpoint at the exact source anchor and killing it
# while suspended (hs_jdb_break_and_kill). When a window cannot be armed -- no jdb, --no-jdwp, an
# unresolvable anchor, or a jar/source mismatch -- the window AUTOMATICALLY degrades to a
# randomized-timing loop of --prob-iters iterations. Degraded windows are labelled
# `probabilistic(n)` in the verdict table, in every PHASE_VERDICT line, and in the run summary.
# A probabilistic window can never prove it entered the target window; it can only prove that no
# iteration produced a silent half state.
#
# ==============================================================================================
# JAR/SOURCE DRIFT -- a verified trap; do not remove the preflight
# ==============================================================================================
# Breakpoints are set by SOURCE LINE. A jar whose line tables no longer match the working tree
# will place the breakpoint in a DIFFERENT METHOD, and the run would report "hit" for a window it
# never reached. This is not hypothetical: at the time of writing, the checked-in
# framework/build/libs/FullNode.jar had pushBlock() spanning source lines 1815-1986, so the
# documented anchors at 1994/2010/2013 resolved into blockTrigger().
#
# Three independent guards, all mandatory:
#   1. Line numbers are never hardcoded -- km_resolve_anchor() greps the CURRENT source.
#   2. STATIC:  km_verify_anchor() runs javap on the jar under test and requires the resolved
#               line to appear in the target method's LineNumberTable.
#   3. RUNTIME: jdb prints `Breakpoint hit: ... Class.method(), line=N`; km_assert_hit_method()
#               requires the reported method to match before the kill is credited.
# If guard 2 or 3 fails, the window degrades to probabilistic rather than reporting a false pass.
#
# ==============================================================================================
# KNOWN PRODUCT DEFECT this matrix is expected to surface (do not paper over it)
# ==============================================================================================
# Archive startup validation inside Manager.initInternal throws a bare ArchiveException, which is
# a plain RuntimeException and NOT a TronError. ExitManager.findTronError therefore returns empty
# and System.exit never runs, while Metrics.init() has already started a NON-DAEMON Prometheus
# HTTP server (FullNode.java, before the Spring context). Net effect: the JVM stays alive forever
# instead of fail-stopping. Observed via validateCanonicalHead ->
# UnifiedArchiveTxNumIndex.validateCanonicalHead ("archive head block N does not match canonical
# head block M").
# Contrast: Manager.archiveGenesisCanonicalStateError() returns a real TronError, and the
# DefaultConfig.archiveService() wrapper converts to TronError(ARCHIVE_RUNTIME) -- both exit 1
# cleanly. That is why w6 is expected to exit cleanly while w2..w5 may legitimately report HUNG.
# HUNG is its own verdict so it is never mistaken for harness flake.
#
# ==============================================================================================
# MACHINE-CHECKABLE OUTPUT
# ==============================================================================================
#   PHASE_VERDICT window=<id> mode=<mode> verdict=<...> detail=<...>   (one per window)
#   KILL_MATRIX_OK   checks=<n> windows=<n> ...                        (exit 0)
#   KILL_MATRIX_FAIL checks=<n> failures=<n> ...                       (exit 1)
#   exit 2 = the harness could not run at all (environment/setup problem)
#
# USAGE
#   ./scenario-kill-matrix.sh [options]
#     --windows "w1 w3"    subset to run (default: all six)
#     --prob-iters N       iterations for a degraded window (default 3)
#     --height N           height to reach before killing (default 12)
#     --bp-timeout SECS    breakpoint wait (default 180)
#     --jdwp-port N        JDWP port (default 5005)
#     --no-jdwp            force every window to the probabilistic path
#     --allow-hung         accept HUNG as a passing outcome (documents the known defect)
#     --strict             treat INCONCLUSIVE as a failure
#     --keep               keep the run directory
#
# No production source is modified; it is only read to resolve line anchors.

set -uo pipefail

KM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"

if [ ! -f "$KM_DIR/lib.sh" ]; then
  printf 'FATAL %s/lib.sh not found; scenario-kill-matrix.sh cannot run standalone\n' "$KM_DIR" >&2
  printf 'KILL_MATRIX_FAIL reason=missing-lib\n'
  exit 2
fi
# shellcheck source=lib.sh disable=SC1091
. "$KM_DIR/lib.sh"

# ---------------------------------------------------------------------------------------- knobs

KM_WINDOWS="w1 w2 w3 w4 w5 w6"
KM_PROB_ITERS=3
KM_HEIGHT=12
KM_BP_TIMEOUT=180
KM_JDWP_PORT=5005
KM_NO_JDWP=0
KM_ALLOW_HUNG=0
KM_STRICT=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --windows)     KM_WINDOWS="$2"; shift 2 ;;
    --prob-iters)  KM_PROB_ITERS="$2"; shift 2 ;;
    --height)      KM_HEIGHT="$2"; shift 2 ;;
    --bp-timeout)  KM_BP_TIMEOUT="$2"; shift 2 ;;
    --jdwp-port)   KM_JDWP_PORT="$2"; shift 2 ;;
    --no-jdwp)     KM_NO_JDWP=1; shift ;;
    --allow-hung)  KM_ALLOW_HUNG=1; shift ;;
    --strict)      KM_STRICT=1; shift ;;
    --keep)        HS_KEEP_WORKDIR=1; export HS_KEEP_WORKDIR; shift ;;
    -h|--help)     sed -n '2,115p' "$0"; exit 0 ;;
    *) printf 'unknown option: %s\n' "$1" >&2; exit 2 ;;
  esac
done

KM_MANAGER_SRC="framework/src/main/java/org/tron/core/db/Manager.java"
KM_BACKEND_SRC="chainbase/src/main/java/org/tron/core/archive/UnifiedArchiveBackend.java"

# ------------------------------------------------------------------- lib.sh compatibility
#
# lib.sh evolves independently of this scenario. Everything this file depends on is asserted up
# front so a rename fails loudly here instead of silently skipping an assertion mid-run, and the
# one gate that has already been renamed once is bound through a shim.

KM_REQUIRED_FNS="hs_init hs_finish hs_log hs_step hs_pass hs_fail hs_marker hs_repo_root
hs_new_node hs_node_start hs_node_kill9 hs_node_await_startup hs_node_exit_reason
hs_node_has_archive_failstop hs_node_has_watchdog_halt hs_config_set_identity_init
hs_archive_dir hs_head_num hs_wait_height hs_metric_int hs_hist_balance hs_eth_of_priv
hs_jdb_break_and_kill hs_jdb_log_path"

km_require_lib() {
  local missing="" fn
  for fn in $KM_REQUIRED_FNS; do
    declare -F "$fn" >/dev/null 2>&1 || missing="$missing $fn"
  done
  if [ -n "$missing" ]; then
    printf 'FATAL lib.sh is missing required function(s):%s\n' "$missing" >&2
    printf 'KILL_MATRIX_FAIL reason=lib-api-drift\n'
    exit 2
  fi
}

# km_wait_queryable <node_dir> <addr0x> <height> -- block until the archive can answer a
# historical read at <height>. lib.sh has already renamed this gate once
# (hs_wait_published -> hs_wait_archive_drained), so bind to whichever it currently exposes.
km_wait_queryable() {
  local node_dir="$1" addr="$2" height="$3"
  if declare -F hs_wait_hist_available >/dev/null 2>&1; then
    hs_wait_hist_available "$node_dir" "$addr" "$height" 240
  elif declare -F hs_wait_archive_drained >/dev/null 2>&1; then
    hs_wait_archive_drained "$node_dir" 240
  elif declare -F hs_wait_published >/dev/null 2>&1; then
    hs_wait_published "$node_dir" "$height" 240
  else
    hs_log "no archive-publication gate in lib.sh; falling back to a fixed settle delay"
    sleep 15
  fi
}

# Verdict table: parallel newline-delimited strings (bash 3.2 has no associative arrays).
KM_ROW_ID=""
KM_ROW_MODE=""
KM_ROW_VERDICT=""
KM_ROW_NOTE=""
KM_SLOT=0
KM_ANCHOR_WHY=""
KM_VERDICT=""
KM_NOTE=""

# ------------------------------------------------------------------------------ verdict table

# km_record <window> <mode> <verdict> <detail...>
km_record() {
  local id="$1" mode="$2" verdict="$3"
  shift 3
  local detail="$*"

  KM_ROW_ID="${KM_ROW_ID}${id}"$'\n'
  KM_ROW_MODE="${KM_ROW_MODE}${mode}"$'\n'
  KM_ROW_VERDICT="${KM_ROW_VERDICT}${verdict}"$'\n'
  KM_ROW_NOTE="${KM_ROW_NOTE}${detail}"$'\n'

  case "$verdict" in
    RECOVERED|FAILSTOP|REPAIR_FLAGGED)
      hs_pass "window $id [$mode]: $verdict -- $detail" ;;
    HUNG)
      if [ "$KM_ALLOW_HUNG" -eq 1 ]; then
        hs_pass "window $id [$mode]: HUNG accepted via --allow-hung -- $detail"
      else
        hs_fail "window $id [$mode]: HUNG (no exit, never ready) -- $detail"
      fi ;;
    INCONCLUSIVE)
      if [ "$KM_STRICT" -eq 1 ]; then
        hs_fail "window $id [$mode]: INCONCLUSIVE under --strict -- $detail"
      else
        hs_log "window $id [$mode]: INCONCLUSIVE (not a product failure) -- $detail"
      fi ;;
    *)
      hs_fail "window $id [$mode]: $verdict -- $detail" ;;
  esac

  hs_marker PHASE_VERDICT "window=$id" "mode=$mode" "verdict=$verdict" "detail=$detail"
}

# ------------------------------------------------------------------------------ source anchors

# km_repo_root -- repo root, independent of hs_init ordering (anchors are resolved before the
# harness is fully initialized in some paths, and hs_repo_root refuses to answer that early).
km_repo_root() {
  local root=""
  if declare -F hs_repo_root >/dev/null 2>&1; then
    root="$(hs_repo_root 2>/dev/null)" || root=""
  fi
  [ -n "$root" ] || root="$(cd "$KM_DIR/../../.." >/dev/null 2>&1 && pwd)"
  printf '%s\n' "$root"
}

# km_resolve_anchor <window> -> "<src> <line> <class> <method>"; empty when unresolvable.
km_resolve_anchor() {
  local w="$1" root line
  root="$(km_repo_root)"
  local m="$root/$KM_MANAGER_SRC" b="$root/$KM_BACKEND_SRC"

  case "$w" in
    w2)
      # The canonical commit that immediately follows the durable journal write.
      line="$(awk '/archiveJournalToken = journalArchiveBlockOnlyOrFailStop\(newBlock, "push block"\)/{f=NR}
                   f && NR>f && /tmpSession\.commit\(\);/{print NR; exit}' "$m")"
      [ -n "$line" ] && printf '%s %s %s %s\n' "$KM_MANAGER_SRC" "$line" org.tron.core.db.Manager pushBlock ;;
    w3)
      line="$(grep -n 'acknowledgeArchiveJournalOrFailStop(archiveJournalToken, newBlock, "push block");' "$m" \
              | head -1 | cut -d: -f1)"
      [ -n "$line" ] && printf '%s %s %s %s\n' "$KM_MANAGER_SRC" "$line" org.tron.core.db.Manager pushBlock ;;
    w4)
      line="$(awk '/acknowledgeArchiveJournalOrFailStop\(archiveJournalToken, newBlock, "push block"\);/{f=NR}
                   f && NR>f && /publishArchiveSolidifiedOrFailStop\(/{print NR; exit}' "$m")"
      [ -n "$line" ] && printf '%s %s %s %s\n' "$KM_MANAGER_SRC" "$line" org.tron.core.db.Manager pushBlock ;;
    w5)
      line="$(grep -n 'runPersistentMutation(() -> db.publishBlockAtomically(' "$b" | head -1 | cut -d: -f1)"
      [ -n "$line" ] && printf '%s %s %s %s\n' "$KM_BACKEND_SRC" "$line" \
        org.tron.core.archive.UnifiedArchiveBackend publishBlock ;;
    w6)
      # genesisSession.commitToRoot() has run; the COMMITTED marker has not been persisted yet.
      line="$(grep -n 'saveArchiveGenesisCommitComplete(' "$m" | head -1 | cut -d: -f1)"
      [ -n "$line" ] && printf '%s %s %s %s\n' "$KM_MANAGER_SRC" "$line" org.tron.core.db.Manager initGenesis ;;
  esac
}

# km_verify_anchor <class> <method> <line> -- 0 when the jar under test agrees with the source.
km_verify_anchor() {
  local cls="$1" method="$2" line="$3" out
  KM_ANCHOR_WHY=""
  out="$(KM_JAR="$HS_JAR" KM_CLS="$cls" KM_MTH="$method" KM_LN="$line" python3 - <<'PYEOF' 2>/dev/null
import os, re, subprocess, sys
jar = os.environ["KM_JAR"]; cls = os.environ["KM_CLS"]
method = os.environ["KM_MTH"]; line = int(os.environ["KM_LN"])
try:
    out = subprocess.run(["javap", "-p", "-l", "-cp", jar, cls],
                         capture_output=True, text=True, check=True).stdout
except Exception:
    print("javap failed for " + cls); sys.exit(0)
cur, table = None, {}
for l in out.split("\n"):
    if l.startswith("  ") and not l.startswith("   ") and "(" in l:
        cur = l.strip(); table.setdefault(cur, set())
    m = re.match(r"^\s+line (\d+): (\d+)$", l)
    if m and cur:
        table[cur].add(int(m.group(1)))
hits = [k for k in table if re.search(r"\b" + re.escape(method) + r"\s*\(", k)]
if not hits:
    print("method %s not found in %s" % (method, cls)); sys.exit(0)
if any(line in table[h] for h in hits):
    print("OK"); sys.exit(0)
owner = [k for k in table if line in table[k]]
own = owner[0].split("(")[0].split()[-1] if owner else "no-method"
rng = sorted(table[hits[0]])
span = "%d-%d" % (rng[0], rng[-1]) if rng else "empty"
print("line %d belongs to '%s'; %s covers %s (jar is stale vs source)" % (line, own, method, span))
PYEOF
)"
  [ "$out" = "OK" ] && return 0
  KM_ANCHOR_WHY="${out:-anchor verification produced no output}"
  return 1
}

# km_assert_hit_method <jdb-log> <method> -- 0 when the breakpoint fired in the expected method.
km_assert_hit_method() {
  local log="$1" method="$2" hit
  hit="$(grep -m1 'Breakpoint hit' "$log" 2>/dev/null)"
  [ -n "$hit" ] || return 1
  case "$hit" in
    *".${method}("*) return 0 ;;
    *) KM_ANCHOR_WHY="breakpoint fired outside $method: $hit"; return 1 ;;
  esac
}

# ------------------------------------------------------------------------------ offline probe

KM_PROBE_READY=0

km_build_probe() {
  [ "$KM_PROBE_READY" -eq 1 ] && return 0
  [ -f "$KM_DIR/ArchiveProbe.java" ] || return 1
  javac -nowarn -cp "$HS_JAR" -d "$HS_CLASSES" "$KM_DIR/ArchiveProbe.java" >/dev/null 2>&1 || return 1
  KM_PROBE_READY=1
  return 0
}

# km_probe <node_dir> -- echo the probe JSON and RETURN the probe's exit code
# (0 clean, 1 violation, 3 unopenable, 99 the probe could not be built or run).
#
# The status MUST be returned, not published in KM_PROBE_RC. Every caller writes
# `probe="$(km_probe "$node_dir")"`, which runs this in a subshell, so a global set here is
# invisible to the caller: KM_PROBE_RC stayed at its initial value forever and the
# "serving with a structurally broken archive" branch in km_classify could never fire --
# the probe's entire violation-detection was dead code. Callers now use `$?`.
KM_PROBE_RC=99
km_probe() {
  local node_dir="$1" unified out rc
  KM_PROBE_RC=99
  km_build_probe || { printf '{}\n'; return 99; }
  unified="$(hs_archive_dir "$node_dir")/unified"
  out="$(java -cp "$HS_JAR:$HS_CLASSES" ArchiveProbe "$unified" 2>/dev/null)"; rc=$?
  KM_PROBE_RC="$rc"
  printf '%s\n' "${out:-\{\}}"
  return "$rc"
}

km_probe_field() {
  printf '%s' "$1" | jq -r "(.$2 // \"?\") | tostring" 2>/dev/null || printf '?'
}

# ------------------------------------------------------------------------------ oracles
#
# Archive-vs-archive replay across the kill. Values are captured from the historical read path at
# heights the archive has already published, then re-read after restart and required to be
# byte-identical. Comparing against hardcoded literals would be wrong (see the round-8 notes on
# eth_getCode returning runtimecode on a private chain), so the oracle is always the node's own
# pre-kill answer.

# Minimum usable oracles a mid-chain window must carry. Below this the window cannot
# distinguish "the archive still answers correctly" from "the archive answers nothing",
# so the verdict is ERROR rather than a green RECOVERED.
KM_MIN_ORACLES=3

# km_oracle_capture <node_dir> <file> <height> -- sets KM_ORACLE_N to the number of
# USABLE oracles written.
#
# Only 0x-shaped answers are recorded. hs_hist_balance aborts its own subshell on a
# JSON-RPC error, which yields an EMPTY field; writing that would let km_oracle_replay
# later compare empty-against-empty and report a clean recovery from an archive that
# answers nothing at all. That is the headline vacuous assertion this scenario exists
# to prevent, so unusable answers are dropped and counted instead.
KM_ORACLE_N=0
km_oracle_capture() {
  local node_dir="$1" file="$2" height="$3" addr h value skipped=0
  : >"$file"
  KM_ORACLE_N=0
  for addr in "$KM_ETH_ZION" "$KM_ETH_SUN"; do
    for h in 1 $((height / 2)) "$height"; do
      [ "$h" -ge 1 ] 2>/dev/null || continue
      value="$(hs_hist_balance "$node_dir" "$addr" "$h" 2>/dev/null)"
      case "$value" in
        0x*)
          printf '%s|%s|%s\n' "$addr" "$h" "$value" >>"$file"
          KM_ORACLE_N=$((KM_ORACLE_N + 1)) ;;
        *)
          skipped=$((skipped + 1)) ;;
      esac
    done
  done
  hs_log "captured $KM_ORACLE_N usable historical oracles (${skipped} unusable and dropped)"
}

# km_oracle_replay <node_dir> <file> -- 0 when every oracle replays identically.
# Returns 2 when there is nothing to compare, which the caller must NOT read as a pass.
km_oracle_replay() {
  local node_dir="$1" file="$2" addr h want got bad=0 compared=0
  [ -s "$file" ] || return 2
  while IFS='|' read -r addr h want; do
    [ -n "$addr" ] || continue
    case "$want" in 0x*) : ;; *) continue ;; esac
    got="$(hs_hist_balance "$node_dir" "$addr" "$h" 2>/dev/null)"
    compared=$((compared + 1))
    if [ "$got" != "$want" ]; then
      hs_log "ORACLE DIVERGED addr=$addr block=$h before=[$want] after=[$got]"
      bad=$((bad + 1))
    fi
  done <"$file"
  [ "$compared" -gt 0 ] || return 2
  [ "$bad" -eq 0 ]
}

# ------------------------------------------------------------------------------ classification

# km_head_now <node_dir> -- head or -1, never aborts.
km_head_now() { hs_head_num "$1" 2>/dev/null || printf '%s\n' -1; }

# km_wait_progress <node_dir> <target> <timeout> -- 0 when head reaches target. Never aborts.
km_wait_progress() {
  local node_dir="$1" target="$2" timeout="${3:-90}" head
  local deadline=$(( $(date +%s) + timeout ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    head="$(km_head_now "$node_dir")"
    [ "$head" != "-1" ] && [ "$head" -ge "$target" ] 2>/dev/null && return 0
    sleep 1
  done
  return 1
}

# km_classify <node_dir> <oracle-file|-> <pre-kill-head>
#
# Restarts the node and decides the verdict. Sets KM_VERDICT and KM_NOTE.
km_classify() {
  local node_dir="$1" oracle="$2" prehead="$3"
  local verdict probe probe_rc repair head

  KM_VERDICT="ERROR"; KM_NOTE=""

  # The archive identity must not be re-initialized on a restart.
  hs_config_set_identity_init "$node_dir" false
  hs_node_start "$node_dir"
  verdict="$(hs_node_await_startup "$node_dir" "${KM_BP_TIMEOUT}")"

  probe="$(km_probe "$node_dir")"
  probe_rc=$?
  repair="$(km_probe_field "$probe" repairRequired)"

  case "$verdict" in
    HUNG)
      KM_VERDICT="HUNG"
      KM_NOTE="process alive, HTTP never ready; $(hs_node_exit_reason "$node_dir"); probe repair=$repair"
      return 0 ;;
    EXIT:1)
      if hs_node_has_archive_failstop "$node_dir"; then
        KM_VERDICT="FAILSTOP"; KM_NOTE="exit 1 ARCHIVE_RUNTIME, probe repair=$repair"
      else
        KM_VERDICT="ERROR"; KM_NOTE="exit 1 without the ARCHIVE_RUNTIME breadcrumb"
      fi
      return 0 ;;
    EXIT:70)
      if hs_node_has_watchdog_halt "$node_dir"; then
        KM_VERDICT="FAILSTOP"; KM_NOTE="exit 70 archive fatal watchdog, probe repair=$repair"
      else
        KM_VERDICT="ERROR"; KM_NOTE="exit 70 without the watchdog breadcrumb"
      fi
      return 0 ;;
    EXIT:*)
      KM_VERDICT="ERROR"
      KM_NOTE="unexpected ${verdict}; $(hs_node_exit_reason "$node_dir")"
      return 0 ;;
  esac

  # --- READY: the node is serving. Now prove it is not a silent half state. -------------------
  #
  # "-" means the window legitimately has no pre-kill oracles (w6 dies before any block
  # exists). Any OTHER value must carry real evidence: a missing or empty ledger means the
  # capture failed, and treating that as "nothing diverged" is exactly the vacuous pass this
  # verdict is supposed to catch.
  if [ "$oracle" != "-" ]; then
    km_oracle_replay "$node_dir" "$oracle"
    case "$?" in
      0) : ;;
      2)
        KM_VERDICT="ERROR"
        KM_NOTE="no usable pre-kill oracle survived capture, so recovery cannot be demonstrated"
        return 0 ;;
      *)
        KM_VERDICT="SILENT_HALF_STATE"
        KM_NOTE="node serves but pre-kill historical oracles changed their answers"
        return 0 ;;
    esac
  fi

  # An explicit repair flag is accepted wherever it appears -- on disk or on the gauge.
  case "$repair" in
    true|1)
      KM_VERDICT="REPAIR_FLAGGED"
      KM_NOTE="serving with repair-required persisted (explicit): $(km_probe_field "$probe" repairReason)"
      return 0 ;;
  esac
  case "$(hs_metric_int "$node_dir" repair_required 2>/dev/null)" in
    1) KM_VERDICT="REPAIR_FLAGGED"; KM_NOTE="serving with repair_required=1 (explicit)"; return 0 ;;
  esac

  # The archive must still be making forward progress after the restart.
  head="$(km_head_now "$node_dir")"
  if ! km_wait_progress "$node_dir" $(( ${head:-0} + 2 )) 90; then
    KM_VERDICT="SILENT_HALF_STATE"
    KM_NOTE="node serves but head stalled at $head after restart (archive not progressing)"
    return 0
  fi

  # ---- structural half of "no silent half state" ---------------------------------------------
  #
  # The probe above ran while the node was LIVE. ArchiveProbe uses RocksDB.openReadOnly, which
  # does not take the file lock, so it succeeds -- but it sees whatever the last MANIFEST flush
  # published, which legitimately lags a running writer. Reporting SILENT_HALF_STATE off that
  # view would produce false accusations against a healthy node.
  #
  # So a live violation is treated as a SUSPICION and confirmed against a quiesced database:
  # stop the node cleanly and probe again. Only a violation that survives the clean stop is
  # real damage. Conversely the confirmation probe is mandatory before RECOVERED -- "the probe
  # did not run" must never be read as "the probe found nothing".
  local confirm confirm_rc
  hs_node_stop_force "$node_dir" 120 >/dev/null 2>&1 || true
  confirm="$(km_probe "$node_dir")"
  confirm_rc=$?

  if [ "$confirm_rc" = "99" ] && [ "$probe_rc" = "99" ]; then
    KM_VERDICT="INCONCLUSIVE"
    KM_NOTE="node recovered and oracles replayed, but the offline probe never ran, so structural integrity is unverified"
    return 0
  fi
  if [ "$confirm_rc" = "3" ]; then
    KM_VERDICT="SILENT_HALF_STATE"
    KM_NOTE="node served queries but its archive database cannot be opened once quiesced: $(km_probe_field "$confirm" openError)"
    return 0
  fi
  if [ "$confirm_rc" = "1" ]; then
    case "$(km_probe_field "$confirm" repairRequired)" in
      true|1)
        KM_VERDICT="REPAIR_FLAGGED"
        KM_NOTE="quiesced probe: repair-required persisted (explicit): $(km_probe_field "$confirm" repairReason)"
        return 0 ;;
    esac
    KM_VERDICT="SILENT_HALF_STATE"
    KM_NOTE="quiesced probe found violations the node never acted on: $(km_probe_field "$confirm" violations)"
    return 0
  fi
  if [ "${confirm_rc}" != "0" ]; then
    KM_VERDICT="ERROR"
    KM_NOTE="offline probe exited $confirm_rc after the clean stop"
    return 0
  fi

  KM_VERDICT="RECOVERED"
  KM_NOTE="oracles identical, head $prehead -> $head, repair=0, quiesced probe clean (ranges=$(km_probe_field "$confirm" rangeCount) inflight=$(km_probe_field "$confirm" inFlightKeys) gaps=$(km_probe_field "$confirm" blockGaps)); live-probe rc was $probe_rc"
  return 0
}

# ------------------------------------------------------------------------------ node bring-up

# km_boot <name> -- fresh node at KM_HEIGHT with the archive published. Echoes node_dir.
# Returns 1 (and echoes nothing) if the chain never got there.
km_boot() {
  local name="$1" node_dir
  KM_SLOT=$((KM_SLOT + 1))
  node_dir="$(hs_new_node "$name" "$KM_SLOT")" || return 1
  hs_node_start "$node_dir" || return 1
  if [ "$(hs_node_await_startup "$node_dir" 180)" != "READY" ]; then
    hs_log "$name never became ready: $(hs_node_exit_reason "$node_dir")"
    return 1
  fi
  hs_wait_height "$node_dir" "$KM_HEIGHT" 180 >/dev/null || return 1
  km_wait_queryable "$node_dir" "$KM_ETH_ZION" "$KM_HEIGHT" || return 1
  printf '%s\n' "$node_dir"
}

# ------------------------------------------------------------------------------ w1: steady state

km_run_w1() {
  local node_dir oracle prehead
  hs_step "w1 -- SIGKILL during steady block production with the archive publishing"

  node_dir="$(km_boot km-w1)" || { km_record w1 deterministic-window ERROR "chain never reached height $KM_HEIGHT"; return; }
  oracle="$node_dir/oracles.txt"
  km_oracle_capture "$node_dir" "$oracle" "$KM_HEIGHT"
  if [ "$KM_ORACLE_N" -lt "$KM_MIN_ORACLES" ]; then
    hs_node_kill9 "$node_dir" 2>/dev/null || true
    km_record w1 deterministic-window ERROR \
      "only $KM_ORACLE_N usable oracle(s) captured (need $KM_MIN_ORACLES); a recovery verdict would be unverifiable"
    return
  fi
  prehead="$(km_head_now "$node_dir")"

  # Land somewhere inside a block's journal/commit/ack/publish sequence.
  sleep "$(awk -v s="$RANDOM" 'BEGIN{srand(s);printf "%.2f", 0.2 + rand() * 1.6}')"
  hs_node_kill9 "$node_dir"

  km_classify "$node_dir" "$oracle" "$prehead"
  km_record w1 deterministic-window "$KM_VERDICT" "$KM_NOTE"
}

# ------------------------------------------------------------------------- w2..w5: mid-chain

# km_run_bp_window <id> <label>
km_run_bp_window() {
  local id="$1" label="$2"
  local anchor src line cls method node_dir oracle prehead hit jdb_log

  hs_step "$id -- $label"

  anchor="$(km_resolve_anchor "$id")"
  if [ -z "$anchor" ]; then
    km_run_probabilistic "$id" midchain "anchor not resolvable in the current source"
    return
  fi
  set -- $anchor
  src="$1"; line="$2"; cls="$3"; method="$4"
  hs_log "$id anchor: $src:$line ($cls.$method)"

  if [ "$KM_NO_JDWP" -eq 1 ] || ! command -v jdb >/dev/null 2>&1; then
    km_run_probabilistic "$id" midchain "jdb unavailable or --no-jdwp"
    return
  fi
  if ! km_verify_anchor "$cls" "$method" "$line"; then
    hs_log "$id anchor rejected: $KM_ANCHOR_WHY"
    km_run_probabilistic "$id" midchain "jar/source drift: $KM_ANCHOR_WHY"
    return
  fi
  hs_log "$id anchor verified against $HS_JAR"

  HS_JDWP_PORT="$KM_JDWP_PORT"; HS_JDWP_SUSPEND=n
  export HS_JDWP_PORT HS_JDWP_SUSPEND
  node_dir="$(km_boot "km-$id")"
  if [ -z "$node_dir" ]; then
    unset HS_JDWP_PORT HS_JDWP_SUSPEND
    km_record "$id" deterministic ERROR "chain under JDWP never reached height $KM_HEIGHT"
    return
  fi
  oracle="$node_dir/oracles.txt"
  km_oracle_capture "$node_dir" "$oracle" "$KM_HEIGHT"
  prehead="$(km_head_now "$node_dir")"

  hit="$(hs_jdb_break_and_kill "$node_dir" "$cls:$line" "$KM_BP_TIMEOUT")"
  unset HS_JDWP_PORT HS_JDWP_SUSPEND

  # The transcript path comes from lib.sh, never from a locally reconstructed name: an
  # off-by-one-character guess makes km_assert_hit_method fail on EVERY successful hit and
  # turns a working deterministic window into a permanent ERROR.
  jdb_log="$(hs_jdb_log_path "$node_dir" "$cls:$line")"
  if [ "$hit" != "HIT" ]; then
    hs_node_kill9 "$node_dir" 2>/dev/null || true
    km_run_probabilistic "$id" midchain "breakpoint never fired within ${KM_BP_TIMEOUT}s"
    return
  fi
  if [ ! -f "$jdb_log" ]; then
    km_record "$id" deterministic ERROR "jdb reported a hit but no transcript at $jdb_log; the method guard could not run"
    return
  fi
  if ! km_assert_hit_method "$jdb_log" "$method"; then
    km_record "$id" deterministic ERROR "$KM_ANCHOR_WHY"
    return
  fi

  # A HIT means the JVM was SIGKILLed while suspended inside the window, so the oracles must
  # have been captured before that. If they were not, the window can prove nothing.
  if [ "$KM_ORACLE_N" -lt "$KM_MIN_ORACLES" ]; then
    km_record "$id" deterministic ERROR \
      "only $KM_ORACLE_N usable oracle(s) captured (need $KM_MIN_ORACLES); recovery would be unverifiable"
    return
  fi

  km_classify "$node_dir" "$oracle" "$prehead"
  km_record "$id" deterministic "$KM_VERDICT" "$KM_NOTE"
}

# ------------------------------------------------------------------------------ w6: genesis

km_run_w6() {
  local anchor src line cls method node_dir hit jdb_log probe

  hs_step "w6 -- SIGKILL between genesis commitToRoot and the COMMITTED marker (first boot)"

  anchor="$(km_resolve_anchor w6)"
  if [ -z "$anchor" ]; then
    km_run_probabilistic w6 genesis "anchor not resolvable in the current source"
    return
  fi
  set -- $anchor
  src="$1"; line="$2"; cls="$3"; method="$4"
  hs_log "w6 anchor: $src:$line ($cls.$method)"

  if [ "$KM_NO_JDWP" -eq 1 ] || ! command -v jdb >/dev/null 2>&1; then
    km_run_probabilistic w6 genesis "jdb unavailable or --no-jdwp"
    return
  fi
  if ! km_verify_anchor "$cls" "$method" "$line"; then
    hs_log "w6 anchor rejected: $KM_ANCHOR_WHY"
    km_run_probabilistic w6 genesis "jar/source drift: $KM_ANCHOR_WHY"
    return
  fi

  KM_SLOT=$((KM_SLOT + 1))
  node_dir="$(hs_new_node km-w6 "$KM_SLOT")" \
    || { km_record w6 deterministic ERROR "could not create the node dir"; return; }

  # suspend=y: genesis runs once, in main, on the very first boot. Attaching with the VM already
  # suspended is the only way to arm the breakpoint before initGenesis executes.
  HS_JDWP_PORT="$KM_JDWP_PORT"; HS_JDWP_SUSPEND=y
  export HS_JDWP_PORT HS_JDWP_SUSPEND
  hs_node_start "$node_dir"

  hit="$(hs_jdb_break_and_kill "$node_dir" "$cls:$line" "$KM_BP_TIMEOUT")"
  unset HS_JDWP_PORT HS_JDWP_SUSPEND

  jdb_log="$(hs_jdb_log_path "$node_dir" "$cls:$line")"
  if [ "$hit" != "HIT" ]; then
    hs_node_kill9 "$node_dir" 2>/dev/null || true
    km_run_probabilistic w6 genesis "genesis breakpoint never fired within ${KM_BP_TIMEOUT}s"
    return
  fi
  if [ ! -f "$jdb_log" ]; then
    km_record w6 deterministic ERROR "jdb reported a hit but no transcript at $jdb_log"
    return
  fi
  if ! km_assert_hit_method "$jdb_log" "$method"; then
    km_record w6 deterministic ERROR "$KM_ANCHOR_WHY"
    return
  fi

  # There are no pre-kill oracles: the chain never got past genesis. Canonical blocks now exist
  # with no COMMITTED marker, so validateArchiveGenesisCommitMarkerPresence must fail-stop with a
  # real TronError (exit 1) rather than quietly re-running genesis over a half-committed state.
  km_classify "$node_dir" "-" 0
  if [ "$KM_VERDICT" = "RECOVERED" ]; then
    # "It came up" is not enough for the genesis window. Canonical genesis state was already
    # committed when the kill landed, so a node that serves with an EMPTY archive index has
    # accepted exactly the half state this window exists to detect.
    probe="$(km_probe "$node_dir")"
    local w6_ranges w6_opened
    w6_opened="$(km_probe_field "$probe" opened)"
    w6_ranges="$(km_probe_field "$probe" rangeCount)"
    if [ "$w6_opened" != "true" ]; then
      KM_VERDICT="SILENT_HALF_STATE"
      KM_NOTE="node serves but the archive database cannot be opened offline: $(km_probe_field "$probe" openError)"
    elif [ "${w6_ranges:-0}" -lt 1 ] 2>/dev/null; then
      KM_VERDICT="SILENT_HALF_STATE"
      KM_NOTE="node serves after the genesis-window kill but the archive index is empty (rangeCount=$w6_ranges)"
    else
      KM_NOTE="$KM_NOTE; genesis re-ran cleanly (rangeCount=$w6_ranges)"
    fi
  fi
  km_record w6 deterministic "$KM_VERDICT" "$KM_NOTE"
}

# ------------------------------------------------------------------- probabilistic fallback

# km_run_probabilistic <id> <midchain|genesis> <why>
#
# Randomized-timing loop for a window that could not be armed deterministically. It can never
# prove it entered the target window, so the strongest claim it supports is "no iteration
# produced a silent half state".
km_run_probabilistic() {
  local id="$1" kind="$2" why="$3"
  local i node_dir oracle prehead delay best="INCONCLUSIVE" best_note=""

  hs_log "$id: DEGRADED to the PROBABILISTIC path ($KM_PROB_ITERS iterations) -- $why"

  for i in $(seq 1 "$KM_PROB_ITERS"); do
    oracle="-"; prehead=0

    if [ "$kind" = genesis ]; then
      KM_SLOT=$((KM_SLOT + 1))
      node_dir="$(hs_new_node "km-${id}-p$i" "$KM_SLOT")" || continue
      hs_node_start "$node_dir"
      # Race the very first boot: genesis commits within the first seconds.
      delay="$(awk -v s="$((RANDOM + i))" 'BEGIN{srand(s);printf "%.2f", 0.5 + rand() * 4.0}')"
      sleep "$delay"
      hs_node_kill9 "$node_dir" 2>/dev/null || true
    else
      node_dir="$(km_boot "km-${id}-p$i")" || continue
      oracle="$node_dir/oracles.txt"
      km_oracle_capture "$node_dir" "$oracle" "$KM_HEIGHT"
      if [ "$KM_ORACLE_N" -lt "$KM_MIN_ORACLES" ]; then
        # Skipping keeps `best` at INCONCLUSIVE rather than manufacturing a green
        # RECOVERED from an iteration that had nothing to compare.
        hs_log "$id iteration $i: only $KM_ORACLE_N usable oracle(s); skipping this iteration"
        hs_node_kill9 "$node_dir" 2>/dev/null || true
        continue
      fi
      prehead="$(km_head_now "$node_dir")"
      delay="$(awk -v s="$((RANDOM + i))" 'BEGIN{srand(s);printf "%.3f", rand() * 3.0}')"
      sleep "$delay"
      hs_node_kill9 "$node_dir"
    fi

    km_classify "$node_dir" "$oracle" "$prehead"
    hs_log "$id iteration $i/$KM_PROB_ITERS (delay ${delay}s) -> $KM_VERDICT"

    case "$KM_VERDICT" in
      SILENT_HALF_STATE|ERROR)
        # A real defect in any iteration is the answer; stop and report it.
        km_record "$id" "probabilistic($i/$KM_PROB_ITERS)" "$KM_VERDICT" "$KM_NOTE [degraded: $why]"
        return ;;
      HUNG)
        best="HUNG"; best_note="$KM_NOTE" ;;
      FAILSTOP|REPAIR_FLAGGED)
        [ "$best" = "HUNG" ] || { best="$KM_VERDICT"; best_note="$KM_NOTE"; } ;;
      RECOVERED)
        [ "$best" = "INCONCLUSIVE" ] && { best="RECOVERED"; best_note="$KM_NOTE"; } ;;
    esac
  done

  km_record "$id" "probabilistic($KM_PROB_ITERS)" "$best" \
    "${best_note:-no iteration produced a decisive outcome} [degraded: $why]"
}

# ------------------------------------------------------------------------------ report

km_print_table() {
  local n i id mode verdict note
  n="$(printf '%s' "$KM_ROW_ID" | grep -c '' 2>/dev/null || printf 0)"
  printf '\n'
  printf '================================ KILL MATRIX VERDICTS ================================\n'
  printf '%-4s %-24s %-18s %s\n' WIN MODE VERDICT DETAIL
  printf '%-4s %-24s %-18s %s\n' '----' '------------------------' '------------------' '------'
  i=1
  while [ "$i" -le "$n" ]; do
    id="$(printf '%s' "$KM_ROW_ID" | sed -n "${i}p")"
    mode="$(printf '%s' "$KM_ROW_MODE" | sed -n "${i}p")"
    verdict="$(printf '%s' "$KM_ROW_VERDICT" | sed -n "${i}p")"
    note="$(printf '%s' "$KM_ROW_NOTE" | sed -n "${i}p")"
    printf '%-4s %-24s %-18s %s\n' "$id" "$mode" "$verdict" "$note"
    i=$((i + 1))
  done
  printf '=====================================================================================\n'
  printf 'PASS  : RECOVERED | FAILSTOP | REPAIR_FLAGGED\n'
  printf 'FAIL  : SILENT_HALF_STATE | ERROR | HUNG (unless --allow-hung)\n'
  printf 'INCONC: window never provably entered; fails only under --strict\n'
  printf 'MODE "probabilistic(n)" means the window was NOT provably entered -- randomized timing.\n\n'
}

# ------------------------------------------------------------------------------ main

main() {
  km_require_lib
  hs_init kill-matrix

  KM_ETH_ZION="$(hs_eth_of_priv "$HS_KEY_ZION")"
  KM_ETH_SUN="$(hs_eth_of_priv "$HS_KEY_SUN")"
  hs_log "oracle accounts: zion=$KM_ETH_ZION sun=$KM_ETH_SUN"

  if [ "$KM_NO_JDWP" -eq 1 ]; then
    hs_log "--no-jdwp: every breakpoint window will run on the probabilistic path"
  elif command -v jdb >/dev/null 2>&1; then
    hs_log "jdb found: w2..w6 will be armed deterministically via JDWP on port $KM_JDWP_PORT"
  else
    hs_log "jdb NOT found: w2..w6 will degrade to the probabilistic path"
  fi
  km_build_probe && hs_log "offline archive probe compiled" \
    || hs_log "WARNING: ArchiveProbe.java unavailable; structural checks will be skipped"

  local w
  for w in $KM_WINDOWS; do
    case "$w" in
      w1) km_run_w1 ;;
      w2) km_run_bp_window w2 "journal put -> canonical commit" ;;
      w3) km_run_bp_window w3 "canonical commit -> journal ack" ;;
      w4) km_run_bp_window w4 "journal ack -> publish" ;;
      w5) km_run_bp_window w5 "mid publish batch" ;;
      w6) km_run_w6 ;;
      *)  hs_log "unknown window '$w' (expected: w1 w2 w3 w4 w5 w6)" ;;
    esac
  done

  km_print_table
  hs_finish KILL_MATRIX_OK "windows=$(printf '%s' "$KM_ROW_ID" | grep -c '')"
}

main "$@"
