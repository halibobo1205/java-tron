# java-tron Archive 模块 04：ArchiveTemporalStore 细化设计

日期：2026-05-21

关联总文档：[java-tron 交易级状态树支持：Erigon V2/V3 模型调研](./20260520-java-tron-archive-state-erigon-v2-v3-research.md)

源码对照深挖：[模块 04：ArchiveTemporalStore Erigon 源码对照深挖](./20260527-java-tron-module-04-temporal-store-erigon-source-deep-dive.md)

java-tron 源码对照：[模块 04 ArchiveTemporalStore：java-tron 源码对照](./20260601-java-tron-module-04-temporal-store-java-tron-source-deep-dive.md)

逐文件实现清单：[java-tron Archive 模块 04：ArchiveTemporalStore 逐文件 Patch 清单](./20260602-java-tron-archive-module-04-temporal-store-patch-checklist.md)

实现蓝图：[java-tron Archive 状态树实现蓝图](./20260602-java-tron-archive-implementation-blueprint.md)

前置模块：

- [模块 01：ArchiveTxNumIndex](./20260521-java-tron-archive-module-01-txnum-index.md)
- [模块 02：ArchiveDomainRegistry](./20260521-java-tron-archive-module-02-domain-registry.md)
- [模块 03：ArchiveWriteCollector](./20260521-java-tron-archive-module-03-write-collector.md)

## 1. 模块定位

`ArchiveTemporalStore` 是 archive 状态系统的核心持久化和时间旅行查询模块。它接收 `ArchiveWriteCollector` 输出的 `BlockWriteSet`，维护每个 domain key 的 latest value、历史 before-value、历史变化索引，并提供 `GetAsOf(domain, key, asOfTxNum)`。

如果前三个模块分别解决“什么时候”“写的是什么”“本交易改了什么”，`ArchiveTemporalStore` 解决的是：

```text
如何用最小增量持久化每个 txNum 的状态变化，并在任意历史 state point 读回当时可见的值。
```

这个模块参考 Erigon V3 的 temporal/domain/history/index 模型，但要适配 java-tron 的多 Store、多 domain、独立 archive sidecar 和 80T+ 数据规模。

## 2. 职责和非职责

职责：

- 持久化 domain latest value。
- 持久化 domain history before-value。
- 维护 key -> changed txNum 的 inverted index。
- 提供 `getLatest`、`getAsOf`、`rangeAsOf`、`prefixAsOf` 等读取能力。
- 以 block 为单位原子应用 `BlockWriteSet`。
- 支持 hot DB unwind。
- 支持 finalized 区间 freeze 成 cold segment。
- 在 apply 时校验 collector 提供的 `prevValue` 与 latest 一致。
- 提供 segment manifest、integrity check、repair/rebuild 辅助接口。

非职责：

- 不分配 txNum，这属于 `ArchiveTxNumIndex`。
- 不定义 domain/key/value codec，这属于 `ArchiveDomainRegistry`。
- 不截获 Store 写入，这属于 `ArchiveWriteCollector`。
- 不计算 state root，这属于 `CommitmentBuilder`。
- 不解析 RPC blockTag/txTag，这属于 `ArchiveStateReader`。

## 3. 设计目标

1. 时间语义准确。
   `GetAsOf(domain, key, asOfTxNum)` 返回 logical tx `asOfTxNum` 执行前的值。

2. 存储增量化。
   不保存每笔交易或每个区块的全量状态，只保存 changed key 的 before-value。

3. 查询可扩展。
   exact key 查询必须快；prefix/range 查询可以分阶段优化，但语义必须先正确。

4. 冷热分层。
   recent/finalization window 在 hot DB，可 unwind；旧历史 freeze 成不可变 segment。

5. 原子提交。
   一个 block 的 latest/history/index/progress 要么全部提交，要么全部不提交。

6. 可校验。
   apply 时校验 prevValue，freeze 后校验 checksum，重启时校验 progress。

## 4. 时间语义

`ArchiveTemporalStore` 使用 Erigon V3 风格的 before-tx 语义：

```text
GetAsOf(key, N) = logical tx N 执行之前 key 的值
```

假设：

```text
tx10: K old -> A
tx12: K A   -> B
```

查询结果：

```text
GetAsOf(K, 10) = old
GetAsOf(K, 11) = A
GetAsOf(K, 12) = A
GetAsOf(K, 13) = B
```

因此 `ArchiveStateReader` 必须先用 `ArchiveTxNumIndex.resolve(StatePoint)` 把 `BLOCK_END`、`TX_AFTER` 等外部语义转换成 `asOfTxNum`。`ArchiveTemporalStore` 不接收 `blockNum` 或 `txId`，只接收 `asOfTxNum`。

## 5. 数据模型

### 5.1 `archive_domain_latest`

当前 latest value。

| key | value |
|---|---|
| `domain_id_u16 || domain_key` | `last_tx_num_u64 || value_len || domain_value` |

说明：

- key 不存在表示当前 latest 中该 domain key 不存在。
- `domain_value` 是 Registry 输出的 canonical value，建议已包含 codec version。
- `last_tx_num` 是最后一次改变该 key 的 txNum。
- value 可以为空 bytes，所以不能用空 value 表示删除。

### 5.2 `archive_domain_history_vals`

历史 before-value。

| key | value |
|---|---|
| `domain_id_u16 || domain_key || changed_tx_num_u64` | `HistoryValueEnvelope` |

`changed_tx_num` 表示该 key 在这笔 logical tx 被改变。value 保存改变前的值。

`HistoryValueEnvelope`：

```text
state_u8:
  0x00 = NOT_EXISTS_BEFORE
  0x01 = EXISTS_BEFORE
payload_len_u32
payload_bytes
```

例子：

| 场景 | history value |
|---|---|
| key 创建 | `NOT_EXISTS_BEFORE` |
| key 更新 | `EXISTS_BEFORE(prev_value)` |
| key 删除 | `EXISTS_BEFORE(deleted_prev_value)` |
| key 原本存在且值为空 bytes | `EXISTS_BEFORE(empty_payload)` |

### 5.3 `archive_domain_history_idx`

每个 key 的变化 txNum 索引。

| key | value |
|---|---|
| `domain_id_u16 || domain_key` | compressed sorted txNum set |

要求：

- 支持 `seekFirstGreaterOrEqual(asOfTxNum)`。
- hot DB 可用 appendable delta 或小块 bitmap。
- cold segment 建议使用 Elias-Fano、RoaringBitmap 或类似压缩结构。
- index 与 history vals 必须一一对应。

### 5.4 `archive_domain_progress`

每个 domain 的应用进度。

| key | value |
|---|---|
| `domain_id_u16` | `latest_applied_tx_num, latest_applied_block, history_count, latest_count, frozen_to_tx_num` |

用于重启校验和 freeze 选择。

### 5.5 `archive_temporal_progress`

全局进度。

| key | value |
|---|---|
| `applied_block` | 已完整应用的最高 block |
| `applied_block_id` | block id |
| `applied_tx_num` | 已应用的最大 logical txNum |
| `next_asof_tx_num` | latest state 对应的 asOfTxNum，通常是 `applied_tx_num + 1` |
| `registry_checksum` | 当前 registry checksum |
| `txnum_progress_checksum` | txNum index progress checksum |

### 5.6 `archive_segment_manifest`

cold segment 元数据。

| key | value |
|---|---|
| `domain_id || from_tx_num || to_tx_num || segment_type` | path、checksum、codec versions、key count、history count、created time、status |

`segment_type`：

- `HISTORY_VALS`
- `HISTORY_IDX`
- `DOMAIN_LATEST_CHECKPOINT`
- `ACCESSOR`

## 6. 核心写入算法

### 6.1 applyBlock

输入：`BlockWriteSet`，其中 `TxWriteSet` 按 txNum 升序排列，`TxWrite` 按 `domainId/domainKey` 排序。

```text
applyBlock(blockWriteSet):
  begin archive db transaction

  validate block parent/progress

  for txWriteSet in blockWriteSet.txWriteSets:
    applyTx(txWriteSet)

  update domain progress
  update temporal progress
  commit archive db transaction
```

### 6.2 applyTx

```text
applyTx(txWriteSet):
  for write in txWriteSet.writes:
    current = latest.get(write.domainId, write.domainKey)

    assert current == write.prevValue

    historyVals.put(
      domainId,
      domainKey,
      changedTxNum = txWriteSet.txNum,
      encodeHistoryValue(write.prevValue)
    )

    historyIdx.add(domainId, domainKey, txWriteSet.txNum)

    if write.newValue == null:
      latest.delete(domainId, domainKey)
    else:
      latest.put(domainId, domainKey, lastTxNum=txWriteSet.txNum, value=write.newValue)
```

### 6.3 prevValue 校验

Collector 已经读取过 before-value，但 TemporalStore 仍必须校验：

- 如果 latest 与 `write.prevValue` 不一致，说明 collector overlay、archive latest 或 replay 顺序有 bug。
- strict 模式直接失败。
- observe 模式可以记录 divergence，但不能继续生成可信 root。

这个校验是防止 archive history 悄悄分叉的核心保护。

### 6.4 重复写保护

同一 txNum、同一 domain key 不应出现多条 write。TemporalStore 应拒绝：

```text
historyVals already has (domainId, domainKey, txNum)
```

这通常表示 Collector 去重失败或 replay 重复提交。

### 6.5 no-op

Collector 应过滤 final no-op。TemporalStore 如果收到 `prevValue == newValue`：

- strict 模式拒绝，提示 Collector bug。
- observe 模式跳过并记录指标。

## 7. GetAsOf 算法

### 7.1 exact key 查询

核心算法：

```text
getAsOf(domainId, domainKey, asOfTxNum):
  changedTxNum = historyIdx.seekFirstGreaterOrEqual(domainId, domainKey, asOfTxNum)

  if changedTxNum exists:
    hv = historyVals.get(domainId, domainKey, changedTxNum)
    if hv.state == NOT_EXISTS_BEFORE:
      return NOT_FOUND
    return hv.payload

  latest = domainLatest.get(domainId, domainKey)
  if latest exists:
    return latest.value

  return NOT_FOUND
```

解释：

- history 存的是“改变前值”。
- 要知道 `asOfTxNum` 时刻的值，需要找从 `asOfTxNum` 往后第一次变化。
- 如果未来某次变化的 before-value 是 A，则说明从 `asOfTxNum` 到那次变化前，该 key 的值都是 A。
- 如果找不到未来变化，说明当前 latest 就是 `asOfTxNum` 时的值。

### 7.2 创建/删除示例

```text
tx10: K absent -> A
tx20: K A      -> deleted
tx30: K absent -> B
```

history：

```text
(K, 10) = NOT_EXISTS_BEFORE
(K, 20) = EXISTS_BEFORE(A)
(K, 30) = NOT_EXISTS_BEFORE
latest(K) = B
```

查询：

```text
GetAsOf(K, 10) = NOT_FOUND
GetAsOf(K, 11) = A
GetAsOf(K, 20) = A
GetAsOf(K, 21) = NOT_FOUND
GetAsOf(K, 30) = NOT_FOUND
GetAsOf(K, 31) = B
```

### 7.3 查询未来状态

如果 `asOfTxNum > temporal_progress.next_asof_tx_num`：

- strict/default 行为：返回 `FUTURE_STATE` 错误。
- 不允许 fallback 到 latest。

如果 `asOfTxNum == next_asof_tx_num`，返回 latest state。

### 7.4 未启用 archive 的早期高度

如果 archive 从高度 `H` 启用，`ArchiveTxNumIndex` 对 `H` 前 state point 应返回 unsupported。TemporalStore 也应保存 `base_asof_tx_num`：

```text
asOfTxNum < base_asof_tx_num -> UNSUPPORTED_BEFORE_ARCHIVE_START
```

不要把 unknown 错误伪装成 key 不存在。

## 8. range/prefix 查询

### 8.1 需求

`rangeAsOf` 主要用于：

- 合约 storage prefix scan。
- 状态导出。
- segment 构建。
- proof/debug 工具。

ETH 标准接口 `eth_getStorageAt` 是 exact key 查询，但 java-tron 内部 debug 或 proof 可能需要 range。

### 8.2 朴素正确算法

PoC 可以先实现朴素算法：

```text
rangeAsOf(domainId, prefix, asOfTxNum):
  candidateKeys = union(
    latest.keysWithPrefix(domainId, prefix),
    historyIdx.keysWithPrefixHavingChangeAtOrAfter(domainId, prefix, asOfTxNum)
  )

  for key in sorted(candidateKeys):
    value = getAsOf(domainId, key, asOfTxNum)
    if value exists:
      emit key, value
```

这个算法语义正确，但成本较高。

### 8.3 优化方向

后续优化：

- 对 prefix 常用 domain 建 accessor segment。
- 对 `CONTRACT_STORAGE` 使用 `contractAddress21` 前缀的 domain-specific index。
- 在 cold segment 中维护 key range bloom/filter。
- range 查询按 limit 分页，禁止无界扫描拖垮节点。
- 对 root/proof 构建使用 segment iterator，而不是逐 key `GetAsOf`。

### 8.4 排序规则

range 输出顺序必须是 domain key 的字节序升序，不能受 Store 遍历顺序、Map 顺序或 DB backend 差异影响。

## 9. Hot/Cold 分层

### 9.1 hot DB

hot DB 保存：

- 所有 current latest。
- 最近 finalized window 内的 history vals/index。
- 尚未 freeze 的 progress。
- crash recovery marker。

hot DB 必须支持 unwind。

### 9.2 cold segment

cold segment 保存：

- old history vals。
- old history index。
- accessor。
- 可选 domain latest checkpoint。

segment 不可变，只能 append 新 segment 或 tombstone 整个 segment。不要在原地修改。

### 9.3 freeze 边界

freeze 只能发生在不可回滚区间：

```text
freeze_to_tx_num <= finalized_or_solid_asof_tx_num - safety_margin
```

如果 TRON solid block 能提供稳定 finality 边界，archive freeze 应以 solid block 为准，而不是 head block。

### 9.4 freeze 流程

```text
freezeDomain(domainId, fromTxNum, toTxNum):
  assert range is finalized
  scan hot history vals/index in range
  write segment files
  compute checksum
  write manifest status = PREPARED
  atomically mark manifest ACTIVE
  prune hot history for frozen range
  update domain frozen_to_tx_num
```

prune hot history 前必须保证 segment active 且 checksum 验证通过。

### 9.5 查询 hot + cold

`historyIdx.seekFirstGreaterOrEqual` 需要同时查：

- hot index。
- cold index segments whose range may contain txNum >= asOfTxNum。

返回最小的 changedTxNum。然后从对应 hot/cold historyVals 读取 before-value。

如果 changedTxNum 在 cold segment，而 value 文件缺失或 checksum 不匹配，返回 storage corruption 错误，不 fallback latest。

## 10. Unwind

### 10.1 hot unwind

目标：回退到 `targetAsOfTxNum`，通常对应 `BLOCK_END(targetBlock)`。

算法：

```text
unwindTo(targetAsOfTxNum):
  for each domain:
    find changes with changedTxNum >= targetAsOfTxNum in descending txNum order
    for each change:
      hv = historyVals.get(domain, key, changedTxNum)
      if hv.state == NOT_EXISTS_BEFORE:
        latest.delete(domain, key)
      else:
        latest.put(domain, key, lastTxNum = previousChangedTxNumBeforeTargetOrUnknown, value = hv.payload)
      delete historyVals(domain, key, changedTxNum)
      remove changedTxNum from historyIdx(domain, key)
  update progress
```

注意：latest 的 `lastTxNum` 可通过该 key 在 target 前最近一次变化推导；如果短期不需要，可在 unwind 后用 rebuild latest metadata 修复，但 latest value 必须正确。

### 10.2 frozen 区间限制

如果 `targetAsOfTxNum <= frozen_to_tx_num`：

- 默认拒绝 unwind。
- 需要离线重建或丢弃 frozen segment 后重放。

不要自动修改 cold segment。

### 10.3 与 TxNumIndex 协同

unwind 顺序建议：

1. 停止 archive 写入。
2. `ArchiveTemporalStore.unwindTo(targetAsOfTxNum)`。
3. `ArchiveTxNumIndex.unwindToBlock(targetBlock)`。
4. `CommitmentBuilder.unwindTo(targetStatePoint)`。
5. 更新统一 progress。

如果其中一步失败，必须进入 repair mode，不继续接新 block。

## 11. 原子性和恢复

### 11.1 apply marker

如果 archive DB 独立，TemporalStore 应支持 block apply marker：

| marker | 含义 |
|---|---|
| `TEMPORAL_PENDING(blockNum, blockId)` | 开始写 block |
| `TEMPORAL_WRITES_DONE` | latest/history/index 写完 |
| `TEMPORAL_PROGRESS_DONE` | progress 写完 |
| `TEMPORAL_COMMITTED` | block temporal apply 完成 |

重启时：

- `PENDING` 未完成：回滚该 block partial writes 或重新 apply。
- `COMMITTED`：校验 progress 与 txNum index。
- 中间状态：按 block id 和 txNum range 执行 idempotent repair。

### 11.2 幂等性

重复 apply 同一个 committed block 不应悄悄覆盖数据。建议：

- 如果 progress 已经超过该 block，拒绝重复 apply。
- 如果 marker 显示 pending，可先清理该 block txNum range 的 partial writes，再重新 apply。
- historyVals 重复 key 视为错误，除非处于显式 repair 模式。

## 12. Java 接口草案

```java
public interface ArchiveTemporalStore {
  Optional<VersionedValue> getLatest(short domainId, byte[] domainKey);

  AsOfResult getAsOf(short domainId, byte[] domainKey, long asOfTxNum);

  CloseableIterator<DomainKV> rangeAsOf(
      short domainId,
      byte[] fromKeyInclusive,
      byte[] toKeyExclusive,
      long asOfTxNum,
      int limit);

  void applyBlock(BlockWriteSet blockWriteSet);

  void unwindTo(long targetAsOfTxNum);

  SegmentManifest freezeDomain(short domainId, long fromTxNum, long toTxNum);

  TemporalProgress progress();

  IntegrityReport checkIntegrity(IntegrityScope scope);
}
```

```java
public record VersionedValue(
    long lastTxNum,
    byte[] value) {
}
```

```java
public sealed interface AsOfResult {
  record Found(byte[] value) implements AsOfResult {}
  record NotFound() implements AsOfResult {}
  record FutureState(long requested, long latestAsOf) implements AsOfResult {}
  record UnsupportedBeforeArchiveStart(long requested, long baseAsOf) implements AsOfResult {}
  record Corrupted(String reason) implements AsOfResult {}
}
```

```java
public record DomainKV(
    short domainId,
    byte[] domainKey,
    byte[] value) {
}
```

接口要求：

- `getAsOf` 不接受 block number 或 tx id。
- `rangeAsOf` 必须有 limit。
- `applyBlock` 内部必须验证 txNum 连续性和 progress。
- `checkIntegrity` 至少能检查 history index 与 history vals 一致。

## 13. 与其他模块的接口

### 13.1 ArchiveWriteCollector

Collector 输出 `BlockWriteSet`。TemporalStore 使用其中：

- `txNum`
- `domainId`
- `domainKey`
- `prevValue`
- `newValue`
- `historyPolicy`

TemporalStore 不解析 raw Store key/value。

### 13.2 ArchiveDomainRegistry

TemporalStore 启动时校验 registry checksum；写入时只信任 Registry 生成的 domain/value bytes。

如果 Registry schema 变化，TemporalStore 通过 segment manifest 和 value codec version 读取旧数据。

### 13.3 ArchiveTxNumIndex

TemporalStore progress 必须与 TxNumIndex progress 对齐：

```text
temporal.next_asof_tx_num == txnum.latestAsOfTxNum()
```

Reader 先通过 TxNumIndex resolve StatePoint，再调用 TemporalStore。

### 13.4 ArchiveStateReader

StateReader 是 TemporalStore 的主要读方：

```text
ArchiveStateReader.getAccount(statePoint, address)
  -> asOfTxNum = txNumIndex.resolve(statePoint)
  -> temporalStore.getAsOf(ACCOUNT, key, asOfTxNum)
```

### 13.5 CommitmentBuilder

CommitmentBuilder 不应从 TemporalStore 推断本 block 写集。它应直接消费 Collector 的 `BlockWriteSet`。TemporalStore 主要提供：

- root 重建时的 `rangeAsOf`。
- proof/debug 时的历史状态读取。
- integrity check。

## 14. PoC 范围

### 14.1 PoC v1

目标：exact historical reads。

实现：

- `archive_domain_latest`
- `archive_domain_history_vals`
- 简单 `archive_domain_history_idx`
- `getLatest`
- `getAsOf`
- `applyBlock`

覆盖 domain：

- `ACCOUNT`
- `CONTRACT_CODE`
- `CONTRACT_STORAGE`

不做：

- cold segment。
- 高性能 range。
- root rebuild。

### 14.2 PoC v2

目标：历史 `eth_call` 和 block 级 archive 稳定运行。

增加：

- `rangeAsOf` 朴素实现。
- `DYNAMIC_GLOBAL` / `CONTRACT_META` 支持。
- `unwindTo` hot window。
- integrity check。

### 14.3 PoC v3

目标：大规模 archive。

增加：

- freeze/cold segment。
- compressed history index。
- segment manifest/checksum。
- 多盘路径。
- range/accessor 优化。
- repair/rebuild 工具。

## 15. 边界场景

| 场景 | 期望行为 |
|---|---|
| key 创建 | history 写 `NOT_EXISTS_BEFORE`，latest 写新值 |
| key 删除 | history 写旧值，latest 删除 |
| key 更新为空 bytes | history 写旧值，latest 写空 bytes，不能当删除 |
| 查询创建前 | `NOT_FOUND` |
| 查询删除后 | 如果无后续创建，`NOT_FOUND` |
| 删除后重新创建 | 根据 asOfTxNum 返回正确的 absent/value |
| 查询未来 txNum | `FUTURE_STATE` 错误 |
| 查询 archive 起点前 | `UNSUPPORTED_BEFORE_ARCHIVE_START` |
| Collector prev mismatch | strict 失败 |
| history idx 有 txNum 但 vals 缺失 | corruption 错误 |
| cold segment checksum 错误 | corruption 错误，不 fallback latest |
| duplicate history key | apply 失败 |
| no-op write 进入 TemporalStore | strict 失败 |

## 16. Integrity Check

建议最小检查：

- 每个 history idx txNum 都存在对应 history vals。
- 每个 history vals 都能在 history idx 找到 txNum。
- 每个 domain progress 不超过 global progress。
- latest `last_tx_num` 不超过 global applied txNum。
- frozen segment manifest checksum 正确。
- hot history 没有落在已 frozen range 内，除非处于 repair 模式。
- registry checksum 与 manifest/schema 一致。

完整检查：

- 从 genesis 或 base snapshot replay BlockWriteSet，重建 latest，与当前 latest 比较。
- 对随机 key 抽样，比较 getAsOf 与 replay oracle。
- 对 segment range 做独立 verifier。

## 17. 性能和容量

### 17.1 写放大控制

- Collector 已过滤 no-op，TemporalStore 不再写 no-op。
- history idx 批量追加，按 block flush。
- latest/history/index 在同一 block transaction 中批量写。
- 对大 block 使用 sorted batch，减少底层 DB write amplification。

### 17.2 读性能

exact `GetAsOf` 目标路径：

```text
historyIdx.seekGE -> historyVals.get 或 latest.get
```

通常是 1 次 index 查询 + 1 次 value 查询。

### 17.3 存储估算

每次 domain key 改变至少产生：

- history value：prev payload + envelope。
- history index：txNum entry。
- latest 更新：当前 value 覆盖。

容量主要由高频 storage slot、account/resource 状态、系统维护写决定。需要通过 observe 模式统计每个 domain 的 write count 和 payload bytes，再估算 80T+ archive 的分布。

### 17.4 多盘

建议配置：

```text
archive.hot.path = fast SSD
archive.segments.history.paths = capacity disks
archive.segments.idx.paths = capacity/SSD mixed
archive.segments.accessor.path = SSD if range query heavy
```

manifest 必须记录实际文件路径，不能依赖目录扫描推断。

## 18. 测试计划

### 18.1 单元测试

- create/update/delete/recreate 的 `GetAsOf`。
- empty bytes value 与 delete 区分。
- `seekFirstGreaterOrEqual` 边界。
- future state 错误。
- archive start 前错误。
- duplicate history key 拒绝。
- prev mismatch 拒绝。
- no-op write 拒绝。
- TxWriteSet 顺序不影响最终 latest，但输出必须排序。

### 18.2 联合测试

与 Collector：

- 同 tx 多次写只进入一次 history。
- block 内前序 tx latest overlay 与 TemporalStore apply 一致。
- failed/revert/system tx 的 write-set 正确落库。

与 TxNumIndex：

- `BLOCK_END`、`TX_AFTER` 转 asOf 后查询结果正确。
- unwind block 后 TemporalStore 和 TxNumIndex progress 一致。

与 Registry：

- codec version 变化后旧值仍可读取。
- registry checksum 不一致时拒绝写入。

### 18.3 Freeze 测试

- freeze finalized range 后，hot history 被 prune，cold query 仍正确。
- freeze 前后 `GetAsOf` 结果一致。
- checksum 错误返回 corruption。
- frozen range 不允许 hot unwind。
- manifest 缺失或重复时启动失败。

### 18.4 属性测试

随机生成 key/value 变化序列，对比 replay oracle：

- 任意 asOfTxNum 的 `GetAsOf` 与 oracle 一致。
- 任意 delete/recreate 序列正确。
- freeze/unfreeze 前后结果一致。
- unwind 到任意 hot asOf 后再 replay suffix，latest 和 history 一致。

### 18.5 压测

- 高写入 storage slot block。
- 大账户 value。
- 大量 no-op 已过滤后的写放大。
- `eth_getStorageAt` exact query 延迟。
- prefix/range query limit 行为。
- freeze throughput 和 segment 文件大小。

## 19. 验收标准

M2 级别：

- PoC 三个 domain 支持 latest/history/index。
- `GetAsOf` 精确查询正确覆盖 create/update/delete/recreate。
- `eth_getBalance`、`eth_getCode`、`eth_getStorageAt` 可通过 StatePoint 查询历史值。
- applyBlock 原子提交，prev mismatch 能被发现。

M3 级别：

- hot unwind 可用。
- 历史 `eth_call` 所需 domain 可查询。
- 朴素 `rangeAsOf` 可用于 debug/导出。
- integrity check 可检测 idx/value 缺失。

M4/M5 级别：

- cold segment freeze 可用。
- hot + cold 查询结果一致。
- 多盘 manifest 可用。
- root rebuild/proof 工具可通过 `rangeAsOf` 或 segment iterator 获取历史状态。
- 大规模 replay 下 progress、checksum、segment 均可恢复。

## 20. 实现顺序建议

1. 定义 latest/history/index/progress 表结构。
2. 实现 `HistoryValueEnvelope`，明确 absent/empty/delete。
3. 实现内存版 TemporalStore，用属性测试验证 `GetAsOf`。
4. 实现持久化 latest 和 history vals。
5. 实现简单 history idx，支持 `seekFirstGreaterOrEqual`。
6. 接入 `BlockWriteSet.applyBlock`。
7. 增加 prev mismatch、duplicate、future state 校验。
8. 接入 `ArchiveStateReader` 的 exact 查询。
9. 实现 hot unwind。
10. 实现朴素 `rangeAsOf`。
11. 增加 integrity check。
12. 最后做 freeze/cold segment、压缩 index、多盘 manifest。

第一版不要先做复杂 segment 格式。TemporalStore 最重要的是把 before-value 历史语义、删除语义、asOf 边界和 block 原子提交做对；这些一旦写错，后续 root/proof 都会建立在错误历史上。
