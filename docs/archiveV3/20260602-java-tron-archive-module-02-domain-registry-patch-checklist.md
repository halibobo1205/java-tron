# java-tron Archive 模块 02：ArchiveDomainRegistry 逐文件 Patch 清单

日期：2026-06-02

> 2026-06-03 更新：本文是旧 `a79693e450` patch 清单，当前实现请先看 [java-tron Archive S3：ArchiveDomainRegistry 4e80 编码执行包](./20260603-java-tron-archive-s3-domain-registry-4e80-coding-packet.md) 和 [模块 02 ArchiveDomainRegistry：4e80 java-tron 源码对照细化](./20260603-java-tron-module-02-domain-registry-4e80-source-deep-dive.md)。当前源码精确冲突标记扫描无命中，旧行号不可直接用于编码。

关联设计：[java-tron Archive 模块 02：ArchiveDomainRegistry 细化设计](./20260521-java-tron-archive-module-02-domain-registry.md)

java-tron 源码对照：[模块 02 ArchiveDomainRegistry：java-tron 源码对照](./20260601-java-tron-module-02-domain-registry-java-tron-source-deep-dive.md)

S3 编码执行包：[java-tron Archive S3：ArchiveDomainRegistry 4e80 编码执行包](./20260603-java-tron-archive-s3-domain-registry-4e80-coding-packet.md)

关联 PR3/PR4 规格：[java-tron Archive PR3/PR4 WriteCollector 代码级实现规格](./20260602-java-tron-archive-pr3-pr4-write-collector-implementation-spec.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 旧文档原始基线：`a79693e450`，当前 4e80 实现请看页头链接。

## 1. 目标

本文把 `ArchiveDomainRegistry` 落到 java-tron 逐文件 patch 级别。这个模块不是 write collector 本身，而是 PR3/PR4 的 schema 前置层：

```text
java-tron Store write
  -> source db name / raw key / raw value
  -> ArchiveDomainRegistry
  -> domain id / canonical key / canonical value / root policy / history policy
```

合并后应具备：

1. 每个 archive domain 有稳定 `domainId`、名称、key codec、value codec。
2. 每个 java-tron Store 有明确分类：root domain、history-only、index/cache ignored、P1/P2 未覆盖。
3. P0 能覆盖 `ACCOUNT`、`CONTRACT`、`CODE`、`CONTRACT_STORAGE`、`DYNAMIC_PROPERTIES` 的 schema 定义。
4. `CONTRACT_STORAGE` 使用逻辑 `(address, slot)` domain key，不把 `storage-row` physical key 当最终 archive key。
5. Registry checksum 能检测不同节点 schema 不一致。
6. 后续 `ArchiveWriteCollector`、`ArchiveTemporalStore`、`ArchiveStateReader`、`CommitmentBuilder` 只能依赖 registry，不各自发明隐式分类。

## 2. 源码事实

| java-tron 位置 | 事实 | 对 registry 的含义 |
| --- | --- | --- |
| `chainbase/src/main/java/org/tron/core/ChainBaseManager.java:79` | `ChainBaseManager` 聚合主要 Store | inventory 以这里为主入口 |
| `ChainBaseManager.java:81` | `AccountStore` | P0 `ACCOUNT` |
| `ChainBaseManager.java:99` | `DynamicPropertiesStore` | P0 `DYNAMIC_PROPERTIES`，但 root key 需 allowlist |
| `ChainBaseManager.java:138` | `AbiStore` | ABI 不在 `contract` 落盘值内，应独立分类 |
| `ChainBaseManager.java:141` | `CodeStore` | P0 `CODE` |
| `ChainBaseManager.java:144` | `ContractStore` | P0 `CONTRACT` |
| `ChainBaseManager.java:147` | `ContractStateStore` | P1/P0+ 候选，TVM 合约状态缓存会写它 |
| `ChainBaseManager.java:156` | `StorageRowStore` | P0 `CONTRACT_STORAGE` 的 physical source |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java:88-93` | 通用 `put(byte[], T)` 调 `revokingDB.put(key, item.getData())` | 大多数 revoking Store 可在这里做 Store write event |
| `TronStoreWithRevoking.java:97-98` | 通用 `delete(byte[])` 调 `revokingDB.delete(key)` | 删除事件统一入口 |
| `AccountStore.java:68` | `AccountStore.put` 重写后仍调用 `super.put` | 通用 hook 可采集 `account` 实际写 |
| `AccountStore.java:87` | `super.put(key, item)` | `ACCOUNT` value 是 `AccountCapsule.getData()` |
| `AccountStore.java:88` | `accountStateCallBackUtils.accountCallBack` | 现有 account root 回调不能替代 archive registry |
| `ContractStore.java:31` / `36-39` | `ContractStore.put` 清 ABI 后直接 `revokingDB.put` | 通用 hook 会漏 `contract`；PR3 必须给 `ContractStore` 单独 hook |
| `ContractStateStore.java:27` / `32` | `ContractStateStore.put` 直接 `revokingDB.put` | 若纳入 `CONTRACT_STATE`，也需单独 hook |
| `AbiStore.java:27` / `32` | `AbiStore.put(byte[], byte[])` 直接 `revokingDB.put` | ABI 写入绕过 `ProtoCapsule` 通用 `put`，需单独分类 |
| `CodeStore.java:16` | DB 名 `code` | `CODE` source db |
| `CodeCapsule.java:38` | `getData()` 返回 bytecode bytes | `CODE` value codec 是 raw bytes |
| `StorageRowStore.java:15` | DB 名 `storage-row` | physical Store 不是 final domain key |
| `StorageRowStore.java:20` | `get` 会把 physical key 填入 row | raw hook 只能看到 physical row key |
| `StorageRowCapsule.java:73` | `getData()` 返回 row value | storage value 是 32-byte value bytes |
| `actuator/src/main/java/org/tron/core/vm/program/Storage.java:46` | `compose(key, addrHash)` 生成 physical row key | physical key 不等于 `(address, slot)` |
| `Storage.java:47` | contractVersion 1 时 slot 先 `sha3(key)` | registry 需记录 storage key version |
| `Storage.java:68` | create2 场景 `generateAddrHash(trxId)` | physical key 还受 trxHash 影响 |
| `Storage.java:96` | `commit()` 落盘 storage cache | PR4 semantic hook 应在这里或 Repository commit 前后建逻辑写 |
| `Storage.java:100` | zero value 删除 row | `CONTRACT_STORAGE` delete/zero 必须统一语义 |
| `RepositoryImpl.java:625` | `saveCode(address, code)` | `CODE` key 是 contract address |
| `RepositoryImpl.java:641` | Constantinople 后设置 contract codeHash | `CONTRACT` 与 `CODE` 有依赖 |
| `RepositoryImpl.java:660` | `putStorageValue(address, key, value)` | 这是 `CONTRACT_STORAGE` 逻辑 key 的入口 |
| `RepositoryImpl.java:948` / `954` | `commitAccountCache` 最终调 `AccountStore.put` | TVM account writes 可被 generic hook 覆盖 |
| `RepositoryImpl.java:960` / `966` | `commitCodeCache` 最终调 `CodeStore.put` | TVM code writes 可被 generic hook 覆盖 |
| `RepositoryImpl.java:972` / `980-982` | `commitContractCache` 先 `AbiStore.put` 再 `ContractStore.put` | `ABI` 和 `CONTRACT` 都需分类；`CONTRACT` 是 store-specific |
| `RepositoryImpl.java:988` / `995` | `commitContractStateCache` 最终调 `ContractStateStore.put` | P1/P0+ `CONTRACT_STATE`，store-specific |
| `RepositoryImpl.java:1001` / `1008` | `commitStorageCache` 最终调 `Storage.commit()` | storage semantic hook 应等到 root repository 持久化边界 |
| `RepositoryImpl.java:1014` / `1020` | `commitDynamicCache` 最终调 `DynamicPropertiesStore.put` | dynamic properties 可被 generic hook 覆盖，但 root 需 allowlist |
| `DynamicPropertiesStore.java:32` | `latest_block_header_timestamp` key | `properties` 同时包含 latest header 与执行参数 |
| `DynamicPropertiesStore.java:73` | `ENERGY_FEE` key | 历史 `eth_call` 需要 execution 参数 |
| `DynamicPropertiesStore.java:142` | `ALLOW_TVM_ISTANBUL` 等 TVM fork key | 必须进入 historical VM config view |

## 3. 模块边界

`ArchiveDomainRegistry` 只负责 schema 和映射，不做以下事情：

- 不分配 `txNum`。
- 不读取 before-value。
- 不持有 `TxWriteSet`。
- 不写 temporal DB。
- 不计算 root。
- 不决定 RPC 如何读。

它对外提供稳定的 domain 描述和 Store binding，后续模块按它执行。

## 4. Patch 1：新增 domain 包结构

新增目录：

```text
chainbase/src/main/java/org/tron/core/archive/domain/
```

新增文件：

```text
ArchiveDomain.java
ArchiveDomainDescriptor.java
ArchiveDomainRegistry.java
DefaultArchiveDomainRegistry.java
StoreBinding.java
StoreCategory.java
RootPolicy.java
HistoryPolicy.java
ReaderPolicy.java
CanonicalKeyCodec.java
CanonicalValueCodec.java
ArchiveKeyCodec.java
ArchiveValueCodec.java
ArchiveDomainCodecs.java
RegistryChecksum.java
StoreInventoryEntry.java
UnclassifiedStorePolicy.java
```

放在 `chainbase` 的原因：

- `TronStoreWithRevoking` 在 `chainbase`，PR3 会直接调用 registry。
- `ArchiveTemporalStore`、`ArchiveStateReader`、`CommitmentBuilder` 都应依赖同一套 schema。
- `framework` 的 Manager/RPC 可以依赖 `chainbase`，反向不成立。

## 5. Patch 2：枚举和策略

### 5.1 `ArchiveDomain`

推荐 P0/P1 编号：

```java
public enum ArchiveDomain {
  ACCOUNT(0x0001, "account"),
  CONTRACT(0x0002, "contract"),
  CODE(0x0003, "code"),
  CONTRACT_STORAGE(0x0004, "contract-storage"),
  DYNAMIC_PROPERTIES(0x0005, "dynamic-properties"),
  CONTRACT_STATE(0x0006, "contract-state"),
  ABI(0x0101, "abi"),
  RECEIPT_LOG_CACHE(0x0102, "receipt-log-cache"),
  ACCOUNT_TRACE(0x0103, "account-trace");
}
```

规则：

- `domainId` 使用 `uint16`，编码为大端。
- `0x0001-0x00ff` 保留给 root/domain-root 候选状态。
- `0x0100-0x01ff` 保留给 history-only / cache / query domain。
- 一旦发布，`domainId` 不得复用。
- root 聚合必须按 `domainId` 升序，不能按 enum 声明顺序或 `Map` 迭代顺序。

### 5.2 `RootPolicy`

```java
public enum RootPolicy {
  IN_GLOBAL_ROOT,
  DOMAIN_ROOT_ONLY,
  HISTORY_ONLY,
  EXCLUDED
}
```

P0 建议（PR1-PR6 影子 root 阶段）：

| domain | RootPolicy | 原因 |
| --- | --- | --- |
| `ACCOUNT` | `DOMAIN_ROOT_ONLY` | 先影子计算，暂不承诺完整 global root |
| `CONTRACT` | `DOMAIN_ROOT_ONLY` | 合约主体是执行状态，但 P0 global root 不完整 |
| `CODE` | `DOMAIN_ROOT_ONLY` | 历史 `eth_getCode` 和 `eth_call` 必需 |
| `CONTRACT_STORAGE` | `DOMAIN_ROOT_ONLY` | 历史 `eth_getStorageAt` 和 `eth_call` 必需 |
| `DYNAMIC_PROPERTIES` | `DOMAIN_ROOT_ONLY` + key allowlist | 全量 properties 混有 latest/index/开关，需筛选 |
| `ABI` | `HISTORY_ONLY` | 不应混入 `CONTRACT` root，RPC 需要时可历史查询 |
| `ACCOUNT_TRACE` | `HISTORY_ONLY` 或 `EXCLUDED` | 查询辅助，不是 canonical root |
| block/tx/index/cache stores | `EXCLUDED` | 链数据或索引，不是状态 root |

PR7 启用 `CommitmentBuilder` 并发布 `coverage=TVM_STATE_ONLY` 的 block-end global root 时，应把 `ACCOUNT/CONTRACT/CODE/CONTRACT_STORAGE` 从 `DOMAIN_ROOT_ONLY` 升级为 `IN_GLOBAL_ROOT`。如果仍保持 `DOMAIN_ROOT_ONLY`，PR7 只能发布 domain roots，不能把 global root 描述为 TVM state root。

### 5.3 `HistoryPolicy`

```java
public enum HistoryPolicy {
  FULL_HISTORY,
  LATEST_ONLY,
  CHECKPOINT_ONLY,
  NO_ARCHIVE
}
```

P0 root/domain-root 候选必须使用 `FULL_HISTORY`。否则后续无法在任意 `StatePoint` 重放 root 或证明。

### 5.4 `ReaderPolicy`

```java
public enum ReaderPolicy {
  STATE_READER,
  DEBUG_ONLY,
  INTERNAL_ONLY,
  NOT_READABLE
}
```

示例：

- `ACCOUNT/CODE/CONTRACT_STORAGE/DYNAMIC_PROPERTIES` 是 `STATE_READER`。
- `CONTRACT` 可为 `STATE_READER`，历史 `eth_call` 需要合约版本/codeHash/trxHash。
- `ABI` 是 `DEBUG_ONLY` 或 `STATE_READER`，取决于是否要支持历史 `getContractInfo`。
- index/cache Store 是 `NOT_READABLE`。

### 5.5 `StoreCategory`

```java
public enum StoreCategory {
  ROOT_STATE,
  HISTORY_ONLY,
  INDEX_OR_CACHE,
  BLOCK_OR_TX_DATA,
  LOCAL_METADATA,
  PENDING_P1_STATE,
  UNCLASSIFIED
}
```

它用于 inventory 和诊断，不直接等于 root policy。

## 6. Patch 3：Descriptor 数据结构

### 6.1 `ArchiveDomainDescriptor`

建议字段：

```java
public final class ArchiveDomainDescriptor {
  private final ArchiveDomain domain;
  private final int domainId;
  private final String domainName;
  private final RootPolicy rootPolicy;
  private final HistoryPolicy historyPolicy;
  private final ReaderPolicy readerPolicy;
  private final CanonicalKeyCodec keyCodec;
  private final CanonicalValueCodec valueCodec;
  private final int schemaVersion;
  private final long activationBlock;
  private final boolean largeValue;
  private final boolean prefixScan;
  private final List<String> sourceDbNames;
  private final List<ArchiveDomain> dependencies;
  private final DynamicKeyPolicy dynamicKeyPolicy;
}
```

`dynamicKeyPolicy` 可先做成简单对象：

```text
ALL_KEYS
ALLOWLIST
DENYLIST
```

P0 只有 `DYNAMIC_PROPERTIES` 需要 allowlist。

### 6.2 `StoreBinding`

建议字段：

```java
public enum RawHookMode {
  GENERIC_TRON_STORE,
  STORE_SPECIFIC,
  SEMANTIC_ONLY,
  IGNORED
}

public final class StoreBinding {
  private final String dbName;
  private final StoreCategory category;
  private final ArchiveDomain domain;
  private final RawHookMode rawHookMode;
  private final boolean warnWhenWritten;
  private final String reason;
}
```

说明：

- `GENERIC_TRON_STORE`：通用 `TronStoreWithRevoking` hook 可直接转为 `DomainWrite`，例如 `account`、`code`、allowlist 内的 `properties`。
- `STORE_SPECIFIC`：该 Store 绕过通用 hook，必须在具体 Store 方法中采集，例如 `ContractStore`、`AbiStore`、`ContractStateStore`。
- `SEMANTIC_ONLY`：不能只靠 raw Store key，例如 `storage-row` 对应的 `CONTRACT_STORAGE` 必须从 `(address, slot)` 语义写入采集。
- `IGNORED`：不是 archive root/history 需要采集的状态 Store。
- `warnWhenWritten=true`：P0 未覆盖但执行期间可能写入，需告警和计数。

早期文档里的 `collectRawStoreWrites/requiresSemanticHook` 两个 boolean 只作为语义来源；实现时以 S3 编码执行包里的 `RawHookMode` 为准。

### 6.3 `StoreInventoryEntry`

用于覆盖检查：

```java
public final class StoreInventoryEntry {
  private final String dbName;
  private final String storeClassName;
  private final StoreCategory category;
  private final ArchiveDomain domain;
  private final String policyReason;
}
```

Inventory 必须覆盖 `ChainBaseManager` 注入的 Store 和常见 `TronDatabase`，至少覆盖本文第 11 节表格。

## 7. Patch 4：Codec

### 7.1 接口

```java
public interface CanonicalKeyCodec {
  byte[] encodeStoreKey(StoreWriteEvent event);

  byte[] encodeSemanticKey(SemanticStoreWrite event);
}

public interface CanonicalValueCodec {
  byte[] encodeAfterValue(StoreWriteEvent event);

  byte[] encodeBeforeValue(byte[] rawBeforeValue);
}
```

PR3 可先让 `encodeSemanticKey` 只服务 storage，其他 domain 抛 `UnsupportedOperationException`。

### 7.2 Delete 编码

不要把 tombstone 混进 value bytes。建议 `DomainWrite` 显式携带：

```java
private final boolean delete;
private final byte[] beforeValue;
private final byte[] afterValue; // delete 时为 null
```

TemporalStore 后续再决定落盘 tombstone marker。

### 7.3 P0 codec 表

| domain | source | canonical key | canonical value |
| --- | --- | --- | --- |
| `ACCOUNT` | `account` | 21-byte TRON address | `AccountCapsule.getData()`，即 protobuf `Account.toByteArray()` |
| `CONTRACT` | `contract` | 21-byte contract address | `ContractStore` 清 ABI 后的实际 `SmartContract.toByteArray()` |
| `CODE` | `code` | 21-byte contract address | bytecode bytes |
| `CONTRACT_STORAGE` | semantic hook | `address21 || slot32 || keyVersion1` | 32-byte storage value |
| `DYNAMIC_PROPERTIES` | `properties` | raw property key bytes | raw `BytesCapsule.getData()` |
| `ABI` | `abi` | 21-byte contract address | ABI protobuf bytes |

### 7.4 `CONTRACT_STORAGE` codec

不要使用 `Storage.compose()` 的 result 作为 archive domain key。

源码事实：

```text
Storage.compose(key, addrHash):
  if contractVersion == 1:
      key = sha3(key)
  result = first 16 bytes of addrHash + last 16 bytes of key
```

并且 create2 场景：

```text
addrHash = sha3(address || trxHash)
```

所以 physical key 不是可逆的 `(address, slot)`。

P0 semantic key：

```text
domainKey = address21 || normalizedSlot32 || storageKeyVersion1
```

`storageKeyVersion1` 建议：

| value | 含义 |
| --- | --- |
| `0x00` | raw TVM slot |
| `0x01` | contractVersion 1，java-tron 读写前对 slot 做 sha3 |

Reader 查 `eth_getStorageAt(address, slot)` 时按同一规则解析，不需要知道 physical row key。

### 7.5 Dynamic properties key allowlist

`properties` Store 包含执行参数、latest header、计数器、迁移标记和历史价格字符串，不能一律当 root state。

P0 建议：

```text
history: 记录所有 properties key
root: 只允许 execution parameter allowlist
reader: ArchiveDynamicPropertiesView 只暴露 VM/资源/费用需要的 key
```

初始 allowlist 不应在本文硬编码完整名单，但必须由 `DefaultArchiveDomainRegistry` 显式返回。最少要覆盖：

```text
ENERGY_FEE
MAX_CPU_TIME_OF_ONE_TX
CREATE_ACCOUNT_FEE
CREATE_NEW_ACCOUNT_FEE_IN_SYSTEM_CONTRACT
TRANSACTION_FEE
ALLOW_CREATION_OF_CONTRACTS
ALLOW_TVM_TRANSFER_TRC10
ALLOW_TVM_ISTANBUL
ALLOW_TVM_CONSTANTINOPLE
ALLOW_TVM_SOLIDITY_059
ALLOW_TVM_LONDON
ALLOW_TVM_COMPATIBLE_EVM
ALLOW_TVM_SHANGHAI
ALLOW_TVM_CANCUN
ALLOW_TVM_BLOB
ALLOW_TVM_OSAKA
ALLOW_TVM_PRAGUE
ALLOW_STRICT_MATH
ALLOW_DYNAMIC_ENERGY
DYNAMIC_ENERGY_THRESHOLD
DYNAMIC_ENERGY_INCREASE_FACTOR
DYNAMIC_ENERGY_MAX_FACTOR
MAX_FEE_LIMIT
```

PR8 historical `eth_call` 细化时可以继续扩充。扩充 allowlist 会改变 root input，必须纳入 registry checksum。

## 8. Patch 5：DefaultArchiveDomainRegistry

### 8.1 文件

```text
chainbase/src/main/java/org/tron/core/archive/domain/DefaultArchiveDomainRegistry.java
```

推荐作为 Spring bean：

```java
@Component
public final class DefaultArchiveDomainRegistry implements ArchiveDomainRegistry {
  ...
}
```

### 8.2 核心 API

```java
public interface ArchiveDomainRegistry {
  Optional<ArchiveDomainDescriptor> descriptor(ArchiveDomain domain);

  Optional<StoreBinding> bindingForStore(String dbName);

  Optional<DomainWrite> mapStoreWrite(StoreWriteEvent event);

  Optional<DomainWrite> mapSemanticWrite(SemanticStoreWrite event);

  boolean isIgnoredStore(String dbName);

  List<StoreInventoryEntry> inventory();

  RegistryChecksum checksum();
}
```

`mapStoreWrite` 只能处理 raw key 可直接成为 canonical key 的 Store：

```text
account
contract
code
properties
abi
```

`storage-row` raw event 不应直接映射成 `CONTRACT_STORAGE`，除非只是 debug/diagnostic event。

### 8.3 Registry checksum

`RegistryChecksum` 输入必须按稳定排序：

```text
archive schema version
domain descriptors sorted by domainId
store bindings sorted by dbName
codec identifiers and versions
root/history/reader policy
dynamic properties allowlist
activation block
dependency list
```

输出：

```java
public final class RegistryChecksum {
  private final byte[] hash;
  private final int schemaVersion;
}
```

Hash 函数建议复用 java-tron 现有 `Sha256Hash`，但输入必须是 registry 自己的稳定 byte stream，不能用 Java object serialization。

## 9. Patch 6：P0 domain descriptors

### 9.1 `ACCOUNT`

```text
domainId = 0x0001
domainName = account
sourceDbName = account
rootPolicy = DOMAIN_ROOT_ONLY
PR7 commitment coverage=TVM_STATE_ONLY 时改为 IN_GLOBAL_ROOT
historyPolicy = FULL_HISTORY
readerPolicy = STATE_READER
keyCodec = TronAddressKeyCodec(21 bytes)
valueCodec = RawValueCodec(AccountCapsule.getData bytes)
```

注意：

- `AccountStateEntity` 只抽取 address/balance/allowance，不能作为 archive canonical value。
- `AccountStore.put` 已记录 balance trace 和 account callback，但 archive 仍应采集实际 Store write。

### 9.2 `CONTRACT`

```text
domainId = 0x0002
sourceDbName = contract
rootPolicy = DOMAIN_ROOT_ONLY
PR7 commitment coverage=TVM_STATE_ONLY 时改为 IN_GLOBAL_ROOT
historyPolicy = FULL_HISTORY
readerPolicy = STATE_READER
keyCodec = TronAddressKeyCodec
valueCodec = ContractStoredValueCodec
dependencies = [CODE]
```

特殊点：

- `ContractStore.put` 会清 ABI 后落盘。
- Registry/collector 必须记录清 ABI 后的 value。
- `RepositoryImpl.saveCode` 会更新 contract codeHash，历史 `eth_call` 需要能读到对应版本的 contract metadata。

### 9.3 `CODE`

```text
domainId = 0x0003
sourceDbName = code
rootPolicy = DOMAIN_ROOT_ONLY
PR7 commitment coverage=TVM_STATE_ONLY 时改为 IN_GLOBAL_ROOT
historyPolicy = FULL_HISTORY
readerPolicy = STATE_READER
keyCodec = TronAddressKeyCodec
valueCodec = RawBytesValueCodec
```

第一阶段按 address 保存 code，不改成 codeHash key。原因：

- `RepositoryImpl.saveCode(address, code)` 和 `getCode(address)` 以地址为主键。
- `eth_getCode(address, block)` 也以地址查询。
- 改成 codeHash key 需要额外处理 `CONTRACT.codeHash -> CODE` 的二级索引。

### 9.4 `CONTRACT_STORAGE`

```text
domainId = 0x0004
sourceDbName = storage-row
rootPolicy = DOMAIN_ROOT_ONLY
PR7 commitment coverage=TVM_STATE_ONLY 时改为 IN_GLOBAL_ROOT
historyPolicy = FULL_HISTORY
readerPolicy = STATE_READER
keyCodec = ContractStorageLogicalKeyCodec
valueCodec = StorageWordValueCodec
requiresSemanticHook = true
```

raw `storage-row` StoreBinding：

```text
dbName = storage-row
category = ROOT_STATE
domain = CONTRACT_STORAGE
collectRawStoreWrites = false
requiresSemanticHook = true
warnWhenWritten = false
reason = physical key is not reversible to address/slot
```

PR4 应新增 semantic event，示意：

```java
public final class SemanticStoreWrite {
  private final ArchiveDomain domain;
  private final byte[] address;
  private final byte[] storageSlot;
  private final byte[] beforeValue;
  private final byte[] afterValue;
  private final int storageKeyVersion;
  private final byte[] physicalRowKey;
}
```

### 9.5 `DYNAMIC_PROPERTIES`

```text
domainId = 0x0005
sourceDbName = properties
rootPolicy = DOMAIN_ROOT_ONLY
historyPolicy = FULL_HISTORY
readerPolicy = STATE_READER
keyCodec = RawBytesKeyCodec
valueCodec = RawBytesValueCodec
dynamicKeyPolicy = ALLOWLIST for root, ALL_KEYS for history
```

注意：

- `latest_block_header_*` 这类 key 是 block progress/header metadata，进入 history 有用，但不一定应进 root。
- `ALLOW_TVM_*`、费用、资源参数需要历史读取，PR8 的 historical `eth_call` 会依赖。

### 9.6 `ABI`

```text
domainId = 0x0101
sourceDbName = abi
rootPolicy = HISTORY_ONLY
historyPolicy = FULL_HISTORY
readerPolicy = DEBUG_ONLY
```

理由：

- `ContractStore` 主体落盘值清除了 ABI。
- `RepositoryImpl.commitContractCache` 如果 `abiStore` 未存在，会单独写 `AbiStore`。
- ABI 不应悄悄混入 `CONTRACT` canonical value。

## 10. Patch 7：StoreBinding inventory

P0 必须显式分类以下 Store。

### 10.1 P0 root/domain-root 候选

| dbName | Store | domain | binding |
| --- | --- | --- | --- |
| `account` | `AccountStore` | `ACCOUNT` | `GENERIC_TRON_STORE` |
| `contract` | `ContractStore` | `CONTRACT` | `STORE_SPECIFIC` after ABI clear |
| `code` | `CodeStore` | `CODE` | `GENERIC_TRON_STORE` |
| `storage-row` | `StorageRowStore` | `CONTRACT_STORAGE` | `SEMANTIC_ONLY` |
| `properties` | `DynamicPropertiesStore` | `DYNAMIC_PROPERTIES` | `GENERIC_TRON_STORE`, root allowlist |

### 10.2 P0 history-only / debug

| dbName | Store | domain | policy |
| --- | --- | --- | --- |
| `abi` | `AbiStore` | `ABI` | history-only |

### 10.3 P1/P2 execution-state candidates

这些 Store 可能影响执行或治理语义，P0 不能承诺完整 global root 前必须逐一补齐或证明可排除。

| dbName | Store | 建议 domain | 备注 |
| --- | --- | --- | --- |
| `contract-state` | `ContractStateStore` | `CONTRACT_STATE` | Repository commit 会写；put 也绕过 `super.put` |
| `asset-issue` | `AssetIssueStore` | `ASSET_TRC10` | TRC10 状态 |
| `asset-issue-v2` | `AssetIssueV2Store` | `ASSET_TRC10` | TRC10 V2 |
| `account-asset` | `AccountAssetStore` | `ASSET_TRC10` | 继承 `TronDatabase`，不走 revoking hook |
| `witness` | `WitnessStore` | `WITNESS_GOVERNANCE` | witness/reward/governance |
| `witness_schedule` | `WitnessScheduleStore` | `WITNESS_GOVERNANCE` | schedule 状态 |
| `votes` | `VotesStore` | `WITNESS_GOVERNANCE` | Repository commit 会写 |
| `proposal` | `ProposalStore` | `WITNESS_GOVERNANCE` | proposalController 写 |
| `DelegatedResource` | `DelegatedResourceStore` | `DELEGATION_RESOURCE` | Repository commit 会写 |
| `delegation` | `DelegationStore` | `DELEGATION_RESOURCE` | reward/cycle/brokerage |
| `DelegatedResourceAccountIndex` | `DelegatedResourceAccountIndexStore` | `DELEGATION_RESOURCE` 或 index | 需确认是否只是索引 |
| `exchange` | `ExchangeStore` | `EXCHANGE_MARKET` | exchange 状态 |
| `exchange-v2` | `ExchangeV2Store` | `EXCHANGE_MARKET` | exchange V2 |
| `market_account` | `MarketAccountStore` | `EXCHANGE_MARKET` | market order account |
| `market_order` | `MarketOrderStore` | `EXCHANGE_MARKET` | order state |
| `market_pair_to_price` | `MarketPairToPriceStore` | `EXCHANGE_MARKET` | 可能是 index，需二次确认 |
| `market_pair_price_to_order` | `MarketPairPriceToOrderStore` | `EXCHANGE_MARKET` | 可能是 index，需二次确认 |
| `nullifier` | `NullifierStore` | `SHIELDED_STATE` | shielded 交易状态 |
| `zkProof` | `ZKProofStore` | `SHIELDED_STATE` 或 local cache | 继承 `TronDatabase`，需确认 |
| `IncrementalMerkleTree` | `IncrementalMerkleTreeStore` | `SHIELDED_STATE` | shielded tree |

### 10.4 index/cache/block/tx excluded

| dbName | Store | category | reason |
| --- | --- | --- | --- |
| `block` | `BlockStore` | `BLOCK_OR_TX_DATA` | 链数据，不是状态 domain |
| `block-index` | `BlockIndexStore` | `INDEX_OR_CACHE` | block num/hash 索引 |
| `tree-block-index` | `TreeBlockIndexStore` | `INDEX_OR_CACHE` | block tree index |
| `trans` | `TransactionStore` | `BLOCK_OR_TX_DATA` | tx 数据/索引，非 state root |
| `transactionRetStore` | `TransactionRetStore` | `BLOCK_OR_TX_DATA` | receipt/result |
| `transactionHistoryStore` | `TransactionHistoryStore` | `BLOCK_OR_TX_DATA` | tx info history |
| `recent-block` | `RecentBlockStore` | `INDEX_OR_CACHE` | recent cache |
| `recent-transaction` | `RecentTransactionStore` | `INDEX_OR_CACHE` | recent cache |
| `trans-cache` | `TransactionCache` | `INDEX_OR_CACHE` | tx cache |
| `balance-trace` | `BalanceTraceStore` | `INDEX_OR_CACHE` | balance trace 查询辅助 |
| `account-trace` | `AccountTraceStore` | `INDEX_OR_CACHE` | account trace 查询辅助 |
| `section-bloom` | `SectionBloomStore` | `INDEX_OR_CACHE` | event bloom index |
| `account-index` | `AccountIndexStore` | `INDEX_OR_CACHE` | account name index |
| `accountid-index` | `AccountIdIndexStore` | `INDEX_OR_CACHE` | account id index |
| `common` | `CommonStore` | `LOCAL_METADATA` | 非 revoking state |
| `common-database` | `CommonDataBase` | `LOCAL_METADATA` | local/common metadata |
| `pbft-sign-data` | `PbftSignDataStore` | `LOCAL_METADATA` | PBFT local/sign data |
| `block_KDB` | `KhaosDatabase` | `LOCAL_METADATA` | fork cache |
| `tmp` | `CheckTmpStore` | `LOCAL_METADATA` | tmp |
| `reward-vi` | `RewardViStore` | `LOCAL_METADATA` 或 P1 | 需确认是否迁移/奖励 view |
| checkpoint stores | `CheckPointV2Store` | `LOCAL_METADATA` | checkpoint metadata |

## 11. Patch 8：特殊 Store hook 要求

Registry 本身不实现 hook，但必须告诉 PR3/PR4 哪些 Store 不能走通用 `TronStoreWithRevoking.put`。

### 11.1 `ContractStore`

文件：

```text
chainbase/src/main/java/org/tron/core/store/ContractStore.java
```

当前直接：

```java
if (item.getInstance().hasAbi()) {
  item = new ContractCapsule(item.getInstance().toBuilder().clearAbi().build());
}
revokingDB.put(key, item.getData());
```

PR3 hook 应放在 ABI 清理之后：

```text
before = revokingDB.getUnchecked(key)
after = item.getData()
archiveService.onStorePut("contract", key, before, after)
revokingDB.put(key, after)
```

### 11.2 `ContractStateStore`

如果 P0 纳入 `CONTRACT_STATE`，必须同样单独 hook：

```text
before = revokingDB.getUnchecked(key)
after = item.getData()
archiveService.onStorePut("contract-state", key, before, after)
revokingDB.put(key, after)
```

如果 P0 暂不纳入，应在 registry 标为 `PENDING_P1_STATE`，并在写入时按 `warnUnclassifiedStoreWrites` 告警。

### 11.3 `AbiStore`

`AbiStore.put(byte[], byte[])` 不是 `ProtoCapsule` 通用签名：

```text
before = revokingDB.getUnchecked(key)
after = value
archiveService.onStorePut("abi", key, before, after)
revokingDB.put(key, after)
```

### 11.4 `TransactionStore`

`TransactionStore.put` 在 `transHistory.switch=off` 时只写 blockNum：

```text
revokingDB.put(key, ByteArray.fromLong(item.getBlockNum()))
```

`trans` 不进入 state domain。Registry 应把它标为 `BLOCK_OR_TX_DATA`，collector 不应把它放进 `TxWriteSet`。

### 11.5 `AccountAssetStore` / `ZKProofStore` / `TronDatabase`

这些不继承 `TronStoreWithRevoking` 或不符合 `ProtoCapsule` 通用 hook。P0 不采集时必须在 inventory 标注原因，不能留成未知 Store。

## 12. Patch 9：与 WriteCollector 的接口

`ArchiveWriteCollector` 不应直接硬编码 dbName。它应调用 registry：

```java
Optional<DomainWrite> maybeWrite = domainRegistry.mapStoreWrite(event);
maybeWrite.ifPresent(currentTxWriteSet::add);
```

对 `storage-row`：

```text
mapStoreWrite(raw storage-row event) -> empty, optional diagnostic
mapSemanticWrite(CONTRACT_STORAGE event) -> DomainWrite
```

对 ignored Store：

```text
binding.category = INDEX_OR_CACHE / BLOCK_OR_TX_DATA / LOCAL_METADATA
collector increments ignored counter
no DomainWrite
```

对 unclassified Store：

```text
warnUnclassifiedStoreWrites=true:
  once per block/dbName warn
  metrics counter
warnUnclassifiedStoreWrites=false:
  metrics only
```

不要在 P0 因未分类 Store 直接 fail block。P0 coverage 是 `TVM_STATE_ONLY`，不是完整 TRON state root。

## 13. Patch 10：测试清单

### 13.1 Registry descriptor 测试

文件建议：

```text
chainbase/src/test/java/org/tron/core/archive/domain/DefaultArchiveDomainRegistryTest.java
```

覆盖：

1. `ACCOUNT/CONTRACT/CODE/CONTRACT_STORAGE/DYNAMIC_PROPERTIES` descriptor 存在。
2. domainId 固定且唯一。
3. root/history/reader policy 符合 P0 表。
4. `domainId` 排序稳定。
5. `checksum()` 多次调用结果一致。

### 13.2 Store binding 测试

覆盖：

1. `account -> ACCOUNT`。
2. `contract -> CONTRACT`，并标记 special hook 或可 raw collect after transform。
3. `code -> CODE`。
4. `storage-row -> CONTRACT_STORAGE`，`rawHookMode=SEMANTIC_ONLY`，raw Store write 不直接生成 `DomainWrite`。
5. `properties -> DYNAMIC_PROPERTIES`。
6. `trans/block/recent-block/section-bloom/account-trace` 等 ignored。
7. P1/P2 Store 不应是 `UNCLASSIFIED`。

### 13.3 Codec 测试

覆盖：

| case | 断言 |
| --- | --- |
| account address key | 21 bytes 原样输出 |
| invalid account key | 抛异常或返回 empty mapping |
| code key/value | key 为地址，value 原样 bytecode |
| contract value | 输入 SmartContract with ABI，经 ContractStore transform 后 value 不含 ABI |
| dynamic property | key/value 原样，root allowlist 正确 |
| storage semantic key | `address21 || slot32 || version1` |
| storage raw key | raw `storage-row` 不直接映射成 DomainWrite |
| delete | `afterValue=null` 且 `delete=true` |

### 13.4 Inventory 覆盖测试

测试目标：

```text
ChainBaseManager 中列出的 Store dbName 都能在 registry.inventory() 找到
```

如果直接反射 Spring 字段过重，可以先用静态 expected list 单测。后续 PR 可加集成测试。

### 13.5 Checksum 测试

覆盖：

1. descriptor 顺序变化不改变 checksum。
2. domain policy 变化改变 checksum。
3. dynamic properties allowlist 变化改变 checksum。
4. codec version 变化改变 checksum。

## 14. Review Checklist

合并前逐项检查：

- [ ] P0 domain id 固定且唯一。
- [ ] `ACCOUNT/CONTRACT/CODE/CONTRACT_STORAGE/DYNAMIC_PROPERTIES` descriptor 完整。
- [ ] `CONTRACT_STORAGE` raw `storage-row` 不直接映射成最终 domain key。
- [ ] `DYNAMIC_PROPERTIES` root 使用 allowlist，history 可全量。
- [ ] `ContractStore` 清 ABI 后的实际落盘值被定义为 canonical value。
- [ ] `AbiStore` 独立 history-only，不混入 `CONTRACT`。
- [ ] `ContractStateStore` 明确标为 P1/P0+，不是 unknown。
- [ ] block/tx/index/cache Store 明确 `EXCLUDED` 或 ignored。
- [ ] P1/P2 执行状态候选都在 inventory 中有说明。
- [ ] Registry checksum 覆盖 descriptor、binding、codec、policy、allowlist。
- [ ] WriteCollector 只通过 registry 做 dbName -> domain 映射。
- [ ] 未分类 Store 只诊断，不在 P0 直接 fail block。

## 15. 对后续模块的接口承诺

PR3/PR4 WriteCollector 依赖：

```text
StoreWriteEvent -> ArchiveDomainRegistry.mapStoreWrite -> DomainWrite
SemanticStoreWrite -> ArchiveDomainRegistry.mapSemanticWrite -> DomainWrite
```

PR5 TemporalStore 依赖：

```text
ArchiveDomainDescriptor.historyPolicy
ArchiveDomainDescriptor.keyCodec/valueCodec
```

PR6 StateReader 依赖：

```text
ReaderPolicy.STATE_READER domains
ACCOUNT/CODE/CONTRACT_STORAGE/DYNAMIC_PROPERTIES key codec
```

PR7 CommitmentBuilder 依赖：

```text
RootPolicy.DOMAIN_ROOT_ONLY / IN_GLOBAL_ROOT
domainId sorted order
registry checksum
dynamic properties root allowlist
```

PR8 historical `eth_call` 依赖：

```text
CONTRACT
CODE
CONTRACT_STORAGE
DYNAMIC_PROPERTIES
```

因此 registry 一旦落地，后续模块不得绕过它直接按 dbName 写死 domain 规则。
