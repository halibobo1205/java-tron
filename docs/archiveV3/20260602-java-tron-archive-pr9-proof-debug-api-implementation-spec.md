# java-tron Archive PR9 Proof/Debug API 代码级实现规格

> 2026-06-04 更新：本文是旧 PR9 规格。当前 `4e80f8ffa9a2` 的 S14 编码入口请看 [java-tron Archive S14：proof/debug API 4e80 编码执行包](./20260604-java-tron-archive-s14-proof-debug-api-4e80-coding-packet.md)。当前 S14 首版收敛为 `debug_getArchiveRoot`、`debug_getArchiveProof`、`debug_verifyArchiveProof`，明确不实现 `eth_getProof` 和 `debug_traceCall`。

日期：2026-06-02

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

关联总文档：[java-tron 交易级状态树支持：Erigon V2/V3 模型调研](./20260520-java-tron-archive-state-erigon-v2-v3-research.md)

关联实现蓝图：[java-tron Archive 状态树实现蓝图](./20260602-java-tron-archive-implementation-blueprint.md)

前置规格：

- [java-tron Archive PR6 StateReader/JSON-RPC 代码级实现规格](./20260602-java-tron-archive-pr6-state-reader-jsonrpc-implementation-spec.md)
- [java-tron Archive PR7 CommitmentBuilder 代码级实现规格](./20260602-java-tron-archive-pr7-commitment-builder-implementation-spec.md)
- [java-tron Archive PR8 historical eth_call 代码级实现规格](./20260602-java-tron-archive-pr8-historical-eth-call-implementation-spec.md)

模块 06 逐文件 Patch 清单：[java-tron Archive 模块 06：CommitmentBuilder 逐文件 Patch 清单](./20260602-java-tron-archive-module-06-commitment-builder-patch-checklist.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

## 1. PR9 目标

PR9 在 PR7 的 archive sidecar root 和 PR8 的 historical execution 基础上，增加默认关闭的 archive-native proof/debug 能力。

本 PR 做：

1. 查询 block-end archive root。
2. 按需计算 tx-level root。
3. 生成 domain proof + global domain proof。
4. 验证 archive proof。
5. 暴露受控 debug JSON-RPC 方法。
6. 为 historical `debug_traceCall` 提供默认关闭的 trace capture。
7. 保证所有 proof/root response 都标记 `ARCHIVE_SIDECAR`、`consensusParticipation=NONE`、`coverage`、`algorithmId`、`registryChecksum`。

本 PR 不做：

1. 不实现 Ethereum-compatible `eth_getProof`。
2. 不把 archive root 写入 `BlockHeader.raw.accountStateRoot`。
3. 不让 root/proof 参与共识。
4. 不默认持久化 every-tx root。
5. 不实现 public high-QPS proof 服务。
6. 不实现 root node GC。
7. 不让 `debug_traceCall` 写 `vm_trace/*.json` 文件。

首版 API 定位：

```text
debug / internal / archive-native
不是 consensus state proof
不是 Ethereum MPT proof
```

## 2. 源码事实

### 2.1 issue #6289 对 proof/debug 的定位

issue #6289 的明确 P0 Ethereum-compatible interfaces 是：

```text
eth_getBalance
eth_getCode
eth_getStorageAt
eth_call
```

它把 `debug_traceCall`、`eth_getTransactionCount`、`eth_getProof` 放在 future/discussion 范围；同时说明 TRON 当前 state data 分散在多类 DB 中，`accountStateRoot` 不包含 contract storage/codeHash 这类 Ethereum account trie 字段，stateRoot 不应在 archive branch 首期参与共识。

PR9 因此只做 archive-native proof/debug，不把结果伪装成 Ethereum `eth_getProof`。

### 2.2 JSON-RPC 当前没有 proof/debug 方法

| 文件 | 位置 | 事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java:90` | `eth_getBalance` | 已声明 |
| `TronJsonRpc.java:96` | `eth_getStorageAt` | 已声明 |
| `TronJsonRpc.java:103` | `eth_getCode` | 已声明 |
| `TronJsonRpc.java:154` | `eth_getBlockReceipts` | 已声明 |
| `TronJsonRpc.java:162` | `eth_call` | 已声明 |
| `TronJsonRpc.java:251` | `eth_getTransactionCount` | 当前声明为 method-not-found 路径 |
| 全 `jsonrpc` 包 | `eth_getProof` | 未声明 |
| 全 `jsonrpc` 包 | `debug_traceCall` | 未声明 |

结论：

- PR9 新增 debug 方法不会覆盖已有方法。
- `eth_getProof` 不应在 PR9 中直接加入，因为返回语义不是 Ethereum proof。
- 如果后续要实现 `eth_getProof`，必须先定义 Ethereum-facing account view，包括 nonce/storageRoot/codeHash 的 TRON 映射。

### 2.3 TrieImpl 可参考但不能复用到 chainbase archive 核心

| 文件 | 位置 | 事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/trie/TrieImpl.java:290` | `getRootHash` | framework 中 MPT-like root |
| `TrieImpl.java:355` | `scanTree` | 可扫描节点和值 |
| `TrieImpl.java:381` | `prove(byte[] key)` | 生成 proof node map |
| `TrieImpl.java:490` | `verifyProof` | 验证 proof node map |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/db/accountstate/storetrie/AccountStateStoreTrie.java:19` | `AccountStateStoreTrie` | framework 内 account trie backing store |

模块依赖仍然是：

```text
framework -> chainbase
chainbase -/-> framework
```

PR7 已决定 archive commitment tree 在 `chainbase` 内实现，不 import framework `TrieImpl`。PR9 继续这个边界：

- `TrieImpl.prove/verifyProof` 只作为 proof API 形态参考。
- archive proof builder/verifier 基于 PR7 的 `SparseMerkleTree/CommitmentTree`。
- 如果未来想复用 `TrieImpl`，先做独立 trie package 下沉，不放进 PR9。

### 2.4 现有 VM trace 是全局开关 + 文件输出

| 文件 | 位置 | 事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/common/src/main/resources/reference.conf:757` | `vmTrace=false` | 全局配置 |
| `/Users/boson/IdeaProjects/java-tron/actuator/src/main/java/org/tron/core/vm/config/ConfigLoader.java:19` | `VMConfig.setVmTrace(...)` | 从 `CommonParameter` 写全局 VMConfig |
| `/Users/boson/IdeaProjects/java-tron/actuator/src/main/java/org/tron/core/vm/VM.java:31` | `VMConfig.vmTrace()` | 每 op 保存 trace |
| `/Users/boson/IdeaProjects/java-tron/actuator/src/main/java/org/tron/core/vm/program/Program.java:1610` | `trace.addOp(...)` | 记录 op trace |
| `/Users/boson/IdeaProjects/java-tron/actuator/src/main/java/org/tron/core/vm/trace/ProgramTrace.java:14` | `ProgramTrace` | 内存 trace model |
| `/Users/boson/IdeaProjects/java-tron/actuator/src/main/java/org/tron/core/actuator/VMActuator.java:297` | save trace branch | 执行后保存 trace |
| `/Users/boson/IdeaProjects/java-tron/actuator/src/main/java/org/tron/core/vm/VMUtils.java:57` | `./vm_trace` | trace 输出目录 |
| `VMUtils.java:93` | `saveProgramTraceFile` | 写文件 |

结论：

```text
debug_traceCall 不能直接打开全局 vmTrace 并让 VMActuator 写文件。
```

PR9 需要 per-call trace capture，并把 trace 作为 JSON-RPC result 返回。

### 2.5 TransactionTrace 固定 latest StoreFactory

| 文件 | 位置 | 事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/db/TransactionTrace.java:84` | constructor | 接收 `StoreFactory` |
| `TransactionTrace.java:100` | dynamic store | `storeFactory.getChainBaseManager().getDynamicPropertiesStore()` |
| `TransactionTrace.java:101-104` | latest stores | contract/code/abi/account store 都从 latest manager 取 |
| `TransactionTrace.java:129` | `TransactionContext` | 使用 latest `storeFactory` |
| `TransactionTrace.java:186` | `exec()` | 调 runtime execute |
| `TransactionTrace.java:213` | `finalization()` | pay/delete 等 canonical finalization |

结论：

- historical `debug_traceCall` 不能复用 canonical `TransactionTrace`。
- 应复用 PR8 的 `ArchiveEthCallExecutor` / archive repository overlay。
- trace call 不进入 bandwidth/fee finalization，不写 canonical store。

## 3. 总体实现形态

PR9 分两条线：

```text
Archive proof:
  StatePoint -> root -> domain/global proof -> verify

Archive debug trace:
  CallArguments + StatePoint -> PR8 historical execution -> in-memory ProgramTrace
```

推荐新增：

```text
chainbase archive commitment/proof core
framework JSON-RPC debug adapter
actuator per-call VM trace capture hook
```

不要把 proof/debug 和 PR1-PR5 的写路径耦合。PR9 是读侧和校验侧，默认关闭，不影响 block apply TPS。

## 4. 配置

在 `storage.archive` 下新增：

```hocon
storage {
  archive {
    debug {
      enable = false
      proofEnable = false
      traceCallEnable = false
      maxProofNodes = 1024
      maxTraceOps = 200000
      maxOnDemandReplayTx = 2000
      allowTxRootOnDemand = true
      exposeJsonRpc = false
    }
  }
}
```

规则：

- `archive.enable=false` 时全部 no-op。
- `archive.commitment.enable=false` 时 proof/root API 返回 commitment disabled。
- `debug.exposeJsonRpc=false` 时 JSON-RPC 方法返回 method not found 或 disabled。
- `allowTxRootOnDemand=false` 时未持久化 tx root 返回 unsupported。
- `maxOnDemandReplayTx` 防止从很远 checkpoint/block root replay 太多 tx。
- `maxTraceOps` 防止 traceCall 内存膨胀。

## 5. 包和文件

### 5.1 chainbase 新增

```text
chainbase/src/main/java/org/tron/core/archive/proof/
  ArchiveProofService.java
  DefaultArchiveProofService.java
  ArchiveRootReader.java
  TxRootComputer.java
  ProofBuilder.java
  ProofVerifier.java
  ProofRequest.java
  ProofTarget.java
  ProofKind.java
  RootLookupResult.java
  ArchiveRootResult.java
  ArchiveProof.java
  DomainProof.java
  GlobalProof.java
  ProofNode.java
  ProofVerificationResult.java
  ProofException.java
```

复用 PR7：

```text
CommitmentBuilder
CommitmentTree
SparseMerkleTree
RootRecord
DomainRootRecord
RootKeyCodec
CommitmentNodeCodec
ArchiveRootStore
RootAlgorithmDescriptor
ArchiveBatch
ArchiveRawStore
```

### 5.2 actuator 调整

```text
actuator/src/main/java/org/tron/core/actuator/VMActuator.java
actuator/src/main/java/org/tron/core/vm/config/VMConfig.java
actuator/src/main/java/org/tron/core/vm/program/Program.java
actuator/src/main/java/org/tron/core/vm/trace/ProgramTrace.java
actuator/src/main/java/org/tron/core/vm/trace/TraceCapture.java
actuator/src/main/java/org/tron/core/vm/trace/TraceOptions.java
```

目标：

- per-call trace enable。
- max op guard。
- trace result 返回内存对象。
- 不写 `./vm_trace` 文件。

### 5.3 framework 新增

```text
framework/src/main/java/org/tron/core/services/jsonrpc/archive/
  ArchiveDebugJsonRpcAdapter.java
  ArchiveRootJsonResult.java
  ArchiveProofJsonResult.java
  ArchiveProofVerificationJsonResult.java
  ArchiveTraceCallExecutor.java
  ArchiveTraceCallResult.java
  ArchiveDebugMethodGuard.java
```

调整：

```text
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java
```

## 6. ArchiveProofService

### 6.1 接口

```java
public interface ArchiveProofService {
  RootLookupResult getRoot(StatePoint statePoint, RootLookupOptions options)
      throws ProofException;

  ArchiveProof prove(ProofRequest request)
      throws ProofException;

  ProofVerificationResult verify(ArchiveProof proof)
      throws ProofException;

  ArchiveRootResult computeOnDemandRoot(StatePoint statePoint, RootLookupOptions options)
      throws ProofException;
}
```

`ProofRequest`：

```java
public record ProofRequest(
    StatePoint statePoint,
    ArchiveDomain domain,
    byte[] domainKey,
    ProofTarget target,
    boolean includeValue,
    boolean allowOnDemandRoot) {
}
```

`ProofTarget`：

```text
ACCOUNT(address)
CONTRACT(address)
CODE(address)
CONTRACT_STORAGE(address, slot)
DYNAMIC_PROPERTY(key)
RAW(domain, key)
```

外部 API 不直接拼 raw key。RPC adapter 先通过 `ArchiveDomainRegistry` 把 address/slot/query 编成 domain key。

### 6.2 RootLookupResult

```java
public record RootLookupResult(
    StatePoint statePoint,
    long asOfTxNum,
    RootScope rootScope,
    ConsensusParticipation consensusParticipation,
    Coverage coverage,
    String algorithmId,
    int algorithmVersion,
    byte[] registryChecksum,
    Optional<RootRecord> persistedRoot,
    Optional<ArchiveRootResult> computedRoot,
    RootSource source,
    RootAvailability availability,
    String message) {
}
```

`RootSource`：

```text
PERSISTED_BLOCK
PERSISTED_TX
ON_DEMAND_REPLAY
UNAVAILABLE
```

`RootAvailability`：

```text
OK
COMMITMENT_DISABLED
ROOT_MISSING
TX_ROOT_NOT_PERSISTED
ON_DEMAND_DISABLED
REPLAY_LIMIT_EXCEEDED
ARCHIVE_RANGE_UNAVAILABLE
REPAIR_REQUIRED
```

## 7. Root 查找和 tx-level root

### 7.1 block-end root

`BLOCK_END(blockNum)`：

```text
ROOT_BLOCK(blockNum) -> RootRecord
```

如果不存在：

- commitment 未启用：`COMMITMENT_DISABLED`
- root 启用但缺失：`ROOT_MISSING/REPAIR_REQUIRED`
- 查询超过 progress：`ARCHIVE_RANGE_UNAVAILABLE`

不要向前搜索 parent root 伪装成该 block root。PR7 已规定空块也要写 `ROOT_BLOCK`。

### 7.2 tx root 已持久化

如果 PR7/配置启用了：

```text
storage.archive.commitment.persistTxRoots = true
```

则：

```text
ROOT_TX(txNum) -> RootRecord
```

`TX_BEFORE(tx)`：

```text
root(txNum - 1)
```

`TX_AFTER(tx)`：

```text
root(txNum)
```

注意 `txNum - 1` 可能是 block before root 或前一个 system/user tx root，必须通过 `ArchiveTxNumIndex` 解析，不允许 RPC 层自己减一。

### 7.3 tx root on-demand

默认不持久化 every-tx root。PR9 的 on-demand root 使用：

```text
base root = ROOT_BLOCK(blockNum - 1)
for txNum in block range until target:
  changedKeys = CHANGESET(txNum)
  for each key:
    afterValue = ArchiveStateReader.atTxNum(txNum).getRaw(domain, key)
    normalized = registry.normalizeRootValue(domain, afterValue)
    commitmentTree.update(domain, key, normalized)
```

`TX_BEFORE(txIndex)`：

```text
replay until previous logical tx
```

`TX_AFTER(txIndex)`：

```text
replay through current logical tx
```

如果 block 内包含 `BLOCK_FINALIZE`/system tx：

- `BLOCK_END(block)` 包含 system tx。
- `TX_AFTER(lastUserTx)` 不等于 `BLOCK_END(block)`，除非没有 finalization writes。
- `SYSTEM_AFTER(block, phase)` 可按 txNum replay 到该 phase。

性能限制：

```text
replayTxCount <= storage.archive.debug.maxOnDemandReplayTx
```

超过限制返回 `REPLAY_LIMIT_EXCEEDED`，不做慢查询。

### 7.4 checkpoint

PR9 可以先从 `ROOT_BLOCK(blockNum - 1)` replay 当前 block。跨 block 的 checkpoint root/proof 是后续优化。

如果未来需要跨 block checkpoint：

```text
ROOT_CHECKPOINT(checkpointId) -> root + node roots + blockNum/asOfTxNum
```

PR9 只预留 schema，不要求实现冷数据 checkpoint。

## 8. Proof 生成

### 8.1 proof 组成

完整 archive proof：

```text
ArchiveProof
  root metadata
  statePoint/asOfTxNum
  domainId/domainName
  domainKey
  value or valueHash
  proofKind
  domainRoot
  domainProof
  globalRoot
  globalProof
  registryChecksum
  algorithmDescriptor
  coverage
```

`proofKind`：

```text
EXISTENCE
NON_EXISTENCE_EMPTY_LEAF
NON_EXISTENCE_DOMAIN_EXCLUDED
DOMAIN_ROOT_ONLY
PARTIAL_ROOT
UNSUPPORTED
```

### 8.2 domain proof

Sparse Merkle proof：

```java
public record DomainProof(
    short domainId,
    byte[] domainRoot,
    byte[] commitmentPath,
    byte[] leafHash,
    List<ProofNode> siblings,
    ProofKind proofKind) {
}
```

`ProofNode`：

```java
public record ProofNode(
    int depth,
    byte direction,
    byte[] siblingHash) {
}
```

方向：

```text
0 = current path goes left, sibling is right
1 = current path goes right, sibling is left
```

验证时从 leaf/empty leaf 自底向上重算 domainRoot。

### 8.3 global proof

global tree key：

```text
globalPath = hash("domain" || domainId || registryChecksum)
globalLeaf = hash("domainRoot" || domainId || domainRoot || domainDescriptorHash)
```

`GlobalProof` 证明 domainRoot 被纳入 globalRoot。

如果 domain `RootPolicy=DOMAIN_ROOT_ONLY`：

- 可以返回 domain proof。
- 不能返回 global inclusion proof。
- `proofKind=DOMAIN_ROOT_ONLY`。
- response 必须写明该 domain 不参与 global root。

如果 domain `RootPolicy=EXCLUDED`：

- 返回 `NON_EXISTENCE_DOMAIN_EXCLUDED`。
- 不允许生成“全局状态 proof”。

### 8.4 value 绑定

proof 不能只证明 key path，还要绑定 domain value。

leaf hash：

```text
leafHash = H(
  "leaf" ||
  algorithmId ||
  domainId ||
  canonicalDomainKey ||
  canonicalValueHash
)
```

`canonicalValueHash` 来自 Registry value normalizer：

- account：完整 `Account` protobuf bytes。
- contract：ABI 清理后的 `SmartContract` bytes。
- code：runtime code bytes。
- storage：32-byte value，zero 视为 missing/delete。
- dynamic：白名单 key value。

如果 `includeValue=false`，response 可以只返回 `valueHash`，但 verifier 只能验证 hash，不能验证外部 value。

## 9. ProofVerifier

接口：

```java
public interface ProofVerifier {
  ProofVerificationResult verify(ArchiveProof proof);
}
```

验证步骤：

1. 校验 algorithmId/version 支持。
2. 校验 registryChecksum 与 proof descriptor 一致。
3. 用 domain key/value 重算 leaf hash。
4. 验证 domain proof 得到 domainRoot。
5. 如果需要 global proof，验证 domainRoot 得到 globalRoot。
6. 校验 globalRoot 等于 root record globalRoot。
7. 校验 `coverage/rootScope/consensusParticipation` 与 proof claim 一致。

`ProofVerificationResult`：

```text
valid
invalidReason
rootScope
coverage
consensusParticipation
statePoint
domain
proofKind
```

Verifier 必须能离线运行，不依赖 live Store。可以依赖 algorithm descriptors 和 registry descriptor。

## 10. Debug JSON-RPC

### 10.1 方法

PR9 新增默认关闭方法：

```text
debug_getArchiveRoot(statePoint)
debug_getArchiveProof(proofRequest)
debug_verifyArchiveProof(proof)
debug_traceCall(callArgs, blockParam, traceOptions)
```

也可以使用 `tron_debug...` 前缀降低和 Ethereum debug namespace 冲突；首版建议使用 `debug_*`，但必须受配置门控。

`TronJsonRpc` 新增声明：

```java
@JsonRpcMethod("debug_getArchiveRoot")
ArchiveRootJsonResult debugGetArchiveRoot(Object statePoint)
    throws JsonRpcInvalidParamsException, JsonRpcInternalException, JsonRpcMethodNotFoundException;

@JsonRpcMethod("debug_getArchiveProof")
ArchiveProofJsonResult debugGetArchiveProof(ArchiveProofRequest request)
    throws JsonRpcInvalidParamsException, JsonRpcInternalException, JsonRpcMethodNotFoundException;

@JsonRpcMethod("debug_verifyArchiveProof")
ArchiveProofVerificationJsonResult debugVerifyArchiveProof(ArchiveProofJsonResult proof)
    throws JsonRpcInvalidParamsException, JsonRpcInternalException, JsonRpcMethodNotFoundException;

@JsonRpcMethod("debug_traceCall")
ArchiveTraceCallResult debugTraceCall(CallArguments args, Object blockParam, TraceOptions options)
    throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException,
           JsonRpcInternalException, JsonRpcMethodNotFoundException;
```

### 10.2 method guard

每个 debug 方法先检查：

```text
archive.enable
archive.debug.enable
archive.debug.exposeJsonRpc
specific feature enable
```

未开启时返回 `JsonRpcMethodNotFoundException` 或明确 disabled。建议：

- method 未公开：`method not found`
- method 公开但 proof/trace 子功能关闭：`JsonRpcInternalException("archive proof debug disabled")`

### 10.3 response metadata

所有 root/proof response 都必须包含：

```json
{
  "rootScope": "ARCHIVE_SIDECAR",
  "consensusParticipation": "NONE",
  "coverage": "TVM_STATE_ONLY",
  "algorithmId": "TRON_ARCHIVE_SMT_KECCAK_V1",
  "registryChecksum": "0x...",
  "statePoint": "...",
  "asOfTxNum": "0x..."
}
```

不要只返回一个 root hash。

## 11. debug_traceCall

### 11.1 复用 PR8 executor

`debug_traceCall` 使用 PR8 的 historical call path：

```text
ArchiveTraceCallExecutor
  -> ArchiveEthCallExecutor.prepare(...)
  -> ArchiveRepositoryAdapter
  -> VMActuator with TraceOptions
  -> ProgramTrace in memory
```

latest traceCall 可以选择：

- 仍走 PR8 executor 的 latest-compatible adapter；
- 或暂不支持 latest traceCall，只支持 archive historical。

建议首版支持 historical only，latest 仍返回 unsupported，避免改动现有 Wallet constant call。

### 11.2 trace capture

当前 `VMConfig.vmTrace()` 是全局静态，且 `VMActuator` 会写文件。PR9 要新增 per-execution trace capture：

```java
public record TraceOptions(
    boolean enableMemoryTrace,
    boolean includeStack,
    boolean includeMemory,
    boolean includeStorage,
    int maxOps) {
}
```

实现策略：

1. `VMActuator` 增加 `TraceCapture traceCapture` override。
2. `Program` 创建时使用 `traceCapture.isEnabled()`，不直接读全局 `VMConfig.vmTrace()`。
3. `VM.play` 的 save-op 条件改成 `program.isTraceEnabled()`。
4. `VMActuator.execute` 如果是 trace capture，不调用 `VMUtils.saveProgramTraceFile`。
5. `TraceCapture` 超过 `maxOps` 时中止并返回 limit error。

如果这个改动过大，PR9 可以先提供 `debug_traceCall` skeleton 并返回 disabled；但不要通过打开全局 vmTrace 来实现。

### 11.3 trace result

```java
public record ArchiveTraceCallResult(
    String output,
    String error,
    long energyUsed,
    long energyPenalty,
    List<OpTraceJson> structLogs,
    String statePoint,
    String blockNumber,
    String txRoot,
    String archiveRoot) {
}
```

`structLogs` 可直接由 `ProgramTrace.getOps()` 转换。

## 12. eth_getProof 兼容层边界

PR9 不实现：

```text
eth_getProof(address, storageKeys, blockTag)
```

原因：

1. issue #6289 也把 `eth_getProof` 列为 discussion。
2. TRON `Account` 没有 Ethereum nonce 语义。
3. PR7 archive root 不是 Ethereum account MPT root。
4. TRON contract storage root 不是 Ethereum account.storageRoot。
5. `accountStateRoot` 当前只覆盖 balance/allowance 子集，不能作为 `eth_getProof` root。

如果后续要做 `eth_getProof`：

```text
PR10: Ethereum-facing proof adapter
  -> define EthAccountView(nonce,balance,storageRoot,codeHash)
  -> define storage trie root per contract
  -> decide nonce hard fork / synthetic nonce
  -> return proof with explicit compatibility caveat
```

不要在 PR9 中返回 archive proof 给 `eth_getProof` 方法名。

## 13. 数据表扩展

PR7 已定义：

```text
ROOT_BLOCK    0x31
ROOT_DOMAIN   0x32
ROOT_TX       0x33
ROOT_NODE     0x34
ROOT_CURRENT  0x35
ROOT_LEAF     0x36
```

PR9 可选新增：

```text
ROOT_CHECKPOINT 0x37
PROOF_META      0x38
```

首版不需要持久化 proof。Proof 应按请求生成。

`ROOT_TX` 使用规则：

- `persistTxRoots=true` 时 block apply 写入。
- on-demand 计算可以选择写入 cache，但必须受配置控制。
- cache key 必须包含 `blockHash/asOfTxNum/registryChecksum/algorithmId`，避免 reorg 或 schema 变化污染。

## 14. 错误模型

| 场景 | 错误 |
| --- | --- |
| archive disabled | `ARCHIVE_DISABLED` |
| commitment disabled | `COMMITMENT_DISABLED` |
| debug API disabled | `DEBUG_DISABLED` |
| root missing | `ROOT_MISSING` |
| root and state progress mismatch | `REPAIR_REQUIRED` |
| tx root not persisted | `TX_ROOT_NOT_PERSISTED` |
| on-demand disabled | `ON_DEMAND_DISABLED` |
| replay too long | `REPLAY_LIMIT_EXCEEDED` |
| domain not in root | `DOMAIN_NOT_IN_ROOT` |
| domain excluded | `DOMAIN_EXCLUDED` |
| proof node missing/corrupt | `PROOF_CORRUPTED` |
| algorithm mismatch | `ALGORITHM_MISMATCH` |
| registry checksum mismatch | `REGISTRY_MISMATCH` |
| trace op limit exceeded | `TRACE_LIMIT_EXCEEDED` |

不要把这些错误折叠成 account not found 或 method not found。

## 15. 实现步骤

### Step 1：Proof model 和 root reader

1. 新增 proof model records。
2. 新增 `ArchiveRootReader.getRoot(StatePoint)`。
3. 实现 block-end root 查询。
4. 单测 `BLOCK_END` root missing/disabled/range error。

### Step 2：CommitmentTree proof

1. 给 PR7 `CommitmentTree` 增加 `prove(path32)`。
2. 对 binary SMT 生成 sibling proof。
3. 增加 domain proof verifier。
4. 单测 existence/non-existence proof。

### Step 3：global proof

1. 定义 global domain path/leaf hash。
2. 实现 global proof builder。
3. 验证 domainRoot -> globalRoot。
4. 单测 domain excluded/domain root only。

### Step 4：on-demand tx root

1. 通过 `ArchiveTxNumIndex` 解析 StatePoint。
2. 从 `ROOT_BLOCK(blockNum - 1)` 初始化 trees。
3. replay `CHANGESET` 到目标 txNum。
4. 使用 `ArchiveStateReader.atTxNum(txNum)` 读取 afterValue。
5. 受 `maxOnDemandReplayTx` 限制。
6. 单测 `TX_BEFORE/TX_AFTER/BLOCK_END` root 差异。

### Step 5：JSON-RPC debug root/proof

1. 新增 `ArchiveDebugJsonRpcAdapter`。
2. 新增 `debug_getArchiveRoot`。
3. 新增 `debug_getArchiveProof`。
4. 新增 `debug_verifyArchiveProof`。
5. 加配置 guard。

### Step 6：trace capture

1. 新增 `TraceOptions/TraceCapture`。
2. 改 `Program`/`VM` 支持 per-execution trace flag。
3. PR8 executor 支持 trace mode。
4. 新增 `debug_traceCall`。
5. 单测不写 `vm_trace` 文件。

## 16. 测试清单

### 16.1 Root 查询

- commitment disabled 返回 disabled。
- block root 存在返回 metadata。
- 空块也有 root。
- root missing 返回 repair required。
- archive lag 返回 range unavailable。

### 16.2 Domain proof

- account existence proof。
- storage existence proof。
- storage zero/missing non-existence proof。
- code missing proof。
- dynamic property domain root only proof。
- domain excluded proof。

### 16.3 Global proof

- domainRoot inclusion。
- modified domain root changes global root。
- excluded domain 不生成 global proof。
- partial root 标记不能声明 full state proof。

### 16.4 On-demand tx root

构造同 block 两笔交易：

```text
tx0: slot = A
tx1: slot = B
finalize: dynamic property = C
```

断言：

- `TX_BEFORE(tx0)` = parent block root。
- `TX_AFTER(tx0)` proof 验证 slot=A。
- `TX_BEFORE(tx1)` proof 验证 slot=A。
- `TX_AFTER(tx1)` proof 验证 slot=B。
- `BLOCK_END(block)` 包含 finalize dynamic write。

### 16.5 JSON-RPC guards

- debug disabled -> method not found/disabled。
- proof disabled -> proof disabled。
- malformed statePoint -> invalid params。
- malformed proof -> invalid proof result。

### 16.6 TraceCall

- historical `debug_traceCall` 返回 op list。
- trace result 使用 historical block context。
- call overlay 不污染 archive/latest。
- maxOps 超限返回 `TRACE_LIMIT_EXCEEDED`。
- 不创建 `./vm_trace/<tx>.json`。
- traceCall 后 latest VMConfig 不被历史配置污染。

## 17. 代码审查清单

1. PR9 没有新增 `eth_getProof`，除非单独定义 Ethereum-facing account/storage root。
2. 所有 root/proof response 都包含 `rootScope/consensusParticipation/coverage/algorithmId/registryChecksum`。
3. proof verifier 可以离线验证，不读 latest Store。
4. on-demand tx root 使用 `ArchiveTxNumIndex`，RPC 层不手写 txNum off-by-one。
5. on-demand replay 读取 afterValue 时走 `ArchiveStateReader.atTxNum`，不读 latest Store。
6. `debug_traceCall` 复用 PR8 archive repository overlay。
7. trace capture 不写 `vm_trace` 文件。
8. debug JSON-RPC 默认关闭。
9. domain excluded/partial root 不被包装成 complete global proof。
10. proof/root API 不参与 consensus。

## 18. 风险和后续

### 18.1 Proof 成本

SMT proof 固定 256 层，未压缩时 proof 较大。首版可接受；后续可以：

- 压缩连续 empty siblings。
- 用 bitmap 标记 empty levels。
- 对 hot paths 加 proof cache。

### 18.2 On-demand replay 成本

同 block 内交易很多时，tx-level proof 可能慢。首版用 `maxOnDemandReplayTx` 限制。后续：

- 每 N tx checkpoint。
- 热点 block tx root cache。
- every-tx root 仅在专用 archive 节点开启。

### 18.3 debug_traceCall 改 VM trace 侵入较大

如果 per-execution trace 改造太大，PR9 可以先落 root/proof API，并把 traceCall 留为 disabled skeleton。不能用全局 `VMConfig.vmTrace` + 文件输出来凑功能。

### 18.4 eth_getProof

`eth_getProof` 需要 Ethereum-facing state model，特别是 nonce 和 storageRoot。issue #6289 也把它列为讨论项。建议独立 PR10，不进入 PR9。

## 19. 推荐拆分

如果按小 PR 拆：

1. `archive: add root reader and proof models`
2. `archive: add SMT proof builder and verifier`
3. `archive: add tx root on-demand replay`
4. `jsonrpc: expose guarded archive debug proof methods`
5. `vm: add per-call trace capture for archive traceCall`

推荐先做 1-4。第 5 项涉及 VM trace 静态配置和文件输出改造，风险更高，可以单独评审。
