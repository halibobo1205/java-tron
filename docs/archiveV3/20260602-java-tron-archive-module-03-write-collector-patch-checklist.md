# java-tron Archive 模块 03：ArchiveWriteCollector 逐文件 Patch 清单

日期：2026-06-02

> 2026-06-03 更新：本文是旧 `a79693e450` patch 清单。当前 `4e80f8ffa9a2` 的 Module 03 编码入口请先看 [java-tron Archive S4/S5：WriteCollector 与 Storage Semantic Hook 4e80 编码执行包](./20260603-java-tron-archive-s4-s5-write-collector-storage-4e80-coding-packet.md)，旧行号不可直接用于编码。

关联设计：[java-tron Archive 模块 03：ArchiveWriteCollector 细化设计](./20260521-java-tron-archive-module-03-write-collector.md)

java-tron 源码对照：[模块 03 ArchiveWriteCollector：java-tron 源码对照](./20260601-java-tron-module-03-write-collector-java-tron-source-deep-dive.md)

DomainRegistry 前置清单：[java-tron Archive 模块 02：ArchiveDomainRegistry 逐文件 Patch 清单](./20260602-java-tron-archive-module-02-domain-registry-patch-checklist.md)

关联 PR3/PR4 规格：[java-tron Archive PR3/PR4 WriteCollector 代码级实现规格](./20260602-java-tron-archive-pr3-pr4-write-collector-implementation-spec.md)

S4/S5 4e80 编码执行包：[java-tron Archive S4/S5：WriteCollector 与 Storage Semantic Hook 4e80 编码执行包](./20260603-java-tron-archive-s4-s5-write-collector-storage-4e80-coding-packet.md)

S4 历史编码执行包：[java-tron Archive S4：ArchiveWriteCollector 编码执行包](./20260602-java-tron-archive-s4-write-collector-coding-packet.md)

S5 历史编码执行包：[java-tron Archive S5：Contract Storage Semantic Hook 编码执行包](./20260602-java-tron-archive-s5-contract-storage-semantic-coding-packet.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 旧文档原始基线：`a79693e450`，当前 4e80 实现请看页头链接。

## 1. 目标

本文把 `ArchiveWriteCollector` 落到 java-tron 逐文件 patch 级别。

```text
PR3: Store-level raw put/delete hook -> TxWriteSet / BlockWriteSet
PR4: CONTRACT_STORAGE semantic hook -> logical address/slot storage writes
```

PR3/PR4 合并后应做到：

1. 只在 archive enabled 且 `ArchiveExecutionContext` active 时采集写入。
2. 默认关闭时 Store 写入不额外读取 before-value，普通 fullnode 行为和性能尽量不变。
3. `TronStoreWithRevoking.put/delete` 覆盖大多数 Store raw writes。
4. `ContractStore`、`AbiStore`、`ContractStateStore` 这类绕过 `super.put` 的 Store 有明确处理。
5. Store 写入通过 `ArchiveDomainRegistry` 映射成 `DomainWrite`；collector 不硬编码 dbName。
6. 同一 tx 内同一 `domainId + canonicalKey` 多次写，保留第一次 before-value 和最后一次 after-value。
7. `storage-row` raw write 不直接生成 `CONTRACT_STORAGE`；storage 正式写集来自 semantic hook。
8. block apply 失败、tx 异常、fork 回滚时 pending write-set 可丢弃。
9. `trace.checkNeedRetry()` 前 abort+restart 当前 tx accumulator，retry 后沿用同一个 txNum。

本模块仍不落 `ArchiveTemporalStore`，不实现历史查询，不计算 root。

## 2. 源码事实

| java-tron 位置 | 事实 | 对 collector 的含义 |
| --- | --- | --- |
| `framework/src/main/java/org/tron/core/db/Manager.java:1871` | `processBlock()` 在 tx loop 内调用 `processTransaction(transactionCapsule, block)` | PR1/PR2 的 tx context 是采集边界 |
| `Manager.java:1891` | `payReward(block)` 在 tx loop 后 | system tx context 也要采集 |
| `Manager.java:1910` | `updateDynamicProperties(block)` 在 tx loop 后 | dynamic properties 写属于 block finalize |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java:88-93` | 通用 `put(byte[], T)` | PR3 主 raw hook 点 |
| `TronStoreWithRevoking.java:93` | `revokingDB.put(key, item.getData())` | hook 应在这之前读取 before |
| `TronStoreWithRevoking.java:97-98` | 通用 `delete(byte[])` | delete hook 应在这之前读取 before |
| `TronStoreWithRevoking.java:107-115` | `getUnchecked(byte[])` | 可用于 raw before-value |
| `chainbase/src/main/java/org/tron/core/store/AccountStore.java:68` | `AccountStore.put` 重写但调用 `super.put` | 通用 hook 能采集 `account` |
| `AccountStore.java:88` | account state callback | 只能覆盖账户，不能替代 collector |
| `chainbase/src/main/java/org/tron/core/store/ContractStore.java:31` | `ContractStore.put` 重写 | 通用 hook 会漏 `contract` |
| `ContractStore.java:36-39` | 写前清 ABI，然后直接 `revokingDB.put` | `CONTRACT` after-value 必须是清 ABI 后值；PR3 必须改成 `super.put` 或单独 hook |
| `chainbase/src/main/java/org/tron/core/store/AbiStore.java:27 / 32` | `AbiStore.put(byte[], byte[])` 重载后直接 `revokingDB.put` | `abi` history-only 写会漏通用 hook |
| `chainbase/src/main/java/org/tron/core/store/ContractStateStore.java:27 / 32` | `ContractStateStore.put` 重写后直接 `revokingDB.put` | P1/P0+ 诊断或采集都需单独 hook |
| `chainbase/src/main/java/org/tron/core/db/TransactionStore.java:33` | in-block tx 只写 blockNum | `trans` 是 block/tx data，不应进入 state write-set |
| `actuator/src/main/java/org/tron/core/vm/repository/RepositoryImpl.java:144-149` | `RepositoryImpl` 手动构造，不是 Spring bean | 不宜直接 `@Autowired ArchiveService` |
| `RepositoryImpl.java:152-170` | 从 `StoreFactory` 取各 Store | 可通过 Store 把 hook service 传给 `Storage` |
| `RepositoryImpl.java:948 / 954` | `commitAccountCache` 写 `AccountStore` | TVM account 最终走 raw hook |
| `RepositoryImpl.java:960 / 966` | `commitCodeCache` 写 `CodeStore` | TVM code 最终走 raw hook |
| `RepositoryImpl.java:972 / 980-982` | `commitContractCache` 写 `AbiStore` 后写 `ContractStore` | special Store hook 必须覆盖 |
| `RepositoryImpl.java:988 / 995` | `commitContractStateCache` 写 `ContractStateStore` | P0+/debug path 必须 special/generic hook |
| `RepositoryImpl.java:1001 / 1008` | `commitStorageCache` 调 `storage.commit()` | storage semantic hook 应在 root commit 时触发 |
| `RepositoryImpl.java:1014 / 1020` | `commitDynamicCache` 写 `DynamicPropertiesStore` | dynamic properties raw hook + registry allowlist |
| `actuator/src/main/java/org/tron/core/vm/program/Storage.java:18` | `rowCache` key 是 `DataWord` | commit 时能拿到 logical slot |
| `Storage.java:25` | `address` 字段 | commit 时能拿到 contract address |
| `Storage.java:27` | `contractVersion` 字段 | storage key version 可记录 |
| `Storage.java:46` | `compose(key, addrHash)` | physical key 不可逆 |
| `Storage.java:68` | create2 更新 `addrHash` | raw physical key 更不能当 archive key |
| `Storage.java:96` | `commit()` 遍历 dirty row | PR4 semantic hook 最小落点 |
| `Storage.java:100` | zero storage -> `store.delete(rowKey)` | zero/delete 语义必须统一 |
| `Storage.java:102` | non-zero -> `store.put(rowKey,row)` | raw `storage-row` hook 会同时出现，需忽略 |

## 3. 前置依赖

PR3/PR4 依赖前两个模块：

```text
ArchiveExecutionContext.current()      -> 当前 TxNumMeta
ArchiveDomainRegistry.mapStoreWrite()  -> raw StoreWriteEvent -> DomainWrite
ArchiveDomainRegistry.mapSemanticWrite -> semantic storage event -> DomainWrite
ArchiveService.begin/end tx            -> collector lifecycle
TronStoreWithRevoking.getDbName()      -> 必须已返回 db.getDbName()
```

如果 PR1 的 `getDbName()` 修复未合并，PR3 不能正确按 dbName 做 registry mapping。

## 4. Patch 1：collector 包结构

新增目录：

```text
chainbase/src/main/java/org/tron/core/archive/collector/
```

新增文件：

```text
ArchiveWriteCollector.java
DefaultArchiveWriteCollector.java
StoreWriteEvent.java
SemanticStoreWrite.java
DomainWrite.java
DomainKey.java
TxWriteSet.java
BlockWriteSet.java
ArchiveWriteStats.java
ArchiveWriteSequence.java
WriteOperation.java
```

建议都放在 `chainbase`，因为 Store hook 位于 `chainbase`，`actuator` 的 storage semantic hook 也能依赖 `chainbase`。

## 5. Patch 2：数据结构

### 5.1 `StoreWriteEvent`

```java
public final class StoreWriteEvent {
  private final String dbName;
  private final byte[] rawKey;
  private final byte[] beforeValue;
  private final byte[] afterValue;
  private final WriteOperation operation;
  private final long sequence;
}
```

规则：

- `beforeValue` 是 Store 写入前的 raw bytes；missing 用 `null`。
- `afterValue` 对 `DELETE` 为 `null`。
- 所有 byte array 在构造时 defensive copy。
- `sequence` 由 collector 单调分配，用于诊断和稳定 replay。

### 5.2 `SemanticStoreWrite`

先只服务 `CONTRACT_STORAGE`：

```java
public final class SemanticStoreWrite {
  private final ArchiveDomain domain;
  private final byte[] address;
  private final byte[] logicalKey;
  private final byte[] beforeValue;
  private final byte[] afterValue;
  private final byte[] physicalKey;
  private final int storageKeyVersion;
  private final WriteOperation operation;
  private final long sequence;
}
```

`logicalKey` 对 storage 是 TVM slot 32 bytes。`storageKeyVersion` 由 java-tron `contractVersion` 归一得到，作为 canonical key 的 1-byte suffix：

```text
0 = raw TVM slot
1 = contractVersion 1, Storage.compose 前对 slot 做 sha3
```

### 5.3 `DomainWrite`

```java
public final class DomainWrite {
  private final ArchiveDomain domain;
  private final int domainId;
  private final byte[] canonicalKey;
  private final byte[] beforeValue;
  private final byte[] afterValue;
  private final byte[] rawKey;
  private final String sourceDbName;
  private final WriteOperation operation;
  private final long firstSequence;
  private final long lastSequence;
}
```

合并规则：

```text
same domain + canonicalKey:
  beforeValue = first write beforeValue
  afterValue  = last write afterValue
  operation   = last write operation
  firstSequence preserved
  lastSequence updated
```

`afterValue == beforeValue` 的 final no-op 在 PR3 先保留，并计入 stats；PR5 TemporalStore 再决定是否跳过 history。

### 5.4 `DomainKey`

不要用裸 `byte[]` 做 map key。

```java
public final class DomainKey {
  private final ArchiveDomain domain;
  private final byte[] canonicalKey;
}
```

要求：

- 构造时 copy。
- `equals/hashCode` 按 bytes 内容。
- 输出排序按 `domainId ASC, canonicalKey lexicographic ASC`。

### 5.5 `TxWriteSet`

```java
public final class TxWriteSet {
  private final TxNumMeta txNumMeta;
  private final List<DomainWrite> writes;
  private final ArchiveWriteStats stats;
}
```

输出要求：

- `writes` 是不可变 list。
- list 排序稳定。
- 即使 writes 为空，也可输出 empty `TxWriteSet`，用于证明 txNum context 覆盖。

### 5.6 `BlockWriteSet`

```java
public final class BlockWriteSet {
  private final long blockNum;
  private final byte[] blockHash;
  private final List<TxWriteSet> txWriteSets;
  private final ArchiveWriteStats stats;
}
```

输出要求：

- `txWriteSets` 按 `txNum` 升序。
- PR3 只内存保存，供测试和 PR5 接入。
- `abortBlock()` 必须丢弃当前 block 的所有 pending write set。

## 6. Patch 3：ArchiveWriteCollector 接口

文件：

```text
chainbase/src/main/java/org/tron/core/archive/collector/ArchiveWriteCollector.java
```

建议接口：

```java
public interface ArchiveWriteCollector {
  void beginBlock(BlockCapsule block);

  void beginTx(TxNumMeta txNumMeta);

  void onStoreWrite(StoreWriteEvent event);

  void onSemanticWrite(SemanticStoreWrite write);

  TxWriteSet endTx();

  BlockWriteSet commitBlock();

  void abortTx();

  void abortBlock();

  void unwindBlock(BlockCapsule block);

  Optional<BlockWriteSet> lastCommittedBlockForTest();
}
```

说明：

- `beginTx` 由 `DefaultArchiveService.beginUserTx/beginSystemTx` 调用。
- `endTx` 由 `DefaultArchiveService.endUserTx/endSystemTx` 调用。
- `abortTx` 用于未来更细的 tx rollback；PR3 可以由 `abortBlock` 一次性清理。
- `commitBlock` 在 `tmpSession.commit()` 成功后由 `ArchiveService.commitBlock()` 调用。

## 7. Patch 4：DefaultArchiveWriteCollector

文件：

```text
chainbase/src/main/java/org/tron/core/archive/collector/DefaultArchiveWriteCollector.java
```

Spring bean：

```java
@Component
public final class DefaultArchiveWriteCollector implements ArchiveWriteCollector {
  private final ArchiveDomainRegistry domainRegistry;
  ...
}
```

内部状态：

```java
private PendingBlock pendingBlock;
private PendingTx pendingTx;
private BlockWriteSet lastCommittedBlockForTest;
private long nextSequence;
```

`PendingTx`：

```java
private static final class PendingTx {
  private final TxNumMeta txNumMeta;
  private final Map<DomainKey, DomainWrite> writes = new LinkedHashMap<>();
  private final ArchiveWriteStats stats = new ArchiveWriteStats();
}
```

### 7.1 beginBlock

规则：

```text
if pendingBlock != null:
  throw IllegalStateException
pendingBlock = new PendingBlock(blockNum, blockHash)
nextSequence = 0
```

### 7.2 beginTx

规则：

```text
if pendingBlock == null:
  throw IllegalStateException
if pendingTx != null:
  throw IllegalStateException
pendingTx = new PendingTx(txNumMeta)
```

不要在没有 block 的情况下容忍 tx；否则 Store 写可能被挂到错误 block。

### 7.3 onStoreWrite

流程：

```text
if pendingTx == null:
  stats.writeWithoutTxContext++
  return

event = StoreWriteEvent(...)
domainRegistry.mapStoreWrite(event)
  empty + ignored binding      -> ignored stats
  empty + unclassified binding -> unclassified stats / warn
  present                     -> merge DomainWrite
```

Collector 不应知道 `account`、`contract`、`code` 这些 dbName 的具体规则。

### 7.4 onSemanticWrite

流程：

```text
if pendingTx == null:
  stats.semanticWriteWithoutTxContext++
  return

domainRegistry.mapSemanticWrite(write)
  present -> merge DomainWrite
  empty   -> diagnostic
```

PR4 的 `CONTRACT_STORAGE` 只走 semantic path。

### 7.5 merge

伪代码：

```java
private void merge(DomainWrite write) {
  DomainKey key = DomainKey.of(write.getDomain(), write.getCanonicalKey());
  DomainWrite previous = pendingTx.writes.get(key);
  if (previous == null) {
    pendingTx.writes.put(key, write);
    return;
  }
  pendingTx.writes.put(key, previous.withAfter(
      write.getAfterValue(),
      write.getOperation(),
      write.getLastSequence()));
}
```

必须保留 `previous.beforeValue`。

### 7.6 endTx

流程：

```text
writes = pendingTx.writes values
sort by domainId/canonicalKey
txWriteSet = new TxWriteSet(meta, writes, stats)
pendingBlock.add(txWriteSet)
pendingTx = null
return txWriteSet
```

如果 `endTx` 过程中抛异常，应由上层 block abort。不要吞异常后继续执行 canonical block。

### 7.7 commitBlock / abortBlock

`commitBlock`：

```text
if pendingTx != null:
  throw IllegalStateException
build BlockWriteSet
lastCommittedBlockForTest = blockWriteSet
pendingBlock = null
return blockWriteSet
```

`abortBlock`：

```text
pendingTx = null
pendingBlock = null
```

PR3 不持久化，所以 `unwindBlock` 可以清测试缓存或 no-op；PR5 开始才需要 persisted unwind。

## 8. Patch 5：ArchiveService 扩展

文件：

```text
chainbase/src/main/java/org/tron/core/archive/ArchiveService.java
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
```

### 8.1 接口新增

```java
boolean shouldCollectStoreWrites();

void onStoreWrite(StoreWriteEvent event);

void onSemanticWrite(SemanticStoreWrite write);
```

`shouldCollectStoreWrites()` 很重要：Store hook 先调用它，只有 true 才读取 before-value。默认关闭时不能额外 `revokingDB.getUnchecked(key)`。

### 8.2 DefaultArchiveService 注入 collector

新增字段：

```java
private final ArchiveWriteCollector writeCollector;
```

PR2 lifecycle 同步扩展：

```text
beginBlock:
  txNumIndex.beginBlock(block)
  writeCollector.beginBlock(block)

beginUserTx / beginSystemTx:
  meta = txNumIndex.allocate...
  executionContext.bind(meta)
  writeCollector.beginTx(meta)

endUserTx / endSystemTx:
  try writeCollector.endTx()
  finally executionContext.clear()

commitBlock:
  txNumIndex.completeBlock(currentBlock)
  writeCollector.commitBlock()

abortBlock:
  executionContext.clear()
  writeCollector.abortBlock()
  txNumIndex.abortBlock(currentBlock)
```

顺序要求：

- `beginTx` 要在 Store 写入前完成。
- `endTx` 要在 `executionContext.clear()` 前完成，便于诊断写入归属。
- `commitBlock` 仍必须在 outer `tmpSession.commit()` 成功后执行。
- `abortBlock` 必须清 collector 和 txNum pending。

### 8.3 Store hook gating

```java
@Override
public boolean shouldCollectStoreWrites() {
  return isEnabled() && executionContext.active();
}

@Override
public void onStoreWrite(StoreWriteEvent event) {
  if (!shouldCollectStoreWrites()) {
    return;
  }
  writeCollector.onStoreWrite(event);
}
```

如果 archive enabled 但无 active context 写入 Store，PR3 可只在 `DefaultArchiveService` 计数，不 fail block。

## 9. Patch 6：TronStoreWithRevoking hook

文件：

```text
chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java
```

新增字段：

```java
@Autowired(required = false)
private ArchiveService archiveService;
```

`put` 修改：

```java
@Override
public void put(byte[] key, T item) {
  if (Objects.isNull(key) || Objects.isNull(item)) {
    return;
  }

  byte[] after = item.getData();
  if (archiveService != null && archiveService.shouldCollectStoreWrites()) {
    byte[] before = revokingDB.getUnchecked(key);
    archiveService.onStoreWrite(StoreWriteEvent.put(getDbName(), key, before, after));
  }
  revokingDB.put(key, after);
}
```

`delete` 修改：

```java
@Override
public void delete(byte[] key) {
  if (archiveService != null && archiveService.shouldCollectStoreWrites()) {
    byte[] before = revokingDB.getUnchecked(key);
    archiveService.onStoreWrite(StoreWriteEvent.delete(getDbName(), key, before));
  }
  revokingDB.delete(key);
}
```

要求：

- `after`、`before` 由 event/collector 复制，Store 不额外复制也可以。
- `getDbName()` 必须是 PR1 修复后的 `db.getDbName()`。
- `archiveService == null` 时保持原行为，方便非 Spring 单测。
- 不要在 archive disabled 时读取 before。

## 10. Patch 7：特殊 Store hook

### 10.1 ContractStore

文件：

```text
chainbase/src/main/java/org/tron/core/store/ContractStore.java
```

推荐改为清 ABI 后走 `super.put`：

```java
@Override
public void put(byte[] key, ContractCapsule item) {
  if (Objects.isNull(key) || Objects.isNull(item)) {
    return;
  }

  if (item.getInstance().hasAbi()) {
    item = new ContractCapsule(item.getInstance().toBuilder().clearAbi().build());
  }
  super.put(key, item);
}
```

这样 collector 采到的 afterValue 就是实际落盘值。

### 10.2 AbiStore

文件：

```text
chainbase/src/main/java/org/tron/core/store/AbiStore.java
```

`AbiStore.put(byte[], byte[])` 不是 `ProtoCapsule` 签名，通用 hook 采不到。建议新增可选 `ArchiveService` 字段并按同样 gating：

```text
if archiveService.shouldCollectStoreWrites():
  before = revokingDB.getUnchecked(key)
  archiveService.onStoreWrite(StoreWriteEvent.put(getDbName(), key, before, value))
revokingDB.put(key, value)
```

`abi` 在 registry 中是 history-only/debug-only，不进入 P0 root。

### 10.3 ContractStateStore

文件：

```text
chainbase/src/main/java/org/tron/core/store/ContractStateStore.java
```

`ContractStateStore.put` 也直接 `revokingDB.put`。两种可接受方案：

1. P0+ 纳入 `CONTRACT_STATE` domain：按 `ContractStore` 同样 hook。
2. P0 暂不纳入：仍 hook 到 ArchiveService，让 registry 标记 `PENDING_P1_STATE` 并产生 diagnostic。

不要完全静默。`RepositoryImpl.commitContractStateCache` 会写这个 Store，未来 historical `eth_call` 或完整 root 可能需要它。

### 10.4 TransactionStore

文件：

```text
chainbase/src/main/java/org/tron/core/db/TransactionStore.java
```

不建议在 PR3 改它。`trans` 是 block/tx data，不是 state domain。即使它绕过 `super.put`，registry 已将它分类为 excluded。

## 11. Patch 8：PR4 storage semantic hook

### 11.1 推荐落点

文件：

```text
actuator/src/main/java/org/tron/core/vm/program/Storage.java
```

`Storage.commit()` 最适合发 semantic write，因为这里同时有：

```text
address
contractVersion
logical slot (rowCache map key)
physical row key (StorageRowCapsule.rowKey)
after value
StorageRowStore
```

### 11.2 ArchiveService 传入方式

`Storage` 不是 Spring bean，`RepositoryImpl` 也不是 Spring bean。不要在它们上直接依赖 Spring 注入。

推荐方案：

1. `StorageRowStore` 作为 Spring Store bean 注入 `ArchiveService`。
2. `StorageRowStore` 暴露 package/public 方法：

```java
public ArchiveService getArchiveService() {
  return archiveService;
}
```

3. `Storage.commit()` 通过 `store.getArchiveService()` 获取。

这样不需要改 `RepositoryImpl` 构造函数，也不需要让 `Storage` 成为 bean。

备选方案：

```text
Storage.setArchiveService(archiveService)
RepositoryImpl.getStorage(address) 创建 Storage 后设置
```

但这需要给 `RepositoryImpl` 传入或获取 `ArchiveService`，改动面更大。

### 11.3 Storage.commit 修改

当前：

```java
if (new DataWord(row.getValue()).isZero()) {
  this.store.delete(row.getRowKey());
} else {
  this.store.put(row.getRowKey(), row);
}
```

推荐：

```java
byte[] physicalKey = row.getRowKey();
StorageRowCapsule oldRow = store.getUnchecked(physicalKey);
byte[] before = normalizeStorageValue(oldRow == null ? null : oldRow.getValue());
byte[] rawAfter = row.getValue();
byte[] after = normalizeStorageValue(rawAfter);
ArchiveService archiveService = store.getArchiveService();

if (archiveService != null && archiveService.shouldCollectStoreWrites()) {
  archiveService.onSemanticWrite(SemanticStoreWrite.contractStorage(
      address,
      rowKey.getData(),
      before,
      after,
      physicalKey,
      contractVersion));
}

if (after == null) {
  this.store.delete(physicalKey);
} else {
  this.store.put(physicalKey, row);
}
```

注意：

- `rowKey` 是 `rowCache` map key，代表 logical slot。
- `before` 必须在 delete/put 前读取。
- `rawAfter` 为 32-byte zero 时，semantic write 仍应发出，但 `afterValue` 归一为 null tombstone。
- raw `storage-row` put/delete 仍会被 base hook 看见，但 registry 必须 ignore，不生成第二条 `DomainWrite`。

### 11.4 StorageRowStore.get 风险

`StorageRowStore.get` 当前：

```java
StorageRowCapsule row = getUnchecked(key);
row.setRowKey(key);
return row;
```

missing row 可能 NPE。PR4 新增 before 读取应使用 `getUnchecked`，不要调用 `get`。如果现有测试触发 NPE，可单独修成：

```java
if (row != null) {
  row.setRowKey(key);
}
return row;
```

不要把这个 bugfix 和 collector 语义混在同一个小 patch，除非测试必须。

## 12. Patch 9：raw storage-row 去重

Registry 必须确保：

```text
mapStoreWrite(dbName=storage-row) -> empty / diagnostic
mapSemanticWrite(domain=CONTRACT_STORAGE) -> DomainWrite
```

否则同一个 TVM storage 写会产生两条不同 key：

```text
physical row key
logical address/slot key
```

这是错误的，PR4 测试必须覆盖。

## 13. Patch 10：Collector stats 和 diagnostics

`ArchiveWriteStats` 建议字段：

```java
private long collectedWrites;
private long ignoredWrites;
private long unclassifiedWrites;
private long noOpWrites;
private long writesWithoutTxContext;
private long semanticWrites;
private long codecErrors;
```

诊断原则：

- ignored Store：计数，不 warn。
- unclassified Store：按 `warnUnclassifiedStoreWrites` 每 block/dbName 最多 warn 一次。
- codec error：PR3 应 fail 当前 block apply，避免 silently corrupt archive sidecar。
- no active context：默认计数；如果 archive strict mode 后续开启，再 fail。

## 14. Patch 11：测试清单

### 14.1 Collector unit tests

文件建议：

```text
chainbase/src/test/java/org/tron/core/archive/collector/DefaultArchiveWriteCollectorTest.java
```

覆盖：

1. `beginBlock -> beginTx -> onStoreWrite -> endTx -> commitBlock` 输出 BlockWriteSet。
2. 同一 tx 同一 key `A -> B -> C`，before=A，after=C。
3. 同一 tx `A -> B -> A` 保留 no-op stats。
4. `delete` 输出 operation=DELETE，afterValue=null。
5. `trans` ignored，不进入 writes。
6. unclassified Store 只进入 stats。
7. no pending tx 时 Store write 不进入 writes。
8. `abortBlock` 清空 pending state。
9. writes 输出按 domainId/key 排序。
10. byte array 输入后外部 mutation 不影响 write-set。

### 14.2 Store hook tests

文件建议：

```text
chainbase/src/test/java/org/tron/core/archive/collector/ArchiveStoreHookTest.java
```

覆盖：

1. archive disabled 时 `TronStoreWithRevoking.put` 不读取 before、不调用 collector。
2. archive enabled 但无 active tx context 时不采集。
3. archive enabled + active tx context 时 `AccountStore.put` 产生 `ACCOUNT` write。
4. `delete` 在删除前读取 before。
5. `getDbName()` 返回正确 dbName，否则 registry mapping 失败。

如果难以验证“不读取 before”，可以用 fake `IRevokingDB` 统计 `getUnchecked` 调用。

### 14.3 Special Store tests

覆盖：

1. `ContractStore.put` 清 ABI 后 collector afterValue 不含 ABI。
2. `AbiStore.put(byte[], byte[])` 产生 `ABI` history-only write 或 diagnostic。
3. `ContractStateStore.put` 产生 `CONTRACT_STATE` write 或 PENDING_P1 diagnostic。
4. `TransactionStore.put` 不产生 state write。

### 14.4 Storage semantic tests

文件建议：

```text
actuator/src/test/java/org/tron/core/archive/collector/ArchiveStorageSemanticHookTest.java
```

覆盖：

1. `Storage.commit()` 对非零 storage 输出 semantic write。
2. canonical key 使用 `address21 + logicalSlot32 + storageKeyVersion_u8`，不使用 physical row key。
3. zero value 输出 semantic delete/tombstone write，`afterValue=null`。
4. raw `storage-row` hook 不重复生成 `CONTRACT_STORAGE`。
5. child repository `deposit != null` 时中间 `putStorage` 不触发 final semantic write。
6. `contractVersion=1` 时 `storageKeyVersion=1`，logical slot 仍可用于 reader 语义。
7. `generateAddrHash(trxHash)` 后 physical key 改变，但 canonical domain key 不变。

### 14.5 Manager lifecycle tests

文件建议：

```text
framework/src/test/java/org/tron/core/archive/collector/ArchiveWriteCollectorLifecycleTest.java
```

覆盖：

1. normal block success 后 `lastCommittedBlockForTest()` 有 USER_TX 和 system tx write-set。
2. `processTransaction` 抛异常后 `abortBlock` 清空 pending write-set。
3. empty block 仍有 `BLOCK_PREPARE/BLOCK_FINALIZE` empty write-set。
4. `eraseBlock()` 对 PR3 内存 collector no-op，但不抛异常。

## 15. Review Checklist

PR3 合并前检查：

- [ ] Store hook 先判断 `shouldCollectStoreWrites()`，默认关闭不读 before。
- [ ] `ArchiveWriteCollector` 只通过 `ArchiveDomainRegistry` 做 mapping。
- [ ] 同 tx 同 domain/key 多写合并正确。
- [ ] `DomainKey` 不使用裸 `byte[]` equality。
- [ ] byte arrays 都 defensive copy。
- [ ] `ContractStore` 采集清 ABI 后实际落盘值。
- [ ] `AbiStore` 和 `ContractStateStore` 不静默漏写。
- [ ] `TransactionStore` 不进入 state write-set。
- [ ] ignored/unclassified/codec error 有不同处理。
- [ ] abort block 清空 collector pending state。

PR4 合并前检查：

- [ ] `Storage.commit()` 发 semantic write，不在 child repository 中间态发最终 write。
- [ ] semantic storage key 是 logical address/slot/storageKeyVersion，不包含 physical row key。
- [ ] before-value 在 physical put/delete 前读取。
- [ ] zero storage 语义可区分。
- [ ] raw `storage-row` 不重复生成 `CONTRACT_STORAGE`。
- [ ] create2 不改变 canonical domain key；contractVersion 只通过 `storageKeyVersion_u8` 表达。

## 16. 对后续模块的接口承诺

PR5 TemporalStore 依赖：

```text
BlockWriteSet.txWriteSets in txNum order
TxWriteSet.writes sorted by domain/key
DomainWrite.beforeValue/afterValue/delete
```

PR6 StateReader 依赖：

```text
CONTRACT_STORAGE writes use logical address/slot key
DYNAMIC_PROPERTIES writes are available for historical VM config
```

PR7 CommitmentBuilder 依赖：

```text
same tx merge preserves final value
domainId/key ordering is stable
no raw storage physical key enters root input
```

PR8 historical `eth_call` 依赖：

```text
ACCOUNT / CONTRACT / CODE / CONTRACT_STORAGE / DYNAMIC_PROPERTIES write sets
```

因此 collector 一旦落地，不允许后续模块绕开 `BlockWriteSet` 直接扫描 Store 或 block final state 推断历史。
