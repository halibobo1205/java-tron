# 模块 03 ArchiveWriteCollector：java-tron 源码对照

日期：2026-06-01

> 2026-06-03 更新：本文是旧 `a79693e450` 源码对照。当前实现请改看 [模块 03 ArchiveWriteCollector：4e80 java-tron 源码对照细化](./20260603-java-tron-module-03-write-collector-4e80-source-deep-dive.md)。旧行号和旧配置模型不可直接用于编码。

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

关联设计：[java-tron Archive 模块 03：ArchiveWriteCollector 细化设计](./20260521-java-tron-archive-module-03-write-collector.md)

Erigon 对照：[模块 03 ArchiveWriteCollector：Erigon 源码对照深挖](./20260526-java-tron-module-03-write-collector-erigon-source-deep-dive.md)

逐文件实现清单：[java-tron Archive 模块 03：ArchiveWriteCollector 逐文件 Patch 清单](./20260602-java-tron-archive-module-03-write-collector-patch-checklist.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 旧文档原始基线：`a79693e450`，当前 4e80 实现请看页头链接。

## 1. 结论

java-tron 的状态写不是集中从 `RepositoryImpl` 出口落盘。源码里至少有三类写入路径：

1. 普通 actuator 直接写 `AccountStore` 等 Store。
2. TVM 通过 `RepositoryImpl` 缓存后 commit 到 Store。
3. block finalize / consensus / dynamic properties 在交易循环外写 Store。

因此 `ArchiveWriteCollector` 不能只 hook TVM repository，也不能只依赖现有 account state callback。最稳妥的 P0 方案是：

```text
Store-level hook 捕获所有 raw put/delete
+ semantic hook 补足 storage/contract 等需要逻辑 key 的写入
+ tx context 绑定当前 txNum/block/phase
+ before-value 在第一次写 key 时读取 current state
+ logical tx 结束时输出 TxWriteSet
```

## 2. 当前基础 Store 写入口

多数 Store 继承 `TronStoreWithRevoking`。

关键源码：

| 位置 | 作用 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java:40` | Store 基类 |
| `TronStoreWithRevoking.java:88-93` | `put(byte[] key, T item)` |
| `TronStoreWithRevoking.java:97-98` | `delete(byte[] key)` |
| `TronStoreWithRevoking.java:107-115` | `getUnchecked(byte[] key)` |
| `TronStoreWithRevoking.java:118-119` | `getFromRoot(byte[] key)` |
| `TronStoreWithRevoking.java:135-136` | `has(byte[] key)` |
| `TronStoreWithRevoking.java:184-185` | `iterator()` |

这说明一个低层 hook 点很清晰：

```text
TronStoreWithRevoking.put/delete
```

优点：

- 覆盖绝大多数 Store 写。
- 不需要改所有 actuator。
- 能在写入前读取 before value。

缺点：

- 只知道 `dbName/rawKey/rawValue`，不知道业务语义。
- `CONTRACT_STORAGE` 物理 row key 不一定能还原 logical slot。
- 某些 Store 可能绕过 `super.put`，例如 `ContractStore.put` 内部直接 `revokingDB.put`，hook 位置要特别处理。

## 3. 普通交易写路径

### 3.1 TransferActuator 直接写 Store

关键源码：

| 位置 | 作用 |
| --- | --- |
| `actuator/src/main/java/org/tron/core/actuator/TransferActuator.java:33` | `execute` |
| `TransferActuator.java:55` | 新账户 `accountStore.put(toAddress, toAccount)` |
| `TransferActuator.java:60` | `adjustBalance(accountStore, ownerAddress, ...)` |
| `TransferActuator.java:66` | `adjustBalance(accountStore, toAddress, amount)` |

这证明非 TVM 交易会直接写 `AccountStore`。如果 WriteCollector 只包 `RepositoryImpl.commit`，普通转账会漏写。

P0 规则：

```text
所有 Store put/delete 必须能被捕获。
RepositoryImpl hook 只能作为语义增强，不能作为唯一采集点。
```

### 3.2 TransactionTrace 执行和 finalization

关键源码：

| 位置 | 作用 |
| --- | --- |
| `framework/src/main/java/org/tron/core/db/Manager.java:1492` | `processTransaction` |
| `chainbase/src/main/java/org/tron/core/db/TransactionTrace.java:189` | `runtime.execute(transactionContext)` |
| `TransactionTrace.java:213` | `finalization()` |
| `TransactionTrace.java:327` | `checkNeedRetry` |
| `TransactionTrace.java:354` | `setResult` |

WriteCollector 应只记录最终被 canonical transaction commit 的写入。对于 VM 内部失败、重试或 revert，需要以 java-tron 最终 Store 写为准，而不是以执行过程中的临时修改为准。

建议原则：

- hook 点在 revoking store 真实写入处。
- 以 `processTransaction` 返回成功/失败后当前 session 中实际写入为事实。
- 如果 transaction 被异常回滚，丢弃该 txNum 的 pending write set。

## 4. TVM Repository 写路径

关键源码：

| 位置 | 作用 |
| --- | --- |
| `actuator/src/main/java/org/tron/core/vm/repository/Repository.java:10` | Repository 接口 |
| `Repository.java:26` | `getAccount` |
| `Repository.java:62` | `updateAccount` |
| `Repository.java:82` | `saveCode` |
| `Repository.java:84` | `getCode` |
| `Repository.java:86` | `putStorageValue` |
| `Repository.java:88` | `getStorageValue` |
| `Repository.java:90` | `getStorage` |
| `Repository.java:96` | `newRepositoryChild` |
| `Repository.java:100` | `commit` |
| `actuator/src/main/java/org/tron/core/vm/repository/RepositoryImpl.java:82` | `RepositoryImpl` |
| `RepositoryImpl.java:180` | `newRepositoryChild` |
| `RepositoryImpl.java:625` | `saveCode` |
| `RepositoryImpl.java:660` | `putStorageValue` |
| `RepositoryImpl.java:753` | `commit` |
| `RepositoryImpl.java:948 / 954` | `commitAccountCache` 写 `AccountStore` |
| `RepositoryImpl.java:960 / 966` | `commitCodeCache` 写 `CodeStore` |
| `RepositoryImpl.java:1001 / 1008` | `commitStorageCache` 调 `Storage.commit()` |

Repository 层适合补充语义：

- `saveCode(address, code)` 可以明确 CODE domain key 是 contract address。
- `putStorageValue(address, key, value)` 可以明确 storage logical slot。
- `commitAccountCache` 可以确认账户缓存的最终写入批次。

但 Repository 层不是全覆盖，因此建议：

```text
Store hook: 负责完整性
Repository hook: 负责语义增强
```

## 5. Storage 写路径与逻辑 key

关键源码：

| 位置 | 作用 |
| --- | --- |
| `actuator/src/main/java/org/tron/core/vm/program/Storage.java:15` | `Storage` |
| `Storage.java:46` | `compose(key, addrHash)` |
| `Storage.java:77` | 读取 row |
| `Storage.java:86` | `put(DataWord key, DataWord value)` |
| `Storage.java:96` | `commit()` |
| `Storage.java:100` | zero value 删除 row |
| `Storage.java:102` | 非 zero 写入 row |

Storage 的 raw row key 是内部物理编码，Archive 必须能得到：

```text
contract address
logical slot key
physical row key
after value
```

建议加 semantic hook：

```text
archiveCollector.onStorageWrite(
    txContext,
    contractAddress,
    logicalSlot,
    physicalRowKey,
    beforeValue,
    afterValue
)
```

如果只在 `StorageRowStore.put/delete` hook：

- 可以知道 `physicalRowKey`。
- 可以读到 row bytes。
- 但无法稳定反推原始 slot，尤其存在 `contractVersion == 1` 的 key hash 逻辑。

因此 `Storage.put` 或 `RepositoryImpl.putStorageValue` 是 storage semantic hook 的必要补点。

## 6. AccountStore 现有回调不能替代 WriteCollector

关键源码：

| 位置 | 作用 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/store/AccountStore.java:68` | 重写 `put` |
| `AccountStore.java:73` | 新账户记录余额 |
| `AccountStore.java:80` | 旧账户记录余额 diff |
| `AccountStore.java:88` | `accountStateCallBackUtils.accountCallBack(key, item)` |
| `AccountStore.java:92` | 重写 `delete` |
| `AccountStore.java:136` | `recordBalance` |
| `chainbase/src/main/java/org/tron/core/db/accountstate/AccountStateCallBackUtils.java:13` | `accountCallBack` |

现有回调问题：

- 只覆盖 AccountStore。
- 记录的是 account state root 所需的账户实体，不是通用 domain write set。
- balance history 只关心余额 diff。
- 不记录 code/storage/contract/dynamic properties。

因此可复用其“Store 写时回调”的思想，但不能复用为 ArchiveWriteCollector。

## 7. block finalize 写路径

关键源码：

| 位置 | 作用 |
| --- | --- |
| `framework/src/main/java/org/tron/core/db/Manager.java:1891` | `payReward(block)` |
| `Manager.java:1899` | `consensus.applyBlock(block)` |
| `Manager.java:1910` | `updateDynamicProperties(block)` |
| `chainbase/src/main/java/org/tron/core/store/DynamicPropertiesStore.java:2190` | latest timestamp 写入 |
| `DynamicPropertiesStore.java:2198` | latest block number 写入 |
| `DynamicPropertiesStore.java:2206` | latest block hash 写入 |

这些写入不属于某一笔用户交易，但属于 canonical 状态推进。WriteCollector 必须在 `ArchiveTxNumIndex` 绑定系统 phase 后采集：

```text
txNum = allocate(BLOCK_FINALIZE)
context.bind(txNum)
payReward/consensus/updateDynamicProperties
context.flush()
```

如果不绑定 context，Store hook 会看到写入但不知道归属哪个 txNum。

## 8. TxContext 生命周期

建议引入线程内或显式参数传递的 context：

```text
ArchiveTxContext:
  enabled
  blockNum
  blockHash
  txNum
  txIndex
  phase
  txId
  writeSetBuilder
```

生命周期：

```text
beginBlock(block)
  beginTx(USER_TX, txIndex, txId)
    processTransaction
    collect Store writes
  endTx()

  beginTx(BLOCK_FINALIZE)
    payReward
    consensus.applyBlock
    updateDynamicProperties
  endTx()
endBlock()
```

约束：

- 没有 active tx context 时，Store hook 默认不采集，或只记录 diagnostics。
- 如果 archive 开启且执行路径发生 Store 写但无 context，应报警。
- context 必须在 finally 中 clear，避免污染 pending/constant call。

## 9. before-value 采集规则

Erigon V3 的历史模型记录 before value。java-tron Store hook 可以在写入前读取 current value：

```text
before = store.getUnchecked(rawKey)
after = item.getData()
```

同一 tx 内同一 domain key 多次写：

```text
firstBefore = value before tx starts
lastAfter = final value after tx
```

WriteCollector 输出应压缩为一条：

```text
TxWrite(domain, key, firstBefore, lastAfter)
```

规则：

- 第一次写 key 时保存 before。
- 后续写同一 key 只更新 after。
- 如果 `before == after`，可选择不输出，但建议先保留 diagnostic，再由 TemporalStore 去重。
- delete 的 after 用 tombstone 表示，不使用 `null` 混淆 missing 和 zero bytes。

## 10. put/delete 语义

### 10.1 put

对普通 `put`：

```text
before = current store value
after = canonical encoded value
```

### 10.2 delete

对 `delete`：

```text
before = current store value
after = tombstone
```

对 storage zero：

`Storage.commit` 中 zero value 会调用 `store.delete(rowKey)`。Archive 的 logical domain 建议仍表示为：

```text
CONTRACT_STORAGE(address, slot): after = zero/tombstone
```

需要在 Reader 层统一：

- storage missing 等价于 zero。
- account/code/contract missing 是不存在。

## 11. 失败交易和回滚

java-tron 使用 revoking DB session 管理状态回滚。相关源码：

| 位置 | 作用 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/db2/core/SnapshotManager.java:115` | `buildSession` |
| `SnapshotManager.java:160` | `advance` |
| `SnapshotManager.java:165` | `retreat` |
| `SnapshotManager.java:170` | `merge` |

ArchiveWriteCollector 不能在 Store hook 时立即持久化 history；应先写入内存 pending set：

```text
Store hook -> current TxWriteSetBuilder
tx success -> hand off to ArchiveTemporalStore pending block batch
block success/merge -> persist
tx/block fail -> discard pending write set
```

这样可以跟随 java-tron 的 session 结果。

## 12. 与 ArchiveTemporalStore 的交付格式

建议输出：

```java
record TxWriteSet(
    long blockNum,
    byte[] blockHash,
    long txNum,
    int txIndex,
    ArchivePhase phase,
    byte[] txId,
    List<DomainWrite> writes
) {}

record DomainWrite(
    ArchiveDomain domain,
    byte[] canonicalKey,
    byte[] beforeValue,
    byte[] afterValue,
    byte[] rawKey,
    String sourceDbName
) {}
```

`rawKey/sourceDbName` 主要用于诊断和迁移，不应进入 root。

## 13. 接入策略

### 13.1 P0 Store hook

改造 `TronStoreWithRevoking.put/delete`：

```text
if archiveCollector.enabled():
    archiveCollector.onBeforeStoreWrite(dbName, key, before, after/delete)
revokingDB.put/delete(...)
```

需要让 `TronStoreWithRevoking` 暴露 `dbName`。如果当前基类没有字段，可从构造或 revoking DB 包装层补充。

### 13.2 P0 特殊 Store 补点

`ContractStore.put` 直接 `revokingDB.put`，应确保 hook 捕获最终落盘值。选择：

1. 改 `ContractStore.put` 最后走统一 hook。
2. 在 `revokingDB.put` 更底层 hook。
3. 在 `ContractStore.put` 内显式调用 ArchiveWriteCollector。

推荐第一种，保持 Store-level 语义统一。

### 13.3 P0 Storage semantic hook

在 `RepositoryImpl.putStorageValue` 或 `Storage.put/commit` 加逻辑 key hook。推荐：

- `RepositoryImpl.putStorageValue` 记录 logical slot 写意图。
- `Storage.commit` 绑定 physical row key 和最终 after。

如果只选一个，优先 `Storage.commit`，因为它最接近最终落盘。

## 14. 测试建议

### 14.1 普通转账

断言：

- `TransferActuator` 触发 `ACCOUNT` domain writes。
- owner/to/fee 相关账户 before/after 正确。
- 同一交易内多次写账户时只输出一条最终 write。

### 14.2 合约部署

断言：

- `ACCOUNT` 创建合约账户。
- `CONTRACT` 写合约元信息。
- `CODE` 写 bytecode。
- txNum 坐标一致。

### 14.3 Storage 写和删除

断言：

- `putStorageValue(address, slot, value)` 输出 logical key。
- zero value 删除输出 tombstone。
- `eth_getStorageAt` 可通过 ArchiveStateReader 读取历史 slot。

### 14.4 失败交易

构造失败或 revert：

- 如果 java-tron 最终没有落盘写，Archive 不应输出 write。
- 如果费用扣除等 finalization 写落盘，应输出对应 `ACCOUNT` write。

### 14.5 block finalize

空区块也要采集 `BLOCK_FINALIZE` 写：

- reward 账户变化。
- dynamic properties 写。
- latest header cursor 是否按 policy 进入 history/root。

## 15. 关键风险

1. 只 hook Repository 会漏掉 actuator 直写 Store。
2. 只 hook Store 会丢失 storage logical key。
3. Store hook 立即持久化会与 revoking session 回滚不一致。
4. 同一 tx 多次写 key 不压缩，会产生错误 before-value 链。
5. archive context 泄漏到 constant call/pending transaction，会污染 canonical history。
6. 未注册 Store 写入如果静默忽略，会导致 root 不完整但难以发现。
