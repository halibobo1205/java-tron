# java-tron Archive L5：ArchiveTemporalStore 代码级执行包

日期：2026-06-05

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 当前基线：`4e80f8ffa9a2`

上游路线：[java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)

落地看板：[java-tron Archive：4e80 分阶段落地执行看板](./20260604-java-tron-archive-4e80-landing-readiness-board.md)

逐文件落点：[java-tron Archive：4e80 逐文件实现落点矩阵](./20260604-java-tron-archive-4e80-file-implementation-map.md)

模块来源：[模块 04 ArchiveTemporalStore：4e80 java-tron 源码对照细化](./20260603-java-tron-module-04-temporal-store-4e80-source-deep-dive.md)

背景执行包：[java-tron Archive S6/S7：ArchiveTemporalStore 4e80 编码执行包](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md)

前置代码级执行包：

- [L1 config/no-op/dbName](./20260604-java-tron-archive-l1-config-noop-dbname-4e80-code-plan.md)
- [L2 Manager lifecycle + txNum](./20260605-java-tron-archive-l2-manager-txnum-4e80-code-plan.md)
- [L3 ArchiveDomainRegistry](./20260605-java-tron-archive-l3-domain-registry-4e80-code-plan.md)
- [L4 WriteCollector + Storage Semantic Hook](./20260605-java-tron-archive-l4-write-collector-4e80-code-plan.md)

本文是 L5 的收窄执行包。后续真实编码以本文为准；S6/S7 旧包保留背景、源码锚点和早期推导。

## 1. L5 位置

L5 是 archive sidecar 的第一个持久化模块：

```text
L4 BlockWriteSet
  -> L5 ArchiveTemporalStore
       latest/history/changeset/txnum/progress
  -> L6 ArchiveStateReader
  -> L7 CommitmentBuilder
```

L5 接收 L4 已经归并好的 `BlockWriteSet`，不再重新扫描 java-tron latest Store，也不重新解析 Store 的 capsule 类型。

L5 完成后必须具备：

1. 单 physical archive DB，多个 logical table prefix。
2. `applyBlock(BlockWriteSet)` 同 batch 写 latest/history/changeset/txnum/progress。
3. `getAfterTx/getBeforeTx/getAsOf` 可按 txNum 读取历史值。
4. `unwindBlock(blockNum, blockHash)` 可用 changeset 精确恢复 latest 并删除 block 相关 temporal rows。
5. `PersistentArchiveTxNumIndex` 从 temporal rows 查询 block range、tx meta、txId mapping、nextTxNum。
6. `ArchiveStartupVerifier` 检测 archive ahead、behind、hash mismatch、schema/checksum drift、缺行 corrupt。

L5 不做：

- 不接 JSON-RPC。
- 不实现 historical `eth_call`。
- 不计算 sidecar root。
- 不实现 proof/debug API。
- 不做 Erigon freezer/segment 文件。
- 不把 archive DB 加入 java-tron revoking DB。
- 不在 canonical `tmpSession.commit()` 成功前写 archive DB。

## 2. Erigon 源码结论

L5 借鉴 Erigon V3 temporal 模型，但不移植 Erigon 的 MDBX/freezer/segment 实现。

| Erigon 源码 | 事实 | L5 转译 |
| --- | --- | --- |
| `db/kv/temporal/kv_temporal.go:541-546` | `GetLatest(domain,key)` 是 latest 读取门面 | `ArchiveTemporalStore.getLatest(domain,key)` |
| `kv_temporal.go:549-558` | `GetAsOf(domain,key,ts)` 是按 txNum 历史读 | `getAfterTx/getAsOf` |
| `kv_temporal.go:561-570` | `HistorySeek(domain,key,ts)` 直接查历史 | `historySeekAfterKey(domain,key,targetTxNum)` |
| `kv_temporal.go:573-587` | `IndexRange` 从 inverted index 找变化 txNums | P0 先实现 `CHANGESET(txNum,domain,key)`，P1 再加 key-oriented index |
| `kv_temporal.go:757-758` | `Unwind(txNumUnwindTo, changeset)` 依赖 changeset 回退 | `unwindBlock` 从 `CHANGESET` + `HISTORY` 恢复 latest |
| `db/state/temporal_mem_batch.go:132-146` | `DomainPut/DomainDel` 同时更新 latest 和 history | `applyBlock` 对每个 `DomainWrite` 同批写 latest/history/changeset |
| `db/state/execctx/domain_shared.go:833-850` | `prevVal` 可外部提供；before==after 跳过 | L4 已提供 `firstBefore/finalAfter`；L5 仍校验 no-op |
| `db/state/domain.go:1389-1422` | `GetAsOf` 先查 history，没命中再读 latest | L5 `getAfterTx` 同样先用 latest shortcut，再 history seek，再 latest/missing |
| `db/state/changeset/state_changeset.go:327-345` | block/hash 维度保存 diff set | L5 `TXNUM_BLOCK` 记录 blockHash，并用 blockHash 校验 unwind |

关键转译：

- Erigon 不靠全量状态快照查询历史，而靠 latest + before-value history + changed-key index。
- Erigon 的 `DomainPut` 会把写入的 txNum 和 before-value 绑定；java-tron 的 L4 `DomainWrite` 已经提供这组信息。
- Erigon unwind 的正确性来自 changeset；java-tron L5 必须把 `CHANGESET` 作为核心表，而不是只保存 latest/history。
- Erigon 的 `GetAsOf` 对 key 的历史查询必须是点查/窄范围 seek；java-tron L5 不能通过扫描全库恢复一个 key。

## 3. java-tron 源码事实

### 3.1 DB 与 batch

| java-tron 源码 | 当前事实 | L5 约束 |
| --- | --- | --- |
| `chainbase/.../db2/common/DB.java:8-22` | 只有 `get/put/remove/iterator/close/getDbName` | 不作为 ArchiveRawStore 的唯一底层 API |
| `chainbase/.../db/common/BatchSourceInter.java:25-29` | `updateByBatch(Map<K,V>)` 和带 `WriteOptionsWrapper` 重载 | L5 batch 封装这个接口 |
| `DbSourceInter.java:32-65` | 继承 batch source，支持 `prefixQuery`，可迭代 | `DefaultArchiveRawStore` 封装 `DbSourceInter<byte[]>` |
| `TronDatabase.java:37-48` | 按 `storage.dbEngine` 分 LevelDB/RocksDB 构造路径 | `ArchiveDbFactory` 复用同一 engine 分支 |
| `TronDatabase.java:63-64` | `updateByBatch(rows, writeOptions)` 下发到底层 source | L5 统一用 `WriteOptionsWrapper` |
| `LevelDbDataSourceImpl.java:411-418` | batch 中 `value == null` 表示 delete | `ArchiveBatch.delete` 可转成 null value |
| `RocksDbDataSourceImpl.java:301-314` | RocksDB batch 同样 `null -> delete` | LevelDB/RocksDB tombstone 语义一致 |
| `LevelDbDataSourceImpl.java:366-379` | `prefixQuery` 无 limit | L5 不把它暴露成无界 API；prefix/range 必须传 limit |
| `RocksDbDataSourceImpl.java:381-397` | RocksDB prefix query 也无 limit | 同上 |
| `DBIterator.java:31-56` | key/value slice 只在 iterator 下次变化前有效 | `ArchiveEntry` 构造和 getter 必须 defensive copy |
| `WrappedByteArray.java:14-24` | `of` 不 copy，`copyOf` 才 copy | fake store 和 batch 内部使用 copy |
| `ByteArray.java:87-92` | `fromLong/fromInt` 是 big-endian | temporal key 可保持数值序 == 字典序 |
| `WriteOptionsWrapper.java:31-44` | 创建 level/rocks options，并支持 `sync(boolean)` | archive DB sync 跟随 `storage.dbSync`，不新增第二套 sync 开关 |

### 3.2 canonical commit / unwind

| java-tron 源码 | 当前事实 | L5 约束 |
| --- | --- | --- |
| `Manager.java:1379-1381` | normal path：`applyBlock(newBlock, txs)` 后 `tmpSession.commit()` | `archive.commitBlock` 必须在 `tmpSession.commit()` 成功后 |
| `Manager.java:1382-1386` | apply/commit 异常会移除 khaos block 并 rethrow | L5 必须只 abort pending，不写 sidecar |
| `Manager.java:1388-1389` | `blockTrigger` 在 commit 后 | archive flush 应在 trigger 前 |
| `Manager.java:1034-1041` | `eraseBlock()` 取 old head 后 `khaosDb.pop()`、`revokingStore.fastPop()` | archive unwind 必须放在 `fastPop()` 成功后 |
| `Manager.java:1142-1149` | fork 新分支 replay 有 `buildSession/applyBlock/commit` | replay 成功后也 apply archive |
| `Manager.java:1185-1187` | fork 失败 recovery replay 也 commit | recovery replay 不能漏 archive |
| `SnapshotManager.java:119-138` | `buildSession()` 创建 revoking session | archive DB 不加入这个 session |
| `SnapshotManager.java:207-219` | `commit()` 关闭当前回滚能力 | archive sidecar 晚于 canonical commit |
| `SnapshotManager.Session.destroy():607-617` | 未 commit session destroy 会 revoke | archive abort 只清内存 pending |

### 3.3 block/head metadata

| java-tron 源码 | 当前事实 | L5 约束 |
| --- | --- | --- |
| `BlockCapsule.java:157-159` | `getTransactions()` 返回原始 tx list | tx meta 与 L2 txIndex 保持原始顺序 |
| `BlockCapsule.java:209-214` | `getBlockId()` 由 header hash + number 构造 | progress/txnum 存 `block.getBlockId().getBytes()` copy |
| `BlockCapsule.java:320-322` | `getNum()` 返回 block number | `BlockWriteSet.blockNum` 必须一致 |
| `ChainBaseManager.java:273-279` | head id/num 从 dynamic properties 读 | startup verifier 用它读 canonical head |
| `ChainBaseManager.java:325-331` | `getBlockById` 先查 khaos，再查 block store | verifier/unwind 可取 canonical block |
| `DynamicPropertiesStore.java:2157-2173` | latest timestamp/number 从 properties DB 读 | verifier 不扫描 block store 找 head |
| `Manager.java:488-520` | `Manager.init()` 初始化 head/khaosDb | verifier 应在 head 可读之后执行 |

## 4. 设计修正

本文相对 S6/S7 背景包做三处收窄：

1. `ArchiveStartupVerifier` 放在 `chainbase/src/main/java/org/tron/core/archive/startup/`，因为它只依赖 `ArchiveTemporalStore`、`ChainBaseManager` 和 `ArchiveDomainRegistry`，不应放进 `framework` core package。`Manager` 只负责调用。
2. L5 temporal key codec 测试命名为 `ArchiveStoreKeyCodecTest`，避免与 L3 的 `ArchiveKeyCodecTest` domain canonical key 测试冲突。
3. L5 不新增第二个 `ArchiveValue.java`。L4 已有 `org.tron.core.archive.write.ArchiveValue`；L5 使用 `ArchiveValueCodec`、`LatestValue`、`VersionedValue` 和 `ArchiveStoredValue`，避免两个包里同名 value wrapper 混淆。

## 5. Package 边界

允许新增：

```text
chainbase/src/main/java/org/tron/core/archive/store/...
chainbase/src/main/java/org/tron/core/archive/temporal/...
chainbase/src/main/java/org/tron/core/archive/startup/...
chainbase/src/main/java/org/tron/core/archive/txnum/PersistentArchiveTxNumIndex.java
chainbase/src/test/java/org/tron/core/archive/store/...
chainbase/src/test/java/org/tron/core/archive/temporal/...
chainbase/src/test/java/org/tron/core/archive/startup/...
```

允许修改：

```text
chainbase/src/main/java/org/tron/core/archive/ArchiveService.java
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
chainbase/src/main/java/org/tron/core/archive/NoopArchiveService.java
chainbase/src/main/java/org/tron/core/archive/ArchiveServiceFactory.java
framework/src/main/java/org/tron/core/db/Manager.java
```

禁止修改：

```text
framework/src/main/java/org/tron/core/services/jsonrpc/...
actuator/src/main/java/org/tron/core/vm/repository/...
actuator/src/main/java/org/tron/core/vm/program/Storage.java
chainbase/src/main/java/org/tron/core/archive/commitment/...
chainbase/src/main/java/org/tron/core/archive/proof/...
```

L5 不能回头修改 L4 hook 语义；如果发现 write-set 不足，记录为 L4 返工项。

## 6. 文件落点

### 6.1 Raw store / physical DB

```text
chainbase/src/main/java/org/tron/core/archive/store/
  ArchiveRawStore.java
  DefaultArchiveRawStore.java
  TreeMapArchiveRawStore.java
  ArchiveBatch.java
  ArchiveEntry.java
  ArchiveDbFactory.java
  ArchiveStoreException.java
  ArchiveByteComparator.java
```

### 6.2 Key/value codecs

```text
chainbase/src/main/java/org/tron/core/archive/store/
  ArchiveTable.java
  ArchiveStoreKeyCodec.java
  ArchiveKeyRange.java
  ArchiveValueCodec.java
  LatestValue.java
  LatestValueCodec.java
  TxNumMetaCodec.java
  BlockTxNumRange.java
  BlockTxNumRangeCodec.java
  ArchiveProgress.java
  ArchiveProgressCodec.java
  ArchiveProgressStatus.java
  ArchiveStoredValue.java
  ChangedKey.java
  ChangeRecord.java
  ChangeRecordCodec.java
```

`ChangeRecord` P0 可以只保存 `txNum/domain/key` 以外的轻量 metadata；before-value 仍从 `HISTORY` 读取。若后续想减少 unwind seek，可扩展 `ChangeRecordCodec`，但必须 bump schema version。

### 6.3 Temporal API

```text
chainbase/src/main/java/org/tron/core/archive/temporal/
  ArchiveTemporalStore.java
  DefaultArchiveTemporalStore.java
  VersionedValue.java
  ArchiveTemporalException.java
  ArchiveGapException.java
  ArchiveRepairRequiredException.java
```

### 6.4 TxNum persistence

```text
chainbase/src/main/java/org/tron/core/archive/txnum/
  PersistentArchiveTxNumIndex.java
```

### 6.5 Startup verifier

```text
chainbase/src/main/java/org/tron/core/archive/startup/
  ArchiveStartupVerifier.java
  ArchiveStartupCheckResult.java
  ArchiveStartupStatus.java
```

`ArchiveStartupVerifier` 是 chainbase 类；`framework` 的 `Manager.init()` 在 canonical head 初始化完成后调用它。

## 7. Physical DB

L5 使用一个 physical DB：

```text
dbName = archive
```

目录规则：

```text
if storage.archive.db.directory is set:
  archiveOutputDirectory = storage.archive.db.directory
else:
  archiveOutputDirectory = StorageUtils.getOutputDirectory()
```

engine 构造：

```java
if storage.dbEngine == LEVELDB:
  new LevelDbDataSourceImpl(archiveOutputDirectory, "archive")

if storage.dbEngine == ROCKSDB:
  parent = Paths.get(archiveOutputDirectory, storage.dbDirectory).toString()
  new RocksDbDataSourceImpl(parent, "archive")
```

sync 策略：

```java
WriteOptionsWrapper.getInstance()
    .sync(CommonParameter.getInstance().getStorage().isDbSync())
```

不要新增 `storage.archive.db.sync`。canonical DB 与 archive DB 不是一个跨 DB transaction；增加不同 sync 策略只会让 crash 语义更难解释。

## 8. ArchiveRawStore

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

- `put` 不接受 null value。
- `delete` 最终转成 `value == null` 的 batch row。
- `updateByBatch` 接收 null value 表示 delete。
- 所有输入 key/value 在进入 store/batch 时 copy。
- 所有输出 key/value 在返回前 copy。
- `prefix/range` 必须要求 `limit > 0`。
- `seek` 返回 greater-or-equal key，调用方必须检查 prefix/range。
- iterator 必须 try-with-resources 关闭。
- `TreeMapArchiveRawStore` 使用 unsigned lexicographic comparator，测试排序与 LevelDB/RocksDB 一致。

`ArchiveEntry`：

```java
public final class ArchiveEntry {
  private final byte[] key;
  private final byte[] value;
}
```

constructor 和 getter 都 copy。不要直接暴露 `Map.Entry<byte[], byte[]>`。

## 9. ArchiveBatch

接口：

```java
public interface ArchiveBatch {
  void put(byte[] key, byte[] value);

  void delete(byte[] key);

  boolean containsKey(byte[] key);

  Optional<byte[]> get(byte[] key);

  boolean isDeleted(byte[] key);

  int size();

  boolean isEmpty();

  Map<byte[], byte[]> toRawMap();

  void commit();
}
```

实现规则：

```text
internal key wrapper = WrappedByteArray.copyOf(key)
duplicate key        = last write wins
staged delete        = key present with null value
containsKey          = true for staged delete
get                  = empty for missing and staged delete
isDeleted            = true only for staged delete
toRawMap             = defensive copy, null values preserved
commit               = rawStore.updateByBatch(toRawMap())
```

不要把 delete 表达成 empty bytes；empty bytes 是合法 archive value。

## 10. ArchiveTable

L5 固定 P0 table prefix：

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

说明：

- `TXNUM_BLOCK` 是 `blockNum -> BlockTxNumRange`。
- `TXNUM_META` 是 `txNum -> TxNumMeta`。
- `TXNUM_BY_TXID` 是 `txId -> txNum/meta pointer`。
- 不再新增第二个 block-to-tx range table；旧口径统一合并到 `TXNUM_BLOCK`。
- root/commitment prefixes 只预留，L7 写 value。

## 11. Key Layout

编码规则：

```text
u8/u16/u32/u64 = unsigned big-endian
domainId       = u16 stable id from L3 registry
txNum/blockNum = non-negative signed long, encoded as u64-compatible big-endian
keyLen         = u32 exact canonicalKey length
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

ROOT_RECORD:
  table_u8 | L7-defined sub-key
```

L5 只预留 `ROOT_RECORD/COMMITMENT_BRANCH/COMMITMENT_META` prefix，不定义 root 子类型。L7 的 [CommitmentBuilder 代码级执行包](./20260605-java-tron-archive-l7-commitment-builder-4e80-code-plan.md) 负责把 `ROOT_RECORD` 扩展为 `ROOT_BY_BLOCK/ROOT_BY_TX/ROOT_CURRENT/ROOT_CHECKPOINT`。

`keyLen` 是 mandatory。否则 `key=A` 的 history prefix 会错误扫到 `A || suffix`。

`ArchiveStoreKeyCodec` API：

```java
public final class ArchiveStoreKeyCodec {
  public static byte[] metaKey(String asciiName);
  public static byte[] progressKey();
  public static byte[] registryChecksumKey();
  public static byte[] schemaVersionKey();

  public static byte[] txNumBlockKey(long blockNum);
  public static byte[] txNumByTxIdKey(byte[] txId);
  public static byte[] txNumMetaKey(long txNum);

  public static byte[] latestKey(ArchiveDomainDescriptor domain, byte[] canonicalKey);

  public static byte[] historyPrefix(ArchiveDomainDescriptor domain, byte[] canonicalKey);
  public static byte[] historyKey(ArchiveDomainDescriptor domain, byte[] canonicalKey, long txNum);
  public static byte[] historySeekAfterKey(ArchiveDomainDescriptor domain, byte[] canonicalKey,
      long targetTxNum);

  public static byte[] changesetKey(long txNum, ArchiveDomainDescriptor domain,
      byte[] canonicalKey);
  public static ArchiveKeyRange changesetRange(long firstTxNum, long lastTxNumInclusive);
  public static ChangedKey decodeChangesetKey(byte[] physicalKey);

  public static byte[] rootRecordKey(long blockNum);

  public static boolean startsWith(byte[] key, byte[] prefix);
}
```

`historySeekAfterKey(domain,key,targetTxNum)` 返回 `historyKey(domain,key,targetTxNum + 1)`。如果 `targetTxNum == Long.MAX_VALUE`，直接拒绝，避免 overflow。

## 12. Value Codecs

### 12.1 ArchiveValueCodec

```text
0x00                         -> tombstone / absent
0x01 | valueLen_u32 | value  -> present value
```

规则：

- empty byte array 必须编码成 `0x01 | 0 | ""`，不是 tombstone。
- storage zero 在 L4 semantic hook 已归一为 tombstone；L5 只保存归一结果。
- decode 遇到 unknown version/tag 直接抛 `ArchiveTemporalException`。

### 12.2 LatestValueCodec

```text
version_u8 | lastTxNum_u64 | ArchiveValueCodec(value)
```

P0 写入规则：

- after present：`put LATEST -> LatestValue(lastTxNum=txNum, value=after)`。
- after tombstone：`put LATEST -> LatestValue(lastTxNum=txNum, value=tombstone)`。
- LATEST tombstone row 表示“当前 head 下该 key 已被删除”，用于区分 never existed 与 deleted。L7 rebuild/root normalizer 看到 tombstone 时应把该 key 当作 absent/delete，不把 tombstone leaf 纳入 state root。

### 12.3 TxNumMetaCodec

```text
version_u8
txNum_u64
blockNum_u64
phase_u8
txIndex_i32
txIdLen_u32 | txId
blockHashLen_u32 | blockHash
```

phase 来自 L2 `ArchivePhase`：

```text
BLOCK_PREPARE
USER_TX
BLOCK_FINALIZE
UNWIND  // only metadata/debug; not part of normal block apply range
```

system phase 没有 txId，`txIdLen=0`。user tx 必须有 txId。

### 12.4 BlockTxNumRangeCodec

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

空块也至少包含 prepare/finalize 两个 state point。

### 12.5 ArchiveProgressCodec

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

Progress row 必须和 latest/history/changeset/txnum rows 同 batch 写。不能先写 progress 再写数据。

## 13. ArchiveTemporalStore API

```java
public interface ArchiveTemporalStore {
  Optional<VersionedValue> getLatest(ArchiveDomainDescriptor domain, byte[] canonicalKey);

  ArchiveStoredValue getAfterTx(ArchiveDomainDescriptor domain, byte[] canonicalKey, long txNum);

  ArchiveStoredValue getBeforeTx(ArchiveDomainDescriptor domain, byte[] canonicalKey, long txNum);

  ArchiveStoredValue getAsOf(ArchiveDomainDescriptor domain, byte[] canonicalKey, long targetTxNum);

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
- `getAsOf(domain,key,targetTxNum)`：P0 alias 到 `getAfterTx`。
- L6 用 block finalize txNum 作为 JSON-RPC historical block selector。

## 14. applyBlock Validation

`DefaultArchiveTemporalStore.applyBlock(BlockWriteSet block)` 开头必须校验：

```text
archive enabled
progress.status in {EMPTY, OK}
registry checksum == block.registryChecksum
registry checksum == progress.registryChecksum unless progress EMPTY
blockHash non-empty and copied
block.txWriteSets sorted by txNum ascending
txNum sequence contiguous
block range first/last matches txWriteSets
progress.nextTxNum == block.firstTxNum unless explicit bootstrap/backfill mode
block.blockNum == progress.appliedBlockNum + 1 unless explicit bootstrap/backfill mode
```

异常策略：

| progress status | applyBlock 行为 |
| --- | --- |
| `EMPTY` | 只允许明确 bootstrap/backfill 的第一个 archive block |
| `OK` | 正常 apply |
| `ARCHIVE_GAP` | 拒绝 apply，要求 backfill/repair |
| `REPAIR_REQUIRED` | 拒绝 apply，要求人工 rebuild/repair |

如果 canonical DB 已 commit 而 archive apply 失败，节点应该 fail-fast。下次启动 verifier 会报告 archive behind/corrupt；不能 silent fallback latest。

## 15. applyBlock Flow

同一 block 内多个 tx 可能写同一 key。L5 必须维护 block 内 latest overlay：

```text
Map<DomainWriteKey, Optional<byte[]>> latestOverlay
```

读取 current value 顺序：

```text
if latestOverlay contains domain/key:
  current = latestOverlay[domain/key]
else:
  current = raw latest row
```

伪代码：

```text
applyBlock(block):
  progress = progress()
  validate(block, progress)

  batch = rawStore.newBatch()
  latestOverlay = new LinkedHashMap()

  for tx in block.txWriteSets ascending:
    batch.put(TXNUM_META(tx.txNum), encode(tx.meta))
    if tx.meta.txId present:
      batch.put(TXNUM_BY_TXID(txId), encode(tx.meta))

    for write in tx.writes ascending domain/key:
      current = readCurrent(write.domain, write.key, latestOverlay)
      if current != write.firstBefore:
        batch.put(META(progress), encode(REPAIR_REQUIRED))
        batch.commit()
        throw ArchiveRepairRequiredException

      if write.firstBefore == write.finalAfter:
        continue

      batch.put(HISTORY(domain,key,tx.txNum), encode(write.firstBefore))
      batch.put(CHANGESET(tx.txNum,domain,key), encodeChangeRecord(write))

      if write.finalAfter is tombstone:
        batch.put(LATEST(domain,key), encodeLatest(tx.txNum, tombstone))
        latestOverlay[domain/key] = tombstone
      else:
        batch.put(LATEST(domain,key), encodeLatest(tx.txNum, write.finalAfter))
        latestOverlay[domain/key] = write.finalAfter

  batch.put(TXNUM_BLOCK(block.blockNum), encode(block.range))
  batch.put(META(progress), encode(nextProgress(block)))
  batch.commit()
```

Rules:

- no-op write 不写 `HISTORY/CHANGESET/LATEST`。
- no-write tx 仍写 `TXNUM_META`。
- user tx 仍写 `TXNUM_BY_TXID`。
- empty block 仍写 prepare/finalize tx meta、block range、progress。
- latest/history/changeset/txnum/progress 必须同 batch。
- `DomainWriteKey` 不能用裸 `byte[]` 做 map key。

Repair marker note:

If before mismatch is found, write `REPAIR_REQUIRED` in its own small batch before throwing. This makes restart verifier fail deterministically instead of reattempting on a corrupt sidecar. Do not mix partially generated temporal rows with the repair marker.

## 16. getLatest / getAfterTx

`getLatest`：

```text
raw latest missing -> Optional.empty
latest tombstone   -> VersionedValue(lastTxNum,tombstone)
latest present     -> VersionedValue(lastTxNum,value)
```

`getAfterTx(domain,key,targetTxNum)`：

```text
guardReadable(progress, targetTxNum)

latest = getLatest(domain,key)
if latest exists and latest.lastTxNum <= targetTxNum:
  return latest.storedValue

prefix = historyPrefix(domain,key)
seekKey = historySeekAfterKey(domain,key,targetTxNum)
entry = rawStore.seek(seekKey)
if entry exists and startsWith(entry.key,prefix):
  return ArchiveValueCodec.decodeStored(entry.value)

if latest exists:
  return latest.storedValue
return ArchiveStoredValue.missing()
```

Prefix check is mandatory. `seek` may return another key's history row.

Example:

```text
tx10: A 100 -> 70
tx11: A 70  -> 50
latest(lastTxNum=11): A 50

getAfterTx(A, 9)  = 100
getAfterTx(A, 10) = 70
getAfterTx(A, 11) = 50
```

Delete/recreate example:

```text
tx10: A 100 -> tombstone
tx20: A tombstone -> 7
latest(lastTxNum=20): A 7

getAfterTx(A, 15) = tombstone
getAfterTx(A, 20) = 7
```

## 17. getBeforeTx / getAsOf

`getBeforeTx(domain,key,txNum)`：

```text
exact = rawStore.get(HISTORY(domain,key,txNum))
if exact exists:
  return decode exact before-value
if txNum == 0:
  return Optional.empty
return getAfterTx(domain,key,txNum - 1)
```

`getAsOf(domain,key,targetTxNum)`：

```text
return getAfterTx(domain,key,targetTxNum)
```

Read guard:

| progress | 读取行为 |
| --- | --- |
| `OK` and `targetTxNum < progress.nextTxNum` | allow |
| `OK` but `targetTxNum >= progress.nextTxNum` | archive gap error |
| `EMPTY` | only genesis/bootstrap explicit range |
| `ARCHIVE_GAP` | gap error |
| `REPAIR_REQUIRED` | corrupt/repair error |

L6/L8 must not catch gap/corrupt and fallback to latest Store.

## 18. CHANGESET

P0 changeset key：

```text
0x22 | txNum_u64 | domainId_u16 | keyLen_u32 | canonicalKey
```

P0 changeset value：

```text
version_u8
op_u8
beforeTag_u8
afterTag_u8
```

The before value itself remains in `HISTORY`. The after value is in `LATEST` if still canonical, or reconstructable from later history only for debug; unwind only needs before.

`changedKeys(fromTxNumInclusive, toTxNumExclusive)`：

```text
range = changesetRange(from, to - 1)
scan rawStore.range(range.from, range.to, limit)
decode keys
```

P0 does not need key-oriented inverted index. If L6/L7 later needs `changedTxNums(domain,key,range)`, add:

```text
CHANGESET_BY_KEY:
  table_u8 | domainId_u16 | keyLen_u32 | canonicalKey | txNum_u64
```

Do not add it in L5 unless a concrete consumer requires it.

## 19. unwindBlock

`unwindBlock(blockNum, blockHash)`：

```text
progress = progress()
validate progress.appliedBlockNum == blockNum
validate progress.appliedBlockHash == blockHash

range = getBlockRange(blockNum)
validate range.blockHash == blockHash

changes = scan CHANGESET [range.firstTxNum, range.lastTxNum] with upper bound
sort changes by txNum desc

batch = rawStore.newBatch()
for change in changes:
  historyKey = HISTORY(domain,key,change.txNum)
  before = rawStore.get(historyKey)
  if before missing:
    markRepairRequiredAndThrow("missing history row")

  if before is tombstone:
    batch.put(LATEST(domain,key), LatestValueCodec.encode(change.txNum - 1, tombstone))
  else:
    batch.put(LATEST(domain,key), LatestValueCodec.encode(change.txNum - 1, before))

  batch.delete(historyKey)
  batch.delete(CHANGESET(change.txNum,domain,key))

for txNum in [range.firstTxNum..range.lastTxNum]:
  meta = getTxNumMeta(txNum)
  if meta.txId present:
    batch.delete(TXNUM_BY_TXID(meta.txId))
  batch.delete(TXNUM_META(txNum))

batch.delete(TXNUM_BLOCK(blockNum))
// L7 stageUnwindBlock handles ROOT_RECORD/COMMITMENT_* rows in the same shared batch.
batch.put(META(progress), encode(parentProgress))
batch.commit()
```

同一 key 多 tx：

```text
tx10: A 100 -> 90
tx11: A 90  -> 70

unwind tx11 -> latest 90
unwind tx10 -> latest 100
```

`LatestValue.lastTxNum = changeTxNum - 1` is acceptable in P0 after unwind. It may be greater than the true previous change txNum, but `getAfterTx` remains correct because it uses history seek when needed. If L7 later requires exact latest modification txNum, extend `HISTORY` value with `beforeLastTxNum` and bump schema version.

Scan limit:

- `unwindBlock` must set an expected max rows limit from block write stats or config.
- If the range scan hits the limit before all rows are consumed, mark `REPAIR_REQUIRED` and throw.
- Never partially unwind.

## 20. PersistentArchiveTxNumIndex

S7 replaces L2 in-memory txNum index with a temporal-backed thin wrapper:

```java
public final class PersistentArchiveTxNumIndex implements ArchiveTxNumIndex {
  private final ArchiveTemporalStore temporalStore;
}
```

Mapping:

| TxNumIndex API | Temporal row |
| --- | --- |
| `rangeOfBlock(blockNum)` | `TXNUM_BLOCK(blockNum)` |
| `metaOfTxNum(txNum)` | `TXNUM_META(txNum)` |
| `txNumOfTxId(txId)` | `TXNUM_BY_TXID(txId)` |
| `nextTxNum()` | `META(progress).nextTxNum` |

Do not create a second txnum DB. txnum rows must be written in the same batch as state rows.

## 21. DefaultArchiveService

L5 upgrades `DefaultArchiveService.commitBlock`:

```text
commitBlock(block):
  blockWriteSet = collector.finishBlock()
  temporalStore.applyBlock(blockWriteSet)
```

Rules:

- `abortBlock` drops collector pending state and does not touch temporal store.
- `commitBlock` may throw; Manager must fail-fast after canonical commit if archive apply fails.
- `unwindBlock(oldHeadBlock)` delegates to `temporalStore.unwindBlock(oldHeadBlock.getNum(), oldHeadBlock.getBlockId().getBytes())`.
- `NoopArchiveService` remains no-op.

L5 must not let `DefaultArchiveService` call JSON-RPC or commitment code.

## 22. Manager Wiring

Normal `pushBlock`:

```text
archive.beginBlock(newBlock, NORMAL)
try (ISession tmpSession = revokingStore.buildSession()) {
  applyBlock(newBlock, txs)
  tmpSession.commit()
} catch (Throwable t) {
  archive.abortBlock(newBlock)
  throw t
}
archive.commitBlock(newBlock)  // after canonical commit, before blockTrigger
blockTrigger(newBlock, oldSolidNum, newSolidNum)
```

Fork replay:

```text
archive.beginBlock(item.getBlk(), REPLAY)
try (ISession tmpSession = revokingStore.buildSession()) {
  applyBlock(item.getBlk().setSwitch(true))
  tmpSession.commit()
} catch (...) {
  archive.abortBlock(item.getBlk())
  throw
}
archive.commitBlock(item.getBlk())
```

Fork recovery replay:

```text
archive.beginBlock(khaosBlock.getBlk(), RECOVERY)
try (ISession tmpSession = revokingStore.buildSession()) {
  applyBlock(khaosBlock.getBlk().setSwitch(true))
  tmpSession.commit()
} catch (...) {
  archive.abortBlock(khaosBlock.getBlk())
  throw
}
archive.commitBlock(khaosBlock.getBlk())
```

Erase block:

```text
oldHeadBlock = chainBaseManager.getBlockById(latestHash)
khaosDb.pop()
revokingStore.fastPop()
archive.unwindBlock(oldHeadBlock)  // only after fastPop succeeds
```

If `fastPop()` throws, archive must not unwind.

## 23. ArchiveStartupVerifier

Location:

```text
chainbase/src/main/java/org/tron/core/archive/startup/ArchiveStartupVerifier.java
```

Call site:

```text
Manager.init()
  ChainBaseManager.init(...)
  ...
  khaosDb.start(chainBaseManager.getBlockById(latestHash))
  archiveStartupVerifier.verify()
```

Inputs:

```java
ArchiveTemporalStore temporalStore;
ArchiveDomainRegistry domainRegistry;
ChainBaseManager chainBaseManager;
```

Checks:

```text
canonicalHeadNum  = chainBaseManager.getHeadBlockNum()
canonicalHeadHash = chainBaseManager.getHeadBlockId().getBytes()
progress          = temporalStore.progress()
```

Status matrix:

| 状态 | L5 P0 行为 |
| --- | --- |
| archive disabled | no-op |
| progress EMPTY and canonical at genesis/bootstrap | OK |
| progress EMPTY and canonical ahead | ARCHIVE_GAP; if archive enabled strict, fail-fast |
| progress block > canonical head | fail-fast or explicit repair mode unwind; P0 default fail-fast |
| progress block == canonical but hash mismatch | mark REPAIR_REQUIRED and fail-fast |
| progress block < canonical | mark ARCHIVE_GAP and fail-fast when archive enabled |
| registry checksum mismatch | mark REPAIR_REQUIRED and fail-fast |
| progress OK but txnum/history/changeset rows missing | mark REPAIR_REQUIRED and fail-fast |

P0 default:

```text
storage.archive.enable=true && progress not OK for canonical head -> fail fast
```

If later adding backfill/repair mode, make it explicit:

```text
storage.archive.startup.mode = strict | backfill | repair
```

Never silently continue historical reads against latest Store.

## 24. Tests

### 24.1 Raw store tests

`chainbase/src/test/java/org/tron/core/archive/store/ArchiveRawStoreTest.java`

Test methods:

```text
putGetCopiesInputAndOutput
deleteUsesNullBatchValue
batchDuplicateKeyLastWriteWins
batchDeleteDistinctFromMissing
seekReturnsGreaterOrEqual
prefixRequiresPositiveLimit
rangeRequiresPositiveLimit
treeMapUsesUnsignedLexicographicOrder
levelDbBatchDeleteSmoke
rocksDbBatchDeleteSmoke
```

RocksDB smoke can be integration-tagged if local startup is heavy, but do not skip it.

### 24.2 Key codec tests

`chainbase/src/test/java/org/tron/core/archive/store/ArchiveStoreKeyCodecTest.java`

```text
txNumKeysSortByNumericOrder
blockNumKeysSortByNumericOrder
historyPrefixDoesNotMatchLongerCanonicalKey
historySeekAfterRejectsMaxTxNum
changesetRangeCoversOnlyRequestedTxNums
decodeChangesetKeyRoundTrip
negativeTxNumRejected
domainIdOverflowRejected
```

### 24.3 Value codec tests

`chainbase/src/test/java/org/tron/core/archive/store/ArchiveValueCodecTest.java`

```text
tombstoneRoundTrip
emptyBytesArePresentValue
latestValueRoundTrip
txNumMetaRoundTripSystemPhase
txNumMetaRoundTripUserTx
blockRangeRoundTrip
progressRoundTrip
unknownCodecVersionRejected
inputArraysAreCopied
```

### 24.4 Temporal apply/read tests

`chainbase/src/test/java/org/tron/core/archive/temporal/DefaultArchiveTemporalStoreTest.java`

```text
applySinglePutThenReadLatest
applyUpdateStoresHistoryBeforeValue
applyDeleteRemovesLatestButHistoryReadsOldValue
sameValueWriteSkipped
noWriteTxStillWritesTxMeta
emptyBlockStillAdvancesProgress
sameBlockMultipleTxSameKeyUsesOverlay
beforeMismatchMarksRepairRequired
getAfterTxAcrossCreateUpdateDeleteRecreate
getBeforeTxUsesExactHistory
targetTxBeyondProgressThrowsGap
```

### 24.5 Temporal unwind tests

`chainbase/src/test/java/org/tron/core/archive/temporal/DefaultArchiveTemporalStoreUnwindTest.java`

```text
unwindOneBlockRestoresLatest
unwindSameKeyMultipleTxInDescendingOrder
unwindTombstoneBeforeDeletesLatest
unwindDeletesHistoryChangesetAndTxMeta
unwindDeletesTxIdMapping
unwindDeletesRootRecordIfPresent
unwindBlockHashMismatchMarksRepair
unwindMissingHistoryMarksRepair
unwindScanLimitReachedRejectsPartialUnwind
```

### 24.6 Persistent txNum tests

`chainbase/src/test/java/org/tron/core/archive/txnum/PersistentArchiveTxNumIndexTest.java`

```text
rangeOfBlockReadsTxNumBlockRow
metaOfTxNumReadsTxNumMetaRow
txNumOfTxIdReadsTxIdIndex
nextTxNumReadsProgress
missingRowsReturnEmptyOrGap
```

### 24.7 Startup verifier tests

`chainbase/src/test/java/org/tron/core/archive/startup/ArchiveStartupVerifierTest.java`

```text
disabledArchiveNoOp
progressMatchesCanonicalHeadOk
emptyProgressWithCanonicalAheadFailsStrict
archiveBehindCanonicalMarksGap
archiveAheadCanonicalFailsStrict
sameHeightHashMismatchMarksRepair
registryChecksumMismatchMarksRepair
missingTxNumRowsMarksRepair
```

### 24.8 Manager integration tests

`framework/src/test/java/org/tron/core/db/ArchiveTemporalStoreManagerWiringTest.java`

```text
normalPushBlockCommitsArchiveAfterCanonicalCommit
normalPushBlockArchiveCommitBeforeBlockTrigger
applyBlockFailureAbortsArchivePendingState
archiveApplyFailureAfterCanonicalCommitFailsFast
forkReplayCommitsArchive
forkRecoveryReplayCommitsArchive
eraseBlockUnwindsArchiveAfterFastPop
fastPopFailureDoesNotUnwindArchive
```

## 25. Patch Sequence

### L5a：Raw store

新增 `ArchiveRawStore`、`DefaultArchiveRawStore`、`TreeMapArchiveRawStore`、`ArchiveBatch`、`ArchiveEntry`、`ArchiveByteComparator`。

Gate:

```bash
./gradlew :chainbase:test --tests '*ArchiveRawStoreTest'
```

### L5b：ArchiveDbFactory

新增 `ArchiveDbFactory`，封装 LevelDB/RocksDB 构造和 `WriteOptionsWrapper` lifecycle。

Gate:

```bash
./gradlew :chainbase:test --tests '*ArchiveRawStoreTest'
```

### L5c：Table/key/value codecs

新增 `ArchiveTable`、`ArchiveStoreKeyCodec`、`ArchiveValueCodec`、txNum/progress codecs。

Gate:

```bash
./gradlew :chainbase:test --tests '*ArchiveStoreKeyCodecTest'
./gradlew :chainbase:test --tests '*ArchiveValueCodecTest'
```

### L5d：Temporal apply/read

新增 `ArchiveTemporalStore`、`DefaultArchiveTemporalStore`、`VersionedValue`、gap/repair exceptions。

Gate:

```bash
./gradlew :chainbase:test --tests '*DefaultArchiveTemporalStoreTest'
```

### L5e：Temporal unwind

实现 changeset scan、descending restore、progress rollback、root row delete。

Gate:

```bash
./gradlew :chainbase:test --tests '*DefaultArchiveTemporalStoreUnwindTest'
```

### L5f：PersistentArchiveTxNumIndex

把 L2 txNum 查询切到 temporal rows。

Gate:

```bash
./gradlew :chainbase:test --tests '*PersistentArchiveTxNumIndexTest'
```

### L5g：DefaultArchiveService + Manager wiring

接入 normal/fork/recovery commit 和 eraseBlock unwind。

Gate:

```bash
./gradlew :framework:test --tests '*ArchiveTemporalStoreManagerWiringTest'
```

### L5h：Startup verifier

实现 progress/head/checksum/corruption verifier。

Gate:

```bash
./gradlew :chainbase:test --tests '*ArchiveStartupVerifierTest'
```

## 26. Acceptance Gates

Focused:

```bash
./gradlew :chainbase:test --tests '*ArchiveRawStoreTest'
./gradlew :chainbase:test --tests '*ArchiveStoreKeyCodecTest'
./gradlew :chainbase:test --tests '*ArchiveValueCodecTest'
./gradlew :chainbase:test --tests '*DefaultArchiveTemporalStoreTest'
./gradlew :chainbase:test --tests '*DefaultArchiveTemporalStoreUnwindTest'
./gradlew :chainbase:test --tests '*PersistentArchiveTxNumIndexTest'
./gradlew :chainbase:test --tests '*ArchiveStartupVerifierTest'
./gradlew :framework:test --tests '*ArchiveTemporalStoreManagerWiringTest'
```

Regression:

```bash
./gradlew :framework:test --tests '*ManagerArchiveLifecycleTest'
./gradlew :framework:test --tests '*ArchiveRetryLifecycleTest'
./gradlew checkstyleMain checkstyleTest
```

Pre-merge:

```bash
./gradlew build
```

## 27. DONE Evidence

L5 can be marked done only when:

- single archive DB exists and is default-off behind L1 config。
- LevelDB/RocksDB batch delete tests pass。
- key codec order tests prove txNum/blockNum lexicographic order。
- `getAfterTx/getBeforeTx/getAsOf` pass create/update/delete/recreate cases。
- applyBlock writes latest/history/changeset/txnum/progress in one batch。
- same block multi-tx same key uses overlay and before-value validation。
- no-write tx and empty block still write tx meta/progress。
- unwind restores latest and deletes history/changeset/txnum/root rows。
- startup verifier catches behind/ahead/hash/checksum/corrupt states。
- Manager normal/fork/recovery/erase paths call temporal store at the correct boundary。
- L6 can consume only `ArchiveTemporalStore` and `PersistentArchiveTxNumIndex` without scanning canonical latest Store。

## 28. Stop Conditions

Stop and return to design review if any of these are found during implementation:

- java-tron `DbSourceInter` cannot provide safe bounded range iteration without adding a new DB abstraction。
- LevelDB and RocksDB batch delete semantics diverge in tests。
- L4 `BlockWriteSet` cannot prove `firstBefore` for any P0 domain。
- `Manager` has a canonical commit path not covered by L2/L5 hooks。
- startup verifier cannot read canonical head after `Manager.init()` without framework-only dependency leakage。
- archive apply failure after canonical commit has no acceptable fail-fast path。

## 29. L6 Handoff

L6 `ArchiveStateReader` should receive:

```text
ArchiveTemporalStore
PersistentArchiveTxNumIndex
ArchiveDomainRegistry
```

L6 must not know:

```text
ArchiveRawStore physical keys
LevelDB/RocksDB implementation
Manager commit lifecycle
L4 Store hook details
```

The L6 reader should call:

```text
txNum = txNumIndex.rangeOfBlock(blockNum).finalizeTxNum()
value = temporalStore.getAsOf(domain, canonicalKey, txNum)
```

That is the core contract L5 must leave stable.
