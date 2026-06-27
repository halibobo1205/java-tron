# java-tron Archive S3：ArchiveDomainRegistry 编码执行包

日期：2026-06-02

> 2026-06-03 更新：本文是旧 `a79693e450` 执行包。当前 `4e80f8ffa9a2` 的 S3 编码入口请以 [java-tron Archive S3：ArchiveDomainRegistry 4e80 编码执行包](./20260603-java-tron-archive-s3-domain-registry-4e80-coding-packet.md) 为准。

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 旧文档原始基线：`a79693e450`，当前 4e80 实现请看页头链接。

端到端矩阵：[java-tron Archive 端到端实现矩阵与 PR 执行队列](./20260602-java-tron-archive-end-to-end-implementation-matrix.md)

S1/S2 编码执行包：[java-tron Archive S1/S2 编码执行包](./20260602-java-tron-archive-s1-s2-coding-packet.md)

模块 02 逐文件清单：[java-tron Archive 模块 02：ArchiveDomainRegistry 逐文件 Patch 清单](./20260602-java-tron-archive-module-02-domain-registry-patch-checklist.md)

PR3/PR4 WriteCollector 规格：[java-tron Archive PR3/PR4 WriteCollector 代码级实现规格](./20260602-java-tron-archive-pr3-pr4-write-collector-implementation-spec.md)

## 1. 本文定位

S3 是真正接 Store hook 前的 schema gate。它不采集 write-set，也不写 temporal history，只把 java-tron 的 Store 空间转换成稳定、可测试、可 checksum 的 archive domain registry。

```text
java-tron dbName / Store class
  -> StoreBinding
  -> ArchiveDomainDescriptor
  -> canonical key/value codec id
  -> root/history/reader policy
```

S3 完成后，S4/S5 的 collector 不允许再硬编码 `dbName -> domain`。所有分类都必须来自 `ArchiveDomainRegistry`。

## 2. 当前源码复核结果

### 2.1 ChainBaseManager inventory

`ChainBaseManager` 是 java-tron Store 聚合入口。S3 registry 的 inventory 至少要覆盖这里的主要 Store：

| 位置 | Store | S3 分类 |
| --- | --- | --- |
| `ChainBaseManager.java:81` | `AccountStore` | P0 `ACCOUNT` |
| `ChainBaseManager.java:84` | `AccountAssetStore` | P1 TRC10/account asset |
| `ChainBaseManager.java:87` | `BlockStore` | block data，excluded |
| `ChainBaseManager.java:90` | `WitnessStore` | P1/P2 governance state |
| `ChainBaseManager.java:93` | `AssetIssueStore` | P1 TRC10 |
| `ChainBaseManager.java:96` | `AssetIssueV2Store` | P1 TRC10 |
| `ChainBaseManager.java:99` | `DynamicPropertiesStore` | P0 `DYNAMIC_PROPERTIES` with allowlist |
| `ChainBaseManager.java:102` | `BlockIndexStore` | index，excluded |
| `ChainBaseManager.java:105` | `AccountIdIndexStore` | index，excluded |
| `ChainBaseManager.java:108` | `AccountIndexStore` | index，excluded |
| `ChainBaseManager.java:111` | `WitnessScheduleStore` | P1/P2 governance/schedule |
| `ChainBaseManager.java:114` | `VotesStore` | P1 votes |
| `ChainBaseManager.java:117` | `ProposalStore` | P1 governance |
| `ChainBaseManager.java:120` | `ExchangeStore` | P1 exchange |
| `ChainBaseManager.java:123` | `ExchangeV2Store` | P1 exchange |
| `ChainBaseManager.java:126` | `MarketAccountStore` | P1 market |
| `ChainBaseManager.java:129` | `MarketOrderStore` | P1 market |
| `ChainBaseManager.java:132` | `MarketPairPriceToOrderStore` | P1 market index/state，需要后续确认 |
| `ChainBaseManager.java:135` | `MarketPairToPriceStore` | P1 market index/state，需要后续确认 |
| `ChainBaseManager.java:138` | `AbiStore` | history/debug domain，不进 P0 root |
| `ChainBaseManager.java:141` | `CodeStore` | P0 `CODE` |
| `ChainBaseManager.java:144` | `ContractStore` | P0 `CONTRACT` |
| `ChainBaseManager.java:147` | `ContractStateStore` | P1/P0+，PR8 前重新确认 |
| `ChainBaseManager.java:150` | `DelegatedResourceStore` | P1 delegated resource |
| `ChainBaseManager.java:153` | `DelegatedResourceAccountIndexStore` | P1/index，后续确认 |
| `ChainBaseManager.java:156` | `StorageRowStore` | physical source only，semantic `CONTRACT_STORAGE` |
| `ChainBaseManager.java:159` | `NullifierStore` | shielded state，P2 |
| `ChainBaseManager.java:162` | `ZKProofStore` | local/proof cache，excluded |
| `ChainBaseManager.java:166` | `IncrementalMerkleTreeStore` | shielded state，P2 |
| `ChainBaseManager.java:178` | `DelegationStore` | P1 delegated resource |
| `ChainBaseManager.java:186` | `CommonStore` | local/common metadata，excluded by default |
| `ChainBaseManager.java:190` | `TransactionStore` | tx data，excluded |
| `ChainBaseManager.java:193` | `TransactionRetStore` | receipt/ret data，excluded from state |
| `ChainBaseManager.java:196` | `RecentBlockStore` | cache，excluded |
| `ChainBaseManager.java:199` | `RecentTransactionStore` | cache，excluded |
| `ChainBaseManager.java:202` | `TransactionHistoryStore` | tx history data，excluded from state |
| `ChainBaseManager.java:214` | `PbftSignDataStore` | consensus/local data，excluded |
| `ChainBaseManager.java:218` | `BalanceTraceStore` | existing balance history aid，excluded from P0 state |
| `ChainBaseManager.java:222` | `AccountTraceStore` | existing balance history aid，excluded from P0 state |
| `ChainBaseManager.java:230` | `TreeBlockIndexStore` | index，excluded |
| `ChainBaseManager.java:234` | `SectionBloomStore` | log bloom index，excluded |

S3 必须 make unknown visible：任何 `ChainBaseManager` Store 不在 inventory 中时，registry test 应失败。

### 2.2 P0 Store facts

| Store | DB name | Source fact | S3 conclusion |
| --- | --- | --- | --- |
| `AccountStore` | `account` | `AccountStore.java:44-45` constructor; `put` calls `super.put` at line 87 | generic Store hook can collect raw account writes |
| `ContractStore` | `contract` | constructor line 21; `put` clears ABI and directly calls `revokingDB.put` at line 39 | generic hook will miss it; S4 needs store-specific hook |
| `CodeStore` | `code` | constructor line 16; inherits generic put | generic Store hook can collect code writes |
| `DynamicPropertiesStore` | `properties` | constructor line `241-243`; many private key constants | generic Store hook can collect raw writes, but root input must use allowlist |
| `StorageRowStore` | `storage-row` | constructor line 15; `Storage.compose` builds physical irreversible-ish key | raw storage-row key must not become archive key |
| `AbiStore` | `abi` | constructor line 18; `put(byte[], byte[])` directly calls `revokingDB.put` at line 32 | generic hook will miss this overload; history/debug only |
| `ContractStateStore` | `contract-state` | constructor line 17; `put` directly calls `revokingDB.put` at line 32 | generic hook will miss it; P1/P0+ store-specific hook |

### 2.3 Storage semantic facts

`actuator/src/main/java/org/tron/core/vm/program/Storage.java`:

| 位置 | Fact | S3 rule |
| --- | --- | --- |
| line 46 | `compose(byte[] key, byte[] addrHash)` creates physical row key | physical key is not archive canonical key |
| line 47 | contractVersion 1 hashes slot with `sha3(key)` | registry must track storage key version |
| line 51-52 | physical key combines first 16 bytes addrHash and last 16 bytes key | cannot recover original `(address, slot)` reliably |
| line 61-65 | create2 can derive addrHash from `address || trxHash` | physical key depends on deployment context |
| line 96-105 | `commit()` writes dirty rows and deletes zero values | S5 semantic hook belongs here or at Repository storage commit boundary |

S3 registry should bind `storage-row` as `SEMANTIC_ONLY`, not as raw root state.

### 2.4 Repository commit facts

`RepositoryImpl` confirms TVM writes eventually hit Store classes:

| 位置 | Fact | Registry impact |
| --- | --- | --- |
| `RepositoryImpl.java:948` / `954` | account cache commits to `AccountStore.put` | `ACCOUNT` generic hook |
| `RepositoryImpl.java:960` / `966` | code cache commits to `CodeStore.put` | `CODE` generic hook |
| `RepositoryImpl.java:972` / `980-982` | contract cache commits to `AbiStore.put` and `ContractStore.put` | `ABI` and `CONTRACT` need special handling |
| `RepositoryImpl.java:988` / `995` | contractState cache commits to `ContractStateStore.put` | P1/P0+ special handling |
| `RepositoryImpl.java:1001` / `1008` | storage cache commits via `Storage.commit()` | S5 semantic hook |
| `RepositoryImpl.java:1014` / `1020` | dynamic cache commits to `DynamicPropertiesStore.put` | `DYNAMIC_PROPERTIES` generic hook with allowlist |

## 3. S3 scope

S3 does:

1. Define stable domain ids.
2. Define domain descriptors and Store bindings.
3. Define canonical key/value codec interfaces and P0 codec ids.
4. Define dynamic properties allowlist structure.
5. Define registry checksum.
6. Add tests proving inventory coverage and deterministic ordering.

S3 does not:

- Modify `TronStoreWithRevoking.put/delete`.
- Add Store-specific hooks.
- Add `Storage.commit()` semantic hook.
- Create `BlockWriteSet`.
- Persist archive data.
- Compute roots.

## 4. Recommended split

S3 may exceed java-tron’s preferred small PR size if landed at once. Recommended split:

```text
S3a: domain model, policies, StoreBinding, static inventory
S3b: codecs, dynamic allowlist, registry checksum, tests
```

If kept as one PR, keep non-test files under review by grouping only registry code and no hook integration.

## 5. Files

新增 package：

```text
chainbase/src/main/java/org/tron/core/archive/domain/
```

### 5.1 S3a files

| File | Purpose |
| --- | --- |
| `ArchiveDomain.java` | stable `u16` domain ids |
| `ArchiveDomainDescriptor.java` | domain schema descriptor |
| `ArchiveDomainRegistry.java` | registry interface |
| `StoreBinding.java` | dbName/store classification |
| `StoreCategory.java` | root/history/index/cache/local/unclassified category |
| `RootPolicy.java` | `IN_GLOBAL_ROOT/DOMAIN_ROOT_ONLY/HISTORY_ONLY/EXCLUDED` |
| `HistoryPolicy.java` | `FULL_HISTORY/LATEST_ONLY/CHECKPOINT_ONLY/NO_ARCHIVE` |
| `ReaderPolicy.java` | `STATE_READER/DEBUG_ONLY/INTERNAL_ONLY/NOT_READABLE` |
| `RawHookMode.java` | `GENERIC_TRON_STORE/STORE_SPECIFIC/SEMANTIC_ONLY/IGNORED` |
| `DynamicKeyPolicy.java` | `ALL_KEYS/ALLOWLIST/DENYLIST` |

### 5.2 S3b files

| File | Purpose |
| --- | --- |
| `CanonicalKeyCodec.java` | canonical key interface |
| `CanonicalValueCodec.java` | canonical value interface |
| `ArchiveDomainCodecs.java` | P0 codec implementations/factory |
| `DefaultArchiveDomainRegistry.java` | static P0 registry |
| `RegistryChecksum.java` | deterministic schema hash |
| `StoreInventoryEntry.java` | Store inventory coverage model |
| `ArchiveDomainException.java` | registry/codec errors |

### 5.3 Test files

`chainbase/build.gradle` already has a `test {}` block, so S3 can create `chainbase/src/test/java`.

| File | Purpose |
| --- | --- |
| `chainbase/src/test/java/org/tron/core/archive/domain/DefaultArchiveDomainRegistryTest.java` | P0 bindings and inventory |
| `chainbase/src/test/java/org/tron/core/archive/domain/ArchiveDomainCodecsTest.java` | key/value codec behavior |
| `chainbase/src/test/java/org/tron/core/archive/domain/RegistryChecksumTest.java` | deterministic checksum |

If build wiring surprises appear, move tests to `framework/src/test/java/org/tron/core/archive/domain/` as a fallback because framework test classpath already sees chainbase main classes.

## 6. Domain ids

Use `int` in Java fields and validate `0 <= id <= 0xffff`. Do not store ids as signed `short` in public APIs.

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

Rules:

1. `0x0000` reserved for global root tree.
2. `0x0001-0x00ff` root/domain-root state candidates.
3. `0x0100-0x01ff` history/debug/helper domains.
4. `domainId` encoding is unsigned `u16` big-endian.
5. Global root aggregation sorts by numeric `domainId`, not enum ordinal.

## 7. Policies

### 7.1 RootPolicy

```java
public enum RootPolicy {
  IN_GLOBAL_ROOT,
  DOMAIN_ROOT_ONLY,
  HISTORY_ONLY,
  EXCLUDED
}
```

S3/S4/S5/S6 default policy:

| Domain | RootPolicy | Reason |
| --- | --- | --- |
| `ACCOUNT` | `DOMAIN_ROOT_ONLY` | P0 first builds history/schema; PR7 upgrades for TVM global root |
| `CONTRACT` | `DOMAIN_ROOT_ONLY` | same |
| `CODE` | `DOMAIN_ROOT_ONLY` | same |
| `CONTRACT_STORAGE` | `DOMAIN_ROOT_ONLY` | same |
| `DYNAMIC_PROPERTIES` | `DOMAIN_ROOT_ONLY` with allowlist | full properties store includes latest/index/migration keys |
| `CONTRACT_STATE` | `HISTORY_ONLY` or disabled P1 | PR8 must confirm whether historical call touches it |
| `ABI` | `HISTORY_ONLY` | debug/contract info, not TVM state root |
| `ACCOUNT_TRACE/BALANCE_TRACE` | `EXCLUDED` from P0 archive state | existing balance lookup aid, not canonical TVM root |

PR7 can only claim `coverage=TVM_STATE_ONLY` global root after upgrading `ACCOUNT/CONTRACT/CODE/CONTRACT_STORAGE` to `IN_GLOBAL_ROOT`. If registry still says `DOMAIN_ROOT_ONLY`, only publish domain roots.

### 7.2 HistoryPolicy

```java
public enum HistoryPolicy {
  FULL_HISTORY,
  LATEST_ONLY,
  CHECKPOINT_ONLY,
  NO_ARCHIVE
}
```

P0 historical getters require `FULL_HISTORY` for `ACCOUNT/CODE/CONTRACT_STORAGE`. PR8 historical call also requires `CONTRACT` and selected `DYNAMIC_PROPERTIES`.

### 7.3 RawHookMode

Older module docs used `collectRawStoreWrites` and `requiresSemanticHook` booleans. S3 should use a single enum to avoid invalid combinations:

```java
public enum RawHookMode {
  GENERIC_TRON_STORE,
  STORE_SPECIFIC,
  SEMANTIC_ONLY,
  IGNORED
}
```

| Mode | Meaning | Examples |
| --- | --- | --- |
| `GENERIC_TRON_STORE` | `TronStoreWithRevoking.put/delete` can collect raw writes | `account`, `code`, `properties` |
| `STORE_SPECIFIC` | Store bypasses generic put or changes value before direct write | `contract`, `abi`, `contract-state` |
| `SEMANTIC_ONLY` | Raw key/value is not canonical domain write | `storage-row` |
| `IGNORED` | index/cache/block/tx/local metadata | `block`, `trans`, `recent-block`, `section-bloom` |

S4 collector should dispatch by this enum.

## 8. Store bindings

`StoreBinding` recommended fields:

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

### 8.1 P0 bindings

| dbName | Store | Domain | RawHookMode | Reason |
| --- | --- | --- | --- | --- |
| `account` | `AccountStore` | `ACCOUNT` | `GENERIC_TRON_STORE` | `AccountStore.put` calls `super.put` after balance trace |
| `contract` | `ContractStore` | `CONTRACT` | `STORE_SPECIFIC` | clears ABI then direct `revokingDB.put` |
| `code` | `CodeStore` | `CODE` | `GENERIC_TRON_STORE` | raw key/value are canonical |
| `storage-row` | `StorageRowStore` | `CONTRACT_STORAGE` | `SEMANTIC_ONLY` | physical key is not archive key |
| `properties` | `DynamicPropertiesStore` | `DYNAMIC_PROPERTIES` | `GENERIC_TRON_STORE` | full history allowed, root allowlist |

### 8.2 P1/P0+ bindings

| dbName | Store | Domain | RawHookMode | Reason |
| --- | --- | --- | --- | --- |
| `contract-state` | `ContractStateStore` | `CONTRACT_STATE` | `STORE_SPECIFIC` | direct `revokingDB.put`; PR8 must confirm need |
| `abi` | `AbiStore` | `ABI` | `STORE_SPECIFIC` | overload `put(byte[], byte[])`; history/debug only |
| `asset-issue` | `AssetIssueStore` | future TRC10 domain | `GENERIC_TRON_STORE` | out of P0 TVM scope |
| `asset-issue-v2` | `AssetIssueV2Store` | future TRC10 domain | `GENERIC_TRON_STORE` | out of P0 TVM scope |
| `votes` | `VotesStore` | future votes domain | `GENERIC_TRON_STORE` | governance/vote state |
| `proposal` | `ProposalStore` | future governance domain | `GENERIC_TRON_STORE` | governance state |
| `DelegatedResource` | `DelegatedResourceStore` | future delegated resource domain | `GENERIC_TRON_STORE` | resource state |
| `delegation` | `DelegationStore` | future delegation domain | `GENERIC_TRON_STORE` | resource state |

### 8.3 Excluded examples

| dbName | Reason |
| --- | --- |
| `block` | block data, not state domain |
| `block-index` | index |
| `trans` | transaction data |
| `transactionRetStore` | receipt/ret data, not account state |
| `transactionHistoryStore` | tx history, not state |
| `recent-block` | cache |
| `recent-transaction` | cache |
| `account-index` | index |
| `accountid-index` | index |
| `tree-block-index` | index |
| `section-bloom` | log bloom index |
| `balance-trace` | existing balance history auxiliary |
| `account-trace` | existing balance history auxiliary |
| `pbft-sign-data` | consensus/local |
| `zkProof` | proof/local |
| `tmp` | temporary |

## 9. Codecs

### 9.1 Interfaces

```java
public interface CanonicalKeyCodec {
  byte[] encodeRawStoreKey(String dbName, byte[] rawKey);

  byte[] encodeSemanticKey(SemanticDomainKey key);
}
```

```java
public interface CanonicalValueCodec {
  byte[] encodeRawStoreValue(String dbName, byte[] rawValue);

  byte[] encodeSemanticValue(SemanticDomainValue value);
}
```

S3 does not need full `StoreWriteEvent` yet. Keep codecs event-light so registry tests can run before S4 creates collector events.

### 9.2 P0 codec table

| Domain | Key codec | Value codec |
| --- | --- | --- |
| `ACCOUNT` | raw 21-byte account address | protobuf `Account` bytes |
| `CONTRACT` | raw 21-byte contract address | actual stored `SmartContract` bytes after ABI cleared |
| `CODE` | raw 21-byte contract address | raw bytecode bytes |
| `CONTRACT_STORAGE` | semantic `address21 || slot32 || keyVersion1` | 32-byte storage value; zero means delete at collector/temporal layer |
| `DYNAMIC_PROPERTIES` | raw property key bytes | raw `BytesCapsule` bytes |
| `ABI` | raw contract address | ABI protobuf bytes |

### 9.3 Validation

S3 codecs should validate shape where cheap:

| Domain | Key validation |
| --- | --- |
| `ACCOUNT` | key length 21 |
| `CONTRACT` | key length 21 |
| `CODE` | key length 21 |
| `CONTRACT_STORAGE` | address length 21, slot length 32, keyVersion known |
| `DYNAMIC_PROPERTIES` | non-empty key |
| `ABI` | key length 21 |

Do not parse full protobuf in S3 codecs. That is expensive and not needed for schema mapping. PR6 reader can parse domain values when needed.

## 10. CONTRACT_STORAGE semantic key

Canonical key:

```text
address21 || slot32 || storageKeyVersion_u8
```

`storageKeyVersion_u8`:

| Value | Meaning |
| --- | --- |
| `0x00` | raw TVM slot |
| `0x01` | contractVersion 1 slot hash semantics |

S5 semantic hook must pass the original logical `DataWord` slot and contract version. It must not ask registry to reverse `Storage.compose`.

## 11. Dynamic properties allowlist

`DynamicPropertiesStore` has many private byte-array keys. S3 should define allowlist using ASCII key names converted to bytes, not by reflecting private constants.

Initial S3 allowlist object:

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
    "ALLOW_TVM_PRAGUE"
)
```

Rules:

1. Full temporal history may record all `properties` writes.
2. Root input uses allowlist only.
3. PR8 `ArchiveDynamicPropertiesView` may expand allowlist.
4. Any allowlist change changes `RegistryChecksum`.
5. Do not include latest cursor/index/migration markers such as `latest_block_header_number`, `LATEST_SOLIDIFIED_BLOCK_NUM`, `BLOCK_FILLED_SLOTS_INDEX`, `ABI_MOVE_DONE`, or history install markers by default.

## 12. Registry checksum

`RegistryChecksum` should hash a deterministic schema string, not object identity.

Include:

- registry schema version;
- domain id/name;
- root/history/reader policies;
- raw hook mode;
- source db names;
- codec ids;
- dynamic allowlist keys sorted lexicographically;
- storage key version definitions.

Exclude:

- runtime block number;
- local config path;
- Java class identity hash;
- map iteration order.

Recommended format:

```text
archive-registry-v1
domain:0001:ACCOUNT:account:DOMAIN_ROOT_ONLY:FULL_HISTORY:STATE_READER:key=address21:value=protobuf-account
binding:account:AccountStore:ACCOUNT:GENERIC_TRON_STORE
...
dynamic-allowlist:DYNAMIC_PROPERTIES:ALLOW_CREATION_OF_CONTRACTS,ALLOW_TVM_...
```

Hash with fixed SHA-256, independent of consensus crypto engine.

## 13. Implementation order

1. Add enums and immutable value classes.
2. Add `ArchiveDomain` id validation and `u16` encode helper.
3. Add `StoreBinding` and `RawHookMode`.
4. Add static P0/P1/excluded inventory in `DefaultArchiveDomainRegistry`.
5. Add codec interfaces and simple P0 codec implementations.
6. Add dynamic properties allowlist.
7. Add checksum builder.
8. Add tests.

## 14. Tests

### 14.1 Registry tests

`DefaultArchiveDomainRegistryTest`:

- all `ArchiveDomain` ids are unique;
- all ids fit unsigned `u16`;
- ids sort numerically and do not rely on enum ordinal;
- `account -> ACCOUNT/GENERIC_TRON_STORE`;
- `contract -> CONTRACT/STORE_SPECIFIC`;
- `code -> CODE/GENERIC_TRON_STORE`;
- `storage-row -> CONTRACT_STORAGE/SEMANTIC_ONLY`;
- `properties -> DYNAMIC_PROPERTIES/GENERIC_TRON_STORE`;
- `abi -> ABI/STORE_SPECIFIC`;
- known excluded stores return `IGNORED`;
- unknown store returns unclassified according to policy, not silent ignored.

### 14.2 Codec tests

`ArchiveDomainCodecsTest`:

- 21-byte account/contract/code keys accepted;
- wrong address length rejected with `ArchiveDomainException`;
- dynamic property empty key rejected;
- storage semantic key encodes `address21 || slot32 || keyVersion`;
- storage key version `0x00` and `0x01` both roundtrip;
- raw `storage-row` key cannot be encoded as `CONTRACT_STORAGE`.

### 14.3 Checksum tests

`RegistryChecksumTest`:

- checksum stable across repeated registry construction;
- checksum independent of insertion order;
- checksum changes when dynamic allowlist changes;
- checksum changes when root policy changes;
- checksum includes `domainId=256/65535` encoding correctly.

## 15. Verification commands

Focused:

```bash
cd /Users/boson/IdeaProjects/java-tron
./gradlew :chainbase:test --tests 'org.tron.core.archive.domain.*'
```

Final Java gate after code changes:

```bash
./gradlew lint
./gradlew checkstyleMain checkstyleTest -x generateGitProperties
```

If creating `chainbase/src/test/java` exposes unexpected Gradle wiring issues, move focused tests to framework and run:

```bash
./gradlew :framework:test --tests 'org.tron.core.archive.domain.*'
```

## 16. S3 acceptance

- [ ] Registry has stable `u16` domain ids.
- [ ] P0 domains have descriptors: `ACCOUNT`, `CONTRACT`, `CODE`, `CONTRACT_STORAGE`, `DYNAMIC_PROPERTIES`.
- [ ] `storage-row` is `SEMANTIC_ONLY`, never raw `CONTRACT_STORAGE`.
- [ ] `contract`, `abi`, and `contract-state` are marked `STORE_SPECIFIC`.
- [ ] Dynamic properties root allowlist exists and participates in checksum.
- [ ] Known ChainBaseManager stores are classified or explicitly unclassified/P1.
- [ ] Unknown dbName cannot be silently treated as excluded.
- [ ] S4 collector can dispatch exclusively via `RawHookMode`.
- [ ] No Store hook or temporal DB writes are added in S3.

## 17. Handoff to S4/S5

S4 raw collector consumes:

```text
bindingForStore(dbName).rawHookMode
descriptor(domain).keyCodec/valueCodec
```

S4 must implement:

- `GENERIC_TRON_STORE` via `TronStoreWithRevoking.put/delete`;
- `STORE_SPECIFIC` hooks for `ContractStore`, `AbiStore`, and possibly `ContractStateStore`;
- diagnostics for unclassified writes.

S5 storage semantic hook consumes:

```text
descriptor(CONTRACT_STORAGE)
encodeSemanticKey(address, slot, storageKeyVersion)
encodeSemanticValue(value32)
```

S5 must not pass `storage-row` physical keys into registry as final archive keys.
