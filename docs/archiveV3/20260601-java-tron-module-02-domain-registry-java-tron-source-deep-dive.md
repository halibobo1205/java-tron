# 模块 02 ArchiveDomainRegistry：java-tron 源码对照

日期：2026-06-01

> 2026-06-03 更新：本文是旧 `a79693e450` 源码对照，当前实现请改看 [模块 02 ArchiveDomainRegistry：4e80 java-tron 源码对照细化](./20260603-java-tron-module-02-domain-registry-4e80-source-deep-dive.md)。当前本地 java-tron 为 `4e80f8ffa9a2`，精确冲突标记扫描无命中，旧行号不可直接用于编码。

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

关联设计：[java-tron Archive 模块 02：ArchiveDomainRegistry 细化设计](./20260521-java-tron-archive-module-02-domain-registry.md)

Erigon 对照：[模块 02 ArchiveDomainRegistry：Erigon 源码对照深挖](./20260525-java-tron-module-02-domain-registry-erigon-source-deep-dive.md)

逐文件实现清单：[java-tron Archive 模块 02：ArchiveDomainRegistry 逐文件 Patch 清单](./20260602-java-tron-archive-module-02-domain-registry-patch-checklist.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 旧文档原始基线：`a79693e450`，当前 4e80 实现请看页头链接。

## 1. 结论

java-tron 的执行状态分散在大量 `Store` 中，Archive 不能只覆盖 `AccountStore`。#6289 明确要求历史 `eth_getBalance`、`eth_getCode`、`eth_getStorageAt`、`eth_call`，并指出 TRON 状态分散在多 DB、stateRoot 不进入共识；因此 Module 02 必须把 domain 明确定义成“可执行状态的一组稳定 key/value 命名空间”，并为每个 domain 固定：

```text
source store
canonical key
canonical value
delete encoding
history policy
root policy
reader policy
```

源码证据显示，第一阶段最小可用集合应至少包括：

```text
ACCOUNT
CONTRACT
CODE
CONTRACT_STORAGE
DYNAMIC_PROPERTIES
```

其中 `ACCOUNT` 是账户主体，`CONTRACT/CODE/CONTRACT_STORAGE` 是 TVM 执行最小闭包，`DYNAMIC_PROPERTIES` 是资源/费用/网络参数闭包。其他 store 可以按 P1/P2 逐步纳入，但不能在 root 对外承诺之前遗漏会影响执行的 store。

## 2. java-tron Store 总清单入口

`ChainBaseManager` 是 Store 依赖聚合入口。

关键源码：

| 位置 | Store |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/ChainBaseManager.java:81` | `AccountStore` |
| `ChainBaseManager.java:84` | `AccountAssetStore` |
| `ChainBaseManager.java:87` | `BlockStore` |
| `ChainBaseManager.java:90` | `WitnessStore` |
| `ChainBaseManager.java:93` | `AssetIssueStore` |
| `ChainBaseManager.java:96` | `AssetIssueV2Store` |
| `ChainBaseManager.java:99` | `DynamicPropertiesStore` |
| `ChainBaseManager.java:102` | `BlockIndexStore` |
| `ChainBaseManager.java:105` | `AccountIdIndexStore` |
| `ChainBaseManager.java:108` | `AccountIndexStore` |
| `ChainBaseManager.java:111` | `WitnessScheduleStore` |
| `ChainBaseManager.java:114` | `VotesStore` |
| `ChainBaseManager.java:117` | `ProposalStore` |
| `ChainBaseManager.java:120` | `ExchangeStore` |
| `ChainBaseManager.java:123` | `ExchangeV2Store` |
| `ChainBaseManager.java:126` | `MarketAccountStore` |
| `ChainBaseManager.java:129` | `MarketOrderStore` |
| `ChainBaseManager.java:132` | `MarketPairPriceToOrderStore` |
| `ChainBaseManager.java:135` | `MarketPairToPriceStore` |
| `ChainBaseManager.java:138` | `AbiStore` |
| `ChainBaseManager.java:141` | `CodeStore` |
| `ChainBaseManager.java:144` | `ContractStore` |
| `ChainBaseManager.java:147` | `ContractStateStore` |
| `ChainBaseManager.java:150` | `DelegatedResourceStore` |
| `ChainBaseManager.java:153` | `DelegatedResourceAccountIndexStore` |
| `ChainBaseManager.java:156` | `StorageRowStore` |
| `ChainBaseManager.java:159` | `NullifierStore` |
| `ChainBaseManager.java:162` | `ZKProofStore` |
| `ChainBaseManager.java:166` | `IncrementalMerkleTreeStore` |
| `ChainBaseManager.java:178` | `DelegationStore` |
| `ChainBaseManager.java:186` | `CommonStore` |
| `ChainBaseManager.java:190` | `TransactionStore` |
| `ChainBaseManager.java:193` | `TransactionRetStore` |
| `ChainBaseManager.java:196` | `RecentBlockStore` |
| `ChainBaseManager.java:199` | `RecentTransactionStore` |
| `ChainBaseManager.java:202` | `TransactionHistoryStore` |
| `ChainBaseManager.java:214` | `PbftSignDataStore` |
| `ChainBaseManager.java:218` | `BalanceTraceStore` |
| `ChainBaseManager.java:222` | `AccountTraceStore` |
| `ChainBaseManager.java:230` | `TreeBlockIndexStore` |
| `ChainBaseManager.java:234` | `SectionBloomStore` |

DomainRegistry 不能机械纳入所有 Store。需要分清：

- 执行状态：会影响交易执行、RPC state、root/proof。
- 索引状态：帮助查询，不应进入 state root。
- 历史辅助状态：已有历史特性，不应作为 canonical source。
- block/tx 数据：链数据，不是账户状态树 domain。

### 2.1 当前源码下的 hook 分类结论

`ArchiveDomainRegistry` 需要把 `ChainBaseManager` inventory 和真实写入口统一成 `RawHookMode`。当前 `a79693e450` 源码下，P0/P1 的关键分类如下：

| dbName | Store | Domain | RawHookMode | 源码依据 |
| --- | --- | --- | --- | --- |
| `account` | `AccountStore` | `ACCOUNT` | `GENERIC_TRON_STORE` | `AccountStore.java:68-88` 最终调用 `super.put`，`TronStoreWithRevoking.java:88-93` 可采集真实落盘 bytes |
| `code` | `CodeStore` | `CODE` | `GENERIC_TRON_STORE` | `CodeStore` 未重写 `put/delete`，地址到 bytecode 的写入走通用 Store |
| `properties` | `DynamicPropertiesStore` | `DYNAMIC_PROPERTIES` | `GENERIC_TRON_STORE` | `DynamicPropertiesStore` 继承通用 `put`，但 root 必须做 key allowlist |
| `contract` | `ContractStore` | `CONTRACT` | `STORE_SPECIFIC` | `ContractStore.java:31-39` 清 ABI 后直接 `revokingDB.put`，通用 hook 会漏写 |
| `abi` | `AbiStore` | `ABI` | `STORE_SPECIFIC` | `AbiStore.java:27-32` 使用 `put(byte[], byte[])` overload 并直接写 `revokingDB` |
| `contract-state` | `ContractStateStore` | `CONTRACT_STATE` | `STORE_SPECIFIC` | `ContractStateStore.java:27-32` 直接 `revokingDB.put`，是否进 P0 root 需 PR8 前确认 |
| `storage-row` | `StorageRowStore` | `CONTRACT_STORAGE` | `SEMANTIC_ONLY` | `Storage.java:46-53` 物理 row key 由 addrHash/slot 组合，不能作为 archive logical key |
| `BlockStore` / `TransactionStore` / `TransactionRetStore` | block/tx stores | none | `IGNORED` | 链数据或 receipt 数据，不是 execution state domain |
| `BlockIndexStore` / `AccountIndexStore` / `SectionBloomStore` | index/cache stores | none | `IGNORED` | 查询索引，不应进入 state root |
| `BalanceTraceStore` / `AccountTraceStore` | history helper stores | none | `IGNORED` 或 history aid | 已有余额历史辅助，不是 canonical ACCOUNT source |

这个分类直接约束后续模块：

- S4 的 raw collector 只能对 `GENERIC_TRON_STORE` 直接生成 raw store event。
- S4 必须为 `STORE_SPECIFIC` Store 提供方法内 hook，且 `ContractStore` 的 hook 必须在清 ABI 后。
- S5 必须为 `SEMANTIC_ONLY` 的 storage 写提供 `(address, slot)` 语义事件，不能从 `storage-row` raw key 反推。
- S6/S7/S8/S11 只能消费 registry 暴露的 domain/key/value codec，不应重新硬编码 dbName。

## 3. P0 Domain 源码对照

### 3.1 ACCOUNT

关键源码：

| 位置 | 作用 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/store/AccountStore.java:25` | `AccountStore` |
| `AccountStore.java:44-45` | DB 名 `account` 并调用 `super(dbName)` |
| `AccountStore.java:68` | 重写 `put`，记录余额历史和 account state callback |
| `AccountStore.java:92` | 重写 `delete` |
| `actuator/src/main/java/org/tron/core/actuator/TransferActuator.java:55` | 直接 `accountStore.put(toAddress, toAccount)` |
| `actuator/src/main/java/org/tron/core/vm/repository/Repository.java:26` | `getAccount` |
| `Repository.java:62` | `updateAccount` |
| `actuator/src/main/java/org/tron/core/vm/repository/RepositoryImpl.java:948` / `954` | `commitAccountCache` 最终调 `AccountStore.put` |

Domain 定义建议：

```text
domain = ACCOUNT
sourceStore = account
canonicalKey = raw TRON account address bytes
canonicalValue = Account protobuf bytes
deleteValue = tombstone
history = true
root = true
reader = true
```

注意：

- 当前 `AccountStateEntity` 只抽取 `address/balance/allowance`，不能作为 Archive ACCOUNT canonical value。
- `AccountStore.put` 已经有 account state root 回调，但只覆盖账户，不覆盖 code/storage/contract。
- 很多 actuator 直接写 `AccountStore`，不能只 hook `RepositoryImpl`。

### 3.2 CONTRACT

关键源码：

| 位置 | 作用 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/store/ContractStore.java:18` | `ContractStore` |
| `ContractStore.java:21-22` | DB 名 `contract` 并调用 `super(dbName)` |
| `ContractStore.java:26` | `get` |
| `ContractStore.java:31` | `put` |
| `ContractStore.java:36-39` | 写入前清空 ABI 后直接 `revokingDB.put` |
| `ContractStore.java:52` | `findContractByHash` |
| `framework/src/main/java/org/tron/core/Wallet.java:3205` | `getContract` |
| `framework/src/main/java/org/tron/core/Wallet.java:3234` | `getContractInfo` |

Domain 定义建议：

```text
domain = CONTRACT
sourceStore = contract
canonicalKey = contract address bytes
canonicalValue = SmartContract protobuf bytes after java-tron canonical store transform
deleteValue = tombstone
history = true
root = true
reader = true
```

特殊点：

- `ContractStore.put` 会清空 ABI 后落盘。Archive 如果 hook 在 `ContractStore.put` 外层，需要记录实际落盘值，不是调用方传入的未变换对象。
- ABI 是否进入 archive state root 要单独决策。按当前 `ContractStore` 行为，合约主体 store 不保存 ABI；ABI 如果需要历史查询，应作为 `ABI` 独立 domain，而不是混入 `CONTRACT`。

### 3.3 CODE

关键源码：

| 位置 | 作用 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/store/CodeStore.java:13` | `CodeStore` |
| `CodeStore.java:16` | DB 名 `code` |
| `CodeStore.java:21` | `get` |
| `CodeStore.java:29` | `findCodeByHash` |
| `actuator/src/main/java/org/tron/core/vm/repository/Repository.java:82` | `saveCode` |
| `Repository.java:84` | `getCode` |
| `actuator/src/main/java/org/tron/core/vm/repository/RepositoryImpl.java:625` | `saveCode` |
| `RepositoryImpl.java:650` | `getCode` |
| `RepositoryImpl.java:960` / `966` | `commitCodeCache` 最终调 `CodeStore.put` |

Domain 定义建议：

```text
domain = CODE
sourceStore = code
canonicalKey = contract address bytes
canonicalValue = deployed bytecode bytes
deleteValue = tombstone
history = true
root = true
reader = true
```

注意：

- `CodeStore.findCodeByHash` 暗示代码可以通过 hash 查找，但 `RepositoryImpl.saveCode(address, code)` 仍按地址保存。
- `eth_getCode` 的历史读取必须能从 `CODE` domain 取指定状态点的 code。
- 若未来改为 code hash canonical key，需要同时修改 `CONTRACT` 中 codeHash 与 `CODE` key 的关系；第一阶段不建议增加这层复杂度。

### 3.4 CONTRACT_STORAGE

关键源码：

| 位置 | 作用 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/store/StorageRowStore.java:12` | `StorageRowStore` |
| `StorageRowStore.java:15-16` | DB 名 `storage-row` 并调用 `super(dbName)` |
| `StorageRowStore.java:20` | `get` |
| `actuator/src/main/java/org/tron/core/vm/repository/Repository.java:86` | `putStorageValue` |
| `Repository.java:88` | `getStorageValue` |
| `Repository.java:90` | `getStorage` |
| `actuator/src/main/java/org/tron/core/vm/repository/RepositoryImpl.java:660` | `putStorageValue` |
| `RepositoryImpl.java:681` | `getStorageValue` |
| `RepositoryImpl.java:1001` / `1008` | `commitStorageCache` 最终调 `Storage.commit()` |
| `actuator/src/main/java/org/tron/core/vm/program/Storage.java:46` | `compose(key, addrHash)` |
| `Storage.java:96` | `commit` |
| `Storage.java:100` | zero value 时 `store.delete(rowKey)` |
| `Storage.java:102` | 非 zero 时 `store.put(rowKey, row)` |

`Storage.compose` 的物理 key 不是简单 `address || slot`。源码逻辑是：

```text
rowKey = first 16 bytes of addrHash + last 16 bytes of key
if contractVersion == 1:
    key = sha3(key)
```

Domain 定义建议：

```text
domain = CONTRACT_STORAGE
sourceStore = storage-row
canonicalKey = contract address bytes || logical slot key bytes || storage key version
physicalKey = Storage.compose(logical slot, addrHash)
canonicalValue = 32-byte storage value
deleteValue = tombstone or zero
history = true
root = true
reader = true
```

关键决策：

- Archive domain key 应使用逻辑 `(contractAddress, slot)`，还是复用物理 `rowKey`？
- RPC `eth_getStorageAt` 需要按合约地址和 slot 查询，因此 reader 至少需要逻辑 key。
- 如果只存物理 key，会丢失 address/slot 可解释性，除非额外保存反向映射。

建议第一阶段：

```text
history key 使用 logical key: address || normalizedSlot
latest sidecar 可同时保存 physical key -> logical key 映射，便于从 Store hook 反查
root key 使用 logical key，避免受 java-tron 内部 rowKey 组合策略影响
```

### 3.5 DYNAMIC_PROPERTIES

关键源码：

| 位置 | 作用 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/store/DynamicPropertiesStore.java:30` | `DynamicPropertiesStore` |
| `DynamicPropertiesStore.java:241-243` | DB 名 `properties` 并调用 `super(dbName)` |
| `framework/src/main/java/org/tron/core/db/Manager.java:1441` | `updateDynamicProperties(block)` 方法定义 |
| `Manager.java:1910` | `processBlock` finalize 阶段调用 `updateDynamicProperties(block)` |
| `DynamicPropertiesStore.java:2190` | 保存 latest block timestamp |
| `DynamicPropertiesStore.java:2198` | 保存 latest block number |
| `DynamicPropertiesStore.java:2206` | 保存 latest block hash |
| `DynamicPropertiesStore.java:2355` | `saveAllowAccountStateRoot` |
| `DynamicPropertiesStore.java:2368` | `allowAccountStateRoot` |

Domain 定义建议：

```text
domain = DYNAMIC_PROPERTIES
sourceStore = properties
canonicalKey = property key bytes
canonicalValue = BytesCapsule bytes
deleteValue = tombstone
history = true
root = phase-dependent
reader = internal
```

这里要区分两类 key：

- 执行参数：会影响资源计算、TVM fork、费用、proposal 激活，应该进入 archive root。
- latest chain cursor：如 latest block header number/hash/timestamp，主要是节点执行索引，不一定应进入 state root。

建议 `ArchiveDomainRegistry` 对 `DYNAMIC_PROPERTIES` 支持 key-level root policy：

```text
rootIncluded(propertyKey) -> boolean
historyIncluded(propertyKey) -> boolean
```

否则把所有 `properties` key 都纳入 root 会把节点运行状态和共识执行状态混在一起。

## 4. P1 / P2 Domain 候选

### 4.1 资源、投票、治理

候选 Store：

```text
VotesStore
WitnessStore
WitnessScheduleStore
DelegatedResourceStore
DelegatedResourceAccountIndexStore
DelegationStore
ProposalStore
```

这些 Store 影响 TRON 资源、投票、奖励或治理。第一阶段若 root 只承诺 TVM/账户状态，可先不进入 root，但如果目标是“完整 TRON 执行状态 root”，它们必须纳入。

建议：

- P0 先 history=false/root=false，但记录源码清单。
- P1 给每个 Store 定义 domain descriptor。
- P2 决定是否纳入 global root。

### 4.2 TRC10 / Exchange / Market

候选 Store：

```text
AssetIssueStore
AssetIssueV2Store
AccountAssetStore
ExchangeStore
ExchangeV2Store
MarketAccountStore
MarketOrderStore
MarketPairPriceToOrderStore
MarketPairToPriceStore
```

这些 Store 对 TRC10、交易所、市场订单相关交易有执行影响。只支持 TVM 历史查询时可以延后，但 archive node 如果覆盖所有交易类型，不能长期遗漏。

### 4.3 隐私 / ZK / Merkle

候选 Store：

```text
NullifierStore
ZKProofStore
IncrementalMerkleTreeStore
```

这些 Store 的 key/value 语义需要单独调研，不应在没有测试向量前纳入 root。建议 P2 处理。

## 5. 不应作为 state domain 的 Store

| Store | 原因 |
| --- | --- |
| `BlockStore` | 区块数据，不是账户执行状态 |
| `BlockIndexStore` | 查询索引 |
| `TransactionStore` | 交易本体，不是 state trie domain |
| `TransactionRetStore` | 交易结果，不是 state |
| `TransactionHistoryStore` | 历史交易辅助 |
| `RecentBlockStore` | recent cache |
| `RecentTransactionStore` | recent cache |
| `BalanceTraceStore` | 已有余额历史辅助，不是 canonical state |
| `AccountTraceStore` | 已有余额历史辅助，不是 canonical state |
| `SectionBloomStore` | 日志检索索引 |

这些 Store 可以被 archive 系统使用，但不应进入 `CommitmentBuilder` 的 global root。

## 6. AccountStateRoot 现有模型的边界

java-tron 已有 account state root 管线：

| 位置 | 作用 |
| --- | --- |
| `protocol/src/main/protos/core/Tron.proto:514` | block header 字段 `accountStateRoot` |
| `framework/src/main/resources/config.conf:734` | `allowAccountStateRoot = 0` 默认关闭示例 |
| `framework/src/main/java/org/tron/core/config/args/Args.java:993-995` | 从 `committee.allowAccountStateRoot` 读取 runtime 参数 |
| `chainbase/src/main/java/org/tron/core/store/DynamicPropertiesStore.java:2355/2368` | 开关保存和读取 |
| `framework/src/main/java/org/tron/core/db/accountstate/callback/AccountStateCallBack.java:52` | block 前加载 parent root |
| `AccountStateCallBack.java:40` | tx finish 时 `trie.put` |
| `AccountStateCallBack.java:49` | delete account |
| `AccountStateCallBack.java:103` | `blockCapsule.setAccountStateRoot(newRoot)` |
| `framework/src/main/java/org/tron/core/db/accountstate/AccountStateEntity.java:16` | 只设置 address、balance、allowance |

这个模型不能直接替代 ArchiveDomainRegistry，原因是：

- 只覆盖 account，不覆盖 contract/code/storage。
- `AccountStateEntity` 不是完整 Account protobuf。
- 主要服务 block-level account root，不提供 tx-level domain history。
- delete path 是否完整接入所有账户删除语义需要额外验证。

可复用的是：

- `TrieImpl` 的 trie 操作经验。
- `AccountStateCallBack` 的 block/tx 回调位置。
- `allowAccountStateRoot` 开关的治理模式。

不能复用的是：

- 现有 account-only schema。
- 把 account root 当作 archive global root。

## 7. Registry Descriptor 建议

建议每个 domain 描述为不可变配置：

```java
record ArchiveDomainDescriptor(
    ArchiveDomain domain,
    String sourceDbName,
    CanonicalKeyCodec keyCodec,
    CanonicalValueCodec valueCodec,
    DeleteSemantics deleteSemantics,
    HistoryPolicy historyPolicy,
    RootPolicy rootPolicy,
    ReaderPolicy readerPolicy
) {}
```

其中：

```text
HistoryPolicy:
  DISABLED
  ENABLED_HOT_ONLY
  ENABLED_HOT_AND_COLD

RootPolicy:
  EXCLUDED
  INCLUDED_DOMAIN_ROOT
  INCLUDED_KEY_FILTERED

ReaderPolicy:
  INTERNAL_ONLY
  RPC_READABLE
  EXECUTION_READABLE
```

P0 descriptors：

| Domain | source DB | history | root | reader |
| --- | --- | --- | --- | --- |
| `ACCOUNT` | `account` | yes | yes | RPC + execution |
| `CONTRACT` | `contract` | yes | yes | RPC + execution |
| `CODE` | `code` | yes | yes | RPC + execution |
| `CONTRACT_STORAGE` | `storage-row` | yes | yes | RPC + execution |
| `DYNAMIC_PROPERTIES` | `properties` | yes | key-filtered | internal + execution |

## 8. 与 WriteCollector 的接口

`ArchiveWriteCollector` 不应该硬编码 Store 名称。它应调用 Registry：

```text
onStorePut(dbName, rawKey, rawValue):
    descriptor = registry.findByStore(dbName)
    if descriptor == null:
        ignore or warn
    canonicalKey = descriptor.keyCodec.encode(rawKey, context)
    canonicalValue = descriptor.valueCodec.encode(rawValue, context)
    emit(domain, canonicalKey, before, after)
```

对 `CONTRACT_STORAGE`，单纯 `rawKey` 不足以稳定恢复 logical slot。需要 `Storage`/`RepositoryImpl` 提供更语义化的 hook：

```text
onStoragePut(contractAddress, logicalSlot, physicalRowKey, value)
```

Registry 应允许一个 source DB 同时被低层 Store hook 和高层 semantic hook 识别。

## 9. 测试建议

### 9.1 Domain 覆盖测试

构造交易类型：

- 普通 TRX transfer：应写 `ACCOUNT`。
- 合约部署：应写 `ACCOUNT`、`CONTRACT`、`CODE`。
- 合约 storage 写：应写 `CONTRACT_STORAGE`。
- 资源/费用变化：应写 `ACCOUNT` 和相关 `DYNAMIC_PROPERTIES` 或资源 Store。

断言每个写入都能被 Registry 映射到 domain。

### 9.2 Canonical value 测试

对同一笔交易 replay 两次：

```text
canonicalKey/value bytes 必须一致
domain root 必须一致
```

特别测试：

- `ContractStore.put` 清 ABI 后的值。
- zero storage 删除。
- dynamic properties key filter。

### 9.3 未归类 Store 检测

Archive 开启时，对执行期间出现但未注册的 Store 写入计数。P0 可以允许部分 Store 被 ignore，但必须输出诊断：

```text
unclassified store write: store=votes key=...
```

进入 root 对外承诺前，诊断必须清零或有显式 ignore policy。

## 10. 实现优先级

P0：

- 建立 `ArchiveDomainRegistry` 和 P0 domain descriptors。
- 给 `TronStoreWithRevoking` 提供按 `dbName` 查 descriptor 的能力。
- 给 storage 写增加 semantic hook。
- 明确 `DYNAMIC_PROPERTIES` key-level policy。

P1：

- 扩展资源、投票、治理、TRC10 domain。
- 未归类 Store 写入诊断。
- domain schema version 持久化。

P2：

- ZK/privacy domain。
- domain root migration。
- root policy governance 开关。

## 11. 关键风险

1. 只纳入 `AccountStore` 会得到不完整 root，历史 `eth_getCode/eth_getStorageAt/eth_call` 也无法正确工作。
2. 把索引 Store 纳入 root 会让 root 包含非执行状态，重放稳定性变差。
3. 直接使用 `StorageRowStore` 物理 key 可能无法支撑 RPC 逻辑 slot 查询。
4. `ContractStore.put` 的 value 变换如果 hook 错位置，会导致 archive value 与真实落盘 value 不一致。
5. `DYNAMIC_PROPERTIES` 全量纳入 root 会混入 latest cursor 类节点状态，需要 key-level policy。
