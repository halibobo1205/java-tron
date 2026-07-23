# Archive schema-6 complete local E2E results

- Date: 2026-07-19
- Branch: `feat/archive-node`
- Commit under test: `c5cef4add5ce8bfd56cd0ba0efe57efb6487c445`
- Persisted layout: `UNIFIED_V1`, schema 6
- Node: locally built `FullNode.jar`, single SR, P2P disabled
- Platform: macOS aarch64, Java 17
- FullNode SHA-256: `1011dfc4000b47b874deff0ca412f2eff6424bfc7cbd3978d250d2ab26e24ea1`
- Test root: `/private/tmp/java-tron-archive-schema6-e2e-20260719`

## 1. Result

The current schema-6 implementation passed the complete local single-node matrix exercised in this
run:

- empty-database functional archive E2E;
- HTTP, JSON-RPC, gRPC, and Prometheus service access;
- exact historical-state oracle replay across restart and later state changes;
- normal shutdown with an unsolidified tail and startup reconciliation;
- five exact archive commit/publication crash windows plus two hook-free timed SIGKILL windows;
- twelve physical and logical corruption cases;
- real capacity exhaustion on a bounded filesystem;
- real archive-device disappearance producing `Input/output error`;
- capacity-matched and saturated concurrent historical reads while transactions and blocks ran;
- archive-focused repository regression tests and framework checkstyle.

No production-code defect was found by this run. No historical query returned a value different
from its captured oracle, and no corruption case silently fell back to live state.

This is a strong local correctness and bounded-concurrency result. It is not a production capacity
certification or a substitute for a large network sync and long soak.

## 2. Functional E2E

The official jar started from an empty node directory with archive enabled, asynchronous
publication enabled, and a single local witness. The scenario confirmed 20 transactions covering:

1. Account creation, funding, rename, and balance return.
2. Bandwidth, energy, and Tron Power freeze.
3. Witness voting.
4. Resource delegation, undelegation, and unfreeze.
5. TRC10 issue and transfer.
6. Contract deployment and constant calls.
7. Contract storage transitions `111 -> 222 -> 0`.
8. `SELFDESTRUCT` code and contract deletion.

Fifteen historical JSON-RPC oracles covered account balances, code, storage, constant execution,
and deletion boundaries at multiple block heights:

```text
SCENARIO_OK transactions=20 oracles=15 finalHeight=50
ORACLE_REPLAY_OK count=15
```

The initial clean restart settled with `repair_required=0`, `publisher_lag_blocks=0`, and no
in-flight rows. The strict offline probe validated every block range, transaction-number position,
changeset chain, locator, payload, marker, typed state value, and domain row.

The final probe after all later load runs was:

```text
CF_COUNTS {META=2, INFLIGHT=0, INDEX=593, LATEST=325, HISTORY=2415,
CHANGESET=2415, TEMPORAL_PAYLOAD=2740, BLOCK_MARKER=146, COMMITMENT=325}
DOMAIN_COUNTS {ACCOUNT=292, ACCOUNT_ASSET=3, ASSET_ISSUE=2, ABI=3, CODE=3,
CONTRACT=3, DELEGATION=189, DELEGATED_RESOURCE=2, DYNAMIC_PROPERTIES=1667,
CONTRACT_STORAGE=103, VOTES=2, WITNESS=146}
OFFLINE_PROBE_OK first=0 last=145 changesets=2415 tombstones=7
```

In schema 6, `COMMITMENT` owns temporal anchor locators. Its nonzero row count is expected even
though the optional `storage.archive.commitment.enable` feature was disabled.

## 3. Normal shutdown and restart

The node was stopped while the canonical head had an unsolidified tail. Shutdown stopped consensus
before dependent services and databases. Before restart, the durable journal contained
`CANONICAL_COMMITTED` tail rows beyond the canonical database's restart-recoverable head.

Startup reconciliation used the recovered canonical head, removed the non-recoverable tail, kept
the first valid archived block, and reopened services with:

```text
repair_required=0
inflight_blocks=0
publisher_lag_blocks=0
ORACLE_REPLAY_OK count=15
```

The same behavior was rechecked after both concurrent-load runs. A normal witness shutdown may
leave one unsolidified `CANONICAL_COMMITTED` journal block; the subsequent startup cleared it before
the offline probe ran.

## 4. Crash and publication windows

A separate temporary jar based on the same commit added test-only process-halt hooks. Its SHA-256
was `b3dfe02be0f151a795ef7fd4ee5078065fff70a8c7e253a4a678fa20129466ee`.
The hooks are not present in the production source tree or official jar.

| Window | Durable state at kill | Recovery result |
|---|---|---|
| After journal append | Block 72 `JOURNALED` | Tail removed; head and oracles retained |
| After canonical session commit | Block 72 still `JOURNALED` | Tail removed; no repair marker |
| After durable journal acknowledgement | Block 72 `CANONICAL_COMMITTED` | Tail reconciled against canonical head |
| Before atomic publication batch | Blocks 72-74 committed, published head 71 | Recoverable block 72 retained/published; later tail removed |
| After atomic publication batch | Published head 73, blocks 74-75 still journaled | Published block 73 retained; later tail removed |
| 100 ms before a block boundary, official jar | No new journal row | Clean recovery |
| 20 ms after a block boundary, official jar | Next block `CANONICAL_COMMITTED` | Clean recovery |

Every kill exited with SIGKILL status 137. Every restart replayed all 15 historical oracles, had
`repair_required=0` and `inflight_blocks=0`, and passed the full offline reference scan. Transaction
numbers remained contiguous on the recovered branch.

## 5. Corruption matrix

The following twelve mutations were applied serially to a clean baseline. Raw logical mutations
opened RocksDB directly and deliberately bypassed archive maintenance APIs and repair-marker writes,
so detection depended on startup validation and full scrub.

1. Archive protocol identity anchor replacement.
2. Active MANIFEST middle-byte corruption.
3. Largest SST middle-byte corruption.
4. Read-only archive database tree.
5. Missing middle block marker.
6. Malformed history value.
7. Malformed latest value.
8. Missing changeset locator.
9. Missing temporal payload.
10. Mutated temporal payload.
11. Orphan temporal payload.
12. Unknown in-flight journal encoding.

Every damaged node exited with status 1 before its HTTP API became ready. After restoring the
baseline at the original identity-bound path, every case replayed all 15 oracles and produced the
same strict probe result:

```text
repair_required=0
inflight_blocks=0
first=0 last=73 changesets=1317 tombstones=7
```

No damaged database served a historical response.

## 6. Real disk faults

### 6.1 Capacity exhaustion

The node ran the complete 20-transaction and 15-oracle scenario with its archive database on a
64 MiB HFS+ disk image. A real zero-filled file then exhausted the volume:

```text
dd: .../fill.bin: No space left on device
63963136 bytes transferred
HEAD_BEFORE_FILL 46
```

At block 47 the durable journal preflight observed only 507,904 free bytes against a 16,777,216-byte
reserve requirement. The node exited fail-stop with status 1 and `ARCHIVE_RUNTIME(1)`; it did not
continue block execution with an unavailable archive.

### 6.2 Device disappearance and I/O failure

The complete scenario ran again with archive data on a 128 MiB mounted image. The image was then
force-detached while the witness remained active. The next archive WAL/meta operation received a
real RocksDB `Input/output error`, followed by `NoSuchFileException` from capacity sampling.

The device was unavailable, so even the repair marker could not be persisted. The fatal watchdog
halted the process with status 70 after its 30-second bound. Repeated attempts to produce the next
block failed; no new block was committed or served.

After remounting the same image at the same identity-bound path, startup full scrub and reconcile
recovered the database, rolled back the unfinalized tail, replayed all 15 oracles, and reported:

```text
HEAD_BEFORE_DETACH 44
FAULT_STATUS 70
RECOVERY RECOVERED
repair_required=0
inflight_blocks=0
OFFLINE_PROBE_OK first=0 last=43 changesets=909 tombstones=7
```

## 7. Concurrent reads, transactions, and blocks

The query driver mixed single historical requests and eight-call JSON-RPC batches. Each successful
sub-call was compared byte-for-byte with one of the 15 fixed oracles. A separate thread submitted,
signed, and waited for one real transfer transaction per produced block. A third thread observed
head progress.

### 7.1 Capacity-matched run

The configured historical JSON-RPC worker count was two, so this run used two clients for 60
seconds:

```text
HTTP requests                 75,866 (1,216.8/s)
Successful oracle sub-calls  209,083
Controlled rejections        0
Wrong results                0
Unexpected RPC errors        0
Transport errors             0
Transactions mined           19, failures 0
Blocks advanced              19
Query p50/p95/p99            0.271 / 6.887 / 9.008 ms
```

### 7.2 Saturated run

This run used 32 clients for 120 seconds, intentionally exceeding the two-worker admission limit:

```text
HTTP requests                 1,206,777 (9,841.5/s)
Successful oracle sub-calls  345,857
Controlled rejections        1,056,402
Wrong results                0
Unexpected RPC errors        0
Transport errors             0
Transactions mined           37, failures 0
Blocks advanced              37
Query p50/p95/p99            3.170 / 4.803 / 7.712 ms
```

Saturated batches were rejected with the explicit JSON-RPC error
`-32005 historical JSON-RPC worker limit reached: limit=2`. Rejected work did not run on the caller
or block thread.

Prometheus deltas during the saturated run showed:

| Operation | Count | Mean |
|---|---:|---:|
| Block generation | 37 | 2.36 ms |
| Manager block push | 37 | 8.42 ms |
| Transaction execution (`trx`) | 37 | 0.39 ms |
| Archive journal | 37 | 4.64 ms |
| Archive publication | 38 | 4.81 ms |
| Completed archive query | 345,887 | 0.51 ms |

The capacity-matched run had comparable means: 2.44 ms block generation, 9.54 ms block push,
0.47 ms transaction execution, 5.46 ms journal, 5.50 ms publication, and 0.52 ms completed archive
query. This provides no evidence that accepted historical reads lengthened transaction or block
execution in this small database.

Observed block-interval P95 was about nine seconds in both runs. Node logs place those gaps exactly
at the configured one-minute maintenance boundary (`:00 -> :09`); actual block-generation work
remained below 75 ms. They were maintenance slots, not archive query lock stalls.

Process samples for the saturated run covered 122 seconds:

```text
RSS min/average/max: 410 / 500 / 584 MiB
CPU average/max:     210% / 356%
```

RSS rose during warmup and dropped after GC to about 466 MiB at the end; it did not grow
monotonically during this short run. This is not a heap/native leak soak.

Both runs ended with `repair_required=0` and `publisher_lag_blocks=0`. Their expected unsolidified
shutdown tail was cleared by a no-witness restart before the final strict offline scan.

## 8. Repository verification

The current source passed the archive-focused tests across `common`, `chainbase`, `actuator`, and
`framework`:

```text
./gradlew :common:test :chainbase:test :actuator:test :framework:test \
  -x generateGitProperties \
  --tests '*Archive*' --tests '*Historical*' \
  --tests '*StructLogReconstructorTest' --tests '*StorageConfigTest'

BUILD SUCCESSFUL in 2m 2s
```

`*StructLogReconstructorTest` matched no test at this commit and must not be counted as coverage;
the command still succeeded because the other selected suites matched tests.

The repository's actual framework checkstyle tasks also passed:

```text
./gradlew :framework:checkstyleMain :framework:checkstyleTest \
  -x generateGitProperties

BUILD SUCCESSFUL in 11s
```

## 9. Harness corrections during the run

Four failures were found in temporary test code and corrected before final results were accepted:

1. A disk-image runner initially omitted `--witness`, so the node served APIs but did not produce
   blocks.
2. The offline probe incorrectly required recovery to preserve an observed, unfinalized scenario
   head instead of the highest block required by the transaction/oracle set.
3. The load driver initially treated a batch-level, null-ID worker-limit response as an unknown-ID
   protocol failure rather than controlled backpressure.
4. The first load script probed offline before restarting to reconcile a valid unsolidified
   `CANONICAL_COMMITTED` tail.

These were harness assumptions, not production-code defects. Final fault and load cases were rerun
after correction.

## 10. Remaining release gates

The following remain outside this local run:

1. Representative mainnet/testnet from-zero synchronization and oracle comparison.
2. A mature large archive database with production compaction, cache, bloom-filter, and disk sizing.
3. A 72-hour or longer sustained transaction/query soak with heap, native memory, RSS, WAL, and
   compaction-latency monitoring.
4. Multi-node network partition, fork, and deep reorg behavior under archive queries.
5. Power-loss and block-device fault injection below the mounted-filesystem/process boundary.
6. Production hardware capacity thresholds and operator alert tuning.

The local implementation can now be described as passing the complete schema-6 single-node E2E and
fault matrix defined for this round. It should not yet be described as production-qualified until
the large-sync and long-soak gates above are complete.
