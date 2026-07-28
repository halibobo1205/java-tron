# Archive debug trace V1 round-2 adversarial repair plan

## 1. Scope and invariants

This round reviews the two commits after `origin/feat/archive-node` together with the uncommitted
round-1 fixes:

- `96e24a9685 feat(archive): add historical debug trace v1`
- `9abc80b9a8 feat(archive): complete historical selfdestruct replay`

The repair must preserve these non-negotiable properties:

1. archive-off execution remains byte-identical and does not allocate trace state;
2. canonical transaction and block execution never share request-owned mutable trace state;
3. historical replay remains non-constant where transaction semantics require it;
4. malformed or unsupported historical data fails closed instead of returning a partial trace;
5. debug RPC work remains bounded by the existing worker, deadline, and memory limits; and
6. wire output follows the supported Erigon/Geth tracer contract where that contract is explicit.

## 2. Confirmed findings

### R2-F1 - High - historical transaction replay starts before canonical transaction precharges

The archive currently assigns one `USER_TX` txNum to the entire transaction. Bandwidth consumption,
multi-sign fees, and memo fees mutate state after that position is opened but before
`TransactionTrace.exec()` enters the TVM. `openTransactionReader()` returns `txNum - 1`, while the
historical executor invokes `VMActuator` directly and does not replay those precharges.

A contract that reads the caller balance, or whose energy limit depends on that balance, can
therefore produce a successful replay with silently incorrect struct logs, call values, or energy.
The existing broad SUCCESS/REVERT/FAILED comparison does not detect a successful-but-different
execution.

Repair: when debug trace capture is enabled, append a `USER_TX_VM` position immediately after the
canonical precharges and before `TransactionTrace.init()/exec()`. The txId index resolves traceable
transactions to that position, so `txNum - 1` is the exact TVM pre-state. Keep the ordinary
`USER_TX` position as the block/index coordinate. Transactions without TVM execution do not receive
the extra position.

This changes the unified index shape and therefore requires a layout-schema bump. There is no
legacy-data compatibility requirement for this unreleased implementation. If debug capture was
disabled for an older position, transaction tracing fails closed rather than approximating the
pre-state.

### R2-F2 - High - historical transaction replay shares the canonical jump-destination LRU

`Program.getProgramPrecompile()` only uses an instance-local `ProgramPrecompile` for constant calls.
`debug_traceTransaction` intentionally executes with `constantCall=false`, so it reaches the static
Apache `LRUMap` also used by canonical transaction execution.

The map is not thread-safe, and even `LRUMap.get()` mutates access ordering. A dedicated archive
worker can therefore mutate the same map concurrently with the consensus thread, risking corrupted
LRU links, exceptions, or incorrect jump-destination analysis on the canonical path.

Repair: treat historical archive replay as an isolated VM execution context and use an
instance-local `ProgramPrecompile`. Do not synchronize the global LRU: a lock would put debug work
on the canonical execution path.

### R2-F3 - High - historical transaction replay shares mutable precompile singletons

`PrecompiledContracts.getContractForAddress()` returns static singleton contract objects.
`OperationActions.exeCall()` creates a fresh instance only for constant calls, while historical
transaction replay is deliberately non-constant.

`Program.callToPrecompiledAddressUntraced()` mutates the selected object with the caller,
repository, result, constant-call flag, and deadline. Concurrent debug and canonical execution can
therefore overwrite each other's repository or result reference.

Repair: create a request-owned precompile instance for every isolated VM execution context. Keep
transaction semantics non-constant; only mutable VM helpers are isolated.

### R2-F4 - High - historical FreezeV2 energy calculation differs from the TVM repository

`ArchiveRepositoryAdapter.calculateGlobalEnergyLimit()` uses fractional TRX weight when
`supportUnfreezeDelay=true`. The canonical TVM path in `RepositoryImpl` first truncates frozen
balance to whole-TRX weight. A 1.5 TRX frozen balance can therefore receive 1.5 times the canonical
energy during replay, changing OUT_OF_ENERGY behavior and trace output.

Repair: preserve the exact operation ordering and hardening branch from
`RepositoryImpl.calculateGlobalEnergyLimit()`. Differential tests must use `RepositoryImpl`, not
`EnergyProcessor`, as the oracle.

### R2-F5 - Medium - max-depth CREATE/CREATE2 frames are silently omitted

CALL enters the call-trace scope before its depth failure is resolved, but CREATE and the compatible
CREATE2 path return before a scope is opened when `callDeep == 64`. The opcode remains visible in
`structLogger`, while `callTracer` silently loses the attempted child frame.

Repair: only when a call collector is present, emit the failed CREATE/CREATE2 attempt with the
predicted destination, zero gas used, and `max call depth exceeded`. Preserve the original
untraced control flow and legacy CREATE2 compatibility branch.

### R2-F6 - Medium - standard opcode wire names and fault attachment diverge

The struct logger currently emits TVM's internal `SHA3` and `SUICIDE` names where the supported
Geth/Erigon wire contract uses `KECCAK256` and `SELFDESTRUCT`. It also attaches every terminal
result, including a normal REVERT halt, to the final opcode entry. Upstream loggers only attach an
error when that opcode faults.

Repair: add an archive wire-name mapper that preserves genuinely TRON-specific opcodes, and add a
fault callback from the VM's existing exception path. `onProgramExit` only releases per-frame
tracking. The fault string is charged to the trace byte budget before it is retained.

The documented `gasCost=0` for faults before energy calculation remains unchanged. Changing that
would contradict the V1 authority and add work before canonical stack validation.

### R2-F7 - Low - deterministic child-call failures collapse to `execution failed`

Depth, insufficient-balance, and precompile energy failures can be known before a child VM exists,
but the tracer currently infers all such outcomes from the stack's zero result.

Repair: carry an optional trace-only failure reason through the nested-call result. Do not change
VM exceptions, stack results, or canonical execution behavior.

### R2-F8 - Low - failed CREATE destination and Panic revert decoding differ from Erigon

Failed CREATE/CREATE2 frames currently omit `to`; Erigon serializes the zero address. The collector
also only decodes `Error(string)`, while Erigon decodes Solidity `Panic(uint256)`.

Repair: serialize the zero address for failed CREATE/CREATE2 and add bounded, callTracer-local Panic
decoding. Do not change the shared JSON-RPC revert decoder, whose current behavior is an existing
public contract.

### R2-F9 - Low - first terminal failure, queue rejection, and numeric parsing are imprecise

- A nested archive/budget terminal failure swallowed by the VM can be replaced by a later ordinary
  `RuntimeException`; only a JVM `Error` should outrank the first recorded terminal.
- A bounded trace-queue rejection is classified from a second queue-size sample, so a worker
  dequeue can relabel it as a concurrent-query rejection.
- `limit: 1.0` is accepted because `BigDecimal.longValueExact()` ignores a zero fractional scale;
  the JSON contract requires an integer token.

Repair: restore first-terminal precedence, classify rejection from fixed executor configuration,
and accept only integral Java number types for `limit`.

### R2-F10 - Low - unified latest-key scans read each RocksDB iterator key twice

`UnifiedArchiveTemporalStore.scanLatestCanonicalKeys()` invokes `iterator.key()` in both the loop
condition and body. RocksJNI materializes a byte array for each call.

Repair: cache the physical key once per iterator position. This does not alter ordering, budgeting,
or validation.

### R2-F11 - Low - archive-off Servlet classification performs avoidable work

The Servlet classifies every request before dispatch to decide whether a deferred response is
required, even when historical debug trace is disabled. The executor then observes the disabled
state and runs inline.

Repair: gate deferred classification on the executor's enabled state. Keep the full batch
classifier when enabled so mixed historical batches are still dispatched safely.

## 3. Verification gaps to close

The fixes above need focused regression tests plus these missing release gates:

- block/index lookups remain stable with optional `USER_TX_VM` positions;
- in-memory, journal, unified publication, startup validation, unwind, and txId lookup agree on the
  new position shape;
- a VM transaction with precharge writes opens at the captured VM pre-state, while an archive
  without debug capture fails closed;
- archive debug-off block execution allocates no additional VM position;
- historical and canonical Programs use separate versus shared jump-destination helpers as intended;
- historical replay receives distinct mutable precompile instances;
- historical FreezeV2 limits differentially match `RepositoryImpl` for fractional frozen balances;
- real VM traces cover DELEGATECALL, STATICCALL, CALLCODE, CREATE, CREATE2, and a precompile;
- CREATE depth failure, early child failures, failed CREATE output, and Panic output have exact wire
  assertions;
- enabled `JsonRpcServer` binding and final JSON serialization are exercised, not only direct Java
  calls;
- ordinary historical `eth_call` allocates no struct/call trace collectors;
- legacy global `vmTrace` still records operations outside an archive request;
- archive-off Servlet routing remains inline; and
- unified temporal scan oracle tests continue to pass.

## 4. Explicitly rejected changes

- Do not lock the global jump-destination LRU. That would let an opt-in debug request delay canonical
  transaction execution.
- Do not duplicate or fork the VM interpreter loop to remove predictable null checks.
- Do not replace bounded temporal scans with a new streaming API in this round.
- Do not broaden historical CALLTOKEN support without a separate state-completeness design.
- Do not change AccountStore SHA-256 behavior in this patch; it predates these two commits and is not
  needed to repair trace correctness.
- Do not mix pre-existing generic JSON-RPC notification/null-id behavior into this archive patch.
  Those findings affect all methods and need a separate transport change with its own compatibility
  review.
- Do not replay bandwidth/memo/multi-sign charging approximately from receipts. The receipt does not
  preserve every account/dynamic/asset path selected by `BandwidthProcessor`, so approximation can
  still return a successful but incorrect trace.

## 5. Implementation order

1. Add the debug-gated `USER_TX_VM` coordinate, explicit block/index rows, and unified layout bump.
2. Make transaction readers require that exact VM pre-state.
3. Introduce one Program predicate for request-isolated VM helpers.
4. Repair FreezeV2 replay math and struct/call tracer semantics.
5. Remove the low-risk iterator/classification costs and tighten resource/failure handling.
6. Add focused unit and real-VM/wire integration tests.
7. Run module compilation, focused tests, broad archive regressions, checkstyle, lint, and
   `git diff --check`.

No commit or push is part of this plan unless explicitly requested.

## 6. Implemented repairs

Status: all confirmed findings in section 2 have production fixes and focused regression tests.

### Exact transaction VM pre-state

- Added the optional `USER_TX_VM` phase immediately after bandwidth, multi-sign, and memo charging
  and before `TransactionTrace.init()/exec()`.
- Kept `(blockNum, txIndex)` mapped to `USER_TX`; transaction-id lookup points to
  `USER_TX_VM` when present.
- Added an explicit unified block/index row only for variable-shape blocks containing a VM
  position, because optional positions make arithmetic `firstTxNum + 1 + txIndex` invalid there.
  Fixed-shape debug-off blocks retain arithmetic lookup and do not gain this write amplification.
- Extended the in-memory allocator, journal codec/validator, unified publication, full startup
  scrub, restart recovery, and unwind validation to the optional position shape.
- Bumped the unreleased unified layout schema from 6 to 7. No migration or compatibility path was
  added because this implementation has no deployed data.
- Transaction readers now require `USER_TX_VM`. Blocks captured without it return
  `HISTORY_UNAVAILABLE` with `VM pre-state was not captured`; there is no approximation or live
  fallback.

`storage.archive.debug.enable` deliberately controls both RPC exposure and VM-pre-state capture.
This keeps debug-off synchronization free of the extra position/journal/index rows. It must be
enabled before synchronizing blocks that need `debug_traceTransaction`; this boundary is now
documented in `reference.conf` and the V1 plan.

### VM concurrency and replay fidelity

- Historical Programs use request-local jump-destination analysis while canonical Programs retain
  the existing shared LRU behavior.
- Historical non-constant replay and constant calls both receive request-owned precompile
  instances; transaction semantics remain non-constant.
- Historical global energy calculation now mirrors `RepositoryImpl` exactly, including whole-TRX
  truncation before both hardened and non-hardened formulas.
- First recorded archive/deadline/budget terminal failure wins over ordinary VM runtime failures;
  JVM `Error` remains authoritative and retains the terminal failure as suppressed context.

### Trace shape and resource handling

- CREATE/CREATE2 max-depth attempts and deterministic early child failures are retained in
  `callTracer`.
- Failed CREATE/CREATE2 frames use the zero address, Solidity Panic output is decoded, and
  SELFDESTRUCT uses the standard wire name.
- Struct logs render `KECCAK256` and `SELFDESTRUCT`; only the actual throwing opcode receives an
  error. Normal REVERT no longer annotates its final opcode as faulting.
- Fault text is charged before retention, trace queue rejection uses fixed configured capacity,
  numeric `limit` requires an integer token, unified latest scans read each iterator key once, and
  archive-disabled Servlet execution skips historical classification.

## 7. Added adversarial oracles

The repair adds direct tests for:

- state after canonical precharges and before VM writes, plus debug-capture-disabled fail-closed;
- block/index stability, txId-to-VM lookup, unwind cleanup, and optional-position shape rejection;
- canonical-committed journal restart, full startup validation, publication, and lookup after
  reopening RocksDB;
- distinct historical jump-destination helpers versus shared canonical helpers;
- distinct request-owned precompile instances;
- fractional FreezeV2 balance differential against `RepositoryImpl`;
- CREATE and CREATE2 max-depth failure frames;
- failed CREATE address, SELFDESTRUCT wire name, known/unknown Solidity Panic output;
- opcode wire names, normal REVERT versus fault attachment, and fault-byte budgeting;
- stable pending-query classification under a full debug queue; and
- rejection of `limit: 1.0`.

## 8. Verification

All executed gates pass:

- production and test compilation for `chainbase`, `actuator`, and `framework`;
- focused cross-module repair regressions;
- all `org.tron.core.archive.*` chainbase tests plus account capture tests;
- all actuator historical archive VM tests plus Program SELFDESTRUCT/CREATE depth tests;
- framework archive, Manager lifecycle/fork/restart, historical VM, debug trace, JSON-RPC routing,
  Servlet concurrency/serialization, and `JsonrpcServiceTest` regressions;
- framework main/test Checkstyle and repository `lint`;
- `check_reference_comments.py common/src/main/resources/reference.conf`; and
- `git diff --check`.

The attempted `:common:checkstyleMain`, `:chainbase:checkstyleMain`, and
`:actuator:checkstyleMain` tasks do not exist in this repository. Their Java sources were still
compiled through the module test runs; repository `lint` and the available framework Checkstyle
tasks passed.

## 9. Residual boundaries, not surviving defects

- Historical replay still fails closed when a required archive domain is intentionally unavailable;
  it does not substitute live state.
- Real-VM coverage is strongest for nested CALL and SELFDESTRUCT. A broader
  CALLCODE/DELEGATECALL/STATICCALL/CREATE2/precompile matrix remains useful test hardening, but no
  corresponding production defect survived source reconciliation or the current integration runs.
- Generic JSON-RPC notification/null-id behavior predates archive debug tracing and was not mixed
  into this repair.

## 10. Final post-repair reconciliation

A final source pass rechecked the optional-position layout, transaction selectors, publication,
startup scrub, restart recovery, and archive-off write path after the repairs above. No additional
confirmed defect survived source and test reconciliation.

The final index shape deliberately has two modes:

- fixed-shape blocks use arithmetic `(blockNum, txIndex)` resolution and persist no block-index row;
- blocks containing at least one `USER_TX_VM` position persist explicit block-index rows for their
  `USER_TX` positions.

This preserves exact VM pre-state lookup when debug capture is enabled without adding index writes
to ordinary debug-disabled synchronization. Focused RocksDB tests assert both physical row shapes,
transaction lookup, restart plus full startup validation, and publication after journal recovery.
