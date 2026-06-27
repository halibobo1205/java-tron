# java-tron Archive：4e80 完整实现总装计划

日期：2026-06-04

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 当前基线：`4e80f8ffa9a2`

Erigon 源码路径：`/Users/boson/GolandProjects/erigon`

主入口：[java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)

逐文件落点：[java-tron Archive：4e80 逐文件实现落点矩阵](./20260604-java-tron-archive-4e80-file-implementation-map.md)

测试与验收：[java-tron Archive：4e80 模块测试与验收计划](./20260604-java-tron-archive-4e80-test-verification-plan.md)

落地执行看板：[java-tron Archive：4e80 分阶段落地执行看板](./20260604-java-tron-archive-4e80-landing-readiness-board.md)

L6 代码级执行包：[java-tron Archive L6：ArchiveStateReader + historical getters 代码级执行包](./20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md)

L7 代码级执行包：[java-tron Archive L7：CommitmentBuilder 代码级执行包](./20260605-java-tron-archive-l7-commitment-builder-4e80-code-plan.md)

本文件不是替代各模块 coding packet，而是把 S1-S14 组装成一个可执行、可验收、可逐步合入的实现计划。目标是让后续编码时每个 patch 都知道自己产出的接口、消费谁的接口、怎样证明没有破坏 java-tron 当前行为。

## 1. 当前事实

以 2026-06-04 本地源码复核为准：

```text
java-tron HEAD = 4e80f8ffa9a2
java-tron source root = /Users/boson/IdeaProjects/java-tron
erigon source root = /Users/boson/GolandProjects/erigon
```

当前 java-tron 已具备的配置结构：

```text
common/src/main/resources/reference.conf
common/src/main/java/org/tron/core/config/args/StorageConfig.java
framework/src/main/java/org/tron/core/config/args/Args.java
common/src/main/java/org/tron/common/parameter/CommonParameter.java
```

当前 archive 实现状态：

```text
Archive sidecar code: not implemented
Archive docs/specs: S1-S14 已按 4e80 细化
Implementation target: default-off, non-consensus, archive sidecar
```

issue #6289 的 P0 外部能力：

```text
eth_getBalance(address, historicalBlock)
eth_getCode(address, historicalBlock)
eth_getStorageAt(address, slot, historicalBlock)
eth_call(args, historicalBlock)
```

P0 明确不做：

```text
no consensus stateRoot
no header accountStateRoot replacement
no fake Ethereum eth_getProof
no silent fallback latest
no full 25+ domain coverage claim
```

## 2. 权威文档层级

编码时按以下优先级看文档：

1. 本文件：跨模块接口、landing order、验收证据。
2. [4e80 逐文件实现落点矩阵](./20260604-java-tron-archive-4e80-file-implementation-map.md)：物理文件、测试文件、review diff 边界。
3. [4e80 分阶段落地执行看板](./20260604-java-tron-archive-4e80-landing-readiness-board.md)：L1-L9 状态、依赖、合入 gate、DONE 证据。
4. [4e80 模块测试与验收计划](./20260604-java-tron-archive-4e80-test-verification-plan.md)：模块级测试 fixture、验收矩阵、失败判据。
5. [4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)：S1-S14 总览。
6. S1-S14 4e80 coding packet：逐 slice 文件级实现细节。
7. 六模块 4e80 source deep dive：java-tron 源码事实。
8. `20260602-*` 旧 PR/spec 文档：历史背景，遇到冲突时不作为当前编码依据。

当前 4e80 coding packet：

| Slice | 文档 |
| --- | --- |
| S1/S2 | [配置/no-op/dbName + Manager lifecycle/txNum](./20260603-java-tron-archive-s1-s2-4e80-coding-packet.md) |
| S3 | [ArchiveDomainRegistry](./20260603-java-tron-archive-s3-domain-registry-4e80-coding-packet.md) |
| S4/S5 | [WriteCollector 与 Storage Semantic Hook](./20260603-java-tron-archive-s4-s5-write-collector-storage-4e80-coding-packet.md) |
| S6/S7 | [ArchiveTemporalStore](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md) |
| S8/S9 | [ArchiveStateReader 与 JSON-RPC Historical Getters](./20260603-java-tron-archive-s8-s9-state-reader-rpc-4e80-coding-packet.md) |
| L6 | [ArchiveStateReader + historical getters 代码级执行包](./20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md) |
| S10/S11 | [CommitmentBuilder](./20260603-java-tron-archive-s10-s11-commitment-builder-4e80-coding-packet.md) |
| L7 | [CommitmentBuilder 代码级执行包](./20260605-java-tron-archive-l7-commitment-builder-4e80-code-plan.md) |
| S12/S13 | [historical eth_call](./20260604-java-tron-archive-s12-s13-historical-eth-call-4e80-coding-packet.md) |
| L8 | [historical eth_call 代码级执行包](./20260605-java-tron-archive-l8-historical-eth-call-4e80-code-plan.md) |
| S14 | [proof/debug API](./20260604-java-tron-archive-s14-proof-debug-api-4e80-coding-packet.md) |
| L9 | [proof/debug API 代码级执行包](./20260605-java-tron-archive-l9-proof-debug-api-4e80-code-plan.md) |

## 3. Final shape

最终实现应长成下面这个链路：

```text
Config
  -> ArchiveServiceFactory
  -> DefaultArchiveService
  -> Manager lifecycle hooks
  -> ArchiveExecutionContext / txNum
  -> ArchiveDomainRegistry
  -> ArchiveWriteCollector
  -> BlockWriteSet
  -> ArchiveTemporalStore
  -> ArchiveStateReader
  -> JSON-RPC historical getters
  -> ArchiveCommitmentBuilder
  -> RootRecord / CommitmentBranch / RebuildVerifier
  -> ArchiveRepositoryAdapter
  -> historical eth_call
  -> archive-native proof/debug API
```

Hot path rules:

- Store hooks collect write events only; they do not write archive DB.
- Archive temporal/root flush happens only after canonical revoking session commit succeeds.
- Failure before canonical commit calls archive abort and leaves no committed archive rows.
- Unwind uses old canonical head and rewinds archive to parent.
- Historical read paths never infer history from latest Store.

## 4. Cross-module contracts

### 4.1 ArchiveConfig contract

Producer: S1.

Consumer: all slices.

Runtime path:

```text
reference.conf
  -> StorageConfig.ArchiveConfig
  -> Args.applyStorageConfig
  -> CommonParameter.storage.archive
  -> ArchiveServiceFactory
```

Required fields:

```text
enable=false
path/database settings
strictMode=true
history.enable=false
commitment.enable=false
commitment.persistTxRoots=false
debug.enable=false
debug.exposeJsonRpc=false
debug.proofEnable=false
limits...
```

Invariant:

```text
All archive behavior is disabled unless storage.archive.enable=true.
Default config must not alter existing node behavior.
```

Evidence:

- Config bean default test.
- Override config test.
- `Args` bridge test proving `CommonParameter.storage.archive.enable=false` by default.
- Existing latest JSON-RPC and block apply regression tests pass with archive disabled.

### 4.2 ArchiveExecutionContext contract

Producer: S2.

Consumer: S4/S5 write collector, S6/S7 temporal store, S10/S11 root builder.

Minimal fields:

```text
blockNum
blockHash
phase = BLOCK_PREPARE | USER_TX | BLOCK_FINALIZE | UNWIND
txIndex
txNum
source = NORMAL | REPLAY | RECOVERY | UNWIND
```

Rules:

- Every canonical block has at least prepare and finalize txNum.
- Every user transaction gets a deterministic txNum in block transaction order.
- System/finalize writes get their own txNum after user transactions.
- Context is cleared after commit/abort.
- Pending transaction validation and constant call do not get archive txNum.
- Canonical `pushBlock/applyBlock` keeps the outer block revoking session as the commit boundary.
- The producer path's per-transaction nested `ISession` pattern may be borrowed for tx-scoped archive checkpoints, but push/apply must not skip failed transactions.

Evidence:

- Manager lifecycle test with multi-tx block.
- Empty block test.
- Apply failure test.
- Fork replay/recovery continuity test.
- Unwind test.
- Tx-scoped checkpoint test proving failed canonical tx aborts the block and clears only pending archive writes.

### 4.3 DomainDescriptor contract

Producer: S3.

Consumer: S4/S5 collector, S6/S7 codecs, S8 reader, S10 root builder, S14 proof.

Required descriptor fields:

```text
domainId
domainName
dbName
keyCodecId
valueCodecId
historyPolicy
rootPolicy
hookPolicy
coverage
```

P0 rooted domains:

```text
ACCOUNT
CONTRACT
CODE
CONTRACT_STORAGE
DYNAMIC_PROPERTIES allowlist
```

Rules:

- Domain id 0 reserved for global root.
- Unknown DB default is ignored, not archived by accident.
- `CONTRACT_STORAGE` is semantic-only; raw `storage-row` physical key is excluded.
- Domain registry checksum is part of root/proof metadata.

Evidence:

- Registry descriptor round-trip tests.
- Store inventory test over `ChainBaseManager`.
- Unknown DB ignored test.
- Registry checksum determinism test.

### 4.4 WriteCollector contract

Producer: S4/S5.

Consumer: S6/S7 temporal store, S10/S11 commitment builder.

Input events:

```text
rawPut(domain, rawKey, before, after)
rawDelete(domain, rawKey, before)
semanticPut(domain, logicalKey, before, after)
semanticDelete(domain, logicalKey, before)
```

Output:

```text
BlockWriteSet {
  blockNum
  blockHash
  txNumRange
  List<TxWriteSet>
}

TxWriteSet {
  txNum
  phase
  List<DomainWrite>
}

DomainWrite {
  domainId
  canonicalKey
  firstBefore
  finalAfter
  operation = PUT | DELETE
}
```

Rules:

- For same `(txNum, domain, key)`, keep first-before and final-after.
- Store hooks do not allocate txNum; they require current context.
- Store hook no-op when archive disabled or no active context.
- Retry/failure discards pending collector buffer.
- `beginTx/endTx/abortTx` form the archive write-set boundary for each canonical tx; this mirrors producer nested session scoping without inheriting producer "skip candidate tx" behavior.

Evidence:

- Deterministic compression tests.
- Put/update/delete/tombstone tests.
- Store-specific hook tests for contract/code stores.
- Storage semantic hook tests.
- Tx-scoped collector checkpoint tests, including canonical tx failure and VM retry.

### 4.5 TemporalStore contract

Producer: S6/S7.

Consumer: S8/S9 reader, S10/S11 root builder, S12/S13 VM call, S14 proof.

Logical tables:

```text
LATEST
HISTORY
CHANGESET
TXNUM_BLOCK
BLOCK_TXNUM
PROGRESS
ROOT_RECORD
COMMITMENT_BRANCH
COMMITMENT_META
```

Rules:

- Single physical archive DB; logical tables via key prefixes.
- Temporal rows and progress are committed in one archive batch.
- `getAsOf(domain,key,txNum)` is defined only for covered archive range.
- Missing, zero, and tombstone remain distinguishable at reader/proof layer.
- Startup verifier must not let archive progress exceed canonical head.

Evidence:

- Key codec order tests.
- Batch atomicity tests.
- `getAsOf` put/update/delete tests.
- Progress/corruption startup tests.
- Unwind latest/history/change tests.

### 4.6 StateReader contract

Producer: S8.

Consumer: S9 JSON-RPC getters, S12/S13 historical eth_call, S14 proof value lookup.

API shape:

```text
getAccount(point,address) -> ArchiveValue<Account>
getCode(point,address) -> ArchiveValue<byte[]>
getStorage(point,contract,slot) -> ArchiveValue<byte[]>
getDynamicProperty(point,key) -> ArchiveValue<byte[]>
```

Rules:

- Reader input is `ArchiveStatePoint`, not loose block tags.
- Reader never reads latest Store to fill gaps.
- Reader preserves object existence separately from JSON-RPC rendering.
- JSON-RPC rendering may map missing to Ethereum-compatible zero/empty values.

Evidence:

- Fake latest Store differs from archive history, reader returns archive.
- Missing-vs-zero tests.
- Historical block/hash/txNum state point tests.

### 4.7 Commitment contract

Producer: S10/S11.

Consumer: S14 proof and debug root; also rebuild verifier.

Root metadata:

```text
rootScope = ARCHIVE_SIDECAR
consensusParticipation = NONE
algorithmId
registryChecksum
coverage
blockNum
blockHash
asOfTxNum
globalRoot
domainRoots
```

Rules:

- Root writes are sidecar only.
- No write to `BlockHeader.raw.accountStateRoot`.
- Root builder consumes `BlockWriteSet`, not latest Store scans, on hot path.
- Updates sorted by hashed path.
- Root record and temporal rows use same archive batch.
- Content-addressed branch nodes are immutable in P0; no root node GC.

Evidence:

- Root determinism tests.
- Rebuild verifier test from archive `LATEST`.
- Header root regression test.
- Hot unwind root current/progress rollback tests.

### 4.8 JSON-RPC historical contract

Producer: S9 and S13/S14.

Consumer: external users.

Methods:

```text
eth_getBalance(address, block)
eth_getCode(address, block)
eth_getStorageAt(address, slot, block)
eth_call(args, block)
debug_getArchiveRoot(request)
debug_getArchiveProof(request)
debug_verifyArchiveProof(request)
```

Rules:

- Latest selectors preserve existing latest path.
- Non-latest supported methods use archive reader/executor.
- Unsupported historical methods return explicit unsupported, not latest fallback.
- `eth_getProof` remains absent.
- S14 debug methods are default off and FullNode-only unless explicitly configured otherwise.

Evidence:

- JSON-RPC integration tests with latest state intentionally different from historical state.
- Method-not-found tests for default-off debug API and `eth_getProof`.
- Historical `eth_call` test proving `Wallet.triggerConstantContract` is not called.

## 5. Landing sequence

### L0：baseline guard

Do first in every implementation branch.

Commands:

```bash
git -C /Users/boson/IdeaProjects/java-tron rev-parse --short=12 HEAD
git -C /Users/boson/IdeaProjects/java-tron status --short
rg -n '^(<<<<<<< .+|=======$|>>>>>>> .+)' /Users/boson/IdeaProjects/java-tron
```

Stop only when:

```text
HEAD == 4e80f8ffa9a2 or intentionally rebased with docs refreshed
status clean or understood
no conflict markers
```

### L1：config/no-op/dbName

Includes S1.

Outputs:

```text
ArchiveConfig
ArchiveService interface
NoopArchiveService
TronStoreWithRevoking.getDbName fixed
```

Do not include:

```text
Manager txNum
Store hooks
Archive DB
JSON-RPC behavior
```

Gate:

```bash
./gradlew :common:test --tests '*StorageConfig*Test'
./gradlew :chainbase:test --tests '*ArchiveService*Test'
./gradlew :framework:test --tests '*TronStoreWithRevoking*Test'
```

### L2：Manager lifecycle and txNum

Includes S2.

Outputs:

```text
ArchiveExecutionContext
ArchiveTxNumIndex interface
InMemoryArchiveTxNumIndex for tests
Manager begin/commit/abort/unwind hooks
```

Do not include:

```text
Write collection
Archive persistence
Root building
```

Gate:

```bash
./gradlew :framework:test --tests '*ArchiveTxNum*Test'
./gradlew :framework:test --tests '*Manager*Archive*Test'
```

### L3：domain registry

Includes S3.

Outputs:

```text
ArchiveDomainRegistry
ArchiveDomainDescriptor
Domain codecs
Registry checksum
P0 domain inventory
```

Gate:

```bash
./gradlew :chainbase:test --tests '*ArchiveDomainRegistry*Test'
```

### L4：write collection

Includes S4/S5.

Outputs:

```text
ArchiveWriteCollector
BlockWriteSet
generic store hook
store-specific hook
contract storage semantic hook
```

Do not include:

```text
Archive DB persistence
JSON-RPC historical reads
```

Gate:

```bash
./gradlew :chainbase:test --tests '*ArchiveWriteCollector*Test'
./gradlew :actuator:test --tests '*Storage*Archive*Test'
./gradlew :framework:test --tests '*ArchiveStoreHook*Test'
```

### L5：temporal persistence

Includes S6/S7.

Outputs:

```text
ArchiveRawStore
ArchiveBatch
ArchiveTable / key codecs
ArchiveTemporalStore
PersistentArchiveTxNumIndex
ArchiveStartupVerifier
DefaultArchiveService persistence wiring
```

Gate:

```bash
./gradlew :chainbase:test --tests '*ArchiveRawStore*Test'
./gradlew :chainbase:test --tests '*ArchiveTemporalStore*Test'
./gradlew :chainbase:test --tests '*ArchiveStartupVerifier*Test'
./gradlew :framework:test --tests '*ArchiveReorg*Test'
```

### L6：reader and three historical getters

Includes S8/S9.

代码级执行包：[java-tron Archive L6：ArchiveStateReader + historical getters 代码级执行包](./20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md)

Outputs:

```text
ArchiveStateReader
ArchiveStatePointResolver
ArchiveJsonRpcStateAdapter
eth_getBalance historical path
eth_getCode historical path
eth_getStorageAt historical path
```

Gate:

```bash
./gradlew :chainbase:test --tests '*ArchiveStorageKeyCodecTest'
./gradlew :chainbase:test --tests '*DefaultArchiveStateReaderTest'
./gradlew :framework:test --tests '*JsonRpcArchiveStatePointResolverTest'
./gradlew :framework:test --tests '*ArchiveJsonRpcStateAdapterTest'
./gradlew :framework:test --tests '*TronJsonRpcHistoricalGettersTest'
```

Acceptance:

```text
issue #6289 has 3 of 4 P0 historical JSON-RPC APIs working.
```

### L7：commitment builder and root verifier

Includes S10/S11.

代码级执行包：[java-tron Archive L7：CommitmentBuilder 代码级执行包](./20260605-java-tron-archive-l7-commitment-builder-4e80-code-plan.md)

Outputs:

```text
ArchiveCommitmentAlgorithm
CommitmentHash
SparseMerkleArchiveCommitmentTree
RootRecord codecs
Commitment branch node store
Root rebuild verifier
DefaultArchiveService root wiring
ArchiveTxRootComputer
ArchiveRootReader
```

Gate:

```bash
./gradlew :chainbase:test --tests '*ArchiveCommitment*Test'
./gradlew :chainbase:test --tests '*ArchiveRoot*Test'
./gradlew :chainbase:test --tests '*ArchiveTxRootComputerTest'
./gradlew :framework:test --tests '*BlockResult*Archive*Test'
```

Acceptance:

```text
Sidecar root is reproducible.
Transaction-level root is available through on-demand changeset replay.
Header stateRoot is unchanged.
```

### L8：historical eth_call

Includes S12/S13. 代码级执行包：[java-tron Archive L8：historical eth_call 代码级执行包](./20260605-java-tron-archive-l8-historical-eth-call-4e80-code-plan.md)。

Outputs:

```text
VmDynamicProperties
VMConfig scope/snapshot/restore
ArchiveRepositoryAdapter
ArchiveRepositoryChild overlay
HistoricalVmDynamicProperties
HistoricalConstantCallExecutor
eth_call historical branch
```

Gate:

```bash
./gradlew :actuator:test --tests '*ArchiveRepository*Test'
./gradlew :chainbase:test --tests '*HistoricalVmDynamicPropertiesTest'
./gradlew :common:test --tests '*VmConfigScopeTest'
./gradlew :actuator:test --tests '*HistoricalConstantCallExecutor*Test'
./gradlew :framework:test --tests '*EthCallArchive*Test'
```

Acceptance:

```text
issue #6289 has all 4 P0 historical JSON-RPC APIs working.
```

### L9：proof/debug API

Includes S14；代码级执行包：[java-tron Archive L9：proof/debug API 代码级执行包](./20260605-java-tron-archive-l9-proof-debug-api-4e80-code-plan.md)。

Outputs:

```text
ArchiveProofService
ArchiveRootReader
ArchiveDomainProofBuilder
ArchiveGlobalProofBuilder
ArchiveProofVerifier
ArchiveProofLimitChecker
ArchiveDebugFacade
ArchiveDebugAccessGuard
ArchiveProofJsonAdapter
debug_getArchiveRoot
debug_getArchiveProof
debug_verifyArchiveProof
```

Gate:

```bash
./gradlew :common:test --tests '*StorageConfigArchiveDebugTest'
./gradlew :chainbase:test --tests '*ArchiveRootResultTest'
./gradlew :chainbase:test --tests '*ArchiveProofTargetResolverTest'
./gradlew :chainbase:test --tests '*ArchiveDomainProofBuilderTest'
./gradlew :chainbase:test --tests '*ArchiveGlobalProofBuilderTest'
./gradlew :chainbase:test --tests '*ArchiveProofVerifierTest'
./gradlew :chainbase:test --tests '*ArchiveProofLimitCheckerTest'
./gradlew :framework:test --tests '*TronJsonRpcArchiveDebugTest'
./gradlew :framework:test --tests '*BlockResultArchiveRootRegressionTest'
```

Acceptance:

```text
Archive proof is explicitly sidecar/debug.
eth_getProof remains unimplemented.
debug_traceCall remains unimplemented.
```

## 6. Global verification gates

After every Java code landing:

```bash
./gradlew checkstyleMain checkstyleTest
```

Before opening or updating a PR:

```bash
./gradlew build
```

For archive milestone verification:

```bash
./gradlew :common:test --tests '*Archive*'
./gradlew :chainbase:test --tests '*Archive*'
./gradlew :framework:test --tests '*Archive*'
./gradlew :actuator:test --tests '*Archive*'
```

If test names differ from the plan, use current Gradle module names and concrete test classes. Do not add test skips.

## 7. Completion evidence matrix

| Requirement | Evidence that proves completion |
| --- | --- |
| Archive default off | Config tests + latest block apply/RPC regression |
| No silent latest fallback | Tests where latest state differs from requested historical state |
| txNum covers all phases | Manager lifecycle tests over prepare/user/finalize/replay/unwind |
| Domain mapping centralized | Registry tests and collector code has no scattered domain switch beyond registry |
| Storage semantic key reversible | Contract storage tests using same slot before/after physical key encoding |
| Temporal persistence atomic | Batch failure tests and progress consistency tests |
| Unwind correct | Reorg tests proving latest/history/root/progress return to parent |
| Historical getters work | JSON-RPC integration for balance/code/storage at N and N+1 |
| historical eth_call works | Contract read call returns old storage/code and writes no state |
| Sidecar root reproducible | Rebuild verifier equals committed root |
| Header root untouched | `BlockResult.stateRoot` and `BlockHeader.raw.accountStateRoot` regression |
| Proof/debug bounded and sidecar | S14 proof verifier tests and default-off JSON-RPC tests |
| No fake eth_getProof | Method-not-found test for `eth_getProof` |
| No debug_traceCall in S14 | Method-not-found test and no `vm_trace` file creation |

## 8. Non-negotiable invariants

```text
Archive disabled means no behavior change.
Archive root is ARCHIVE_SIDECAR and consensusParticipation=NONE.
Historical reads never fill gaps from latest Store.
Store hooks never write archive DB directly.
Commitment consumes deterministic BlockWriteSet.
Root/proof verifier runs before returning debug proof.
Missing, zero, and tombstone are distinct below JSON-RPC rendering.
```

## 9. Known open decisions

These can be decided during implementation, but must be explicit before declaring completion:

| Decision | Default in current plan |
| --- | --- |
| Full domain coverage | P0 only rooted domains from S3; response carries coverage |
| every-tx root persistence | default off; tx root on-demand bounded |
| archive DB physical path | under storage archive config, single physical DB |
| repair mode | fail-fast strict mode first; repair tooling later |
| `eth_getTransactionCount` | out of P0 |
| `eth_getProof` | out of P0 |
| `debug_traceCall` | out of S14; future per-call trace design |

## 10. Implementation readiness checklist

Before writing code:

- [ ] Confirm java-tron current branch and intended target branch.
- [ ] Re-run baseline guard.
- [ ] Decide first landing slice, preferably L1.
- [ ] Create tests first for default-off behavior.
- [ ] Keep each landing slice independently reviewable.
- [ ] Do not mix JSON-RPC behavior into storage/root slices before their gates pass.
- [ ] Do not add test skips.

Before calling the whole objective complete:

- [ ] All L1-L9 outputs exist in java-tron source, not only docs.
- [ ] Completion evidence matrix has direct test or source evidence for every row.
- [ ] `./gradlew build` passes.
- [ ] checkstyle passes.
- [ ] Archive disabled regression is proven.
- [ ] P0 historical APIs are proven.
- [ ] Root/proof semantics are explicitly sidecar and non-consensus.
