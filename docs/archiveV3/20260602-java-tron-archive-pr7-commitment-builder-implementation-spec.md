# java-tron Archive PR7 CommitmentBuilder 代码级实现规格

日期：2026-06-02

关联总文档：[java-tron 交易级状态树支持：Erigon V2/V3 模型调研](./20260520-java-tron-archive-state-erigon-v2-v3-research.md)

关联实现蓝图：[java-tron Archive 状态树实现蓝图](./20260602-java-tron-archive-implementation-blueprint.md)

前置规格：

- [java-tron Archive PR5 TemporalStore 代码级实现规格](./20260602-java-tron-archive-pr5-temporal-store-implementation-spec.md)
- [java-tron Archive PR6 StateReader/JSON-RPC 代码级实现规格](./20260602-java-tron-archive-pr6-state-reader-jsonrpc-implementation-spec.md)

逐文件 Patch 清单：[java-tron Archive 模块 06：CommitmentBuilder 逐文件 Patch 清单](./20260602-java-tron-archive-module-06-commitment-builder-patch-checklist.md)

S10 编码执行包：[java-tron Archive S10：Sparse Merkle Tree Core + Root Codecs 编码执行包](./20260602-java-tron-archive-s10-sparse-merkle-root-codecs-coding-packet.md)

S11 编码执行包：[java-tron Archive S11：CommitmentBuilder Integration + Rebuild Verifier 编码执行包](./20260602-java-tron-archive-s11-commitment-builder-integration-rebuild-coding-packet.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

旧规格原复核基线：本地 java-tron `a79693e450`。当前 `4e80f8ffa9a2` 的 Module 06 源码事实请以 [模块 06 CommitmentBuilder：4e80 java-tron 源码对照细化](./20260603-java-tron-module-06-commitment-builder-4e80-source-deep-dive.md) 为准。

## 1. PR7 目标

PR7 在 PR1-PR6 的 `txNum -> write-set -> temporal history -> historical reader` 闭环上，增加默认关闭的 archive sidecar block-end commitment root。

本 PR 做：

1. 增加 `CommitmentBuilder` 接口和默认实现。
2. 增加 block-end `RootRecord`、`DomainRootRecord` 和 root table schema。
3. 使用 `ArchiveDomainRegistry.RootPolicy` 过滤进入 root 的 domain/key。
4. 对每个 root domain 维护 `domainRoot`。
5. 用 `domainRoot` 维护 `globalRoot`。
6. 在 `BLOCK_END(blockNum)` 生成 archive sidecar root。
7. 支持 root deterministic replay 和 rebuild 校验。
8. 支持 hot unwind 时恢复前一 block 的 `ROOT_CURRENT`，并恢复或重建 `ROOT_LEAF` metadata。

本 PR 不做：

1. 不写入 `BlockHeader.raw.accountStateRoot`。
2. 不修改 `txTrieRoot` 或共识校验。
3. 不实现历史 `eth_call`。
4. 不实现公开 proof API。
5. 不默认持久化每 tx root。
6. 不做 cold segment / root node GC。

PR7 的产物仍然是：

```text
root_scope = ARCHIVE_SIDECAR
consensus_participation = NONE
coverage = TVM_STATE_ONLY
```

不能把它描述为完整 TRON consensus state root。

## 2. 源码事实

### 2.1 现有区块头 root 不能复用

| 文件 | 位置 | 事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/protocol/src/main/protos/core/Tron.proto:504-513` | `txTrieRoot/accountStateRoot` | 区块交易列表 Merkle root 与账户状态 root 字段 |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/capsule/BlockCapsule.java:218-230` | `calcMerkleRoot` | 用交易 `getMerkleHash()` 构建 `txTrieRoot`，空交易返回 `Sha256Hash.ZERO_HASH` |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/capsule/BlockCapsule.java:233-244` | `validateMerkleRoot` | 校验交易 `txTrieRoot` |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/capsule/BlockCapsule.java:246-253` | `setMerkleRoot` | 写 `txTrieRoot` |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/capsule/BlockCapsule.java:255-262` | `setAccountStateRoot` | 写 `accountStateRoot` |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/services/jsonrpc/types/BlockResult.java:101-104` | JSON-RPC block result | 暴露 `transactionsRoot/stateRoot`，archive root 不静默复用 |

结论：

- `txTrieRoot` 不是状态 root。
- `accountStateRoot` 是已有共识相关字段，PR7 不写它。
- archive sidecar root 必须写在 sidecar DB 中，不能塞进区块头。

### 2.2 accountStateRoot 管线只覆盖轻量账户状态

| 文件 | 位置 | 事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/common/src/main/resources/reference.conf:812` | `allowAccountStateRoot = 0` | 默认关闭 |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/config/args/Args.java:462` | `allowAccountStateRoot` | 从配置读取 `cc.getAllowAccountStateRoot()` |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/store/DynamicPropertiesStore.java:789-793` | init | 缺省时写入 `CommonParameter.getAllowAccountStateRoot()` |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/store/DynamicPropertiesStore.java:2375-2378` | `saveAllowAccountStateRoot` | governance toggle |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/store/DynamicPropertiesStore.java:2380-2389` | `allowAccountStateRoot` | 只在开关为 1 时生成/校验 |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/db/accountstate/callback/AccountStateCallBack.java:52-72` | `preExecute` | 从 parent block 的 `accountStateRoot` 初始化 trie |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/db/accountstate/callback/AccountStateCallBack.java:38-42` | `exeTransFinish` | 每 tx 后把 account dirty entries 写入 trie |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/db/accountstate/callback/AccountStateCallBack.java:74-92` | `executePushFinish` | 接收 block 时校验新 root |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/db/accountstate/callback/AccountStateCallBack.java:94-105` | `executeGenerateFinish` | 本地产块时写入 block header |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/db/accountstate/AccountStateCallBackUtils.java:13-22` | `accountCallBack` | 只消费 `AccountCapsule` |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/db/accountstate/AccountStateEntity.java:16-22` | constructor | 只保留 `address/balance/allowance` |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/store/AccountStore.java:68-105` | `put/delete` | `put` 触发 callback，`delete` 未触发现有 accountStateRoot 删除 callback |

结论：

- `accountStateRoot` 当前不是完整账户 protobuf root。
- 它不覆盖 contract、code、storage、dynamic properties。
- 它可作为“block 前加载 parent root、tx 后 apply dirty、block 结束 root”的流程参考，但不能作为 archive global root 复用。

### 2.3 TrieImpl 的可复用边界

| 文件 | 位置 | 事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/trie/TrieImpl.java:33` | `TrieImpl` | 位于 `framework` 模块 |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/trie/TrieImpl.java:144` | `put` | MPT-like put |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/trie/TrieImpl.java:206-213` | `delete` | MPT-like delete |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/trie/TrieImpl.java:286-288` | `getRootHash` | root hash；空 root 返回 `EMPTY_TRIE_HASH` |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/trie/TrieImpl.java:377-424` | `prove` | RLP Hex Patricia proof |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/trie/TrieImpl.java:486-552` | `verifyProof` | proof verifier |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/trie/TrieImpl.java:559-564` | `setRoot` | 把 `EMPTY_TRIE_HASH` 当空树 |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/db/accountstate/storetrie/AccountStateStoreTrie.java:19` | `AccountStateStoreTrie` | `TrieImpl` 的 account-state backing store |

模块依赖事实：

```text
framework -> chainbase
chainbase -/-> framework
```

结论：

- archive 核心包在 `chainbase`，不能直接 import `framework` 里的 `TrieImpl`。
- 如果未来要复用 `TrieImpl`，应先把通用 trie 抽到 `chainbase` 或 `common`，这是独立重构，不放进 PR7。
- PR7 推荐在 `chainbase` 内实现 archive 专用 commitment tree，`TrieImpl` 只作为流程和 proof 设计参考。

## 3. 实现选择

PR7 使用内容寻址的 binary sparse Merkle tree。

选择原因：

1. 放在 `chainbase`，不引入 `chainbase -> framework` 依赖。
2. key path 固定为 256-bit hash，domain/key schema 不必仿照 Ethereum MPT。
3. branch node 内容寻址，多个历史 root 可以共享节点。
4. hot unwind 不回滚 content-addressed nodes；必须恢复 `ROOT_CURRENT`，并让 `ROOT_LEAF` metadata 回到同一 state point。
5. proof API 后续可以基于同一 node store 增量实现。

代价：

1. 每个 changed key 最多写 256 个 branch node。
2. PR7 不做 node GC。
3. 大规模生产启用前需要 benchmark 和 checkpoint/compaction 优化。

因此 PR7 的 commitment 默认继续关闭：

```hocon
storage.archive.commitment.enable = false
```

开启后先用于验证和小范围 archive 节点，不作为普通 fullnode 默认路径。

## 4. 包和文件

### 4.1 chainbase 新增

```text
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentBuilder.java
chainbase/src/main/java/org/tron/core/archive/commitment/DefaultCommitmentBuilder.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentTree.java
chainbase/src/main/java/org/tron/core/archive/commitment/SparseMerkleTree.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentHash.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootAlgorithm.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootAlgorithmDescriptor.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentUpdate.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentUpdateBatch.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentPath.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/DomainRootRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/CurrentRootRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/NodeRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/LeafRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootRecordCodec.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootKeyCodec.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentNodeCodec.java
chainbase/src/main/java/org/tron/core/archive/commitment/LeafMetadataGuard.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentRebuilder.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentIntegrityReport.java
```

PR7 仍复用 PR5 的：

```text
ArchiveRawStore
ArchiveTable
ArchiveTemporalStore
ArchiveValueCodec
ArchiveProgress
```

### 4.2 framework 调整

```text
framework/src/main/java/org/tron/core/archive/ArchiveStartupVerifier.java
framework/src/main/java/org/tron/core/db/Manager.java
```

如果 PR5 已把 `DefaultArchiveService.commitBlock()` 放在 `chainbase`，PR7 只改它和相关接口；`Manager` 不需要新增 root 专用 hook。

## 5. 配置

在 PR1/PR2 的 `storage.archive` 下补齐或确认：

```hocon
storage {
  archive {
    commitment {
      enable = false
      persistTxRoots = false
      algorithm = "TRON_ARCHIVE_SMT_KECCAK_V1"
      verifyRebuildOnStartup = false
      verifyRebuildEveryNBlocks = 0
      maxRebuildKeysForStartup = 100000
    }
  }
}
```

规则：

- `archive.enable=false` 时 commitment 必须 no-op。
- `archive.enable=true && commitment.enable=false` 时 PR1-PR6 功能不受影响。
- `commitment.enable=true` 且 root schema/algorithm 不匹配时，节点启动失败或进入 `REPAIR_REQUIRED`，不能静默换算法继续写。
- `persistTxRoots=false` 是默认；PR7 只要求 block-end root。

## 6. ArchiveTable 扩展

PR5 使用单个 physical `archive` DB。PR7 继续使用同一个 physical DB，避免 `state + txnum + root + progress` 跨 DB 非原子。

扩展 `ArchiveTable`：

```java
public enum ArchiveTable {
  META((byte) 0x01),
  TXNUM_BLOCK((byte) 0x10),
  TXNUM_BY_TXID((byte) 0x11),
  TXNUM_META((byte) 0x12),
  LATEST((byte) 0x20),
  HISTORY((byte) 0x21),
  CHANGESET((byte) 0x22),

  COMMITMENT_META((byte) 0x30),
  ROOT_BLOCK((byte) 0x31),
  ROOT_DOMAIN((byte) 0x32),
  ROOT_TX((byte) 0x33),
  ROOT_NODE((byte) 0x34),
  ROOT_CURRENT((byte) 0x35),
  ROOT_LEAF((byte) 0x36);
}
```

`archive-root` physical DB 继续延后。只有在有跨 DB transaction 或 root 可以异步重建且 verifier 成熟后，才拆物理库。

## 7. Key schema

### 7.1 COMMITMENT_META

```text
0x30 | asciiName -> value
```

建议 key：

```text
algorithm
algorithmDescriptor
rootSchemaVersion
rootProgress
```

### 7.2 ROOT_BLOCK

```text
0x31 | u64 blockNum -> RootRecord
```

只保存 `BLOCK_END(blockNum)` root。

### 7.3 ROOT_DOMAIN

```text
0x32 | u16 domainId | u64 blockNum -> DomainRootRecord
```

每个进入 root 的 domain 在 block-end 记录一次 domain root。

### 7.4 ROOT_TX

```text
0x33 | u64 txNum -> RootRecord
```

PR7 默认不写。只有 `persistTxRoots=true` 时写 `TX_AFTER(txNum)` root。

### 7.5 ROOT_NODE

内容寻址 node：

```text
0x34 | u16 algorithmId | u8 treeKind | u16 domainId | bytes32 nodeHash -> NodeRecord
```

`treeKind`：

```text
1 = DOMAIN_TREE
2 = GLOBAL_TREE
```

`domainId`：

- domain tree：真实 domain id。
- global tree：0。

Node key 用 `nodeHash` 内容寻址。相同内容可重复 put，最终值相同。

### 7.6 ROOT_CURRENT

当前 latest root 指针：

```text
0x35 | u16 algorithmId | u8 treeKind | u16 domainId -> CurrentRootRecord
```

用途：

- block apply 从 current root 增量更新。
- hot unwind 把 `ROOT_CURRENT` 恢复到前一 block 的 `RootRecord`；`ROOT_LEAF` metadata 需要通过 unwind path 恢复或 rebuild。
- startup verifier 检查 root progress。

### 7.7 ROOT_LEAF

当前 active leaf 元数据：

```text
0x36 | u16 algorithmId | u16 domainId | bytes32 path32 -> LeafRecord
```

用途：

- debug。
- rebuild 对比。
- collision guard。`LeafRecord` 内保存 canonical key；如果同一 `path32` 出现不同 key，fail closed。
- leaf count 统计。

如果 value tombstone 或 root normalizer 判定为空，删除 `ROOT_LEAF(path32)`。

## 8. Value schema

所有 value 使用稳定二进制，不使用 Java serialization。

### 8.1 RootRecord

```text
u32 schemaVersion
u16 algorithmId
u8  rootScope                 // 1 = ARCHIVE_SIDECAR
u8  consensusParticipation    // 0 = NONE
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

PR7 固定：

```text
schemaVersion = 1
rootScope = ARCHIVE_SIDECAR
consensusParticipation = NONE
coverage = storage.archive.coverage
```

### 8.2 DomainRootRecord

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

### 8.3 CurrentRootRecord

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

### 8.4 NodeRecord

Binary sparse Merkle branch node：

```text
u32 schemaVersion
u16 algorithmId
u8  treeKind
u16 domainId
u16 depth              // 0 root, 256 leaf boundary
u32 leftHashLen | leftHash
u32 rightHashLen | rightHash
u32 nodeHashLen | nodeHash
```

Leaf nodes不单独保存为 branch `NodeRecord`；leaf hash 由 `LeafRecord` 和 empty hash 规则定义。Branch node 的 `left/right` 可以是 empty hash。

### 8.5 LeafRecord

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

`path32 = domainPath(...)`。`keyHash = H("tron.archive.key.v1" || u16 algorithmId || u16 domainId || u32 keyLen || canonicalKey)`。`keyHash` 只用于 metadata/collision guard，不作为 tree route。

## 9. HashSpec

### 9.1 Algorithm descriptor

PR7 固定一个算法：

```text
algorithmId = 1
name = TRON_ARCHIVE_SMT_KECCAK_V1
hash = org.tron.common.crypto.Hash.sha3
pathBits = 256
tree = binary_sparse_merkle
```

descriptor 必须写入 `COMMITMENT_META("algorithmDescriptor")`，RootRecord 保存 `algorithmId`。

### 9.2 Domain path

```text
domainPath = H(
  "tron.archive.domain.path.v1"
  || u16 algorithmId
  || u16 domainId
  || u32 keyLen
  || canonicalKey
)
```

`domainPath` 即 `path32`。

### 9.3 Key hash

```text
keyHash = H(
  "tron.archive.key.v1"
  || u16 algorithmId
  || u16 domainId
  || u32 keyLen
  || canonicalKey
)
```

`keyHash` 用于 `LeafRecord` metadata 和 path collision guard，不参与 branch route。

### 9.4 Domain leaf

```text
valueHash = H(
  "tron.archive.domain.value.v1"
  || u16 algorithmId
  || u16 domainId
  || u32 valueLen
  || canonicalValue
)

leafHash = H(
  "tron.archive.domain.leaf.v1"
  || u16 algorithmId
  || u16 domainId
  || domainPath
  || valueHash
)
```

Delete/tombstone 不生成 leaf，使用对应 depth 的 empty hash。

### 9.5 Global path / leaf

```text
globalPath = H(
  "tron.archive.global.path.v1"
  || u16 algorithmId
  || u16 domainId
)

globalLeafHash = H(
  "tron.archive.global.leaf.v1"
  || u16 algorithmId
  || u16 domainId
  || domainRoot
  || u64 leafCount
)
```

Global tree 只包含 `RootPolicy.IN_GLOBAL_ROOT` 的 domain。

### 9.6 Empty hash

```text
empty[256] = H("tron.archive.smt.empty.leaf.v1" || u16 algorithmId)

empty[depth] = H(
  "tron.archive.smt.empty.branch.v1"
  || u16 algorithmId
  || u16 depth
  || empty[depth + 1]
  || empty[depth + 1]
)
```

Branch hash：

```text
branchHash = H(
  "tron.archive.smt.branch.v1"
  || u16 algorithmId
  || u8 treeKind
  || u16 domainId
  || u16 depth
  || leftHash
  || rightHash
)
```

所有 prefix 必须 ASCII 固定，不允许后续静默改名。改名就是新 algorithmId。

## 10. Domain root policy

PR7 不在 builder 内硬编码 domain。它只读取 `ArchiveDomainRegistry`。

P0 建议：

| Domain | RootPolicy | root value normalizer |
| --- | --- | --- |
| `ACCOUNT` | `IN_GLOBAL_ROOT` | full `Account` protobuf bytes；tombstone 删除 |
| `CONTRACT` | `IN_GLOBAL_ROOT` | ABI 已由 `ContractStore.put` 清理后的 `SmartContract` bytes |
| `CODE` | `IN_GLOBAL_ROOT` | runtime code bytes；empty/tombstone 删除 |
| `CONTRACT_STORAGE` | `IN_GLOBAL_ROOT` | 32-byte slot value；zero/tombstone 删除 |
| `DYNAMIC_PROPERTIES` | `DOMAIN_ROOT_ONLY` 或 key-filtered `IN_GLOBAL_ROOT` | 只允许 registry 白名单 key |

关键规则：

- storage 32-byte zero 应从 root 中删除，避免“显式零 leaf”和“missing slot”出现两个 root 语义。
- `DYNAMIC_PROPERTIES` 不能一股脑进入 global root；必须先明确执行语义相关 key 白名单。
- 未启用 domain 不进入 global root。
- 启用但为空的 domain 进入 global root，domainRoot 为该 domain 的 empty root，除非 RootPolicy 明确为 `EXCLUDED`。

## 11. CommitmentBuilder API

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

PR7 不暴露 proof API。`stageBlockEnd` 和 `stageUnwindBlock` 接受 `ArchiveBatch`，确保 root rows 与 temporal rows 同批写入。

如果 PR5 的 `ArchiveTemporalStore.applyBlock()` 已经直接 `rawStore.updateByBatch()`，PR7 需要做一次小重构：

```java
ArchiveBatch batch = new ArchiveBatch();
temporalStore.stageApplyBlock(blockWriteSet, batch);
commitmentBuilder.stageBlockEnd(blockWriteSet, batch);
rawStore.updateByBatch(batch.toRawMap());
```

不要在 PR7 里用：

```text
temporalStore.applyBlock(...)
commitmentBuilder.applyBlock(...)
```

两个独立 batch 会制造 `state 已前进但 root 缺失` 的 crash window。

## 12. 写路径

### 12.1 commitBlock

```java
public void commitBlock() {
  if (!isEnabled()) {
    return;
  }

  BlockWriteSet blockWriteSet = writeCollector.commitBlock();
  ArchiveBatch batch = new ArchiveBatch();

  temporalStore.stageApplyBlock(blockWriteSet, batch);

  if (commitmentBuilder.isEnabled()) {
    commitmentBuilder.stageBlockEnd(blockWriteSet, batch);
  }

  rawStore.updateByBatch(batch.toRawMap());
}
```

顺序：

1. temporal rows 先 stage。
2. commitment rows 后 stage。
3. progress/rootProgress 最后 stage。
4. 单个 physical `archive` DB batch flush。

如果 commitment 计算失败：

- 不 flush archive sidecar batch。
- canonical DB 可能已经 commit，archive progress 仍停在前一 block。
- startup verifier 应识别 archive behind，并要求 replay/repair。
- 不能写半个 root。

如果在一个已有 archive DB 上首次开启 `commitment.enable=true`，但 `ROOT_BLOCK(progress.appliedBlockNum)` 不存在，节点不能从 empty root 继续写。必须先执行 rebuild/bootstrap，或让 startup verifier 返回 `ROOT_MISSING/REPAIR_REQUIRED`。

### 12.2 update batch

对每个 `TxWriteSet`：

1. 从 `DomainWrite.afterValue` 生成 `CommitmentUpdate`。
2. 用 registry normalizer 判断 present/delete。
3. 按 domain 分组。
4. 每个 domain 内按 `path32 ASC, canonicalKey ASC` 排序。
5. 更新 domain sparse tree。
6. tx roots 默认不落盘。

block 结束：

1. 对发生变化的 `IN_GLOBAL_ROOT` domain，更新 global sparse tree。
2. 为所有 root-included domain 写 `ROOT_DOMAIN(domain, blockNum)`，即使该 block 未修改该 domain。
3. 写 `ROOT_BLOCK(blockNum)`。
4. 为所有 root-included domain 和 global tree 写 `ROOT_CURRENT`，即使 root hash 未变化，也要更新 `latestBlockNum/latestAsOfTxNum`。
5. 写 `COMMITMENT_META(rootProgress)`。

空块或无 root-domain 写入的 block 仍然必须写 `ROOT_BLOCK(blockNum)`。这样 `getRoot(BLOCK_END(blockNum))` 不需要向前搜索，也能明确证明该 block 的 state root 与 parent 相同。

### 12.3 SparseMerkleTree update

接口草案：

```java
public interface CommitmentTree {
  byte[] rootHash();

  UpdateResult update(byte[] path32, Optional<byte[]> leafHash, ArchiveBatch batch)
      throws CommitmentException;
}
```

实现逻辑：

```text
oldRoot = currentRoot
walk depth 0..255:
  if current node hash == empty[depth]:
    sibling = empty[depth + 1]
  else:
    load NodeRecord by hash
    choose child by path bit
    keep sibling hash

newChild = leafHash or empty[256]
for depth 255..0:
  newParent = branchHash(depth, left, right)
  if newParent != empty[depth]:
    stage ROOT_NODE(newParent) -> NodeRecord
return newRoot
```

Node 是内容寻址的 immutable record。不要覆盖旧 node，也不要在 hot unwind 时删除 node。后续可以做 GC。

### 12.4 staged read overlay

同一个 block 内多个 update 可能读到刚 stage 但尚未 flush 的 node。`SparseMerkleTree` 读 node 时必须先查 `ArchiveBatch`，再查 `ArchiveRawStore`：

```text
CommitmentNodeReader:
  getNode(hash):
    if batch has ROOT_NODE(hash): return batch value
    return rawStore.get(ROOT_NODE(hash))
```

否则同一 block 多个 key 更新会基于旧 root 计算，导致 root 错误。

## 13. Unwind

PR7 的 sparse nodes 内容寻址，不需要 node-level before-value changeset。

`stageUnwindBlock(blockNum, blockHash, batch)`：

1. 校验 `ROOT_BLOCK(blockNum)` 存在且 blockHash 匹配。
2. 删除 `ROOT_BLOCK(blockNum)`。
3. 删除该 block 的 `ROOT_DOMAIN(domain, blockNum)`。
4. 如果 `persistTxRoots=true`，删除该 block tx range 的 `ROOT_TX(txNum)`。
5. 查找 `ROOT_BLOCK(blockNum - 1)`。
6. 用前一 block 的 root record 恢复 `ROOT_CURRENT`。
7. 用 temporal history/changeset 反向恢复 changed rooted keys 的 `ROOT_LEAF(path32)` 元数据，或在标记 OK 前完成 leaf metadata rebuild。
8. 更新 `COMMITMENT_META(rootProgress)` 到前一 block。

如果 `blockNum - 1` 没有 root：

- genesis 前或 root 启用起点前，恢复到 empty roots。
- 否则返回 `CommitmentException(REPAIR_REQUIRED)`。

注意调用顺序：

```java
ArchiveBatch batch = new ArchiveBatch();
temporalStore.stageUnwindBlock(blockNum, blockHash, batch);
commitmentBuilder.stageUnwindBlock(blockNum, blockHash, batch);
rawStore.updateByBatch(batch.toRawMap());
```

Temporal 和 commitment unwind 也必须同批。

`ROOT_CURRENT` 只恢复 root hash，不足以保证下一块可以继续增量更新；`ROOT_LEAF` metadata 也必须恢复或重建，否则 leafCount 和 path collision guard 会与 restored root 不一致。

## 14. Rebuild

PR7 至少提供 block-end latest rebuild，用于测试和 startup integrity check。

### 14.1 rebuild latest

```text
scan LATEST table for included domains
  -> normalize root value
  -> build domain sparse trees from empty
  -> build global sparse tree
  -> compare with ROOT_BLOCK(progress.appliedBlockNum)
```

这个 rebuild 可以慢，不走 block apply 热路径。

### 14.2 rebuild historical block

PR7 可以只定义接口，不要求生产实现：

```text
scan CHANGESET/HISTORY to reconstruct included domain values as of block
```

如果实现成本过高，PR7 测试只要求 latest rebuild；历史 block rebuild 放到后续 proof/checkpoint PR。

### 14.3 root mismatch

`checkIntegrity` 返回：

```text
OK
COMMITMENT_DISABLED
ROOT_MISSING
ROOT_AHEAD_OF_ARCHIVE
ROOT_BEHIND_ARCHIVE
ROOT_MISMATCH
REPAIR_REQUIRED
```

不要在 startup 自动删除 root 数据。自动 repair 需要单独配置。

## 15. 与现有 accountStateRoot 的关系

PR7 不调用：

```text
BlockCapsule.setAccountStateRoot
AccountStateCallBack.executeGenerateFinish
AccountStateCallBack.executePushFinish
```

PR7 不读取 `BlockHeader.raw.accountStateRoot` 作为 parent root。

可以借鉴的只有流程：

```text
parent root -> tx dirty entries -> block finish root
```

但 archive root parent 来自：

```text
ROOT_CURRENT / ROOT_BLOCK(blockNum - 1)
```

而不是 block header。

## 16. 与 Erigon 源码原则对齐

PR7 必须吸收 Erigon 的这些不变量：

1. root 计算消费 touch/write-set，不全量扫描 hot path。
2. update 必须绑定原始 `StatePoint`。
3. key 排序按 commitment path，不按 raw key 或 Map iteration。
4. builder 读取的是 root 计算点的 post-state 或 write-set after-value，不读 live Store。
5. branch/node 更新是独立持久化对象，不只保存 root hash。
6. deferred/staged node 写入必须保留 block/tx 归属，便于 unwind/repair。

java-tron 的适配：

- Erigon 是单棵 Ethereum state trie。
- java-tron PR7 使用 `domainRoot -> globalRoot` 两层 root。
- domain 覆盖范围由 `ArchiveDomainRegistry` 声明。

## 17. 测试设计

### 17.1 Unit：hash spec

```text
chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentHashTest.java
```

覆盖：

1. empty hash vector 固定。
2. domain path 相同输入相同输出。
3. 不同 domain 相同 key path 不同。
4. branch hash left/right 顺序敏感。
5. algorithmId 不同输出不同。
6. `domainPath != keyHash`，且二者都有 key length。

### 17.2 Unit：SparseMerkleTree

```text
chainbase/src/test/java/org/tron/core/archive/commitment/SparseMerkleTreeTest.java
```

覆盖：

1. empty tree root 等于 `empty[0]`。
2. put one leaf root 改变。
3. delete same leaf 回到 empty root。
4. put A then B 和 put B then A root 相同。
5. same leaf update 后 root 可重复。
6. batch overlay：同一 batch 两次 update 能读到 staged node。
7. missing non-empty node 报 corruption，不回退 empty。

### 17.3 Unit：CommitmentBuilder

```text
chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentBuilderTest.java
```

覆盖：

1. `ACCOUNT` write 生成 domain root 和 global root。
2. `CONTRACT_STORAGE` zero after-value 删除 leaf。
3. `DOMAIN_ROOT_ONLY` 不进入 global root。
4. `EXCLUDED` domain 不写 domain root。
5. 同一 block 多 tx 修改同 key，block root 取最后 after-value。
6. writes 输入乱序，root deterministic。
7. RootRecord 保存 `ARCHIVE_SIDECAR/NONE/TVM_STATE_ONLY`。
8. empty block 仍写 `ROOT_BLOCK`，root 等于 parent。
9. `ROOT_LEAF(path32)` collision guard：同 path 不同 canonical key 报错。

### 17.4 Unit：unwind

```text
chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentUnwindTest.java
```

覆盖：

1. block 2 root current 指向 root2。
2. unwind block 2 后 current 恢复 root1。
3. `ROOT_BLOCK(2)` 删除。
4. content-addressed old nodes 未删除但不影响 root1 后续更新。
5. `ROOT_LEAF` metadata 恢复到 block 1 后状态。

### 17.5 Unit：rebuild

```text
chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentRebuilderTest.java
```

覆盖：

1. 从 `LATEST` 扫描 rebuild latest root。
2. rebuild root 等于 incremental root。
3. 篡改 `ROOT_NODE` 或 `ROOT_BLOCK` 后 `checkIntegrity` 返回 mismatch。

### 17.6 Integration

```text
framework/src/test/java/org/tron/core/archive/ArchiveCommitmentIntegrationTest.java
```

覆盖：

1. archive commitment disabled：PR1-PR6 行为不变。
2. transfer block 改 account 后 block root 改变。
3. contract deploy 后 `CONTRACT/CODE` root 改变。
4. storage write 后 `CONTRACT_STORAGE` root 改变。
5. replay 同一段链 root 完全一致。
6. switch fork / erase block 后 root 回到 fork 前。

## 18. 验收命令

定向测试：

```bash
./gradlew :chainbase:test --tests '*Commitment*'
./gradlew :framework:test --tests '*ArchiveCommitment*'
```

PR 级别建议：

```bash
./gradlew :chainbase:test --tests '*Archive*'
./gradlew :framework:test --tests '*Archive*'
./gradlew checkstyleMain
```

文档阶段不要求执行这些命令。

## 19. Code review 检查表

- [ ] PR7 没有写 `BlockHeader.raw.accountStateRoot`。
- [ ] PR7 没有 import `framework` 的 `TrieImpl` 到 `chainbase`。
- [ ] commitment 默认关闭。
- [ ] root rows 与 temporal rows 在同一个 physical `archive` DB batch 中提交。
- [ ] RootRecord 标记 `ARCHIVE_SIDECAR` 和 `consensusParticipation=NONE`。
- [ ] `BLOCK_END(blockNum)` 使用 PR6/TxNumIndex 的 `lastTxNum + 1`。
- [ ] domain update 按 commitment path 排序。
- [ ] root builder 使用 `DomainWrite.afterValue` 或 archive reader 的 as-of post-state，不读 latest live Store。
- [ ] storage zero normalizes to delete leaf。
- [ ] content-addressed node 不在 unwind 中删除，`ROOT_CURRENT` 正确恢复。
- [ ] `ROOT_LEAF` metadata 在 unwind 后与 restored current root 一致。
- [ ] rebuild latest root 等于 incremental root。
- [ ] 没有添加测试 skip。

## 20. 后续 PR

PR7 之后可以进入两条线：

1. PR8 historical `eth_call`：复用 `ArchiveStateReader`，新增 `ArchiveRepositoryAdapter` 和 overlay。
2. PR9 proof/debug API：在 PR7 node store 上实现 archive-native domain proof + global proof、tx-level root on-demand replay、proof verifier 和受控 debug API；详见 [java-tron Archive PR9 Proof/Debug API 代码级实现规格](./20260602-java-tron-archive-pr9-proof-debug-api-implementation-spec.md)。

checkpoint rebuild 和 root node GC 可以继续作为 PR9 之后的存储/运维优化，不应阻塞首版 archive-native proof。

不要在 PR8 之前把 archive sidecar root 接入共识。进入共识需要独立 TIP/Proposal、激活高度、完整 domain 覆盖和性能预算。
