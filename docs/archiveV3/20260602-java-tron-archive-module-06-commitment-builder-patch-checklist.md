# java-tron Archive 模块 06：CommitmentBuilder 逐文件 Patch 清单

日期：2026-06-02

> 2026-06-03 更新：本文是旧 `a79693e450` patch 清单。当前 `4e80f8ffa9a2` 的 Module 06 实现入口请先看 [java-tron Archive S10/S11：CommitmentBuilder 4e80 编码执行包](./20260603-java-tron-archive-s10-s11-commitment-builder-4e80-coding-packet.md)，旧行号和旧配置模型不可直接用于编码。

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

关联设计：[java-tron Archive 模块 06：CommitmentBuilder 细化设计](./20260521-java-tron-archive-module-06-commitment-builder.md)

java-tron 源码对照：[模块 06 CommitmentBuilder：4e80 java-tron 源码对照细化](./20260603-java-tron-module-06-commitment-builder-4e80-source-deep-dive.md)

Erigon 源码对照：[模块 06 CommitmentBuilder：Erigon 源码对照深挖](./20260601-java-tron-module-06-commitment-builder-erigon-source-deep-dive.md)

代码级实现规格：[java-tron Archive PR7 CommitmentBuilder 代码级实现规格](./20260602-java-tron-archive-pr7-commitment-builder-implementation-spec.md)

S10/S11 4e80 编码执行包：[java-tron Archive S10/S11：CommitmentBuilder 4e80 编码执行包](./20260603-java-tron-archive-s10-s11-commitment-builder-4e80-coding-packet.md)

S10 历史编码执行包：[java-tron Archive S10：Sparse Merkle Tree Core + Root Codecs 编码执行包](./20260602-java-tron-archive-s10-sparse-merkle-root-codecs-coding-packet.md)

S11 历史编码执行包：[java-tron Archive S11：CommitmentBuilder Integration + Rebuild Verifier 编码执行包](./20260602-java-tron-archive-s11-commitment-builder-integration-rebuild-coding-packet.md)

后续 Proof/Debug API 当前 4e80 编码入口：[java-tron Archive S14：proof/debug API 4e80 编码执行包](./20260604-java-tron-archive-s14-proof-debug-api-4e80-coding-packet.md)

后续 Proof/Debug API 历史规格：[java-tron Archive PR9 Proof/Debug API 代码级实现规格](./20260602-java-tron-archive-pr9-proof-debug-api-implementation-spec.md)

前置 patch 清单：

- [模块 01：ArchiveTxNumIndex 逐文件 Patch 清单](./20260602-java-tron-archive-module-01-txnum-index-patch-checklist.md)
- [模块 02：ArchiveDomainRegistry 逐文件 Patch 清单](./20260602-java-tron-archive-module-02-domain-registry-patch-checklist.md)
- [模块 03：ArchiveWriteCollector 逐文件 Patch 清单](./20260602-java-tron-archive-module-03-write-collector-patch-checklist.md)
- [模块 04：ArchiveTemporalStore 逐文件 Patch 清单](./20260602-java-tron-archive-module-04-temporal-store-patch-checklist.md)
- [模块 05：ArchiveStateReader 逐文件 Patch 清单](./20260602-java-tron-archive-module-05-state-reader-patch-checklist.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

旧清单原复核基线：本地 java-tron `a79693e450`。当前实现请以 `4e80f8ffa9a2` 细化文档为准。

## 1. 模块目标

模块 06 在 PR1-PR6 的 archive history 闭环上增加默认关闭的 archive sidecar root：

```text
BlockWriteSet
  -> CommitmentUpdateBatch
  -> domain sparse Merkle root
  -> global domain root
  -> RootRecord(BLOCK_END)
```

P0 只要求 block-end sidecar root：

- `BLOCK_END(blockNum)` 可查 `RootRecord`。
- root rows 与 temporal rows 同一个 physical `archive` DB batch 提交。
- hot unwind 恢复 `ROOT_CURRENT`，并恢复或重建 `ROOT_LEAF` metadata。
- latest rebuild 能验证 incremental root。
- 不写共识区块头。
- 不实现公开 proof API。
- 不默认持久化 every-tx root。

P0 root 是：

```text
root_scope = ARCHIVE_SIDECAR
consensus_participation = NONE
coverage = TVM_STATE_ONLY 或 PARTIAL_DOMAIN_SET
```

不能把它称为完整 TRON consensus state root。

## 2. 当前源码事实

### 2.1 区块头已有两个 root 字段，但都不是 archive global root

| 文件 | 位置 | 源码事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/protocol/src/main/protos/core/Tron.proto:504-513` | `txTrieRoot/accountStateRoot` | 区块交易列表 Merkle root 与账户状态 root 字段 |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/capsule/BlockCapsule.java:218-230` | `calcMerkleRoot` | 用交易 `getMerkleHash()` 构建 tx root，空交易返回 `Sha256Hash.ZERO_HASH` |
| `BlockCapsule.java:233-244` | `validateMerkleRoot` | 校验交易 `txTrieRoot` |
| `BlockCapsule.java:246-253` | `setMerkleRoot` | 写 `txTrieRoot` |
| `BlockCapsule.java:255-262` | `setAccountStateRoot` | 写 `accountStateRoot` |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/services/jsonrpc/types/BlockResult.java:101-104` | JSON-RPC block result | 暴露 `transactionsRoot/stateRoot`，archive root 不静默复用 |

结论：

- `txTrieRoot` 是交易 root，不是状态 root。
- `accountStateRoot` 是已有 header 字段，PR7 不写它。
- archive root 写 sidecar DB，不参与共识校验。

### 2.2 accountStateRoot 管线只覆盖轻量账户状态

| 文件 | 位置 | 源码事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/common/src/main/resources/reference.conf:812` | `allowAccountStateRoot = 0` | 默认关闭 |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/config/args/Args.java:462` | `allowAccountStateRoot` | 从配置读取 `cc.getAllowAccountStateRoot()` |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/store/DynamicPropertiesStore.java:789-793` | init | 缺省时写入 `CommonParameter.getAllowAccountStateRoot()` |
| `DynamicPropertiesStore.java:2375-2378` | `saveAllowAccountStateRoot` | governance 开关写入 |
| `DynamicPropertiesStore.java:2380-2389` | `allowAccountStateRoot` | 开关为 1 时才启用 |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/db/accountstate/callback/AccountStateCallBack.java:52-72` | `preExecute` | 从 parent block header 的 `accountStateRoot` 初始化 trie |
| `AccountStateCallBack.java:38-42` | `exeTransFinish` | 每 tx 后把 account dirty entries put 到 trie |
| `AccountStateCallBack.java:74-92` | `executePushFinish` | 接收 block 时校验 header root |
| `AccountStateCallBack.java:94-105` | `executeGenerateFinish` | 本地产块时写 header `accountStateRoot` |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/db/accountstate/AccountStateEntity.java:16-22` | constructor | 只保留 `address/balance/allowance` |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/db/accountstate/AccountStateCallBackUtils.java:13-22` | `accountCallBack` | 只消费 `AccountCapsule` |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/store/AccountStore.java:68-88` | `AccountStore.put` | 写 account 后触发 callback |
| `AccountStore.java:92-104` | `AccountStore.delete` | 删除 account 时没有触发 `deleteAccount` callback |

结论：

- `AccountStateEntity` 不是完整 `Account` protobuf。
- accountStateRoot 不覆盖 contract、code、storage、dynamic properties。
- CommitmentBuilder 只能借鉴它的生命周期，不复用它的 root 语义。

### 2.3 `TrieImpl` 位于 framework，不能直接给 chainbase archive 核心复用

| 文件 | 位置 | 源码事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/trie/Trie.java:8` | `getRootHash` | trie 接口 |
| `Trie.java:17` | `put` | put |
| `Trie.java:21` | `delete` | delete |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/trie/TrieImpl.java:33` | `TrieImpl` | 实现位于 `framework` |
| `TrieImpl.java:144-155` | `put` | 空 value 走 delete |
| `TrieImpl.java:286-288` | `getRootHash` | 返回 root hash；空 root 返回 `EMPTY_TRIE_HASH` |
| `TrieImpl.java:297-305` | `flush` | dirty nodes 持久化后 root 收缩成 hash node |
| `TrieImpl.java:377-424` | `prove` | RLP Hex Patricia proof 参考 |
| `TrieImpl.java:486-552` | `verifyProof` | proof verifier 参考 |
| `TrieImpl.java:559-564` | `setRoot` | 把 `EMPTY_TRIE_HASH` 视为空树 |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/db/accountstate/storetrie/AccountStateStoreTrie.java:19` | `AccountStateStoreTrie` | framework 里的 account trie store |

模块依赖方向是：

```text
framework -> chainbase
chainbase -/-> framework
```

PR7 的 archive 核心在 `chainbase`，不能 import `framework` 的 `TrieImpl`。如果未来要复用 `TrieImpl`，应先做独立 trie 包下沉，这是单独重构，不放进 PR7。

### 2.4 Erigon commitment pipeline 给出的硬约束

| Erigon 位置 | 约束 |
| --- | --- |
| `execution/commitment/commitment.go:92` | `Trie` 只通过 context 做 IO，不直接依赖 DB |
| `commitment.go:130` | `PatriciaContext` 抽象 branch/account/storage 读写 |
| `commitment.go:1429` | `Updates` 保存 touched/update key |
| `commitment.go:1585` | `TouchPlainKey` 支持 serialized value route |
| `commitment.go:1624` | `TouchPlainKeyDirect` 支持 direct structured update route |
| `commitment.go:1797` | `HashSort` 按 hashed key 顺序处理 |
| `commitment.go:1981` | `keyUpdateLessFn` hashed key 优先、plain key tie-breaker |
| `commitmentdb/commitment_context.go:302` | `ComputeCommitment` 负责 root 计算和 state 保存 |
| `commitmentdb/commitment_context.go:682` | 保存 commitment state，不只存 root hash |
| `db/state/execctx/domain_shared.go:997` | `ComputeCommitment` wrapper 先处理 pending updates，再 compute |
| `db/state/execctx/domain_shared.go:1056` | `TouchChangedKeysFromHistory` 支持从 history 重建 touched keys |
| `commitmentdb/reader.go:9` | commitment reader 有 latest/history/rebuild/split 多种视图 |

java-tron PR7 要吸收的结论：

- root 计算消费 write-set/touch-set，不扫描 live Store 热路径。
- update 排序必须按 commitment path，不按 raw key、domain enum 或 `Map` iteration。
- root builder 读取的是 state point 的 post-state 或 `DomainWrite.afterValue`，不能读 latest Store。
- branch/node 是独立持久化对象，不能只存 root hash。
- rebuild 需要能从 history changed keys 或 latest table 重算 root。

### 2.5 对照 java-tron 后的 PR7 编码约束

把以上源码事实落到 java-tron 的实现规则：

| 约束 | 编码要求 | 违反后果 |
| --- | --- | --- |
| `Manager.pushBlock` 只内联校验 `txTrieRoot` | `CommitmentBuilder` 不接入该校验分支，不抛 `BadBlockException` 影响共识 | sidecar root 错误会变成共识拒块 |
| `AccountStateCallBack` 从 parent header root 启动 | archive parent root 只能从 `ROOT_CURRENT` 或 `ROOT_BLOCK(blockNum - 1)` 读取 | 与现有 `accountStateRoot` 语义混淆，历史节点不兼容 |
| `AccountStateEntity` 只保留三项字段 | `ACCOUNT` leaf value 使用完整 `Account` protobuf canonical bytes，不使用 `AccountStateEntity` | root 覆盖不足，历史 RPC 与 root 证明不一致 |
| `AccountStore.delete` 不触发 account callback | archive delete 只能来自 `DomainWrite.afterValue` tombstone 或 normalizer | 删除/recreate 场景 leaf metadata 和 root 漏更新 |
| `TrieImpl` 在 `framework` | S10/S11 tree/core codec 放在 `chainbase` archive 包内 | 形成 `chainbase -> framework` 反向依赖 |
| `TrieImpl` empty root 是 RLP Patricia 特殊值 | archive SMT 定义自己的 empty hash chain | 空树、delete 回空 root 与 proof 语义错误 |
| `Manager.eraseBlock` 先 canonical fastPop | `archiveService.unwindBlock(oldHeadBlock)` 在 fastPop 成功后执行，temporal/root 同 batch | fork 回退后 archive latest 领先 canonical head |
| `switchFork` 会 replay 新旧分支 | `RootRecord` 和 unwind 必须带 `blockHash` 校验 | fork bounce 后仅按 blockNum 恢复会选错 root |

## 3. P0 不变量

### 3.1 不参与共识

PR7 不调用：

```text
BlockCapsule.setAccountStateRoot(...)
AccountStateCallBack.executeGenerateFinish()
AccountStateCallBack.executePushFinish()
```

PR7 不读取：

```text
BlockHeader.raw.accountStateRoot
```

archive parent root 来自：

```text
ROOT_CURRENT / ROOT_BLOCK(blockNum - 1)
```

### 3.2 单 physical archive DB

PR5 已收敛为单个 physical DB：

```text
archive
```

PR7 继续在同一个 DB 中新增 `0x30+` commitment tables。不要新增 `archive-root` physical DB。否则 temporal rows 和 root rows 无法同批原子提交。

### 3.3 `domainId` 统一使用 `u16`

前置模块已经统一：

```text
domainId_u16, big-endian
```

PR7 所有 commitment key、value、hash preimage 都必须使用 `u16 domainId`。不要重新引入 `u8 domainId`。

### 3.4 RootPolicy 与 coverage

模块 02 早期可以把 P0 domain 标记为 `DOMAIN_ROOT_ONLY` 做影子 root。PR7 若要发布 `globalRoot`，必须在 registry/配置中明确哪些 domain 进入 global root。

推荐 PR7 coverage：

```text
coverage = TVM_STATE_ONLY
```

对应 root policy：

| Domain | PR7 RootPolicy | 说明 |
| --- | --- | --- |
| `ACCOUNT` | `IN_GLOBAL_ROOT` | full `Account` protobuf bytes |
| `CONTRACT` | `IN_GLOBAL_ROOT` | `ContractStore.put` 清 ABI 后的 `SmartContract` bytes |
| `CODE` | `IN_GLOBAL_ROOT` | runtime code bytes |
| `CONTRACT_STORAGE` | `IN_GLOBAL_ROOT` | logical `address21 || slot32 || storageKeyVersion_u8`，zero 删除 |
| `DYNAMIC_PROPERTIES` | `DOMAIN_ROOT_ONLY` 或 allowlist 后 `IN_GLOBAL_ROOT` | 不得全量混入 latest cursor/索引类属性 |

如果 registry 仍配置为全部 `DOMAIN_ROOT_ONLY`，PR7 只能发布 domain roots，`globalRoot` 必须标记为 `PARTIAL_DOMAIN_SET` 或 disabled，不能声称 TVM state global root。

## 4. Patch 1：配置扩展

修改 PR1/PR2 已引入的配置结构：

```text
framework/src/main/resources/config.conf
common/src/main/java/org/tron/common/parameter/CommonParameter.java
framework/src/main/java/org/tron/core/config/args/Args.java
chainbase/src/main/java/org/tron/core/archive/ArchiveConfig.java
```

当前 `4e80f8ffa9a2` 本地 java-tron 已有 `common/src/main/resources/reference.conf`；`allowAccountStateRoot` 的读取链路是 `reference.conf`/配置文件 -> `Args` -> `CommonParameter` -> `DynamicPropertiesStore`。Archive commitment 需要独立的 `storage.archive.commitment.*` 配置，不复用该治理开关。

新增或确认：

```hocon
storage {
  archive {
    commitment {
      enable = false
      persistTxRoots = false
      algorithm = "TRON_ARCHIVE_SMT_KECCAK_V1"
      coverage = "TVM_STATE_ONLY"
      verifyRebuildOnStartup = false
      verifyRebuildEveryNBlocks = 0
      maxRebuildKeysForStartup = 100000
    }
  }
}
```

规则：

- `storage.archive.enable=false` 时 commitment 必须 no-op。
- `storage.archive.commitment.enable=false` 时 PR1-PR6 不受影响。
- `commitment.enable=true` 但 algorithm/coverage/schema 不匹配时，startup verifier 返回 `REPAIR_REQUIRED`。
- 默认不持久化 tx roots。

测试：

```text
ArchiveConfigTest
```

覆盖默认关闭、显式开启、非法 algorithm、archive disabled 下 commitment no-op。

## 5. Patch 2：扩展 `ArchiveTable` 和 key codec

修改：

```text
chainbase/src/main/java/org/tron/core/archive/store/ArchiveTable.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveKeyCodec.java
```

扩展 table prefix：

```java
COMMITMENT_META((byte) 0x30)
ROOT_BLOCK((byte) 0x31)
ROOT_DOMAIN((byte) 0x32)
ROOT_TX((byte) 0x33)
ROOT_NODE((byte) 0x34)
ROOT_CURRENT((byte) 0x35)
ROOT_LEAF((byte) 0x36)
```

P0 key schema：

```text
COMMITMENT_META:
  table_u8 | asciiName

ROOT_BLOCK:
  table_u8 | blockNum_u64

ROOT_DOMAIN:
  table_u8 | domainId_u16 | blockNum_u64

ROOT_TX:
  table_u8 | txNum_u64

ROOT_NODE:
  table_u8 | algorithmId_u16 | treeKind_u8 | domainId_u16 | nodeHash_32

ROOT_CURRENT:
  table_u8 | algorithmId_u16 | treeKind_u8 | domainId_u16

ROOT_LEAF:
  table_u8 | algorithmId_u16 | domainId_u16 | path32
```

约束：

- `domainId` 用 `u16`。
- `algorithmId` 用 `u16`。
- `treeKind` 用 `u8`，`1=DOMAIN_TREE`，`2=GLOBAL_TREE`。
- global tree 的 `domainId` 固定为 `0x0000`，registry domain 从 `0x0001` 开始。
- 所有数值 big-endian。
- `ROOT_LEAF` 按 `path32` 建 key；canonical key 放在 `LeafRecord` 中，用于 collision guard。
- 不使用 `ByteUtil.compare` 比较 variable-length archive key；继续使用模块 04 的 unsigned variable-length comparator。

测试：

```text
RootKeyCodecTest
```

覆盖 encode/decode、prefix scan、domainId 大于 255、排序和 malformed key。

## 6. Patch 3：record/value codec

新增：

```text
chainbase/src/main/java/org/tron/core/archive/commitment/RootRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/DomainRootRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/CurrentRootRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/NodeRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/LeafRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootRecordCodec.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentNodeCodec.java
```

所有 value 使用稳定二进制：

- 不使用 Java serialization。
- 不使用 JSON。
- 不使用 protobuf，除非先固定 archive 专用 proto schema 和版本。

### 6.1 RootRecord

```text
u32 schemaVersion
u16 algorithmId
u8  rootScope
u8  consensusParticipation
u32 coverageLen | coverageAscii
u64 blockNum
u32 blockHashLen | blockHash
u64 asOfTxNum
u32 registryChecksumLen | registryChecksum
u32 globalRootLen | globalRoot
u32 domainCount
repeated:
  u16 domainId
  u32 domainRootLen | domainRoot
  u8  rootPolicy
  u64 leafCount
```

P0 固定：

```text
schemaVersion = 1
rootScope = ARCHIVE_SIDECAR
consensusParticipation = NONE
coverage = storage.archive.commitment.coverage
```

### 6.2 DomainRootRecord

```text
u32 schemaVersion
u16 algorithmId
u16 domainId
u64 blockNum
u64 asOfTxNum
u32 rootLen | domainRoot
u64 leafCount
u32 registryChecksumLen | registryChecksum
u16 keyCodecVersion
u16 valueCodecVersion
u8  rootPolicy
```

### 6.3 CurrentRootRecord

```text
u32 schemaVersion
u16 algorithmId
u8  treeKind
u16 domainId
u64 latestBlockNum
u64 latestAsOfTxNum
u32 rootLen | root
u64 leafCount
```

### 6.4 NodeRecord

```text
u32 schemaVersion
u16 algorithmId
u8  treeKind
u16 domainId
u16 depth
u32 leftHashLen | leftHash
u32 rightHashLen | rightHash
u32 nodeHashLen | nodeHash
```

### 6.5 LeafRecord

```text
u32 schemaVersion
u16 algorithmId
u16 domainId
u32 pathLen | path32
u32 keyLen | canonicalKey
u32 keyHashLen | keyHash
u32 valueHashLen | valueHash
u32 leafHashLen | leafHash
```

测试：

```text
RootRecordCodecTest
CommitmentNodeCodecTest
```

覆盖 roundtrip、version mismatch、truncated bytes、domainId=256/65535、unknown treeKind、unknown rootPolicy。

## 7. Patch 4：hash spec

新增：

```text
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentHash.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootAlgorithm.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootAlgorithmDescriptor.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentPath.java
```

PR7 固定算法：

```text
algorithmId = 1
name = TRON_ARCHIVE_SMT_KECCAK_V1
hash = org.tron.common.crypto.Hash.sha3
pathBits = 256
tree = binary_sparse_merkle
```

descriptor 写入：

```text
COMMITMENT_META("algorithmDescriptor")
```

hash preimage：

```text
domainPath = H(
  "tron.archive.domain.path.v1"
  || algorithmId_u16
  || domainId_u16
  || keyLen_u32
  || canonicalKey
)

keyHash = H(
  "tron.archive.key.v1"
  || algorithmId_u16
  || domainId_u16
  || keyLen_u32
  || canonicalKey
)

valueHash = H(
  "tron.archive.domain.value.v1"
  || algorithmId_u16
  || domainId_u16
  || valueLen_u32
  || canonicalValue
)

leafHash = H(
  "tron.archive.domain.leaf.v1"
  || algorithmId_u16
  || domainId_u16
  || domainPath
  || valueHash
)

globalPath = H(
  "tron.archive.global.path.v1"
  || algorithmId_u16
  || domainId_u16
)

globalLeafHash = H(
  "tron.archive.global.leaf.v1"
  || algorithmId_u16
  || domainId_u16
  || domainRoot
  || leafCount_u64
)
```

empty hash：

```text
empty[256] = H("tron.archive.smt.empty.leaf.v1" || algorithmId_u16)

empty[depth] = H(
  "tron.archive.smt.empty.branch.v1"
  || algorithmId_u16
  || depth_u16
  || empty[depth + 1]
  || empty[depth + 1]
)
```

branch hash：

```text
branchHash = H(
  "tron.archive.smt.branch.v1"
  || algorithmId_u16
  || treeKind_u8
  || domainId_u16
  || depth_u16
  || leftHash
  || rightHash
)
```

规则：

- ASCII prefix 一旦发布不可改；改 prefix 必须换 algorithmId。
- domain path、value hash、leaf hash 都带 `domainId_u16`。
- storage zero 和 tombstone 不生成 leaf。
- empty root 由 algorithm 和 depth 决定，不从 DB 读取。

测试：

```text
CommitmentHashTest
```

覆盖 deterministic vector、domain namespace、algorithmId namespace、left/right 顺序、empty hash chain。

## 8. Patch 5：SparseMerkleTree

新增：

```text
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentTree.java
chainbase/src/main/java/org/tron/core/archive/commitment/SparseMerkleTree.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentNodeReader.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentNodeWriter.java
```

接口：

```java
public interface CommitmentTree {
  byte[] rootHash();

  UpdateResult update(byte[] path32, Optional<byte[]> leafHash, ArchiveBatch batch)
      throws CommitmentException;
}
```

实现约束：

- binary sparse Merkle tree，path 固定 256 bit。
- node 内容寻址，`ROOT_NODE` key 使用 `nodeHash`。
- node immutable，update 写新 node，不覆盖旧 node。
- hot unwind 不删除 old nodes；恢复 `ROOT_CURRENT`，并恢复或重建 `ROOT_LEAF` metadata。
- PR7 不做 node GC。

update 伪代码：

```text
walk old root depth 0..255:
  if nodeHash == empty[depth]:
    sibling = empty[depth + 1]
  else:
    node = nodeReader.get(nodeHash)
    choose child by path bit
    keep sibling hash

newChild = leafHash or empty[256]
for depth 255..0:
  newParent = branchHash(depth, left, right)
  if newParent != empty[depth]:
    batch.put(ROOT_NODE(newParent), NodeRecord)
return newRoot
```

### 8.1 staged overlay

同一个 block 内可能多次 update tree。Node reader 必须先看 staged batch：

```text
getNode(hash):
  if batch has ROOT_NODE(hash): return batch value
  return rawStore.get(ROOT_NODE(hash))
```

否则第二个 key update 会基于旧 root 读不到第一个 key 刚 stage 的 branch。

测试：

```text
SparseMerkleTreeTest
```

覆盖 empty root、put/delete、A then B 与 B then A、same leaf update、batch overlay、missing non-empty node corruption。

## 9. Patch 6：CommitmentUpdate 和 normalizer

新增：

```text
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentUpdate.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentUpdateBatch.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootValueNormalizer.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentException.java
```

`CommitmentUpdate`：

```java
public final class CommitmentUpdate {
  private final short domainId;
  private final byte[] canonicalKey;
  private final byte[] path32;
  private final byte[] keyHash;
  private final byte[] valueHash;
  private final byte[] leafHash;
  private final boolean delete;
}
```

从 `DomainWrite` 转换：

```text
DomainWrite.afterValue
  -> registry descriptor
  -> rootPolicy
  -> RootValueNormalizer
  -> present/delete
  -> CommitmentUpdate
```

normalizer 规则：

| Domain | 规则 |
| --- | --- |
| `ACCOUNT` | full Account protobuf；tombstone 删除 |
| `CONTRACT` | cleaned SmartContract bytes；tombstone 删除 |
| `CODE` | empty/tombstone 删除 |
| `CONTRACT_STORAGE` | 32-byte zero/tombstone 删除 |
| `DYNAMIC_PROPERTIES` | 未在 allowlist 的 key 忽略；allowlist key 按 raw bytes |

排序规则：

```text
domain tree:
  sort by path32 ASC, then canonicalKey ASC

global tree:
  sort by globalPath ASC, then domainId ASC
```

不要按：

- raw Store iteration order；
- `HashMap` iteration；
- enum declaration order；
- plain key 字典序。

测试：

```text
CommitmentUpdateBatchTest
RootValueNormalizerTest
```

覆盖 same block 同 key 多次写取最后值、乱序输入 deterministic、storage zero delete、excluded domain 忽略、DOMAIN_ROOT_ONLY 不进 global。

## 10. Patch 7：CommitmentBuilder API

新增：

```text
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentBuilder.java
chainbase/src/main/java/org/tron/core/archive/commitment/DefaultCommitmentBuilder.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentIntegrityReport.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentIntegrityScope.java
```

接口：

```java
public interface CommitmentBuilder {
  boolean isEnabled();

  void stageBlockEnd(BlockWriteSet blockWriteSet, ArchiveBatch batch)
      throws CommitmentException;

  void stageUnwindBlock(long blockNum, byte[] blockHash, ArchiveBatch batch)
      throws CommitmentException;

  Optional<RootRecord> getBlockRoot(long blockNum);

  Optional<DomainRootRecord> getDomainRoot(ArchiveDomain domain, long blockNum);

  RootRecord rebuildBlockEnd(long blockNum) throws CommitmentException;

  CommitmentIntegrityReport checkIntegrity(CommitmentIntegrityScope scope);
}
```

PR7 不暴露 proof API。`prove(path32)` 进入 PR9。

`stageBlockEnd` 要求：

1. 如果 disabled，直接 no-op。
2. 从 `BlockWriteSet` 生成 commitment updates。
3. 按 domain 分组并排序。
4. 更新每个 domain sparse tree。
5. 对发生变化的 `IN_GLOBAL_ROOT` domain 更新 global tree。
6. 写所有 root-included domain 的 `ROOT_DOMAIN(domain, blockNum)`，即使本 block 未修改该 domain。
7. 写 `ROOT_BLOCK(blockNum)`，空块也必须写。
8. 写 `ROOT_CURRENT`。
9. 写 `COMMITMENT_META(rootProgress)`。

如果 commitment 计算失败：

- 不 flush `ArchiveBatch`。
- archive progress 不推进。
- startup verifier 后续识别 archive behind 或 repair required。

## 11. Patch 8：同批提交重构

修改：

```text
chainbase/src/main/java/org/tron/core/archive/store/ArchiveTemporalStore.java
chainbase/src/main/java/org/tron/core/archive/store/DefaultArchiveTemporalStore.java
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
```

如果 PR5 仍是：

```text
temporalStore.applyBlock(blockWriteSet) -> rawStore.updateByBatch(...)
```

PR7 必须重构成：

```java
ArchiveBatch batch = new ArchiveBatch();
temporalStore.stageApplyBlock(blockWriteSet, batch);
if (commitmentBuilder.isEnabled()) {
  commitmentBuilder.stageBlockEnd(blockWriteSet, batch);
}
rawStore.updateByBatch(batch.toRawMap());
```

unwind 同理：

```java
ArchiveBatch batch = new ArchiveBatch();
temporalStore.stageUnwindBlock(blockNum, blockHash, batch);
commitmentBuilder.stageUnwindBlock(blockNum, blockHash, batch);
rawStore.updateByBatch(batch.toRawMap());
```

禁止：

```text
temporalStore.applyBlock(...)
commitmentBuilder.applyBlock(...)
```

两个独立 batch 会制造 `state 已前进但 root 缺失` 或 `root ahead of state` 的 crash window。

## 12. Patch 9：block apply 写路径

`DefaultCommitmentBuilder.stageBlockEnd` 内部流程：

```text
blockEnd = StatePoint.blockEnd(blockNum)
asOfTxNum = blockWriteSet.lastTxNum + 1
parentRoot = ROOT_CURRENT or empty roots

for txWriteSet in blockWriteSet:
  for domainWrite in txWriteSet.writes:
    if registry excludes root: continue
    update latest post-value for (domainId, canonicalKey)

for grouped domain updates:
  normalize value
  sort by path32
  domainTree.update(...)
  write ROOT_LEAF(path32) present/delete
  update domain current root

for root included domains:
  write ROOT_DOMAIN(domain, blockNum)

update global tree from IN_GLOBAL_ROOT domains
write ROOT_BLOCK(blockNum)
write ROOT_CURRENT(global)
write COMMITMENT_META(rootProgress)
```

关键规则：

- block root 基于 after value。
- 同一 block 同一 key 多次写，block-end root 只看最后 after value。
- 如果 `persistTxRoots=true`，必须按 tx 边界增量发布 `ROOT_TX`，不能用 block-end final values 伪造 tx root。
- empty block 仍写 `ROOT_BLOCK`，root 等于 parent。
- `DOMAIN_ROOT_ONLY` 只写 domain root，不进 global root。

## 13. Patch 10：unwind

`stageUnwindBlock(blockNum, blockHash, batch)`：

1. 校验 `ROOT_BLOCK(blockNum)` 存在。
2. 校验 RootRecord.blockHash 与传入 blockHash 一致。
3. 删除 `ROOT_BLOCK(blockNum)`。
4. 删除 `ROOT_DOMAIN(*, blockNum)`。
5. 如果 `persistTxRoots=true`，按 txNum range 删除 `ROOT_TX`。
6. 查 `ROOT_BLOCK(blockNum - 1)`。
7. 用前一 block 的 RootRecord 恢复所有 `ROOT_CURRENT`。
8. 用 temporal history/changeset 反向恢复 changed rooted keys 的 `ROOT_LEAF(path32)` 元数据，或在标记 OK 前完成 leaf metadata rebuild。
9. 写 `COMMITMENT_META(rootProgress)` 到前一 block。

content-addressed `ROOT_NODE` 不删除。后续 GC 单独做。

`ROOT_CURRENT` 只恢复 root hash，不恢复 active leaf metadata。S11 必须保证 unwind 后 `ROOT_LEAF` 与 restored root 一致，否则下一块增量更新的 leafCount/collision guard 会错。

如果找不到前一 block root：

- 如果是 commitment 启用起点前，恢复 empty root，并标记 progress 起点。
- 否则返回 `REPAIR_REQUIRED`，不要猜测 root。

测试：

```text
CommitmentUnwindTest
```

覆盖 current 指针恢复、root rows 删除、`ROOT_LEAF` metadata 恢复、node 保留、blockHash mismatch。

## 14. Patch 11：rebuild 和 integrity

新增：

```text
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentRebuilder.java
chainbase/src/main/java/org/tron/core/archive/commitment/DefaultCommitmentRebuilder.java
```

P0 必须实现 latest rebuild：

```text
scan LATEST table for root-included domains
  -> normalize root value
  -> build domain sparse tree from empty
  -> build global sparse tree
  -> compare ROOT_BLOCK(progress.appliedBlockNum)
```

P0 可以只定义 historical rebuild 接口：

```text
scan HISTORY/CHANGESET as-of block
```

但 PR9 tx-level proof 会依赖更完整的 historical/on-demand replay。

`checkIntegrity` 返回：

```text
OK
COMMITMENT_DISABLED
ROOT_MISSING
ROOT_AHEAD_OF_ARCHIVE
ROOT_BEHIND_ARCHIVE
ROOT_MISMATCH
ALGORITHM_MISMATCH
REGISTRY_CHECKSUM_MISMATCH
REPAIR_REQUIRED
```

不要在 startup 自动删除 root 数据；自动 repair 需要单独配置。

## 15. Patch 12：startup verifier

修改：

```text
framework/src/main/java/org/tron/core/archive/ArchiveStartupVerifier.java
```

启动校验增加 commitment：

| 状态 | 行为 |
| --- | --- |
| archive disabled | commitment no-op |
| commitment disabled | 不校验 root rows |
| root schema missing but archive empty | OK |
| commitment enabled but root missing at archive progress | `ROOT_MISSING/REPAIR_REQUIRED` |
| root progress behind temporal progress | `ROOT_BEHIND_ARCHIVE`，需要 rebuild missing roots |
| root progress ahead temporal progress | `ROOT_AHEAD_OF_ARCHIVE` |
| algorithm descriptor mismatch | `ALGORITHM_MISMATCH` |
| registry checksum mismatch | `REGISTRY_CHECKSUM_MISMATCH` |
| optional rebuild mismatch | `ROOT_MISMATCH` |

首次在已有 archive DB 上开启 commitment：

- 不能从 empty root 直接继续写。
- 必须 bootstrap/rebuild 到 current archive progress。
- 或保持 commitment disabled。

## 16. Patch 13：tests

### 16.1 Unit：hash spec

```text
chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentHashTest.java
```

覆盖：

- empty hash vector 固定。
- 同输入同输出。
- 不同 domain 同 key path 不同。
- branch left/right 顺序敏感。
- algorithmId 不同输出不同。
- domainId=256/65535。
- `domainPath != keyHash`。

### 16.2 Unit：SparseMerkleTree

```text
chainbase/src/test/java/org/tron/core/archive/commitment/SparseMerkleTreeTest.java
```

覆盖：

- empty tree root。
- put one leaf。
- delete same leaf 回到 empty。
- put A then B 和 B then A root 相同。
- same leaf update deterministic。
- batch overlay 能读 staged node。
- corrupt/missing node 报 `CORRUPTED`。

### 16.3 Unit：CommitmentBuilder

```text
chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentBuilderTest.java
```

覆盖：

- ACCOUNT write 生成 domain/global root。
- CONTRACT/CODE 写入改变对应 domain root。
- storage zero after-value 删除 leaf。
- DOMAIN_ROOT_ONLY 不进入 global root。
- EXCLUDED 不写 domain root。
- 同 block 多 tx 修改同 key，block root 取最后 after-value。
- writes 输入乱序 root deterministic。
- RootRecord 标记 `ARCHIVE_SIDECAR/NONE/TVM_STATE_ONLY`。
- empty block 仍写 `ROOT_BLOCK`。
- `ROOT_LEAF(path32)` 同 path 不同 canonical key 报 collision。

### 16.4 Unit：unwind/rebuild

```text
chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentUnwindTest.java
chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentRebuilderTest.java
```

覆盖：

- unwind block 2 后 current 恢复 block 1。
- `ROOT_BLOCK(2)` 删除。
- old content-addressed nodes 保留。
- latest rebuild 等于 incremental root。
- 篡改 `ROOT_NODE` 或 `ROOT_BLOCK` 后 integrity mismatch。

### 16.5 Integration

```text
framework/src/test/java/org/tron/core/archive/ArchiveCommitmentIntegrationTest.java
```

覆盖：

- commitment disabled 时 PR1-PR6 行为不变。
- transfer block 改 ACCOUNT root。
- deploy block 改 CONTRACT/CODE root。
- storage write 改 CONTRACT_STORAGE root。
- replay 同一段链 root 一致。
- switch fork / erase block 后 root unwind。
- 不写 `BlockHeader.raw.accountStateRoot`。

不要添加任何 test skip、`@Ignore`、条件绕过或矩阵排除。

## 17. 验收命令

定向测试：

```bash
./gradlew :chainbase:test --tests '*Commitment*'
./gradlew :framework:test --tests '*ArchiveCommitment*'
```

PR 级建议：

```bash
./gradlew :chainbase:test --tests '*Archive*'
./gradlew :framework:test --tests '*Archive*'
./gradlew checkstyleMain
```

文档阶段不要求执行这些命令。

## 18. Code review 检查表

- [ ] PR7 没有写 `BlockHeader.raw.accountStateRoot`。
- [ ] PR7 没有把 `txTrieRoot` 当状态 root。
- [ ] `chainbase` archive 包没有 import `framework` 的 `TrieImpl`。
- [ ] commitment 默认关闭。
- [ ] root rows 与 temporal rows 同一个 physical `archive` DB batch 提交。
- [ ] 所有 commitment key/value/hash preimage 使用 `domainId_u16`。
- [ ] RootRecord 标记 `ARCHIVE_SIDECAR` 和 `consensusParticipation=NONE`。
- [ ] coverage 明确，不把 TVM subset root 称为完整 TRON root。
- [ ] `BLOCK_END(blockNum)` 使用 `lastTxNum + 1`。
- [ ] updates 按 commitment path 排序，不按 raw key 或 Map iteration。
- [ ] root builder 使用 `DomainWrite.afterValue` 或 as-of reader，不读 latest live Store。
- [ ] storage zero normalizes to delete leaf。
- [ ] empty block 仍写 `ROOT_BLOCK`。
- [ ] `ROOT_CURRENT` unwind 后恢复到前一 block。
- [ ] `ROOT_LEAF` metadata unwind 后与 restored root 一致。
- [ ] content-addressed nodes 不在 unwind 中删除。
- [ ] latest rebuild root 等于 incremental root。
- [ ] 首次开启 commitment 时没有从 empty root 接着已有 archive 写。
- [ ] 没有新增测试 skip。

## 19. PR9 交接边界

PR7 完成后，PR9 可以复用：

```text
RootRecord
DomainRootRecord
CurrentRootRecord
CommitmentHash
RootKeyCodec
CommitmentNodeCodec
SparseMerkleTree
ROOT_NODE / ROOT_LEAF
rebuild/on-demand replay 基础
```

PR9 还需要新增：

```text
ArchiveProofService
ProofBuilder
ProofVerifier
CommitmentTree.prove(path32)
TxRootComputer
debug_getArchiveRoot
debug_getArchiveProof
debug_verifyArchiveProof
historical debug_traceCall trace capture
```

PR9 的 proof 必须是 archive-native：

```text
domain proof + global proof + algorithmDescriptor + registryChecksum + coverage
```

不要在 PR9 中伪装成 Ethereum `eth_getProof`，也不要让 root/proof 参与共识。
