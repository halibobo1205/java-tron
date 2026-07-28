# Archive schema-7 round-8 E2E results

Date: 2026-07-28

## Scope

This run closes the round-8 exact-artifact evidence gap for archive layout schema 7.
There is no deployed archive database to migrate or preserve, so the primary run started from an
empty data directory and initialized the current schema directly.

Artifact:

- `framework/build/libs/FullNode.jar`
- SHA-256:
  `17d432135475b05e1a26a44c4819260ca3c0fff2a71e9d288009d14ca1ea2b58`
- Eclipse Adoptium OpenJDK `17.0.17+10`, macOS arm64
- Single-SR private chain with archive, HTTP, JSON-RPC, gRPC, and metrics enabled

## Fresh-chain state and trace run

Data directory:
`/private/tmp/java-tron-archive-round8-final3-e2e-20260728/node`

The state scenario submitted 20 transactions and retained 15 independent historical-query
oracles. It covered:

- account creation, TRX transfers, and account mutation;
- freeze, unfreeze, vote, delegate, and undelegate operations;
- TRC10 issue and transfer;
- contract deployment, calls, storage transitions `0 -> 111 -> 222 -> 0`;
- contract code before and after deployment;
- `SELFDESTRUCT` code deletion;
- historical `eth_getBalance`, `eth_getCode`, `eth_getStorageAt`, and `eth_call`;
- HTTP, JSON-RPC, and gRPC availability.

Result:

```text
ORACLE_REPLAY_OK count=15
SCENARIO_OK transactions=20 oracles=15 finalHeight=49
```

The debug trace scenario covered baseline replay, struct logs, callTracer, MSTORE8 memory,
nested CALL, REVERT, invalid opcode, SELFDESTRUCT, non-TVM transaction rejection, missing
transactions, concurrent requests, and controlled admission rejection.

Result:

```text
TRACE_E2E_OK mode=full
```

The concurrent phase admitted 7,507 traces, rejected 89,346 requests through controlled
admission, returned no unexpected responses, mined three foreground transfers, advanced the head
from 71 to 74, and observed maximum trace/oracle latencies of 54 ms / 1 ms.

## Restart and reconciliation

The node was stopped normally and restarted from the same directory without block production.
The un-solidified final candidate was reconciled to canonical block 73. All 15 state oracles and
the retained trace results were then replayed successfully:

```text
ORACLE_REPLAY_OK count=15
TRACE_E2E_OK mode=verify-only
```

Post-restart metrics:

```text
repair_required          0
inflight_records         0
inflight_bytes           0
inflight_resource_bytes  0
oldest_inflight_block   -1
publisher_lag_blocks     0
inflight_blocks          0
```

After shutdown, the strict offline probe checked the tx-number index, startup tail, every
committed marker and digest, history/change-set/latest rows, payload/domain rows, and absence of
in-flight journals:

```text
OFFLINE_PROBE_OK first=0 last=73 nextTxNum=186 inFlight=0
```

## SIGKILL fault injection

The same artifact and fresh-chain data directory were restarted with block production. The
process was killed with `SIGKILL` immediately after the observer detected a head advance from 76
to 80. Startup reconciled the un-solidified tail to canonical block 78. After restart:

- all 15 historical state oracles passed;
- trace verification passed;
- `repair_required` remained 0;
- no in-flight records or bytes remained;
- publisher lag returned to 0;
- the strict offline probe completed with
  `first=0 last=78 nextTxNum=196 inFlight=0`.

This verifies recovery at a real process boundary. It does not simulate arbitrary byte-level
corruption inside an SST or filesystem-level write reordering.

## Regression gates

- `:chainbase:test --tests 'org.tron.core.archive.*' --max-workers=1`: passed.
- Focused actuator and framework archive, historical VM, memory, and JSON-RPC tests: passed.
- `:framework:checkstyleMain` and `:framework:checkstyleTest`: passed.
- `git diff --check`: passed.

## Remaining release evidence

This run validates correctness on a local single-node private chain. Mainnet from-zero sync,
long-duration soak, sustained production query load, and a physical disk-corruption matrix remain
deployment qualification work; this result alone is not a production-release declaration.
