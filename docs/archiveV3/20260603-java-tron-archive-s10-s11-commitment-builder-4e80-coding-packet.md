# java-tron Archive S10/S11：CommitmentBuilder 4e80 编码执行包

日期：2026-06-03

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`，`git status --short` 为空，精确冲突标记扫描无命中。

Erigon 源码路径：`/Users/boson/GolandProjects/erigon`

归属路线：[java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)

模块来源：[模块 06 CommitmentBuilder：4e80 java-tron 源码对照细化](./20260603-java-tron-module-06-commitment-builder-4e80-source-deep-dive.md)

收窄后的代码级执行包：[java-tron Archive L7：CommitmentBuilder 代码级执行包](./20260605-java-tron-archive-l7-commitment-builder-4e80-code-plan.md)

前置依赖：

- [S3 ArchiveDomainRegistry 4e80 编码执行包](./20260603-java-tron-archive-s3-domain-registry-4e80-coding-packet.md)：root policy、domain id、key/value codec、coverage。
- [S4/S5 WriteCollector 与 Storage Semantic Hook 4e80 编码执行包](./20260603-java-tron-archive-s4-s5-write-collector-storage-4e80-coding-packet.md)：`BlockWriteSet` 与 `DomainWrite(firstBefore, finalAfter)`。
- [S6/S7 ArchiveTemporalStore 4e80 编码执行包](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md)：single archive DB、`ArchiveBatch`、temporal stage/apply/unwind。
- [S8/S9 ArchiveStateReader 与 JSON-RPC Historical Getters 4e80 编码执行包](./20260603-java-tron-archive-s8-s9-state-reader-rpc-4e80-coding-packet.md)：reader/rebuild verifier 的 state view。

S10/S11 在 archive history 闭环上增加 archive sidecar commitment root。它不替换 java-tron 区块头 root，不参与共识校验；它为 archive domains 提供可重放、可验证、可 debug 的状态承诺。

## 1. 交付边界

S10 交付 tree core 与 root codecs：

```text
ArchiveCommitmentAlgorithm
  -> CommitmentHash / empty hash chain
  -> content-addressed sparse Merkle node codecs
  -> RootRecord / CurrentRoot / CommitmentProgress codecs
  -> RootKeyCodec over ArchiveTable 0x30+
```

S11 交付 block commit/unwind/rebuild 接入：

```text
BlockWriteSet
  -> ArchiveCommitmentUpdate(rooted domains only)
  -> SparseMerkleArchiveCommitmentTree
  -> RootRecord(block end)
  -> ROOT_CURRENT / COMMITMENT_BRANCH / COMMITMENT_META
  -> same ArchiveBatch as temporal rows
  -> startup/latest rebuild verifier
```

本批次不交付：

- 不写 `BlockHeader.raw.txTrieRoot`。
- 不写 `BlockHeader.raw.accountStateRoot`。
- 不修改 `BlockCapsule.validateMerkleRoot()`。
- 不修改 JSON-RPC `BlockResult.stateRoot` 的现有含义。
- 不把 archive proof 伪装成 Ethereum `eth_getProof`。
- 不默认持久化 every-tx root；默认只持久化 block-end root，支持 on-demand `rootAtTxNum`。
- 不实现 concurrent commitment update；先保证确定性和可验证性。

完成条件：

1. Archive root 是 sidecar root，header root 行为不变。
2. Updates 按 hashed path 排序，输入顺序不影响 root。
3. Root rows 与 temporal rows 同一个 `ArchiveBatch` 原子提交。
4. `ROOT_RECORD`、`ROOT_CURRENT`、`COMMITMENT_BRANCH`、`COMMITMENT_META` 同步推进。
5. Hot unwind 校验 block hash 后恢复 root current/progress，与 temporal progress 不分叉。
6. Rebuild verifier 可从 archive `LATEST` 重新计算 latest root 并比对落盘 root。

## 2. 4e80 源码锚点

### 2.1 header roots 不是 archive roots

| 源码 | 当前事实 | S10/S11 约束 |
| --- | --- | --- |
| `protocol/.../Tron.proto:504-513` | `BlockHeader.raw` 里有 `txTrieRoot = 2` 和 `accountStateRoot = 11` | archive root 不写这两个字段 |
| `BlockCapsule.java:218-230` | `calcMerkleRoot()` 计算交易 Merkle root；空交易返回 `Sha256Hash.ZERO_HASH` | 这是 transaction root，不是 state root |
| `BlockCapsule.java:233-244` | `validateMerkleRoot()` 校验 `txTrieRoot` | archive root 不接入此校验 |
| `BlockCapsule.java:246-253` | `setMerkleRoot()` 写 `txTrieRoot` | archive root 不调用 |
| `BlockCapsule.java:255-262` | `setAccountStateRoot(byte[])` 写 header `accountStateRoot` | archive root 不调用 |
| `BlockCapsule.java:278-284` | `getAccountRoot()` 读取 header `accountStateRoot`，空时返回 zero hash | 与 archive root 独立 |
| `BlockResult.java:101-104` | JSON-RPC block result 暴露 `txTrieRoot` 和 `accountStateRoot` | `stateRoot` 不静默替换成 archive root |

### 2.2 accountStateRoot 只能借生命周期

| 源码 | 当前事实 | S10/S11 结论 |
| --- | --- | --- |
| `reference.conf:812` | `allowAccountStateRoot = 0` 默认关闭 | archive commitment 走独立 `storage.archive.commitment.*` 配置 |
| `CommonParameter.java:379`、`Args.java:462` | runtime/args 读取现有 account state root 开关 | 不复用为 archive commitment 开关 |
| `DynamicPropertiesStore.java:2375-2389` | governance property 控制 `allowAccountStateRoot()` | archive sidecar root 不受该治理属性控制 |
| `AccountStateCallBack.java:52-72` | 从 parent header `accountStateRoot` 初始化 `TrieImpl` | archive parent root 来自 `ROOT_CURRENT`/root records |
| `AccountStateCallBack.java:74-92` | push block 时校验 header root | archive verifier 不拒绝共识块，除非显式 strict archive 模式 |
| `AccountStateCallBack.java:94-105` | generate block 时写 header `accountStateRoot` | archive 禁止调用 |
| `AccountStateEntity.java:16-22` | 只保留 `address/balance/allowance` | 不是完整 archive account state |
| `AccountStateCallBackUtils.java:13-22` | 只消费 `AccountCapsule` | 不覆盖 contract/code/storage/dynamic |
| `AccountStore.delete` | 删除路径不触发 account trie delete callback | archive delete 必须来自 `DomainWrite.afterValue=tombstone` |

### 2.3 TrieImpl 不作为 archive tree

| 源码 | 当前事实 | S10/S11 结论 |
| --- | --- | --- |
| `framework/.../TrieImpl.java` | 位于 `framework` 模块 | `chainbase` archive core 不反向依赖 framework |
| `TrieImpl.java:144-155` | null/empty value 走 delete | 可参考 tombstone 语义，但 codec 另定 |
| `TrieImpl.java:290-292` | empty root 使用 `Hash.EMPTY_TRIE_HASH` | archive SMT 定义自己的 empty hash chain |
| `TrieImpl.java:301-306` | dirty nodes flush 后 root 收缩为 hash node | archive tree 采用 content-addressed node store |
| `TrieImpl.java:563-568` | `setRoot` 以 `EMPTY_TRIE_HASH` 表示空树 | archive root current 不使用该特殊值 |
| `AccountStateStoreTrie.java:39-42` | 通过 `TrieImpl` 读取 `AccountStateEntity` | 只服务现有 accountStateRoot |

`Hash.java:41-78` 中 `Hash.sha3` 可复用；`Hash.EMPTY_TRIE_HASH` 不可复用，因为它是 RLP Patricia 空 trie hash。

## 3. Erigon 对照不变量

| Erigon 源码 | 事实 | java-tron 映射 |
| --- | --- | --- |
| `commitment.go:91-141` | commitment `Trie` 与 state IO context 分离，context 提供 `Branch/PutBranch` | `ArchiveCommitmentTree` 与 `ArchiveCommitmentContext` 分离 |
| `commitment_context.go:248-267` | `TouchKey` 从 domain write 转 commitment update | java-tron 从 `DomainWrite` 转 `ArchiveCommitmentUpdate` |
| `commitment.go:1429-1440` | updates 必须按 hashed key 排序；plain key 顺序会导致 divergent root | java-tron 必须按 `path32` 排序，不按 Store/Map/tx 顺序 |
| `commitment.go:1972-1985` | sort by hashedKey，plainKey 仅 tie-break | java-tron tie-break 用 `domainId || canonicalKey` |
| `commitment_context.go:436-458` | `Process` 后通过 `PutBranch` 保存 branch state | java-tron root batch 必须写 `COMMITMENT_BRANCH` |
| `commitment_context.go:682-705` | commitment state 单独编码保存 | java-tron 写 `COMMITMENT_META(rootProgress)` |
| `commitment_context.go:799-823` | `Branch` copy bytes，`PutBranch` 写 CommitmentDomain | java-tron node records defensive copy，同 archive DB batch |

结论：

```text
CommitmentBuilder consumes BlockWriteSet, not latest Store scans.
Tree input order is hashed path order.
Persisted state includes nodes/current/progress, not only root hash.
```

## 4. 配置

S1/S2 的 `ArchiveConfig` 下新增 commitment 配置：

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

规则：

| 配置 | 行为 |
| --- | --- |
| `archive.enable=false` | commitment no-op |
| `commitment.enable=false` | temporal 可启用，root rows 不写 |
| `commitment.enable=true` 且 temporal disabled | startup fail fast |
| `persistTxRoots=true` 但 tx root persistence 未实现 | startup fail fast |
| `verifyOnStartup=true` | head/progress/root 基础一致性检查 |
| `rebuildOnStartup=true` | 可慢速扫描 `LATEST` 重建 latest root |

不要复用 `allowAccountStateRoot` 或 `ALLOW_ACCOUNT_STATE_ROOT`。

## 5. Archive DB key space

沿用 S6/S7 `ArchiveTable` 预留：

```text
ROOT_RECORD        0x30
COMMITMENT_BRANCH  0x31
COMMITMENT_META    0x32
```

`RootKeyCodec`：

```text
ROOT_BY_BLOCK:
  0x30 | 0x01 | algorithmId_u16 | blockNum_u64

ROOT_BY_TX:
  0x30 | 0x02 | algorithmId_u16 | txNum_u64

ROOT_CURRENT:
  0x30 | 0x03 | algorithmId_u16 | treeKind_u8 | domainId_u16

COMMITMENT_NODE:
  0x31 | algorithmId_u16 | treeKind_u8 | domainId_u16 | nodeHash32

COMMITMENT_META:
  0x32 | asciiName
```

`treeKind`：

```text
0x01 = DOMAIN_TREE
0x02 = GLOBAL_TREE
```

`domainId=0` is reserved for global tree.

P0 使用 content-addressed immutable nodes：

- branch/leaf node key by `nodeHash32`。
- updates create new node records in the same batch。
- unwind restores `ROOT_CURRENT` to previous root; old nodes remain addressable。
- future compaction/GC can remove unreachable nodes after retention policy is defined。

这样避免每次 unwind 需要反向删除/重写 path-addressed branch state。

## 6. Hash spec

`ArchiveCommitmentAlgorithm`：

```text
algorithmId = 0x0001
name = tron-archive-smt-keccak-v1
hash = TRON-KECCAK-256 via Hash.sha3
pathBits = 256
```

所有 hash 都加域隔离：

```text
emptyLeaf(depth=256):
  H("tron.archive.smt.empty.leaf.v1" || algorithmId)

emptyBranch(depth):
  H("tron.archive.smt.empty.branch.v1" || algorithmId || depth_u16 ||
    empty[depth+1] || empty[depth+1])

domain leaf path:
  H("tron.archive.domain.path.v1" || algorithmId || domainId_u16 || canonicalKey)

domain leaf hash:
  H("tron.archive.domain.leaf.v1" || algorithmId || domainId_u16 ||
    keyCodecId_u16 || valueCodecId_u16 || keyLen_u32 || canonicalKey ||
    valueHash32)

domain branch hash:
  H("tron.archive.domain.branch.v1" || algorithmId || depth_u16 || left32 || right32)

global leaf path:
  H("tron.archive.global.path.v1" || algorithmId || domainId_u16)

global leaf hash:
  H("tron.archive.global.leaf.v1" || algorithmId || domainId_u16 ||
    domainRoot32 || domainMetaHash32)

global branch hash:
  H("tron.archive.global.branch.v1" || algorithmId || depth_u16 || left32 || right32)
```

`valueHash32`：

```text
tombstone -> H("tron.archive.value.tombstone.v1" || algorithmId || domainId)
present   -> H("tron.archive.value.present.v1" || algorithmId || domainId ||
               valueCodecId || valueLen || canonicalValue)
```

不要使用 `Hash.EMPTY_TRIE_HASH`、TRON tx merkle root 或 Ethereum trie root 常量。

## 7. Node records

`CommitmentNodeRecord`：

```java
public final class CommitmentNodeRecord {
  enum Kind { BRANCH, LEAF }
  int algorithmId;
  int treeKind;
  int domainId;
  int depth;
  byte[] nodeHash;
}
```

`BranchNodeRecord`：

```text
kind = BRANCH
depth_u16
leftHash32
rightHash32
```

`LeafNodeRecord`：

```text
kind = LEAF
depth = 256
path32
domainId_u16
keyCodecId_u16
valueCodecId_u16
canonicalKeyLen_u32 | canonicalKey
valueHash32
```

Collision guard：

```text
if existing leaf path32 == new path32
and (domainId || canonicalKey) differs:
  throw ArchiveCommitmentException(COLLISION)
```

Do not silently overwrite a different leaf at the same path.

Codec rules:

- All byte arrays copied.
- Hash lengths exactly 32.
- Unknown algorithm/tree kind rejected.
- Branch nodes with both children empty may be collapsed to empty hash and not stored.

## 8. Root records

`ArchiveRootRecord`：

```java
public final class ArchiveRootRecord {
  long blockNum;
  byte[] blockHash;
  long asOfTxNum;
  byte[] globalRoot;
  byte[] parentGlobalRoot;
  Map<ArchiveDomain, DomainRootRecord> domainRoots;
  int algorithmId;
  ArchiveRootCoverage coverage;
  int schemaVersion;
  byte[] registryChecksum;
  byte[] writeSetHash;
}
```

`DomainRootRecord`：

```java
int domainId;
byte[] domainRoot;
byte[] parentDomainRoot;
long asOfTxNum;
int keyCodecId;
int valueCodecId;
RootPolicy rootPolicy;
```

`CurrentRootRecord`：

```java
int algorithmId;
int treeKind;
int domainId;
byte[] rootHash;
long blockNum;
byte[] blockHash;
long asOfTxNum;
byte[] registryChecksum;
ArchiveRootCoverage coverage;
```

`CommitmentProgressRecord`：

```java
int schemaVersion;
int algorithmId;
long appliedBlockNum;
byte[] appliedBlockHash;
long asOfTxNum;
byte[] globalRoot;
byte[] registryChecksum;
Status status; // OK or REPAIR_REQUIRED
String message;
```

Root coverage examples:

```text
TVM_STATE_ONLY
ARCHIVE_DOMAIN_SET_V1
```

Do not call P0 root `FULL_TRON_STATE` unless all canonical state domains are included.

## 9. Rooted domains

S11 uses S3 registry policy:

| Domain | P0 root inclusion |
| --- | --- |
| `ACCOUNT` | in global root |
| `CONTRACT` | in global root |
| `CODE` | in global root |
| `CONTRACT_STORAGE` | in global root |
| `DYNAMIC_PROPERTIES` | allowlist only |
| `ABI` | P1/debug; not P0 global root by default |
| `CONTRACT_STATE` | P1/P0 optional |

`RootValueNormalizer`:

```text
DomainWrite.finalAfter tombstone -> delete leaf
present value -> domain.valueCodec canonical bytes -> valueHash
same before/after no-op -> no update
domain not rooted -> ignored for commitment, still temporal if history policy says so
```

`CONTRACT_STORAGE` must use semantic canonical key from S5. Raw `storage-row` never enters root.

## 10. Tree API

```java
public interface ArchiveCommitmentTree {
  byte[] emptyRoot(ArchiveTreeId treeId);

  ArchiveCommitmentResult apply(
      ArchiveCommitmentContext context,
      ArchiveTreeId treeId,
      byte[] parentRoot,
      List<ArchiveCommitmentUpdate> updates);
}
```

`ArchiveCommitmentUpdate`:

```java
ArchiveDomain domain;
byte[] canonicalKey;
byte[] canonicalValue; // null means tombstone
byte[] path32;
byte[] valueHash32;
long txNum;
```

Sorting:

```text
sort by path32 ASC
then domainId ASC
then canonicalKey unsigned lexicographic ASC
```

`ArchiveCommitmentContext`:

```java
Optional<CommitmentNodeRecord> getNode(ArchiveTreeId treeId, byte[] nodeHash);
void putNode(ArchiveTreeId treeId, CommitmentNodeRecord node, ArchiveBatch batch);
Optional<CurrentRootRecord> getCurrent(ArchiveTreeId treeId);
void putCurrent(ArchiveTreeId treeId, CurrentRootRecord current, ArchiveBatch batch);
```

Tree core never reads `Manager`、`Wallet`、canonical Store or temporal `LATEST` directly. Rebuild verifier prepares updates from `LATEST`; hot path prepares updates from `BlockWriteSet`。

## 11. S11 block commit flow

S7 `DefaultArchiveService.commitBlock` must be refactored from self-committing temporal store to shared batch staging:

```text
commitBlock(block):
  writeSet = collector.finishBlock()
  batch = archiveRawStore.newBatch()

  temporalStore.stageApplyBlock(writeSet, batch)
  if commitment enabled:
    commitmentBuilder.stageBlockEnd(writeSet, batch)

  archiveRawStore.updateByBatch(batch.toRawMap())
```

`stageBlockEnd`:

```text
validate commitment progress aligns temporal progress parent
group DomainWrite by rooted domain
for each domain:
  parentDomainRoot = ROOT_CURRENT(DOMAIN_TREE, domainId) or domain empty root
  updates = normalize finalAfter writes for that domain
  domainResult = domainTree.apply(context, domainTreeId, parentDomainRoot, updates)
  batch.put(new COMMITMENT_NODE rows)
  batch.put(ROOT_CURRENT(domain), new current)

globalUpdates = one leaf per domain root descriptor
parentGlobalRoot = ROOT_CURRENT(GLOBAL_TREE, 0) or global empty root
globalResult = globalTree.apply(context, globalTreeId, parentGlobalRoot, globalUpdates)

rootRecord = ArchiveRootRecord(blockNum, blockHash, finalizeTxNum, globalRoot, ...)
batch.put(ROOT_BY_BLOCK(blockNum), encode(rootRecord))
batch.put(ROOT_CURRENT(global), encode(current))
batch.put(COMMITMENT_META(rootProgress), encode(progress OK))
if persistTxRoots:
  write ROOT_BY_TX for configured tx points
```

Every canonical block writes `ROOT_BY_BLOCK`, including empty blocks. If no domain root changes, root hash may equal parent, but record/progress still advance.

`writeSetHash` is recommended:

```text
H("tron.archive.writeSet.v1" || blockNum || blockHash || ordered domain/key/after hashes)
```

It lets rebuild verifier detect root built from a different write-set even if blockNum matches.

## 12. Transaction-level root support

Default:

```text
persistTxRoots = false
ROOT_BY_BLOCK persisted for BLOCK_END
rootAtTxNum(txNum) supported on demand
```

`rootAtTxNum(txNum)` strategy:

1. Find containing `BlockTxNumRange`.
2. If `txNum == finalizeTxNum`, return `ROOT_BY_BLOCK(blockNum)`.
3. Else load nearest root checkpoint: parent block root or optional tx checkpoint.
4. Replay `CHANGESET` from checkpoint txNum+1 to target txNum through commitment tree in memory.
5. Return transient root record with `persisted=false`.

If `persistTxRoots=true`:

- S11 may persist `ROOT_BY_TX(txNum)` after every logical tx.
- If implementation has not landed this mode, config must fail fast.
- Logical tx includes `BLOCK_PREPARE`、all `USER_TX(txIndex)`、`BLOCK_FINALIZE`。

This satisfies transaction-level state tree semantics without forcing first PR to store every tx root.

## 13. Unwind

`DefaultArchiveService.unwindBlock(oldHeadBlock)` should stage temporal and root unwind in one batch:

```text
batch = archiveRawStore.newBatch()
temporalStore.stageUnwindBlock(blockNum, blockHash, batch)
if commitment enabled:
  commitmentBuilder.stageUnwindBlock(blockNum, blockHash, batch)
archiveRawStore.updateByBatch(batch.toRawMap())
```

`stageUnwindBlock`:

```text
progress = COMMITMENT_META(rootProgress)
require progress.appliedBlockNum == blockNum
require progress.appliedBlockHash == blockHash
rootRecord = ROOT_BY_BLOCK(blockNum)
require rootRecord.blockHash == blockHash

parentBlockNum = blockNum - 1
parentRootRecord = ROOT_BY_BLOCK(parentBlockNum), unless genesis/bootstrap

for each domain current:
  restore ROOT_CURRENT(domain) to parentRootRecord.domainRoots[domain].domainRoot
restore ROOT_CURRENT(global) to parentRootRecord.globalRoot
delete ROOT_BY_BLOCK(blockNum)
delete ROOT_BY_TX rows in block range if persistTxRoots
put COMMITMENT_META(rootProgress) for parent
```

Content-addressed `COMMITMENT_NODE` rows are not deleted in P0. They are immutable and can be shared by old/new roots; GC is a later retention task.

If parent root record is missing, mark `REPAIR_REQUIRED` and throw. Do not guess root from current latest Store during hot unwind.

## 14. Startup and rebuild verifier

When commitment enabled, startup verifier must check both temporal and commitment progress:

```text
temporalProgress.appliedBlockNum == rootProgress.appliedBlockNum
temporalProgress.appliedBlockHash == rootProgress.appliedBlockHash
ROOT_BY_BLOCK(rootProgress.appliedBlockNum) exists
RootRecord.globalRoot == rootProgress.globalRoot
RootRecord.asOfTxNum == rootProgress.asOfTxNum
RootRecord.registryChecksum == current registry checksum
ROOT_CURRENT(global).rootHash == rootProgress.globalRoot
```

Latest rebuild verifier:

```text
scan archive LATEST table for domains with RootPolicy.IN_GLOBAL_ROOT
decode domain/key/value via registry codecs
build each domain tree from empty using sorted path32 updates
build global tree from domain roots
compare with ROOT_BY_BLOCK(progress.appliedBlockNum).globalRoot
```

Rules:

- Rebuild uses archive `LATEST`, not java-tron latest Store.
- If mismatch, write `COMMITMENT_META(rootProgress).status=REPAIR_REQUIRED` and fail fast in strict mode.
- Historical rebuild from changesets can be added later; latest rebuild is P0 verifier.

## 15. Proof/debug boundary

S10 stores enough node records for future proof generation, but public proof/debug API is S14.

Allowed future APIs:

```text
debug_getArchiveRoot
debug_getArchiveProof
debug_verifyArchiveProof
```

Not allowed in S10/S11:

- Do not implement fake Ethereum `eth_getProof`.
- Do not claim archive proof is anchored in TRON block header.
- Do not change `BlockResult.stateRoot`.

## 16. Patch 分片

### S10a：config + algorithm descriptor

Add commitment config, `ArchiveCommitmentAlgorithm`, `ArchiveRootCoverage`, `NoopArchiveCommitmentBuilder`.

Tests:

- default disabled。
- commitment enabled requires archive temporal enabled。
- `persistTxRoots=true` fail-fast if not supported。

### S10b：RootKeyCodec and records

Add `RootKeyCodec`、`ArchiveRootRecord`、`DomainRootRecord`、`CurrentRootRecord`、`CommitmentProgressRecord` codecs。

Tests:

- root keys sort by block/tx numeric order。
- block hash/asOfTxNum/registry checksum round-trip。
- unknown algorithm/tree kind rejected。

### S10c：CommitmentHash and empty chain

Define domain-separated hash and empty roots。

Tests:

- empty root deterministic。
- archive empty root differs from `Hash.EMPTY_TRIE_HASH`。
- domain/global branch hash separation。

### S10d：content-addressed SMT

Implement `SparseMerkleArchiveCommitmentTree` and node codecs。

Tests:

- update order independence。
- put/update/delete/tombstone。
- hash collision guard。
- branch nodes are content-addressed and defensive copied。

### S11a：RootValueNormalizer

Convert `DomainWrite` to rooted commitment updates via registry。

Tests:

- rooted domains included。
- history-only/excluded domains ignored。
- storage-row raw write ignored。
- dynamic properties allowlist enforced。

### S11b：stageBlockEnd

Integrate builder with shared `ArchiveBatch`。

Tests:

- block with writes creates domain/global root。
- empty block writes root record and advances progress。
- no-op writes do not change root but still write block root record。
- temporal rows and root rows are staged in one batch。

### S11c：unwind

Implement root unwind with block hash validation。

Tests:

- restore current root to parent。
- blockHash mismatch marks repair。
- missing parent root marks repair。
- content-addressed nodes remain readable after unwind。

### S11d：DefaultArchiveService integration

Refactor commit/unwind to `temporal stage + commitment stage + one batch commit`。

Tests:

- normal block commit flushes temporal and root rows atomically。
- archive apply failure after canonical commit fail-fast。
- fork replay/recovery uses same helper。
- eraseBlock after `fastPop()` unwinds root and temporal together。

### S11e：rebuild verifier

Implement latest rebuild from `LATEST`。

Tests:

- rebuild root equals stored root。
- tampered latest/root/node triggers repair required。
- registry checksum mismatch fails。

## 17. 编码检查清单

- [ ] Archive commitment disabled by default。
- [ ] Commitment enable requires archive temporal enable。
- [ ] No call to `BlockCapsule.setAccountStateRoot()`。
- [ ] No write to `txTrieRoot`。
- [ ] No dependency from `chainbase` archive core to `framework TrieImpl`。
- [ ] `Hash.EMPTY_TRIE_HASH` not used as archive empty root。
- [ ] Updates sorted by `path32` then `domainId/canonicalKey`。
- [ ] Rooted domains come only from `ArchiveDomainRegistry.rootPolicy`。
- [ ] Root record contains algorithm、coverage、schema、registry checksum。
- [ ] Branch/leaf nodes are persisted in `COMMITMENT_BRANCH` in the same batch as root records。
- [ ] Temporal rows and root rows commit in one archive DB batch。
- [ ] Unwind validates `(blockNum, blockHash)`。
- [ ] Rebuild verifier scans archive `LATEST`, not canonical Store。
- [ ] JSON-RPC `BlockResult.stateRoot` remains header accountStateRoot。

## 18. 建议验证命令

文档阶段不需要运行。进入编码后，优先跑 focused tests：

```bash
./gradlew :chainbase:test --tests '*CommitmentHashTest'
./gradlew :chainbase:test --tests '*RootKeyCodecTest'
./gradlew :chainbase:test --tests '*ArchiveRootRecordCodecTest'
./gradlew :chainbase:test --tests '*SparseMerkleArchiveCommitmentTreeTest'
./gradlew :chainbase:test --tests '*DefaultArchiveCommitmentBuilderTest'
./gradlew :framework:test --tests '*ArchiveCommitmentIntegrationTest'
./gradlew :framework:test --tests '*ArchiveCommitmentVerifierTest'
./gradlew :framework:test --tests '*BlockResultArchiveRootRegressionTest'
```

合并前按 java-tron 规则跑：

```bash
./gradlew lint
./gradlew build
```
