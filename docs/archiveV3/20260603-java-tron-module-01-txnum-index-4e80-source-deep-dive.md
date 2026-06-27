# 模块 01 ArchiveTxNumIndex：4e80 java-tron 源码对照细化

日期：2026-06-03

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

关联总表：[java-tron Archive：4e80 六模块源码对照细化](./20260603-java-tron-archive-4e80-six-modules-source-detail.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`，`git status --short` 为空，精确冲突标记扫描无命中。

## 1. 当前结论

java-tron 当前没有 Erigon 式全局 `txNum`。现有持久化坐标只够回答“交易在哪个 block”，不够回答“交易级历史状态在全局时间线上的哪个点”。

当前源码证据：

| 源码 | 事实 |
| --- | --- |
| `TransactionCapsule.java:103-107` | 只有 `blockNum` 字段，Lombok 生成 setter/getter |
| `TransactionCapsule.java:115-117` | `order` 字段存在，但不是 block 内交易序号 |
| `Manager.java:1873` | `processBlock` 用 `block.getTransactions()` 顺序执行交易 |
| `Manager.java:1881` | 执行时只调用 `transactionCapsule.setBlockNum(num)` |
| `TransactionStore.java:33-38` | 交易带 blockNum 时，`trans` store 只保存 `txId -> blockNum` |
| `TransactionStore.java:41-49` | 读取交易时再从 `BlockStore` 的 block 交易列表里找 tx |

因此 `ArchiveTxNumIndex` 必须新增 sidecar txNum timeline，不能复用 `TransactionStore`、`TransactionCapsule.order` 或 `block.getTransactions()` 的临时 index。

## 2. 当前源码状态

当前 java-tron 工作区干净，且以下精确冲突标记扫描无命中：

```bash
git -C /Users/boson/IdeaProjects/java-tron rev-parse --short=12 HEAD
# 4e80f8ffa9a2

git -C /Users/boson/IdeaProjects/java-tron status --short
# no output

rg -n '^(<<<<<<< .+|=======$|>>>>>>> .+)' /Users/boson/IdeaProjects/java-tron
# no output
```

因此本文行号按当前 `4e80f8ffa9a2` 源码直接可用。后续 java-tron 分支切换后仍需重新跑 `rg` 和 `nl` 校验。

## 3. Manager 生命周期锚点

### 3.1 normal block apply

| 源码 | 事实 | archive hook |
| --- | --- | --- |
| `Manager.java:1266` | `pushBlock(final BlockCapsule block)` 是 fullnode 推块入口 | 不直接分配 txNum，进入 normal/fork path 后再分配 |
| `Manager.java:1379` | `try (ISession tmpSession = revokingStore.buildSession())` | `archive.beginBlock(newBlock)` 必须在 session 内、`applyBlock` 前 |
| `Manager.java:1380` | `applyBlock(newBlock, txs)` | 所有 tx phase 和 Store write 发生在这一段 |
| `Manager.java:1381` | `tmpSession.commit()` | canonical commit 成功后才能 `archive.commitBlock(newBlock)` |
| `Manager.java:1382-1386` | catch 会移除 khaos block、清理 trigger、rethrow | catch 中必须 `archive.abortBlock(newBlock)` |

核心规则：

```text
archive write-set/txNum 在 canonical revoking session 成功前只能 pending。
canonical tmpSession.commit() 成功后，archive 才能把 txNum/write-set/progress 同 batch 落盘。
```

### 3.2 fork unwind

| 源码 | 事实 | archive hook |
| --- | --- | --- |
| `Manager.java:1034` | `eraseBlock()` 是 fork 回退入口 | 拿 old head 作为 archive unwind 目标 |
| `Manager.java:1037-1039` | 通过 latest block hash 找 `oldHeadBlock` | archive unwind 需要这个 blockNum |
| `Manager.java:1040` | `khaosDb.pop()` | canonical 内存分支先回退 |
| `Manager.java:1041` | `revokingStore.fastPop()` | 成功后再 `archive.unwindBlock(oldHeadBlock)` |
| `Manager.java:1043-1046` | 回退交易进入 popped queue 并计 metrics | 不影响 archive state，但说明回退 block 的交易会被重放 |

archive unwind 不能放在 `fastPop()` 前。否则 canonical 回退失败时 archive 已经回退，会造成两边 progress 不一致。

### 3.3 fork replay 和 recovery replay

| 源码 | 事实 | archive hook |
| --- | --- | --- |
| `Manager.java:1094` | `switchFork(BlockCapsule newHead)` 是切分支入口 | replay 不是普通 push，但会改 canonical state |
| `Manager.java:1142` | 新分支 replay 建 `ISession` | 同样 `beginBlock` |
| `Manager.java:1149` | 新分支 replay commit | 同样 `commitBlock` |
| `Manager.java:1185` | 失败恢复原分支 replay 建 `ISession` | 同样 `beginBlock` |
| `Manager.java:1187` | 恢复原分支 replay commit | 同样 `commitBlock` |

fork replay/recovery replay 不能绕过 archive lifecycle。否则 txNum/progress 会只覆盖正常入块，不覆盖切分支造成的 canonical state。

## 4. block 内 logical phase

`processBlock` 是 txNum phase 的主切入点：

| 源码 | 事实 | phase |
| --- | --- | --- |
| `Manager.java:1838` | `processBlock(BlockCapsule block, List<TransactionCapsule> txs)` | block 执行主体 |
| `Manager.java:1851` | `BalanceTraceStore.initCurrentBlockBalanceTrace(block)` | `BLOCK_PREPARE` |
| `Manager.java:1854` | `saveBlockEnergyUsage(0)` | `BLOCK_PREPARE` |
| `Manager.java:1867` | `HistoryBlockHashUtil.write(this, block)` | block-level write，是否进 archive 由 registry 决定 |
| `Manager.java:1870` | `accountStateCallBack.preExecute(block)` | 现有 accountStateRoot 回调，不是 archive txNum |
| `Manager.java:1873` | 遍历 `block.getTransactions()` | `USER_TX(txIndex)` |
| `Manager.java:1881` | 给交易设置 blockNum | 没有 txIndex |
| `Manager.java:1885-1887` | `preExeTrans` / `processTransaction` / `exeTransFinish` | 单笔用户交易边界 |
| `Manager.java:1893` | `executePushFinish()` | 现有 accountStateRoot finalize |
| `Manager.java:1906` | `payReward(block)` | `BLOCK_FINALIZE` |
| `Manager.java:1911-1914` | proposal maintenance / consensus apply | `BLOCK_FINALIZE` |
| `Manager.java:1922-1925` | trans hash cache、recent block/tx、dynamic properties | `BLOCK_FINALIZE`，其中 dynamic properties 需要 archive |

必须单独分 `BLOCK_FINALIZE`。否则 reward、maintenance、dynamic properties 这类系统写入会被错误归到最后一笔用户交易，或者完全没有 txNum。

## 5. TransactionStore 为什么不能复用

`TransactionStore` 是 txId 到交易所在 block 的辅助索引，不是 txNum index。

| 源码 | 事实 | archive 结论 |
| --- | --- | --- |
| `TransactionStore.java:28-29` | DB name 是 `trans` | block/tx 数据，不是 execution state domain |
| `TransactionStore.java:33-38` | item 有 blockNum 时只写 8-byte blockNum | 没有 txIndex，没有 phase |
| `TransactionStore.java:41-49` | 通过 blockNum 从 `BlockStore` 找交易 | 读时才能恢复 tx 对象 |
| `TransactionStore.java:68-79` | `getBlockNumber` 可返回 blockNum | 只能作为 txId->blockNum 辅助 |
| `TransactionStore.java:81-104` | `get` 可能从 block store/khaos database 找交易 | 不稳定为 archive temporal 坐标 |

P0 可以把 `txId -> txNum` 写进 archive txnum table，不能用 `trans` DB 替代。

## 6. ArchiveTxNumIndex 数据模型

建议 P0 最小模型：

```text
ArchiveTxPhase:
  BLOCK_PREPARE
  USER_TX
  BLOCK_FINALIZE

TxPosition:
  blockNum
  txIndex        // USER_TX 时有效；system phase 为 -1
  phase
  txId           // USER_TX 时有效；system phase 为空
  txNum

BlockTxRange:
  blockNum
  firstTxNum
  lastTxNum
  userTxCount
  finalizeTxNum
```

必要索引：

| 索引 | 用途 |
| --- | --- |
| `blockNum -> BlockTxRange` | historical block selector 到 finalize txNum |
| `blockNum + txIndex -> txNum` | trace/debug 或 eth_call 中定位交易前后状态 |
| `txId -> txNum` | 根据交易查 archive state point |
| `txNum -> TxPosition` | unwind、debug、rebuild verifier |

## 7. 和后续模块的契约

| 下游模块 | 需要 Module 01 提供什么 |
| --- | --- |
| Module 03 `ArchiveWriteCollector` | 当前 execution context 中的 `txNum`、phase、blockNum、txIndex |
| Module 04 `ArchiveTemporalStore` | block range、txNum 顺序、unwind 目标 |
| Module 05 `ArchiveStateReader` | block tag/number 解析后的 finalize txNum |
| Module 06 `CommitmentBuilder` | block finalize txNum 和 block range 内 changeset 顺序 |

如果 Module 01 不稳定，后续 temporal/history/root 都只能靠 blockNum 粗粒度工作，无法满足交易级状态树。

## 8. 第一版实现落点

新增类建议：

| 类 | package | 说明 |
| --- | --- | --- |
| `ArchiveTxNumIndex` | `org.tron.core.archive.txnum` | 接口，分配/查询 txNum |
| `ArchiveTxPhase` | 同上 | `BLOCK_PREPARE/USER_TX/BLOCK_FINALIZE` |
| `ArchiveTxPosition` | 同上 | txNum 到 block/phase 的值对象 |
| `ArchiveBlockRange` | 同上 | blockNum 到 txNum range |
| `InMemoryArchiveTxNumIndex` | 同上 | S2 阶段测试用 |
| `PersistentArchiveTxNumIndex` | 同上 | S6/S7 接入 temporal raw store |
| `ArchiveExecutionContext` | `org.tron.core.archive` | 当前 txNum/phase，上下游共享 |
| `ArchiveService` / `NoopArchiveService` | `org.tron.core.archive` | Manager 调用门面 |

Manager hook 顺序：

```text
beginBlock(block)
  beginBlockPrepare(block)
  process block prepare writes
  endBlockPrepare()

  for txIndex, tx in block.getTransactions():
    beginUserTx(block, txIndex, txId)
    processTransaction(tx, block)
    endUserTx()

  beginBlockFinalize(block)
  payReward/proposal/consensus/updateDynamicProperties
  endBlockFinalize()
canonical tmpSession.commit()
commitBlock(block)
```

## 9. 测试证据

最小测试必须证明：

| 测试 | 要证明 |
| --- | --- |
| normal block with N tx | txNum 顺序为 prepare、N 个 user tx、finalize |
| empty block | 仍分配 prepare/finalize |
| applyBlock 抛异常 | pending txNum 不落盘 |
| fork erase | canonical `fastPop()` 后 archive range 同步回退 |
| fork replay | replay block 也生成 txNum range |
| txId lookup | `txId -> txNum` 不依赖 `TransactionStore` |
| block selector | `blockNum -> finalizeTxNum` 可供 Module 05 使用 |
