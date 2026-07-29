#!/usr/bin/env bash
#
# scenario-resource-faults.sh — archive behaviour under storage faults (target C).
#
# =============================================================================================
# WHAT IS INJECTED
# =============================================================================================
#   enospc      the archive RocksDB lives on its own small HFS+ disk image; the harness fills the
#               image until the archive cannot reserve its next durable write
#   permission  the archive directory is made read-only mid-run, and then a restart is attempted
#               while it is still read-only
#   truncation  (opt-in) the archive's newest MANIFEST is truncated while the node is stopped, then
#               the node is restarted
#
# =============================================================================================
# WHAT "CORRECT" MEANS HERE
# =============================================================================================
# Fail-stop is the DESIGNED behaviour, so the harness grades the *shape* of the stop, not merely
# "the node died".  The three explicit stop shapes, each verified against source:
#
#   exit 1   TronError.ErrCode.ARCHIVE_RUNTIME (common/.../exception/TronError.java:42) delivered by
#            ExitManager.logAndExit, which logs
#              "Shutting down with code: ARCHIVE_RUNTIME(1), reason: ..."
#            Both archive journal failures reach it: archivePreCommitError (Manager.java:1506) for a
#            failure before the canonical commit, archiveRuntimeError for one after, and
#            DefaultConfig.archiveService() (DefaultConfig.java:60-68) for a startup failure.
#   exit 70  ArchiveFatalController's watchdog (chainbase/.../ArchiveFatalController.java:10,224-230)
#            when fatal handling — including a stuck repair-marker write — misses its deadline.  The
#            breadcrumb "archive fatal watchdog timeout; halting with exit status 70" goes to stderr
#            only and never reaches tron.log, so stderr is captured separately.
#   startup  "archive repair required: <reason>" from the startup tail validation
#   refusal  (UnifiedArchiveTxNumIndex.validateStartupTailState) — the rebuild-required outcome.
#
# The disk-space messages the ENOSPC case can produce.  On a filling volume the observed path is
# the first one — the publisher's hard watermark, checked on the block-push thread:
#   "archive in-flight journal reached hard watermark: blocks=..., diskFree=<bytes>"
#       (DefaultArchiveService.awaitWriterCapacity:659-666, reached from Manager.pushBlock:1920;
#        `usableSpace < hardMinFreeBytes` is one of the hard-limit disjuncts)
#   "archive publisher backpressure timed out: ..., diskFree=<bytes>"
#       (DefaultArchiveService.java:680-683, the soft-watermark wait giving up)
#   "archive journal filesystem cannot reserve the next durable write while appending block N"
#       (DefaultArchiveService.java:1923-1929, hardMinFreeBytes + a 16 MiB journal headroom)
#   "archive journal filesystem is below hard free-space watermark"
#       (DefaultArchiveService.java:1166-1172, the startup preflight)
# All four are matched by ah_disk_fault_evidence, and the harness additionally requires the
# reported diskFree to be under the configured hard watermark before calling the stop disk-related.
#
# NOTE ON repair-required: a disk-full failure raised *before* the canonical commit leaves nothing
# half-written, so the archive is not required to set the `repair-required` marker.  This scenario
# therefore asserts the pair that actually matters — an explicit fail-stop, and a restart that
# either recovers cleanly or refuses explicitly — plus, in both cases, that the committed index has
# no gaps.  A silently accepted half state is the failure.
#
# A third verdict, HUNG, exists because an archive startup-validation failure raised inside
# Manager.initInternal throws a plain ArchiveException (not a TronError), so ExitManager.findTronError
# returns empty, no System.exit runs, and the JVM stays alive forever on the non-daemon Prometheus
# HTTP server started at FullNode.java:51.  The harness never judges fail-stop by exit code alone:
# it always pairs a startup-readiness timeout with a liveness check.
#
# =============================================================================================
# USAGE
# =============================================================================================
#   docs/archiveV3/harness/scenario-resource-faults.sh
#   FAULT_SCENARIOS="enospc permission truncation" docs/archiveV3/harness/scenario-resource-faults.sh
#
#   Environment overrides (all optional):
#     ARCHIVE_HARNESS_JAR        path to FullNode.jar
#     ARCHIVE_HARNESS_BUILD=1    build the jar if it is missing
#     ARCHIVE_HARNESS_RUN_ROOT   where run directories are created
#     ARCHIVE_HARNESS_PORT_OFFSET shift every listening port (run two harnesses side by side)
#     FAULT_SCENARIOS            space separated subset of "enospc permission truncation"
#                                (default: "enospc permission")
#     FAULT_IMAGE_MB             size of the ENOSPC disk image (default 256)
#     FAULT_MIN_HEAD             blocks to produce before injecting a fault (default 6)
#     FAULT_FAILSTOP_TIMEOUT     seconds to wait for a fail-stop after injection (default 240)
#     HS_CFG_WITNESS_COUNT       SRs on the single node, 1..27 (default 1).  At 1, solid == head
#                                and the archive's in-flight window is ~2 blocks, so the fault
#                                lands on a nearly empty window; at 27 solid trails head by 18
#                                and the window is ~20 blocks.  Solidification-gated waits then
#                                take ~54 s longer, which the timeouts below already allow for.
#
#   Exit 0 + `FAULT_E2E_OK checks=N ...`; exit 1 + `FAULT_E2E_FAIL checks=N failures=M ...`;
#   exit 2 + `HARNESS_ERROR` (the scenario could not run — no verdict about the product).

set -uo pipefail

AH_HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
[ -f "$AH_HARNESS_DIR/lib.sh" ] && . "$AH_HARNESS_DIR/lib.sh"
# shellcheck source=/dev/null
. "$AH_HARNESS_DIR/scenario-common.sh"

FAULT_SCENARIOS="${FAULT_SCENARIOS:-enospc permission}"
FAULT_IMAGE_MB="${FAULT_IMAGE_MB:-256}"
FAULT_MIN_HEAD="${FAULT_MIN_HEAD:-6}"
FAULT_FAILSTOP_TIMEOUT="${FAULT_FAILSTOP_TIMEOUT:-240}"

READY_TIMEOUT=180
PUBLISH_TIMEOUT=180

# The runtime reserve check needs hardMinFreeBytes + max(16 MiB, 2x retained bytes) free, so these
# watermarks put the fail-stop threshold around 24 MiB on the small image.  The 5 GiB / 1 GiB
# defaults would fail-stop during the very first preflight on any image this size.
FAULT_SOFT_MIN_FREE=16777216
FAULT_HARD_MIN_FREE=8388608

N_P2P=$(( 16666 + AH_PORT_OFFSET )); N_HTTP=$(( 8090 + AH_PORT_OFFSET ))
N_RPC=$(( 50051 + AH_PORT_OFFSET )); N_JSONRPC=$(( 8545 + AH_PORT_OFFSET ))
N_METRICS=$(( 9527 + AH_PORT_OFFSET ))

RUN_DIR=""
NODE_PID=""
IMAGE_MOUNT=""
IMAGE_FILE=""
READONLY_DIR=""
ETH_P1=""

cleanup() {
  local rc=$?
  [ -n "$NODE_PID" ] && ah_alive "$NODE_PID" && kill -TERM "$NODE_PID" 2>/dev/null
  sleep 2
  [ -n "$NODE_PID" ] && ah_alive "$NODE_PID" && kill -KILL "$NODE_PID" 2>/dev/null
  # Always give write permission back; otherwise the run directory cannot be cleaned up later.
  [ -n "$READONLY_DIR" ] && [ -d "$READONLY_DIR" ] && chmod -R u+rwX "$READONLY_DIR" 2>/dev/null
  if [ -n "$IMAGE_MOUNT" ] && [ -d "$IMAGE_MOUNT" ]; then
    hdiutil detach "$IMAGE_MOUNT" -quiet 2>/dev/null \
      || hdiutil detach "$IMAGE_MOUNT" -force -quiet 2>/dev/null
  fi
  # stderr, not stdout: the scenario marker must stay the last stdout line.
  [ -n "$RUN_DIR" ] && printf 'artifacts: %s\n' "$RUN_DIR" >&2
  return $rc
}
trap cleanup EXIT INT TERM

# --------------------------------------------------------------------------- helpers

selected() {
  case " $FAULT_SCENARIOS " in
    *" $1 "*) return 0 ;;
    *) return 1 ;;
  esac
}

hex_height() { printf '0x%x' "$1"; }

# A node that cannot even produce blocks says nothing about the archive under fault, so failing to
# reach the starting height is an environment error (exit 2), never a product verdict.  It fires in
# practice when several JVMs compete for the machine.
ah_require_min_head() {
  local port="$1" want="$2" timeout="$3"
  ah_wait_head "$port" "$want" "$timeout" && return 0
  ah_fatal "node reached only head $(ah_head_num "$port") of $want within ${timeout}s; the machine is
too loaded to grade a storage fault. Re-run on an idle machine, or raise FAULT_MIN_HEAD timeouts."
}

# start_case NAME ARCHIVE_DIR IDENTITY_INIT [DATADIR]
#   Writes a config and starts the node.  The witness set follows HS_CFG_WITNESS_COUNT
#   (ah_apply_witness_count in scenario-common.sh), which defaults to 1.
#
#   At the default, a single witness keeps solid == head, so the archive publishes promptly and a
#   plain SIGTERM restart is clean; that isolates this scenario's variable to the injected storage
#   fault.  HS_CFG_WITNESS_COUNT=27 puts 27 SRs on this same single node instead, which makes solid
#   trail head by 18 and gives the archive the realistic ~20-block in-flight window; every wait
#   that depends on solidification then takes ~54 s longer, which the timeouts above already allow.
start_case() {
  local name="$1" archive_dir="$2" identity_init="$3" datadir="${4:-$RUN_DIR/$1/data}"
  ah_conf_reset
  AH_CONF_P2P_PORT=$N_P2P; AH_CONF_HTTP_PORT=$N_HTTP; AH_CONF_RPC_PORT=$N_RPC
  AH_CONF_JSONRPC_PORT=$N_JSONRPC; AH_CONF_METRICS_PORT=$N_METRICS
  AH_CONF_ARCHIVE_DIR="$archive_dir"
  AH_CONF_IDENTITY_INIT="$identity_init"
  AH_CONF_SOFT_MIN_FREE=$FAULT_SOFT_MIN_FREE
  AH_CONF_HARD_MIN_FREE=$FAULT_HARD_MIN_FREE
  ah_write_node_conf "$RUN_DIR/$name/node.conf"
  ah_start_node "$RUN_DIR/$name" "$RUN_DIR/$name/node.conf" "$datadir"
  NODE_PID=$AH_LAST_PID
}

# wait_publishing PORT TIMEOUT — waits until the archive answers a historical query, i.e. it has
# actually published something and the fault will land on a live, working archive.
wait_publishing() {
  local port="$1" timeout="$2" deadline
  deadline=$(( $(date +%s) + timeout ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    case "$(ah_eth_balance "$port" "$ETH_P1" 0x1)" in
      0x*) return 0 ;;
    esac
    sleep 3
  done
  return 1
}

# grade_failstop CASE WORKDIR PID — the shared "did it stop the right way?" grader.
#   Sets GRADE to one of: FAILSTOP / EXITED_WRONG / ALIVE_PROGRESSING / ALIVE_STALLED
#   and GRADE_DETAIL to a human-readable explanation.
grade_failstop() {
  local case_name="$1" workdir="$2" pid="$3" head_before head_after evidence
  head_before="$(ah_head_num "$N_HTTP")"
  if ah_wait_exit "$pid" "$FAULT_FAILSTOP_TIMEOUT"; then
    evidence="$(ah_failstop_evidence "$workdir" | head -1)"
    [ -n "$evidence" ] || evidence="$(ah_archive_error_evidence "$workdir" | head -1)"
    if [ "$AH_LAST_EXIT" -eq 1 ] || [ "$AH_LAST_EXIT" -eq 70 ]; then
      if [ -n "$evidence" ]; then
        GRADE=FAILSTOP
        GRADE_DETAIL="exit $AH_LAST_EXIT; $evidence"
      else
        # Exit 1 with no archive breadcrumb is NOT a demonstrated archive fail-stop: a port
        # clash, an OOM or a config error exits 1 too. Accepting it would let an unrelated
        # crash satisfy the assertion this case exists to make.
        GRADE=EXITED_WRONG
        GRADE_DETAIL="exit $AH_LAST_EXIT with NO archive fail-stop breadcrumb in any log; the stop was not attributed to the archive"
      fi
    else
      GRADE=EXITED_WRONG
      GRADE_DETAIL="exit $AH_LAST_EXIT is not an archive fail-stop code (expected 1 or 70); $evidence"
    fi
    return 0
  fi
  head_after="$(ah_head_num "$N_HTTP")"
  if [ -n "$head_before" ] && [ -n "$head_after" ] && [ "$head_after" -gt "$head_before" ] 2>/dev/null; then
    GRADE=ALIVE_PROGRESSING
    GRADE_DETAIL="node kept producing blocks ($head_before -> $head_after) for ${FAULT_FAILSTOP_TIMEOUT}s under the $case_name fault"
  else
    GRADE=ALIVE_STALLED
    GRADE_DETAIL="node is alive but frozen at head ${head_after:-unreachable} after ${FAULT_FAILSTOP_TIMEOUT}s; it neither failed stop nor made progress"
  fi
  return 0
}

# grade_restart WORKDIR — grades an already-started node's restart outcome.
#   Sets RESTART_GRADE to RECOVERED / REFUSED / HUNG / EXITED_WRONG.
grade_restart() {
  local workdir="$1" rc
  ah_wait_ready_or_exit "$NODE_PID" "$N_HTTP" 180
  rc=$?
  if [ "$rc" -eq 0 ]; then
    RESTART_GRADE=RECOVERED
    RESTART_DETAIL="node came up and serves head $(ah_head_num "$N_HTTP")"
    return 0
  fi
  if [ "$rc" -eq 1 ]; then
    NODE_PID=""
    local evidence
    evidence="$(ah_failstop_evidence "$workdir" | head -1)"
    [ -n "$evidence" ] || evidence="$(ah_archive_error_evidence "$workdir" | head -1)"
    if { [ "$AH_LAST_EXIT" -eq 1 ] || [ "$AH_LAST_EXIT" -eq 70 ]; } && [ -n "$evidence" ]; then
      RESTART_GRADE=REFUSED
      RESTART_DETAIL="explicit archive fail-stop on startup (exit $AH_LAST_EXIT): $evidence"
    elif [ "$AH_LAST_EXIT" -eq 1 ] || [ "$AH_LAST_EXIT" -eq 70 ]; then
      # Same reasoning as grade_failstop: the exit code alone does not attribute the refusal
      # to the archive, and "REFUSED" is a PASS in every caller.
      RESTART_GRADE=EXITED_WRONG
      RESTART_DETAIL="exit $AH_LAST_EXIT on startup with no archive breadcrumb; the refusal was not attributed to the archive"
    else
      RESTART_GRADE=EXITED_WRONG
      RESTART_DETAIL="exit $AH_LAST_EXIT is neither a clean start nor an archive fail-stop code"
    fi
    return 0
  fi
  RESTART_GRADE=HUNG
  RESTART_DETAIL="process alive, HTTP never ready, no exit — an archive startup failure that ExitManager cannot convert into an exit; $(ah_archive_error_summary "$workdir")"
  kill -KILL "$NODE_PID" 2>/dev/null
  NODE_PID=""
  return 0
}

# probe_case CASE ARCHIVE_UNIFIED_DIR — records the structural checks common to every fault case.
#   Gaps are always a failure.  repair-required is NOT a failure by itself; it is the archive
#   correctly telling the operator to rebuild, and it is reported as its own INFO/PASS line.
probe_case() {
  local case_name="$1" unified="$2" json rc gaps ranges inflight marker
  json="$(ah_probe_archive "$unified")"
  rc=$?
  printf '%s\n' "$json" >"$RUN_DIR/$case_name-archive-probe.json"
  # The probe's output must be usable before any field is believed: on a missing class,
  # a JVM crash, or an empty document, jq returns empty strings and the "no gaps" branch
  # below would report a clean archive that was never actually inspected.
  if ! printf '%s' "$json" | jq -e '.opened == true' >/dev/null 2>&1; then
    ah_check "fault.$case_name.no_silent_gaps" FAIL \
      "the offline probe produced no usable output (rc=$rc); structural integrity is unverified: $(printf '%s' "$json" | jq -r '.openError // "no output"' 2>/dev/null || printf 'no output')"
    return 0
  fi
  if [ "$rc" -eq 3 ]; then
    ah_check "fault.$case_name.no_silent_gaps" FAIL \
      "archive database could not be opened: $(printf '%s' "$json" | jq -r '.openError // "?"')"
    return 0
  fi
  # Journal rows above the published head are normal after any stop; rows at or below it are a torn
  # state, so they are folded into the same "no silent damage" verdict as index gaps.
  gaps="$(printf '%s' "$json" | jq -r '
    ((.blockGaps // []) + (.txNumGaps // []) + (.spanViolations // [])
     + ((.staleInFlightBlocks // []) | map("stale journal row at published height " + tostring)))
    | join("; ")')"
  ranges="$(printf '%s' "$json" | jq -r '.rangeCount // 0')"
  inflight="$(printf '%s' "$json" | jq -r '.inFlightKeys // 0')"
  marker="$(printf '%s' "$json" | jq -r '.repairRequired // false')"
  if [ -n "$gaps" ]; then
    ah_check "fault.$case_name.no_silent_gaps" FAIL "committed index is torn: $gaps"
  elif [ "${ranges:-0}" -lt 1 ] 2>/dev/null; then
    # An index with no ranges has no gaps by definition. That is absence of evidence, not
    # evidence of integrity -- the baseline check above already required the archive to have
    # published, so an empty index here means the committed data disappeared.
    ah_check "fault.$case_name.no_silent_gaps" FAIL \
      "the committed index is EMPTY (rangeCount=$ranges) even though the archive published before the fault"
  else
    ah_check "fault.$case_name.no_silent_gaps" PASS \
      "$ranges contiguous range(s), blocks $(printf '%s' "$json" | jq -r '.minBlock')..$(printf '%s' "$json" | jq -r '.maxBlock'), inFlight=$inflight rows above the published head"
  fi
  if [ "$marker" = "true" ]; then
    ah_check "fault.$case_name.repair_marker" INFO \
      "repair-required is set: $(printf '%s' "$json" | jq -r '.repairReason // "?"') (rebuild-required is a correct outcome)"
  else
    ah_check "fault.$case_name.repair_marker" INFO "repair-required is not set"
  fi
}

# --------------------------------------------------------------------------- preflight

ah_require_cmds java javac curl jq awk sed dd df
ah_require_jar
ah_require_free_ports "$N_P2P" "$N_HTTP" "$N_RPC" "$N_JSONRPC" "$N_METRICS"

RUN_DIR="$(ah_run_dir resource-faults)"
ah_log "run directory: $RUN_DIR"
ah_compile_helpers "$RUN_DIR/classes"
ETH_P1="$(ah_signer addr 1111111111111111111111111111111111111111111111111111111111111111 | cut -f3)"

# =============================================================================================
# CASE 1 — ENOSPC
# =============================================================================================
if selected enospc; then
  if ! command -v hdiutil >/dev/null 2>&1; then
    ah_check fault.enospc.failstop SKIP "hdiutil is unavailable; the ENOSPC case needs a small mountable volume"
  else
    ah_log "case enospc: archive on a ${FAULT_IMAGE_MB} MiB image, filled until the reserve check fails"
    IMAGE_FILE="$RUN_DIR/enospc/archive-volume.dmg"
    IMAGE_MOUNT="$RUN_DIR/enospc/mnt"
    mkdir -p "$RUN_DIR/enospc"
    hdiutil create -size "${FAULT_IMAGE_MB}m" -fs HFS+ -volname ARCHFAULT -quiet "$IMAGE_FILE" \
      || ah_fatal "hdiutil create failed"
    hdiutil attach -nobrowse -quiet -mountpoint "$IMAGE_MOUNT" "$IMAGE_FILE" \
      || ah_fatal "hdiutil attach failed"
    mkdir -p "$IMAGE_MOUNT/archive" || ah_fatal "cannot create the archive directory on the image"

    start_case enospc "$IMAGE_MOUNT/archive" true
    if ! ah_wait_ready "$N_HTTP" "$READY_TIMEOUT"; then
      ah_check fault.enospc.baseline FAIL "node never became ready with the archive on the small volume"
    else
      ah_require_min_head "$N_HTTP" "$FAULT_MIN_HEAD" "$READY_TIMEOUT"
      if wait_publishing "$N_JSONRPC" "$PUBLISH_TIMEOUT"; then
        ah_check fault.enospc.baseline PASS \
          "archive publishing on the small volume at head $(ah_head_num "$N_HTTP"), free $(df -k "$IMAGE_MOUNT" | awk 'NR==2{print $4}') KiB"
      else
        ah_check fault.enospc.baseline FAIL \
          "archive never published a queryable height before the fault; the fault would land on an empty archive"
      fi

      # Fill the volume down to below the runtime reserve (hardMinFreeBytes + 16 MiB headroom).
      TARGET_KB=$(( (FAULT_HARD_MIN_FREE / 1024) / 2 ))
      FILL_INDEX=0
      while [ "$FILL_INDEX" -lt 500 ]; do
        AVAIL_KB="$(df -k "$IMAGE_MOUNT" | awk 'NR==2{print $4}')"
        [ -n "$AVAIL_KB" ] || break
        [ "$AVAIL_KB" -le "$TARGET_KB" ] && break
        CHUNK_MB=$(( (AVAIL_KB - TARGET_KB) / 1024 ))
        [ "$CHUNK_MB" -lt 1 ] && CHUNK_MB=1
        [ "$CHUNK_MB" -gt 32 ] && CHUNK_MB=32
        dd if=/dev/zero of="$IMAGE_MOUNT/fill.$FILL_INDEX" bs=1m count="$CHUNK_MB" 2>/dev/null || break
        FILL_INDEX=$(( FILL_INDEX + 1 ))
      done
      ah_log "volume filled: $(df -k "$IMAGE_MOUNT" | awk 'NR==2{print $4}') KiB free"

      grade_failstop enospc "$RUN_DIR/enospc" "$NODE_PID"
      case "$GRADE" in
        FAILSTOP)
          ah_check fault.enospc.failstop PASS "$GRADE_DETAIL"
          NODE_PID="" ;;
        EXITED_WRONG)
          ah_check fault.enospc.failstop FAIL "$GRADE_DETAIL"
          NODE_PID="" ;;
        ALIVE_PROGRESSING)
          ah_check fault.enospc.failstop FAIL "silent divergence: $GRADE_DETAIL" ;;
        ALIVE_STALLED)
          ah_check fault.enospc.failstop FAIL "no explicit fail-stop: $GRADE_DETAIL" ;;
      esac
      [ -n "$NODE_PID" ] && { ah_stop_node "$NODE_PID" 120; NODE_PID=""; }

      # The stop must be attributed to disk space, not merely to an in-flight block/record count
      # that happened to trip at the same moment: when the message carries a diskFree figure, that
      # figure has to be under the configured hard watermark.
      DISK_MSG="$(ah_disk_fault_evidence "$RUN_DIR/enospc" | head -1)"
      DISK_FREE_REPORTED="$(printf '%s' "$DISK_MSG" | sed -n 's/.*diskFree=\([0-9][0-9]*\).*/\1/p')"
      if [ -z "$DISK_MSG" ]; then
        ah_check fault.enospc.diagnosed FAIL \
          "no disk-attributed archive message; the stop, if any, was not attributed to disk space"
      elif [ -n "$DISK_FREE_REPORTED" ] && [ "$DISK_FREE_REPORTED" -ge "$FAULT_HARD_MIN_FREE" ] 2>/dev/null; then
        ah_check fault.enospc.diagnosed FAIL \
          "archive stopped while reporting diskFree=$DISK_FREE_REPORTED, at or above hardMinFreeBytes=$FAULT_HARD_MIN_FREE: $DISK_MSG"
      else
        ah_check fault.enospc.diagnosed PASS "$DISK_MSG"
      fi

      # Restart with the space returned: the archive must either recover or refuse explicitly.
      rm -f "$IMAGE_MOUNT"/fill.* 2>/dev/null
      ah_log "case enospc: space freed ($(df -k "$IMAGE_MOUNT" | awk 'NR==2{print $4}') KiB); restarting"
      start_case enospc-restart "$IMAGE_MOUNT/archive" false "$RUN_DIR/enospc/data"
      grade_restart "$RUN_DIR/enospc-restart"
      case "$RESTART_GRADE" in
        RECOVERED) ah_check fault.enospc.restart PASS "recovered: $RESTART_DETAIL" ;;
        REFUSED)   ah_check fault.enospc.restart PASS "rebuild-required: $RESTART_DETAIL" ;;
        HUNG)      ah_check fault.enospc.restart FAIL "HUNG: $RESTART_DETAIL" ;;
        *)         ah_check fault.enospc.restart FAIL "$RESTART_DETAIL" ;;
      esac
      [ -n "$NODE_PID" ] && { ah_stop_node "$NODE_PID" 120; NODE_PID=""; }

      probe_case enospc "$IMAGE_MOUNT/archive/unified"
    fi
    hdiutil detach "$IMAGE_MOUNT" -quiet 2>/dev/null || hdiutil detach "$IMAGE_MOUNT" -force -quiet 2>/dev/null
    IMAGE_MOUNT=""
  fi
fi

# =============================================================================================
# CASE 2 — PERMISSION LOSS
# =============================================================================================
if selected permission; then
  ah_log "case permission: revoke write permission on the archive directory mid-run"
  PERM_DATA="$RUN_DIR/permission/data"
  PERM_ARCHIVE="$PERM_DATA/database/archive"
  start_case permission archive true

  if ! ah_wait_ready "$N_HTTP" "$READY_TIMEOUT"; then
    ah_check fault.permission.baseline FAIL "node never became ready"
  else
    ah_require_min_head "$N_HTTP" "$FAULT_MIN_HEAD" "$READY_TIMEOUT"
    if wait_publishing "$N_JSONRPC" "$PUBLISH_TIMEOUT"; then
      ah_check fault.permission.baseline PASS "archive publishing at head $(ah_head_num "$N_HTTP")"
    else
      ah_check fault.permission.baseline FAIL "archive never published a queryable height before the fault"
    fi

    READONLY_DIR="$PERM_ARCHIVE"
    chmod -R a-w "$PERM_ARCHIVE" || ah_fatal "could not revoke write permission on $PERM_ARCHIVE"
    ah_log "archive directory is now read-only"

    grade_failstop permission "$RUN_DIR/permission" "$NODE_PID"
    case "$GRADE" in
      FAILSTOP)
        ah_check fault.permission.mid_run PASS "$GRADE_DETAIL"
        NODE_PID="" ;;
      EXITED_WRONG)
        ah_check fault.permission.mid_run FAIL "$GRADE_DETAIL"
        NODE_PID="" ;;
      ALIVE_PROGRESSING|ALIVE_STALLED)
        # RocksDB keeps writing through file descriptors it opened before the chmod; a running node
        # may legitimately not notice until it needs to create a new file.  That is not a defect by
        # itself, so this is reported rather than failed — the load-bearing assertion is the
        # deterministic read-only *restart* below plus the structural probe.
        ah_check fault.permission.mid_run INFO \
          "INCONCLUSIVE: $GRADE_DETAIL (writes through already-open descriptors are unaffected by chmod)" ;;
    esac

    if [ -n "$NODE_PID" ]; then
      ah_stop_node "$NODE_PID" 120
      NODE_PID=""
    fi

    # Deterministic half: a restart must open the archive for writing, which cannot succeed on a
    # read-only directory.  Anything other than an explicit refusal is a defect.
    ah_log "case permission: restart while the archive directory is still read-only"
    start_case permission-readonly-restart archive false "$PERM_DATA"
    grade_restart "$RUN_DIR/permission-readonly-restart"
    case "$RESTART_GRADE" in
      REFUSED)
        ah_check fault.permission.readonly_restart PASS "$RESTART_DETAIL" ;;
      RECOVERED)
        ah_check fault.permission.readonly_restart FAIL \
          "node started and served requests with an unwritable archive directory: $RESTART_DETAIL" ;;
      HUNG)
        ah_check fault.permission.readonly_restart FAIL "HUNG: $RESTART_DETAIL" ;;
      *)
        ah_check fault.permission.readonly_restart FAIL "$RESTART_DETAIL" ;;
    esac
    [ -n "$NODE_PID" ] && { ah_stop_node "$NODE_PID" 120; NODE_PID=""; }

    chmod -R u+rwX "$PERM_ARCHIVE" || ah_fatal "could not restore write permission on $PERM_ARCHIVE"
    READONLY_DIR=""
    ah_log "case permission: permission restored; restarting"
    start_case permission-restart archive false "$PERM_DATA"
    grade_restart "$RUN_DIR/permission-restart"
    case "$RESTART_GRADE" in
      RECOVERED) ah_check fault.permission.restart PASS "recovered: $RESTART_DETAIL" ;;
      REFUSED)   ah_check fault.permission.restart PASS "rebuild-required: $RESTART_DETAIL" ;;
      HUNG)      ah_check fault.permission.restart FAIL "HUNG: $RESTART_DETAIL" ;;
      *)         ah_check fault.permission.restart FAIL "$RESTART_DETAIL" ;;
    esac
    [ -n "$NODE_PID" ] && { ah_stop_node "$NODE_PID" 120; NODE_PID=""; }

    probe_case permission "$PERM_ARCHIVE/unified"
  fi
fi

# =============================================================================================
# CASE 3 — PARTIAL / TRUNCATED FILE VISIBILITY (opt-in)
# =============================================================================================
if selected truncation; then
  ah_log "case truncation: truncate the archive MANIFEST while the node is stopped"
  TRUNC_DATA="$RUN_DIR/truncation/data"
  TRUNC_ARCHIVE="$TRUNC_DATA/database/archive/unified"
  start_case truncation archive true

  if ! ah_wait_ready "$N_HTTP" "$READY_TIMEOUT"; then
    ah_check fault.truncation.baseline FAIL "node never became ready"
  else
    ah_require_min_head "$N_HTTP" "$FAULT_MIN_HEAD" "$READY_TIMEOUT"
    if wait_publishing "$N_JSONRPC" "$PUBLISH_TIMEOUT"; then
      ah_check fault.truncation.baseline PASS "archive publishing at head $(ah_head_num "$N_HTTP")"
    else
      ah_check fault.truncation.baseline FAIL "archive never published a queryable height before the fault"
    fi
    ah_stop_node "$NODE_PID" 120; NODE_PID=""

    MANIFEST="$(ls -t "$TRUNC_ARCHIVE"/MANIFEST-* 2>/dev/null | head -1)"
    if [ -z "$MANIFEST" ]; then
      ah_check fault.truncation.restart SKIP "no MANIFEST file found under $TRUNC_ARCHIVE"
    else
      MANIFEST_SIZE="$(wc -c <"$MANIFEST" | tr -d ' ')"
      TRUNC_SIZE=$(( MANIFEST_SIZE / 2 ))
      ah_log "truncating $(basename "$MANIFEST") from $MANIFEST_SIZE to $TRUNC_SIZE bytes"
      # Rewrite the first half only: the file is present but partially visible, which is the fault
      # shape a torn/partially-replicated volume produces.
      dd if="$MANIFEST" of="$MANIFEST.part" bs=1 count="$TRUNC_SIZE" 2>/dev/null \
        && mv "$MANIFEST.part" "$MANIFEST"
      rm -f "$MANIFEST.part"
      # VERIFY THE FAULT WAS ACTUALLY INJECTED. If dd or mv silently failed the MANIFEST is
      # intact, the node restarts cleanly, and the run would report a FALSE product failure
      # ("started on a truncated MANIFEST") for a fault that never happened.
      MANIFEST_NOW="$(wc -c <"$MANIFEST" 2>/dev/null | tr -d ' ')"
      case "$MANIFEST_NOW" in ''|*[!0-9]*) MANIFEST_NOW=-1 ;; esac
      if [ "$MANIFEST_NOW" -ne "$TRUNC_SIZE" ]; then
        ah_check fault.truncation.injected SKIP \
          "the MANIFEST could not be truncated (size is $MANIFEST_NOW, wanted $TRUNC_SIZE); the fault was never injected"
        ah_check fault.truncation.restart SKIP "no truncation fault to grade"
      else
        ah_check fault.truncation.injected PASS \
          "MANIFEST truncated $MANIFEST_SIZE -> $MANIFEST_NOW bytes"

        start_case truncation-restart archive false "$TRUNC_DATA"
        grade_restart "$RUN_DIR/truncation-restart"
        case "$RESTART_GRADE" in
          REFUSED)
            ah_check fault.truncation.restart PASS "$RESTART_DETAIL" ;;
          RECOVERED)
            ah_check fault.truncation.restart FAIL \
              "node started on a truncated MANIFEST and served requests: $RESTART_DETAIL" ;;
          HUNG)
            ah_check fault.truncation.restart FAIL "HUNG: $RESTART_DETAIL" ;;
          *)
            ah_check fault.truncation.restart FAIL "$RESTART_DETAIL" ;;
        esac
        [ -n "$NODE_PID" ] && { ah_stop_node "$NODE_PID" 120; NODE_PID=""; }
      fi
    fi
  fi
fi

ah_finish FAULT_E2E "cases=$(printf '%s' "$FAULT_SCENARIOS" | wc -w | tr -d ' ')"
