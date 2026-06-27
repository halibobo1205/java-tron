# 模块 01 ArchiveTxNumIndex：java-tron 源码对照

日期：2026-06-01

> 2026-06-03 更新：本文是旧 `a79693e450` 源码对照，当前实现请改看 [模块 01 ArchiveTxNumIndex：4e80 java-tron 源码对照细化](./20260603-java-tron-module-01-txnum-index-4e80-source-deep-dive.md)。当前本地 java-tron 为 `4e80f8ffa9a2`，精确冲突标记扫描无命中，旧行号不可直接用于编码。

关联设计：[java-tron Archive 模块 01：ArchiveTxNumIndex 细化设计](./20260521-java-tron-archive-module-01-txnum-index.md)

Erigon 对照：[模块 01 ArchiveTxNumIndex：Erigon 源码对照深挖](./20260523-java-tron-module-01-txnum-index-erigon-source-deep-dive.md)

模块 01 逐文件 Patch 清单：[java-tron Archive 模块 01：ArchiveTxNumIndex 逐文件 Patch 清单](./20260602-java-tron-archive-module-01-txnum-index-patch-checklist.md)

逐文件实现清单：[java-tron Archive PR1/PR2 逐文件 Patch 清单](./20260602-java-tron-archive-pr1-pr2-patch-checklist.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

本轮复核基线：本地 java-tron `a79693e450`。

## 1. 结论

java-tron 当前没有 Erigon 式全局 `txNum`。现有坐标由三类数据拼出来：

1. block number / block hash：区块级坐标。
2. transaction id：交易对象坐标。
3. block 内交易列表顺序：执行时存在，但没有持久化为“交易级历史状态坐标”。

因此 `ArchiveTxNumIndex` 在 java-tron 的第一件事不是“复用某张现有索引表”，而是补一条新的 canonical logical transaction 时间线。

建议落地为 sidecar 索引：

```text
blockNum + txIndex + phase  -> txNum
txId                       -> txNum
txNum                      -> blockNum + txIndex + phase + txId?
blockNum                   -> [firstTxNum, lastTxNum]
```

其中 `phase` 必须覆盖普通交易之外的系统状态变化，否则区块奖励、动态参数、维护周期、latest block header 等 block finalize 写入会丢失。

## 2. java-tron 当前执行坐标

### 2.1 区块处理入口

关键源码：

| 位置 | 作用 |
| --- | --- |
| `framework/src/main/java/org/tron/core/db/Manager.java:1261-1267` | `pushBlock` 入口 |
| `framework/src/main/java/org/tron/core/db/Manager.java:1295-1301` | 校验 block `txTrieRoot` |
| `framework/src/main/java/org/tron/core/db/Manager.java:1824` | `processBlock` |
| `framework/src/main/java/org/tron/core/db/Manager.java:1858` | 遍历 `block.getTransactions()` |
| `framework/src/main/java/org/tron/core/db/Manager.java:1866` | 给交易设置 `blockNum` |
| `framework/src/main/java/org/tron/core/db/Manager.java:1871` | 调用 `processTransaction(transactionCapsule, block)` |
| `framework/src/main/java/org/tron/core/db/Manager.java:1891` | 普通交易后执行 `payReward(block)` |
| `framework/src/main/java/org/tron/core/db/Manager.java:1910` | 普通交易后执行 `updateDynamicProperties(block)` |

`processBlock` 是 archive txNum 分配的主切入点。源码的关键顺序是：

```text
initCurrentBlockBalanceTrace(block)
saveBlockEnergyUsage(0)
accountStateCallBack.preExecute(block)
for transaction in block.getTransactions():
    transaction.setBlockNum(blockNum)
    accountStateCallBack.preExeTrans()
    processTransaction(transaction, block)
    accountStateCallBack.exeTransFinish()
accountStateCallBack.executePushFinish()
payReward(block)
proposalController.processProposals() if maintenance time reached
consensus.applyBlock(block)
updateTransHashCache(block)
updateRecentBlock(block)
updateRecentTransaction(block)
updateDynamicProperties(block)
resetCurrentBlockTrace()
sectionBloomStore.initBlockSection/write
```

这个顺序说明：

- 普通交易的 canonical 顺序就是 `block.getTransactions()` 顺序。
- `TransactionCapsule.setBlockNum` 只保存区块号，不保存区块内 index。
- `accountStateCallBack.preExeTrans/exeTransFinish` 已经体现交易边界，但只服务现有 account state root 回调，不能直接作为 archive 索引。
- `payReward`、proposal maintenance、`consensus.applyBlock`、recent cache/dynamic properties 和 section bloom 写入发生在交易循环之后。它们共享 block finalize 执行区间，其中 dynamic properties、奖励、维护类写入是 archive state 关注对象，recent/bloom 这类索引写入后续由 registry 标记排除。

### 2.2 交易处理入口

关键源码：

| 位置 | 作用 |
| --- | --- |
| `framework/src/main/java/org/tron/core/db/Manager.java:1492` | `processTransaction` |
| `framework/src/main/java/org/tron/core/db/Manager.java:1562` | `TransactionStore.put(txId, trxCap)` |
| `framework/src/main/java/org/tron/core/db/Manager.java:1591` | `trxCap.setOrder(transactionInfo.getFee())` |
| `chainbase/src/main/java/org/tron/core/db/TransactionTrace.java:189` | TVM/actuator 执行 `runtime.execute(transactionContext)` |
| `chainbase/src/main/java/org/tron/core/db/TransactionTrace.java:213` | `finalization()` 处理费用、删除账户等 |

这里有一个容易误判的点：`TransactionCapsule.setOrder(transactionInfo.getFee())` 不是 block 内交易序号，而是交易排序/费用相关字段，不能作为 archive tx index。

`ArchiveTxNumIndex` 必须在 `processBlock` 的交易循环中显式维护 `txIndex`：

```text
long txIndex = 0;
for (TransactionCapsule transactionCapsule : block.getTransactions()) {
    beginLogicalTx(blockNum, txIndex, USER_TX, txId);
    processTransaction(transactionCapsule, block);
    endLogicalTx();
    txIndex++;
}
```

## 3. 当前持久化索引能提供什么

### 3.1 BlockStore / BlockIndexStore

关键源码：

| 位置 | 作用 |
| --- | --- |
| `framework/src/main/java/org/tron/core/db/Manager.java:1069` | `BlockStore.put(blockId, block)` |
| `framework/src/main/java/org/tron/core/db/Manager.java:1070` | `BlockIndexStore.put(blockId)` |
| `framework/src/main/java/org/tron/core/Wallet.java:696` | `getBlockByNum` |
| `framework/src/main/java/org/tron/core/Wallet.java:1808` | `getBlockById` |

这些表可以解析 `blockNum`、`blockHash` 和区块体，但不能直接回答：

```text
txId 在哪个 txNum？
block N 的第 K 笔交易后状态对应哪个 asOfTxNum？
block N finalize 之后的系统写对应哪个 txNum？
```

因此它们只能作为 `ArchiveTxNumIndex` 的输入来源，不能替代新索引。

### 3.2 TransactionStore

关键源码：

| 位置 | 作用 |
| --- | --- |
| `framework/src/main/java/org/tron/core/db/Manager.java:1571` | `TransactionStore.put(txId, trxCap)` |
| `framework/src/main/java/org/tron/core/Wallet.java:1855` | `getTransactionCapsuleById` |
| `chainbase/src/main/java/org/tron/core/capsule/TransactionCapsule.java:721` | `getTransactionId` |

`TransactionStore` 的主键是 `txId`，value 是交易本体。它适合做 `txId -> transaction`，但不保存：

- 该交易在 block 中的 index。
- 该交易对应的全局 `txNum`。
- `before` / `after` 状态点。

`ArchiveTxNumIndex` 需要新增 `txId -> txNum`，并且只对 canonical block 中成功进入链的交易建立映射。

### 3.3 TransactionRetStore / TransactionHistoryStore

关键源码：

| 位置 | 作用 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/store/TransactionRetStore.java:28` | DB 名 `transactionRetStore` |
| `chainbase/src/main/java/org/tron/core/store/TransactionHistoryStore.java:17` | DB 名 `transactionHistoryStore` |
| `framework/src/main/java/org/tron/core/db/Manager.java:1072` | 区块处理后按 `blockNum` 写入交易结果 |
| `framework/src/main/java/org/tron/core/db/Manager.java:2514-2519` | `getTransactionInfoByBlockNum` 读取 `TransactionRetStore` |
| `framework/src/main/java/org/tron/core/db/Manager.java:2531` | fallback 到 `TransactionHistoryStore` |
| `framework/src/main/java/org/tron/core/Wallet.java:1872-1888` | `getTransactionInfoById` |

`TransactionRetStore` 是区块级交易结果集合，不是 txNum 索引。它可以帮助 backfill：

```text
blockNum -> transaction results -> txId/result/order in block
```

但 archive 不应把它当成唯一事实来源，原因是：

- txNum 的事实顺序应以 block 中交易列表为准。
- 交易结果可能在历史迁移、ret store fallback、不同配置下有差异。
- 系统写不在交易结果列表中。

## 4. 系统状态变化必须进入同一时间线

Erigon 的 `txNum` 不只服务交易查询；它是所有 domain history 的统一时间坐标。java-tron 若只给普通交易分配 txNum，会出现以下问题：

```text
block N tx K 后查询       可以回答
block N 完成后查询        可能漏掉 reward / dynamic properties
block N+1 tx 0 前查询     与 block N 完成后状态不一致
```

java-tron 当前 `processBlock` 中交易循环之后的状态变化包括：

| 源码位置 | 事件 | archive phase 建议 |
| --- | --- | --- |
| `Manager.java:1891` | `payReward(block)` | `BLOCK_REWARD` 或合并到 `BLOCK_FINALIZE` |
| `Manager.java:1896` | `proposalController.processProposals()` | `MAINTENANCE` 或合并到 `BLOCK_FINALIZE` |
| `Manager.java:1899` | `consensus.applyBlock(block)` | `CONSENSUS_APPLY` 或合并到 `BLOCK_FINALIZE` |
| `Manager.java:1907-1910` | recent cache 与 `updateDynamicProperties(block)` | dynamic properties 属于 `BLOCK_FINALIZE`；recent cache 后续 registry 排除 |
| `Manager.java:1912` | `resetCurrentBlockTrace()` | 通常不进入执行状态 root |
| `Manager.java:1914-1917` | section bloom 初始化和写入 | index/cache 类写入，registry 排除 |

建议 `ArchiveTxNumIndex` 定义 logical tx：

```text
USER_TX(blockNum, txIndex, txId)
BLOCK_REWARD(blockNum)
CONSENSUS_APPLY(blockNum)
BLOCK_FINALIZE(blockNum)
```

第一阶段可以把 block 后置系统写合并为一个 `BLOCK_FINALIZE` txNum；如果后续要支持更精细 proof，再拆成多个 phase。

## 5. 建议索引表

### 5.1 `archive_txnum_by_block`

```text
key   = blockNum
value = firstTxNum, lastTxNumInclusive, txCount, finalizedPhaseCount
```

用途：

- `latest block` 到 `asOfTxNum`。
- block rewind 时快速定位需要删除的 txNum range。
- snapshot/freeze 时按 block range 切分。

### 5.2 `archive_txnum_by_txid`

```text
key   = txId
value = txNum, blockNum, txIndex, status
```

用途：

- `TX_BEFORE(txId)` / `TX_AFTER(txId)`。
- debug/trace 入口从 tx id 找状态点。

注意：reorg 时必须删除非 canonical txId 映射；如果同一个 txId 重新进入新 canonical block，要写入新 `blockNum/txIndex/txNum`。

### 5.3 `archive_txnum_meta`

```text
key   = txNum
value = blockNum, txIndex, phase, txId?, blockHash
```

用途：

- 诊断 archive 数据。
- 按 txNum 反查执行上下文。
- proof 输出携带坐标。

### 5.4 `archive_txnum_cursor`

```text
key   = "nextTxNum"
value = next txNum to allocate
```

必须与 block apply session 同事务提交，或者至少在 reorg/replay 时能从 `archive_txnum_by_block` 重建。

## 6. 与 java-tron 执行流程的接入点

### 6.1 block 开始

在 `Manager.processBlock` 开头建立 archive block context：

```text
ArchiveBlockContext {
    blockNum
    blockHash
    parentHash
    firstTxNum
    txCursor
}
```

输入来源：

- `BlockCapsule.getNum()`
- `BlockCapsule.getBlockId()`
- `block.getTransactions()`

### 6.2 每笔普通交易

在 `Manager.java:1858` 的循环中：

```text
txIndex = loop index
txId = transactionCapsule.getTransactionId()
txNum = allocate(USER_TX, blockNum, txIndex, txId)
ArchiveExecutionContext.bind(txNum, blockNum, txIndex, USER_TX)
processTransaction(...)
ArchiveExecutionContext.clear()
```

`ArchiveWriteCollector` 后续会依赖这个 context 给所有 store 写打上 `txNum`。

### 6.3 block finalize

在 `payReward`、maintenance proposal、`consensus.applyBlock`、recent cache/dynamic properties、section bloom 周围绑定系统 logical tx：

```text
begin(BLOCK_FINALIZE)
payReward(block)
proposalController.processProposals()
consensus.applyBlock(block)
updateTransHashCache(block)
updateRecentBlock(block)
updateRecentTransaction(block)
updateDynamicProperties(block)
sectionBloomStore.write(block.getNum())
end()
```

第一阶段推荐合并一个 `BLOCK_FINALIZE`，因为对 RPC 历史读来说，用户主要需要：

```text
block N 结束后的完整状态
block N 中第 K 笔交易前/后的状态
```

系统写再细分的收益低于实现复杂度。

## 7. StatePoint 到 asOfTxNum 的解析

Erigon V3 的核心语义是 `GetAsOf(key, txNum)` 返回 “`txNum` 之前”的值。java-tron 对外不应暴露这个 off-by-one，因此 `ArchiveTxNumIndex` 需要集中处理。

建议规则：

| 外部状态点 | 内部 `asOfTxNum` |
| --- | --- |
| `BLOCK_BEFORE(N)` | `firstTxNum(N)` |
| `BLOCK_END(N)` | `lastTxNum(N) + 1` |
| `TX_BEFORE(txId)` | `txNum(txId)` |
| `TX_AFTER(txId)` | `txNum(txId) + 1` |
| `BLOCK_TX_AFTER(N, k)` | `txNum(N, k) + 1` |
| `LATEST` | `nextTxNum` |

这样 `ArchiveStateReader` 只接收已经规范化的 `asOfTxNum`，避免 RPC、debug、commitment 多处重复实现边界逻辑。

## 8. Reorg / replay 语义

java-tron 使用 revoking/snapshot 机制处理回滚：

| 位置 | 作用 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/db2/core/SnapshotManager.java:115` | `buildSession` |
| `SnapshotManager.java:160-162` | `advance` |
| `SnapshotManager.java:165-167` | `retreat` |
| `SnapshotManager.java:170-180` | `merge` |

Archive txNum 索引需要与 canonical 状态同生共死。推荐规则：

1. block apply 成功并 merge 后，`archive_txnum_by_block` 才可见。
2. block apply 失败或 session retreat，丢弃本 block 分配的 txNum 和 write set。
3. reorg unwind 到 block `H` 时，删除所有 `blockNum > H` 的 txNum 索引和 temporal history。
4. replay 同一条 canonical chain 时，必须得到相同 txNum。txNum 只能依赖 block 顺序、交易顺序和固定 phase，不能依赖本地时间、线程调度或缓存命中。

## 9. 对现有 balance history 的关系

java-tron 已有历史余额查询开关：

| 位置 | 作用 |
| --- | --- |
| `framework/src/main/resources/config.conf:80` | `balance.history.lookup = false` |
| `common/src/main/java/org/tron/common/parameter/CommonParameter.java:626` | `--history-balance-lookup` |
| `common/src/main/java/org/tron/core/Constant.java:370` | `storage.balance.history.lookup` 配置 key |
| `chainbase/src/main/java/org/tron/core/store/AccountStore.java:73` | 记录余额变化 |
| `chainbase/src/main/java/org/tron/core/store/AccountTraceStore.java:39` | `getPrevBalance(address, blockNum)` |
| `framework/src/main/java/org/tron/core/Wallet.java:4355` | `getAccountBalance` |

这套机制是 block-level balance-only history，不是 tx-level archive：

- 坐标是 block number，不是 txNum。
- 只覆盖账户余额，不覆盖 code/storage/contract/dynamic properties。
- 不能回答区块内中间态。

`ArchiveTxNumIndex` 不应复用 `account-trace` 的 key 设计，但可以在兼容层把旧的 `getAccountBalance(block)` 映射到 `BLOCK_END(blockNum)`。

## 10. 测试建议

### 10.1 单区块多交易

构造一个 block 内多笔转账：

```text
tx0: A -> B
tx1: B -> C
tx2: A -> C
```

断言：

- `txNum(tx0) < txNum(tx1) < txNum(tx2)`
- `TX_AFTER(tx0)` 能看到 tx0 结果但看不到 tx1/tx2。
- `BLOCK_END(block)` 能看到全部结果和 block finalize 写。

### 10.2 空区块 / 系统写

构造没有普通交易但有 block finalize 写的区块。断言：

- block 仍然有 txNum range。
- `BLOCK_END(block)` 与 `BLOCK_BEFORE(block+1)` 等价。

### 10.3 reorg

构造：

```text
main: A -> B -> C
fork: A -> B' -> C'
```

断言：

- 原 `B/C` 的 txId 映射被删除。
- 新 `B'/C'` txNum 单调连续。
- 非 canonical txId 查询 archive 状态点返回不存在或非 canonical 错误。

## 11. 实现优先级

P0：

- 新增 txNum 分配和索引表。
- 接入 `Manager.processBlock` 普通交易循环。
- 接入 block finalize 合并 phase。
- 提供 `StatePoint -> asOfTxNum` API。

P1：

- reorg/unwind 删除 txNum range。
- backfill/replay 生成历史 txNum。
- 与 `ArchiveTemporalStore` 同事务提交。

P2：

- 系统 phase 细分。
- txNum range snapshot/freeze。
- debug API 暴露 txNum meta。

## 12. 关键风险

1. 把 `TransactionCapsule.order` 误用为 txIndex：源码显示它在 `Manager.java:1591` 被设置为 fee，不是 block 内序号。
2. 漏掉交易循环之后的系统写：会导致 `BLOCK_END(N)` 与 `BLOCK_BEFORE(N+1)` 不一致。
3. txNum cursor 与 canonical DB 非原子提交：崩溃恢复后可能出现 txNum 空洞或重复。
4. reorg 只回滚 current state、不回滚 archive sidecar：会留下非 canonical 历史。
5. 历史 balance 查询与 archive 查询并存时语义不一致：需要明确旧接口是 block-level，archive 是 tx-level。
