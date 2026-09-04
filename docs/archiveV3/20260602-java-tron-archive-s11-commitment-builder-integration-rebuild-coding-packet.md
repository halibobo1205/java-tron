# java-tron Archive S11：CommitmentBuilder Integration + Rebuild Verifier 编码执行包

日期：2026-06-02

> 2026-06-03 更新：本文是旧 `a79693e450` S11 执行包。当前 `4e80f8ffa9a2` 的 S10/S11 编码入口请看 [java-tron Archive S10/S11：CommitmentBuilder 4e80 编码执行包](./20260603-java-tron-archive-s10-s11-commitment-builder-4e80-coding-packet.md)，本文只保留作历史设计参考。

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

前置执行包：

- [S4 ArchiveWriteCollector 编码执行包](./20260602-java-tron-archive-s4-write-collector-coding-packet.md)
- [S5 Contract Storage Semantic Hook 编码执行包](./20260602-java-tron-archive-s5-contract-storage-semantic-coding-packet.md)
- [S6 ArchiveRawStore + Temporal Codecs 编码执行包](./20260602-java-tron-archive-s6-raw-store-temporal-codecs-coding-packet.md)
- [S7 Temporal Commit / GetAsOf / Unwind / Startup 编码执行包](./20260602-java-tron-archive-s7-temporal-commit-unwind-startup-coding-packet.md)
- [S8 ArchiveStateReader Core 编码执行包](./20260602-java-tron-archive-s8-state-reader-core-coding-packet.md)
- [S10 Sparse Merkle Tree Core + Root Codecs 编码执行包](./20260602-java-tron-archive-s10-sparse-merkle-root-codecs-coding-packet.md)

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

S11 对应 PR7 后半段：

```text
BlockWriteSet
  -> temporalStore.stageApplyBlock(...)
  -> DefaultCommitmentBuilder.stageBlockEnd(...)
  -> one ArchiveBatch flush
  -> ROOT_DOMAIN / ROOT_BLOCK / ROOT_CURRENT / rootProgress
  -> unwind + latest rebuild verifier
```

S11 不再重新设计 sparse tree。它消费 S10 提供的：

- `RootKeyCodec`
- `RootRecordCodec`
- `CommitmentNodeCodec`
- `CommitmentHash`
- `SparseMerkleTree`
- `RootValueNormalizer`
- `LeafMetadataGuard`

交付边界：

| 范围 | S11 是否交付 | 说明 |
| --- | --- | --- |
| `CommitmentBuilder` API | 是 | block root、domain root、unwind、integrity |
| `DefaultCommitmentBuilder.stageBlockEnd` | 是 | 从 `BlockWriteSet` 发布 block-end sidecar root |
| temporal/root 同批提交 | 是 | `DefaultArchiveService.commitBlock/unwindBlock` 改为 single `ArchiveBatch` |
| `ROOT_BLOCK` / `ROOT_DOMAIN` 写入 | 是 | 空块也写 `ROOT_BLOCK` |
| `ROOT_CURRENT` 维护 | 是 | domain/global current pointer |
| `COMMITMENT_META(rootProgress)` | 是 | startup/integrity 依据 |
| hot unwind root restore | 是 | 删除 block root rows，恢复 previous current |
| latest rebuild verifier | 是 | 从 `LATEST` 重建 latest root 并比较 |
| historical block rebuild | 接口可定义 | P0 不要求生产实现 |
| per-tx root persistence | 默认否 | `persistTxRoots=false`；若配置 true 但未实现，fail fast |
| proof/debug API | 否 | PR9/S14 做 |
| concurrent rebuild/update | 否 | 后续性能 PR |

核心原则：

```text
BlockWriteSet is the only hot-path input.
Temporal rows and root rows flush in the same physical archive DB batch.
Root progress must never advance without temporal progress.
Temporal progress must not advance in the same batch without root progress when commitment is enabled.
Archive root is ARCHIVE_SIDECAR and never writes block header roots.
```

## 2. java-tron 当前源码事实

### 2.1 本地源码没有 archive package

当前本地 java-tron 源码中没有 `org.tron.core.archive` 包。S11 仍是新增 archive sidecar 代码和 service wiring，不是修改已有 archive 实现。

这意味着 S11 落地时要沿用 S1-S10 已规划的新文件路径，不要假设本地已有 `ArchiveService/ArchiveTemporalStore/CommitmentBuilder` 可以直接改。

### 2.2 Normal block apply 的安全接入点

`/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/db/Manager.java:1261-1383` 是 normal `pushBlock` 的核心 canonical apply/commit 区段。

关键流程：

| 源码位置 | 事实 | S11 结论 |
| --- | --- | --- |
| `BlockCapsule.java:233-244` | `validateMerkleRoot()` 校验 tx Merkle root | archive 不接入交易 Merkle root 校验 |
| `Manager.java:1374-1376` | normal path 在 `try (ISession tmpSession = revokingStore.buildSession())` 内 `applyBlock(newBlock, txs)`，随后 `tmpSession.commit()` | `archiveService.commitBlock()` 必须在 `tmpSession.commit()` 成功后 |
| `Manager.java:1377-1380` | apply/commit 失败会 remove khaos block 并抛出 | archive 必须 `abortBlock()`，不能留下 pending write-set |
| `Manager.java:1383` | `blockTrigger` 在 canonical commit 后 | archive root flush 应在 block trigger 前完成，避免 RPC/event 看到 canonical head 但 archive behind |

S7 已定义 helper 形态：

```java
archiveService.beginBlock(newBlock);
try (ISession tmpSession = revokingStore.buildSession()) {
  applyBlock(newBlock, txs);
  tmpSession.commit();
}
archiveService.commitBlock();
```

S11 不再建议直接在 `Manager.pushBlock` 内塞 root 逻辑。若 S7 helper 已落地，只改 `DefaultArchiveService.commitBlock()` 内部。

### 2.3 Fork switch 会 erase 后 replay

`/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/db/Manager.java:1094-1206` 是 `switchFork`：

| 源码位置 | 事实 | S11 结论 |
| --- | --- | --- |
| `Manager.java:1124-1133` | fork old branch 非空时循环 `eraseBlock()` | archive temporal/root 必须跟随每次 erase unwind |
| `Manager.java:1142-1144` | replay 新 branch 时 `applyBlock(...setSwitch(true))` 后 `tmpSession.commit()` | replay apply 也必须走同一个 archive begin/commit helper |
| `Manager.java:1170-1182` | fork replay 失败后切回 old branch，再次 `eraseBlock()`/`applyBlock()` | S11 root rows 必须按 `blockNum + blockHash` 校验，不能只按 blockNum 恢复 |

Erigon 的 block-hash-aware changeset 路由正是为这类 fork bounce 服务。java-tron S11 的 `stageUnwindBlock(blockNum, blockHash, batch)` 必须校验 `RootRecord.blockHash`。

### 2.4 eraseBlock 的 unwind 接入点

`/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/db/Manager.java:1017-1024`：

```text
oldHeadBlock = getBlockById(latestBlockHeaderHash)
khaosDb.pop()
revokingStore.fastPop()
```

S7 已要求改成：

```java
BlockCapsule oldHeadBlock = chainBaseManager.getBlockById(latestHash);
khaosDb.pop();
revokingStore.fastPop();
archiveService.unwindBlock(oldHeadBlock);
```

S11 只改变 `archiveService.unwindBlock(oldHeadBlock)` 的内部：

```text
temporalStore.stageUnwindBlock(blockNum, blockHash, batch)
commitmentBuilder.stageUnwindBlock(blockNum, blockHash, batch)
rawStore.updateByBatch(batch.toRawMap())
```

如果 `fastPop` 失败，不 unwind archive。若 archive unwind 失败，canonical DB 已经回退，必须抛出并让 startup verifier 下次报告 repair，不允许静默继续处理新区块。

### 2.5 BlockWriteSet 是 root 的唯一热路径输入

S4 已明确：

```text
BlockWriteSet
  TxWriteSet(USER_TX txNum=...)
    DomainWrite(ACCOUNT/CONTRACT/CODE/CONTRACT_STORAGE/DYNAMIC_PROPERTIES ...)
```

并且 S4 结论是：

```text
BlockWriteSet 是后续 temporal/root 的唯一写输入，不能让 S6/S7 再回头扫 canonical Store。
```

S11 的 `stageBlockEnd` 只能消费 `BlockWriteSet`。`CommitmentRebuilder` 可以慢速扫描 `LATEST`，但 normal block apply 不能扫描 latest store 重算 root。

## 3. Erigon 源码对照结论

### 3.1 Block boundary commitment 必须按 block 归属

`/Users/boson/GolandProjects/erigon/execution/stagedsync/committer.go:99-109` 说明：当需要 changeset/reorg 支持时，必须每个 block boundary 计算 commitment。批量 fold 多个 block 会把 branch delta 合并到最后一个 block changeset，破坏 per-block unwind。

java-tron S11 对应规则：

- P0 只发布 `BLOCK_END(blockNum)` root。
- 每个 canonical block 都写 `ROOT_BLOCK(blockNum)`，包括空块。
- `ROOT_CURRENT.latestBlockNum` 每个 block 都推进，即使 root hash 未变化。
- 不允许跳过无写 block，否则 `getRoot(BLOCK_END(N))` 必须向前搜索，语义不再精确。

### 3.2 Fork bounce 需要 block hash 参与路由

`/Users/boson/GolandProjects/erigon/execution/stagedsync/committer.go:459-520` 的 `computeWithBlockAccumulator` 使用 `(BlockNum, BlockHash)` 找 changeset，注释说明 number-only lookup 会在 fork bounce 后把 commitment state 写到错误 block changeset。

java-tron S11 对应规则：

```text
ROOT_BLOCK key 仍按 blockNum 查 current canonical root
RootRecord value 必须保存 blockHash
stageUnwindBlock(blockNum, blockHash) 必须校验 blockHash
rootProgress 也必须保存 blockHash
```

不要在 unwind/repair 中只按 `blockNum` 猜 root。

### 3.3 Integrity 不是只看 root row 存在

`/Users/boson/GolandProjects/erigon/db/integrity/commitment_integrity.go:143-208` 做了这些检查：

- commitment state row 是否存在。
- state row 覆盖的 txNum range 是否匹配 file range。
- root txNum 是否落在 block min/max txNum 内。
- block header root 是否匹配。

`/Users/boson/GolandProjects/erigon/db/integrity/commitment_integrity.go:211-270` 又用独立 state reader seek/recompute root，比对 verified root。

java-tron S11 对应：

- startup 不能只看 `COMMITMENT_META(rootProgress)`。
- 必须同时检查 `ROOT_BLOCK(progress.appliedBlockNum)`、`RootRecord.blockHash`、`RootRecord.asOfTxNum`、`BlockTxNumRange`、`registryChecksum`、`ROOT_CURRENT`。
- latest rebuild verifier 必须独立从 `LATEST` 重新构造 root，再比对 stored `ROOT_BLOCK`。

### 3.4 Rebuild 可以慢，但必须独立于 hot path

`/Users/boson/GolandProjects/erigon/db/state/squeeze.go:921-1012` rebuild commitment files 时，从 account/storage file stream 生成 key iterator，并设置 `FilesOnlyStateReader`。

`/Users/boson/GolandProjects/erigon/db/state/squeeze.go:1089-1112` 的 shard rebuild 通过 touch key 后 `ComputeCommitment(..., saveState=true)` 生成 root。

java-tron S11 P0 采用更简单但等价的独立验证路线：

```text
scan archive LATEST table for rooted domains
  -> normalize value
  -> build domain SparseMerkleTree from empty
  -> build global SparseMerkleTree from domain roots
  -> compare stored ROOT_BLOCK(progress.appliedBlockNum)
```

这个 rebuild 不在 block apply 热路径运行。

### 3.5 Unwind 边界必须受 changeset/root 约束

`/Users/boson/GolandProjects/erigon/db/rawdb/rawtemporaldb/accessors_commitment.go:12-36` 通过 changeset 和 latest commitment 判断可 unwind 边界。

java-tron S11 对应：

- `stageUnwindBlock` 只允许 unwind 当前 root progress 指向的 block。
- 如果 root progress behind/ahead temporal progress，返回 repair required，不继续猜测。
- content-addressed `ROOT_NODE` 不删；只恢复 `ROOT_CURRENT`。

## 4. 关键设计决定

### 4.1 S11 改 `DefaultArchiveService`，不扩大 Manager 改动

如果 S7 已落地 helper，S11 不再改 `Manager` 的正常路径和 fork replay路径。只把：

```text
temporalStore.applyBlock(blockWriteSet)
```

重构为：

```text
ArchiveBatch batch = rawStore.newBatch()
temporalStore.stageApplyBlock(blockWriteSet, batch)
commitmentBuilder.stageBlockEnd(blockWriteSet, batch)
rawStore.updateByBatch(batch.toRawMap())
```

unwind 同理。

如果 S7 helper 尚未落地，S11 不能只改 normal `pushBlock`。fork replay 和 recovery replay 也必须走同一 helper。

### 4.2 `commitment.enable` 依赖 temporal store

S11 root 构建依赖：

- `BlockWriteSet`。
- `ArchiveBatch`。
- `LATEST/HISTORY/CHANGESET/TXNUM_BLOCK`。
- `ArchiveProgress`。

配置规则：

| 配置 | 行为 |
| --- | --- |
| `archive.enable=false` | commitment no-op |
| `archive.temporal.enable=false` + `commitment.enable=true` | startup fail fast |
| `commitment.enable=false` | temporal 正常，root rows 不写 |
| `commitment.persistTxRoots=true` 但 S11 未实现 tx roots | startup fail fast，不能静默忽略 |

### 4.3 Root progress 是独立 meta

S11 新增：

```text
COMMITMENT_META("rootProgress") -> CommitmentProgressRecord
```

`CommitmentProgressRecord`：

```text
u32 schemaVersion
u16 algorithmId
u64 appliedBlockNum
u32 blockHashLen | blockHash
u64 asOfTxNum
u32 registryChecksumLen | registryChecksum
u32 globalRootLen | globalRoot
u8  status              // 1 = OK, 2 = REPAIR_REQUIRED
u32 messageLen | messageUtf8
```

规则：

- 正常 commit 只写 `status=OK`。
- `REPAIR_REQUIRED` 只由 verifier/repair 标记，不由 hot path吞错后继续写。
- `blockHash` 必须等于 `ROOT_BLOCK(appliedBlockNum).blockHash`。
- `asOfTxNum` 必须等于 `ROOT_BLOCK(appliedBlockNum).asOfTxNum`。

### 4.4 Rooted domains

S11 使用 registry 过滤：

| RootPolicy | Domain tree | Global tree |
| --- | --- | --- |
| `IN_GLOBAL_ROOT` | 写 `ROOT_DOMAIN` | 写 global leaf |
| `DOMAIN_ROOT_ONLY` | 写 `ROOT_DOMAIN` | 不写 global leaf |
| `HISTORY_ONLY` | 不写 root | 不写 global leaf |
| `EXCLUDED` | 不写 root | 不写 global leaf |

`ROOT_BLOCK.domainCount` 包含 `IN_GLOBAL_ROOT` 和 `DOMAIN_ROOT_ONLY` domains。

如果 registry 仍把所有 P0 domains 设成 `DOMAIN_ROOT_ONLY`，S11 可以发布 domain roots，但 `globalRoot` 只能按配置标识为 partial/disabled，不能声称是完整 TVM state root。

### 4.5 Empty domain 也要有 root

启用的 rooted domain 即使没有 leaf，也要有：

```text
domainRoot = empty[0] for DOMAIN_TREE(domainId)
leafCount = 0
ROOT_DOMAIN(domainId, blockNum)
ROOT_CURRENT(DOMAIN_TREE, domainId)
```

如果该 domain 是 `IN_GLOBAL_ROOT`，global tree 的 leaf 为：

```text
globalLeafHash(domainId, emptyDomainRoot, 0)
```

这让 `globalRoot` 明确承诺 domain 覆盖范围，而不是“只包含有写入的 domain”。

### 4.6 Same block 同 key 多次写只看 final after-value

S11 block-end root 默认不持久化 tx roots，因此：

```text
same block same (domainId, canonicalKey)
  first beforeValue ignored by root builder
  final afterValue is root input
```

Temporal store 仍按 S7 保存每 tx history/change。Root builder 只 collapse 到 block-end final value。

如果未来 `persistTxRoots=true`：

- 必须按 tx 顺序逐步更新 tree。
- 必须写每个 `TX_AFTER(txNum)` 的 `ROOT_TX`。
- 不能用 block-end final values 伪造 tx root。

S11 P0 若不实现 tx roots，配置 true 必须 fail fast。

## 5. 代码落点总表

### 5.1 修改 S7/S10 已有文件

| java-tron 文件 | 动作 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java` | commit/unwind 改为 temporal + commitment 同批 stage |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveTemporalStore.java` | 增加 `stageApplyBlock` / `stageUnwindBlock` |
| `chainbase/src/main/java/org/tron/core/archive/store/DefaultArchiveTemporalStore.java` | 原 `applyBlock/unwindBlock` 内部拆成 stage + flush |
| `chainbase/src/main/java/org/tron/core/archive/ArchiveStartupVerifier.java` | 增加 commitment root progress/schema/integrity 检查 |
| `chainbase/src/main/java/org/tron/core/archive/commitment/RootKeyCodec.java` | 增加 `COMMITMENT_META("rootProgress")` helper |

### 5.2 新增 commitment integration 类

```text
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentBuilder.java
chainbase/src/main/java/org/tron/core/archive/commitment/DefaultCommitmentBuilder.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentUpdate.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentUpdateBatch.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentProgressRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentProgressCodec.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentIntegrityReport.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentIntegrityScope.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentIntegrityStatus.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentRebuilder.java
chainbase/src/main/java/org/tron/core/archive/commitment/DefaultCommitmentRebuilder.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentRootStore.java
chainbase/src/main/java/org/tron/core/archive/commitment/DefaultCommitmentRootStore.java
```

## 6. Patch 1：ArchiveTemporalStore stage API

S7 的 `applyBlock` 当前会创建 batch 并 flush。S11 必须拆成：

```java
public interface ArchiveTemporalStore {
  void applyBlock(BlockWriteSet blockWriteSet);

  void stageApplyBlock(BlockWriteSet blockWriteSet, ArchiveBatch batch);

  void unwindBlock(long blockNum, byte[] blockHash);

  void stageUnwindBlock(long blockNum, byte[] blockHash, ArchiveBatch batch);

  ArchiveProgress progress();
}
```

实现规则：

- `applyBlock(block)` 变成兼容 wrapper：

```java
ArchiveBatch batch = rawStore.newBatch();
stageApplyBlock(block, batch);
rawStore.updateByBatch(batch.toRawMap());
```

- `unwindBlock(blockNum, blockHash)` 同理。
- `stageApplyBlock` 不 flush。
- `stageUnwindBlock` 不 flush。
- `stage*` 可以读 raw store，但同一个 key 如果被前面 stage 过，必须考虑 batch overlay。

这样 PR5/S7 单测仍能跑，S11 的 `DefaultArchiveService` 可以把 temporal 和 commitment 放同一批。

## 7. Patch 2：CommitmentBuilder API

```java
public interface CommitmentBuilder {
  boolean isEnabled();

  void stageBlockEnd(BlockWriteSet blockWriteSet, ArchiveBatch batch)
      throws CommitmentException;

  void stageUnwindBlock(long blockNum, byte[] blockHash, ArchiveBatch batch)
      throws CommitmentException;

  Optional<RootRecord> getBlockRoot(long blockNum);

  Optional<DomainRootRecord> getDomainRoot(ArchiveDomain domain, long blockNum);

  Optional<CommitmentProgressRecord> rootProgress();

  RootRecord rebuildBlockEnd(long blockNum) throws CommitmentException;

  CommitmentIntegrityReport checkIntegrity(CommitmentIntegrityScope scope);
}
```

Disabled implementation：

```java
public final class NoopCommitmentBuilder implements CommitmentBuilder {
  public boolean isEnabled() { return false; }
  public void stageBlockEnd(BlockWriteSet blockWriteSet, ArchiveBatch batch) {}
  public void stageUnwindBlock(long blockNum, byte[] blockHash, ArchiveBatch batch) {}
  public Optional<RootRecord> getBlockRoot(long blockNum) { return Optional.empty(); }
}
```

`DefaultArchiveService` should depend on the interface, not branch on implementation class.

## 8. Patch 3：DefaultArchiveService commit 同批提交

S11 `commitBlock()`：

```java
public void commitBlock() {
  if (!isEnabled() || !archiveConfig.getTemporal().isEnable()) {
    clearPending();
    return;
  }

  BlockWriteSet blockWriteSet = writeCollector.commitBlock();
  ArchiveBatch batch = rawStore.newBatch();
  boolean staged = false;
  try {
    temporalStore.stageApplyBlock(blockWriteSet, batch);
    if (commitmentBuilder.isEnabled()) {
      commitmentBuilder.stageBlockEnd(blockWriteSet, batch);
    }
    rawStore.updateByBatch(batch.toRawMap());
    txNumIndex.completeBlock(blockWriteSet.toBlockRange());
    staged = true;
  } finally {
    executionContext.clear();
    if (!staged) {
      writeCollector.abortBlock();
      txNumIndex.abortBlock();
    }
  }
}
```

要求：

- `temporalStore.stageApplyBlock` 先 stage state/txnum/progress。
- `commitmentBuilder.stageBlockEnd` 后 stage root/domain/current/rootProgress。
- `rawStore.updateByBatch` 是唯一 flush。
- `txNumIndex.completeBlock` 在 flush 后；persistent wrapper 可以 no-op。
- archive exception 不吞，向上抛。
- finally 必须清理 thread-local/pending context。

如果 commitment disabled：

```text
temporalStore.stageApplyBlock -> flush
no root rows
```

如果 temporal disabled：

```text
commitmentBuilder must be disabled by config validation
```

## 9. Patch 4：DefaultArchiveService unwind 同批提交

S11 `unwindBlock(oldHeadBlock)`：

```java
public void unwindBlock(BlockCapsule oldHeadBlock) {
  if (!isEnabled() || !archiveConfig.getTemporal().isEnable()) {
    return;
  }

  long blockNum = oldHeadBlock.getNum();
  byte[] blockHash = copy(oldHeadBlock.getBlockId().getBytes());
  ArchiveBatch batch = rawStore.newBatch();
  temporalStore.stageUnwindBlock(blockNum, blockHash, batch);
  if (commitmentBuilder.isEnabled()) {
    commitmentBuilder.stageUnwindBlock(blockNum, blockHash, batch);
  }
  rawStore.updateByBatch(batch.toRawMap());
  txNumIndex.reloadFromProgress();
}
```

要求：

- 输入 block 必须是 canonical fastPop 前取到的 old head。
- temporal/root unwind 同一个 batch。
- `stageUnwindBlock` 失败时不 flush。
- root node rows 不删除。
- root progress 恢复到 previous block。
- archive unwind 失败不吞。

## 10. Patch 5：CommitmentUpdateBatch

输入：

```text
BlockWriteSet
  -> TxWriteSet[]
    -> DomainWrite[]
```

输出：

```java
public final class CommitmentUpdate {
  private final int domainId;
  private final byte[] canonicalKey;
  private final byte[] path32;
  private final byte[] keyHash;
  private final Optional<byte[]> valueHash;
  private final Optional<byte[]> leafHash;
}
```

Build flow：

```text
for tx in blockWriteSet.txWriteSets:
  for write in tx.writes:
    descriptor = registry.get(write.domain)
    if rootPolicy not rooted: continue
    normalized = rootValueNormalizer.normalize(descriptor, write.key, write.afterValue)
    collapse map[(domainId, canonicalKey)] = normalized final value

for each collapsed entry:
  path32 = CommitmentHash.domainPath(...)
  keyHash = CommitmentHash.keyHash(...)
  if normalized delete:
    valueHash = empty
    leafHash = empty
  else:
    valueHash = CommitmentHash.valueHash(...)
    leafHash = CommitmentHash.domainLeafHash(path32, valueHash)
```

Sorting：

```text
domain updates:
  group by domainId ASC
  inside group sort by path32 ASC, canonicalKey ASC

global updates:
  sort by globalPath ASC, domainId ASC
```

Do not use:

- tx order for block-end root.
- raw store iteration order.
- `HashMap` iteration.
- enum order.
- canonical key order alone.

## 11. Patch 6：stageBlockEnd algorithm

### 11.1 Inputs

`blockWriteSet` must provide:

```text
blockNum
blockHash
parentBlockHash
firstTxNum
lastTxNum
txWriteSets
```

`asOfTxNum` for `BLOCK_END(blockNum)`:

```text
asOfTxNum = blockWriteSet.lastTxNum() + 1
```

Use the same convention as S1/S2 txNum range. Do not recompute txNum from transaction count in S11.

### 11.2 Validation

Before writing root rows:

1. `commitment.enable` is true.
2. algorithm descriptor exists or archive is empty/bootstrap state.
3. registry checksum matches current descriptor set.
4. temporal stage belongs to same block range.
5. `rootProgress.appliedBlockNum == blockNum - 1`, unless this is first commitment block/bootstrap.
6. if existing archive temporal progress is ahead but root progress missing, throw `REPAIR_REQUIRED`.
7. if `persistTxRoots=true` and tx roots unsupported, throw config exception before node startup.

### 11.3 Current root loading

For each rooted domain:

```text
current = ROOT_CURRENT(algorithmId, DOMAIN_TREE, domainId)
if current missing:
  if allowed bootstrap: root = empty[0], leafCount = 0
  else: REPAIR_REQUIRED
```

For global:

```text
current = ROOT_CURRENT(algorithmId, GLOBAL_TREE, 0)
if current missing:
  bootstrap global from all IN_GLOBAL_ROOT domain roots
```

Do not read `BlockHeader.raw.accountStateRoot`.

### 11.4 Domain update

For each domain update group:

```text
current = load domain CurrentRootRecord
tree = SparseMerkleTree(current.root)
leafCount = current.leafCount

for update in sorted group:
  leafAction = leafMetadataGuard.stageLeaf(...)
  tree.update(update.path32, leafAction.leafHashForTree, batch)
  leafCount += leafAction.leafCountDelta

newDomainRoot = tree.rootHash()
stage ROOT_CURRENT(DOMAIN_TREE, domainId)
```

Domains without updates still get `ROOT_DOMAIN(domain, blockNum)` and `ROOT_CURRENT` with `latestBlockNum/asOfTxNum` advanced.

### 11.5 Global update

For each `IN_GLOBAL_ROOT` domain:

```text
globalPath = CommitmentHash.globalPath(algorithmId, domainId)
globalLeafHash = CommitmentHash.globalLeafHash(algorithmId, domainId, domainRoot, leafCount)
```

Update global tree when:

- global current is missing/bootstrap; or
- domainRoot changed; or
- leafCount changed; or
- rootPolicy changed into `IN_GLOBAL_ROOT` during controlled registry migration.

P0 should fail on registry checksum mismatch instead of silently handling live rootPolicy migration.

### 11.6 Root records

Stage for every rooted domain:

```text
ROOT_DOMAIN(domainId, blockNum) -> DomainRootRecord
```

Then stage:

```text
ROOT_BLOCK(blockNum) -> RootRecord
ROOT_CURRENT(GLOBAL_TREE, 0) -> CurrentRootRecord
ROOT_CURRENT(DOMAIN_TREE, domainId) -> CurrentRootRecord
COMMITMENT_META("rootProgress") -> CommitmentProgressRecord
```

Ordering inside `ArchiveBatch` is not semantic, but tests should assert all rows are present before flush.

Empty block rule:

```text
No updates still writes ROOT_DOMAIN for rooted domains,
ROOT_BLOCK(blockNum),
ROOT_CURRENT with latestBlockNum/asOfTxNum advanced,
rootProgress.
```

## 12. Patch 7：stageUnwindBlock algorithm

Input:

```text
blockNum
blockHash
ArchiveBatch batch
```

Strict flow:

```text
progress = read rootProgress
if progress missing and commitment enabled:
  REPAIR_REQUIRED unless no commitment has ever been written

if progress.appliedBlockNum != blockNum:
  REPAIR_REQUIRED

root = read ROOT_BLOCK(blockNum)
if missing:
  ROOT_MISSING
if root.blockHash != blockHash:
  BLOCK_HASH_MISMATCH

batch.delete(ROOT_BLOCK(blockNum))
for domain in root.domainRoots:
  batch.delete(ROOT_DOMAIN(domainId, blockNum))

if persistTxRoots:
  delete ROOT_TX for txNum range from ArchiveTxNumIndex

previous = read ROOT_BLOCK(blockNum - 1)
if previous present:
  restore ROOT_CURRENT from previous
  stage rootProgress(previous)
else if blockNum is commitment activeFromBlock:
  restore/delete current roots according to bootstrap policy
  stage rootProgress before activeFromBlock or delete rootProgress
else:
  REPAIR_REQUIRED
```

Restore rules:

- Restore all domains present in previous `RootRecord`.
- For current registry rooted domains absent from previous record, write empty current only if this is a controlled bootstrap boundary; otherwise `REGISTRY_CHECKSUM_MISMATCH`.
- Restore global current from `previous.globalRoot`.
- Do not delete `ROOT_NODE`.
- Delete `ROOT_LEAF` only if unwinding before activeFromBlock and clearing all commitment state. Normal hot unwind does not update leaf metadata by replaying inverse writes; it restores current roots. If leaf metadata is needed for subsequent forward blocks after unwind, S11 must rebuild active leaf metadata from latest or keep metadata consistent during unwind. P0 recommendation: during `stageUnwindBlock`, also restore `ROOT_LEAF` using temporal changeset inverse for changed rooted keys, or mark `ROOT_LEAF` rebuild required before accepting new forward blocks.

Important leaf metadata decision:

```text
ROOT_CURRENT alone restores root hash, but future incremental updates need ROOT_LEAF(path32)
for leafCount and collision guard.
```

Therefore S11 must choose one of these two P0-safe options:

1. `stageUnwindBlock` replays changed rooted keys in reverse and restores/deletes `ROOT_LEAF(path32)`.
2. `stageUnwindBlock` restores roots, then calls latest leaf metadata rebuild before root progress is marked OK.

Do not leave `ROOT_LEAF` at the unwound-away block while accepting new blocks.

Recommended P0: option 1, because S7 already scans changeset/history for temporal unwind.

## 13. Patch 8：ROOT_LEAF unwind restore

For each changed rooted key in the unwound block range, sorted descending by txNum:

```text
before = HISTORY(domain,key,txNum)
normalizedBefore = rootValueNormalizer.normalize(domain,key,before)
path32/keyHash computed from canonicalKey

if normalizedBefore delete:
  batch.delete(ROOT_LEAF(path32))
else:
  batch.put(ROOT_LEAF(path32), LeafRecord(before))
```

Need collapse by `(domainId, canonicalKey)` so each key is restored once to the value before the earliest unwound tx touching that key.

Algorithm:

```text
restoreMap = LinkedHashMap<DomainKey, Optional<byte[]>>
for changes descending txNum:
  restoreMap[domainKey] = historyBeforeValue(txNum)

for restoreMap entries:
  stage ROOT_LEAF based on normalized before value
```

Then restore `ROOT_CURRENT` from previous root record.

This keeps:

- root hash restored by pointer.
- leaf metadata restored for future incremental updates.
- leafCount in `ROOT_CURRENT` consistent with `ROOT_LEAF` table.

## 14. Patch 9：CommitmentRootStore

S11 should centralize root row reads/writes:

```java
public interface CommitmentRootStore {
  Optional<RootRecord> getBlockRoot(long blockNum);
  Optional<DomainRootRecord> getDomainRoot(int domainId, long blockNum);
  Optional<CurrentRootRecord> getCurrent(TreeKind treeKind, int domainId);
  Optional<CommitmentProgressRecord> getProgress();

  void putBlockRoot(RootRecord record, ArchiveBatch batch);
  void putDomainRoot(DomainRootRecord record, ArchiveBatch batch);
  void putCurrent(CurrentRootRecord record, ArchiveBatch batch);
  void putProgress(CommitmentProgressRecord record, ArchiveBatch batch);

  void deleteBlockRoot(long blockNum, ArchiveBatch batch);
  void deleteDomainRoot(int domainId, long blockNum, ArchiveBatch batch);
}
```

This avoids duplicating key/codec calls across builder, unwinder, verifier, and tests.

All getters must read batch overlay only when explicitly passed a batch. Startup/check methods use raw store only.

## 15. Patch 10：latest rebuild verifier

`CommitmentRebuilder`:

```java
public interface CommitmentRebuilder {
  RootRecord rebuildLatest() throws CommitmentException;

  RootRecord rebuildBlockEnd(long blockNum) throws CommitmentException;
}
```

P0 production implementation:

```text
rebuildLatest():
  progress = temporalStore.progress()
  scan LATEST prefix for every rooted domain
  normalize each latest value
  compute path32/keyHash/valueHash/leafHash
  build domain sparse trees from empty
  build ROOT_LEAF metadata in memory
  build global tree from IN_GLOBAL_ROOT domain roots
  return RootRecord(progress.appliedBlockNum, progress.blockHash, asOfTxNum, roots...)
```

Do not write rows in `rebuildLatest()`; it returns a computed record.

Optional repair command can later:

```text
rebuildLatestAndStageRepair(batch)
```

but S11 startup verifier should not silently rewrite root rows unless explicit repair config is added.

`rebuildBlockEnd(blockNum)`:

- P0 may throw `UNSUPPORTED_OPERATION` for arbitrary historical block.
- Tests can use a small in-memory fixture to rebuild from temporal `getAsOf` if already available.
- PR9/S14 will need real historical/on-demand replay.

## 16. Patch 11：checkIntegrity

`CommitmentIntegrityScope`:

```java
public enum CommitmentIntegrityScope {
  STARTUP_FAST,
  LATEST_ROOT_RECORD,
  LATEST_REBUILD,
  FULL_ROOT_SCAN
}
```

`CommitmentIntegrityStatus`:

```java
OK
COMMITMENT_DISABLED
TEMPORAL_DISABLED
ROOT_SCHEMA_MISSING
ROOT_MISSING
ROOT_AHEAD_OF_ARCHIVE
ROOT_BEHIND_ARCHIVE
ROOT_MISMATCH
CURRENT_MISMATCH
LEAF_METADATA_MISMATCH
ALGORITHM_MISMATCH
REGISTRY_CHECKSUM_MISMATCH
BLOCK_HASH_MISMATCH
REPAIR_REQUIRED
```

`STARTUP_FAST` checks:

1. commitment config valid.
2. algorithm descriptor present and matches.
3. temporal progress present if archive has rows.
4. root progress not ahead of temporal progress.
5. root progress equals temporal progress when commitment enabled.
6. `ROOT_BLOCK(rootProgress.appliedBlockNum)` exists.
7. root block hash/asOfTxNum match progress.
8. `ROOT_CURRENT` rows match latest `ROOT_BLOCK`.
9. registry checksum matches.

`LATEST_REBUILD` additionally:

1. `rebuildLatest()` returns root.
2. computed `RootRecord.globalRoot` equals stored `ROOT_BLOCK.globalRoot`.
3. every computed domain root/leafCount equals stored domain root.

`FULL_ROOT_SCAN` can remain unsupported in P0 or only scan root row codec validity.

## 17. Patch 12：ArchiveStartupVerifier

Startup behavior:

| State | Result |
| --- | --- |
| archive disabled | no-op |
| temporal disabled and commitment disabled | no-op |
| temporal disabled and commitment enabled | fail fast |
| archive temporal empty and root rows missing | OK |
| commitment disabled but root rows exist | warn only |
| commitment enabled and root rows missing while temporal progress exists | `ROOT_MISSING/REPAIR_REQUIRED` |
| root progress behind temporal progress | `ROOT_BEHIND_ARCHIVE` |
| root progress ahead temporal progress | `ROOT_AHEAD_OF_ARCHIVE` |
| algorithm descriptor mismatch | `ALGORITHM_MISMATCH` |
| registry checksum mismatch | `REGISTRY_CHECKSUM_MISMATCH` |
| latest rebuild mismatch | `ROOT_MISMATCH` |

Existing archive DB first enabling commitment:

```text
temporal progress exists
rootProgress missing
commitment.enable=true
=> startup fails with ROOT_MISSING/REPAIR_REQUIRED
```

Do not start writing from empty root on top of a non-empty temporal archive. Require explicit rebuild/bootstrap.

## 18. Tests

### 18.1 Unit：CommitmentUpdateBatch

```text
chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentUpdateBatchTest.java
```

Cover:

- same block same key final after-value wins.
- final after-value delete.
- `DOMAIN_ROOT_ONLY` included in domain root but not global update list.
- `HISTORY_ONLY/EXCLUDED` ignored.
- storage zero normalized to delete.
- deterministic sort by `path32 ASC, canonicalKey ASC`.
- `persistTxRoots=true` unsupported fail fast if not implemented.

### 18.2 Unit：DefaultCommitmentBuilder stageBlockEnd

```text
chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentBuilderBlockEndTest.java
```

Cover:

- empty block writes `ROOT_BLOCK` and `ROOT_DOMAIN`.
- first block bootstraps empty domain roots.
- account write changes domain/global root.
- contract storage zero deletes leaf and decrements leafCount.
- unchanged domain still writes `ROOT_DOMAIN(domain, blockNum)` with advanced asOf.
- `ROOT_CURRENT.latestBlockNum/latestAsOfTxNum` advances even if root unchanged.
- `ROOT_BLOCK.blockHash` equals block id bytes.
- root rows and temporal rows are in one `ArchiveBatch`.

### 18.3 Unit：DefaultArchiveService same-batch commit

```text
chainbase/src/test/java/org/tron/core/archive/DefaultArchiveServiceCommitmentTest.java
```

Cover:

- commitment disabled keeps S7 temporal behavior.
- commitment enabled stages temporal before root and flushes once.
- commitment failure does not flush temporal rows.
- `rawStore.updateByBatch` called exactly once.
- pending context cleared on success/failure.

### 18.4 Unit：Commitment unwind

```text
chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentUnwindTest.java
```

Cover:

- unwind current block deletes `ROOT_BLOCK(blockNum)`.
- deletes `ROOT_DOMAIN(domain, blockNum)`.
- restores `ROOT_CURRENT` from previous `ROOT_BLOCK`.
- restores `ROOT_LEAF` metadata for changed rooted keys.
- keeps `ROOT_NODE` rows.
- blockHash mismatch returns `BLOCK_HASH_MISMATCH`.
- rootProgress mismatch returns `REPAIR_REQUIRED`.

### 18.5 Unit：Commitment rebuild/integrity

```text
chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentRebuilderTest.java
chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentIntegrityTest.java
```

Cover:

- latest rebuild matches incremental root.
- tampered `ROOT_BLOCK` -> `ROOT_MISMATCH`.
- missing `ROOT_CURRENT` -> `CURRENT_MISMATCH`.
- root progress behind temporal progress -> `ROOT_BEHIND_ARCHIVE`.
- root progress ahead temporal progress -> `ROOT_AHEAD_OF_ARCHIVE`.
- registry checksum mismatch -> `REGISTRY_CHECKSUM_MISMATCH`.
- existing temporal archive first enables commitment -> `ROOT_MISSING/REPAIR_REQUIRED`.

### 18.6 Integration：Manager hook

```text
framework/src/test/java/org/tron/core/archive/ArchiveCommitmentIntegrationTest.java
```

Cover:

- normal `pushBlock` with commitment enabled writes `ROOT_BLOCK`.
- fork switch erase restores previous root current.
- replayed switch branch writes new block hash into `RootRecord`.
- archive failure after canonical commit makes verifier report repair on restart.

If this test is too large for S11, keep unit tests mandatory and add integration in follow-up, but do not remove the behavior from the spec.

## 19. Review checklist

- [ ] `DefaultArchiveService.commitBlock` has one archive batch flush.
- [ ] `DefaultArchiveService.unwindBlock` has one archive batch flush.
- [ ] `commitment.enable=true` requires temporal enabled.
- [ ] root rows are not written when commitment disabled.
- [ ] `stageBlockEnd` consumes `BlockWriteSet`, not latest Store scans.
- [ ] empty blocks write `ROOT_BLOCK`.
- [ ] rooted domains with no changes write `ROOT_DOMAIN`.
- [ ] `RootRecord.blockHash` is checked on unwind.
- [ ] `rootProgress` and `ROOT_BLOCK` match.
- [ ] `ROOT_CURRENT` restored on unwind.
- [ ] `ROOT_LEAF` metadata restored or rebuilt on unwind before accepting new blocks.
- [ ] `ROOT_NODE` rows are not deleted by hot unwind.
- [ ] `persistTxRoots=true` is not silently ignored.
- [ ] startup refuses existing temporal archive with missing root progress when commitment enabled.
- [ ] latest rebuild compares independent computed root with stored root.
- [ ] no writes to `BlockHeader.raw.accountStateRoot`.
- [ ] no reads from `TrieImpl` or `AccountStateStoreTrie`.
- [ ] no test skip or `@Ignore`.

## 20. Suggested commands

```bash
./gradlew :chainbase:test --tests '*CommitmentUpdateBatchTest'
./gradlew :chainbase:test --tests '*CommitmentBuilderBlockEndTest'
./gradlew :chainbase:test --tests '*DefaultArchiveServiceCommitmentTest'
./gradlew :chainbase:test --tests '*CommitmentUnwindTest'
./gradlew :chainbase:test --tests '*CommitmentRebuilderTest'
./gradlew :chainbase:test --tests '*CommitmentIntegrityTest'
./gradlew :framework:test --tests '*ArchiveCommitmentIntegrationTest'
./gradlew checkstyleMain checkstyleTest -x generateGitProperties
```

Do not add `@Ignore`, conditional skips, or test matrix exclusions.

## 21. S11 完成定义

S11 完成后必须能证明：

```text
canonical block apply
  -> BlockWriteSet
  -> temporal rows
  -> commitment rows
  -> one ArchiveBatch
  -> ROOT_BLOCK(BLOCK_END)
  -> root current/progress
  -> hot unwind restore
  -> latest rebuild match
```

Concrete acceptance:

1. Commitment disabled 时 S7 temporal behavior 不变。
2. Commitment enabled 时 temporal/root 同批提交。
3. Empty block 有 `ROOT_BLOCK`。
4. Rooted domain 未修改也有 per-block `ROOT_DOMAIN`。
5. Fork/erase unwind 后 `ROOT_CURRENT` 和 `ROOT_LEAF` metadata 可继续 forward。
6. Startup verifier 能识别 missing/ahead/behind/mismatch。
7. Latest rebuild verifier 与 incremental root 一致。
8. 没有触碰共识 header root。

完成 S11 后，PR7 的 root sidecar 才算闭环；后续进入 S12/S13 historical `eth_call`，或 S14 proof/debug API。
