# java-tron Archive：六个模块本地源码对照实现总表

日期：2026-06-02

需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

> 2026-06-03 源码重校准：本文保留为旧总表，正文中仍有 `a79693e450` 行号和旧配置模型描述。当前权威基线是 `4e80f8ffa9a2`，且源码中存在 `StorageConfig.java`、`reference.conf`，精确冲突标记扫描无命中。当前逐模块源码对照请以 [java-tron Archive：4e80 六模块源码对照细化](./20260603-java-tron-archive-4e80-six-modules-source-detail.md) 为准。

关联总设计：

- [java-tron Archive 状态树实现蓝图](./20260602-java-tron-archive-implementation-blueprint.md)
- [java-tron Archive 端到端实现矩阵与 PR 执行队列](./20260602-java-tron-archive-end-to-end-implementation-matrix.md)
- [java-tron Archive 状态树：Erigon 源码深挖后的落地路线图](./20260601-java-tron-archive-erigon-source-synthesis-implementation-roadmap.md)

关联执行包：

- S1/S2：[Archive config + txNum lifecycle](./20260602-java-tron-archive-s1-s2-coding-packet.md)
- Module 01 checklist：[ArchiveTxNumIndex 逐文件 Patch 清单](./20260602-java-tron-archive-module-01-txnum-index-patch-checklist.md)
- S3：[ArchiveDomainRegistry](./20260602-java-tron-archive-s3-domain-registry-coding-packet.md)
- S4：[ArchiveWriteCollector](./20260602-java-tron-archive-s4-write-collector-coding-packet.md)
- S5：[Contract Storage Semantic Hook](./20260602-java-tron-archive-s5-contract-storage-semantic-coding-packet.md)
- S6/S7：[ArchiveRawStore + Temporal](./20260602-java-tron-archive-s6-raw-store-temporal-codecs-coding-packet.md)、[Temporal commit/unwind/startup](./20260602-java-tron-archive-s7-temporal-commit-unwind-startup-coding-packet.md)
- S8/S9：[ArchiveStateReader Core](./20260602-java-tron-archive-s8-state-reader-core-coding-packet.md)、[JSON-RPC Historical Getters](./20260602-java-tron-archive-s9-jsonrpc-historical-getters-coding-packet.md)
- S10/S11：[Sparse Merkle Tree Core + Root Codecs](./20260602-java-tron-archive-s10-sparse-merkle-root-codecs-coding-packet.md)、[CommitmentBuilder Integration + Rebuild Verifier](./20260602-java-tron-archive-s11-commitment-builder-integration-rebuild-coding-packet.md)

## 1. 本文定位

本文把六个模块全部压到本地 java-tron 源码层面，回答每个模块：

1. 现有 java-tron 的真实源码边界在哪里。
2. 新增类应该放在哪个 package。
3. 哪些现有方法要 hook，哪些不能 hook。
4. 和后续模块的契约是什么。
5. 最小测试应该证明什么。

本文不替代各模块的详细执行包，而是做统一入口。实现时先看本文确定模块边界，再跳到对应 S 编码执行包写代码。

## 2. 全局源码事实

### 2.1 当前没有 archive 包

本地源码中当前不存在：

```text
org.tron.core.archive
```

因此 archive 不是补现有实现，而是新增 sidecar 子系统。新增代码建议优先放在：

```text
chainbase/src/main/java/org/tron/core/archive/
```

原因：

- `chainbase` 能访问核心 Store 类型。
- `framework` 可以调用 `chainbase` 的 archive service。
- `chainbase` 不能反向依赖 `framework` 的 JSON-RPC、Wallet 或 TrieImpl。

`framework` 只放 block apply hook、startup verifier、JSON-RPC adapter 等需要 `Manager/Wallet/RPC` 的薄适配。

### 2.2 canonical block apply 边界在 Manager

关键源码：

| 源码 | 事实 | archive 含义 |
| --- | --- | --- |
| `framework/src/main/java/org/tron/core/db/Manager.java:1261-1267` | `pushBlock(final BlockCapsule block)` 是 fullnode block 推进入口 | archive block lifecycle 必须跟 canonical path 同步 |
| `Manager.java:1374` | normal path 创建 `try (ISession tmpSession = revokingStore.buildSession())` | archive pending block 应在 revoking session 内采集 |
| `Manager.java:1375` | 调 `applyBlock(newBlock, txs)` | beginBlock 应早于 apply |
| `Manager.java:1376` | `tmpSession.commit()` | archive sidecar commit 必须晚于此行 |
| `Manager.java:1377-1380` | apply/commit 失败会 remove khaos block 并 rethrow | archive 必须 abort pending block |
| `Manager.java:1017-1024` | `eraseBlock()` 读取 old head、`khaosDb.pop()`、`revokingStore.fastPop()` | archive 必须在 canonical `fastPop()` 后 unwind 同一 block |
| `Manager.java:1142-1144` | `switchFork()` replay 新分支时创建 `ISession`、`applyBlock`、`commit` | fork replay 也必须 begin/commit/abort archive |
| `Manager.java:1180-1182` | fork 失败后 replay 原分支同样创建 `ISession`、`applyBlock`、`commit` | recovery replay 同样不能漏 archive |

核心规则：

```text
canonical revoking session 成功 commit 之前，archive 只能 pending。
canonical commit 成功之后，archive 才能把 txNum/write-set/temporal/root 同 batch 落盘。
canonical 回退之后，archive 需要显式 unwind 到同一 block。
```

### 2.3 block 内 logical tx 边界在 processBlock

关键源码：

| 源码 | 事实 | archive phase |
| --- | --- | --- |
| `Manager.java:1824` | `processBlock(BlockCapsule block, List<TransactionCapsule> txs)` 是 canonical block 逻辑执行体 | archive logical txNum 必须在这里分段 |
| `Manager.java:1837` | `BalanceTraceStore.initCurrentBlockBalanceTrace(block)` | `BLOCK_PREPARE` |
| `Manager.java:1840` | `DynamicPropertiesStore.saveBlockEnergyUsage(0)` | `BLOCK_PREPARE` |
| `Manager.java:1858` | 遍历 `block.getTransactions()` | `USER_TX(txIndex)` |
| `Manager.java:1866` | `transactionCapsule.setBlockNum(num)` | 只有 blockNum，没有 txIndex |
| `Manager.java:1871` | `processTransaction(transactionCapsule, block)` | 用户交易执行体 |
| `Manager.java:1891` | `payReward(block)` | `BLOCK_FINALIZE` |
| `Manager.java:1896` | maintenance block 处理 proposals | `BLOCK_FINALIZE` |
| `Manager.java:1899` | `consensus.applyBlock(block)` | `BLOCK_FINALIZE` |
| `Manager.java:1907-1910` | update trans hash cache、recent block、recent transaction、dynamic properties | `BLOCK_FINALIZE` |
| `Manager.java:1914-1917` | section bloom 初始化、写入、推进 index | `BLOCK_FINALIZE` |

不能只给普通交易分配 txNum。否则 block finalize 后的 reward、dynamic properties、maintenance 写入会没有交易级时间点。

### 2.4 Store 统一写入口在 TronStoreWithRevoking，但有例外

关键源码：

| 源码 | 事实 | archive 含义 |
| --- | --- | --- |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java:78` | `getDbName()` 当前返回 `null` | S1 必须先修为代理底层 DB name |
| `TronStoreWithRevoking.java:88-93` | `put(byte[] key, T item)` 写 `revokingDB.put(key, item.getData())` | S4 通用 write hook 主入口 |
| `TronStoreWithRevoking.java:97-98` | `delete(byte[] key)` 写 `revokingDB.delete(key)` | S4 通用 delete hook 主入口 |
| `chainbase/src/main/java/org/tron/core/store/AccountStore.java:68` | `AccountStore.put` 做 balance trace 后调用 `super.put` | generic hook 可采集 ACCOUNT |
| `chainbase/src/main/java/org/tron/core/store/AccountStore.java:92` | `AccountStore.delete` 做 trace 后调用 `super.delete` | generic hook 可采集 delete |
| `chainbase/src/main/java/org/tron/core/store/ContractStore.java:31` | `ContractStore.put` 清 ABI 后直接 `revokingDB.put` | generic hook 会漏，必须 store-specific |
| `chainbase/src/main/java/org/tron/core/store/StorageRowStore.java:15` | DB 名 `storage-row` | physical source only，不能直接成为 logical storage key |
| `chainbase/src/main/java/org/tron/core/store/CodeStore.java:16` | DB 名 `code`，未重写 put | generic hook 可采集 CODE |

S4 不能只改 `TronStoreWithRevoking` 然后认为所有 state 都被采集了。`ContractStore`、`AbiStore`、`ContractStateStore` 这类直接写 `revokingDB` 的 Store 必须由 S3 inventory 标记为 `STORE_SPECIFIC`，由 S4/S5 单独处理。

### 2.5 SnapshotManager 是 canonical 短期回滚链，不是 archive storage

关键源码：

| 源码 | 事实 | archive 含义 |
| --- | --- | --- |
| `chainbase/src/main/java/org/tron/core/db2/core/SnapshotManager.java:119` | `buildSession` 会 `advance()` 并打开 revoking session | canonical 状态短期回滚 |
| `SnapshotManager.java:170` | `merge()` 合并当前 snapshot 到 previous | pending write 合并 |
| `SnapshotManager.java:242` | `fastPop()` 回退 snapshot | fork 回滚 |
| `SnapshotManager.java:290` | flush services 按 dbName 刷 Store | 依赖 `getDbName()` |

archive 不应挂进 `SnapshotManager` 的 revoking stack。原因：

- archive 是长期历史，不能随着 canonical snapshot flush/retreat 消失。
- archive 需要 own progress、startup verifier、repair 状态。
- archive temporal/root rows 需要在 single physical archive DB batch 中一起提交。

### 2.6 raw archive DB 可以用现有 batch 能力

关键源码：

| 源码 | 事实 | archive 含义 |
| --- | --- | --- |
| `chainbase/src/main/java/org/tron/core/db/common/DbSourceInter.java:29` | `DbSourceInter` extends `BatchSourceInter` | raw store 可基于现有 batch API |
| `LevelDbDataSourceImpl.java:365-379` | LevelDB `updateByBatch` 中 `value == null` 表示 delete | `ArchiveBatch` 可用 null tombstone |
| `RocksDbDataSourceImpl.java:301-313` | RocksDB `updateByBatch` 同样支持 null delete | LevelDB/RocksDB 语义一致 |
| `LevelDB.java:54` / `RocksDB.java:54` | wrapper `flush(Map<...>)` 最终调用 `updateByBatch` | 可以封装 `DefaultArchiveRawStore` |

S6/S7 的 P0 选择：

```text
single physical archive DB
logical tables by one-byte prefix
ArchiveBatch staged overlay
updateByBatch(batch.toRawMap())
```

不要在 P0 拆 `archive-root` 独立 DB。Temporal rows 和 root rows 必须能同 batch 提交。

### 2.7 JSON-RPC latest 查询现在强制 latest

关键源码：

| 源码 | 事实 | archive 含义 |
| --- | --- | --- |
| `framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java:394-419` | `eth_getBalance` 方法内联 latest-only 判断 | S9 需要非 latest 分支走 `ArchiveStateReader` |
| `TronJsonRpcImpl.java:536-568` | `eth_getStorageAt` 方法内联 latest-only 判断 | S9 需要非 latest storage reader |
| `TronJsonRpcImpl.java:553-558` | latest storage 用 `new Storage(addressByte, store)` | archive storage reader 必须复用 logical `(address, slot)` 语义 |
| `TronJsonRpcImpl.java:1001-1044` | `eth_call` 支持 string/object block param，但最后仍 latest-only | historical call 需要独立 adapter |
| `TronJsonRpcImpl.java:967-999` | object block selector 校验后又设为 `latest` | PR8 不能继续折回 latest |
| `JsonRpcApiUtil.java:518-531` | `getByJsonBlockId` 支持 latest/earliest/finalized/pending 语义，但 quantity 严格要求 `0x` | `ArchiveStatePointResolver` 只参考 tag 语义，quantity 仍用 `ByteArray.hexToBigInteger` |

S8 先做 reader core，S9 只接 `eth_getBalance/getCode/getStorageAt`，PR8 再接 historical `eth_call`。

### 2.8 VM storage 的真实语义在 Storage.commit

关键源码：

| 源码 | 事实 | archive 含义 |
| --- | --- | --- |
| `actuator/src/main/java/org/tron/core/vm/repository/RepositoryImpl.java:660` | `putStorageValue(address,key,value)` 只写 cache/intent | 不能在这里输出最终 DomainWrite |
| `RepositoryImpl.java:753` | `commit()` 汇总各类 cache | root repository commit 才代表落盘 |
| `RepositoryImpl.java:1001-1008` | `commitStorageCache` 对 root repository 调 `storage.commit()` | S5 semantic hook 最佳位置 |
| `actuator/src/main/java/org/tron/core/vm/program/Storage.java:46` | `compose` 生成 physical storage-row key | physical key 不可逆回 logical slot |
| `Storage.java:47` | contractVersion 1 会先 `sha3(key)` | canonical key 需要带 `keyVersion` |
| `Storage.java:51-52` | physical key 是 addrHash/key 各取 16 字节拼接 | raw key 不能作为 archive key |
| `Storage.java:61-65` | create2 addrHash 可依赖 `address || trxHash` | storage physical key 与部署上下文有关 |
| `Storage.java:96-105` | dirty row zero value 会 delete | zero/tombstone 语义必须统一 |

因此 `CONTRACT_STORAGE` domain key 必须是：

```text
address21 || slot32 || keyVersion1
```

而不是 `storage-row` raw key。

### 2.9 现有 TrieImpl/accountStateRoot 不能直接当 archive root

关键源码：

| 源码 | 事实 | archive 含义 |
| --- | --- | --- |
| `framework/src/main/java/org/tron/core/trie/TrieImpl.java:33` | TrieImpl 在 `framework` 包 | `chainbase` archive 不能 import |
| `TrieImpl.java:286-288` | empty root 用 `Hash.EMPTY_TRIE_HASH` | archive SMT 需要自己的 empty hash chain |
| `framework/src/main/java/org/tron/core/db/accountstate/callback/AccountStateCallBack.java:52-71` | existing account root 用 `TrieImpl(db, rootHash)` | 这是现有 account state root，不是 archive sidecar root |
| `AccountStateCallBack.java:94-104` | block 生成时写 `blockCapsule.setAccountStateRoot(newRoot)` | PR7 不写共识 header |
| `chainbase/src/main/java/org/tron/core/capsule/BlockCapsule.java:237-243` | `setAccountStateRoot` 写 block header raw | archive root 不能写这里 |
| `chainbase/src/main/java/org/tron/core/store/AccountStore.java:68-105` | `put` 触发 account callback，`delete` 不触发 | 现有 accountStateRoot 删除语义不足以复用为 archive root |
| `framework/src/main/java/org/tron/core/db/accountstate/TrieService.java:35` | 从 block header 读 accountStateRoot | archive reader/root 不走这里 |

PR7/S10/S11 结论：

```text
Archive root 是 sidecar root。
不写 BlockHeader.raw.accountStateRoot。
不复用 framework TrieImpl。
使用 chainbase 内 archive 专用 binary sparse Merkle tree。
hot unwind 恢复 ROOT_CURRENT，并恢复或重建 ROOT_LEAF metadata。
```

## 3. 模块 01：ArchiveTxNumIndex

详细文档：

- [模块 01 ArchiveTxNumIndex：java-tron 源码对照](./20260601-java-tron-module-01-txnum-index-java-tron-source-deep-dive.md)
- [模块 01 逐文件 Patch 清单](./20260602-java-tron-archive-module-01-txnum-index-patch-checklist.md)
- [PR1/PR2 逐文件 Patch 清单](./20260602-java-tron-archive-pr1-pr2-patch-checklist.md)
- [S1/S2 编码执行包](./20260602-java-tron-archive-s1-s2-coding-packet.md)

### 3.1 java-tron 源码边界

模块 01 只做三件事：

1. 默认关闭的 archive 配置和 no-op service。
2. canonical block lifecycle hook。
3. 交易级 logical txNum 时间线。

源码锚点：

| 源码 | 处理 |
| --- | --- |
| `common/src/main/java/org/tron/core/config/args/Storage.java:48` | 当前 storage 配置模型是 `Storage`，没有 `StorageConfig.java` | 在 `Storage` 下新增 `ArchiveConfig archive` runtime config |
| `common/src/main/java/org/tron/core/config/args/Storage.java:405-470` | 当前 storage helper 从 `Config` 读取子树并填充字段 | 新增 `Storage.getArchiveConfigFromConfig(config)` 或等价解析方法 |
| `framework/src/main/java/org/tron/core/config/args/Args.java:516-564` | `Args` 手工创建并填充 `CommonParameter.storage`，没有 `applyStorageConfig` | 在同一初始化链路把 `storage.archive.*` 填入 `CommonParameter.getStorage().getArchive()` |
| `common/src/main/java/org/tron/common/parameter/CommonParameter.java:479` | `CommonParameter` 持有 `public Storage storage` | archive runtime config 应挂在 `Storage` 下，而不是 `CommonParameter` 顶层 |
| `framework/src/main/resources/config.conf` | 用户可见默认 `storage.archive.enable=false` |
| 当前源码无 `common/src/main/resources/reference.conf` | 不要把默认值写到不存在的默认配置源 |
| `TronStoreWithRevoking.java:77-78` | 修 `getDbName()`，为后续 registry/write hook 铺路 |
| `Manager.java:1374-1376` | normal canonical apply hook |
| `Manager.java:1017-1024` | fork rewind hook |
| `Manager.java:1824/1858/1871/1891/1907-1910` | logical tx phase hook |

### 3.2 新增文件

P0 建议：

```text
common/src/main/java/org/tron/core/config/args/ArchiveConfig.java

chainbase/src/main/java/org/tron/core/archive/ArchiveService.java
chainbase/src/main/java/org/tron/core/archive/NoopArchiveService.java
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
chainbase/src/main/java/org/tron/core/archive/ArchivePhase.java
chainbase/src/main/java/org/tron/core/archive/StatePoint.java
chainbase/src/main/java/org/tron/core/archive/ArchiveBlockContext.java
chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveTxNumIndex.java
chainbase/src/main/java/org/tron/core/archive/txnum/InMemoryArchiveTxNumIndex.java
```

`DefaultArchiveService` 在 S1/S2 可以只绑定 txNum，不采集 Store write。S4 再注入 collector，S7 再注入 temporal store，S11 再注入 commitment builder。

### 3.3 Manager hook 形状

normal apply：

```text
archiveService.beginBlock(newBlock)
try (ISession tmpSession = revokingStore.buildSession()) {
  applyBlock(newBlock, txs)
  tmpSession.commit()
  archiveService.commitBlock()
} catch (Throwable t) {
  archiveService.abortBlock()
  ...
}
```

fork replay：

```text
for branch block:
  archiveService.beginBlock(item.getBlk().setSwitch(true))
  try (ISession tmpSession = revokingStore.buildSession()) {
    applyBlock(...)
    tmpSession.commit()
    archiveService.commitBlock()
  } catch (...) {
    archiveService.abortBlock()
    throw
  }
```

erase/unwind：

```text
oldHeadBlock = chainBaseManager.getBlockById(latestHash)
khaosDb.pop()
revokingStore.fastPop()
archiveService.unwindBlock(oldHeadBlock.getNum(), oldHeadBlock.getBlockId().getBytes())
```

processBlock phase：

```text
beginSystemTx(BLOCK_PREPARE)
  init block balance trace
  saveBlockEnergyUsage(0)
endSystemTx()

for txIndex, transaction in block.getTransactions():
  beginUserTx(block, transaction, txIndex)
    processTransaction(transaction, block)
  endUserTx()

beginSystemTx(BLOCK_FINALIZE)
  payReward
  proposalController.processProposals if maintenance
  consensus.applyBlock
  updateRecentBlock/updateRecentTransaction
  updateDynamicProperties
endSystemTx()
```

### 3.4 txNum 数据模型

S1/S2 in-memory，S6/S7 持久化：

```text
blockNum + phase + txIndex -> txNum
txId -> txNum
txNum -> blockNum + blockHash + phase + txIndex + txId?
blockNum -> firstTxNum + lastTxNumInclusive + blockHash
```

`phase` 至少：

```text
BLOCK_PREPARE
USER_TX
BLOCK_FINALIZE
```

可选细分：

```text
BLOCK_REWARD
CONSENSUS_APPLY
MAINTENANCE
```

P0 可以把后置系统写合并为 `BLOCK_FINALIZE`，但不能漏掉它。

### 3.5 不做事项

- 不采集 Store write-set。
- 不写 temporal history。
- 不计算 root。
- 不新增 JSON-RPC。
- 不改 block header。
- 不让 pending transaction、mempool validation、constant call 进入 archive。

### 3.6 验收

必须证明：

1. `storage.archive.enable=false` 时 fullnode 行为不变。
2. block with 0 tx 仍有 prepare/finalize txNum。
3. block with N tx 的 `USER_TX` txNum 连续且 txIndex 稳定。
4. applyBlock 失败不会留下 committed archive progress。
5. switchFork old branch unwind 后，新 branch replay 的 txNum/progress 与 canonical head 一致。
6. `TronStoreWithRevoking.getDbName()` 不再返回 null。

## 4. 模块 02：ArchiveDomainRegistry

详细文档：

- [模块 02 ArchiveDomainRegistry：java-tron 源码对照](./20260601-java-tron-module-02-domain-registry-java-tron-source-deep-dive.md)
- [模块 02 逐文件 Patch 清单](./20260602-java-tron-archive-module-02-domain-registry-patch-checklist.md)
- [S3 编码执行包](./20260602-java-tron-archive-s3-domain-registry-coding-packet.md)

### 4.1 java-tron 源码边界

模块 02 负责把 java-tron 分散 Store 映射成稳定 archive domain。它不采集写，不持久化数据。

关键 Store inventory 来自 `ChainBaseManager`：

| 源码 | Store | P0 结论 |
| --- | --- | --- |
| `ChainBaseManager.java:81` | `AccountStore` | `ACCOUNT` |
| `ChainBaseManager.java:99` | `DynamicPropertiesStore` | `DYNAMIC_PROPERTIES`，root key allowlist |
| `ChainBaseManager.java:141` | `CodeStore` | `CODE` |
| `ChainBaseManager.java:144` | `ContractStore` | `CONTRACT` |
| `ChainBaseManager.java:147` | `ContractStateStore` | P1 或 P0+，history-only until confirmed |
| `ChainBaseManager.java:156` | `StorageRowStore` | physical source only，logical `CONTRACT_STORAGE` |
| `ChainBaseManager.java:162` | `DelegationStore` | execution-adjacent，P1/P0+ inventory required |
| `ChainBaseManager.java:177` | `TransactionStore` | block/tx data，not state domain |
| `ChainBaseManager.java:181` | `TransactionRetStore` | tx result data，not state domain |
| `ChainBaseManager.java:201` | `BalanceTraceStore` | history helper，not canonical state root |
| `ChainBaseManager.java:203` | `AccountTraceStore` | history helper，not canonical state root |
| `ChainBaseManager.java:234` | `SectionBloomStore` | index/cache，excluded |

Registry test 必须覆盖所有 `ChainBaseManager` 注入的 Store。新增 Store 未分类时，测试失败或显式落入 `UNCLASSIFIED` 并告警。

### 4.2 P0 domain

P0 fixed ids：

```text
0x0001 ACCOUNT
0x0002 CONTRACT
0x0003 CODE
0x0004 CONTRACT_STORAGE
0x0005 DYNAMIC_PROPERTIES
0x0006 CONTRACT_STATE
0x0101 ABI
0x0102 ACCOUNT_TRACE
```

P0 reader/root 策略：

| Domain | History | Root P0 | Reader | Source |
| --- | --- | --- | --- | --- |
| `ACCOUNT` | full | domain root first，PR7 可升 global | yes | `account` |
| `CONTRACT` | full | domain root first，PR7 可升 global | yes | `contract` |
| `CODE` | full | domain root first，PR7 可升 global | yes | `code` |
| `CONTRACT_STORAGE` | full | domain root first，PR7 可升 global | yes | semantic hook |
| `DYNAMIC_PROPERTIES` | full history | root allowlist only | yes for historical call | `properties` |
| `CONTRACT_STATE` | P1/P0+ | history-only until PR8 confirms | maybe | `contract-state` |
| `ABI` | history/debug | excluded from state root | debug/state optional | `abi` |
| `ACCOUNT_TRACE` | existing helper | excluded | debug only | `account-trace` |

PR7 只有在 registry 把 `ACCOUNT/CONTRACT/CODE/CONTRACT_STORAGE` 升为 `IN_GLOBAL_ROOT` 后，才能把 global root 描述为 `coverage=TVM_STATE_ONLY`。否则只能发布 domain roots。

### 4.3 key/value codec

P0 codec：

| Domain | Canonical key | Canonical value |
| --- | --- | --- |
| `ACCOUNT` | raw 21-byte TRON address | `AccountCapsule.getData()` |
| `CONTRACT` | raw 21-byte contract address | `ContractStore` 实际落盘 bytes，即 ABI cleared `SmartContract` |
| `CODE` | raw 21-byte contract address | bytecode bytes |
| `CONTRACT_STORAGE` | `address21 || slot32 || keyVersion1` | 32-byte storage value |
| `DYNAMIC_PROPERTIES` | raw property key bytes | `BytesCapsule.getData()` |

`CONTRACT_STORAGE` 禁止 raw `storage-row` key 直接编码。`Storage.compose` 的 physical key 已丢失原始 slot/address 语义。

### 4.4 Store binding

`StoreBinding` 应包含：

```text
dbName
storeClassName
domain
StoreCategory
RawHookMode
warnWhenWritten
reason
```

`RawHookMode`：

| Mode | 含义 | 示例 |
| --- | --- | --- |
| `GENERIC_TRON_STORE` | `TronStoreWithRevoking.put/delete` 可直接采集 | `account`, `code`, `properties` |
| `STORE_SPECIFIC` | Store 绕过 generic hook | `contract`, `abi`, `contract-state` |
| `SEMANTIC_ONLY` | raw key 不可作为 archive key | `storage-row` |
| `IGNORED` | index/cache/block data | block index, bloom, recent tx |

### 4.5 不做事项

- 不修改 `TronStoreWithRevoking`。
- 不改 `Storage.commit`。
- 不生成 `DomainWrite`。
- 不写 archive DB。
- 不计算 root。

### 4.6 验收

必须证明：

1. domainId 唯一且按 unsigned u16 big-endian 编码。
2. root aggregation 后续只能按 numeric domainId 排序。
3. `account -> ACCOUNT`、`contract -> CONTRACT`、`code -> CODE`、`properties -> DYNAMIC_PROPERTIES`。
4. `storage-row -> CONTRACT_STORAGE` 但 `RawHookMode=SEMANTIC_ONLY`。
5. `ContractStore` 标记为 `STORE_SPECIFIC`。
6. `DYNAMIC_PROPERTIES` 有 root allowlist 结构。
7. registry checksum deterministic。

## 5. 模块 03：ArchiveWriteCollector

详细文档：

- [模块 03 ArchiveWriteCollector：java-tron 源码对照](./20260601-java-tron-module-03-write-collector-java-tron-source-deep-dive.md)
- [模块 03 逐文件 Patch 清单](./20260602-java-tron-archive-module-03-write-collector-patch-checklist.md)
- [S4 编码执行包](./20260602-java-tron-archive-s4-write-collector-coding-packet.md)
- [S5 Contract Storage Semantic Hook 编码执行包](./20260602-java-tron-archive-s5-contract-storage-semantic-coding-packet.md)

### 5.1 java-tron 源码边界

模块 03 从 Store write 事件生成 `TxWriteSet`/`BlockWriteSet`。它必须运行在模块 01 的 archive execution context 里，使用模块 02 registry 做分类。

源码锚点：

| 源码 | 事实 | collector 处理 |
| --- | --- | --- |
| `TronStoreWithRevoking.java:88-93` | generic put | `StoreWriteEvent.put(dbName,key,before,after)` |
| `TronStoreWithRevoking.java:97-98` | generic delete | `StoreWriteEvent.delete(dbName,key,before)` |
| `AccountStore.java:68-87` | AccountStore put 最后 `super.put` | generic hook 采集 actual account bytes |
| `AccountStore.java:92` | AccountStore delete 最后 `super.delete` | generic hook 采集 delete |
| `ContractStore.java:31-39` | clear ABI 后直接 `revokingDB.put` | store-specific hook，after value 必须是 cleared bytes |
| `RepositoryImpl.java:625` | `saveCode` 写 code cache 并可更新 contract codeHash | code/contract 同 tx 内会多 domain write |
| `RepositoryImpl.java:660` | storage intent | 不作为 final write |
| `RepositoryImpl.java:1001/1008` | root repository storage commit | S5 semantic write final boundary |
| `Storage.java:96-105` | zero value delete physical row | semantic write 要把 zero 统一成 tombstone |

### 5.2 新增文件

```text
chainbase/src/main/java/org/tron/core/archive/collector/ArchiveExecutionContext.java
chainbase/src/main/java/org/tron/core/archive/collector/ArchiveWriteCollector.java
chainbase/src/main/java/org/tron/core/archive/collector/DefaultArchiveWriteCollector.java
chainbase/src/main/java/org/tron/core/archive/collector/StoreWriteEvent.java
chainbase/src/main/java/org/tron/core/archive/collector/SemanticStoreWrite.java
chainbase/src/main/java/org/tron/core/archive/collector/DomainWrite.java
chainbase/src/main/java/org/tron/core/archive/collector/TxWriteSet.java
chainbase/src/main/java/org/tron/core/archive/collector/BlockWriteSet.java
```

### 5.3 generic Store hook

P0 hook 形状：

```text
before = revokingDB.getUnchecked(key)
revokingDB.put(key, item.getData())
after = item.getData()
archiveService.onStoreWrite(dbName, key, before, after)
```

delete：

```text
before = revokingDB.getUnchecked(key)
revokingDB.delete(key)
archiveService.onStoreWrite(dbName, key, before, null)
```

hook 必须受 `ArchiveExecutionContext` 保护：

```text
archive disabled -> no-op
no active block/tx -> no-op or diagnostic, never collect pending/constant call
registry says IGNORED -> no DomainWrite
registry says UNCLASSIFIED -> metric/warn, not block fail in P0
```

### 5.4 same-tx collapse

一个 logical tx 内同 domain/key 多次写，collector 应保留：

```text
beforeValue = first before
afterValue = last after
writeCount
sourceEvents
```

如果最终 `beforeValue == afterValue`，可以丢弃 domain write，但诊断计数保留。

跨 tx 不能 collapse。temporal history 需要每个 `txNum` 的边界。

### 5.5 ContractStore 特例

`ContractStore.put` 清 ABI 后直接写 `revokingDB.put`，因此：

1. 不能在调用方 `RepositoryImpl.updateContract` 采集原始 `ContractCapsule`。
2. 必须在 `ContractStore.put` 内采集清 ABI 后的 final bytes。
3. ABI 若要历史化，作为 `ABI` domain 单独从 `AbiStore` 采集。
4. `CONTRACT` value codec 以实际落盘 bytes 为准。

### 5.6 Storage semantic hook

`CONTRACT_STORAGE` 必须从 semantic event 生成：

```text
contractStorage(address21, slot32, before32?, after32?, physicalRowKey, keyVersion)
```

推荐 hook 在 `Storage.commit()` 或 `RepositoryImpl.commitStorageCache(root)`，但 event 需要能拿到：

```text
address
logical rowKey/DataWord slot
old value
new value
contractVersion/keyVersion
physical row key for diagnostic only
```

不要在 `RepositoryImpl.putStorageValue` 直接输出 final `DomainWrite`。child repository/revert 会污染 archive。

### 5.7 不做事项

- 不写 temporal DB。
- 不分配 txNum。
- 不计算 root。
- 不默认 fail block on unclassified store。
- 不采集 mempool validation 或 constant call。

### 5.8 验收

必须证明：

1. archive disabled 时 Store hook no-op。
2. active block/tx 时 generic account/code/properties write 能进入 `TxWriteSet`。
3. no active context 时 Store write 不进入 archive。
4. `ContractStore.put` 采集的是 ABI cleared bytes。
5. same tx same key collapse 正确。
6. tx boundary 结束后 write-set 被 seal，后续不能修改。
7. `storage-row` raw write 不直接生成 `CONTRACT_STORAGE`。
8. `Storage.commit()` zero value 生成 tombstone。

## 6. 模块 04：ArchiveTemporalStore

详细文档：

- [模块 04 ArchiveTemporalStore：java-tron 源码对照](./20260601-java-tron-module-04-temporal-store-java-tron-source-deep-dive.md)
- [模块 04 逐文件 Patch 清单](./20260602-java-tron-archive-module-04-temporal-store-patch-checklist.md)
- [S6 ArchiveRawStore + Temporal Codecs 编码执行包](./20260602-java-tron-archive-s6-raw-store-temporal-codecs-coding-packet.md)
- [S7 Temporal Commit / GetAsOf / Unwind / Startup 编码执行包](./20260602-java-tron-archive-s7-temporal-commit-unwind-startup-coding-packet.md)

### 6.1 java-tron 源码边界

模块 04 把模块 03 的 `BlockWriteSet` 持久化为 temporal history，并提供 `getAsOf`。

源码锚点：

| 源码 | 事实 | temporal 处理 |
| --- | --- | --- |
| `LevelDbDataSourceImpl.java:365-379` | batch delete 用 `value == null` | `ArchiveBatch` tombstone 语义 |
| `RocksDbDataSourceImpl.java:301-313` | RocksDB batch delete 同 LevelDB | raw store cross-engine 一致 |
| `SnapshotManager.java:119/170/242` | canonical revoking session | archive 不挂入 revoking stack |
| `Manager.java:1376` | canonical commit 成功点 | temporal commitBlock 晚于它 |
| `Manager.java:1024` | canonical fastPop | temporal unwind 同向执行 |

### 6.2 新增文件

```text
chainbase/src/main/java/org/tron/core/archive/store/ArchiveRawStore.java
chainbase/src/main/java/org/tron/core/archive/store/DefaultArchiveRawStore.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveBatch.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveTable.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveKeyCodec.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveValueCodec.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveTemporalStore.java
chainbase/src/main/java/org/tron/core/archive/store/DefaultArchiveTemporalStore.java
chainbase/src/main/java/org/tron/core/archive/store/ChangedKey.java
chainbase/src/main/java/org/tron/core/archive/store/ArchiveProgressRecord.java
```

### 6.3 logical tables

P0 single physical DB：

```text
0x01 META
0x02 TXNUM_BLOCK
0x03 TXNUM_BY_TXID
0x04 TXNUM_META
0x10 LATEST
0x11 HISTORY
0x12 CHANGESET
0x13 PROGRESS
0x30 ROOT_META
0x31 ROOT_BLOCK
0x32 ROOT_DOMAIN
0x33 ROOT_NODE
0x34 ROOT_CURRENT
0x36 ROOT_LEAF
```

S6/S7 实现 temporal；S10/S11 继续使用同一个 `ArchiveBatch` 写 root rows。

### 6.4 temporal write schema

建议 schema：

```text
LATEST(domainId, canonicalKey) -> latest value/tombstone
HISTORY(domainId, canonicalKey, txNumDesc) -> previous value/tombstone
CHANGESET(txNum, domainId, canonicalKey) -> before/after summary
TXNUM_BLOCK(blockNum) -> first/last txNum + blockHash
TXNUM_BY_TXID(txId) -> txNum + blockNum + txIndex
PROGRESS -> appliedBlockNum/hash + first/last txNum + status
```

`HISTORY` 使用 descending txNum encoding，方便 `getAsOf(domain,key,asOfTxNum)` 做 prefix seek。

### 6.5 commitBlock 同 batch

S7/S11 最终 commit path：

```text
ArchiveBatch batch = rawStore.newBatch()
temporalStore.stageApplyBlock(blockWriteSet, batch)
if commitment enabled:
  commitmentBuilder.stageBlockEnd(blockWriteSet, batch)
rawStore.updateByBatch(batch.toRawMap())
```

不能出现：

```text
temporal batch commit 成功
root batch commit 失败
```

这种状态会破坏 startup verifier 和 rebuild。

### 6.6 getAsOf

`getAsOf(domain,key,asOfTxNum)`：

1. 如果 latest progress `lastTxNum <= asOfTxNum`，可读 `LATEST`。
2. 否则 seek `HISTORY(domain,key, txNum <= asOfTxNum)`。
3. tombstone 返回 missing，不返回 empty bytes。
4. codec error/corruption 抛 `ArchiveReaderException`，不静默 fallback latest。

### 6.7 unwind

`unwindBlock(blockNum, blockHash)`：

1. 校验 `TXNUM_BLOCK(blockNum).blockHash`。
2. 反向扫描该 block txNum range 的 `CHANGESET`。
3. 恢复 `LATEST` 为 before。
4. 删除该 block 写入的 `HISTORY/CHANGESET/TXNUM_*` rows。
5. 更新 `PROGRESS` 到 parent block。
6. S11 开启 commitment 时，同 batch 恢复 `ROOT_CURRENT` 和 `ROOT_LEAF` metadata。

hash mismatch 必须进入 repair-needed，不能静默继续。

### 6.8 startup verifier

启动时比较：

```text
archive progress blockNum/hash
chain latest blockNum/hash
root progress blockNum/hash if commitment enabled
registry checksum
```

状态：

| 情况 | 处理 |
| --- | --- |
| archive equal chain latest | OK |
| archive ahead | unwind archive to chain latest if hash chain matches |
| archive behind | P0 fail fast，提示 backfill/rebuild |
| hash mismatch | repair-needed |
| empty archive on non-genesis chain | fail fast |

### 6.9 验收

必须证明：

1. LevelDB/RocksDB batch null delete 语义被 raw store 测试覆盖。
2. `ArchiveBatch.get` 能区分 unstaged 和 staged delete。
3. `commitBlock` 对 temporal rows 原子提交。
4. `getAsOf` 覆盖 create/update/delete/recreate。
5. unwind 后 latest/history/change index/progress 回到 parent。
6. startup verifier 检出 ahead/behind/mismatch/empty archive。

## 7. 模块 05：ArchiveStateReader

详细文档：

- [模块 05 ArchiveStateReader：java-tron 源码对照](./20260601-java-tron-module-05-state-reader-java-tron-source-deep-dive.md)
- [模块 05 逐文件 Patch 清单](./20260602-java-tron-archive-module-05-state-reader-patch-checklist.md)
- [S8 ArchiveStateReader Core 编码执行包](./20260602-java-tron-archive-s8-state-reader-core-coding-packet.md)
- [S9 JSON-RPC Historical Getters 编码执行包](./20260602-java-tron-archive-s9-jsonrpc-historical-getters-coding-packet.md)

### 7.1 java-tron 源码边界

模块 05 提供 archive-backed reader。它不执行 VM，不改 latest Store。

源码锚点：

| 源码 | 事实 | reader 处理 |
| --- | --- | --- |
| `TronJsonRpcImpl.java:394-419` | `eth_getBalance` latest only | non-latest 走 reader |
| `TronJsonRpcImpl.java:536-568` | `eth_getStorageAt` latest only | non-latest 走 reader |
| `TronJsonRpcImpl.java:553-558` | latest storage 用 `Storage` physical lookup | archive 用 logical `(address,slot)` |
| `TronJsonRpcImpl.java:1001-1044` | `eth_call` object selector 后仍 latest | PR8 另做，不在 S8/S9 |
| `JsonRpcApiUtil.java:518-531` | tag 解析支持 latest/earliest/finalized；quantity parser 和 state getter 不一致 | resolver 参考 tag，quantity 用 `ByteArray.hexToBigInteger` |
| `RepositoryImpl.java:309/650/681` | latest account/code/storage 读取路径 | reader output 要匹配这些 latest 语义 |

### 7.2 新增文件

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStateReader.java
chainbase/src/main/java/org/tron/core/archive/reader/DefaultArchiveStateReader.java
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStateReaderFactory.java
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveReadResult.java
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveReaderException.java

framework/src/main/java/org/tron/core/archive/ArchiveStatePointResolver.java
framework/src/main/java/org/tron/core/services/jsonrpc/archive/ArchiveJsonRpcStateAdapter.java
```

### 7.3 Reader API

```text
ArchiveReadResult<AccountCapsule> getAccount(address21)
ArchiveReadResult<ContractCapsule> getContract(address21)
ArchiveReadResult<byte[]> getCode(address21)
ArchiveReadResult<byte[]> getStorage(address21, slot32)
ArchiveReadResult<BytesCapsule> getDynamicProperty(key)
```

`ArchiveReadResult` 必须区分：

```text
present(value)
missing()
```

不要把 missing、zero bytes、empty bytes 混成一个 null。codec corruption 抛 exception。

### 7.4 StatePoint

模块 01 已定义 `StatePoint`。reader 只接收已解析状态点：

```text
LATEST
BLOCK_END(blockNum)
TX_BEFORE(txNum)
TX_AFTER(txNum)
```

JSON-RPC adapter 负责把：

```text
latest / earliest / finalized / quantity / object selector
```

解析成 `StatePoint`。S9 对 object selector 可先只支持 block number/hash 校验和 block-end；PR8 再做完整 EIP-1898 风格 call。

### 7.5 JSON-RPC 接入

S9 改造：

| Method | latest 行为 | non-latest 行为 |
| --- | --- | --- |
| `eth_getBalance` | 继续走 `wallet.getAccount` | reader ACCOUNT |
| `eth_getCode` | 继续走 `wallet.getContractInfo`/code path | reader CODE |
| `eth_getStorageAt` | 继续走 `Storage` latest | reader CONTRACT_STORAGE |

错误策略：

```text
archive disabled + non-latest -> clear JSON-RPC error
archive behind requested block -> clear JSON-RPC error
archive corrupt -> internal error
missing account -> balance 0
missing code -> "0x"
missing storage -> 32-byte zero
```

禁止：

```text
historical query silently fallback latest
```

### 7.6 与 PR8 historical eth_call 的边界

S8/S9 不执行 VM。PR8 才新增：

```text
ArchiveRepositoryAdapter
ArchiveDynamicPropertiesView
ArchiveEthCallExecutor
historical VM context
```

原因：

- `eth_call` 需要 historical account/code/storage/dynamic properties 同时一致。
- VM execution 可能访问 Repository 多个 domain。
- latest `call(...)` 路径会使用当前 Manager/Wallet 环境，不能直接复用。

### 7.7 验收

必须证明：

1. block-end reader 能读 account/code/storage。
2. missing account/code/storage 返回 JSON-RPC 兼容默认值。
3. non-latest query 在 archive disabled 时返回明确错误。
4. archive behind request 不 fallback latest。
5. codec corruption 抛 reader exception。
6. `eth_getStorageAt` 使用 logical slot，覆盖 contractVersion/keyVersion。
7. finalized/earliest/latest block tag 解析正确。

## 8. 模块 06：CommitmentBuilder

详细文档：

- [模块 06 CommitmentBuilder：java-tron 源码对照](./20260601-java-tron-module-06-commitment-builder-java-tron-source-deep-dive.md)
- [模块 06 CommitmentBuilder：Erigon 源码对照深挖](./20260601-java-tron-module-06-commitment-builder-erigon-source-deep-dive.md)
- [模块 06 逐文件 Patch 清单](./20260602-java-tron-archive-module-06-commitment-builder-patch-checklist.md)
- [S10 Sparse Merkle Tree Core + Root Codecs 编码执行包](./20260602-java-tron-archive-s10-sparse-merkle-root-codecs-coding-packet.md)
- [S11 CommitmentBuilder Integration + Rebuild Verifier 编码执行包](./20260602-java-tron-archive-s11-commitment-builder-integration-rebuild-coding-packet.md)

### 8.1 java-tron 源码边界

模块 06 计算 archive sidecar root。它不写共识 block header。

源码锚点：

| 源码 | 事实 | root 处理 |
| --- | --- | --- |
| `framework/src/main/java/org/tron/core/trie/TrieImpl.java:33` | TrieImpl 在 framework | chainbase archive 不能 import |
| `TrieImpl.java:286-288` | empty root 是 RLP Patricia empty trie hash | archive SMT 用自己的 empty hash chain |
| `AccountStateCallBack.java:52-71` | 现有 account root 用 TrieImpl | 只作为生命周期对照，不复用 |
| `AccountStateCallBack.java:94-104` | generated block 写 accountStateRoot | archive 不写 |
| `BlockCapsule.java:237-243` | `setAccountStateRoot` 写 header | PR7 禁止调用 |
| `BlockCapsule.java:213-225` | `calcMerkleRoot` 只计算交易 Merkle root | `txTrieRoot` 不是状态 root |
| `Manager.java:1295-1301` | 非本地产块内联校验 `txTrieRoot` | archive root 不参与共识校验 |
| `TrieService.java:35` | 从 block header 读 account root | archive reader/root 不走这里 |
| `Manager.java:1374-1376` | canonical session 内 `applyBlock` 后 `tmpSession.commit()` | `stageBlockEnd` 必须在 canonical commit 成功后、archive batch flush 前 |
| `Manager.java:1017-1024` | `eraseBlock` 中 `khaosDb.pop()` 后 `revokingStore.fastPop()` | root unwind 与 temporal unwind 同 batch |

### 8.2 新增文件

```text
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentBuilder.java
chainbase/src/main/java/org/tron/core/archive/commitment/DefaultCommitmentBuilder.java
chainbase/src/main/java/org/tron/core/archive/commitment/SparseMerkleTree.java
chainbase/src/main/java/org/tron/core/archive/commitment/SparseMerkleNode.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/DomainRootRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/LeafRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootKeyCodec.java
chainbase/src/main/java/org/tron/core/archive/commitment/RootRecordCodec.java
chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentProgressRecord.java
chainbase/src/main/java/org/tron/core/archive/commitment/LatestStateRootVerifier.java
```

### 8.3 root schema

S10/S11 最终 schema：

```text
ROOT_META("rootProgress") -> CommitmentProgressRecord
ROOT_BLOCK(blockNum, algorithmId) -> RootRecord global root
ROOT_DOMAIN(blockNum, algorithmId, domainId) -> DomainRootRecord
ROOT_NODE(nodeHash) -> SparseMerkleNode
ROOT_CURRENT(algorithmId, domainId/global) -> current root pointer
ROOT_LEAF(algorithmId, domainId, path32) -> LeafRecord
```

`ROOT_LEAF` key 是 `path32`，不是 canonical key：

```text
path32 = domainPath(algorithmId, domainId, canonicalKey)
LeafRecord = canonicalKey + keyHash + valueHash + leafHash
```

`keyHash` 只是 metadata/collision guard，不是 leaf lookup key。

### 8.4 CommitmentBuilder API

P0 API：

```text
RootRecord stageBlockEnd(BlockWriteSet blockWriteSet, ArchiveBatch batch)
void stageUnwindBlock(long blockNum, byte[] blockHash, ArchiveBatch batch)
RootRecord rebuildLatest(ReadOnlyArchiveRawStore rawStore)
ArchiveProof prove(StatePoint point, ArchiveDomain domain, byte[] canonicalKey) // PR9
```

不要回到旧口径：

```text
按单笔交易直接推进 root
把 root compute 和 block-end staging 拆成不可同批提交的两步
```

block-end root 需要整块 write-set，因为 same-block same-key 要 collapse 到 final afterValue。

### 8.5 sparse Merkle tree

选择 binary sparse Merkle tree：

```text
path = 256-bit domain path
leaf = hash(domainId, canonicalKey, normalizedValue)
branch = hash(left, right)
emptyHash[level] = archive-owned chain
```

约束：

1. node content-addressed immutable。
2. update 写新 nodes，不覆盖旧 nodes。
3. hot unwind 不删除 old nodes。
4. root value normalizer 统一 tombstone/zero/empty value。
5. node reader 必须先看 same-batch staged overlay，再看 raw store。

### 8.6 stageBlockEnd

流程：

```text
input BlockWriteSet
filter registry root-included domains
collapse same block same domain/key to final afterValue
for each domain:
  load ROOT_CURRENT domain root
  update SparseMerkleTree leaves
  write ROOT_NODE nodes
  write/delete ROOT_LEAF(path32)
  write ROOT_DOMAIN(blockNum, domainId)
build global root from domain roots sorted by domainId
write ROOT_BLOCK(blockNum)
write ROOT_CURRENT(global/domain)
write ROOT_META(rootProgress)
```

即使 block 没有 root-included state write，也要写 block root/progress，保证 block-end root 可查。

### 8.7 stageUnwindBlock

Hot unwind：

1. 校验 `ROOT_BLOCK(blockNum).blockHash`。
2. 删除或标记该 block 的 `ROOT_BLOCK/ROOT_DOMAIN` rows。
3. 用 parent block root record 恢复 `ROOT_CURRENT`。
4. 用 temporal history/changeset 反向恢复 changed rooted keys 的 `ROOT_LEAF(path32)` metadata，或在标记 OK 前 rebuild active leaf metadata。
5. 不删除 `ROOT_NODE` content-addressed nodes。
6. 更新 `ROOT_META(rootProgress)` 到 parent。

只恢复 `ROOT_CURRENT` 不够。下一块增量更新需要 `ROOT_LEAF` 的 leafCount/collision guard；metadata 与 restored root 不一致会导致后续 root 错。

### 8.8 rebuild verifier

`LatestStateRootVerifier`：

1. 扫描 `LATEST` rooted domains。
2. 按 registry root policy 过滤 key。
3. 使用同一 `RootValueNormalizer`。
4. 从 empty SMT rebuild domain roots/global root。
5. 比较 latest `ROOT_BLOCK`。
6. mismatch 进入 repair-needed，不静默覆盖。

### 8.9 不做事项

- 不写 `BlockHeader.raw.accountStateRoot`。
- 不修改 `txTrieRoot`。
- 不实现 Ethereum-compatible `eth_getProof`。
- 不默认持久化 every-tx root。
- 不拆独立 root DB。
- 不在 unwind 删除 content-addressed nodes。

### 8.10 验收

必须证明：

1. incremental root == rebuild latest root。
2. same block same key 多次写只影响 final value。
3. tombstone/zero normalizer 对 root 行为稳定。
4. `ROOT_LEAF(path32)` collision guard 生效。
5. empty block 也有 block root。
6. root rows 与 temporal rows 同 batch。
7. unwind 后 `ROOT_CURRENT` 和 `ROOT_LEAF` metadata 都回到 parent。
8. `commitment.enable=true` 时 temporal 必须 enabled。
9. `persistTxRoots=true` 在未实现 P0 时 fail fast。

## 9. 模块间硬契约

| 契约 | 生产者 | 消费者 | 说明 |
| --- | --- | --- | --- |
| `StatePoint` / `txNum` | 模块 01 | 模块 04/05/06 | 所有历史读、temporal、root 共用时间坐标 |
| `ArchiveExecutionContext` | 模块 01 | 模块 03 | Store hook 必须知道当前 block/tx |
| `ArchiveDomainRegistry` | 模块 02 | 模块 03/04/05/06 | 不允许下游硬编码 dbName/domain |
| `DomainWrite` | 模块 03 | 模块 04/06 | before/after、canonical key/value |
| `BlockWriteSet` | 模块 03 | 模块 04/06 | temporal 和 root 同批输入 |
| `ArchiveBatch` | 模块 04 | 模块 06 | same-batch overlay，delete/tombstone |
| `getAsOf` | 模块 04 | 模块 05/PR8/PR9 | 不允许 fallback latest |
| `RootRecord` | 模块 06 | PR9/debug/rebuild | sidecar root，不是 consensus root |

## 10. 推荐落地顺序

```text
S1: config + no-op ArchiveService + getDbName fix
S2: Manager lifecycle + in-memory txNum
S3: ArchiveDomainRegistry + codecs + inventory tests
S4: generic Store write collector + ContractStore special hook
S5: CONTRACT_STORAGE semantic hook
S6: ArchiveRawStore + key/value codecs + ArchiveBatch
S7: temporal apply/getAsOf/unwind/startup verifier
S8: ArchiveStateReader core
S9: JSON-RPC historical getBalance/getCode/getStorageAt
S10: sparse Merkle tree + root codecs
S11: CommitmentBuilder integration + rebuild verifier
```

PR8 historical `eth_call` 和 PR9 proof/debug API 在上述核心闭环后落地。

## 11. 最小端到端验收链

一条测试链应覆盖：

1. Block 1：创建 account，产生 `ACCOUNT` write。
2. Block 2：部署 contract，产生 `ACCOUNT/CONTRACT/CODE` write。
3. Block 3：写 storage slot，产生 `CONTRACT_STORAGE` semantic write。
4. Block 4：删除或写 zero storage，产生 tombstone。
5. 查询 block 1/2/3/4 的 balance/code/storage。
6. rebuild latest root 与 incremental root 一致。
7. switch fork unwind block 4，再 replay alternative block 4，archive progress/root 跟 canonical head 一致。

测试原则：

- 不新增 test skip、`@Ignore`、条件绕过或测试矩阵排除。
- archive disabled regression 必须覆盖。
- historical query 失败必须是明确错误，不 fallback latest。
- 文档中所有 source anchor 若因上游源码变更偏移，编码前重新 `rg` 确认。
