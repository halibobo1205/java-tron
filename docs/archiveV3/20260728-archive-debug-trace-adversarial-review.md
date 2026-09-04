# Archive debug trace V1 adversarial review

## 1. Scope

This review covers the two commits added after `origin/feat/archive-node`:

- `96e24a9685 feat(archive): add historical debug trace v1`
- `9abc80b9a8 feat(archive): complete historical selfdestruct replay`

The authority documents are:

- `docs/archiveV3/00-ARCHIVE-V3-AUTHORITY-AND-DECISIONS.md`, decision 8
- `docs/archiveV3/20260727-archive-debug-trace-v1-plan.md`

The review used six independent lenses:

1. JSON-RPC compatibility, selectors, and fail-closed behavior;
2. VM listener/call-scope lifecycle and canonical hot-path impact;
3. live-versus-historical `SELFDESTRUCT` state semantics;
4. executor concurrency, cancellation, shutdown, and resource ownership;
5. ACCOUNT_ASSET temporal enumeration and snapshot correctness; and
6. test-gate and repository-standard completeness.

Every candidate finding was then checked against the production source, the local Erigon
implementation/test vectors, and the V1 contract. Findings that did not survive that reconciliation
are listed separately instead of being presented as bugs.

## 2. Confirmed findings

### F1 - Medium - `callTracer` omits successful `SELFDESTRUCT`

`Program` creates request-owned call scopes for CALL, CREATE, and precompile execution, but neither
`suicide()` nor `suicide2()` emits a scope. The structured logger still records the `SUICIDE` opcode
and the historical state transition is correct, but the nested call tree silently omits the
destruction.

The expected Erigon/Geth shape is a zero-gas child frame:

- `type=SELFDESTRUCT`;
- `from` is the destroyed contract;
- `to` is the beneficiary;
- `value` is the liquid balance transferred;
- empty input/output; and
- `gas=gasUsed=0`.

Impact is limited to opt-in archive `callTracer` responses. Canonical execution and persisted archive
state are unaffected.

Fix: emit an immediate successful scope after the state operation succeeds, normalize TVM's
`SUICIDE` name to wire-level `SELFDESTRUCT`, and cover normal, restricted, and self-beneficiary
paths.

### F2 - Medium - EIP-1898 `requireCanonical` booleans are rejected

The V1 plan explicitly supports a canonical EIP-1898 `blockHash` selector. The shared selector parser
currently rejects the mere presence of `requireCanonical`, including the only value that exactly
matches archive semantics: `true`.

Fix: accept an omitted field or either Boolean value and reject non-Boolean values. `true` is
satisfied because archive hash resolution is always canonical. `false` is equivalent to omission;
the archive remains canonical-only and does not claim to retain fork state.

### F3 - Medium - `debug_traceCall` silently ignores requested gas

`CallArguments.gas` is not forwarded to historical replay. A request that asks for a low energy limit
can therefore return a successful trace produced with the node's default constant-call limit. This
is a silent execution-semantic mismatch, not merely a wire-format difference.

Fix: parse gas as an optional unsigned JSON-RPC quantity and inject it through
`VMActuator.setConstantCallMaxEnergyLimit`, capped by the node's configured constant-call limit.
This represents zero correctly, prevents a request from raising the operator's limit, and avoids
converting through a fee limit or reading the current energy price.

### F4 - Medium - contract-creation `debug_traceCall` is not connected

A standard call object with no `to` and init code in `input` currently fails as `invalid address`.
The replay engine already supports canonical `CreateSmartContract` transactions, so the missing
piece is the synthetic constant-call transaction and root CREATE trace specification.

Fix: build a historical synthetic `CreateSmartContract`, derive its TRON contract address from the
final synthetic transaction, and execute it with a root CREATE frame. Only an omitted or JSON-null
`to` selects creation; explicit empty strings and `"0x"` remain invalid addresses.

### F5 - Medium - ACCOUNT_ASSET key validation and enumeration disagree

`AccountAssetKeyCodec` accepts any non-empty suffix after the 21-byte address. Historical account
enumeration scans only one through nineteen bytes and then requires a canonical positive decimal
`long`. A 20-byte suffix can therefore pass capture/startup row validation but be skipped by
enumeration.

Normal TRC10 IDs do not trigger this, but accepting data that the only reader cannot enumerate
violates the schema integrity contract and can turn corruption into an incomplete `SELFDESTRUCT`
replay.

Fix: make the codec and reader share one invariant: 1-19 ASCII decimal digits, no leading zero, and a
value in `1..Long.MAX_VALUE`.

### F6 - Medium - TRC10 enumeration materialization bypasses the VM byte budget

The RocksDB scan first materializes key copies, the reader builds a complete `LinkedHashMap`, and
later replay layers may copy that map. Backend-read count bounds the number of rows, but those Java
allocations are not charged to `maxVmOverlayBytes`.

Fix:

- charge candidate key/list materialization before allocating each returned key;
- charge each retained reader map entry before insertion;
- avoid the adapter's unconditional map copy when no local overlay exists; and
- charge the `InternalTransaction` token snapshot before its defensive copy.

The existing bounded list API is retained. Replacing it with a cross-layer streaming callback would
add substantially more interface and exception-plumbing complexity for little benefit after byte
accounting and the default single-trace concurrency limit.

### F7 - Low - unset or unknown `contractResult` is accepted as a terminal failure

`DEFAULT` is protobuf's unset sentinel, and `UNRECOGNIZED` represents an enum value unknown to this
binary. The replay matcher currently groups either with real non-revert failures, so malformed or
future data can be presented as a valid matching trace.

Fix: reject both before starting VM replay and keep the replay-outcome check defensive.

### F8 - Low - empty enabled memory is serialized as `memory:[]`

With `enableMemory=true`, an opcode before the first memory allocation produces an empty list.
Current Geth/Erigon structured logs omit the optional memory field in that state.

Fix: retain memory snapshots only when at least one word exists. Stack semantics remain unchanged.

### F9 - Low - archive-off request classification and shutdown have avoidable overhead

The disabled executor classifies every JSON-RPC request before observing `enabled=false`, duplicating
the Servlet's scan. Shutdown also waits a full configured interval for each of two pools
sequentially.

Fix: run inline before classification when disabled, and use one monotonic shutdown deadline shared
by both executors.

## 3. Post-fix adversarial pass

Two fresh read-only agents reviewed the completed patch from independent API/VM and
storage/concurrency perspectives.

The API/VM pass found four patch-level boundary issues:

1. requested gas replaced rather than capped the configured constant-call limit;
2. Boolean `requireCanonical:false` was unnecessarily rejected despite being equivalent to an
   omitted field for a canonical-only backend;
3. `contractResult.UNRECOGNIZED` was not rejected with `DEFAULT`; and
4. an explicit empty `to` was interpreted as contract creation.

All four were reproduced from source, fixed, and covered by tests. The storage/concurrency pass
found no surviving defect. It confirmed that scan materialization is request-budgeted, iterator and
worker ownership remain bounded, and no general transaction/block hot path was added.

One interface-contract gap was also closed: `ArchiveStateReader.getAccountAssets` now explicitly
requires an immutable defensive snapshot, matching the production reader and allowing the
repository adapter to avoid an unconditional copy when no local overlay exists.

## 4. Rejected or deferred candidates

### Caller interruption must cancel an admitted worker

Rejected as an unconditional fix. The admitted worker owns mutation of the deferred Servlet
response. Returning the caller while that worker is still settling the response creates a response
reuse race. `Future.cancel(true)` also marks the future complete before the worker has actually
released its snapshot and permit. The current wait-and-restore-interrupt behavior is deliberate and
tested.

Client disconnect does not normally interrupt the Servlet thread, so changing this code would not
solve general disconnect cancellation. A future design would need an explicit transport
cancellation token and a worker-exit acknowledgement, not a local `Future.cancel` patch.

### Queue time must consume the replay deadline

Deferred as a policy change, not classified as a correctness defect. The authority text defines the
deadline as the wall-clock timeout for debug replay; execution begins after the dedicated queue.
Queue depth is bounded (one by default), the active replay has its own finite deadline, and admission
saturation is explicit.

If an end-to-end request SLA is required later, it should be a separate admission/queue deadline
propagated as an absolute monotonic timestamp rather than silently shortening the VM replay budget.

### Remove the extra listener null branch from every opcode

Not changed without benchmark evidence. The branch is hoisted, request-owned, and strongly
predictable. Duplicating the interpreter loop or adding mode-specific operation tables would create
more consensus-sensitive code than the measured risk currently justifies.

## 5. Residual verification gaps

The existing suite has strong component coverage, but these broader gates remain useful after the
confirmed fixes:

- real-VM integration for DELEGATECALL, STATICCALL, CREATE2, and precompiles;
- enabled JSON-RPC wire serialization through `JsonRpcServer`, not only direct support calls;
- canonical hash/finalized/fork mutation selector scenarios through the full transport;
- full `debug_traceTransaction -> reader -> VM` `SELFDESTRUCT` private-chain coverage; and
- boundary matrices for many TRC10 assets, FreezeV1/V2, vote reward, and nested
  `CALL -> SELFDESTRUCT -> parent BALANCE`.

These are test-hardening items, not evidence of an additional production defect.

## 6. Verification

Status: fixes complete; all executed gates pass.

- production compilation:
  `./gradlew :chainbase:compileJava :actuator:compileJava :framework:compileJava
  -x generateGitProperties`;
- chainbase focused tests, followed by all `org.tron.core.archive.*` tests plus
  `AccountStoreArchiveCaptureTest`: 870 tests passed in the broad run;
- actuator `org.tron.core.vm.archive.*` plus `ProgramHistoricalSelfDestructTest`: passed;
- framework focused trace/API tests: passed;
- framework archive/historical/Manager lifecycle/Servlet concurrency broad regression: passed;
- `:framework:checkstyleMain`, `:framework:checkstyleTest`, and repository `lint`: passed; and
- `git diff --check`: passed.

The only failures encountered during verification were stale test fixtures that used non-numeric
placeholder asset IDs and one synthetic-call fixture that had not registered its historical block
header. Both fixtures were corrected; neither represented a production-code regression.
