# java-tron Archive PR5 TemporalStore 代码级实现规格

日期：2026-06-02

关联实现蓝图：[java-tron Archive 状态树实现蓝图](./20260602-java-tron-archive-implementation-blueprint.md)

逐文件实现清单：[java-tron Archive 模块 04：ArchiveTemporalStore 逐文件 Patch 清单](./20260602-java-tron-archive-module-04-temporal-store-patch-checklist.md)

S6 ArchiveRawStore + temporal codecs 编码执行包：[java-tron Archive S6：ArchiveRawStore + Temporal Codecs 编码执行包](./20260602-java-tron-archive-s6-raw-store-temporal-codecs-coding-packet.md)

S7 Temporal commit/unwind/startup 编码执行包：[java-tron Archive S7：Temporal Commit / GetAsOf / Unwind / Startup 编码执行包](./20260602-java-tron-archive-s7-temporal-commit-unwind-startup-coding-packet.md)

前置规格：

- [java-tron Archive PR1/PR2 代码级实现规格](./20260602-java-tron-archive-pr1-pr2-implementation-spec.md)
- [java-tron Archive PR3/PR4 WriteCollector 代码级实现规格](./20260602-java-tron-archive-pr3-pr4-write-collector-implementation-spec.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 旧文档原始基线：`a79693e450`，当前 4e80 实现请看 2026-06-03 细化文档。

## 1. 范围

PR5 把 PR3/PR4 产生的内存 `BlockWriteSet` 持久化为可查询、可回滚的 temporal history：

```text
BlockWriteSet
  -> archive latest
  -> before-value history
  -> changed-key index
  -> persistent txNum index
  -> progress meta
```

PR5 交付后应支持：

1. `GetAsOf(domain, key, asOfTxNum)`。
2. `applyBlock(BlockWriteSet)` 原子写入 archive sidecar。
3. `unwindBlock(blockNum, blockHash)` 从 archive sidecar 回退。
4. 启动时校验 archive progress 与 canonical chain 是否一致。
5. PR1/PR2 的 in-memory txNum index 可从 DB 重建，或直接由 persistent index 提供查询。

仍不做：

- 不改 JSON-RPC。
- 不实现 `ArchiveStateReader` 的 capsule/protobuf 适配。
- 不计算 commitment root。
- 不做 cold segment/freeze。
- 不做自动全量 backfill。

## 2. 源码证据

| 位置 | 事实 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/db2/common/DB.java:10` | DB 接口只有 get/put/remove/iterator |
| `chainbase/src/main/java/org/tron/core/db/common/iterator/DBIterator.java:13` | iterator 支持 `seek(byte[] key)` |
| `chainbase/src/main/java/org/tron/core/db/common/iterator/StoreIterator.java:73` | LevelDB iterator 支持 seek |
| `chainbase/src/main/java/org/tron/core/db/common/iterator/RockStoreIterator.java:92` | RocksDB iterator 支持 seek |
| `chainbase/src/main/java/org/tron/common/storage/leveldb/LevelDbDataSourceImpl.java:365-379` | batch value 为 null 时 delete |
| `chainbase/src/main/java/org/tron/common/storage/rocksdb/RocksDbDataSourceImpl.java:301-314` | RocksDB batch value 为 null 时 delete |
| `chainbase/src/main/java/org/tron/core/db/TronDatabase.java:63` | `updateByBatch(Map<byte[], byte[]>)` |
| `chainbase/src/main/java/org/tron/common/utils/StorageUtils.java:20` | `getOutputDirectoryByDbName` 支持 per-DB path |
| `common/src/main/java/org/tron/common/utils/ByteArray.java:87` | `ByteArray.fromLong` 是 8-byte big-endian |
| `common/src/main/java/org/tron/common/utils/ByteArray.java:91` | `ByteArray.fromInt` 是 4-byte big-endian |
| `common/src/main/java/org/tron/common/utils/ByteUtil.java:400-414` | `compare` 是 unsigned lexicographic，但要求等长数组 |
| `framework/src/main/java/org/tron/core/db/Manager.java:1374-1377` | canonical DB commit 后才应 flush archive |
| `framework/src/main/java/org/tron/core/db/Manager.java:1017-1025` | `eraseBlock` 是 archive unwind 接入点 |

关键结论：

- LevelDB/RocksDB 支持 seek + prefix scan，足够实现 `GetAsOf`。
- batch 可以 put/delete，但只能保证单个 physical DB 内原子。
- 多 physical DB 没有跨 DB transaction。PR5 应优先用一个 physical archive DB，通过 table prefix 分出 meta/txnum/state。
- Erigon V3 的 temporal write 是 latest/history/changed-index 的组合；java-tron PR5 也必须把 `latest + history + changeset + txnum + progress` 放进同一个 archive batch。

## 3. 对实现蓝图的收敛修正

早期蓝图曾把 sidecar 按职责拆成四类逻辑区域：

```text
meta
txnum
state
root
```

PR5 收敛为先使用一个 physical DB：

```text
archive
```

内部用 table prefix 分出逻辑表：

```text
META
TXNUM_BLOCK
TXNUM_BY_TXID
TXNUM_META
LATEST
HISTORY
CHANGESET
```

理由：

1. java-tron 当前没有跨 DB batch transaction。
2. PR5 需要 `state + txnum + progress` 同批原子更新。
3. 后续 cold segment 或 root DB 可以再拆物理库，但 P0 先保证一致性。

## 4. 改动文件

```text
chainbase/src/main/java/org/tron/core/archive/store/ArchiveTemporalStore.java
chainbase/src/main/java/org/tron/core/archive/store/DefaultArchiveTemporalStore.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveRawStore.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveBatch.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveEntry.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveStoreException.java
chainbase/src/main/java/org/tron/core/archive/store/DefaultArchiveRawStore.java
chainbase/src/main/java/org/tron/core/archive/store/TreeMapArchiveRawStore.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveDbFactory.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveKeyCodec.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveKeyRange.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveValueCodec.java
chainbase/src/main/java/org/tron/core/archive/store/LatestValue.java
chainbase/src/main/java/org/tron/core/archive/store/LatestValueCodec.java
chainbase/src/main/java/org/tron/core/archive/store/TxNumValueCodec.java
chainbase/src/main/java/org/tron/core/archive/store/BlockRangeValueCodec.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveProgress.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveProgressCodec.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveTable.java
chainbase/src/main/java/org/tron/core/archive/store/ChangedKey.java
chainbase/src/main/java/org/tron/core/archive/store/HistoryEntry.java
chainbase/src/main/java/org/tron/core/archive/txnum/PersistentArchiveTxNumIndex.java
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
framework/src/main/java/org/tron/core/archive/ArchiveStartupVerifier.java
framework/src/main/java/org/tron/core/db/Manager.java
framework/src/test/java/org/tron/core/archive/ArchiveTemporalStoreTest.java
framework/src/test/java/org/tron/core/archive/ArchiveTemporalStoreUnwindTest.java
framework/src/test/java/org/tron/core/archive/ArchiveProgressVerifierTest.java
framework/src/test/java/org/tron/core/archive/ArchiveRawStoreLevelDbTest.java
framework/src/test/java/org/tron/core/archive/ArchiveRawStoreRocksDbTest.java
```

如果 PR5 体量过大，可以拆成：

```text
PR5a: raw store + key/value codec + temporal unit tests
PR5b: Manager commit/unwind + progress verifier
```

## 5. ArchiveRawStore

### 5.1 接口

```java
public interface ArchiveRawStore extends AutoCloseable {
  Optional<byte[]> get(byte[] key);

  void put(byte[] key, byte[] value);

  void delete(byte[] key);

  void updateByBatch(Map<byte[], byte[]> rows);

  Optional<ArchiveEntry> seek(byte[] key);

  List<ArchiveEntry> prefix(byte[] prefix, int limit);

  List<ArchiveEntry> range(byte[] fromInclusive, byte[] toExclusive, int limit);
}
```

实现注意：

- `updateByBatch` 中 value 为 null 表示 delete，复用 LevelDB/RocksDB 现有语义。
- `ArchiveEntry` 必须 immutable copy，不直接暴露 `Map.Entry<byte[], byte[]>`。
- `seek` 必须检查 iterator 当前 key 是否仍属于目标 prefix；调用方不能假设 seek 命中就是同一逻辑 key。
- `prefix/range` 必须关闭 iterator。

### 5.2 DB 创建路径

Archive 使用配置：

```text
storage.archive.db.directory = "archive"
storage.db.directory = "database"
```

推荐 physical DB 名：

```text
archive
```

路径规则：

```text
LEVELDB:
  new LevelDbDataSourceImpl(outputDir/archiveDir, "archive")
  实际路径约为 outputDir/archiveDir/storage.db.directory/archive

ROCKSDB:
  new RocksDbDataSourceImpl(outputDir/archiveDir/storage.db.directory, "archive")
  实际路径约为 outputDir/archiveDir/storage.db.directory/archive
```

原因：

- `LevelDbDataSourceImpl` 构造器内部会追加 `storage.db.directory`。
- `RocksDbDataSourceImpl` 构造器不会追加，需要调用方传入 parent path。

### 5.3 byte[] key 去重

Java `HashMap<byte[], byte[]>` 使用引用相等，不能用于需要去重的 batch 构建。建议内部用：

```java
LinkedHashMap<WrappedByteArray, byte[]> rows
```

flush 前转换为：

```java
Map<byte[], byte[]> rawRows = new LinkedHashMap<>();
rows.forEach((key, value) -> rawRows.put(key.getBytes(), value));
rawStore.updateByBatch(rawRows);
```

不要直接在业务层用 `HashMap<byte[], byte[]>` 合并同一个 key。

## 6. Key schema

### 6.1 设计原则

1. 所有整数使用 big-endian，保证 lexicographic order。
2. key 中 variable bytes 必须 length-prefix，避免拼接歧义。
3. `HISTORY` key 排序必须支持 seek 到 `txNum >= asOfTxNum`。
4. `CHANGESET` key 排序必须支持按 txNum 扫描。

### 6.2 table prefix

```java
public enum ArchiveTable {
  META((byte) 0x01),
  TXNUM_BLOCK((byte) 0x10),
  TXNUM_BY_TXID((byte) 0x11),
  TXNUM_META((byte) 0x12),
  LATEST((byte) 0x20),
  HISTORY((byte) 0x21),
  CHANGESET((byte) 0x22);
}
```

### 6.3 key layout

```text
META:
  0x01 | asciiName

TXNUM_BLOCK:
  0x10 | u64 blockNum

TXNUM_BY_TXID:
  0x11 | u32 txIdLen | txId

TXNUM_META:
  0x12 | u64 txNum

LATEST:
  0x20 | u16 domainId | u32 keyLen | canonicalKey

HISTORY:
  0x21 | u16 domainId | u32 keyLen | canonicalKey | u64 txNum

CHANGESET:
  0x22 | u64 txNum | u16 domainId | u32 keyLen | canonicalKey
```

`HISTORY` prefix：

```text
0x21 | domainId_u16 | keyLen_u32 | canonicalKey
```

`GetAsOf` seek key：

```text
historyPrefix(domain,key) | u64 asOfTxNum
```

## 7. Value schema

### 7.1 ArchiveValueCodec

不要使用 Java serialization。使用稳定二进制：

```text
0x00                         tombstone/missing
0x01 | valueLen_u32 | value  present value, valueLen can be 0
```

API：

```java
byte[] encodeNullable(byte[] value);

Optional<byte[]> decodeNullable(byte[] encoded);

boolean isTombstone(byte[] encoded);
```

规则：

- `null` before/after 编码为 tombstone。
- `new byte[0]` 是 present empty bytes，不等于 tombstone。
- `CONTRACT_STORAGE` zero 已在 S5 归一为 `afterValue=null` tombstone；S6/S7 不再把 semantic storage zero 写成 present 32-byte zero。
- account/code/contract delete 编码为 tombstone。

### 7.2 TxNumMeta value

稳定二进制：

```text
u64 txNum
u64 blockNum
i32 txIndex
u8 phase
u32 blockHashLen | blockHash
u32 txIdLen | txId, txIdLen=0 表示 null
```

`txIndex=-1` 用 signed int big-endian。

### 7.3 BlockTxNumRange value

```text
u64 blockNum
u32 blockHashLen | blockHash
u64 firstTxNum
u64 lastTxNum
u32 userTxCount
u32 systemTxCount
```

### 7.4 ArchiveProgress value

```text
u32 schemaVersion
u64 appliedBlockNum
u32 appliedBlockHashLen | appliedBlockHash
u64 nextTxNum
u32 coverageLen | coverageAscii
u8  status
```

`status`：

```text
0 = EMPTY
1 = OK
2 = REPAIR_REQUIRED
```

## 8. ArchiveTemporalStore API

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

PR5 可以让 `PersistentArchiveTxNumIndex` 只是这个 store 的 thin wrapper，避免 txNum index 和 temporal store 重复维护 batch。

## 9. applyBlock 流程

输入来自 PR3/PR4：

```text
BlockWriteSet(blockNum, blockHash, txWriteSets)
```

流程：

```java
void applyBlock(BlockWriteSet blockWriteSet) {
  ArchiveBatch batch = rawStore.newBatch();
  Map<DomainKey, Optional<byte[]>> latestOverlay = new LinkedHashMap<>();

  for (TxWriteSet tx : blockWriteSet.getTxWriteSets()) {
    putTxNumMeta(batch, tx.getTxNumMeta());
    putTxIdIndex(batch, tx.getTxNumMeta());

    for (DomainWrite write : tx.getWrites()) {
      Optional<byte[]> current = readCurrentValue(write.domain(), write.key(), latestOverlay);
      validateBeforeValue(current, write.beforeValue());
      if (sameValue(write.beforeValue(), write.afterValue())) {
        continue;
      }
      putHistory(batch, write.domain(), write.key(), tx.txNum(), write.beforeValue());
      putChangeset(batch, tx.txNum(), write.domain(), write.key());
      putLatestOrDelete(batch, write.domain(), write.key(), tx.txNum(), write.afterValue());
      latestOverlay.put(domainKey(write), Optional.ofNullable(copy(write.afterValue())));
    }
  }

  putBlockRange(batch, blockWriteSet.range());
  putProgress(batch, newProgress);
  batch.commit();
}
```

写入同一 physical DB 的一个 batch，因此：

- history/latest/changeset/txnum/progress 同批原子。
- batch 成功后 progress 可见。
- batch 失败后 progress 不前进。
- 同一个 block 内多个 tx 修改同一 key 时，`latestOverlay` 必须参与 before-value 校验；不能每次只读 raw latest，否则第二笔 tx 会误判 mismatch。
- P0 中 `afterValue == null` 删除 `LATEST(domain,key)`；`beforeValue == null` 只在 `HISTORY` 中编码 tombstone。

### 9.1 same-value write

PR5 开始可以跳过 history：

```text
if encoded(before) == encoded(after):
  do not write history/change/latest
  keep diagnostic counter
```

但 txNum meta 仍要写，因为交易时间线存在。

### 9.2 block with no writes

empty block 或无状态变化 block：

- 仍写 TXNUM_BLOCK range。
- 仍写 TXNUM_META for `BLOCK_FINALIZE`。
- 不写 HISTORY/CHANGESET。
- progress 前进。

## 10. GetAsOf 语义

算法：

```java
Optional<byte[]> getAsOf(domain, key, asOfTxNum) {
  byte[] prefix = historyPrefix(domain, key);
  byte[] seekKey = historyKey(domain, key, asOfTxNum);
  Optional<Entry<byte[], byte[]>> next = rawStore.seek(seekKey);

  if (next.isPresent() && startsWith(next.get().getKey(), prefix)) {
    return valueCodec.decodeNullable(next.get().getValue());
  }

  byte[] latest = rawStore.get(latestKey(domain, key));
  return valueCodec.decodeNullable(latest);
}
```

例子：

```text
txNum 10: A 100 -> 90
txNum 11: A 90  -> 70
latest: A 70

GetAsOf(A, 10) seek history >= 10 -> before 100
GetAsOf(A, 11) seek history >= 11 -> before 90
GetAsOf(A, 12) no future history -> latest 70
```

注意：

- `asOfTxNum` 是 exclusive 语义，由 `ArchiveTxNumIndex`/StatePoint resolver 提供。
- `getAsOf` 不接受 blockNum/txId，避免 off-by-one 分散。

## 11. unwindBlock 流程

输入：

```text
blockNum
blockHash
```

流程：

```java
BlockTxNumRange range = getBlockRange(blockNum)
if range missing:
  return or throw based on strict mode
if !Arrays.equals(range.blockHash, blockHash):
  mark REPAIR_REQUIRED and throw

List<ChangedKey> changed = scanChanges(range.firstTxNum, range.lastTxNum)
sort changed by txNum descending

ArchiveBatch batch = rawStore.newBatch()
for changedKey in changed:
  byte[] encodedBefore = rawStore.get(historyKey(domain, key, txNum))
  Optional<byte[]> before = valueCodec.decodeNullable(encodedBefore)
  if before is present:
    batch.put(latestKey(domain, key), latestValueCodec.encode(txNum - 1, before))
  else:
    batch.delete(latestKey(domain, key))
  batch.delete(historyKey(domain, key, txNum))
  batch.delete(changesetKey(txNum, domain, key))

for txNum in [firstTxNum..lastTxNum]:
  TxNumMeta meta = getTxNumMeta(txNum)
  batch.delete(txNumMetaKey(txNum))
  if meta.txId != null:
    batch.delete(txIdKey(meta.txId))

batch.delete(blockRangeKey(blockNum))
batch.put(progressKey, previousProgress(blockNum - 1))
batch.commit()
```

### 11.1 previousProgress

P0 可通过 `TXNUM_BLOCK(blockNum - 1)` 找 previous block range：

- 如果存在，`nextTxNum = previous.lastTxNum + 1`。
- 如果不存在且 unwind 到 genesis 前，`nextTxNum = 0`。

如果 block hash 缺失或 range 不连续，标记 `REPAIR_REQUIRED`。

### 11.2 scanChanges

`CHANGESET` key 按 `txNum` 排序：

```text
from = 0x22 | firstTxNum
to   = 0x22 | lastTxNum + 1
```

如果 `lastTxNum == Long.MAX_VALUE`，`changesetRange` 必须拒绝，避免 upper-bound 溢出。

PR5 可以 forward scan 后在内存 reverse。单 block write set 通常可控；后续大范围 unwind 再优化 reverse index。

## 12. Manager 接入

PR3/PR4 后 `DefaultArchiveService.commitBlock()` 拿到 `BlockWriteSet`。PR5 改为：

```java
public void commitBlock() {
  if (!isEnabled()) {
    return;
  }
  BlockWriteSet blockWriteSet = writeCollector.commitBlock();
  temporalStore.applyBlock(blockWriteSet);
}
```

`abortBlock()`：

```java
writeCollector.abortBlock();
txNumIndex.abortBlock();
executionContext.clear();
```

`unwindBlock(oldHeadBlock)`：

```java
temporalStore.unwindBlock(oldHeadBlock.getNum(), oldHeadBlock.getBlockId().getBytes());
txNumIndex.reloadFromTemporalProgress();
```

如果 PR5 把 txNum index 持久查询合并进 `ArchiveTemporalStore`，`txNumIndex.reload` 可以只是刷新 `nextTxNum`。

## 13. Startup verifier

新增：

```text
framework/src/main/java/org/tron/core/archive/ArchiveStartupVerifier.java
```

触发位置：

- Spring context ready 后。
- `ArchiveService` 初始化后。
- `Manager` 开始处理新 block 前。

校验：

```text
archive disabled -> no-op
archive progress EMPTY and canonical head is genesis/0 -> keep EMPTY or initialize first archive block
archive progress EMPTY and canonical head > genesis/0 -> ARCHIVE_GAP
archive appliedBlockNum/hash == chain latest -> OK
archive appliedBlockNum > chain latest -> REPAIR_REQUIRED or unwind
archive appliedBlockNum < chain latest -> ARCHIVE_GAP
same height but hash mismatch -> REPAIR_REQUIRED
registry checksum mismatch -> REPAIR_REQUIRED
```

P0 策略：

- 不自动 backfill。
- `ARCHIVE_GAP` 时历史查询返回 archive gap error。
- 节点是否允许继续生产/同步由配置决定；建议 archive node fail fast，普通 fullnode 不应开启 archive。

### 13.1 canonical DB 与 archive DB 非原子

即使 PR5 使用单 archive DB，canonical DB commit 与 archive commit 仍不是一个事务：

```text
tmpSession.commit() success
crash before archive applyBlock()
```

启动后表现为：

```text
canonical latest > archive applied
```

P0 必须检测并报告，不要假装 archive 可用。

## 14. Tests

### 14.1 ArchiveKeyCodecTest

纯 unit：

1. `ByteArray.fromLong` big-endian 顺序满足 lexicographic ordering。
2. `historyKey(domain,key,10) < historyKey(domain,key,11)`。
3. 不同 keyLen 不产生 prefix 冲突。
4. `changesetKey(txNum)` range 可按 txNum 扫描。

### 14.2 ArchiveValueCodecTest

纯 unit：

1. null -> tombstone。
2. empty bytes -> present empty bytes。
3. 32-byte zero -> present zero bytes。
4. decode invalid prefix 抛明确异常。

### 14.3 ArchiveTemporalStoreTest

使用 `TreeMapArchiveRawStore` 单测，不依赖 LevelDB/RocksDB：

1. single key before-value chain。
2. same tx same key 多写已由 collector 压缩，TemporalStore 正确保存第一次 before。
3. same-value write 不写 history/change。
4. missing key 返回 empty。
5. latest fallback 正确。

### 14.4 ArchiveTemporalStoreUnwindTest

1. apply block A/B/C 后 unwind C。
2. 同一 key 在 C 内多 tx 变化，恢复到 B 后状态。
3. delete/recreate unwind 正确。
4. hash mismatch 标记 `REPAIR_REQUIRED`。
5. block range 删除，txId index 删除。

### 14.5 ArchiveRawStoreLevelDbTest / RocksDbTest

测试真实 DB wrapper：

1. `seek` 命中目标 prefix。
2. `seek` 到不存在 key 返回下一 key，调用方 prefix check 生效。
3. `updateByBatch` null value 删除 key。
4. prefix/range iterator 关闭。

如果本地 native DB 环境不可用，不要加 `@Ignore` 或 assume-style 跳过。应把真实 DB wrapper case 放到已有 CI 环境覆盖，或在 PR 说明中明确列为本地未运行 gate。

### 14.6 ArchiveProgressVerifierTest

1. archive disabled no-op。
2. EMPTY progress 初始化。
3. applied hash 与 chain latest 不一致 -> `REPAIR_REQUIRED`。
4. archive behind -> `ARCHIVE_GAP`。
5. archive ahead -> `REPAIR_REQUIRED` 或 unwind path。

## 15. 代码审查清单

- P0 使用一个 physical archive DB，避免 state/txnum/progress 跨 DB 不一致。
- 所有 key/value codec 都是稳定二进制，不使用 Java serialization。
- Batch 构建不直接用 `HashMap<byte[], byte[]>` 去重。
- `GetAsOf` 必须检查 seek 结果 prefix。
- same-value write 不写 history/change，但 txNum meta 仍写。
- tombstone 能区分 missing 与 present empty bytes。
- unwind 按 txNum 逆序恢复 latest。
- `updateByBatch` 使用 null value delete 的现有语义。
- startup verifier 明确处理 archive gap。
- canonical commit 与 archive commit 非原子的问题被显式检测。

## 16. 建议执行命令

定向测试：

```bash
./gradlew :framework:test --tests 'org.tron.core.archive.ArchiveKeyCodecTest'
./gradlew :framework:test --tests 'org.tron.core.archive.ArchiveValueCodecTest'
./gradlew :framework:test --tests 'org.tron.core.archive.ArchiveTemporalStoreTest'
./gradlew :framework:test --tests 'org.tron.core.archive.ArchiveTemporalStoreUnwindTest'
./gradlew :framework:test --tests 'org.tron.core.archive.ArchiveProgressVerifierTest'
```

真实 DB wrapper：

```bash
./gradlew :framework:test --tests 'org.tron.core.archive.ArchiveRawStoreLevelDbTest'
./gradlew :framework:test --tests 'org.tron.core.archive.ArchiveRawStoreRocksDbTest'
```

回归：

```bash
./gradlew :framework:test --tests '*Archive*'
./gradlew :framework:test --tests 'org.tron.core.db2.ChainbaseTest'
./gradlew :framework:test --tests 'org.tron.core.db.ManagerTest'
./gradlew lint
```
