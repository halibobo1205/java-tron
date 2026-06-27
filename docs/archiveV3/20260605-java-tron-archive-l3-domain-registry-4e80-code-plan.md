# java-tron Archive L3：ArchiveDomainRegistry 代码级执行包

日期：2026-06-05

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 当前基线：`4e80f8ffa9a2`

上游路线：[java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)

落地执行看板：[java-tron Archive：4e80 分阶段落地执行看板](./20260604-java-tron-archive-4e80-landing-readiness-board.md)

逐文件落点：[java-tron Archive：4e80 逐文件实现落点矩阵](./20260604-java-tron-archive-4e80-file-implementation-map.md)

前置 L1：[java-tron Archive L1：config/no-op/dbName 代码级执行包](./20260604-java-tron-archive-l1-config-noop-dbname-4e80-code-plan.md)

前置 L2：[java-tron Archive L2：Manager lifecycle + txNum 代码级执行包](./20260605-java-tron-archive-l2-manager-txnum-4e80-code-plan.md)

上游 S3 包：[java-tron Archive S3：ArchiveDomainRegistry 4e80 编码执行包](./20260603-java-tron-archive-s3-domain-registry-4e80-coding-packet.md)

模块调研：[模块 02 ArchiveDomainRegistry：4e80 java-tron 源码对照细化](./20260603-java-tron-module-02-domain-registry-4e80-source-deep-dive.md)

## 1. 本执行包定位

L3 的任务是建立 archive sidecar 的 schema 层：把 java-tron 的多 Store、物理 DB name、特殊写入口、动态属性 key 和后续 semantic storage 写入，统一映射为稳定 `ArchiveDomain`。

这一步不采集写集、不写 archive DB、不计算 root、不改 RPC。L3 只提供不可变 registry、codec、policy、coverage 和 checksum，让 L4 以后所有模块通过 registry 做判断，不能在 collector、temporal store、state reader、commitment builder 里继续硬编码 Store 名称。

完成后，下游模块必须只依赖这些问题的答案：

```text
dbName -> StoreBinding
ArchiveDomain -> ArchiveDomainDescriptor
ArchiveDomain -> CanonicalKeyCodec / CanonicalValueCodec
dynamic property key -> DynamicKeyPolicy
registry -> deterministic checksum
registry -> coverage report
```

## 2. Erigon 对照结论

Erigon V3 的状态域不是按底层表名自由拼接，而是围绕固定 domain 读写：

| Erigon 源码 | 事实 | java-tron L3 借鉴 |
| --- | --- | --- |
| `db/kv/tables.go:695-705` | `AccountsDomain`、`StorageDomain`、`CodeDomain`、`CommitmentDomain` 是稳定枚举；`StateDomains` 明确列出 state domain | java-tron 必须固定 `ArchiveDomain` id/name，不能用 Store class 或 dbName 直接进 root |
| `db/state/statecfg/state_schema.go:37-70` | aggregator 注册 domain，并声明 account/storage 对 commitment 的依赖 | java-tron registry 要声明 root/history/read policy，CommitmentBuilder 只消费 registry |
| `execution/state/rw_v3.go:232-300` | 写 account/code/storage 时按 domain 写入；storage key 是 address + slot 的 semantic composite key | java-tron `storage-row` raw key 不可逆，`CONTRACT_STORAGE` 必须来自 semantic hook |
| `execution/state/history_reader_v3.go:175-187` | archive 起点按 account/storage/code 历史域计算 | java-tron ArchiveStateReader 也应按 registry 的 P0 state domain 判断历史可用性 |
| `db/state/aggregator.go:2436-2458` | `RangeAsOf/GetAsOf/GetLatest` 都以 domain 为第一参数 | java-tron temporal/read/root API 必须以 `ArchiveDomain + canonicalKey` 为核心 |

因此，java-tron L3 不应照搬 Erigon 的 domain 数量；TRON 有 `CONTRACT`、`DYNAMIC_PROPERTIES`、`storage-row`、ABI、delegation、resource 等特有状态。但必须照搬核心分层：raw Store 只是输入，archive 真实 schema 是 domain。

## 3. 完成目标

L3 `DONE` 必须证明：

- P0 domain id/name 固定，且 id 唯一、非负、在 `0x0001..0xffff` 范围内。
- `account`、`contract`、`code`、`properties`、`storage-row` 这 5 个关键 dbName 都有明确 binding。
- `storage-row` 只能绑定为 `SEMANTIC_BACKING` / `SEMANTIC_ONLY`，不能产生 raw `CONTRACT_STORAGE` domain write。
- `contract` 使用 `STORE_SPECIFIC`，因为 `ContractStore.put` 清 ABI 后直接写 `revokingDB.put`。
- `abi`、`contract-state` 使用 `STORE_SPECIFIC` 或 P1/debug binding，不能被 generic hook 误判。
- `properties` 进入 `DYNAMIC_PROPERTIES` domain，但 root 输入必须按 key-level allowlist 控制。
- unknown dbName 返回 `UNKNOWN` binding，不能静默当作 excluded。
- canonical key/value codec 会校验长度、clone 输入输出，不暴露可变内部数组。
- `RegistryChecksum` 对 domain、binding、codec id、policy、dynamic key allowlist、excluded inventory 做稳定排序后计算。
- L4/S4 collector 可以只通过 `ArchiveDomainRegistry` 解析 raw/semantic write，不需要硬编码 Store 名称。

## 4. 非目标

L3 明确不做：

- 不修改 `TronStoreWithRevoking.put/delete`。
- 不修改 `Manager`、`processBlock`、replay、unwind。
- 不修改 TVM `Storage.commit()`。
- 不新增 `BlockWriteSet`、`TxWriteSet`、`DomainWrite`。
- 不写 `ArchiveTemporalStore`。
- 不新增 archive DB column family/table。
- 不改 JSON-RPC。
- 不计算 sidecar root，不写 root record。
- 不把 registry 注入到所有 Store。L3 只建立 schema，L4 再接 hook。

## 5. 前置条件

L3 开工前必须满足：

| 前置 | 依赖原因 |
| --- | --- |
| L1 `TronStoreWithRevoking.getDbName()` 可返回真实 DB name | L4 generic hook 需要通过 dbName 查 registry |
| L1 archive 默认关闭/no-op gate 通过 | registry 增加后默认不开启行为变化 |
| L2 `ArchiveExecutionContext` 与 txNum 接口稳定 | L4 collector 会把 registry mapping 写入当前 txNum |

如果 L1 尚未完成，L3 仍可先实现纯 registry/codec 单测，但不能声称可解锁 L4 hook。

## 6. 当前 java-tron 源码事实

### 6.1 Store inventory 入口

`chainbase/src/main/java/org/tron/core/ChainBaseManager.java` 是 Store 聚合入口：

| 源码 | Store | L3 分类 |
| --- | --- | --- |
| `ChainBaseManager.java:79-81` | `AccountStore accountStore` | P0 `ACCOUNT` |
| `ChainBaseManager.java:97-99` | `DynamicPropertiesStore dynamicPropertiesStore` | P0 `DYNAMIC_PROPERTIES`，key-level policy |
| `ChainBaseManager.java:136-138` | `AbiStore abiStore` | P1/debug，store-specific |
| `ChainBaseManager.java:139-141` | `CodeStore codeStore` | P0 `CODE` |
| `ChainBaseManager.java:142-144` | `ContractStore contractStore` | P0 `CONTRACT`，store-specific |
| `ChainBaseManager.java:145-147` | `ContractStateStore contractStateStore` | P1/historical VM candidate，store-specific |
| `ChainBaseManager.java:154-156` | `StorageRowStore storageRowStore` | physical backing only，semantic storage domain later |
| `ChainBaseManager.java:188-202` | transaction/recent/history stores | tx/index/cache，excluded from P0 state root |
| `ChainBaseManager.java:216-234` | balance/account trace, tree index, section bloom | existing helper/index，excluded from P0 state root |

### 6.2 Generic Store 写入口

`chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java`：

| 源码 | 事实 | L3 影响 |
| --- | --- | --- |
| `TronStoreWithRevoking.java:56-70` | 构造时根据 dbName 创建 LevelDB/RocksDB 并包装 Chainbase | dbName 是 raw hook 的唯一稳定输入之一 |
| `TronStoreWithRevoking.java:77-80` | 当前 `getDbName()` 返回 `null` | L1 必须修；L3 不能依赖当前 null 行为 |
| `TronStoreWithRevoking.java:88-95` | `put(byte[], T)` 统一写 `revokingDB.put(key, item.getData())` | `GENERIC_TRON_STORE` 的目标入口 |
| `TronStoreWithRevoking.java:97-99` | `delete(byte[])` 统一写 `revokingDB.delete(key)` | raw delete hook 后续从这里接 |
| `TronStoreWithRevoking.java:107-115` | `getUnchecked` 可读 before value | L4 collector 可用它补 before value |

### 6.3 P0/P1 Store 特殊行为

| Store | 源码 | 当前行为 | L3 binding |
| --- | --- | --- | --- |
| `AccountStore` | `AccountStore.java:68-88`、`92-104` | balance trace 后调用 `super.put/delete` | `ACCOUNT` + `GENERIC_TRON_STORE` |
| `CodeStore` | `CodeStore.java:13-17` | 未重写 put/delete | `CODE` + `GENERIC_TRON_STORE` |
| `DynamicPropertiesStore` | `DynamicPropertiesStore.java:261-263` | dbName `properties`，继承 generic put/delete | `DYNAMIC_PROPERTIES` + `GENERIC_TRON_STORE_ALLOWLIST` |
| `ContractStore` | `ContractStore.java:31-39` | 清 ABI 后直接 `revokingDB.put` | `CONTRACT` + `STORE_SPECIFIC` |
| `AbiStore` | `AbiStore.java:27-32` | overload `put(byte[], byte[])` 直接 `revokingDB.put` | P1/debug + `STORE_SPECIFIC` |
| `ContractStateStore` | `ContractStateStore.java:27-32` | 直接 `revokingDB.put` | P1/historical VM + `STORE_SPECIFIC` |
| `StorageRowStore` | `StorageRowStore.java:19-23` | `get` 把 physical row key 写回 capsule | physical backing，不能做 raw storage domain |

### 6.4 CONTRACT_STORAGE 必须 semantic-only

`actuator/src/main/java/org/tron/core/vm/program/Storage.java`：

| 源码 | 事实 | L3 规则 |
| --- | --- | --- |
| `Storage.java:46-53` | `compose(key, addrHash)` 生成 physical row key | physical key 不可逆回 `(contractAddress, slot)` |
| `Storage.java:47-49` | `contractVersion == 1` 时 slot 先 `Hash.sha3(key)` | semantic key 必须记录 `storageKeyVersion` |
| `Storage.java:61-70` | create2 场景 `addrHash` 可来自 `address || trxHash` | 不能从 physical row key 推合约地址 |
| `Storage.java:96-105` | dirty row 为 zero 时 delete，否则 put | tombstone 语义属于 S5 semantic hook |

L3 必须把 `storage-row` 绑定为：

```text
bindingKind = SEMANTIC_BACKING
rawHookMode = SEMANTIC_ONLY
domain = CONTRACT_STORAGE
rawWritesProduceDomainWrite = false
```

真正的 `CONTRACT_STORAGE` canonical key：

```text
contractAddress21 || slot32 || storageKeyVersion_u8
```

### 6.5 DynamicProperties 过宽，必须 key-level policy

`DynamicPropertiesStore.java:32-260` 定义了大量 key，包含 head cursor、治理参数、VM fork toggle、fee、统计值、迁移标记和 query index 游标。不能把整个 `properties` DB 默认纳入 root。

第一版 key 分类：

| Key family | 源码例子 | L3 policy |
| --- | --- | --- |
| head cursor | `LATEST_BLOCK_HEADER_TIMESTAMP/NUMBER/HASH` at `DynamicPropertiesStore.java:32-39` | `HISTORY_ONLY`，不进 root |
| resource/fee execution params | `ENERGY_FEE`、`TRANSACTION_FEE`、`MAX_CPU_TIME_OF_ONE_TX` at `DynamicPropertiesStore.java:73-86`、`1459-1576` | `IN_GLOBAL_ROOT` allowlist |
| TVM fork/config toggles | `ALLOW_TVM_*` at `DynamicPropertiesStore.java:136-144`、`171-175`、`215`、`233-243` | `IN_GLOBAL_ROOT` allowlist |
| dynamic energy params | `ALLOW_DYNAMIC_ENERGY`、`DYNAMIC_ENERGY_*` at `DynamicPropertiesStore.java:201-208`、`2747-2796` | `IN_GLOBAL_ROOT` allowlist |
| price history strings | `ENERGY_PRICE_HISTORY`、`BANDWIDTH_PRICE_HISTORY`、`MEMO_FEE_HISTORY` at `DynamicPropertiesStore.java:185-198`、`2623-2717` | `HISTORY_ONLY`，historical fee RPC 用，不进 root |
| migration markers | `ABI_MOVE_DONE`、`TURKISH_KEY_MIGRATION_DONE` | `EXCLUDED` or `HISTORY_ONLY_DIAGNOSTIC`，不进 root |
| pure counters/statistics | `TOTAL_TRANSACTION_COST`、`TRANSACTION_FEE_POOL` | 默认不进 root，是否 history 由后续 PR 决定 |

`actuator/src/main/java/org/tron/core/vm/config/ConfigLoader.java:23-49` 会读取多组 TVM config；这些 getter 对 historical VM 行为有影响，所以必须进入 dynamic allowlist 或至少进入 historical VM policy。

## 7. Patch 边界

### 7.1 允许新增

```text
chainbase/src/main/java/org/tron/core/archive/domain/
chainbase/src/main/java/org/tron/core/archive/codec/
chainbase/src/test/java/org/tron/core/archive/domain/
chainbase/src/test/java/org/tron/core/archive/codec/
```

### 7.2 允许 inspect/minimal modify

```text
chainbase/src/main/java/org/tron/core/archive/ArchiveService.java
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
```

只有在 L2 已经存在 `DefaultArchiveService` 并需要暴露 `ArchiveDomainRegistry getDomainRegistry()` 时才允许最小扩展。不要在 L3 把 registry 接入 Store hook。

### 7.3 禁止修改

```text
framework/src/main/java/org/tron/core/db/Manager.java
chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java
chainbase/src/main/java/org/tron/core/store/*Store.java
actuator/src/main/java/org/tron/core/vm/program/Storage.java
framework/src/main/java/org/tron/core/services/jsonrpc/*
```

如发现 S3 必须修改上述文件，说明 slice 边界越界，应回到 L4/S5。

## 8. 包结构

推荐结构：

```text
org.tron.core.archive.domain
  ArchiveDomain
  ArchiveDomainDescriptor
  ArchiveDomainRegistry
  ArchiveDomainRegistries
  DefaultArchiveDomainRegistry
  StoreBinding
  StoreBindingKind
  StoreCategory
  RawHookMode
  RootPolicy
  HistoryPolicy
  ReaderPolicy
  DynamicKeyPolicy
  DynamicKeyClass
  DynamicKeyDecision
  RegistryChecksum
  ArchiveCoverage
  ArchiveDomainException

org.tron.core.archive.codec
  CanonicalKeyCodec
  CanonicalValueCodec
  ArchiveDomainCodecs
  ContractStorageKeyCodec
  DynamicPropertyKeyCodec
  DomainValue
```

L3 首选纯 Java immutable 类，不强制 Spring `@Component`。L4/L5 需要注入时，再由 `ArchiveServiceFactory` 或 Spring config 暴露 singleton，避免 L3 改变默认运行时行为。

## 9. 核心类型设计

### 9.1 ArchiveDomain

固定 enum，不使用 enum ordinal 作为持久化 id。

```java
public enum ArchiveDomain {
  ACCOUNT(0x0001, "account"),
  CONTRACT(0x0002, "contract"),
  CODE(0x0003, "code"),
  CONTRACT_STORAGE(0x0004, "contract-storage"),
  DYNAMIC_PROPERTIES(0x0005, "dynamic-properties"),

  CONTRACT_STATE(0x0101, "contract-state"),
  ABI(0x0102, "abi"),
  ACCOUNT_ASSET(0x0103, "account-asset"),
  RESOURCE_DELEGATION(0x0104, "resource-delegation"),
  GOVERNANCE(0x0105, "governance"),
  MARKET(0x0106, "market"),
  SHIELDED_STATE(0x0107, "shielded-state");

  private final int id;
  private final String canonicalName;
}
```

规则：

- `0x0000` 保留给 global root。
- `0x0001..0x00ff` 是 P0 execution/root candidate。
- `0x0100..0x01ff` 是 P1/P2 或 history/debug/helper domain。
- public API 用 `int`，校验 `0 <= id <= 0xffff`，不要暴露 signed `short`。
- root aggregation 按 `id` 排序，不按 enum ordinal。
- `canonicalName` 只使用 lowercase ASCII、数字和 `-`。

### 9.2 Policies

```java
public enum RootPolicy {
  IN_GLOBAL_ROOT,
  DOMAIN_ROOT_ONLY,
  HISTORY_ONLY,
  EXCLUDED
}

public enum HistoryPolicy {
  FULL_HISTORY,
  LATEST_ONLY,
  CHECKPOINT_ONLY,
  NO_ARCHIVE
}

public enum ReaderPolicy {
  PUBLIC_STATE,
  HISTORICAL_VM,
  INTERNAL_ONLY,
  NOT_READABLE
}

public enum RawHookMode {
  GENERIC_TRON_STORE,
  GENERIC_TRON_STORE_ALLOWLIST,
  STORE_SPECIFIC,
  SEMANTIC_ONLY,
  IGNORE_RAW
}
```

策略含义：

| Policy | 下游影响 |
| --- | --- |
| `RootPolicy.IN_GLOBAL_ROOT` | S10/S11 global root 会消费该 domain/key |
| `RootPolicy.DOMAIN_ROOT_ONLY` | 可计算 domain root，但不进 global root |
| `RootPolicy.HISTORY_ONLY` | 只写 latest/history，不进 root |
| `RootPolicy.EXCLUDED` | 不采集 archive state |
| `HistoryPolicy.FULL_HISTORY` | temporal store 写 latest/history/changeset |
| `HistoryPolicy.LATEST_ONLY` | 只保留 latest，通常不满足 historical getter |
| `RawHookMode.SEMANTIC_ONLY` | raw Store hook 不产出 DomainWrite，等待 semantic hook |

### 9.3 StoreBinding

`StoreBinding` 是 raw dbName 的解析结果。unknown 必须是显式状态。

```java
public final class StoreBinding {
  private final String dbName;
  private final StoreBindingKind bindingKind;
  private final StoreCategory storeCategory;
  private final Optional<ArchiveDomain> domain;
  private final RawHookMode rawHookMode;
  private final RootPolicy rootPolicy;
  private final HistoryPolicy historyPolicy;
  private final String storeClassName;
  private final String reason;
}
```

```java
public enum StoreBindingKind {
  DOMAIN,
  SEMANTIC_BACKING,
  P1_DOMAIN,
  EXCLUDED,
  UNKNOWN
}
```

`UNKNOWN` 规则：

- `rawHookMode = IGNORE_RAW`
- `rootPolicy = EXCLUDED`
- `historyPolicy = NO_ARCHIVE`
- `isKnown() = false`
- consumer 必须记录 warning/counter，不能当作普通 excluded。

### 9.4 ArchiveDomainDescriptor

```java
public final class ArchiveDomainDescriptor {
  private final ArchiveDomain domain;
  private final int domainId;
  private final String canonicalName;
  private final List<String> sourceDbNames;
  private final RootPolicy rootPolicy;
  private final HistoryPolicy historyPolicy;
  private final ReaderPolicy readerPolicy;
  private final CanonicalKeyCodec keyCodec;
  private final CanonicalValueCodec valueCodec;
  private final Optional<DynamicKeyPolicy> dynamicKeyPolicy;
  private final boolean semanticOnly;
}
```

实现要求：

- 所有 `List`、`byte[]`、policy set 都要 defensive copy。
- descriptor 构造时校验 domain id 与 enum 一致。
- descriptor 不持有 Store 实例，不持有 Spring bean。
- `sourceDbNames` 按 ASCII 排序，用于 checksum。

### 9.5 ArchiveDomainRegistry

```java
public interface ArchiveDomainRegistry {
  Optional<ArchiveDomainDescriptor> descriptor(ArchiveDomain domain);

  StoreBinding bindingForDbName(String dbName);

  List<StoreBinding> allStoreBindings();

  List<ArchiveDomainDescriptor> rootDomains();

  List<ArchiveDomainDescriptor> historyDomains();

  DynamicKeyDecision dynamicKeyDecision(byte[] key);

  ArchiveCoverage coverage();

  RegistryChecksum checksum();
}
```

说明：

- `bindingForDbName` 对 unknown 不返回 `Optional.empty()`，而是返回 `UNKNOWN` binding。
- `rootDomains()` 只返回 `RootPolicy.IN_GLOBAL_ROOT` 的 descriptor，并按 domain id 排序。
- `historyDomains()` 返回 `HistoryPolicy.FULL_HISTORY` 和 `LATEST_ONLY` 的 descriptor。
- `dynamicKeyDecision` 只处理 `properties` domain 的 key；其他 domain 调用应返回 `NOT_DYNAMIC_PROPERTY` 或抛 `ArchiveDomainException`，实现时二选一并写测试。

### 9.6 ArchiveDomainRegistries

工厂类，避免调用方直接 new 具体实现：

```java
public final class ArchiveDomainRegistries {
  public static ArchiveDomainRegistry defaultRegistry();
}
```

L3 先不做 Spring bean。后续 L4 可在 `ArchiveServiceFactory` 内持有同一个 default registry。

## 10. P0 domain descriptor

| Domain | id | source | key | value | hook | root | history | reader |
| --- | ---: | --- | --- | --- | --- | --- | --- | --- |
| `ACCOUNT` | `0x0001` | `account` | 21-byte TRON address | `AccountCapsule.getData()` | generic | global | full | public state |
| `CONTRACT` | `0x0002` | `contract` | 21-byte contract address | ABI-cleared `ContractCapsule.getData()` | store-specific | global | full | historical VM |
| `CODE` | `0x0003` | `code` | 21-byte contract address | bytecode bytes | generic | global | full | public state |
| `CONTRACT_STORAGE` | `0x0004` | semantic hook only | `address21 || slot32 || version1` | slot value or tombstone | semantic-only | global | full | public state |
| `DYNAMIC_PROPERTIES` | `0x0005` | `properties` | dynamic property key | raw bytes | generic allowlist | key-level | full or history-only | historical VM/internal |

关键决策：

- `CONTRACT` value 必须是 `ContractStore` 实际落库后的 ABI-cleared bytes，不是原始 `ContractCapsule` 输入。
- `CODE` key 固定为 21-byte contract address。当前 `RepositoryImpl.saveCode(address, code)` 和 `commitCodeCache` 都以 address 作为 `CodeStore` key；不要在 P0 引入 codeHash 二段索引。
- `DYNAMIC_PROPERTIES` 的 root policy 不能只看 domain，要看 key-level policy。

## 11. P1/P2/excluded inventory

L3 不是要把全部 Store 进 P0 root，但必须把已知 Store 全部显式分类。第一版建议：

| dbName | Store | binding | reason |
| --- | --- | --- | --- |
| `abi` | `AbiStore` | P1 `ABI` + store-specific | metadata/debug，P0 historical getters 不需要 |
| `contract-state` | `ContractStateStore` | P1 `CONTRACT_STATE` + store-specific | historical VM 候选，P0 root 暂不承诺 |
| `account-asset` | `AccountAssetStore` | P1 `ACCOUNT_ASSET` | TRC10/account asset state，P0 先不纳入 |
| `asset-issue` / `asset-issue-v2` | asset stores | P1 governance/asset | future full TRON execution domain |
| `DelegatedResource` / `DelegatedResourceAccountIndex` / `delegation` | resource stores | P1 `RESOURCE_DELEGATION` | future resource historical state |
| `proposal` / `votes` / `witness` / `witness_schedule` | governance stores | P1 `GOVERNANCE` | future governance state |
| `exchange` / `exchange-v2` / market stores | market stores | P1 `MARKET` | future market state |
| `nullifier` / `IncrementalMerkleTree` / proof store | shielded stores | P1 `SHIELDED_STATE` | future shielded state |
| `block` / `block-index` | block stores | excluded | chain data, not execution state |
| `trans` / `transactionRetStore` / `transactionHistoryStore` | tx/receipt stores | excluded | tx index/receipt, not state root |
| `recent-block` / `recent-transaction` / `trans-cache` | cache stores | excluded | cache |
| `account-index` / `accountid-index` / `tree-block-index` | index stores | excluded | secondary index |
| `balance-trace` / `account-trace` | history helper stores | excluded from canonical state | existing balance history aid |
| `section-bloom` | log bloom index | excluded | query index |
| `common` / `reward-vi` / `pbft` / `block_KDB` | local/helper stores | excluded | local or operational data |

测试要锁住两件事：

- known Store 不能落入 `UNKNOWN`。
- P1 Store 不允许误入 `RootPolicy.IN_GLOBAL_ROOT`，除非后续显式升级并更新 checksum。

## 12. DynamicKeyPolicy

### 12.1 类型

`byte[]` 不能直接作为 `HashMap`/`HashSet` key。实现可复用 `org.tron.core.db2.common.WrappedByteArray`，或新增 archive 内部 key wrapper。

```java
public final class DynamicKeyPolicy {
  private final Map<WrappedByteArray, DynamicKeyDecision> decisions;

  public DynamicKeyDecision decision(byte[] key);

  public List<DynamicKeyDecision> allDecisions();
}
```

```java
public final class DynamicKeyDecision {
  private final byte[] key;
  private final DynamicKeyClass keyClass;
  private final RootPolicy rootPolicy;
  private final HistoryPolicy historyPolicy;
  private final ReaderPolicy readerPolicy;
  private final String reason;
}
```

```java
public enum DynamicKeyClass {
  HEADER_CURSOR,
  VM_CONFIG,
  RESOURCE_PARAMETER,
  FEE_PARAMETER,
  PRICE_HISTORY,
  GOVERNANCE_PARAMETER,
  INDEX_CURSOR,
  STATISTIC,
  MIGRATION_MARKER,
  UNKNOWN
}
```

Unknown dynamic key 默认：

```text
rootPolicy = EXCLUDED
historyPolicy = FULL_HISTORY or NO_ARCHIVE  // 二选一，由 L3 实现时固定并测试
readerPolicy = INTERNAL_ONLY
keyClass = UNKNOWN
```

建议第一版采用 `FULL_HISTORY` + diagnostic：先保留历史，不进 root，避免后续发现某个 key 影响 historical VM 时无历史可用。若磁盘预算优先，可改 `NO_ARCHIVE`，但必须在 roadmap 里显式承认会降低历史 VM 完整性。

### 12.2 P0 root allowlist

第一版 `DYNAMIC_PROPERTIES` root allowlist 建议覆盖：

```text
ENERGY_FEE
TRANSACTION_FEE
MAX_CPU_TIME_OF_ONE_TX
MEMO_FEE

ALLOW_TVM_TRANSFER_TRC10
ALLOW_TVM_CONSTANTINOPLE
ALLOW_TVM_SOLIDITY_059
ALLOW_TVM_ISTANBUL
ALLOW_TVM_FREEZE
ALLOW_TVM_VOTE
ALLOW_TVM_LONDON
ALLOW_TVM_COMPATIBLE_EVM
ALLOW_OPTIMIZED_RETURN_VALUE_OF_CHAIN_ID
ALLOW_DYNAMIC_ENERGY
DYNAMIC_ENERGY_THRESHOLD
DYNAMIC_ENERGY_INCREASE_FACTOR
DYNAMIC_ENERGY_MAX_FACTOR
ALLOW_TVM_SHANGHAI
ALLOW_STRICT_MATH
ALLOW_TVM_CANCUN
ALLOW_TVM_BLOB
ALLOW_TVM_SELFDESTRUCT_RESTRICTION
ALLOW_TVM_OSAKA
ALLOW_TVM_PRAGUE
ALLOW_HARDEN_RESOURCE_CALCULATION
ALLOW_HARDEN_EXCHANGE_CALCULATION
```

这些 key 的共同点：会影响 VM、actuator 执行、资源/fee 计算或 historical call 语义。

### 12.3 P0 history-only allowlist

```text
latest_block_header_timestamp
latest_block_header_number
latest_block_header_hash
LATEST_SOLIDIFIED_BLOCK_NUM
ENERGY_PRICE_HISTORY
BANDWIDTH_PRICE_HISTORY
MEMO_FEE_HISTORY
```

这些 key 可能服务 historical reader、fee RPC 或诊断，但不进入 P0 global root。

### 12.4 必须显式排除

```text
ABI_MOVE_DONE
ENERGY_PRICE_HISTORY_DONE
BANDWIDTH_PRICE_HISTORY_DONE
TURKISH_KEY_MIGRATION_DONE
BLOCK_HASH_HISTORY_INSTALLED
TOTAL_TRANSACTION_COST
TOTAL_CREATE_ACCOUNT_COST
TOTAL_CREATE_WITNESS_FEE
TOTAL_STORAGE_POOL
TOTAL_STORAGE_TAX
TOTAL_STORAGE_RESERVED
TRANSACTION_FEE_POOL
```

排除不是说永远无用，而是不能默认进 root。后续若升级，需要改 `DynamicKeyPolicy`、更新 checksum、增加迁移说明。

## 13. Canonical codecs

### 13.1 Key codec interface

```java
public interface CanonicalKeyCodec {
  String codecId();

  byte[] normalize(byte[] key);

  void validate(byte[] canonicalKey);
}
```

规则：

- `normalize` 必须返回新数组。
- `validate` 不修改输入。
- invalid length/key version 抛 `ArchiveDomainException`。
- codec id 进入 checksum。

### 13.2 Value codec interface

```java
public interface CanonicalValueCodec {
  String codecId();

  DomainValue normalizePut(byte[] value);

  DomainValue normalizeDelete();

  void validate(DomainValue value);
}
```

```java
public final class DomainValue {
  private final boolean deleted;
  private final byte[] value;
}
```

规则：

- delete/tombstone 用 `deleted=true` 表示，不用 `byte[0]` 表示。
- empty bytecode 是合法 value 还是 delete，必须由 domain-specific codec 决定。
- `CONTRACT_STORAGE` 的 zero slot 在 S5 semantic hook 中转换为 delete；L3 只定义 tombstone 表达。

### 13.3 P0 key codec

| Domain | codec id | normalize/validate |
| --- | --- | --- |
| `ACCOUNT` | `tron-address21-v1` | key 长度必须 21 |
| `CONTRACT` | `tron-address21-v1` | key 长度必须 21 |
| `CODE` | `tron-code-key-v1` | key 长度必须 21，语义为 contract address；测试锁定 clone/非 null |
| `CONTRACT_STORAGE` | `tron-storage-semantic-v1` | key 长度 54，前 21 address，后 32 slot，最后 1 version，version in `{0,1}` |
| `DYNAMIC_PROPERTIES` | `tron-dynamic-property-key-v1` | ASCII bytes，非空，按 `DynamicKeyPolicy` 分类 |

### 13.4 P0 value codec

| Domain | codec id | validate |
| --- | --- | --- |
| `ACCOUNT` | `account-capsule-bytes-v1` | 非 null；不在 L3 parse protobuf |
| `CONTRACT` | `contract-capsule-abi-cleared-v1` | 非 null；来源必须是 ContractStore after value |
| `CODE` | `bytecode-bytes-v1` | 非 null；允许 zero-length 需单测确认 |
| `CONTRACT_STORAGE` | `storage-word-or-delete-v1` | put value 长度建议 32；delete 用 tombstone |
| `DYNAMIC_PROPERTIES` | `dynamic-property-raw-v1` | 非 null；具体含义由 key policy 决定 |

L3 不解析 protobuf 的原因：codec 负责 canonical bytes，不负责业务语义。后续 StateReader 可以在 reader 层 parse `AccountCapsule` 或 `ContractCapsule`。

## 14. RegistryChecksum

### 14.1 输入

checksum 必须覆盖：

- registry schema version，例如 `archive-domain-registry-v1`
- 所有 `ArchiveDomain` 的 id/name
- 每个 descriptor 的 root/history/reader policy
- 每个 descriptor 的 key/value codec id
- 每个 descriptor 的 source dbName 列表
- 所有 StoreBinding 的 dbName、bindingKind、domain、rawHookMode、category
- dynamic key decisions：key bytes hex、keyClass、root/history/reader policy
- P1/excluded inventory

checksum 不应覆盖：

- Java object identity
- map iteration order
- comments/reason 文本，除非团队决定 reason 也是 schema 的一部分
- runtime config 值

### 14.2 排序规则

```text
domains: by numeric domain id ascending
sourceDbNames: ASCII ascending
storeBindings: dbName ASCII ascending
dynamic keys: unsigned lexicographic byte order
policies/codecs: use enum name / codecId string
```

### 14.3 输出

```java
public final class RegistryChecksum {
  private final String schemaVersion;
  private final byte[] sha256;

  public String hex();
}
```

测试要求：

- 两次构建 default registry，checksum 相同。
- 改变 dynamic allowlist 会改变 checksum。
- 改变 StoreBinding 顺序不会改变 checksum。
- 改变 domain id 会改变 checksum。

## 15. Coverage

`ArchiveCoverage` 用于让 review 和启动诊断看到当前 archive 覆盖范围。

```java
public final class ArchiveCoverage {
  private final List<ArchiveDomainDescriptor> rootDomains;
  private final List<ArchiveDomainDescriptor> historyDomains;
  private final List<StoreBinding> p0Bindings;
  private final List<StoreBinding> p1Bindings;
  private final List<StoreBinding> excludedBindings;
  private final List<StoreBinding> unknownBindings;
  private final RegistryChecksum checksum;
}
```

L3 不需要在节点启动时打印 coverage，但要让后续 `debug_getArchiveCoverage` 或 startup verifier 可以读取。

Coverage 测试：

- P0 root domain 不为空。
- P1 Store 明确列出，不计入 P0 root。
- unknown list 在 default registry 中为空。
- 调用 `bindingForDbName("new-store")` 返回 `UNKNOWN`，但不污染 default coverage。

## 16. Default registry 初始表

### 16.1 P0 root/history binding

| dbName | domain | bindingKind | rawHookMode | rootPolicy | historyPolicy |
| --- | --- | --- | --- | --- | --- |
| `account` | `ACCOUNT` | `DOMAIN` | `GENERIC_TRON_STORE` | `IN_GLOBAL_ROOT` | `FULL_HISTORY` |
| `contract` | `CONTRACT` | `DOMAIN` | `STORE_SPECIFIC` | `IN_GLOBAL_ROOT` | `FULL_HISTORY` |
| `code` | `CODE` | `DOMAIN` | `GENERIC_TRON_STORE` | `IN_GLOBAL_ROOT` | `FULL_HISTORY` |
| `properties` | `DYNAMIC_PROPERTIES` | `DOMAIN` | `GENERIC_TRON_STORE_ALLOWLIST` | key-level | key-level |
| `storage-row` | `CONTRACT_STORAGE` | `SEMANTIC_BACKING` | `SEMANTIC_ONLY` | `IN_GLOBAL_ROOT` | `FULL_HISTORY` |

### 16.2 P1 bindings

| dbName | domain | rawHookMode | rootPolicy |
| --- | --- | --- | --- |
| `abi` | `ABI` | `STORE_SPECIFIC` | `HISTORY_ONLY` |
| `contract-state` | `CONTRACT_STATE` | `STORE_SPECIFIC` | `HISTORY_ONLY` |
| `account-asset` | `ACCOUNT_ASSET` | `IGNORE_RAW` in L3 | `HISTORY_ONLY` |
| `DelegatedResource` / `DelegatedResourceAccountIndex` / `delegation` | `RESOURCE_DELEGATION` | `IGNORE_RAW` in L3 | `HISTORY_ONLY` |
| `proposal` / `votes` / `witness` / `witness_schedule` | `GOVERNANCE` | `IGNORE_RAW` in L3 | `HISTORY_ONLY` |
| exchange/market stores | `MARKET` | `IGNORE_RAW` in L3 | `HISTORY_ONLY` |
| shielded stores | `SHIELDED_STATE` | `IGNORE_RAW` in L3 | `HISTORY_ONLY` |

`IGNORE_RAW in L3` 表示 registry 先知道这些 Store，但 L4 不采集它们，直到对应 domain 升级为 P0/P1 active collector。

### 16.3 Excluded bindings

至少覆盖：

```text
block
block-index
trans
transactionRetStore
transactionHistoryStore
recent-block
recent-transaction
trans-cache
account-index
accountid-index
tree-block-index
balance-trace
account-trace
section-bloom
common
reward-vi
block_KDB
pbft-sign-data if present in dbName inventory
```

实现时按本地 `@Value("...")` inventory 校验实际 dbName，避免写不存在的名字。

## 17. 实现顺序

### L3.1 Domain enum 和 policy enum

新增：

```text
ArchiveDomain.java
RootPolicy.java
HistoryPolicy.java
ReaderPolicy.java
RawHookMode.java
StoreBindingKind.java
StoreCategory.java
DynamicKeyClass.java
ArchiveDomainException.java
```

测试：

- `archiveDomainIdsAreUnique`
- `archiveDomainIdsFitUnsignedShort`
- `archiveDomainCanonicalNamesAreStableAscii`
- `rootDomainOrderUsesNumericId`

### L3.2 Codec 基础

新增：

```text
CanonicalKeyCodec.java
CanonicalValueCodec.java
DomainValue.java
ArchiveDomainCodecs.java
ContractStorageKeyCodec.java
DynamicPropertyKeyCodec.java
```

测试：

- `addressCodecAcceptsOnlyTwentyOneBytes`
- `storageCodecAcceptsAddressSlotVersion`
- `storageCodecRejectsRawStorageRowKey`
- `dynamicPropertyCodecRejectsEmptyKey`
- `codecsReturnDefensiveCopies`
- `deleteValueUsesTombstoneNotEmptyBytes`

### L3.3 Descriptor 和 binding

新增：

```text
ArchiveDomainDescriptor.java
StoreBinding.java
```

测试：

- `descriptorDefensivelyCopiesSourceDbNames`
- `descriptorRejectsMismatchedDomainId`
- `unknownBindingIsNotExcludedBinding`
- `semanticBackingBindingDoesNotProduceRawWrites`

### L3.4 Dynamic properties policy

新增：

```text
DynamicKeyPolicy.java
DynamicKeyDecision.java
```

测试：

- `rootAllowlistContainsVmConfigKeys`
- `headerCursorKeysAreHistoryOnly`
- `priceHistoryKeysAreHistoryOnly`
- `migrationMarkersAreExcludedFromRoot`
- `unknownDynamicKeyIsDiagnosticAndNotRooted`
- `dynamicKeyPolicyUsesByteContentNotArrayIdentity`

### L3.5 Default registry

新增：

```text
ArchiveDomainRegistry.java
ArchiveDomainRegistries.java
DefaultArchiveDomainRegistry.java
ArchiveCoverage.java
```

测试：

- `p0DbNamesResolveToExpectedDomains`
- `contractIsStoreSpecific`
- `abiAndContractStateAreStoreSpecificButNotP0Root`
- `storageRowIsSemanticBackingOnly`
- `unknownDbNameReturnsUnknownBinding`
- `rootDomainsExcludeP1AndIndexes`
- `allKnownStoreBindingsAreExplicitlyClassified`

### L3.6 Checksum

新增：

```text
RegistryChecksum.java
```

测试：

- `defaultRegistryChecksumIsStable`
- `storeBindingOrderDoesNotChangeChecksum`
- `dynamicAllowlistChangeChangesChecksum`
- `domainIdChangeChangesChecksum`

## 18. 测试文件落点

```text
chainbase/src/test/java/org/tron/core/archive/domain/ArchiveDomainTest.java
chainbase/src/test/java/org/tron/core/archive/domain/DefaultArchiveDomainRegistryTest.java
chainbase/src/test/java/org/tron/core/archive/domain/DynamicKeyPolicyTest.java
chainbase/src/test/java/org/tron/core/archive/domain/RegistryChecksumTest.java
chainbase/src/test/java/org/tron/core/archive/domain/ArchiveCoverageTest.java
chainbase/src/test/java/org/tron/core/archive/codec/ArchiveDomainCodecsTest.java
```

如果 `chainbase/src/test` 目录在实现时尚不存在，直接创建。`chainbase/build.gradle` 已有 test task，不需要把这些纯单测放进 framework。

## 19. Gate 命令

L3 focused gate：

```bash
./gradlew :chainbase:test --tests '*ArchiveDomainTest'
./gradlew :chainbase:test --tests '*DefaultArchiveDomainRegistryTest'
./gradlew :chainbase:test --tests '*DynamicKeyPolicyTest'
./gradlew :chainbase:test --tests '*RegistryChecksumTest'
./gradlew :chainbase:test --tests '*ArchiveCoverageTest'
./gradlew :chainbase:test --tests '*ArchiveDomainCodecsTest'
```

L3 package gate：

```bash
./gradlew :chainbase:test --tests 'org.tron.core.archive.*'
./gradlew checkstyleMain checkstyleTest
```

Regression gate：

```bash
./gradlew :common:test --tests '*StorageConfigTest'
./gradlew :chainbase:test --tests '*NoopArchiveServiceTest'
```

L3 不改 runtime hook，通常不需要 framework Manager 集成测试；如果实现时改了 `DefaultArchiveService` 构造或 factory，再补跑 L1/L2 对应 service tests。

## 20. Review checklist

代码 review 时逐项检查：

- `ArchiveDomain` id 不是 enum ordinal。
- 所有 public getter 返回 defensive copy 或 immutable list。
- `byte[]` 不直接作为 map key。
- unknown dbName 不是普通 excluded。
- `storage-row` 没有被映射为 raw `CONTRACT_STORAGE` write。
- `contract` 没有被误判成 generic hook。
- `properties` 没有全量进 root。
- dynamic key allowlist 改动会改变 checksum。
- P1 Store 只显式分类，不被 L4 默认采集。
- registry 不持有 Store 实例，不引入 Spring 运行时副作用。
- collector/root/read 模块没有反向依赖到 java-tron Store class 名称。

## 21. 停止条件

L3 可以标记 `DONE` 的证据：

- `ArchiveDomainRegistryTest` 证明 P0/P1/excluded inventory 全覆盖。
- `ArchiveDomainCodecsTest` 证明 P0 key/value codec 行为确定。
- `DynamicKeyPolicyTest` 证明 dynamic properties root/history/excluded 策略确定。
- `RegistryChecksumTest` 证明 checksum 稳定且覆盖 schema 变化。
- `ArchiveCoverageTest` 证明 coverage 可用于后续启动校验或 debug API。
- java-tron 默认关闭 archive 时没有任何 Store hook、DB 写入、Manager 行为变化。

若以上任一项缺失，不能进入 L4 WriteCollector。

## 22. 与后续模块的接口契约

| 下游 | 只能从 L3 读取 |
| --- | --- |
| L4 WriteCollector | `bindingForDbName`、`RawHookMode`、codec、dynamic key decision |
| L5 ArchiveTemporalStore | domain id、history policy、canonical key/value/tombstone |
| L6 ArchiveStateReader | domain descriptor、reader policy、address/storage/dynamic key codec |
| L7 CommitmentBuilder | root domain list、root policy、dynamic key root decision、checksum |
| L9 proof/debug API | domain id/name、coverage、checksum、proof key codec |

最重要的不变量：L4 以后不允许出现独立的 `if ("account".equals(dbName))`、`if ("storage-row".equals(dbName))` 这类 domain 分派逻辑。例外只能是测试里构造输入。
