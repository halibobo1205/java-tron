# java-tron Archive S4：ArchiveWriteCollector 编码执行包

本文把第三个模块 `ArchiveWriteCollector` 落到可编码粒度。S4 对应 PR3：raw Store hook + in-memory `TxWriteSet/BlockWriteSet` collector。S4 不落 temporal DB，不计算 archive root，也不把 `storage-row` physical key 当成 `CONTRACT_STORAGE` 最终 key；storage semantic hook 交给 S5。

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

> 2026-06-03 更新：本文是旧 `a79693e450` 编码包。当前 `4e80f8ffa9a2` 的 S4/S5 编码入口请看 [java-tron Archive S4/S5：WriteCollector 与 Storage Semantic Hook 4e80 编码执行包](./20260603-java-tron-archive-s4-s5-write-collector-storage-4e80-coding-packet.md)，旧行号和部分路径不可直接用于编码。

java-tron 旧文档原始基线：`a79693e450`。

关联文档：

- 当前 4e80 S4/S5 编码入口：[java-tron Archive S4/S5：WriteCollector 与 Storage Semantic Hook 4e80 编码执行包](./20260603-java-tron-archive-s4-s5-write-collector-storage-4e80-coding-packet.md)
- 模块 03 设计：[java-tron Archive 模块 03：ArchiveWriteCollector 细化设计](./20260521-java-tron-archive-module-03-write-collector.md)
- 模块 03 java-tron 源码对照：[模块 03 ArchiveWriteCollector：java-tron 源码对照](./20260601-java-tron-module-03-write-collector-java-tron-source-deep-dive.md)
- Erigon 对照：[模块 03 ArchiveWriteCollector：Erigon 源码对照深挖](./20260526-java-tron-module-03-write-collector-erigon-source-deep-dive.md)
- 模块 03 patch checklist：[java-tron Archive 模块 03：ArchiveWriteCollector 逐文件 Patch 清单](./20260602-java-tron-archive-module-03-write-collector-patch-checklist.md)
- PR3/PR4 规格：[java-tron Archive PR3/PR4 WriteCollector 代码级实现规格](./20260602-java-tron-archive-pr3-pr4-write-collector-implementation-spec.md)
- S3 registry 执行包：[java-tron Archive S3：ArchiveDomainRegistry 编码执行包](./20260602-java-tron-archive-s3-domain-registry-coding-packet.md)

## 1. S4 交付边界

S4 交付：

```text
canonical block apply -> per logical tx raw store writes -> normalized TxWriteSet -> pending BlockWriteSet
```

S4 不交付：

```text
ArchiveTemporalStore persistence
archive state root
historical read API
CONTRACT_STORAGE semantic address+slot final key
all java-tron stores full coverage
```

S4 完成后应能证明：

1. archive disabled 时 raw Store hook 不读取 before-value，不改变现有执行。
2. archive enabled 且处于 canonical block apply context 时，P0 root Store raw writes 被采集。
3. 每个 logical tx 输出一个 deterministic `TxWriteSet`，同一 domain/key 多次写只保留 first-before 和 final-after。
4. block apply 失败、revoking session 未 commit、tx retry、VM revert 都不会把错误中间态交给后续 temporal/root 模块。
5. collector 只通过 S3 `ArchiveDomainRegistry` 和 `RawHookMode` 做 mapping，不硬编码 `dbName -> domain`。

## 2. java-tron 源码事实

### 2.1 通用 Store hook 点

| 源码 | 事实 | S4 结论 |
| --- | --- | --- |
| `TronStoreWithRevoking.java:56-69` | 构造时用 `dbName` 创建 `LevelDB/RocksDB`，再包 `Chainbase(SnapshotRoot(db))` | raw hook 可在 Store 基类拿到底层 DB 名 |
| `TronStoreWithRevoking.java:76-79` | 当前 `getDbName()` 返回 `null` | S1/S3 必须先修为 `db.getDbName()` |
| `TronStoreWithRevoking.java:88-93` | `put(byte[], T)` null guard 后直接 `revokingDB.put(key, item.getData())` | S4 主 put hook；before 必须在 line 93 前读 |
| `TronStoreWithRevoking.java:97-98` | `delete(byte[])` 直接 `revokingDB.delete(key)` | S4 主 delete hook；before 必须在 line 98 前读 |
| `Chainbase.java:123-129` | `put/delete` 写当前 snapshot head | hook 看到的是 revoking session 内状态，不等于已持久化 archive |
| `SnapshotManager.java:119/136-138` | `buildSession()` advance snapshot 并增加 active session | block apply 内 writes 可被 revoke |
| `SnapshotManager.Session.commit():583-585` / `SnapshotManager.java:207-219` | session commit 调 `SnapshotManager.commit()`，提交当前 session 状态 | archive block commit 必须晚于 session commit |
| `SnapshotManager.Session.destroy():607-617` | 未 commit 的 session destroy 会 revoke；`disableOnExit` 时禁用 manager | archive pending block 必须在异常路径 abort |

底层 DB 名：

- `LevelDB.getDbName()` 返回 `db.getDBName()`。
- `RocksDB.getDbName()` 返回 `db.getDBName()`。
- `Chainbase.getDbName()` 返回当前 head 的 `getDbName()`。

### 2.2 特殊 Store 绕过点

| Store | 源码 | 事实 | S4 方案 |
| --- | --- | --- | --- |
| `AccountStore` | `AccountStore.java:68-88` | 有余额 trace/account callback 副作用，最后调用 `super.put` | 继续让基类 hook 捕获最终 account bytes |
| `AccountStore` | `AccountStore.java:92-104` | delete 有余额 trace 副作用，最后调用 `super.delete` | 继续让基类 hook 捕获 delete |
| `ContractStore` | `ContractStore.java:31-39` | 清 ABI 后直接 `revokingDB.put`，绕过 `super.put` | 改为清 ABI 后调用 `super.put`，让 hook 捕获实际落盘 value |
| `AbiStore` | `AbiStore.java:27-32` | `put(byte[], byte[])` 直接 `revokingDB.put`，参数不是 capsule | 保留显式 store-specific hook；不要强行构造通用路径 |
| `ContractStateStore` | `ContractStateStore.java:27-32` | override `put` 后直接 `revokingDB.put` | 可改为 null guard 后 `super.put` |
| `CodeStore` | `CodeStore.java:13-23` | 未 override `put/delete` | 基类 hook |
| `DynamicPropertiesStore` | `DynamicPropertiesStore.java:30` | 继承 `TronStoreWithRevoking<BytesCapsule>` | 基类 hook，S3 allowlist 过滤 root keys |
| `StorageRowStore` | `StorageRowStore.java:12-23` | raw DB 名是 `storage-row`，get 会 set physical rowKey | raw hook 只能 diagnostic/ignored，不能输出 final `CONTRACT_STORAGE` |

### 2.3 VM 和 repository 落盘边界

| 源码 | 事实 | S4/S5 结论 |
| --- | --- | --- |
| `RuntimeImpl.java:53-64` | VM 和普通 actuator 都在 `runtime.execute(context)` 内执行 | Store hook 可覆盖普通 actuator 和 VM root repository commit 后的 raw writes |
| `VMActuator.java:234-250` | exception/revert 清 log/deleteAccounts，异常时不走 `rootRepository.commit()` | S4 不应在 `putStorageValue` 或 child repository 中提前输出 final write |
| `VMActuator.java:250-260` | 非异常非 revert 才执行 `rootRepository.commit()` | final state writes 出现在 root repository commit 之后 |
| `RepositoryImpl.java:753-770` | root `commit()` 依次 commit account/code/contract/contract-state/storage/dynamic 等 cache | raw hook 会在这些 Store put/delete 上触发 |
| `RepositoryImpl.java:948-955` | account cache 最终写 `AccountStore.put` | `ACCOUNT` raw hook |
| `RepositoryImpl.java:960-967` | code cache 最终写 `CodeStore.put` | `CODE` raw hook |
| `RepositoryImpl.java:972-982` | contract cache 先可能写 `AbiStore.put`，再写 `ContractStore.put` | `CONTRACT` 必须用清 ABI 后 value；`ABI` 独立 |
| `RepositoryImpl.java:988-995` | contract-state cache 最终写 `ContractStateStore.put` | P0+/debug path 必须 special/generic hook |
| `RepositoryImpl.java:1001-1008` | storage cache 最终调用 `Storage.commit()` | S5 semantic hook 放这里，不放 `putStorageValue` |
| `RepositoryImpl.java:1014-1020` | dynamic cache 最终写 `DynamicPropertiesStore.put` | `DYNAMIC_PROPERTIES` raw hook + allowlist |
| `Storage.java:96-105` | dirty row value 为 zero 时 delete，否则 put raw `storage-row` | S4 raw `storage-row` 不生成 state domain；S5 在 commit 内发 semantic write |

### 2.4 交易和区块生命周期

| 源码 | 事实 | S4 结论 |
| --- | --- | --- |
| `Manager.java:1374-1376` | normal apply path 在 revoking session 中 `applyBlock(newBlock, txs)`，之后 `tmpSession.commit()` | `archiveService.commitBlock()` 必须在 `tmpSession.commit()` 后 |
| `Manager.java:1858-1872` | `processBlock` 内循环调用 `processTransaction(transactionCapsule, block)`，并由 account state callback 包围 | user tx begin/end 最自然挂在 loop 内 |
| `Manager.java:1492-1498` | `processTransaction` 支持 `blockCap` 为 null | broadcast/pack/pre-exec 路径不能采集 canonical archive write |
| `Manager.java:1515-1518` | `blockCap != null` 时初始化当前交易 balance trace 并 `trxCap.setInBlock(true)` | S4 `shouldCollectStoreWrites()` 至少要求当前 archive tx context active |
| `Manager.java:1531-1541` | 创建 `TransactionTrace`，consume bandwidth/fee 后 `trace.init/checkIsConstant/exec()` | beginUserTx 必须覆盖 resource/fee writes 和 exec writes |
| `Manager.java:1543-1552` | `trace.checkNeedRetry()` 可重新 `trace.init/exec/setResult` | S4 必须在 retry 前 abort+restart 当前 tx accumulator |
| `Manager.java:1558-1577` | `trace.finalization()` 后写 `TransactionStore`、build `TransactionInfo`、post trigger | finalization 内 deleteContract/account fee writes 仍属于当前 tx；`TransactionStore` 由 registry ignored |
| `Manager.java:1584-1588` | tx 结束后更新 balance trace status 并 reset trace | 如果这些 Store 被写，应仍属于当前 tx；但 balance trace Store 不进 state domain |
| `Manager.java:1870-1872` | `accountStateCallBack.preExeTrans/exeTransFinish` 包围 `processTransaction` | archive tx begin/end 应在这层附近，覆盖完整 tx writes |

## 3. Erigon 对 S4 的直接约束

Erigon V3 给 java-tron 的核心启发：

1. write-set 必须绑定 txNum，而不是只在 block 末尾记录一个 delta。
2. collector 内同一 key 多次写，输出只保留 first-before/final-after。
3. raw event 可保留调试计数，但写给 temporal/root 的必须是 normalized domain write。
4. `BlockWriteSet` 可以内存积累到 block 结束后批量提交，但每条 write 仍保留自己的 txNum。
5. unwind/retry/reorg 必须按 tx/block 边界丢弃 pending write，不能让 canonical DB rollback 和 archive sidecar 不一致。

## 4. S4 分片

建议拆成 6 个小 patch。

```text
S4a: collector 数据模型
S4b: DefaultArchiveWriteCollector 聚合逻辑
S4c: ArchiveService 接口扩展和 tx lifecycle
S4d: TronStoreWithRevoking raw hook
S4e: special Store adaptation
S4f: Manager retry/abort 语义和 focused tests
```

S4 可以不一次接 S5 semantic storage。但 S4 的接口应预留 `onSemanticWrite`，让 S5 只补事件来源，不重写 collector。

## 5. 新增/修改文件总览

### 5.1 chainbase archive collector 包

新增：

```text
chainbase/src/main/java/org/tron/core/archive/collector/StoreWriteOp.java
chainbase/src/main/java/org/tron/core/archive/collector/StoreWriteEvent.java
chainbase/src/main/java/org/tron/core/archive/collector/SemanticStoreWrite.java
chainbase/src/main/java/org/tron/core/archive/collector/DomainWriteKey.java
chainbase/src/main/java/org/tron/core/archive/collector/DomainWrite.java
chainbase/src/main/java/org/tron/core/archive/collector/DomainWriteAccumulator.java
chainbase/src/main/java/org/tron/core/archive/collector/TxWriteMeta.java
chainbase/src/main/java/org/tron/core/archive/collector/TxWriteSet.java
chainbase/src/main/java/org/tron/core/archive/collector/BlockWriteSet.java
chainbase/src/main/java/org/tron/core/archive/collector/WriteCollectStats.java
chainbase/src/main/java/org/tron/core/archive/collector/ArchiveWriteCollector.java
chainbase/src/main/java/org/tron/core/archive/collector/DefaultArchiveWriteCollector.java
```

修改：

```text
chainbase/src/main/java/org/tron/core/archive/ArchiveService.java
chainbase/src/main/java/org/tron/core/archive/NoopArchiveService.java
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java
chainbase/src/main/java/org/tron/core/store/ContractStore.java
chainbase/src/main/java/org/tron/core/store/AbiStore.java
chainbase/src/main/java/org/tron/core/store/ContractStateStore.java
framework/src/main/java/org/tron/core/db/Manager.java
```

S4 不修改：

```text
actuator/src/main/java/org/tron/core/vm/program/Storage.java
```

`Storage.java` 留给 S5。

### 5.2 测试落点

优先新增：

```text
framework/src/test/java/org/tron/core/archive/collector/DefaultArchiveWriteCollectorTest.java
framework/src/test/java/org/tron/core/archive/collector/ArchiveStoreHookTest.java
framework/src/test/java/org/tron/core/archive/collector/ArchiveWriteCollectorLifecycleTest.java
framework/src/test/java/org/tron/core/archive/collector/ArchiveSpecialStoreHookTest.java
```

不建议为 S4 单独新增 `chainbase/src/test/java`，因为当前 java-tron 本地源码没有该目录，S1 已建议把早期 archive tests 放在 `framework/src/test`。

## 6. 数据模型

### 6.1 `StoreWriteOp`

```java
public enum StoreWriteOp {
  PUT,
  DELETE
}
```

### 6.2 `StoreWriteEvent`

raw Store 事件只描述 java-tron Store 层看见的事实，不承担 domain mapping：

```java
public final class StoreWriteEvent {
  private final String dbName;
  private final StoreWriteOp op;
  private final byte[] key;
  private final byte[] beforeValue;
  private final byte[] afterValue;

  public static StoreWriteEvent put(String dbName, byte[] key, byte[] beforeValue,
      byte[] afterValue) {
    return new StoreWriteEvent(dbName, StoreWriteOp.PUT, key, beforeValue, afterValue);
  }

  public static StoreWriteEvent delete(String dbName, byte[] key, byte[] beforeValue) {
    return new StoreWriteEvent(dbName, StoreWriteOp.DELETE, key, beforeValue, null);
  }
}
```

约束：

- constructor clone 所有 `byte[]`。
- `dbName` 不能为空。
- `key` 不能为空。
- `PUT.afterValue` 不能为空。
- `DELETE.afterValue` 固定为 null。
- `beforeValue` 可为 null，表示 key 当前不存在。

### 6.3 `SemanticStoreWrite`

S4 只放类型和接口，不生成 contract storage semantic event：

```java
public final class SemanticStoreWrite {
  private final ArchiveDomain domain;
  private final byte[] domainKey;
  private final byte[] beforeValue;
  private final byte[] afterValue;
  private final String source;
}
```

S5 会增加：

```java
SemanticStoreWrite.contractStorage(address21, slot32, beforeValue, afterValue)
```

S4 的 `DefaultArchiveWriteCollector.onSemanticWrite` 可实现完整 merge 逻辑，但测试只覆盖手工传入事件，不改 `Storage.java`。

### 6.4 `DomainWriteKey`

```java
public final class DomainWriteKey implements Comparable<DomainWriteKey> {
  private final ArchiveDomain domain;
  private final byte[] key;
}
```

排序规则：

```text
domain.id unsigned ascending
then key lexicographic unsigned byte ascending
```

注意：

- S3 domain id 是 unsigned u16，不再使用 u8。
- `DomainWriteKey` 不拼接成单个 mutable byte[] 做 map key，避免调用方复用数组导致 hash 变化。

### 6.5 `DomainWrite`

```java
public final class DomainWrite {
  private final ArchiveDomain domain;
  private final byte[] domainKey;
  private final byte[] beforeValue;
  private final byte[] afterValue;
  private final long firstSequence;
  private final long lastSequence;
}
```

语义：

- `beforeValue == null && afterValue != null`：create/put。
- `beforeValue != null && afterValue == null`：delete。
- `beforeValue != null && afterValue != null`：update。
- `beforeValue == null && afterValue == null`：no-op，不能输出。

S4 不单独保留 `isDelete` 字段；`afterValue == null` 就是删除。这样 temporal store 后续可以统一表达 tombstone。

### 6.6 `DomainWriteAccumulator`

同一 tx 内同一个 `DomainWriteKey` 的多次写用 accumulator 合并：

```java
final class DomainWriteAccumulator {
  private final DomainWriteKey key;
  private final byte[] firstBeforeValue;
  private byte[] finalAfterValue;
  private final long firstSequence;
  private long lastSequence;

  void apply(byte[] afterValue, long sequence) {
    this.finalAfterValue = clone(afterValue);
    this.lastSequence = sequence;
  }

  Optional<DomainWrite> finish() {
    if (bytesEqual(firstBeforeValue, finalAfterValue)) {
      return Optional.empty();
    }
    return Optional.of(new DomainWrite(...));
  }
}
```

关键规则：

| tx 内写序列 | 输出 |
| --- | --- |
| absent -> A | before=null, after=A |
| A -> B -> C | before=A, after=C |
| A -> delete | before=A, after=null |
| absent -> A -> delete | no-op |
| A -> B -> A | no-op |
| absent delete | no-op |

### 6.7 `TxWriteMeta`

```java
public final class TxWriteMeta {
  private final long blockNum;
  private final String blockId;
  private final long txNum;
  private final int txIndex;
  private final ArchivePhase phase;
  private final byte[] txId;
}
```

规则：

- user tx：`phase=USER_TX`，`txIndex >= 0`，`txId` 必填。
- system tx：`phase=BLOCK_PREPARE/BLOCK_FINALIZE`，`txIndex=-1`，`txId` 可为 null。
- `txNum` 来自 S2 `ArchiveTxNumIndex`，collector 不自己分配。

### 6.8 `TxWriteSet`

```java
public final class TxWriteSet {
  private final TxWriteMeta meta;
  private final List<DomainWrite> writes;
  private final WriteCollectStats stats;
}
```

约束：

- `writes` 输出前按 `DomainWriteKey` 排序。
- 即使 writes 为空，也允许输出 empty `TxWriteSet`，用于证明 txNum coverage。
- S4 不持久化，只挂到 pending block。

### 6.9 `BlockWriteSet`

```java
public final class BlockWriteSet {
  private final long blockNum;
  private final String blockId;
  private final long firstTxNum;
  private final long afterBlockTxNum;
  private final List<TxWriteSet> txWriteSets;
}
```

S4 只在 memory 中可见。S6/S7 才会把它作为 temporal/root 输入。

### 6.10 `WriteCollectStats`

建议字段：

```java
public final class WriteCollectStats {
  private final int rawStoreEventCount;
  private final int semanticEventCount;
  private final int mappedDomainWriteEventCount;
  private final int ignoredStoreEventCount;
  private final int unclassifiedStoreEventCount;
  private final int noOpDomainWriteCount;
  private final int codecErrorCount;
}
```

S4 对 stats 的目标是测试和诊断，不是 metrics API。

## 7. `ArchiveWriteCollector` 接口

```java
public interface ArchiveWriteCollector {
  void beginTx(TxWriteMeta meta);

  boolean hasActiveTx();

  void onStoreWrite(StoreWriteEvent event);

  void onSemanticWrite(SemanticStoreWrite write);

  TxWriteSet endTx();

  void abortTx();

  void resetBlock();
}
```

语义：

- `beginTx` 时如果已有 active tx，应抛 `ArchiveException`。上层 service 负责先 abort/restart。
- `onStoreWrite` 如果无 active tx，不写入 accumulator；是否 warn 由 `ArchiveService.shouldCollectStoreWrites()` 控制，正常不应调用到这里。
- `endTx` 后清空 active accumulator。
- `abortTx` 丢弃 active accumulator，不输出 `TxWriteSet`。
- `resetBlock` 清空所有 block 内临时状态。

## 8. `DefaultArchiveWriteCollector`

### 8.1 依赖

```java
public final class DefaultArchiveWriteCollector implements ArchiveWriteCollector {
  private final ArchiveDomainRegistry domainRegistry;
  private TxWriteMeta currentMeta;
  private long sequence;
  private final Map<DomainWriteKey, DomainWriteAccumulator> writes = new LinkedHashMap<>();
  private WriteCollectStatsBuilder stats;
}
```

`domainRegistry` 是必需依赖。

### 8.2 raw store mapping

流程：

```text
onStoreWrite(event)
  stats.rawStoreEventCount++
  result = domainRegistry.mapStoreWrite(event)
  switch result:
    mapped domain write(s) -> merge
    ignored              -> stats.ignored++
    unclassified         -> stats.unclassified++, optional warn
    codec error          -> stats.codecError++, throw ArchiveException
```

S3 registry 对 `RawHookMode` 的解释：

| `RawHookMode` | S4 行为 |
| --- | --- |
| `GENERIC_TRON_STORE` | 允许 raw Store event 转成 `DomainWrite` |
| `STORE_SPECIFIC` | 只有经过特殊 Store adaptation 触发的事件允许 mapping；mapping 仍由 registry 决定 |
| `SEMANTIC_ONLY` | raw Store event 不输出 `DomainWrite`，只计 ignored/diagnostic |
| `IGNORED` | 不输出 `DomainWrite` |

`storage-row` 必须是 `SEMANTIC_ONLY`：

```text
StoreWriteEvent(dbName=storage-row, key=physicalRowKey) -> no DomainWrite
```

### 8.3 semantic mapping

S4 只预留：

```text
onSemanticWrite(write)
  domainRegistry.mapSemanticWrite(write)
  merge DomainWrite
```

如果 S4 测试手工构造 `SemanticStoreWrite.contractStorage(...)`，它应能 merge；但 S4 不改 `Storage.commit()`。

### 8.4 merge

```java
private void merge(DomainWrite write) {
  DomainWriteKey key = new DomainWriteKey(write.getDomain(), write.getDomainKey());
  DomainWriteAccumulator previous = writes.get(key);
  if (previous == null) {
    writes.put(key, DomainWriteAccumulator.from(write, sequence++));
    return;
  }
  previous.apply(write.getAfterValue(), sequence++);
}
```

`from(write)` 用 `write.beforeValue` 作为 first-before，用 `write.afterValue` 作为 first final-after。

### 8.5 finish

```java
public TxWriteSet endTx() {
  List<DomainWrite> normalized = writes.values().stream()
      .map(DomainWriteAccumulator::finish)
      .flatMap(Optional::stream)
      .sorted(Comparator.comparing(DomainWrite::key))
      .collect(toUnmodifiableList());
  TxWriteSet result = new TxWriteSet(currentMeta, normalized, stats.build());
  clearCurrentTx();
  return result;
}
```

S4 不应按 raw event sequence 输出 writes；输出顺序必须 deterministic。

## 9. `ArchiveService` 接口增量

S1/S2 skeleton 已有：

```java
beginBlock
beginUserTx
endUserTx
beginSystemTx
endSystemTx
commitBlock
abortBlock
unwindBlock
```

S4 增加：

```java
boolean shouldCollectStoreWrites();

void onStoreWrite(StoreWriteEvent event);

void onSemanticWrite(SemanticStoreWrite write);

void abortCurrentTx();

void restartCurrentTx();
```

默认/no-op 实现：

```java
default boolean shouldCollectStoreWrites() {
  return false;
}

default void onStoreWrite(StoreWriteEvent event) {
}

default void onSemanticWrite(SemanticStoreWrite write) {
}

default void abortCurrentTx() {
}

default void restartCurrentTx() {
}
```

`DefaultArchiveService` 实现：

| 方法 | 行为 |
| --- | --- |
| `shouldCollectStoreWrites` | `config.enabled && executionContext.hasActiveTx()` |
| `beginUserTx` | 用 S2 context/txNum 创建 `TxWriteMeta`，调用 collector.beginTx |
| `endUserTx` | collector.endTx，append 到 pending block |
| `abortCurrentTx` | collector.abortTx |
| `restartCurrentTx` | 用当前 `TxWriteMeta` abort 后重新 begin |
| `commitBlock` | session commit 后 seal `BlockWriteSet`，S4 只保存到 test-visible pending/sink |
| `abortBlock` | abort active tx + reset pending block |

`restartCurrentTx` 不重新分配 txNum。retry 后的最终结果仍属于同一个 logical tx。

## 10. Manager 生命周期改造

### 10.1 block apply helper

S2 已建议抽 helper：

```java
private void applyBlockWithArchive(BlockCapsule block, List<TransactionCapsule> txs)
    throws ... {
  archiveService.beginBlock(block);
  boolean committed = false;
  try (ISession tmpSession = revokingStore.buildSession()) {
    applyBlock(block, txs);
    tmpSession.commit();
    archiveService.commitBlock();
    committed = true;
  } finally {
    if (!committed) {
      archiveService.abortBlock();
    }
  }
}
```

S4 强化约束：

- `commitBlock()` 必须在 `tmpSession.commit()` 后。
- `abortBlock()` 必须覆盖 `applyBlock` 异常、`tmpSession.commit()` 异常、`archiveService.commitBlock()` 异常。
- S4 不在 `commitBlock()` 里写 archive DB，所以如果 `commitBlock()` 失败，只能暴露编程错误；PR5 后要重新评估错误处理。

### 10.2 user tx loop

S2 的示意可以改成 S4 安全版：

```java
int txIndex = 0;
for (TransactionCapsule transactionCapsule : block.getTransactions()) {
  archiveService.beginUserTx(block, transactionCapsule, txIndex);
  boolean txEnded = false;
  try {
    accountStateCallBack.preExeTrans();
    TransactionInfo result = processTransaction(transactionCapsule, block);
    accountStateCallBack.exeTransFinish();
    archiveService.endUserTx();
    txEnded = true;
    if (Objects.nonNull(result)) {
      results.add(result);
    }
  } finally {
    if (!txEnded) {
      archiveService.abortCurrentTx();
    }
  }
  txIndex++;
}
```

原因：

- 如果 `processTransaction` 抛异常，revoking session 会在外层 abort，collector 也必须丢弃当前 tx accumulator。
- `endUserTx()` 不能放在无条件 finally 里，否则失败 tx 的中间 writes 会进入 pending block。
- `txIndex++` 必须在 finally 后，避免异常路径污染后续状态。

### 10.3 retry hook

在 `Manager.processTransaction` 的 retry 分支：

```java
if (trace.checkNeedRetry()) {
  archiveService.restartCurrentTx();
  trace.init(blockCap, eventPluginLoaded);
  trace.checkIsConstant();
  trace.exec();
  trace.setResult();
}
```

只在 `blockCap != null` 的分支中调用。`restartCurrentTx()` 的行为：

```text
abort current accumulator
begin new accumulator with same TxWriteMeta / txNum / txId
```

如果 retry 时没有 active tx，`restartCurrentTx()` 应按配置 warn 或抛 `ArchiveException`。实现建议抛，因为这说明 Manager hook 顺序错误。

### 10.4 system phases

S4 可先不采集 system phase writes，也可以保留 S2 的 system txNum。推荐：

- `BLOCK_PREPARE`：允许采集 `BalanceTraceStore.initCurrentBlockBalanceTrace(block)`、`saveBlockEnergyUsage(0)` 等当前源码存在的前置写，但 registry 多数会 ignored。
- `BLOCK_FINALIZE`：允许采集 adaptive energy / proposal maintenance 等 dynamic writes。

如果为了控制 PR3 面积，也可 S4 只打开 USER_TX raw collection，但接口必须保留 system phase。最终实现 state root 时 system writes 必须有 txNum，否则 block-end root 不可重放。

## 11. `TronStoreWithRevoking` hook

### 11.1 依赖注入

新增字段：

```java
@Autowired(required = false)
private ArchiveService archiveService = ArchiveService.NOOP;
```

如果 Spring 不允许接口字段默认值和 autowire 共存，可用 setter：

```java
@Autowired(required = false)
public void setArchiveService(ArchiveService archiveService) {
  this.archiveService = archiveService == null ? ArchiveService.NOOP : archiveService;
}
```

### 11.2 put hook

当前：

```java
revokingDB.put(key, item.getData());
```

改为：

```java
byte[] afterValue = item.getData();
if (archiveService != null && archiveService.shouldCollectStoreWrites()) {
  byte[] beforeValue = revokingDB.getUnchecked(key);
  archiveService.onStoreWrite(StoreWriteEvent.put(getDbName(), key, beforeValue, afterValue));
}
revokingDB.put(key, afterValue);
```

约束：

- `shouldCollectStoreWrites()` 必须先判断，disabled 时不读 `beforeValue`。
- before 用 `revokingDB.getUnchecked(key)`，不要用 `getUnchecked(key)`，避免反序列化 capsule 失败或副作用。
- `StoreWriteEvent` constructor clone bytes，hook 不额外 clone 也可接受；若不确定调用方是否复用 `item.getData()`，事件内部必须 clone。
- `getDbName()` 必须已修成 `db.getDbName()`。

### 11.3 delete hook

当前：

```java
revokingDB.delete(key);
```

改为：

```java
if (archiveService != null && archiveService.shouldCollectStoreWrites()) {
  byte[] beforeValue = revokingDB.getUnchecked(key);
  archiveService.onStoreWrite(StoreWriteEvent.delete(getDbName(), key, beforeValue));
}
revokingDB.delete(key);
```

如果 `beforeValue == null`，collector 最终应输出 no-op，但 stats 可以记录 raw delete。

## 12. 特殊 Store 改造

### 12.1 `ContractStore`

当前：

```java
if (item.getInstance().hasAbi()) {
  item = new ContractCapsule(item.getInstance().toBuilder().clearAbi().build());
}
revokingDB.put(key, item.getData());
```

S4 改为：

```java
if (item.getInstance().hasAbi()) {
  item = new ContractCapsule(item.getInstance().toBuilder().clearAbi().build());
}
super.put(key, item);
```

测试必须证明：

- `afterValue` 不含 ABI。
- `RepositoryImpl.commitContractCache` 中 `AbiStore.put` 仍可保存 ABI。
- 合约部署路径不丢 contract/code/account。

### 12.2 `ContractStateStore`

当前直接 `revokingDB.put`，可改：

```java
super.put(key, item);
```

S3 如果暂把 `CONTRACT_STATE` 设为 history/debug only，collector 可 mapping 后 ignored 或输出 history-only。关键是不再绕过 hook。

### 12.3 `AbiStore`

`AbiStore.put(byte[], byte[])` 不能直接走 `super.put(byte[], AbiCapsule)`，因为调用方已经传 bytes，且该方法当前 API 是 byte[]。

建议新增 private helper：

```java
private void putRawAbi(byte[] key, byte[] value) {
  if (archiveService != null && archiveService.shouldCollectStoreWrites()) {
    byte[] beforeValue = revokingDB.getUnchecked(key);
    archiveService.onStoreWrite(StoreWriteEvent.put(getDbName(), key, beforeValue, value));
  }
  revokingDB.put(key, value);
}
```

然后 `put(byte[], byte[])` 调 `putRawAbi`。

如果 S4 不想采集 ABI，也仍应让 registry 通过 `RawHookMode.STORE_SPECIFIC` 返回 ignored/debug，而不是跳过 hook 后留下不可解释缺口。

## 13. Registry 协作

S4 依赖 S3 提供至少这些 API：

```java
Optional<StoreBinding> bindingForStore(String dbName);
StoreWriteMapping mapStoreWrite(StoreWriteEvent event);
StoreWriteMapping mapSemanticWrite(SemanticStoreWrite event);
```

`StoreWriteMapping` 建议不是裸 `Optional<DomainWrite>`，而是可表达 ignored/unclassified/error：

```java
public final class StoreWriteMapping {
  public enum Kind {
    MAPPED,
    IGNORED,
    UNCLASSIFIED
  }

  private final Kind kind;
  private final List<DomainWrite> writes;
  private final String reason;
}
```

S4 collector 对 mapping 的唯一判断依据是 `Kind`。不要在 collector 中写：

```java
if ("account".equals(dbName)) ...
```

## 14. 错误和告警策略

| 场景 | S4 行为 |
| --- | --- |
| archive disabled | Store hook 不读 before、不告警、不调用 collector |
| archive enabled but no active tx | `shouldCollectStoreWrites=false`，Store hook 不调用 collector |
| active tx + unclassified dbName | stats + block/dbName 限频 warn；不输出 write |
| active tx + codec error | 抛 `ArchiveException`，让当前 block apply fail |
| active tx + registry ignored | stats ignored；不输出 write |
| active tx + no-op write | stats no-op；`TxWriteSet.writes` 不含该 key |
| retry | abort current tx accumulator，same txNum restart |
| block/session abort | `archiveService.abortBlock()` 清空 pending block |

codec error 不能静默 ignored。否则 archive sidecar 会和 canonical DB 分叉而节点仍继续运行。

## 15. 测试矩阵

### 15.1 collector unit

文件：

```text
framework/src/test/java/org/tron/core/archive/collector/DefaultArchiveWriteCollectorTest.java
```

用例：

1. begin/end empty tx 输出 empty `TxWriteSet`。
2. absent -> put 输出 create。
3. update same key 多次只保留 first-before/final-after。
4. put then delete 回到 absent 输出 no-op。
5. update then restore original 输出 no-op。
6. 多 domain/key 输出顺序 deterministic。
7. `storage-row` raw event 被 ignored。
8. unclassified store 只增加 stats。
9. codec error 抛 `ArchiveException`。

### 15.2 Store hook tests

文件：

```text
framework/src/test/java/org/tron/core/archive/collector/ArchiveStoreHookTest.java
```

用例：

1. archive disabled 时 `put` 不读取 before，不调用 collector。
2. active tx 时 `TronStoreWithRevoking.put` 读取 before 并上报 after bytes。
3. active tx 时 `delete` 读取 before 并上报 tombstone。
4. no active tx 时即使 archive enabled 也不读 before。
5. `getDbName()` 返回真实 DB 名。

如果 mocking `revokingDB.getUnchecked` 成本高，可用 `SnapshotManagerTest`/`ChainbaseTest` 风格构造 in-memory DB。

### 15.3 special Store tests

文件：

```text
framework/src/test/java/org/tron/core/archive/collector/ArchiveSpecialStoreHookTest.java
```

用例：

1. `ContractStore.put` 清 ABI 后上报 `CONTRACT` after bytes。
2. `AbiStore.put(byte[], byte[])` 上报 `ABI` raw event 或 registry ignored/debug stats。
3. `ContractStateStore.put` 不再绕过 hook。
4. `AccountStore.put/delete` 原有 balance trace/callback 仍执行，且 hook 看到最终 Store write。

### 15.4 lifecycle tests

文件：

```text
framework/src/test/java/org/tron/core/archive/collector/ArchiveWriteCollectorLifecycleTest.java
```

用例：

1. successful block session commit 后 `commitBlock` seal block write set。
2. `processTransaction` 抛异常时 `abortCurrentTx` 丢弃 writes。
3. block apply 抛异常时 `abortBlock` 清空 pending block。
4. retry 时 first attempt writes 被丢弃，same txNum 的 second attempt writes 被保留。
5. broadcast path `processTransaction(trx, null)` 不采集 archive writes。

### 15.5 storage handoff tests

S4 只写接口级测试：

1. 手工 `onSemanticWrite(CONTRACT_STORAGE)` 能 merge。
2. raw `storage-row` event 不输出 `CONTRACT_STORAGE`。

真正 `Storage.commit()` 测试放 S5。

## 16. 验收检索

实现后建议检索：

```text
rg -n 'revokingDB\\.put\\(' chainbase/src/main/java/org/tron/core/store chainbase/src/main/java/org/tron/core/db
rg -n 'revokingDB\\.delete\\(' chainbase/src/main/java/org/tron/core/store chainbase/src/main/java/org/tron/core/db
rg -n '\"account\"|\"contract\"|\"storage-row\"' chainbase/src/main/java/org/tron/core/archive/collector
```

期望：

- collector 包不出现硬编码 `dbName`。
- 剩余直接 `revokingDB.put/delete` 都有明确 ignored/index/debug 说明，或已经有 store-specific hook。
- `storage-row` 只在 registry/tests 中作为 semantic-only/ignored raw 出现。

## 17. S4 出口到 S5/S6

S4 完成后的接口状态：

```text
BlockWriteSet
  TxWriteSet(USER_TX txNum=...)
    DomainWrite(ACCOUNT/CONTRACT/CODE/DYNAMIC_PROPERTIES ...)
```

S5 接入：

```text
Storage.commit()
  -> archiveService.onSemanticWrite(CONTRACT_STORAGE address+slot)
  -> same DefaultArchiveWriteCollector.merge()
```

S6 接入：

```text
archiveService.commitBlock()
  -> ArchiveTemporalStore.apply(BlockWriteSet)
```

S7 接入：

```text
CommitmentBuilder.stageBlockEnd(BlockWriteSet, ArchiveBatch)
```

所以 S4 的强约束是：`BlockWriteSet` 是后续 temporal/root 的唯一写输入，不能让 S6/S7 再回头扫 canonical Store。

## 18. S4 完成标准

- [ ] `ArchiveWriteCollector` 数据模型不可变，byte[] defensive copy。
- [ ] 同 tx same domain/key 归并保留 first-before/final-after。
- [ ] no-op writes 不进入 `TxWriteSet.writes`。
- [ ] output writes 按 domain id + key 排序。
- [ ] Store hook disabled/no-active 时不读 before。
- [ ] Store hook active 时 before 在 put/delete 前读取。
- [ ] `ContractStore` 捕获清 ABI 后实际落盘值。
- [ ] `AbiStore` 和 `ContractStateStore` 不再无说明绕过 hook。
- [ ] raw `storage-row` 不生成 `CONTRACT_STORAGE`。
- [ ] retry 会 abort+restart current tx accumulator，txNum 不变。
- [ ] failed tx/block 不 seal pending writes。
- [ ] broadcast/pre-exec path 不采集 canonical archive writes。
- [ ] collector 不硬编码 `dbName -> domain`。
- [ ] S4 不写 temporal DB，不计算 root。
