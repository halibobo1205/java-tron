# java-tron Archive L7：CommitmentBuilder 代码级执行包

日期：2026-06-05

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`

Erigon 源码路径：`/Users/boson/GolandProjects/erigon`

上游总路线：[java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)

上游源码细化：[模块 06 CommitmentBuilder：4e80 java-tron 源码对照细化](./20260603-java-tron-module-06-commitment-builder-4e80-source-deep-dive.md)

来源大包：[java-tron Archive S10/S11：CommitmentBuilder 4e80 编码执行包](./20260603-java-tron-archive-s10-s11-commitment-builder-4e80-coding-packet.md)

state-root 分支参考：[java-tron state-root 分支借鉴分析](./20260605-java-tron-state-root-branches-reference-analysis.md)

前置执行包：

- [L1 config/no-op/dbName 代码级执行包](./20260604-java-tron-archive-l1-config-noop-dbname-4e80-code-plan.md)：提供 `storage.archive.commitment.*` 默认关闭配置和 no-op service 口径。
- [L2 Manager lifecycle + txNum 代码级执行包](./20260605-java-tron-archive-l2-manager-txnum-4e80-code-plan.md)：提供 block lifecycle、logical txNum、block txNum range。
- [L3 ArchiveDomainRegistry 代码级执行包](./20260605-java-tron-archive-l3-domain-registry-4e80-code-plan.md)：提供 domain id、root policy、canonical key/value codec、registry checksum。
- [L4 WriteCollector + Storage Semantic Hook 代码级执行包](./20260605-java-tron-archive-l4-write-collector-4e80-code-plan.md)：提供 `BlockWriteSet`、per logical tx changes、semantic storage key。
- [L5 ArchiveTemporalStore 代码级执行包](./20260605-java-tron-archive-l5-temporal-store-4e80-code-plan.md)：提供 single archive DB、`ArchiveBatch`、`LATEST/HISTORY/CHANGESET`、root table prefix 和 startup progress。
- [L6 ArchiveStateReader + historical getters 代码级执行包](./20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md)：提供 historical reader，可作为 rebuild/root query 的读取边界，但 L7 hot path 不依赖 JSON-RPC adapter。

本文只做 L7 规划，不修改 java-tron 源码。目标是把 `CommitmentBuilder` 细化到类、key/value codec、hash 规范、block root、transaction-level root、unwind、rebuild verifier、测试 gate 和 review checklist。

## 1. L7 定位

L7 给 archive sidecar 增加可验证的状态承诺：

```text
domainRoot(domain, asOfTxNum)
globalRoot(asOfTxNum)
ArchiveRootRecord(blockNum, blockHash, finalizeTxNum, globalRoot, domainRoots, coverage)
COMMITMENT_BRANCH node records
COMMITMENT_META progress/current state
```

它不是 java-tron 共识 root：

```text
L7 root scope = ARCHIVE_SIDECAR
consensusParticipation = NONE
coverage = TVM_STATE_ONLY / ARCHIVE_DOMAIN_SET_V1
```

L7 交付：

```text
ArchiveCommitmentAlgorithm
CommitmentHash
ArchiveRootRecord / DomainRootRecord / CurrentRootRecord / CommitmentProgressRecord
RootKeyCodec
CommitmentNodeRecord / node codecs
SparseMerkleArchiveCommitmentTree
RootValueNormalizer
DefaultArchiveCommitmentBuilder
ArchiveCommitmentRebuildVerifier
ArchiveTxRootComputer / rootAtTxNum on-demand design
BlockResult header-root regression tests
```

L7 不交付：

```text
BlockHeader.raw.txTrieRoot 写入
BlockHeader.raw.accountStateRoot 写入
BlockCapsule.validateMerkleRoot 修改
JSON-RPC BlockResult.stateRoot 替换
Ethereum eth_getProof
debug_getArchiveProof / debug_verifyArchiveProof public API
historical eth_call
concurrent commitment calculation
COMMITMENT_NODE GC / compaction
```

L7 的核心约束：

```text
1. root 输入只来自 archive write set / archive LATEST / archive CHANGESET。
2. root 写入和 temporal 写入使用同一个 ArchiveBatch。
3. root rows、current rows、branch rows、progress rows 同步推进。
4. unwind 同批回退 temporal progress 和 commitment progress。
5. rebuild verifier 扫 archive LATEST，不扫 java-tron latest Store。
6. transaction-level root 不要求默认持久化每个 tx root，但必须有 rootAtTxNum on-demand 设计。
```

补充参考口径：`feat/state-trie-4.8.1` 是区块级 MPT 实现，只参考 parent-root incremental trie、domain-aware cache、`WorldStateGenesis` baseline 和 read-only query instance；不能把它当成交易级 archive sidecar 方案。`feat/481_state_root` 的 `StateRootStore` 与 corrupted checkpoint evidence 可作为 root record/evidence store 参考。二者的 header `state_root/archive_root`、flat MerkleRoot fingerprint、block-level only root 都不能进入 P0。

## 2. Erigon 源码依据

### 2.1 Domain write 到 commitment touch

| Erigon 源码 | 当前事实 | java-tron L7 映射 |
| --- | --- | --- |
| `db/state/execctx/domain_shared.go:817-831` | `DomainPut` 在读取 prev/no-op 判断前，如果未禁用 inline touch，会调用 `sd.sdCtx.TouchKey(domain, key, value)` | L7 从 L4 `DomainWrite` 转 `ArchiveCommitmentUpdate`，不能等到 latest Store 扫描 |
| `domain_shared.go:833-850` | 读 prev 后，`prev == v` 的 domain write 会 no-op | java-tron block-end root 可压缩为 firstBefore/finalAfter；no-op 不进入 block-end root update |
| `domain_shared.go:858-870` | domain writes 与 commitment calculator 的 accumulator swap 有锁语义，最终写入 temporal mem domain | java-tron L7 不实现并发 calculator，但必须保证 write set -> temporal/root 同批原子 |
| `execution/state/rw_v3.go:161-163` | delete account 时 touch `AccountsDomain` with nil | tombstone/delete 必须进入 commitment update |
| `rw_v3.go:238-242` | account put touch 后再 `DomainPut(AccountsDomain, ...)` | ACCOUNT root value 来自 canonical account bytes |
| `rw_v3.go:258-263` | code put touch 后写 `CodeDomain` | CODE root value 来自 runtime code bytes |
| `rw_v3.go:278-296` | storage delete/put touch `StorageDomain`，key 为 `address || slot` composite | TRON CONTRACT_STORAGE 必须用 L4 semantic `address21 || slot32 || version` |

Erigon 的重要启发不是“把 Erigon trie 直接搬到 java-tron”，而是闭环：

```text
domain write
  -> touch/update accumulator
  -> compute commitment
  -> branch state persisted
  -> root/progress advanced
```

java-tron 的闭环应该是：

```text
ArchiveWriteCollector
  -> BlockWriteSet / DomainWrite
  -> RootValueNormalizer
  -> ArchiveCommitmentUpdate
  -> ArchiveCommitmentTree.apply
  -> ROOT_RECORD / ROOT_CURRENT / COMMITMENT_BRANCH / COMMITMENT_META
```

### 2.2 ComputeCommitment 的分层

| Erigon 源码 | 当前事实 | java-tron L7 映射 |
| --- | --- | --- |
| `db/state/execctx/domain_shared.go:995-1028` | public `ComputeCommitment` 先 flush pending deferred branch updates，再调用 `sdCtx.ComputeCommitment` | `DefaultArchiveService` 先 stage temporal rows，再 stage commitment rows，最后一次 `ArchiveBatch.commit()` |
| `execution/commitment/commitment.go:91-118` | `Trie` 接口包含 `RootHash`、`ResetContext`、`Process` | 拆成 `ArchiveCommitmentTree` + `ArchiveCommitmentContext` |
| `commitment.go:130-140` | `PatriciaContext` 提供 `Branch`、`PutBranch`、`Account`、`Storage` | java-tron context 提供 `getNode/putNode/getCurrent/putCurrent`；不读 Manager/Wallet |
| `commitmentdb/commitment_context.go:436-458` | `Process` 完成后通过 `PutBranch` 合并 branch writes | L7 tree apply 必须输出 node rows，写入 `COMMITMENT_BRANCH` |
| `commitmentdb/commitment_context.go:479-485` | `saveState` 时编码并保存 commitment state | L7 写 `COMMITMENT_META(rootProgress)` |
| `commitmentdb/commitment_context.go:682-705` | commitment state 读取 prev，再 `PutBranch(KeyCommitmentState, encodedState, prevState)` | L7 progress row 与 root/current row 一起进入 batch |
| `commitmentdb/commitment_context.go:799-823` | `Branch` 返回 copy，`PutBranch` 写 `CommitmentDomain` | L7 node/context 所有 key/value defensive copy |

L7 必须保存“继续增量构建所需的 state”，不能只保存一个 root hash。只保存 root hash 会导致：

- 下一个 block 无法从 parent branch state 增量更新。
- unwind 后无法恢复 current/root/progress 闭环。
- proof/debug 后续无法从 sidecar node records 生成证明。
- rootAtTxNum on-demand 无法从 checkpoint root 回放 changeset。

### 2.3 update 排序

| Erigon 源码 | 当前事实 | java-tron L7 映射 |
| --- | --- | --- |
| `execution/commitment/commitment.go:1429-1440` | `Updates` 注释要求 trie traversal 必须按 `hashedKey` 排序；plain key 顺序会产生 divergent root | L7 必须显式按 `path32` 排序，不依赖 Java `Map` 或 Store 写入顺序 |
| `commitment.go:1972-1985` | `keyUpdateLessFn` 先比 `hashedKey`，再用 `plainKey` tie-break | L7 tie-break 用 `domainId || canonicalKey` |
| `execution/commitment/hex_patricia_hashed.go:2799-2861` | `Process` 调 `updates.HashSort` 后逐个 `followAndUpdate` | `SparseMerkleArchiveCommitmentTree.apply` 的唯一输入顺序是 sorted updates |
| `hex_patricia_hashed.go:2863-2880` | 所有 active rows fold 到 root 后再 `RootHash()` | L7 apply 必须输出稳定 root，不能把未 fold 的 partial state 当 root |
| `hex_patricia_hashed.go:2888-2893` | branch encoder deferred updates 最终 `ApplyDeferredUpdates(..., PutBranch)` | L7 不实现 deferred/concurrent，但要有同等的 node record 写出阶段 |

java-tron L7 排序规则：

```text
sort by path32 unsigned lexicographic ASC
then domainId_u16 ASC
then canonicalKey unsigned lexicographic ASC
```

`path32`：

```text
H("tron.archive.domain.path.v1" || algorithmId_u16 || domainId_u16 || canonicalKey)
```

同 path collision guard：

```text
if path32 equal and (domainId || canonicalKey) differs:
  throw ArchiveCommitmentException(COLLISION)
```

## 3. java-tron 4e80 源码事实

### 3.1 header root 不是 archive root

| java-tron 源码 | 当前事实 | L7 约束 |
| --- | --- | --- |
| `protocol/src/main/protos/core/Tron.proto:502-513` | `BlockHeader.raw` 有 `txTrieRoot = 2`、`accountStateRoot = 11` | archive root 不写这两个字段 |
| `BlockCapsule.java:218-230` | `calcMerkleRoot()` 用交易 `getMerkleHash()` 计算交易 Merkle root；空交易返回 `Sha256Hash.ZERO_HASH` | 这是 transaction root，不是 archive state root |
| `BlockCapsule.java:233-244` | `validateMerkleRoot()` 校验 `txTrieRoot` | archive root 不接入该校验 |
| `BlockCapsule.java:246-253` | `setMerkleRoot()` 写 header `txTrieRoot` | L7 不调用 |
| `BlockCapsule.java:255-262` | `setAccountStateRoot(byte[])` 写 header `accountStateRoot` | L7 不调用 |
| `BlockCapsule.java:278-284` | `getAccountRoot()` 读取 header `accountStateRoot`，空时返回 zero hash | 与 archive root 独立 |
| `BlockResult.java:91-104` | JSON-RPC `transactionsRoot` 来自 `txTrieRoot`，`stateRoot` 来自 `accountStateRoot` | L7 不改变 `BlockResult.stateRoot` |

L7 必须新增 framework regression：

```text
Archive commitment enabled
  -> commit/archive root generated
  -> BlockResult.stateRoot still equals block header accountStateRoot
  -> archive root not visible through eth_getBlockByNumber result
```

### 3.2 现有 accountStateRoot 管线只能参考 lifecycle

| java-tron 源码 | 当前事实 | L7 结论 |
| --- | --- | --- |
| `reference.conf:812` | `allowAccountStateRoot = 0` 默认关闭 | archive commitment 使用 `storage.archive.commitment.enable`，不复用现有 governance/config |
| `CommonParameter.java:374-379` | `allowAccountStateRoot` runtime 字段 | 不作为 archive commitment 开关 |
| `Args.java:456-462` | 从 committee config 读取 `allowAccountStateRoot` | archive config 已在 L1 `storage.archive.*` |
| `DynamicPropertiesStore.java:152-153` | `ALLOW_ACCOUNT_STATE_ROOT` 注释为 account state root 专用 | archive commitment 不受此动态属性控制 |
| `DynamicPropertiesStore.java:2375-2389` | `allowAccountStateRoot()` 读取 governance value | L7 不读它 |
| `Manager.java:1636` | 本地产块前 `accountStateCallBack.preExecute(blockCapsule)` | lifecycle 可参考：block begin 加载 parent state |
| `Manager.java:1729-1733` | 每笔交易 `preExeTrans/processTransaction/exeTransFinish/tmpSession.merge` | tx-level root 需要同样的 logical txNum 边界，但来自 L2/L4 |
| `Manager.java:1747` | 本地产块末尾 `executeGenerateFinish()` | 现有 account root 会生成 header root；L7 禁止 |
| `Manager.java:1751` | `blockCapsule.setMerkleRoot()` 写 txTrieRoot | L7 禁止混入 |
| `Manager.java:1870-1893` | push block 执行前后也走 accountState callback，push finish 校验 header root | archive verifier 不拒绝共识块，除非显式 strict archive mode |
| `AccountStateCallBack.java:34-42` | per tx 把 `trieEntryList` 写入 `TrieImpl` | 只参考“per tx flush”概念 |
| `AccountStateCallBack.java:52-71` | 从 parent block header `accountStateRoot` 初始化 `TrieImpl` | archive parent root 来自 `ROOT_CURRENT` / parent `ArchiveRootRecord` |
| `AccountStateCallBack.java:74-92` | push block 时 root 不匹配抛 `BadBlockException` | archive root mismatch 写 `REPAIR_REQUIRED`，不伪装共识校验 |
| `AccountStateCallBack.java:94-105` | generate block 时 `blockCapsule.setAccountStateRoot(newRoot)` | L7 不调用 |

### 3.3 accountStateRoot 数据覆盖不足

| java-tron 源码 | 当前事实 | L7 结论 |
| --- | --- | --- |
| `AccountStateEntity.java:16-22` | 只保留 `address`、`balance`、`allowance`，assetV2 注释掉 | 不是完整 account state |
| `AccountStateCallBackUtils.java:13-22` | 只接收 `AccountCapsule` 并转 `AccountStateEntity` | 不覆盖 contract/code/storage |
| `AccountStore.java:68-89` | `put` 后调用 `accountStateCallBackUtils.accountCallBack` | 只有 account put |
| `AccountStore.java:91-105` | `delete` 只做 balance trace 和 `super.delete`，没有 account trie delete callback | delete 语义不完整 |
| `TrieImpl.java:144-154` | empty/null value 转 delete | 可参考 tombstone，但不能直接复用 |
| `TrieImpl.java:289-292` | empty root 使用 `Hash.EMPTY_TRIE_HASH` | archive SMT 定义自己的 empty hash chain |
| `TrieImpl.java:301-306` | dirty nodes flush 后 root 变 hash node | archive tree 需要 branch/node records |
| `TrieImpl.java:563-568` | `setRoot` 把 `EMPTY_TRIE_HASH` 当空树 | archive root current 不使用此常量 |
| `AccountStateStoreTrie.java:35-42` | 用 `TrieImpl` 和 account trie DB 读取 account state | 这是 accountStateRoot 专用 Store |

因此 L7 不使用：

```text
AccountStateCallBack
AccountStateCallBackUtils
AccountStateEntity
AccountStateStoreTrie
TrieImpl
Hash.EMPTY_TRIE_HASH
allowAccountStateRoot
BlockCapsule.setAccountStateRoot
```

可复用：

```text
org.tron.common.crypto.Hash.sha3
ByteArray / ByteUtil 编码工具
BlockCapsule blockNum/blockHash accessors
```

## 4. L7 文件级落点

### 4.1 修改文件

```text
common/src/main/resources/reference.conf
common/src/main/java/org/tron/core/config/args/Storage.java
common/src/test/java/org/tron/core/config/args/StorageConfigArchiveTest.java

chainbase/src/main/java/org/tron/core/archive/store/ArchiveTable.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveStoreKeyCodec.java
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
chainbase/src/main/java/org/tron/core/archive/ArchiveService.java

framework/src/test/java/org/tron/core/services/jsonrpc/BlockResultArchiveRootRegressionTest.java
```

说明：

- L1 已规划 commitment config 默认值；如果 L1 已实现这些字段，L7 只扩展校验和测试。
- L5 已预留 `ROOT_RECORD(0x30)`、`COMMITMENT_BRANCH(0x31)`、`COMMITMENT_META(0x32)`；L7 定义这些 prefix 下的 sub-key layout。
- `BlockCapsule.java` 和 `BlockResult.java` 只做 test inspect，不应改生产代码。

### 4.2 新增 package

```text
chainbase/src/main/java/org/tron/core/archive/commitment/
```

新增文件：

```text
ArchiveCommitmentAlgorithm.java
ArchiveRootCoverage.java
ArchiveTreeKind.java
ArchiveTreeId.java

CommitmentHash.java
ArchiveCommitmentException.java
ArchiveCommitmentUpdate.java
ArchiveCommitmentResult.java

CommitmentNodeRecord.java
CommitmentNodeCodec.java
CommitmentNodeStore.java
ArchiveCommitmentContext.java
DefaultArchiveCommitmentContext.java

RootKeyCodec.java
ArchiveRootRecord.java
DomainRootRecord.java
CurrentRootRecord.java
CommitmentProgressRecord.java
ArchiveRootRecordCodec.java
CommitmentProgressCodec.java

RootValueNormalizer.java
ArchiveCommitmentTree.java
SparseMerkleArchiveCommitmentTree.java

ArchiveCommitmentBuilder.java
NoopArchiveCommitmentBuilder.java
DefaultArchiveCommitmentBuilder.java
ArchiveCommitmentRebuildVerifier.java
ArchiveTxRootComputer.java
ArchiveRootReader.java
```

依赖方向：

```text
chainbase/archive/commitment
  -> chainbase/archive/domain
  -> chainbase/archive/store
  -> chainbase/archive/temporal
  -> chainbase/archive/txnum
  -> common hash/bytes helpers

chainbase/archive/commitment
  -X-> framework
  -X-> Wallet
  -X-> Manager
  -X-> BlockResult
  -X-> TrieImpl
```

## 5. 配置

L1 规划配置形状：

```hocon
storage {
  archive {
    enable = false
    commitment {
      enable = false
      algorithm = "tron-archive-smt-keccak-v1"
      coverage = "TVM_STATE_ONLY"
      persistTxRoots = false
      verifyOnStartup = true
      rebuildOnStartup = false
      checkpointInterval = 0
    }
  }
}
```

L7 校验规则：

| 配置 | 行为 |
| --- | --- |
| `archive.enable=false` | commitment no-op |
| `archive.enable=true, commitment.enable=false` | temporal 可用，root rows 不写 |
| `commitment.enable=true` 且 temporal disabled | startup fail fast |
| unknown algorithm | startup fail fast |
| unknown coverage | startup fail fast |
| `persistTxRoots=true` 但 every-tx persistence 未实现 | startup fail fast |
| `verifyOnStartup=true` | 校验 temporal/root progress 基础一致性 |
| `rebuildOnStartup=true` | 扫 archive `LATEST` 重建 latest root，成本高，默认 false |
| `checkpointInterval > 0` | 预留 tx root checkpoint；未实现时 fail fast |

不要复用：

```text
committee.allowAccountStateRoot
DynamicPropertiesStore.ALLOW_ACCOUNT_STATE_ROOT
CommonParameter.allowAccountStateRoot
```

## 6. Archive DB key space

L5 已固定 table prefix：

```text
ROOT_RECORD        0x30
COMMITMENT_BRANCH  0x31
COMMITMENT_META    0x32
```

L7 在这些 prefix 下定义 sub-key。L5 旧的 `ROOT_RECORD: table | blockNum` 只是 placeholder；L7 需要改 `ArchiveStoreKeyCodec`，否则无法同时表达 block root、tx root、current root。

### 6.1 RootKeyCodec

文件：

```text
chainbase/src/main/java/org/tron/core/archive/commitment/RootKeyCodec.java
```

key layout：

```text
ROOT_BY_BLOCK:
  table_u8(0x30) | subType_u8(0x01) | algorithmId_u16 | blockNum_u64

ROOT_BY_TX:
  table_u8(0x30) | subType_u8(0x02) | algorithmId_u16 | txNum_u64

ROOT_CURRENT:
  table_u8(0x30) | subType_u8(0x03) | algorithmId_u16 |
  treeKind_u8 | domainId_u16

ROOT_CHECKPOINT:
  table_u8(0x30) | subType_u8(0x04) | algorithmId_u16 | txNum_u64
  // P1 unless checkpointInterval implemented

COMMITMENT_NODE:
  table_u8(0x31) | algorithmId_u16 | treeKind_u8 | domainId_u16 | nodeHash32

COMMITMENT_META:
  table_u8(0x32) | asciiName
```

`treeKind`：

```text
0x01 = DOMAIN_TREE
0x02 = GLOBAL_TREE
```

`domainId`：

```text
domain tree  -> L3 stable domain id
global tree  -> 0 reserved
```

Ordering tests：

```text
ROOT_BY_BLOCK(1) < ROOT_BY_BLOCK(2)
ROOT_BY_TX(10) < ROOT_BY_TX(11)
ROOT_CURRENT domain roots grouped by algorithm/tree/domain
COMMITMENT_NODE key validates nodeHash length exactly 32
```

### 6.2 Meta keys

```text
COMMITMENT_META("rootProgress")
COMMITMENT_META("algorithm")
COMMITMENT_META("coverage")
COMMITMENT_META("schemaVersion")
```

P0 必需：

```text
rootProgress
```

其他 meta 可在 `CommitmentProgressRecord` 中携带，单独 meta rows 只在实现需要时增加。

## 7. Algorithm and hash spec

### 7.1 ArchiveCommitmentAlgorithm

文件：

```text
ArchiveCommitmentAlgorithm.java
```

建议字段：

```java
public final class ArchiveCommitmentAlgorithm {
  public static final int TRON_ARCHIVE_SMT_KECCAK_V1 = 1;

  private final int id;
  private final String name;
  private final int pathBits;
  private final String hashName;
}
```

P0：

```text
id = 0x0001
name = tron-archive-smt-keccak-v1
hash = org.tron.common.crypto.Hash.sha3
pathBits = 256
```

### 7.2 CommitmentHash

文件：

```text
CommitmentHash.java
```

规则：

```text
所有 hash 使用 domain-separated preimage。
所有多字节整数 unsigned big-endian。
所有 byte[] 长度参与 hash。
不要使用 Hash.EMPTY_TRIE_HASH。
不要使用 BlockCapsule tx merkle root。
不要使用 Ethereum/Patricia empty trie hash 常量。
```

hash definitions：

```text
emptyLeaf:
  H("tron.archive.smt.empty.leaf.v1" || algorithmId_u16)

emptyBranch(depth):
  H("tron.archive.smt.empty.branch.v1" || algorithmId_u16 ||
    depth_u16 || empty[depth+1] || empty[depth+1])

domainPath:
  H("tron.archive.domain.path.v1" || algorithmId_u16 ||
    domainId_u16 || keyCodecId_u16 || keyLen_u32 || canonicalKey)

valueHash present:
  H("tron.archive.value.present.v1" || algorithmId_u16 ||
    domainId_u16 || valueCodecId_u16 || valueLen_u32 || canonicalValue)

valueHash tombstone:
  H("tron.archive.value.tombstone.v1" || algorithmId_u16 ||
    domainId_u16 || valueCodecId_u16)

domainLeaf:
  H("tron.archive.domain.leaf.v1" || algorithmId_u16 ||
    domainId_u16 || keyCodecId_u16 || valueCodecId_u16 ||
    path32 || keyLen_u32 || canonicalKey || valueHash32)

domainBranch:
  H("tron.archive.domain.branch.v1" || algorithmId_u16 ||
    depth_u16 || left32 || right32)

globalPath:
  H("tron.archive.global.path.v1" || algorithmId_u16 || domainId_u16)

globalLeaf:
  H("tron.archive.global.leaf.v1" || algorithmId_u16 ||
    domainId_u16 || domainRoot32 || domainMetaHash32)

globalBranch:
  H("tron.archive.global.branch.v1" || algorithmId_u16 ||
    depth_u16 || left32 || right32)
```

`domainMetaHash32`：

```text
H("tron.archive.domain.meta.v1" || algorithmId_u16 ||
  domainId_u16 || domainNameLen_u16 || domainName ||
  keyCodecId_u16 || valueCodecId_u16 ||
  rootPolicy_u8 || registryChecksum32)
```

Rationale：

- `domainId` 和 codec id 进入 leaf，防止不同 domain/key/value codec 下同 bytes 混淆。
- tombstone 与 present empty bytes 不同。
- global root 绑定 registry checksum，防止 schema 漂移后 proof 误验。

## 8. Rooted domains and coverage

L7 只 root L3 registry 声明为 `RootPolicy.IN_GLOBAL_ROOT` 的 domain。

P0 rooted domains：

| Domain | root policy | key/value |
| --- | --- | --- |
| `ACCOUNT` | in global root | address21 -> `AccountCapsule.getData()` |
| `CONTRACT` | in global root | address21 -> `ContractCapsule.getData()` |
| `CODE` | in global root | address21 -> runtime code bytes |
| `CONTRACT_STORAGE` | in global root | `address21 || slot32 || version` -> storage value |
| `DYNAMIC_PROPERTIES` | allowlist only | only L3-approved execution semantic keys |

P0 not default-rooted：

| Domain | reason |
| --- | --- |
| `ABI` | not required by P0 state getters |
| `CONTRACT_STATE` | historical eth_call may need later |
| raw `storage-row` | physical key, explicitly excluded |
| accountStateRoot account trie | existing partial root, not archive domain |

Coverage enum：

```text
TVM_STATE_ONLY
ARCHIVE_DOMAIN_SET_V1
```

禁止命名：

```text
FULL_TRON_STATE
CONSENSUS_STATE_ROOT
ETHEREUM_STATE_ROOT
```

除非后续确实把 TRON 全部 canonical state domain 纳入 root。

## 9. Root value normalization

文件：

```text
RootValueNormalizer.java
```

职责：

```text
DomainWrite / ArchiveChange -> ArchiveCommitmentUpdate
```

输入来源：

```text
block-end root:
  BlockWriteSet.finalWrites() / compressed firstBefore-finalAfter

tx root on-demand:
  ArchiveTemporalStore CHANGESET rows replayed in txNum order

rebuild verifier:
  ArchiveTemporalStore LATEST rows
```

规则：

```text
domain.rootPolicy != IN_GLOBAL_ROOT -> ignore
domain history-only/debug-only -> ignore
same before/after -> ignore for block-end root
finalAfter tombstone -> update with tombstone valueHash, tree apply deletes leaf
finalAfter present empty bytes -> present empty value, not tombstone
canonical value bytes -> registry value codec
canonical key bytes -> registry key codec
CONTRACT_STORAGE -> semantic key only
DYNAMIC_PROPERTIES -> L3 allowlist only
```

`ArchiveCommitmentUpdate`：

```java
public final class ArchiveCommitmentUpdate {
  private final int domainId;
  private final String domainName;
  private final byte[] canonicalKey;
  private final byte[] canonicalValue; // null means tombstone
  private final byte[] path32;
  private final byte[] valueHash32;
  private final long txNum;
  private final int keyCodecId;
  private final int valueCodecId;
}
```

`canonicalValue == null` means tombstone only inside commitment update. It must not be confused with `ArchiveBatch.delete`, which deletes archive rows.

## 10. Node records

文件：

```text
CommitmentNodeRecord.java
CommitmentNodeCodec.java
CommitmentNodeStore.java
```

P0 sparse Merkle node model：

```java
public final class CommitmentNodeRecord {
  public enum Kind {
    BRANCH,
    LEAF
  }

  private final int algorithmId;
  private final ArchiveTreeKind treeKind;
  private final int domainId;
  private final int depth;
  private final byte[] nodeHash;
  private final byte[] encodedPayload;
}
```

Branch payload：

```text
kind_u8 = BRANCH
depth_u16
leftHash32
rightHash32
```

Leaf payload：

```text
kind_u8 = LEAF
depth_u16 = 256
path32
domainId_u16
keyCodecId_u16
valueCodecId_u16
keyLen_u32
canonicalKey
valueHash32
```

Codec rules：

- Input/output arrays copied.
- `nodeHash` exactly 32 bytes.
- `leftHash/rightHash/path/valueHash` exactly 32 bytes.
- `depth` 0..256.
- Unknown kind rejected.
- Unknown algorithm rejected.
- Unknown tree kind rejected.
- Branch with both children empty can collapse to empty hash and not be stored.
- Leaf collision throws `ArchiveCommitmentException(COLLISION)`.

Content-addressed node key：

```text
COMMITMENT_NODE(algorithmId, treeKind, domainId, nodeHash32)
```

P0 does not delete node records on unwind. Nodes are immutable and may be shared by old/new roots. GC needs explicit retention policy later.

## 11. Root records

### 11.1 ArchiveRootRecord

文件：

```text
ArchiveRootRecord.java
ArchiveRootRecordCodec.java
```

Suggested model：

```java
public final class ArchiveRootRecord {
  private final int schemaVersion;
  private final int algorithmId;
  private final long blockNum;
  private final byte[] blockHash;
  private final long asOfTxNum;
  private final byte[] globalRoot;
  private final byte[] parentGlobalRoot;
  private final Map<Integer, DomainRootRecord> domainRoots;
  private final ArchiveRootCoverage coverage;
  private final byte[] registryChecksum;
  private final byte[] writeSetHash;
  private final boolean persisted;
}
```

For persisted block-end rows:

```text
persisted = true
key = ROOT_BY_BLOCK(blockNum)
asOfTxNum = blockRange.finalizeTxNum
```

For optional tx rows:

```text
persisted = true
key = ROOT_BY_TX(txNum)
asOfTxNum = txNum
```

For on-demand tx root:

```text
persisted = false
not written to archive DB
returned by ArchiveTxRootComputer
```

### 11.2 DomainRootRecord

```java
public final class DomainRootRecord {
  private final int domainId;
  private final String domainName;
  private final byte[] domainRoot;
  private final byte[] parentDomainRoot;
  private final long asOfTxNum;
  private final int keyCodecId;
  private final int valueCodecId;
  private final RootPolicy rootPolicy;
}
```

### 11.3 CurrentRootRecord

```java
public final class CurrentRootRecord {
  private final int algorithmId;
  private final ArchiveTreeKind treeKind;
  private final int domainId;
  private final byte[] rootHash;
  private final long blockNum;
  private final byte[] blockHash;
  private final long asOfTxNum;
  private final byte[] registryChecksum;
  private final ArchiveRootCoverage coverage;
}
```

### 11.4 CommitmentProgressRecord

```java
public final class CommitmentProgressRecord {
  public enum Status {
    EMPTY,
    OK,
    REPAIR_REQUIRED
  }

  private final int schemaVersion;
  private final int algorithmId;
  private final long appliedBlockNum;
  private final byte[] appliedBlockHash;
  private final long asOfTxNum;
  private final byte[] globalRoot;
  private final byte[] registryChecksum;
  private final Status status;
  private final String message;
}
```

Progress invariants：

```text
status OK:
  ROOT_BY_BLOCK(appliedBlockNum) exists
  ROOT_BY_BLOCK.globalRoot == globalRoot
  ROOT_BY_BLOCK.asOfTxNum == asOfTxNum
  ROOT_CURRENT(global).rootHash == globalRoot
  registryChecksum == current L3 checksum

status REPAIR_REQUIRED:
  archive enabled strict startup must fail fast
```

## 12. ArchiveCommitmentTree

文件：

```text
ArchiveCommitmentTree.java
SparseMerkleArchiveCommitmentTree.java
ArchiveCommitmentContext.java
DefaultArchiveCommitmentContext.java
ArchiveCommitmentResult.java
```

Tree API：

```java
public interface ArchiveCommitmentTree {
  byte[] emptyRoot(ArchiveTreeId treeId);

  ArchiveCommitmentResult apply(
      ArchiveCommitmentContext context,
      ArchiveTreeId treeId,
      byte[] parentRoot,
      List<ArchiveCommitmentUpdate> updates)
      throws ArchiveCommitmentException;
}
```

Context API：

```java
public interface ArchiveCommitmentContext {
  Optional<CommitmentNodeRecord> getNode(ArchiveTreeId treeId, byte[] nodeHash);

  void putNode(ArchiveTreeId treeId, CommitmentNodeRecord node, ArchiveBatch batch);

  Optional<CurrentRootRecord> getCurrent(ArchiveTreeId treeId);

  void putCurrent(ArchiveTreeId treeId, CurrentRootRecord current, ArchiveBatch batch);
}
```

Result：

```java
public final class ArchiveCommitmentResult {
  private final byte[] rootHash;
  private final List<CommitmentNodeRecord> newNodes;
  private final int appliedUpdateCount;
}
```

Implementation shape：

```text
1. Validate parentRoot length or use tree empty root.
2. Sort updates by path32/domainId/canonicalKey.
3. Apply updates into a transient sparse tree view.
4. For tombstone, remove leaf.
5. Collapse empty branches.
6. Encode new leaf/branch records.
7. Return root hash and new nodes.
```

P0 can choose a simple path-copy sparse Merkle implementation instead of an optimized Patricia fold, as long as tests prove:

- deterministic root independent of input order.
- put/update/delete/tombstone correctness.
- content-addressed node records are persisted.
- applying updates from parent root works after restart.

Do not import `framework/src/main/java/org/tron/core/trie/TrieImpl.java` into chainbase.

## 13. DefaultArchiveCommitmentBuilder

文件：

```text
ArchiveCommitmentBuilder.java
NoopArchiveCommitmentBuilder.java
DefaultArchiveCommitmentBuilder.java
```

Interface：

```java
public interface ArchiveCommitmentBuilder {
  void stageBlockEnd(BlockWriteSet writeSet, ArchiveBatch batch)
      throws ArchiveCommitmentException;

  void stageUnwindBlock(long blockNum, byte[] blockHash, ArchiveBatch batch)
      throws ArchiveCommitmentException;

  Optional<ArchiveRootRecord> rootAtBlock(long blockNum);

  Optional<ArchiveRootRecord> rootAtTxNum(long txNum)
      throws ArchiveCommitmentException;
}
```

No-op：

```text
archive disabled or commitment disabled:
  stageBlockEnd -> no-op
  stageUnwindBlock -> no-op
  rootAtBlock/rootAtTxNum -> empty
```

Default dependencies：

```java
public DefaultArchiveCommitmentBuilder(
    ArchiveConfig.CommitmentConfig config,
    ArchiveRawStore rawStore,
    ArchiveTemporalStore temporalStore,
    PersistentArchiveTxNumIndex txNumIndex,
    ArchiveDomainRegistry domainRegistry,
    ArchiveCommitmentTree tree,
    ArchiveCommitmentContext context,
    RootValueNormalizer normalizer) {
  ...
}
```

`stageBlockEnd` flow：

```text
1. if disabled -> no-op
2. validate temporal progress parent:
     temporal parent must match commitment progress parent
3. blockNum/blockHash/finalizeTxNum from BlockWriteSet/txNumIndex
4. group final writes by rooted domain
5. for each rooted domain:
     parentDomainRoot = ROOT_CURRENT(domain) or domain empty root
     updates = normalizer.fromBlockFinalWrites(domainWrites)
     domainResult = domainTree.apply(context, domainTreeId, parentDomainRoot, updates)
     stage new COMMITMENT_NODE rows
     stage ROOT_CURRENT(domain)
6. build globalUpdates from all rooted domain descriptors/root hashes
7. parentGlobalRoot = ROOT_CURRENT(global) or global empty root
8. globalResult = globalTree.apply(context, globalTreeId, parentGlobalRoot, globalUpdates)
9. stage global COMMITMENT_NODE rows
10. stage ROOT_CURRENT(global)
11. stage ROOT_BY_BLOCK(blockNum)
12. stage COMMITMENT_META(rootProgress OK)
13. if persistTxRoots=true:
      either stage per tx roots or fail-fast at startup if unsupported
```

Every canonical block writes `ROOT_BY_BLOCK`, including empty blocks. Empty block root may equal parent root, but root record/progress must still advance to the block's finalize txNum.

`writeSetHash`：

```text
H("tron.archive.writeSet.v1" ||
  blockNum_u64 || blockHash32 || finalizeTxNum_u64 ||
  ordered(domainId || keyLen || canonicalKey || afterValueHash))
```

## 14. Shared batch integration

L5 `DefaultArchiveService.commitBlock` must become shared staging:

```text
commitBlock(block):
  writeSet = collector.finishBlock()
  batch = archiveRawStore.newBatch()

  temporalStore.stageApplyBlock(writeSet, batch)
  commitmentBuilder.stageBlockEnd(writeSet, batch)

  archiveRawStore.updateByBatch(batch.toRawMap())
```

`stageApplyBlock` and `stageBlockEnd` both write to the same `ArchiveBatch` before `commit()`。If commitment fails, temporal rows must not be committed.

This is the java-tron equivalent of Erigon `ComputeCommitment` flushing branch state into the same temporal/commitment domain lifecycle.

Failure behavior after canonical block commit:

```text
canonical revoking session already committed
archive batch fails
  -> mark archive progress/commitment status REPAIR_REQUIRED if possible
  -> fail fast in strict archive mode
  -> do not silently continue serving historical reads
```

## 15. Transaction-level root support

Issue #6289 的目标是“交易级别的状态树”。L7 不能只设计 block-end root。

Default persistence：

```text
persistTxRoots = false
ROOT_BY_BLOCK persisted for block finalize point
ROOT_BY_TX not persisted by default
rootAtTxNum(txNum) supported on demand
```

`ArchiveTxRootComputer`：

```java
public final class ArchiveTxRootComputer {
  ArchiveRootRecord rootAtTxNum(long txNum)
      throws ArchiveCommitmentException;
}
```

On-demand strategy：

```text
1. Locate containing BlockTxNumRange from PersistentArchiveTxNumIndex.
2. If txNum == range.finalizeTxNum:
     return ROOT_BY_BLOCK(blockNum)
3. Determine checkpoint:
     parent block root by default
     optional ROOT_CHECKPOINT / ROOT_BY_TX if persisted
4. Load parent domain/global roots and branch nodes.
5. Scan CHANGESET from checkpointTxNum + 1 through txNum.
6. Replay changes in txNum order, grouped by logical tx point.
7. Apply each tx's rooted changes to domain roots, then global root.
8. Return transient ArchiveRootRecord(persisted=false, asOfTxNum=txNum).
```

Semantic mapping：

| State point | Root lookup |
| --- | --- |
| `BLOCK_END(blockNum)` | `ROOT_BY_BLOCK(blockNum)` |
| `TX_AFTER(txNum)` | `rootAtTxNum(txNum)` |
| `TX_BEFORE(txNum)` | `rootAtTxNum(txNum - 1)` unless txNum is block first, then parent block root |
| `SYSTEM_AFTER(txNum)` | `rootAtTxNum(txNum)` |

Important distinction：

```text
block-end root input:
  compressed final-after writes for the whole block

transaction-level root input:
  sequential CHANGESET replay per txNum
```

If a key changes A -> B -> A inside one block:

- block-end final-after update may be no-op and root equals parent.
- intermediate tx root must change.

Therefore L4/L5 changeset retention is mandatory for transaction-level state tree.

If `persistTxRoots=true`:

```text
L7 may persist ROOT_BY_TX after every logical tx.
If not implemented, config must fail fast.
Logical tx includes BLOCK_PREPARE, USER_TX(index), BLOCK_FINALIZE.
```

## 16. Unwind

`stageUnwindBlock` flow：

```text
1. if disabled -> no-op
2. read CommitmentProgressRecord rootProgress
3. require rootProgress.appliedBlockNum == blockNum
4. require rootProgress.appliedBlockHash == blockHash
5. read ROOT_BY_BLOCK(blockNum)
6. require rootRecord.blockHash == blockHash
7. parentBlockNum = blockNum - 1
8. read ROOT_BY_BLOCK(parentBlockNum), unless genesis/bootstrap
9. restore ROOT_CURRENT(domain) rows to parentRootRecord.domainRoots
10. restore ROOT_CURRENT(global)
11. delete ROOT_BY_BLOCK(blockNum)
12. delete ROOT_BY_TX rows in block range if persistTxRoots
13. stage COMMITMENT_META(rootProgress OK for parent)
```

Do not delete `COMMITMENT_NODE` rows in P0.

Missing/mismatch behavior：

```text
block hash mismatch -> stage REPAIR_REQUIRED if possible, throw
parent root missing -> stage REPAIR_REQUIRED if possible, throw
progress behind/ahead temporal progress -> REPAIR_REQUIRED, throw
```

Never rebuild root from java-tron latest Store during hot unwind.

## 17. Startup verifier and rebuild verifier

### 17.1 Startup verifier

When commitment enabled:

```text
temporalProgress = ArchiveTemporalStore.progress()
rootProgress = CommitmentProgressRecord

assert rootProgress.status == OK
assert temporalProgress.appliedBlockNum == rootProgress.appliedBlockNum
assert temporalProgress.appliedBlockHash == rootProgress.appliedBlockHash
assert temporalProgress.finalizeTxNum == rootProgress.asOfTxNum
assert ROOT_BY_BLOCK(rootProgress.appliedBlockNum) exists
assert ROOT_BY_BLOCK.globalRoot == rootProgress.globalRoot
assert ROOT_BY_BLOCK.asOfTxNum == rootProgress.asOfTxNum
assert ROOT_CURRENT(global).rootHash == rootProgress.globalRoot
assert rootProgress.registryChecksum == current registry checksum
```

If any check fails:

```text
write/keep REPAIR_REQUIRED
strict archive mode -> fail fast
no historical reads served as latest fallback
```

### 17.2 Latest rebuild verifier

文件：

```text
ArchiveCommitmentRebuildVerifier.java
```

Flow：

```text
1. Read commitment progress.
2. Read archive LATEST table for domains with RootPolicy.IN_GLOBAL_ROOT.
3. Decode keys/values with ArchiveDomainRegistry codecs.
4. Build domain updates from scratch from empty roots.
5. Build each domain root.
6. Build global root from domain roots and domain metadata.
7. Compare with ROOT_BY_BLOCK(progress.appliedBlockNum).globalRoot.
8. Compare domain roots if record includes them.
9. On mismatch, mark REPAIR_REQUIRED and throw in strict mode.
```

禁止：

```text
AccountStore.get
ContractStore.get
CodeStore.get
StorageRowStore.get
Wallet.getAccount
Wallet.getContract
```

Rebuild scans archive `LATEST` only。This proves the archive sidecar is internally consistent; it does not prove complete TRON consensus state.

## 18. Root reader

文件：

```text
ArchiveRootReader.java
```

API：

```java
public interface ArchiveRootReader {
  Optional<ArchiveRootRecord> rootAtBlock(long blockNum);

  Optional<ArchiveRootRecord> rootAtTxNum(long txNum)
      throws ArchiveCommitmentException;

  CommitmentProgressRecord progress();
}
```

L7 root reader is internal. Public debug API comes in L9.

Rules：

```text
rootAtBlock missing -> Optional.empty()
rootAtTxNum outside archive coverage -> HISTORY_UNAVAILABLE
rootAtTxNum target == finalizeTxNum -> persisted block root
rootAtTxNum intermediate -> transient computed root
```

## 19. Proof/debug boundary

L7 stores enough node records to enable future proof generation, but no public proof API.

Allowed in L7:

```text
internal root reader
internal rebuild verifier
framework regression proving BlockResult.stateRoot unchanged
```

Not allowed in L7:

```text
debug_getArchiveRoot
debug_getArchiveProof
debug_verifyArchiveProof
eth_getProof
debug_traceCall
proof response DTOs
VM trace output
```

L9 will expose archive-native debug APIs with names that make sidecar scope explicit.

## 20. Tests

### 20.1 Config tests

Extend:

```text
common/src/test/java/org/tron/core/config/args/StorageConfigArchiveTest.java
```

Cases：

```text
commitmentDefaultsDisabled
commitmentEnableRequiresArchiveEnable
unknownCommitmentAlgorithmRejected
unknownRootCoverageRejected
persistTxRootsUnsupportedFailsFast
checkpointIntervalUnsupportedFailsFast
doesNotReadAllowAccountStateRoot
```

### 20.2 Key/record codec tests

新增：

```text
chainbase/src/test/java/org/tron/core/archive/commitment/RootKeyCodecTest.java
chainbase/src/test/java/org/tron/core/archive/commitment/ArchiveRootRecordCodecTest.java
chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentProgressCodecTest.java
chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentNodeCodecTest.java
```

Cases：

```text
rootByBlockKeysSortByBlockNum
rootByTxKeysSortByTxNum
rootCurrentKeyRoundTrip
commitmentNodeKeyRequiresHash32
archiveRootRecordRoundTripCopiesArrays
domainRootRecordRoundTrip
progressRecordRoundTrip
unknownAlgorithmRejected
unknownTreeKindRejected
unknownSchemaRejected
```

### 20.3 Hash/tree tests

新增：

```text
chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentHashTest.java
chainbase/src/test/java/org/tron/core/archive/commitment/SparseMerkleArchiveCommitmentTreeTest.java
```

Cases：

```text
emptyRootDeterministic
emptyRootDiffersFromHashEmptyTrieHash
domainAndGlobalHashSeparated
sameKeyDifferentDomainDifferentRoot
sameValuePresentEmptyDiffersFromTombstone
updatesInputOrderIndependent
putUpdateDeleteTombstoneChangesRoot
deleteMissingKeyKeepsRootStable
collisionGuardRejectsDifferentKeySamePath
contentAddressedNodesCopied
applyFromPersistedParentRootAfterRestart
```

### 20.4 Normalizer/builder tests

新增：

```text
chainbase/src/test/java/org/tron/core/archive/commitment/RootValueNormalizerTest.java
chainbase/src/test/java/org/tron/core/archive/commitment/DefaultArchiveCommitmentBuilderTest.java
```

Cases：

```text
normalizerIncludesOnlyRootedDomains
normalizerIgnoresStorageRowPhysicalKey
normalizerUsesContractStorageSemanticKey
normalizerTreatsEmptyBytesAsPresent
normalizerTreatsTombstoneAsDelete
normalizerSkipsBlockEndNoop
stageBlockEndWritesDomainAndGlobalRoots
emptyBlockWritesRootRecordAndProgress
sameWriteSetDifferentOrderSameRoot
temporalAndCommitmentRowsSameBatch
commitmentFailurePreventsBatchCommit
rootRecordContainsRegistryChecksumCoverageAlgorithm
```

### 20.5 Unwind/rebuild/root reader tests

新增：

```text
chainbase/src/test/java/org/tron/core/archive/commitment/ArchiveCommitmentUnwindTest.java
chainbase/src/test/java/org/tron/core/archive/commitment/ArchiveCommitmentRebuildVerifierTest.java
chainbase/src/test/java/org/tron/core/archive/commitment/ArchiveTxRootComputerTest.java
chainbase/src/test/java/org/tron/core/archive/commitment/ArchiveRootReaderTest.java
```

Cases：

```text
unwindRestoresCurrentRootToParent
unwindDeletesRootByBlockForUnwoundBlock
unwindDoesNotDeleteContentAddressedNodes
unwindBlockHashMismatchMarksRepair
unwindMissingParentMarksRepair
startupProgressMatchesTemporalProgress
startupRegistryChecksumMismatchFails
rebuildFromArchiveLatestEqualsStoredRoot
rebuildTamperedLatestDetectsMismatch
rebuildDoesNotReadCanonicalStores
rootAtBlockReturnsPersistedRoot
rootAtTxNumFinalizeReturnsBlockRoot
rootAtTxNumIntermediateReplaysChangeset
rootAtTxNumAtoBtoAIntermediateChangesButBlockEndSame
rootAtTxNumOutsideCoverageFails
```

### 20.6 Framework regression tests

新增：

```text
framework/src/test/java/org/tron/core/services/jsonrpc/BlockResultArchiveRootRegressionTest.java
```

Cases：

```text
blockResultStateRootStillHeaderAccountStateRoot
blockResultTransactionsRootStillHeaderTxTrieRoot
archiveRootNotInjectedIntoEthGetBlockByNumber
archiveCommitmentDoesNotCallSetAccountStateRoot
```

If direct call spying on `BlockCapsule.setAccountStateRoot` is awkward, assert via block header bytes before/after archive commitment and JSON-RPC `BlockResult` output.

## 21. Patch 拆分顺序

### L7a：config + algorithm + no-op

Files：

```text
ArchiveCommitmentAlgorithm.java
ArchiveRootCoverage.java
ArchiveCommitmentException.java
NoopArchiveCommitmentBuilder.java
StorageConfigArchiveTest
```

Gate：

```bash
./gradlew :common:test --tests '*StorageConfigArchiveTest'
./gradlew :chainbase:test --tests '*ArchiveCommitmentAlgorithmTest'
```

### L7b：root/node key and value codecs

Files：

```text
RootKeyCodec.java
ArchiveRootRecord*.java
DomainRootRecord.java
CurrentRootRecord.java
CommitmentProgressRecord.java
CommitmentNodeRecord.java
CommitmentNodeCodec.java
```

Gate：

```bash
./gradlew :chainbase:test --tests '*RootKeyCodecTest'
./gradlew :chainbase:test --tests '*ArchiveRootRecordCodecTest'
./gradlew :chainbase:test --tests '*CommitmentProgressCodecTest'
./gradlew :chainbase:test --tests '*CommitmentNodeCodecTest'
```

### L7c：hash + sparse Merkle tree

Files：

```text
CommitmentHash.java
ArchiveTreeKind.java
ArchiveTreeId.java
ArchiveCommitmentTree.java
SparseMerkleArchiveCommitmentTree.java
ArchiveCommitmentContext.java
DefaultArchiveCommitmentContext.java
```

Gate：

```bash
./gradlew :chainbase:test --tests '*CommitmentHashTest'
./gradlew :chainbase:test --tests '*SparseMerkleArchiveCommitmentTreeTest'
```

### L7d：normalizer + block-end builder

Files：

```text
RootValueNormalizer.java
ArchiveCommitmentBuilder.java
DefaultArchiveCommitmentBuilder.java
DefaultArchiveService.java
```

Gate：

```bash
./gradlew :chainbase:test --tests '*RootValueNormalizerTest'
./gradlew :chainbase:test --tests '*DefaultArchiveCommitmentBuilderTest'
```

### L7e：unwind + startup verifier

Files：

```text
DefaultArchiveCommitmentBuilder.stageUnwindBlock
Commitment startup verifier wiring
DefaultArchiveService unwind shared batch integration
```

Gate：

```bash
./gradlew :chainbase:test --tests '*ArchiveCommitmentUnwindTest'
./gradlew :chainbase:test --tests '*ArchiveTemporalStoreManagerWiringTest'
```

### L7f：rebuild verifier + tx root computer

Files：

```text
ArchiveCommitmentRebuildVerifier.java
ArchiveTxRootComputer.java
ArchiveRootReader.java
```

Gate：

```bash
./gradlew :chainbase:test --tests '*ArchiveCommitmentRebuildVerifierTest'
./gradlew :chainbase:test --tests '*ArchiveTxRootComputerTest'
./gradlew :chainbase:test --tests '*ArchiveRootReaderTest'
```

### L7g：framework header untouched regression

Files：

```text
BlockResultArchiveRootRegressionTest.java
```

Gate：

```bash
./gradlew :framework:test --tests '*BlockResultArchiveRootRegressionTest'
```

## 22. 验证命令

L7 focused gate：

```bash
./gradlew :common:test --tests '*StorageConfigArchiveTest'
./gradlew :chainbase:test --tests '*RootKeyCodecTest'
./gradlew :chainbase:test --tests '*ArchiveRootRecordCodecTest'
./gradlew :chainbase:test --tests '*CommitmentProgressCodecTest'
./gradlew :chainbase:test --tests '*CommitmentNodeCodecTest'
./gradlew :chainbase:test --tests '*CommitmentHashTest'
./gradlew :chainbase:test --tests '*SparseMerkleArchiveCommitmentTreeTest'
./gradlew :chainbase:test --tests '*RootValueNormalizerTest'
./gradlew :chainbase:test --tests '*DefaultArchiveCommitmentBuilderTest'
./gradlew :chainbase:test --tests '*ArchiveCommitmentUnwindTest'
./gradlew :chainbase:test --tests '*ArchiveCommitmentRebuildVerifierTest'
./gradlew :chainbase:test --tests '*ArchiveTxRootComputerTest'
./gradlew :chainbase:test --tests '*ArchiveRootReaderTest'
./gradlew :framework:test --tests '*BlockResultArchiveRootRegressionTest'
./gradlew checkstyleMain checkstyleTest
```

合入前：

```bash
./gradlew :common:test
./gradlew :chainbase:test
./gradlew :framework:test
./gradlew checkstyleMain checkstyleTest
```

## 23. Review checklist

- archive commitment disabled by default.
- commitment enable requires archive temporal enable.
- no call to `BlockCapsule.setAccountStateRoot()`.
- no call to `BlockCapsule.setMerkleRoot()` from archive code.
- no production change to `BlockResult.stateRoot`.
- no dependency from `chainbase` archive core to `framework TrieImpl`.
- `Hash.EMPTY_TRIE_HASH` not used as archive empty root.
- root hash includes domain id、canonical key、codec id、value/tombstone marker。
- updates sorted by `path32` then `domainId/canonicalKey`。
- storage root uses `CONTRACT_STORAGE` semantic key, not `StorageRowStore` physical key。
- rooted domains come only from `ArchiveDomainRegistry.rootPolicy`。
- root record contains algorithm、coverage、schema、registry checksum、writeSetHash。
- branch/leaf nodes are persisted in `COMMITMENT_BRANCH` in same archive batch as root records。
- temporal rows and root rows commit in one archive DB batch。
- empty blocks still write root record/progress。
- unwind validates `(blockNum, blockHash)`。
- rebuild verifier scans archive `LATEST`, not canonical Store。
- rootAtTxNum intermediate root replays `CHANGESET` sequentially。
- `persistTxRoots=true` fails fast until every-tx persistence is implemented。
- no `eth_getProof` or debug proof API in L7。

## 24. DONE 定义

L7 完成必须同时满足：

1. `CommitmentHash` 和 empty root chain 有稳定、domain-separated spec 和测试。
2. `RootKeyCodec` 能表达 block root、tx root、current root、node 和 meta key，且排序稳定。
3. `ArchiveRootRecord`、`DomainRootRecord`、`CurrentRootRecord`、`CommitmentProgressRecord` codecs round-trip 且 copy bytes。
4. `SparseMerkleArchiveCommitmentTree` 支持 put/update/delete/tombstone，并保证 input order independence。
5. `DefaultArchiveCommitmentBuilder.stageBlockEnd` 从 `BlockWriteSet` 生成 domain/global root，并与 temporal rows 同 batch。
6. 每个 canonical block 都有 `ROOT_BY_BLOCK`，包括空块。
7. `stageUnwindBlock` 能按 parent root 恢复 current/progress，并校验 block hash。
8. startup verifier 能校验 temporal/root progress、root record、current root、registry checksum。
9. rebuild verifier 从 archive `LATEST` 重建 latest root，与落盘 root 比对。
10. `rootAtTxNum` on-demand 设计和测试覆盖中间 tx root，不只覆盖 block-end root。
11. java-tron header `txTrieRoot/accountStateRoot` 和 JSON-RPC `BlockResult.stateRoot` 不被 archive root 改写。
12. L7 focused tests 和 checkstyle gate 通过。
