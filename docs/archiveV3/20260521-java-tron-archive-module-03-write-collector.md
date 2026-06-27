# java-tron Archive 模块 03：ArchiveWriteCollector 细化设计

日期：2026-05-21

关联总文档：[java-tron 交易级状态树支持：Erigon V2/V3 模型调研](./20260520-java-tron-archive-state-erigon-v2-v3-research.md)

源码对照深挖：[模块 03：ArchiveWriteCollector Erigon 源码对照深挖](./20260526-java-tron-module-03-write-collector-erigon-source-deep-dive.md)

java-tron 源码对照：[模块 03 ArchiveWriteCollector：java-tron 源码对照](./20260601-java-tron-module-03-write-collector-java-tron-source-deep-dive.md)

逐文件实现清单：[java-tron Archive 模块 03：ArchiveWriteCollector 逐文件 Patch 清单](./20260602-java-tron-archive-module-03-write-collector-patch-checklist.md)

实现蓝图：[java-tron Archive 状态树实现蓝图](./20260602-java-tron-archive-implementation-blueprint.md)

前置模块：

- [模块 01：ArchiveTxNumIndex](./20260521-java-tron-archive-module-01-txnum-index.md)
- [模块 02：ArchiveDomainRegistry](./20260521-java-tron-archive-module-02-domain-registry.md)

## 1. 模块定位

`ArchiveWriteCollector` 是 archive 状态系统的写集采集模块。它位于 java-tron canonical execution 和 archive storage 之间，负责把每个 logical transaction 中真实落盘的状态变化收集成 `TxWriteSet`。

如果 `ArchiveTxNumIndex` 解决“什么时候”，`ArchiveDomainRegistry` 解决“写的是什么”，那么 `ArchiveWriteCollector` 解决“这笔交易最终改了哪些 domain key，以及这些 key 在交易前是什么值、交易后是什么值”。

它是交易级状态树的关键模块，因为交易级历史不是从区块末尾 diff 推导出来的。只记录 block final delta 会丢失区块内中间态，无法回答 `TX_AFTER(txId)`，也无法重放某笔交易前的状态。

## 2. 职责和非职责

职责：

- 在 canonical block apply 过程中，为每个 `LogicalTx` 建立采集上下文。
- 截获 canonical Store 的 put/delete 写入事件。
- 调用 `ArchiveDomainRegistry` 将 Store 写入映射为 `DomainWrite`。
- 对同一 logical tx 内的同一 `domainId + domainKey` 去重，保留交易前 before-value 和交易后 final-value。
- 处理 nested rollback、VM revert、failed tx、system tx 等执行语义。
- 生成按 txNum 排序的 `TxWriteSet` / `BlockWriteSet`。
- 将 write-set 提交给 `ArchiveTemporalStore` 和 `CommitmentBuilder`。
- 记录未注册 Store、被排除 Store、no-op 写入等审计信息。

非职责：

- 不分配 txNum，这属于 `ArchiveTxNumIndex`。
- 不决定 Store 到 domain 的映射，这属于 `ArchiveDomainRegistry`。
- 不持久化历史值和 latest state，这属于 `ArchiveTemporalStore`。
- 不计算 root，这属于 `CommitmentBuilder`。
- 不改变 java-tron 原有执行结果，只旁路采集 canonical writes。

## 3. 设计目标

1. 只采 canonical 写。
   pending transaction、local validation、RPC 预执行、fork block 预处理都不能进入 archive history。

2. 交易级。
   每个上链用户交易和系统 logical tx 都有独立 write-set，即使最终 write-set 为空。

3. 可回滚。
   java-tron 的 revoking/snapshot 回滚、VM revert、block apply 失败都必须能丢弃临时写。

4. 可去重。
   同一交易内同一 key 多次写，只记录一次 before-value 和最终 new-value。

5. 可审计。
   未注册 Store、excluded Store、no-op 写入、codec error 都要可观测。

6. 低侵入。
   优先挂在公共 Store 写入抽象或 revoking 层，不把 archive 逻辑散落到每个 actuator。

## 4. 核心概念

### 4.1 StoreWriteEvent

`StoreWriteEvent` 是 java-tron 原始 Store 写入事件：

```text
store_id
raw_key
raw_value
operation = PUT | DELETE
source = CANONICAL_BLOCK | GENESIS | MIGRATION | PRE_EXEC | RPC_CALL | REPAIR
block_num
logical_tx
sequence_no
```

Collector 只接受 source 属于 canonical/replay 的事件：

- `CANONICAL_BLOCK`
- `GENESIS`
- `MIGRATION`，仅用于明确的 archive migration/backfill
- `REPAIR`，仅用于离线修复工具

其他 source 必须被忽略或拒绝。

### 4.2 DomainWrite

`DomainWrite` 是 Registry 映射后的 archive 写入：

```text
domain_id
domain_key
new_value
operation = PUT | DELETE
root_policy
history_policy
key_codec_version
value_codec_version
source_store_id
```

`new_value = null` 表示 delete。删除 marker 不由 Collector 生成，后续由 `ArchiveTemporalStore` 在 history 中表达。

### 4.3 DomainWriteKey

Collector 去重的主键：

```text
DomainWriteKey = domain_id || domain_key
```

去重必须发生在 domain 层，而不是 Store 层。原因是一个 Store 写入可能映射多个 domain，不同 Store 也可能映射到同一个 domain key。

### 4.4 DomainWriteAccumulator

同一 logical tx 内，同一个 `DomainWriteKey` 的累积器：

```text
domain_id
domain_key
prev_value
final_value
first_sequence_no
last_sequence_no
root_policy
history_policy
source_events
```

规则：

- `prev_value` 只在第一次写该 domain key 时读取一次。
- 后续同 key 写入只更新 `final_value` 和 `last_sequence_no`。
- 交易提交时，如果 `final_value == prev_value`，这是 final no-op，不写 history，不触发 root，但可保留诊断计数。

### 4.5 TxWriteSet

每个 logical tx 输出一个 `TxWriteSet`：

```text
tx_num
block_num
tx_index
logical_type
tx_id
writes[]
noop_count
excluded_count
unmapped_count
error_count
```

`writes[]` 建议按 `domain_id ASC, domain_key ASC` 排序，保证后续 TemporalStore 和 CommitmentBuilder 的输入稳定。

### 4.6 BlockWriteSet

每个 block 输出一个 `BlockWriteSet`：

```text
block_num
block_id
parent_block_id
tx_write_sets_in_txnum_order
system_write_sets
collector_stats
```

`BlockWriteSet` 是 archive 写入事务的自然边界。PoC 阶段建议以 block 为单位提交 archive DB，避免 block apply 失败后出现半个 block 的 archive history。

## 5. 采集点选择

### 5.1 候选方案

| 方案 | 优点 | 问题 |
|---|---|---|
| 在每个 Actuator/VM 手动记录 | 业务语义清楚 | 改动分散，容易漏 Store，维护成本高 |
| 在底层 DB put/delete 截获 | 覆盖广 | 缺少 Store 语义，容易采到本地元数据和临时写 |
| 在公共 Store/Revoking 层截获 | Store 语义清楚，覆盖集中 | 需要处理 snapshot rollback 和 source 标记 |
| 在交易结束后读取 revoking diff | 最接近最终落盘结果 | 需要 revoking 层暴露完整 touched key/value，可能缺少 codec 所需上下文 |

推荐采用“公共 Store/Revoking 层截获 + logical tx scope + rollback-aware staging”的方案。

### 5.2 推荐挂载位置

优先挂载在 java-tron 的公共状态 Store 写入路径，例如 `TronStoreWithRevoking` / `ChainBase` / Store 基类一类的 put/delete 入口。实际落点需要以 java-tron 代码确认，但原则是：

- 必须能拿到 `store_id`。
- 必须能拿到 raw key 和 raw new value。
- 必须只在 canonical execution 上下文启用。
- 必须能感知 rollback 或在 rollback 后丢弃采集结果。
- 不能采集 block/header/transaction index 等非 state Store，除非 registry 明确标注 history-only。

不建议直接挂在底层 DB engine，因为那里很难区分 Store 语义，也容易采到 sync progress、本地缓存、索引表。

### 5.3 source gating

Collector 必须有明确的启用上下文：

```text
archiveCollectionContext.enterCanonicalBlock(block)
archiveCollectionContext.beginLogicalTx(logicalTx)
...
archiveCollectionContext.endLogicalTx()
archiveCollectionContext.leaveCanonicalBlock()
```

Store 写入时如果没有 active canonical context：

- observe 模式：忽略并计数。
- strict 模式：如果该写入声称来自 archive source 但无 context，直接失败。

这样可以避免 pending pool、RPC `triggerconstantcontract`、本地预执行污染 archive。

## 6. before-value 读取策略

### 6.1 为什么不能只读 Store old value

Store 层原始 old value 有两个问题：

- Registry 可能会把一个 Store value 拆成多个 domain value，raw old value 未必等于 domain old value。
- 同一 block 前序交易已经修改 archive latest；Collector 应以 archive domain latest 为 before-value 来源，而不是随手读当前 Java object。

因此建议：Collector 的 `prev_value` 以 `ArchiveTemporalStore.latest(domain, domainKey)` 为准；Store raw old value 只用于断言和诊断。

### 6.2 推荐流程

首次写某个 domain key：

```text
prev = archiveTemporalStore.getLatest(domainId, domainKey)
accumulator.prev_value = prev
accumulator.final_value = mapped.new_value
```

后续同一 tx 再写同 key：

```text
accumulator.final_value = mapped.new_value
```

交易提交后：

```text
if final_value == prev_value:
  skip history/root, keep no-op stats
else:
  emit TxWrite
```

### 6.3 与 block 内前序交易的关系

`ArchiveTemporalStore.latest` 必须在每个 tx commit 后反映该 tx 的 final writes，或者 Collector 在 block buffer 中维护一层 pending latest overlay。

PoC 推荐：

```text
tx commit:
  blockPendingLatest.apply(txWriteSet)

next tx prev lookup:
  first read blockPendingLatest
  then read ArchiveTemporalStore.latest
```

block 提交时再把所有 tx write-set 批量写入 `ArchiveTemporalStore`。这样能兼顾交易级 before-value 和 block 级原子性。

### 6.4 no-op 语义

场景：

```text
tx before: K = A
same tx:   K -> B -> A
tx after:  K = A
```

这笔交易对历史状态没有最终改变：

- `TX_BEFORE(tx)` 和 `TX_AFTER(tx)` 看到同一个值。
- 不需要写 domain history。
- 不需要触发 root。
- txNum 仍然存在，`TxWriteSet` 可以为空。

反例：

```text
tx1: K = A -> B
tx2: K = B -> A
```

tx2 不是 no-op，因为 tx2 的 before-value 是 B，after-value 是 A，必须写 history。

## 7. 回滚和嵌套 scope

### 7.1 transaction scope

每个 logical tx 有独立 scope：

```text
beginLogicalTx(logicalTx)
  record writes
commitLogicalTx()
```

如果交易执行异常且整笔交易未进入 canonical block：

```text
rollbackLogicalTx()
```

### 7.2 nested snapshot scope

java-tron 的执行中可能存在 nested snapshot/revoking 语义。Collector 应支持嵌套 scope：

```text
pushSnapshotScope()
  record writes
  pushSnapshotScope()
    record writes
  rollbackSnapshotScope()
  record writes
commitSnapshotScope()
```

实现方式：

- 每个 scope 持有自己的 event log 或 accumulator delta。
- commit 子 scope 时合并到父 scope。
- rollback 子 scope 时直接丢弃子 scope。

如果短期无法接入 revoking scope，必须在 transaction finalize 后从最终 touched keys 重建 write-set，不能把已回滚的中间写误写进 archive。

### 7.3 VM revert

TVM 内部 `REVERT` 或执行失败时：

- 临时 storage/account 写必须回滚，不进入 TxWriteSet。
- 手续费、资源扣减、receipt 相关最终写如果 canonical 落盘，应进入对应 logical tx。
- 如果失败交易完全没有 state domain writes，仍保留 txNum 和空 TxWriteSet。

这要求 Collector 不能简单“看到 Store put 就永久记录”。写入必须先在可回滚 staging 里，等 transaction final state 确认后再输出。

### 7.4 block apply 失败

如果 block apply 中途失败：

- 丢弃整个 `BlockWriteSet`。
- 不推进 `ArchiveTxNumIndex` progress。
- 不写 `ArchiveTemporalStore`。
- 不写 root。

Archive 不能出现半个 block 的 history。

## 8. 系统写和维护写

TRON 的状态变化不只来自用户交易。Collector 必须支持系统 logical tx：

```text
beginSystemTx(BLOCK_END_REWARD)
beginSystemTx(MAINTENANCE)
beginSystemTx(RESOURCE_SETTLEMENT)
beginSystemTx(GOVERNANCE_APPLY)
```

原则：

- 任何会改变 canonical state 的系统逻辑都必须在某个 `SystemPhase` 下采集。
- phase 顺序必须稳定，纳入 `ArchiveTxNumIndex`。
- 系统写与用户交易一样生成 TxWriteSet。
- block-end root 使用所有系统写之后的 `BLOCK_END(blockNum)`。

如果系统写和用户交易交错发生，应按实际 canonical execution 顺序分配 txNum，而不是强行放到 block 末尾。

## 9. 运行模式

### 9.1 observe 模式

用于盘点和 PoC 初期：

- 未注册 Store 写入只记录告警和统计。
- excluded Store 写入计数。
- codec error 可配置为告警或失败。
- 不输出 complete root，只能输出 partial/diagnostic root。

适合发现 java-tron 真实执行路径中还有哪些 Store 被修改。

### 9.2 strict 模式

用于 root/replay 验证：

- 未注册 canonical Store 写入直接失败。
- root domain codec error 直接失败。
- root domain latest-only 配置直接失败。
- source/context 不合法直接失败。

strict 模式是声称“完整 archive state root”前的必要条件。

### 9.3 disabled 模式

Archive 未启用时：

- Store 写路径不应有明显额外开销。
- Collector hook 应快速返回。
- 不应访问 Registry、TemporalStore 或 root builder。

## 10. 数据结构草案

```java
public interface ArchiveWriteCollector {
  BlockCollection beginBlock(BlockCapsule block, BlockTxNumContext txNumContext);

  void onStorePut(StoreId storeId, byte[] rawKey, byte[] rawValue);

  void onStoreDelete(StoreId storeId, byte[] rawKey);

  void pushSnapshotScope();

  void commitSnapshotScope();

  void rollbackSnapshotScope();
}
```

```java
public interface BlockCollection {
  TxCollection beginLogicalTx(LogicalTx logicalTx);

  BlockWriteSet endBlock();

  void rollbackBlock();
}
```

```java
public interface TxCollection {
  LogicalTx logicalTx();

  void record(StoreWriteEvent event);

  TxWriteSet commit();

  void rollback();
}
```

```java
public record TxWriteSet(
    long txNum,
    long blockNum,
    int txIndex,
    LogicalTxType logicalType,
    @Nullable ByteString txId,
    List<TxWrite> writes,
    CollectorStats stats) {
}
```

```java
public record TxWrite(
    short domainId,
    byte[] domainKey,
    @Nullable byte[] prevValue,
    @Nullable byte[] newValue,
    RootPolicy rootPolicy,
    HistoryPolicy historyPolicy,
    int firstSequenceNo,
    int lastSequenceNo) {
}
```

```java
public record CollectorStats(
    int rawStoreWriteCount,
    int mappedDomainWriteCount,
    int emittedWriteCount,
    int noopWriteCount,
    int excludedWriteCount,
    int unmappedWriteCount,
    int rollbackWriteCount,
    int codecErrorCount) {
}
```

注意：

- `TxWrite.prevValue` 可由 Collector 填好，也可只填 `newValue`，由 `ArchiveTemporalStore` 在 apply 阶段读取 latest。推荐 Collector 填好，TemporalStore 再做一致性断言。
- `prevValue == null` 表示 tx 前 key 不存在。
- `newValue == null` 表示 tx 后 key 被删除。

## 11. 核心算法

### 11.1 recordStoreWrite

```text
recordStoreWrite(event):
  if collector disabled:
    return

  if no active canonical context:
    ignore or fail by mode

  mappings = registry.resolveStoreWrite(event)

  if mappings is empty:
    stats.excluded/unmapped += 1
    return

  for mapping in mappings:
    domainKey = mapping.domainId || mapping.domainKey
    acc = currentScope.find(domainKey)

    if acc does not exist:
      prev = pendingLatest.get(mapping.domainId, mapping.domainKey)
      if prev not found:
        prev = temporalStore.getLatest(mapping.domainId, mapping.domainKey)
      acc = newAccumulator(prev)

    acc.finalValue = mapping.domainValue
    acc.lastSequenceNo = event.sequenceNo
    acc.mergePolicy(mapping.rootPolicy, mapping.historyPolicy)
```

### 11.2 commitLogicalTx

```text
commitLogicalTx():
  writes = []

  for acc in accumulators:
    if bytesEqual(acc.prevValue, acc.finalValue):
      stats.noop += 1
      continue

    writes.add(TxWrite(acc))
    pendingLatest.putOrDelete(acc.domainId, acc.domainKey, acc.finalValue)

  sort writes by domainId, domainKey
  return TxWriteSet(logicalTx, writes, stats)
```

### 11.3 apply block

```text
endBlock():
  assert logical tx scopes closed
  assert tx write sets sorted by txNum
  return BlockWriteSet

archive commit:
  temporalStore.apply(blockWriteSet)
  commitmentBuilder.apply(blockWriteSet)
  txNumIndex.commitBlock()
```

这三个操作必须保持原子性。PoC 可以用单 DB transaction；如果 archive DB 与 java-tron chain DB 分离，则需要可恢复的 commit marker。

## 12. 原子性和恢复

### 12.1 单 DB transaction

如果 archive tables 和 collector metadata 在同一个 DB transaction 中：

```text
begin archive tx
  write temporal history
  write latest
  write roots
  write txnum progress
commit archive tx
```

失败时整体回滚。

### 12.2 独立 archive DB

如果 archive sidecar 使用独立 DB，需要 commit marker：

| 阶段 | 标记 |
|---|---|
| 开始写 block | `BLOCK_ARCHIVE_PENDING(blockNum, blockId)` |
| temporal writes 完成 | `TEMPORAL_APPLIED` |
| root 完成 | `ROOT_APPLIED` |
| txnum progress 完成 | `BLOCK_ARCHIVE_COMMITTED` |

重启时：

- `PENDING` 且未 committed：删除该 block archive 写入或重新 apply。
- `COMMITTED`：校验 progress 和 block id。
- 中间状态：按 idempotent block write-set 重放或回滚。

Collector 本身可以不持久化原始 event，但 block write-set 重放必须可从 canonical chain 再生成。

## 13. 与其他模块的接口

### 13.1 ArchiveTxNumIndex

Collector 只接受 `LogicalTx`，不自行生成 txNum：

```text
logicalTx = txNumContext.beginUserTx(tx, txIndex)
collector.beginLogicalTx(logicalTx)
```

如果没有 logical tx context，collector 不应采集。

### 13.2 ArchiveDomainRegistry

Collector 所有 raw Store writes 都必须经过 Registry：

```text
StoreWriteEvent -> DomainMapping[] -> DomainWriteAccumulator
```

Collector 不应内置 Store 到 domain 的硬编码映射。

### 13.3 ArchiveTemporalStore

Collector 输出：

```text
BlockWriteSet(txNum ordered)
```

TemporalStore 负责：

- 写 `domain_history_vals`。
- 写 `domain_history_idx`。
- 更新 `domain_latest`。
- 校验 `prevValue` 与 latest 一致。

### 13.4 CommitmentBuilder

CommitmentBuilder 只处理 root domain：

```text
for write in TxWriteSet.writes:
  if write.rootPolicy requires root:
    touch(domainId, domainKey, newValue)
```

history-only domain 不应改变 global root。

### 13.5 ArchiveStateReader

Collector 不直接服务查询。但 Collector 的排序、before-value 和 no-op 语义会决定 Reader 的历史正确性。

## 14. 边界场景

| 场景 | 期望行为 |
|---|---|
| 同一 tx 内 K: A -> B -> C | 记录 prev=A, new=C |
| 同一 tx 内 K: A -> B -> A | no-op，不写 history/root |
| tx1 K: A -> B, tx2 K: B -> A | 两笔 tx 都写 history |
| delete 不存在的 key | no-op，除非业务 Store 将其表示为有效状态 |
| create 后同 tx delete | no-op |
| delete 后同 tx create | prev=旧值, new=新值 |
| failed tx 扣手续费 | 记录手续费/资源最终写 |
| VM revert storage | reverted storage 写不进入 TxWriteSet |
| 未注册 Store | observe 告警；strict 失败 |
| excluded Store | 不输出 DomainWrite，但计数 |
| system maintenance 写 | 使用 system logical tx 采集 |
| block apply 失败 | 丢弃整个 BlockWriteSet |
| archive latest 与 collector prev 不一致 | strict 失败，提示 archive state divergence |

## 15. 性能设计

### 15.1 快路径

Archive disabled 时：

```text
if (!archiveEnabled) return;
```

不要做 StoreId 构造、byte copy、registry lookup。

### 15.2 缓存

建议缓存：

- StoreId -> StoreBinding。
- domain key codec 实例。
- domain latest lookup 的 tx 内 first-write 结果。
- excluded Store fast path。

不能缓存会随 txNum 变化的 latest value，除非缓存作用域是 block pending overlay。

### 15.3 内存控制

Block buffer 可能在大区块或批量回放时增长。建议：

- 按 tx 提交到 block buffer，释放 raw Store event。
- 只保留 dedup 后 TxWriteSet，不保留全部中间事件。
- diagnostics 可采样或按开关记录。
- 单 block write-set 超过阈值时 spill 到临时文件。

### 15.4 no-op 优化

最终 no-op 不写 history/root，可以显著降低写放大。但 no-op 判断必须在 domain canonical value 层做，不能只比较 raw Store value。

## 16. 可观测性

建议指标：

| 指标 | 说明 |
|---|---|
| `archive_collector_raw_store_writes` | 原始 Store 写入数 |
| `archive_collector_domain_writes` | Registry 映射后的 domain 写入数 |
| `archive_collector_emitted_writes` | 去重/no-op 后输出写入数 |
| `archive_collector_noop_writes` | final no-op 数 |
| `archive_collector_unmapped_store_writes` | 未注册 Store 写入数 |
| `archive_collector_excluded_store_writes` | 明确排除 Store 写入数 |
| `archive_collector_rollback_writes` | rollback 丢弃写入数 |
| `archive_collector_codec_errors` | codec 错误数 |
| `archive_collector_block_buffer_bytes` | block buffer 估算内存 |

建议日志：

- strict 模式下第一个 unmapped Store 直接给出 storeId、block、txId。
- observe 模式按 storeId 聚合输出，不要每次写都刷日志。
- block commit 输出 write-set 摘要：tx count、domain write count、noop count、unmapped count。

## 17. PoC 范围

### 17.1 PoC v1

目标：支撑三类 ETH 历史查询。

采集 domain：

- `ACCOUNT`
- `CONTRACT_CODE`
- `CONTRACT_STORAGE`

要求：

- 每个 user tx 都生成 TxWriteSet。
- 同一 tx 内去重。
- block 内前序 tx 写入对后续 tx before-value 可见。
- failed/revert 基础场景正确。
- observe 模式记录其他 Store 写入，为后续 inventory 服务。

### 17.2 PoC v2

目标：支撑历史 `eth_call`。

增加：

- `CONTRACT_META`
- `DYNAMIC_GLOBAL`
- TVM/资源参数相关 Store。
- 更完整的 system tx phase。

重点验证：

- 历史 blockTag 的 VM reader 不读当前全局参数。
- 系统写后的 block-end 状态可查询。

### 17.3 PoC v3

目标：完整 archive root。

要求：

- strict 模式无 unmapped canonical Store。
- 所有 root domain write-set 都进入 collector。
- replay 相同区间得到相同 DomainWrite 序列。
- 与 `CommitmentBuilder` 生成稳定 block-end root。

## 18. 测试计划

### 18.1 单元测试

- `beginLogicalTx` 无 active block 失败。
- Store write 无 active tx 在 strict 模式失败。
- 同一 domain key 多次写去重。
- `A -> B -> A` final no-op。
- create/delete/delete-create 语义。
- excluded Store 不输出 DomainWrite。
- unmapped Store observe/strict 行为。
- Registry 一写多 domain。
- 多 Store 映射同一 domain key。
- pending latest overlay 可被下一 tx 读取。
- TxWriteSet 排序稳定。

### 18.2 rollback 测试

- nested scope commit 后父 scope 可见。
- nested scope rollback 后写入消失。
- VM revert storage 不进入 TxWriteSet。
- failed tx 的手续费/资源写保留。
- block apply 失败丢弃整个 BlockWriteSet。

### 18.3 集成测试

- replay 一个包含多笔交易的 block，校验每笔 tx 的 write-set 独立。
- 同一账户在同一 block 多笔交易中连续变化，历史 `TX_AFTER` 正确。
- 合约 storage 在 tx 内多次修改，最终状态正确。
- maintenance/system 写产生独立 logical tx。
- reorg/unwind 后重新 replay，collector 输出一致。

### 18.4 属性测试

随机生成 Store 写入序列和 nested rollback：

- 输出 writes 等价于最终每个 domain key 的状态差异。
- 每个 emitted write 的 `prevValue` 等于 tx 开始状态。
- final no-op 不输出。
- rollback scope 写入不影响输出。
- 排序与事件输入顺序无关。

### 18.5 与 TemporalStore 联测

构造：

```text
tx10: K old -> A
tx11: K A -> B -> A
tx12: K A -> C
```

验证：

```text
GetAsOf(K, TX_BEFORE(tx10)) = old
GetAsOf(K, TX_AFTER(tx10))  = A
GetAsOf(K, TX_AFTER(tx11))  = A
GetAsOf(K, TX_AFTER(tx12))  = C
```

### 18.6 与 CommitmentBuilder 联测

- root domain write 触发 touch。
- no-op write 不触发 touch。
- history-only domain 不触发 global root。
- 同一 tx 多次写同 key 只触发最终一次。

## 19. 验收标准

M1 级别：

- Collector 能以 `LogicalTx` 为单位输出 TxWriteSet。
- PoC 三个 domain 的 put/delete 能被采集。
- 同 tx 去重、no-op、delete/create 语义正确。
- observe 模式能输出未注册 Store 统计。
- block apply 失败不会留下 archive 写。

M2/M3 级别：

- failed/revert/system tx 场景正确。
- block 内前序 tx 写入对后续 tx before-value 可见。
- 历史 `eth_getBalance`、`eth_getCode`、`eth_getStorageAt` 的数据来自 TxWriteSet。
- 历史 `eth_call` 所需 Store 被采集。

M4/M5 级别：

- strict 模式无 unmapped canonical Store。
- replay 相同区间，TxWriteSet bytes 完全一致。
- Collector 输出能稳定驱动 block-end root。
- tx-level root/proof 能基于 `TX_AFTER(txId)` 的 TxWriteSet 重建。

## 20. 实现顺序建议

1. 定义 `StoreWriteEvent`、`DomainWriteAccumulator`、`TxWriteSet`、`BlockWriteSet`。
2. 实现 disabled/observe/strict 三种模式。
3. 在公共 Store put/delete 入口接入 hook，但先只 observe，不写 archive。
4. 接入 `ArchiveDomainRegistry`，生成 PoC 三个 domain 的 DomainWrite。
5. 实现 per-tx accumulator 和 no-op 去重。
6. 实现 block pending latest overlay。
7. 接入 logical tx lifecycle：user tx、system tx、genesis。
8. 接入 nested rollback 或交易结束最终 diff，确保 reverted writes 不输出。
9. 输出 BlockWriteSet 给 `ArchiveTemporalStore`。
10. 接入 metrics 和 unmapped Store inventory 报告。
11. strict 模式下跑 replay 一段区块，修完所有 unmapped root Store。
12. 再接 `CommitmentBuilder` 做 root touch。

第一版不要把 collector 写成 root builder，也不要把 Store 到 domain 的映射硬编码在 collector 里。Collector 的边界越清晰，后续 TemporalStore、StateReader、CommitmentBuilder 才能独立测试和替换。
