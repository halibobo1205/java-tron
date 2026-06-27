# java-tron Archive S6/S7：ArchiveTemporalStore 4e80 编码执行包

> ⚠️ **命名冲突已冻结**：store-key codec 类名以 **L5 `ArchiveStoreKeyCodec`** 为准；本文类体里残留的 `ArchiveKeyCodec` 与 L3 的域 codec 同名碰撞，须改名。物理表前缀同样以 L5 为准（见 module-04 deep-dive banner）。详见 [00-ARCHIVE-V3-AUTHORITY-AND-DECISIONS.md](00-ARCHIVE-V3-AUTHORITY-AND-DECISIONS.md) §2。

日期：2026-06-03

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`，`git status --short` 为空，精确冲突标记扫描无命中。

归属路线：[java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)

模块来源：[模块 04 ArchiveTemporalStore：4e80 java-tron 源码对照细化](./20260603-java-tron-module-04-temporal-store-4e80-source-deep-dive.md)

收窄执行包：[java-tron Archive L5：ArchiveTemporalStore 代码级执行包](./20260605-java-tron-archive-l5-temporal-store-4e80-code-plan.md)

后续真实编码以 L5 收窄执行包为准；本文保留 S6/S7 背景、源码锚点和原始分片。

前置依赖：

- [S1/S2 4e80 编码执行包](./20260603-java-tron-archive-s1-s2-4e80-coding-packet.md)：配置、archive service、txNum lifecycle。
- [S3 ArchiveDomainRegistry 4e80 编码执行包](./20260603-java-tron-archive-s3-domain-registry-4e80-coding-packet.md)：domain id、codec、policy、registry checksum。
- [S4/S5 WriteCollector 与 Storage Semantic Hook 4e80 编码执行包](./20260603-java-tron-archive-s4-s5-write-collector-storage-4e80-coding-packet.md)：`BlockWriteSet`、`TxWriteSet`、`DomainWrite(firstBefore, finalAfter)`。

S6/S7 是 archive sidecar 的持久化批次。S6 固定 raw DB 与 temporal key/value contract；S7 在这个 contract 上实现 block apply、historical read 原语、unwind、startup verifier 和 service/Manager wiring。

## 1. 交付边界

S6 交付：

```text
single physical archive DB
  -> ArchiveRawStore
  -> ArchiveBatch
  -> ArchiveTable / ArchiveKeyCodec
  -> ArchiveValueCodec / LatestValueCodec / TxNum codecs / Progress codec
```

S7 交付：

```text
BlockWriteSet
  -> DefaultArchiveTemporalStore.applyBlock
  -> latest/history/changeset/txnum/progress same batch
  -> getAfterTx/getBeforeTx/getAsOf
  -> unwindBlock
  -> PersistentArchiveTxNumIndex
  -> ArchiveStartupVerifier
```

本批次不交付：

- 不接 JSON-RPC；S8/S9 做。
- 不实现 historical `eth_call`；S12/S13 做。
- 不计算 archive root；S10/S11 做，但本批次预留 root rows。
- 不实现 Erigon segment/freezer；P0 只保证 schema 为后续 freeze 留 txNum range。
- 不把 archive DB 纳入 java-tron revoking DB。
- 不在 canonical `tmpSession.commit()` 成功前持久化 archive。

完成条件：

1. `applyBlock(BlockWriteSet)` 原子写 latest/history/changeset/txnum/progress。
2. `getAsOf(domain,key,targetTxNum)` 能读交易级历史，不读 latest Store 推断历史。
3. `unwindBlock(blockNum,blockHash)` 能恢复 latest 并删除对应 history/changeset/txnum/root/progress。
4. startup verifier 能识别 archive ahead、behind、hash mismatch、缺行 corrupt。
5. `ArchiveRawStore` 支持 LevelDB/RocksDB batch delete，且 fake store 排序等价 unsigned lexicographic。
6. S8/S11 后续只消费本批次 schema，不再定义第二套 temporal key/value。

## 2. 4e80 源码锚点

### 2.1 DB 抽象与 batch 能力

| 源码 | 当前事实 | S6 约束 |
| --- | --- | --- |
| `chainbase/.../db2/common/DB.java:8-22` | 只有 `get/put/remove/iterator/close/getDbName` | 不适合作为 temporal raw store 唯一 API |
| `BatchSourceInter.java:25-29` | `updateByBatch(Map<K,V>)` 和带 `WriteOptionsWrapper` 的重载 | archive batch 应落在这个抽象上 |
| `DbSourceInter.java:32-65` | 继承 batch source，支持 `prefixQuery`，并可迭代 | `DefaultArchiveRawStore` 应封装 `DbSourceInter<byte[]>` |
| `TronDatabase.java:37-48` | 根据 `storage.dbEngine` 分 LevelDB/RocksDB 构造路径 | `ArchiveDbFactory` 要复用这套引擎分支 |
| `TronDatabase.java:63-64` | `updateByBatch` 调底层 `dbSource.updateByBatch(rows, writeOptions)` | 可复用 write options 策略 |
| `LevelDbDataSourceImpl.java:404-418` | batch 中 `value == null` 执行 `batch.delete(key)` | `ArchiveBatch.delete` 可用 null-delete |
| `RocksDbDataSourceImpl.java:301-314` | RocksDB batch 中 `value == null` 执行 delete | LevelDB/RocksDB 删除语义一致 |
| `LevelDB.java:59-63`、`RocksDB.java:60-64` | `Flusher.flush(Map<WrappedByteArray, WrappedByteArray>)` 会取 `value.getBytes()` | `Flusher` 不能表达 null-delete，不用于 S6 batch |
| `DBIterator.java:13-17` | iterator 支持 `seek/seekToFirst/seekToLast` | `getAsOf` 和 range scan 可用 seek |
| `DBIterator.java:44-56` | key/value slice 只在 iterator 下次移动前有效 | `ArchiveEntry` 必须 defensive copy |
| `LevelDbDataSourceImpl.java:366-379`、`RocksDbDataSourceImpl.java:381-397` | `prefixQuery` 无 limit | archive 不把它作为唯一公开 prefix API，避免无界扫描 |
| `WrappedByteArray.java:14-24` | `of` 不 copy，`copyOf` 才 copy | batch/fake store 内部必须 copy key |

### 2.2 编码基础

| 源码 | 当前事实 | S6 约束 |
| --- | --- | --- |
| `common/.../ByteArray.java:87-92` | `fromLong/fromInt` 是 big-endian | txNum/blockNum/keyLen 可按字典序排序 |
| `common/.../Sha256Hash.java:303-308` | `getBytes()` 返回内部数组 | blockHash/txId 写 archive 前必须 copy |
| `StorageUtils.java:22-28` | 普通 Store 支持 per-DB output path | archive sidecar 优先走 `storage.archive.db.directory`，不混用 canonical Store override |

### 2.3 canonical commit / rollback 边界

| 源码 | 当前事实 | S7 约束 |
| --- | --- | --- |
| `Manager.java:1379-1381` | normal path 在 revoking session 内 `applyBlock(newBlock, txs)` 后 `tmpSession.commit()` | archive `applyBlock` 只能在 commit 成功后 |
| `Manager.java:1382-1386` | apply/commit 异常会 remove khaos block 并 rethrow | archive pending block 必须 abort，不写 sidecar |
| `Manager.java:1388-1389` | `blockTrigger` 在 commit 后 | archive flush 应在 trigger 前，避免事件/RPC 看见 canonical head 但 archive behind |
| `Manager.java:1034-1041` | `eraseBlock()` 先取 old head，再 `khaosDb.pop()`、`revokingStore.fastPop()` | archive unwind 放在 `fastPop()` 成功后，使用 oldHead |
| `Manager.java:1142-1149` | fork 新分支 replay 也有 `buildSession/applyBlock/commit` | replay block 成功后也必须 apply archive |
| `Manager.java:1185-1187` | fork 失败 recovery replay 也 commit | recovery replay 不能漏 archive |
| `SnapshotManager.java:119-138` | `buildSession()` 会 `advance()` 并增加 active session | block 内 canonical writes 可回滚 |
| `SnapshotManager.java:207-219` | `commit()` 关闭当前 session 回滚能力 | archive sidecar commit 晚于这里 |
| `SnapshotManager.Session.destroy():607-617` | 未 commit 的 session destroy 会 revoke | S7 abort 只清内存 pending，不写 archive DB |

### 2.4 block/head metadata

| 源码 | 当前事实 | S7 约束 |
| --- | --- | --- |
| `BlockCapsule.java:157-159` | `getTransactions()` 返回 block 原始 tx list | txIndex 必须按原始顺序，不用 filtered `getVerifyTxs` |
| `BlockCapsule.java:209-214` | `getBlockId()` 由 raw header hash + block number 构造 | progress/txnum 保存 `block.getBlockId().getBytes()` 的 copy |
| `BlockCapsule.java:320-322` | `getNum()` 取 header raw number | `BlockWriteSet.blockNum` 必须一致 |
| `BlockCapsule.BlockId.java:444-446` | `BlockId.getNum()` 保存 block number | txnum range 可校验 block id number |
| `ChainBaseManager.java:273-279` | head id/num 来自 `DynamicPropertiesStore` | startup verifier 可读 canonical head |
| `ChainBaseManager.java:325-331` | `getBlockById` 先查 khaos，再查 block store | verifier 可取 canonical block 详情 |
| `DynamicPropertiesStore.java:2157-2173` | latest timestamp/number 从 properties DB 读 | verifier 不需要扫描 block store 找 head |
| `Manager.java:488-520` | `Manager.init()` 初始化 ChainBaseManager/khaosDb head | startup verifier 应在 canonical head 可读之后运行 |

## 3. 物理 DB 与 package

新增 package：

```text
chainbase/src/main/java/org/tron/core/archive/store/
```

一个 physical DB：

```text
physical db name = archive
default directory = storage.archive.db.directory
```

原因：

- java-tron 当前没有跨 physical DB transaction。
- latest/history/changeset/txnum/progress/root 必须能同 batch 原子写。
- P0 不需要多个 DB；后续 freeze/segment 可以按 txNum range 导出 immutable 文件。

`ArchiveDbFactory` 构造规则：

| engine | 构造方式 |
| --- | --- |
| LevelDB | `new LevelDbDataSourceImpl(archiveOutputDirectory, "archive")` |
| RocksDB | `new RocksDbDataSourceImpl(Paths.get(archiveOutputDirectory, storage.dbDirectory), "archive")` |

`archiveOutputDirectory` 来自 S1/S2 `storage.archive.db.directory`。P0 不新增 `storage.archive.db.sync`，write sync 跟随 `CommonParameter.getInstance().getStorage().isDbSync()`，避免 canonical DB 与 archive DB crash 策略分叉。

## 4. 文件落点

### 4.1 S6 raw store / codecs

```text
chainbase/src/main/java/org/tron/core/archive/store/
  ArchiveRawStore.java
  DefaultArchiveRawStore.java
  TreeMapArchiveRawStore.java
  ArchiveBatch.java
  ArchiveEntry.java
  ArchiveStoreException.java
  ArchiveDbFactory.java
  ArchiveTable.java
  ArchiveStoreKeyCodec.java
  ArchiveKeyRange.java
  ArchiveByteComparator.java
  ArchiveValueCodec.java
  LatestValue.java
  LatestValueCodec.java
  TxNumMetaCodec.java
  BlockTxNumRangeCodec.java
  ArchiveProgress.java
  ArchiveProgressCodec.java
  ArchiveProgressStatus.java
```

### 4.2 S7 temporal flow

```text
chainbase/src/main/java/org/tron/core/archive/temporal/
  ArchiveTemporalStore.java
  DefaultArchiveTemporalStore.java
  VersionedValue.java
  ChangedKey.java
  ArchiveTemporalException.java

chainbase/src/main/java/org/tron/core/archive/txnum/
  PersistentArchiveTxNumIndex.java

chainbase/src/main/java/org/tron/core/archive/
  DefaultArchiveService.java

chainbase/src/main/java/org/tron/core/archive/startup/
  ArchiveStartupVerifier.java

framework/src/main/java/org/tron/core/db/
  Manager.java
```

不要新增：

```text
archive-state DB
archive-txnum DB
archive-progress DB
TemporalKeyCodec.java
```

`ArchiveKeyCodec` 是唯一 table key contract。

## 5. ArchiveRawStore

接口：

```java
public interface ArchiveRawStore extends AutoCloseable {
  Optional<byte[]> get(byte[] key);

  void put(byte[] key, byte[] value);

  void delete(byte[] key);

  void updateByBatch(Map<byte[], byte[]> rows);

  ArchiveBatch newBatch();

  Optional<ArchiveEntry> seek(byte[] key);

  List<ArchiveEntry> prefix(byte[] prefix, int limit);

  List<ArchiveEntry> range(byte[] fromInclusive, byte[] toExclusive, int limit);

  @Override
  void close();
}
```

规则：

- `put` 不接受 null value；delete 走 `delete` 或 batch delete。
- `updateByBatch` 中 `value == null` 表示 delete。
- `get/seek/prefix/range` 返回 copy。
- `prefix/range` 必须要求 `limit > 0`。
- `seek` 命中 greater-or-equal key；调用方必须做 prefix/range 检查。
- 所有 iterator 用 try-with-resources 关闭。

`ArchiveEntry` 不暴露 `Map.Entry`：

```java
public final class ArchiveEntry {
  private final byte[] key;
  private final byte[] value;
}
```

constructor 和 getter 都 copy key/value。原因是 `DBIterator` 明确说 slice 只在下次 iterator 移动前有效。

## 6. ArchiveBatch

接口：

```java
public interface ArchiveBatch {
  void put(byte[] key, byte[] value);

  void delete(byte[] key);

  boolean containsKey(byte[] key);

  Optional<byte[]> get(byte[] key);

  int size();

  boolean isEmpty();

  Map<byte[], byte[]> toRawMap();

  void commit();
}
```

实现规则：

```text
internal key: WrappedByteArray.copyOf(key)
ordering: LinkedHashMap insertion order
duplicate key: last write wins
staged delete: key present with null value
containsKey: distinguishes staged delete from missing overlay
toRawMap: key/value copy; null value preserved
```

`TreeMapArchiveRawStore` 用 `ArchiveByteComparator.UNSIGNED_LEXICOGRAPHIC`，让 tests 覆盖 LevelDB/RocksDB 的 unsigned byte ordering。

## 7. ArchiveTable 与 key layout

P0 table prefixes：

```java
public enum ArchiveTable {
  META((byte) 0x01),

  TXNUM_BLOCK((byte) 0x10),
  TXNUM_BY_TXID((byte) 0x11),
  TXNUM_META((byte) 0x12),

  LATEST((byte) 0x20),
  HISTORY((byte) 0x21),
  CHANGESET((byte) 0x22),

  ROOT_RECORD((byte) 0x30),
  COMMITMENT_BRANCH((byte) 0x31),
  COMMITMENT_META((byte) 0x32);
}
```

Root prefixes 只预留；S10/S11 实现 value codec。

编码规则：

```text
u8/u16/u32/u64: unsigned big-endian
domainId: u16 from ArchiveDomain
txNum/blockNum: non-negative u64
keyLen: u32 exact canonicalKey length
```

key layout：

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

`keyLen` 是 mandatory。否则 `key=A` 的 history prefix 会扫到 `key=A||suffix`。

`ArchiveKeyCodec` API：

```java
public final class ArchiveKeyCodec {
  public static byte[] metaKey(String asciiName);
  public static byte[] progressKey();
  public static byte[] registryChecksumKey();

  public static byte[] txNumBlockKey(long blockNum);
  public static byte[] txNumByTxIdKey(byte[] txId);
  public static byte[] txNumMetaKey(long txNum);

  public static byte[] latestKey(ArchiveDomain domain, byte[] canonicalKey);

  public static byte[] historyPrefix(ArchiveDomain domain, byte[] canonicalKey);
  public static byte[] historyKey(ArchiveDomain domain, byte[] canonicalKey, long txNum);
  public static byte[] historySeekAfterKey(ArchiveDomain domain, byte[] canonicalKey,
      long targetTxNum);

  public static byte[] changesetKey(long txNum, ArchiveDomain domain, byte[] canonicalKey);
  public static ArchiveKeyRange changesetRange(long firstTxNum, long lastTxNumInclusive);

  public static ChangedKey decodeChangesetKey(byte[] physicalKey);
  public static long decodeTxNumMetaKey(byte[] physicalKey);
  public static boolean startsWith(byte[] key, byte[] prefix);
}
```

`historySeekAfterKey(domain,key,targetTxNum)` 返回 `historyKey(..., targetTxNum + 1)`，用于 after-tx 语义查找“目标点之后的第一条变化”。如果 `targetTxNum == Long.MAX_VALUE`，直接拒绝。

## 8. Value codecs

### 8.1 nullable archive value

`ArchiveValueCodec`：

```text
0x00                         -> tombstone / absent
0x01 | valueLen_u32 | value  -> present value
```

不能把 empty byte array 当 tombstone。code empty、ABI empty、storage zero 归一后的 tombstone 是不同语义。

### 8.2 latest value

`LatestValueCodec`：

```text
version_u8 | lastTxNum_u64 | ArchiveValueCodec(value)
```

S7 P0 规则：

- present after value：put latest row。
- tombstone after value：delete latest row。
- codec 仍支持 tombstone，为未来兼容和 tests 留口。

### 8.3 txNum metadata

`TxNumMetaCodec` 存：

```text
version_u8
txNum_u64
blockNum_u64
phase_u8
txIndex_i32
txIdLen_u32 | txId
blockHashLen_u32 | blockHash
```

`phase` 来自 S1/S2 `ArchivePhase`：

```text
BLOCK_PREPARE
USER_TX
BLOCK_FINALIZE
```

system phase 没有 txId，`txIdLen=0`；user tx 必须有 txId。

### 8.4 block range

`BlockTxNumRangeCodec` 存：

```text
version_u8
blockNum_u64
firstTxNum_u64
lastTxNum_u64
prepareTxNum_u64
finalizeTxNum_u64
userTxCount_u32
blockHashLen_u32 | blockHash
registryChecksumLen_u32 | registryChecksum
```

`firstTxNum..lastTxNum` 必须覆盖 prepare、user tx、finalize。空块也至少有 prepare/finalize。

### 8.5 progress

`ArchiveProgress`：

```java
public final class ArchiveProgress {
  private final int schemaVersion;
  private final ArchiveProgressStatus status;
  private final long appliedBlockNum;
  private final byte[] appliedBlockHash;
  private final long appliedFinalizedTxNum;
  private final long nextTxNum;
  private final byte[] registryChecksum;
  private final long lastRootBlockNum;
}
```

status：

```text
EMPTY
OK
ARCHIVE_GAP
REPAIR_REQUIRED
```

`Progress` 必须和 block apply/unwind 同 batch 写。不要只保存 `nextTxNum`。

## 9. ArchiveTemporalStore API

```java
public interface ArchiveTemporalStore {
  Optional<VersionedValue> getLatest(ArchiveDomain domain, byte[] canonicalKey);

  Optional<byte[]> getAfterTx(ArchiveDomain domain, byte[] canonicalKey, long txNum);

  Optional<byte[]> getBeforeTx(ArchiveDomain domain, byte[] canonicalKey, long txNum);

  Optional<byte[]> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long targetTxNum);

  void applyBlock(BlockWriteSet blockWriteSet);

  void unwindBlock(long blockNum, byte[] blockHash);

  Optional<BlockTxNumRange> getBlockRange(long blockNum);

  Optional<TxNumMeta> getTxNumMeta(long txNum);

  Optional<TxNumMeta> getTxNumByTxId(byte[] txId);

  ArchiveProgress progress();
}
```

命名约定：

- `getAfterTx(domain,key,txNum)`：读 txNum 执行后的状态。
- `getBeforeTx(domain,key,txNum)`：读 txNum 执行前的状态。
- `getAsOf(domain,key,targetTxNum)`：P0 alias 到 `getAfterTx`，Module 05 用 block finalize txNum 做 historical block selector。

这样 JSON-RPC block-level historical read 不需要猜 offset。

## 10. applyBlock 校验

`DefaultArchiveTemporalStore.applyBlock(BlockWriteSet block)` 开头必须校验：

```text
archive enabled
progress.status in {EMPTY, OK}
registry checksum == block.registryChecksum
registry checksum == progress.registryChecksum, unless progress EMPTY
blockHash non-empty and copied
txWriteSets sorted by txNum ascending
txNum sequence contiguous
block range first/last matches txWriteSets
progress.nextTxNum == block.firstTxNum, unless progress EMPTY/bootstrap
block.blockNum == progress.appliedBlockNum + 1, unless progress EMPTY/bootstrap/backfill mode
```

异常策略：

| progress status | applyBlock 行为 |
| --- | --- |
| `EMPTY` | 只允许明确 bootstrap/backfill 的第一个 archive block |
| `OK` | 正常 apply |
| `ARCHIVE_GAP` | 拒绝 apply，要求 backfill/repair |
| `REPAIR_REQUIRED` | 拒绝 apply，要求人工 rebuild/repair |

如果 canonical DB 已 commit，而 archive apply 失败，节点应 fail-fast。下次启动 verifier 会报告 archive behind/corrupt，不能 silent fallback latest。

## 11. applyBlock 写入流程

同一个 block 内多个 tx 可写同一个 key，所以 apply 必须有 latest overlay：

```text
Map<DomainKey, Optional<byte[]>> latestOverlay
```

读取 current value 顺序：

```text
if latestOverlay contains key:
  current = latestOverlay[key]
else:
  current = raw latest
```

伪代码：

```text
applyBlock(block):
  progress = progress()
  validate(block, progress)
  batch = rawStore.newBatch()
  latestOverlay = new LinkedHashMap()

  for tx in block.txWriteSets:
    batch.put(TXNUM_META(tx.txNum), encode(tx.meta))
    if tx.meta.txId present:
      batch.put(TXNUM_BY_TXID(txId), encode(tx.meta))

    for write in tx.writes:
      current = readCurrent(write.domain, write.key, latestOverlay)
      if current != write.firstBefore:
        markRepairRequired("latest/before mismatch")
        throw ArchiveTemporalException

      if write.firstBefore == write.finalAfter:
        continue

      batch.put(HISTORY(domain,key,txNum), ArchiveValueCodec.encode(write.firstBefore))
      batch.put(CHANGESET(txNum,domain,key), empty)

      if write.finalAfter is tombstone:
        batch.delete(LATEST(domain,key))
        latestOverlay[domain,key] = empty
      else:
        batch.put(LATEST(domain,key), LatestValueCodec.encode(txNum, write.finalAfter))
        latestOverlay[domain,key] = write.finalAfter

  batch.put(TXNUM_BLOCK(block.blockNum), BlockTxNumRangeCodec.encode(block.range))
  batch.put(META(progress), ArchiveProgressCodec.encode(nextProgress(block)))
  batch.commit()
```

规则：

- same-value write 不写 history/changeset/latest。
- no-write tx 仍写 `TXNUM_META`；user tx 仍写 `TXNUM_BY_TXID`。
- empty block 仍写 txnum range 和 progress。
- `latest/history/changeset/txnum/progress` 必须同 batch。
- `DomainKey` 不能用裸 `byte[]` 做 map key；必须按 domainId + canonicalKey bytes equality。

## 12. getLatest / getAsOf

`getLatest`：

```text
raw latest missing -> Optional.empty
latest tombstone   -> Optional.empty
present            -> VersionedValue(lastTxNum,value)
```

`getAfterTx(domain,key,targetTxNum)`：

```text
guardReadable(progress, targetTxNum)

latest = getLatest(domain,key)
if latest exists and latest.lastTxNum <= targetTxNum:
  return latest.value

prefix = historyPrefix(domain,key)
seekKey = historySeekAfterKey(domain,key,targetTxNum)
entry = rawStore.seek(seekKey)
if entry exists and startsWith(entry.key,prefix):
  return ArchiveValueCodec.decodeNullable(entry.value)

return latest.value if present else empty
```

例子：

```text
tx10: A 100 -> 70
tx11: A 70  -> 50
latest(lastTxNum=11): A 50

getAfterTx(A, 9)  = 100
getAfterTx(A, 10) = 70
getAfterTx(A, 11) = 50
```

`getBeforeTx(A, 10)` 可以：

1. 先查 `HISTORY(A,10)` exact key，命中则返回 before。
2. 未命中时返回 `getAfterTx(A, 9)`。

read guard：

| progress | 读取行为 |
| --- | --- |
| `OK` and `targetTxNum < progress.nextTxNum` | allow |
| `OK` but `targetTxNum >= progress.nextTxNum` | archive gap error |
| `EMPTY` | 只允许 genesis/bootstrap 约定范围 |
| `ARCHIVE_GAP` | gap error |
| `REPAIR_REQUIRED` | corrupt/repair error |

prefix check 是 mandatory。`seek` 返回 greater-or-equal key，可能是下一个 domain/key 的 history。

## 13. changeset 与 unwind

`CHANGESET` key：

```text
0x22 | txNum_u64 | domainId_u16 | keyLen_u32 | canonicalKey
```

P0 value 可为空 bytes；unwind 从 key 解出 changed key，再读取 `HISTORY` before value。

`unwindBlock(blockNum, blockHash)`：

```text
progress = progress()
validate progress.appliedBlockNum == blockNum
validate progress.appliedBlockHash == blockHash
range = TXNUM_BLOCK(blockNum)
validate range.blockHash == blockHash

changes = scan CHANGESET [range.firstTxNum, range.lastTxNum] with upper bound
sort changes by txNum desc

batch = rawStore.newBatch()
for change in changes:
  historyKey = HISTORY(domain,key,change.txNum)
  before = decode history value
  if history missing:
    markRepairRequired("missing history row")
    throw

  if before tombstone:
    batch.delete(LATEST(domain,key))
  else:
    batch.put(LATEST(domain,key), LatestValueCodec.encode(change.txNum - 1, before))

  batch.delete(historyKey)
  batch.delete(CHANGESET(change.txNum,domain,key))

delete TXNUM_META for every txNum in block range
delete TXNUM_BY_TXID for user tx meta
delete TXNUM_BLOCK(blockNum)
delete ROOT_RECORD(blockNum) if present
update META(progress) to parent block
batch.commit()
```

倒序恢复能处理同一 key 多 tx 变化：

```text
tx10: A 100 -> 90
tx11: A 90  -> 70

unwind tx11 -> latest 90
unwind tx10 -> latest 100
```

`LatestValue.lastTxNum` 在 unwind 后 P0 可写 `changeTxNum - 1`。`getAfterTx` 正确性依赖 history seek，不依赖该字段精确等于“上一次修改 txNum”。若 Module 06 后续需要精确 lastTxNum，可在 `HISTORY` value 扩展 `beforeLastTxNum` 并 bump schema。

changeset scan 必须有 expected limit。如果 scan rows 达到 limit，标记 `REPAIR_REQUIRED` 并抛异常，不能部分 unwind。

## 14. PersistentArchiveTxNumIndex

S1/S2 的 in-memory index 在 S7 后应替换为 persistent thin wrapper：

```java
public final class PersistentArchiveTxNumIndex implements ArchiveTxNumIndex {
  private final ArchiveTemporalStore temporalStore;
}
```

API 映射：

| TxNumIndex API | temporal row |
| --- | --- |
| `rangeOfBlock(blockNum)` | `TXNUM_BLOCK(blockNum)` |
| `metaOfTxNum(txNum)` | `TXNUM_META(txNum)` |
| `txNumOfTxId(txId)` | `TXNUM_BY_TXID(txId)` |
| `nextTxNum()` | `META(progress).nextTxNum` |

不要另建 txnum DB。txnum rows 必须和 state rows 同 batch。

## 15. DefaultArchiveService / Manager wiring

S7 应把 `DefaultArchiveService.commitBlock` 从“保存 pending write-set”升级为：

```text
commitBlock(block):
  blockWriteSet = collector.finishBlock()
  temporalStore.applyBlock(blockWriteSet)
```

Manager hook 顺序：

```text
normal pushBlock:
  archive.beginBlock(newBlock)
  try (session) {
    applyBlock(newBlock, txs)
    session.commit()
  } catch {
    archive.abortBlock(newBlock)
    throw
  }
  archive.commitBlock(newBlock)   // before blockTrigger
  blockTrigger(...)
```

fork replay/recovery：

```text
try (session) {
  archive.beginBlock(block)
  applyBlock(block.setSwitch(true))
  session.commit()
} catch {
  archive.abortBlock(block)
  throw
}
archive.commitBlock(block)
```

eraseBlock：

```text
oldHeadBlock = chainBaseManager.getBlockById(latestHash)
khaosDb.pop()
revokingStore.fastPop()
archive.unwindBlock(oldHeadBlock)
```

`archive.unwindBlock` 必须放在 `fastPop()` 成功后。如果 `fastPop()` 抛异常，archive 不能提前回退。

## 16. ArchiveStartupVerifier

落点：

```text
chainbase/src/main/java/org/tron/core/archive/startup/ArchiveStartupVerifier.java
```

执行时机：`Manager.init()` 已经完成 `khaosDb.start(chainBaseManager.getBlockById(latestHash))` 之后，canonical head 可读，再执行 verifier。不要在 ChainBaseManager/head 尚未初始化前读 archive progress。

校验：

```text
canonicalHeadNum = chainBaseManager.getHeadBlockNum()
canonicalHeadHash = chainBaseManager.getHeadBlockId().getBytes()
progress = temporalStore.progress()
```

策略：

| 状态 | 处理 |
| --- | --- |
| archive disabled | no-op |
| progress EMPTY and canonical at genesis/bootstrap | OK |
| progress EMPTY and canonical ahead | mark ARCHIVE_GAP，historical read unsupported until backfill |
| progress block > canonical head | unwind archive by txnum rows until <= canonical, or fail-fast if configured strict |
| progress block == canonical but hash mismatch | mark REPAIR_REQUIRED and fail-fast |
| progress block < canonical | mark ARCHIVE_GAP; node may continue latest fullnode, historical archive disabled |
| progress says OK but required txnum/history/progress rows missing | mark REPAIR_REQUIRED and fail-fast |

P0 推荐默认 strict：

```text
archive.enable=true && progress not OK for canonical head -> fail fast
```

如果后续想允许 backfill，必须显式配置 `storage.archive.startup.mode = repair|backfill|strict`，不能 silent fallback latest。

## 17. Patch 分片

### S6a：ArchiveRawStore + fake store

新增 raw store API、`DefaultArchiveRawStore`、`TreeMapArchiveRawStore`、`ArchiveEntry`、`ArchiveBatch`。

测试：

- input/output byte arrays defensive copy。
- duplicate batch key last write wins。
- staged delete 和 missing 可区分。
- prefix/range/seek unsigned lexicographic。
- `limit <= 0` 拒绝。

### S6b：ArchiveDbFactory + LevelDB/RocksDB batch smoke

新增 archive DB factory，封装 `DbSourceInter<byte[]>`。

测试：

- LevelDB `value == null` batch delete 生效。
- RocksDB `value == null` batch delete 生效。
- iterator close 后无 native resource 泄漏迹象。

RocksDB focused test 如果本地环境重，可先标成 integration category，但不要 skip。

### S6c：ArchiveTable / ArchiveKeyCodec

固定 table prefixes 和 key layout。

测试：

- txNum/blockNum key 字典序等于数值序。
- `historyPrefix(domain,key)` 不匹配 `key + suffix`。
- `changesetRange(first,last)` 上下界准确。
- bad negative txNum/blockNum 拒绝。
- domain id 超过 u16 拒绝。

### S6d：Value codecs

实现 nullable value、latest、txNum meta、block range、progress codec。

测试：

- tombstone 与 empty bytes 可区分。
- `Sha256Hash.getBytes()` 输入被 copy。
- progress status round-trip。
- registry checksum round-trip。

### S7a：ArchiveTemporalStore API + applyBlock

实现 applyBlock 校验、latest overlay、same batch commit。

测试：

- single tx put/update/delete。
- 同 block 多 tx 同 key before-value 链。
- same-value write 不产 history/change/latest。
- no-write tx 仍写 tx meta。
- empty block 仍推进 txnum/progress。
- latest/before mismatch 标 repair 并抛异常。

### S7b：getLatest/getAfterTx/getBeforeTx/getAsOf

实现 historical read 原语。

测试：

- 连续 tx 写同 key，每个 txNum 读值正确。
- delete 后历史仍能读删除前值。
- unknown key 返回 missing。
- targetTxNum 超过 progress 报 gap。

### S7c：unwindBlock

实现 changeset scan、倒序恢复、删除 txnum/history/changeset/root/progress。

测试：

- unwind 单 block 恢复 latest。
- 同 key 多 tx 倒序恢复正确。
- tombstone before 删除 latest。
- blockHash mismatch 标 repair。
- missing history 标 repair。
- scan limit 达到上限时拒绝 partial unwind。

### S7d：PersistentArchiveTxNumIndex

把 txNum 查询切到 temporal rows。

测试：

- blockNum -> txNum range。
- txNum -> meta。
- txId -> txNum。
- nextTxNum 从 progress 读取。

### S7e：DefaultArchiveService / Manager wiring

把 canonical commit/unwind 接到 temporal store。

测试：

- normal pushBlock：canonical commit 后 archive commit，且在 blockTrigger 前。
- apply 异常：archive abort，无 sidecar rows。
- fork replay/recovery：archive apply 不漏。
- eraseBlock：fastPop 成功后 archive unwind。

### S7f：ArchiveStartupVerifier

实现 startup progress/head 校验。

测试：

- archive disabled no-op。
- progress/head match OK。
- archive behind -> gap。
- archive ahead -> unwind or fail-fast，按配置。
- same height hash mismatch -> repair required。
- missing txnum/history rows -> repair required。

## 18. 编码检查清单

- [ ] Archive DB 是单 physical `archive` DB。
- [ ] Archive DB 不加入 `RevokingDatabase`。
- [ ] Raw store 封装 `DbSourceInter<byte[]>`，不是 `DB<byte[],byte[]>` 或 `Flusher`。
- [ ] Batch delete 用 raw map `value == null`。
- [ ] All key/value byte arrays defensive copy。
- [ ] Prefix/range scan 有 limit。
- [ ] Key layout 含 `keyLen`，避免 prefix 混淆。
- [ ] `HISTORY` 以 asc txNum 编码，`getAfterTx` seek `targetTxNum + 1`。
- [ ] `CHANGESET` 至少支持 `txNum -> changed keys`。
- [ ] latest/history/changeset/txnum/progress 同 batch。
- [ ] `applyBlock` 校验 progress、registry checksum、txNum 连续性。
- [ ] 同 block latest overlay 校验 before-value。
- [ ] `getAsOf` 不读 java-tron latest Store 推断历史。
- [ ] `unwindBlock` 按 txNum desc 恢复。
- [ ] startup verifier 不 silent fallback latest。
- [ ] Manager normal/fork/recovery/erase 四条路径都接入。

## 19. 建议验证命令

文档阶段不需要运行。进入编码后，优先跑 focused tests：

```bash
./gradlew :chainbase:test --tests '*ArchiveRawStoreTest'
./gradlew :chainbase:test --tests '*ArchiveStoreKeyCodecTest'
./gradlew :chainbase:test --tests '*ArchiveValueCodecTest'
./gradlew :chainbase:test --tests '*DefaultArchiveTemporalStoreTest'
./gradlew :chainbase:test --tests '*DefaultArchiveTemporalStoreUnwindTest'
./gradlew :chainbase:test --tests '*PersistentArchiveTxNumIndexTest'
./gradlew :framework:test --tests '*ArchiveTemporalStoreManagerWiringTest'
./gradlew :chainbase:test --tests '*ArchiveStartupVerifierTest'
```

合并前按 java-tron 规则跑：

```bash
./gradlew lint
./gradlew build
```
