# java-tron Archive S14：proof/debug API 4e80 编码执行包

日期：2026-06-04

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`，`git status --short` 为空，精确冲突标记扫描无命中。

Erigon 源码路径：`/Users/boson/GolandProjects/erigon`

归属路线：[java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)

前置依赖：

- [S3 ArchiveDomainRegistry 4e80 编码执行包](./20260603-java-tron-archive-s3-domain-registry-4e80-coding-packet.md)：domain id、root policy、canonical key/value codec、coverage。
- [S6/S7 ArchiveTemporalStore 4e80 编码执行包](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md)：as-of state lookup、archive progress、single archive DB。
- [S8/S9 ArchiveStateReader 与 JSON-RPC Historical Getters 4e80 编码执行包](./20260603-java-tron-archive-s8-s9-state-reader-rpc-4e80-coding-packet.md)：`ArchiveStatePoint`、block/hash/tx point resolver、historical getters。
- [S10/S11 CommitmentBuilder 4e80 编码执行包](./20260603-java-tron-archive-s10-s11-commitment-builder-4e80-coding-packet.md)：`ArchiveRootRecord`、domain root、global root、content-addressed branch nodes、root progress verifier。
- [S12/S13 historical eth_call 4e80 编码执行包](./20260604-java-tron-archive-s12-s13-historical-eth-call-4e80-coding-packet.md)：historical VM read path，S14 不依赖 traceCall。

S14 暴露默认关闭的 archive-native proof/debug API。它证明的是 java-tron archive sidecar commitment root，不是 TRON 区块头 root，不是 Ethereum `eth_getProof`，不参与共识。

## 1. 交付边界

S14 交付三个 debug JSON-RPC 方法：

```text
debug_getArchiveRoot
debug_getArchiveProof
debug_verifyArchiveProof
```

核心调用链：

```text
JSON-RPC request
  -> ArchiveDebugFacade
  -> ArchiveStatePointResolver
  -> ArchiveRootReader
  -> ArchiveProofService
  -> DomainProofBuilder
  -> GlobalProofBuilder
  -> ArchiveProofVerifier
```

本批次做：

1. 查询 block-end archive root。
2. 在配置允许时查询 tx-level root；默认优先读已持久化 `ROOT_BY_TX`，未持久化时按限额 on-demand replay。
3. 对 rooted domain 生成 domain key proof。
4. 对 domain root 生成 global domain proof，证明 domain root 被 global root 收录。
5. 对 proof result 做本地自校验后再返回。
6. 对外响应固定携带 `rootScope=ARCHIVE_SIDECAR`、`consensusParticipation=NONE`、`algorithmId`、`registryChecksum`、`coverage`。

本批次不做：

- 不实现 Ethereum-compatible `eth_getProof`。
- 不把 archive root 写入 `BlockHeader.raw.accountStateRoot`。
- 不修改 JSON-RPC `BlockResult.stateRoot`。
- 不把 archive proof 解释成 block header proof。
- 不默认持久化 every-tx root。
- 不实现 high-QPS public proof 服务。
- 不实现 root node GC。
- 不实现 `debug_traceCall`。
- 不打开现有全局 `vmTrace`，也不写 `./vm_trace/*.json`。

完成条件：

1. archive disabled 或 commitment disabled 时，三个 debug 方法默认不可用。
2. root/proof API 只在 FullNode JSON-RPC 源可用，PBFT/Solidity 源默认返回 method not found。
3. `debug_getArchiveRoot` 返回的 root 必须来自 `ArchiveRootRecord` 或受限 on-demand root computer，不读 latest Store。
4. `debug_getArchiveProof` 生成 domain proof 和 global proof 后先调用 verifier，校验失败不返回 proof。
5. `debug_verifyArchiveProof` 能在不读取 latest Store 的情况下验证请求体内 proof。
6. missing key 返回可验证的 non-existence proof，而不是简单 `null`。
7. `eth_getProof` 仍未声明；若请求它，维持 method not found。
8. `BlockResult.stateRoot` 仍等于 header `accountStateRoot`。

## 2. issue #6289 范围复核

2026-06-04 通过 `gh issue view 6289 --repo tronprotocol/java-tron` 复核，issue 仍为 open，labels 包含 `topic:archive node`、`topic:DB`、`type:feature`。

issue 正文的 P0 Ethereum-compatible historical interfaces 是：

```text
eth_getBalance
eth_getCode
eth_getStorageAt
eth_call
```

issue 把后续讨论项单独列出：

```text
debug_traceCall
eth_getTransactionCount
eth_getProof
```

issue 还明确了 TRON 当前差异：

- TRON header 有 `txTrieRoot` 和 `accountStateRoot`，但 `accountStateRoot` 不等价于 Ethereum state root。
- TRON state data 分散在多类 DB，contract data、TRC10、votes、delegation 等不统一封装在 account 内。
- archive branch 首期 stateRoot 不参与共识，以降低对 SR 和普通 FullNode 的影响。

S14 因此只能实现 archive-native debug proof：

```text
archive sidecar proof
not Ethereum MPT proof
not consensus state proof
not header-root proof
```

## 3. 4e80 源码锚点

### 3.1 JSON-RPC 暴露方式

| 源码 | 当前事实 | S14 约束 |
| --- | --- | --- |
| `TronJsonRpc.java:90-107` | 已声明 `eth_getBalance`、`eth_getStorageAt`、`eth_getCode` | S14 不改这些方法签名 |
| `TronJsonRpc.java:154-170` | 已声明 `eth_getBlockReceipts`、`eth_call` | S14 不混入 proof 语义 |
| `TronJsonRpc.java:251-256` | `eth_getTransactionCount` 已声明但返回 method-not-found | discussion 方法保持不实现 |
| 全 `jsonrpc` 包 | 无 `eth_getProof`、无 `debug_traceCall`、无 `debug_*Archive*` | S14 新增 archive-native debug 方法，不新增 `eth_getProof` |
| `TronJsonRpcImpl.java:1313-1318` | `disableInPBFT(method)` 已有 PBFT method-not-found gate | S14 debug 方法复用同类 source guard |
| `TronJsonRpcImpl.java:1395-1400` | `eth_getTransactionCount` 实现为 method-not-found | future/discussion 方法可保留此模式 |

`JsonRpcServlet` 只把一个 `TronJsonRpc` bean 暴露成 composite service：

| 源码 | 当前事实 | S14 约束 |
| --- | --- | --- |
| `JsonRpcServlet.java:64-79` | 注入 `TronJsonRpc`，`ProxyUtil.createCompositeServiceProxy(..., new Object[]{tronJsonRpc}, new Class[]{TronJsonRpc.class}, true)` | 最小接入是在 `TronJsonRpc` 接口增加 `@JsonRpcMethod("debug_*")` |
| `JsonRpcServlet.java:81-83` | `JsonRpcServer` 使用 `JsonRpcErrorResolver.INSTANCE` | S14 错误码通过接口注解映射 |
| `FullNodeJsonRpcHttpService.java:23-32` | FullNode JSON-RPC 端口由 `Args` 控制，servlet 挂 `/jsonrpc` | S14 不新增 HTTP endpoint |
| `JsonRpcOnSolidityServlet` / `JsonRpcOnPBFTServlet` | Solidity/PBFT 也继承 JSON-RPC servlet 模式 | S14 debug 方法默认要限制源，避免 solidity/pbft 暴露 |

结论：

```text
P0 不另建 /debug endpoint。
P0 在 TronJsonRpc 接口声明 debug_* 方法。
实现里调用 ArchiveDebugFacade，并按 source/config fail-fast。
```

### 3.2 JSON-RPC 参数和错误模型

| 源码 | 当前事实 | S14 约束 |
| --- | --- | --- |
| `JsonRpcApiUtil.java:583-600` | `latest/earliest/finalized` tag 解析；`pending/safe` 明确 unsupported | S14 state point resolver 复用 tag 语义 |
| `JsonRpcApiUtil.java:617-635` | `parseBlockNumber` 对 block number 做长度、负数、overflow guard | S14 block number/tx number 解析复用该防御风格 |
| `JsonRpcErrorResolver.java:22-45` | 根据 `@JsonRpcErrors` 把异常映射为 JSON-RPC error | S14 接口必须声明 method-not-found、invalid-params、internal-error、limit error |
| `JsonRpcException.java:7-29` | 支持 error data | `debug_verifyArchiveProof` 的失败原因可以放 structured data |
| `JsonRpcInternalException.java:17-19` | 支持 message + data | proof verification mismatch 可带 expected/actual/root metadata |

建议错误映射：

| 场景 | 异常 | JSON-RPC code |
| --- | --- | --- |
| debug API 未启用 | `JsonRpcMethodNotFoundException` | `-32601` |
| 参数格式错误、未知 domain/target | `JsonRpcInvalidParamsException` | `-32602` |
| proof/root 超过限额 | `JsonRpcExceedLimitException` | `-32005` |
| archive 缺口、commitment disabled、root mismatch | `JsonRpcInternalException` | `-32000` |

默认未启用时建议返回 method not found，而不是 exposed-but-disabled：

```text
debug API 默认关闭
  -> external method discovery sees no supported behavior
  -> 减少误用为 public consensus proof 的风险
```

### 3.3 配置链路

4e80 的 storage 配置链路是：

```text
reference.conf
  -> StorageConfig.fromConfig(config)
  -> Args.applyStorageConfig(StorageConfig)
  -> CommonParameter.storage
  -> archive service/runtime checks
```

源码锚点：

| 源码 | 当前事实 | S14 约束 |
| --- | --- | --- |
| `reference.conf:100-135` | `storage` 节已有 db、balance history、checkpoint、txCache、snapshot 默认值 | S14 新增配置应放在 `storage.archive.debug`，保持默认关闭 |
| `StorageConfig.java:21-33` | `StorageConfig` 是 storage bean，当前没有 archive config | S14 新增 `ArchiveConfig`/`ArchiveDebugConfig` 嵌套 bean |
| `StorageConfig.java:173-188` | `fromConfig(config)` 读取 `storage` 节并做 post-process | S14 archive 参数校验放在 `ArchiveDebugConfig.postProcess()` |
| `Args.java:212-244` | `applyStorageConfig(StorageConfig)` 把 storage bean 写入 `CommonParameter.storage` | S14 debug/proof 限额在这里集中桥接，不走散落 HOCON 读取 |
| `Args.java:713-716` | 初始化 `PARAMETER.storage = new Storage()` 后读取 `StorageConfig` | archive runtime config 挂在 `CommonParameter.storage.archive` 或等价 holder |
| `reference.conf:401-426`、`NodeConfig.java:233-250`、`Args.java:548-562` | `node.jsonrpc` 只定义 JSON-RPC HTTP enable、端口和通用请求/响应限额 | S14 可以参考这些通用限额，但 archive debug 开关不应放入 `NodeConfig.JsonRpcConfig` |
| `CommonParameter.java:463-490` | JSON-RPC runtime 参数存在 singleton 中 | JSON-RPC adapter 不应直接反复解析 HOCON |

建议新增配置：

```hocon
storage {
  archive {
    enable = false
    commitment {
      enable = false
    }
    debug {
      enable = false
      exposeJsonRpc = false
      proofEnable = false
      verifyBeforeReturn = true
      includeGlobalProofByDefault = true
      allowTxRootOnDemand = false
      maxOnDemandReplayTx = 2000
      maxProofNodes = 1024
      maxProofBytes = 1048576
      maxProofTargets = 16
    }
  }
}
```

规则：

- `storage.archive.enable=false`：所有 S14 方法返回 method not found。
- `storage.archive.commitment.enable=false`：root/proof 返回明确 commitment disabled。
- `debug.exposeJsonRpc=false`：JSON-RPC 方法返回 method not found。
- `debug.proofEnable=false`：root 可查，proof/verify 关闭。
- `allowTxRootOnDemand=false`：未持久化 tx root 时返回 unsupported。
- `maxOnDemandReplayTx` 限制从 nearest checkpoint/root replay 的 tx 数。
- `maxProofNodes`、`maxProofBytes` 限制输出规模。
- `verifyBeforeReturn=true` 是 P0 强制默认，不建议关闭。

### 3.4 header root 不是 archive root

| 源码 | 当前事实 | S14 约束 |
| --- | --- | --- |
| `BlockResult.java:101-104` | JSON-RPC `transactionsRoot` 来自 header `txTrieRoot`，`stateRoot` 来自 header `accountStateRoot` | S14 不修改 `BlockResult.stateRoot` |
| `BlockCapsule.java:218-230` | `calcMerkleRoot()` 只计算交易 Merkle root | archive root 不接入 tx root |
| `BlockCapsule.java:233-244` | `validateMerkleRoot()` 校验 header `txTrieRoot` | archive proof 不经过此校验 |
| `BlockCapsule.java:246-253` | `setMerkleRoot()` 写 header `txTrieRoot` | S14 不调用 |
| `BlockCapsule.java:255-262` | `setAccountStateRoot(byte[])` 写 header `accountStateRoot` | S14 不调用 |

S14 response 必须把 root 语义写清楚：

```json
{
  "rootScope": "ARCHIVE_SIDECAR",
  "consensusParticipation": "NONE",
  "headerField": null,
  "headerStateRoot": "0x...",
  "archiveRoot": "0x...",
  "sameAsHeaderStateRoot": false
}
```

`sameAsHeaderStateRoot` 只作为调试字段，不作为任何信任前提。即使偶然相等，也不能把 archive proof 解释成 header proof。

### 3.5 framework TrieImpl 只能参考，不能复用到 chainbase

| 源码 | 当前事实 | S14 约束 |
| --- | --- | --- |
| `TrieImpl.java:290-293` | `getRootHash()` 返回 framework trie root | 可参考 root API 形态 |
| `TrieImpl.java:355-379` | `scanTree` 可遍历 node/value | 可参考 test helper 形态 |
| `TrieImpl.java:381-429` | `prove(byte[] key)` 生成 proof node map | 可参考 existence proof 输出 |
| `TrieImpl.java:490-557` | `verifyProof(rootHash,key,nodeMap)` 验证 proof | 可参考 verifier API 边界 |

包边界仍是：

```text
framework -> chainbase
chainbase -/-> framework
```

S14 的 `ArchiveProofService`、`DomainProofBuilder`、`ArchiveProofVerifier` 应放在 chainbase archive 包内，不能 import `org.tron.core.trie.TrieImpl`。如果未来要复用 `TrieImpl`，需要先把 trie core 下沉到独立基础包，不应在 S14 中做。

### 3.6 现有 VM trace 不是 S14 traceCall 基础

| 源码 | 当前事实 | S14 约束 |
| --- | --- | --- |
| `reference.conf:758-765` | `vm.vmTrace=false` 全局开关 | S14 不打开它 |
| `ConfigLoader.java:16-20` | 从 `CommonParameter.isVmTrace()` 写入 `VMConfig` | trace 是进程级状态 |
| `VM.java:31-34` | 每个 opcode 处根据 `VMConfig.vmTrace()` 调 `program.saveOpTrace()` | 不是 per-call capture |
| `Program.java:1608-1615` | `saveOpTrace` 写入 `ProgramTrace` | 可作为后续 trace hook 参考 |
| `ProgramTrace.java:14-29`、`73-84` | trace model 保存 op 列表 | 仅为后续 PR 参考 |
| `VMActuator.java:297-309` | 执行后把 trace 写文件 | S14 不触发 |
| `VMUtils.java:52-98` | 文件路径是 `./vm_trace/<tx>.json` | debug API 不写文件 |

结论：

```text
S14 不实现 debug_traceCall。
后续 traceCall 必须做 per-call in-memory capture。
不能为了 historical trace 打开全局 vmTrace。
```

## 4. Erigon 对照结论

### 4.1 eth_getProof 绑定 header state root

Erigon `eth_getProof` 的关键不变量：

| 源码 | Erigon 行为 | java-tron S14 迁移 |
| --- | --- | --- |
| `rpc/jsonrpc/eth_call.go:400-432` | `GetProof` 解析 block，检查 prune history，然后调用 `getProof` | S14 也要先 resolve state point 和 archive coverage |
| `eth_call.go:457-506` | proof trie root 必须等于 header `Root`，否则报 mismatch | java-tron 不能用 header root，必须等于 `ArchiveRootRecord.globalRoot` |
| `eth_call.go:518-523` | 对 account path 调 `Prove` 并写 response | S14 对 domain key path 生成 proof |
| `eth_call.go:547-562` | storage proof root 同样比对 header root | S14 global proof 比对 archive global root |
| `eth_call.go:610-622` | 返回前验证 account proof 和 storage proof | S14 返回前必须验证 domain proof 和 global proof |

不能迁移的部分：

```text
Ethereum account fields: nonce, balance, storageHash, codeHash
Ethereum storage root
Ethereum MPT path: keccak(address), keccak(slot)
header.Root trust anchor
```

应该迁移的部分：

```text
resolve point
check history availability
generate proof
verify proof before returning
return explicit errors on root mismatch
```

### 4.2 Erigon debug witness 的门槛

| 源码 | Erigon 行为 | java-tron S14 迁移 |
| --- | --- | --- |
| `debug_api.go:53-70` | debug API 是 private/debug namespace，包含 `ExecutionWitness` | S14 用 `debug_*` 命名，默认关闭 |
| `debug_execution_witness.go:523-539` | 没有 commitment history 直接报错 | S14 没有 archive commitment/root progress 直接报错 |
| `debug_execution_witness.go:558-560` | 用 exact txnum 构建 parent state reader | S14 tx-level proof 也必须绑定 txNum/asOf |
| `debug_execution_witness.go:677-680` | commitment history pruned 时 fail-fast | S14 archive gap/pruned range 不能 fallback latest |
| `debug_execution_witness.go:682-710` | 构造 witness 后做 stateless verification | S14 proof result 做 verifier self-check |
| `flags.go:1115-1119` | commitment history 需显式 flag | S14 proof API 需显式配置打开 |

Erigon 对 collapse sibling 的处理尤其重要：

| 源码 | 行为 | S14 单测 |
| --- | --- | --- |
| `debug_execution_witness.go:885-909` | collapse detection 先 compute commitment，root 必须等于 expected | 构造删除/合并节点场景，proof root 不可丢 sibling |
| `debug_execution_witness.go:915-960` | 重新 seek parent commitment，touch accessed key 和 sibling path，再生成 witness trie | S14 proof builder 需要包含 sibling/collapse 路径 |

### 4.3 Erigon trie proof 的存在/不存在语义

| 源码 | Erigon 行为 | java-tron S14 迁移 |
| --- | --- | --- |
| `execution/commitment/trie/proof.go:33-40` | missing key 返回最长已有前缀节点，用于证明 absence | S14 missing key 必须返回 non-existence proof |
| `proof.go:294-342` | account proof 校验 value 与 response account fields 一致 | S14 domain proof 校验 `valueHash/valueBytes/exists` 一致 |
| `proof.go:345-375` | empty storage root 对 proof/value 有严格约束 | S14 empty domain root 和 zero/empty proof 要有固定规则 |

### 4.4 stateless witness 的 trust anchor 模式

Erigon stateless witness 要求 parent header root 作为 pre-state trust anchor：

| 源码 | 行为 | S14 迁移 |
| --- | --- | --- |
| `execution/types/stateless/witness.go:36-70` | witness pre-state root 必须等于 parent header root | S14 proof trust anchor 是 `ArchiveRootRecord.globalRoot`，不是 header root |
| `witness.go:85-98` | witness 创建时强制读取 parent header | S14 root 查询时强制读取 root record 和 block metadata |
| `debug_execution_witness.go:1061-1091` | stateless re-execution root 不等于 block root则失败 | S14 verifier root 不等于 proof root则失败 |

## 5. S14 数据模型

### 5.1 StatePoint

复用 S8/S9 的 state point：

```java
final class ArchiveStatePoint {
  long blockNum;
  byte[] blockHash;
  long asOfTxNum;
  StatePointKind kind; // BLOCK_END or TX_END
  Integer txIndex;
  byte[] txId;
}
```

S14 额外要求：

- `blockHash` 必须来自 canonical block lookup。
- `asOfTxNum` 必须落在 archive txNum 覆盖范围内。
- `TX_END` 只允许指向真实 user/system tx 后状态，不允许 block start 虚点。
- object 参数若同时给 `blockHash` 与 `blockNumber`，必须校验一致。

### 5.2 ArchiveRootResult

```java
final class ArchiveRootResult {
  String rootScope;                 // ARCHIVE_SIDECAR
  String consensusParticipation;    // NONE
  String statePointKind;            // BLOCK_END / TX_END
  long blockNumber;
  String blockHash;
  Long txNum;
  Integer txIndex;
  String txId;
  String rootHash;                  // global archive root
  String rootSource;                // ROOT_BY_BLOCK / ROOT_BY_TX / ON_DEMAND_REPLAY
  String algorithmId;
  String registryChecksum;
  String coverage;
  List<DomainRootView> domains;
  String headerStateRoot;
  boolean sameAsHeaderStateRoot;
}
```

Rules:

- `rootHash` 是 archive global root。
- `domains` 只在 `includeDomains=true` 时返回。
- `headerStateRoot` 仅用于调试对照，不能作为 proof anchor。
- `rootSource=ON_DEMAND_REPLAY` 必须带 replay tx count。

### 5.3 Proof target

请求体：

```java
final class ArchiveProofRequest {
  Object block;              // "latest" / "0xN" / {"blockHash": "..."} / {"blockNumber": "..."}
  Long txNum;                // optional
  Integer txIndex;           // optional
  List<ArchiveProofTarget> targets;
  Boolean includeGlobalProof;
  Boolean includeValue;
}
```

target：

```java
final class ArchiveProofTarget {
  String kind;               // ACCOUNT / CONTRACT / CODE / CONTRACT_STORAGE / DYNAMIC_PROPERTY / RAW_DOMAIN_KEY
  String address;
  String slot;
  String domain;
  String key;
}
```

P0 target kinds：

| kind | Domain | Canonical key source |
| --- | --- | --- |
| `ACCOUNT` | Account domain | 21-byte TRON address codec |
| `CONTRACT` | Contract domain | contract address codec |
| `CODE` | Code domain | code key or contract address to code domain mapping from S3 |
| `CONTRACT_STORAGE` | Contract storage domain | `(contractAddress, slot)` semantic key from S4/S5 |
| `DYNAMIC_PROPERTY` | Dynamic property domain | registry-defined property key |
| `RAW_DOMAIN_KEY` | Any debug-allowed rooted domain | hex canonical key; admin/debug only |

不支持的 target：

- Ethereum `nonce` proof。
- Ethereum `storageRoot` proof。
- Ethereum `codeHash` proof as account field。
- TRON resource/vote/delegation domains，除非 S3 已定义 rooted domain codec 和 coverage。

### 5.4 ArchiveProof response

```java
final class ArchiveProof {
  ArchiveRootResult root;
  List<ArchiveDomainProof> domainProofs;
  ArchiveGlobalProof globalProof;
  String proofFormat;           // ARCHIVE_SMT_V1
  String verifierVersion;
  boolean verifiedBeforeReturn;
}
```

domain proof：

```java
final class ArchiveDomainProof {
  int domainId;
  String domainName;
  String treeKind;              // SPARSE_MERKLE_V1
  String keyCodecId;
  String valueCodecId;
  String logicalKey;
  String canonicalKey;
  String keyHash;
  boolean exists;
  String valueHash;
  String value;                 // optional, only includeValue=true
  List<ArchiveProofNode> nodes;
  String domainRoot;
  String calculatedDomainRoot;
}
```

global proof：

```java
final class ArchiveGlobalProof {
  String treeKind;
  String domainLeafKey;
  String domainLeafValueHash;
  List<ArchiveProofNode> nodes;
  String globalRoot;
  String calculatedGlobalRoot;
}
```

proof node：

```java
final class ArchiveProofNode {
  int depth;
  String pathPrefix;
  String nodeHash;
  String nodeType;              // EMPTY / LEAF / BRANCH / COMPRESSED
  String leftHash;
  String rightHash;
  String encoded;
}
```

P0 可以同时返回 structured fields 和 `encoded`。Verifier 以 `encoded` 为 canonical input，structured fields 用于 debug/readability。若二者不一致，verifier 失败。

### 5.5 Verification result

```java
final class ArchiveProofVerificationResult {
  boolean valid;
  String failureCode;
  String failureMessage;
  String rootScope;
  String consensusParticipation;
  String expectedRoot;
  String calculatedRoot;
  String algorithmId;
  String registryChecksum;
  List<ArchiveDomainProofVerification> domains;
}
```

Verifier 不应该读取 latest Store。可选读取：

- 当前支持的 algorithm descriptor。
- registry descriptor，用于检查 `registryChecksum` 和 codec id 是否已知。

为了离线验证，proof 内必须包含足够 descriptor：

```text
algorithmId
hashFunction
emptyHashVersion
treeKind
domainId/domainName
keyCodecId/valueCodecId
registryChecksum
```

## 6. Root 查询算法

### 6.1 block-end root

```text
resolve block selector
  -> blockNum/blockHash
  -> ArchiveRootReader.getRootByBlock(blockNum)
  -> verify blockHash matches root record
  -> load domain roots if requested
  -> return ArchiveRootResult
```

Fail-fast：

- root record missing。
- root record block hash 与 canonical block hash 不一致。
- root progress 落后 requested block。
- registry checksum 与当前 registry 不一致，除非 verifier 支持 historical registry descriptor。

### 6.2 tx-level root

优先级：

```text
if ROOT_BY_TX(txNum) exists:
  return persisted tx root
else if allowTxRootOnDemand:
  compute from nearest root checkpoint
else:
  unsupported
```

on-demand replay 规则：

1. 找到 `txNum` 所属 block 和 txIndex。
2. 找到 nearest root checkpoint：
   - same block start root，或 parent block-end root。
   - 如果 S11 已实现 intra-block checkpoint，优先用最近 checkpoint。
3. 用 S4/S5 `BlockWriteSet` 的 domain writes 按 canonical order replay 到目标 tx。
4. replay 数量超过 `maxOnDemandReplayTx` 直接拒绝。
5. 计算出的 root 只作为 response，不写入 `ROOT_BY_TX`，除非单独配置 `persistOnDemandTxRoot=true`，P0 不建议启用。

不能做：

- 不能从 latest Store 读取 value 修补 replay。
- 不能跳过 system transaction phase。
- 不能对 missing write set 猜测默认值。

## 7. Proof 生成算法

### 7.1 domain proof

```text
for each target:
  domain = ArchiveDomainRegistry.resolve(target)
  require domain.rootPolicy == IN_GLOBAL_ROOT
  canonicalKey = domain.keyCodec.encode(target)
  keyHash = CommitmentHash.path(domainId, canonicalKey)
  domainRoot = rootRecord.domainRoot(domainId)
  proofNodes = CommitmentNodeStore.collectPath(domainRoot, keyHash)
  leaf = decode leaf or absence boundary
  value = ArchiveStateReader.read(point, domain, canonicalKey)
  valueHash = domain.valueCodec.hash(value)
  verify domain proof
```

value handling：

- `includeValue=false`：response 不带 raw value，但 verifier 仍校验 `valueHash`。
- `exists=false`：`valueHash` 必须等于 canonical empty/tombstone hash。
- tombstone 与 missing 必须区分，按 S6/S7 temporal semantics。

### 7.2 global proof

```text
domainLeafKey = encodeDomainId(domainId)
domainLeafValue = hash(domainRoot, domain metadata)
globalRoot = rootRecord.globalRoot
globalProofNodes = collectPath(globalRoot, domainLeafKey)
verify domainLeafValue under globalRoot
```

global leaf value 建议包含：

```text
domainId
domainNameHash
domainRoot
rootPolicy
keyCodecId
valueCodecId
coverage
registryChecksum
```

这样可以防止 proof 被跨 domain 重放。

### 7.3 self verification before return

返回前执行：

```text
verifyDomainProof(domainProof, expectedDomainRoot)
verifyGlobalProof(globalProof, expectedGlobalRoot)
verify domainRoot in global leaf == domainProof.domainRoot
verify rootRecord.globalRoot == globalProof.globalRoot
verify registryChecksum == request-time registry checksum
verify proof node count and byte size limits
```

任何 mismatch 都返回 internal error，不返回半可信 proof。

## 8. JSON-RPC API

### 8.1 debug_getArchiveRoot

Request:

```json
[
  {
    "block": "0x1234",
    "txIndex": null,
    "txNum": null,
    "includeDomains": false
  }
]
```

Response:

```json
{
  "rootScope": "ARCHIVE_SIDECAR",
  "consensusParticipation": "NONE",
  "statePointKind": "BLOCK_END",
  "blockNumber": "0x1234",
  "blockHash": "0x...",
  "txNum": "0x...",
  "rootHash": "0x...",
  "rootSource": "ROOT_BY_BLOCK",
  "algorithmId": "ARCHIVE_SMT_V1",
  "registryChecksum": "0x...",
  "coverage": "PARTIAL_TRON_DOMAINS",
  "headerStateRoot": "0x...",
  "sameAsHeaderStateRoot": false
}
```

### 8.2 debug_getArchiveProof

Request:

```json
[
  {
    "block": {"blockNumber": "0x1234"},
    "targets": [
      {
        "kind": "CONTRACT_STORAGE",
        "address": "0x...",
        "slot": "0x..."
      }
    ],
    "includeGlobalProof": true,
    "includeValue": false
  }
]
```

Response top-level：

```json
{
  "root": {
    "rootScope": "ARCHIVE_SIDECAR",
    "consensusParticipation": "NONE",
    "blockNumber": "0x1234",
    "rootHash": "0x..."
  },
  "proofFormat": "ARCHIVE_SMT_V1",
  "verifiedBeforeReturn": true,
  "domainProofs": [],
  "globalProof": {}
}
```

### 8.3 debug_verifyArchiveProof

Request:

```json
[
  {
    "proof": {
      "root": {},
      "domainProofs": [],
      "globalProof": {}
    }
  }
]
```

Response:

```json
{
  "valid": true,
  "rootScope": "ARCHIVE_SIDECAR",
  "consensusParticipation": "NONE",
  "expectedRoot": "0x...",
  "calculatedRoot": "0x...",
  "algorithmId": "ARCHIVE_SMT_V1",
  "registryChecksum": "0x..."
}
```

Invalid proof 返回 `valid=false` 适合 debug；malformed request 仍返回 invalid params。

## 9. 包和文件规划

### 9.1 chainbase archive proof core

```text
chainbase/src/main/java/org/tron/core/archive/proof/
  ArchiveProofService.java
  DefaultArchiveProofService.java
  ArchiveRootReader.java
  ArchiveTxRootComputer.java
  ArchiveProofTarget.java
  ArchiveProofRequest.java
  ArchiveRootResult.java
  ArchiveProof.java
  ArchiveDomainProof.java
  ArchiveGlobalProof.java
  ArchiveProofNode.java
  ArchiveProofVerifier.java
  ArchiveProofVerificationResult.java
  DomainProofBuilder.java
  GlobalProofBuilder.java
  ProofLimit.java
  ProofSizeEstimator.java
```

### 9.2 chainbase commitment support

如果 S10/S11 尚未提供 proof path reader，S14 补：

```text
chainbase/src/main/java/org/tron/core/archive/commitment/
  CommitmentNodeStore.java
  CommitmentPathReader.java
  CommitmentProofCodec.java
  CommitmentProofWalker.java
```

要求：

- 只读 `COMMITMENT_BRANCH` / `ROOT_RECORD` / `COMMITMENT_META`。
- 不依赖 framework `TrieImpl`。
- 对 missing/corrupt node fail-fast。
- 对 node hash、encoded node、child hash 做一致性校验。

### 9.3 framework JSON-RPC adapter

```text
framework/src/main/java/org/tron/core/services/jsonrpc/types/
  ArchiveRootRequest.java
  ArchiveProofJsonRequest.java
  ArchiveVerifyProofJsonRequest.java
  ArchiveRootJsonResult.java
  ArchiveProofJsonResult.java
  ArchiveProofVerificationJsonResult.java

framework/src/main/java/org/tron/core/services/jsonrpc/
  ArchiveDebugJsonRpcAdapter.java
```

`TronJsonRpcImpl` 只做薄封装：

```java
@Override
public ArchiveRootJsonResult debugGetArchiveRoot(ArchiveRootRequest request)
    throws JsonRpcMethodNotFoundException, JsonRpcInvalidParamsException,
    JsonRpcInternalException, JsonRpcExceedLimitException {
  return archiveDebugJsonRpcAdapter.getArchiveRoot(request, getSource());
}
```

不建议把 proof 逻辑直接写进 `TronJsonRpcImpl`。这个类已经承担 block、tx、filter、call、buildTransaction 等大量职责，S14 只加 adapter call。

## 10. Patch 分片

### S14a：配置和 disabled behavior

Files:

```text
common/src/main/resources/reference.conf
common/src/main/java/org/tron/core/config/args/StorageConfig.java
common/src/main/java/org/tron/common/parameter/CommonParameter.java
framework/src/main/java/org/tron/core/config/args/Args.java
```

Tasks:

- 增加 archive debug/proof 配置默认值。
- 增加 `ArchiveDebugConfig` 或集中 archive config holder。
- 增加 unit test：默认 disabled。

Tests:

- 默认配置下 `debug_getArchiveRoot` 返回 method not found。
- `archive.enable=false` 即使 `debug.exposeJsonRpc=true` 也不可用。
- `proofEnable=false` 时 proof/verify 不可用。

### S14b：Root reader

Files:

```text
chainbase/src/main/java/org/tron/core/archive/proof/ArchiveRootReader.java
chainbase/src/main/java/org/tron/core/archive/proof/ArchiveRootResult.java
chainbase/src/test/java/org/tron/core/archive/proof/ArchiveRootReaderTest.java
```

Tasks:

- 读取 `ROOT_BY_BLOCK`。
- 可选读取 `ROOT_BY_TX`。
- 校验 block hash、asOfTxNum、registryChecksum、coverage。
- 输出 `ArchiveRootResult`。

Tests:

- block root round-trip。
- block hash mismatch fail。
- missing root fail。
- includeDomains 返回 rooted domains。
- `BlockResult.stateRoot` regression，确认不变。

### S14c：Tx root on-demand

Files:

```text
chainbase/src/main/java/org/tron/core/archive/proof/ArchiveTxRootComputer.java
chainbase/src/test/java/org/tron/core/archive/proof/ArchiveTxRootComputerTest.java
```

Tasks:

- `ROOT_BY_TX` 存在时优先读。
- 未持久化时按配置 on-demand replay。
- replay 使用 archive write set，不读 latest Store。
- 超过 replay limit 返回明确错误。

Tests:

- persisted tx root 优先。
- on-demand root 与 full block replay 中间 root 一致。
- replay limit 生效。
- missing write set fail，不 fallback。

### S14d：Domain proof builder/verifier

Files:

```text
chainbase/src/main/java/org/tron/core/archive/proof/DomainProofBuilder.java
chainbase/src/main/java/org/tron/core/archive/proof/ArchiveProofVerifier.java
chainbase/src/main/java/org/tron/core/archive/proof/ArchiveDomainProof.java
chainbase/src/main/java/org/tron/core/archive/proof/ArchiveProofNode.java
chainbase/src/test/java/org/tron/core/archive/proof/DomainProofBuilderTest.java
```

Tasks:

- 支持 existence proof。
- 支持 non-existence proof。
- 校验 value hash。
- 校验 empty/tombstone semantics。
- 校验 proof node encoding/hash。

Tests:

- account exists。
- contract storage exists。
- missing slot non-existence proof。
- tombstone 与 missing 区分。
- corrupt node hash verifier fail。
- corrupt value hash verifier fail。
- collapse/sibling path 场景。

### S14e：Global proof builder/verifier

Files:

```text
chainbase/src/main/java/org/tron/core/archive/proof/GlobalProofBuilder.java
chainbase/src/main/java/org/tron/core/archive/proof/ArchiveGlobalProof.java
chainbase/src/test/java/org/tron/core/archive/proof/GlobalProofBuilderTest.java
```

Tasks:

- 对 domain leaf 生成 global proof。
- global leaf value 绑定 domain metadata。
- 校验 domain root 被 global root 收录。
- 防跨 domain proof replay。

Tests:

- domain root included。
- wrong domain id fail。
- wrong registry checksum fail。
- wrong domain root fail。
- domain root policy not in global root fail。

### S14f：Proof service orchestration

Files:

```text
chainbase/src/main/java/org/tron/core/archive/proof/DefaultArchiveProofService.java
chainbase/src/test/java/org/tron/core/archive/proof/ArchiveProofServiceTest.java
```

Tasks:

- resolve target domain/key。
- 调 root reader。
- 调 domain/global builder。
- 统计 proof size。
- 返回前 self-verify。

Tests:

- multi-target proof。
- max targets limit。
- max proof nodes limit。
- max proof bytes limit。
- verifier disabled 不建议支持；如支持必须有测试证明默认开启。

### S14g：JSON-RPC wiring

Files:

```text
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java
framework/src/main/java/org/tron/core/services/jsonrpc/ArchiveDebugJsonRpcAdapter.java
framework/src/main/java/org/tron/core/services/jsonrpc/types/Archive*.java
framework/src/test/java/org/tron/core/services/jsonrpc/TronJsonRpcArchiveDebugTest.java
```

Tasks:

- 增加 `@JsonRpcMethod("debug_getArchiveRoot")`。
- 增加 `@JsonRpcMethod("debug_getArchiveProof")`。
- 增加 `@JsonRpcMethod("debug_verifyArchiveProof")`。
- 配置/source gate。
- 参数 JSON model。
- 错误映射。

Tests:

- default method not found。
- FullNode + enabled returns service result。
- PBFT source method not found。
- invalid target maps `-32602`。
- archive gap maps `-32000`。
- proof size limit maps `-32005`。
- `eth_getProof` still method not found。

## 11. Test matrix

| Scenario | Expected |
| --- | --- |
| archive disabled | all S14 methods method not found |
| debug exposed but commitment disabled | root/proof explicit internal error |
| block root exists | `debug_getArchiveRoot` returns sidecar root |
| block hash mismatch | fail, no proof returned |
| tx root persisted | tx-level root from `ROOT_BY_TX` |
| tx root not persisted and on-demand disabled | unsupported |
| tx root on-demand within limit | calculated root returned with replay metadata |
| tx root on-demand over limit | exceed limit |
| account exists | existence proof verifies |
| contract storage slot exists | domain proof and global proof verify |
| storage slot missing | non-existence proof verifies |
| deleted/tombstoned value | tombstone semantics verifies |
| corrupted proof node | verify returns invalid |
| corrupted global proof | verify returns invalid |
| wrong registry checksum | verify invalid or unsupported descriptor |
| proof for domain not rooted | invalid params |
| `BlockResult.stateRoot` | unchanged header accountStateRoot |
| `eth_getProof` | method not found |
| VM trace files | no `./vm_trace` files created |

No test may use `t.Skip`/skip gates.

## 12. Implementation checklist

- [ ] `debug_getArchiveRoot`/`debug_getArchiveProof`/`debug_verifyArchiveProof` are default off.
- [ ] S14 methods are FullNode-only unless explicitly configured otherwise.
- [ ] `eth_getProof` remains absent.
- [ ] `debug_traceCall` remains absent.
- [ ] `BlockResult.stateRoot` remains header `accountStateRoot`.
- [ ] root response always says `ARCHIVE_SIDECAR` and `NONE`.
- [ ] root response includes `algorithmId` and `registryChecksum`.
- [ ] proof response includes `coverage` and domain metadata.
- [ ] proof builder never reads latest Store.
- [ ] verifier can validate proof without latest Store.
- [ ] missing keys produce non-existence proof.
- [ ] tombstone and missing are not collapsed accidentally.
- [ ] global proof binds `domainId` and `domainRoot`.
- [ ] proof is self-verified before return.
- [ ] proof node and byte limits are enforced.
- [ ] on-demand tx root replay is bounded.
- [ ] archive gap/root mismatch fails loudly.
- [ ] existing VM global trace remains untouched.

## 13. Commands

Targeted tests after implementation:

```bash
./gradlew :common:test --tests '*Archive*Config*Test'
./gradlew :chainbase:test --tests '*ArchiveRootReaderTest'
./gradlew :chainbase:test --tests '*ArchiveTxRootComputerTest'
./gradlew :chainbase:test --tests '*DomainProofBuilderTest'
./gradlew :chainbase:test --tests '*GlobalProofBuilderTest'
./gradlew :chainbase:test --tests '*ArchiveProofServiceTest'
./gradlew :framework:test --tests '*TronJsonRpcArchiveDebugTest'
```

Before PR:

```bash
./gradlew build
```

If touching checkstyle-sensitive files:

```bash
./gradlew checkstyleMain checkstyleTest
```

## 14. Main risks

1. 用户把 archive proof 当成 consensus proof。Mitigation：method name 使用 `Archive`，response 固定 `ARCHIVE_SIDECAR/NONE`，不实现 `eth_getProof`。
2. proof builder 偷读 latest Store。Mitigation：proof core 放 chainbase archive 包，测试用 fake latest Store 设置不同值。
3. missing key 被返回为 `null`。Mitigation：non-existence proof 是强制测试项。
4. registry 变更导致旧 proof 无法解释。Mitigation：proof 携带 registry checksum 和 codec id；verifier 对未知 checksum fail-fast。
5. tx-level on-demand replay 成本失控。Mitigation：默认关闭，开启时限额。
6. global proof 被跨 domain 重放。Mitigation：global leaf value 绑定 domain id/name/root policy/codec/coverage。
7. VM trace 被误用。Mitigation：S14 不实现 `debug_traceCall`，测试确认不创建 `./vm_trace` 文件。
