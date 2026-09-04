#!/usr/bin/env bash
#
# run-all.sh -- driver for the archive private-chain fault-injection suite.
#
# Runs every scenario-*.sh in this directory in a fixed order, streams each one's output
# live, records a per-scenario verdict, prints a summary table, and exits nonzero if
# anything failed. A subset can be selected by name.
#
#   ./run-all.sh                          # whole suite, in order
#   ./run-all.sh --list                   # show what would run
#   ./run-all.sh concurrency kill         # subset, matched by substring
#   ./run-all.sh --fail-fast              # stop at the first failure
#   ./run-all.sh --timeout 3600           # per-scenario wall-clock budget
#   ./run-all.sh concurrency -- --no-fork # everything after -- goes to each scenario
#
# VERDICTS
#   PASS          exit 0 and the scenario printed its own <NAME>_OK marker
#   FAIL          the PRODUCT misbehaved: exit 1 (or any other nonzero that is not a harness
#                 error), or exit 0 with no marker (a silent pass is not a pass)
#   INCONCLUSIVE  the scenario COULD NOT RUN: exit 2 / a HARNESS_ERROR line, or this driver's
#                 port pre-flight refused to start it. This is NOT a statement about the
#                 product -- nothing was measured -- but it still keeps the suite non-green,
#                 because an unmeasured scenario is not a passing scenario.
#   SKIPPED       exit 77, or the scenario printed a <NAME>_SKIPPED marker
#   TIMEOUT       the scenario exceeded --timeout and was terminated. Graded with FAIL, not
#                 INCONCLUSIVE, on purpose: a hang is one of the product defects this suite
#                 exists to catch, so it must never be filed under "could not run".
#
# FINAL LINE (machine-checkable)
#   PRIVATE_CHAIN_FAULT_SUITE_OK   scenarios=<n> passed=<n> failed=0 inconclusive=0 skipped=<n>
#                                                                                    -> exit 0
#   PRIVATE_CHAIN_FAULT_SUITE_FAIL scenarios=<n> passed=<n> failed=<n> inconclusive=<n> ...
#                                                                                    -> exit 1
#   exit 2 = usage or precondition error (nothing ran)
#
# A green suite requires failed=0 AND inconclusive=0 AND passed>0.
#
# CONTRACT EXPECTED OF EACH SCENARIO
#   - exits 0 on success and prints a line matching ^[A-Z0-9_]+_OK\b
#   - exits 1 on a product failure and prints ^[A-Z0-9_]+_FAIL\b
#   - exits 2 when the harness/environment could not run it, printing ^HARNESS_ERROR
#   - exits 77 when it decides the scenario is not applicable on this host
#   - picks up the jar from FULLNODE_JAR or ARCHIVE_HARNESS_JAR, and the artifact root from
#     HARNESS_RUN_ROOT, ARCHIVE_HARNESS_RUN_ROOT or HS_WORK_ROOT (this is how --jar and
#     --out-dir reach it, rather than flags every scenario would have to implement). All
#     spellings are exported below because the scenarios use different ones.
#   - traps SIGTERM and tears down its own child processes, so --timeout can reclaim them
#
set -uo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT_DEFAULT="$(cd "${HARNESS_DIR}/../../.." && pwd)"

# lib.sh is the shared harness library; sourced when present, with fallbacks below so the
# driver still works standalone. See scenario-*.sh for the same pattern.
if [ -f "${HARNESS_DIR}/lib.sh" ]; then
  # shellcheck source=lib.sh disable=SC1091
  . "${HARNESS_DIR}/lib.sh"
else
  printf 'WARN  %s/lib.sh not found; using built-in fallbacks\n' "${HARNESS_DIR}" >&2
fi

# ports.sh is THE port map. It is dependency-free and guards against double-sourcing, so this
# is safe whether or not lib.sh already pulled it in. Without it the pre-flight below cannot
# know which ports a scenario is about to bind, so it is a hard requirement.
if [ -f "${HARNESS_DIR}/ports.sh" ]; then
  # shellcheck source=ports.sh disable=SC1091
  . "${HARNESS_DIR}/ports.sh"
else
  printf 'ERROR %s/ports.sh not found; cannot pre-flight scenario ports\n' "${HARNESS_DIR}" >&2
  exit 2
fi

have_fn() { declare -F "$1" >/dev/null 2>&1; }
have_fn log  || log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
have_fn warn || warn() { printf '[%s] WARN  %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
have_fn err  || err()  { printf '[%s] ERROR %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
have_fn die  || die()  { err "$*"; exit 2; }

# ---------------------------------------------------------------------------
# options
# ---------------------------------------------------------------------------
# Declared run order. smoke runs first: it is the cheap environment check, and there is no
# point spending ~40 minutes on the fault scenarios if the node cannot even start here.
# history-accuracy runs second, before any fault is injected: if the archive returns a WRONG
# value on a chain where nothing went wrong, "it survived a SIGKILL" is not worth measuring.
SUITE_ORDER="smoke history-accuracy fork-reorg kill-matrix resource-faults concurrency-under-fault"

JAR=""
OUT_DIR=""
PER_SCENARIO_TIMEOUT=5400
FAIL_FAST=0
LIST_ONLY=0
DRY_RUN=0
QUIET=0
SELECTED=""
FORWARD_ARGS=""

usage() {
  cat <<'USAGE'
run-all.sh -- archive private-chain fault-injection suite driver

  ./run-all.sh [options] [scenario ...] [-- scenario-args ...]

Options:
  -l, --list              list the scenarios that would run, then exit
  -f, --fail-fast         stop after the first failing scenario
  -t, --timeout SECS      per-scenario wall-clock budget (default 5400)
  -o, --out-dir DIR       where logs and run artifacts go
                          (default $TMPDIR/java-tron-archive-harness/<ts>-suite)
  -j, --jar PATH          FullNode.jar to exercise, exported as FULLNODE_JAR
  -q, --quiet             do not stream scenario output; logs are still written
  -n, --dry-run           print the plan without running anything
  -h, --help              this help

Scenario selection matches the slug (the scenario-<slug>.sh middle part) exactly first,
then by substring, so "concurrency" and "kill" both work. Unmatched names are an error.
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    -l|--list) LIST_ONLY=1; shift ;;
    -f|--fail-fast) FAIL_FAST=1; shift ;;
    -t|--timeout) PER_SCENARIO_TIMEOUT="${2:-}"; shift 2 ;;
    -o|--out-dir) OUT_DIR="${2:-}"; shift 2 ;;
    -j|--jar) JAR="${2:-}"; shift 2 ;;
    -q|--quiet) QUIET=1; shift ;;
    -n|--dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    --) shift; while [ $# -gt 0 ]; do FORWARD_ARGS="${FORWARD_ARGS} $1"; shift; done ;;
    -*) die "unknown option: $1 (try --help)" ;;
    *) SELECTED="${SELECTED} $1"; shift ;;
  esac
done

case "${PER_SCENARIO_TIMEOUT}" in
  ''|*[!0-9]*) die "--timeout expects whole seconds, got '${PER_SCENARIO_TIMEOUT}'" ;;
esac

# ---------------------------------------------------------------------------
# discovery and ordering
# ---------------------------------------------------------------------------
slug_of() { # path -> slug
  local base
  base="$(basename "$1")"
  base="${base#scenario-}"
  printf '%s' "${base%.sh}"
}

# Discovered slugs, newline-delimited, in run order: the declared SUITE_ORDER first (so a
# freshly added scenario cannot silently reorder the suite), then anything else found on
# disk, alphabetically. Unknown scenarios still run -- they are just appended.
#
# The executable bit is what separates a scenario from a sourced library: scenario-common.sh
# is shared plumbing that other scenarios `.`-include, and running it would fail the suite
# every time. Non-executable files are reported rather than silently dropped.
list_non_executable() {
  local file
  for file in "${HARNESS_DIR}"/scenario-*.sh; do
    [ -f "${file}" ] || continue
    [ -x "${file}" ] || printf ' %s' "$(basename "${file}")"
  done
}

discover_slugs() {
  local found="" file slug known known_slug
  for file in "${HARNESS_DIR}"/scenario-*.sh; do
    [ -f "${file}" ] || continue
    [ -x "${file}" ] || continue
    found="${found}$(slug_of "${file}")"$'\n'
  done
  found="$(printf '%s' "${found}" | grep -v '^$' | sort)"
  [ -n "${found}" ] || return 0
  for slug in ${SUITE_ORDER}; do
    if printf '%s\n' "${found}" | grep -qx -- "${slug}"; then
      printf '%s\n' "${slug}"
    fi
  done
  while IFS= read -r slug; do
    [ -n "${slug}" ] || continue
    known=0
    for known_slug in ${SUITE_ORDER}; do
      [ "${slug}" = "${known_slug}" ] && known=1
    done
    [ "${known}" -eq 0 ] && printf '%s\n' "${slug}"
  done <<EOF
${found}
EOF
}

ALL_SLUGS="$(discover_slugs)"
NON_EXECUTABLE="$(list_non_executable)"
[ -n "${ALL_SLUGS}" ] || die "no executable scenario-*.sh found in ${HARNESS_DIR}"
[ -n "${NON_EXECUTABLE}" ] && \
  log "not run (sourced libraries / missing exec bit):${NON_EXECUTABLE}"

# resolve_selection -- map the user's names onto discovered slugs, preserving run order
resolve_selection() {
  local wanted="$1" slug name matched any
  if [ -z "${wanted}" ]; then
    printf '%s\n' "${ALL_SLUGS}"
    return 0
  fi
  for name in ${wanted}; do
    any=0
    while IFS= read -r slug; do
      [ -n "${slug}" ] || continue
      [ "${slug}" = "${name}" ] && any=1
    done <<EOF
${ALL_SLUGS}
EOF
    if [ "${any}" -eq 0 ]; then
      matched="$(printf '%s\n' "${ALL_SLUGS}" | grep -F -- "${name}" || true)"
      [ -n "${matched}" ] || {
        err "no scenario matches '${name}'; available:"
        printf '%s\n' "${ALL_SLUGS}" | sed 's/^/  /' >&2
        return 1
      }
    fi
  done
  # Emit in canonical run order, keeping only selected slugs, de-duplicated.
  while IFS= read -r slug; do
    [ -n "${slug}" ] || continue
    for name in ${wanted}; do
      if [ "${slug}" = "${name}" ] || printf '%s' "${slug}" | grep -qF -- "${name}"; then
        printf '%s\n' "${slug}"
        break
      fi
    done
  done <<EOF
${ALL_SLUGS}
EOF
}

RUN_SLUGS="$(resolve_selection "${SELECTED}")" || exit 2
[ -n "${RUN_SLUGS}" ] || die "selection matched no scenarios"

if [ "${LIST_ONLY}" -eq 1 ]; then
  printf 'scenarios in %s:\n' "${HARNESS_DIR}"
  while IFS= read -r slug; do
    [ -n "${slug}" ] || continue
    printf '  %-26s %s\n' "${slug}" "${HARNESS_DIR}/scenario-${slug}.sh"
  done <<EOF
${RUN_SLUGS}
EOF
  exit 0
fi

# ---------------------------------------------------------------------------
# environment handed to every scenario
# ---------------------------------------------------------------------------
if [ -n "${JAR}" ]; then
  [ -f "${JAR}" ] || die "jar not found: ${JAR}"
  JAR="$(cd "$(dirname "${JAR}")" && pwd)/$(basename "${JAR}")"
else
  JAR="${FULLNODE_JAR:-${REPO_ROOT_DEFAULT}/framework/build/libs/FullNode.jar}"
fi
if [ ! -f "${JAR}" ]; then
  die "FullNode.jar not found at ${JAR}
build it first:  cd ${REPO_ROOT_DEFAULT} && ./gradlew :framework:buildFullNodeJar
or point at one: ./run-all.sh --jar /path/to/FullNode.jar"
fi
# Three names for one thing, because the scenarios grew independently:
#   FULLNODE_JAR         scenario-concurrency-under-fault.sh
#   ARCHIVE_HARNESS_JAR  scenario-common.sh (fork-reorg, resource-faults)
# Exporting only FULLNODE_JAR made --jar a silent no-op for three of five scenarios: they
# kept using the repo default while the summary claimed the requested jar was under test.
export FULLNODE_JAR="${JAR}"
export ARCHIVE_HARNESS_JAR="${JAR}"

if [ -z "${OUT_DIR}" ]; then
  OUT_DIR="${TMPDIR:-/tmp}/java-tron-archive-harness/$(date +%Y%m%d-%H%M%S)-suite"
fi
mkdir -p "${OUT_DIR}" || die "cannot create out dir ${OUT_DIR}"
OUT_DIR="$(cd "${OUT_DIR}" && pwd)"
# Same story for the artifact root:
#   HARNESS_RUN_ROOT          scenario-concurrency-under-fault.sh
#   ARCHIVE_HARNESS_RUN_ROOT  scenario-common.sh (fork-reorg, resource-faults)
#   HS_WORK_ROOT              lib.sh (smoke, kill-matrix)
# Without all three, --out-dir scattered artifacts across $TMPDIR and the summary's
# "logs: <dir>" line pointed at a directory that held only the driver's own logs.
export HARNESS_RUN_ROOT="${OUT_DIR}"
export ARCHIVE_HARNESS_RUN_ROOT="${OUT_DIR}"
export HS_WORK_ROOT="${OUT_DIR}"

if [ "${DRY_RUN}" -eq 1 ]; then
  printf 'jar      : %s\n' "${JAR}"
  printf 'out dir  : %s\n' "${OUT_DIR}"
  printf 'timeout  : %ss per scenario\n' "${PER_SCENARIO_TIMEOUT}"
  printf 'forward  :%s\n' "${FORWARD_ARGS:- (none)}"
  printf 'plan     :\n'
  while IFS= read -r slug; do
    [ -n "${slug}" ] || continue
    printf '  scenario-%s.sh%s\n' "${slug}" "${FORWARD_ARGS}"
  done <<EOF
${RUN_SLUGS}
EOF
  exit 0
fi

# ---------------------------------------------------------------------------
# execution
# ---------------------------------------------------------------------------
RESULT_SLUGS=""
RESULT_VERDICTS=""
RESULT_DURATIONS=""
RESULT_MARKERS=""
RESULT_LOGS=""
TOTAL=0
PASSED=0
FAILED=0
INCONCLUSIVE=0
SKIPPED=0

CURRENT_CHILD=""
CURRENT_TAIL=""

# ---------------------------------------------------------------------------
# port pre-flight
# ---------------------------------------------------------------------------
# Every scenario owns a 400-port band (ports.sh). Before starting one, prove the whole band is
# free -- not just "no listener", but no socket of any state, because a TIME_WAIT left behind by
# the previous scenario's curl storm is exactly the kind of thing that makes the next scenario
# fail for reasons that have nothing to do with the product.
#
# TIME_WAIT is transient, so a busy band is WAITED OUT rather than treated as fatal on sight.
# Only if it is still busy after the budget does the scenario go down as INCONCLUSIVE, naming
# the PID that holds the port.
PORT_WAIT_SECS="${ARCHIVE_HARNESS_PORT_WAIT_SECS:-120}"
PORT_POLL_SECS=3

# band_sockets LO HI -- one line per socket touching the band, "<state> <detail>".
# Two sources because neither alone is complete on macOS:
#   lsof     process-owned sockets (LISTEN / ESTABLISHED / CLOSE_WAIT) WITH the owning pid
#   netstat  orphaned states (TIME_WAIT, FIN_WAIT_2) that have no process and are invisible
#            to lsof, yet still make bind() fail
band_sockets() {
  local lo="$1" hi="$2" have_lsof=0
  if command -v lsof >/dev/null 2>&1; then
    have_lsof=1
    lsof -nP -iTCP:"${lo}"-"${hi}" 2>/dev/null \
      | awk 'NR > 1 { printf "held  %s pid=%s %s %s\n", $1, $2, $9, $10 }'
  fi
  if command -v netstat >/dev/null 2>&1; then
    # skip=1 only when lsof already listed the process-owned states, so dropping lsof from the
    # host degrades the diagnosis but never the detection.
    netstat -an -p tcp 2>/dev/null | awk -v lo="${lo}" -v hi="${hi}" -v skip="${have_lsof}" '
      $1 ~ /^tcp/ {
        n = split($4, a, ".")
        p = a[n] + 0
        state = (NF >= 6 ? $6 : "?")
        if (p < lo || p > hi) next
        if (skip == 1 && (state == "LISTEN" || state == "ESTABLISHED")) next
        printf "lingering %s local=%s\n", state, $4
      }'
  fi
}

# preflight_ports SLUG LOGPATH -- 0 when the band is free, 1 when it is still busy after the wait.
# Appends its own diagnosis to LOGPATH so an INCONCLUSIVE verdict is never silent.
preflight_ports() {
  local slug="$1" log_path="$2" range lo hi waited busy
  range="$(ah_port_band_range "${slug}")"
  lo="${range% *}"
  hi="${range#* }"

  waited=0
  while :; do
    busy="$(band_sockets "${lo}" "${hi}")"
    [ -n "${busy}" ] || break
    if [ "${waited}" -ge "${PORT_WAIT_SECS}" ]; then
      {
        printf 'PORT PRE-FLIGHT FAILED for %s (band %s-%s)\n' "${slug}" "${lo}" "${hi}"
        printf 'still busy after %ss:\n' "${PORT_WAIT_SECS}"
        printf '%s\n' "${busy}" | sed 's/^/  /'
        printf 'HARNESS_ERROR ports %s-%s not free; the scenario was never started\n' "${lo}" "${hi}"
      } | tee -a "${log_path}"
      return 1
    fi
    if [ "${waited}" -eq 0 ]; then
      log "${slug}: band ${lo}-${hi} busy, waiting up to ${PORT_WAIT_SECS}s for release"
      printf '%s\n' "${busy}" | sed 's/^/  /' >&2
    fi
    sleep "${PORT_POLL_SECS}"
    waited=$((waited + PORT_POLL_SECS))
  done
  [ "${waited}" -eq 0 ] || log "${slug}: band ${lo}-${hi} free after ${waited}s"
  return 0
}

# kill_tree PID SIGNAL -- signal the scenario's whole process group, falling back to the
# single pid when it has no group of its own.
kill_tree() {
  local pid="$1" sig="$2"
  [ -n "${pid}" ] || return 0
  kill "-${sig}" "-${pid}" 2>/dev/null || kill "-${sig}" "${pid}" 2>/dev/null || true
  return 0
}

cleanup() {
  [ -n "${CURRENT_TAIL}" ] && kill "${CURRENT_TAIL}" 2>/dev/null
  [ -n "${CURRENT_CHILD}" ] && kill_tree "${CURRENT_CHILD}" TERM
  return 0
}
trap cleanup EXIT
trap 'err "suite interrupted"; cleanup; exit 1' INT TERM

record() { # slug verdict duration marker log
  RESULT_SLUGS="${RESULT_SLUGS}$1"$'\n'
  RESULT_VERDICTS="${RESULT_VERDICTS}$2"$'\n'
  RESULT_DURATIONS="${RESULT_DURATIONS}$3"$'\n'
  RESULT_MARKERS="${RESULT_MARKERS}${4:--}"$'\n'
  RESULT_LOGS="${RESULT_LOGS}$5"$'\n'
  TOTAL=$((TOTAL + 1))
  case "$2" in
    PASS) PASSED=$((PASSED + 1)) ;;
    SKIPPED) SKIPPED=$((SKIPPED + 1)) ;;
    # INCONCLUSIVE is counted apart from FAILED so the summary never presents "the harness
    # could not run this" as "the product is broken". It still blocks a green suite.
    INCONCLUSIVE) INCONCLUSIVE=$((INCONCLUSIVE + 1)) ;;
    *) FAILED=$((FAILED + 1)) ;;
  esac
}

# marker_in LOG -> the last ^NAME_OK / ^NAME_FAIL / ^NAME_SKIPPED line's marker token.
# Matches the whole line and then takes field 1 rather than using \b, which is a GNU
# extension that BSD grep on macOS does not reliably honour.
marker_in() {
  grep -hE '^[A-Z][A-Z0-9_]*_(OK|FAIL|SKIPPED)([[:space:]]|$)' "$1" 2>/dev/null \
    | tail -1 | awk '{print $1}'
}

# harness_error_in LOG -> 0 when the scenario declared it could not run. HARNESS_ERROR is
# deliberately NOT in marker_in's pattern: it is not a verdict about the product.
harness_error_in() {
  grep -qE '^HARNESS_ERROR([[:space:]]|$)' "$1" 2>/dev/null
}

# run_scenario SLUG -> sets SCENARIO_RC and SCENARIO_TIMED_OUT
#
# No timeout(1) on macOS, so the budget is enforced with a watchdog subshell. The scenario
# is started as a direct background child of THIS shell -- never inside $( ) -- because a
# job owned by a subshell cannot be reaped with `wait`, which would corrupt the exit code
# every verdict below depends on.
SCENARIO_RC=0
SCENARIO_TIMED_OUT=0
run_scenario() {
  local slug="$1" script="${HARNESS_DIR}/scenario-${slug}.sh"
  local log="${OUT_DIR}/${slug}.log" fired="${OUT_DIR}/${slug}.timeout"
  local watchdog rc
  SCENARIO_RC=0
  SCENARIO_TIMED_OUT=0
  rm -f "${fired}"
  : >"${log}"

  # `set -m` puts the scenario in its own process group, so a timeout can terminate the
  # whole tree (node JVMs, storm drivers, relays) with a single group kill. Without it the
  # scenario's shell dies but its children survive and keep holding ports, which would
  # then break every scenario that runs after it.
  set -m
  # shellcheck disable=SC2086 -- FORWARD_ARGS is intentionally word-split
  if [ -x "${script}" ]; then
    "${script}" ${FORWARD_ARGS} >>"${log}" 2>&1 &
  else
    bash "${script}" ${FORWARD_ARGS} >>"${log}" 2>&1 &
  fi
  CURRENT_CHILD=$!
  set +m

  if [ "${QUIET}" -eq 0 ]; then
    tail -n +1 -f "${log}" 2>/dev/null &
    CURRENT_TAIL=$!
  fi

  ( sleep "${PER_SCENARIO_TIMEOUT}"
    if kill -0 "${CURRENT_CHILD}" 2>/dev/null; then
      : >"${fired}"
      kill_tree "${CURRENT_CHILD}" TERM
      sleep 20
      kill_tree "${CURRENT_CHILD}" KILL
    fi ) >/dev/null 2>&1 &
  watchdog=$!

  wait "${CURRENT_CHILD}"
  rc=$?
  CURRENT_CHILD=""

  kill "${watchdog}" 2>/dev/null
  wait "${watchdog}" 2>/dev/null
  if [ -n "${CURRENT_TAIL}" ]; then
    sleep 1
    kill "${CURRENT_TAIL}" 2>/dev/null
    wait "${CURRENT_TAIL}" 2>/dev/null
    CURRENT_TAIL=""
  fi

  [ -f "${fired}" ] && SCENARIO_TIMED_OUT=1
  SCENARIO_RC="${rc}"
  return 0
}

log "suite      : $(printf '%s' "${RUN_SLUGS}" | grep -c . ) scenario(s)"
log "jar        : ${JAR}"
log "out dir    : ${OUT_DIR}"
log "timeout    : ${PER_SCENARIO_TIMEOUT}s per scenario"
[ -n "${FORWARD_ARGS}" ] && log "forwarding :${FORWARD_ARGS}"

SUITE_STARTED=$(date +%s)

while IFS= read -r slug; do
  [ -n "${slug}" ] || continue
  script="${HARNESS_DIR}/scenario-${slug}.sh"
  log_file="${OUT_DIR}/${slug}.log"

  if [ ! -f "${script}" ]; then
    err "${slug}: ${script} disappeared"
    record "${slug}" FAIL 0 "-" "${log_file}"
    continue
  fi
  printf '\n=============================================================\n'
  printf '>>> %s\n' "${slug}"
  printf '=============================================================\n'

  started=$(date +%s)

  # Refuse to start a scenario onto ports somebody else still holds. A collision here produces
  # a node that dies on bind and a verdict that describes the harness, not the product -- which
  # is precisely what INCONCLUSIVE exists to avoid asserting.
  : >"${log_file}"
  if ! preflight_ports "${slug}" "${log_file}"; then
    elapsed=$(( $(date +%s) - started ))
    log "${slug}: INCONCLUSIVE (port pre-flight failed, ${elapsed}s)"
    record "${slug}" INCONCLUSIVE "${elapsed}" "-" "${log_file}"
    if [ "${FAIL_FAST}" -eq 1 ]; then
      warn "--fail-fast: stopping after ${slug}"
      break
    fi
    continue
  fi

  run_scenario "${slug}"
  elapsed=$(( $(date +%s) - started ))
  marker="$(marker_in "${log_file}")"

  if [ "${SCENARIO_TIMED_OUT}" -eq 1 ]; then
    # A hang is a candidate product defect, so it stays in the FAIL bucket rather than being
    # excused as "could not run".
    verdict=TIMEOUT
    detail="exceeded ${PER_SCENARIO_TIMEOUT}s"
  elif [ "${SCENARIO_RC}" -eq 77 ]; then
    verdict=SKIPPED
    detail="scenario reported not-applicable"
  elif [ "${SCENARIO_RC}" -eq 2 ]; then
    # The documented harness-error exit. No node behaviour was graded, so this must not be
    # reported as a product failure.
    verdict=INCONCLUSIVE
    detail="exit 2 (harness could not run it)"
  elif [ "${SCENARIO_RC}" -eq 0 ]; then
    case "${marker}" in
      *_OK) verdict=PASS; detail="${marker}" ;;
      *_SKIPPED) verdict=SKIPPED; detail="${marker}" ;;
      # Exit 0 with no marker is treated as a failure on purpose: the suite's whole
      # premise is machine-checkable evidence, and a scenario that produced none has
      # not demonstrated anything.
      *) verdict=FAIL; detail="exit 0 but no _OK marker" ;;
    esac
  elif [ -z "${marker}" ] && harness_error_in "${log_file}"; then
    # Killed or crashed after declaring a harness error and before printing any verdict --
    # e.g. exit 137 from an external kill. Still no verdict about the product.
    verdict=INCONCLUSIVE
    detail="exit ${SCENARIO_RC} after HARNESS_ERROR"
  else
    verdict=FAIL
    detail="exit ${SCENARIO_RC}"
  fi

  log "${slug}: ${verdict} (${detail}, ${elapsed}s)"
  record "${slug}" "${verdict}" "${elapsed}" "${marker}" "${log_file}"

  if [ "${FAIL_FAST}" -eq 1 ] && [ "${verdict}" != "PASS" ] && [ "${verdict}" != "SKIPPED" ]; then
    warn "--fail-fast: stopping after ${slug}"
    break
  fi
done <<EOF
${RUN_SLUGS}
EOF

SUITE_ELAPSED=$(( $(date +%s) - SUITE_STARTED ))

# ---------------------------------------------------------------------------
# summary
# ---------------------------------------------------------------------------
printf '\n=============================================================\n'
printf 'SUITE SUMMARY\n'
printf '=============================================================\n'
printf '%-26s %-12s %8s  %s\n' "SCENARIO" "VERDICT" "SECONDS" "MARKER"
printf '%-26s %-12s %8s  %s\n' "--------------------------" "------------" "--------" "--------------------"
index=1
while [ "${index}" -le "${TOTAL}" ]; do
  s="$(printf '%s' "${RESULT_SLUGS}" | sed -n "${index}p")"
  v="$(printf '%s' "${RESULT_VERDICTS}" | sed -n "${index}p")"
  d="$(printf '%s' "${RESULT_DURATIONS}" | sed -n "${index}p")"
  m="$(printf '%s' "${RESULT_MARKERS}" | sed -n "${index}p")"
  printf '%-26s %-12s %8s  %s\n' "${s}" "${v}" "${d}" "${m}"
  index=$((index + 1))
done
printf '%-26s %-12s %8s  %s\n' "--------------------------" "------------" "--------" "--------------------"
# INCONCLUSIVE is its own column, never folded into FAILED: it means "not measured", and the
# difference between "the product is broken" and "we could not look" is the whole point.
printf '%-26s passed=%d failed=%d inconclusive=%d skipped=%d\n' \
  "TALLY" "${PASSED}" "${FAILED}" "${INCONCLUSIVE}" "${SKIPPED}"

# Per-window / per-phase sub-verdicts, so a KILL_MATRIX or concurrency run shows which
# durability window or phase actually failed without opening the logs. The pattern matches
# any first token ending in VERDICT, which covers PHASE_VERDICT here as well as the
# KM_VERDICT / KM_ROW_VERDICT / AH_CHECK_VERDICT lines the other scenarios emit.
printf '\nSUB-VERDICTS\n'
index=1
any_sub=0
while [ "${index}" -le "${TOTAL}" ]; do
  s="$(printf '%s' "${RESULT_SLUGS}" | sed -n "${index}p")"
  l="$(printf '%s' "${RESULT_LOGS}" | sed -n "${index}p")"
  subs="$(grep -hE '^[A-Z][A-Z0-9_]*VERDICT[[:space:]]' "${l}" 2>/dev/null || true)"
  if [ -n "${subs}" ]; then
    any_sub=1
    printf '  %s:\n' "${s}"
    printf '%s\n' "${subs}" | sed 's/^/    /'
  fi
  index=$((index + 1))
done
[ "${any_sub}" -eq 0 ] && printf '  (none reported)\n'

printf '\nlogs: %s\n' "${OUT_DIR}"
printf 'total: %ss\n\n' "${SUITE_ELAPSED}"

if [ "${FAILED}" -eq 0 ] && [ "${INCONCLUSIVE}" -eq 0 ] && [ "${PASSED}" -gt 0 ]; then
  printf 'PRIVATE_CHAIN_FAULT_SUITE_OK scenarios=%d passed=%d failed=0 inconclusive=0 skipped=%d elapsed=%ds\n' \
    "${TOTAL}" "${PASSED}" "${SKIPPED}" "${SUITE_ELAPSED}"
  exit 0
fi
if [ "${FAILED}" -eq 0 ] && [ "${INCONCLUSIVE}" -eq 0 ] && [ "${PASSED}" -eq 0 ]; then
  # Everything skipped: nothing was validated, so this is not a green suite.
  printf 'PRIVATE_CHAIN_FAULT_SUITE_FAIL scenarios=%d passed=0 failed=0 inconclusive=0 skipped=%d reason=nothing-validated\n' \
    "${TOTAL}" "${SKIPPED}"
  exit 1
fi
# Nonzero for both FAIL and INCONCLUSIVE so CI notices either way; the counters say which.
# inconclusive>0 with failed=0 means the suite never reached a verdict about the product --
# read it as "re-run needed", not "regression found".
printf 'PRIVATE_CHAIN_FAULT_SUITE_FAIL scenarios=%d passed=%d failed=%d inconclusive=%d skipped=%d elapsed=%ds\n' \
  "${TOTAL}" "${PASSED}" "${FAILED}" "${INCONCLUSIVE}" "${SKIPPED}" "${SUITE_ELAPSED}"
exit 1
