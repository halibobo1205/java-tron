# java-tron Archive S4/S5：WriteCollector 与 Storage Semantic Hook 4e80 编码执行包

日期：2026-06-03

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`，`git status --short` 为空，精确冲突标记扫描无命中。

归属路线：[java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)

模块来源：[模块 03 ArchiveWriteCollector：4e80 java-tron 源码对照细化](./20260603-java-tron-module-03-write-collector-4e80-source-deep-dive.md)

收窄执行包：[java-tron Archive L4：WriteCollector + Storage Semantic Hook 代码级执行包](./20260605-java-tron-archive-l4-write-collector-4e80-code-plan.md)

后续真实编码以 L4 收窄执行包为准；本文保留 S4/S5 背景、源码锚点和原始分片。

前置依赖：

- [S1/S2 4e80 编码执行包](./20260603-java-tron-archive-s1-s2-4e80-coding-packet.md)：配置、no-op service、`getDbName()`、Manager lifecycle、txNum。
- [S3 ArchiveDomainRegistry 4e80 编码执行包](./20260603-java-tron-archive-s3-domain-registry-4e80-coding-packet.md)：domain inventory、Store binding、codec、root/history policy。

S4/S5 是第一个真正观察执行写入的批次。它只产出内存 `BlockWriteSet`，不落 archive DB，不实现 historical RPC，不计算 commitment root。

## 1. 交付边界

S4 交付 raw Store 写入采集：

```text
TronStoreWithRevoking.put/delete
  -> StoreWriteEvent(dbName, rawKey, before, after)
  -> ArchiveDomainRegistry.mapRawWrite(...)
  -> TxWriteSet(domain, canonicalKey, firstBefore, finalAfter)
```

S5 交付合约 storage 语义写入采集：

```text
Storage.commit()
  -> SemanticStoreWrite.contractStorage(address21, slot32, before, after, physicalKey, version)
  -> ArchiveDomainRegistry.mapSemanticWrite(...)
  -> TxWriteSet(CONTRACT_STORAGE, address21 || slot32 || version_u8, firstBefore, finalAfter)
```

本批次不交付：

- 不写 `ArchiveTemporalStore`。
- 不写 latest/history/changeset 表。
- 不计算 archive root。
- 不接 JSON-RPC。
- 不把 raw `storage-row` physical key 当成 `CONTRACT_STORAGE` key。
- 不追求覆盖 java-tron 所有 Store；P0 只覆盖 S3 定义的 P0 domain。

完成条件：

1. archive disabled 时 hook 不读取 before-value，不改变 fullnode 路径。
2. archive enabled 且 tx context active 时，P0 Store 写入进入 deterministic `TxWriteSet`。
3. 同一 `txNum + domain + canonicalKey` 多次写只输出 first-before 与 final-after。
4. `ContractStore`、`AbiStore`、`ContractStateStore` 不被 generic hook 漏掉。
5. `CONTRACT_STORAGE` 使用 `(address21, slot32, storageKeyVersion)`，raw `storage-row` 被 registry 排除。
6. `trace.checkNeedRetry()` 不把第一次执行尝试的 pending write 混进最终 tx write-set。

## 2. 4e80 源码锚点

### 2.1 generic Store hook

| 源码 | 当前事实 | S4 约束 |
| --- | --- | --- |
| `chainbase/.../TronStoreWithRevoking.java:47-54` | superclass 已有 Spring 注入字段和底层 `DB<byte[], byte[]> db` | 可在 superclass 再注入 archive service/registry |
| `TronStoreWithRevoking.java:72-75` | 第二个构造器接收现成 `DB` 并包成 `Chainbase` | 测试可用内存 DB 覆盖 hook 行为 |
| `TronStoreWithRevoking.java:78-80` | `getDbName()` 当前返回 `null` | S1 必须先修为 `return db.getDbName()` |
| `TronStoreWithRevoking.java:89-95` | generic `put(byte[], T)` null guard 后 `revokingDB.put(key, item.getData())` | before 必须在 line 94 写入前读取 |
| `TronStoreWithRevoking.java:98-99` | generic `delete(byte[])` 直接 `revokingDB.delete(key)` | before 必须在 line 99 删除前读取 |
| `TronStoreWithRevoking.java:108-115` | `getUnchecked(byte[])` 读取 revoking view，并把坏 capsule 转为 null | raw before-value 可由这里读取 |
| `DB.java:22`、`Chainbase.java:46-47` | dbName 可从底层 DB/Chainbase 透传 | registry 分类以 dbName 为准 |

generic hook 只负责发 raw event；是否进入 domain、是否进 root/history，由 S3 `ArchiveDomainRegistry` 判断。

### 2.2 store-specific 绕过点

| Store | 源码 | 当前事实 | S4 方案 |
| --- | --- | --- | --- |
| `ContractStore` | `ContractStore.java:31-39` | 清 ABI 后直接 `revokingDB.put(key, item.getData())` | 显式 store-specific hook；after 必须是 clearAbi 后 bytes |
| `AbiStore` | `AbiStore.java:27-32` | overload `put(byte[], byte[])` 直接写 raw bytes | 显式 hook；P0 可 history/debug，不进 global root |
| `ContractStateStore` | `ContractStateStore.java:27-32` | override `put` 直接写 `revokingDB` | 显式 hook；P0+ 或 debug domain |
| `TransactionStore` | `TransactionStore.java:33-38` | in-block tx 写 `txId -> blockNum` | registry 标 excluded，不属于 execution state |
| `CodeStore` | `CodeStore.java:13-23` | 未 override `put/delete` | generic hook |

`ContractStore` 不建议简单改成 `super.put()` 来“顺便”复用 generic hook，除非 S3 同步把该 Store 的 `RawHookMode` 改成 generic。当前 S3 已把 `contract` 标为 `STORE_SPECIFIC`，所以 S4 应按 store-specific 路线实现，避免 descriptor 与实际 hook 模式分叉。

### 2.3 普通 actuator 直写 Store

`TransferActuator.execute()` 证明非 TVM 交易会直接写 Store：

| 源码 | 写入 |
| --- | --- |
| `TransferActuator.java:55` | 新账户时 `accountStore.put(toAddress, toAccount)` |
| `TransferActuator.java:60` | 扣 owner 余额 |
| `TransferActuator.java:64` | 非 blackhole optimization 时写 blackhole |
| `TransferActuator.java:66` | 给收款账户加余额 |

所以 collector 不能只挂在 `RepositoryImpl.commit()`；Store-level hook 是完整性来源。

### 2.4 TVM repository commit 边界

| 源码 | 当前事实 | S4/S5 约束 |
| --- | --- | --- |
| `RepositoryImpl.java:638-646` | `saveCode` 写 code cache，并更新 contract codeHash | 最终落盘由 commit 阶段 Store hook 捕获 |
| `RepositoryImpl.java:673-677` | `putStorageValue(address,key,value)` 只更新 `Storage` cache | 不在这里输出最终 storage write |
| `RepositoryImpl.java:766-782` | `commit()` 依次提交 account/code/contract/contract-state/storage/dynamic 等 cache | root repository commit 是最终写入边界 |
| `RepositoryImpl.java:997-1004` | account cache 写 `AccountStore.put` | generic ACCOUNT |
| `RepositoryImpl.java:1009-1016` | code cache 写 `CodeStore.put` | generic CODE |
| `RepositoryImpl.java:1021-1031` | ABI 缺失时写 `AbiStore.put`，再写 `ContractStore.put` | store-specific ABI/CONTRACT |
| `RepositoryImpl.java:1037-1045` | contract-state cache 写 `ContractStateStore.put` | store-specific CONTRACT_STATE |
| `RepositoryImpl.java:1050-1058` | root repository 才 `storage.commit()` | S5 semantic hook 放这里的下层 `Storage.commit()` |
| `RepositoryImpl.java:1063-1070` | dynamic cache 写 `DynamicPropertiesStore.put` | generic DYNAMIC_PROPERTIES + allowlist |

`VMActuator.java:234-250` 在 exception/revert 时不会执行 `rootRepository.commit()`；非异常非 revert 才在 `VMActuator.java:250` 或 `260` commit。因此 S5 不应在 `Storage.put()` 阶段直接输出最终 write，避免 revert/exception path 污染 write-set。

### 2.5 Storage physical key 不可逆

| 源码 | 当前事实 | S5 结论 |
| --- | --- | --- |
| `Storage.java:18-25` | `rowCache` key 是 logical `DataWord`；`address` 是 storage owner | semantic key 可以在 `Storage.commit()` 构造 |
| `Storage.java:26-27` | 只有 `@Setter contractVersion`，没有 getter | S5 需增加 getter 或显式 `storageKeyVersion()` |
| `Storage.java:46-53` | `compose(key, addrHash)` 拼 physical key，`contractVersion == 1` 会先 hash slot | physical key 不能反推 slot |
| `Storage.java:61-70` | create2 用 `address || trxHash` 改 `addrHash` | physical key 也不能稳定反推 address |
| `Storage.java:73-83` | `getValue` 读 physical row 后用 logical key 放入 rowCache | logical slot 只在 VM/Storage 层可见 |
| `Storage.java:86-93` | `put` 只更新 rowCache | 这是 intent/cache，不是最终落盘 |
| `Storage.java:96-105` | `commit` 遍历 dirty row，zero 删除、非 zero put | S5 hook 的最小正确落点 |
| `StorageRowCapsule.java:44-48` | 新 row 构造即 dirty | 新 slot 可以在 commit 看到 |
| `StorageRowCapsule.java:67-69` | `setValue` 覆盖 value 并 dirty | before 不能从 row 本身恢复 |
| `StorageRowStore.java:20-23` | `get()` 返回 capsule 并补 physical rowKey；absent raw value 会表现为 `row.getInstance() == null` | S5 读 before 时应使用显式 helper，把 absent 统一转成 tombstone |

`Program.java:1281-1284` 的 SSTORE 和 `Program.java:1444-1446` 的 SLOAD 都使用 `getContextAddress()`。因此 semantic storage owner 是 context address，不是 code address；delegatecall/callcode 也必须沿用 context owner。

### 2.6 Manager tx lifecycle 与 retry

| 源码 | 当前事实 | S4 约束 |
| --- | --- | --- |
| `Manager.java:1521-1524` | block tx 初始化 balance trace 并 `trxCap.setInBlock(true)` | `beginUserTx` 应覆盖后续 fee/resource 写 |
| `Manager.java:1544-1550` | consume bandwidth/multisign/memo 后 `trace.init/check/exec` | begin tx 要在 resource fee 前 |
| `Manager.java:1552-1561` | `trace.checkNeedRetry()` 后再次 `trace.init/check/exec/setResult` | 必须提供 retry accumulator reset |
| `Manager.java:1567-1572` | `trace.finalization()` 后写 `TransactionStore` | finalization 写仍属当前 tx；`TransactionStore` 由 registry excluded |
| `Manager.java:1593-1597` | tx 末更新/reset balance trace | context 不能过早结束 |
| `Manager.java:1873-1887` | `processBlock` 遍历原始 `block.getTransactions()` | tx context 应按原始 txIndex |
| `Manager.java:1906-1925` | reward/proposal/consensus/dynamic writes | Module 01 的 `BLOCK_FINALIZE` context 必须覆盖这些写 |

## 3. 新增/修改文件

### 3.1 chainbase archive write 包

新增：

```text
chainbase/src/main/java/org/tron/core/archive/write/
  ArchiveValue.java
  ArchiveWriteOp.java
  RawStoreWriteEvent.java
  ArchiveWriteSource.java
  SemanticStoreWrite.java
  DomainWriteKey.java
  DomainWrite.java
  DomainWriteAccumulator.java
  TxWriteMeta.java
  TxWriteSet.java
  BlockWriteSet.java
  WriteCollectStats.java
  ArchiveWriteCollector.java
  DefaultArchiveWriteCollector.java
```

修改：

```text
chainbase/src/main/java/org/tron/core/archive/ArchiveService.java
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
chainbase/src/main/java/org/tron/core/archive/NoopArchiveService.java
chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java
chainbase/src/main/java/org/tron/core/store/ContractStore.java
chainbase/src/main/java/org/tron/core/store/AbiStore.java
chainbase/src/main/java/org/tron/core/store/ContractStateStore.java
chainbase/src/main/java/org/tron/core/store/StorageRowStore.java
actuator/src/main/java/org/tron/core/vm/program/Storage.java
framework/src/main/java/org/tron/core/db/Manager.java
```

S4/S5 不修改：

```text
actuator/src/main/java/org/tron/core/vm/repository/RepositoryImpl.java
```

`RepositoryImpl` 是手工构造对象，不是 Spring bean；把 archive dependency 传入这里会扩大改动面。S5 推荐通过 `StorageRowStore` 暴露 archive service 给 `Storage.commit()`。

## 4. 数据模型

### 4.1 `ArchiveValue`

用显式 value/tombstone 表达缺失，避免在内部混用裸 `null`：

```java
public final class ArchiveValue {
  private final byte[] bytes;

  public static ArchiveValue tombstone() {
    return new ArchiveValue(null);
  }

  public static ArchiveValue of(byte[] bytes) {
    return bytes == null ? tombstone() : new ArchiveValue(bytes.clone());
  }

  public boolean isTombstone() {
    return bytes == null;
  }

  public byte[] bytesOrNull() {
    return bytes == null ? null : bytes.clone();
  }
}
```

对外写 temporal store 时可以转回 `byte[]`/null；collector 内部用 `ArchiveValue` 保证语义明确。

### 4.2 raw event

```java
public enum StoreWriteOp {
  PUT,
  DELETE
}

public enum StoreWriteSource {
  GENERIC_TRON_STORE,
  STORE_SPECIFIC,
  SEMANTIC
}

public final class StoreWriteEvent {
  private final String dbName;
  private final StoreWriteOp op;
  private final byte[] rawKey;
  private final ArchiveValue beforeValue;
  private final ArchiveValue afterValue;
  private final StoreWriteSource source;
}
```

构造规则：

- `dbName` 必须非空；如果 S1 的 `getDbName()` 未修复，archive enabled 时直接 diagnostic/fail fast。
- 所有 byte array defensive copy。
- `DELETE.afterValue` 固定 tombstone。
- `beforeValue` 是 revoking view 中写入前的 raw bytes。
- `storage-row` raw event 可以记录 stats，但 registry 不应映射成 `CONTRACT_STORAGE`。

### 4.3 semantic event

```java
public final class SemanticStoreWrite {
  public enum Kind {
    CONTRACT_STORAGE
  }

  private final Kind kind;
  private final byte[] address;
  private final byte[] logicalKey;
  private final ArchiveValue beforeValue;
  private final ArchiveValue afterValue;
  private final byte[] physicalKey;
  private final int storageKeyVersion;
}
```

`contractStorage(...)` factory 校验：

| 字段 | 校验 |
| --- | --- |
| `address` | 21 bytes TRON address |
| `logicalKey` | 32 bytes TVM slot |
| `beforeValue/afterValue` | tombstone 或 32-byte non-zero value |
| `physicalKey` | 允许 null；非 null 只用于诊断 |
| `storageKeyVersion` | P0 只允许 `0` 或 `1` |

zero 归一规则：

```text
null or 32-byte zero -> tombstone
32-byte non-zero    -> value
other length        -> error
```

### 4.4 domain write

```java
public final class DomainWriteKey {
  private final ArchiveDomain domain;
  private final byte[] canonicalKey;
}

public final class DomainWrite {
  private final ArchiveDomain domain;
  private final byte[] canonicalKey;
  private final ArchiveValue firstBefore;
  private final ArchiveValue finalAfter;
  private final long firstSequence;
  private final long lastSequence;
  private final StoreWriteSource source;
}
```

同一 tx 内的归并规则：

| 已有状态 | 新 event | 输出 |
| --- | --- | --- |
| none | before=A, after=B | firstBefore=A, finalAfter=B |
| firstBefore=A, finalAfter=B | before=B, after=C | firstBefore=A, finalAfter=C |
| firstBefore=A, finalAfter=B | before=X, after=C | firstBefore 仍 A，finalAfter=C，并记录 before mismatch diagnostic |
| firstBefore=A, finalAfter=A | tx end | no-op，可从 `TxWriteSet` 删除或标为 skipped |

before mismatch 不应静默吞掉。它通常意味着 hook 顺序、retry reset、store-specific before 读取或 revoking session 生命周期有错。

### 4.5 write set

```java
public final class TxWriteMeta {
  private final long blockNum;
  private final long txNum;
  private final int txIndex;
  private final ArchivePhase phase;
  private final byte[] txId;
}

public final class TxWriteSet {
  private final TxWriteMeta meta;
  private final List<DomainWrite> writes;
  private final WriteCollectStats stats;
}

public final class BlockWriteSet {
  private final long blockNum;
  private final byte[] blockHash;
  private final long firstTxNum;
  private final long lastTxNum;
  private final List<TxWriteSet> txs;
}
```

`BlockWriteSet.txs` 必须按 txNum 升序；每个 `TxWriteSet.writes` 按 `(domainId, canonicalKey lexicographic)` 输出，避免后续 root/temporal commit 受 HashMap 遍历顺序影响。

## 5. Collector API

```java
public interface ArchiveWriteCollector {
  void beginBlock(ArchiveExecutionContext blockContext);

  void beginTx(ArchiveExecutionContext txContext);

  void onRawPut(String dbName, byte[] rawKey, byte[] beforeValue, byte[] afterValue,
      StoreWriteSource source);

  void onRawDelete(String dbName, byte[] rawKey, byte[] beforeValue, StoreWriteSource source);

  void onSemanticWrite(SemanticStoreWrite write);

  TxWriteSet endTx();

  void abortTx();

  void restartTxForRetry(ArchiveExecutionContext txContext);

  BlockWriteSet finishBlock();

  void abortBlock();
}
```

`DefaultArchiveWriteCollector` 只依赖：

```text
ArchiveDomainRegistry
ArchiveExecutionContext.current()
```

它不依赖 Store、Manager、Repository，也不持久化数据。`ArchiveService` 负责把 Manager lifecycle、Store hook 和 collector 串起来。

## 6. ArchiveService 扩展

在 S1/S2 的基础上扩展接口：

```java
public interface ArchiveService {
  boolean isEnabled();

  boolean hasActiveWriteContext();

  void beginBlock(BlockCapsule block);

  void beginTx(TxNumMeta meta);

  void markRetryCheckpoint();

  void rollbackToRetryCheckpoint();

  void endTx();

  void commitBlock(BlockCapsule block);

  void abortBlock(BlockCapsule block);

  void onRawPut(String dbName, byte[] rawKey, byte[] beforeValue, byte[] afterValue,
      StoreWriteSource source);

  void onRawDelete(String dbName, byte[] rawKey, byte[] beforeValue, StoreWriteSource source);

  void onSemanticWrite(SemanticStoreWrite write);
}
```

实现规则：

- disabled/no active context 时，Store hook 必须快速返回，不读取 before。
- `onRaw*` 内部再检查 registry descriptor；Store 层不要硬编码 domain。
- `markRetryCheckpoint()` 记录 VM 尝试前的位置；`rollbackToRetryCheckpoint()` 只丢弃 checkpoint 后的 attempt 写入。
- `commitBlock()` 在 canonical revoking session commit 成功后调用，把 `BlockWriteSet` 暂存在 service，供 S6/S7 消费。
- `abortBlock()` 丢弃 pending block/tx accumulator。

## 7. Store hook 设计

### 7.1 在 superclass 注入 service

`TronStoreWithRevoking` 是各 Store 的 superclass，Spring 会注入 superclass 字段。推荐加：

```java
@Autowired(required = false)
private ArchiveService archiveService;

protected ArchiveService archiveService() {
  return archiveService == null ? NoopArchiveService.INSTANCE : archiveService;
}
```

S1/S2 如果已提供 `ArchiveService.noop()` 或静态 no-op，可复用现有形态。关键是 Store hook 不能因为测试未启动 Spring 而 NPE。

### 7.2 generic put/delete

伪代码：

```text
put(key, item):
  if key == null or item == null:
    return

  service = archiveService()
  if service.isEnabled() && service.hasActiveWriteContext():
    beforeCapsule = getUnchecked(key)
    before = beforeCapsule == null ? null : beforeCapsule.getData()
    after = item.getData()
    service.onRawPut(getDbName(), key, before, after, GENERIC_TRON_STORE)

  revokingDB.put(key, item.getData())

delete(key):
  service = archiveService()
  if service.isEnabled() && service.hasActiveWriteContext():
    beforeCapsule = getUnchecked(key)
    before = beforeCapsule == null ? null : beforeCapsule.getData()
    service.onRawDelete(getDbName(), key, before, GENERIC_TRON_STORE)

  revokingDB.delete(key)
```

注意：

- `item.getData()` 调用两次可能返回同一数组；event 构造时 clone，Store 仍按原逻辑写。
- 不要在 disabled 或 no context 时调用 `getUnchecked`。
- `getDbName()` 为 null 时，enabled context 下应产生 hard diagnostic；不要把 null 当 unknown silently ignored。

### 7.3 store-specific put

`ContractStore.put`：

```text
put(key, item):
  if key == null or item == null:
    return
  if item has ABI:
    item = clearAbi(item)
  if archive active:
    before = getUnchecked(key).getDataOrNull()
    archiveService().onRawPut("contract", key, before, item.getData(), STORE_SPECIFIC)
  revokingDB.put(key, item.getData())
```

`AbiStore.put(byte[], byte[])`：

```text
if archive active:
  before = getUnchecked(key).getDataOrNull()
  archiveService().onRawPut("abi", key, before, value, STORE_SPECIFIC)
revokingDB.put(key, value)
```

`ContractStateStore.put` 同理，dbName 为 `contract-state`。

不建议在这些 Store 里直接构造 `DomainWrite`。store-specific 的“specific”只表示入口不同，不表示绕过 registry。

## 8. Storage semantic hook 设计

### 8.1 通过 StorageRowStore 暴露 archive service

`Storage` 不是 Spring bean，但持有 `StorageRowStore store`。推荐在 `StorageRowStore` 增加：

```java
public ArchiveService getArchiveService() {
  return archiveService();
}

public StorageRowCapsule getPresentWithRowKey(byte[] key) {
  StorageRowCapsule row = getUnchecked(key);
  if (row == null || row.getInstance() == null) {
    return null;
  }
  row.setRowKey(key);
  return row;
}
```

第二个 helper 是为 S5 before 读取准备的。当前 `StorageRowStore.get(byte[])` 适合现有 `Storage.getValue()` 流程，但它会把 absent raw value 表达成 `rowValue == null` 的 capsule；S5 hook 更需要一个直接返回 null/tombstone 语义的 helper，避免每个调用点重复判断。

### 8.2 增加 storage key version getter

当前 `Storage.contractVersion` 只有 setter。S5 需要读取归一版本：

```java
private int storageKeyVersion() {
  return contractVersion == 1 ? 1 : 0;
}
```

也可以给 `contractVersion` 加 `@Getter`，但推荐显式 helper，避免后续把 raw int 直接写入 canonical key。

### 8.3 `Storage.commit()` hook 顺序

`StorageRowCapsule.setValue()` 会覆盖 row value，所以 before 不能从 dirty row 本身取。正确做法是在真正 put/delete 之前，从 Store 的 revoking view 读取 physical row 当前值：

```text
commit():
  for each (logicalSlot, row) in rowCache:
    if !row.isDirty():
      continue

    physicalKey = row.getRowKey()
    beforeRow = store.getPresentWithRowKey(physicalKey)
    before = normalizeStorageValue(beforeRow == null ? null : beforeRow.getValue())
    after = normalizeStorageValue(row.getValue())

    if archive active:
      store.getArchiveService().onSemanticWrite(
        SemanticStoreWrite.contractStorage(
          address, logicalSlot.getData(), before, after, physicalKey, storageKeyVersion()))

    if after is tombstone:
      store.delete(physicalKey)
    else:
      store.put(physicalKey, row)
```

这样可以覆盖：

- first write 没有先 `getValue()` 的场景；
- 同一 tx 多次 `Storage.put()` 覆盖 row value 的场景；
- loaded row 被 `setValue()` 覆盖后 row 内 before 丢失的场景。

hook 发生在 physical `store.delete/put` 前，raw `storage-row` hook 会随后触发。但 S3 必须把 `storage-row` raw write 映射为 ignored/diagnostic，最终 `CONTRACT_STORAGE` 只能来自 semantic event。

### 8.4 canonical key/value

`CONTRACT_STORAGE` canonical key：

```text
address21 || slot32 || storageKeyVersion_u8
```

value：

```text
tombstone        -> zero/missing
32-byte non-zero -> storage word
```

禁止加入 canonical key：

- physical `storage-row` key；
- `addrHash`；
- create2 `trxHash`；
- code address；
- raw `contractVersion` int。

## 9. Retry 与 abort 语义

`Manager.processTransaction()` 在 `Manager.java:1554-1558` 会重试执行：

```text
trace.setResult()
if trace.checkNeedRetry():
  trace.init(...)
  trace.checkIsConstant()
  trace.exec()
  trace.setResult()
```

L4 不应在 retry 时清空整个 tx accumulator。`consumeBandwidth`、`consumeMultiSignFee`、`consumeMemoFee`、resource receipt 等写入发生在 `Manager.java:1521-1550`，它们在 retry 前已经属于该交易的 canonical 执行副作用，第二次 VM 尝试不会重新播放这些前置写入。

S4 必须在 retry 可能发生前建立 VM-attempt checkpoint，并在第二次 `trace.init()` 前回滚到该 checkpoint：

```text
archiveService.markRetryCheckpoint()
...
if trace.checkNeedRetry():
  archiveService.rollbackToRetryCheckpoint()
  trace.init(...)
```

推荐语义：

1. 保留 checkpoint 之前的 raw/semantic event，例如 bandwidth、memo fee、resource receipt。
2. 丢弃 checkpoint 之后第一次 VM 尝试产生的 raw/semantic event。
3. 保留当前 tx context、txNum、txIndex、txId。
4. stats 记录 retry count，便于测试和诊断。
5. retry 后的 `trace.finalization()`、`TransactionStore.put` 等仍属于同一个 tx context。

block apply 异常、session 未 commit、fork recovery 失败时，Module 01 的 `abortBlock()` 必须调用 collector `abortBlock()`；不能把 pending `BlockWriteSet` 交给 S6/S7。

## 10. Patch 分片

### S4a：collector 数据模型

新增 `ArchiveValue`、event、domain write、write-set 和 stats 类型。

测试：

- byte array defensive copy。
- tombstone/value 判等。
- storage zero 归一为 tombstone。

### S4b：DefaultArchiveWriteCollector

实现 begin/end tx、raw/semantic event mapping、first-before/final-after 归并、排序输出。

测试：

- put A->B、B->C 输出 A->C。
- put 后 delete 输出 tombstone。
- create 后 delete 输出 no-op。
- before mismatch 产生 diagnostic。
- writes 按 domainId/key 排序稳定。

### S4c：ArchiveService 接口扩展

把 Manager tx lifecycle 与 collector 连接起来，no-op 实现保持零副作用。

测试：

- disabled service 调用不创建 write-set。
- no active tx context 的 raw write 被忽略或 hard diagnostic，按配置决定。
- `rollbackToRetryCheckpoint()` 保留 checkpoint 前写入，只丢弃第一次 VM attempt 写入。

### S4d：generic Store hook

修改 `TronStoreWithRevoking.put/delete`。

测试：

- enabled/context active 时读取 before 并调用 service。
- disabled/no context 时不调用 `getUnchecked`。
- delete missing 输出 before tombstone。
- `getDbName()` null 在 enabled context 下报 diagnostic。

### S4e：store-specific hook

修改 `ContractStore`、`AbiStore`、`ContractStateStore`。

测试：

- `ContractStore` after-value 是 clear ABI 后 bytes。
- `AbiStore.put(byte[], byte[])` 被捕获。
- `ContractStateStore.put` 被捕获。
- `TransactionStore` 不进入 execution state write-set。

### S5a：semantic storage event 与 registry mapping

补 `SemanticStoreWrite.contractStorage`、`ArchiveDomainRegistry.mapSemanticWrite`。

测试：

- canonical key = address21 + slot32 + version_u8。
- bad address/slot/value length 报错。
- 32-byte zero 归一为 tombstone。
- raw `storage-row` 不映射 `CONTRACT_STORAGE`。

### S5b：StorageRowStore helper

暴露 archive service 和 absent-safe before 读取 helper。

测试：

- absent key 返回 null/tombstone，不返回 `rowValue == null` 的 present value。
- present key 会补 rowKey。

### S5c：Storage.commit semantic hook

在 dirty row put/delete 前发 semantic write。

测试：

- absent -> nonzero 输出 create。
- nonzero -> new nonzero 输出 update。
- nonzero -> zero 输出 delete/tombstone。
- absent -> zero 输出 no-op。
- 同一合约同 slot 多次写压缩为 final value。
- 不同合约同 slot 不冲突。
- `contractVersion == 1` key suffix 为 `0x01`。

### S4f/S5d：Manager retry 与 end-to-end focused tests

在 `Manager.processTransaction()` retry 分支加 accumulator restart。

测试：

- 构造需要 retry 的 tx 时，最终 `TxWriteSet` 保留 checkpoint 前写入，但不包含第一次 VM 尝试事件。
- VM revert/exception 不产生 storage semantic final write。
- block apply 异常后 collector pending block 被丢弃。

## 11. 测试落点

优先放在 `framework/src/test/java/org/tron/core/archive/write/`，因为需要 Manager/StoreFactory/Store wiring。纯 domain/collector 单测可放在 `chainbase/src/test/java/org/tron/core/archive/write/`，如果 Gradle wiring 不顺，再迁移到 framework test。

建议测试文件：

```text
chainbase/src/test/java/org/tron/core/archive/write/DefaultArchiveWriteCollectorTest.java
chainbase/src/test/java/org/tron/core/archive/write/SemanticStoreWriteTest.java
framework/src/test/java/org/tron/core/archive/write/ArchiveGenericStoreHookTest.java
framework/src/test/java/org/tron/core/archive/write/ArchiveSpecialStoreHookTest.java
framework/src/test/java/org/tron/core/archive/write/ArchiveContractStorageSemanticWriteTest.java
framework/src/test/java/org/tron/core/archive/write/ArchiveRetryLifecycleTest.java
```

不要通过跳过测试来绕过现有失败；若 java-tron 现有测试暴露 hook 副作用，应修 hook 或隔离 archive disabled 路径。

## 12. 编码检查清单

- [ ] `TronStoreWithRevoking.getDbName()` 已由 S1 修为底层 DB name。
- [ ] archive disabled/no context 时 Store hook 不读 before-value。
- [ ] 所有 event 构造时 clone byte array。
- [ ] collector 不硬编码 dbName/domain，统一调用 registry。
- [ ] `ContractStore` after-value 是 clear ABI 后 value。
- [ ] `AbiStore.put(byte[], byte[])` 不漏。
- [ ] `ContractStateStore.put` 不漏。
- [ ] raw `storage-row` 不进入 `CONTRACT_STORAGE` domain。
- [ ] `Storage.commit()` before 从 revoking Store 读取，而不是从 dirty row 读取。
- [ ] absent storage before 读取通过 helper 统一成 tombstone。
- [ ] zero storage 归一为 tombstone。
- [ ] `CONTRACT_STORAGE` key 固定为 `address21 || slot32 || version_u8`。
- [ ] retry 前调用 `markRetryCheckpoint()`，第二次 `trace.init()` 前调用 `rollbackToRetryCheckpoint()`。
- [ ] block abort 丢弃 pending write-set。
- [ ] 输出 `BlockWriteSet` 和 `TxWriteSet` 排序稳定。

## 13. 建议验证命令

文档阶段不需要运行。进入编码后，优先跑 focused tests：

```bash
./gradlew :chainbase:test --tests '*DefaultArchiveWriteCollectorTest'
./gradlew :chainbase:test --tests '*SemanticStoreWriteTest'
./gradlew :framework:test --tests '*ArchiveGenericStoreHookTest'
./gradlew :framework:test --tests '*ArchiveSpecialStoreHookTest'
./gradlew :framework:test --tests '*ArchiveContractStorageSemanticWriteTest'
./gradlew :framework:test --tests '*ArchiveRetryLifecycleTest'
```

合并前按 java-tron 规则跑：

```bash
./gradlew lint
./gradlew build
```
