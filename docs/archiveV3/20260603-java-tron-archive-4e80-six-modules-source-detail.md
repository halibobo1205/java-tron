# java-tron Archive：4e80 六模块源码对照细化

日期：2026-06-03

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`，`git status --short` 为空，精确冲突标记扫描无命中。

编码主入口：[java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)。

## 0. 本轮必须先记住的源码状态

当前 java-tron 工作区干净，且以下精确冲突标记扫描无命中：

```bash
git -C /Users/boson/IdeaProjects/java-tron rev-parse --short=12 HEAD
# 4e80f8ffa9a2

git -C /Users/boson/IdeaProjects/java-tron status --short
# no output

rg -n '^(<<<<<<< .+|=======$|>>>>>>> .+)' /Users/boson/IdeaProjects/java-tron
# no output
```

因此本文可以直接按当前 `4e80f8ffa9a2` 源码写模块落点。早前 `a771d440d9f7` 快照中的冲突标记结论已经过期。

## 1. Erigon V2/V3 模型到 TRON 的最小映射

Erigon 的 archive/state 模型可以压缩成四个可落地概念：

| Erigon 源码锚点 | 模型含义 | java-tron 映射 |
| --- | --- | --- |
| `db/kv/rawdbv3/txnum.go:60/204/238/247/297` | block 与全局 txNum 的双向映射，按 block 记录 maxTxNum | Module 01 记录 block logical txNum range，不只记录用户交易 |
| `db/kv/tables.go:696-705` | `AccountsDomain/StorageDomain/CodeDomain/CommitmentDomain` | Module 02 把 java-tron 多 DB Store 归一成 archive domain |
| `db/state/execctx/domain_shared.go:817` | `DomainPut(domain,k,v,txNum,prevVal)` | Module 03 收集 first-before/final-after 写集 |
| `db/kv/temporal/kv_temporal.go:490/541/565/582` | `RangeAsOf/GetLatest/HistorySeek/IndexRange` | Module 04 提供 `getAsOf`、latest、history、changeset |
| `execution/state/history_reader_v3.go:67/192/217/263` | historical reader 通过 txNum 读 account/storage/code | Module 05 提供 TRON historical state reader |
| `db/state/execctx/domain_shared.go:997-1028` | `ComputeCommitment` 基于 domain 更新计算 root | Module 06 基于 archive domains 计算 sidecar root |

TRON 不能照搬 Erigon 的 Ethereum account/storage trie 形状。java-tron 状态分散在 `account`、`contract`、`code`、`storage-row`、`properties`、`abi`、`contract-state` 等 Store；合约 storage 的物理 key 还不可逆。因此必须先做 Domain Registry，再做 Temporal Store 和 Commitment。

## 1.1 当前已拆分细化文档

| 模块 | 当前 4e80 细化文档 |
| --- | --- |
| Module 01 `ArchiveTxNumIndex` | [模块 01 ArchiveTxNumIndex：4e80 java-tron 源码对照细化](./20260603-java-tron-module-01-txnum-index-4e80-source-deep-dive.md) |
| Module 02 `ArchiveDomainRegistry` | [模块 02 ArchiveDomainRegistry：4e80 java-tron 源码对照细化](./20260603-java-tron-module-02-domain-registry-4e80-source-deep-dive.md) |
| Module 03 `ArchiveWriteCollector` | [模块 03 ArchiveWriteCollector：4e80 java-tron 源码对照细化](./20260603-java-tron-module-03-write-collector-4e80-source-deep-dive.md) |
| Module 04 `ArchiveTemporalStore` | [模块 04 ArchiveTemporalStore：4e80 java-tron 源码对照细化](./20260603-java-tron-module-04-temporal-store-4e80-source-deep-dive.md) |
| Module 05 `ArchiveStateReader` | [模块 05 ArchiveStateReader：4e80 java-tron 源码对照细化](./20260603-java-tron-module-05-state-reader-4e80-source-deep-dive.md) |
| Module 06 `CommitmentBuilder` | [模块 06 CommitmentBuilder：4e80 java-tron 源码对照细化](./20260603-java-tron-module-06-commitment-builder-4e80-source-deep-dive.md) |

## 2. Module 01：ArchiveTxNumIndex

### 2.1 java-tron 对照点

| java-tron 源码 | 当前事实 | 实现落点 |
| --- | --- | --- |
| `Manager.java:1266` | `pushBlock(final BlockCapsule block)` 是 fullnode block 推进入口 | begin/abort/commit lifecycle 从这里的 normal path 接入 |
| `Manager.java:1379-1381` | normal path 在 `ISession` 内 `applyBlock(newBlock, txs)` 后 `tmpSession.commit()` | archive 只能 pending 到 canonical commit 成功后 flush |
| `Manager.java:1034-1041` | `eraseBlock()` 里 `khaosDb.pop()` 后 `revokingStore.fastPop()` | archive unwind 在 `fastPop()` 成功后做 |
| `Manager.java:1142/1149` | fork replay 新分支 session commit | fork replay 要分配同样 txNum |
| `Manager.java:1185/1187` | fork 失败恢复原分支 session commit | recovery replay 也不能漏 archive |
| `Manager.java:1838` | `processBlock(BlockCapsule block, List<TransactionCapsule> txs)` 是 block 执行主体 | logical tx phase 在这里维护 |
| `Manager.java:1851/1854` | balance trace 初始化、block energy 清零 | `BLOCK_PREPARE` phase |
| `Manager.java:1873/1886` | 遍历 `block.getTransactions()` 并执行 `processTransaction` | `USER_TX(txIndex)` phase |
| `Manager.java:1906/1925` | reward 与 dynamic properties 更新 | `BLOCK_FINALIZE` phase |
| `Manager.java:1498/1521/1593-1597` | `processTransaction` 内 balance trace 有 begin/end | 只能参考，archive txNum 不依赖 balance trace |

这些 hook 必须挂在 canonical execution path 上，而不是只挂 `TransactionStore` 或 JSON-RPC 查询路径。

### 2.2 模块职责

`ArchiveTxNumIndex` 不保存状态值，只保存“某个 block 的哪些 logical phase 对应哪些全局 txNum”。P0 推荐定义：

```text
BlockPrepareTxNum
UserTxNum(blockNum, txIndex)
BlockFinalizeTxNum
BlockRange(blockNum) = [minTxNum, maxTxNum]
```

这样 reward、proposal、maintenance、dynamic properties 的系统写入不会被挤到某个用户交易里，也不会丢失历史查询时间点。

### 2.3 新增类建议

| 新增类 | package | 说明 |
| --- | --- | --- |
| `ArchiveTxNumIndex` | `org.tron.core.archive.txnum` | txNum 分配与 block range 查询接口 |
| `InMemoryArchiveTxNumIndex` | 同上 | S2 测试用最小实现 |
| `PersistentArchiveTxNumIndex` | 同上 | S6/S7 接入 temporal store 后落盘 |
| `ArchiveExecutionContext` | `org.tron.core.archive` | 当前 block/phase/txIndex/txNum thread-local 或显式上下文 |
| `ArchiveService` | `org.tron.core.archive` | Manager 调用的门面 |
| `NoopArchiveService` | 同上 | 默认关闭时无行为 |

### 2.4 实现顺序

1. 配置默认关闭，先让 `ArchiveService` 在 `storage.archive.enable=false` 时是 no-op。
2. 在 normal apply path：`beginBlock(block)` 放在 `applyBlock` 前，`commitBlock(block)` 放在 `tmpSession.commit()` 后，catch 中 `abortBlock(block)`。
3. 在 `processBlock` 里显式进入 `BLOCK_PREPARE`、每个 `USER_TX(txIndex)`、`BLOCK_FINALIZE`。
4. 在 `eraseBlock` 里拿到 old head 后，canonical `fastPop()` 成功再 `archiveService.unwindBlock(oldHeadBlock)`。
5. fork replay/recovery replay 调用同一套 begin/commit/abort，不走特殊分支。

### 2.5 测试证据

- normal block：txNum 顺序为 prepare、用户交易 0..N-1、finalize。
- 空块：仍有 prepare/finalize txNum。
- apply 抛异常：pending txNum 不落盘。
- fork erase：canonical fastPop 后 archive 也回退同一 block。
- fork replay/recovery replay：block range 连续，不出现 txNum gap。

## 3. Module 02：ArchiveDomainRegistry

### 3.1 java-tron 对照点

| java-tron 源码 | 当前事实 | domain 结论 |
| --- | --- | --- |
| `ChainBaseManager.java:81/144/141/156/99/138/147` | 注入 `AccountStore/ContractStore/CodeStore/StorageRowStore/DynamicPropertiesStore/AbiStore/ContractStateStore` | registry 从这些 Store 开始建 P0 inventory |
| `AccountStore.java:44` | DB name `account` | `ACCOUNT` domain |
| `ContractStore.java:21` | DB name `contract` | `CONTRACT` domain |
| `CodeStore.java:16` | DB name `code` | `CODE` domain |
| `StorageRowStore.java:15` | DB name `storage-row` | raw physical source，不能直接当 domain key |
| `DynamicPropertiesStore.java:261-264` | DB name `properties` | `DYNAMIC_PROPERTIES` allowlist |
| `AbiStore.java:18` | DB name `abi` | P1 或 contract info 辅助 domain |
| `ContractStateStore.java:17` | DB name `contract-state` | historical `eth_call` 可能需要，P0 可标 optional |
| `TronStoreWithRevoking.java:78-80` | `getDbName()` 当前返回 `null` | S1 必须修成代理底层 DB name |

### 3.2 P0 domain 表

| Domain | 来源 Store | key | value | raw hook mode |
| --- | --- | --- | --- | --- |
| `ACCOUNT` | `account` | address 21 bytes | `AccountCapsule.getData()` | `GENERIC_TRON_STORE` |
| `CONTRACT` | `contract` | address 21 bytes | ABI 清理后的 `ContractCapsule.getData()` | `STORE_SPECIFIC` |
| `CODE` | `code` | address 21 bytes | runtime bytecode | `GENERIC_TRON_STORE` |
| `CONTRACT_STORAGE` | semantic hook | address 21 + slot 32 + keyVersion 1 | slot value 32 bytes or tombstone | `SEMANTIC_ONLY` |
| `DYNAMIC_PROPERTIES` | `properties` | allowlisted property key | raw bytes | `GENERIC_TRON_STORE_ALLOWLIST` |

P1 再考虑 `ABI`、`CONTRACT_STATE`、market/order/delegation 等 domain。P0 目标是 issue #6289 的 historical `eth_getBalance/getCode/getStorageAt` 与后续 `eth_call` 的最小闭包。

### 3.3 registry 输出契约

`ArchiveDomainRegistry` 不只是 enum。它至少要输出：

```text
dbName -> DomainDescriptor
domain -> codec
domain -> rawHookMode
domain -> rootPolicy
domain -> historyPolicy
domain -> keyNormalizer
```

`rawHookMode` 是 Module 03 的关键输入，不能让 collector 自己硬编码 `contract`、`storage-row` 这些名字。

### 3.4 实现顺序

1. 先修 `TronStoreWithRevoking.getDbName()`，否则 registry 无法从 generic hook 识别 Store。
2. 新增 `ArchiveDomain`、`ArchiveDomainDescriptor`、`RawHookMode`。
3. 在 `ChainBaseManager` 或 archive bootstrap 中注册 P0 Store。
4. 对 `storage-row` 明确标 `SEMANTIC_ONLY` 或 `IGNORE_RAW`，避免把不可逆 physical key 写进 temporal domain。
5. unknown Store 默认 `IGNORE` 但要能 debug 计数，避免静默漏状态。

### 3.5 测试证据

- P0 Store 都有 descriptor。
- `contract`、`abi`、`contract-state` 被识别为 store-specific 或 optional，不被 generic hook 假装覆盖。
- `storage-row` raw write 不生成 `CONTRACT_STORAGE` domain write。
- unknown dbName 可诊断。

## 4. Module 03：ArchiveWriteCollector

### 4.1 java-tron 对照点

| java-tron 源码 | 当前事实 | collector 处理 |
| --- | --- | --- |
| `TronStoreWithRevoking.java:92-103` | 通用 `put/delete` 写 `revokingDB` | generic raw hook 主入口 |
| `AccountStore.java:68-88` | balance trace 后 `super.put`，再 account callback | generic hook 能采 `ACCOUNT` |
| `AccountStore.java:92-104` | delete trace 后 `super.delete` | generic hook 能采 account delete |
| `ContractStore.java:31-39` | 清 ABI 后直接 `revokingDB.put` | 必须 store-specific hook，且 after 值应是清 ABI 后 bytes |
| `AbiStore.java:27-32` | `put(byte[], byte[])` 直接写 `revokingDB` | 若纳入 P1，需要 store-specific hook |
| `ContractStateStore.java:27-32` | 直接写 `revokingDB` | 若纳入 PR8 historical call，需要 store-specific hook |
| `RepositoryImpl.java:638-646` | `saveCode` 写 cache，并更新 contract codeHash | 不在这里落最终 write-set |
| `RepositoryImpl.java:673-677` | `putStorageValue` 写 storage cache | 只能采 intent，最终以 storage commit 为准 |
| `RepositoryImpl.java:766-783` | root repository `commit()` 汇总各 cache | 写集最终出现在各 Store put 或 `Storage.commit()` |
| `Storage.java:46-53` | physical key 不可逆 | raw `storage-row` 不可用于 historical storage |
| `Storage.java:96-105` | dirty zero delete，非零 put | semantic storage hook 最佳位置 |

### 4.2 collector 语义

collector 输入是当前 `ArchiveExecutionContext` 的 txNum 和 domain write event。每个 `(domain,key,txNum)` 只保留：

```text
before = 第一次写之前的值
after = 当前 tx 结束时最后一次写后的值
deleted = after tombstone
```

同一交易内多次写同 key，不写多条 history。这样和 Erigon `DomainPut(..., txNum, prevVal)` 的压缩语义一致。

### 4.3 before 读取规则

- generic `put`：hook 在 `revokingDB.put` 前读 `getUnchecked(key)` 作为 before。
- generic `delete`：hook 在 `revokingDB.delete` 前读 before，after 为 tombstone。
- store-specific `ContractStore.put`：先构造清 ABI 后的 after，再读 before，再写 event。
- semantic storage：before 不能只读 physical row；需要用 `Storage.getValue(slot)` 或 rowCache 判断，最终 key 是 `(address, slot, keyVersion)`。

### 4.4 abort/retry 规则

java-tron 交易执行可能抛异常，block apply 也可能 catch 后 rethrow。collector 必须支持：

```text
beginTx(txNum)
recordWrite(...)
commitTx()
abortTx()
abortBlock()
```

`abortTx` 必须丢掉当前 tx pending writes；`abortBlock` 必须丢掉整个 block pending writes；不能把失败交易或失败 block 的写集落 temporal store。

### 4.5 测试证据

- 同 tx 同 key：first-before/final-after。
- put 后 delete：after tombstone。
- delete 后 put：before 为 tx 前值，after 为最终值。
- `ContractStore.put` 不漏。
- `storage-row` raw write 被忽略，semantic hook 产生 logical storage write。
- block abort 后没有 temporal rows。

## 5. Module 04：ArchiveTemporalStore

### 5.1 Erigon 对照

Erigon temporal API 不是“每个 block 拷贝全状态”。关键是：

| Erigon 源码 | 语义 |
| --- | --- |
| `kv_temporal.go:541/545` | `GetLatest(domain,key)` 取 latest |
| `kv_temporal.go:565/569` | `HistorySeek(domain,key,txNum)` 取 as-of |
| `kv_temporal.go:490/494` | `RangeAsOf(domain,from,to,txNum)` 支持 prefix/range |
| `kv_temporal.go:582/586` | `IndexRange` 通过 inverted index 找 changed txNums |

TRON P0 不需要立即实现 Erigon 的文件分段与 freezer，但必须保持 API 形状，为后续 segment/freeze 留接口。

### 5.2 java-tron 对照点

| java-tron 源码 | 当前事实 | temporal 实现含义 |
| --- | --- | --- |
| `LevelDbDataSourceImpl.java:404-418` | `value == null` 时 `batch.delete(key)` | `ArchiveBatch.delete` 可落 null tombstone |
| `RocksDbDataSourceImpl.java:301-309` | RocksDB batch 同样 null-delete | LevelDB/RocksDB 行为可统一 |
| `DbSourceInter` / wrapper `flush(Map)` | 现有 DB 层已有 batch flush | P0 可封装 single physical archive DB |
| `Manager.java:1379-1381` | canonical commit 成功点 | `commitBlock` 在这里之后写 archive batch |
| `Manager.java:1034-1041` | canonical unwind 点 | `unwindBlock` 与 temporal progress 同步回退 |

### 5.3 P0 物理表设计

P0 推荐一个物理 archive DB，多 logical table prefix：

| Prefix | 表 | key | value |
| --- | --- | --- | --- |
| `0x01` | latest | domain + key | latest value or absent |
| `0x02` | history | domain + key + txNumDesc | before value/tombstone |
| `0x03` | changeset | domain + txNum + key | before/after summary |
| `0x04` | txnum | blockNum | min/max/finalize txNum |
| `0x05` | progress | singleton/domain | last committed block/txNum/root |
| `0x06` | roots | blockNum | sidecar root |

latest、history、changeset、txnum、progress、root 必须同 batch。否则 crash 后会出现 reader 看到 latest 已变但 txnum/progress 未推进，或者 root 对不上 history 的状态。

### 5.4 `getAsOf` 语义

对某个 `(domain,key,asOfTxNum)`：

1. 若 latest 的 lastTxNum <= asOfTxNum，返回 latest。
2. 否则在 history 中找第一个 `txNum > asOfTxNum` 的 before 值。
3. before 是 tombstone 则返回 absent/zero。
4. missing storage 对 JSON-RPC 返回 zero 32 bytes；missing account/code 按接口语义返回 0/`0x`。

### 5.5 unwind 语义

`unwindBlock(blockNum)` 不能只删 history。它需要用 changeset 反向恢复 latest：

```text
for txNum desc in block range:
  for each change:
    latest[domain,key] = before or delete
    delete history/change rows for txNum
delete txnum[blockNum]
progress = previous block
delete root[blockNum]
```

### 5.6 测试证据

- latest/history/changeset/progress 同 batch 可恢复。
- crash 模拟：progress 不推进时 reader 不读半提交 block。
- unwind 后 latest 与 as-of 都回到前一 block。
- tombstone 语义对 account/code/storage 都正确。
- LevelDB/RocksDB fake raw store 都覆盖 null-delete。

## 6. Module 05：ArchiveStateReader

### 6.1 java-tron JSON-RPC 对照点

| java-tron 源码 | 当前事实 | historical reader 接法 |
| --- | --- | --- |
| `TronJsonRpc.java:90-108` | `eth_getBalance`、`eth_getStorageAt`、`eth_getCode` 的接口映射 | P0 只接这三个 historical getter |
| `TronJsonRpcImpl.java:387-397` | `requireLatestBlockTag` 只接受 latest；tag/quantity 都拒绝 | non-latest 分支应绕过该 guard，改用 state point resolver |
| `TronJsonRpcImpl.java:457-470` | `getTrxBalance` latest 走 `wallet.getAccount` | non-latest 走 `ArchiveStateReader.getBalance` |
| `Wallet.java:332-352` | `wallet.getAccount` 从 latest `AccountStore` 读并更新 usage | historical 不能调用它 |
| `TronJsonRpcImpl.java:611-631` | `getStorageAt` latest 构造 VM `Storage` + `StorageRowStore` | historical 不能读 latest `StorageRowStore` |
| `TronJsonRpcImpl.java:635-649` | `eth_getCode` 映射到 `getABIOfSmartContract`，实际返回 runtime code | non-latest 走 `ArchiveStateReader.getCode` |
| `Wallet.java:3179-3198` | `getContract` 从 latest `AccountStore/ContractStore/AbiStore` 读 | historical contract 不能调用 |
| `Wallet.java:3208-3241` | `getContractInfo` 从 latest Store 读 runtime code 和 contract-state | historical code 不能调用 |
| `TronJsonRpcImpl.java:1001-1044` | `eth_call` object block 参数验证后仍强制 latest；string 参数也走 latest guard | historical `eth_call` 后置 PR8 |

RPC 接入前需要再次确认 `requireLatestBlockTag` 的调用链，因为 historical getter 要走 non-latest 分支，而 latest 分支应保持现有行为。

### 6.2 StatePointResolver

Module 05 需要一个独立 resolver：

```text
latest -> LatestStatePoint
earliest -> block 0 finalize txNum
finalized -> wallet.getSolidBlockNum() 对应 block finalize txNum
safe/pending -> 当前 JsonRpcApiUtil 明确 unsupported，P0 不静默支持
0xN -> block N finalize txNum
```

不要在 reader 内直接解析字符串，也不要让 RPC 方法各自解析。resolver 输出统一的 `ArchiveStatePoint(blockNum, txNum, tag)`。

### 6.3 Reader API

```text
getAccount(address21, statePoint) -> AccountCapsule/null
getBalance(address21, statePoint) -> long
getCode(address21, statePoint) -> byte[]
getStorage(address21, slot32, statePoint) -> byte[32]
getContract(address21, statePoint) -> ContractCapsule/null
```

`getStorage` 的 key 必须和 Module 03 semantic hook 一致：`address21 || slot32 || keyVersion1`。keyVersion 来自合约版本/TVM storage 语义，不能临时拼 physical `storage-row` key。

### 6.4 RPC 行为

| RPC | latest | historical |
| --- | --- | --- |
| `eth_getBalance` | 保留现有 `wallet.getAccount` 路径 | `ArchiveStateReader.getBalance`，missing 返回 `0x0` |
| `eth_getCode` | 保留现有 `Wallet.getContractInfo` 路径 | `ArchiveStateReader.getCode`，missing 返回 `0x` |
| `eth_getStorageAt` | 保留现有 `StorageRowStore` 路径 | `ArchiveStateReader.getStorage`，missing 返回 32-byte zero |
| `eth_call` | 保留 latest | PR8 用 archive-backed Repository，不在 P0 静默 fallback latest |

### 6.5 测试证据

- 同一地址余额在 block N/N+1 改变，查 N 返回旧值，查 latest 返回新值。
- 合约 code 变更或创建前后，historical code 不走 latest。
- storage slot 在同一 block 多次改，block finalize as-of 返回最终值，前一 block 返回旧值。
- 非 latest block 不再触发 `requireLatestBlockTag` 错误。
- archive disabled 时 historical 参数返回明确 unsupported，不 fallback latest。

## 7. Module 06：CommitmentBuilder

详细源码对照见：[模块 06 CommitmentBuilder：4e80 java-tron 源码对照细化](./20260603-java-tron-module-06-commitment-builder-4e80-source-deep-dive.md)。

### 7.1 java-tron 对照点

| java-tron 源码 | 当前事实 | archive root 结论 |
| --- | --- | --- |
| `Tron.proto:513` | `BlockHeader.raw.accountStateRoot` 已存在 | P0 不写这个字段 |
| `BlockCapsule.java:233-244` | `validateMerkleRoot()` 已存在，校验的是交易 `txTrieRoot` | archive root 不接入该校验 |
| `BlockCapsule.java:255-258` | `setAccountStateRoot(byte[] root)` 写 block header raw | archive root 不调用它 |
| `common/src/main/resources/reference.conf:812` | `allowAccountStateRoot = 0` 默认关闭 | 这是现有 account state root 配置，不是 archive root 开关 |
| `DynamicPropertiesStore.java:2375-2389` | `allowAccountStateRoot` governance 开关 | 这是现有 account state root，不是 archive root 开关 |
| `AccountStateCallBack.java:52-72` | 用 parent header root 构造 `TrieImpl` | 现有 trie 只覆盖 account callback 语义 |
| `AccountStateCallBack.java:94-105` | 生成 block 时写 accountStateRoot | archive sidecar root 不进共识 block |
| `TrieImpl.java:33/292` | framework TrieImpl，empty root 为 Ethereum trie 空根 | chainbase archive 不直接依赖 framework trie |

### 7.2 root 范围

P0 root 是 archive sidecar root，覆盖 Module 02 P0 domains：

```text
ACCOUNT
CONTRACT
CODE
CONTRACT_STORAGE
DYNAMIC_PROPERTIES allowlist
```

它证明 archive temporal state 自洽，不证明 java-tron 共识 header。后续如果要把 root 变成共识字段，是单独的 governance/compatibility 议题。

### 7.3 builder 输入输出

输入：

```text
blockNum
finalizeTxNum
changed DomainWrite list
previousRoot
ArchiveTemporalStore latest view
DomainRegistry codecs
```

输出：

```text
ArchiveRoot(blockNum, rootHash, domainRootHashes, txNum, codecVersion)
```

root rows 与 temporal commit 同 batch 写入。rebuild verifier 从 genesis 或 checkpoint 重放 temporal changeset，重算每个 block root，与 sidecar root 对比。

### 7.4 key 编码

root key 必须带 domain 前缀，避免不同 domain key 空间碰撞：

```text
domainId || normalizedDomainKey
```

`CONTRACT_STORAGE` 使用 logical key，不使用 `storage-row` physical key。`DYNAMIC_PROPERTIES` 只包含 registry allowlist 内 key，避免把非 VM/state 统计量混进 root。

### 7.5 测试证据

- 同一批 domain writes 不同输入顺序 root 一致。
- put/delete/tombstone root 可逆。
- rewind block 后 root 回到前一 block。
- rebuild verifier 能发现 latest/history/root 任意一处损坏。
- 不调用 `BlockCapsule.setAccountStateRoot`。

## 8. 模块间契约总表

| 上游 | 下游 | 契约 |
| --- | --- | --- |
| Module 01 | Module 03/04/05/06 | 每个 write 必须带稳定 txNum；block finalize 有 txNum |
| Module 02 | Module 03/04/06 | domain/key/value/root policy 只从 registry 取 |
| Module 03 | Module 04/06 | write-set 已压缩成 first-before/final-after |
| Module 04 | Module 05/06 | `getAsOf`、latest、changeset、progress 同 batch 可信 |
| Module 05 | JSON-RPC/PR8 | historical reader 不读 latest Store，不 silent fallback |
| Module 06 | verifier/debug API | sidecar root 只证明 archive state，不是共识 header |

## 9. 第一批实现建议

在当前源码冲突清理后，第一批只做 S1/S2/S3，不直接碰 temporal/root：

1. `StorageConfig.ArchiveConfig` + `Storage.ArchiveConfig` runtime 字段 + `reference.conf` 默认关闭。
2. `Args.applyStorageConfig` 桥接 archive config。
3. `TronStoreWithRevoking.getDbName()` 返回真实底层 DB name。
4. `ArchiveService` + no-op implementation。
5. `Manager` normal/fork/recovery/erase/processBlock lifecycle hook。
6. `ArchiveDomainRegistry` 注册 P0 domain，并用测试锁定 raw hook mode。

这一批完成后再进入 Module 03/04，否则 collector 没有稳定 txNum 与 domain policy，temporal store 会被迫硬编码 java-tron Store 名称。
