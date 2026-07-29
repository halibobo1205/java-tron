#!/usr/bin/env bash
#
# ports.sh -- the single source of truth for every TCP port this harness binds.
#
# Sourced by lib.sh, scenario-common.sh, scenario-concurrency-under-fault.sh and
# run-all.sh, so there is exactly ONE port map and a new node can never partially
# collide with an old one.
#
# ---------------------------------------------------------------------------
# WHY THIS FILE EXISTS
# ---------------------------------------------------------------------------
# Two independent defects made scenario-fork-reorg.sh die inside run-all.sh with
# "node B never became ready" while passing standalone:
#
#   1. THE EPHEMERAL RANGE.  macOS hands outbound sockets local ports from
#      net.inet.ip.portrange.first..last = 49152..65535.  The old map put every
#      node's gRPC listener at 50051 + 100*slot -- 50051, 50151, 50251 ... all
#      INSIDE that range.  fork-reorg's preflight saw 50151 free at 14:54:43;
#      ten seconds later RpcApiService tried to bind 0.0.0.0:50151 and got
#      EADDRINUSE, because in between the kernel had handed 50151 to some other
#      process's outbound connection.  No amount of preflight can close that
#      race -- the port simply must not be in the ephemeral range.  Every port
#      below is therefore < AH_PORT_EPHEMERAL_FLOOR, and ah_port_space_check
#      refuses to run if an offset would push the space into it.
#
#   2. PARTIALLY-DERIVED NODES.  fork-reorg's node B took p2p from a hand-picked
#      +1 (16667) but http/rpc/jsonrpc/metrics from the +100 family
#      (8190/50151/8645/9627).  Node B's set was thus neither disjoint from nor
#      aligned with anybody else's, which is exactly how "only some of its ports
#      collide" happens.  Here EVERY port of a node comes from one per-node base.
#
# ---------------------------------------------------------------------------
# THE MAP
# ---------------------------------------------------------------------------
# space base            21000  (ARCHIVE_HARNESS_PORT_SPACE_BASE)
# global shift          + ARCHIVE_HARNESS_PORT_OFFSET (default 0)
#
# One BAND per scenario, 400 ports wide = 40 node blocks:
#
#   band 0  smoke                     21000 - 21399
#   band 1  history-accuracy          21400 - 21799
#   band 2  fork-reorg                21800 - 22199
#   band 3  kill-matrix               22200 - 22599
#   band 4  resource-faults           22600 - 22999
#   band 5  concurrency-under-fault   23000 - 23399
#   band 6  catchup-batch-flush-kill  23400 - 23799
#   band 7  (unregistered scenarios)  23800 - 24199
#
# One BLOCK per node inside a band, 10 ports wide, slot 0..38:
#
#   node_base = band + 10 * slot
#     +0  p2p listen        node.listen.port
#     +1  http fullnode     node.http.fullNodePort
#     +2  rpc (gRPC)        node.rpc.port
#     +3  jsonrpc           node.jsonrpc.httpFullNodePort
#     +4  prometheus        node.metrics.prometheus.port
#     +5  JDWP agent        -agentlib:jdwp address
#     +6..+9  reserved (never bound; keeps a future listener from spilling over)
#
# Slot 39 is the band's AUX block, for scenario-level listeners that belong to
# no node (the TCP relay used by fork-reorg and concurrency):
#
#   aux_base = band + 390
#     +0  relay
#     +1..+9  reserved
#
# A slot above 38 is a hard error rather than a silent spill into the next
# scenario's band.

if [ -n "${AH_PORTS_SH_SOURCED:-}" ]; then
  return 0 2>/dev/null || true
fi
AH_PORTS_SH_SOURCED=1

AH_PORT_SPACE_BASE="${ARCHIVE_HARNESS_PORT_SPACE_BASE:-21000}"
AH_PORT_OFFSET="${ARCHIVE_HARNESS_PORT_OFFSET:-0}"
AH_PORT_BAND_WIDTH=400
AH_PORT_NODE_STRIDE=10
AH_PORT_MAX_SLOT=38
AH_PORT_AUX_SLOT=39
AH_PORT_BAND_COUNT=8
# net.inet.ip.portrange.first on macOS; Linux's is 32768 but its default
# ip_local_port_range never dips below 32768 either, so one floor covers both.
AH_PORT_EPHEMERAL_FLOOR=49152

# ah_port_err MSG -- ports.sh must not depend on lib.sh's logger, because lib.sh
# sources this file before defining one.
ah_port_err() { printf 'ERROR [ports] %s\n' "$*" >&2; }

# ah_port_band_index SLUG -> 0..7.  Unregistered scenarios land in the shared
# sandbox band and say so, rather than silently overlapping a real one.
ah_port_band_index() {
  case "$1" in
    smoke)                    printf '0\n' ;;
    history-accuracy)         printf '1\n' ;;
    fork-reorg)               printf '2\n' ;;
    kill-matrix)              printf '3\n' ;;
    resource-faults)          printf '4\n' ;;
    concurrency-under-fault)  printf '5\n' ;;
    catchup-batch-flush-kill) printf '6\n' ;;
    *)
      ah_port_err "scenario '$1' has no reserved port band; using the shared sandbox band 7." \
        "Register it in ports.sh before adding it to the suite."
      printf '7\n'
      ;;
  esac
}

# ah_port_space_check -- 0 when the whole space stays clear of the ephemeral
# range, 1 (with a diagnosis) otherwise.
ah_port_space_check() {
  local lo hi
  lo=$(( AH_PORT_SPACE_BASE + AH_PORT_OFFSET ))
  hi=$(( lo + AH_PORT_BAND_COUNT * AH_PORT_BAND_WIDTH - 1 ))
  if [ "$lo" -lt 1024 ]; then
    ah_port_err "port space starts at $lo, inside the privileged range (<1024)"
    return 1
  fi
  if [ "$hi" -ge "$AH_PORT_EPHEMERAL_FLOOR" ]; then
    ah_port_err "port space $lo-$hi reaches into the ephemeral range (>=$AH_PORT_EPHEMERAL_FLOOR).
The kernel hands ephemeral ports to outbound sockets, so a listener there can lose a race it
cannot detect. Lower ARCHIVE_HARNESS_PORT_OFFSET / ARCHIVE_HARNESS_PORT_SPACE_BASE."
    return 1
  fi
  return 0
}

# ah_port_band SLUG -> first port of that scenario's band.
ah_port_band() {
  local idx
  idx="$(ah_port_band_index "$1")"
  printf '%s\n' $(( AH_PORT_SPACE_BASE + AH_PORT_OFFSET + idx * AH_PORT_BAND_WIDTH ))
}

# ah_port_band_range SLUG -> "LO HI", the closed interval run-all.sh preflights.
ah_port_band_range() {
  local lo
  lo="$(ah_port_band "$1")"
  printf '%s %s\n' "$lo" $(( lo + AH_PORT_BAND_WIDTH - 1 ))
}

# ah_port_node_base SLUG SLOT -> first port of that node's 10-port block.
ah_port_node_base() {
  local slug="$1" slot="$2" band
  case "$slot" in
    ''|*[!0-9]*) ah_port_err "node slot must be a non-negative integer, got '$slot'"; return 1 ;;
  esac
  if [ "$slot" -gt "$AH_PORT_MAX_SLOT" ]; then
    ah_port_err "node slot $slot exceeds $AH_PORT_MAX_SLOT for scenario '$slug'; \
the next slot would spill into another scenario's band"
    return 1
  fi
  band="$(ah_port_band "$slug")"
  printf '%s\n' $(( band + slot * AH_PORT_NODE_STRIDE ))
}

# ah_port_role_offset ROLE -> the offset inside a node block.
ah_port_role_offset() {
  case "$1" in
    p2p)      printf '0\n' ;;
    http)     printf '1\n' ;;
    rpc)      printf '2\n' ;;
    jsonrpc)  printf '3\n' ;;
    metrics)  printf '4\n' ;;
    jdwp)     printf '5\n' ;;
    *) ah_port_err "unknown port role '$1' (p2p|http|rpc|jsonrpc|metrics|jdwp)"; return 1 ;;
  esac
}

# ah_port_for SLUG SLOT ROLE -> one port.
ah_port_for() {
  local base off
  base="$(ah_port_node_base "$1" "$2")" || return 1
  off="$(ah_port_role_offset "$3")" || return 1
  printf '%s\n' $(( base + off ))
}

# ah_port_aux SLUG N -> scenario-level listener N (0 = relay).
ah_port_aux() {
  local band n="${2:-0}"
  case "$n" in
    ''|*[!0-9]*) ah_port_err "aux index must be a non-negative integer, got '$n'"; return 1 ;;
  esac
  if [ "$n" -ge "$AH_PORT_NODE_STRIDE" ]; then
    ah_port_err "aux index $n exceeds the $AH_PORT_NODE_STRIDE-port aux block"
    return 1
  fi
  band="$(ah_port_band "$1")"
  printf '%s\n' $(( band + AH_PORT_AUX_SLOT * AH_PORT_NODE_STRIDE + n ))
}
