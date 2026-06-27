# java-tron Archive 模块 04：ArchiveTemporalStore 逐文件 Patch 清单

日期：2026-06-02

> 2026-06-03 更新：本文是旧 `a79693e450` patch 清单。当前 `4e80f8ffa9a2` 的 Module 04 编码入口请先看 [java-tron Archive S6/S7：ArchiveTemporalStore 4e80 编码执行包](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md)，旧行号不可直接用于编码。

关联设计：[java-tron Archive 模块 04：ArchiveTemporalStore 细化设计](./20260521-java-tron-archive-module-04-temporal-store.md)

java-tron 源码对照：[模块 04 ArchiveTemporalStore：java-tron 源码对照](./20260601-java-tron-module-04-temporal-store-java-tron-source-deep-dive.md)

关联 PR5 规格：[java-tron Archive PR5 TemporalStore 代码级实现规格](./20260602-java-tron-archive-pr5-temporal-store-implementation-spec.md)

S6/S7 4e80 编码执行包：[java-tron Archive S6/S7：ArchiveTemporalStore 4e80 编码执行包](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md)

S6 历史编码执行包：[java-tron Archive S6：ArchiveRawStore + Temporal Codecs 编码执行包](./20260602-java-tron-archive-s6-raw-store-temporal-codecs-coding-packet.md)

S7 历史编码执行包：[java-tron Archive S7：Temporal Commit / GetAsOf / Unwind / Startup 编码执行包](./20260602-java-tron-archive-s7-temporal-commit-unwind-startup-coding-packet.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 旧文档原始基线：`a79693e450`，当前 4e80 实现请看页头链接。

## 1. 目标

本文把 `ArchiveTemporalStore` 落到 java-tron 逐文件 patch 级别。该模块把 PR3/PR4 产出的 `BlockWriteSet` 持久化为可查询、可回滚的 archive sidecar。

```text
BlockWriteSet
  -> one physical archive DB
  -> latest(domain,key)
  -> history before-value(domain,key,txNum)
  -> changeset(txNum,domain,key)
  -> persistent txNum index
  -> progress meta
```

PR5 合并后应具备：

1. `applyBlock(BlockWriteSet)` 原子写入 latest/history/changeset/txnum/progress。
2. `getAsOf(domain, key, asOfTxNum)` 实现 before-tx 语义。
3. `unwindBlock(blockNum, blockHash)` 能倒序恢复 latest 并删除 history/changeset/txnum。
4. 启动时能校验 archive progress 与 canonical chain 的关系。
5. PR1/PR2 的 in-memory txNum index 可以由 persistent index 替代或重建。
6. P0 不做 cold segment/freeze，但 key/value schema 为后续 freeze 留好边界。

## 2. 源码事实

| java-tron 位置 | 事实 | 对 TemporalStore 的含义 |
| --- | --- | --- |
| `chainbase/src/main/java/org/tron/core/db2/common/DB.java:8` | DB 接口只有 get/put/remove/iterator | TemporalStore 需要额外封装 seek/prefix/batch |
| `chainbase/src/main/java/org/tron/core/db/common/iterator/DBIterator.java:13` | iterator 支持 `seek(byte[] key)` | `GetAsOf` 可用 history seek 实现 |
| `chainbase/src/main/java/org/tron/core/db/common/iterator/StoreIterator.java:73` | LevelDB iterator seek | raw store prefix/range 可支持 LevelDB |
| `chainbase/src/main/java/org/tron/core/db/common/iterator/RockStoreIterator.java:92` | RocksDB iterator seek | raw store prefix/range 可支持 RocksDB |
| `chainbase/src/main/java/org/tron/common/storage/leveldb/LevelDbDataSourceImpl.java:365-379` | batch 中 value=null 时 `batch.delete(key)` | unwind 可在同 batch 删除 history/change |
| `chainbase/src/main/java/org/tron/common/storage/rocksdb/RocksDbDataSourceImpl.java:301-314` | RocksDB batch 中 value=null 时 delete | 同上 |
| `chainbase/src/main/java/org/tron/core/db/TronDatabase.java:63` | `updateByBatch(Map<byte[], byte[]>)` | 可复用 data source batch 语义 |
| `chainbase/src/main/java/org/tron/core/db2/common/LevelDB.java:59` | `flush(Map<WrappedByteArray, WrappedByteArray>)` 不能表达 null delete | 不建议用 `Flusher` 作为 PR5 batch 抽象 |
| `chainbase/src/main/java/org/tron/common/utils/StorageUtils.java:22` | `getOutputDirectoryByDbName` 支持 per-DB path | archive DB 可用独立 directory |
| `common/src/main/java/org/tron/common/utils/ByteArray.java:87` | `fromLong` 是 8-byte big-endian | txNum/blockNum key 可按字典序排序 |
| `common/src/main/java/org/tron/common/utils/ByteArray.java:91` | `fromInt` 是 4-byte big-endian | keyLen 编码可复用 |
| `common/src/main/java/org/tron/common/utils/ByteUtil.java:401` | `compare` 要求等长数组 | archive prefix/range 不能直接复用它 |
| `framework/src/main/java/org/tron/core/db/Manager.java:1374-1377` | normal apply path 在 `buildSession` 内 `applyBlock` 后 `tmpSession.commit()` | archive flush 必须在它之后 |
| `framework/src/main/java/org/tron/core/db/Manager.java:1017-1025` | `eraseBlock()` 先取 old head，再 `khaosDb.pop()` 和 `revokingStore.fastPop()` | PR5 在 canonical fastPop 成功后调用 temporal unwind |
| `chainbase/src/main/java/org/tron/core/db2/core/SnapshotManager.java:115 / 119-138` | revoking session 是 canonical DB 回滚机制 | archive sidecar 不应放入 revoking DB |
| `AccountStore.java:68-88` / `AccountTraceStore.java:32-41` | 现有余额历史只覆盖 balance | 不能替代 full temporal store |

### 2.1 Erigon 对 PR5 的约束

Erigon 当前源码给 PR5 的直接约束：

| Erigon 位置 | 事实 | java-tron PR5 约束 |
| --- | --- | --- |
| `db/state/temporal_mem_batch.go:132-149` | `DomainPut/DomainDel` 同时更新 latest overlay 和 history writer | `applyBlock` 内每条 `DomainWrite` 必须同批产生 latest/history/changeset |
| `db/state/domain.go:321-349` | `PutWithPrev/DeleteWithPrev` 先 `AddPrevValue`，再写 after-value | PR5 必须以 collector 的 before-value 为历史事实；不能只存 after-value |
| `db/state/history.go:368-415` | nil before-value 编成空 marker，并写 `key + txNum` history | java-tron 用 `ArchiveValueCodec` tombstone 表达 absent，避免 empty value 歧义 |
| `db/state/inverted_index.go:342-356` | 同时写 key->txNum 与 txNum->key 方向 | java-tron P0 至少保留 `CHANGESET(txNum,domain,key)`，供 unwind/root/debug 扫描 |
| `db/state/domain.go:1384-1412` / `history.go:1209-1222` | `GetAsOf` 找 `>= txNum` 的第一条 history，否则 fallback latest | `HISTORY(domain,key,txNum)` 的 key 排序必须支持 exact-prefix seek |
| `db/state/aggregator.go:2511-2522` | unwind 以 txNum 目标和 diff 恢复 domain | java-tron unwind 必须按 txNum 倒序用 before-value 恢复 latest |

## 3. 关键收敛

PR5 使用一个 physical archive DB：

```text
storage.archive.db.directory = "archive"
physical db name = "archive"
```

内部通过 table prefix 切逻辑表：

```text
META
TXNUM_BLOCK
TXNUM_BY_TXID
TXNUM_META
LATEST
HISTORY
CHANGESET
```

原因：

- java-tron 当前没有跨 physical DB 的 batch transaction。
- PR5 必须让 state/txnum/progress 同批原子提交。
- 后续 root/cold segment 可再拆文件或 DB，P0 先保证一致性。

## 4. 实现顺序

建议拆成以下小 patch：

```text
patch 1: ArchiveRawStore + TreeMap test store
patch 2: ArchiveTable / ArchiveKeyCodec / ArchiveValueCodec
patch 3: Persistent txNum value codec
patch 4: DefaultArchiveTemporalStore applyBlock/getAsOf
patch 5: unwindBlock + progress
patch 6: PersistentArchiveTxNumIndex
patch 7: DefaultArchiveService commit/unwind 接入
patch 8: ArchiveStartupVerifier
patch 9: tests
```

如果 PR5 太大，优先拆：

```text
PR5a: raw store + codec + in-memory temporal tests
PR5b: persistent DB + service/Manager/startup 接入
```

## 5. Patch 1：ArchiveRawStore

### 5.1 新增文件

```text
chainbase/src/main/java/org/tron/core/archive/store/ArchiveRawStore.java
chainbase/src/main/java/org/tron/core/archive/store/DefaultArchiveRawStore.java
chainbase/src/main/java/org/tron/core/archive/store/TreeMapArchiveRawStore.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveDbFactory.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveStoreException.java
```

`TreeMapArchiveRawStore` 只用于 unit test，不接 Spring。

### 5.2 接口

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

要求：

- 所有返回 byte array 都要 copy。
- `ArchiveEntry` 是 immutable copy，不直接暴露 `Map.Entry<byte[], byte[]>`。
- `updateByBatch` 中 value 为 `null` 表示 delete。
- `seek` 返回大于等于 key 的第一项，但调用方必须检查 prefix。
- iterator 必须 try-with-resources 关闭。
- `limit <= 0` 必须拒绝，避免无界扫描。

### 5.3 DefaultArchiveRawStore

`DefaultArchiveRawStore` 直接封装 `DbSourceInter<byte[]>` 更合适，不建议复用 `LevelDB/RocksDB` 的 `Flusher`：

```text
LevelDB/RocksDB Flusher 使用 WrappedByteArray -> WrappedByteArray
无法表达 value=null delete
```

构建逻辑：

```java
String engine = CommonParameter.getInstance().getStorage().getDbEngine();
ArchiveConfig archive = CommonParameter.getInstance().getArchive();
String dbName = "archive";
String archiveDir = archive.getDb().getDirectory();
```

建议路径：

```text
baseOutput = StorageUtils.getOutputDirectory()
archiveOutput = Paths.get(baseOutput, archiveDir)

LEVELDB:
  new LevelDbDataSourceImpl(archiveOutput, "archive")

ROCKSDB:
  parent = Paths.get(archiveOutput, CommonParameter.getInstance().getStorage().getDbDirectory())
  new RocksDbDataSourceImpl(parent, "archive")
```

注意：

- 普通 `StorageUtils.getOutputDirectoryByDbName("archive")` 会受 `storage.properties` 影响；archive sidecar 更适合明确使用 `storage.archive.db.directory`。
- 如果希望支持 per-DB path override，可在后续给 `storage.archive.db.path` 单独加配置，不要复用普通 Store 的 property map。

### 5.4 ArchiveBatch

新增：

```text
chainbase/src/main/java/org/tron/core/archive/store/ArchiveBatch.java
```

实现内部不要用裸 `byte[]` 去重：

```java
private final LinkedHashMap<WrappedByteArray, byte[]> rows = new LinkedHashMap<>();
```

API：

```java
void put(byte[] key, byte[] value);
void delete(byte[] key);
Map<byte[], byte[]> toRawMap();
boolean containsKey(byte[] key);
Optional<byte[]> get(byte[] key);
void commit();
```

规则：

- 最后一次 put/delete wins。
- `value == null` 只能来自 `delete`。
- `containsKey(key)` 区分未 stage 与 staged delete；`get(key)` 对 staged delete 返回 empty。
- `toRawMap` 输出时 copy key/value。

## 6. Patch 2：ArchiveTable 和 KeyCodec

### 6.1 文件

```text
chainbase/src/main/java/org/tron/core/archive/store/ArchiveTable.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveKeyCodec.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveKeyRange.java
```

### 6.2 ArchiveTable

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

### 6.3 Key layout

`domainId` 统一使用 `u16`，与模块 02 的 `ArchiveDomain.domainId` 一致。

```text
META:
  table_u8 | asciiName

TXNUM_BLOCK:
  table_u8 | blockNum_u64

TXNUM_BY_TXID:
  table_u8 | txIdLen_u32 | txId

TXNUM_META:
  table_u8 | txNum_u64

LATEST:
  table_u8 | domainId_u16 | keyLen_u32 | canonicalKey

HISTORY:
  table_u8 | domainId_u16 | keyLen_u32 | canonicalKey | txNum_u64

CHANGESET:
  table_u8 | txNum_u64 | domainId_u16 | keyLen_u32 | canonicalKey
```

`HISTORY` prefix：

```text
table_u8 | domainId_u16 | keyLen_u32 | canonicalKey
```

`GetAsOf` seek key：

```text
historyPrefix(domain,key) | asOfTxNum_u64
```

`CHANGESET` tx range：

```text
from = table_u8 | firstTxNum_u64
to   = table_u8 | (lastTxNum + 1)_u64
```

如果 `lastTxNum == Long.MAX_VALUE`，`changesetRange` 必须拒绝，避免 upper-bound 溢出。

### 6.4 Comparator / prefix helper

不要直接使用 `ByteUtil.compare` 做 variable-length key 比较，因为它要求两个数组等长。

新增 helper：

```java
static boolean startsWith(byte[] key, byte[] prefix);
static int compareUnsignedLexicographic(byte[] left, byte[] right);
static byte[] exclusiveUpperBoundForTxNum(long txNum);
```

`TreeMapArchiveRawStore` 使用同一 comparator，避免单元测试和 LevelDB/RocksDB 顺序不一致。

## 7. Patch 3：Value codec

### 7.1 文件

```text
chainbase/src/main/java/org/tron/core/archive/store/ArchiveValueCodec.java
chainbase/src/main/java/org/tron/core/archive/store/HistoryValue.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveProgress.java
```

### 7.2 Nullable value

不要用 empty bytes 表示 missing。

```text
0x00                     -> tombstone / not exists
0x01 | valueLen_u32 | v  -> present value, valueLen 可以为 0
```

API：

```java
byte[] encodeNullable(byte[] value);
Optional<byte[]> decodeNullable(byte[] encoded);
boolean isTombstone(byte[] encoded);
```

规则：

- `null` -> tombstone。
- `new byte[0]` -> present empty。
- generic codec 可以编码 present 32-byte zero；但 S5 已规定 `CONTRACT_STORAGE` zero 在 semantic hook 中归一为 tombstone/null。

### 7.3 Latest value

`LATEST` value 建议：

```text
lastTxNum_u64 | nullableValue
```

理由：

- latest 读取能知道最后修改 txNum。
- apply 校验可以判断 stale write。

### 7.4 Progress value

```text
schemaVersion_u32
appliedBlockNum_u64
blockHashLen_u32 | blockHash
nextTxNum_u64
registryChecksumLen_u32 | registryChecksum
coverageLen_u32 | coverageAscii
status_u8
```

`status`：

```text
0 = EMPTY
1 = OK
2 = ARCHIVE_GAP
3 = REPAIR_REQUIRED
```

## 8. Patch 4：Persistent txNum codec

文件：

```text
chainbase/src/main/java/org/tron/core/archive/store/TxNumValueCodec.java
chainbase/src/main/java/org/tron/core/archive/store/BlockRangeValueCodec.java
```

### 8.1 TxNumMeta value

```text
txNum_u64
blockNum_u64
txIndex_i32
phase_u8
blockHashLen_u32 | blockHash
txIdLen_u32 | txId, txIdLen=0 表示 null
```

### 8.2 BlockTxNumRange value

```text
blockNum_u64
blockHashLen_u32 | blockHash
firstTxNum_u64
lastTxNum_u64
userTxCount_u32
systemTxCount_u32
```

`txIndex=-1` 用 signed int big-endian。

## 9. Patch 5：ArchiveTemporalStore API

文件：

```text
chainbase/src/main/java/org/tron/core/archive/store/ArchiveTemporalStore.java
chainbase/src/main/java/org/tron/core/archive/store/DefaultArchiveTemporalStore.java
```

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
  private final byte[] value;
}
```

## 10. Patch 6：applyBlock

### 10.1 输入校验

`applyBlock(BlockWriteSet block)` 开头校验：

```text
archive enabled
registry checksum matches progress
block txWriteSets sorted by txNum
block range contiguous
progress.nextTxNum == block.firstTxNum
blockNum == progress.appliedBlockNum + 1, unless EMPTY/bootstrap
```

如果 canonical DB 已经 commit，而 archive apply 失败，节点应抛出 archive error 并在下次启动报告 `ARCHIVE_GAP/REPAIR_REQUIRED`。不要静默跳过。

### 10.2 写入流程

伪代码：

```java
ArchiveBatch batch = rawStore.newBatch();
Map<DomainKey, Optional<byte[]>> latestOverlay = new LinkedHashMap<>();

for (TxWriteSet tx : block.getTxWriteSets()) {
  putTxNumMeta(batch, tx.getTxNumMeta());
  putTxIdIndex(batch, tx.getTxNumMeta());

  for (DomainWrite write : tx.getWrites()) {
    Optional<byte[]> current = readCurrentValue(write.domain(), write.key(), latestOverlay);
    validateBeforeValue(current, write.getBeforeValue());
    if (sameValue(write.getBeforeValue(), write.getAfterValue())) {
      stats.sameValueSkipped++;
      continue;
    }
    putHistory(batch, write, tx.txNum());
    putChangeset(batch, write, tx.txNum());
    putLatestOrDelete(batch, write, tx.txNum());
    latestOverlay.put(domainKey(write), Optional.ofNullable(copy(write.getAfterValue())));
  }
}

putBlockRange(batch, block.range());
putProgress(batch, nextProgress(block));
batch.commit();
```

### 10.3 latest 校验

严格校验：

```text
current latest nullable value == write.beforeValue
```

如果 mismatch：

- strict：抛异常，标记 `REPAIR_REQUIRED`。
- observe 模式仅用于开发，不应用于可信 archive。

P0 推荐 strict。

### 10.4 no writes tx

即使某个 `TxWriteSet` 没有 writes，也要持久化 `TXNUM_META`。否则 `TX_AFTER/TX_BEFORE` 会缺坐标。

### 10.5 same-value write

same-value 不写 history/latest/change，但保留 txNum meta。统计放在 progress 或 diagnostics。

## 11. Patch 7：getAsOf

算法：

```java
Optional<byte[]> getAsOf(domain, key, asOfTxNum) {
  byte[] prefix = historyPrefix(domain, key);
  byte[] seekKey = historyKey(domain, key, asOfTxNum);
  Optional<ArchiveEntry> next = rawStore.seek(seekKey);

  if (next.isPresent() && ArchiveKeyCodec.startsWith(next.get().getKey(), prefix)) {
    return valueCodec.decodeNullable(next.get().getValue());
  }

  Optional<VersionedValue> latest = getLatest(domain, key);
  return latest.map(VersionedValue::getValue);
}
```

要点：

- history 存 before-value。
- seek 找 `txNum >= asOfTxNum` 的第一条变化。
- 如果没有未来变化，回退 latest。
- `seek` 命中后必须检查 prefix，否则会把下一个 key 的 history 当成当前 key。

## 12. Patch 8：changeset 和 unwindBlock

### 12.1 ChangedKey

文件：

```text
chainbase/src/main/java/org/tron/core/archive/store/ChangedKey.java
```

字段：

```java
private final long txNum;
private final ArchiveDomain domain;
private final byte[] canonicalKey;
```

### 12.2 scan changes

```text
range = ArchiveKeyCodec.changesetRange(firstTxNum, lastTxNum)
from  = range.fromInclusive()
to    = range.toExclusive()
rows = rawStore.range(from, to, limit)
```

`changesetRange` 内部必须拒绝 `lastTxNum == Long.MAX_VALUE`，避免 upper-bound 溢出。

P0 可 forward scan 后 reverse。单 block unwind 数据量可控。

### 12.3 unwindBlock

伪代码：

```java
BlockTxNumRange range = getBlockRange(blockNum).orElseThrow();
verify blockHash;
List<ChangedKey> changes = scanChanges(range.firstTxNum, range.lastTxNum);
Collections.reverse(changes);

ArchiveBatch batch = rawStore.newBatch();
for (ChangedKey change : changes) {
  byte[] historyKey = historyKey(change.domain, change.key, change.txNum);
  byte[] before = rawStore.get(historyKey);
  if (isTombstone(before)) {
    batch.delete(latestKey(change.domain, change.key));
  } else {
    batch.put(latestKey(change.domain, change.key), encodeLatest(change.txNum - 1, decode(before)));
  }
  batch.delete(historyKey);
  batch.delete(changesetKey(change.txNum, change.domain, change.key));
}

delete txNum meta / txId index for range
delete block range
put previous progress
batch.commit();
```

注意：

- 如果 before 是 tombstone，latest 应 delete 或写 tombstone？P0 推荐删除 latest key，保持 missing 表示不存在。
- 同一 key 在 unwind range 内多次变化，倒序恢复后最终回到 range 前状态。
- history/change 删除和 latest 恢复必须同批。

### 12.4 previous progress

从 `TXNUM_BLOCK(blockNum - 1)` 找前一个 block：

```text
exists:
  appliedBlockNum = blockNum - 1
  nextTxNum = previousRange.lastTxNum + 1
missing:
  appliedBlockNum = -1/0 by bootstrap policy
  nextTxNum = 0
```

如果 range 不连续，状态置为 `REPAIR_REQUIRED`。

## 13. Patch 9：PersistentArchiveTxNumIndex

文件：

```text
chainbase/src/main/java/org/tron/core/archive/txnum/PersistentArchiveTxNumIndex.java
```

两种方式：

1. `PersistentArchiveTxNumIndex` 直接调用 `ArchiveTemporalStore` 查询。
2. `DefaultArchiveTemporalStore` 实现 txnum index 查询，`PersistentArchiveTxNumIndex` 是 thin wrapper。

建议采用 2，避免 txNum metadata 重复 batch。

需要支持：

```java
nextTxNum()
findByTxId(byte[] txId)
findByTxNum(long txNum)
findBlockRange(long blockNum)
reloadFromProgress()
```

PR5 可以替换 PR2 的 `InMemoryArchiveTxNumIndex` bean；若担心切换过大，可先让 `DefaultArchiveService` 同时写 temporal txnum，重启时再选择 persistent 实现。

## 14. Patch 10：DefaultArchiveService 接入

文件：

```text
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
```

新增注入：

```java
private final ArchiveTemporalStore temporalStore;
```

`commitBlock()`：

```java
public void commitBlock() {
  if (!isEnabled()) {
    return;
  }
  BlockWriteSet blockWriteSet = writeCollector.commitBlock();
  temporalStore.applyBlock(blockWriteSet);
  txNumIndex.completeBlock(currentBlock);
}
```

顺序说明：

- `writeCollector.commitBlock()` 只是生成内存 write set，不落 DB。
- `temporalStore.applyBlock()` 落 `TXNUM_*` 和 state tables。
- `txNumIndex.completeBlock()` 如果仍是 in-memory index，可在 temporal apply 成功后更新；如果已经 persistent thin wrapper，可 no-op。

如果 PR2 仍让 `txNumIndex.completeBlock()` 生成 block range，则 PR5 要避免 double-write。建议 PR5 后 block range 由 temporal store 持久化，txNumIndex 只负责 pending allocation/query wrapper。

`abortBlock()`：

```text
executionContext.clear()
writeCollector.abortBlock()
txNumIndex.abortBlock(currentBlock)
```

`unwindBlock(block)`：

```text
temporalStore.unwindBlock(blockNum, blockHash)
txNumIndex.reloadFromProgress()
```

## 15. Patch 11：Manager/Startup 接入

### 15.1 Manager

PR2 已在 `pushBlock/switchFork` 中保证：

```text
applyBlock
tmpSession.commit()
archiveService.commitBlock()
```

PR5 不需要再改 Manager 正常路径，只要 `ArchiveService.commitBlock()` 内部落 temporal store。

`eraseBlock()` 已调用：

```text
archiveService.unwindBlock(oldHeadBlock)
```

PR5 在该方法里接 temporal unwind。

### 15.2 ArchiveStartupVerifier

新增：

```text
framework/src/main/java/org/tron/core/archive/ArchiveStartupVerifier.java
```

建议 Spring bean：

```java
@Component
public final class ArchiveStartupVerifier {
  @Autowired ArchiveService archiveService;
  @Autowired ChainBaseManager chainBaseManager;
  @PostConstruct
  public void verify() { ... }
}
```

校验：

```text
archive disabled -> no-op
progress EMPTY -> initialize or wait first block
archive appliedBlock == canonical latest && hash match -> OK
archive appliedBlock < canonical latest -> ARCHIVE_GAP
archive appliedBlock > canonical latest -> REPAIR_REQUIRED
same height hash mismatch -> REPAIR_REQUIRED
registry checksum mismatch -> REPAIR_REQUIRED
```

P0 不自动 backfill。`ARCHIVE_GAP` 下历史查询应返回 gap error；archive node 可选择 fail fast。

## 16. Patch 12：测试清单

### 16.1 Codec tests

```text
chainbase/src/test/java/org/tron/core/archive/store/ArchiveKeyCodecTest.java
chainbase/src/test/java/org/tron/core/archive/store/ArchiveValueCodecTest.java
```

覆盖：

- `u64` big-endian 顺序。
- `HISTORY(domain,key,10) < HISTORY(domain,key,11)`。
- 不同 keyLen 不产生 prefix 冲突。
- `CHANGESET` 可按 txNum range scan。
- `domainId` 是 u16，不是 u8。
- tombstone 与 present empty bytes 可区分。
- invalid value prefix 抛异常。

### 16.2 RawStore tests

```text
chainbase/src/test/java/org/tron/core/archive/store/TreeMapArchiveRawStoreTest.java
framework/src/test/java/org/tron/core/archive/store/ArchiveRawStoreLevelDbTest.java
framework/src/test/java/org/tron/core/archive/store/ArchiveRawStoreRocksDbTest.java
```

覆盖：

- seek 返回 next key 时调用方 prefix check 生效。
- prefix/range 正确停止。
- `updateByBatch` value=null 删除。
- iterator 被关闭。
- TreeMap comparator 与 DB lexicographic 顺序一致。

### 16.3 TemporalStore tests

```text
chainbase/src/test/java/org/tron/core/archive/store/DefaultArchiveTemporalStoreTest.java
chainbase/src/test/java/org/tron/core/archive/store/DefaultArchiveTemporalStoreUnwindTest.java
```

覆盖：

| case | 断言 |
| --- | --- |
| single key update chain | `GetAsOf(10/11/12)` 符合 before-tx |
| create key | creation tx 前 not found，之后 latest |
| delete key | delete tx 前旧值，之后 not found |
| same-value write | 不写 history/change，txNum meta 保留 |
| empty block | progress 和 txNum meta 前进，history/change 空 |
| multi tx same key | 每个 txNum 都能读到中间状态 |
| latest mismatch | applyBlock 抛异常并标记 repair |
| duplicate history key | 拒绝重复写 |
| unwind latest block | latest/history/change/txnum/progress 回退 |
| unwind hash mismatch | 标记 `REPAIR_REQUIRED` |

### 16.4 Service/startup tests

```text
framework/src/test/java/org/tron/core/archive/ArchiveServiceTemporalCommitTest.java
framework/src/test/java/org/tron/core/archive/ArchiveStartupVerifierTest.java
```

覆盖：

- `commitBlock()` 在 collector commit 后调用 temporal apply。
- temporal apply 失败时不 complete in-memory txnum index。
- `unwindBlock()` 调 temporal unwind。
- startup `ARCHIVE_GAP` / hash mismatch / checksum mismatch。

## 17. Review Checklist

PR5 合并前检查：

- [ ] P0 只使用一个 physical archive DB。
- [ ] key layout 中 `domainId` 使用 u16。
- [ ] 所有 integer big-endian。
- [ ] variable key 使用 length prefix。
- [ ] `GetAsOf` seek 后必须 prefix check。
- [ ] `ByteUtil.compare` 不用于变长 key 比较。
- [ ] `ArchiveBatch` 不用裸 `byte[]` 做去重 key。
- [ ] value codec 区分 tombstone / present empty / present zero。
- [ ] applyBlock 同批写 latest/history/changeset/txnum/progress。
- [ ] txNum meta 对 empty write-set 也持久化。
- [ ] same-value write 不写 history/change，但不删除 txNum meta。
- [ ] latest 校验 mismatch 会失败。
- [ ] unwind 按 txNum 倒序恢复。
- [ ] canonical commit 与 archive commit 非原子的问题由 startup verifier 检测。
- [ ] PR5 不做 cold segment/freeze，不暗示完整长期压缩已实现。

## 18. 对后续模块的接口承诺

PR6 ArchiveStateReader 依赖：

```text
getAsOf(domain, canonicalKey, asOfTxNum)
getLatest(domain, canonicalKey)
progress / archive gap status
```

PR7 CommitmentBuilder 依赖：

```text
BlockWriteSet persisted in txNum/domain/key order
changeset(txNum,domain,key)
registry checksum in progress
```

PR8 historical `eth_call` 依赖：

```text
ACCOUNT / CONTRACT / CODE / CONTRACT_STORAGE / DYNAMIC_PROPERTIES exact GetAsOf
```

PR9 proof/debug 依赖：

```text
changeset scan
txNum meta
block txNum range
```

因此 PR5 的 key/value schema 一旦发布，不应在后续模块中静默改动；任何 schema 变更都必须改变 registry/progress checksum 并要求迁移或重建。
