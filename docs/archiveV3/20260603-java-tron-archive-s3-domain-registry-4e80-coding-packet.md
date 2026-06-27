# java-tron Archive S3：ArchiveDomainRegistry 4e80 编码执行包

> ⚠️ **枚举已被冻结契约取代**：域 ID 表与 `RawHookMode` **以 L3 为准**——`0x0101 = CONTRACT_STATE`（非本文的 ABI），`RawHookMode` 必含 `GENERIC_TRON_STORE_ALLOWLIST` / `IGNORE_RAW`（勿用本文的 `IGNORED/UNCLASSIFIED`）。详见 [00-ARCHIVE-V3-AUTHORITY-AND-DECISIONS.md](00-ARCHIVE-V3-AUTHORITY-AND-DECISIONS.md) §2。

日期：2026-06-03

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`，`git status --short` 为空，精确冲突标记扫描无命中。

归属路线：[java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)

模块来源：[模块 02 ArchiveDomainRegistry：4e80 java-tron 源码对照细化](./20260603-java-tron-module-02-domain-registry-4e80-source-deep-dive.md)

前置依赖：[java-tron Archive S1/S2：4e80 编码执行包](./20260603-java-tron-archive-s1-s2-4e80-coding-packet.md)

收窄执行包：[java-tron Archive L3：ArchiveDomainRegistry 代码级执行包](./20260605-java-tron-archive-l3-domain-registry-4e80-code-plan.md)

S3 是 Store hook 前的 schema gate。它只定义 domain、Store 分类、codec、policy 和 checksum；不采集 write-set，不写 temporal DB，不改 Manager/RPC。

实际编码时以 L3 收窄执行包为准；本文保留为 S3 的上游背景和边界约束。

## 1. S3 目标

S3 完成后，后续 S4/S5/S6/S11 不能再各自硬编码 Store 名称。所有分类都必须来自：

```text
ArchiveDomainRegistry
  dbName -> StoreBinding
  domain -> ArchiveDomainDescriptor
  domain -> CanonicalKeyCodec / CanonicalValueCodec
  domain -> RootPolicy / HistoryPolicy / ReaderPolicy
  dynamic property key -> DynamicKeyPolicy
```

P0 domain：

```text
ACCOUNT
CONTRACT
CODE
CONTRACT_STORAGE
DYNAMIC_PROPERTIES
```

P1/P0+ 候选：

```text
CONTRACT_STATE
ABI
TRC10 / delegated resource / votes / proposal / market
```

S3 不做：

- 不改 `TronStoreWithRevoking.put/delete`。
- 不改 `ContractStore/AbiStore/ContractStateStore`。
- 不改 `actuator/.../Storage.commit()`。
- 不新增 `BlockWriteSet`。
- 不持久化 archive 数据。
- 不计算 commitment root。

## 2. 当前源码锚点

### 2.1 ChainBaseManager inventory

`chainbase/src/main/java/org/tron/core/ChainBaseManager.java` 是 Store 聚合入口。

| 源码 | Store | S3 分类 |
| --- | --- | --- |
| `ChainBaseManager.java:81` | `AccountStore` | P0 `ACCOUNT` |
| `ChainBaseManager.java:99` | `DynamicPropertiesStore` | P0 `DYNAMIC_PROPERTIES`，必须 allowlist |
| `ChainBaseManager.java:138` | `AbiStore` | P1/debug，store-specific |
| `ChainBaseManager.java:141` | `CodeStore` | P0 `CODE` |
| `ChainBaseManager.java:144` | `ContractStore` | P0 `CONTRACT`，store-specific |
| `ChainBaseManager.java:147` | `ContractStateStore` | P1/P0+，store-specific |
| `ChainBaseManager.java:156` | `StorageRowStore` | physical backing only，`SEMANTIC_ONLY` |
| `ChainBaseManager.java:178` | `DelegationStore` | P1 delegated resource |
| `ChainBaseManager.java:186` | `CommonStore` | local/common metadata，excluded |
| `ChainBaseManager.java:190` | `TransactionStore` | transaction data，excluded |
| `ChainBaseManager.java:193` | `TransactionRetStore` | receipt/ret data，excluded |
| `ChainBaseManager.java:196/199` | `RecentBlockStore/RecentTransactionStore` | cache，excluded |
| `ChainBaseManager.java:202` | `TransactionHistoryStore` | tx history，excluded from state |
| `ChainBaseManager.java:218/222` | `BalanceTraceStore/AccountTraceStore` | existing balance-history aid，excluded |
| `ChainBaseManager.java:234` | `SectionBloomStore` | log bloom index，excluded |

S3 registry test 要覆盖这些已知 Store：要么映射到 domain，要么显式标成 P1/P2/excluded。unknown dbName 不能静默当作 excluded。

### 2.2 P0 Store facts

| Store | 源码 | 事实 | S3 结论 |
| --- | --- | --- | --- |
| `AccountStore` | `AccountStore.java:44-45`、`68-88`、`92-104` | DB name `account`；put/delete 最终走 `super` | `GENERIC_TRON_STORE` |
| `CodeStore` | `CodeStore.java:13-17` | DB name `code`；未重写 put/delete | `GENERIC_TRON_STORE` |
| `DynamicPropertiesStore` | `DynamicPropertiesStore.java:30`、`261-263` | DB name `properties`；继承 generic store | `GENERIC_TRON_STORE` + allowlist |
| `ContractStore` | `ContractStore.java:31-39` | 清 ABI 后直接 `revokingDB.put` | `STORE_SPECIFIC` |
| `StorageRowStore` | `StorageRowStore.java:15-23` | DB name `storage-row`；`get` 把 physical key 填回 capsule | raw physical key 不可作为 canonical storage key |

### 2.3 Store-specific facts

| Store | 源码 | 事实 | S4/S5 影响 |
| --- | --- | --- | --- |
| `ContractStore` | `ContractStore.java:36-39` | stored value 是清 ABI 后的 `ContractCapsule.getData()` | S4 必须在 store-specific hook 后采 after value |
| `AbiStore` | `AbiStore.java:27-32` | overload `put(byte[], byte[])` 直接写 `revokingDB` | generic hook 捕不到 |
| `ContractStateStore` | `ContractStateStore.java:27-32` | 直接写 `revokingDB.put` | generic hook 捕不到 |

### 2.4 CONTRACT_STORAGE facts

`actuator/src/main/java/org/tron/core/vm/program/Storage.java`：

| 源码 | 事实 | S3 规则 |
| --- | --- | --- |
| `Storage.java:46-53` | `compose(key, addrHash)` 生成 physical row key | physical key 不可逆回 `(address, slot)` |
| `Storage.java:47-49` | `contractVersion == 1` 时 slot 先 `Hash.sha3(key)` | semantic key 必须记录 `storageKeyVersion` |
| `Storage.java:61-70` | create2 场景 addrHash 可来自 `address || trxHash` | 不能从 physical key 推合约地址 |
| `Storage.java:96-105` | dirty row zero 时 delete，否则 put | S5 负责 zero/tombstone 语义 |

S3 必须把 `storage-row` 绑定为 `SEMANTIC_ONLY`。真正的 `CONTRACT_STORAGE` write 只能由 S5 semantic hook 提供：

```text
contractAddress21 || slot32 || storageKeyVersion_u8
```

### 2.5 DynamicProperties facts

`DynamicPropertiesStore` 的 `properties` DB 同时包含 execution 参数、latest cursor、index、迁移标记和统计值。

| 源码 | key | S3 结论 |
| --- | --- | --- |
| `DynamicPropertiesStore.java:32-39` | latest header timestamp/number/hash/solidified number | temporal 可记录，root 默认不纳入 |
| `DynamicPropertiesStore.java:73-86` | `ENERGY_FEE`、`TRANSACTION_FEE` | P0 allowlist 候选 |
| `DynamicPropertiesStore.java:122-143` | contract/TVM fork toggles | historical VM config 候选 |
| `DynamicPropertiesStore.java:185-186` | energy price history | PR8 候选 |
| `DynamicPropertiesStore.java:233-243` | Cancun/blob/Osaka/Prague toggles | historical VM config 候选 |
| `DynamicPropertiesStore.java:252-256` | hardened resource/exchange calculation | execution/resource candidate |

S3 不能把所有 `properties` key 直接纳入 root。P0 策略：

```text
temporal history: 可以记录 properties writes
root input: 只允许 allowlist key
unknown dynamic key: 默认不进 root，记录 diagnostic
```

## 3. 新增文件

Package：

```text
chainbase/src/main/java/org/tron/core/archive/domain/
```

S3a：domain model 和 inventory。

| 文件 | 职责 |
| --- | --- |
| `ArchiveDomain.java` | 稳定 unsigned `u16` domain id |
| `ArchiveDomainDescriptor.java` | domain schema descriptor |
| `ArchiveDomainRegistry.java` | registry interface |
| `DefaultArchiveDomainRegistry.java` | 静态 P0/P1/excluded inventory |
| `StoreBinding.java` | dbName/store 分类 |
| `StoreCategory.java` | `STATE/DEBUG/P1/INDEX/CACHE/LOCAL/UNCLASSIFIED` |
| `RootPolicy.java` | root inclusion policy |
| `HistoryPolicy.java` | history retention policy |
| `ReaderPolicy.java` | state/debug/internal readable policy |
| `RawHookMode.java` | raw hook dispatch policy |
| `DynamicKeyPolicy.java` | dynamic properties allowlist |

S3b：codec 和 checksum。

| 文件 | 职责 |
| --- | --- |
| `CanonicalKeyCodec.java` | canonical key interface |
| `CanonicalValueCodec.java` | canonical value interface |
| `ArchiveDomainCodecs.java` | P0 codec factory/impl |
| `SemanticDomainKey.java` | semantic key carrier, primarily storage |
| `SemanticDomainValue.java` | semantic value carrier |
| `RegistryChecksum.java` | deterministic schema hash |
| `ArchiveDomainException.java` | registry/codec errors |

Test package：

```text
chainbase/src/test/java/org/tron/core/archive/domain/
```

`chainbase/build.gradle` 已有 `test {}` 配置；当前仓库还没有 `chainbase/src/test` 目录，实现时可以创建。如果 Gradle wiring 有意外，再把 focused tests 放到 `framework/src/test/java/org/tron/core/archive/domain/`。

## 4. Domain ids

使用 `int` 暴露 domain id，编码时校验 `0 <= id <= 0xffff`。不要在公开 API 使用 signed `short`。

```java
public enum ArchiveDomain {
  ACCOUNT(0x0001, "account"),
  CONTRACT(0x0002, "contract"),
  CODE(0x0003, "code"),
  CONTRACT_STORAGE(0x0004, "contract-storage"),
  DYNAMIC_PROPERTIES(0x0005, "dynamic-properties"),
  CONTRACT_STATE(0x0006, "contract-state"),

  ABI(0x0101, "abi"),
  ACCOUNT_TRACE(0x0102, "account-trace"),
  BALANCE_TRACE(0x0103, "balance-trace");
}
```

规则：

- `0x0000` 保留给 global root tree。
- `0x0001-0x00ff` 是 execution/root candidate。
- `0x0100-0x01ff` 是 history/debug/helper domain。
- global root aggregation 按 numeric domain id 排序，不能按 enum ordinal。

## 5. Policies

### 5.1 RootPolicy

```java
public enum RootPolicy {
  IN_GLOBAL_ROOT,
  DOMAIN_ROOT_ONLY,
  HISTORY_ONLY,
  EXCLUDED
}
```

S3 推荐默认：

| Domain | RootPolicy | 说明 |
| --- | --- | --- |
| `ACCOUNT` | `IN_GLOBAL_ROOT` | P0 TVM state root |
| `CONTRACT` | `IN_GLOBAL_ROOT` | P0 TVM state root |
| `CODE` | `IN_GLOBAL_ROOT` | P0 TVM state root |
| `CONTRACT_STORAGE` | `IN_GLOBAL_ROOT` | P0 TVM state root，来自 semantic hook |
| `DYNAMIC_PROPERTIES` | `IN_GLOBAL_ROOT` with allowlist | 只纳入 allowlisted keys |
| `CONTRACT_STATE` | `HISTORY_ONLY` | PR8 前确认是否升级 |
| `ABI` | `HISTORY_ONLY` | debug/contract info |
| index/cache/local stores | `EXCLUDED` | 非 execution state |

S3 只定义 policy，不发布 root。真正写 root record 在 S11。

### 5.2 HistoryPolicy

```java
public enum HistoryPolicy {
  FULL_HISTORY,
  LATEST_ONLY,
  CHECKPOINT_ONLY,
  NO_ARCHIVE
}
```

P0 historical getters 至少要求：

| Domain | HistoryPolicy |
| --- | --- |
| `ACCOUNT` | `FULL_HISTORY` |
| `CODE` | `FULL_HISTORY` |
| `CONTRACT_STORAGE` | `FULL_HISTORY` |
| `CONTRACT` | `FULL_HISTORY`，为 PR8/historical call 铺路 |
| `DYNAMIC_PROPERTIES` | `FULL_HISTORY` with allowlist for root |

### 5.3 RawHookMode

```java
public enum RawHookMode {
  GENERIC_TRON_STORE,
  STORE_SPECIFIC,
  SEMANTIC_ONLY,
  IGNORED,
  UNCLASSIFIED
}
```

| Mode | 含义 | 示例 |
| --- | --- | --- |
| `GENERIC_TRON_STORE` | S4 可从 `TronStoreWithRevoking.put/delete` 收集 | `account`、`code`、`properties` |
| `STORE_SPECIFIC` | 绕过 generic 或改写 value 后写入 | `contract`、`abi`、`contract-state` |
| `SEMANTIC_ONLY` | raw key/value 不是 canonical domain write | `storage-row` |
| `IGNORED` | index/cache/block/tx/local metadata | `block`、`trans`、`section-bloom` |
| `UNCLASSIFIED` | unknown dbName | 默认 warning/fail-fast，不能静默忽略 |

## 6. StoreBinding

建议字段：

```java
public final class StoreBinding {
  private final String dbName;
  private final String storeClassName;
  private final StoreCategory category;
  private final ArchiveDomain domain;
  private final RawHookMode rawHookMode;
  private final boolean warnWhenWritten;
  private final String reason;
}
```

P0 bindings：

| dbName | Store | Domain | RawHookMode |
| --- | --- | --- | --- |
| `account` | `AccountStore` | `ACCOUNT` | `GENERIC_TRON_STORE` |
| `contract` | `ContractStore` | `CONTRACT` | `STORE_SPECIFIC` |
| `code` | `CodeStore` | `CODE` | `GENERIC_TRON_STORE` |
| `storage-row` | `StorageRowStore` | `CONTRACT_STORAGE` | `SEMANTIC_ONLY` |
| `properties` | `DynamicPropertiesStore` | `DYNAMIC_PROPERTIES` | `GENERIC_TRON_STORE` |

P1/debug bindings：

| dbName | Store | Domain | RawHookMode |
| --- | --- | --- | --- |
| `contract-state` | `ContractStateStore` | `CONTRACT_STATE` | `STORE_SPECIFIC` |
| `abi` | `AbiStore` | `ABI` | `STORE_SPECIFIC` |

Excluded examples：

```text
block
block-index
trans
transactionRetStore
transactionHistoryStore
recent-block
recent-transaction
account-index
accountid-index
tree-block-index
section-bloom
balance-trace
account-trace
pbft-sign-data
zkProof
tmp
common
```

这些名字必须有 explicit binding 或 explicit excluded inventory，测试不能只靠 unknown fallback。

## 7. Codecs

### 7.1 Interfaces

```java
public interface CanonicalKeyCodec {
  byte[] encodeRawStoreKey(byte[] rawKey);
  byte[] encodeSemanticKey(SemanticDomainKey key);
  String codecId();
}
```

```java
public interface CanonicalValueCodec {
  byte[] encodeRawStoreValue(byte[] rawValue);
  byte[] encodeSemanticValue(SemanticDomainValue value);
  byte[] tombstone();
  String codecId();
}
```

S3 codecs 保持 event-light。不要引入 S4 的 `StoreWriteEvent`。

### 7.2 P0 codec table

| Domain | Key codec | Value codec |
| --- | --- | --- |
| `ACCOUNT` | raw 21-byte address | protobuf `Account` bytes |
| `CONTRACT` | raw 21-byte address | stored `SmartContract` bytes after ABI clear |
| `CODE` | raw 21-byte contract address | bytecode bytes |
| `CONTRACT_STORAGE` | `address21 || slot32 || storageKeyVersion_u8` | 32-byte slot value or tombstone |
| `DYNAMIC_PROPERTIES` | raw non-empty property key bytes | raw `BytesCapsule` bytes |
| `ABI` | raw 21-byte contract address | ABI protobuf bytes |

Cheap validation:

| Domain | Validation |
| --- | --- |
| account/contract/code/abi key | length 21 |
| dynamic key | non-empty |
| storage semantic key | address length 21、slot length 32、version in allowed set |
| storage raw physical key | rejected for `CONTRACT_STORAGE` |

不要在 S3 parse full protobuf。reader/root 层后续按需 parse。

## 8. CONTRACT_STORAGE semantic key

Canonical key：

```text
address21 || slot32 || storageKeyVersion_u8
```

`storageKeyVersion_u8`：

| Value | Meaning |
| --- | --- |
| `0x00` | raw TVM slot |
| `0x01` | contractVersion 1 slot hash semantics |

S5 必须从 VM/Repository 层传入原始 logical slot 和 contract version。Registry 不允许尝试反解 `Storage.compose()` 的 physical key。

## 9. Dynamic properties allowlist

S3 用 ASCII key name 定义 allowlist，不通过反射访问 `DynamicPropertiesStore` private constants。

首版 allowlist 建议：

```java
DynamicKeyPolicy.allowlist(
    "ENERGY_FEE",
    "MAX_CPU_TIME_OF_ONE_TX",
    "CREATE_ACCOUNT_FEE",
    "CREATE_NEW_ACCOUNT_FEE_IN_SYSTEM_CONTRACT",
    "TRANSACTION_FEE",
    "ALLOW_CREATION_OF_CONTRACTS",
    "ALLOW_TVM_TRANSFER_TRC10",
    "ALLOW_TVM_ISTANBUL",
    "ALLOW_TVM_CONSTANTINOPLE",
    "ALLOW_TVM_SOLIDITY_059",
    "ALLOW_TVM_LONDON",
    "ALLOW_TVM_COMPATIBLE_EVM",
    "ALLOW_TVM_SHANGHAI",
    "ALLOW_TVM_CANCUN",
    "ALLOW_TVM_BLOB",
    "ALLOW_TVM_OSAKA",
    "ALLOW_TVM_PRAGUE",
    "ALLOW_STRICT_MATH",
    "ALLOW_DYNAMIC_ENERGY",
    "DYNAMIC_ENERGY_THRESHOLD",
    "DYNAMIC_ENERGY_INCREASE_FACTOR",
    "DYNAMIC_ENERGY_MAX_FACTOR"
)
```

明确不默认纳入：

```text
latest_block_header_number
latest_block_header_hash
latest_block_header_timestamp
LATEST_SOLIDIFIED_BLOCK_NUM
BLOCK_FILLED_SLOTS_INDEX
BLOCK_HASH_HISTORY_INSTALLED
ABI_MOVE_DONE
TURKISH_KEY_MIGRATION_DONE
```

规则：

- temporal history 可以记录 all properties writes。
- commitment root 只消费 allowlist key。
- allowlist 变更必须改变 `RegistryChecksum`。
- PR8 historical `eth_call` 可扩展 allowlist，但必须通过 checksum 暴露 schema 变化。

## 10. RegistryChecksum

Checksum 输入必须是确定性 schema 字符串，不使用 Java object identity 或 map iteration order。

包含：

- registry schema version；
- domain id/name；
- root/history/reader policy；
- raw hook mode；
- source db names；
- key/value codec id；
- dynamic allowlist sorted lexicographically；
- storage key version definitions。

排除：

- runtime block number；
- local config path；
- Java identity hash；
- map insertion order。

Hash 使用固定 SHA-256，不依赖 TRON consensus crypto engine。

## 11. 实现顺序

1. 添加 enum/value class：`ArchiveDomain`、policy、`StoreBinding`。
2. 添加 `ArchiveDomainDescriptor` 和 `ArchiveDomainRegistry` interface。
3. 添加 `DefaultArchiveDomainRegistry` static inventory。
4. 添加 codec interface 和 P0 codec implementations。
5. 添加 dynamic properties allowlist。
6. 添加 `RegistryChecksum`。
7. 添加 tests。

不要在 S3 添加 Store hook。S4 才接 `TronStoreWithRevoking.put/delete`。

## 12. 测试

### 12.1 DefaultArchiveDomainRegistryTest

```text
chainbase/src/test/java/org/tron/core/archive/domain/DefaultArchiveDomainRegistryTest.java
```

断言：

- all domain ids unique；
- all ids fit unsigned `u16`；
- sort by numeric id, not enum ordinal；
- `account -> ACCOUNT/GENERIC_TRON_STORE`；
- `contract -> CONTRACT/STORE_SPECIFIC`；
- `code -> CODE/GENERIC_TRON_STORE`；
- `storage-row -> CONTRACT_STORAGE/SEMANTIC_ONLY`；
- `properties -> DYNAMIC_PROPERTIES/GENERIC_TRON_STORE`；
- `abi -> ABI/STORE_SPECIFIC`；
- `contract-state -> CONTRACT_STATE/STORE_SPECIFIC`；
- known excluded stores return `IGNORED`；
- unknown dbName returns `UNCLASSIFIED` and `warnWhenWritten=true`；
- P0 root domains are exactly `ACCOUNT/CONTRACT/CODE/CONTRACT_STORAGE/DYNAMIC_PROPERTIES`。

### 12.2 ArchiveDomainCodecsTest

```text
chainbase/src/test/java/org/tron/core/archive/domain/ArchiveDomainCodecsTest.java
```

断言：

- 21-byte account/contract/code keys accepted；
- wrong address length rejected；
- dynamic property empty key rejected；
- storage semantic key encodes `address21 || slot32 || keyVersion`；
- storage key version `0x00/0x01` accepted；
- raw `storage-row` physical key cannot be encoded as `CONTRACT_STORAGE`；
- tombstone bytes are stable and domain-separated。

### 12.3 RegistryChecksumTest

```text
chainbase/src/test/java/org/tron/core/archive/domain/RegistryChecksumTest.java
```

断言：

- repeated construction yields same checksum；
- checksum independent of insertion order；
- checksum changes when dynamic allowlist changes；
- checksum changes when root policy changes；
- checksum includes domain id encoding correctly。

## 13. 验证命令

Focused：

```bash
cd /Users/boson/IdeaProjects/java-tron
./gradlew :chainbase:test --tests 'org.tron.core.archive.domain.*'
```

Fallback，如果 `chainbase/src/test` wiring 暴露问题：

```bash
./gradlew :framework:test --tests 'org.tron.core.archive.domain.*'
```

最终 Java gate：

```bash
./gradlew checkstyleMain checkstyleTest
./gradlew lint
```

失败测试必须修复，不加 skip。

## 14. S3 停止条件

- `ArchiveDomain` domain ids 稳定、唯一、可 `u16` 编码。
- P0 domains 有 descriptor：`ACCOUNT`、`CONTRACT`、`CODE`、`CONTRACT_STORAGE`、`DYNAMIC_PROPERTIES`。
- `storage-row` 是 `SEMANTIC_ONLY`，不可能走 raw `CONTRACT_STORAGE` codec。
- `contract`、`abi`、`contract-state` 是 `STORE_SPECIFIC`。
- dynamic properties allowlist 存在，未分类 key 不进 root。
- known ChainBaseManager stores 被映射或显式 excluded/P1/P2。
- unknown dbName 不能静默 ignored。
- `RegistryChecksum` 覆盖 domain/policy/codec/allowlist。
- 没有 Store hook、temporal DB、Manager、RPC 改动。

## 15. Handoff to S4/S5

S4 raw collector 只消费：

```text
registry.bindingForDbName(dbName).rawHookMode()
registry.descriptor(domain).keyCodec()
registry.descriptor(domain).valueCodec()
```

S5 storage semantic hook 只消费：

```text
registry.descriptor(CONTRACT_STORAGE)
encodeSemanticKey(address, slot, storageKeyVersion)
encodeSemanticValue(value32)
```

S5 禁止把 `storage-row` physical key 当作最终 archive key。
