#!/usr/bin/env bash
#
# scenario-concurrency-under-fault.sh -- archive fault-injection harness, target (D).
#
# WHAT THIS PROVES
#   Historical archive queries are held in flight (a continuous multi-threaded storm of
#   eth_getBalance / eth_getStorageAt / eth_call, plus debug_traceTransaction when
#   storage.archive.debug.enable is on) while a fault happens underneath them:
#
#     baseline    no fault              -- establishes a clean floor and proves the storm works
#     clean-stop  SIGTERM under storm   -- graceful shutdown while queries are executing
#     sigkill     SIGKILL under storm   -- process boundary while queries are executing
#     reorg       switchFork under storm-- unwind/re-capture while queries are executing
#
#   For every phase the assertions are:
#     1. NO query returns a WRONG value. Every response is either the value captured before
#        the fault, or an explicit failure (JSON-RPC error object / non-200 / transport error).
#        A 200 response carrying a different result than the pre-fault oracle is a FAILURE.
#     2. No response is malformed (200 + parseable body that is neither result nor error).
#     3. No request hangs: per-request timeouts are counted and must stay at zero, the node
#        must exit within a bounded wait, and the restart must reach readiness within a
#        bounded wait. A node that neither serves nor exits is reported as HUNG (this is a
#        real, observed failure mode -- an archive startup-validation failure inside
#        Manager.initInternal throws a plain ArchiveException, which is not a TronError, so
#        ExitManager never calls System.exit and the JVM stays alive forever on the
#        non-daemon Prometheus HTTP server thread).
#     4. After restart the same oracles replay IDENTICALLY (same result, or same error code).
#     5. No snapshot/permit leak: once the storm is over, tron:archive_state active_snapshots,
#        active_queries and pending_queries settle back to 0 and repair_required stays 0.
#
#   Explicit fail-stop is a DESIGNED behavior of the archive, so it is never confused with
#   silent divergence: a restart that fail-stops (exit 1 = TronError.ARCHIVE_RUNTIME, or
#   exit 70 = ArchiveFatalController watchdog halt) is reported as verdict FAILSTOP with the
#   observed exit code and the repair-required signal, distinct from WRONG-VALUE and from HUNG.
#   Pass --allow-failstop to accept an explicit fail-stop as a passing outcome for a phase.
#
# MACHINE-CHECKABLE OUTPUT
#   PHASE_VERDICT phase=<name> verdict=<PASS|FAIL|SKIPPED> detail=<...>   (one per phase)
#   CONCURRENCY_E2E_OK phases=<n> passed=<n> skipped=<n>                  (exit 0)
#   CONCURRENCY_E2E_FAIL phases=<n> passed=<n> failed=<n> skipped=<n>     (exit 1)
#   exit 2 = precondition/usage error (nothing was validated)
#
# REQUIREMENTS
#   bash 3.2 (macOS stock), java+javac 17, python3 (stdlib only), curl, jq.
#   framework/build/libs/FullNode.jar -- build with ./gradlew :framework:buildFullNodeJar
#   No production source is modified and nothing outside the run directory is touched.
#
# USAGE
#   ./scenario-concurrency-under-fault.sh [options]
#     --jar PATH           FullNode.jar to exercise (default: repo framework/build/libs)
#     --run-dir DIR        run artifacts (default: $TMPDIR/java-tron-archive-harness/<ts>-concurrency)
#     --port-base N        base port block, needs ~40 free ports (default 18600)
#     --workers N          storm threads (default 24)
#     --pre-fault SECS     storm warm-up before the fault (default 5)
#     --post-fault SECS    storm continuation after the fault (default 6)
#     --min-requests N     minimum storm requests per phase, guards a vacuous pass (default 100)
#     --max-timeouts N     tolerated per-request timeouts per phase (default 0)
#     --no-fork            skip the reorg phase (it needs a 2-witness topology, ~5 min)
#     --no-txs             skip transaction submission, use genesis-only oracles
#     --allow-failstop     accept an explicit archive fail-stop on restart as PASS
#     --keep-going         run every phase even after one fails (default: stop at first FAIL)
#     -h|--help            this help
#
set -uo pipefail

SCENARIO_NAME="concurrency-under-fault"
HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT_DEFAULT="$(cd "${HARNESS_DIR}/../../.." && pwd)"

# ---------------------------------------------------------------------------
# lib.sh
# ---------------------------------------------------------------------------
# lib.sh is the shared harness library (logging, node lifecycle, JSON-RPC and
# metrics helpers). It is sourced when present; every helper this scenario needs
# is then defined ONLY IF lib.sh did not already provide it, so the scenario is
# also runnable standalone and survives an independently evolving lib.sh.
if [ -f "${HARNESS_DIR}/lib.sh" ]; then
  # shellcheck source=lib.sh disable=SC1091
  . "${HARNESS_DIR}/lib.sh"
else
  printf 'WARN  %s/lib.sh not found; using built-in fallbacks\n' "${HARNESS_DIR}" >&2
fi

have_fn() { declare -F "$1" >/dev/null 2>&1; }

have_fn log  || log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
have_fn warn || warn() { printf '[%s] WARN  %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
have_fn err  || err()  { printf '[%s] ERROR %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
have_fn die  || die()  { err "$*"; exit 2; }

have_fn require_cmd || require_cmd() {
  local missing="" c
  for c in "$@"; do
    command -v "$c" >/dev/null 2>&1 || missing="${missing} ${c}"
  done
  [ -z "${missing}" ] || die "missing required command(s):${missing}"
}

# port_free PORT -> 0 when nothing is listening
have_fn port_free || port_free() {
  if (exec 3<>"/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1; then
    exec 3>&- 2>/dev/null || true
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# options
# ---------------------------------------------------------------------------
JAR=""
RUN_DIR=""
PORT_BASE=18600
WORKERS=24
PRE_FAULT_SECS=5
POST_FAULT_SECS=6
MIN_REQUESTS=100
MAX_TIMEOUTS=0
WITH_FORK=1
WITH_TXS=1
ALLOW_FAILSTOP=0
KEEP_GOING=0

usage() {
  cat <<'USAGE'
scenario-concurrency-under-fault.sh -- archive fault-injection harness, target (D)

Holds a storm of concurrent historical archive queries in flight while a fault happens
underneath them, and asserts that no query ever returns a wrong value.

  phases   setup, baseline, clean-stop (SIGTERM), sigkill (SIGKILL), reorg (switchFork)
  markers  PHASE_VERDICT phase=<n> verdict=<PASS|FAIL|SKIPPED> detail=<...>
           CONCURRENCY_E2E_OK / CONCURRENCY_E2E_FAIL
  exits    0 pass, 1 fail, 2 precondition/usage error

Options:
  --jar PATH          FullNode.jar to exercise (default: repo framework/build/libs)
  --run-dir DIR       run artifacts (default: $TMPDIR/java-tron-archive-harness/<ts>-...)
  --port-base N       base port block, needs ~40 free ports (default 18600)
  --workers N         storm threads (default 24)
  --pre-fault SECS    storm warm-up before the fault (default 5)
  --post-fault SECS   storm continuation after the fault (default 6)
  --min-requests N    minimum storm requests per phase, guards a vacuous pass (default 100)
  --max-timeouts N    tolerated per-request timeouts per phase (default 0)
  --no-fork           skip the reorg phase (needs a 2-witness topology, ~5 min)
  --no-txs            skip transaction submission, use genesis-only oracles
  --allow-failstop    accept an explicit archive fail-stop on restart as PASS
  --keep-going        run every phase even after one fails
  -h, --help          this help

Read the banner comment at the top of this file for the full assertion contract.
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --jar) JAR="${2:-}"; shift 2 ;;
    --run-dir) RUN_DIR="${2:-}"; shift 2 ;;
    --port-base) PORT_BASE="${2:-}"; shift 2 ;;
    --workers) WORKERS="${2:-}"; shift 2 ;;
    --pre-fault) PRE_FAULT_SECS="${2:-}"; shift 2 ;;
    --post-fault) POST_FAULT_SECS="${2:-}"; shift 2 ;;
    --min-requests) MIN_REQUESTS="${2:-}"; shift 2 ;;
    --max-timeouts) MAX_TIMEOUTS="${2:-}"; shift 2 ;;
    --no-fork) WITH_FORK=0; shift ;;
    --no-txs) WITH_TXS=0; shift ;;
    --allow-failstop) ALLOW_FAILSTOP=1; shift ;;
    --keep-going) KEEP_GOING=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
done

# ---------------------------------------------------------------------------
# tunables that are not worth a flag
# ---------------------------------------------------------------------------
READY_TIMEOUT_SECS=180        # first boot / restart readiness
EXIT_TIMEOUT_SECS=150         # bounded wait for the JVM to leave after SIGTERM
STORM_MAX_SECS=180            # safety net; the storm is normally stopped by its stop-file
STORM_REQ_TIMEOUT_SECS=20     # per-request socket timeout; > archive deadlineMs below
ARCHIVE_DEADLINE_MS=5000      # keep well under STORM_REQ_TIMEOUT_SECS so a timeout is unambiguous
METRIC_SETTLE_SECS=45         # allowance for the async metrics dispatcher to publish zeros
PUBLISH_WAIT_SECS=240         # wait for the archive publisher to cover an oracle height
CHAIN_WARMUP_BLOCKS=6

# fork-phase timings, from the verified 2-witness recipe
FORK_PARTITION_SECS=35
FORK_FREEZE_SECS=75
FORK_SWITCH_TIMEOUT_SECS=210

# deterministic private-chain keys (address derivation verified against ECKey/StringUtil)
W1_KEY=1234567890123456789012345678901234567890123456789012345678901234
W1_B58=TEDapYSVvAZ3aYH7w8N9tMEEFKaNKUD5Bp
W2_KEY=5555555555555555555555555555555555555555555555555555555555555555
W2_B58=TWa5cxQFesyCQUm17usvHrVkKce6rMCV4H
ZION_KEY=1111111111111111111111111111111111111111111111111111111111111111
ZION_B58=TCLBgkbfVkJroVBJVqBEsxtPNQEQMTQCLQ
ZION_ETH=0x19e7e376e7c213b7e7e7e46cc70a5dd086daff2a
SUN_B58=TBvJUBXorwBPzqvV38vjDgegj5Eh6g2Tsq
SUN_ETH=0x1563915e194d8cfba1943570603f7606a3115508
BLACKHOLE_B58=TDvSsdrNM5eeXNL3czpa6AxLDHZA9nwe9K

# ---------------------------------------------------------------------------
# phase bookkeeping (bash 3.2: parallel indexed arrays, no associative arrays)
# ---------------------------------------------------------------------------
PHASE_NAMES=""
PHASE_VERDICTS=""
PHASE_DETAILS=""
PHASE_COUNT=0
PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0

record_phase() { # name verdict detail
  PHASE_NAMES="${PHASE_NAMES}$1"$'\n'
  PHASE_VERDICTS="${PHASE_VERDICTS}$2"$'\n'
  PHASE_DETAILS="${PHASE_DETAILS}$3"$'\n'
  PHASE_COUNT=$((PHASE_COUNT + 1))
  case "$2" in
    PASS) PASS_COUNT=$((PASS_COUNT + 1)) ;;
    SKIPPED) SKIP_COUNT=$((SKIP_COUNT + 1)) ;;
    *) FAIL_COUNT=$((FAIL_COUNT + 1)) ;;
  esac
  printf 'PHASE_VERDICT phase=%s verdict=%s detail=%s\n' "$1" "$2" "$3"
}

# phase_failed_stop -- honour fail-fast unless --keep-going
phase_should_continue() { [ "${KEEP_GOING}" -eq 1 ] || [ "${FAIL_COUNT}" -eq 0 ]; }

# ---------------------------------------------------------------------------
# process tracking + cleanup
# ---------------------------------------------------------------------------
TRACKED_PIDS=""
track_pid() { TRACKED_PIDS="${TRACKED_PIDS} $1"; }

kill_quiet() { # pid [signal]
  local pid="$1" sig="${2:-TERM}"
  [ -n "${pid}" ] || return 0
  kill -0 "${pid}" 2>/dev/null || return 0
  kill "-${sig}" "${pid}" 2>/dev/null || true
}

cleanup() {
  local pid
  for pid in ${TRACKED_PIDS}; do
    kill_quiet "${pid}" CONT
    kill_quiet "${pid}" TERM
  done
  sleep 1
  for pid in ${TRACKED_PIDS}; do
    kill_quiet "${pid}" KILL
  done
}
trap cleanup EXIT
trap 'err "interrupted"; exit 1' INT TERM

# ---------------------------------------------------------------------------
# preflight
# ---------------------------------------------------------------------------
require_cmd java curl jq python3
[ "${WITH_TXS}" -eq 1 ] && ! command -v javac >/dev/null 2>&1 && {
  warn "javac not found; falling back to genesis-only oracles (--no-txs)"
  WITH_TXS=0
}

if [ -z "${JAR}" ]; then
  JAR="${FULLNODE_JAR:-${REPO_ROOT_DEFAULT}/framework/build/libs/FullNode.jar}"
fi
[ -f "${JAR}" ] || die "FullNode.jar not found at ${JAR} (build: ./gradlew :framework:buildFullNodeJar)"
JAR="$(cd "$(dirname "${JAR}")" && pwd)/$(basename "${JAR}")"

if [ -z "${RUN_DIR}" ]; then
  # HARNESS_RUN_ROOT is how run-all.sh points every scenario at one artifact tree.
  if [ -n "${HARNESS_RUN_ROOT:-}" ]; then
    RUN_DIR="${HARNESS_RUN_ROOT}/${SCENARIO_NAME}"
  else
    RUN_DIR="${TMPDIR:-/tmp}/java-tron-archive-harness/$(date +%Y%m%d-%H%M%S)-${SCENARIO_NAME}"
  fi
fi
mkdir -p "${RUN_DIR}" || die "cannot create run dir ${RUN_DIR}"
RUN_DIR="$(cd "${RUN_DIR}" && pwd)"

# port map -- every phase gets its own block so a leftover process from another
# scenario cannot be mistaken for this node.
P_HTTP=$((PORT_BASE + 0));  P_RPC=$((PORT_BASE + 1));  P_JSONRPC=$((PORT_BASE + 2))
P_METRICS=$((PORT_BASE + 3)); P_P2P=$((PORT_BASE + 4))
A_HTTP=$((PORT_BASE + 10)); A_RPC=$((PORT_BASE + 11)); A_JSONRPC=$((PORT_BASE + 12))
A_METRICS=$((PORT_BASE + 13)); A_P2P=$((PORT_BASE + 14))
B_HTTP=$((PORT_BASE + 20)); B_RPC=$((PORT_BASE + 21)); B_JSONRPC=$((PORT_BASE + 22))
B_METRICS=$((PORT_BASE + 23)); B_P2P=$((PORT_BASE + 24))
RELAY_PORT=$((PORT_BASE + 30))

check_ports() {
  local p busy=""
  for p in "$@"; do
    port_free "${p}" || busy="${busy} ${p}"
  done
  [ -z "${busy}" ] || die "port(s) already in use:${busy} (use --port-base)"
}
check_ports "${P_HTTP}" "${P_RPC}" "${P_JSONRPC}" "${P_METRICS}" "${P_P2P}"
[ "${WITH_FORK}" -eq 1 ] && check_ports "${A_HTTP}" "${A_JSONRPC}" "${A_METRICS}" "${A_P2P}" \
  "${B_HTTP}" "${B_JSONRPC}" "${B_METRICS}" "${B_P2P}" "${RELAY_PORT}"

log "scenario   : ${SCENARIO_NAME}"
log "jar        : ${JAR}"
log "run dir    : ${RUN_DIR}"
log "port base  : ${PORT_BASE}"
log "storm      : ${WORKERS} workers, pre=${PRE_FAULT_SECS}s post=${POST_FAULT_SECS}s"

# ---------------------------------------------------------------------------
# embedded assets
# ---------------------------------------------------------------------------
ASSET_DIR="${RUN_DIR}/assets"
mkdir -p "${ASSET_DIR}"
STORM_PY="${ASSET_DIR}/archive_query_driver.py"
RELAY_PY="${ASSET_DIR}/tcp_relay.py"

write_assets() {
  cat >"${STORM_PY}" <<'PYEOF'
#!/usr/bin/env python3
"""Archive historical-query driver: capture | replay | storm.

Written by scenario-concurrency-under-fault.sh into the run directory. Stdlib only.

capture  issue each spec once, record the observed answer as the oracle
replay   issue each oracle once, require an identical answer
storm    hammer the oracles from N threads until a stop-file appears, classifying
         every response as ok / wrong / unexpected_result / rpc_error / http_error /
         transport / timeout / malformed
"""

import argparse
import http.client
import json
import os
import random
import socket
import sys
import threading
import time

RESULT = "result"
RPC_ERROR = "rpc_error"
HTTP_ERROR = "http_error"
TRANSPORT = "transport"
TIMEOUT = "timeout"
MALFORMED = "malformed"

# Transient, explicitly-signalled rejections. Never a correctness violation, but a
# capture/replay probe retries past them so an oracle is never pinned to one.
TRANSIENT_RPC_CODES = (-32005,)


class Endpoint(object):
    """One keep-alive JSON-RPC connection that reconnects after any failure."""

    def __init__(self, host, port, timeout, path="/jsonrpc"):
        self.host = host
        self.port = port
        self.timeout = timeout
        self.path = path
        self.conn = None

    def _connect(self):
        reused = self.conn is not None
        if self.conn is None:
            self.conn = http.client.HTTPConnection(
                self.host, self.port, timeout=self.timeout)
        return self.conn, reused

    def drop(self):
        if self.conn is not None:
            try:
                self.conn.close()
            except Exception:
                pass
            self.conn = None

    def call(self, method, params, rid):
        """-> (kind, payload, note, elapsed_seconds)."""
        body = json.dumps(
            {"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        headers = {"Content-Type": "application/json", "Connection": "keep-alive"}
        started = time.time()
        attempt = 0
        reused = False
        while True:
            try:
                conn, reused = self._connect()
                conn.request("POST", self.path, body, headers)
                resp = conn.getresponse()
                raw = resp.read()
                status = resp.status
                break
            except socket.timeout:
                self.drop()
                return (TIMEOUT, None, "socket timeout", time.time() - started)
            except Exception as exc:
                # refused / reset / broken pipe / incomplete read / bad status line: all
                # transport-level and all explicitly observable, never a wrong value.
                self.drop()
                # A failure on a REUSED keep-alive socket is normally just the server
                # having closed an idle connection, which every HTTP client retries once
                # on a fresh socket. Counting that as a fault would make the no-fault
                # baseline flaky; a genuinely gone node fails the fresh attempt too.
                if reused and attempt == 0:
                    attempt += 1
                    continue
                return (TRANSPORT, None, type(exc).__name__, time.time() - started)
        elapsed = time.time() - started
        if status != 200:
            return (HTTP_ERROR, None, "http %d" % status, elapsed)
        try:
            obj = json.loads(raw.decode("utf-8", "replace"))
        except Exception:
            return (MALFORMED, None, "unparseable body", elapsed)
        if not isinstance(obj, dict):
            return (MALFORMED, None, "non-object body", elapsed)
        error = obj.get("error")
        if error is not None:
            code = error.get("code") if isinstance(error, dict) else None
            message = error.get("message") if isinstance(error, dict) else str(error)
            return (RPC_ERROR, {"code": code, "message": message}, str(code), elapsed)
        if "result" in obj:
            return (RESULT, obj["result"], None, elapsed)
        return (MALFORMED, None, "neither result nor error", elapsed)


def normalize(value):
    if isinstance(value, str):
        return value.strip().lower()
    return json.dumps(value, sort_keys=True)


def load_json(path):
    with open(path, "r") as handle:
        return json.load(handle)


def dump_json(path, obj):
    with open(path, "w") as handle:
        json.dump(obj, handle, indent=2, sort_keys=True)
        handle.write("\n")


def probe(endpoint, entry, retries, delay):
    """Issue one request, retrying past transient/transport answers."""
    attempt = 0
    while True:
        kind, payload, note, _ = endpoint.call(
            entry["method"], entry["params"], entry.get("id", "probe"))
        transient = kind in (TRANSPORT, TIMEOUT) or (
            kind == RPC_ERROR and payload.get("code") in TRANSIENT_RPC_CODES)
        if not transient or attempt >= retries:
            return kind, payload, note
        attempt += 1
        time.sleep(delay)


def cmd_capture(args):
    endpoint = Endpoint(args.host, args.port, args.timeout)
    specs = load_json(args.spec)["specs"]
    oracles = []
    failures = []
    for entry in specs:
        kind, payload, note = probe(endpoint, entry, args.retries, args.retry_delay)
        if kind == RESULT:
            oracles.append({"id": entry["id"], "method": entry["method"],
                            "params": entry["params"],
                            "expect": {"kind": "result", "value": payload}})
            print("CAPTURE %-28s result %s" % (entry["id"], normalize(payload)[:80]))
        elif kind == RPC_ERROR:
            oracles.append({"id": entry["id"], "method": entry["method"],
                            "params": entry["params"],
                            "expect": {"kind": "error", "code": payload.get("code"),
                                       "message": payload.get("message")}})
            print("CAPTURE %-28s error  %s %s"
                  % (entry["id"], payload.get("code"), payload.get("message")))
        else:
            failures.append("%s -> %s (%s)" % (entry["id"], kind, note))
    if failures:
        for line in failures:
            print("CAPTURE_FAILED %s" % line)
        print("ORACLE_CAPTURE_FAIL captured=%d failed=%d" % (len(oracles), len(failures)))
        return 1
    dump_json(args.out, {"oracles": oracles})
    distinct = len(set(normalize(o["expect"].get("value"))
                       for o in oracles if o["expect"]["kind"] == "result"))
    print("ORACLE_CAPTURE_OK count=%d distinct_results=%d" % (len(oracles), distinct))
    return 0


def cmd_replay(args):
    endpoint = Endpoint(args.host, args.port, args.timeout)
    oracles = load_json(args.oracles)["oracles"]
    mismatches = []
    unavailable = []
    for oracle in oracles:
        kind, payload, note = probe(endpoint, oracle, args.retries, args.retry_delay)
        expect = oracle["expect"]
        if expect["kind"] == "result":
            if kind == RESULT and normalize(payload) == normalize(expect["value"]):
                continue
            if kind == RESULT:
                mismatches.append("%s expected result %s got result %s"
                                  % (oracle["id"], normalize(expect["value"]),
                                     normalize(payload)))
            elif kind == RPC_ERROR:
                mismatches.append("%s expected result %s got error %s %s"
                                  % (oracle["id"], normalize(expect["value"]),
                                     payload.get("code"), payload.get("message")))
            else:
                unavailable.append("%s -> %s (%s)" % (oracle["id"], kind, note))
        else:
            if kind == RPC_ERROR and payload.get("code") == expect.get("code"):
                continue
            if kind == RESULT:
                mismatches.append("%s expected error %s got result %s"
                                  % (oracle["id"], expect.get("code"), normalize(payload)))
            elif kind == RPC_ERROR:
                mismatches.append("%s expected error %s got error %s %s"
                                  % (oracle["id"], expect.get("code"),
                                     payload.get("code"), payload.get("message")))
            else:
                unavailable.append("%s -> %s (%s)" % (oracle["id"], kind, note))
    for line in mismatches:
        print("ORACLE_MISMATCH %s" % line)
    for line in unavailable:
        print("ORACLE_UNAVAILABLE %s" % line)
    if mismatches or unavailable:
        print("ORACLE_REPLAY_FAIL count=%d mismatched=%d unavailable=%d"
              % (len(oracles), len(mismatches), len(unavailable)))
        return 1
    print("ORACLE_REPLAY_OK count=%d" % len(oracles))
    return 0


class Tally(object):
    def __init__(self):
        self.lock = threading.Lock()
        self.counts = {"ok": 0, "wrong": 0, "unexpected_result": 0, "rpc_error": 0,
                       "http_error": 0, "transport": 0, "timeout": 0, "malformed": 0}
        self.by_code = {}
        self.samples = []
        self.max_latency_ms = 0.0

    def add(self, bucket, note, elapsed, sample=None):
        with self.lock:
            self.counts[bucket] = self.counts.get(bucket, 0) + 1
            if bucket in ("rpc_error", "http_error", "transport", "timeout", "malformed"):
                key = str(note)
                self.by_code[key] = self.by_code.get(key, 0) + 1
            latency_ms = elapsed * 1000.0
            if latency_ms > self.max_latency_ms:
                self.max_latency_ms = latency_ms
            if sample is not None and len(self.samples) < 32:
                self.samples.append(sample)


def cmd_storm(args):
    oracles = load_json(args.oracles)["oracles"]
    if not oracles:
        print("STORM_FAIL no oracles")
        return 1
    tally = Tally()
    deadline = time.time() + args.duration
    ready_flag = threading.Event()
    started_at = time.time()

    def stop_requested():
        if args.stop_file and os.path.exists(args.stop_file):
            return True
        return time.time() >= deadline

    def worker(seed):
        rng = random.Random(seed)
        endpoint = Endpoint(args.host, args.port, args.timeout)
        counter = 0
        while not stop_requested():
            oracle = oracles[rng.randrange(len(oracles))]
            counter += 1
            kind, payload, note, elapsed = endpoint.call(
                oracle["method"], oracle["params"], "%d-%d" % (seed, counter))
            expect = oracle["expect"]
            if kind == RESULT:
                ready_flag.set()
                if expect["kind"] == "result":
                    if normalize(payload) == normalize(expect["value"]):
                        tally.add("ok", None, elapsed)
                    else:
                        tally.add("wrong", None, elapsed, {
                            "id": oracle["id"], "expected": expect["value"],
                            "observed": payload})
                else:
                    # Baseline said this query fails explicitly; a value now is an anomaly.
                    tally.add("unexpected_result", None, elapsed, {
                        "id": oracle["id"], "expected_error": expect.get("code"),
                        "observed": payload})
            elif kind == RPC_ERROR:
                ready_flag.set()
                tally.add("rpc_error", payload.get("code"), elapsed)
            elif kind == HTTP_ERROR:
                tally.add("http_error", note, elapsed)
            elif kind == TRANSPORT:
                tally.add("transport", note, elapsed)
            elif kind == TIMEOUT:
                tally.add("timeout", note, elapsed)
            else:
                tally.add("malformed", note, elapsed, {
                    "id": oracle["id"], "note": note})
        endpoint.drop()

    threads = []
    for index in range(args.workers):
        thread = threading.Thread(target=worker, args=(index + 1,), daemon=True)
        thread.start()
        threads.append(thread)

    if args.ready_file:
        if ready_flag.wait(timeout=args.ready_timeout):
            open(args.ready_file, "w").close()
        else:
            print("STORM_NEVER_READY after %ss" % args.ready_timeout)

    for thread in threads:
        thread.join(timeout=args.timeout + 30)

    summary = dict(tally.counts)
    summary["total"] = sum(tally.counts.values())
    summary["by_code"] = tally.by_code
    summary["samples"] = tally.samples
    summary["max_latency_ms"] = round(tally.max_latency_ms, 1)
    summary["elapsed_s"] = round(time.time() - started_at, 1)
    summary["workers"] = args.workers
    summary["oracles"] = len(oracles)
    dump_json(args.out, summary)
    print("STORM_DONE total=%d ok=%d wrong=%d unexpected_result=%d rpc_error=%d "
          "http_error=%d transport=%d timeout=%d malformed=%d"
          % (summary["total"], summary["ok"], summary["wrong"],
             summary["unexpected_result"], summary["rpc_error"], summary["http_error"],
             summary["transport"], summary["timeout"], summary["malformed"]))
    return 0


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("capture", "replay", "storm"))
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--spec")
    parser.add_argument("--oracles")
    parser.add_argument("--out")
    parser.add_argument("--retries", type=int, default=5)
    parser.add_argument("--retry-delay", type=float, default=1.0)
    parser.add_argument("--workers", type=int, default=16)
    parser.add_argument("--duration", type=float, default=120.0)
    parser.add_argument("--stop-file")
    parser.add_argument("--ready-file")
    parser.add_argument("--ready-timeout", type=float, default=30.0)
    args = parser.parse_args(argv)

    if args.mode == "capture":
        if not (args.spec and args.out):
            parser.error("capture needs --spec and --out")
        return cmd_capture(args)
    if args.mode == "replay":
        if not args.oracles:
            parser.error("replay needs --oracles")
        return cmd_replay(args)
    if not (args.oracles and args.out):
        parser.error("storm needs --oracles and --out")
    return cmd_storm(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
PYEOF

  cat >"${RELAY_PY}" <<'PYEOF'
#!/usr/bin/env python3
"""Killable TCP relay: partition two p2p peers by killing this process."""
import socket
import sys
import threading

listen_port, remote_host, remote_port = int(sys.argv[1]), sys.argv[2], int(sys.argv[3])


def pipe(source, sink):
    try:
        while True:
            chunk = source.recv(65536)
            if not chunk:
                break
            sink.sendall(chunk)
    except Exception:
        pass
    finally:
        for sock in (source, sink):
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
            try:
                sock.close()
            except Exception:
                pass


server = socket.socket()
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(("127.0.0.1", listen_port))
server.listen(16)
print("relay %d -> %s:%d" % (listen_port, remote_host, remote_port), flush=True)
while True:
    downstream, _ = server.accept()
    try:
        upstream = socket.create_connection((remote_host, remote_port))
    except Exception:
        downstream.close()
        continue
    threading.Thread(target=pipe, args=(downstream, upstream), daemon=True).start()
    threading.Thread(target=pipe, args=(upstream, downstream), daemon=True).start()
PYEOF

  if [ "${WITH_TXS}" -eq 1 ]; then
    mkdir -p "${ASSET_DIR}/java"
    cat >"${ASSET_DIR}/java/Sign.java" <<'JAVAEOF'
import org.tron.common.crypto.SignInterface;
import org.tron.common.crypto.SignUtils;
import org.tron.common.utils.ByteArray;

/** Client-side transaction signing: there is no gettransactionsign servlet. */
public class Sign {
  public static void main(String[] args) {
    SignInterface key = SignUtils.fromPrivate(ByteArray.fromHexString(args[0]), true);
    byte[] signature = key.Base64toBytes(key.signHash(ByteArray.fromHexString(args[1])));
    System.out.println(ByteArray.toHexString(signature));
  }
}
JAVAEOF
    if ! javac -nowarn -cp "${JAR}" -d "${ASSET_DIR}/classes" \
        "${ASSET_DIR}/java/Sign.java" >"${ASSET_DIR}/javac.log" 2>&1; then
      warn "javac failed (see ${ASSET_DIR}/javac.log); falling back to genesis-only oracles"
      WITH_TXS=0
    fi
  fi
}

# ---------------------------------------------------------------------------
# node configuration
# ---------------------------------------------------------------------------
# Set CONF_* then call write_node_conf FILE. Only whitelisted storage.archive keys are
# emitted -- StorageConfig.validateArchiveConfigKeys rejects anything else at startup.
write_node_conf() {
  local file="$1"
  mkdir -p "$(dirname "${file}")" || die "cannot create $(dirname "${file}")"
  cat >"${file}" <<EOF
# generated by ${SCENARIO_NAME} -- do not edit, regenerate instead
net { }

storage {
  db.engine = "LEVELDB"
  db.directory = "database"
  index.directory = "index"
  transHistory.switch = "on"

  archive {
    enable = true
    db { directory = "archive", fullScrubOnStartup = false }
    identity { initialize = ${CONF_IDENTITY_INIT} }
    txnum { enable = true }
    temporal { enable = true }
    publisher {
      async = true
      backpressure = true
      softMinFreeBytes = 33554432
      hardMinFreeBytes = 16777216
    }
    query {
      jsonRpcWorkerThreads = 2
      maxConcurrentQueries = 8
      maxPendingQueries = 16
      maxOpenSnapshots = 8
      acquireTimeoutMs = 0
      deadlineMs = ${ARCHIVE_DEADLINE_MS}
    }
    debug { enable = true, maxConcurrentTraces = 1, maxPendingTraces = 1 }
  }
}

node.discovery = { enable = false, persist = false, external.ip = "127.0.0.1" }

node {
  listen.port = ${CONF_P2P_PORT}
  minParticipationRate = 0
  maxConnectionsWithSameIp = 10
  p2p { version = 20260728 }
  active = [ ${CONF_ACTIVE} ]
  passive = []
  http { fullNodeEnable = true, fullNodePort = ${CONF_HTTP_PORT}, solidityEnable = false, PBFTEnable = false }
  rpc { enable = true, port = ${CONF_RPC_PORT}, minEffectiveConnection = 0, solidityEnable = false, PBFTEnable = false }
  jsonrpc { httpFullNodeEnable = true, httpFullNodePort = ${CONF_JSONRPC_PORT}, httpSolidityEnable = false, httpPBFTEnable = false }
}

node.metrics = { prometheus { enable = true, port = ${CONF_METRICS_PORT} } }

seed.node = { ip.list = [] }

genesis.block = {
  assets = [
    { accountName = "Zion", accountType = "AssetIssue", address = "${ZION_B58}", balance = "90000000000000000" },
    { accountName = "Sun", accountType = "AssetIssue", address = "${SUN_B58}", balance = "10000000000000000" },
    { accountName = "Blackhole", accountType = "AssetIssue", address = "${BLACKHOLE_B58}", balance = "-9223372036854775808" }
  ]
  witnesses = [
${CONF_GENESIS_WITNESSES}
  ]
  timestamp = "0"
  parentHash = "0x0000000000000000000000000000000000000000000000000000000000000000"
}

localwitness = [
  ${CONF_LOCAL_WITNESS}
]

block = { needSyncCheck = false, maintenanceTimeInterval = 21600000, proposalExpireTime = 259200000 }

vm = { supportConstant = true, minTimeRatio = 0.0, maxTimeRatio = 5.0, saveInternalTx = true }

committee = { allowCreationOfContracts = 1, allowAdaptiveEnergy = 0 }

event.subscribe = { enable = false }
EOF
  [ -s "${file}" ] || die "failed to write ${file}"
}

single_witness_block() {
  printf '    { address: %s, url = "http://sr1.local", voteCount = 100 }' "${W1_B58}"
}

dual_witness_block() {
  printf '    { address: %s, url = "http://sr1.local", voteCount = 100 },\n' "${W1_B58}"
  printf '    { address: %s, url = "http://sr2.local", voteCount = 99 }' "${W2_B58}"
}

# ---------------------------------------------------------------------------
# node lifecycle
# ---------------------------------------------------------------------------
# node_start DIR CONF -> sets LAST_NODE_PID
#
# The node MUST be launched from the top-level shell, never from a command substitution:
# a background job started inside $( ) belongs to the subshell, and `wait` in the parent
# then returns 127 instead of the real exit status, which would silently corrupt every
# fail-stop verdict below. Same reason node_outcome/node_await_exit publish globals.
LAST_NODE_PID=""
node_start() {
  local dir="$1" conf="$2"
  mkdir -p "${dir}"
  ( cd "${dir}" && exec java -Xms1g -Xmx2g -jar "${JAR}" \
      -c "${conf}" -d "${dir}/data" --witness \
      >>"${dir}/node.out" 2>>"${dir}/node.err" ) &
  LAST_NODE_PID=$!
  track_pid "${LAST_NODE_PID}"
}

http_ready() { # http_port
  curl -fsS -m 5 -XPOST "http://127.0.0.1:$1/wallet/getnowblock" 2>/dev/null \
    | jq -e '.blockID? // empty' >/dev/null 2>&1
}

archive_error_in_log() { # dir
  grep -qE 'ArchiveException|archive fatal watchdog|Shutting down with code: ARCHIVE_RUNTIME|repair-required' \
    "$1/logs/tron.log" "$1/node.err" 2>/dev/null
}

# node_outcome PID DIR HTTP_PORT TIMEOUT -> sets NODE_OUTCOME to READY|EXITED:<code>|HUNG
#
# Three outcomes on purpose: a node that neither serves HTTP nor exits is a real observed
# archive failure mode (a plain ArchiveException from Manager.initInternal is not a
# TronError, so ExitManager never calls System.exit and the non-daemon Prometheus HTTP
# server thread keeps the JVM alive forever). Judging fail-stop by exit code alone would
# score that hang as "still starting".
NODE_OUTCOME=""
node_outcome() {
  local pid="$1" dir="$2" port="$3" timeout="$4" waited=0 code
  while [ "${waited}" -lt "${timeout}" ]; do
    if http_ready "${port}"; then
      NODE_OUTCOME="READY"
      return 0
    fi
    if ! kill -0 "${pid}" 2>/dev/null; then
      wait "${pid}" 2>/dev/null
      code=$?
      NODE_OUTCOME="EXITED:${code}"
      return 0
    fi
    sleep 2
    waited=$((waited + 2))
  done
  NODE_OUTCOME="HUNG"
  return 0
}

# node_await_exit PID TIMEOUT -> sets NODE_EXIT_CODE (254 when it never left)
NODE_EXIT_CODE=""
node_await_exit() {
  local pid="$1" timeout="$2" waited=0 code
  while [ "${waited}" -lt "${timeout}" ]; do
    if ! kill -0 "${pid}" 2>/dev/null; then
      wait "${pid}" 2>/dev/null
      code=$?
      NODE_EXIT_CODE="${code}"
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  NODE_EXIT_CODE=254
  return 0
}

head_num() { # http_port -> block number, 0 when unreachable
  local value
  value="$(curl -fsS -m 5 -XPOST "http://127.0.0.1:$1/wallet/getnowblock" 2>/dev/null \
    | jq -r '.block_header.raw_data.number // 0' 2>/dev/null)"
  case "${value}" in
    ''|*[!0-9]*) printf '0' ;;
    *) printf '%s' "${value}" ;;
  esac
}

wait_for_height() { # http_port target timeout
  local port="$1" target="$2" timeout="$3" waited=0 now
  while [ "${waited}" -lt "${timeout}" ]; do
    now="$(head_num "${port}")"
    if [ "${now}" -ge "${target}" ]; then
      return 0
    fi
    sleep 2
    waited=$((waited + 2))
  done
  return 1
}

metric_state() { # metrics_port type
  curl -fsS -m 5 "http://127.0.0.1:$1/metrics" 2>/dev/null \
    | grep -F "tron:archive_state{type=\"$2\"" | tail -1 | awk '{print $NF}'
}

# wait_metric_zero METRICS_PORT TYPE TIMEOUT
wait_metric_zero() {
  local port="$1" type="$2" timeout="$3" waited=0 value
  while [ "${waited}" -lt "${timeout}" ]; do
    value="$(metric_state "${port}" "${type}")"
    case "${value}" in
      0|0.0|-0.0) return 0 ;;
    esac
    sleep 2
    waited=$((waited + 2))
  done
  return 1
}

# assert_no_leak METRICS_PORT LABEL -> 0 clean, 1 leaked; prints its own findings
assert_no_leak() {
  local port="$1" label="$2" bad="" type value
  for type in active_snapshots active_queries pending_queries; do
    if ! wait_metric_zero "${port}" "${type}" "${METRIC_SETTLE_SECS}"; then
      value="$(metric_state "${port}" "${type}")"
      bad="${bad} ${type}=${value:-unreadable}"
    fi
  done
  value="$(metric_state "${port}" repair_required)"
  case "${value}" in
    0|0.0) : ;;
    *) bad="${bad} repair_required=${value:-unreadable}" ;;
  esac
  if [ -n "${bad}" ]; then
    err "${label}: archive state did not settle:${bad}"
    return 1
  fi
  log "${label}: active_snapshots/active_queries/pending_queries settled to 0, repair_required 0"
  return 0
}

# ---------------------------------------------------------------------------
# transactions
# ---------------------------------------------------------------------------
# submit_transfer HTTP_PORT AMOUNT -> 0 on success, publishing LAST_TX_BLOCK and LAST_TXID.
# Globals rather than stdout: the caller must not run this in a command substitution, which
# would discard the txid needed for the debug_trace oracle.
LAST_TXID=""
LAST_TX_BLOCK=""
submit_transfer() {
  local port="$1" amount="$2" tx txid sig signed result waited=0 block
  LAST_TXID=""
  LAST_TX_BLOCK=""
  tx="$(curl -fsS -m 10 -XPOST -H 'Content-Type: application/json' \
      --data "{\"owner_address\":\"${ZION_B58}\",\"to_address\":\"${SUN_B58}\",\"amount\":${amount},\"visible\":true}" \
      "http://127.0.0.1:${port}/wallet/createtransaction" 2>/dev/null)" || return 1
  txid="$(printf '%s' "${tx}" | jq -r '.txID // empty')"
  [ -n "${txid}" ] || return 1
  sig="$(java -cp "${ASSET_DIR}/classes:${JAR}" Sign "${ZION_KEY}" "${txid}" 2>/dev/null)" || return 1
  [ -n "${sig}" ] || return 1
  signed="$(printf '%s' "${tx}" | jq -c --arg s "${sig}" '. + {signature: [$s]}')"
  result="$(curl -fsS -m 10 -XPOST -H 'Content-Type: application/json' --data "${signed}" \
      "http://127.0.0.1:${port}/wallet/broadcasttransaction" 2>/dev/null)" || return 1
  printf '%s' "${result}" | jq -e '.result == true' >/dev/null 2>&1 || {
    warn "broadcast rejected: $(printf '%s' "${result}" | head -c 200)"
    return 1
  }
  while [ "${waited}" -lt 60 ]; do
    block="$(curl -fsS -m 10 -XPOST -H 'Content-Type: application/json' \
        --data "{\"value\":\"${txid}\"}" \
        "http://127.0.0.1:${port}/wallet/gettransactioninfobyid" 2>/dev/null \
        | jq -r '.blockNumber // empty')"
    if [ -n "${block}" ]; then
      LAST_TX_BLOCK="${block}"
      LAST_TXID="${txid}"
      return 0
    fi
    sleep 2
    waited=$((waited + 2))
  done
  return 1
}

# ---------------------------------------------------------------------------
# oracles
# ---------------------------------------------------------------------------
SPEC_ENTRIES=""
spec_reset() { SPEC_ENTRIES=""; }
spec_add() { # id method params_json
  local entry
  entry="$(printf '{"id":"%s","method":"%s","params":%s}' "$1" "$2" "$3")"
  if [ -z "${SPEC_ENTRIES}" ]; then
    SPEC_ENTRIES="${entry}"
  else
    SPEC_ENTRIES="${SPEC_ENTRIES},${entry}"
  fi
}
spec_write() { printf '{"specs":[%s]}\n' "${SPEC_ENTRIES}" >"$1"; }

# build_spec_for_heights FILE HEIGHT... -- one balance/storage/call probe per height.
# TRACE_TXID, when set, adds a debug_traceTransaction probe. That transaction is a plain
# transfer, so the trace is rejected deterministically; the oracle is therefore an
# error-signature oracle whose value is exercising the SEPARATE trace admission permits
# (storage.archive.debug.maxConcurrentTraces) under the storm.
TRACE_TXID=""
build_spec_for_heights() {
  local file="$1" height hex
  shift
  spec_reset
  for height in "$@"; do
    hex="$(printf '0x%x' "${height}")"
    spec_add "balance.zion@${height}" eth_getBalance "[\"${ZION_ETH}\",\"${hex}\"]"
    spec_add "balance.sun@${height}" eth_getBalance "[\"${SUN_ETH}\",\"${hex}\"]"
    spec_add "storage.sun@${height}" eth_getStorageAt "[\"${SUN_ETH}\",\"0x0\",\"${hex}\"]"
    spec_add "call.sun@${height}" eth_call \
      "[{\"from\":\"${ZION_ETH}\",\"to\":\"${SUN_ETH}\",\"data\":\"0x\"},\"${hex}\"]"
  done
  if [ -n "${TRACE_TXID}" ]; then
    spec_add "trace.transfer" debug_traceTransaction "[\"0x${TRACE_TXID}\"]"
  fi
  spec_write "${file}"
}

# wait_archive_covers HTTP_JSONRPC_PORT HEIGHT TIMEOUT
# The publisher lags the head; poll until a historical read at HEIGHT stops answering
# -32000 "archive history unavailable".
wait_archive_covers() {
  local port="$1" height="$2" timeout="$3" waited=0 hex response
  hex="$(printf '0x%x' "${height}")"
  while [ "${waited}" -lt "${timeout}" ]; do
    response="$(curl -fsS -m 10 -XPOST -H 'Content-Type: application/json' \
        --data "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_getBalance\",\"params\":[\"${ZION_ETH}\",\"${hex}\"]}" \
        "http://127.0.0.1:${port}/jsonrpc" 2>/dev/null)"
    if printf '%s' "${response}" | jq -e 'has("result")' >/dev/null 2>&1; then
      return 0
    fi
    sleep 3
    waited=$((waited + 3))
  done
  return 1
}

capture_oracles() { # jsonrpc_port spec_file oracles_file log_file
  python3 "${STORM_PY}" capture --port "$1" --spec "$2" --out "$3" \
    --timeout "${STORM_REQ_TIMEOUT_SECS}" >"$4" 2>&1
}

replay_oracles() { # jsonrpc_port oracles_file log_file
  python3 "${STORM_PY}" replay --port "$1" --oracles "$2" \
    --timeout "${STORM_REQ_TIMEOUT_SECS}" >"$3" 2>&1
}

# ---------------------------------------------------------------------------
# storm control
# ---------------------------------------------------------------------------
STORM_PID=""
STORM_OUT=""
STORM_STOP=""
STORM_LOG=""

storm_start() { # jsonrpc_port tag
  local port="$1" tag="$2"
  STORM_OUT="${RUN_DIR}/storm-${tag}.json"
  STORM_STOP="${RUN_DIR}/storm-${tag}.stop"
  STORM_LOG="${RUN_DIR}/storm-${tag}.log"
  local ready="${RUN_DIR}/storm-${tag}.ready"
  rm -f "${STORM_STOP}" "${ready}" "${STORM_OUT}"
  python3 "${STORM_PY}" storm --port "${port}" --oracles "${ORACLES_FILE}" \
    --workers "${WORKERS}" --duration "${STORM_MAX_SECS}" \
    --timeout "${STORM_REQ_TIMEOUT_SECS}" --stop-file "${STORM_STOP}" \
    --ready-file "${ready}" --out "${STORM_OUT}" >"${STORM_LOG}" 2>&1 &
  STORM_PID=$!
  track_pid "${STORM_PID}"
  local waited=0
  while [ "${waited}" -lt 40 ]; do
    [ -f "${ready}" ] && { log "storm[${tag}]: live"; return 0; }
    kill -0 "${STORM_PID}" 2>/dev/null || { err "storm[${tag}] died early"; return 1; }
    sleep 1
    waited=$((waited + 1))
  done
  err "storm[${tag}] never got a first answer from the node"
  return 1
}

storm_stop() {
  [ -n "${STORM_PID}" ] || return 0
  touch "${STORM_STOP}"
  local waited=0
  while [ "${waited}" -lt 60 ]; do
    kill -0 "${STORM_PID}" 2>/dev/null || { STORM_PID=""; return 0; }
    sleep 1
    waited=$((waited + 1))
  done
  err "storm driver did not stop within 60s"
  kill_quiet "${STORM_PID}" KILL
  STORM_PID=""
  return 1
}

storm_count() { # key
  [ -f "${STORM_OUT}" ] || { printf '0'; return; }
  jq -r --arg k "$1" '.[$k] // 0' "${STORM_OUT}" 2>/dev/null || printf '0'
}

# storm_assert LABEL ALLOW_TRANSPORT -> 0 clean, 1 violated
storm_assert() {
  local label="$1" allow_transport="$2" bad="" total wrong unexpected malformed timeouts transport
  if [ ! -f "${STORM_OUT}" ]; then
    err "${label}: no storm result file"
    return 1
  fi
  total="$(storm_count total)"
  wrong="$(storm_count wrong)"
  unexpected="$(storm_count unexpected_result)"
  malformed="$(storm_count malformed)"
  timeouts="$(storm_count timeout)"
  transport="$(storm_count transport)"
  log "${label}: total=${total} ok=$(storm_count ok) wrong=${wrong} unexpected_result=${unexpected} rpc_error=$(storm_count rpc_error) http_error=$(storm_count http_error) transport=${transport} timeout=${timeouts} malformed=${malformed} max_latency_ms=$(storm_count max_latency_ms)"
  [ "${wrong}" -eq 0 ] || bad="${bad} wrong=${wrong}"
  [ "${unexpected}" -eq 0 ] || bad="${bad} unexpected_result=${unexpected}"
  [ "${malformed}" -eq 0 ] || bad="${bad} malformed=${malformed}"
  [ "${timeouts}" -le "${MAX_TIMEOUTS}" ] || bad="${bad} timeout=${timeouts}"
  [ "${total}" -ge "${MIN_REQUESTS}" ] || bad="${bad} too-few-requests=${total}"
  if [ "${allow_transport}" -eq 0 ] && [ "${transport}" -ne 0 ]; then
    bad="${bad} transport=${transport}"
  fi
  if [ -n "${bad}" ]; then
    err "${label}: assertion violated:${bad}"
    jq -r '.samples[]? | "  SAMPLE " + tostring' "${STORM_OUT}" 2>/dev/null | head -20 >&2
    return 1
  fi
  return 0
}

# storm_saw_fault -- the storm must have observed the node going away, otherwise the
# phase proved nothing about queries in flight across the fault.
storm_saw_fault() {
  local transport http_error
  transport="$(storm_count transport)"
  http_error="$(storm_count http_error)"
  [ "${transport}" -gt 0 ] || [ "${http_error}" -gt 0 ]
}

# ---------------------------------------------------------------------------
# phase 0: build the chain and capture the oracles
# ---------------------------------------------------------------------------
NODE_DIR="${RUN_DIR}/node"
NODE_CONF="${NODE_DIR}/node.conf"
NODE_PID=""
ORACLES_FILE="${RUN_DIR}/oracles.json"
SPEC_FILE="${RUN_DIR}/spec.json"

write_single_conf() { # identity_init
  CONF_IDENTITY_INIT="$1"
  CONF_P2P_PORT="${P_P2P}"
  CONF_HTTP_PORT="${P_HTTP}"
  CONF_RPC_PORT="${P_RPC}"
  CONF_JSONRPC_PORT="${P_JSONRPC}"
  CONF_METRICS_PORT="${P_METRICS}"
  CONF_ACTIVE=""
  CONF_GENESIS_WITNESSES="$(single_witness_block)"
  CONF_LOCAL_WITNESS="${W1_KEY}"
  write_node_conf "${NODE_CONF}"
}

start_single_node() { # identity_init label
  write_single_conf "$1"
  node_start "${NODE_DIR}" "${NODE_CONF}"
  NODE_PID="${LAST_NODE_PID}"
  node_outcome "${NODE_PID}" "${NODE_DIR}" "${P_HTTP}" "${READY_TIMEOUT_SECS}"
  case "${NODE_OUTCOME}" in
    READY) log "$2: node ready (pid ${NODE_PID})"; return 0 ;;
    *)
      err "$2: node did not become ready -> ${NODE_OUTCOME}"
      if archive_error_in_log "${NODE_DIR}"; then
        err "$2: archive error present in log; see ${NODE_DIR}/logs/tron.log"
      fi
      return 1
      ;;
  esac
}

phase_setup() {
  log "== setup: fresh single-SR chain with archive enabled =="
  if ! start_single_node true "setup"; then
    record_phase setup FAIL "node-never-ready:${NODE_OUTCOME:-unknown}"
    return 1
  fi
  if ! wait_for_height "${P_HTTP}" "${CHAIN_WARMUP_BLOCKS}" 180; then
    record_phase setup FAIL "chain-did-not-produce-blocks"
    return 1
  fi

  # Height 1 always participates so at least one oracle predates every mutation; the
  # transfer heights make the per-height oracle values differ, which is what turns a
  # wrong-height answer under concurrency into a detectable WRONG value.
  local heights="1"
  if [ "${WITH_TXS}" -eq 1 ]; then
    local amount
    for amount in 1000000 2000000 3000000; do
      if ! submit_transfer "${P_HTTP}" "${amount}"; then
        warn "transfer of ${amount} did not land; continuing with fewer mutation heights"
        break
      fi
      log "setup: transfer ${amount} mined in block ${LAST_TX_BLOCK} (tx ${LAST_TXID})"
      heights="${heights} ${LAST_TX_BLOCK}"
      TRACE_TXID="${LAST_TXID}"
    done
  fi

  local last=1 height
  for height in ${heights}; do
    if [ "${height}" -gt "${last}" ]; then
      last="${height}"
    fi
  done
  # One block past the last mutation guarantees the mutated value is visible.
  last=$((last + 1))
  heights="${heights} ${last}"

  if ! wait_for_height "${P_HTTP}" "${last}" 180; then
    record_phase setup FAIL "head-never-reached-${last}"
    return 1
  fi
  if ! wait_archive_covers "${P_JSONRPC}" "${last}" "${PUBLISH_WAIT_SECS}"; then
    record_phase setup FAIL "archive-never-published-${last}"
    return 1
  fi
  log "setup: archive covers heights ${heights}"

  build_spec_for_heights "${SPEC_FILE}" ${heights}
  if ! capture_oracles "${P_JSONRPC}" "${SPEC_FILE}" "${ORACLES_FILE}" "${RUN_DIR}/capture.log"; then
    cat "${RUN_DIR}/capture.log" >&2
    record_phase setup FAIL "oracle-capture-failed"
    return 1
  fi
  grep -E '^(CAPTURE|ORACLE_CAPTURE)' "${RUN_DIR}/capture.log" | sed 's/^/  /' >&2

  # distinct_results counts only oracles whose baseline was a real RESULT. Zero means every
  # oracle is an error-signature oracle, and the storm would then only be proving that errors
  # stay errors -- it could never observe a WRONG VALUE, which is this scenario's entire
  # point. That is a vacuous configuration, not a soft warning.
  local distinct
  distinct="$(grep -o 'distinct_results=[0-9]*' "${RUN_DIR}/capture.log" | tail -1 | cut -d= -f2)"
  case "${distinct}" in ''|*[!0-9]*) distinct=0 ;; esac
  if [ "${distinct}" -lt 1 ]; then
    err "setup: no oracle captured a served VALUE (distinct_results=0); the storm could not detect a wrong value"
    record_phase setup FAIL "no-value-oracles"
    return 1
  fi
  if [ "${distinct}" -lt 2 ]; then
    warn "setup: oracles carry only ${distinct} distinct result(s); a wrong-height answer may be undetectable"
  fi
  record_phase setup PASS \
    "oracles=$(jq -r '.oracles | length' "${ORACLES_FILE}") heights=$(printf '%s' "${heights}" | tr ' ' ',')"
  return 0
}

# ---------------------------------------------------------------------------
# phase: baseline (no fault)
# ---------------------------------------------------------------------------
phase_baseline() {
  log "== baseline: storm with no fault =="
  if ! storm_start "${P_JSONRPC}" baseline; then
    record_phase baseline FAIL "storm-never-live"
    return 1
  fi
  sleep $((PRE_FAULT_SECS + POST_FAULT_SECS))
  storm_stop || { record_phase baseline FAIL "storm-driver-hung"; return 1; }
  if ! storm_assert "baseline" 0; then
    record_phase baseline FAIL "storm-assertions"
    return 1
  fi
  if ! assert_no_leak "${P_METRICS}" "baseline"; then
    record_phase baseline FAIL "snapshot-or-permit-leak"
    return 1
  fi
  record_phase baseline PASS "requests=$(storm_count total) rpc_error=$(storm_count rpc_error)"
  return 0
}

# ---------------------------------------------------------------------------
# restart + replay, shared by the clean-stop and sigkill phases
# ---------------------------------------------------------------------------
# restart_and_replay LABEL -> 0 pass, 1 fail, 2 explicit fail-stop
restart_and_replay() {
  local label="$1" code
  write_single_conf false
  node_start "${NODE_DIR}" "${NODE_CONF}"
  NODE_PID="${LAST_NODE_PID}"
  node_outcome "${NODE_PID}" "${NODE_DIR}" "${P_HTTP}" "${READY_TIMEOUT_SECS}"
  case "${NODE_OUTCOME}" in
    READY) : ;;
    EXITED:*)
      code="${NODE_OUTCOME#EXITED:}"
      err "${label}: restart fail-stopped with exit ${code}"
      if archive_error_in_log "${NODE_DIR}"; then
        err "${label}: archive fail-stop evidence in ${NODE_DIR}/logs/tron.log"
      fi
      RESTART_DETAIL="failstop-exit-${code}"
      return 2
      ;;
    HUNG)
      err "${label}: restart HUNG -- process alive, HTTP never ready (no exit path)"
      archive_error_in_log "${NODE_DIR}" \
        && err "${label}: archive error in log; this is the ArchiveException-without-TronError hang"
      RESTART_DETAIL="restart-hung"
      return 1
      ;;
  esac

  if ! replay_oracles "${P_JSONRPC}" "${ORACLES_FILE}" "${RUN_DIR}/replay-${label}.log"; then
    grep -E '^(ORACLE_MISMATCH|ORACLE_UNAVAILABLE|ORACLE_REPLAY)' \
      "${RUN_DIR}/replay-${label}.log" | sed 's/^/  /' >&2
    RESTART_DETAIL="oracle-replay-mismatch"
    return 1
  fi
  log "${label}: $(grep ORACLE_REPLAY_OK "${RUN_DIR}/replay-${label}.log")"
  if ! assert_no_leak "${P_METRICS}" "${label}"; then
    RESTART_DETAIL="post-restart-leak"
    return 1
  fi
  RESTART_DETAIL="recovered"
  return 0
}

# ---------------------------------------------------------------------------
# phase: clean stop under storm
# ---------------------------------------------------------------------------
phase_clean_stop() {
  log "== clean-stop: SIGTERM while historical queries are in flight =="
  if ! storm_start "${P_JSONRPC}" clean-stop; then
    record_phase clean-stop FAIL "storm-never-live"
    return 1
  fi
  sleep "${PRE_FAULT_SECS}"
  log "clean-stop: sending SIGTERM to ${NODE_PID}"
  kill -TERM "${NODE_PID}" 2>/dev/null || true
  node_await_exit "${NODE_PID}" "${EXIT_TIMEOUT_SECS}"
  local code="${NODE_EXIT_CODE}"
  sleep "${POST_FAULT_SECS}"
  storm_stop || { record_phase clean-stop FAIL "storm-driver-hung"; return 1; }

  if [ "${code}" = "254" ]; then
    err "clean-stop: node did not exit within ${EXIT_TIMEOUT_SECS}s after SIGTERM"
    record_phase clean-stop FAIL "shutdown-hung"
    return 1
  fi
  log "clean-stop: node exited with ${code}"
  if ! storm_assert "clean-stop" 1; then
    record_phase clean-stop FAIL "storm-assertions"
    return 1
  fi
  if ! storm_saw_fault; then
    err "clean-stop: storm never observed the node going away; the phase proved nothing"
    record_phase clean-stop FAIL "fault-not-observed"
    return 1
  fi

  restart_and_replay clean-stop
  local rc=$?
  if [ "${rc}" -eq 0 ]; then
    record_phase clean-stop PASS "exit=${code} ${RESTART_DETAIL} requests=$(storm_count total)"
    return 0
  fi
  if [ "${rc}" -eq 2 ] && [ "${ALLOW_FAILSTOP}" -eq 1 ]; then
    record_phase clean-stop PASS "explicit-${RESTART_DETAIL} (accepted via --allow-failstop)"
    return 0
  fi
  record_phase clean-stop FAIL "${RESTART_DETAIL}"
  return 1
}

# ---------------------------------------------------------------------------
# phase: SIGKILL under storm
# ---------------------------------------------------------------------------
phase_sigkill() {
  log "== sigkill: kill -9 while historical queries are in flight =="
  if ! storm_start "${P_JSONRPC}" sigkill; then
    record_phase sigkill FAIL "storm-never-live"
    return 1
  fi
  sleep "${PRE_FAULT_SECS}"
  log "sigkill: sending SIGKILL to ${NODE_PID}"
  kill -KILL "${NODE_PID}" 2>/dev/null || true
  node_await_exit "${NODE_PID}" 60
  local code="${NODE_EXIT_CODE}"
  sleep "${POST_FAULT_SECS}"
  storm_stop || { record_phase sigkill FAIL "storm-driver-hung"; return 1; }

  if [ "${code}" = "254" ]; then
    err "sigkill: process still present 60s after SIGKILL"
    record_phase sigkill FAIL "process-survived-sigkill"
    return 1
  fi
  if ! storm_assert "sigkill" 1; then
    record_phase sigkill FAIL "storm-assertions"
    return 1
  fi
  if ! storm_saw_fault; then
    err "sigkill: storm never observed the node going away; the phase proved nothing"
    record_phase sigkill FAIL "fault-not-observed"
    return 1
  fi

  restart_and_replay sigkill
  local rc=$?
  if [ "${rc}" -eq 0 ]; then
    record_phase sigkill PASS "${RESTART_DETAIL} requests=$(storm_count total)"
    return 0
  fi
  if [ "${rc}" -eq 2 ] && [ "${ALLOW_FAILSTOP}" -eq 1 ]; then
    record_phase sigkill PASS "explicit-${RESTART_DETAIL} (accepted via --allow-failstop)"
    return 0
  fi
  record_phase sigkill FAIL "${RESTART_DETAIL}"
  return 1
}

stop_single_node() {
  [ -n "${NODE_PID}" ] || return 0
  kill -0 "${NODE_PID}" 2>/dev/null || return 0
  kill -TERM "${NODE_PID}" 2>/dev/null || true
  node_await_exit "${NODE_PID}" "${EXIT_TIMEOUT_SECS}"
  NODE_PID=""
}

# ---------------------------------------------------------------------------
# phase: reorg / unwind under storm (2-witness topology)
# ---------------------------------------------------------------------------
# A single-SR chain can never reorg: solid == head, so khaosDb/revokingStore capacity is
# head - solid + 1 == 1. Two witnesses make solid the MIN of both latestBlockNums, so a
# partition stalls solid on both sides and an arbitrarily deep fork becomes legal.
# Node A is frozen with SIGSTOP while partitioned, so B's branch is strictly heavier and
# A is deterministically the side that unwinds. The storm runs against A.
FORK_A_DIR="${RUN_DIR}/fork-a"
FORK_B_DIR="${RUN_DIR}/fork-b"
FORK_A_PID=""
FORK_B_PID=""
RELAY_PID=""

fork_write_conf() { # dir file identity_init p2p http rpc jsonrpc metrics active witness_key
  CONF_IDENTITY_INIT="$3"
  CONF_P2P_PORT="$4"
  CONF_HTTP_PORT="$5"
  CONF_RPC_PORT="$6"
  CONF_JSONRPC_PORT="$7"
  CONF_METRICS_PORT="$8"
  CONF_ACTIVE="$9"
  CONF_GENESIS_WITNESSES="$(dual_witness_block)"
  CONF_LOCAL_WITNESS="${10}"
  mkdir -p "$1"
  write_node_conf "$2"
}

relay_start() {
  python3 "${RELAY_PY}" "${RELAY_PORT}" 127.0.0.1 "${A_P2P}" \
    >>"${RUN_DIR}/relay.log" 2>&1 &
  RELAY_PID=$!
  track_pid "${RELAY_PID}"
  sleep 1
}

relay_stop() {
  [ -n "${RELAY_PID}" ] || return 0
  kill_quiet "${RELAY_PID}" KILL
  RELAY_PID=""
}

# count_matches FILE PATTERN -> occurrence count (0 when the file is absent)
count_matches() {
  local count
  count="$(grep -cE "$2" "$1" 2>/dev/null || true)"
  case "${count}" in
    ''|*[!0-9]*) printf '0' ;;
    *) printf '%s' "${count}" ;;
  esac
}

# wait_for_new_switch_fork DIR BASELINE_COUNT TIMEOUT
#
# Waits for a NEW 'Switch fork!' beyond the baseline count rather than for the pattern to
# be present at all: a 2-witness chain already reorgs once while the two nodes first sync
# to each other, so matching on presence would return instantly on that stale line and let
# the phase claim success without ever observing the reorg it induced.
wait_for_new_switch_fork() {
  local dir="$1" baseline="$2" timeout="$3" waited=0 now
  while [ "${waited}" -lt "${timeout}" ]; do
    now="$(count_matches "${dir}/logs/tron.log" 'Switch fork!')"
    if [ "${now}" -gt "${baseline}" ]; then
      return 0
    fi
    sleep 3
    waited=$((waited + 3))
  done
  return 1
}

phase_reorg() {
  log "== reorg: switchFork/unwind while historical queries are in flight =="

  fork_write_conf "${FORK_A_DIR}" "${FORK_A_DIR}/node.conf" true \
    "${A_P2P}" "${A_HTTP}" "${A_RPC}" "${A_JSONRPC}" "${A_METRICS}" "" "${W1_KEY}"
  fork_write_conf "${FORK_B_DIR}" "${FORK_B_DIR}/node.conf" true \
    "${B_P2P}" "${B_HTTP}" "${B_RPC}" "${B_JSONRPC}" "${B_METRICS}" \
    "\"127.0.0.1:${RELAY_PORT}\"" "${W2_KEY}"

  relay_start
  node_start "${FORK_A_DIR}" "${FORK_A_DIR}/node.conf"
  FORK_A_PID="${LAST_NODE_PID}"
  node_start "${FORK_B_DIR}" "${FORK_B_DIR}/node.conf"
  FORK_B_PID="${LAST_NODE_PID}"

  node_outcome "${FORK_A_PID}" "${FORK_A_DIR}" "${A_HTTP}" "${READY_TIMEOUT_SECS}"
  [ "${NODE_OUTCOME}" = "READY" ] \
    || { record_phase reorg SKIPPED "node-a-not-ready:${NODE_OUTCOME}"; return 0; }
  node_outcome "${FORK_B_PID}" "${FORK_B_DIR}" "${B_HTTP}" "${READY_TIMEOUT_SECS}"
  [ "${NODE_OUTCOME}" = "READY" ] \
    || { record_phase reorg SKIPPED "node-b-not-ready:${NODE_OUTCOME}"; return 0; }

  if ! wait_for_height "${A_HTTP}" 10 240 || ! wait_for_height "${B_HTTP}" 10 240; then
    record_phase reorg SKIPPED "two-witness-chain-did-not-produce"
    return 0
  fi

  local common
  common="$(head_num "${A_HTTP}")"
  local oracle_height=$((common - 4))
  [ "${oracle_height}" -ge 1 ] || oracle_height=1
  if ! wait_archive_covers "${A_JSONRPC}" "${oracle_height}" "${PUBLISH_WAIT_SECS}"; then
    record_phase reorg SKIPPED "archive-never-published-pre-fork-height"
    return 0
  fi
  # Only pre-fork heights are used: blocks 0..oracle_height are common to both branches,
  # so their historical values are invariant across the reorg. Any change is a real defect.
  # The setup chain's txid does not exist here, so no trace oracle on this chain.
  TRACE_TXID=""
  build_spec_for_heights "${RUN_DIR}/spec-reorg.json" 1 "${oracle_height}"
  ORACLES_FILE="${RUN_DIR}/oracles-reorg.json"
  if ! capture_oracles "${A_JSONRPC}" "${RUN_DIR}/spec-reorg.json" "${ORACLES_FILE}" \
      "${RUN_DIR}/capture-reorg.log"; then
    cat "${RUN_DIR}/capture-reorg.log" >&2
    record_phase reorg SKIPPED "pre-fork-oracle-capture-failed"
    return 0
  fi
  # Same anti-vacuity rule as phase_setup: an all-errors oracle set cannot detect a wrong
  # value across the unwind, which is the only thing this phase is here to prove.
  local reorg_distinct
  reorg_distinct="$(grep -o 'distinct_results=[0-9]*' "${RUN_DIR}/capture-reorg.log" | tail -1 | cut -d= -f2)"
  case "${reorg_distinct}" in ''|*[!0-9]*) reorg_distinct=0 ;; esac
  if [ "${reorg_distinct}" -lt 1 ]; then
    warn "reorg: no pre-fork oracle captured a served VALUE; the unwind could not be graded on values"
    record_phase reorg SKIPPED "no-value-oracles-pre-fork"
    return 0
  fi
  log "reorg: pre-fork oracles captured at heights 1,${oracle_height} (common head ${common})"

  log "reorg: partitioning for ${FORK_PARTITION_SECS}s"
  relay_stop
  sleep "${FORK_PARTITION_SECS}"
  log "reorg: freezing node A for ${FORK_FREEZE_SECS}s so B's branch wins"
  kill -STOP "${FORK_A_PID}" 2>/dev/null || true
  sleep "${FORK_FREEZE_SECS}"
  kill -CONT "${FORK_A_PID}" 2>/dev/null || true
  sleep 2

  if ! storm_start "${A_JSONRPC}" reorg; then
    record_phase reorg FAIL "storm-never-live-after-thaw"
    return 1
  fi

  # Baselines taken immediately before healing, so both the switch and the erase count
  # measure only what this phase induced.
  local switch_before erase_before erased switch_line
  switch_before="$(count_matches "${FORK_A_DIR}/logs/tron.log" 'Switch fork!')"
  erase_before="$(count_matches "${FORK_A_DIR}/logs/tron.log" 'Start to erase block')"
  log "reorg: healing the partition; node A must unwind onto B's heavier branch"
  relay_start

  if ! wait_for_new_switch_fork "${FORK_A_DIR}" "${switch_before}" "${FORK_SWITCH_TIMEOUT_SECS}"; then
    storm_stop || true
    warn "reorg: no NEW 'Switch fork!' on node A within ${FORK_SWITCH_TIMEOUT_SECS}s" \
      "(baseline was ${switch_before})"
    record_phase reorg SKIPPED "reorg-not-observed"
    return 0
  fi
  switch_line="$(grep 'Switch fork!' "${FORK_A_DIR}/logs/tron.log" | tail -1)"
  erased=$(( $(count_matches "${FORK_A_DIR}/logs/tron.log" 'Start to erase block') - erase_before ))
  log "reorg: ${switch_line}"
  log "reorg: blocks erased by this reorg on A: ${erased}"

  sleep "${POST_FAULT_SECS}"
  storm_stop || { record_phase reorg FAIL "storm-driver-hung"; return 1; }

  # Node A stays up through the unwind, but it is also replaying B's branch under load, so
  # transport-level churn is tolerated and only reported. The load-bearing assertions here
  # are wrong=0 / unexpected_result=0 / malformed=0 / timeout=0.
  if ! storm_assert "reorg" 1; then
    record_phase reorg FAIL "storm-assertions"
    return 1
  fi
  if ! replay_oracles "${A_JSONRPC}" "${ORACLES_FILE}" "${RUN_DIR}/replay-reorg.log"; then
    grep -E '^(ORACLE_MISMATCH|ORACLE_UNAVAILABLE|ORACLE_REPLAY)' \
      "${RUN_DIR}/replay-reorg.log" | sed 's/^/  /' >&2
    record_phase reorg FAIL "pre-fork-oracle-changed-across-reorg"
    return 1
  fi
  log "reorg: $(grep ORACLE_REPLAY_OK "${RUN_DIR}/replay-reorg.log")"
  if ! assert_no_leak "${A_METRICS}" "reorg"; then
    record_phase reorg FAIL "snapshot-or-permit-leak"
    return 1
  fi
  record_phase reorg PASS "erased=${erased} requests=$(storm_count total)"
  return 0
}

fork_teardown() {
  relay_stop
  local pid
  for pid in "${FORK_A_PID}" "${FORK_B_PID}"; do
    [ -n "${pid}" ] || continue
    kill_quiet "${pid}" CONT
    kill_quiet "${pid}" TERM
  done
  for pid in "${FORK_A_PID}" "${FORK_B_PID}"; do
    [ -n "${pid}" ] || continue
    node_await_exit "${pid}" 60
  done
  FORK_A_PID=""
  FORK_B_PID=""
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
write_assets

phase_setup || true

if [ "${FAIL_COUNT}" -eq 0 ]; then
  phase_baseline || true
  if phase_should_continue; then
    phase_clean_stop || true
  else
    record_phase clean-stop SKIPPED "earlier-phase-failed"
  fi
  if phase_should_continue; then
    phase_sigkill || true
  else
    record_phase sigkill SKIPPED "earlier-phase-failed"
  fi
  stop_single_node
  if [ "${WITH_FORK}" -eq 1 ]; then
    if phase_should_continue; then
      phase_reorg || true
      fork_teardown
    else
      record_phase reorg SKIPPED "earlier-phase-failed"
    fi
  else
    record_phase reorg SKIPPED "disabled-via---no-fork"
  fi
else
  record_phase baseline SKIPPED "setup-failed"
  record_phase clean-stop SKIPPED "setup-failed"
  record_phase sigkill SKIPPED "setup-failed"
  record_phase reorg SKIPPED "setup-failed"
fi

printf '\n'
printf '%-14s %-9s %s\n' "PHASE" "VERDICT" "DETAIL"
printf '%-14s %-9s %s\n' "--------------" "---------" "----------------------------------------"
paste_index=1
while [ "${paste_index}" -le "${PHASE_COUNT}" ]; do
  name="$(printf '%s' "${PHASE_NAMES}" | sed -n "${paste_index}p")"
  verdict="$(printf '%s' "${PHASE_VERDICTS}" | sed -n "${paste_index}p")"
  detail="$(printf '%s' "${PHASE_DETAILS}" | sed -n "${paste_index}p")"
  printf '%-14s %-9s %s\n' "${name}" "${verdict}" "${detail}"
  paste_index=$((paste_index + 1))
done
printf '\n'
log "artifacts: ${RUN_DIR}"

if [ "${FAIL_COUNT}" -eq 0 ] && [ "${PASS_COUNT}" -gt 0 ]; then
  printf 'CONCURRENCY_E2E_OK phases=%d passed=%d skipped=%d\n' \
    "${PHASE_COUNT}" "${PASS_COUNT}" "${SKIP_COUNT}"
  exit 0
fi
printf 'CONCURRENCY_E2E_FAIL phases=%d passed=%d failed=%d skipped=%d\n' \
  "${PHASE_COUNT}" "${PASS_COUNT}" "${FAIL_COUNT}" "${SKIP_COUNT}"
exit 1
