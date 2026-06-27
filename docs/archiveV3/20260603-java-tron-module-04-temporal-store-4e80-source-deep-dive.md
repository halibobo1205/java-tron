# 模块 04 ArchiveTemporalStore：4e80 java-tron 源码对照细化

> ⚠️ **部分内容已被冻结契约取代**：本文的物理表前缀 / 历史键序（`0x01-0x07 + txNumDesc` 降序）**已废弃**；权威实现以 **L5** 为准（`LATEST=0x20 / HISTORY=0x21 / CHANGESET=0x22 / ROOT_*=0x30-0x32`，**升序 txNum**，seek=`historyKey(txNum+1)`）。详见 [00-ARCHIVE-V3-AUTHORITY-AND-DECISIONS.md](00-ARCHIVE-V3-AUTHORITY-AND-DECISIONS.md) §2。

日期：2026-06-03

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

关联总表：[java-tron Archive：4e80 六模块源码对照细化](./20260603-java-tron-archive-4e80-six-modules-source-detail.md)

上游模块：[模块 01 ArchiveTxNumIndex](./20260603-java-tron-module-01-txnum-index-4e80-source-deep-dive.md)、[模块 02 ArchiveDomainRegistry](./20260603-java-tron-module-02-domain-registry-4e80-source-deep-dive.md)、[模块 03 ArchiveWriteCollector](./20260603-java-tron-module-03-write-collector-4e80-source-deep-dive.md)

编码执行包：[java-tron Archive S6/S7：ArchiveTemporalStore 4e80 编码执行包](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md)

代码级执行包：[java-tron Archive L5：ArchiveTemporalStore 代码级执行包](./20260605-java-tron-archive-l5-temporal-store-4e80-code-plan.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`，`git status --short` 为空，精确冲突标记扫描无命中。

## 1. 当前结论

`ArchiveTemporalStore` 是 archive sidecar 的持久化核心。它接收 Module 03 输出的 `BlockWriteSet/TxWriteSet`，把每个 domain key 的变化写成 Erigon V3 式 temporal 数据：

```text
latest(domain,key)      -> 当前 canonical value
history(domain,key,tx)  -> tx 发生前的 before value
changeset(tx,domain,key)-> tx 修改了哪些 key
txnum(block)            -> block 到 txNum range
progress                -> archive 已提交到哪个 block/txNum
roots(block)            -> Module 06 sidecar root
```

java-tron 当前的 `SnapshotManager`、revoking DB、`BalanceTraceStore` 都不能替代它：

| 现有机制 | 能力 | 为什么不能替代 temporal store |
| --- | --- | --- |
| `SnapshotManager` / revoking DB | recent execution rollback and fork unwind | 不保留长期 before-values，不提供 `getAsOf(txNum)` |
| `BalanceTraceStore` / `AccountTraceStore` | 余额历史辅助 | 只覆盖余额，不覆盖 account/code/contract/storage/dynamic properties |
| `TransactionStore` | `txId -> blockNum` | 没有 txIndex、phase、global txNum |

P0 推荐一个独立 archive sidecar DB，通过有序 key prefix 分 logical table，先不实现 Erigon 的 immutable segment/freezer，但 key schema 必须为后续 freeze 留出 txNum range 边界。

## 2. Erigon 对照

Erigon temporal API 的关键不是“每个 block 拷贝全状态”，而是 latest + history + inverted index：

| Erigon 源码 | 语义 | java-tron 映射 |
| --- | --- | --- |
| `db/kv/temporal/kv_temporal.go:541/545` | `GetLatest(domain,key)` | `getLatest(domain,key)` |
| `kv_temporal.go:565/569` | `HistorySeek(domain,key,txNum)` | `getAsOf(domain,key,asOfTxNum)` |
| `kv_temporal.go:490/494` | `RangeAsOf(domain,from,to,txNum)` | P1 range/prefix historical scan |
| `kv_temporal.go:582/586` | `IndexRange` 通过 inverted index 找 changed txNums | `changedKeys` / `changedTxNums` |
| `kv_temporal.go:757-758` | `Unwind(txNumUnwindTo, changeset)` | fork/reorg 时按 txNum range 回退 latest/history |

Erigon 的 `DomainPut` 在 Module 03 已经被翻译成 `DomainWrite(firstBefore, finalAfter)`。Module 04 要做的是把这些 write set 按 txNum 写成可读、可回退、可重建 root 的 sidecar 数据。

## 3. java-tron batch 能力

当前 java-tron DB 抽象已经支持批量写：

| java-tron 源码 | 当前事实 | temporal 意义 |
| --- | --- | --- |
| `BatchSourceInter.java:25-29` | `updateByBatch(Map<K,V>)` 和带 `WriteOptionsWrapper` 的重载 | archive 可以同批写多 logical table |
| `DbSourceInter.java:32` | DB source 继承 batch source | LevelDB/RocksDB 都走同一接口 |
| `TronDatabase.java:63-64` | wrapper 调 `dbSource.updateByBatch(rows, writeOptions)` | 可封装 `ArchiveDatabase` |
| `LevelDbDataSourceImpl.java:404-418` | batch 中 `value == null` 时 `batch.delete(key)` | unwind/delete 可用 null tombstone 落底层 delete |
| `RocksDbDataSourceImpl.java:301-310` | RocksDB batch 同样 `null -> delete` | LevelDB/RocksDB 语义一致 |
| `LevelDbDataSourceImpl.java:366-383` | 支持 `prefixQuery(byte[] key)` | P0 可做 prefix scan，但大量 history 需要更窄 range seek |
| `RocksDbDataSourceImpl.java:381-395` | RocksDB 同样支持 prefix query | changed-key/history 扫描可复用或包装 iterator |

P0 可以先使用单物理 DB + prefix key。不要把 archive history 放进 revoking DB session 内，因为 archive history 不应被 recent snapshot flush/retreat 策略清理。

## 4. 物理表设计

推荐一个 archive sidecar DB，多个 logical prefix：

| Prefix | logical table | key | value |
| --- | --- | --- | --- |
| `0x01` | `latest` | `domainId || canonicalKey` | `ArchiveStoredValue(value/tombstone, lastTxNum)` |
| `0x02` | `history` | `domainId || canonicalKey || txNumDesc` | `beforeValue/tombstone` |
| `0x03` | `changeset` | `txNumAsc || domainId || canonicalKey` | `before/after metadata or pointer` |
| `0x04` | `txnum` | `blockNumAsc` | `firstTxNum,lastTxNum,finalizeTxNum,userTxCount,blockHash` |
| `0x05` | `progress` | singleton keys | `appliedBlockNum,appliedBlockHash,nextTxNum,schemaVersion` |
| `0x06` | `roots` | `blockNumAsc` | Module 06 sidecar root |
| `0x07` | `txid` | `txId` | `txNum,blockNum,txIndex` |

关键编码：

```text
txNumAsc  = unsigned 8-byte big-endian
txNumDesc = bitwiseNot(txNumAsc) or Long.MAX_VALUE - txNum encoded big-endian
blockNumAsc = unsigned 8-byte big-endian
domainId = 1-byte or varint stable id from ArchiveDomainRegistry
```

`history` 使用 `txNumDesc` 是为了 `getAsOf` 快速找到“asOf 之后的第一条变化”：

```text
prefix = 0x02 || domainId || canonicalKey
seekKey = prefix || desc(asOfTxNum + 1)
scan prefix first match
```

如果实现复杂，P0 也可以用 asc txNum + iterator seek upper-bound helper，但必须在 API 中保持 `getAsOf` 语义不变。

## 5. applyBlock 写入语义

输入：

```text
BlockWriteSet:
  blockNum
  blockHash
  firstTxNum
  lastTxNum
  finalizeTxNum
  txWriteSets[]
```

对每个 `DomainWrite(domain,key,before,after,txNum)`：

```text
if before == after:
  skip canonical write, increment no-op diagnostic
else:
  batch.put(historyKey(domain,key,txNum), before)
  batch.put(changesetKey(txNum,domain,key), encodedChange(before,after))
  if after is tombstone:
      batch.delete(latestKey(domain,key)) or batch.put(latest tombstone)
  else:
      batch.put(latestKey(domain,key), encodedLatest(after, txNum))
```

最后同一个 batch 写：

```text
batch.put(txnumKey(blockNum), block range)
batch.put(progressKey, new progress)
batch.put(optional rootKey(blockNum), sidecar root)
```

`latest/history/changeset/txnum/progress/root` 必须同 batch。否则崩溃后可能出现 latest 已变化但 progress 未推进，或 progress 推进但 history 不完整。

## 6. getAsOf 语义

`asOfTxNum` 建议解释为“读 txNum 执行后的状态”还是“读 txNum 执行前的状态”必须全项目统一。为方便 JSON-RPC block selector，推荐 Module 05 对外使用 block finalize txNum，TemporalStore 内部提供清晰命名：

```text
getAfterTx(domain,key,txNum)
getBeforeTx(domain,key,txNum)
```

P0 可以统一实现为：

```text
getAsOf(domain, key, targetTxNum):
  latest = latest(domain,key)
  if latest.lastTxNum <= targetTxNum:
      return latest.value
  hist = first history entry for domain/key with changeTxNum > targetTxNum
  if hist exists:
      return hist.beforeValue
  return latest.value
```

例子：

```text
tx10: A 100 -> 70
tx11: A 70  -> 50
latest(lastTxNum=11): A 50

getAsOf(A, 9)  = 100
getAsOf(A, 10) = 70
getAsOf(A, 11) = 50
```

如果要查询 tx 执行前状态，用 `getBeforeTx(A, 10)` 转换为 `getAsOf(A, 9)`，不要让调用方猜 offset。

## 7. changedKeys 与 range

`changeset` 是 unwind、debug、root rebuild 的核心索引。

基本 API：

```text
changedKeys(fromTxNumInclusive, toTxNumExclusive)
changedKeys(domain, fromTxNumInclusive, toTxNumExclusive)
changedTxNums(domain, key, fromTxNumInclusive, toTxNumExclusive) // P1 inverted index
```

P0 至少需要 `txNum -> changed keys`：

```text
scan 0x03 || txNumAsc prefix
```

P1 再加 key-oriented inverted index：

```text
0x08 || domainId || canonicalKey || txNumAsc -> empty
```

没有 changeset，unwind 和 Module 06 root rebuild 就只能全库扫描，不能接受。

## 8. unwind 语义

java-tron fork 回退点：

| java-tron 源码 | 当前事实 | temporal hook |
| --- | --- | --- |
| `Manager.java:1034-1041` | `eraseBlock()` 中 `khaosDb.pop()` 后 `revokingStore.fastPop()` | `fastPop()` 成功后再 `archiveTemporalStore.unwindBlock(oldHeadBlock)` |
| `Manager.java:1142/1149` | fork 新分支 replay session commit | replay block 成功后 apply archive block write set |
| `Manager.java:1185/1187` | fork 失败恢复原分支 session commit | recovery replay 同样 apply archive block write set |

`unwindBlock(blockNum)`：

```text
range = txnum[blockNum]
for txNum in [range.lastTxNum..range.firstTxNum] desc:
  for each change in changeset(txNum):
    before = change.before
    if before is tombstone:
        delete latest(domain,key)
    else:
        put latest(domain,key, before with previous lastTxNum)
    delete history(domain,key,txNum)
    delete changeset(txNum,domain,key)
delete txnum(blockNum)
delete roots(blockNum)
progress = previous canonical block
```

注意 `latest.lastTxNum` 的恢复：如果 before 不是 tombstone，需要知道 range 前该 key 的上一个 change txNum。P0 可以在 `changeset` value 中保存 `previousTxNum`，或在 unwind 时从 history 找下一个变化推导。推荐保存 `previousTxNum`，避免 unwind 时额外 seek 复杂化。

## 9. progress 与崩溃恢复

必须保存：

```text
ArchiveProgress:
  schemaVersion
  appliedBlockNum
  appliedBlockHash
  appliedFinalizedTxNum
  nextTxNum
  lastRootBlockNum
```

启动校验：

1. `appliedBlockHash` 是否仍在 canonical chain 对应高度。
2. 若 archive ahead of chain，按 txnum 表 unwind。
3. 若 archive behind chain，进入 replay/backfill，不允许 silent latest fallback。
4. 若 progress 存在但 txnum/history/changeset 缺行，进入 repair 或 fail-fast。

不要只保存 `nextTxNum`。崩溃可能发生在 txnum/progress 与 latest/history 半写之间，因此 progress 必须和同批 write set 绑定。

## 10. 与 revoking DB 的边界

`SnapshotManager` 只负责 current state session：

| java-tron 源码 | 当前事实 |
| --- | --- |
| `SnapshotManager.java:115-138` | `buildSession()` 后 `advance()` |
| `SnapshotManager.java:160-168` | `advance/retreat` 移动 recent snapshot head |
| `SnapshotManager.java:170` | `merge()` 合并 session |
| `SnapshotManager.java:242` | `fastPop()` 用于 fork 回退 |

TemporalStore 不能挂在 revoking DB 里面自动 merge/retreat。它必须被 `ArchiveService` 明确驱动：

```text
canonical tmpSession.commit succeeds -> temporal.applyBlock(blockWriteSet)
canonical fastPop succeeds -> temporal.unwindBlock(oldHead)
```

这样 archive progress 与 java-tron canonical progress 才能一一对应。

## 11. 与旧 balance history 的关系

当前旧余额历史只覆盖余额：

| java-tron 源码 | 当前事实 |
| --- | --- |
| `reference.conf:118` | `balance.history.lookup = false` 默认关闭 |
| `AccountStore.java:68-88` | account put 时记录 balance diff |
| `AccountStore.java:92-104` | delete 时记录负余额 |
| `BalanceTraceStore` / `AccountTraceStore` | block-level balance trace |

ArchiveTemporalStore 启用后可以为旧余额查询提供更强后端，但不要让 temporal store 依赖旧 trace。正确方向：

```text
eth_getBalance historical -> ArchiveStateReader -> TemporalStore ACCOUNT domain
legacy balance API optional -> 也可复用 TemporalStore
```

## 12. API 建议

```java
interface ArchiveTemporalStore {
  Optional<ArchiveValue> getLatest(ArchiveDomain domain, byte[] key);
  Optional<ArchiveValue> getAsOf(ArchiveDomain domain, byte[] key, long targetTxNum);

  void applyBlock(BlockWriteSet blockWriteSet, Optional<byte[]> sidecarRoot);
  void unwindBlock(long blockNum);
  void unwindToTxNum(long txNumExclusive);

  ArchiveBlockRange getBlockRange(long blockNum);
  Iterator<ChangedKey> changedKeys(long fromTxNumInclusive, long toTxNumExclusive);
  ArchiveProgress progress();
}
```

实现类建议：

| 类 | package | 说明 |
| --- | --- | --- |
| `ArchiveTemporalStore` | `org.tron.core.archive.temporal` | temporal API |
| `PersistentArchiveTemporalStore` | 同上 | single sidecar DB 实现 |
| `ArchiveKeyLayout` | 同上 | prefix/key encoding |
| `ArchiveBatch` | 同上 | logical put/delete -> physical batch |
| `ArchiveProgressStore` | 同上 | progress read/write |
| `ArchiveBlockRangeStore` | 同上 | txnum table |
| `ArchiveChangesetCursor` | 同上 | changed keys scan |
| `ArchiveTemporalStoreTest` | test | before chain、unwind、crash recovery |

## 13. 第一版实现顺序

1. 定义 `ArchiveKeyLayout`，先写 codec tests。
2. 用 java-tron `DbSourceInter<byte[]>` 封装 single archive DB。
3. 实现 `ArchiveBatch`，支持 `put/delete`，delete 映射到底层 batch null。
4. 实现 `applyBlock`：latest/history/changeset/txnum/progress 同 batch。
5. 实现 `getLatest/getAsOf`。
6. 实现 `changedKeys(tx range)` 和 `unwindBlock`。
7. 接入 `ArchiveService.commitBlock/unwindBlock`。
8. 再接 Module 06 root 写入同 batch。

## 14. 测试证据

最小测试必须证明：

| 测试 | 要证明 |
| --- | --- |
| before-value 链 | 连续 tx 写同一 key，`getAsOf` 每个 txNum 返回正确状态 |
| 同 tx 多写 | Module 03 压缩后的 single write 只产生一条 history |
| tombstone | account/code/storage delete 语义正确，storage missing 交给 reader 解释为 zero |
| applyBlock batch | latest/history/changeset/txnum/progress 同批可见 |
| crash 模拟 | progress 未推进时 reader 不读半提交数据 |
| unwindBlock | latest 恢复到 block 前状态，history/changeset/txnum/root 删除或不可见 |
| empty block | 仍写 txnum/progress，可无 domain writes |
| LevelDB/RocksDB delete | `ArchiveBatch.delete` 在两种后端都变成 null-delete batch |
| duplicate apply | 重复 apply 同一 block 被检测，不能双写 history |

## 15. 关键风险

1. 用 blockNum 代替 txNum，会丢交易级历史。
2. latest/history/changeset/progress 不同 batch，会导致崩溃后错位。
3. 缺少 changeset，会让 unwind/root rebuild 退化为全库扫描。
4. tombstone 和 zero bytes 混淆，会破坏 storage/account/code 的读取语义。
5. 把 archive DB 纳入 revoking snapshot，会让长期 history 被 recent session 生命周期污染。
6. key schema 不预留 txNum range，会阻碍后续 immutable segment 和 freeze。
7. archive ahead/behind canonical chain 时 silent fallback latest，会掩盖数据损坏。
