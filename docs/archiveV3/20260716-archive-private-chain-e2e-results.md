# Archive private-chain E2E and fault-matrix results

- **Date:** 2026-07-16
- **Branch:** `feat/archive-node`
- **Layout:** `UNIFIED_V1` only
- **Node:** locally built `FullNode.jar`, single SR, P2P disabled
- **Artifact SHA-256:** `1b65ef635c6b7497e88ed1ad9f00a4b1d42c528a4c2bd03edd494e48d1cc86d7`

## 1. Functional E2E

The node started from an empty temporary directory with archive enabled and exposed:

- HTTP wallet APIs
- JSON-RPC
- gRPC
- Prometheus metrics

The scenario submitted and confirmed 20 transactions covering:

- account creation, funding, rename and balance return
- bandwidth, energy and Tron Power freeze
- witness vote
- resource delegation and undelegation
- resource unfreeze
- TRC10 issue and transfer
- smart-contract deployment and calls
- contract storage transitions `111 -> 222 -> 0`
- `SELFDESTRUCT` contract deletion

Fifteen historical-state oracles were captured across multiple block heights. They covered account
state, contract code, contract storage and deletion boundaries. All 15 were replayed successfully
after later state changes and again after node restart.

Final scenario result:

```text
SCENARIO_OK transactions=20 oracles=15 finalHeight=50
ORACLE_REPLAY_OK count=15
```

## 2. Graceful shutdown and restart

Before shutdown the archive had captured block 57 and the node produced block 58. Shutdown stopped
consensus before services and the canonical database. No block 59 was produced after shutdown
started.

On restart the canonical database recovered through block 56. Startup reconciliation removed the
non-recoverable in-flight tail at blocks 57 and 58 without setting a repair marker. After the
publication durability fix and subsequent crash tests, the final durable archive baseline was:

```text
first=0 last=57 changesets=1098 tombstones=7
inFlight=0 repairRequired=0
```

The final offline strict-open probe reported:

```text
CF_COUNTS {META=2, INFLIGHT=0, INDEX=215, LATEST=285, HISTORY=1098,
CHANGESET=1098, BLOCK_MARKER=58, COMMITMENT=0}
OFFLINE_PROBE_OK first=0 last=57 changesets=1098 tombstones=7
```

## 3. Crash windows

SIGKILL was injected around normal block production and at exact temporary test hooks. The hooks
were built into a separate temporary fault jar and are not present in the final source or jar.

Covered windows:

1. Before block production.
2. Immediately after block generation.
3. After the canonical push log but before a durable archive journal.
4. After journal persistence and before canonical session commit.
5. After canonical session commit and before journal acknowledgement.
6. After journal acknowledgement and before publication.
7. Multiple in-flight blocks above the recoverable canonical head.

Observed recovery behavior:

- `JOURNALED` and `CANONICAL_COMMITTED` tails above the recovered canonical head were rolled back.
- The first durable archived block was not skipped.
- Transaction numbers remained gap-free on the recovered branch.
- No historical query fell back to live state.
- Recoverable crashes restarted cleanly with no repair marker.
- The Manager-level real `switchFork` recovery test passed.

## 4. Disk and logical corruption matrix

The following corruptions were rejected before serving archive queries:

- invalid archive protocol identity ledger
- active MANIFEST byte corruption
- active SST data corruption under full scrub
- read-only archive database directory
- missing mid-chain block marker
- corrupted history row
- unknown in-flight key

The node either failed startup or persisted a repair-required marker. No case silently returned an
incorrect historical value. Corrupting RocksDB's own `IDENTITY` file was safely self-recovered by
RocksDB from its durable metadata and the strict probe still passed.

## 5. Defects found and fixed during E2E

1. A store registered during an active revoking snapshot was not aligned with the current snapshot
   depth. Root commit could partially merge stores or fail during genesis migrations.
2. Archive publication could advance beyond the canonical database's restart-recoverable head.
   A clean restart then treated valid archive data as being ahead of canonical state.
3. Shutdown stopped services before consensus, and the DPoS task could produce one final block
   after waking from sleep.
4. Empty asset-v2 stores were reset inside the forced genesis snapshot, detaching them from that
   snapshot.

Focused regression tests were added for these paths.

## 6. Regression and performance observations

The archive-focused chainbase, actuator and framework suites passed, including historical VM,
JSON-RPC, trace reconstruction, genesis lifecycle, publication durability, snapshot alignment and
real fork-switch recovery tests. Framework main/test checkstyle and `git diff --check` also passed.
The configuration and service-factory tests also confirmed that both commitment flags fail closed.

At the end of the functional run:

- archive lag: 0
- repair marker: 0
- completed historical queries: 30
- all observed historical queries were below the 5 ms histogram bucket
- merged archive records: 1079 before the later crash-matrix blocks

No material block-production slowdown was visible at this small private-chain scale. This is a
correctness result, not a production throughput benchmark.

## 7. Remaining production gates

The following are not claimed by this run:

- 72-hour or longer soak with sustained transaction load
- large from-zero synchronization and mature multi-billion-row RocksDB behavior
- real filesystem `ENOSPC` using a capacity-limited mount
- power-loss testing below the process/filesystem boundary
- production cache, bloom-filter and compaction sizing under representative hardware

`storage.archive.commitment.enable` remains an explicitly unsupported P0 option and fails closed.
The commitment column family is empty by design; there is no runnable commitment mode to validate
in this branch yet.
