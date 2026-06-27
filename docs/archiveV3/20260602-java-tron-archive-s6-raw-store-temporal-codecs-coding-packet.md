# java-tron Archive S6：ArchiveRawStore + Temporal Codecs 编码执行包

日期：2026-06-02

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

> 2026-06-03 更新：本文是旧 `a79693e450` 编码包。当前 `4e80f8ffa9a2` 的 S6/S7 编码入口请看 [java-tron Archive S6/S7：ArchiveTemporalStore 4e80 编码执行包](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md)，旧行号和部分路径不可直接用于编码。

java-tron 旧文档原始基线：`a79693e450`。

关联文档：

- 当前 4e80 S6/S7 编码入口：[java-tron Archive S6/S7：ArchiveTemporalStore 4e80 编码执行包](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md)
- 端到端矩阵：[java-tron Archive 端到端实现矩阵与 PR 执行队列](./20260602-java-tron-archive-end-to-end-implementation-matrix.md)
- 实现蓝图：[java-tron Archive 状态树实现蓝图](./20260602-java-tron-archive-implementation-blueprint.md)
- 模块 04 设计：[java-tron Archive 模块 04：ArchiveTemporalStore 细化设计](./20260521-java-tron-archive-module-04-temporal-store.md)
- 模块 04 java-tron 源码对照：[模块 04 ArchiveTemporalStore：java-tron 源码对照](./20260601-java-tron-module-04-temporal-store-java-tron-source-deep-dive.md)
- 模块 04 patch checklist：[java-tron Archive 模块 04：ArchiveTemporalStore 逐文件 Patch 清单](./20260602-java-tron-archive-module-04-temporal-store-patch-checklist.md)
- PR5 TemporalStore 规格：[java-tron Archive PR5 TemporalStore 代码级实现规格](./20260602-java-tron-archive-pr5-temporal-store-implementation-spec.md)
- S5 Storage semantic hook：[java-tron Archive S5：Contract Storage Semantic Hook 编码执行包](./20260602-java-tron-archive-s5-contract-storage-semantic-coding-packet.md)

## 1. 本文定位

S6 对应 PR5 前半段，也就是 `PR5a`：

```text
single physical archive DB
  -> ArchiveRawStore
  -> ArchiveBatch
  -> table key codecs
  -> nullable/latest/txnum/progress value codecs
  -> fake store + LevelDB/RocksDB focused tests
```

S6 只建立持久化底座，不接 `BlockWriteSet` 写入流程：

| 范围 | S6 是否交付 | 说明 |
| --- | --- | --- |
| `ArchiveRawStore` facade | 是 | 封装 get/put/delete/batch/seek/prefix/range |
| single physical `archive` DB | 是 | state、txnum、progress、后续 root rows 都放同一个 physical DB |
| temporal key/value schema | 是 | 固定 `LATEST/HISTORY/CHANGESET/TXNUM/META` 的二进制格式 |
| `TreeMapArchiveRawStore` | 是 | 单测用，排序必须等价于 LevelDB/RocksDB unsigned lexicographic |
| `DefaultArchiveTemporalStore.applyBlock` | 否 | S7 做 |
| `getAsOf/unwindBlock/startup verifier` | 否 | S7 做 |
| JSON-RPC / StateReader | 否 | S8/S9 做 |
| commitment root value codec | 否 | PR7 做；S6 只保证 `ArchiveBatch` 可承载 `0x30+` root rows |

关键边界：

```text
S6 的输出是 stable binary storage contract。
S7/S8/PR7 只能复用这个 contract，不能各自重新定义 table key/value。
```

## 2. java-tron 源码事实

### 2.1 当前 DB 抽象能力

| java-tron 位置 | 源码事实 | 对 S6 的结论 |
| --- | --- | --- |
| `chainbase/src/main/java/org/tron/core/db2/common/DB.java:4-18` | `DB<K,V>` 只有 `get/put/remove/iterator/close/getDbName` | 不能直接作为 archive raw store API，需要新增 facade |
| `chainbase/src/main/java/org/tron/core/db/common/BatchSourceInter.java:25-29` | batch API 是 `updateByBatch(Map<K,V>)` | raw store 可复用底层 batch |
| `chainbase/src/main/java/org/tron/core/db/common/DbSourceInter.java:32-65` | `DbSourceInter<V>` extends batch source，且有 `prefixQuery(byte[])` | 封装 `DbSourceInter<byte[]>` 比封装 `DB<byte[], byte[]>` 更合适 |
| `chainbase/src/main/java/org/tron/core/db/common/iterator/DBIterator.java:13-17` | iterator 支持 `seek/seekToFirst/seekToLast` | S7 的 `GetAsOf` 可用 `HISTORY` seek 实现 |
| `chainbase/src/main/java/org/tron/core/db/common/iterator/StoreIterator.java:73-77` | LevelDB iterator 支持 seek | `DefaultArchiveRawStore.seek/range` 可覆盖 LevelDB |
| `chainbase/src/main/java/org/tron/core/db/common/iterator/RockStoreIterator.java:92-96` | RocksDB iterator 支持 seek | `DefaultArchiveRawStore.seek/range` 可覆盖 RocksDB |
| `chainbase/src/main/java/org/tron/common/storage/leveldb/LevelDbDataSourceImpl.java:365-379` | `updateByBatch` 中 `value == null` 时 `batch.delete(key)` | `ArchiveBatch.delete` 可落到底层 null-delete |
| `chainbase/src/main/java/org/tron/common/storage/rocksdb/RocksDbDataSourceImpl.java:301-314` | RocksDB batch 中 `value == null` 时 delete | LevelDB/RocksDB 删除语义可统一 |
| `chainbase/src/main/java/org/tron/core/db2/common/LevelDB.java:59-63` | `flush(Map<WrappedByteArray, WrappedByteArray>)` 把 value 调 `getBytes()` | `Flusher` 不能表达 null-delete，不适合作 S6 batch |
| `chainbase/src/main/java/org/tron/core/db2/common/RocksDB.java:60-64` | RocksDB `Flusher` 同样不能表达 null-delete | 同上 |
| `chainbase/src/main/java/org/tron/core/db/TronDatabase.java:37-48` | 现有 DB factory 依赖 `storage.dbEngine`，LevelDB/RocksDB parent path 不同 | `ArchiveDbFactory` 必须按引擎分支构造 |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java:56-69` | Store 默认放进 `RevokingDatabase/Chainbase` | archive sidecar 不应进入 revoking DB |
| `chainbase/src/main/java/org/tron/common/utils/StorageUtils.java:22-28` | `getOutputDirectoryByDbName` 支持 per-DB path override | S6 可复用 output directory，但 archive 更适合走 `storage.archive.db.directory` |
| `common/src/main/java/org/tron/common/utils/ByteArray.java:87-92` | `fromLong/fromInt` 是 Guava big-endian | txNum/blockNum/keyLen 可以用 big-endian 编码 |
| `common/src/main/java/org/tron/common/utils/ByteUtil.java:400-414` | `compare` 要求两个数组等长 | archive variable-length key 需要自己的 unsigned lexicographic comparator |
| `chainbase/src/main/java/org/tron/core/db2/common/WrappedByteArray.java:14-24` | `of` 不 copy，`copyOf` 才 copy | `ArchiveBatch` 内部必须用 `WrappedByteArray.copyOf` |

### 2.2 S6 的源码级判断

1. `DefaultArchiveRawStore` 直接封装 `DbSourceInter<byte[]>`，不要封装 `DB<byte[], byte[]>` 或 `Flusher`。
2. archive sidecar 不进入 `RevokingDatabase`，因为它的提交边界是 canonical session commit 后的独立 sidecar batch。
3. PR5 P0 只建一个 physical DB：`archive`。`txnum + latest/history/changeset + progress` 必须在同一个 `updateByBatch` 中原子提交。
4. 所有 archive key/value 都是稳定二进制，不使用 Java serialization、protobuf `Any`、JSON string 作为 DB value。
5. 所有对外返回的 `byte[]` 都 copy；所有内部 map key 也 copy，避免调用方修改数组后破坏 hash/equality。

## 3. 文件落点

S6 建议新增以下文件：

```text
chainbase/src/main/java/org/tron/core/archive/store/ArchiveRawStore.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveBatch.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveEntry.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveStoreException.java
chainbase/src/main/java/org/tron/core/archive/store/DefaultArchiveRawStore.java
chainbase/src/main/java/org/tron/core/archive/store/TreeMapArchiveRawStore.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveDbFactory.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveTable.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveKeyCodec.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveKeyRange.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveByteComparator.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveValueCodec.java
chainbase/src/main/java/org/tron/core/archive/store/LatestValue.java
chainbase/src/main/java/org/tron/core/archive/store/LatestValueCodec.java
chainbase/src/main/java/org/tron/core/archive/store/TxNumValueCodec.java
chainbase/src/main/java/org/tron/core/archive/store/BlockRangeValueCodec.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveProgress.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveProgressCodec.java
```

测试文件：

```text
chainbase/src/test/java/org/tron/core/archive/store/ArchiveKeyCodecTest.java
chainbase/src/test/java/org/tron/core/archive/store/ArchiveValueCodecTest.java
chainbase/src/test/java/org/tron/core/archive/store/TxNumValueCodecTest.java
chainbase/src/test/java/org/tron/core/archive/store/ArchiveProgressCodecTest.java
chainbase/src/test/java/org/tron/core/archive/store/TreeMapArchiveRawStoreTest.java
framework/src/test/java/org/tron/core/archive/store/ArchiveRawStoreLevelDbTest.java
framework/src/test/java/org/tron/core/archive/store/ArchiveRawStoreRocksDbTest.java
```

`framework` 里的 LevelDB/RocksDB focused test 可以延到 PR5b，如果 PR5a 想保持更小；但 codec 和 `TreeMapArchiveRawStore` 单测不应延后。

## 4. Patch 1：ArchiveRawStore API

### 4.1 `ArchiveRawStore`

推荐接口：

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

- `get/seek/prefix/range` 返回的 key/value 必须 copy。
- `put` 不接受 null value；删除只能走 `delete` 或 batch delete。
- `updateByBatch` 中 `value == null` 表示 delete，和 LevelDB/RocksDB 底层语义一致。
- `prefix/range` 的 `limit <= 0` 必须拒绝，不能退化成全库扫描。
- `range(from,to,limit)` 的 `toExclusive == null` 可以保留给内部扫描，但公开调用建议始终传上界。

### 4.2 `ArchiveEntry`

不要直接把 `Map.Entry<byte[], byte[]>` 暴露出去。建议：

```java
public final class ArchiveEntry {
  private final byte[] key;
  private final byte[] value;

  public byte[] getKey() {
    return Arrays.copyOf(key, key.length);
  }

  public byte[] getValue() {
    return Arrays.copyOf(value, value.length);
  }
}
```

原因：

- LevelDB/RocksDB iterator 返回的 slice 在 iterator 移动后可能失效。
- `Map.Entry` 的 `setValue` 语义不适合 raw store。
- 后续 `GetAsOf` 必须对 `seek` 命中的 key 做 prefix check，稳定 copy 更安全。

### 4.3 `ArchiveBatch`

推荐接口：

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
key equality: bytes content equality
ordering: insertion order
duplicate key: last write wins
delete representation: raw map value == null
copy rule: input key/value copy, output key/value copy
overlay read: containsKey(key) distinguishes unstaged from staged delete
```

内部可以使用：

```java
private final LinkedHashMap<WrappedByteArray, byte[]> rows = new LinkedHashMap<>();
```

但必须用：

```java
WrappedByteArray.copyOf(key)
```

不要用 `WrappedByteArray.of(key)`，因为 `of` 不 copy。

### 4.4 为什么不复用 `Flusher`

`LevelDB.flush` 和 `RocksDB.flush` 的签名是：

```java
void flush(Map<WrappedByteArray, WrappedByteArray> batch)
```

内部会执行：

```java
e.getValue().getBytes()
```

这意味着 delete 无法表达。S6 必须直接调用底层：

```java
DbSourceInter<byte[]> dbSource;
dbSource.updateByBatch(rawRows, writeOptions);
```

否则 `unwindBlock` 删除 `HISTORY/CHANGESET/TXNUM` 时只能写 tombstone value，不能真正删除 row。

## 5. Patch 2：DefaultArchiveRawStore

### 5.1 封装对象

`DefaultArchiveRawStore` 构造函数建议接收底层 source：

```java
public final class DefaultArchiveRawStore implements ArchiveRawStore {
  private final DbSourceInter<byte[]> dbSource;
  private final WriteOptionsWrapper writeOptions;
}
```

读写映射：

| ArchiveRawStore API | 底层调用 |
| --- | --- |
| `get(key)` | `dbSource.getData(key)` |
| `put(key,value)` | `dbSource.putData(key,value)` |
| `delete(key)` | `dbSource.deleteData(key)` |
| `updateByBatch(rows)` | `dbSource.updateByBatch(rows, writeOptions)` |
| `seek(key)` | `dbSource.iterator().seek(key)` |
| `prefix(prefix,limit)` | `iterator.seek(prefix)` + `ArchiveKeyCodec.startsWith` |
| `range(from,to,limit)` | `iterator.seek(from)` + comparator `< toExclusive` |

不要直接使用 `DbSourceInter.prefixQuery(byte[])` 作为唯一实现，因为现有 `prefixQuery` 没有 limit 参数；archive 查询必须避免无界扫描。

### 5.2 iterator 关闭

`DBIterator` 同时实现 `AutoCloseable`。所有 seek/range/prefix 必须：

```java
try (DBIterator iterator = dbSource.iterator()) {
  iterator.seek(fromInclusive);
  ...
}
```

RocksDB 源码注释已经强调 iterator 持有 native resources。S6 test 要覆盖 range/prefix 后不会泄漏无法关闭的 iterator。

### 5.3 path factory

新增：

```text
chainbase/src/main/java/org/tron/core/archive/store/ArchiveDbFactory.java
```

职责：

```text
ArchiveConfig + CommonParameter.storage
  -> DbSourceInter<byte[]>
  -> DefaultArchiveRawStore
```

S1/S2 已建议 archive 配置：

```text
storage.archive.enable = false
storage.archive.db.directory = "archive"
```

S6 physical DB name 固定：

```text
archive
```

路径构造必须对齐 java-tron 现有 DB 构造器差异：

| 引擎 | 构造方式 | 结果路径形态 |
| --- | --- | --- |
| LevelDB | `new LevelDbDataSourceImpl(archiveOutput, "archive")` | `output/archive/storage.db.directory/archive` |
| RocksDB | `new RocksDbDataSourceImpl(Paths.get(archiveOutput, storage.dbDirectory), "archive")` | `output/archive/storage.db.directory/archive` |

其中：

```java
String baseOutput = StorageUtils.getOutputDirectory();
String archiveDir = CommonParameter.getInstance().getArchive().getDb().getDirectory();
String archiveOutput = Paths.get(baseOutput, archiveDir).toString();
```

不要直接调用：

```java
StorageUtils.getOutputDirectoryByDbName("archive")
```

作为唯一规则。普通 Store 的 per-DB property map 是 canonical DB 的配置机制；archive sidecar 应优先遵循 `storage.archive.db.directory`，后续如果要支持 per-DB override，再单独增加 `storage.archive.db.path`。

### 5.4 write options

`DefaultArchiveRawStore` 的 write sync 应跟随 java-tron 当前 storage sync：

```java
WriteOptionsWrapper.getInstance()
    .sync(CommonParameter.getInstance().getStorage().isDbSync())
```

P0 不新增 `storage.archive.db.sync`，避免 archive DB 和 canonical DB 在 crash consistency 策略上分叉。若后续要求 archive 异步写，那必须同时设计 startup verifier/repair 行为。

## 6. Patch 3：TreeMapArchiveRawStore

`TreeMapArchiveRawStore` 是单测用 fake store：

```java
public final class TreeMapArchiveRawStore implements ArchiveRawStore {
  private final NavigableMap<byte[], byte[]> rows =
      new TreeMap<>(ArchiveByteComparator.UNSIGNED_LEXICOGRAPHIC);
}
```

实现要求：

- `get` 返回 copy。
- `put` 存 copy。
- `delete` 删除 key。
- `updateByBatch` 按 input iteration order 执行，`null` 表示 delete。
- `seek(key)` 返回 `ceilingEntry(key)`。
- `prefix(prefix,limit)` 从 `ceilingEntry(prefix)` 开始，直到不 `startsWith`。
- `range(from,to,limit)` 从 `ceilingEntry(from)` 开始，直到 `key >= toExclusive`。

这个 fake store 的排序必须和 LevelDB/RocksDB 一致，即 unsigned lexicographic。不能用 Java 默认 `byte[]` 引用排序，也不能用 `ByteUtil.compare`，因为它要求等长数组。

## 7. Patch 4：ArchiveTable

S6 固定 PR5 table prefixes：

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

语义对应：

| Prefix | 逻辑表 | 说明 |
| --- | --- | --- |
| `0x01` | `META` | schema/progress/registry checksum |
| `0x10` | `TXNUM_BLOCK` | blockNum -> block txNum range |
| `0x11` | `TXNUM_BY_TXID` | txId -> txNum |
| `0x12` | `TXNUM_META` | txNum -> tx metadata |
| `0x20` | `LATEST` | domain/key 当前 latest value |
| `0x21` | `HISTORY` | domain/key/txNum -> before-value |
| `0x22` | `CHANGESET` | txNum/domain/key -> changed key marker |

`0x30+` 是 PR7 commitment root 保留区。S6 不实现 root value codec，但 `ArchiveRawStore` 和 `ArchiveBatch` 必须能写任意 binary key，因此 PR7 后续可以把 root rows 放入同一个 batch。

## 8. Patch 5：ArchiveKeyCodec

### 8.1 编码规则

统一规则：

```text
integer: big-endian
txNum/blockNum: non-negative long
domainId: u16, 0..65535
keyLen: u32, exact payload length
variable bytes: length-prefix where needed
```

为什么 `keyLen` 必须存在：

```text
HISTORY(domain, key=A, txNum=...)
HISTORY(domain, key=A||suffix, txNum=...)
```

如果没有 `keyLen`，prefix scan 可能把短 key 和长 key 混在一起。`keyLen` 放在 canonical key 前可以保证 `historyPrefix(domain,key)` 是精确前缀。

### 8.2 key layout

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

### 8.3 API 草案

```java
public final class ArchiveKeyCodec {
  public static byte[] metaKey(String asciiName);

  public static byte[] txNumBlockKey(long blockNum);

  public static byte[] txNumByTxIdKey(byte[] txId);

  public static byte[] txNumMetaKey(long txNum);

  public static byte[] latestKey(ArchiveDomain domain, byte[] canonicalKey);

  public static byte[] historyPrefix(ArchiveDomain domain, byte[] canonicalKey);

  public static byte[] historyKey(ArchiveDomain domain, byte[] canonicalKey, long txNum);

  public static byte[] historySeekKey(ArchiveDomain domain, byte[] canonicalKey, long asOfTxNum);

  public static byte[] changesetKey(long txNum, ArchiveDomain domain, byte[] canonicalKey);

  public static ArchiveKeyRange changesetRange(long firstTxNum, long lastTxNumInclusive);

  public static boolean startsWith(byte[] key, byte[] prefix);
}
```

`ArchiveDomain` 来自 S3 `ArchiveDomainRegistry`。如果 S3 最终类名是 `ArchiveDomainDescriptor`，codec 只需要拿到 `int domainId()`，不要依赖 registry 实例。

### 8.4 `ArchiveKeyRange`

```java
public final class ArchiveKeyRange {
  private final byte[] fromInclusive;
  private final byte[] toExclusive;
}
```

用例：

```text
changesetRange(100, 120)
  from = 0x22 | u64(100)
  to   = 0x22 | u64(121)
```

如果 `lastTxNumInclusive == Long.MAX_VALUE`，P0 直接拒绝，避免 upper-bound 溢出。txNum 是内部自增坐标，现实不会触达这个边界。

### 8.5 unsigned comparator

新增：

```java
public final class ArchiveByteComparator implements Comparator<byte[]> {
  public static final Comparator<byte[]> UNSIGNED_LEXICOGRAPHIC = ...;
}
```

比较规则：

```text
for each byte: Byte.toUnsignedInt(left[i]) vs Byte.toUnsignedInt(right[i])
if all equal: shorter array sorts first
```

不要复用 `ByteUtil.compare`，因为它在第 404 行要求两个数组长度相等。

## 9. Patch 6：ArchiveValueCodec

### 9.1 nullable raw value

S6 不能把 empty bytes 当 missing。固定编码：

```text
0x00                         tombstone / missing
0x01 | valueLen_u32 | value  present value, valueLen can be 0
```

API：

```java
public final class ArchiveValueCodec {
  public static byte[] encodeNullable(byte[] value);

  public static Optional<byte[]> decodeNullable(byte[] encoded);

  public static boolean isTombstone(byte[] encoded);
}
```

规则：

- `null` -> `0x00`。
- `new byte[0]` -> `0x01 | 0x00000000`。
- 32-byte zero storage 在 S5 已归一成 `afterValue=null`；如果某些 domain 真实值是 32-byte zero，codec 仍按 present value 编码。
- decoder 遇到 unknown tag、长度不足、长度多余 trailing bytes 都抛 `ArchiveStoreException`。

### 9.2 Latest value

`LATEST` value 不只保存 raw value，还保存最后写入 txNum：

```text
lastTxNum_u64 | nullableValue
```

类：

```java
public final class LatestValue {
  private final long lastTxNum;
  private final byte[] value; // nullable semantic value
}
```

codec：

```java
public final class LatestValueCodec {
  public static byte[] encode(long lastTxNum, byte[] nullableValue);

  public static LatestValue decode(byte[] encoded);
}
```

S7 `applyBlock` 可以用 `lastTxNum` 做 stale/mismatch 诊断，但 S6 不实现校验逻辑。

### 9.3 HISTORY value

`HISTORY(domain,key,txNum)` 存 before-value，直接使用 `ArchiveValueCodec.encodeNullable(beforeValue)`：

```text
txNum 10: before=A, after=B
HISTORY(domain,key,10) = encodeNullable(A)
LATEST(domain,key) = latestValue(10,B)
```

`GetAsOf` 的 seek 语义属于 S7，但 S6 key/value schema 必须支持：

```text
seek HISTORY(domain,key,asOfTxNum)
  -> first future-or-equal change
  -> return that row's before-value
```

## 10. Patch 7：txNum value codecs

S1/S2 已定义交易级坐标类型，S6 只给它们加稳定二进制落盘 codec。

### 10.1 TxNumMeta

`TXNUM_META(txNum)` value：

```text
txNum_u64
blockNum_u64
txIndex_i32
phase_u8
blockHashLen_u32 | blockHash
txIdLen_u32 | txId, txIdLen=0 means null
```

说明：

- `txIndex=-1` 表示 system tx，用 signed int big-endian。
- `phase` 使用 S1/S2 的 `ArchivePhase` ordinal/code，必须写成显式 `byte code`，不要依赖 enum ordinal。
- `blockHash` 当前按 byte[] length-prefix 保存，避免在 S6 强绑定 TRON `BlockId` 类。
- `txIdLen=0` 只用于 system tx；user tx 应有固定 32-byte txId，但 codec 不把 32 写死。

### 10.2 TxId index

`TXNUM_BY_TXID(txId)` value：

```text
txNum_u64
```

如果后续发现 TRON 存在同 txId 重放冲突，S7/PR6 查询层再按 blockNum/hash 校验；S6 先保持一对一 index。

### 10.3 BlockTxNumRange

`TXNUM_BLOCK(blockNum)` value：

```text
blockNum_u64
blockHashLen_u32 | blockHash
firstTxNum_u64
lastTxNum_u64
userTxCount_u32
systemTxCount_u32
```

规则：

- empty block 仍然要有 block range。若只有 `BLOCK_PREPARE/BLOCK_FINALIZE` system tx，则 `userTxCount=0`。
- `firstTxNum <= lastTxNum`。如果某个实现允许完全无 txNum 的 genesis/bootstrap 状态，应单独用 `ArchiveProgress.EMPTY` 表达，不要写非法 range。

## 11. Patch 8：ArchiveProgress

`ArchiveProgress` 不属于 temporal state 本身，但它必须和 state/txnum rows 同 batch 更新，所以放在 S6 schema。

`META(progress)` value：

```text
schemaVersion_u32
appliedBlockNum_u64
appliedBlockHashLen_u32 | appliedBlockHash
nextTxNum_u64
registryChecksumLen_u32 | registryChecksum
coverageLen_u32 | coverageAscii
status_u8
```

status：

```text
0 = EMPTY
1 = OK
2 = ARCHIVE_GAP
3 = REPAIR_REQUIRED
```

字段语义：

| 字段 | 说明 |
| --- | --- |
| `schemaVersion` | S6 初始为 `1`；任何 key/value 非兼容变化都必须 bump |
| `appliedBlockNum` | archive 已完整落盘的最高 block |
| `appliedBlockHash` | 和 canonical block hash 比较，检测同高分叉 |
| `nextTxNum` | 下一条待分配 txNum |
| `registryChecksum` | S3 domain registry checksum；避免同一 DB 被不同 domain schema 打开 |
| `coverageAscii` | 例如 `TVM_STATE_ONLY` |
| `status` | startup verifier/repair 状态 |

S6 只实现 codec 和 helper key，不实现 startup verifier。S7 在 canonical DB commit 与 archive commit 非原子时使用该 progress 检测 gap。

## 12. 写入顺序与原子性边界

S6 必须把原子性边界写清楚：

```text
rawStore.newBatch()
  put/delete txnum rows
  put/delete latest/history/change rows
  put progress row
  commit()
```

在同一个 physical `archive` DB 内：

```text
LevelDB/RocksDB WriteBatch success -> all visible
WriteBatch failure -> none visible
```

但 canonical DB 与 archive DB 之间仍不是一个事务：

```text
canonical tmpSession.commit() success
crash before archive batch commit
```

这不是 S6 能解决的问题。S6 只保证 archive sidecar 内部一致；S7 的 startup verifier 必须检测：

```text
canonical latest > archive progress
```

并返回 `ARCHIVE_GAP` 或 `REPAIR_REQUIRED`。

## 13. 和 S4/S5/S7 的接口契约

### 13.1 来自 S4/S5 的 key/value

S4/S5 输出给 S7 的 `DomainWrite` 必须已经是 canonical domain key/value：

| Domain | canonical key | S6 是否理解业务语义 |
| --- | --- | --- |
| `ACCOUNT` | address bytes | 否，只存 binary |
| `CODE` | address/code key | 否，只存 binary |
| `CONTRACT` | contract address | 否，只存 binary |
| `DYNAMIC_PROPERTIES` | allowlist key | 否，只存 binary |
| `CONTRACT_STORAGE` | `address21 || slot32 || storageKeyVersion_u8` | 否，只存 binary |

S6 不解析 capsule，不理解 storage slot，不做 domain allowlist；这些都属于 S3/S4/S5。

### 13.2 给 S7 的能力

S7 可以基于 S6 实现：

```text
applyBlock:
  batch.put(TXNUM_META)
  batch.put(TXNUM_BY_TXID)
  batch.put(HISTORY)
  batch.put(CHANGESET)
  batch.put(LATEST)
  batch.put(TXNUM_BLOCK)
  batch.put(META(progress))
  batch.commit()

getAsOf:
  seek(HISTORY prefix + asOfTxNum)
  prefix check
  else get(LATEST)

unwind:
  range(CHANGESET first..last)
  get(HISTORY)
  restore/delete LATEST
  delete HISTORY/CHANGESET/TXNUM rows
  update META(progress)
  batch.commit()
```

S6 不实现这些流程，但所有 API 必须足够支持这些流程，不能让 S7 重新绕过 raw store。

### 13.3 给 PR7 的能力

PR7 的 commitment builder 需要和 temporal rows 同 batch。S6 的要求：

- `ArchiveBatch` 不限制 table prefix。
- `ArchiveRawStore.range` 支持任意 binary from/to。
- `ArchiveProgressCodec` 带 `schemaVersion/registryChecksum/coverage`，PR7 可扩展 root progress 或新增 `COMMITMENT_META` rows。
- S6 不新增 physical `archive-root` DB。

## 14. 测试计划

### 14.1 `ArchiveKeyCodecTest`

必测：

1. `txNumMetaKey(1) < txNumMetaKey(2)`。
2. `historyKey(domain,key,10) < historyKey(domain,key,11)`。
3. `historyPrefix(domain,key)` 只匹配同一 `domainId + keyLen + key`。
4. `key=[0x01]` 和 `key=[0x01,0x00]` 不产生 prefix 污染。
5. `changesetRange(10,12)` 只扫到 txNum 10、11、12。
6. `domainId=65535` 可编码；`domainId=65536` 拒绝。
7. negative txNum/blockNum 拒绝。

### 14.2 `ArchiveValueCodecTest`

必测：

1. `null -> tombstone -> Optional.empty()`。
2. empty bytes round-trip 后仍是 present empty。
3. 32-byte zero round-trip 后仍是 present 32-byte zero。
4. unknown tag 抛 `ArchiveStoreException`。
5. valueLen 小于/大于 payload 实际长度都抛异常。
6. returned byte[] 修改不影响 codec 内部或 store 中 value。

### 14.3 txNum/progress codec tests

必测：

1. user tx `TxNumMeta` round-trip。
2. system tx `txIndex=-1` and `txId=null` round-trip。
3. `BlockTxNumRange` empty user tx block round-trip。
4. `ArchiveProgress.EMPTY/OK/ARCHIVE_GAP/REPAIR_REQUIRED` round-trip。
5. `registryChecksum` 不同长度可 round-trip，但 production builder 应固定 checksum 算法。
6. malformed length-prefix 不被忽略。

### 14.4 `TreeMapArchiveRawStoreTest`

必测：

1. `put/get/delete` copy semantics。
2. batch put/delete last write wins。
3. batch delete 输出到底层后 `get` missing。
4. `seek` 返回 greater-or-equal key。
5. `prefix` 在第一个非 prefix key 停止。
6. `range(from,to,limit)` 尊重 exclusive upper bound。
7. `limit <= 0` 抛异常。

### 14.5 LevelDB/RocksDB focused tests

如果 PR5a 包含 persistent raw store test：

```text
framework/src/test/java/org/tron/core/archive/store/ArchiveRawStoreLevelDbTest.java
framework/src/test/java/org/tron/core/archive/store/ArchiveRawStoreRocksDbTest.java
```

覆盖：

1. temp datadir 下创建 physical `archive` DB。
2. batch put 后可 get。
3. batch delete 后 missing，确认不是 tombstone value。
4. seek/prefix/range 和 `TreeMapArchiveRawStore` 结果一致。
5. close 后释放 DB；测试自行清理 temp dir。

如果本地环境没有 RocksDB native 依赖，不允许加 skip。正确处理是把 RocksDB focused test 放到已有 RocksDB test suite 或在 PR 描述里列为未运行 gate，由 CI 环境执行。

## 15. 编码顺序

建议 PR5a 拆成 6 个小 commit：

```text
1. archive/store: add ArchiveRawStore API and immutable ArchiveEntry
2. archive/store: add DefaultArchiveRawStore and ArchiveDbFactory
3. archive/store: add TreeMapArchiveRawStore and byte comparator
4. archive/store: add ArchiveTable and ArchiveKeyCodec
5. archive/store: add value/txnum/progress codecs
6. archive/store: add focused codec/raw-store tests
```

每个 commit 的停止条件：

| Commit | 停止条件 |
| --- | --- |
| 1 | API 编译通过，无业务接入 |
| 2 | LevelDB/RocksDB 构造逻辑有 focused test 或清晰 test seam |
| 3 | TreeMap fake 与 comparator 测试通过 |
| 4 | key ordering/prefix/range 测试通过 |
| 5 | nullable/latest/txnum/progress round-trip 测试通过 |
| 6 | `:chainbase:test` 相关测试通过；如加 framework persistent test，再跑指定 framework test |

## 16. 验收清单

S6 合并前检查：

- [ ] 只有一个 physical DB：`archive`。
- [ ] 未新增 `archive-state`、`archive-txnum`、`archive-root` physical DB。
- [ ] `DefaultArchiveRawStore` 直接封装 `DbSourceInter<byte[]>`。
- [ ] 没有复用 `Flusher` 表达 archive batch。
- [ ] batch delete 通过 `value == null` 落到底层。
- [ ] 所有 key/value 对外返回都 copy。
- [ ] 内部 batch key 使用 content equality，不用裸 `byte[]` 做 HashMap key。
- [ ] `domainId` 是 `u16`。
- [ ] variable key 全部 length-prefix。
- [ ] `ArchiveValueCodec` 区分 tombstone、empty bytes、zero bytes。
- [ ] `TreeMapArchiveRawStore` 排序和 LevelDB/RocksDB 一致。
- [ ] `prefix/range` 有 limit，不允许无界扫描。
- [ ] S6 不接 `Manager`、不写 `DefaultArchiveTemporalStore.applyBlock`、不改 JSON-RPC。
- [ ] 不新增任何 test skip。

## 17. S7 handoff

S6 完成后，S7 只需要实现 temporal 业务流程：

```text
BlockWriteSet
  -> ArchiveBatch rows
  -> rawStore batch commit
  -> GetAsOf
  -> unwind
  -> startup verifier
```

如果 S7 发现 S6 schema 不够用，必须回到 S6 文档修改 schema 并 bump `schemaVersion`。不要在 S7 局部新增第二套 key/value 编码。

当前 4e80 S6/S7 编码入口：[java-tron Archive S6/S7：ArchiveTemporalStore 4e80 编码执行包](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md)

S7 历史编码执行包：[java-tron Archive S7：Temporal Commit / GetAsOf / Unwind / Startup 编码执行包](./20260602-java-tron-archive-s7-temporal-commit-unwind-startup-coding-packet.md)
