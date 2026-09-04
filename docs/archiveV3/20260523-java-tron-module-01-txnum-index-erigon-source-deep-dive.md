# 模块 01 ArchiveTxNumIndex：Erigon 源码对照深挖

日期：2026-05-23

关联设计文档：[java-tron Archive 模块 01：ArchiveTxNumIndex 细化设计](./20260521-java-tron-archive-module-01-txnum-index.md)

## 1. 调研范围

本轮只深入第一个模块 `ArchiveTxNumIndex`，对照 Erigon V3 源码确认 txNum 的实际语义、索引表设计、恢复逻辑、执行阶段使用方式，以及这些行为对 java-tron 方案的修正建议。

主要源码：

- [`db/kv/tables.go`](../../db/kv/tables.go)
- [`db/kv/rawdbv3/txnum.go`](../../db/kv/rawdbv3/txnum.go)
- [`execution/stagedsync/exec3.go`](../../execution/stagedsync/exec3.go)
- [`execution/stagedsync/exec3_serial.go`](../../execution/stagedsync/exec3_serial.go)
- [`execution/stagedsync/exec3_parallel.go`](../../execution/stagedsync/exec3_parallel.go)
- [`execution/exec/txtask.go`](../../execution/exec/txtask.go)
- [`execution/state/rw_v3.go`](../../execution/state/rw_v3.go)
- [`execution/state/history_reader_v3.go`](../../execution/state/history_reader_v3.go)
- [`db/state/execctx/domain_shared.go`](../../db/state/execctx/domain_shared.go)
- [`db/kv/kv_interface.go`](../../db/kv/kv_interface.go)

## 2. 核心结论

1. Erigon V3 的 txNum 是 canonical execution order，不是本地自增 tx id。
   `tables.go` 明确说明 snapshots、history、indices 使用 TxNum；本地 `TxnID` 不同节点可能不同，不能作为历史坐标。

2. Erigon 的 block -> txNum 索引只存 `MaxTxNum`。
   `MinTxNum(block)` 由 `max(block-1)+1` 推导。java-tron 可以为了查询方便存 min/max，但必须保留这个连续性不变量。

3. Erigon 把 block begin / user tx / block end 都建模为 execution task。
   `TxTask.TxIndex == -1` 表示 block initialisation，`TxIndex == len(txs)` 表示 block end。这直接支持 java-tron 把 maintenance/reward/resource settlement 作为 system logical tx。

4. Erigon 的 `GetAsOf(key, txNum)` 是 before-tx 语义。
   这验证了模块 01 里 `StatePoint -> asOfTxNum` 的必要性：RPC 层不应裸用 txNum。

5. Erigon 在恢复 partial block 时回到 block 起点重放。
   `restoreTxNum` 如果发现当前 txNum 落在 block 中间，会计算 `offsetFromBlockBeginning`，从 block min txNum 重新开始，前缀用 history execution 跳过或补齐。这对 java-tron 很关键：不要在 block 中间随意继续写 archive。

6. Erigon 的 `Max()` 有“block 不存在时 fallback 到 latest/pending”的行为。
   这是内部执行便利，但 java-tron 的 archive RPC 不能照搬。对用户查询缺失 block 时应返回 `ARCHIVE_NOT_SYNCED` 或 `BLOCK_NOT_FOUND`，不能静默读 latest。

7. Commitment/root 计算依赖正确的 MaxTxNum 快照。
   `computeAndCheckCommitmentV3` 注释说明必须用当前 apply transaction 读 `MaxTxNum`，否则新写入的 block MaxTxNum 可能被只读快照漏掉，导致 root 和 blockNum 绑定错位。java-tron 的 root/txNum/progress 提交也必须在同一事务视图中。

## 3. Erigon 的 txNum 表模型

### 3.1 `MaxTxNum` 表

`db/kv/tables.go` 定义：

```text
MaxTxNum = "MaxTxNum" // block_number_u64 -> max_tx_num_in_block_u64
```

同一段注释说明：

- `TxnID` 是本地自增，不同节点可能不同。
- snapshots frozen data 使用 TxNum，不使用 TxnID。
- reorg 时 transactions 不一定删除/更新。
- system tx 可能位于 block 前后。
- Erigon3 的 history/indices 使用 TxNum。

对 java-tron 的含义：

- `ArchiveTxNumIndex` 必须使用 replay 可复现的 canonical txNum。
- 不能使用本地 DB 自增 id、线程任务 id、执行批次 id。
- system tx 必须显式建模，不能隐藏在 block finalization 里。

### 3.2 `TxNumsReader`

`db/kv/rawdbv3/txnum.go` 中 `TxNumsReader` 封装 `MaxTxNum`：

| 方法 | 行为 |
|---|---|
| `Max(blockNum)` | 返回该 block 的 max txNum；block 不存在时 fallback 到最后可用 txNum |
| `Min(blockNum)` | `max(blockNum-1)+1`；block 0 返回 0 |
| `BlockNumber(txNum)` | 通过 `MaxTxNum` 二分查找第一个 `maxTxNum >= txNum` 的 block |
| `Append(blockNum, maxTxNum)` | 追加 block -> maxTxNum，检查 block 连续性 |
| `Truncate(blockNum)` | 从某个 block 开始删除后续 MaxTxNum |
| `Last()` / `First()` | 读取索引边界 |

这个模型极简：只存每个 block 的最大 txNum，其他关系推导。

java-tron 可采用更显式的表：

```text
block_num -> min_tx_num, max_tx_num, block_start_asof, block_end_asof
tx_id     -> tx_num
tx_num    -> block_num, tx_index, phase
```

但不变量应对齐 Erigon：

```text
minTxNum(blockN) = maxTxNum(blockN - 1) + 1
blockEndAsOf(blockN) = maxTxNum(blockN) + 1
```

如果 java-tron 允许空 block 不消耗 txNum，则 `blockEndAsOf(blockN)` 可以等于 `blockStartAsOf(blockN)`，但必须在索引里显式表示，避免推导歧义。

## 4. Append / Truncate 的不变量

### 4.1 Append 防 gap

Erigon `TxNumsReader.Append`：

- 读取 `MaxTxNum` 最后一个 key。
- 如果不是 genesis 特例，要求 `lastBlockNum + 1 == blockNum`。
- 使用 big-endian 编码 blockNum 和 maxTxNum。

这说明 txNum block index 必须顺序增长，不能随便补洞。

java-tron 建议：

- `archive_block_txnum` 写入必须按 canonical block 高度连续。
- 发现高度 gap 直接失败。
- repair/backfill 必须从 gap 前共同点重新构建，不要跳写。

### 4.2 Truncate 支持 unwind

Erigon `Truncate(blockNum)` 从给定 block 开始删除后续 `MaxTxNum`，并检查遍历中的 blockNum 连续性。

java-tron 对应：

```text
unwindToBlock(targetBlock):
  delete block_num > targetBlock
  delete tx_num > blockEndAsOf(targetBlock)-1
  reset next_tx_num = blockEndAsOf(targetBlock)
```

注意：Erigon 的 `Truncate(blockNum)` 删除的是 `blockNum` 及之后；模块 01 设计里的 `unwindToBlock(targetBlock)` 保留 targetBlock，删除 targetBlock 之后。实现时必须命名清楚，避免 off-by-one。

## 5. txNum 如何进入执行任务

### 5.1 TxTask

`execution/exec/txtask.go` 中 `TxTask` 包含：

```text
TxNum uint64
TxIndex int // -1 for block initialisation
```

并且：

```text
IsBlockEnd() = TxIndex == len(Txs)
```

这说明 Erigon 的 txNum 不只绑定用户交易，也绑定 block 初始化和 block 收尾任务。

java-tron 设计应保留这个抽象：

```text
LogicalTx:
  GENESIS
  BLOCK_BEGIN_SYSTEM
  USER_TX
  BLOCK_END_SYSTEM
  MAINTENANCE
```

而不是只给用户交易分配 txNum。

### 5.2 exec3 任务循环

`execution/stagedsync/exec3.go` 和 `exec3_serial.go` 都会构造 tx task，循环形态是：

```text
for txIndex := -1; txIndex <= len(txs); txIndex++ {
  txTask.TxNum = inputTxNum
  txTask.TxIndex = txIndex
  inputTxNum++
}
```

含义：

- `txIndex = -1`：block start。
- `txIndex = 0..len(txs)-1`：用户交易。
- `txIndex = len(txs)`：block end。

java-tron 应把这种“逻辑执行点”显式持久化到 `archive_txnum_meta`：

```text
txNum -> blockNum, txIndex, logicalType, phase, txId
```

其中 system phase 不能只靠 `txIndex = -1/len` 表达，因为 TRON 可能有多类维护/奖励/资源结算。建议额外记录 `phase`。

## 6. txNum 如何进入状态写入

### 6.1 State writer 使用 txNum

`execution/state/rw_v3.go` 的 `Writer` 在账户、代码、storage 写入时都把 `w.txNum` 传给 domain 写：

```text
DomainPut(AccountsDomain, key, value, w.txNum, nil)
DomainPut(CodeDomain, key, code, w.txNum, nil)
DomainPut(StorageDomain, composite, value, w.txNum, nil)
DomainDel(..., w.txNum, nil)
```

这验证了模块 03/04 的设计方向：

- Collector 输出的每个 domain write 必须带 txNum。
- TemporalStore 的 history key 必须是 `domain/key/changedTxNum`。
- 删除也必须带 txNum。

### 6.2 ApplyStateWrites 也带 txNum

`StateV3.ApplyStateWrites(ctx, roTx, blockNum, txNum, writes, ...)` 的签名把 blockNum 和 txNum 同时传入。它在 step boundary 计算 commitment 时使用 `(txNum+1)%stepSize == 0` 判断。

对 java-tron 的启发：

- blockNum 用于日志、root metadata、系统上下文。
- txNum 用于状态历史和时间坐标。
- step/checkpoint 应按 txNum，而不是 blockNum。

## 7. `GetAsOf` 的 before-tx 语义

`db/kv/kv_interface.go` 明确注释：

```text
GetAsOf(Account, key, txNum) returns account's value before txNum transaction changed it.
To re-execute txNum on historical state, read with txNum.
```

这对模块 01 的 `StatePoint` 是直接支持：

```text
TX_BEFORE(txNum) = GetAsOf(key, txNum)
TX_AFTER(txNum)  = GetAsOf(key, txNum + 1)
BLOCK_END(block) = GetAsOf(key, maxTxNum(block) + 1)
```

java-tron 的外部 API 必须隐藏这个细节。`eth_getBalance(addr, blockNum)` 应解析成 `BLOCK_END(blockNum)`，而不是直接用 block 的 maxTxNum。

## 8. partial block 恢复逻辑

### 8.1 SeekCommitment

`SharedDomains.SeekCommitment` 会找到最近 commitment 并设置 `sd.txNum`。`NewSharedDomains` 中还有检查：如果 commitment 对应的 blockNum 超过 TxNums index 的最后 block，会返回 behind commitment 错误。

这说明 Erigon 要求 commitment progress 和 TxNums index 对齐。

java-tron 对应：

```text
ArchiveTxNumIndex progress
ArchiveTemporalStore progress
CommitmentBuilder progress
```

必须有统一检查，不能单独推进。

### 8.2 restoreTxNum

`restoreTxNum` 逻辑：

1. 从 commitment/currentTxNum 开始。
2. 用 `TxNumsReader.Last` 看是否已经到最后。
3. 用 `FindBlockNum(currentTxNum)` 找 currentTxNum 所属 block。
4. 如果 currentTxNum 正好等于该 block max，则切到下一个 block。
5. 计算 block min。
6. 如果 currentTxNum 大于 block min，说明停在 block 中间，设置 `offsetFromBlockBeginning`，并从 block min 重新开始。

源码注释说如果停在 block 中间，前半部分会以 `HistoryExecution` 模式执行。

java-tron 启发：

- archive 恢复不要从“半个交易/半个 block 的中间状态”继续写。
- 如果上次进度落在 block 内，应回到 block start 或最近完整 logical tx boundary。
- 对于 block 级原子提交的 PoC，最简单是永远只在 block boundary 持久化 progress。
- 如果未来支持 tx 级提交，也要能从 tx boundary 恢复，并校验 txNum/index/write-set 完整。

## 9. MaxTxNum fallback 的风险

Erigon `TxNumsReader.Max` 注释：

```text
If block not found - return last available value (`latest`/`pending` state)
```

这种 fallback 在内部阶段有用途，但 `computeAndCheckCommitmentV3` 的注释也暴露了风险：如果用 fresh read-only transaction 读不到当前 batch 新写的 MaxTxNum，会 fallback 到前一个 block 的 max txNum，导致 root 和 blockNum 错配。

java-tron 不建议在 ArchiveStateReader/RPC 中实现这种 fallback：

- 查询 block 不存在：返回 `BLOCK_NOT_FOUND`。
- 查询超过 archive progress：返回 `ARCHIVE_NOT_SYNCED_TO_BLOCK`。
- root 计算读不到当前 block txNum：直接失败。
- 内部恢复工具如果需要 fallback，必须只在明确的 repair/rebuild 上下文使用。

## 10. TxNum 写入时机

Erigon 的 `MaxTxNum` 可能由 block body/header 写入阶段提前写好；`computeAndCheckCommitmentV3` 特别强调要用当前 `applyTx` 读取，因为 header 阶段已经在这个 transaction 中写了 MaxTxNum。

java-tron 可以选择不同实现，但必须满足：

```text
block txNum index
temporal history
commitment root
progress
```

在同一个 archive transaction 或同一个可恢复 two-phase commit 中一致提交。

推荐顺序：

1. `ArchiveTxNumIndex.beginBlock` 在内存中分配 logical txNum。
2. `ArchiveWriteCollector` 使用这些 txNum 采集 write-set。
3. block apply 成功后，在一个 archive commit 中写：
   - txNum index
   - temporal history/latest
   - commitment root
   - progress
4. 任一失败则 block archive 全部回滚。

## 11. java-tron 模块 01 设计修正建议

### 11.1 保留显式 min，但定义 Erigon 不变量

模块 01 已建议存 `block_num -> min/max`。源码对照后建议补充不变量：

```text
min_tx_num(blockN) must equal max_tx_num(blockN-1) + 1
block_start_asof(blockN) must equal min_tx_num(blockN)
block_end_asof(blockN) must equal max_tx_num(blockN) + 1
```

如果 block 完全无 logical tx：

```text
block_start_asof == block_end_asof
min/max nullable
```

并且 Reader 不能通过 `max+1` 推导空 block，必须读 `block_end_asof`。

### 11.2 增加 block begin/end phase

Erigon 用 `TxIndex=-1` 和 `TxIndex=len(txs)` 表达 block init/end。java-tron 应显式支持：

```text
BLOCK_BEGIN_SYSTEM
BLOCK_END_SYSTEM
MAINTENANCE
REWARD
RESOURCE_SETTLEMENT
GOVERNANCE_APPLY
```

这些 phase 是否每个 block 都消耗 txNum，可以由实际是否有 canonical state write 决定；但一旦消耗，必须写入 `archive_txnum_meta`。

### 11.3 用户交易即使无状态写也消耗 txNum

Erigon 的 execution task 给每个用户交易分配 txNum。java-tron 应保持：

- 已上链交易无状态写：消耗 txNum，TxWriteSet 为空。
- failed 但上链交易：消耗 txNum，记录费用/资源最终写。
- 未上链 rejected transaction：不消耗 txNum。

这样 `TX_BEFORE/TX_AFTER(txId)` 对所有上链交易都有稳定语义。

### 11.4 明确不采用 Erigon Max fallback 的对外语义

模块 01 应继续保持：

- 查询未来 block/tx 返回错误。
- 查询 archive 起点前返回 unsupported。
- 查询非 canonical tx 返回 not found。

不要把 Erigon 内部 `Max(block not found) -> latest` 行为暴露给 java-tron 用户。

### 11.5 进度一致性检查需要前置

Erigon `SharedDomains` 会检查 commitment 不要领先 TxNums。java-tron 应在启动和每次 block commit 前检查：

```text
txnum_progress >= temporal_progress
txnum_progress >= commitment_progress
all progress point to same blockId/asOfTxNum
```

发现不一致进入 repair mode，不继续写 archive。

## 12. 建议补充到模块 01 的接口

结合 Erigon 源码，建议 `ArchiveTxNumIndex` 增加这些方法或约束：

```java
BlockTxNumRange requireBlockRange(long blockNum);

Optional<BlockTxNumRange> findBlockByTxNum(long txNum);

long blockStartAsOf(long blockNum);

long blockEndAsOf(long blockNum);

ArchiveProgressCheck checkProgressAgainst(
    TemporalProgress temporalProgress,
    CommitmentProgress commitmentProgress);

void truncateFromBlock(long blockNumInclusive);
```

其中：

- `requireBlockRange` 不做 latest fallback。
- `findBlockByTxNum` 类似 Erigon `BlockNumber(txNum)`，用于恢复和 debug。
- `truncateFromBlock` 语义要和 `unwindToBlock` 区分清楚。

## 13. 下一步源码对照问题

继续模块 01 还可以补三类细节：

1. block body 的 tx count 如何包含 system tx。
   `db/rawdb/accessors_chain.go` 用 `BodyForStorage.TxCount` 重建 MaxTxNum；需要进一步追 `BodyForStorage` 的 TxCount 是否包含 begin/end system tx。

2. snapshot/frozen block reader 如何从 segment 查 txNum。
   `db/snapshotsync/freezeblocks/block_reader.go` 有 `txBlockIndexWithBlockReader`，可对照多盘/segment archive 的 txNum 查询。

3. parallel executor 中 txNum 与 block cache 的精确关系。
   `exec3_parallel.go` 对 `ApplyStateWrites`、block end finalVersion、block cache flush 有更多细节，可作为模块 03/04 的源码对照重点。

## 14. 对 java-tron 的落地判断

模块 01 原设计方向是正确的，但源码对照后需要强调三个实现纪律：

1. `ArchiveTxNumIndex` 是所有历史读写的坐标事实源。
   任何模块都不能自行推 txNum。

2. block-end state point 必须显式化。
   不要让调用方到处写 `maxTxNum + 1`。

3. archive 进度必须以 block boundary 原子推进。
   Erigon 支持 partial block 恢复，但机制复杂；java-tron PoC 应先选择 block-level atomic commit，等 block-level 历史和 root 稳定后，再考虑 tx-level commit/recovery。
