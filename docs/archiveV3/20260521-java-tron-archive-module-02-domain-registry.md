# java-tron Archive 模块 02：ArchiveDomainRegistry 细化设计

日期：2026-05-21

关联总文档：[java-tron 交易级状态树支持：Erigon V2/V3 模型调研](./20260520-java-tron-archive-state-erigon-v2-v3-research.md)

前置模块：[模块 01：ArchiveTxNumIndex](./20260521-java-tron-archive-module-01-txnum-index.md)

源码对照深挖：[模块 02 ArchiveDomainRegistry：Erigon 源码对照深挖](./20260525-java-tron-module-02-domain-registry-erigon-source-deep-dive.md)

java-tron 源码对照：[模块 02 ArchiveDomainRegistry：java-tron 源码对照](./20260601-java-tron-module-02-domain-registry-java-tron-source-deep-dive.md)

逐文件实现清单：[java-tron Archive 模块 02：ArchiveDomainRegistry 逐文件 Patch 清单](./20260602-java-tron-archive-module-02-domain-registry-patch-checklist.md)

实现蓝图：[java-tron Archive 状态树实现蓝图](./20260602-java-tron-archive-implementation-blueprint.md)

## 1. 模块定位

`ArchiveDomainRegistry` 是 archive 状态系统的 schema 和归类模块。它负责回答一个核心问题：

```text
java-tron 某个 Store 的某个 key/value 写入，应该映射到哪个 archive domain，用什么 canonical key/value bytes，是否进入 global root，是否允许历史查询。
```

如果 `ArchiveTxNumIndex` 解决“什么时候”的问题，`ArchiveDomainRegistry` 就解决“写的是什么”的问题。这个模块的质量直接决定交易级状态树是否完整：漏掉一个影响执行的 Store，root 就算可重复，也不是完整状态 root；value 编码不稳定，root 就无法跨节点复现。

## 2. 职责和非职责

职责：

- 定义稳定的 `domainId`、domain 名称、root inclusion 策略、history 策略。
- 将 java-tron Store 写入映射为 `DomainWrite`。
- 定义每个 domain 的 canonical key codec 和 value codec。
- 区分 root state、history-only index、derived/cache、local metadata。
- 为 schema 版本、激活高度、兼容升级提供元数据。
- 在启动和 replay 时校验 registry checksum，避免不同节点用不同 schema 构建 root。
- 提供 store inventory 的落地格式和覆盖检查。

非职责：

- 不分配 txNum，这属于 `ArchiveTxNumIndex`。
- 不捕获执行过程中的 write-set，这属于 `ArchiveWriteCollector`。
- 不保存历史值，这属于 `ArchiveTemporalStore`。
- 不计算 root，这属于 `CommitmentBuilder`。
- 不决定是否把 root 写入 block header，这属于共识/TIP 层。

## 3. 设计目标

1. 稳定。
   `domainId`、key bytes、value bytes、root inclusion 不能随代码重构而漂移。

2. 可审计。
   每个 java-tron Store 必须明确标注：进入 root、仅做历史查询、派生缓存、排除。

3. 可灰度。
   PoC 可以先支持 `ACCOUNT`、`CONTRACT_CODE`、`CONTRACT_STORAGE`，但 schema 必须能扩展到 25+ Store。

4. 可演进。
   新 domain、新字段、新 codec 都必须有版本和激活点，旧历史不能被隐式改写。

5. 可复现。
   同一条 canonical chain 在不同机器 replay，domain writes 和 root 输入字节必须完全一致。

## 4. 核心概念

### 4.1 Store

Store 是 java-tron 现有状态存储的业务边界，例如账户、合约、合约 storage、动态参数、资产、投票、委托等。Store 名称和 key/value 结构来自 java-tron 当前代码。

Registry 不要求现有 Store 物理合并；它只是为 archive sidecar 提供逻辑映射。

### 4.2 Domain

Domain 是 archive 侧的逻辑状态空间。一个 domain 应满足：

- key codec 单一且稳定。
- value codec 单一且稳定。
- root inclusion 策略一致。
- 查询场景一致。
- 可以独立做 history/index/snapshot。

不要把所有 Store 粗暴塞进一个 domain。Domain 太粗会让 key/value codec 和 proof 复杂；Domain 太细会让 global root 和 snapshot manifest 管理复杂。建议按“业务语义 + key 形态 + 查询需求”拆分。

### 4.3 StoreBinding

`StoreBinding` 描述现有 Store 到 domain 的映射：

```text
store_id + raw_store_key + operation
  -> domain_id + domain_key + domain_value + policy
```

一个 Store 可以映射到多个 domain。例如合约相关 Store 可能拆成：

- `CONTRACT_META`
- `CONTRACT_CODE`
- `CONTRACT_STORAGE`

一个 domain 也可以由多个 Store 共同组成。例如资源委托状态可能来自多个 delegation 相关 Store。

### 4.4 Codec

Codec 分为两类：

- key codec：把 Store key 转成 domain key。
- value codec：把 Store value 转成 canonical bytes。

Codec 必须是确定性的、版本化的、跨节点一致的。Registry 应禁止调用方直接把 Java 对象或不稳定 map 结构传给 root 计算。

### 4.5 RootPolicy

建议定义四类：

| RootPolicy | 含义 |
|---|---|
| `IN_GLOBAL_ROOT` | 进入 archive global root，是完整状态的一部分 |
| `DOMAIN_ROOT_ONLY` | 计算 domain root，但暂不聚合进 global root，用于灰度或候选验证 |
| `HISTORY_ONLY` | 保存历史查询，但不进 root，例如 receipt/cache/index |
| `EXCLUDED` | 不进入 archive，不可历史查询，通常是本地元数据或可重建缓存 |

PoC 阶段可以对部分 domain 使用 `DOMAIN_ROOT_ONLY` 做影子校验，等覆盖和性能稳定后再切换到 `IN_GLOBAL_ROOT`。

### 4.6 HistoryPolicy

建议定义：

| HistoryPolicy | 含义 |
|---|---|
| `FULL_HISTORY` | 每个 txNum 的 before-value 都记录，可 `GetAsOf` |
| `LATEST_ONLY` | 只保存 latest，不支持历史 |
| `CHECKPOINT_ONLY` | 只保存 checkpoint 或 segment 快照 |
| `NO_ARCHIVE` | 完全不进入 archive |

通常 `IN_GLOBAL_ROOT` 的 domain 应使用 `FULL_HISTORY`，否则无法对历史 root 做证明或重算。

## 5. Domain 分类建议

### 5.1 PoC 必选 domain

第一阶段建议先实现三个 domain：

| Domain | 目的 | RootPolicy | HistoryPolicy |
|---|---|---|---|
| `ACCOUNT` | 支持历史余额、账户基础状态 | `DOMAIN_ROOT_ONLY` 或 `IN_GLOBAL_ROOT` | `FULL_HISTORY` |
| `CONTRACT_CODE` | 支持 `eth_getCode`、历史 `eth_call` 代码读取 | `DOMAIN_ROOT_ONLY` 或 `IN_GLOBAL_ROOT` | `FULL_HISTORY` |
| `CONTRACT_STORAGE` | 支持 `eth_getStorageAt`、历史合约执行 | `DOMAIN_ROOT_ONLY` 或 `IN_GLOBAL_ROOT` | `FULL_HISTORY` |

说明：

- 如果 PoC 只验证 ETH 兼容接口，可以先用 `DOMAIN_ROOT_ONLY`，避免对“完整 TRON 状态 root”过早承诺。
- 一旦宣称 global root 覆盖完整状态，就不能只包含这三个 domain。

### 5.2 完整 archive 候选 domain

候选 domain：

| Domain | 内容 | 是否应进 root | 备注 |
|---|---|---:|---|
| `ACCOUNT` | AccountCapsule 中影响执行和账户语义的字段 | 是 | 余额、资源、权限、资产、合约标记等需盘点 |
| `CONTRACT_CODE` | 合约代码 bytes 或 code hash -> code | 是 | ETH API 需要按历史高度读取 |
| `CONTRACT_STORAGE` | TVM storage slot | 是 | key 建议固定为 contract address + slot |
| `CONTRACT_META` | 合约元数据、ABI、origin、资源参数 | 是 | 历史 `eth_call` 可能依赖 |
| `DYNAMIC_GLOBAL` | 影响交易执行的动态参数 | 是 | 需逐字段筛选，不建议整 Store 盲收 |
| `ASSET_TRC10` | TRC10 asset issue/state | 是 | 涉及 TransferAsset/participate 等 |
| `WITNESS_GOVERNANCE` | witness、vote、proposal、committee 相关状态 | 是 | 治理和奖励影响 canonical state |
| `DELEGATION_RESOURCE` | staking/delegation/resource 状态 | 是 | Stake 2.0 等资源模型必须覆盖 |
| `EXCHANGE_MARKET` | exchange/market/order 相关状态 | 视业务 | 若影响交易执行必须进 root |
| `REWARD_ALLOWANCE` | allowance/reward 相关状态 | 视业务 | 需确认是否派生或 canonical |
| `RECEIPT_LOG_CACHE` | receipt、log、trace 查询缓存 | 否 | history-only 或外部索引 |
| `ACCOUNT_TRACE` | balance/account trace | 否 | 查询优化，不应定义 root 语义 |
| `LOCAL_NODE_METADATA` | 节点配置、peer、sync progress | 否 | 必须排除 |

这张表只是初始候选，最终必须以 java-tron 代码 inventory 为准。

### 5.3 排除规则

满足任一条件可以考虑 `EXCLUDED`：

- 只影响本地节点运行，不影响区块执行结果。
- 可由其他 root domain 完整重建。
- 是查询缓存、统计表、索引表。
- 是 pending pool、临时执行上下文、snapshot/revoking 临时结构。
- 是链外配置或运维元数据。

排除必须写明原因；不能因为“暂时不清楚”就默认排除。

## 6. Domain ID 治理

### 6.1 编号规则

建议使用 `uint16 domainId`，固定大端编码：

| 范围 | 用途 |
|---|---|
| `0x0000` | 保留，非法 domain |
| `0x0001 - 0x00ff` | 核心 root domain |
| `0x0100 - 0x01ff` | history-only 查询 domain |
| `0x0200 - 0x02ff` | 影子/实验 domain |
| `0x7f00 - 0x7fff` | 本地测试 domain，不得用于主网 |

初始建议：

| domainId | Domain |
|---:|---|
| `0x0001` | `ACCOUNT` |
| `0x0002` | `CONTRACT_CODE` |
| `0x0003` | `CONTRACT_STORAGE` |
| `0x0004` | `CONTRACT_META` |
| `0x0005` | `DYNAMIC_GLOBAL` |
| `0x0006` | `ASSET_TRC10` |
| `0x0007` | `WITNESS_GOVERNANCE` |
| `0x0008` | `DELEGATION_RESOURCE` |
| `0x0009` | `EXCHANGE_MARKET` |
| `0x0101` | `RECEIPT_LOG_CACHE` |
| `0x0102` | `ACCOUNT_TRACE` |

规则：

- domainId 一旦发布，不得复用。
- domain 删除后保留 tombstone。
- domain 名称可以变更展示文案，但 domainId 和 codec 版本不能静默改变。
- global root 聚合按 domainId 升序，不能按注册顺序或 map 顺序。

### 6.2 Registry checksum

每个节点启动 archive 时计算 registry checksum：

```text
registryChecksum = H(
  chain_id,
  archive_schema_version,
  sorted(domain descriptors),
  sorted(store bindings),
  codec version table,
  root policy table,
  activation table
)
```

该 checksum 写入 `archive_registry_meta`。如果本地已有 archive 数据，但新代码 checksum 不一致：

- 兼容升级：必须有 migration plan。
- 不兼容升级：拒绝继续写入，要求重建或指定迁移。

## 7. Key 编码规范

### 7.1 总原则

domain key 必须：

- 不包含 Java 类名、Store 名等易变信息。
- 使用固定大小或带长度前缀的二进制格式。
- 数字统一大端编码。
- 地址统一 canonical 形式。
- 能支持 prefix scan。
- 对同一业务对象只有一种编码方式。

archive 存储层最终 key 可以是：

```text
storage_key = domain_id_u16 || domain_key
```

但 domain root 内部应把 `domainId` 作为外层聚合 key，不建议在 domain root 的 leaf key 中重复混入 domainId。

### 7.2 地址编码

TRON 地址存在多种表示：

- API/钱包常见 base58check。
- hex 形式通常带 `0x41` 前缀。
- EVM 地址常用 20 bytes。
- java-tron 内部 Store key 可能使用 raw address bytes。

Registry 必须选择一种 canonical address：

```text
TronAddress21 = 0x41 || evmAddress20
```

建议 root 和 archive domain 使用 21-byte TRON 地址，因为这最贴近 java-tron 原生账户空间。ETH JSON-RPC 入口负责把 20-byte EVM address 转为 `0x41 || address20` 再查询 archive。

如果某些 Store 目前使用 20-byte key，binding 必须显式转换，不能让同一账户同时出现 20-byte 和 21-byte 两种 domain key。

### 7.3 建议 key 格式

| Domain | domain key |
|---|---|
| `ACCOUNT` | `address21` |
| `CONTRACT_CODE` | `contract_address21` |
| `CONTRACT_STORAGE` | `contract_address21 || slot_key32` |
| `CONTRACT_META` | `contract_address21` |
| `DYNAMIC_GLOBAL` | `property_id_u16` 或 `property_name_len || property_name` |
| `ASSET_TRC10` | `asset_id_u64` 或 canonical asset id bytes |
| `WITNESS_GOVERNANCE` | `subtype_u8 || key` |
| `DELEGATION_RESOURCE` | `subtype_u8 || owner_address21 || target_address21 || resource_type_u8` |
| `EXCHANGE_MARKET` | `subtype_u8 || exchange_id/order_id/...` |
| `RECEIPT_LOG_CACHE` | `block_num_u64 || tx_index_u32` 或 `tx_id32` |

### 7.4 Prefix scan 约束

为了支持合约 storage 范围查询和 segment 构建：

- `CONTRACT_STORAGE` 必须以 `contract_address21` 作为前缀。
- 同一 domain 下相同实体的子 key 应连续。
- 变长 key 必须使用长度前缀，避免前缀歧义。

示例：

```text
CONTRACT_STORAGE key:
  address21 || slot32 || storageKeyVersion_u8

DELEGATION_RESOURCE key:
  owner21 || resource_type_u8 || target21
```

## 8. Value 编码规范

### 8.1 总原则

value bytes 必须是 canonical bytes，而不是 Java 对象。

推荐优先级：

1. 如果现有 Store 持久化 bytes 已经是 consensus state 且稳定，优先使用 Store 原始 bytes。
2. 如果原始 bytes 含派生字段、本地字段或不稳定顺序，定义 archive-only canonical codec。
3. 如果使用 protobuf，必须明确字段顺序、默认值、map/repeated 排序、unknown fields 处理。
4. value codec 必须版本化。

### 8.2 Protobuf/Capsule 风险

需要重点审计：

- map 字段序列化顺序是否稳定。
- repeated 字段是否存在业务上无序但序列化有序的问题。
- 默认值字段是否显式写入。
- unknown fields 是否保留。
- 老版本节点写出的 bytes 是否和新版本一致。
- Capsule 包装层是否包含非状态字段或缓存字段。

如果无法证明现有 `toByteArray()` 稳定，应定义 archive canonical encoder，例如：

```text
ACCOUNT value v1:
  account_type_u8
  balance_i64
  allowance_i64
  latest_withdraw_time_i64
  frozen_v2_list_sorted
  asset_map_sorted_by_asset_id
  permission_list_sorted_by_id
  contract_address_optional
  ...
```

这会增加工程量，但可以避免 root 不稳定。

### 8.3 空值和删除

Registry 只负责编码存在的 value。删除语义由 `ArchiveTemporalStore` 表达：

| 状态 | 表达 |
|---|---|
| key 不存在 | no latest value |
| key 创建 | history 写 creation marker |
| key 删除 | latest 删除，history 写删除前 value |
| value 为空 bytes | value codec 输出合法空 bytes 或带类型前缀，不能等同删除 |

如果某个 Store 使用空 bytes 表示业务值，value codec 必须加 envelope：

```text
value = value_codec_version_u16 || raw_payload
```

这样 deletion marker 不会和业务空值冲突。

### 8.4 Codec envelope

建议每个 domain value 带 codec version：

```text
domain_value = codec_version_u16 || payload
```

root leaf hash 输入：

```text
leaf_hash = H(domain_key || domain_value)
```

如果希望减小存储开销，也可以在 manifest 中记录 domain codec version，value 内不重复携带版本。但历史上发生 codec 升级时，读取旧 segment 会更复杂。PoC 建议先显式携带版本。

## 9. Store inventory

### 9.1 inventory 表结构

建议先产出一份机器可读清单，例如 YAML/CSV：

```yaml
stores:
  - store_id: AccountStore
    package: org.tron.core.store
    canonical_state: true
    modified_by_user_tx: true
    modified_by_system: true
    affects_vm_or_actuator: true
    history_required: true
    root_policy: IN_GLOBAL_ROOT
    domain: ACCOUNT
    key_codec: tron_address_21_v1
    value_codec: account_canonical_v1
    notes: "需拆分/排序 asset map 和 permission fields"
```

字段建议：

| 字段 | 说明 |
|---|---|
| `store_id` | java-tron Store 类或逻辑 Store 名 |
| `physical_db` | 底层 DB/table 名 |
| `key_type` | address、asset id、contract+slot、property key 等 |
| `value_type` | Capsule/protobuf/raw bytes |
| `canonical_state` | 是否属于 canonical state |
| `modified_by_user_tx` | 是否可被普通交易修改 |
| `modified_by_system` | 是否可被维护/系统逻辑修改 |
| `affects_vm_or_actuator` | 是否影响执行结果 |
| `history_required` | 是否需要历史查询 |
| `root_policy` | RootPolicy |
| `history_policy` | HistoryPolicy |
| `domain` | Archive domain |
| `key_codec` | key codec 版本 |
| `value_codec` | value codec 版本 |
| `activation_block` | 进入 archive 的起始高度 |
| `owner` | 负责确认语义的模块 owner |
| `notes` | 风险说明 |

### 9.2 候选 Store 盘点方向

以下是需要优先盘点的 Store 类别，具体类名以 java-tron 代码为准：

| Store 类别 | 候选 domain | 风险 |
|---|---|---|
| Account store | `ACCOUNT` | AccountCapsule 字段多，需区分执行字段和派生字段 |
| Contract store | `CONTRACT_META` | ABI/资源参数是否影响历史 eth_call |
| Code store | `CONTRACT_CODE` | code hash 与 code bytes 关系要固定 |
| Contract storage store | `CONTRACT_STORAGE` | key 是否已包含地址和 slot，slot 是否 32-byte |
| Dynamic properties store | `DYNAMIC_GLOBAL` | 不能整表盲收，需逐字段判断 |
| Asset issue / TRC10 store | `ASSET_TRC10` | asset id/name 兼容历史规则 |
| Witness / votes / proposal / committee store | `WITNESS_GOVERNANCE` | 维护周期系统写入多 |
| Delegation / resource store | `DELEGATION_RESOURCE` | Stake 1.0/2.0 兼容 |
| Exchange / market store | `EXCHANGE_MARKET` | 订单、撮合、成交状态需按业务拆分 |
| Transaction / receipt / log store | `RECEIPT_LOG_CACHE` | 通常 history-only，不应进 state root |
| Account trace / balance trace store | `ACCOUNT_TRACE` | 查询优化；与 `ACCOUNT` history 交叉校验 |
| Block/header/index store | `EXCLUDED` 或 history-only | 链结构数据不等于 state domain |
| Node local metadata | `EXCLUDED` | 严禁进入 root |

### 9.3 覆盖检查

需要一个测试或静态检查确保：

- 所有 `TronStoreWithRevoking` / state store 写入口都有 binding 或显式 exclusion。
- 新增 Store 时必须更新 inventory。
- `EXCLUDED` Store 必须有 reason。
- `IN_GLOBAL_ROOT` domain 必须有 key/value codec。
- root domain 不允许 `LATEST_ONLY`。

## 10. Registry 持久化模型

建议表：

| 表 | key | value |
|---|---|---|
| `archive_registry_meta` | `schema_version` | registry checksum、chain id、activation block |
| `archive_domain_descriptor` | `domain_id` | name、root policy、history policy、codec ids、status |
| `archive_store_binding` | `store_id` | domain id、key codec、value codec、activation block、exclusion reason |
| `archive_codec_descriptor` | `codec_id` | codec name、version、hash、activation block |
| `archive_domain_tombstone` | `domain_id` | retired reason、retired block |

这些表不是热路径查询必需，但对重启校验、segment manifest、离线 verifier 很重要。

## 11. Java 接口草案

```java
public interface ArchiveDomainRegistry {
  Optional<DomainMapping> resolveStoreWrite(StoreWrite write);

  DomainDescriptor getDomain(short domainId);

  Collection<DomainDescriptor> getRootDomains();

  Collection<StoreBinding> getStoreBindings();

  byte[] registryChecksum();

  void validateStartup(ArchiveRegistryMeta persistedMeta);
}
```

```java
public record StoreWrite(
    StoreId storeId,
    byte[] rawKey,
    @Nullable byte[] rawValue,
    StoreOperation operation,
    StoreWriteSource source) {
}
```

```java
public record DomainMapping(
    short domainId,
    byte[] domainKey,
    @Nullable byte[] domainValue,
    RootPolicy rootPolicy,
    HistoryPolicy historyPolicy,
    short keyCodecVersion,
    short valueCodecVersion) {
}
```

```java
public record DomainDescriptor(
    short domainId,
    String name,
    RootPolicy rootPolicy,
    HistoryPolicy historyPolicy,
    short keyCodecVersion,
    short valueCodecVersion,
    long activationBlock,
    DomainStatus status) {
}
```

```java
public interface DomainKeyCodec {
  byte[] encode(StoreId storeId, byte[] rawKey, StoreWriteContext context);

  boolean supportsPrefixScan();

  String codecId();
}
```

```java
public interface DomainValueCodec {
  byte[] encode(StoreId storeId, byte[] rawValue, StoreWriteContext context);

  String codecId();
}
```

说明：

- `rawValue == null` 表示 delete；Registry 不生成 deletion marker，只返回 `domainValue = null`。
- `StoreWriteSource` 用于区分 canonical apply、genesis replay、migration、repair；pending/pre-exec source 必须被拒绝。
- `StoreWriteContext` 可以包含 block version、chain parameters、fork flags，但不能包含会导致非确定性的本地状态。

## 12. 写路径集成

推荐写路径：

```text
canonical store put/delete
  -> ArchiveWriteCollector intercepts StoreWrite
  -> ArchiveDomainRegistry.resolveStoreWrite(write)
  -> DomainWrite(domainId, domainKey, domainValue, policies)
  -> ArchiveTemporalStore records before-value by txNum
  -> CommitmentBuilder touches root domain if rootPolicy requires
```

Registry 必须在 collector 之前完成映射，原因：

- collector 的去重 key 应该是 `domainId + domainKey`，不是 raw Store key。
- 同一交易内多个 Store 写可能映射到同一 domain key，需要按 domain key 合并。
- root inclusion 只看 domain policy，不应散落在 collector/commitment 里。

## 13. 读路径集成

RPC 或业务查询不应该直接依赖 Store 名：

```text
eth_getStorageAt(address, slot, blockTag)
  -> domain = CONTRACT_STORAGE
  -> domainKey = contract_address21 || slot32 || storageKeyVersion_u8
  -> statePoint = BLOCK_END(blockTag)
  -> ArchiveStateReader.get(domain, domainKey, statePoint)
```

`ArchiveStateReader` 可以使用 registry 做查询端 key 编码：

```java
byte[] key = registry.encodeQueryKey(Domain.CONTRACT_STORAGE, query);
```

查询端 codec 必须和写入端 codec 共用实现，不能复制一套。

## 14. Schema 演进

### 14.1 新增 domain

新增 domain 需要：

- 分配新 domainId。
- 增加 descriptor。
- 增加 store binding。
- 指定 activation block/txNum。
- 定义旧高度查询行为：unsupported、empty、或从 migration snapshot 读取。

### 14.2 修改 codec

修改 codec 需要：

- 增加新 codec version。
- 保留旧 codec decoder。
- 指定 activation block。
- segment manifest 标注每个 range 使用的 codec version。
- root verifier 根据 statePoint 选择正确 codec。

不能用同一个 `value_codec_version` 改变 bytes 语义。

### 14.3 修改 RootPolicy

例如 `DOMAIN_ROOT_ONLY -> IN_GLOBAL_ROOT`：

- 必须有 activation block。
- activation 前 global root 不包含该 domain。
- activation 后 global root 包含该 domain。
- root proof 需要携带 activation metadata。

如果未来把 root 纳入共识，该 activation 必须通过 TIP/Proposal 固定。

### 14.4 domain 拆分/合并

domain 拆分比合并更安全。建议策略：

- 老 domain 保留历史。
- 新 domain 从 activation block 开始写入。
- 如果需要完整历史，离线 migration/backfill 生成新 domain 历史 segment。
- 不要在同一个 domainId 下改变 key 空间。

## 15. 与 root 的关系

Registry 提供 root 输入的确定顺序：

```text
root_domains = sorted(domain where rootPolicy == IN_GLOBAL_ROOT by domainId)
globalRoot = Merkle(domainId -> domainRoot)
```

domain leaf 建议：

```text
domainLeaf = H(domainKey || domainValue)
```

global leaf 建议：

```text
globalLeaf = H(domainId_u16 || domainRoot)
```

Registry 不负责实现 Merkle，但必须提供：

- domainId。
- domain root inclusion。
- domain order。
- codec version。
- empty domain root 的确定规则。

## 16. PoC 范围

### 16.1 PoC v1

目标：支撑 `eth_getBalance`、`eth_getCode`、`eth_getStorageAt` 的历史 blockTag。

实现 domain：

- `ACCOUNT`
- `CONTRACT_CODE`
- `CONTRACT_STORAGE`

RootPolicy：

- 如果只验证查询：`HISTORY_ONLY` 或 `DOMAIN_ROOT_ONLY`。
- 如果验证 block-end archive root：`DOMAIN_ROOT_ONLY`，并生成 global root 但明确标注为 partial root。

必须避免在文档/API 中称为“完整 state root”。

### 16.2 PoC v2

目标：支撑历史 `eth_call`。

增加：

- `CONTRACT_META`
- `DYNAMIC_GLOBAL` 中影响 TVM/actuator 的字段。
- 资源/能量价格相关字段。

否则历史 `eth_call` 可能读到当前链参数。

### 16.3 PoC v3

目标：完整 TRON archive root。

增加：

- TRC10 asset。
- governance/witness/vote/proposal。
- delegation/resource/staking。
- exchange/market。
- reward/allowance。

并完成 Store inventory 全覆盖。

## 17. 边界场景

| 场景 | Registry 行为 |
|---|---|
| 未注册 Store 写入 | archive strict 模式直接失败；宽松模式记录告警但不能计算 complete root |
| Store 明确排除 | 返回 empty mapping，并记录 exclusion reason |
| raw key 格式不合法 | 返回 codec error，拒绝写入 archive |
| raw value 为空 bytes | 走 value codec，不能当成 delete |
| delete 操作 | `domainValue = null`，删除语义交给 TemporalStore |
| 同一 Store key 映射多个 domain | 返回多个 DomainMapping |
| 不同 Store 映射同一 domain key | collector 按 domain key 合并，Registry 必须允许声明 |
| 查询使用 20-byte EVM address | 查询 codec 转成 21-byte TRON address |
| schema checksum 不一致 | 拒绝继续写入，除非存在显式 migration |
| root domain 设置为 latest-only | 启动校验失败 |

## 18. 测试计划

### 18.1 Codec 单元测试

- address 20-byte/21-byte/base58 输入归一为同一 domain key。
- `CONTRACT_STORAGE` key 前缀扫描正确。
- 数字大端编码排序符合预期。
- value codec 对同一 Capsule 多次编码 bytes 完全一致。
- map/repeated 字段排序稳定。
- 空 bytes value 与 delete 区分。
- codec version 写入和读取一致。

### 18.2 StoreBinding 测试

- 每个注册 Store 写入都能 resolve 到预期 domain。
- excluded Store 有 reason。
- 未注册 Store 在 strict 模式失败。
- 同一交易内同一 domain key 的多次映射可被 collector 去重。

### 18.3 Coverage 测试

- 扫描所有 state Store 类，确保 inventory 有记录。
- 新增 Store 类时测试失败，要求更新 registry。
- 所有 `IN_GLOBAL_ROOT` domain 都有 key/value codec。
- 所有 root domain 都启用 `FULL_HISTORY`。

### 18.4 Replay 一致性测试

同一段 block replay 两次，比较：

- DomainWrite 序列完全一致。
- `domainId/domainKey/domainValue` bytes 完全一致。
- root domain 集合和顺序一致。
- registry checksum 一致。

### 18.5 Cross-module 测试

与 `ArchiveTxNumIndex`、`ArchiveTemporalStore` 联测：

```text
txNum 100 写 AccountStore(address=A, balance=1)
Registry -> ACCOUNT / key=A21 / value=account_v1
TemporalStore -> history at txNum 100
Reader(BLOCK_END) -> balance=1
```

与 `CommitmentBuilder` 联测：

- 只 root domain 触发 touch。
- history-only domain 不改变 global root。
- domainId 排序稳定。

## 19. 验收标准

M0/M1 级别：

- 有完整 Store inventory 初版。
- PoC 三个 domain 的 key/value codec 完成。
- Registry checksum 可持久化并在启动校验。
- 未注册 Store 在 strict 模式会失败。
- 查询端和写入端共用 codec。

M2/M3 级别：

- `eth_getBalance`、`eth_getCode`、`eth_getStorageAt` 使用 registry 编码 domain key。
- 历史 `eth_call` 所需的合约元数据和全局参数已纳入 domain。
- codec 稳定性测试覆盖 map/repeated/default/unknown field 风险。

M4/M5 级别：

- 所有 `IN_GLOBAL_ROOT` domain 有完整 history。
- global root 聚合顺序由 domainId 固定。
- schema activation 规则可被 verifier 读取。
- root/proof API 能返回 domain descriptor 和 codec version。

## 20. 实现顺序建议

1. 建立 `ArchiveDomainRegistry` 接口和内存版 registry。
2. 写 PoC 三个 domain 的 key codec：`ACCOUNT`、`CONTRACT_CODE`、`CONTRACT_STORAGE`。
3. 写 PoC value codec，先尽量使用现有 Store raw bytes，同时记录稳定性风险。
4. 建立 Store inventory YAML/CSV。
5. 实现 registry checksum 和启动校验。
6. 接入 `ArchiveWriteCollector`，让 collector 只处理 `DomainWrite`。
7. 增加 strict/observe 模式：
   - observe：未注册 Store 只告警，用于盘点。
   - strict：未注册 Store 失败，用于 root/replay 验证。
8. 增加 codec determinism 测试。
9. 扩展 `CONTRACT_META` 和 `DYNAMIC_GLOBAL`，支撑历史 `eth_call`。
10. 再逐步覆盖 TRC10、governance、delegation/resource 等完整状态 domain。

第一版最重要的是把 schema 边界固定住。不要一开始追求覆盖全部 Store；但任何未覆盖 Store 都必须被系统发现并标记，否则后续 root 的可信度无法评估。
