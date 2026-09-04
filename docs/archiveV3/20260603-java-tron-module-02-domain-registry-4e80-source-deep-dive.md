# 模块 02 ArchiveDomainRegistry：4e80 java-tron 源码对照细化

日期：2026-06-03

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

关联总表：[java-tron Archive：4e80 六模块源码对照细化](./20260603-java-tron-archive-4e80-six-modules-source-detail.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`，`git status --short` 为空，精确冲突标记扫描无命中。

## 1. 当前结论

ArchiveDomainRegistry 的职责不是列 enum，而是把 java-tron 多 Store 状态统一成 archive 可以持久化、读取和计算 sidecar root 的稳定 domain。

#6289 要求 historical `eth_getBalance`、`eth_getCode`、`eth_getStorageAt`、后续 historical `eth_call`。按当前 java-tron 执行状态闭包，P0 domain 应先覆盖：

```text
ACCOUNT
CONTRACT
CODE
CONTRACT_STORAGE
DYNAMIC_PROPERTIES
```

`ABI`、`CONTRACT_STATE`、delegation、market/order、witness/votes 等先进入 P1/P2 或 optional domain，不在 P0 对外承诺 root 范围内。

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

因此 registry 设计和后续代码落点可直接按当前 `4e80f8ffa9a2` 源码推进。后续 java-tron 分支切换后仍需重新跑 `rg` 和 `nl` 校验。

## 3. Store inventory 入口

`chainbase/src/main/java/org/tron/core/ChainBaseManager.java` 是 Store 聚合入口。P0/P1 需要关注这些字段：

| 源码 | Store | archive 分类 |
| --- | --- | --- |
| `ChainBaseManager.java:81` | `AccountStore` | P0 execution state |
| `ChainBaseManager.java:99` | `DynamicPropertiesStore` | P0 allowlist execution config |
| `ChainBaseManager.java:138` | `AbiStore` | P1 contract metadata |
| `ChainBaseManager.java:141` | `CodeStore` | P0 execution state |
| `ChainBaseManager.java:144` | `ContractStore` | P0 execution state |
| `ChainBaseManager.java:147` | `ContractStateStore` | P1 or PR8 historical call dependency |
| `ChainBaseManager.java:156` | `StorageRowStore` | P0 physical backing only，semantic domain 另采 |
| `ChainBaseManager.java:190` | `TransactionStore` | chain/tx index，非 execution state |
| `ChainBaseManager.java:218` | `BalanceTraceStore` | existing balance-history helper，非 canonical ACCOUNT |
| `ChainBaseManager.java:222` | `AccountTraceStore` | existing balance-history helper，非 canonical ACCOUNT |
| `ChainBaseManager.java:234` | `SectionBloomStore` | log/query index，非 execution state |

Registry 需要按 Store 类型和 dbName 做显式分类，不能“把所有 `TronStoreWithRevoking` 都进 root”。

## 4. P0 domain 表

| Domain | dbName/source | key | value | raw hook mode | root policy |
| --- | --- | --- | --- | --- | --- |
| `ACCOUNT` | `account` | 21-byte TRON address | `AccountCapsule.getData()` | `GENERIC_TRON_STORE` | included |
| `CONTRACT` | `contract` | 21-byte address | ABI 清理后的 `ContractCapsule.getData()` | `STORE_SPECIFIC` | included |
| `CODE` | `code` | 21-byte address | runtime bytecode | `GENERIC_TRON_STORE` | included |
| `CONTRACT_STORAGE` | semantic storage hook | `address21 || slot32 || keyVersion1` | 32-byte slot value or tombstone | `SEMANTIC_ONLY` | included |
| `DYNAMIC_PROPERTIES` | `properties` | allowlisted property key | raw bytes | `GENERIC_TRON_STORE_ALLOWLIST` | included by allowlist |

P0 不纳入：

| Store | 原因 |
| --- | --- |
| `block` / `block-index` / `trans` / `transactionRetStore` | 链数据和 receipt，不是 execution state domain |
| `recent-block` / `recent-transaction` / `section-bloom` | 查询索引或缓存 |
| `balance-trace` / `account-trace` | 余额历史辅助，不是 canonical state source |
| `abi` | 合约 metadata，historical `eth_getCode` 不需要 ABI |
| `contract-state` | 可能影响 future `eth_call`，但 P0 getters 不需要 |

## 5. raw hook mode 细化

### 5.1 `GENERIC_TRON_STORE`

通用 hook 入口：

| 源码 | 事实 |
| --- | --- |
| `TronStoreWithRevoking.java:78-80` | 当前 `getDbName()` 返回 `null`，S1 必须修 |
| `TronStoreWithRevoking.java:89-95` | `put(byte[] key, T item)` null guard 后 `revokingDB.put(key, item.getData())` |
| `TronStoreWithRevoking.java:97-99` | `delete(byte[] key)` 直接 `revokingDB.delete(key)` |
| `TronStoreWithRevoking.java:107-115` | `getUnchecked` 可读取 before value |

P0 可走 generic hook 的 Store：

| Store | 源码 | 说明 |
| --- | --- | --- |
| `AccountStore` | `AccountStore.java:68-88` | balance trace 后调用 `super.put` |
| `AccountStore` delete | `AccountStore.java:92-104` | balance trace 后调用 `super.delete` |
| `CodeStore` | `CodeStore.java:13-17` | 未重写 put/delete，走通用 Store |
| `DynamicPropertiesStore` | `DynamicPropertiesStore.java:261-264` | 继承通用 Store，但 root/history 必须 allowlist |

### 5.2 `STORE_SPECIFIC`

这些 Store 绕过了通用 `super.put`，generic hook 会漏：

| Store | 源码 | 为什么必须 store-specific |
| --- | --- | --- |
| `ContractStore` | `ContractStore.java:31-39` | 清 ABI 后直接 `revokingDB.put`，after value 必须是清 ABI 后 bytes |
| `AbiStore` | `AbiStore.java:27-32` | overload `put(byte[], byte[])` 直接写 `revokingDB` |
| `ContractStateStore` | `ContractStateStore.java:27-32` | 直接写 `revokingDB` |

Module 03 必须从 registry 读取 `RawHookMode.STORE_SPECIFIC`，不要在 collector 里硬编码这些 Store 名字。

### 5.3 `SEMANTIC_ONLY`

`storage-row` 不能直接作为 `CONTRACT_STORAGE` domain：

| 源码 | 事实 |
| --- | --- |
| `StorageRowStore.java:15-16` | DB name 是 `storage-row` |
| `StorageRowStore.java:20-23` | `get` 会把 physical row key 写回 capsule |
| `actuator/.../Storage.java:46-53` | physical key 是 addrHash 与 slot 片段组合，contractVersion 1 还会 hash slot |
| `actuator/.../Storage.java:96-105` | dirty row 为 zero 时 delete，否则 put |

physical `storage-row` key 不可逆回 `(address, slot)`，所以 registry 必须把它标成 `SEMANTIC_ONLY` 或 `IGNORE_RAW`。真正的 `CONTRACT_STORAGE` write 由 Module 03/05 在 VM `Storage` semantic hook 里产生。

## 6. DomainDescriptor 结构

建议 registry 输出这样的 descriptor：

```text
ArchiveDomainDescriptor:
  domain
  sourceDbName
  rawHookMode
  keyCodec
  valueCodec
  deleteCodec
  historyPolicy
  rootPolicy
  readerPolicy
  dynamicPropertyAllowlist?  // only DYNAMIC_PROPERTIES
```

关键规则：

- 下游只能通过 descriptor 判断是否采集、如何编码、是否进 root。
- `dbName` 是外部输入，不是 domain 名；同一 domain 可能来自 semantic hook 而没有单一 dbName。
- unknown dbName 默认 `IGNORE`，但必须记录 debug counter 或 warning，避免静默漏状态。

## 7. DynamicProperties allowlist

`properties` Store 太宽，不能全进 archive root。P0 allowlist 应只覆盖会影响 execution/historical call 的 key，例如：

```text
latest block header number/hash/timestamp
energy/resource calculation key
proposal/governance toggles used by VM or actuator validation
account state root toggle only作为现有功能参考，不作为 archive root 开关
```

本文不在这里定死完整 allowlist。实现前需要从 `DynamicPropertiesStore` 中逐个方法审计，按“影响执行状态/影响查询索引/纯统计”分类。P0 可以先用小 allowlist，并在 `ArchiveDomainRegistryTest` 中强制未分类 key 必须显式标注。

## 8. 和后续模块的契约

| 下游模块 | registry 提供什么 |
| --- | --- |
| Module 03 `ArchiveWriteCollector` | `dbName -> descriptor`、`rawHookMode`、key/value/tombstone 编码 |
| Module 04 `ArchiveTemporalStore` | domain id、history policy、delete encoding |
| Module 05 `ArchiveStateReader` | address/code/storage/dynamic property 的 decoder |
| Module 06 `CommitmentBuilder` | root policy、domain root ordering、normalized key codec |

如果 registry 不完整，下游就会硬编码 Store 名称，后续扩展到 P1/P2 domain 会变成高风险改动。

## 9. 第一版实现落点

新增类建议：

| 类 | package | 说明 |
| --- | --- | --- |
| `ArchiveDomain` | `org.tron.core.archive.domain` | domain enum/id |
| `ArchiveDomainDescriptor` | 同上 | domain 元数据 |
| `RawHookMode` | 同上 | `GENERIC_TRON_STORE/STORE_SPECIFIC/SEMANTIC_ONLY/IGNORE` |
| `ArchiveKeyCodec` | 同上 | domain key 编码接口 |
| `ArchiveValueCodec` | 同上 | domain value/tombstone 编码接口 |
| `ArchiveDomainRegistry` | 同上 | descriptor 查询接口 |
| `DefaultArchiveDomainRegistry` | 同上 | P0 registry 实现 |
| `ArchiveDomainRegistryTest` | test | inventory、unknown、root policy 测试 |

实现顺序：

1. 修 `TronStoreWithRevoking.getDbName()`，否则 generic hook 没法知道 dbName。
2. 写 `ArchiveDomain` 和 descriptor，不接 hook。
3. 注册 P0 domain，`storage-row` 标 `SEMANTIC_ONLY`。
4. 写 registry tests，确保 P0 Store 全覆盖、unknown 可诊断。
5. Module 03 再接 write collector。

## 10. 测试证据

最小测试必须证明：

| 测试 | 要证明 |
| --- | --- |
| P0 inventory | `account/contract/code/storage-row/properties` 都有明确 descriptor |
| raw hook mode | `contract/abi/contract-state` 不被误判成 generic |
| storage-row | raw `storage-row` 不生成 `CONTRACT_STORAGE` domain write |
| unknown dbName | 返回 `IGNORE` 且有诊断计数 |
| root policy | P0 root 只包含明确 included domain |
| dynamic allowlist | 未分类 dynamic key 不会默认进 root |
