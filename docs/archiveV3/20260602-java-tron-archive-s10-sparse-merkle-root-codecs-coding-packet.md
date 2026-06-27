# java-tron Archive S10：Sparse Merkle Tree Core + Root Codecs 编码执行包

日期：2026-06-02

> 2026-06-03 更新：本文是旧 `a79693e450` S10 执行包。当前 `4e80f8ffa9a2` 的 S10/S11 编码入口请看 [java-tron Archive S10/S11：CommitmentBuilder 4e80 编码执行包](./20260603-java-tron-archive-s10-s11-commitment-builder-4e80-coding-packet.md)，本文只保留作历史设计参考。

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

前置执行包：

- [S3 ArchiveDomainRegistry 编码执行包](./20260602-java-tron-archive-s3-domain-registry-coding-packet.md)
- [S4 ArchiveWriteCollector 编码执行包](./20260602-java-tron-archive-s4-write-collector-coding-packet.md)
- [S6 ArchiveRawStore + Temporal Codecs 编码执行包](./20260602-java-tron-archive-s6-raw-store-temporal-codecs-coding-packet.md)
- [S7 Temporal Commit / GetAsOf / Unwind / Startup 编码执行包](./20260602-java-tron-archive-s7-temporal-commit-unwind-startup-coding-packet.md)
- [S8 ArchiveStateReader Core 编码执行包](./20260602-java-tron-archive-s8-state-reader-core-coding-packet.md)
- [S9 JSON-RPC Historical Getters 编码执行包](./20260602-java-tron-archive-s9-jsonrpc-historical-getters-coding-packet.md)

归属规格：

- [S10/S11 4e80 编码执行包](./20260603-java-tron-archive-s10-s11-commitment-builder-4e80-coding-packet.md)
- [PR7 CommitmentBuilder 代码级实现规格](./20260602-java-tron-archive-pr7-commitment-builder-implementation-spec.md)
- [模块 06 CommitmentBuilder 逐文件 Patch 清单](./20260602-java-tron-archive-module-06-commitment-builder-patch-checklist.md)
- [模块 06 CommitmentBuilder：4e80 java-tron 源码对照细化](./20260603-java-tron-module-06-commitment-builder-4e80-source-deep-dive.md)
- [模块 06 CommitmentBuilder：Erigon 源码对照深挖](./20260601-java-tron-module-06-commitment-builder-erigon-source-deep-dive.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

Erigon 源码路径：`/Users/boson/GolandProjects/erigon`

旧执行包原复核基线：本地 java-tron `a79693e450`。当前 `4e80f8ffa9a2` 的 Module 06 源码事实请以 [模块 06 CommitmentBuilder：4e80 java-tron 源码对照细化](./20260603-java-tron-module-06-commitment-builder-4e80-source-deep-dive.md) 和 [S10/S11 4e80 编码执行包](./20260603-java-tron-archive-s10-s11-commitment-builder-4e80-coding-packet.md) 为准。

## 1. 本包目标

S10 对应 PR7 前半段：

```text
ArchiveTable 0x30+ root keys
  -> root/node/leaf binary codecs
  -> domain-separated hash spec
  -> content-addressed binary sparse Merkle tree
  -> staged-batch node overlay
```

S10 只交付 tree core 和 root codecs，不接 block apply。

| 范围 | S10 是否交付 | 说明 |
| --- | --- | --- |
| `ArchiveTable` commitment prefixes | 是 | `0x30..0x36` root rows 进入同一个 physical `archive` DB |
| `RootKeyCodec` | 是 | commitment meta/root/current/node/leaf keys |
| root record model + codec | 是 | `RootRecord`、`DomainRootRecord`、`CurrentRootRecord` |
| node/leaf record model + codec | 是 | `NodeRecord`、`LeafRecord` |
| `CommitmentHash` / algorithm descriptor | 是 | `TRON_ARCHIVE_SMT_KECCAK_V1` |
| `SparseMerkleTree` | 是 | 256-bit binary sparse tree，content-addressed branch nodes |
| staged node overlay | 是 | tree update 先读本次 `ArchiveBatch`，再读 raw store |
| `RootValueNormalizer` | 是 | 把 afterValue 规范成 present/delete/valueHash 输入 |
| `DefaultCommitmentBuilder.stageBlockEnd` | 否 | S11 做 |
| block-end `ROOT_BLOCK` 写入 | 否 | S11 做 |
| hot unwind root current 恢复 | 否 | S11 做，S10 只提供 codec |
| rebuild verifier | 否 | S11 做 |
| proof/debug API | 否 | PR9/S14 做 |

核心原则：

```text
archive root is sidecar
do not write BlockHeader.raw.accountStateRoot
do not import framework TrieImpl into chainbase
do not compute roots from latest Store scans during normal block apply
same ArchiveBatch must contain temporal rows and root rows
```

## 2. java-tron 当前源码事实

### 2.1 Hash primitive 可复用，EMPTY_TRIE_HASH 不可复用

`/Users/boson/IdeaProjects/java-tron/crypto/src/main/java/org/tron/common/crypto/Hash.java:41-78`：

| 源码事实 | 对 S10 的结论 |
| --- | --- |
| `EMPTY_TRIE_HASH` 是 `sha3(encodeElement(EMPTY_BYTE_ARRAY))` | 这是 RLP Patricia 空 trie hash，不能作为 archive SMT empty root |
| `sha3(byte[])` 使用 `TRON-KECCAK-256` | S10 的 `CommitmentHash` 可以复用 `Hash.sha3(preimage)` |
| `sha3` 返回 32-byte digest | S10 所有 commitment hash 长度固定 32 |

S10 自己定义 empty hash chain：

```text
empty[256] = H("tron.archive.smt.empty.leaf.v1" || algorithmId_u16)
empty[d]   = H("tron.archive.smt.empty.branch.v1" || algorithmId_u16 || depth_u16 || empty[d+1] || empty[d+1])
```

不要把 `Hash.EMPTY_TRIE_HASH` 写进 `ROOT_CURRENT` 或 `RootRecord.globalRoot`。

### 2.2 `TrieImpl` 是 framework 里的 RLP Hex Patricia Trie

`/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/trie/Trie.java:6-23` 定义了：

```text
getRootHash / setRoot / clear / put / get / delete / flush
```

`/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/trie/TrieImpl.java:286-305`：

| 源码事实 | 对 S10 的结论 |
| --- | --- |
| `getRootHash()` 先 `encode()`，空 root 返回 `EMPTY_TRIE_HASH` | root 语义绑定 RLP Patricia，不适合 archive SMT |
| `flush()` 把 dirty nodes 持久化，再把 root 收缩成 hash node | 可借鉴“root hash + node store”思想，但不能复用编码 |
| `setRoot()` 把 `EMPTY_TRIE_HASH` 当空树 | S10 empty root 来自 SMT empty chain，不使用这个特殊值 |

`/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/trie/TrieImpl.java:377-552` 已有 `prove/verifyProof`，但 proof 逻辑依赖：

- RLP node encoding。
- hex nibble path。
- branch/KVNode node 类型。
- `Hash.encodeElement` 和 short-node inline/hash threshold。

因此 PR9 不能直接复用这套 proof；S10 也不要把 `TrieImpl.Node`、`TrieKey`、RLP node 编码引入 archive root。

### 2.3 `AccountStateCallBack` 只能借生命周期

`/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/db/accountstate/callback/AccountStateCallBack.java:52-103` 的流程：

| 阶段 | 现有行为 | S10/S11 借鉴点 |
| --- | --- | --- |
| `preExecute` | 从 parent block header 读取 `accountStateRoot`，构造 `new TrieImpl(db, rootHash)` | root update 要从当前 root 指针初始化 |
| `executePushFinish` | 重新计算 trie root，与 block header `accountStateRoot` 比较 | archive root 以后可做 verifier，但不参与共识 header 校验 |
| `executeGenerateFinish` | `blockCapsule.setAccountStateRoot(newRoot)` | archive sidecar 禁止调用 |

S10/S11 不能读取 parent header 的 `accountStateRoot` 作为 archive parent root。archive parent root 应来自：

```text
ROOT_CURRENT(algorithmId, treeKind, domainId)
or empty root when bootstrapping from a declared empty/progress state
```

当前 `AccountStateCallBack` 的删除入口也不能直接借用为 archive delete 语义：`AccountStore.put` 在 `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/store/AccountStore.java:68-89` 写入 account 后触发 callback，但 `AccountStore.delete` 在 `AccountStore.java:91-105` 没有调用 `AccountStateCallBack.deleteAccount`。S10/S11 的删除必须来自 `DomainWrite.afterValue` tombstone/normalizer，不依赖现有 account callback。

### 2.4 `AccountStateStoreTrie` 不是完整 archive 状态

`/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/db/accountstate/storetrie/AccountStateStoreTrie.java:35-42`：

```text
getAccount(key, rootHash)
  -> new TrieImpl(this, rootHash)
  -> trie.get(Hash.encodeElement(key))
  -> AccountStateEntity.parse(value)
```

`/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/db/accountstate/AccountStateEntity.java:16-21` 只复制：

```text
address
balance
allowance
```

这不是 `ACCOUNT/CONTRACT/CODE/CONTRACT_STORAGE` 的完整 TVM archive state。S10 不能把它作为 leaf value，也不能把它的 root 描述成 issue #6289 所需状态树。

### 2.5 `BlockCapsule` 的两个 root 都不是 archive root

`/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/capsule/BlockCapsule.java:218-244`：

- `calcMerkleRoot()` 计算交易 Merkle root。
- `validateMerkleRoot()` 校验交易 Merkle root。
- 空交易返回 `Sha256Hash.ZERO_HASH`。

`/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/capsule/BlockCapsule.java:255-262`：

- `setAccountStateRoot(byte[] root)` 写 `BlockHeader.raw.accountStateRoot`。

S10/S11 结论：

```text
txTrieRoot != state root
accountStateRoot != archive sidecar root
RootRecord lives only in archive DB
```

## 3. Erigon 源码对照结论

### 3.1 Commitment trie 不直接绑定 DB

`/Users/boson/GolandProjects/erigon/execution/commitment/commitment.go:91-141`：

| Erigon 源码事实 | java-tron S10 取舍 |
| --- | --- |
| `Trie` 暴露 `RootHash()`、`ResetContext(ctx)`、`Process(updates, ...)` | S10 把 tree core 与 raw store IO 分离 |
| `PatriciaContext` 提供 `Branch/PutBranch/Account/Storage` | S10 提供 `CommitmentNodeReader/Writer`，不要让 tree 直接扫 Store |
| account/storage 读取通过 context 注入 | java-tron normal block apply 用 `BlockWriteSet`，rebuild 才走 `ArchiveStateReader` |

S10 的 tree core API 不依赖 `Manager`、`Wallet`、`Store`、`BlockCapsule`。

### 3.2 更新必须按 hashed path 顺序处理

`/Users/boson/GolandProjects/erigon/execution/commitment/commitment.go:1429-1440` 明确 `Updates` 按 hashed key 排序，注释指出 plain key 顺序会导致 divergent root。

`/Users/boson/GolandProjects/erigon/execution/commitment/commitment.go:1797-1981` 的 `HashSort` 和 `keyUpdateLessFn` 也是同一个约束：

```text
sort by hashedKey
plainKey only as tiebreaker
```

S10/S11 对 java-tron 的硬规则：

```text
domain tree: sort by path32 ASC, then canonicalKey ASC
global tree: sort by globalPath ASC, then domainId ASC
```

不要按以下顺序生成 root：

- raw store iteration。
- `HashMap` iteration。
- enum declaration order。
- canonical key 字典序。
- transaction original order。

### 3.3 Erigon 保存的不只是 root hash

`/Users/boson/GolandProjects/erigon/execution/commitment/commitmentdb/commitment_context.go:297-324`：

- `ComputeCommitment` 在没有更新时仍从当前 trie state 读 `RootHash()`。

`/Users/boson/GolandProjects/erigon/execution/commitment/commitmentdb/commitment_context.go:436-485`：

- `Process(...)` 之后，如果 `saveState`，调用 `encodeAndStoreCommitmentState(...)`。

`/Users/boson/GolandProjects/erigon/execution/commitment/commitmentdb/commitment_context.go:681-705`：

- commitment state 单独编码并写入 branch storage。

S10 对应到 java-tron：

| Erigon 不变量 | S10/S11 对应物 |
| --- | --- |
| root hash 之外保存 trie state | `ROOT_CURRENT` 保存 current root、leafCount、latestBlockNum、latestAsOfTxNum |
| branch/state 与 plain state 一起推进 | root rows 与 temporal rows 放同一个 `ArchiveBatch` |
| crash 后能判断 progress | `COMMITMENT_META(rootProgress)` 和 `ROOT_BLOCK(blockNum)` 由 S11 写 |

S10 只提供 `CurrentRootRecord` codec；S11 负责更新进度。

### 3.4 Reader 视角要可拆分

`/Users/boson/GolandProjects/erigon/execution/commitment/commitmentdb/reader.go:9-80` 有 `LatestStateReader` 和 `HistoryStateReader`。

`/Users/boson/GolandProjects/erigon/execution/commitment/commitmentdb/reader.go:118-140` 有 `SplitStateReader`，可以把 commitment data 和 plain state data 的 as-of 边界拆开。

java-tron P0 不需要先实现 split reader，但 S10 的 codec 必须给 S11/S14 留足信息：

- `RootRecord.asOfTxNum`。
- `DomainRootRecord.asOfTxNum`。
- `CurrentRootRecord.latestAsOfTxNum`。
- `RootRecord.registryChecksum`。
- `DomainRootRecord.keyCodecVersion/valueCodecVersion`。

### 3.5 并发 commitment 不是 S10 范围

`/Users/boson/GolandProjects/erigon/execution/commitment/hex_concurrent_patricia_hashed.go:207-294` 把 updates 按 nibble 拆并行处理，最后 fold root。

S10 不实现并行 tree update。原因：

- java-tron P0 先证明 root 正确性和 archive DB 原子性。
- binary sparse tree 的同批 overlay 需要先稳定。
- 并发拆分要求更严格的 staged node merge 语义，适合后续性能 PR。

但 S10 仍保留可并行的输入条件：updates 已经按 path 排序，tree core 不依赖外部 mutable global state。

## 4. S10 关键设计决定

### 4.1 修正早稿：`ROOT_LEAF` 应按 `path32` 建 key

早期 PR7 草案把 `ROOT_LEAF` key 写成：

```text
0x36 | u16 algorithmId | u16 domainId | u32 keyLen | canonicalKey
```

这不适合作为 collision guard。原因：

- sparse tree 的物理位置由 `path32` 决定。
- 两个不同 `canonicalKey` 如果碰撞到同一个 `path32`，按 canonicalKey 建 key 无法在 update 时 O(1) 检测。
- tree update 只看到 `path32`，会把同一路径上的 leaf 覆盖成最后一个 leaf，导致 `ROOT_LEAF` 元数据和 root 不一致。

S10 固定为：

```text
ROOT_LEAF:
  0x36 | u16 algorithmId | u16 domainId | bytes32 path32 -> LeafRecord
```

`LeafRecord` 内保存 canonical key：

```text
u32 keyLen | canonicalKey
```

这样：

- update/delete 都先由 canonical key 计算 `path32`，再读 `ROOT_LEAF(path32)`。
- 如果现有 `LeafRecord.keyHash/canonicalKey` 与新写入不同，抛 `CommitmentExceptionReason.PATH_COLLISION`。
- delete 时只删除 `ROOT_LEAF(path32)`。
- rebuild 时可按 `path32 ASC, canonicalKey ASC` 复算。

### 4.2 `path32` 和 `keyHash` 分工

S10 固定三个 hash：

```text
path32   = domainPath(...)
keyHash  = H("tron.archive.key.v1" || algorithmId_u16 || domainId_u16 || keyLen_u32 || canonicalKey)
leafHash = domainLeaf(path32, valueHash)
```

| 字段 | 用途 |
| --- | --- |
| `path32` | sparse tree route，必须 32 bytes |
| `keyHash` | leaf metadata/collision guard/debug，不参与 branch route |
| `valueHash` | canonical value commitment |
| `leafHash` | depth 256 leaf content |

`leafHash` 使用 `path32`，不是 `keyHash`。这样 tree proof 只需要 path + value commitment，而 debug/rebuild 仍能检查 canonical key。

### 4.3 S10 只维护 branch nodes，leaf 作为 metadata

`NodeRecord` 只表示 branch：

```text
depth 0..255
leftHash
rightHash
nodeHash = branchHash(depth, leftHash, rightHash)
```

leaf 不作为 `ROOT_NODE` 存储。depth 256 的叶子 hash 来自：

- present：`LeafRecord.leafHash`。
- delete/missing：`empty[256]`。

### 4.4 内容寻址 node 不覆盖、不删除

`ROOT_NODE` key 包含 `nodeHash`：

```text
0x34 | u16 algorithmId | u8 treeKind | u16 domainId | bytes32 nodeHash -> NodeRecord
```

规则：

- 同一个 `nodeHash` 对应唯一 `NodeRecord`。
- 重复 put 相同内容允许。
- 如果 key 里的 `nodeHash` 与 value 里的 `nodeHash` 不一致，读取时视为 corruption。
- hot unwind 不删除 old nodes，只恢复 `ROOT_CURRENT`。
- S10/S11 不做 node GC。

### 4.5 S10 需要扩展 `ArchiveBatch` 的只读 overlay

S6 已有：

```java
void put(byte[] key, byte[] value);
void delete(byte[] key);
boolean containsKey(byte[] key);
Map<byte[], byte[]> toRawMap();
```

S10 需要补充：

```java
Optional<byte[]> get(byte[] key);
```

语义：

```text
containsKey(key) == false -> 本 batch 未 stage 该 key
containsKey(key) == true && get(key).isPresent() -> staged put
containsKey(key) == true && get(key).isEmpty() -> staged delete
```

tree node reader 必须：

```text
if batch.containsKey(ROOT_NODE(hash)):
  return batch.get(ROOT_NODE(hash))      // empty means staged delete/corruption for node read
return rawStore.get(ROOT_NODE(hash))
```

没有这个 overlay，同一个 block 内第二个 key update 会基于旧 root 读取 node，漏掉第一个 key 刚 stage 的 branch。

## 5. 代码落点总表

### 5.1 修改已有文件

| java-tron 文件 | 动作 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveTable.java` | 确保存在 `COMMITMENT_META/ROOT_BLOCK/ROOT_DOMAIN/ROOT_TX/ROOT_NODE/ROOT_CURRENT/ROOT_LEAF` |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveBatch.java` | 增加 `Optional<byte[]> get(byte[] key)` |
| `chainbase/src/main/java/org/tron/core/archive/store/DefaultArchiveBatch.java` | 实现 staged value lookup，copy 输出 value |

### 5.2 新增 commitment package

```text
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentException.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentExceptionReason.java
chainbase/src/main/java/org/tron/core/archive/commitment/TreeKind.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootScope.java
chainbase/src/main/java/org/tron/core/archive/commitment/ConsensusParticipation.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootAlgorithm.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootAlgorithmDescriptor.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootKeyCodec.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentHash.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentPath.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/DomainRootRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/CurrentRootRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/NodeRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/LeafRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootRecordCodec.java
chainbase/src/main/java/org/tron/core/archive/commitment/DomainRootRecordCodec.java
chainbase/src/main/java/org/tron/core/archive/commitment/CurrentRootRecordCodec.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentNodeCodec.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentNodeReader.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentNodeWriter.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentTree.java
chainbase/src/main/java/org/tron/core/archive/commitment/SparseMerkleTree.java
chainbase/src/main/java/org/tron/core/archive/commitment/UpdateResult.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootValueNormalizer.java
chainbase/src/main/java/org/tron/core/archive/commitment/NormalizedRootValue.java
```

## 6. Patch 1：Archive table prefixes 和 RootKeyCodec

### 6.1 Prefix 固定

`ArchiveTable`：

```java
COMMITMENT_META((byte) 0x30),
ROOT_BLOCK((byte) 0x31),
ROOT_DOMAIN((byte) 0x32),
ROOT_TX((byte) 0x33),
ROOT_NODE((byte) 0x34),
ROOT_CURRENT((byte) 0x35),
ROOT_LEAF((byte) 0x36)
```

### 6.2 Key schema

所有数值 big-endian。

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

`RootKeyCodec` API：

```java
public final class RootKeyCodec {
  public byte[] commitmentMeta(String asciiName);
  public byte[] rootBlock(long blockNum);
  public byte[] rootDomain(short domainId, long blockNum);
  public byte[] rootTx(long txNum);
  public byte[] rootNode(short algorithmId, TreeKind treeKind, short domainId, byte[] nodeHash);
  public byte[] rootCurrent(short algorithmId, TreeKind treeKind, short domainId);
  public byte[] rootLeaf(short algorithmId, short domainId, byte[] path32);
}
```

Validation：

| 输入 | 规则 |
| --- | --- |
| `algorithmId` | unsigned `u16`，P0 只接受 `1` |
| `domainId` | unsigned `u16`，global tree 固定 `0` |
| `treeKind` | `1=DOMAIN_TREE`，`2=GLOBAL_TREE` |
| `nodeHash` | 32 bytes |
| `path32` | 32 bytes |
| `asciiName` | printable ASCII，非空 |

测试：

```text
RootKeyCodecTest
```

覆盖：

- encode deterministic hex vector。
- prefix scan 范围。
- `domainId=0,1,255,256,65535`。
- `domainId < 0` 以 unsigned short 工具封装，不让 Java signed short 泄漏到调用端。
- malformed key length。
- invalid treeKind。
- `ROOT_LEAF` 以 `path32` 排序。

## 7. Patch 2：record model 和 binary codecs

### 7.1 通用编码规则

所有 value 使用稳定二进制：

```text
u8/u16/u32/u64 = big-endian unsigned
bytes = u32 length + bytes
enum = stored numeric id
schemaVersion = u32
```

禁止：

- Java serialization。
- JSON。
- 复用链上 protobuf 作为 root record 外壳。
- 变长整数。
- 平台默认 charset。

Decode 必须：

- 检查 schemaVersion。
- 检查 enum known。
- 检查 hash length。
- 检查 consumed all bytes。
- 对所有 `byte[]` 做 defensive copy。
- 用 `CommitmentExceptionReason.CODEC_ERROR` 或 `CORRUPT_RECORD` 报错。

### 7.2 `RootRecord`

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

P0 固定：

```text
schemaVersion = 1
algorithmId = 1
rootScope = ARCHIVE_SIDECAR
consensusParticipation = NONE
coverage = storage.archive.commitment.coverage
globalRootLen = 32
domainRootLen = 32
```

`domainCount` 内按 `domainId ASC` 排序。

### 7.3 `DomainRootRecord`

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

规则：

- `rootLen == 32`。
- `leafCount` 来自 active `ROOT_LEAF`，不是本 block update count。
- `rootPolicy` 使用 S3 `RootPolicy` 的稳定 numeric id。

### 7.4 `CurrentRootRecord`

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

规则：

- global tree：`treeKind=GLOBAL_TREE`，`domainId=0`。
- domain tree：`treeKind=DOMAIN_TREE`，`domainId=registry domain id`。
- root empty 时仍写 32-byte SMT empty root，不写空 byte array。

### 7.5 `NodeRecord`

```text
u32 schemaVersion
u16 algorithmId
u8  treeKind
u16 domainId
u16 depth              // 0..255 for branch nodes
u32 leftHashLen | leftHash
u32 rightHashLen | rightHash
u32 nodeHashLen | nodeHash
```

规则：

- `leftHash/rightHash/nodeHash` 都必须 32 bytes。
- `nodeHash == CommitmentHash.branchHash(...)`。
- `depth=256` 不允许作为 branch `NodeRecord`。
- child 可以是 empty hash。
- 如果 `nodeHash == empty[depth]`，不需要写 `ROOT_NODE`。

### 7.6 `LeafRecord`

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

规则：

- `pathLen == 32`。
- `keyLen > 0`，除非某个 domain 明确定义 empty key 合法。
- `keyHashLen/valueHashLen/leafHashLen == 32`。
- `keyHash = CommitmentHash.keyHash(...)`。
- `leafHash = CommitmentHash.domainLeafHash(path32, valueHash, ...)`。

测试：

```text
RootRecordCodecTest
CommitmentNodeCodecTest
```

覆盖：

- 每个 record roundtrip。
- version mismatch。
- truncated bytes。
- trailing bytes。
- invalid enum。
- hash length 非 32。
- `domainId=256` 和 `domainId=65535` roundtrip。
- Java signed `short` 不影响编码结果。
- `LeafRecord` path collision fixture：相同 `path32` 不同 `canonicalKey/keyHash` 会被上层 guard 拒绝。

## 8. Patch 3：Root algorithm descriptor 和 CommitmentHash

### 8.1 Algorithm descriptor

P0 只固定一个算法：

```text
algorithmId = 1
name = TRON_ARCHIVE_SMT_KECCAK_V1
hash = org.tron.common.crypto.Hash.sha3
pathBits = 256
tree = binary_sparse_merkle
leafEncoding = domain-separated-value-hash
nodeEncoding = domain-separated-branch-hash
```

Descriptor 写入：

```text
COMMITMENT_META("algorithmDescriptor")
```

Root records 保存 `algorithmId`，不重复保存整份 descriptor。

### 8.2 Preimage 编码

`CommitmentHash` 内统一构造 preimage，不允许调用方自己拼 bytes。

```java
public final class CommitmentHash {
  public byte[] domainPath(short algorithmId, short domainId, byte[] canonicalKey);
  public byte[] keyHash(short algorithmId, short domainId, byte[] canonicalKey);
  public byte[] valueHash(short algorithmId, short domainId, byte[] canonicalValue);
  public byte[] domainLeafHash(short algorithmId, short domainId, byte[] path32, byte[] valueHash);
  public byte[] globalPath(short algorithmId, short domainId);
  public byte[] globalLeafHash(short algorithmId, short domainId, byte[] domainRoot, long leafCount);
  public byte[] emptyHash(short algorithmId, int depth);
  public byte[] branchHash(short algorithmId, TreeKind treeKind, short domainId, int depth,
      byte[] leftHash, byte[] rightHash);
}
```

### 8.3 Domain path

```text
domainPath = H(
  "tron.archive.domain.path.v1"
  || algorithmId_u16
  || domainId_u16
  || keyLen_u32
  || canonicalKey
)
```

`domainPath` 即 `path32`。

### 8.4 Key hash

```text
keyHash = H(
  "tron.archive.key.v1"
  || algorithmId_u16
  || domainId_u16
  || keyLen_u32
  || canonicalKey
)
```

`keyHash` 是 metadata/collision guard，不作为 sparse tree route。

### 8.5 Domain value/leaf

```text
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
  || path32
  || valueHash
)
```

Delete/tombstone/root-normalized-empty 不生成 leaf，使用 `empty[256]`。

### 8.6 Global path/leaf

```text
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

Global tree 只包含 `RootPolicy.IN_GLOBAL_ROOT` domain。`DOMAIN_ROOT_ONLY` domain 仍写 `ROOT_DOMAIN`，不进 global root。

### 8.7 Empty hash chain

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

规则：

- `depth` 范围 `0..256`。
- empty hash chain 可在 `CommitmentHash` 构造时预计算。
- `empty[0]` 是空 domain/global tree root。
- 改任何 prefix 都必须分配新 `algorithmId`。

### 8.8 Branch hash

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

测试：

```text
CommitmentHashTest
```

覆盖：

- deterministic vectors。
- `domainPath != keyHash`。
- 同 key 不同 domain 得到不同 path/value/leaf。
- 同 domain 不同 algorithmId 得到不同 path。
- left/right 交换得到不同 branch hash。
- `empty[0]` 稳定，且不等于 `Hash.EMPTY_TRIE_HASH`。
- invalid path/hash length 抛异常。

## 9. Patch 4：RootValueNormalizer

S10 的 tree core 不解析 protobuf 语义。`RootValueNormalizer` 在 `CommitmentUpdate` 之前做 domain-specific 规范化：

```java
public interface RootValueNormalizer {
  NormalizedRootValue normalize(ArchiveDomainDescriptor descriptor, byte[] canonicalKey,
      Optional<byte[]> afterValue) throws CommitmentException;
}
```

`NormalizedRootValue`：

```java
public final class NormalizedRootValue {
  private final boolean present;
  private final byte[] canonicalValue;

  public static NormalizedRootValue delete();
  public static NormalizedRootValue present(byte[] canonicalValue);
}
```

P0 规则：

| Domain | Normalizer 规则 |
| --- | --- |
| `ACCOUNT` | tombstone 删除；present 使用 `AccountCapsule.getData()` 或 raw `Account` protobuf bytes，保持 S4/S8 的 account value codec |
| `CONTRACT` | tombstone 删除；present 使用 cleaned `SmartContract` bytes |
| `CODE` | tombstone 或 empty bytecode 删除；present 使用 bytecode raw bytes |
| `CONTRACT_STORAGE` | tombstone 或 32-byte zero 删除；present 必须规范成 32-byte slot value |
| `DYNAMIC_PROPERTIES` | 未 allowlist 的 key 忽略；allowlist key 按 raw bytes |

注意：

- storage zero 从 root 删除，但 temporal store 仍可按 S6/S7 的语义记录 history/change。
- normalizer 不读 latest Store。
- normalizer 不构造 `Storage` 或访问 `StorageRowStore`。
- dynamic properties 不允许 wholesale 进入 global root。

测试：

```text
RootValueNormalizerTest
```

覆盖：

- account tombstone delete。
- code empty delete。
- storage zero delete。
- malformed storage length 拒绝。
- excluded/allowlist dynamic property。
- present value defensive copy。

## 10. Patch 5：SparseMerkleTree core

### 10.1 Public API

```java
public interface CommitmentTree {
  byte[] rootHash();

  UpdateResult update(byte[] path32, Optional<byte[]> leafHash, ArchiveBatch batch)
      throws CommitmentException;
}
```

`UpdateResult`：

```java
public final class UpdateResult {
  private final byte[] oldRoot;
  private final byte[] newRoot;
  private final boolean changed;
  private final int nodesWritten;
}
```

### 10.2 Constructor

```java
public final class SparseMerkleTree implements CommitmentTree {
  public SparseMerkleTree(
      RootAlgorithm algorithm,
      TreeKind treeKind,
      short domainId,
      byte[] currentRoot,
      CommitmentNodeReader nodeReader,
      CommitmentNodeWriter nodeWriter);
}
```

Validation：

- `currentRoot` 必须 32 bytes。
- empty tree root 必须等于 `CommitmentHash.emptyHash(algorithmId, 0)`。
- global tree 的 `domainId` 必须为 `0`。
- domain tree 的 `domainId` 必须非 0。

### 10.3 Node reader/writer

```java
public interface CommitmentNodeReader {
  Optional<NodeRecord> getNode(TreeKind treeKind, short domainId, byte[] nodeHash, ArchiveBatch batch)
      throws CommitmentException;
}

public interface CommitmentNodeWriter {
  void putNode(NodeRecord node, ArchiveBatch batch) throws CommitmentException;
}
```

Reader 顺序：

```text
key = RootKeyCodec.rootNode(algorithmId, treeKind, domainId, nodeHash)

if batch.containsKey(key):
  staged = batch.get(key)
  if staged.empty: corruption, ROOT_NODE cannot be staged delete during update
  return decode(staged)

raw = rawStore.get(key)
return raw.map(codec::decode)
```

Writer 规则：

- `nodeHash == empty[depth]` 时不写。
- decode/encode 后必须能自校验 `nodeHash`。
- `putNode` 不更新 `ROOT_CURRENT`，由 S11 做。

### 10.4 Update algorithm

```text
require path32.length == 32
targetLeaf = leafHash.orElse(empty[256])

oldRoot = currentRoot
nodeHash = oldRoot
stack = []

for depth in 0..255:
  if nodeHash == empty[depth]:
    bit = bit(path32, depth)
    sibling = empty[depth + 1]
    stack.push(depth, bit, sibling)
    nodeHash = empty[depth + 1]
    continue

  node = reader.getNode(nodeHash, batch)
  if node missing:
    throw CORRUPT_RECORD
  validate node.depth == depth
  bit = bit(path32, depth)
  if bit == 0:
    stack.push(depth, 0, node.rightHash)
    nodeHash = node.leftHash
  else:
    stack.push(depth, 1, node.leftHash)
    nodeHash = node.rightHash

child = targetLeaf

for frame in stack reverse:
  if frame.bit == 0:
    left = child
    right = frame.sibling
  else:
    left = frame.sibling
    right = child
  parent = branchHash(depth, left, right)
  if parent != empty[depth]:
    writer.putNode(NodeRecord(depth, left, right, parent), batch)
  child = parent

currentRoot = child
return UpdateResult(oldRoot, currentRoot, changed = oldRoot != currentRoot)
```

Bit order：

```text
depth 0 = most significant bit of path32[0]
depth 7 = least significant bit of path32[0]
depth 8 = most significant bit of path32[1]
...
depth 255 = least significant bit of path32[31]
```

### 10.5 Delete

Delete 调用：

```java
tree.update(path32, Optional.empty(), batch)
```

语义：

- leaf becomes `empty[256]`。
- rebuild parents。
- 如果 parent hash 等于 `empty[depth]`，不用写 `ROOT_NODE`。
- S11 同时删除 `ROOT_LEAF(path32)`。
- leafCount 在 S11 根据 old/new `ROOT_LEAF` 调整。

### 10.6 Same-block overlay 必测

测试场景：

```text
root = empty
batch = new ArchiveBatch()
tree.update(pathA, leafA, batch)
tree.update(pathB, leafB, batch)
flush batch

reopen tree from current root
rootAB == rebuild([A, B])
```

如果 reader 不查 batch，第二次 update 会读不到第一次写入的 branch，测试必须失败。

测试：

```text
SparseMerkleTreeTest
```

覆盖：

- empty root vector。
- put single leaf。
- put A then B equals put B then A after sorted batch application。
- update same leaf value changes root。
- delete leaf returns empty root。
- two leaves sharing long prefix。
- same-block staged overlay。
- missing non-empty node -> corruption。
- malformed node with wrong `nodeHash` -> corruption。
- global tree rejects non-zero domainId。
- domain tree rejects zero domainId。

## 11. Patch 6：Leaf metadata guard

S10 提供 guard 方法，S11 在 `CommitmentUpdate` 应用时调用：

```java
public final class LeafMetadataGuard {
  public LeafAction stageLeaf(
      short algorithmId,
      short domainId,
      byte[] path32,
      byte[] canonicalKey,
      byte[] keyHash,
      Optional<byte[]> valueHash,
      Optional<byte[]> leafHash,
      ArchiveBatch batch) throws CommitmentException;
}
```

读取顺序：

```text
leafKey = ROOT_LEAF(algorithmId, domainId, path32)

if batch.containsKey(leafKey):
  existing = batch.get(leafKey)
else:
  existing = rawStore.get(leafKey)
```

Present update：

- existing missing：put new `LeafRecord`，leafCount delta `+1`。
- existing same `keyHash` and same `canonicalKey`：replace valueHash/leafHash，leafCount delta `0`。
- existing different `keyHash` or different `canonicalKey`：throw `PATH_COLLISION`。

Delete update：

- existing missing：no-op metadata，leafCount delta `0`。
- existing same key：delete `ROOT_LEAF(path32)`，leafCount delta `-1`。
- existing different key：throw `PATH_COLLISION`。

`LeafAction`：

```java
public final class LeafAction {
  private final Optional<byte[]> leafHashForTree;
  private final long leafCountDelta;
  private final boolean changed;
}
```

S10 不要求实现公开 collision recovery。256-bit collision 是 corruption/security event，应该 fail closed。

测试：

```text
LeafMetadataGuardTest
```

覆盖：

- insert leaf。
- update same canonical key。
- delete existing leaf。
- delete missing leaf。
- path collision fixture。
- staged put 后再 update 同 path。
- staged delete 后再 put 同 path。

## 12. S10 与 S11 的交接

S10 完成后，S11 可以按以下流程接 `DefaultCommitmentBuilder.stageBlockEnd`：

```text
blockWriteSet
  -> collapse same block same key to final afterValue
  -> normalize by RootValueNormalizer
  -> compute path32/keyHash/valueHash/leafHash
  -> LeafMetadataGuard updates ROOT_LEAF and leafCount delta
  -> SparseMerkleTree.update(path32, leafHash/delete)
  -> write ROOT_DOMAIN / ROOT_BLOCK / ROOT_CURRENT / rootProgress
```

S10 明确不做：

- 从 `BlockWriteSet` collapse writes。
- 写 `ROOT_DOMAIN`。
- 写 `ROOT_BLOCK`。
- 写 `ROOT_CURRENT`。
- 写 `COMMITMENT_META(rootProgress)`。
- scan latest state rebuild。
- root startup verifier。

这些都进入 S11。

## 13. 错误模型

`CommitmentExceptionReason`：

```java
INVALID_ARGUMENT
UNSUPPORTED_ALGORITHM
UNKNOWN_TREE_KIND
UNKNOWN_ROOT_POLICY
CODEC_ERROR
CORRUPT_RECORD
MISSING_NODE
PATH_COLLISION
DOMAIN_NOT_ROOTED
STAGED_DELETE_FOR_NODE
```

分类：

| Reason | 含义 | 处理 |
| --- | --- | --- |
| `INVALID_ARGUMENT` | 调用方传入长度/枚举非法 | 单测直接 assert |
| `CODEC_ERROR` | bytes 无法 decode | integrity/startup 报 corrupt |
| `CORRUPT_RECORD` | decode 后自校验失败 | fail closed |
| `MISSING_NODE` | root 指向的 non-empty node 不存在 | fail closed，需要 repair/rebuild |
| `PATH_COLLISION` | 同 path32 不同 canonical key | fail closed |
| `STAGED_DELETE_FOR_NODE` | batch 中出现 ROOT_NODE delete | fail closed |

不要把这些错误吞掉后返回 empty root。

## 14. 单测矩阵

| Test class | 覆盖 |
| --- | --- |
| `RootKeyCodecTest` | 0x30+ key schema、u16/u64 big-endian、prefix scan、malformed key |
| `RootRecordCodecTest` | root/domain/current record binary roundtrip 与 invalid bytes |
| `CommitmentNodeCodecTest` | node/leaf record codec、自校验、path/canonical key |
| `CommitmentHashTest` | deterministic vectors、domain separation、empty chain、branch hash |
| `RootValueNormalizerTest` | tombstone/zero/empty/allowlist |
| `SparseMerkleTreeTest` | put/update/delete、overlay、missing node corruption |
| `LeafMetadataGuardTest` | ROOT_LEAF by path、leafCount delta、path collision |

建议命令：

```bash
./gradlew :chainbase:test --tests '*RootKeyCodecTest'
./gradlew :chainbase:test --tests '*RootRecordCodecTest'
./gradlew :chainbase:test --tests '*CommitmentNodeCodecTest'
./gradlew :chainbase:test --tests '*CommitmentHashTest'
./gradlew :chainbase:test --tests '*RootValueNormalizerTest'
./gradlew :chainbase:test --tests '*SparseMerkleTreeTest'
./gradlew :chainbase:test --tests '*LeafMetadataGuardTest'
./gradlew checkstyleMain checkstyleTest -x generateGitProperties
```

不要添加 `@Ignore`、条件跳过或 test suite 排除。

## 15. Review checklist

- [ ] `chainbase` 没有 import `org.tron.core.trie.TrieImpl`。
- [ ] `chainbase` 没有调用 `BlockCapsule.setAccountStateRoot`。
- [ ] `Hash.EMPTY_TRIE_HASH` 未用于 archive SMT empty root。
- [ ] `ROOT_LEAF` key 使用 `path32`，不是 `canonicalKey`。
- [ ] `LeafRecord` 保存 `canonicalKey`、`keyHash`、`valueHash`、`leafHash`。
- [ ] `path32` 与 `keyHash` 使用不同 prefix，并有测试证明不同。
- [ ] 所有 commitment numeric field 使用 big-endian。
- [ ] 所有 hash 长度固定 32 bytes。
- [ ] decode 检查 trailing bytes。
- [ ] `ArchiveBatch` 支持 staged value lookup。
- [ ] `CommitmentNodeReader` 先读 batch overlay，再读 raw store。
- [ ] sparse tree delete 可折叠回 empty root。
- [ ] `ROOT_NODE` 不删除，不 GC。
- [ ] storage 32-byte zero 不生成 root leaf。
- [ ] dynamic properties 不 wholesale 进入 global root。

## 16. S10 完成定义

S10 完成后，应能在不启动 `Manager`、不执行 block、不开 JSON-RPC 的情况下完成：

```text
root key encode/decode
root/node/leaf value encode/decode
hash vector
empty SMT root
put/update/delete leaves
same-batch overlay update
leaf metadata collision guard
```

验收标准：

1. 所有 S10 unit tests 通过。
2. `./gradlew checkstyleMain checkstyleTest -x generateGitProperties` 通过。
3. 文档和实现都明确 archive root 是 `ARCHIVE_SIDECAR`。
4. 没有引入 `TrieImpl` 到 `chainbase`。
5. 没有写区块头 root。

完成 S10 后再进入 S11：`DefaultCommitmentBuilder` block-end integration、`ROOT_BLOCK/ROOT_DOMAIN/ROOT_CURRENT/rootProgress` 写入、hot unwind 和 latest rebuild verifier。
