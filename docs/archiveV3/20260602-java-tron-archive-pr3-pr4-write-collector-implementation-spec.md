# java-tron Archive PR3/PR4 WriteCollector 代码级实现规格

日期：2026-06-02

关联实现蓝图：[java-tron Archive 状态树实现蓝图](./20260602-java-tron-archive-implementation-blueprint.md)

前置规格：[java-tron Archive PR1/PR2 代码级实现规格](./20260602-java-tron-archive-pr1-pr2-implementation-spec.md)

DomainRegistry 逐文件清单：[java-tron Archive 模块 02：ArchiveDomainRegistry 逐文件 Patch 清单](./20260602-java-tron-archive-module-02-domain-registry-patch-checklist.md)

S3 DomainRegistry 编码执行包：[java-tron Archive S3：ArchiveDomainRegistry 编码执行包](./20260602-java-tron-archive-s3-domain-registry-coding-packet.md)

S4 WriteCollector 编码执行包：[java-tron Archive S4：ArchiveWriteCollector 编码执行包](./20260602-java-tron-archive-s4-write-collector-coding-packet.md)

S5 Contract Storage semantic hook 编码执行包：[java-tron Archive S5：Contract Storage Semantic Hook 编码执行包](./20260602-java-tron-archive-s5-contract-storage-semantic-coding-packet.md)

WriteCollector 逐文件清单：[java-tron Archive 模块 03：ArchiveWriteCollector 逐文件 Patch 清单](./20260602-java-tron-archive-module-03-write-collector-patch-checklist.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 旧文档原始基线：`a79693e450`，当前 4e80 实现请看 2026-06-03 细化文档。

## 1. 范围

本文细化后续两个 PR：

```text
PR 3: Store-level ArchiveWriteCollector P0
PR 4: CONTRACT_STORAGE semantic hook
```

PR3/PR4 的目标是把 canonical block apply 期间真实生效的状态写收集成 `TxWriteSet`，但仍不落 `ArchiveTemporalStore`。也就是说，这两步只解决：

```text
txNum context + Store writes -> pending BlockWriteSet
```

不做：

- 不实现 before-value history DB。
- 不实现 `GetAsOf`。
- 不改 JSON-RPC。
- 不计算 root。
- 不改区块头。

## 2. 源码证据

| 位置 | 事实 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java:88-93` | 通用 `put(byte[] key, T item)` |
| `TronStoreWithRevoking.java:97-98` | 通用 `delete(byte[] key)` |
| `chainbase/src/main/java/org/tron/core/store/ContractStore.java:31` | `ContractStore.put` 重写 |
| `ContractStore.java:36` | 写入前清空 ABI |
| `ContractStore.java:39` | 直接 `revokingDB.put`，绕过 `super.put` |
| `chainbase/src/main/java/org/tron/core/db/TransactionStore.java:29` | `TransactionStore.put` 重写 |
| `TransactionStore.java:33` | in-block tx 写 blockNum，不写交易本体 |
| `actuator/src/main/java/org/tron/core/vm/repository/RepositoryImpl.java:625` | `saveCode` |
| `RepositoryImpl.java:660` | `putStorageValue` |
| `RepositoryImpl.java:753` | `commit` |
| `RepositoryImpl.java:948 / 954` | `commitAccountCache` 写 `AccountStore` |
| `RepositoryImpl.java:960 / 966` | `commitCodeCache` 写 `CodeStore` |
| `RepositoryImpl.java:972 / 980-982` | `commitContractCache` 写 `AbiStore`/`ContractStore` |
| `RepositoryImpl.java:988 / 995` | `commitContractStateCache` 写 `ContractStateStore` |
| `RepositoryImpl.java:1001 / 1008` | `commitStorageCache` 调 `Storage.commit()` |
| `RepositoryImpl.java:1014 / 1020` | `commitDynamicCache` 写 `DynamicPropertiesStore` |
| `RepositoryImpl.java:690` | `getStorage(address)` |
| `RepositoryImpl.java:705` | `new Storage(address, getStorageRowStore())` |
| `RepositoryImpl.java:709` | 设置 contract version |
| `RepositoryImpl.java:711` | create2 场景 `generateAddrHash(contract.getTrxHash())` |
| `actuator/src/main/java/org/tron/core/vm/program/Storage.java:46` | `compose(key, addrHash)` 生成 physical row key |
| `Storage.java:86` | `put(DataWord key, DataWord value)` |
| `Storage.java:96` | `commit()` |
| `Storage.java:100` | zero storage -> `store.delete(rowKey)` |
| `Storage.java:102` | non-zero storage -> `store.put(rowKey,row)` |
| `chainbase/src/main/java/org/tron/core/store/StorageRowStore.java:20` | `get(byte[] key)` 读 row 并设置 rowKey |
| `chainbase/src/main/java/org/tron/core/capsule/StorageRowCapsule.java:67` | `setValue` 标记 dirty |
| `StorageRowCapsule.java:73` | `getData()` 返回 row value |

关键结论：

1. `TronStoreWithRevoking` 可以覆盖大部分 Store，但不是全部。
2. `ContractStore` 必须单独修正，否则 CONTRACT domain 会漏采。
3. `TransactionStore` 这类索引 Store 不进入 archive state root；PR3 只诊断，不采集为 state domain。
4. CONTRACT_STORAGE 不能只靠 `StorageRowStore` raw key，因为 raw key 是 physical row key，不是 RPC 需要的 `(address, slot)`。

## 3. PR 3：Store-level ArchiveWriteCollector P0

### 3.1 改动文件

```text
chainbase/src/main/java/org/tron/core/archive/domain/ArchiveDomain.java
chainbase/src/main/java/org/tron/core/archive/domain/ArchiveDomainDescriptor.java
chainbase/src/main/java/org/tron/core/archive/domain/ArchiveDomainRegistry.java
chainbase/src/main/java/org/tron/core/archive/domain/DefaultArchiveDomainRegistry.java
chainbase/src/main/java/org/tron/core/archive/domain/StoreBinding.java
chainbase/src/main/java/org/tron/core/archive/domain/StoreCategory.java
chainbase/src/main/java/org/tron/core/archive/domain/HistoryPolicy.java
chainbase/src/main/java/org/tron/core/archive/domain/RootPolicy.java
chainbase/src/main/java/org/tron/core/archive/domain/ReaderPolicy.java
chainbase/src/main/java/org/tron/core/archive/domain/CanonicalKeyCodec.java
chainbase/src/main/java/org/tron/core/archive/domain/CanonicalValueCodec.java
chainbase/src/main/java/org/tron/core/archive/collector/ArchiveWriteCollector.java
chainbase/src/main/java/org/tron/core/archive/collector/BlockWriteSet.java
chainbase/src/main/java/org/tron/core/archive/collector/TxWriteSet.java
chainbase/src/main/java/org/tron/core/archive/collector/DomainWrite.java
chainbase/src/main/java/org/tron/core/archive/collector/StoreWriteEvent.java
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java
chainbase/src/main/java/org/tron/core/store/ContractStore.java
framework/src/test/java/org/tron/core/archive/ArchiveWriteCollectorTest.java
framework/src/test/java/org/tron/core/archive/ArchiveStoreHookTest.java
```

### 3.2 P0 domain registry

`ArchiveDomainRegistry` 的逐文件设计以 [模块 02 Patch 清单](./20260602-java-tron-archive-module-02-domain-registry-patch-checklist.md) 为准。PR3/PR4 的 WriteCollector 不应硬编码 dbName -> domain，而应通过 registry 映射。

P0 registry 至少覆盖。S4 collector 只能读取 S3 `ArchiveDomainRegistry` 的 `RawHookMode`，不能重新硬编码 `dbName -> domain`：

| source DB | domain | RawHookMode | PR3 行为 | PR4 行为 |
| --- | --- | --- | --- | --- |
| `account` | `ACCOUNT` | `GENERIC_TRON_STORE` | 采集 | 不变 |
| `contract` | `CONTRACT` | `STORE_SPECIFIC` | 特殊 hook 采集清 ABI 后 value | 不变 |
| `code` | `CODE` | `GENERIC_TRON_STORE` | 采集 | 不变 |
| `properties` | `DYNAMIC_PROPERTIES` | `GENERIC_TRON_STORE` | 采集，但 root policy key-filtered | 不变 |
| `storage-row` | `CONTRACT_STORAGE` | `SEMANTIC_ONLY` | raw 写只诊断或暂存 | semantic hook 正式采集 |
| `abi` | `ABI` | `STORE_SPECIFIC` | history-only 或 debug-only | 不变 |

P1/P0+ 必须已分类但可暂不采集：

```text
contract-state
asset-issue
asset-issue-v2
account-asset
witness
witness_schedule
votes
proposal
DelegatedResource
delegation
DelegatedResourceAccountIndex
exchange / exchange-v2 / market_*
nullifier / zkProof / IncrementalMerkleTree
```

这些 Store 不能留成 unknown。P0 如果不采集，应标为 `PENDING_P1_STATE` 并记录 reason。

明确 ignore 的 Store：

```text
block
block-index
trans
transactionRetStore
transactionHistoryStore
recent-block
recent-transaction
balance-trace
account-trace
section-bloom
```

这些不是执行状态 domain。PR3 可记录 `IGNORED_INDEX_STORE` 计数，但不进入 `TxWriteSet`。

未注册 Store：

```text
warnUnclassifiedStoreWrites=true 时计数并 warn，一条 tx 内同 store/key 可合并 warn。
```

不要因为未注册 Store 直接 fail block；P0 coverage 是 `TVM_STATE_ONLY`，后续 root 对外承诺前再收紧。

特别约束：

```text
storage-row raw StoreWriteEvent -> 不直接生成 CONTRACT_STORAGE DomainWrite
CONTRACT_STORAGE DomainWrite -> 只能来自 PR4 semantic storage event
```

### 3.3 数据结构

#### StoreWriteEvent

```java
public class StoreWriteEvent {
  private final String dbName;
  private final byte[] rawKey;
  private final byte[] beforeValue;
  private final byte[] afterValue;
  private final boolean delete;
}
```

#### DomainWrite

```java
public class DomainWrite {
  private final ArchiveDomain domain;
  private final byte[] canonicalKey;
  private final byte[] beforeValue;
  private final byte[] afterValue;
  private final byte[] rawKey;
  private final String sourceDbName;
  private final boolean delete;
}
```

#### TxWriteSet

```java
public class TxWriteSet {
  private final TxNumMeta txNumMeta;
  private final Map<DomainKey, DomainWrite> writes;
}
```

同一 tx 内同一 domain/key 多次写：

```text
beforeValue 保留第一次写之前的值
afterValue 更新为最后一次写之后的值
```

#### BlockWriteSet

```java
public class BlockWriteSet {
  private final long blockNum;
  private final byte[] blockHash;
  private final List<TxWriteSet> txWriteSets;
}
```

PR3 只保存在内存中，供测试和后续 PR5 TemporalStore 消费。

### 3.4 ArchiveWriteCollector 接口

```java
public interface ArchiveWriteCollector {
  void beginBlock(BlockCapsule block);

  void beginTx(TxNumMeta txNumMeta);

  void onStoreWrite(StoreWriteEvent event);

  void endTx();

  BlockWriteSet commitBlock();

  void abortBlock();

  void unwindBlock(BlockCapsule block);

  Optional<BlockWriteSet> lastCommittedBlockForTest();
}
```

`lastCommittedBlockForTest()` 可用 `@VisibleForTesting` 标记；如果项目避免 Guava 注解，也可以放在实现类 package-private 方法里。

### 3.5 Collector 压缩规则

伪代码：

```java
void addWrite(DomainWrite write) {
  DomainKey key = new DomainKey(write.getDomain(), write.getCanonicalKey());
  DomainWrite previous = currentTx.writes().get(key);
  if (previous == null) {
    currentTx.writes().put(key, write);
    return;
  }

  currentTx.writes().put(key, new DomainWrite(
      write.getDomain(),
      write.getCanonicalKey(),
      previous.getBeforeValue(),
      write.getAfterValue(),
      write.getRawKey(),
      write.getSourceDbName(),
      write.isDelete()));
}
```

same-value 策略：

```text
PR3 先保留 same-value write，并标记 metric。
PR5 TemporalStore 再决定是否跳过 history。
```

理由：PR3 阶段先追求采集可观察性，避免过早优化隐藏写路径。

### 3.6 DefaultArchiveService 扩展

在 PR1/PR2 的基础上新增：

```java
public void onStoreWrite(StoreWriteEvent event) {
  if (!isEnabled() || !executionContext.active()) {
    return;
  }
  writeCollector.onStoreWrite(event);
}
```

注意：

- `executionContext.active()` 是防 pending/constant call 污染的第一道防线。
- 如果 archive enabled 但无 active context 发生 Store 写，可以按配置 `warnUnclassifiedStoreWrites` 打诊断计数；PR3 不建议 fail。

### 3.7 ArchiveService 接口扩展

在 PR3 加回：

```java
boolean shouldCollectStoreWrites();

void onStoreWrite(StoreWriteEvent event);

void abortCurrentTx();

void restartCurrentTx();
```

`shouldCollectStoreWrites()` 应返回 `archive enabled && executionContext.active()`。Store hook 必须先调用它，只有 true 才读取 before-value。PR1/PR2 先不加是为了控制改动面；PR3 加时同步更新 no-op 语义。

`restartCurrentTx()` 专门处理 `Manager.processTransaction` 内部的 `trace.checkNeedRetry()`：在第二次 `trace.init/exec/setResult` 前丢弃第一次 attempt 的 pending writes，然后用同一个 txNum 重新 begin accumulator。

### 3.8 TronStoreWithRevoking hook

当前：

```java
revokingDB.put(key, item.getData());
```

修改：

```java
byte[] after = item.getData();
if (archiveService != null && archiveService.shouldCollectStoreWrites()) {
  byte[] before = revokingDB.getUnchecked(key);
  archiveService.onStoreWrite(StoreWriteEvent.put(getDbName(), key, before, after));
}
revokingDB.put(key, after);
```

delete：

```java
if (archiveService != null && archiveService.shouldCollectStoreWrites()) {
  byte[] before = revokingDB.getUnchecked(key);
  archiveService.onStoreWrite(StoreWriteEvent.delete(getDbName(), key, before));
}
revokingDB.delete(key);
```

注入：

```java
@Autowired(required = false)
private ArchiveService archiveService;
```

如果 `archiveService == null`，保持原行为。这能降低测试或非 Spring 构造 Store 的风险。默认关闭时不要额外调用 `revokingDB.getUnchecked(key)`。

### 3.9 ContractStore 修正

当前 `ContractStore.put` 清 ABI 后直接 `revokingDB.put`。PR3 必须改为统一采集实际落盘值：

```java
if (item.getInstance().hasAbi()) {
  item = new ContractCapsule(item.getInstance().toBuilder().clearAbi().build());
}
super.put(key, item);
```

这会让：

- Archive 采集到清 ABI 后的 canonical contract value。
- 现有 revokingDB 行为仍由 `TronStoreWithRevoking.put` 执行。

风险：

- 如果 `ContractStore.put` 过去绕过 `super.put` 是为了绕过某个副作用，PR3 需要回归测试合约部署和 ABI store。
- `RepositoryImpl.commitContractCache` 已在 `ContractStore.put` 前处理 `AbiStore`，所以清 ABI 后走 `super.put` 不应丢 ABI。

### 3.10 不改 TransactionStore

`TransactionStore.put` 对 in-block tx 写入 block number：

```java
revokingDB.put(key, ByteArray.fromLong(item.getBlockNum()));
```

这是交易索引，不是 archive state domain。PR3 不应把 `trans` 纳入 `TxWriteSet`，也不需要修它走 `super.put`。后续如果要统一所有 Store hook，可以单独做索引写诊断。

### 3.11 PR3 测试

#### ArchiveWriteCollectorTest

纯 unit：

1. 同一 tx 同一 key 多次写，before 保留第一次，after 取最后一次。
2. `account` 映射到 `ACCOUNT`。
3. `code` 映射到 `CODE`。
4. `trans` 被 ignore。
5. 未注册 store 进入 diagnostic，不进入 write set。
6. delete 生成 tombstone write。

#### ArchiveStoreHookTest

可用轻量 Store 或 Spring `BaseMethodTest`：

1. archive disabled 时 Store put 不产生 write set。
2. archive disabled 时 Store put 不额外读取 before-value。
3. archive enabled 但无 active tx context 时 Store put 不产生 write set。
4. archive enabled + active tx context 时 `AccountStore.put` 产生 ACCOUNT write。
5. `ContractStore.put` 清 ABI 后产生 CONTRACT write，afterValue 不含 ABI。
6. `TransactionStore.put` 不产生 state write。

如果构造 `ContractCapsule` 成本高，可先单测 `ContractStore.put` 的 after bytes 经过 collector，但最终应补合约部署集成测试。

## 4. PR 4：CONTRACT_STORAGE semantic hook

### 4.1 改动文件

```text
chainbase/src/main/java/org/tron/core/archive/collector/SemanticStoreWrite.java
chainbase/src/main/java/org/tron/core/archive/collector/ArchiveWriteCollector.java
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
actuator/src/main/java/org/tron/core/vm/program/Storage.java
chainbase/src/main/java/org/tron/core/store/StorageRowStore.java
framework/src/test/java/org/tron/core/archive/collector/ArchiveContractStorageSemanticWriteTest.java
framework/src/test/java/org/tron/common/runtime/vm/ArchiveStorageHookTest.java
```

### 4.2 为什么 PR4 必须有 semantic hook

`Storage.compose` 的 physical row key：

```text
first 16 bytes of addrHash + last 16 bytes of key
```

并且：

```text
contractVersion == 1 时 key = sha3(key)
create2 时 addrHash = sha3(address || trxHash)
```

因此只看 `StorageRowStore.put(rowKey,row)` 无法稳定恢复：

```text
contract address
logical slot
contractVersion
create2 trxHash
```

而历史 RPC `eth_getStorageAt(address, slot, block)` 必须按 `(address, slot)` 读取。

### 4.3 SemanticStoreWrite

```java
public class SemanticStoreWrite {
  private final ArchiveDomain domain;
  private final byte[] address;
  private final byte[] logicalSlot;
  private final byte[] beforeValue;
  private final byte[] afterValue;
  private final byte[] physicalKey;
  private final int storageKeyVersion;
}
```

PR4 直接在 `Storage.commit` 发 `SemanticStoreWrite.contractStorage(...)`，避免先收 intent 再按 physical key 合并。

### 4.4 推荐实现：Storage.commit 直接 semantic write

`Storage` 不是 Spring bean，`RepositoryImpl` 也是手动构造：

```text
RepositoryImpl.createRoot(storeFactory) -> new RepositoryImpl(...)
RepositoryImpl.newRepositoryChild()     -> new RepositoryImpl(storeFactory, this)
```

因此不建议在 `RepositoryImpl` 上加 `@Autowired ArchiveService`。推荐通过 `StorageRowStore` 暴露 ArchiveService：

```java
@Autowired(required = false)
private ArchiveService archiveService;

public ArchiveService getArchiveService() {
  return archiveService;
}
```

`Storage.commit()` 通过 `store.getArchiveService()` 获取。这样不改 `RepositoryImpl` 构造路径。

备选方案是给 `Storage` 增加 setter，并在 `RepositoryImpl.getStorage(address)` 创建后设置：

```java
storage = new Storage(address, getStorageRowStore());
storage.setArchiveService(storageRowStore.getArchiveService());
```

但首选仍是让 `Storage.commit()` 从 `StorageRowStore` 获取，改动更集中。

### 4.5 Storage.commit 采集逻辑

当前：

```java
if (new DataWord(row.getValue()).isZero()) {
  this.store.delete(row.getRowKey());
} else {
  this.store.put(row.getRowKey(), row);
}
```

修改为：

```java
if (row.isDirty()) {
  byte[] physicalKey = row.getRowKey();
  StorageRowCapsule oldRow = store.getUnchecked(physicalKey);
  byte[] before = normalizeStorageValue(oldRow == null ? null : oldRow.getValue());
  byte[] rawAfter = row.getValue();
  byte[] after = normalizeStorageValue(rawAfter);
  ArchiveService archiveService = store.getArchiveService();

  if (archiveService != null && archiveService.shouldCollectStoreWrites()) {
    archiveService.onSemanticWrite(SemanticStoreWrite.contractStorage(
        address, rowKey.getData(), before, after, physicalKey,
        storageKeyVersion(contractVersion)));
  }

  if (after == null) {
    this.store.delete(physicalKey);
  } else {
    this.store.put(physicalKey, row);
  }
}
```

注意：

- `rowKey` 是 `rowCache` 的 map key，类型 `DataWord`，代表 logical slot。
- `physicalKey` 是 `row.getRowKey()`。
- `before` 应在 delete/put 前读取。
- 读取 before 必须用 `getUnchecked`，不要用 `StorageRowStore.get`，因为当前 `get` 对 missing row 可能 NPE。

### 4.6 StorageRowStore.get 风险

`StorageRowStore.get` 当前：

```java
StorageRowCapsule row = getUnchecked(key);
row.setRowKey(key);
return row;
```

如果 missing 返回 null，这里可能 NPE。PR4 的新增代码应绕开它，使用 `getUnchecked` 读取 before。现有 `Storage.getValue` 也依赖这个方法，但 PR4 不建议顺手改这个行为，避免扩大范围；如果现有路径测试触发 NPE，单独提交修复：

```java
if (row != null) {
  row.setRowKey(key);
}
return row;
```

这属于独立 bugfix，不混进 WriteCollector PR。

### 4.7 ArchiveService 接口扩展

```java
void onSemanticWrite(SemanticStoreWrite write);
```

DefaultArchiveService：

```java
if (!isEnabled() || !executionContext.active()) {
  return;
}
writeCollector.onSemanticWrite(write);
```

Collector 输出：

```text
domain = CONTRACT_STORAGE
canonicalKey = address || logicalSlot
rawKey = physicalKey
beforeValue = beforeValue
afterValue = null tombstone or nonzero32
```

P0 storage after-value 建议在 semantic event 中直接归一：zero value 变成 `afterValue=null` tombstone。这样 `absent -> zero` 会在 collector 中成为 no-op，`nonzero -> zero` 会成为 delete，后续 TemporalStore/Root 不需要再保存 zero slot。

### 4.8 避免 raw storage-row 重复采集

PR3 Store hook 会看到 `storage-row` raw put/delete。PR4 开启 semantic storage 后，Registry 对 `storage-row` 应：

```text
semanticOnly = true
raw storage-row writes ignored as state writes
```

否则同一个 storage 写会产生两条：

```text
CONTRACT_STORAGE logical key
CONTRACT_STORAGE physical key
```

这是错误的。

### 4.9 Parent repository 场景

`RepositoryImpl.commitStorageCache`：

```java
if (deposit != null) {
  deposit.putStorage(address, storage);
} else {
  storage.commit();
}
```

只有 root repository `deposit == null` 时真正落盘。PR4 hook 应只在 `Storage.commit()` 触发，避免 child repository 中间态污染 write set。

这点很关键：不要在 `RepositoryImpl.putStorageValue` 直接输出最终 DomainWrite，否则 revert/child commit 会提前采集。

### 4.10 PR4 测试

#### ArchiveContractStorageSemanticWriteTest

纯 unit：

1. `onSemanticWrite(SemanticStoreWrite.contractStorage(...))` 输出 `CONTRACT_STORAGE`。
2. canonical key = `address || slot`。
3. 同一 tx 同 slot 多次写，before 保留第一次，after 取最后一次。
4. raw `storage-row` Store write 被 ignore，不重复采集。

#### ArchiveStorageHookTest

基于现有 VM/Repository 测试：

1. 合约 storage 写非零，collector 收到 logical slot。
2. storage 写零，collector 收到 tombstone/null after。
3. child repository 写入但未 root commit，不产生 final write。
4. create2 场景至少验证 logical slot 不依赖 physical key；contractVersion 场景验证 `storageKeyVersion_u8` suffix。

如果 create2 测试成本高，可先做单元测试覆盖 `Storage.generateAddrHash` 后仍输出同一 logical slot，集成测试放 P1。

## 5. PR3/PR4 与 PR5 的交付边界

PR3/PR4 完成后应该能得到：

```text
BlockWriteSet
  TxWriteSet(USER_TX txNum=...)
    ACCOUNT / CONTRACT / CODE / DYNAMIC_PROPERTIES / CONTRACT_STORAGE writes
  TxWriteSet(BLOCK_FINALIZE txNum=...)
    block finalize writes
```

但这些 write set 只在内存中可见或测试可见，不保证重启后保留。

PR5 才负责：

```text
single physical archive DB 中的 latest/history/changeset logical tables
GetAsOf
unwind from persisted changeset
progress meta
```

## 6. 代码审查清单

PR3：

- `ArchiveService.onStoreWrite(StoreWriteEvent)` 只有 archive enabled + active tx context 时采集。
- `TronStoreWithRevoking.put/delete` 读取 before 在写入前。
- `ContractStore.put` 走 `super.put`，且 afterValue 是清 ABI 后 value。
- `TransactionStore` 不被纳入 state domain。
- 未注册 Store 有 diagnostic，不静默丢失。
- 同 tx 同 key 多写压缩正确。

PR4：

- `Storage` 通过 setter 接收 `ArchiveService`，不直接成为 Spring bean。
- `Storage.commit()` 才发 semantic write，child repository 中间态不采集。
- `onSemanticWrite` 使用 logical slot，不使用 physical row key 做 canonical key。
- raw `storage-row` hook 不重复生成 state write。
- zero storage 语义在 write set 中可区分。

## 7. 建议执行命令

定向测试：

```bash
./gradlew :framework:test --tests 'org.tron.core.archive.ArchiveWriteCollectorTest'
./gradlew :framework:test --tests 'org.tron.core.archive.ArchiveStoreHookTest'
./gradlew :framework:test --tests 'org.tron.core.archive.collector.ArchiveContractStorageSemanticWriteTest'
./gradlew :framework:test --tests 'org.tron.common.runtime.vm.ArchiveStorageHookTest'
```

回归：

```bash
./gradlew :framework:test --tests 'org.tron.common.runtime.vm.RepositoryTest'
./gradlew :framework:test --tests 'org.tron.core.db.AccountStoreTest'
./gradlew :framework:test --tests 'org.tron.core.db.ManagerTest'
./gradlew lint
```

不要添加任何 `t.Skip` 类等价跳过；java-tron 是 JUnit，也不要用 `@Ignore` 绕过失败。
