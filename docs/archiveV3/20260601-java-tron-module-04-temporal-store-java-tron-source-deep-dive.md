# 模块 04 ArchiveTemporalStore：java-tron 源码对照

日期：2026-06-01

> 2026-06-03 更新：本文是旧 `a79693e450` 源码对照。当前实现请改看 [模块 04 ArchiveTemporalStore：4e80 java-tron 源码对照细化](./20260603-java-tron-module-04-temporal-store-4e80-source-deep-dive.md)。旧行号和旧配置模型不可直接用于编码。

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

关联设计：[java-tron Archive 模块 04：ArchiveTemporalStore 细化设计](./20260521-java-tron-archive-module-04-temporal-store.md)

Erigon 对照：[模块 04 ArchiveTemporalStore：Erigon 源码对照深挖](./20260527-java-tron-module-04-temporal-store-erigon-source-deep-dive.md)

逐文件实现清单：[java-tron Archive 模块 04：ArchiveTemporalStore 逐文件 Patch 清单](./20260602-java-tron-archive-module-04-temporal-store-patch-checklist.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 旧文档原始基线：`a79693e450`，当前 4e80 实现请看页头链接。

## 1. 结论

java-tron 当前有两套容易被误认为“历史状态”的机制：

1. `SnapshotManager` / revoking DB：服务区块执行回滚，不保留长期历史查询。
2. `BalanceTraceStore` / `AccountTraceStore`：只支持 block-level 余额历史。

它们都不能替代 Erigon V3 式 temporal store。

`ArchiveTemporalStore` 需要作为新的 sidecar 状态层保存：

```text
latest(domain, key) -> current value
history(domain, key, txNum) -> before value
changeset(txNum) -> changed domain keys
```

并提供：

```text
GetAsOf(domain, key, asOfTxNum)
ChangedKeys(domain, fromTxNum, toTxNum)
Unwind(toBlock/toTxNum)
```

## 2. java-tron revoking DB 的边界

关键源码：

| 位置 | 作用 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java:40` | Store 基类 |
| `TronStoreWithRevoking.java:88-93` | `put` |
| `TronStoreWithRevoking.java:97-98` | `delete` |
| `TronStoreWithRevoking.java:107-115` | `getUnchecked` |
| `TronStoreWithRevoking.java:118-119` | `getFromRoot` |
| `chainbase/src/main/java/org/tron/core/db2/core/SnapshotManager.java:48` | `SnapshotManager` |
| `SnapshotManager.java:115 / 119-138` | `buildSession` |
| `SnapshotManager.java:160` | `advance` |
| `SnapshotManager.java:165` | `retreat` |
| `SnapshotManager.java:170` | `merge` |

revoking DB 的目标是：

```text
执行 block/transaction 时可回滚
block 成功后 merge
fork/reorg 时回退 recent state
```

它不是 archive temporal store：

- 不保留全历史 before-values。
- 不提供 `GetAsOf(txNum)`。
- 不按 domain 暴露 changed-key index。
- snapshot 深度通常受配置和内存/磁盘策略限制。

因此 ArchiveTemporalStore 应与 revoking DB 并行，而不是试图从 revoking DB 读取任意历史。

## 3. 现有 balance history 的边界

关键源码：

| 位置 | 作用 |
| --- | --- |
| `framework/src/main/resources/config.conf:80` | `storage.balance.history.lookup = false` |
| `common/src/main/java/org/tron/core/Constant.java:370` | 配置 key `storage.balance.history.lookup` |
| `common/src/main/java/org/tron/common/parameter/CommonParameter.java:626-627` | CLI `--history-balance-lookup` 和 runtime 字段 |
| `framework/src/main/java/org/tron/core/config/args/Args.java:1130` | 从 config 读取 `historyBalanceLookup` |
| `chainbase/src/main/java/org/tron/core/store/AccountStore.java:68-88` | `AccountStore.put` 只记录账户余额变化 |
| `AccountStore.java:80` | 旧账户记录余额 diff |
| `AccountStore.java:96` | 删除账户记录负余额 |
| `chainbase/src/main/java/org/tron/core/store/BalanceTraceStore.java:67` | `resetCurrentTransactionTrace` |
| `BalanceTraceStore.java:99` | `initCurrentTransactionBalanceTrace` |
| `BalanceTraceStore.java:128-130` | `putBlockBalanceTrace` |
| `chainbase/src/main/java/org/tron/core/store/AccountTraceStore.java:32` | `recordBalanceWithBlock` |
| `AccountTraceStore.java:39` | `getPrevBalance` |
| `framework/src/main/java/org/tron/core/Wallet.java:4355` | `getAccountBalance` |

这套机制只能回答：

```text
某地址在某 block 附近的余额
```

不能回答：

```text
某地址完整 Account 在 tx K 前是什么
某合约 storage slot 在 tx K 后是什么
某合约 code 在历史 block 是什么
某 dynamic property 在 txNum 是什么
```

ArchiveTemporalStore 可以兼容旧接口，但不能建立在旧接口之上。

## 4. 数据模型建议

### 4.1 Latest 表

```text
archive_latest
key   = domainId || canonicalKey
value = current canonical value or tombstone
```

用途：

- 快速读取 latest。
- 写入 history 前读取 before。
- 崩溃恢复时校验 current Store 与 archive latest 是否一致。

注意：

- latest 可以来自 current Store，但维护 sidecar latest 有利于统一 domain canonical encoding。
- P0 可以不对所有 domain 存 latest，而是在 write 时从 Store 取 before；但 `GetAsOf(latest)` 仍需要快速路径。

### 4.2 History 表

```text
archive_history
key   = domainId || canonicalKey || txNum
value = beforeValue
```

语义：

```text
在 txNum 这次变更发生前，domain/key 的值是 beforeValue。
```

读取 `GetAsOf(key, asOfTxNum)`：

1. 找到 `txNum >= asOfTxNum` 的第一条 history。
2. 如果存在，返回该条 beforeValue。
3. 如果不存在，返回 latest。

这是 Erigon V3 before-value 模型的核心。

### 4.3 Changed-key 表

```text
archive_changeset
key   = txNum || domainId || canonicalKey
value = compact metadata
```

用途：

- 按 txNum 回放 root。
- 按 block/txNum unwind。
- debug 输出某 tx 修改了哪些 key。
- freeze/snapshot 构建 segment。

### 4.4 Block range 表

由 `ArchiveTxNumIndex` 维护：

```text
blockNum -> [firstTxNum, lastTxNum]
```

TemporalStore 用它做：

- block-level unwind。
- block-level history pruning/freeze。
- `BLOCK_END` 查询。

## 5. 写入流程

输入来自 `ArchiveWriteCollector`：

```text
BlockWriteSet {
  blockNum
  firstTxNum
  lastTxNum
  List<TxWriteSet>
}
```

对每条 `DomainWrite(domain, key, before, after)`：

```text
if before == after:
    optionally skip, but count diagnostic
else:
    history.put(domain, key, txNum, before)
    changeset.put(txNum, domain, key)
    latest.put(domain, key, after)
```

同一 tx 内重复写已由 WriteCollector 压缩。跨 tx 重复写必须保留每次 before-value，因为这是交易级历史的基础。

## 6. 读取流程

### 6.1 Latest

```text
GetAsOf(domain, key, nextTxNum) -> current value
```

P0 可以直接从 `archive_latest` 读；如果 latest 表未启用，可以从 java-tron Store 读，但要经过 DomainRegistry 的 canonical value codec。

### 6.2 历史

```text
GetAsOf(domain, key, asOfTxNum)
```

查找逻辑：

```text
nextChange = first history entry for (domain, key) where changeTxNum >= asOfTxNum
if nextChange exists:
    return nextChange.beforeValue
else:
    return latest(domain, key)
```

例子：

```text
txNum 10: A 100 -> 70
txNum 11: A 70  -> 50
latest: A 50

GetAsOf(A, 10) = 100
GetAsOf(A, 11) = 70
GetAsOf(A, 12) = 50
```

### 6.3 Tombstone / zero

不同 domain 的 missing 语义不同：

| Domain | tombstone 读取 |
| --- | --- |
| `ACCOUNT` | account 不存在 |
| `CONTRACT` | contract 不存在 |
| `CODE` | empty code |
| `CONTRACT_STORAGE` | zero word |
| `DYNAMIC_PROPERTIES` | property 不存在或默认值，由 codec 决定 |

TemporalStore 只保存 tombstone；具体对外值由 ArchiveStateReader 解释。

## 7. Reorg / unwind

java-tron 当前 reorg 会通过 revoking DB 回退 current state，但 archive sidecar 必须显式回退。

需要支持：

```text
unwindToBlock(blockNum)
unwindToTxNum(txNum)
```

流程：

1. 从 `ArchiveTxNumIndex` 找到要删除的 txNum range。
2. 倒序扫描 `archive_changeset`。
3. 对每个 `(txNum, domain, key)`：
   - 找到该 txNum 的 beforeValue。
   - 把 `archive_latest(domain,key)` 恢复为 beforeValue。
   - 删除该 txNum 的 history/change 记录。
4. 删除对应 txNum/block 索引。

注意：

- 倒序恢复必须按 txNum 从大到小。
- 如果一个 key 在 unwind range 内多次变更，只最终恢复到 range 前的值。
- ArchiveTemporalStore 和 java-tron canonical state unwind 必须在同一个高层流程中完成。

## 8. 与 Store 后端的关系

java-tron 当前 Store 通过 `TronStoreWithRevoking` 包装底层 DB。ArchiveTemporalStore 可以有两种实现路线：

### 8.1 复用现有 DB 抽象

优点：

- 和 java-tron 配置、生命周期、关闭流程一致。
- 容易接入 `ChainBaseManager`。

缺点：

- history/range scan 需求和普通 key-value Store 不同。
- 需要谨慎处理 revoking 语义，archive history 不应被普通 snapshot 短期清理。

### 8.2 独立 sidecar DB

优点：

- 可以按 temporal access pattern 设计 key layout。
- 更容易后续做 hot/cold segment。
- 不污染现有 store 命名空间。

缺点：

- 需要独立事务/恢复流程。
- 与 canonical DB 原子提交更复杂。

建议 P0：

```text
独立 sidecar namespace，但纳入 java-tron 节点生命周期；
先保证 block apply 成功后原子可见，后续再优化冷热分层。
```

## 9. Hot / cold 分层

Erigon V3 的方向是 hot recent state + immutable history segment。java-tron P0 可以先只做 hot DB，但 key schema 应为后续 freeze 留边界。

建议分层：

```text
Hot:
  latest
  recent history
  recent changeset

Cold:
  immutable txNum range segments
  index for GetAsOf
  changed-key compressed segment
```

freeze 条件：

- block 已 solidified。
- txNum range 不会 reorg。
- segment 构建完成并校验 root/checksum。

java-tron 有 solid block 概念，可从 `DynamicPropertiesStore` 读取 latest solidified block number：

| 位置 | 作用 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/ChainBaseManager.java:349` | 使用 `getLatestSolidifiedBlockNum()` |

## 10. 与旧 balance history 兼容

建议保留旧接口行为：

```text
storage.balance.history.lookup = false/true
```

但当 ArchiveTemporalStore 启用时，可以提供新后端：

```text
getAccountBalance(address, blockIdentifier):
    if archive enabled:
        asOfTxNum = txIndex.resolve(BLOCK_END(block))
        account = temporal.GetAsOf(ACCOUNT, address, asOfTxNum)
        return account.balance
    else:
        existing AccountTraceStore path
```

这样旧 API 获得更完整历史能力，但不影响未开启 archive 的节点。

## 11. 崩溃恢复

需要持久化 apply 进度：

```text
archive_meta:
  appliedBlockNum
  appliedBlockHash
  nextTxNum
  schemaVersion
```

启动时校验：

1. `appliedBlockHash` 是否在 canonical chain。
2. `appliedBlockNum` 是否等于 current state 或可接受滞后。
3. 若 archive ahead of chain，执行 unwind。
4. 若 archive behind chain，进入 backfill/replay。

不要只依赖 `nextTxNum`，因为崩溃可能在部分 write set 持久化后发生。

## 12. API 建议

```java
interface ArchiveTemporalStore {
  Optional<byte[]> getAsOf(ArchiveDomain domain, byte[] key, long asOfTxNum);

  void applyBlock(BlockWriteSet blockWriteSet);

  void unwindToTxNum(long txNumExclusive);

  Iterator<ChangedKey> changedKeys(long fromTxNumInclusive, long toTxNumExclusive);

  ArchiveProgress progress();
}
```

约定：

- `asOfTxNum` 是 exclusive before-tx 坐标，由 `ArchiveTxNumIndex` 解析。
- `unwindToTxNum(X)` 之后 latest 表表示 `X` 之前状态。
- `applyBlock` 必须幂等或可检测重复 block。

## 13. 测试建议

### 13.1 before-value 链

同一账户连续三笔交易：

```text
tx10: 100 -> 90
tx11: 90  -> 70
tx12: 70  -> 65
```

断言：

- `GetAsOf(tx10) = 100`
- `GetAsOf(tx11) = 90`
- `GetAsOf(tx12) = 70`
- `GetAsOf(tx13) = 65`

### 13.2 同 tx 多写

同一交易内多次写同一 storage slot：

```text
before = 0
intermediate = 1
after = 2
```

TemporalStore 只应持久化：

```text
history(txNum) = before 0
latest = after 2
```

### 13.3 unwind

应用 block A/B/C 后 unwind 到 A：

- latest 恢复到 A 后状态。
- B/C 的 history/change 删除或标记不可见。
- txNum index 同步回退。

### 13.4 tombstone

测试：

- account create/delete/recreate。
- storage zero delete/rewrite。
- code missing 和 empty code。

## 14. 关键风险

1. 把 revoking DB 当 archive history，会因为 merge/retreat 生命周期丢历史。
2. 只保存 block final delta，会无法回答区块内交易级状态。
3. 不保存 changed-key index，会导致 root rebuild 和 unwind 只能全库扫描。
4. tombstone 语义不统一，会把 storage zero、missing account、empty code 混淆。
5. archive sidecar 与 canonical DB 提交顺序不一致，会导致崩溃后状态错位。
6. P0 key schema 如果不预留 txNum range，会阻碍后续 immutable segment。
