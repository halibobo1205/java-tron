# Archive debug trace V1

## 1. Scope

The first production-gated debug trace release adds:

- `debug_traceCall` for committed historical block-end state;
- `debug_traceTransaction` for the archived pre-state of a historical TVM transaction;
- the default Geth-style `structLogs` result; and
- the native `callTracer` result.

The API is FullNode-only through the existing archive deployment constraint, disabled by default,
and intended for a trusted debug network rather than a public high-QPS endpoint.

V1 deliberately does not add:

- `debug_traceBlockByNumber`, `debug_traceBlockByHash`, or `debug_traceCallMany`;
- JavaScript/custom tracers;
- state overrides, block overrides, reexec, or trace-time fork overrides;
- persistent opcode traces during block import; or
- tracing against `latest`, `pending`, or `safe`.

`finalized`, an explicit archived block number, and a canonical EIP-1898 `blockHash` selector are
supported for `debug_traceCall`. `debug_traceTransaction` supports canonical archived
`TriggerSmartContract` and `CreateSmartContract` transactions. Other TRON contract types do not
have an EVM/TVM opcode execution and fail explicitly instead of returning a misleading empty trace.

## 2. Compatibility contract

The wire shapes follow Geth/Erigon:

- default trace: `gas`, `failed`, `returnValue`, and `structLogs`;
- struct log: `pc`, `op`, `gas`, `gasCost`, `depth`, and the enabled stack/memory/storage/return-data
  fields;
- `callTracer`: nested `type`, `from`, `to`, `gas`, `gasUsed`, `value`, `input`, `output`, `error`,
  `revertReason`, and `calls`.

TRON-specific execution remains visible:

- gas fields contain TVM energy;
- TRON opcodes such as `CALLTOKEN` retain their TVM opcode name; and
- JSON-RPC addresses use the existing 20-byte Ethereum-compatible rendering of TRON addresses.

Legacy struct-log hexadecimal values use the current Geth/Erigon `0x`-prefixed encoding.
`storage` is emitted on `SLOAD`/`SSTORE` entries and contains the cumulative touched slots for that
contract. Empty optional `callTracer` output is omitted, while the mandatory top-level
`returnValue` is `0x`.

Supported default-logger options are `enableMemory`, `disableStack`, `disableStorage`,
`enableReturnData`, and `limit`. In V1, `limit` caps the number of returned struct-log entries;
`maxTraceBytes` independently caps encoded trace data. Supported `callTracer` options are
`onlyTopCall`,
`includePrecompiles`, and `withLog=false`. Unknown options, unsupported tracers, and
`withLog=true` fail as invalid params.

## 3. Accuracy design

V1 does not resurrect the removed delta reconstructor. A merged delta stream cannot faithfully
reconstruct nested call stacks because child and parent stack mutations occupy different VM
frames.

Instead, a request-owned structured listener snapshots the actual pre-op program state:

1. `VM.play` retains its existing one hoisted trace boolean and one branch per opcode.
2. A historical trace explicitly installs a listener on the root `Program`.
3. Nested `Program` instances inherit that listener directly from their parent.
4. The listener snapshots stack and optional memory before the opcode, and tracks cumulative
   `SLOAD`/pending-`SSTORE` slots per contract like the native structured logger.
5. Gas cost is the fully calculated opcode charge supplied immediately before TVM spends it. A
   fault that occurs before cost calculation retains `gasCost=0`; it is not inferred from a later
   all-energy burn.

`callTracer` uses request-owned call scopes around TVM CALL/CREATE/precompile execution. It does not
derive a call tree from persisted internal-transaction records.

No trace listener, scope, response object, or trace budget is reachable from canonical block
execution unless a debug request explicitly injects it.

`debug_traceTransaction` executes with the transaction's original fee limit and historical
FreezeV2 energy accounting. Its broad terminal class (`SUCCESS`, `REVERT`, or non-revert failure)
must match the result stored in the canonical transaction, otherwise the request fails closed.

## 4. Resource and concurrency design

Configuration:

```hocon
storage.archive.debug {
  enable = false
  maxConcurrentTraces = 1
  maxPendingTraces = 1
  maxTraceSteps = 250000
  maxTraceBytes = 16777216
}
```

`enable` is both the RPC exposure switch and the capture switch for the exact transaction TVM
pre-state coordinate. It must be enabled before synchronizing blocks that need
`debug_traceTransaction`. Blocks synchronized while it is disabled remain valid for ordinary
archive queries, but transaction tracing fails closed because their intermediate VM pre-state was
not captured.

The existing finite archive query deadline remains the wall-clock timeout for debug replay. It
replaces the canonical transaction CPU deadline only for an explicitly injected debug trace, so
trace collection overhead cannot create a false `OUT_OF_TIME`. Ordinary historical `eth_call` and
canonical block execution retain their existing timeout behavior.

Debug requests run on a dedicated low-priority executor. The default allows one executing trace and
one queued trace. They also pass through normal archive query admission, snapshot, VM-step,
VM-overlay, backend-read, response-byte, and batch limits. The effective step/byte limit is the
stricter debug or query limit.

The trace materializer reserves an estimated response budget before copying stack/memory/storage
payloads. Final JSON serialization remains bounded by both the archive response budget and the
servlet response ceiling.

## 5. Failure contract

- Disabled debug tracing returns JSON-RPC method-not-found.
- Missing/noncanonical/unarchived selectors fail closed; they never fall back to live state.
- A fork during replay invalidates the response through the existing reader epoch validator.
- Saturation, step, byte, deadline, and snapshot failures use the historical-query limit surface.
- VM reverts remain successful trace responses with `failed=true`; archive corruption or unsupported
  historical state remains an RPC failure.
- A transaction recorded as `OUT_OF_TIME` is rejected because its wall-clock stop point cannot be
  replayed deterministically.
- A replay result that disagrees with the canonical transaction result fails closed.
- Trace collection failures are request-local and cannot mark canonical state or archive
  publication successful.

## 6. Verification gates

Before merge:

1. archive-off and debug-off RPC behavior;
2. config key/default/bounds validation;
3. dedicated worker saturation and shutdown;
4. nested struct-log stack/memory/SLOAD/SSTORE/depth/precomputed-gas ordering;
5. nested CALL/DELEGATECALL/STATICCALL/CREATE/CREATE2/precompile call frames;
6. historical call and transaction selector/canonicality tests;
7. revert, invalid opcode, OUT_OF_TIME, replay-mismatch, deadline, step, byte, and response-limit
   failures;
8. no trace allocation for ordinary historical `eth_call`;
9. canonical VM trace regression; and
10. archive/framework focused tests plus checkstyle.
