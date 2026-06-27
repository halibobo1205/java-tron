# java-tron Archive S8：ArchiveStateReader Core 编码执行包

日期：2026-06-02

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

> 2026-06-03 更新：本文是旧 `a79693e450` 编码包。当前 `4e80f8ffa9a2` 的 S8/S9 编码入口请看 [java-tron Archive S8/S9：ArchiveStateReader 与 JSON-RPC Historical Getters 4e80 编码执行包](./20260603-java-tron-archive-s8-s9-state-reader-rpc-4e80-coding-packet.md)，旧行号和部分 RPC 判断不可直接用于编码。

java-tron 旧文档原始基线：`a79693e450`。

关联文档：

- 当前 4e80 S8/S9 编码入口：[java-tron Archive S8/S9：ArchiveStateReader 与 JSON-RPC Historical Getters 4e80 编码执行包](./20260603-java-tron-archive-s8-s9-state-reader-rpc-4e80-coding-packet.md)
- S7 temporal store：[java-tron Archive S7：Temporal Commit / GetAsOf / Unwind / Startup 编码执行包](./20260602-java-tron-archive-s7-temporal-commit-unwind-startup-coding-packet.md)
- PR6 StateReader/JSON-RPC 规格：[java-tron Archive PR6 StateReader/JSON-RPC 代码级实现规格](./20260602-java-tron-archive-pr6-state-reader-jsonrpc-implementation-spec.md)
- 模块 05 patch checklist：[java-tron Archive 模块 05：ArchiveStateReader 逐文件 Patch 清单](./20260602-java-tron-archive-module-05-state-reader-patch-checklist.md)
- 模块 05 java-tron 源码对照：[模块 05 ArchiveStateReader：java-tron 源码对照](./20260601-java-tron-module-05-state-reader-java-tron-source-deep-dive.md)
- S3 DomainRegistry：[java-tron Archive S3：ArchiveDomainRegistry 编码执行包](./20260602-java-tron-archive-s3-domain-registry-coding-packet.md)
- S5 Storage semantic hook：[java-tron Archive S5：Contract Storage Semantic Hook 编码执行包](./20260602-java-tron-archive-s5-contract-storage-semantic-coding-packet.md)

## 1. 本文定位

S8 对应 PR6 前半段：

```text
StatePoint
  -> ArchiveStateReaderFactory.open(...)
  -> asOfTxNum
  -> ArchiveStateReader
  -> account/code/contract/storage typed reads
```

S8 只实现 archive-backed state reader core，不接 JSON-RPC：

| 范围 | S8 是否交付 | 说明 |
| --- | --- | --- |
| `ArchiveStateReader` | 是 | account/code/contract/storage typed read API |
| `ArchiveStateReaderFactory` | 是 | `StatePoint -> asOfTxNum -> reader` |
| `ArchiveReadResult<T>` | 是 | 保留 present/missing，不提前转 RPC 默认值；corrupt/codec error 走 exception |
| account decode | 是 | `ACCOUNT` raw bytes -> `AccountCapsule` |
| contract decode | 是 | `CONTRACT` raw bytes -> `ContractCapsule` |
| code read | 是 | `CODE` raw bytes，key 为 contract address |
| storage read | 是 | `CONTRACT_STORAGE` logical `address21 || slot32 || storageKeyVersion_u8` |
| JSON-RPC adapter | 否 | S9 做 |
| historical `eth_call` / Repository | 否 | PR8/S12 做 |
| root/proof | 否 | PR7/PR9 做 |

关键边界：

```text
Reader core 不读 latest Store。
Reader core 不调用 Wallet。
Reader core 不构造 Storage/StorageRowStore physical key。
Reader core 不把 missing 提前转成 balance=0/code=0x/storage=zero。
```

## 2. java-tron 源码事实

### 2.1 当前 JSON-RPC getter 是 latest-only

| java-tron 位置 | 源码事实 | 对 S8/S9 的结论 |
| --- | --- | --- |
| `TronJsonRpc.java:90-94` | `eth_getBalance` 只声明 `JsonRpcInvalidParamsException` | S9 要加 internal error；S8 不改 RPC |
| `TronJsonRpc.java:96-101` | `eth_getStorageAt` 只声明 invalid params | 同上 |
| `TronJsonRpc.java:103-108` | `eth_getCode` 方法名是 `getABIOfSmartContract`，只声明 invalid params | S9 改签名但不改 JSON-RPC method 名 |
| `TronJsonRpcImpl.java:155-167` | 当前只定义 `earliest/pending/latest/finalized`；无 `safe` 常量；tag unsupported error 是 private | S9 resolver 不能依赖不存在的 safe/parser |
| `TronJsonRpcImpl.java:394-419` | balance 方法内联判断；`latest` 走 `wallet.getAccount`，quantity 只校验后拒绝 | historical balance 不能走 Wallet |
| `TronJsonRpcImpl.java:536-568` | storage 方法内联判断；`latest` 走 `wallet.getContract`、`StorageRowStore`、`Storage` | historical storage 不能构造 `Storage` |
| `TronJsonRpcImpl.java:572-599` | code 方法内联判断；`latest` 走 `wallet.getContractInfo` | historical code 不能走 Wallet |

### 2.2 block selector parser 现状

| java-tron 位置 | 源码事实 | 对 S8/S9 的结论 |
| --- | --- | --- |
| `JsonRpcApiUtil.java:518-531` | `getByJsonBlockId`：`latest/empty -> -1`、`earliest -> 0`、`finalized -> solid`、`pending -> TAG pending not supported`、其他走严格 `jsonHexToLong` | 可参考 tag 语义，但不适合作为 state getter resolver 主逻辑 |
| `ByteArray.java:146-151` | `hexToBigInteger`：`0x` 前缀按 16 进制，裸字符串按 10 进制 | S9 quantity 分支应复用并补负数/long overflow 校验 |
| `ByteArray.java:154-159` | `jsonHexToLong` 要求 `0x` 前缀 | 如果 S9 直接用 `getByJsonBlockId`，裸 decimal 兼容性会变差 |

S8 不解析 JSON-RPC block 参数；它只接收已经构造好的 `StatePoint`。

### 2.3 latest Wallet path 不能复用于 historical reader

| java-tron 位置 | 源码事实 | 对 S8 的结论 |
| --- | --- | --- |
| `Wallet.java:337-350` | `getAccount` 从 latest `AccountStore` 读账户，还用 latest dynamic/account store 更新资源视图 | historical account 不能调用 `wallet.getAccount` |
| `Wallet.java:3205-3224` | `getContract` 从 latest account/contract/abi stores 读 | historical contract 不能调用 |
| `Wallet.java:3234-3268` | `getContractInfo` 从 latest account/contract/abi/code/contract-state stores 读 runtime code | historical code 不能调用 |
| `Wallet.java:3251-3255` | current code 以 address 读 `CodeStore`，缺失时 runtime code empty | S3 P0 CODE key 继续用 21-byte address |

latest RPC 仍继续走这些路径；S8 只服务 historical path 和后续 PR8。

### 2.4 capsule decode 会吞异常

| java-tron 位置 | 源码事实 | 对 S8 的结论 |
| --- | --- | --- |
| `AccountCapsule.java:64-69` | `new AccountCapsule(byte[])` parse 失败只 log，`account` 可能为 null | reader 必须检查 `getInstance()` |
| `ContractCapsule.java:47-52` | `new ContractCapsule(byte[])` parse 失败不抛，`smartContract` 可能为 null | reader 必须检查 `getInstance()` |
| `CodeCapsule.java:28-44` | `CodeCapsule` 只是 raw bytecode wrapper | reader 可直接返回 raw bytes，不必构造 `CodeCapsule` |
| `ByteArray.java:116-118` | empty bytes JSON hex 为 `0x` | S9 处理 code default；S8 保留 raw empty |
| `DataWord.java:83-91` | 小于 32 字节左填充，大于 32 字节抛 RuntimeException | S9 负责 slot/value normalization；S8 只接受 `slot32` |

### 2.5 storage physical key 是禁区

| java-tron 位置 | 源码事实 | 对 S8 的结论 |
| --- | --- | --- |
| `Storage.java:46-53` | physical key 用 `addrHash` 和 slot 后 16 bytes 组合，contract version 1 会先 hash slot | reader 不能复用 physical key |
| `Storage.java:61-70` | create2 会把 `address || trxHash` 参与 addrHash | historical reader 不能依赖 latest contract trxHash |
| `Storage.java:73-83` | latest `getValue` 读 `StorageRowStore` physical row | S8 不访问 `StorageRowStore` |
| `TronJsonRpcImpl.java:553-558` | latest storage path 构造 `Storage(address, store)` 并设置 contract version/trxHash | historical storage path 必须绕开 |

S5 已固定：

```text
CONTRACT_STORAGE canonical key = address21 || slot32 || storageKeyVersion_u8
```

S8 只消费这个 semantic key；`storageKeyVersion_u8` 由目标历史点的 contract storage key semantics 决定，不读取 physical `storage-row`。

## 3. S8 前置契约

S8 依赖：

| Slice | 前置输出 | S8 使用方式 |
| --- | --- | --- |
| S2 | `StatePoint`、`ArchiveTxNumIndex` | resolve `StatePoint -> asOfTxNum` |
| S3 | `ArchiveDomainRegistry`、domain key codecs | encode reader query keys |
| S5 | `CONTRACT_STORAGE` semantic writes | storage reads use `address21 || slot32 || storageKeyVersion_u8` |
| S7 | `ArchiveTemporalStore.getAsOf/progress` | read historical raw domain values |

如果 S2 尚未落 `StatePoint`，S8 应把 `StatePoint` 作为前置 mini patch 先落；不要把 `StatePoint` 放进 framework JSON-RPC package。

## 4. StatePoint 语义

S8 reader core 支持所有内部 state points；S9 JSON-RPC 只使用 `BLOCK_END` 和 latest bypass。

| StatePoint | asOfTxNum |
| --- | --- |
| `LATEST` | `progress.nextTxNum` |
| `BLOCK_BEFORE(blockNum)` | `blockRange.firstTxNum` |
| `BLOCK_END(blockNum)` | `blockRange.lastTxNum + 1` |
| `TX_BEFORE(blockNum, txIndex)` | txNum |
| `TX_AFTER(blockNum, txIndex)` | txNum + 1 |
| `SYSTEM_AFTER(blockNum, phase)` | system txNum + 1 |

规则：

- `BLOCK_END` 是唯一 block-after 命名，不引入 `BLOCK_AFTER`。
- `StatePoint` resolver 是唯一做 `+1` 的地方；reader 调用方不能手写 `+1`。
- `asOfTxNum` 必须 `<= progress.nextTxNum`。
- requested block 必须 `<= progress.appliedBlockNum`。

## 5. Patch 1：ArchiveReadResult

新增：

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveReadResult.java
```

推荐结构：

```java
public final class ArchiveReadResult<T> {
  public enum Status {
    PRESENT,
    MISSING
  }

  private final Status status;
  private final T value;

  public static <T> ArchiveReadResult<T> present(T value);
  public static <T> ArchiveReadResult<T> missing();
}
```

为什么需要 result wrapper：

- missing account 在 JSON-RPC 是 balance `0x0`，但 PR8/proof/debug 仍需要知道 object 不存在。
- missing code 和 present empty code 都会渲染为 `0x`，但语义不同。
- missing storage 和 present zero storage 都会渲染为 32-byte zero，语义不同。
- corrupt/codec error 不应伪装成 missing。

`ArchiveReadResult.present(null)` 必须拒绝。

## 6. Patch 2：ArchiveReaderException

新增：

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveReaderException.java
```

reason：

```java
public enum Reason {
  DISABLED,
  FUTURE_STATE,
  HISTORY_UNAVAILABLE,
  DOMAIN_NOT_ENABLED,
  CODEC_ERROR,
  CORRUPTED,
  UNSUPPORTED
}
```

语义：

| Reason | 触发 |
| --- | --- |
| `DISABLED` | archive disabled |
| `FUTURE_STATE` | requested block/txNum > progress |
| `HISTORY_UNAVAILABLE` | block range missing、archive gap、progress EMPTY but requested history |
| `DOMAIN_NOT_ENABLED` | registry domain 不支持 reader |
| `CODEC_ERROR` | protobuf/raw value decode failure |
| `CORRUPTED` | progress repair/corrupt、registry checksum mismatch |
| `UNSUPPORTED` | StatePoint kind 暂不支持 |

S8 不映射 JSON-RPC exception；S9 adapter 再映射。

## 7. Patch 3：ArchiveStateReader API

新增：

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStateReader.java
```

接口：

```java
public interface ArchiveStateReader extends AutoCloseable {
  StatePoint statePoint();

  long asOfTxNum();

  ArchiveReadResult<AccountCapsule> getAccount(byte[] address) throws ArchiveReaderException;

  ArchiveReadResult<ContractCapsule> getContract(byte[] address) throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getCode(byte[] address) throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getStorageValue(byte[] address, byte[] slot32)
      throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getRaw(ArchiveDomain domain, byte[] canonicalKey)
      throws ArchiveReaderException;

  @Override
  void close();
}
```

规则：

- `address.length == 21`。
- `slot32.length == 32`。
- returned `byte[]` values are copied.
- `close()` P0 可以 no-op，但保留接口给未来 snapshot/progress pinned resources。
- `getRaw` 只给 internal debug/proof/test，不给 S9 直接拼 domain key。

## 8. Patch 4：DefaultArchiveStateReader

新增：

```text
chainbase/src/main/java/org/tron/core/archive/reader/DefaultArchiveStateReader.java
```

依赖：

```java
private final ArchiveTemporalStore temporalStore;
private final ArchiveDomainRegistry registry;
private final StatePoint statePoint;
private final long asOfTxNum;
private final ArchiveProgress progressSnapshot;
private final byte[] registryChecksumSnapshot;
```

### 8.1 account

流程：

```java
ArchiveReadResult<byte[]> raw = getRaw(ACCOUNT, accountKey(address));
if raw.missing(): return missing
AccountCapsule account = new AccountCapsule(raw.value());
if account.getInstance() == null:
  throw codecError(...)
return present(account)
```

注意：

- `AccountCapsule(byte[])` parse failure 不抛异常，必须检查 `getInstance()`。
- 不调用 `account.importAllAsset()` 或 resource processors；这些是 latest Wallet view 逻辑。

### 8.2 contract

流程：

```java
raw = getRaw(CONTRACT, contractKey(address))
if missing -> missing
ContractCapsule contract = new ContractCapsule(raw.value())
if contract.getInstance() == null -> CODEC_ERROR
return present(contract)
```

S8 不把 ABI 合并进 `ContractCapsule`；ABI 历史域是 debug/PR8 范围。

### 8.3 code

流程：

```java
raw = getRaw(CODE, codeKey(address))
if missing -> missing
return present(copy(raw.value()))
```

P0 code key 是 21-byte contract address，不是 code hash。依据：

- `Wallet.getContractInfo` latest path 当前用 address 读 `CodeStore`。
- 模块 02 已收敛 `CODE` key 为 21-byte address，避免额外维护 `contract.codeHash -> code` 二级索引。

### 8.4 storage

流程：

```java
byte[] key = registry.encodeSemanticKey(CONTRACT_STORAGE, address21, slot32)
raw = temporalStore.getAsOf(CONTRACT_STORAGE, key, asOfTxNum)
if raw missing -> missing
return present(copy(raw))
```

约束：

- 不构造 `Storage`。
- 不访问 `StorageRowStore`。
- 不读取 latest `ContractStore` 的 version/trxHash。
- slot 必须已经是 32 bytes。S9 adapter 负责 `DataWord` normalize。

## 9. Patch 5：domain key encoding

S8 不手写 domain key 拼接，统一通过 S3 registry codec：

```java
byte[] accountKey = registry.encodeQueryKey(ArchiveDomain.ACCOUNT, AccountQuery.of(address));
byte[] contractKey = registry.encodeQueryKey(ArchiveDomain.CONTRACT, ContractQuery.of(address));
byte[] codeKey = registry.encodeQueryKey(ArchiveDomain.CODE, CodeQuery.of(address));
byte[] storageKey = registry.encodeQueryKey(
    ArchiveDomain.CONTRACT_STORAGE, StorageQuery.of(address, slot32));
```

如果 S3 最终没有 query object 类型，也要把 helper 方法放在 registry/domain codec 层：

```java
registry.encodeAccountKey(address)
registry.encodeCodeKey(address)
registry.encodeContractStorageKey(address, slot32)
```

不要在 `DefaultArchiveStateReader` 里写：

```java
ByteUtil.merge(address, slot32)
```

否则 S3 codec version/checksum 失去意义。

## 10. Patch 6：ArchiveStateReaderFactory

新增：

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStateReaderFactory.java
chainbase/src/main/java/org/tron/core/archive/reader/DefaultArchiveStateReaderFactory.java
```

接口：

```java
public interface ArchiveStateReaderFactory {
  ArchiveStateReader open(StatePoint statePoint) throws ArchiveReaderException;
}
```

默认实现依赖：

```java
private final ArchiveConfig archiveConfig;
private final ArchiveTxNumIndex txNumIndex;
private final ArchiveTemporalStore temporalStore;
private final ArchiveDomainRegistry registry;
```

open 流程：

```java
if (!archiveConfig.isEnable() || !archiveConfig.getTemporal().isEnable()) {
  throw disabled;
}
ArchiveProgress progress = temporalStore.progress();
validateProgressReadable(progress);
long asOfTxNum = resolveAsOfTxNum(statePoint, progress);
validateAsOfTxNum(asOfTxNum, progress);
validateRegistryChecksum(progress, registry);
return new DefaultArchiveStateReader(...);
```

progress gate：

| Progress | open 行为 |
| --- | --- |
| `EMPTY` | only `LATEST` at txNum 0 can open; historical block returns `HISTORY_UNAVAILABLE` |
| `OK` | normal |
| `ARCHIVE_GAP` | requested point inside covered range may open; gap range returns `HISTORY_UNAVAILABLE` |
| `REPAIR_REQUIRED` | `CORRUPTED` |

如果不想在 `ARCHIVE_GAP` 下支持 covered range partial query，P0 可更保守：所有 historical queries return `HISTORY_UNAVAILABLE`。但不要 fallback latest。

## 11. Patch 7：resolveAsOfTxNum

`resolveAsOfTxNum`：

```java
private long resolveAsOfTxNum(StatePoint point, ArchiveProgress progress)
    throws ArchiveReaderException {
  switch (point.kind()) {
    case LATEST:
      return progress.nextTxNum();
    case BLOCK_BEFORE:
      return blockRange(point.blockNum()).firstTxNum();
    case BLOCK_END:
      return checkedIncrement(blockRange(point.blockNum()).lastTxNum());
    case TX_BEFORE:
      return txNumIndex.findUserTx(point.blockNum(), point.txIndex()).txNum();
    case TX_AFTER:
      return checkedIncrement(txNumIndex.findUserTx(point.blockNum(), point.txIndex()).txNum());
    case SYSTEM_AFTER:
      return checkedIncrement(txNumIndex.findSystemTx(point.blockNum(), point.phase()).txNum());
    default:
      throw unsupported;
  }
}
```

checks：

- `point.blockNum() <= progress.appliedBlockNum()`。
- `blockRange` exists。
- `lastTxNum + 1` overflow impossible in practice but still guard with `Math.addExact` or explicit check。
- tx lookup missing -> `HISTORY_UNAVAILABLE`。
- `asOfTxNum <= progress.nextTxNum()`。

## 12. Patch 8：error handling contract

S8 method behavior:

| Condition | Reader behavior |
| --- | --- |
| domain/key absent at state point | `ArchiveReadResult.missing()` |
| temporal store returns gap | `ArchiveReaderException(HISTORY_UNAVAILABLE)` |
| temporal store returns repair/corrupt | `ArchiveReaderException(CORRUPTED)` |
| capsule parse failure | `ArchiveReaderException(CODEC_ERROR)` |
| registry does not enable domain for state reader | `ArchiveReaderException(DOMAIN_NOT_ENABLED)` |
| caller passes wrong address/slot length | `IllegalArgumentException` or `ArchiveReaderException(UNSUPPORTED)`; tests should fix caller |

Do not:

- return missing for codec errors.
- convert missing account to empty `AccountCapsule`.
- convert missing storage to zero.
- call latest Store as fallback.

## 13. Patch 9：test doubles

S8 tests should use fake temporal store, not LevelDB/RocksDB:

```text
FakeArchiveTemporalStore
FakeArchiveDomainRegistry
FakeArchiveTxNumIndex
```

Why:

- S6 already tests raw store.
- S7 already tests temporal getAsOf.
- S8 should isolate reader semantics and codec parsing.

`FakeArchiveTemporalStore` behavior:

```java
Map<DomainKeyAtTx, Optional<byte[]>> values;
getAsOf(domain,key,asOfTxNum) -> configured result or missing
progress() -> configured progress
```

## 14. Tests

### 14.1 `DefaultArchiveStateReaderTest`

Cases:

| Case | Assertion |
| --- | --- |
| account present | returns `PRESENT(AccountCapsule)` with correct balance/raw fields |
| account missing | returns `MISSING`, not zero account |
| account corrupt protobuf | throws `CODEC_ERROR` |
| contract present | returns `PRESENT(ContractCapsule)` |
| contract corrupt protobuf | throws `CODEC_ERROR` |
| code present non-empty | returns exact bytes copy |
| code present empty | returns `PRESENT(empty bytes)` |
| code missing | returns `MISSING` |
| storage present nonzero | returns exact 32 bytes |
| storage present short value | returns present raw bytes; S9 normalizes to DataWord |
| storage missing/tombstone | returns `MISSING` |
| address array mutated after call | no reader state corruption |
| returned byte[] mutated | second read unaffected |

### 14.2 `DefaultArchiveStateReaderFactoryTest`

Cases:

| Case | Assertion |
| --- | --- |
| disabled archive | `DISABLED` |
| progress `REPAIR_REQUIRED` | `CORRUPTED` |
| progress `EMPTY` + historical block | `HISTORY_UNAVAILABLE` |
| `BLOCK_END` maps to `lastTxNum + 1` |
| `BLOCK_BEFORE` maps to `firstTxNum` |
| `TX_BEFORE/TX_AFTER` map through txNum index |
| missing block range | `HISTORY_UNAVAILABLE` |
| requested block > applied | `FUTURE_STATE` or `HISTORY_UNAVAILABLE` per chosen policy |
| registry checksum mismatch | `CORRUPTED` |
| `asOfTxNum > nextTxNum` | `FUTURE_STATE` |

### 14.3 `ArchiveReadResultTest`

Cases:

- `present(null)` rejects.
- `missing` has no value.
- returned bytes copy if helper supports byte arrays.
- equality/toString useful for diagnostics.

## 15. S8 coding order

Suggested commits:

```text
1. archive/reader: add ArchiveReadResult and ArchiveReaderException
2. archive/reader: add ArchiveStateReader API
3. archive/reader: add DefaultArchiveStateReader typed reads
4. archive/reader: add DefaultArchiveStateReaderFactory asOf resolver
5. archive/reader: add focused tests
```

Gate after S8 Java changes:

```bash
./gradlew :chainbase:test --tests 'org.tron.core.archive.reader.*'
./gradlew checkstyleMain checkstyleTest -x generateGitProperties
./gradlew lint
```

If tests live in `framework` because archive classes are easier to instantiate there, adjust the package-specific test command. Do not add test skips.

## 16. S8验收清单

- [ ] Reader core 不依赖 `Wallet`。
- [ ] Reader core 不依赖 latest `AccountStore/CodeStore/ContractStore/StorageRowStore`。
- [ ] Reader storage key 通过 registry codec，语义是 `address21 || slot32 || storageKeyVersion_u8`。
- [ ] Reader 不调用 `Storage.compose` / `Storage.getValue`。
- [ ] Reader 保留 missing vs present empty/zero。
- [ ] Reader 对 account/contract protobuf parse failure 抛 `CODEC_ERROR`。
- [ ] ReaderFactory 使用 S7 progress gate，不在 gap/repair 时 fallback latest。
- [ ] `StatePoint -> asOfTxNum` 的 `+1` 只在 factory/resolver 中发生。
- [ ] JSON-RPC 默认值仍未进入 S8 core。
- [ ] 不新增任何 test skip。

## 17. S9 handoff

S9 只需要把 JSON-RPC block selector 和 response default 接到 S8：

```text
TronJsonRpcImpl latest branch -> existing Wallet/latest path
TronJsonRpcImpl historical branch -> ArchiveStatePointResolver -> ArchiveJsonRpcStateAdapter -> ArchiveStateReader
```

S9 才负责：

- `missing account -> 0x0`
- `missing code -> 0x`
- `missing storage -> 32-byte zero`
- JSON-RPC checked exception declarations
- `latest` behavior unchanged
