# java-tron Archive 模块 01：ArchiveTxNumIndex 细化设计

日期：2026-05-21

关联总文档：[java-tron 交易级状态树支持：Erigon V2/V3 模型调研](./20260520-java-tron-archive-state-erigon-v2-v3-research.md)

源码对照深挖：[模块 01 ArchiveTxNumIndex：Erigon 源码对照深挖](./20260523-java-tron-module-01-txnum-index-erigon-source-deep-dive.md)

java-tron 源码对照：[模块 01 ArchiveTxNumIndex：java-tron 源码对照](./20260601-java-tron-module-01-txnum-index-java-tron-source-deep-dive.md)

模块 01 逐文件 Patch 清单：[java-tron Archive 模块 01：ArchiveTxNumIndex 逐文件 Patch 清单](./20260602-java-tron-archive-module-01-txnum-index-patch-checklist.md)

逐文件实现清单：[java-tron Archive PR1/PR2 逐文件 Patch 清单](./20260602-java-tron-archive-pr1-pr2-patch-checklist.md)

实现蓝图：[java-tron Archive 状态树实现蓝图](./20260602-java-tron-archive-implementation-blueprint.md)

## 1. 模块定位

`ArchiveTxNumIndex` 是 archive 状态系统的时间坐标模块。它不保存账户、合约 storage、代码，也不计算 root；它只负责把 java-tron canonical chain 上的每一次逻辑状态转移映射到一个全局单调的 `txNum`，并把外部查询语义转换成内部 `asOfTxNum`。

这个模块解决三个问题：

1. 交易级历史状态必须有交易级时间坐标，不能只用 block number。
2. Erigon V3 的 `GetAsOf(key, txNum)` 是 before-tx 语义，RPC 层不能直接暴露这个 off-by-one 细节。
3. TRON 除普通交易外，还有 block finalize、maintenance、奖励、资源结算等系统状态变化，这些变化也必须进入同一条 canonical 时间线。

## 2. 职责和非职责

职责：

- 为 canonical block apply 中的每个逻辑状态转移分配 `txNum`。
- 保存 `blockNum <-> txNum range`、`txId -> txNum`、`txNum -> block/tx/phase` 的索引。
- 提供 `StatePoint -> asOfTxNum` 解析，供 `ArchiveStateReader`、`ArchiveTemporalStore`、`CommitmentBuilder` 使用。
- 在 reorg/unwind 时回退索引和 `nextTxNum`。
- 在 replay/backfill 时保证同一条 canonical chain 得到完全一致的 txNum 序列。
- 为 snapshot freeze 提供安全边界：哪些 txNum range 已经 finalized，可转成冷 segment。

非职责：

- 不决定哪些 store 进入 archive domain，这属于 `ArchiveDomainRegistry`。
- 不记录 key/value before-value，这属于 `ArchiveWriteCollector` 和 `ArchiveTemporalStore`。
- 不计算 domain root 或 global root，这属于 `CommitmentBuilder`。
- 不处理非 canonical block、pending transaction、预执行交易产生的临时状态。

## 3. 核心概念

### 3.1 logical transaction

这里的 transaction 不是只有用户提交的链上交易，而是“会改变 canonical state 的最小顺序单元”。建议定义为 `LogicalTx`：

| 类型 | 是否消耗 txNum | 说明 |
|---|---:|---|
| `GENESIS` | 是 | genesis state 初始化；如果 archive 从 genesis replay，作为第一个逻辑状态转移 |
| `USER_TX` | 是 | block 中的普通交易，包含成功、失败但已上链的交易 |
| `BLOCK_BEGIN_SYSTEM` | 视情况 | 区块开始前需要落盘的系统状态变化 |
| `BLOCK_END_SYSTEM` | 是 | 区块收尾、奖励、资源结算、维护逻辑等 |
| `MAINTENANCE` | 是 | 如果维护逻辑独立于普通 block finalize，应显式建模 |
| `BLOCK_MARKER` | 可选 | block 没有任何状态转移时，可用于给 block-end 保留唯一边界；通常不是必须 |

建议：所有已上链 `USER_TX` 都消耗 txNum，即使最终没有 domain write。这样 `TX_BEFORE(txId)` 和 `TX_AFTER(txId)` 永远可解析，debug/API 语义稳定。

### 3.2 txNum

`txNum` 是全局单调递增的 `uint64`，只对 canonical chain 分配。非 canonical fork、pending pool、预执行不分配。

推荐约定：

- `txNum = 0` 保留给 pre-genesis boundary，不作为真实 logical tx。
- `GENESIS` 使用 `txNum = 1`。
- genesis 后状态点是 `asOfTxNum = 2`。
- 后续所有 logical tx 从 `2` 开始连续分配。

如果 java-tron PoC 选择从某个高度 `H` 启用 archive，而不是从 genesis replay，需要定义 base snapshot：

```text
BASE_STATE(block = H - 1) -> asOfTxNum = baseAsOfTxNum
first logical tx in block H -> txNum = baseAsOfTxNum
```

这种模式只能支持 `H` 之后的完整历史状态；`H` 之前最多保留现有余额历史能力，不能声明为完整 archive。

### 3.3 asOfTxNum

为了兼容 Erigon V3 的 before-tx 语义，建议将 `asOfTxNum` 定义为“读取某个 logical tx 执行之前的状态边界”：

```text
state before txNum N = asOfTxNum N
state after  txNum N = asOfTxNum N + 1
```

例子：

```text
txNum 10: A 从 0 改为 1
txNum 11: A 从 1 改为 2

GetAsOf(A, 10) = 0   // tx 10 之前
GetAsOf(A, 11) = 1   // tx 10 之后，tx 11 之前
GetAsOf(A, 12) = 2   // tx 11 之后
```

这条规则应由 `ArchiveTxNumIndex` 封装，RPC 和业务代码只使用 `StatePoint`，不直接做 `txNum + 1`。

### 3.4 StatePoint

`StatePoint` 是外部查询语义：

| StatePoint | 内部解析 |
|---|---|
| `GENESIS_AFTER` | genesis logical tx 的 `txNum + 1` |
| `BLOCK_BEFORE(blockNum)` | block 第一个 logical tx 的 `txNum`；如果 block 无 logical tx，则为该 block 开始边界 |
| `BLOCK_END(blockNum)` | block 最后一个 logical tx 的 `txNum + 1`；如果 block 无 logical tx，则等于 block 开始边界 |
| `TX_BEFORE(txId)` | 该用户交易的 `txNum` |
| `TX_AFTER(txId)` | 该用户交易的 `txNum + 1` |
| `SYSTEM_AFTER(blockNum, phase)` | 指定系统 logical tx 的 `txNum + 1` |
| `LATEST` | 当前 archive progress 的 `nextTxNum` |

对 Ethereum 兼容 RPC，`blockTag` 应解析成 `BLOCK_END(blockNum)`。例如 `eth_getBalance(address, 100)` 读取的是 block 100 完成后的状态，而不是 block 100 第一笔交易之前的状态。

## 4. 数据模型

### 4.1 表：`archive_block_txnum`

保存 block 与 txNum range 的关系。

| key | value |
|---|---|
| `block_num_u64` | `block_id, parent_block_id, first_tx_num, last_tx_num, block_start_asof, block_end_asof, user_tx_count, system_tx_count, status` |

字段说明：

- `first_tx_num`：block 内第一个 logical tx；如果没有 logical tx，可为空或等于 `block_end_asof`。
- `last_tx_num`：block 内最后一个 logical tx；如果没有 logical tx，可为空。
- `block_start_asof`：block 开始状态边界。
- `block_end_asof`：block 结束状态边界，通常是 `last_tx_num + 1`。
- `status`：`HOT` / `FINALIZED` / `FROZEN`，供 freeze/unwind 使用。

### 4.2 表：`archive_txnum_meta`

保存 txNum 的反向元数据。

| key | value |
|---|---|
| `tx_num_u64` | `block_num, tx_index, logical_type, phase, tx_id, result_code, write_count, state_root_status` |

字段说明：

- `tx_index`：普通交易在 block 中的 index；系统逻辑可用 `-1` 或单独 phase ordinal。
- `logical_type`：`GENESIS` / `USER_TX` / `BLOCK_END_SYSTEM` 等。
- `phase`：系统 tx 的细分类，如 maintenance、reward、resource settlement。
- `result_code`：普通交易执行结果。失败但上链的交易仍应有 txNum。
- `write_count`：用于诊断；零写入交易也合法。
- `state_root_status`：`NONE` / `BLOCK_ROOT_ONLY` / `TX_ROOT_STORED` / `TX_ROOT_DERIVABLE`。

### 4.3 表：`archive_txid_txnum`

保存用户交易 hash 到 txNum 的索引。

| key | value |
|---|---|
| `tx_id_32` | `tx_num, block_num, tx_index, result_code` |

要求：

- 只记录 canonical block 中的交易。
- reorg/unwind 时必须删除被回退区块内的 txId 映射。
- 如果 java-tron 历史上存在极端 txId 冲突处理规则，必须显式校验并拒绝产生歧义。

### 4.4 表：`archive_statepoint`

可以作为派生索引，也可以按需计算。PoC 阶段建议落表，降低 RPC 层复杂度。

| key | value |
|---|---|
| `state_point_key` | `as_of_tx_num, block_num, tx_num, flags` |

key 编码建议：

```text
0x01 || block_num_u64                  -> BLOCK_BEFORE
0x02 || block_num_u64                  -> BLOCK_END
0x10 || tx_id_32 || 0x00               -> TX_BEFORE
0x10 || tx_id_32 || 0x01               -> TX_AFTER
0x20 || block_num_u64 || phase_u16     -> SYSTEM_AFTER
0x7f                                   -> LATEST
```

### 4.5 表：`archive_txnum_progress`

保存构建进度和恢复信息。

| key | value |
|---|---|
| `canonical_height` | 已完整归档的最高 block |
| `canonical_block_id` | 该高度 block id |
| `next_tx_num` | 下一个将被分配的 txNum；也等于 `LATEST` 的 asOf |
| `archive_start_block` | archive 起始 block |
| `base_asof_tx_num` | 起始 block 前的状态边界 |
| `finalized_height` | 可 freeze 的最高 block |
| `schema_version` | txNum index schema version |

重启时必须校验 `canonical_height/canonical_block_id` 与 java-tron 当前 canonical chain 一致；不一致则先 unwind 到共同祖先。

## 5. Java 接口草案

接口只表达职责，不限制具体包名。

```java
public interface ArchiveTxNumIndex {
  BlockTxNumContext beginBlock(BlockCapsule block);

  Optional<BlockTxNumRange> getBlockRange(long blockNum);

  Optional<TxNumMeta> getByTxId(ByteString txId);

  Optional<TxNumMeta> getByTxNum(long txNum);

  long resolve(StatePoint statePoint);

  long latestAsOfTxNum();

  void commitBlock(BlockTxNumContext context);

  void unwindToBlock(long blockNumInclusive);
}
```

```java
public interface BlockTxNumContext {
  long blockNum();

  long blockStartAsOfTxNum();

  LogicalTx beginUserTx(TransactionCapsule tx, int txIndex);

  LogicalTx beginSystemTx(SystemPhase phase);

  BlockTxNumRange endBlock();
}
```

```java
public record LogicalTx(
    long txNum,
    long asOfBefore,
    long asOfAfter,
    long blockNum,
    int txIndex,
    LogicalTxType type,
    @Nullable ByteString txId,
    @Nullable SystemPhase phase) {
}
```

```java
public sealed interface StatePoint {
  record GenesisAfter() implements StatePoint {}
  record BlockBefore(long blockNum) implements StatePoint {}
  record BlockEnd(long blockNum) implements StatePoint {}
  record TxBefore(ByteString txId) implements StatePoint {}
  record TxAfter(ByteString txId) implements StatePoint {}
  record SystemAfter(long blockNum, SystemPhase phase) implements StatePoint {}
  record Latest() implements StatePoint {}
}
```

实现要求：

- `beginUserTx` 分配 txNum，但只有 `commitBlock` 成功后持久化并对查询可见。
- `asOfBefore == txNum`。
- `asOfAfter == txNum + 1`。
- `commitBlock` 必须和 archive domain write/history/root 写入处于同一个 archive DB transaction，或者使用可恢复的 two-phase commit。

## 6. 写入流程

推荐流程：

```text
beginBlock(block)
  -> validate parent equals archive progress
  -> blockStartAsOf = progress.nextTxNum

for each user tx in block order:
  logicalTx = beginUserTx(tx, txIndex)
  collector.beginTx(logicalTx)
  execute canonical tx
  collector.endTx()

for each block-end/system state transition:
  logicalTx = beginSystemTx(phase)
  collector.beginTx(logicalTx)
  execute system transition
  collector.endTx()

range = endBlock()
archiveTemporalStore.apply(all writeSets)
commitmentBuilder.compute/reroot if enabled
commitBlock(context)
```

关键约束：

- 同一 block 内，txNum 分配顺序必须和 canonical execution 顺序一致。
- `ArchiveWriteCollector` 使用 `LogicalTx.txNum` 写 before-value history。
- `ArchiveStateReader` 对 blockTag 使用 `BlockEnd(blockNum)`。
- 如果某个 `USER_TX` 没有任何 write，也必须保留 txNum meta 和 `TX_BEFORE/TX_AFTER` statepoint。
- 如果 block 没有用户交易但有 maintenance/system write，系统 write 必须消耗 txNum。
- 如果 block 完全没有 state transition，`block_end_asof = block_start_asof`；不强制消耗 txNum。

## 7. 回滚和重组

### 7.1 hot 区间回滚

`ArchiveTxNumIndex` 必须支持按 block 回退：

```text
unwindToBlock(targetBlock)
  -> 删除 block_num > targetBlock 的 archive_block_txnum
  -> 删除这些 block 的 archive_txnum_meta
  -> 删除这些 block 的 archive_txid_txnum
  -> 删除这些 block 的 archive_statepoint
  -> progress.canonical_height = targetBlock
  -> progress.next_tx_num = block_end_asof(targetBlock)
```

注意：`targetBlock` 是回退后仍保留的最高 block。

### 7.2 freeze 后限制

已 freeze 的 txNum range 不建议回滚。TRON 节点应只 freeze solid/finalized 区间：

```text
freeze_to_block <= solid_block_height - safety_margin
```

如果 canonical chain 回退超过 frozen boundary，archive sidecar 应进入保护模式：

- 停止继续写入。
- 报告需要重建 archive segment。
- 不静默删除冷 segment。

### 7.3 重启恢复

重启后：

1. 读取 `archive_txnum_progress`。
2. 查询 java-tron canonical chain 上 `canonical_height` 的 block id。
3. 如果 block id 一致，从 `next_tx_num` 继续。
4. 如果不一致，向前查共同祖先并执行 unwind。
5. 如果 archive domain history 与 txNum progress 不一致，拒绝启动 archive 写入，要求 repair/rebuild。

## 8. backfill 和增量启用

### 8.1 从 genesis replay

完整 archive 的首选方式：

- `txNum = 1` 写入 `GENESIS`。
- 从第一个可执行 block 开始连续分配 txNum。
- 所有历史查询都可以通过 `StatePoint` 解析。

### 8.2 从指定高度启用

适合 PoC 或运维低成本启用，但必须明确能力边界：

```text
archive_start_block = H
base_state = canonical state at H - 1
base_asof_tx_num = configured start boundary
```

限制：

- `H` 之前不能提供完整 historical state。
- `BLOCK_END(H - 1)` 可以映射到 base snapshot。
- `H` 之前的 `TX_BEFORE/TX_AFTER` 不存在。
- 如果继续支持历史余额，可以作为 legacy fallback，但不能混入完整 archive 语义。

### 8.3 replay 一致性

同一条 canonical chain replay 两次，必须得到：

- 相同的 `txId -> txNum`。
- 相同的 `blockNum -> block_end_asof`。
- 相同的 domain history key。
- 如果 commitment 开启，相同的 block-end root。

## 9. 与其他模块的接口

### 9.1 ArchiveWriteCollector

`ArchiveWriteCollector` 必须从 `ArchiveTxNumIndex` 获取 `LogicalTx`：

```text
collector.beginTx(logicalTx)
collector.record(domain, key, prevValue, newValue)
collector.endTx()
```

collector 不应自行分配 txNum。

### 9.2 ArchiveTemporalStore

`ArchiveTemporalStore` 使用 `logicalTx.txNum` 写 history：

```text
domain_history_vals(domain, key, changed_tx_num = logicalTx.txNum) -> prevValue
domain_latest(domain, key) -> newValue, last_tx_num = logicalTx.txNum
```

读取时使用 `ArchiveTxNumIndex.resolve(statePoint)` 得到 `asOfTxNum`。

### 9.3 CommitmentBuilder

`CommitmentBuilder` 应以 `StatePoint` 存 root：

```text
archive_state_root(BLOCK_END(blockNum)) -> root at block_end_asof
archive_state_root(TX_AFTER(txId))      -> optional root at txNum + 1
```

不要直接用 `txNum` 作为对外 root key，否则很容易混淆 before/after。

### 9.4 RPC 层

RPC 层只接受 `StatePoint`：

```text
eth_getBalance(addr, blockTag)
  -> StatePoint.BlockEnd(blockTag)
  -> asOfTxNum
  -> ArchiveStateReader.getAccount(asOfTxNum, addr)
```

`debug` 或交易级接口：

```text
state before tx = StatePoint.TxBefore(txId)
state after tx  = StatePoint.TxAfter(txId)
```

## 10. 边界场景

| 场景 | 期望行为 |
|---|---|
| 成功交易无状态写 | 消耗 txNum，write_count = 0，before/after state 相同 |
| 失败但上链交易 | 消耗 txNum，记录手续费/资源等最终状态变化 |
| VM 内部 REVERT | 临时 storage 写不进入 archive，最终落盘变化进入同一 txNum |
| 同一交易多次写同一 key | history 只保存第一次 before-value，latest 保存最终值 |
| 区块无交易无系统写 | 不消耗 txNum，block_end_asof = block_start_asof |
| 区块无交易但有 maintenance | maintenance 消耗 txNum |
| block apply 中途失败 | 不持久化 txNum index，不推进 progress |
| txId 查询不存在 | 返回 not found，不 fallback 到当前态 |
| 查询未来 block/tx | 返回明确错误，不使用 latest 替代 |

## 11. 测试计划

### 11.1 单元测试

- genesis txNum 初始化。
- 连续普通交易分配 txNum。
- `TX_BEFORE/TX_AFTER` 解析。
- `BLOCK_END` off-by-one：block 最后一笔 txNum=N，则 block end asOf=N+1。
- 空 block 不消耗 txNum。
- 系统 tx 分配和 phase 查询。
- txId 反查。
- txNum 反查 block。
- progress 重启恢复。
- unwind 后 `next_tx_num` 恢复。

### 11.2 集成测试

- 真实 block replay，校验 txNum 顺序等于 block 内交易顺序。
- 失败交易仍保留 txNum。
- block maintenance 写入后，`BLOCK_END` 读取到维护后的状态。
- fork/reorg：旧 canonical txId 被删除，新 canonical txId 映射正确。
- archive 从指定高度启用，历史边界之前查询返回 unsupported。

### 11.3 属性测试

对随机 block/tx/system event 序列验证：

- txNum 严格递增且无空洞，除非明确允许 reserved boundary。
- `asOfAfter(txNum) == txNum + 1`。
- `BLOCK_BEFORE(blockN+1) == BLOCK_END(blockN)`，除非中间有显式跨 block system phase。
- unwind 到任意 hot block 后，再 replay 同样 suffix，所有映射一致。

### 11.4 与 TemporalStore 联合测试

构造 key 连续变化：

```text
tx 10: K = A
tx 11: no-op
tx 12: K = B
```

验证：

```text
GetAsOf(K, TX_BEFORE(10)) = old
GetAsOf(K, TX_AFTER(10))  = A
GetAsOf(K, TX_AFTER(11))  = A
GetAsOf(K, TX_BEFORE(12)) = A
GetAsOf(K, TX_AFTER(12))  = B
```

这组测试能直接暴露 off-by-one 错误。

## 12. 验收标准

M1 级别验收：

- 对任意 canonical block，`ArchiveTxNumIndex` 可以返回 block start/end asOf。
- 对任意已上链交易，能返回 tx before/after asOf。
- 所有 statepoint 解析不需要 RPC 层手写 `+1`。
- block replay 结果可重复。
- hot 区间 reorg/unwind 后，txNum index 和 progress 一致。
- 不完整 archive 起始高度有明确 unsupported 错误。

M2/M3 级别验收：

- `ArchiveStateReader` 只依赖 `StatePoint`，不直接接受裸 `txNum`。
- `eth_getBalance`、`eth_getCode`、`eth_getStorageAt` 的 blockTag 全部解析为 `BLOCK_END`。
- 历史 `eth_call` 使用同一个 `StatePoint` 解析入口。

M4/M5 级别验收：

- block-end root 以 `BLOCK_END(blockNum)` 为 key。
- tx-level root 以 `TX_AFTER(txId)` 为 key。
- proof API 不暴露 before-tx 内部坐标。

## 13. 实现顺序建议

1. 定义 `StatePoint`、`LogicalTx`、`LogicalTxType`、`SystemPhase`。
2. 实现内存版 `ArchiveTxNumIndex`，只跑单元测试和 collector 联调。
3. 实现持久化表和 progress。
4. 接入 replay/canonical block apply，但先只记录 txNum，不写 domain history。
5. 接入 `ArchiveWriteCollector`，验证 write-set 带 txNum。
6. 接入 unwind。
7. 接入 RPC statepoint 解析。
8. 最后考虑 freeze/finalized 状态标记。

第一版不要急着做压缩和多盘；这个模块的数据量主要是按交易数增长，远小于 domain history。先把语义和回滚做对。
