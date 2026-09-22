# Archive Empty-Code Historical Compatibility

Date: 2026-09-21

## Scope

Only `ArchiveRepositoryAdapter` changes in production. Canonical `Value`,
`RepositoryImpl`, `Program`, `VM`, `VMActuator`, block execution, archive capture,
publication, and the on-disk format remain unchanged.

This fixes historical execution fidelity. It does not establish the cause of a
live node's stalled synchronization. A VM exception log alone is insufficient to
distinguish a correctly reproduced failed transaction from a rejected block.

## Confirmed Difference

Canonical `Value.create(byte[], type)` normalizes empty bytes to null. Before
`allowMultiSign`, its type remains null; after activation, its type is UNKNOWN.
The proposal is evaluated when the Value is created, not when it is committed.

Canonical code-cache commit checks the type after merging accounts and before
merging contracts. Before the proposal, empty code therefore throws at commit.
After the proposal, UNKNOWN is neither DIRTY nor CREATE and is skipped.

The archive adapter previously stored raw byte arrays, merged empty code as a
normal write, and skipped all root-commit checks. Differential tests reproduced
an internal CREATE/SUICIDE reporting success instead of the canonical exception,
and a nested CALL consuming a different amount of energy.

## Change

- Reuse canonical `Value<byte[]>` for the request-local code cache.
- Cache positive reads as NORMAL and writes as CREATE. Missing reads stay uncached.
- Preserve the cached Value when merging children; do not re-evaluate the proposal.
- Keep null map entries as explicit deletion tombstones, separate from empty code.
- Perform code-type checks for root commits as well, without writing live stores.
- Preserve account-before-code-before-contract child commit order.
- Charge code caching and child merge references to the request's overlay budget.
- Do not record the compatibility NPE as an archive terminal failure: ordinary VM
  failure may be caught by a surrounding CALL. Archive read/limit failures retain
  their existing fail-closed handling.

An internal CREATE commit failure is normalized by VM to `Unknown Exception`.
A top-level deployment can instead reach root commit and be normalized by
VMActuator to `Unknown Throwable`. Both map to UNKNOWN, but their failure timing
must not be conflated. Original root commit can write accounts before failing on
code; the archive equivalent retains only request-local data and persists nothing.

## Verification

`ArchiveEmptyCodeConformanceTest` compares against unmodified RepositoryImpl:

- Null and empty writes, both proposal states, root and child commit.
- Account merge before failure, no subsequent contract merge.
- Empty positive reads versus missing reads.
- Proposal changes after Value construction.
- Read-only and UNKNOWN entries not overwriting parent code.
- Non-empty overwrite, deletion tombstones, overlay budget failure isolation.
- Real VM CREATE/SUICIDE and CALL/CREATE/SUICIDE, comparing exceptions, output,
  energy and internal transaction rejection flags.

`HistoricalEmptyCodeTransactionTest` uses the real historical debug executor and
VMActuator in non-constant mode. It compares deployment, internal CREATE and
nested CALL against canonical RepositoryImpl and verifies live-store isolation.

Validation on JDK 25 / arm64:

- Actuator archive tests: 67 passed, zero failures/errors/skips.
- Framework targeted tests: 135 passed, zero failures/errors/skips, retries disabled.
  Includes historical execution, RPC routing, trace rendering, query-failure
  isolation, and the original `CreateContractSuicideTest`.
- Checkstyle passed for the four changed Java files using the repository rules.
- `git diff --check` passed.

Functional regression command (run with the branch's JDK 25 environment):

```sh
CI=true ./gradlew :actuator:test --tests 'org.tron.core.vm.archive.*' \
  :framework:test --tests 'org.tron.core.vm.archive.*' \
  --tests org.tron.common.runtime.vm.CreateContractSuicideTest \
  --tests 'org.tron.core.services.jsonrpc.Archive*' \
  --tests 'org.tron.core.services.jsonrpc.Historical*' \
  --tests 'org.tron.core.services.jsonrpc.TronJsonRpcArchive*' \
  -x generateGitProperties --max-workers=2
```

These are local deterministic fixtures, not a replay of the reported mainnet
block or a new private-chain crash/restart run.

No data migration or rebuild is required by this change. It cannot retroactively
certify existing archive data or prove that a reported synchronization stall is fixed.
