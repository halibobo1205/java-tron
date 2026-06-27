# java-tron Archive L9：proof/debug API 代码级执行包

日期：2026-06-05

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`

Erigon 源码路径：`/Users/boson/GolandProjects/erigon`

上游总路线：[java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)

来源大包：[java-tron Archive S14：proof/debug API 4e80 编码执行包](./20260604-java-tron-archive-s14-proof-debug-api-4e80-coding-packet.md)

state-root 分支参考：[java-tron state-root 分支借鉴分析](./20260605-java-tron-state-root-branches-reference-analysis.md)

前置执行包：

- [L1 config/no-op/dbName 代码级执行包](./20260604-java-tron-archive-l1-config-noop-dbname-4e80-code-plan.md)：提供 `storage.archive.*` 配置入口和 default-off 口径。
- [L2 Manager lifecycle + txNum 代码级执行包](./20260605-java-tron-archive-l2-manager-txnum-4e80-code-plan.md)：提供 block/txNum coverage 和 block txNum range。
- [L3 ArchiveDomainRegistry 代码级执行包](./20260605-java-tron-archive-l3-domain-registry-4e80-code-plan.md)：提供 rooted domain、domain id、key/value codec、registry checksum。
- [L5 ArchiveTemporalStore 代码级执行包](./20260605-java-tron-archive-l5-temporal-store-4e80-code-plan.md)：提供 archive DB、root tables、history coverage 和 `getAsOf`。
- [L6 ArchiveStateReader + historical getters 代码级执行包](./20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md)：提供 `ArchiveStatePointResolver` 和 historical value reader。
- [L7 CommitmentBuilder 代码级执行包](./20260605-java-tron-archive-l7-commitment-builder-4e80-code-plan.md)：提供 `ArchiveRootRecord`、`DomainRootRecord`、`CommitmentNodeRecord`、`ArchiveRootReader`、`ArchiveTxRootComputer`。
- [L8 historical eth_call 代码级执行包](./20260605-java-tron-archive-l8-historical-eth-call-4e80-code-plan.md)：提供 historical VM read path；L9 不依赖 traceCall。

本文只做 L9 规划，不修改 java-tron 源码。目标是把 archive-native root/proof/verify debug API 细化到 JSON-RPC 方法、DTO、配置、source guard、proof builder、proof verifier、root/value trust anchor、测试 gate 和 review checklist。

## 1. L9 定位

L9 暴露默认关闭的 archive-native debug API：

```text
debug_getArchiveRoot
debug_getArchiveProof
debug_verifyArchiveProof
```

它证明的是：

```text
rootScope = ARCHIVE_SIDECAR
consensusParticipation = NONE
trustAnchor = ArchiveRootRecord.globalRoot
tree = L7 archive sparse commitment tree
domains = L3 rooted archive domains
```

它不证明：

```text
Ethereum MPT state root
TRON BlockHeader.raw.accountStateRoot
TRON BlockHeader.raw.txTrieRoot
JSON-RPC BlockResult.stateRoot
debug_traceCall execution trace
```

L9 交付：

```text
ArchiveDebugConfig
ArchiveDebugAccessGuard
ArchiveDebugFacade
ArchiveRootQuery / ArchiveRootResult
ArchiveProofRequest / ArchiveProofTarget / ArchiveProof
ArchiveDomainProof / ArchiveGlobalProof / ArchiveProofNode
ArchiveProofService / DefaultArchiveProofService
ArchiveDomainProofBuilder
ArchiveGlobalProofBuilder
ArchiveProofVerifier
ArchiveProofVerificationResult
ArchiveProofJsonAdapter
TronJsonRpc debug_* method declarations and implementations
default-off / FullNode-only / no eth_getProof / no debug_traceCall tests
```

L9 不交付：

```text
eth_getProof
debug_traceCall
debug_executionWitness
high-QPS public proof service
proof node GC
new consensus root
header stateRoot replacement
block result schema change
VM trace file output
full votes/delegation/assets proof coverage unless L3 declares rooted domains
```

补充参考口径：`feat/state-trie-4.8.1` vendored Besu MPT 包含 `Proof`/visitor，可作为后续 proof visitor 研究材料；P0 L9 仍只证明 L7 archive sidecar commitment node records。`feat/481_state_root` 的 `StateRootStore` 只能证明 root 持久化表的需要，不能作为 `eth_getProof` 或 header proof 语义来源。

L9 的核心约束：

```text
1. debug API 默认关闭；关闭时返回 method-not-found。
2. 只在 FullNode JSON-RPC source 可用；Solidity/PBFT 默认 method-not-found。
3. root/proof 只读 archive root/node/value，不读 latest AccountStore/ContractStore/StorageRowStore。
4. proof 返回前必须自验证；验证失败不返回 proof。
5. missing key 返回可验证 non-existence proof，不能只返回 null。
6. proof response 必须显式标注 ARCHIVE_SIDECAR / NONE。
7. eth_getProof 保持未声明或 method-not-found。
8. debug_traceCall 保持未声明或 method-not-found，不开启 VMConfig.vmTrace。
```

## 2. Erigon 源码依据

### 2.1 eth_getProof 的可迁移不变量

| Erigon 源码 | 当前事实 | java-tron L9 映射 |
| --- | --- | --- |
| `rpc/jsonrpc/eth_call.go:400-432` | `GetProof` 先限制 storage key 数量，再解析 canonical block、检查 prune history | L9 先限制 targets/proof bytes，再解析 `ArchiveStatePoint`、检查 archive/root coverage |
| `eth_call.go:457-506` | proof trie root 必须等于 header `Root`，否则报 mismatch | L9 proof root 必须等于 `ArchiveRootRecord.globalRoot` 或 `DomainRootRecord.domainRoot` |
| `eth_call.go:496-503` | 通过 touch key 生成 proof trie | L9 通过 `ArchiveDomainProofBuilder` 沿 `path32` 读取 `COMMITMENT_BRANCH` node |
| `eth_call.go:518-523` | account proof 从 proof trie 生成 | L9 domain proof 从 archive domain tree 生成 |
| `eth_call.go:547-562` | storage proof root 也必须比对 header root | L9 global proof 必须证明 `DomainRootRecord` 被 global root 收录 |
| `eth_call.go:610-622` | 返回前验证 account/storage proof | L9 `DefaultArchiveProofService` 返回前调用 `ArchiveProofVerifier` |

不能照搬：

```text
header.Root as trust anchor
Ethereum account fields: nonce/balance/storageHash/codeHash
Ethereum storage root
MPT path: keccak(address) / keccak(address)||keccak(slot)
eth_getProof response shape
```

应该迁移：

```text
resolve point
check history availability
build proof from commitment nodes
verify before return
fail-fast on root mismatch
bounded request size
```

### 2.2 non-existence proof

| Erigon 源码 | 当前事实 | java-tron L9 映射 |
| --- | --- | --- |
| `execution/commitment/trie/proof.go:33-40` | missing key 返回最长已有前缀节点，用于证明 absence | `ArchiveDomainProof.exists=false` 必须携带 divergence/empty branch proof |
| `proof.go:294-342` | account proof verifier 校验 proof value 与 response fields 一致；missing account 要求 fields 为空 | L9 verifier 校验 `exists/value/valueHash/canonicalKey` 一致；missing target 不允许带 non-empty value |
| `proof.go:345-375` | empty storage root 对 proof/value 有严格规则 | L9 empty domain root 必须有固定 empty proof 规则 |

L9 proof builder 不允许：

```text
if missing -> return null
if missing -> return empty nodes without proving empty root or divergence node
if value missing -> silently read latest
```

### 2.3 debug witness 的默认关闭和自验证

| Erigon 源码 | 当前事实 | java-tron L9 映射 |
| --- | --- | --- |
| `rpc/jsonrpc/debug_api.go:53-70` | debug namespace 是 private/debug API，含 `ExecutionWitness` | L9 方法命名为 `debug_*`，默认关闭 |
| `debug_execution_witness.go:523-539` | 缺 commitment history 直接报错 | L9 commitment/root 未启用或 gap 时直接报错 |
| `debug_execution_witness.go:558-560` | state reader 绑定 exact txnum | L9 root/proof 绑定 `ArchiveStatePoint.asOfTxNum` |
| `debug_execution_witness.go:677-680` | commitment history pruned fail-fast | L9 archive range 缺失 fail-fast |
| `debug_execution_witness.go:686-710` | 构造 witness 后做 stateless verification | L9 proof 返回前做 `ArchiveProofVerifier.verify(proof)` |
| `debug_execution_witness.go:885-909` | collapse detection 时重新 compute commitment，并比对 expected root | L9 删除/branch collapse 场景必须测试 proof 不丢 sibling/divergence path |
| `debug_execution_witness.go:915-960` | build witness 时 touch sibling paths，并比对 witness root | L9 proof builder 必须包含 sibling nodes 和 collapse 后的空/分叉证据 |

### 2.4 trust anchor 模式

| Erigon 源码 | 当前事实 | java-tron L9 映射 |
| --- | --- | --- |
| `execution/types/stateless/witness.go:36-70` | witness pre-state root 必须等于 parent header root | L9 proof root 必须等于 archive root record，不等于 header root |
| `witness.go:85-98` | witness 构造时读取 parent header 作为 trust anchor container | L9 构造时读取 `ArchiveRootRecord` 作为 trust anchor container |

java-tron response 必须把这件事写在数据里：

```json
{
  "rootScope": "ARCHIVE_SIDECAR",
  "consensusParticipation": "NONE",
  "headerField": null,
  "archiveRoot": "0x...",
  "headerStateRoot": "0x...",
  "sameAsHeaderStateRoot": false
}
```

`sameAsHeaderStateRoot` 只是调试字段。即使偶然相等，也不能把 archive proof 解释为 header-root proof。

## 3. java-tron 4e80 源码事实

### 3.1 JSON-RPC 暴露方式

| java-tron 源码 | 当前事实 | L9 动作 |
| --- | --- | --- |
| `TronJsonRpc.java:37-38` | `TronJsonRpc` 是 JSON-RPC interface bean | 新增三个 `@JsonRpcMethod("debug_*")` |
| `TronJsonRpc.java:90-170` | 已声明 P0 historical methods：balance/storage/code/call | L9 不改这些方法签名 |
| `TronJsonRpc.java:251-256` | `eth_getTransactionCount` 已声明但 method-not-found | `eth_getProof` 不新增；如未来声明，也必须 method-not-found |
| `JsonRpcServlet.java:64-83` | servlet 注入一个 `TronJsonRpc`，用 `ProxyUtil.createCompositeServiceProxy` 暴露 | 最小接入是在 `TronJsonRpc` 增加方法，不新增 servlet endpoint |
| `JsonRpcServlet.java:81-83` | `JsonRpcServer` 使用 `JsonRpcErrorResolver.INSTANCE` | L9 依赖 `@JsonRpcErrors` 映射错误码 |
| `JsonRpcOnSolidityServlet.java:14-28` | Solidity servlet 继承同一个 `JsonRpcServlet` | L9 方法必须在实现里拒绝 Solidity source |
| `JsonRpcOnPBFTServlet.java:14-28` | PBFT servlet 继承同一个 `JsonRpcServlet` | L9 方法必须在实现里拒绝 PBFT source |
| `TronJsonRpcImpl.java:1301-1310` | `getSource()` 根据 wallet cursor 返回 FULLNODE/SOLIDITY/PBFT | `ArchiveDebugAccessGuard.requireFullNode(getSource())` |
| `TronJsonRpcImpl.java:1313-1318` | `disableInPBFT` 已有 PBFT method-not-found 样式 | L9 扩展为 FullNode-only guard |
| `TronJsonRpcImpl.java:1395-1400` | method-not-found unsupported 方法直接抛异常 | debug default-off 复用 method-not-found 模式 |

推荐新增：

```java
@JsonRpcMethod("debug_getArchiveRoot")
@JsonRpcErrors({
    @JsonRpcError(exception = JsonRpcMethodNotFoundException.class, code = -32601, data = "{}"),
    @JsonRpcError(exception = JsonRpcInvalidParamsException.class, code = -32602, data = "{}"),
    @JsonRpcError(exception = JsonRpcExceedLimitException.class, code = -32005, data = "{}"),
    @JsonRpcError(exception = JsonRpcInternalException.class, code = -32000, data = "{}"),
})
ArchiveRootJsonResult debugGetArchiveRoot(Object blockOrRequest, Object options)
    throws JsonRpcMethodNotFoundException, JsonRpcInvalidParamsException,
    JsonRpcExceedLimitException, JsonRpcInternalException;
```

为了避免 ambiguous overload，P0 更建议统一单 request object：

```java
ArchiveRootJsonResult debugGetArchiveRoot(ArchiveRootJsonRequest request)

ArchiveProofJsonResult debugGetArchiveProof(ArchiveProofJsonRequest request)

ArchiveProofVerificationJsonResult debugVerifyArchiveProof(ArchiveProofJsonResult proof)
```

### 3.2 错误模型

| java-tron 源码 | 当前事实 | L9 动作 |
| --- | --- | --- |
| `JsonRpcErrorResolver.java:22-45` | 根据 method 上的 `@JsonRpcErrors` 找异常并组装 code/message/data | L9 interface 方法必须列完整异常 |
| `JsonRpcException.java:7-29` | 异常可携带 `Object data` | verification mismatch 可带 structured data |
| `JsonRpcInternalException.java:17-19` | 支持 message + data | proof mismatch/root mismatch 用 internal + data |
| `JsonRpcExceedLimitException` | 可映射 limit error | proof targets/nodes/bytes 超限用它 |

建议映射：

| Condition | Exception | JSON-RPC code |
| --- | --- | --- |
| debug API 未启用 | `JsonRpcMethodNotFoundException` | `-32601` |
| source 非 FULLNODE | `JsonRpcMethodNotFoundException` | `-32601` |
| malformed request / unknown target kind / invalid domain | `JsonRpcInvalidParamsException` | `-32602` |
| max targets/nodes/bytes/on-demand replay exceeded | `JsonRpcExceedLimitException` | `-32005` |
| archive enabled but root/proof gap/corrupt/mismatch | `JsonRpcInternalException` | `-32000` |
| verifier invalid proof in `debug_verifyArchiveProof` | result `valid=false`，不抛异常，除非 request malformed |

`debug_verifyArchiveProof` 是验证用户传入 proof 的方法。proof 无效本身不是 server internal error，应返回：

```json
{
  "valid": false,
  "failureCode": "ROOT_MISMATCH",
  "failureMessage": "...",
  "expectedRoot": "0x...",
  "calculatedRoot": "0x..."
}
```

但如果 proof JSON 结构无法解析，才是 invalid params。

### 3.3 配置链路

| java-tron 源码 | 当前事实 | L9 动作 |
| --- | --- | --- |
| `reference.conf:118-132` | storage 下已有 history/checkpoint/txCache/snapshot | 新增 `storage.archive.debug` 默认关闭 |
| `StorageConfig.java:21-33` | `StorageConfig` 当前没有 archive config 字段 | 新增 `ArchiveConfig`、`ArchiveDebugConfig` bean |
| `StorageConfig.java:173-188` | `fromConfig` 读取 storage section 并 post-process | archive/debug 限额校验放在 post-process |
| `Args.java:212-244` | `applyStorageConfig` 把 storage bean 写进 `CommonParameter.storage` | archive debug config 在这里桥接到 runtime |
| `Args.java:713-716` | 初始化 `PARAMETER.storage = new Storage()` 后读取 `StorageConfig` | `Storage` 新增 archive/debug runtime fields |
| `Storage.java:44-113` | runtime storage holder 当前只含 db/cache/root 等 | 新增 `ArchiveRuntimeConfig` / `ArchiveDebugRuntimeConfig` |
| `NodeConfig.JsonRpcConfig:231-250` | JSON-RPC 只存 HTTP enable、端口、通用限额 | archive debug 开关不放这里，避免与普通 JSON-RPC 混淆 |
| `CommonParameter.java:463-490` | JSON-RPC runtime 参数集中在 singleton | framework adapter 通过 `Args/CommonParameter.storage.archive.debug` 获取配置 |

建议配置：

```hocon
storage {
  archive {
    enable = false
    commitment {
      enable = false
      persistTxRoots = false
    }
    debug {
      enable = false
      exposeJsonRpc = false
      proofEnable = false
      verifyBeforeReturn = true
      includeGlobalProofByDefault = true
      includeValueByDefault = false
      allowTxRootOnDemand = false
      maxOnDemandReplayTx = 2000
      maxProofTargets = 16
      maxProofNodes = 1024
      maxProofBytes = 1048576
    }
  }
}
```

Rules:

```text
archive.enable=false:
  all debug_* archive methods method-not-found

archive.debug.enable=false:
  all debug_* archive methods method-not-found

archive.debug.exposeJsonRpc=false:
  JSON-RPC methods method-not-found even if internal service can be used by tests

archive.commitment.enable=false:
  debug_getArchiveRoot/debug_getArchiveProof return internal commitment disabled

archive.debug.proofEnable=false:
  debug_getArchiveProof/debug_verifyArchiveProof method-not-found
  debug_getArchiveRoot may still work if exposeJsonRpc=true

verifyBeforeReturn=false:
  not recommended; P0 implementation may ignore and always verify before return
```

Limit checks:

```text
targets.size <= maxProofTargets
proof.nodes <= maxProofNodes
encoded proof bytes <= maxProofBytes
on-demand replay tx count <= maxOnDemandReplayTx
```

### 3.4 header root 不是 archive root

| java-tron 源码 | 当前事实 | L9 约束 |
| --- | --- | --- |
| `BlockResult.java:101-104` | JSON-RPC `transactionsRoot`/`stateRoot` 直接来自 block header fields | L9 不修改 `BlockResult` |
| `BlockCapsule.java:233-244` | `validateMerkleRoot` 校验 `txTrieRoot` | L9 不接入 |
| `BlockCapsule.java:255-262` | `setAccountStateRoot` 写 header `accountStateRoot` | L9 不调用 |

所有 L9 response 必须携带：

```text
rootScope=ARCHIVE_SIDECAR
consensusParticipation=NONE
headerField=null
```

`debug_getArchiveRoot` 可额外返回：

```text
headerStateRoot = block.header.raw.accountStateRoot
sameAsHeaderStateRoot = Arrays.equals(archiveRoot, headerStateRoot)
```

但 verifier 不使用 headerStateRoot。

### 3.5 TrieImpl 只能参考

| java-tron 源码 | 当前事实 | L9 约束 |
| --- | --- | --- |
| `TrieImpl.java:381-429` | `prove(byte[] key)` 生成现有 framework trie proof | 只能参考存在性 proof 行为 |
| `TrieImpl.java:490-557` | `verifyProof` 验证 RLP proof | L9 不复用该 proof shape |

包边界：

```text
framework -> chainbase
chainbase -/-> framework
```

`ArchiveProofService`、`ArchiveDomainProofBuilder`、`ArchiveProofVerifier` 放在 chainbase，不能 import `org.tron.core.trie.TrieImpl`。如果未来要复用 trie core，必须先把 trie 抽到 common/chainbase 可依赖包，不能在 L9 顺手做。

### 3.6 VM trace 禁区

| java-tron 源码 | 当前事实 | L9 约束 |
| --- | --- | --- |
| `VM.java:31-34` | `VMConfig.vmTrace()` 时每个 opcode 调 `program.saveOpTrace()` | L9 不打开 |
| `VMActuator.java:297-309` | 执行后把 trace 写文件 | L9 不调用 historical VM trace |
| `VMUtils.java:55-98` | trace 文件写到 `./vm_trace/<tx>.json` | L9 tests 要证明 debug proof 不创建文件 |

L9 不实现 `debug_traceCall`。后续如果要做 traceCall，必须基于 L8 historical VM 增加 per-call in-memory trace，不使用全局 `vmTrace` 文件路径。

## 4. 目标文件与包边界

### 4.1 common

```text
common/src/main/resources/reference.conf
common/src/main/java/org/tron/core/config/args/StorageConfig.java
common/src/main/java/org/tron/core/config/args/Storage.java
```

新增/修改：

```text
StorageConfig.ArchiveConfig
StorageConfig.ArchiveCommitmentConfig
StorageConfig.ArchiveDebugConfig
Storage.ArchiveRuntimeConfig
Storage.ArchiveDebugRuntimeConfig
```

如果 L1 已经引入 `ArchiveConfig`，L9 只补 `ArchiveDebugConfig` 和 proof limit fields，不重复新增。

### 4.2 chainbase

```text
chainbase/src/main/java/org/tron/core/archive/proof/
  ArchiveProofService.java
  DefaultArchiveProofService.java
  ArchiveRootQuery.java
  ArchiveRootResult.java
  ArchiveProofRequest.java
  ArchiveProofTarget.java
  ArchiveProof.java
  ArchiveDomainProof.java
  ArchiveGlobalProof.java
  ArchiveProofNode.java
  ArchiveProofVerifier.java
  ArchiveProofVerificationResult.java
  ArchiveDomainProofBuilder.java
  ArchiveGlobalProofBuilder.java
  ArchiveProofLimitChecker.java
  ArchiveProofException.java
```

Consumes:

```text
org.tron.core.archive.reader.ArchiveStatePointResolver
org.tron.core.archive.reader.ArchiveStateReaderFactory
org.tron.core.archive.domain.ArchiveDomainRegistry
org.tron.core.archive.domain.ArchiveKeyCodec
org.tron.core.archive.domain.ArchiveValueCodec
org.tron.core.archive.commitment.ArchiveRootReader
org.tron.core.archive.commitment.ArchiveTxRootComputer
org.tron.core.archive.commitment.ArchiveRootRecord
org.tron.core.archive.commitment.CommitmentNodeRecord
org.tron.core.archive.commitment.ArchiveCommitmentContext
```

Does not consume:

```text
Wallet
Manager latest stores
Repository
VMActuator
TrieImpl framework package
```

### 4.3 framework

```text
framework/src/main/java/org/tron/core/services/jsonrpc/
  TronJsonRpc.java
  TronJsonRpcImpl.java
  ArchiveDebugFacade.java
  ArchiveDebugAccessGuard.java
  ArchiveProofJsonAdapter.java

framework/src/main/java/org/tron/core/services/jsonrpc/types/
  ArchiveRootJsonRequest.java
  ArchiveRootJsonResult.java
  ArchiveProofJsonRequest.java
  ArchiveProofTargetJson.java
  ArchiveProofJsonResult.java
  ArchiveDomainProofJson.java
  ArchiveGlobalProofJson.java
  ArchiveProofNodeJson.java
  ArchiveProofVerificationJsonResult.java
```

framework职责：

```text
parse JSON request DTO
check source/config through access guard
call chainbase ArchiveProofService
map domain exceptions to JsonRpc exceptions
render 0x hex fields
keep method-not-found behavior for disabled/future APIs
```

framework 不做：

```text
walk commitment tree
normalize domain values
read latest stores
compute roots
verify proof internals
```

## 5. JSON-RPC API

### 5.1 debug_getArchiveRoot

Request:

```json
{
  "block": "0x10",
  "txNum": null,
  "txIndex": null,
  "includeDomains": true,
  "allowOnDemand": false
}
```

Accepted `block`:

```text
"latest"
"earliest"
"finalized"
"0xN"
{"blockNumber":"0xN"}
{"blockHash":"0x..."}
```

P0 rejects:

```text
pending
safe
block object with both blockNumber and blockHash
non-canonical hash
txNum + txIndex both present unless they resolve to same point
tx-level on-demand root when allowOnDemand=false or config forbids
```

Response:

```json
{
  "rootScope": "ARCHIVE_SIDECAR",
  "consensusParticipation": "NONE",
  "statePointKind": "BLOCK_END",
  "blockNumber": "0x10",
  "blockHash": "0x...",
  "txNum": "0x1234",
  "txIndex": null,
  "txId": null,
  "archiveRoot": "0x...",
  "rootSource": "ROOT_BY_BLOCK",
  "persisted": true,
  "algorithmId": "ARCHIVE_SMT_V1",
  "registryChecksum": "0x...",
  "coverage": "TVM_STATE_ONLY",
  "headerField": null,
  "headerStateRoot": "0x...",
  "sameAsHeaderStateRoot": false,
  "domains": [
    {
      "domainId": 1,
      "domainName": "ACCOUNT",
      "domainRoot": "0x...",
      "keyCodecId": "ACCOUNT_KEY_V1",
      "valueCodecId": "ACCOUNT_VALUE_PROTO_V1",
      "rootPolicy": "IN_GLOBAL_ROOT"
    }
  ]
}
```

Root sources:

```text
ROOT_BY_BLOCK
ROOT_BY_TX
ROOT_CHECKPOINT
ON_DEMAND_REPLAY
```

For `ON_DEMAND_REPLAY` include:

```text
replayFromTxNum
replayToTxNum
replayTxCount
persisted=false
```

### 5.2 debug_getArchiveProof

Request:

```json
{
  "block": "0x10",
  "txNum": null,
  "txIndex": null,
  "includeGlobalProof": true,
  "includeValue": false,
  "allowOnDemand": false,
  "targets": [
    {
      "kind": "ACCOUNT",
      "address": "0x..."
    },
    {
      "kind": "CONTRACT_STORAGE",
      "address": "0x...",
      "slot": "0x..."
    }
  ]
}
```

Target kinds:

| kind | Domain | Key rule |
| --- | --- | --- |
| `ACCOUNT` | ACCOUNT | `address21` |
| `CONTRACT` | CONTRACT | `address21` |
| `CODE` | CODE | L3 code domain canonical key |
| `CONTRACT_STORAGE` | CONTRACT_STORAGE | `address21 || slot32 || storageKeyVersion_u8` |
| `DYNAMIC_PROPERTY` | DYNAMIC_PROPERTIES | registry-defined property key |
| `RAW_DOMAIN_KEY` | rooted debug-allowed domain | hex canonical key |

`RAW_DOMAIN_KEY` constraints:

```text
only if debug.allowRawDomainKey=true
domain must be rooted in L3 registry
canonical key length must match domain descriptor
```

Response:

```json
{
  "root": { "...": "ArchiveRootJsonResult" },
  "proofFormat": "ARCHIVE_SMT_V1",
  "verifierVersion": "archive-proof-v1",
  "verifiedBeforeReturn": true,
  "domainProofs": [
    {
      "domainId": 1,
      "domainName": "ACCOUNT",
      "treeKind": "SPARSE_MERKLE_V1",
      "keyCodecId": "ACCOUNT_KEY_V1",
      "valueCodecId": "ACCOUNT_VALUE_PROTO_V1",
      "logicalKey": {"address":"0x..."},
      "canonicalKey": "0x...",
      "path": "0x...",
      "exists": true,
      "valueHash": "0x...",
      "value": null,
      "domainRoot": "0x...",
      "calculatedDomainRoot": "0x...",
      "nodes": []
    }
  ],
  "globalProof": {
    "treeKind": "SPARSE_MERKLE_V1",
    "globalRoot": "0x...",
    "calculatedGlobalRoot": "0x...",
    "nodes": []
  }
}
```

Rules:

```text
includeValue=false:
  proof includes valueHash only; verifier checks node leaf value hash.

includeValue=true:
  proof includes canonical value bytes after RootValueNormalizer.

exists=false:
  valueHash is empty leaf hash or null per proof spec.
  nodes prove empty branch or divergence.

includeGlobalProof=false:
  response still includes root.globalRoot and domainRoot.
  verifier can validate domain proof only, not domain inclusion in global root.
  P0 default should include global proof.
```

### 5.3 debug_verifyArchiveProof

Request:

```json
{
  "root": { "...": "ArchiveRootJsonResult" },
  "proofFormat": "ARCHIVE_SMT_V1",
  "domainProofs": [],
  "globalProof": {}
}
```

Response:

```json
{
  "valid": true,
  "failureCode": null,
  "failureMessage": null,
  "rootScope": "ARCHIVE_SIDECAR",
  "consensusParticipation": "NONE",
  "expectedRoot": "0x...",
  "calculatedRoot": "0x...",
  "algorithmId": "ARCHIVE_SMT_V1",
  "registryChecksum": "0x...",
  "domains": [
    {
      "domainId": 1,
      "valid": true,
      "exists": true,
      "expectedDomainRoot": "0x...",
      "calculatedDomainRoot": "0x..."
    }
  ]
}
```

Verifier must not read latest Store. It may read:

```text
algorithm descriptor
domain registry descriptor for known domain ids/codecs
```

For offline verification, proof response should carry enough descriptors:

```text
algorithmId
treeKind
hashFunction
emptyHashVersion
domainId/domainName
keyCodecId/valueCodecId
registryChecksum
```

## 6. Root Resolution

### 6.1 ArchiveRootQuery

```java
public final class ArchiveRootQuery {
  private final Object blockSelector;
  private final Long txNum;
  private final Integer txIndex;
  private final boolean includeDomains;
  private final boolean allowOnDemand;
}
```

Resolution order:

```text
1. Parse block selector through L6 ArchiveStatePointResolver.
2. If txNum is null and txIndex is null:
     point = BLOCK_END
     root = ArchiveRootReader.rootAtBlock(blockNum)
3. If txNum present:
     validate txNum belongs to block range or resolve block by txNum.
     root = ArchiveRootReader.rootAtTxNum(txNum)
4. If txIndex present:
     resolve txNum from L2 BlockTxNumRange + logical tx index.
     root = ArchiveRootReader.rootAtTxNum(txNum)
5. If root missing and tx-level requested:
     if allowOnDemand && config.allowTxRootOnDemand:
       use ArchiveTxRootComputer within maxOnDemandReplayTx
     else:
       fail unsupported/missing root
6. Verify root coverage and registryChecksum.
```

Missing root handling:

```text
block-end root missing:
  internal error: archive root unavailable at block N

tx root missing + on-demand disabled:
  internal error or unsupported: tx root not persisted and on-demand disabled

tx root missing + on-demand enabled but replay too large:
  exceed limit
```

### 6.2 Header state root comparison

`ArchiveRootResult` may include header state root for clarity:

```text
headerStateRoot = block.header.raw.accountStateRoot
sameAsHeaderStateRoot = archiveRoot.equals(headerStateRoot)
```

But:

```text
ArchiveProofVerifier ignores headerStateRoot.
debug_getArchiveProof trust anchor is root.archiveRoot.
BlockResult.stateRoot remains unchanged.
```

### 6.3 Coverage checks

Before root/proof:

```text
archive enabled
commitment enabled
temporal progress >= point.asOfTxNum
commitment progress >= point.asOfTxNum
root.registryChecksum == ArchiveDomainRegistry.checksum
root.coverage includes requested target domain
domain.rootPolicy == IN_GLOBAL_ROOT for global proof
```

If registry changed:

```text
same checksum:
  OK

different checksum but registry descriptor available by checksum:
  verify with descriptor embedded/stored for that checksum

different checksum and descriptor unavailable:
  internal error: registry descriptor unavailable
```

P0 can simplify by requiring current registry checksum equals root checksum.

## 7. Proof Construction

### 7.1 Canonical target conversion

`ArchiveProofTargetResolver`:

```java
ResolvedArchiveProofTarget resolve(
    ArchiveProofTarget target,
    ArchiveStatePoint point,
    ArchiveStateReader reader,
    ArchiveDomainRegistry registry)
```

Rules:

```text
ACCOUNT:
  address -> address21
  domain = ACCOUNT
  canonicalKey = address21

CONTRACT:
  address -> address21
  domain = CONTRACT
  canonicalKey = address21

CODE:
  address -> address21
  domain = CODE
  canonicalKey = L3 code key for address/codeHash

CONTRACT_STORAGE:
  address -> address21
  slot -> DataWord slot32
  contract = reader.getContract(address21)
  if contract missing:
    exists=false proof for version selected by domain default? Better: fail target contract missing unless request explicitly allows missingContractStorage.
  version = contract.getContractVersion()
  canonicalKey = address21 || slot32 || version_u8

DYNAMIC_PROPERTY:
  property name -> registry key codec
  domain = DYNAMIC_PROPERTIES

RAW_DOMAIN_KEY:
  validate debug flag, domain rooted, key length/codec
```

For storage contract missing:

P0 should return a proof for missing `CONTRACT` target if the user asks `CONTRACT`, but for `CONTRACT_STORAGE` there is no stable version suffix without contract data. Recommended behavior:

```text
contract missing -> invalid params "cannot build storage proof for missing contract; request CONTRACT proof instead"
```

This avoids inventing storage keys not produced by L4.

### 7.2 Value lookup

Proof builder needs value bytes for two reasons:

```text
1. includeValue=true response.
2. verify leaf valueHash matches archive state value.
```

Value source:

```text
ArchiveStateReader.getAsOf(domain, canonicalKey, point.asOfTxNum)
RootValueNormalizer.normalize(domain, rawValue)
```

Not allowed:

```text
Wallet.getAccount
Wallet.getContract
ContractStore
CodeStore
StorageRowStore
DynamicPropertiesStore latest
```

Missing value:

```text
reader missing/tombstone -> exists=false
proof must verify absence under domainRoot
```

Corrupt value:

```text
codec decode error -> internal archive state corrupt
do not return missing proof
```

### 7.3 Domain proof builder

Proposed interface:

```java
public interface ArchiveDomainProofBuilder {
  ArchiveDomainProof build(
      ArchiveRootRecord root,
      DomainRootRecord domainRoot,
      ResolvedArchiveProofTarget target,
      boolean includeValue)
      throws ArchiveProofException;
}
```

Tree access:

```text
treeId = ArchiveTreeId.domain(domainId, algorithmId)
rootHash = domainRoot.domainRoot
path32 = CommitmentHash.path(domainId, canonicalKey)
node reader = ArchiveCommitmentContext.getNode(treeId, nodeHash)
```

Proof algorithm:

```text
1. Start at domainRoot.domainRoot.
2. Walk depth/path bits toward path32.
3. For each branch/compressed node:
     include encoded node and sibling hash/material needed by verifier.
4. If leaf path equals target path:
     exists=true; verify leaf canonicalKey/valueHash matches target.
5. If empty branch or divergent leaf:
     exists=false; include divergence proof.
6. Recompute root from proof nodes.
7. calculatedDomainRoot must equal domainRoot.domainRoot.
```

If L7 `SparseMerkleArchiveCommitmentTree` lacks proof API, L9 adds:

```java
public interface ArchiveCommitmentProofTree {
  ArchiveTreeProof prove(
      ArchiveCommitmentContext context,
      ArchiveTreeId treeId,
      byte[] rootHash,
      byte[] path32)
      throws ArchiveCommitmentException;
}
```

Do not mutate L7 root/current state while proving.

### 7.4 Global proof builder

Global tree proves domain root inclusion in global root.

Proposed global leaf:

```text
global canonical key = domainId_u16
global value = RootValueNormalizer.normalizeDomainRootRecord(domainRootRecord)
global path32 = H("tron.archive.global.domain.path.v1" || algorithmId || domainId_u16)
```

If L7 already defines a different global leaf encoding, L9 must consume that exact encoding through a shared `RootValueNormalizer` method. L9 must not invent a second global tree encoding.

Interface:

```java
public interface ArchiveGlobalProofBuilder {
  ArchiveGlobalProof build(
      ArchiveRootRecord root,
      DomainRootRecord domainRoot)
      throws ArchiveProofException;
}
```

Verification:

```text
calculatedGlobalRoot == root.globalRoot
domainLeafValueHash == hash(normalized DomainRootRecord)
domainRootRecord.domainRoot == domain proof domainRoot
```

### 7.5 Limits

`ArchiveProofLimitChecker` applies after each stage:

```text
before build:
  target count <= maxProofTargets

after each domain proof:
  accumulated node count <= maxProofNodes
  estimated JSON/encoded bytes <= maxProofBytes

on-demand root:
  replay tx count <= maxOnDemandReplayTx
```

Use deterministic byte size estimate from encoded proof nodes, not Java object shallow size.

## 8. Verification

### 8.1 Verifier interface

```java
public interface ArchiveProofVerifier {
  ArchiveProofVerificationResult verify(ArchiveProof proof);

  ArchiveProofVerificationResult verifyDomain(ArchiveDomainProof proof);

  ArchiveProofVerificationResult verifyGlobal(
      ArchiveRootResult root,
      ArchiveGlobalProof globalProof,
      List<ArchiveDomainProof> domainProofs);
}
```

No latest Store access. No archive DB access required for normal verification.

### 8.2 Domain verifier

For each `ArchiveDomainProof`:

```text
1. Validate proofFormat/treeKind/algorithmId.
2. Validate canonicalKey hashes to path.
3. Recompute leaf hash:
     exists=true:
       leaf = hash(domainId || canonicalKey || valueHash)
     exists=false:
       proof must end in empty/divergent node.
4. Recompute branch hashes bottom-up.
5. Compare calculatedDomainRoot with proof.domainRoot.
6. If includeValue:
     hash(value) == valueHash.
7. If exists=false:
     value/valueHash must be empty/null per format.
```

### 8.3 Global verifier

```text
1. Recompute each domain root proof.
2. Recompute global leaf for each DomainRootRecord.
3. Recompute global proof root.
4. Compare calculatedGlobalRoot with root.archiveRoot.
5. Compare registryChecksum/algorithmId across all pieces.
```

If `includeGlobalProof=false`:

```text
valid = domainProofs valid under claimed domainRoot
globalValid = null / not checked
message includes "global proof omitted"
```

P0 default should include global proof.

### 8.4 Failure codes

```text
MALFORMED_PROOF
UNSUPPORTED_PROOF_FORMAT
UNKNOWN_DOMAIN
REGISTRY_CHECKSUM_MISMATCH
KEY_HASH_MISMATCH
VALUE_HASH_MISMATCH
DOMAIN_ROOT_MISMATCH
GLOBAL_ROOT_MISMATCH
MISSING_GLOBAL_PROOF
UNEXPECTED_VALUE_FOR_MISSING_KEY
PROOF_LIMIT_EXCEEDED
```

`debug_verifyArchiveProof` returns these in result. `debug_getArchiveProof` treats any self-verification failure as internal error and does not return proof.

## 9. JSON-RPC Facade Flow

### 9.1 Access guard

```java
final class ArchiveDebugAccessGuard {
  void requireRootEnabled(RequestSource source)
      throws JsonRpcMethodNotFoundException, JsonRpcInternalException;

  void requireProofEnabled(RequestSource source)
      throws JsonRpcMethodNotFoundException, JsonRpcInternalException;
}
```

Rules:

```text
source != FULLNODE:
  method-not-found

archive.debug.enable=false or exposeJsonRpc=false:
  method-not-found

archive.enable=false:
  method-not-found

root request and commitment.enable=false:
  internal "archive commitment disabled"

proof request and proofEnable=false:
  method-not-found
```

Rationale:

- Disabled debug methods should not look like public API.
- Commitment disabled with debug explicitly exposed means operator asked for API but prerequisite missing; internal error is clearer.

### 9.2 TronJsonRpcImpl methods

```java
public ArchiveRootJsonResult debugGetArchiveRoot(ArchiveRootJsonRequest request)
    throws JsonRpcMethodNotFoundException, JsonRpcInvalidParamsException,
    JsonRpcExceedLimitException, JsonRpcInternalException {
  archiveDebugAccessGuard.requireRootEnabled(getSource());
  return archiveDebugFacade.getRoot(request);
}

public ArchiveProofJsonResult debugGetArchiveProof(ArchiveProofJsonRequest request)
    throws JsonRpcMethodNotFoundException, JsonRpcInvalidParamsException,
    JsonRpcExceedLimitException, JsonRpcInternalException {
  archiveDebugAccessGuard.requireProofEnabled(getSource());
  return archiveDebugFacade.getProof(request);
}

public ArchiveProofVerificationJsonResult debugVerifyArchiveProof(ArchiveProofJsonResult proof)
    throws JsonRpcMethodNotFoundException, JsonRpcInvalidParamsException,
    JsonRpcInternalException {
  archiveDebugAccessGuard.requireProofEnabled(getSource());
  return archiveDebugFacade.verifyProof(proof);
}
```

`ArchiveDebugFacade` maps:

```text
ArchiveInvalidTargetException -> JsonRpcInvalidParamsException
ArchiveProofLimitException -> JsonRpcExceedLimitException
ArchiveHistoryUnavailableException -> JsonRpcInternalException
ArchiveCommitmentException -> JsonRpcInternalException
```

### 9.3 eth_getProof remains unavailable

Do not add:

```java
@JsonRpcMethod("eth_getProof")
```

If future compatibility pressure requires declaration, implementation must be:

```java
throw new JsonRpcMethodNotFoundException(
    "the method eth_getProof does not exist/is not available; use debug_getArchiveProof for archive sidecar proof");
```

P0 tests should assert method-not-found.

### 9.4 debug_traceCall remains unavailable

Do not add:

```java
@JsonRpcMethod("debug_traceCall")
```

If existing method appears later, L9 tests must assert it does not use archive proof path and does not create `./vm_trace` files.

## 10. Test Plan

### 10.1 chainbase proof unit tests

```text
ArchiveRootResultTest
  block-end root result includes ARCHIVE_SIDECAR/NONE/headerStateRoot
  tx root result marks ROOT_BY_TX or ON_DEMAND_REPLAY
  registry checksum mismatch fails

ArchiveProofTargetResolverTest
  ACCOUNT address -> address21 key
  CONTRACT_STORAGE address+slot -> address21||slot32||version
  CONTRACT_STORAGE missing contract fails with invalid target
  RAW_DOMAIN_KEY disabled fails
  unknown domain fails

ArchiveDomainProofBuilderTest
  existing account proof verifies
  existing contract proof verifies
  existing code proof verifies
  existing storage proof verifies
  missing account returns non-existence proof
  deleted/tombstone key returns non-existence proof
  branch collapse/delete sibling proof verifies
  corrupt node/root mismatch fails before return

ArchiveGlobalProofBuilderTest
  domain root inclusion verifies under global root
  wrong domain root fails global verification
  missing rooted domain fails

ArchiveProofVerifierTest
  valid proof passes
  modified canonicalKey fails KEY_HASH_MISMATCH
  modified value fails VALUE_HASH_MISMATCH
  modified domain root fails DOMAIN_ROOT_MISMATCH
  modified global root fails GLOBAL_ROOT_MISMATCH
  missing key with non-empty value fails UNEXPECTED_VALUE_FOR_MISSING_KEY
  unsupported proof format fails

ArchiveProofLimitCheckerTest
  max targets exceeded
  max nodes exceeded
  max bytes exceeded
  max on-demand replay tx exceeded
```

### 10.2 framework JSON-RPC tests

```text
TronJsonRpcArchiveDebugTest
  default off -> debug_getArchiveRoot method-not-found
  archive enabled but debug expose off -> method-not-found
  FullNode enabled -> root call reaches facade
  Solidity source -> method-not-found
  PBFT source -> method-not-found
  commitment disabled with debug exposed -> internal commitment disabled
  proof disabled -> debug_getArchiveProof method-not-found
  malformed target -> invalid params
  limit exceeded -> -32005
  self-verification failure -> internal error, no proof returned
  debug_verifyArchiveProof invalid proof -> valid=false result
  eth_getProof -> method-not-found
  debug_traceCall -> method-not-found or existing unsupported
  BlockResult.stateRoot unchanged while archive root exists
  debug_getArchiveProof does not create ./vm_trace files
```

### 10.3 no latest Store leak tests

Use fake/spy stores that throw on read:

```text
AccountStore
ContractStore
CodeStore
StorageRowStore
DynamicPropertiesStore latest
```

Then:

```text
debug_getArchiveRoot succeeds using ArchiveRootReader
debug_getArchiveProof succeeds using ArchiveStateReader/CommitmentNodeRecord
debug_verifyArchiveProof succeeds without archive DB/latest stores
```

Allowed latest-ish reads:

```text
block header lookup for headerStateRoot/debug metadata
JSON-RPC source detection through wallet cursor
```

Not allowed:

```text
state value fallback to latest stores
domain root fallback to header accountStateRoot
proof value fallback to Wallet
```

### 10.4 gate commands

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
./gradlew checkstyleMain checkstyleTest
```

本轮只写规划，不运行这些命令。

## 11. Review checklist

编码评审时逐项确认：

- `TronJsonRpc.java` 新增的是 `debug_getArchiveRoot/debug_getArchiveProof/debug_verifyArchiveProof`，没有新增 `eth_getProof`。
- `debug_traceCall` 没有在 L9 实现。
- default config 下三个 debug 方法 method-not-found。
- Solidity/PBFT source method-not-found。
- root/proof response 强制包含 `rootScope=ARCHIVE_SIDECAR` 和 `consensusParticipation=NONE`。
- proof trust anchor 是 `ArchiveRootRecord.globalRoot`，不是 `BlockHeader.accountStateRoot`。
- `BlockResult.stateRoot` 未改。
- `ArchiveProofService` 放在 chainbase，不依赖 framework `TrieImpl`。
- framework 只做 JSON DTO/guard/adapter，不 walk tree。
- proof builder 读取 `COMMITMENT_BRANCH` node records，不读 latest Store。
- proof value 读取 L6 `ArchiveStateReader`，不读 `Wallet`。
- missing key 有 non-existence proof。
- tombstone/delete/collapse sibling 场景有测试。
- `debug_getArchiveProof` 返回前调用 verifier。
- `debug_verifyArchiveProof` 无效 proof 返回 `valid=false`，不抛 internal。
- proof limits 对 targets/nodes/bytes/on-demand replay 都生效。
- `RAW_DOMAIN_KEY` 默认关闭。
- tx-level root 不持久化时只在配置允许且 replay limit 内 on-demand 计算。
- proof/debug 调用不创建 `./vm_trace` 文件。
- no latest Store leak tests 覆盖 account/contract/code/storage/dynamic stores。

## 12. 后续扩展

L9 P0 完成后，可独立追加：

```text
L9.1 public archive proof namespace:
  add rate limits/auth/API docs if operators want expose beyond debug.

L9.2 eth_getProof compatibility adapter:
  only if TRON defines a precise Ethereum-compatible projection.
  Must not claim header-root proof.

L9.3 debug_traceCall:
  build on L8 historical VM with per-call in-memory trace capture.
  Must not use global VMConfig.vmTrace file output.

L9.4 richer domain coverage:
  votes/delegation/assets/resource proofs after L3/L4 add rooted domains.

L9.5 proof descriptor export:
  standalone verifier package and JSON schema for external tools.
```

这些扩展不能混入 P0。P0 的验收重点是：archive-native root/proof/verify 可用、默认关闭、FullNode-only、无 latest state 泄漏、不伪装 Ethereum/header proof。
