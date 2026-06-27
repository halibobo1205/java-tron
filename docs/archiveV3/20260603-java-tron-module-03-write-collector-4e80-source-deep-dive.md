# 模块 03 ArchiveWriteCollector：4e80 java-tron 源码对照细化

日期：2026-06-03

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

关联总表：[java-tron Archive：4e80 六模块源码对照细化](./20260603-java-tron-archive-4e80-six-modules-source-detail.md)

上游模块：[模块 01 ArchiveTxNumIndex](./20260603-java-tron-module-01-txnum-index-4e80-source-deep-dive.md)、[模块 02 ArchiveDomainRegistry](./20260603-java-tron-module-02-domain-registry-4e80-source-deep-dive.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`，`git status --short` 为空，精确冲突标记扫描无命中。

## 1. 当前结论

`ArchiveWriteCollector` 是把 java-tron 执行过程中的 Store 写入转换成 Erigon 式 temporal domain write 的模块。它不负责持久化历史表，也不负责分配 txNum；它只在当前 `ArchiveExecutionContext` 下收集：

```text
txNum + phase + domain + canonicalKey + firstBefore + finalAfter + rawSource
```

当前 java-tron 的状态写入不是单点出口。P0 必须同时覆盖三条路径：

| 写入路径 | java-tron 证据 | collector 结论 |
| --- | --- | --- |
| 普通 actuator 直写 Store | `TransferActuator.java:55/60/66` 直接写 `AccountStore` | 必须有 Store-level hook |
| TVM repository cache commit | `RepositoryImpl.java:766-776` 和 `997-1069` 提交 account/code/contract/storage/dynamic cache | Repository hook 只能做语义增强，不能替代 Store hook |
| block finalize 系统写 | `Manager.java:1906-1925` reward、proposal、consensus、dynamic properties | 必须绑定 `BLOCK_FINALIZE` txNum，否则写入无归属 |

最小可落地模型：

```text
TronStoreWithRevoking put/delete raw hook
+ ContractStore/AbiStore/ContractStateStore store-specific hook
+ Storage semantic hook
+ ArchiveExecutionContext 绑定 txNum
+ TxWriteSetBuilder 压缩同 tx 同 key
```

## 2. Erigon 对照

Erigon V3 在 domain 层写历史时，核心形状是：

| Erigon 源码 | 语义 | java-tron 映射 |
| --- | --- | --- |
| `db/state/execctx/domain_shared.go:817` | `DomainPut(domain, roTx, k, v, txNum, prevVal)` | collector 输出必须带 domain/key/value/txNum |
| `domain_shared.go:833-835` | `prevVal == nil` 时从 latest 读取 before | java-tron Store hook 第一次写 key 时读取 before |
| `domain_shared.go:840-850` | before 与 after 相同可跳过 | temporal commit 前可去重，但 collector 先保留诊断更稳妥 |
| `domain_shared.go:870` | 写入内存 pending domain batch | java-tron collector 只写 pending write set，block commit 后再落 temporal store |

TRON 不能直接在底层 DB batch 里套 Erigon `DomainPut`，因为 java-tron 的 Store 分散且有业务编码差异。正确迁移方式是保留 Erigon 的语义：

```text
每个 txNum 内，同一 domain/canonicalKey：
  firstBefore = tx 开始前第一次观察到的值
  finalAfter = tx 结束时最后一次写入后的值
```

这比“每次 Store.put 都生成一条历史”更接近 Erigon，也能避免同一交易多次写账户或 storage 时产生错误 before-value 链。

## 3. Store-level raw hook

多数 java-tron Store 继承 `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java`。

| java-tron 源码 | 当前事实 | hook 意义 |
| --- | --- | --- |
| `TronStoreWithRevoking.java:78-80` | `getDbName()` 当前返回 `null` | S1 必须改为返回底层 DB name，否则 registry 无法按 dbName 分类 |
| `TronStoreWithRevoking.java:89-95` | `put(byte[] key, T item)` 最终 `revokingDB.put(key, item.getData())` | generic raw put hook |
| `TronStoreWithRevoking.java:97-99` | `delete(byte[] key)` 最终 `revokingDB.delete(key)` | generic raw delete hook |
| `TronStoreWithRevoking.java:107-115` | `getUnchecked(byte[] key)` 可读当前 before value | 第一次写 key 时采 first-before |

建议 hook 伪代码：

```text
put(key, item):
  if archive enabled and context active:
    before = getUnchecked(key)
    descriptor = registry.resolve(getDbName())
    collector.onRawPut(descriptor, key, before, item.getData())
  revokingDB.put(key, item.getData())

delete(key):
  if archive enabled and context active:
    before = getUnchecked(key)
    descriptor = registry.resolve(getDbName())
    collector.onRawDelete(descriptor, key, before)
  revokingDB.delete(key)
```

raw hook 的职责是完整性，不是 domain 语义推断。是否采集、怎么编码、是否进 root，都由 Module 02 的 `ArchiveDomainDescriptor` 决定。

## 4. store-specific hook

不是所有 Store 都走 `TronStoreWithRevoking.put`。

| Store | java-tron 源码 | 当前事实 | collector 处理 |
| --- | --- | --- | --- |
| `ContractStore` | `ContractStore.java:31-39` | 清掉 ABI 后直接 `revokingDB.put(key, item.getData())` | 必须显式 hook，after value 用清 ABI 后 bytes |
| `AbiStore` | `AbiStore.java:27-32` | overload `put(byte[], byte[])` 直接写 `revokingDB` | P1 或 optional domain；必须有诊断，避免误判为 generic |
| `ContractStateStore` | `ContractStateStore.java:27-32` | 直接 `revokingDB.put(key, item.getData())` | P1/PR8 依赖；不能被 generic hook 静默漏掉 |
| `TransactionStore` | `TransactionStore.java:33-38` | 带 blockNum 时写 `txId -> blockNum`，不是 execution state | registry 标 `IGNORE` 或 tx index policy，不进 state root |

对 `ContractStore.put`，推荐把归一化写入抽成可复用 helper：

```text
archiveCollector.onStoreSpecificPut(
  sourceDbName = "contract",
  domain = CONTRACT,
  rawKey = address,
  before = getUnchecked(address),
  after = contractWithoutAbi.getData()
)
revokingDB.put(address, contractWithoutAbi.getData())
```

不要在 collector 内硬编码 `ContractStore` 名称。collector 应接收 `ArchiveDomainDescriptor`，store-specific 只是调用入口不同。

## 5. Storage semantic hook

`CONTRACT_STORAGE` 是 Module 03 最容易出错的 domain。`storage-row` 的 physical key 不能稳定反推 `(contractAddress, slot)`：

| java-tron 源码 | 当前事实 |
| --- | --- |
| `StorageRowStore.java:15-16` | DB name 是 `storage-row` |
| `StorageRowStore.java:20-23` | `get` 会把 physical row key 写回 capsule |
| `actuator/.../Storage.java:46-53` | `compose(key, addrHash)` 用 addrHash 与 slot 片段拼 physical key，`contractVersion == 1` 会先 hash slot |
| `Storage.java:73-82` | `getValue` 用 physical key 读 `StorageRowStore`，再把 logical `DataWord key` 放进 rowCache |
| `Storage.java:86-93` | `put(DataWord key, DataWord value)` 以 logical key 更新 rowCache |
| `Storage.java:96-103` | `commit()` 中 zero value delete，非 zero put physical row |
| `RepositoryImpl.java:673-677` | `putStorageValue(address, key, value)` 保留 contract address 和 logical slot |
| `RepositoryImpl.java:1050-1058` | repository 最终 `storage.commit()` 持久化 |

P0 必须采 semantic write：

```text
domain = CONTRACT_STORAGE
canonicalKey = address21 || slot32 || keyVersion1
beforeValue = tx 内第一次写 slot 前的 logical value or tombstone
afterValue = final 32-byte value or tombstone
rawKey = physical storage-row key, only for diagnostics
```

推荐落点分两层：

1. 在 `RepositoryImpl.putStorageValue(address, key, value)` 或 `Storage.put(key, value)` 记录 logical write intent。
2. 在 `Storage.commit()` 结合 dirty row 的 physical row key、zero/delete 语义，确认最终 after。

如果短期只能改一个点，优先 `Storage.commit()`，因为它最接近最终落盘；但必须把 `Storage` 中的 `address`、logical `DataWord rowKey`、`contractVersion` 暴露给 hook，不能只传 physical row key。

## 6. 普通交易直写 Store

`TransferActuator` 证明非 TVM 交易会直接写 Store：

| java-tron 源码 | 当前事实 |
| --- | --- |
| `TransferActuator.java:33` | `execute(Object object)` |
| `TransferActuator.java:55` | 新账户时 `accountStore.put(toAddress, toAccount)` |
| `TransferActuator.java:60` | 扣 owner 余额走 `adjustBalance(accountStore, ...)` |
| `TransferActuator.java:64` | 非黑洞优化时给 blackhole 加 fee |
| `TransferActuator.java:66` | 给收款账户加余额 |

所以 `ArchiveWriteCollector` 不能只 hook `RepositoryImpl.commit()`。普通转账、资源扣减、proposal、maintenance 等 actuator 写入都必须被 Store-level hook 捕获。

## 7. TVM Repository 写路径

Repository 层仍然重要，因为它提供业务语义和 cache commit 顺序。

| java-tron 源码 | 当前事实 | collector 用法 |
| --- | --- | --- |
| `RepositoryImpl.java:638-646` | `saveCode(address, code)` 写 code cache，并可能更新 contract codeHash | CODE domain 的 semantic source |
| `RepositoryImpl.java:673-677` | `putStorageValue(address, key, value)` 保留 logical slot | CONTRACT_STORAGE semantic source |
| `RepositoryImpl.java:766-776` | `commit()` 提交 account/code/contract/contractState/storage/dynamic cache | 真实落盘仍由各 Store hook 捕获 |
| `RepositoryImpl.java:997-1004` | `commitAccountCache` 最终 `AccountStore.put` | ACCOUNT raw hook |
| `RepositoryImpl.java:1009-1016` | `commitCodeCache` 最终 `CodeStore.put` | CODE raw hook |
| `RepositoryImpl.java:1021-1031` | `commitContractCache` 写 `AbiStore` 和 `ContractStore` | CONTRACT store-specific hook |
| `RepositoryImpl.java:1037-1045` | `commitContractStateCache` 写 `ContractStateStore` | P1/PR8 diagnostic or optional domain |
| `RepositoryImpl.java:1050-1058` | `commitStorageCache` 调 `Storage.commit()` | CONTRACT_STORAGE semantic hook |
| `RepositoryImpl.java:1063-1069` | `commitDynamicCache` 写 `DynamicPropertiesStore` | DYNAMIC_PROPERTIES allowlist |
| `RepositoryImpl.java:1075-1125` | delegated/votes/delegation cache 写其他 Store | P1/P2 domain，P0 不默认进 root但要有 unknown 诊断 |

设计原则：

```text
Store hook = 完整性来源
Repository hook = semantic key/value 补充
Registry descriptor = 是否采集/是否进 root 的唯一判定
```

## 8. block finalize 写路径

Module 01 会把 block 内系统写入绑定到 `BLOCK_FINALIZE` txNum。Module 03 要确保这些写入发生时 context 仍然 active：

| java-tron 源码 | 当前事实 | 归属 |
| --- | --- | --- |
| `Manager.java:1906` | `payReward(block)` | `BLOCK_FINALIZE` |
| `Manager.java:1911` | `proposalController.processProposals()` | `BLOCK_FINALIZE` |
| `Manager.java:1914` | `consensus.applyBlock(block)` | `BLOCK_FINALIZE` |
| `Manager.java:1922-1925` | trans/recent/dynamic properties 更新 | `BLOCK_FINALIZE`，但 registry 只采 execution state allowlist |
| `Manager.java:1931` | section bloom write | query index，不进 execution state root |
| `Manager.java:1447-1455` | `updateDynamicProperties` 写 latest header hash/number/timestamp | DYNAMIC_PROPERTIES，是否进 root由 allowlist 决定 |
| `DynamicPropertiesStore.java:2210-2228` | latest header 三个 setter 最终 `this.put(...)` | generic raw hook 可捕获 |

如果 `BLOCK_FINALIZE` context 过早结束，Store hook 会捕获到写入但没有 txNum。archive 开启时这种情况应计入 hard diagnostic，测试中应失败。

## 9. tx context 生命周期

`ArchiveWriteCollector` 依赖 Module 01 的上下文。建议结构：

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
  beginTx(BLOCK_PREPARE)
    collect prepare writes
  endTx()

  for txIndex, tx in block.getTransactions():
    beginTx(USER_TX, txIndex, txId)
      processTransaction(tx, block)
      collect Store/Repository/Storage writes
    endTx()

  beginTx(BLOCK_FINALIZE)
    payReward/proposals/consensus/updateDynamicProperties
  endTx()

canonical revoking session commit succeeds
commitBlock(block) -> hand pending write sets to ArchiveTemporalStore
```

约束：

- 没有 active context 时，collector 不应生成 canonical write set。
- archive 开启且 canonical execution path 出现 execution-state Store 写但无 context，应产生 diagnostic。
- context 必须在 `finally` 中 clear，避免污染 pending transaction、constant call、RPC latest 查询或测试辅助执行。
- `processTransaction` retry 时，collector 不能把第一次失败尝试的 transient writes 当成最终事实；以实际落到 revoking Store 的最终写为准。

## 10. before/after 压缩规则

Collector 内部 key：

```text
collectorKey = domain || canonicalKey
```

第一次写：

```text
firstBefore = before value from Store/latest semantic state
finalAfter = after value
source list += raw source
```

后续同 tx 同 key 写：

```text
firstBefore 不变
finalAfter 覆盖为最新 after
source list 追加 diagnostic
```

delete：

```text
after = Tombstone
```

storage zero：

```text
after = Tombstone or canonical zero marker
reader 层把 missing storage 统一解释成 32-byte zero
```

P0 推荐 tombstone 用显式编码，不用 Java `null` 作为 domain value。`null` 可作为 batch API 的 delete 信号，但不要作为 `DomainWrite.afterValue` 的内部语义。

## 11. 与 revoking session 的关系

java-tron canonical state 由 `SnapshotManager` session 管理：

| java-tron 源码 | 当前事实 |
| --- | --- |
| `SnapshotManager.java:115-138` | `buildSession()` 创建 session 并 `advance()` |
| `SnapshotManager.java:160-168` | `advance/retreat` 移动所有 Chainbase head |
| `SnapshotManager.java:170` | `merge()` 合并 session |
| `Manager.java:1379-1381` | normal path `applyBlock` 后 `tmpSession.commit()` |
| `Manager.java:1034-1041` | fork erase 成功后 `fastPop()` |

Collector 不能在 Store hook 时直接写 archive DB。正确顺序：

```text
Store hook -> in-memory current TxWriteSetBuilder
endTx -> pending block write set
tmpSession.commit succeeds -> ArchiveTemporalStore.commitBlock(batch)
apply/commit fails -> discard pending write set
fork fastPop succeeds -> ArchiveTemporalStore.unwindBlock(oldHead)
```

这保证 archive progress 不会领先 java-tron canonical state。

## 12. 输出契约

建议 Module 03 输出给 Module 04 的结构：

```java
record TxWriteSet(
    long blockNum,
    byte[] blockHash,
    long txNum,
    int txIndex,
    ArchiveTxPhase phase,
    byte[] txId,
    List<DomainWrite> writes
) {}

record DomainWrite(
    ArchiveDomain domain,
    byte[] canonicalKey,
    ArchiveValue beforeValue,
    ArchiveValue afterValue,
    String sourceDbName,
    byte[] rawKey,
    List<ArchiveWriteSource> sources
) {}
```

`sourceDbName/rawKey/sources` 用于诊断、rebuild、debug API，不参与 Module 06 root 计算。root 只看 `domain/canonicalKey/afterValue` 的规范编码。

## 13. 第一版实现落点

新增类建议：

| 类 | package | 说明 |
| --- | --- | --- |
| `ArchiveWriteCollector` | `org.tron.core.archive.collector` | collector 接口 |
| `DefaultArchiveWriteCollector` | 同上 | in-memory current tx/block builder |
| `NoopArchiveWriteCollector` | 同上 | archive disabled |
| `TxWriteSetBuilder` | 同上 | 同 tx 同 key first-before/final-after 压缩 |
| `DomainWrite` | `org.tron.core.archive.domain` 或 `collector` | 单个 domain write |
| `ArchiveValue` | 同上 | bytes/tombstone 显式值 |
| `ArchiveWriteSource` | 同上 | raw dbName/rawKey/hook kind 诊断 |
| `ArchiveCollectorHooks` | 同上 | Store/Repository/Storage 调用的薄门面 |

改动顺序：

1. 修 `TronStoreWithRevoking.getDbName()`，接入 `NoopArchiveWriteCollector`。
2. 实现 `ArchiveExecutionContext` active/current lookup。
3. 在 `TronStoreWithRevoking.put/delete` 接 generic raw hook。
4. 在 `ContractStore.put`、`AbiStore.put`、`ContractStateStore.put` 接 store-specific hook 或复用统一 helper。
5. 在 `Storage.commit()` 增加 semantic storage hook，必要时让 `Storage` 暴露 address/logical key/version。
6. 在 Manager phase hook 中打开/关闭 context，并把 TxWriteSet 交给 pending block。
7. Module 04 接管真正持久化。

## 14. 测试证据

最小测试必须证明：

| 测试 | 要证明 |
| --- | --- |
| 普通转账 | `TransferActuator` 产生 `ACCOUNT` writes，owner/to/fee before-after 正确 |
| 同 tx 同账户多次写 | 只输出一条 `ACCOUNT` write，first-before 不变，final-after 为最终值 |
| 合约部署 | 输出 `ACCOUNT/CONTRACT/CODE`，`ContractStore` 清 ABI 后的 bytes 被采集 |
| storage put | 输出 `CONTRACT_STORAGE(address, slot, keyVersion)`，不是 raw `storage-row` physical key |
| storage zero/delete | 输出 tombstone，reader 后续按 zero storage 解释 |
| dynamic allowlist | latest header 写能被捕获，但是否进 root 由 registry allowlist 控制 |
| block finalize | 空块也有 `BLOCK_FINALIZE` write set，reward/dynamic properties 有 txNum |
| apply 抛异常 | pending write set 被丢弃，不写 temporal store |
| fork erase | `fastPop()` 成功后 temporal unwind，不在 Store hook 阶段提前回退 |
| unknown Store | archive 开启时有诊断计数，不能静默进 root |

## 15. 关键风险

1. 只 hook `RepositoryImpl.commit()` 会漏普通 actuator 直写 Store。
2. 只 hook `TronStoreWithRevoking` 会漏 `ContractStore/AbiStore/ContractStateStore` 的直接 `revokingDB.put`。
3. 只 hook `storage-row` raw key 会丢失 contract address 和 logical slot。
4. Store hook 立即持久化会和 revoking session 回滚不一致。
5. 同一 tx 多次写同一 key 不压缩，会破坏 Erigon 式 before-value 链。
6. context 泄漏到 pending tx、constant call 或 RPC latest 查询，会污染 canonical archive。
7. unknown Store 静默忽略会导致 sidecar root 不完整且难以排查。
