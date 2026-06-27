# java-tron Archive S7：Temporal Commit / GetAsOf / Unwind / Startup 编码执行包

日期：2026-06-02

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

> 2026-06-03 更新：本文是旧 `a79693e450` 编码包。当前 `4e80f8ffa9a2` 的 S6/S7 编码入口请看 [java-tron Archive S6/S7：ArchiveTemporalStore 4e80 编码执行包](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md)，旧行号和部分路径不可直接用于编码。

java-tron 旧文档原始基线：`a79693e450`。

关联文档：

- 当前 4e80 S6/S7 编码入口：[java-tron Archive S6/S7：ArchiveTemporalStore 4e80 编码执行包](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md)
- S6 raw store/schema：[java-tron Archive S6：ArchiveRawStore + Temporal Codecs 编码执行包](./20260602-java-tron-archive-s6-raw-store-temporal-codecs-coding-packet.md)
- PR5 TemporalStore 规格：[java-tron Archive PR5 TemporalStore 代码级实现规格](./20260602-java-tron-archive-pr5-temporal-store-implementation-spec.md)
- 模块 04 patch checklist：[java-tron Archive 模块 04：ArchiveTemporalStore 逐文件 Patch 清单](./20260602-java-tron-archive-module-04-temporal-store-patch-checklist.md)
- 端到端矩阵：[java-tron Archive 端到端实现矩阵与 PR 执行队列](./20260602-java-tron-archive-end-to-end-implementation-matrix.md)
- S4 WriteCollector：[java-tron Archive S4：ArchiveWriteCollector 编码执行包](./20260602-java-tron-archive-s4-write-collector-coding-packet.md)
- S5 Storage semantic hook：[java-tron Archive S5：Contract Storage Semantic Hook 编码执行包](./20260602-java-tron-archive-s5-contract-storage-semantic-coding-packet.md)

## 1. 本文定位

S7 对应 PR5 后半段，也就是 `PR5b`：

```text
BlockWriteSet
  -> DefaultArchiveTemporalStore.applyBlock
  -> getAsOf(domain,key,asOfTxNum)
  -> unwindBlock(blockNum,blockHash)
  -> persistent txNum query wrapper
  -> DefaultArchiveService commit/unwind wiring
  -> ArchiveStartupVerifier
```

S7 只实现 temporal history 的业务流程，不重新定义 S6 的 storage contract：

| 范围 | S7 是否交付 | 说明 |
| --- | --- | --- |
| `ArchiveTemporalStore` API | 是 | temporal store 的读写入口 |
| `DefaultArchiveTemporalStore.applyBlock` | 是 | 把 `BlockWriteSet` 写入 S6 tables |
| `getAsOf` | 是 | before-tx 历史查询原语 |
| `unwindBlock` | 是 | 按 txNum 逆序恢复 latest/history/change/txnum/progress |
| `PersistentArchiveTxNumIndex` | 是 | 作为 temporal store 的 thin wrapper |
| `DefaultArchiveService` 接入 | 是 | canonical commit 后 flush archive sidecar |
| `ArchiveStartupVerifier` | 是 | 检测 canonical DB 与 archive DB 非原子造成的 gap/corrupt |
| 新 key/value codec | 否 | 全部复用 S6 `ArchiveKeyCodec` / value codecs |
| JSON-RPC / StateReader | 否 | S8/S9 做 |
| commitment root | 否 | PR7/S11 做 |
| backfill/cold segment | 否 | P0 不做 |

关键边界：

```text
S7 不能在 canonical revoking session commit 前持久化 archive。
S7 不能在 archive gap/corrupt 时假装 historical query 可用。
S7 不能新增第二套 LATEST/HISTORY/CHANGESET key codec。
```

## 2. java-tron 源码事实

### 2.1 canonical commit / rollback 边界

| java-tron 位置 | 源码事实 | 对 S7 的结论 |
| --- | --- | --- |
| `Manager.java:1374-1377` | normal path 在 `try (ISession tmpSession = revokingStore.buildSession())` 内 `applyBlock(newBlock, txs)`，然后 `tmpSession.commit()` | archive flush 必须在 `tmpSession.commit()` 成功后 |
| `Manager.java:1377-1381` | apply/commit 异常会 remove khaos block 并 rethrow | archive pending block 在异常路径只能 abort，不能落 DB |
| `Manager.java:1142-1149` | switch fork 新分支 replay 也用 `buildSession/applyBlock/commit` | replay 分支也必须走同一 archive commit helper |
| `Manager.java:1180-1182` | fork 失败后恢复旧分支也用 `buildSession/applyBlock/commit` | recovery replay 同样不能漏 archive |
| `Manager.java:1017-1025` | `eraseBlock()` 先拿 `oldHeadBlock`，再 `khaosDb.pop()` 和 `revokingStore.fastPop()` | archive unwind 应在 canonical fastPop 成功后，使用 fastPop 前拿到的 `oldHeadBlock` |
| `SnapshotManager.java:136-138` | `buildSession()` 会 advance snapshot 并增加 active session | block 内 Store writes 处在可回滚 session 中 |
| `SnapshotManager.java:207-219` | `commit()` 关闭当前 session 的回滚能力 | archive commit 只能晚于这里 |
| `SnapshotManager.Session.destroy():607-617` | 未 commit 的 session destroy 会 revoke | S7 abort 只清 pending，不写 sidecar |

### 2.2 block / tx 元数据

| java-tron 位置 | 源码事实 | 对 S7 的结论 |
| --- | --- | --- |
| `BlockCapsule.java:152-159` | `getTransactions()` 返回 block 内交易 list | `txIndex` 用 block 原始交易顺序，不用 `getVerifyTxs` filtered list |
| `BlockCapsule.java:204-210` | `getBlockId()` 用 raw header hash + block number 构造 `BlockId` | block hash 用 `block.getBlockId().getBytes()` |
| `BlockCapsule.java:302-304` | `getNum()` 返回 block header number | `BlockWriteSet.blockNum` 必须和它一致 |
| `BlockCapsule.BlockId:338 / 406-408` | `BlockId` extends `Sha256Hash`，`getNum()` 保存 block number | progress/block range 可保存 block id bytes + block num |
| `Sha256Hash.java:303-308` | `getBytes()` 返回内部数组，不 defensive copy | S7 写 meta/progress 前必须 copy blockHash/txId |
| `TransactionCapsule.java:691-695` | `getTransactionId()` lazy 计算 hash | user tx `txId` 从这里拿 |
| `TransactionCapsule.java:886-887` | `getContractCount()` 来自 raw data contracts | 只用于 S1/S2 tx loop 校验，不影响 S7 schema |

### 2.3 startup head source

| java-tron 位置 | 源码事实 | 对 S7 的结论 |
| --- | --- | --- |
| `ChainBaseManager.java:273-279` | `getHeadBlockId/getHeadBlockNum` 来自 `DynamicPropertiesStore` | startup verifier 可从 ChainBaseManager 读 canonical head |
| `ChainBaseManager.java:325-331` | `getBlockById(hash)` 先查 khaos，再查 block store | verifier 可取 canonical head block 详情 |
| `DynamicPropertiesStore.java:2148-2154` | `getLatestBlockHeaderNumber()` 读取 latest number | progress height 对比来源 |
| `DynamicPropertiesStore.java:2180-2184` | `getLatestBlockHeaderHash()` 返回 `Sha256Hash.wrap(blockHash)` | progress hash 对比来源 |
| `Manager.java:475-523` | `Manager.init()` 初始化 ChainBaseManager/khaosDb head | startup verifier 应在 Manager init 已经能读 canonical head 后执行 |

### 2.4 Erigon 当前源码约束

| Erigon 位置 | 事实 | S7 实现约束 |
| --- | --- | --- |
| `db/state/temporal_mem_batch.go:132-149` | `DomainPut/DomainDel` 写 latest overlay 后写 history | `applyBlock` 必须先更新本 block overlay，再让后续 tx 校验 before-value |
| `db/state/domain.go:321-349` | `PutWithPrev/DeleteWithPrev` 同时记录 before-value 和 after-value | `DomainWrite.beforeValue` 是必须字段，不能在 PR5 降级为可选 |
| `db/state/history.go:368-415` | history key/value 按 `key + txNum` 存 before-value | `HISTORY(domain,key,txNum)` 必须 exact-prefix seek |
| `db/state/domain.go:1384-1412` / `history.go:1209-1222` | `GetAsOf` 查 `>= txNum` 的 history，miss 后读 latest | S7 的 `getAsOf` 只接受 txNum，不接受 blockNum/txId |
| `db/state/aggregator.go:2511-2522` | unwind 按 txNum 目标恢复 domain | S7 的 `unwindBlock` 必须倒序扫描 changeset 并同批恢复 latest/history/txnum/progress |

### 2.5 archive 代码现状

本地 java-tron 当前没有 `org.tron.core.archive` main classes；S1-S7 都是新增能力。S7 不能假设已有 `ArchiveTemporalStore` 或 `ArchiveStartupVerifier` 可修改。

## 3. S7 前置契约

S7 依赖前面 slice 的输出：

| Slice | 前置输出 | S7 如何使用 |
| --- | --- | --- |
| S1/S2 | `ArchiveConfig`、`ArchiveService`、`ArchiveExecutionContext`、`ArchiveTxNumIndex`、`TxNumMeta`、`BlockTxNumRange` | temporal store 写 txnum metadata；service commit/unwind |
| S3 | `ArchiveDomainRegistry`、`ArchiveDomain`、registry checksum | apply 前校验 domain/schema |
| S4 | `ArchiveWriteCollector`、`BlockWriteSet`、`TxWriteSet`、`DomainWrite` | `applyBlock` 的唯一输入 |
| S5 | `CONTRACT_STORAGE` semantic writes | S7 只按 domain/key/value 存，不解析 storage |
| S6 | `ArchiveRawStore`、`ArchiveBatch`、`ArchiveKeyCodec`、`ArchiveValueCodec`、txnum/progress codecs | S7 全部复用，不新增 schema |

S7 的 `BlockWriteSet` 假设：

```text
blockNum
blockHash
registryChecksum
ordered txWriteSets
  TxWriteSet:
    txNum
    TxNumMeta
    ordered DomainWrite list
      domain
      canonicalKey
      beforeValue nullable
      afterValue nullable
```

如果 S4/S5 交出的 write-set 不满足这些条件，S7 必须抛出 archive error，不能修正或猜测。

## 4. 文件落点

S7 新增/修改：

```text
chainbase/src/main/java/org/tron/core/archive/store/ArchiveTemporalStore.java
chainbase/src/main/java/org/tron/core/archive/store/DefaultArchiveTemporalStore.java
chainbase/src/main/java/org/tron/core/archive/store/VersionedValue.java
chainbase/src/main/java/org/tron/core/archive/store/ChangedKey.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveTemporalException.java
chainbase/src/main/java/org/tron/core/archive/txnum/PersistentArchiveTxNumIndex.java
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
framework/src/main/java/org/tron/core/archive/ArchiveStartupVerifier.java
framework/src/main/java/org/tron/core/db/Manager.java
```

S7 不新增：

```text
TemporalKeyCodec.java
ArchiveProgressStore.java
archive-state DB
archive-txnum DB
archive-root DB
```

`ArchiveKeyCodec` 已在 S6 负责 temporal keys；`ArchiveTemporalStore.progress()` 直接读取 S6 `META(progress)` row，不再单独建 `ArchiveProgressStore`。

## 5. Patch 1：ArchiveTemporalStore API

接口：

```java
public interface ArchiveTemporalStore {
  Optional<byte[]> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long asOfTxNum);

  Optional<VersionedValue> getLatest(ArchiveDomain domain, byte[] canonicalKey);

  void applyBlock(BlockWriteSet blockWriteSet);

  void unwindBlock(long blockNum, byte[] blockHash);

  Optional<BlockTxNumRange> getBlockRange(long blockNum);

  Optional<TxNumMeta> getTxNumMeta(long txNum);

  Optional<TxNumMeta> getTxNumByTxId(byte[] txId);

  ArchiveProgress progress();
}
```

`VersionedValue`：

```java
public final class VersionedValue {
  private final long lastTxNum;
  private final byte[] value; // nullable semantic value
}
```

规则：

- `getLatest` 返回 current live latest。P0 中 latest key missing 表示 current value missing。
- `getAsOf` 返回 state before `asOfTxNum`。
- `applyBlock/unwindBlock` 是唯一会写 temporal rows 的入口。
- API 入参 byte arrays 都 copy 或只读，不保留调用方引用。

## 6. Patch 2：DefaultArchiveTemporalStore dependencies

构造依赖：

```java
public final class DefaultArchiveTemporalStore implements ArchiveTemporalStore {
  private final ArchiveRawStore rawStore;
  private final ArchiveDomainRegistry registry;
  private final ArchiveConfig archiveConfig;
  private final ArchiveValueCodec valueCodec;
  private final LatestValueCodec latestValueCodec;
  private final TxNumValueCodec txNumValueCodec;
  private final BlockRangeValueCodec blockRangeValueCodec;
  private final ArchiveProgressCodec progressCodec;
}
```

不要注入 `Manager` 或 canonical Store。Temporal store 只看 archive DB 与 registry checksum；canonical head 对比由 `ArchiveStartupVerifier` 做。

## 7. Patch 3：applyBlock 输入校验

`applyBlock(BlockWriteSet block)` 开头严格校验：

```text
archive enabled and temporal enabled
progress.status in {EMPTY, OK}
registry checksum == block.registryChecksum
registry checksum == progress.registryChecksum, unless progress EMPTY
block.blockHash copied and non-empty
block.txWriteSets sorted by txNum ascending
txNum sequence contiguous within block
progress.nextTxNum == block.firstTxNum, unless progress EMPTY bootstrap
block.blockNum == progress.appliedBlockNum + 1, unless progress EMPTY bootstrap
block range first/last matches txWriteSets
```

progress 异常策略：

| progress status | applyBlock 行为 |
| --- | --- |
| `EMPTY` | 只允许 bootstrap 第一个 archive block；如果 canonical 已经远高于 genesis，startup verifier 应先标 `ARCHIVE_GAP` |
| `OK` | 正常 apply |
| `ARCHIVE_GAP` | 拒绝 apply，要求 backfill/repair |
| `REPAIR_REQUIRED` | 拒绝 apply，要求人工 repair/rebuild |

任何 mismatch 都抛 `ArchiveTemporalException`。如果 canonical DB 已经 commit，异常会向上让节点 fail-fast；下次启动由 verifier 报告 gap/corrupt。

## 8. Patch 4：applyBlock latest overlay

### 8.1 为什么必须有 overlay

同一个 block 内多个 tx 可以修改同一个 domain/key：

```text
raw latest before block: A=100
txNum 10: A 100 -> 90
txNum 11: A 90  -> 70
```

如果 tx 11 校验时仍从 raw store 读 latest，会看到 `100`，误判 `before=90` mismatch。S7 必须维护本 block batch 内的 latest overlay：

```text
Map<DomainKey, Optional<byte[]>> latestOverlay
```

读取当前值顺序：

```text
if latestOverlay contains domain/key:
  current = latestOverlay[domain/key]
else:
  current = raw latest
```

每个非 same-value write 处理后更新 overlay：

```text
latestOverlay[domain/key] = afterValue
```

same-value write 也要先校验 current == before；校验通过后不更新 overlay。

### 8.2 DomainKey

内部 key：

```java
final class DomainKey {
  private final int domainId;
  private final byte[] canonicalKey;
}
```

规则：

- `domainId` 是 S3 `u16` id，但 Java 字段可用 `int`。
- `equals/hashCode` 按 bytes 内容。
- constructor copy `canonicalKey`。

不要用裸 `byte[]` 做 `HashMap` key。

## 9. Patch 5：applyBlock 写入流程

伪代码：

```java
void applyBlock(BlockWriteSet block) {
  ArchiveProgress currentProgress = progress();
  validateBlockInput(block, currentProgress);

  ArchiveBatch batch = rawStore.newBatch();
  Map<DomainKey, Optional<byte[]>> latestOverlay = new LinkedHashMap<>();

  for (TxWriteSet tx : block.getTxWriteSets()) {
    validateTxMeta(tx, block);
    putTxNumMeta(batch, tx.getTxNumMeta());
    putTxIdIndex(batch, tx.getTxNumMeta());

    for (DomainWrite write : tx.getWrites()) {
      validateDomainWrite(write);
      Optional<byte[]> current = readCurrentValue(write.domain(), write.key(), latestOverlay);
      if (!nullableEquals(current, write.beforeValue())) {
        markRepairRequired(currentProgress, "latest mismatch");
        throw mismatch(...);
      }

      if (nullableEquals(write.beforeValue(), write.afterValue())) {
        continue;
      }

      batch.put(historyKey(write.domain(), write.key(), tx.txNum()),
          ArchiveValueCodec.encodeNullable(write.beforeValue()));
      batch.put(changesetKey(tx.txNum(), write.domain(), write.key()),
          ChangedKeyCodec.emptyMarker());

      if (write.afterValue() == null) {
        batch.delete(latestKey(write.domain(), write.key()));
      } else {
        batch.put(latestKey(write.domain(), write.key()),
            LatestValueCodec.encode(tx.txNum(), write.afterValue()));
      }

      latestOverlay.put(domainKey(write), Optional.ofNullable(copy(write.afterValue())));
    }
  }

  batch.put(txNumBlockKey(block.blockNum()), BlockRangeValueCodec.encode(block.range()));
  batch.put(progressKey(), ArchiveProgressCodec.encode(nextProgress(block)));
  batch.commit();
}
```

### 9.1 latest delete vs tombstone

P0 规则：

```text
afterValue == null -> delete LATEST(domain,key)
beforeValue == null -> HISTORY stores tombstone
```

理由：

- current latest set 只保存 live state。
- missing latest naturally means nonexistent current value.
- `GetAsOf` 仍可通过 `HISTORY` before-value 恢复删除前状态。

S6 `LatestValueCodec` 能编码 nullable value，但 S7 P0 不把 delete 后的 latest tombstone 常驻 DB。

### 9.2 same-value write

same-value write：

```text
write before == write after
```

处理：

- txNum meta 仍写。
- 不写 history。
- 不写 changeset。
- 不写 latest。
- 可增加 diagnostic counter，但 P0 不必持久化 counter。

### 9.3 tx with no writes

空 write-set tx 仍写：

```text
TXNUM_META(txNum)
TXNUM_BY_TXID(txId) for user tx
```

否则 `TX_BEFORE/TX_AFTER` 无法解析交易坐标。

### 9.4 empty block

没有 user tx 或没有 state write 的 block 仍写：

```text
TXNUM_BLOCK(blockNum)
TXNUM_META for BLOCK_PREPARE/BLOCK_FINALIZE if S1/S2 generated system txNums
META(progress)
```

不写 `HISTORY/CHANGESET`。

## 10. Patch 6：getLatest

实现：

```java
Optional<VersionedValue> getLatest(ArchiveDomain domain, byte[] key) {
  Optional<byte[]> encoded = rawStore.get(ArchiveKeyCodec.latestKey(domain, key));
  if (encoded.isEmpty()) {
    return Optional.empty();
  }
  LatestValue latest = LatestValueCodec.decode(encoded.get());
  if (latest.getValue() == null) {
    return Optional.empty();
  }
  return Optional.of(new VersionedValue(latest.getLastTxNum(), latest.getValue()));
}
```

规则：

- raw latest missing -> current missing。
- latest tombstone 如果未来兼容出现，也按 missing 处理。
- returned value copy。

## 11. Patch 7：getAsOf

语义：

```text
getAsOf(domain,key,asOfTxNum) returns state before asOfTxNum.
```

算法：

```java
Optional<byte[]> getAsOf(ArchiveDomain domain, byte[] key, long asOfTxNum) {
  ArchiveProgress p = progress();
  validateReadable(p, asOfTxNum);

  byte[] prefix = ArchiveKeyCodec.historyPrefix(domain, key);
  byte[] seekKey = ArchiveKeyCodec.historySeekKey(domain, key, asOfTxNum);
  Optional<ArchiveEntry> next = rawStore.seek(seekKey);

  if (next.isPresent() && ArchiveKeyCodec.startsWith(next.get().getKey(), prefix)) {
    return ArchiveValueCodec.decodeNullable(next.get().getValue());
  }

  return getLatest(domain, key).map(VersionedValue::getValue);
}
```

read guard：

| progress | getAsOf 行为 |
| --- | --- |
| `OK` and `asOfTxNum <= progress.nextTxNum` | allow |
| `OK` but `asOfTxNum > progress.nextTxNum` | archive gap error |
| `EMPTY` | return missing only if `asOfTxNum == 0`; otherwise gap |
| `ARCHIVE_GAP` | gap error |
| `REPAIR_REQUIRED` | corrupt/repair error |

prefix check 是 mandatory。`seek` 返回的是 greater-or-equal key，可能是下一个 domain/key 的 history。

例子：

```text
txNum 10: A 100 -> 90
txNum 11: A 90  -> 70
latest: A 70

getAsOf(A, 10) -> seek history >= 10 -> before 100
getAsOf(A, 11) -> seek history >= 11 -> before 90
getAsOf(A, 12) -> no future history -> latest 70
```

## 12. Patch 8：ChangedKey decode and scan

新增：

```text
chainbase/src/main/java/org/tron/core/archive/store/ChangedKey.java
```

字段：

```java
private final long txNum;
private final ArchiveDomain domain;
private final byte[] canonicalKey;
```

`CHANGESET` value P0 可以是 empty bytes；关键数据在 key 中：

```text
0x22 | txNum_u64 | domainId_u16 | keyLen_u32 | canonicalKey
```

S7 需要在 `ArchiveKeyCodec` 增加 decode helper：

```java
ChangedKey decodeChangesetKey(byte[] key);
long decodeTxNumMetaKey(byte[] key);
```

如果 S6 尚未实现 decode helper，S7 patch 应补在 `ArchiveKeyCodec`，不要新建 `TemporalKeyCodec`。

scan changes：

```java
ArchiveKeyRange range = ArchiveKeyCodec.changesetRange(firstTxNum, lastTxNum);
List<ArchiveEntry> rows = rawStore.range(range.fromInclusive(), range.toExclusive(), limit);
List<ChangedKey> changed = rows.stream()
    .map(row -> ArchiveKeyCodec.decodeChangesetKey(row.getKey()))
    .collect(toList());
```

limit：

- 单 block unwind 可以用 block range 的 expected state write count 加上安全 margin。
- 如果 rows 达到 limit，必须抛异常并标记 repair；不能只 unwind 部分 rows。

## 13. Patch 9：unwindBlock

输入来自 `DefaultArchiveService.unwindBlock(oldHeadBlock)`：

```text
blockNum = oldHeadBlock.getNum()
blockHash = copy(oldHeadBlock.getBlockId().getBytes())
```

严格流程：

```java
void unwindBlock(long blockNum, byte[] blockHash) {
  ArchiveProgress p = progress();
  validateCanUnwind(p, blockNum, blockHash);

  BlockTxNumRange range = getBlockRange(blockNum).orElseThrow(...);
  if (!Arrays.equals(range.blockHash(), blockHash)) {
    markRepairRequired(p, "block range hash mismatch");
    throw ...
  }

  List<ChangedKey> changes = scanChanges(range.firstTxNum(), range.lastTxNum());
  changes.sort(Comparator.comparingLong(ChangedKey::txNum).reversed());

  ArchiveBatch batch = rawStore.newBatch();
  for (ChangedKey change : changes) {
    byte[] hKey = ArchiveKeyCodec.historyKey(change.domain(), change.key(), change.txNum());
    Optional<byte[]> encodedBefore = rawStore.get(hKey);
    if (encodedBefore.isEmpty()) {
      markRepairRequired(p, "missing history row");
      throw ...
    }
    Optional<byte[]> before = ArchiveValueCodec.decodeNullable(encodedBefore.get());
    byte[] latestKey = ArchiveKeyCodec.latestKey(change.domain(), change.key());
    if (before.isPresent()) {
      batch.put(latestKey, LatestValueCodec.encode(change.txNum() - 1, before.get()));
    } else {
      batch.delete(latestKey);
    }
    batch.delete(hKey);
    batch.delete(ArchiveKeyCodec.changesetKey(change.txNum(), change.domain(), change.key()));
  }

  deleteTxNumRows(batch, range.firstTxNum(), range.lastTxNum());
  batch.delete(ArchiveKeyCodec.txNumBlockKey(blockNum));
  batch.put(ArchiveKeyCodec.progressKey(), ArchiveProgressCodec.encode(previousProgress(range)));
  batch.commit();
}
```

### 13.1 same key 多次变化

倒序恢复能处理：

```text
tx10: A 100 -> 90
tx11: A 90  -> 70
```

unwind 顺序：

```text
tx11 history before=90  -> latest=90
tx10 history before=100 -> latest=100
```

最终回到 block 前状态。

### 13.2 latest `lastTxNum` on unwind

恢复 latest 时没有必要准确知道 block 前最后一次修改 txNum。P0 可以使用：

```text
restored lastTxNum = changedTxNum - 1
```

该字段只用于 diagnostic/stale check，不参与 `GetAsOf` 正确性。若后续需要精确 lastTxNum，可在 `HISTORY` value 中扩展 beforeLastTxNum 并 bump schema。

### 13.3 before tombstone

如果 history before-value 是 tombstone：

```text
batch.delete(LATEST(domain,key))
```

不要写 latest tombstone 常驻 row。

## 14. Patch 10：delete txnum rows during unwind

删除 txnum rows 要先读 `TXNUM_META`，因为删除 `TXNUM_BY_TXID` 需要 txId：

```java
for (long txNum = range.firstTxNum(); txNum <= range.lastTxNum(); txNum++) {
  TxNumMeta meta = getTxNumMeta(txNum).orElseThrow(...);
  batch.delete(ArchiveKeyCodec.txNumMetaKey(txNum));
  if (meta.getTxId() != null) {
    batch.delete(ArchiveKeyCodec.txNumByTxIdKey(meta.getTxId()));
  }
}
```

如果某个 txNum meta 缺失：

```text
mark REPAIR_REQUIRED
throw
```

不能只删除已有部分，否则 txId index 和 block range 会不一致。

## 15. Patch 11：previousProgress

回退 block N 后的 progress：

```text
if N == 0:
  status = EMPTY
  nextTxNum = 0
  appliedBlockNum = 0
  appliedBlockHash = empty
else:
  previousRange = TXNUM_BLOCK(N - 1)
  if missing -> REPAIR_REQUIRED
  appliedBlockNum = N - 1
  appliedBlockHash = previousRange.blockHash
  nextTxNum = currentRange.firstTxNum
```

需要校验：

```text
previousRange.lastTxNum + 1 == currentRange.firstTxNum
```

如果不连续，标记 `REPAIR_REQUIRED`。这说明 archive txNum timeline 已经损坏。

## 16. Patch 12：markRepairRequired

对 pre-check mismatch，S7 需要尽量持久化 repair 状态：

```java
void markRepairRequired(ArchiveProgress current, String reason) {
  ArchiveProgress repair = current.withStatus(REPAIR_REQUIRED);
  ArchiveBatch batch = rawStore.newBatch();
  batch.put(progressKey(), ArchiveProgressCodec.encode(repair));
  batch.put(metaKey("repairReason"), encodeAscii(reason));
  batch.commit();
}
```

规则：

- repair marker 写在 single physical `archive` DB。
- 如果 mark repair 自身失败，继续抛原异常，并把 mark failure 作为 suppressed/log。
- `applyBlock/getAsOf/unwindBlock` 看到 `REPAIR_REQUIRED` 后必须拒绝继续读写。
- `repairReason` 是 diagnostic meta，不参与 state correctness。

## 17. Patch 13：PersistentArchiveTxNumIndex

文件：

```text
chainbase/src/main/java/org/tron/core/archive/txnum/PersistentArchiveTxNumIndex.java
```

推荐 thin wrapper：

```java
public final class PersistentArchiveTxNumIndex implements ArchiveTxNumIndex {
  private final ArchiveTemporalStore temporalStore;
  private final ArchiveExecutionContext executionContext;
}
```

读路径：

| API | 实现 |
| --- | --- |
| `nextTxNum()` | `temporalStore.progress().nextTxNum()` plus pending context offset |
| `findByTxId(txId)` | `temporalStore.getTxNumByTxId(txId)` |
| `findByTxNum(txNum)` | `temporalStore.getTxNumMeta(txNum)` |
| `findBlockRange(blockNum)` | `temporalStore.getBlockRange(blockNum)` |
| `reloadFromProgress()` | refresh cached nextTxNum if implementation has cache |

写路径仍在 S1/S2 context allocation 和 S7 `applyBlock`：

- tx execution 分配 txNum 时不直接写 DB。
- `applyBlock` 持久化 `TXNUM_*` rows。
- 避免 `ArchiveTxNumIndex.completeBlock()` 和 `DefaultArchiveTemporalStore.applyBlock()` double-write block range。

## 18. Patch 14：DefaultArchiveService commit/unwind

`commitBlock()`：

```java
public void commitBlock() {
  if (!isEnabled() || !archiveConfig.getTemporal().isEnable()) {
    clearPending();
    return;
  }

  BlockWriteSet blockWriteSet = writeCollector.commitBlock();
  try {
    temporalStore.applyBlock(blockWriteSet);
    txNumIndex.completeBlock(blockWriteSet.toBlockRange()); // no-op for persistent wrapper
  } finally {
    executionContext.clear();
  }
}
```

注意：

- `commitBlock()` 只应由 Manager 在 canonical `tmpSession.commit()` 成功后调用。
- `temporalStore.applyBlock` 失败后 canonical DB 已经提交，不能回滚 canonical；必须向上抛出 archive exception，让节点停止或进入明确 repair/gap 状态。
- 即使失败，也要 clear thread-local/pending context，避免下一 block 污染。
- 不要在 `commitBlock()` 内吞掉 archive exception。

`abortBlock()`：

```java
public void abortBlock() {
  writeCollector.abortBlock();
  txNumIndex.abortBlock();
  executionContext.clear();
}
```

`unwindBlock(BlockCapsule oldHeadBlock)`：

```java
public void unwindBlock(BlockCapsule oldHeadBlock) {
  if (!isEnabled() || !archiveConfig.getTemporal().isEnable()) {
    return;
  }
  temporalStore.unwindBlock(oldHeadBlock.getNum(), copy(oldHeadBlock.getBlockId().getBytes()));
  txNumIndex.reloadFromProgress();
}
```

archive unwind 失败时 canonical DB 已经 fastPop；必须抛出，让 startup verifier 下次报告 repair。不能静默继续处理新区块。

## 19. Patch 15：Manager hook

如果 S1/S2 已经把 Manager 正常路径改为 helper：

```text
archiveService.beginBlock(block)
applyBlock(...)
tmpSession.commit()
archiveService.commitBlock()
```

S7 不再额外改 normal apply，只替换 `DefaultArchiveService.commitBlock()` 内部实现。

如果 S1/S2 尚未落代码，S7 要求 Manager 至少满足：

```java
try {
  archiveService.beginBlock(newBlock);
  try (ISession tmpSession = revokingStore.buildSession()) {
    applyBlock(newBlock, txs);
    tmpSession.commit();
  }
  archiveService.commitBlock();
} catch (Throwable t) {
  archiveService.abortBlock();
  ...
  throw t;
}
```

fork replay 和 recovery replay 也必须使用同一 helper；不要只改 normal `pushBlock`。

`eraseBlock()` 接入点：

```java
BlockCapsule oldHeadBlock = chainBaseManager.getBlockById(latestHash);
khaosDb.pop();
revokingStore.fastPop();
archiveService.unwindBlock(oldHeadBlock);
```

顺序：

1. 先取 old head block。
2. canonical fastPop 成功。
3. archive unwind。

如果 fastPop 失败，不 unwind archive。

## 20. Patch 16：ArchiveStartupVerifier

文件：

```text
framework/src/main/java/org/tron/core/archive/ArchiveStartupVerifier.java
```

推荐由 `Manager.init()` 在 `khaosDb.start(...)` 成功后显式调用：

```java
archiveStartupVerifier.verifyOrThrow();
```

理由：

- `Manager.init()` 已完成 `ChainBaseManager.init` 和 khaos head 初始化。
- 显式调用避免 Spring bean `@PostConstruct` 顺序不确定。
- verifier 不需要在 chainbase 模块里引入 framework 生命周期。

依赖：

```java
ArchiveConfig
ArchiveTemporalStore
ArchiveDomainRegistry
ChainBaseManager
```

校验表：

| canonical / archive | S7 状态 |
| --- | --- |
| archive disabled | no-op |
| progress missing or EMPTY, canonical head is genesis/0 | OK to keep EMPTY |
| progress missing or EMPTY, canonical head > genesis/0 | `ARCHIVE_GAP` and fail-fast for archive node |
| progress OK, appliedBlockNum == canonical head num and hash matches | OK |
| progress OK, appliedBlockNum < canonical head num | `ARCHIVE_GAP` |
| progress OK, appliedBlockNum > canonical head num | `REPAIR_REQUIRED` |
| same height hash mismatch | `REPAIR_REQUIRED` |
| registry checksum mismatch | `REPAIR_REQUIRED` |
| progress schemaVersion unsupported | `REPAIR_REQUIRED` |

P0 不做自动 backfill，也不自动从 archive ahead 回退。自动 repair 会放大风险，必须等 verifier/rebuild 工具成熟后再做。

### 20.1 fail-fast 策略

P0 建议：

```text
archive.enable=true && temporal.enable=true && verifier not OK
  -> throw ArchiveTemporalException during startup
```

如果评审希望允许普通 fullnode 带 gap 启动，需要新增显式配置，例如：

```text
storage.archive.allowGapOnStartup = false
```

不要默认宽松。默认宽松会让历史 RPC 看起来可用，但实际缺数据。

## 21. Patch 17：query error model

S7 应区分：

| 错误 | 触发 | 后续 |
| --- | --- | --- |
| `ArchiveDisabledException` | archive disabled but queried | PR6 RPC 返回 archive disabled |
| `ArchiveGapException` | requested txNum beyond progress or startup behind | PR6 RPC 返回 archive gap |
| `ArchiveRepairRequiredException` | checksum/hash/latest mismatch | fail fast / repair |
| `ArchiveCorruptException` | malformed key/value/missing mandatory row | mark repair and throw |

不要让 `getAsOf` 用 `Optional.empty()` 表示 gap/corrupt。`Optional.empty()` 只表示该 domain/key 在目标时间点不存在。

## 22. 测试计划

### 22.1 `DefaultArchiveTemporalStoreApplyTest`

使用 `TreeMapArchiveRawStore`：

| case | 断言 |
| --- | --- |
| single key update chain | history before-values 和 latest 正确 |
| multi tx same key same block | overlay 生效；第二笔 tx 不误判 latest mismatch |
| same-value write | 不写 history/change/latest，txNum meta 保留 |
| create key | before tombstone history，latest present |
| delete key | history 保存旧值，latest key deleted |
| delete then recreate same block | overlay 正确，最终 latest present |
| no writes tx | 只写 txnum meta |
| empty block/system tx only | block range/progress 前进，state rows 为空 |
| registry checksum mismatch | mark repair and throw |
| latest mismatch | mark repair and throw |

### 22.2 `DefaultArchiveTemporalStoreGetAsOfTest`

覆盖：

1. `TX_BEFORE(tx10)` 读到 tx10 before。
2. `TX_AFTER(tx10)` 通过 resolver asOf=tx11 读到 tx10 after。
3. no future history fallback latest。
4. tombstone/missing 返回 `Optional.empty()`。
5. seek 返回相邻 key 时 prefix check 生效。
6. `asOfTxNum > progress.nextTxNum` 抛 gap，不返回 latest。
7. `REPAIR_REQUIRED` progress 下拒绝读。

### 22.3 `DefaultArchiveTemporalStoreUnwindTest`

覆盖：

| case | 断言 |
| --- | --- |
| unwind latest block | latest/history/change/txnum/progress 同批恢复 |
| same key multi tx in block | 倒序恢复到 block 前 value |
| create then unwind | latest deleted |
| delete then unwind | latest 恢复旧值 |
| delete/recreate same block unwind | 回到 block 前状态 |
| no writes block unwind | 只删 txnum/progress |
| missing history row | mark `REPAIR_REQUIRED` |
| block hash mismatch | mark `REPAIR_REQUIRED` |
| previous range missing | mark `REPAIR_REQUIRED` |
| txnum meta missing | mark `REPAIR_REQUIRED` |

### 22.4 `PersistentArchiveTxNumIndexTest`

覆盖：

- find tx by txId。
- find tx by txNum。
- find block range。
- reload from progress。
- pending allocation 不 double-write。

### 22.5 `DefaultArchiveServiceTemporalCommitTest`

覆盖：

- canonical commit 后 `commitBlock` 调 temporal apply。
- temporal apply 成功后 complete/reload txNum index。
- temporal apply 失败后不 complete txNum index，并 clear context。
- abortBlock 清 writeCollector/txNum/context。
- unwindBlock 调 temporal unwind，并 reload txNum index。

### 22.6 `ArchiveStartupVerifierTest`

覆盖：

- disabled no-op。
- empty archive + genesis head -> OK/EMPTY。
- empty archive + non-genesis head -> `ARCHIVE_GAP`。
- archive behind -> `ARCHIVE_GAP`。
- archive ahead -> `REPAIR_REQUIRED`。
- same height hash mismatch -> `REPAIR_REQUIRED`。
- registry checksum mismatch -> `REPAIR_REQUIRED`。
- unsupported schema -> `REPAIR_REQUIRED`。

不要新增 test skip。若某个 DB native 环境不可用，把真实 DB wrapper case 放到已有 CI 环境或报告未运行 gate，不用 `@Ignore` / assume 绕过。

## 23. 验收清单

S7 合并前检查：

- [ ] `applyBlock` 使用 S6 `ArchiveBatch`，不直接拼裸 `HashMap<byte[], byte[]>`。
- [ ] `applyBlock` 有 latest overlay，支持同 block 多 tx 改同 key。
- [ ] `beforeValue` mismatch 会 mark repair 并抛异常。
- [ ] `same-value` 不写 history/change/latest，但保留 txnum。
- [ ] `afterValue=null` 删除 latest key。
- [ ] `getAsOf` seek 后必须 prefix check。
- [ ] `getAsOf` 对 gap/repair 抛错误，不用 empty 混淆。
- [ ] `unwindBlock` 按 txNum 倒序恢复。
- [ ] `unwindBlock` 删除 history/change/txnum/block range/progress 同批提交。
- [ ] missing mandatory rows 或 hash mismatch 会 mark `REPAIR_REQUIRED`。
- [ ] startup verifier 不把已有高区块节点上的空 archive 标成 OK。
- [ ] Manager 正常 apply、switch fork replay、recovery replay 都走 archive commit helper。
- [ ] `eraseBlock` 只在 canonical fastPop 成功后 unwind archive。
- [ ] 不新增 `archive-state/archive-txnum/archive-root` physical DB。
- [ ] 不新增 `TemporalKeyCodec` 或第二套 key schema。
- [ ] 不新增 test skip。

## 24. S8 handoff

S7 完成后，S8 `ArchiveStateReader` 可以只依赖：

```text
ArchiveTxNumIndex.resolve(StatePoint) -> asOfTxNum
ArchiveTemporalStore.getAsOf(domain,key,asOfTxNum)
ArchiveTemporalStore.progress()
ArchiveDomainRegistry
```

S8 不需要知道 `HISTORY`/`CHANGESET` key layout；那些仍然封装在 S6/S7。
