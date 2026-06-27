# java-tron Archive S8/S9：ArchiveStateReader 与 JSON-RPC Historical Getters 4e80 编码执行包

> ⚠️ **枚举已被冻结契约取代**：Reader `Status` 必须含 `TOMBSTONE`，以 **L6 `{PRESENT, TOMBSTONE, MISSING}`** 为准（本文的 `{PRESENT, MISSING}` 违反 06-09 typed-tombstone 强制）；Reason 命名同样以 L6 为准。详见 [00-ARCHIVE-V3-AUTHORITY-AND-DECISIONS.md](00-ARCHIVE-V3-AUTHORITY-AND-DECISIONS.md) §2。

日期：2026-06-03

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`，`git status --short` 为空，精确冲突标记扫描无命中。

归属路线：[java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)

模块来源：[模块 05 ArchiveStateReader：4e80 java-tron 源码对照细化](./20260603-java-tron-module-05-state-reader-4e80-source-deep-dive.md)

收窄后的代码级执行包：[java-tron Archive L6：ArchiveStateReader + historical getters 代码级执行包](./20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md)

前置依赖：

- [S3 ArchiveDomainRegistry 4e80 编码执行包](./20260603-java-tron-archive-s3-domain-registry-4e80-coding-packet.md)：domain id、reader policy、canonical key/value codec。
- [S4/S5 WriteCollector 与 Storage Semantic Hook 4e80 编码执行包](./20260603-java-tron-archive-s4-s5-write-collector-storage-4e80-coding-packet.md)：`CONTRACT_STORAGE` semantic key。
- [S6/S7 ArchiveTemporalStore 4e80 编码执行包](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md)：`ArchiveTemporalStore.getAfterTx/getBeforeTx/getAsOf`、persistent txNum range。

S8/S9 把 archive sidecar 的历史状态读能力接到 java-tron 的三个 read-only JSON-RPC state getter。它不实现 historical `eth_call`，不做 proof/debug API，也不从 latest Store 推断历史状态。

## 1. 交付边界

S8 交付 reader core：

```text
ArchiveStatePoint
  -> ArchiveStateReaderFactory.open(point)
  -> ArchiveTemporalStore.getAfterTx/getBeforeTx
  -> typed account/contract/code/storage reads
```

S9 交付 JSON-RPC 接入：

```text
eth_getBalance(address, block)
eth_getCode(address, block)
eth_getStorageAt(address, slot, block)

latest selector     -> current Wallet/Store path unchanged
historical selector -> ArchiveStatePointResolver -> ArchiveJsonRpcStateAdapter -> ArchiveStateReader
```

本批次不交付：

- 不实现 historical `eth_call`。
- 不实现 EIP-1898 object block selector。
- 不把 historical getter fallback 到 latest。
- 不复用 `Wallet.getAccount/getContract/getContractInfo` 读取历史。
- 不构造 `Storage(address, StorageRowStore)` 或读取 raw `storage-row` physical key。
- 不暴露 proof/debug API。

完成条件：

1. `ArchiveStateReader` 可按 `ArchiveStatePoint` 读取 account、contract、code、storage。
2. `eth_getBalance/getCode/getStorageAt` 的 latest 行为保持当前路径。
3. 非 latest block 参数在 archive enabled 且数据覆盖时走 archive reader。
4. archive disabled/gap/corrupt 时返回明确 JSON-RPC internal error，不能返回 latest 或默认 zero。
5. storage historical key 使用 `address21 || slot32 || storageKeyVersion_u8`。
6. `eth_call(non-latest)` 继续明确 unsupported，直到 archive-backed Repository 完成。

## 2. 4e80 源码锚点

### 2.1 当前 RPC state getters

| 源码 | 当前事实 | S9 约束 |
| --- | --- | --- |
| `TronJsonRpc.java:90-94` | `eth_getBalance` 只声明 `JsonRpcInvalidParamsException` | 需要补 `JsonRpcInternalException` 注解和 throws |
| `TronJsonRpc.java:96-101` | `eth_getStorageAt` 只声明 invalid params | 同上 |
| `TronJsonRpc.java:103-108` | `eth_getCode` 的 Java 方法名是 `getABIOfSmartContract` | 不建议同 PR 改名，只改签名/实现 |
| `TronJsonRpcImpl.java:387-397` | `requireLatestBlockTag` 只接受 `latest`，tag 报 unsupported，quantity 报 unsupported | 三个 getter 替换为 resolver 分支；`eth_call` 保留 guard |
| `TronJsonRpcImpl.java:457-470` | `getTrxBalance` guard 后走 `wallet.getAccount` | latest 保留，historical 走 `ArchiveJsonRpcStateAdapter.getBalance` |
| `TronJsonRpcImpl.java:611-631` | `getStorageAt` guard 后走 latest `wallet.getContract` + `StorageRowStore` + `Storage` | latest 保留，historical 不构造 `Storage` |
| `TronJsonRpcImpl.java:635-649` | `getABIOfSmartContract` guard 后走 `wallet.getContractInfo` | latest 保留，historical 读 `CODE` domain |
| `TronJsonRpcImpl.java:165-170` | private error 文案含 tag unsupported、quantity unsupported、header not found | resolver/adapter 需要复用或保持兼容文案 |

### 2.2 block selector parser

| 源码 | 当前事实 | S9 约束 |
| --- | --- | --- |
| `JsonRpcApiUtil.java:55-62` | 定义 `earliest/pending/latest/finalized/safe` 和 pending/safe unsupported 文案 | `safe` 不再按 malformed quantity 处理 |
| `JsonRpcApiUtil.java:568-574` | `isBlockTag` 包含 `safe` | resolver 可复用 tag 判断 |
| `JsonRpcApiUtil.java:583-600` | `parseBlockTag`：latest=head、earliest=0、finalized=solid、pending/safe 抛 unsupported | resolver 复用 tag 语义，但 latest 要 bypass archive |
| `JsonRpcApiUtil.java:617-635` | `parseBlockNumber(String)` 支持 hex/decimal，拒绝负数和 long overflow | quantity selector 使用这个方法 |
| `JsonRpcApiUtil.java:643-648` | `parseBlockNumber(String, Wallet)` 对非 tag 用严格 `jsonHexToLong` | state getter 不用这个重载，避免破坏裸 decimal 兼容性 |
| `Wallet.java:696-702` | `getBlockByNum` 缺失返回 null | resolved historical block 缺失时不能 fallback latest |
| `Wallet.java:715-720` | `getSolidBlockNum/getHeadBlockNum` 读 solid/head | finalized/latest tag source |

### 2.3 latest Wallet path 不可复用为 historical

| 源码 | 当前事实 | historical 风险 |
| --- | --- | --- |
| `Wallet.java:332-355` | `getAccount` 读 latest `AccountStore`，并用 latest dynamic/account store 更新资源视图 | historical balance 不能调用 |
| `Wallet.java:3179-3198` | `getContract` 读 latest account/contract/abi stores | historical storage key version 不能从 latest contract 取 |
| `Wallet.java:3208-3241` | `getContractInfo` 读 latest account/contract/abi/code/contract-state/dynamic | historical code 不能调用 |
| `Wallet.java:4380-4408` | legacy balance trace 只服务 balance history | 不能替代 full archive account/code/storage |

### 2.4 capsule/value decode

| 源码 | 当前事实 | S8 约束 |
| --- | --- | --- |
| `AccountCapsule.java:64-69` | parse 失败只 log，`account` 可能为 null | reader 构造后必须检查 `getInstance()` |
| `AccountCapsule.java:253-259` | `getData()`/`getInstance()` 是 protobuf Account | ACCOUNT domain value 是 `AccountCapsule.getData()` |
| `AccountCapsule.java:326-327` | `getBalance()` 返回 long balance | JSON adapter 用于 balance |
| `ContractCapsule.java:47-52` | parse 失败不抛，`smartContract` 可能为 null | reader 必须检查 `getInstance()` |
| `ContractCapsule.java:129-134` | 可取 historical `trxHash` 与 version | storage reader 只需要 version suffix，不用 trxHash |
| `CodeCapsule.java:28-44` | raw bytecode wrapper | reader 可直接返回 code bytes |
| `DataWord.java:83-91` | slot/value 超过 32 bytes 抛 RuntimeException | S9 slot parse 映射 invalid params；archive value length 错映射 internal/corrupt |
| `DataWord.java:268-274` | zero 判定按 32-byte word | S5 已把 zero storage 归一为 tombstone |

### 2.5 storage physical key 禁区

| 源码 | 当前事实 | S8/S9 约束 |
| --- | --- | --- |
| `Storage.java:46-53` | physical key 用 addrHash + slot suffix；version 1 会 hash slot | historical reader 不使用 physical key |
| `Storage.java:61-70` | create2 会把 `address || trxHash` 参与 addrHash | semantic archive key 不加入 trxHash |
| `Storage.java:73-83` | latest `getValue` 读 `StorageRowStore` physical row | historical storage 不访问 `StorageRowStore` |
| `TronJsonRpcImpl.java:625-631` | latest path 构造 `Storage` 并读 physical row | 只保留 latest 分支 |

`CONTRACT_STORAGE` canonical key 必须保持 S5 定义：

```text
address21 || slot32 || storageKeyVersion_u8
```

`storageKeyVersion_u8` 由 historical `CONTRACT` domain 中的 contract version 归一得到：

```text
contractVersion == 1 -> 0x01
otherwise            -> 0x00
```

## 3. StatePoint 语义

S8/S9 必须对齐 S6/S7 的 temporal read API：

```text
getAfterTx(domain,key,txNum)  -> txNum 执行后的状态
getBeforeTx(domain,key,txNum) -> txNum 执行前的状态
getAsOf(...)                 -> P0 alias 到 getAfterTx
```

新增：

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStatePoint.java
```

建议结构：

```java
public final class ArchiveStatePoint {
  public enum Kind {
    BLOCK_END,
    TX_BEFORE,
    TX_AFTER,
    SYSTEM_AFTER
  }

  private final Kind kind;
  private final long blockNum;
  private final byte[] blockHash;
  private final long txNum;
}
```

映射规则：

| State point | temporal read |
| --- | --- |
| `BLOCK_END(blockNum, finalizeTxNum)` | `getAfterTx(..., finalizeTxNum)` |
| `TX_BEFORE(userTxNum)` | `getBeforeTx(..., userTxNum)` |
| `TX_AFTER(userTxNum)` | `getAfterTx(..., userTxNum)` |
| `SYSTEM_AFTER(systemTxNum)` | `getAfterTx(..., systemTxNum)` |

P0 JSON-RPC historical getters 只使用 `BLOCK_END`。`TX_BEFORE/TX_AFTER` 是给 debug/proof/historical call 预留的内部能力。

不要在 reader 调用点手写 `+1`。所有 txNum 语义都由 `ArchiveStatePoint` 和 `ArchiveStateReaderFactory` 统一转换。

## 4. Reader core 文件

新增：

```text
chainbase/src/main/java/org/tron/core/archive/reader/
  ArchiveStatePoint.java
  ArchiveReadResult.java
  ArchiveReaderException.java
  ArchiveStateReader.java
  DefaultArchiveStateReader.java
  ArchiveStateReaderFactory.java
  ArchiveStorageKey.java
  ArchiveStorageKeyCodec.java
```

`ArchiveReadResult<T>`：

```java
public final class ArchiveReadResult<T> {
  public enum Status {
    PRESENT,
    MISSING
  }

  public static <T> ArchiveReadResult<T> present(T value);
  public static <T> ArchiveReadResult<T> missing();
}
```

为什么不用裸 `Optional`：

- missing account 在 RPC balance 渲染为 `0x0`，但 proof/debug 需要知道对象不存在。
- missing code 和 present empty code 都会渲染为 `0x`。
- missing storage 和 present zero storage 都会渲染为 32-byte zero。
- codec/corrupt error 不应伪装成 missing。

`ArchiveReaderException.Reason`：

```text
DISABLED
FUTURE_STATE
HISTORY_UNAVAILABLE
DOMAIN_NOT_ENABLED
CODEC_ERROR
CORRUPTED
UNSUPPORTED
```

S8 不映射 JSON-RPC error；S9 adapter 再转 `JsonRpcInternalException`。

## 5. ArchiveStateReader API

```java
public interface ArchiveStateReader extends AutoCloseable {
  ArchiveStatePoint statePoint();

  long txNum();

  ArchiveReadResult<AccountCapsule> getAccount(byte[] address21)
      throws ArchiveReaderException;

  ArchiveReadResult<ContractCapsule> getContract(byte[] address21)
      throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getCode(byte[] address21)
      throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getStorageValue(byte[] address21, byte[] slot32)
      throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getRaw(ArchiveDomain domain, byte[] canonicalKey)
      throws ArchiveReaderException;

  @Override
  void close();
}
```

规则：

- `address21.length == 21`。
- `slot32.length == 32`。
- returned byte arrays must be copied。
- `close()` P0 可以 no-op，但保留给未来 pinned snapshot/progress。
- `getRaw` 只给 internal tests/debug/proof，不给 JSON-RPC adapter 手拼 domain。

## 6. DefaultArchiveStateReader

构造依赖：

```java
public final class DefaultArchiveStateReader implements ArchiveStateReader {
  private final ArchiveTemporalStore temporalStore;
  private final ArchiveDomainRegistry registry;
  private final ArchiveStatePoint point;
}
```

内部读取：

```text
read(domain,key):
  validate reader policy
  switch point.kind:
    BLOCK_END/TX_AFTER/SYSTEM_AFTER -> temporalStore.getAfterTx(domain,key,point.txNum)
    TX_BEFORE                       -> temporalStore.getBeforeTx(domain,key,point.txNum)
```

### 6.1 account

```text
canonical key = address21
raw = read(ACCOUNT, address21)
missing -> ArchiveReadResult.missing()
present -> new AccountCapsule(raw)
if capsule.getInstance() == null -> CODEC_ERROR
```

### 6.2 contract

```text
canonical key = address21
raw = read(CONTRACT, address21)
missing -> missing
present -> new ContractCapsule(raw)
if capsule.getInstance() == null -> CODEC_ERROR
```

P0 不在 `getContract` 自动拼 ABI；`ContractStore` 在 S4 采集的是 clear ABI 后的 stored value。ABI 是 P1/debug domain，不参与三个 historical getters。

### 6.3 code

```text
canonical key = address21
raw = read(CODE, address21)
missing -> missing
present -> copy raw bytes
```

P0 key 继续用 21-byte contract address。不要改成 codeHash；当前 latest path `Wallet.java:3225-3230` 也是 `CodeStore.get(address)`。

### 6.4 storage

```text
getStorageValue(address21, slot32):
  contract = getContract(address21)
  if contract missing:
    return missing
  version = storageKeyVersion(contract)
  key = address21 || slot32 || version_u8
  raw = read(CONTRACT_STORAGE, key)
  missing -> missing
  present value length must be 32
  return copy raw bytes
```

`storageKeyVersion(contract)`：

```text
contract.getContractVersion() == 1 ? 1 : 0
```

不要使用 historical contract `trxHash` 构造 physical key。S5 semantic hook 已经把 create2/addrHash 差异折叠进 canonical `(address,slot,version)`。

## 7. ArchiveStateReaderFactory

新增：

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStateReaderFactory.java
```

接口：

```java
public interface ArchiveStateReaderFactory {
  ArchiveStateReader open(ArchiveStatePoint point) throws ArchiveReaderException;
}
```

`open(point)` 校验：

| 条件 | 行为 |
| --- | --- |
| archive disabled | `DISABLED` |
| temporal progress `ARCHIVE_GAP` | `HISTORY_UNAVAILABLE` |
| temporal progress `REPAIR_REQUIRED` | `CORRUPTED` |
| point block > progress block | `FUTURE_STATE` |
| point txNum >= progress.nextTxNum | `FUTURE_STATE` |
| block range missing | `HISTORY_UNAVAILABLE` |
| registry checksum mismatch | `CORRUPTED` |

JSON-RPC latest 分支不会调用 factory。archive reader 的 “latest” 能力若后续需要，应显式命名为 `ARCHIVE_CURRENT`，避免误读为 canonical latest Store。

## 8. Framework RPC bridge 文件

新增：

```text
framework/src/main/java/org/tron/core/archive/
  ResolvedArchiveStatePoint.java
  ArchiveStatePointResolver.java
  ArchiveJsonRpcStateAdapter.java
```

修改：

```text
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java
```

`ResolvedArchiveStatePoint`：

```java
public final class ResolvedArchiveStatePoint {
  private final boolean latest;
  private final long blockNum;
  private final ArchiveStatePoint point;

  public static ResolvedArchiveStatePoint latest();
  public static ResolvedArchiveStatePoint historical(long blockNum, ArchiveStatePoint point);
}
```

`ArchiveStatePointResolver` dependencies：

```text
Wallet
ArchiveTxNumIndex or ArchiveTemporalStore block range API
```

It should not depend on `TronJsonRpcImpl` private methods.

## 9. ArchiveStatePointResolver

`resolveBlockEnd(String blockNumOrTag)`：

```text
if latest:
  return latest()

if isBlockTag(blockNumOrTag):
  blockNum = JsonRpcApiUtil.parseBlockTag(blockNumOrTag, wallet)
else:
  blockNum = JsonRpcApiUtil.parseBlockNumber(blockNumOrTag)

block = wallet.getBlockByNum(blockNum)
if block == null:
  throw JsonRpcInternalException("header not found")

range = txNumIndex.getBlockRange(blockNum)
if range missing:
  throw JsonRpcInternalException("archive history unavailable")
if range.blockHash != block hash:
  throw JsonRpcInternalException("archive history inconsistent")

return historical(blockNum, ArchiveStatePoint.blockEnd(blockNum, blockHash, range.finalizeTxNum()))
```

解析行为：

| 输入 | 行为 |
| --- | --- |
| `"latest"` | bypass archive |
| `"earliest"` | block 0 end |
| `"finalized"` | solid block end |
| `"pending"` | `JsonRpcInvalidParamsException(TAG_PENDING_SUPPORT_ERROR)` via `parseBlockTag` |
| `"safe"` | `JsonRpcInvalidParamsException(TAG_SAFE_SUPPORT_ERROR)` via `parseBlockTag` |
| `"0x10"` | block 16 end |
| `"16"` | block 16 end |
| negative/overflow/malformed | `JsonRpcInvalidParamsException(BLOCK_NUM_ERROR)` |
| future/missing block | `JsonRpcInternalException("header not found")` or explicit future message |
| archive block range missing | `JsonRpcInternalException`, no fallback |

If implementation wants future block to be `invalid params` instead of internal, make it consistent with existing RPC tests. The invariant is no latest fallback.

## 10. ArchiveJsonRpcStateAdapter

```java
public final class ArchiveJsonRpcStateAdapter {
  private final ArchiveStateReaderFactory readerFactory;

  public String getBalance(byte[] address21, ArchiveStatePoint point)
      throws JsonRpcInternalException;

  public String getCode(byte[] address21, ArchiveStatePoint point)
      throws JsonRpcInternalException;

  public String getStorageAt(byte[] address21, byte[] slotRaw, ArchiveStatePoint point)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;
}
```

### 10.1 balance

```text
reader.getAccount(address21)
missing -> "0x0"
present -> ByteArray.toJsonHex(account.getBalance())
```

### 10.2 code

```text
reader.getCode(address21)
missing -> "0x"
present -> ByteArray.toJsonHex(code)
```

present empty code also renders `"0x"`; result wrapper still preserves semantics inside reader.

### 10.3 storage

```text
slot32 = new DataWord(slotRaw).getData()
reader.getStorageValue(address21, slot32)
missing -> ByteArray.toJsonHex(new byte[32])
present 32-byte -> ByteArray.toJsonHex(value)
present other length -> JsonRpcInternalException("archive storage value must be 32 bytes")
```

Slot parse error from `DataWord` maps to `JsonRpcInvalidParamsException`. Archive value length error maps to internal/corrupt.

`ArchiveReaderException` mapping:

| Reason | JSON-RPC exception |
| --- | --- |
| `DISABLED` | `JsonRpcInternalException("archive is disabled")` |
| `FUTURE_STATE` | `JsonRpcInternalException("archive state unavailable")` |
| `HISTORY_UNAVAILABLE` | `JsonRpcInternalException("archive history unavailable")` |
| `DOMAIN_NOT_ENABLED` | `JsonRpcInternalException("archive domain unavailable")` |
| `CODEC_ERROR` | `JsonRpcInternalException("archive codec error")` |
| `CORRUPTED` | `JsonRpcInternalException("archive data corrupted")` |
| `UNSUPPORTED` | `JsonRpcInternalException("archive state point unsupported")` |

## 11. TronJsonRpc interface changes

Modify three method annotations:

```java
@JsonRpcErrors({
    @JsonRpcError(exception = JsonRpcInvalidParamsException.class, code = -32602, data = "{}"),
    @JsonRpcError(exception = JsonRpcInternalException.class, code = -32000, data = "{}"),
})
String getTrxBalance(String address, String blockNumOrTag)
    throws JsonRpcInvalidParamsException, JsonRpcInternalException;
```

Apply same pattern to:

```text
getStorageAt(String address, String storageIdx, String blockNumOrTag)
getABIOfSmartContract(String contractAddress, String bnOrId)
```

Implementation signatures in `TronJsonRpcImpl` must match. Any tests/mocks that implement `TronJsonRpc` must update throws declarations.

## 12. TronJsonRpcImpl method flow

### 12.1 `eth_getBalance`

```text
resolved = archiveStatePointResolver.resolveBlockEnd(blockNumOrTag)
addressData = addressCompatibleToByteArray(address)

if resolved.isLatest():
  keep current wallet.getAccount path

return archiveJsonRpcStateAdapter.getBalance(addressData, resolved.point())
```

### 12.2 `eth_getCode`

```text
resolved = archiveStatePointResolver.resolveBlockEnd(blockNumOrTag)
addressData = addressCompatibleToByteArray(contractAddress)

if resolved.isLatest():
  keep current wallet.getContractInfo path

return archiveJsonRpcStateAdapter.getCode(addressData, resolved.point())
```

### 12.3 `eth_getStorageAt`

```text
resolved = archiveStatePointResolver.resolveBlockEnd(blockNumOrTag)
addressData = addressCompatibleToByteArray(address)

if resolved.isLatest():
  keep current wallet.getContract + StorageRowStore + Storage path

slotRaw = parse with ByteArray.fromHexString(storageIdx)
return archiveJsonRpcStateAdapter.getStorageAt(addressData, slotRaw, resolved.point())
```

Catch malformed slot/address as `JsonRpcInvalidParamsException`, not generic internal error.

Keep `requireLatestBlockTag` for `eth_call` and other methods that remain latest-only.

## 13. Why eth_call stays out

Current 4e80 `eth_call` is not a small getter change:

| 源码 | 当前事实 |
| --- | --- |
| `TronJsonRpcImpl.java:1001-1044` | object block selector validates block, then line 1037 forces `blockNumOrTag = latest` |
| `Wallet.java:3112-3118` | constant call checks contract in latest `ContractStore` |
| `Wallet.java:3130-3141` | constant call uses latest block and `StoreFactory.getInstance()` |
| `RepositoryImpl.java:501-512` | `getContract` fallback latest contract store |
| `RepositoryImpl.java:650-664` | `getCode` fallback latest code store |
| `RepositoryImpl.java:681-718` | storage uses latest `StorageRowStore` |
| `RepositoryImpl.java:731-733` | balance reads latest account |

historical `eth_call` needs `ArchiveRepositoryAdapter`:

```text
read account/contract/code/storage/balance -> ArchiveStateReader
write methods -> in-memory overlay
commit -> merge child overlay only, never canonical Store
dynamic properties -> historical view or explicit latest-only limitation
```

Until then, `eth_call(non-latest)` must stay unsupported. Do not silently use latest after validating a historical selector.

## 14. Patch 分片

### S8a：reader result/exception/state point

新增 `ArchiveReadResult`、`ArchiveReaderException`、`ArchiveStatePoint`。

测试：

- missing vs present empty 可区分。
- invalid address/slot length 拒绝。
- `BLOCK_END/TX_BEFORE/TX_AFTER` temporal method mapping 正确。

### S8b：DefaultArchiveStateReader

实现 account/contract/code/storage typed reads。

测试：

- account missing/present/corrupt。
- contract missing/present/corrupt。
- code missing/present empty/present non-empty。
- storage uses historical contract version suffix。
- storage missing contract returns missing。
- storage present value length != 32 reports corrupt/internal.

### S8c：ArchiveStateReaderFactory

实现 progress/domain/checksum guard。

测试：

- archive disabled -> `DISABLED`。
- progress gap -> `HISTORY_UNAVAILABLE`。
- repair required -> `CORRUPTED`。
- future block/txNum -> `FUTURE_STATE`。
- checksum mismatch -> `CORRUPTED`。

### S9a：ResolvedArchiveStatePoint + resolver

实现 JSON-RPC string block selector -> latest/historical block end。

测试：

- latest bypass。
- earliest -> block 0 range。
- finalized -> solid block range。
- pending/safe -> existing unsupported errors。
- hex/decimal quantities。
- malformed/negative/overflow。
- missing block and missing archive range no fallback。

### S9b：ArchiveJsonRpcStateAdapter

实现 reader result -> JSON hex/default。

测试：

- missing balance -> `0x0`。
- missing code -> `0x`。
- missing storage -> 32-byte zero hex。
- archive disabled/gap/corrupt -> internal error。
- malformed storage index -> invalid params。

### S9c：TronJsonRpc interface annotations

给三个 method 增加 `JsonRpcInternalException` throws 和 `@JsonRpcError`。

测试：

- json-rpc error resolver 对 archive internal error 返回 `-32000`。
- invalid params 仍返回 `-32602`。

### S9d：TronJsonRpcImpl integration

改三个 getter，latest 分支保留当前源码逻辑。

测试：

- latest getBalance/getCode/getStorageAt 行为不变。
- historical block N/N+1 返回不同旧状态。
- archive disabled + historical selector 明确 internal error。
- `eth_call(non-latest)` 仍被 `requireLatestBlockTag` 拒绝。

## 15. 编码检查清单

- [ ] Reader core 不依赖 `Wallet`。
- [ ] Reader core 不读 latest java-tron Store。
- [ ] Reader core 不访问 `StorageRowStore`。
- [ ] Capsule parse 后检查 `getInstance()`。
- [ ] `CODE` key 使用 21-byte address。
- [ ] `CONTRACT_STORAGE` key 使用 `address21 || slot32 || version_u8`。
- [ ] JSON latest 分支保留现有 Wallet/Store path。
- [ ] JSON historical 分支不 fallback latest。
- [ ] block selector quantity 复用 `JsonRpcApiUtil.parseBlockNumber(String)`。
- [ ] pending/safe 使用现有 unsupported 行为。
- [ ] 三个 RPC interface 方法补 `JsonRpcInternalException` 注解和 throws。
- [ ] malformed slot 是 invalid params；archive corrupt 是 internal error。
- [ ] `eth_call(non-latest)` 仍 latest-only，直到 ArchiveRepositoryAdapter 完成。

## 16. 建议验证命令

文档阶段不需要运行。进入编码后，优先跑 focused tests：

```bash
./gradlew :chainbase:test --tests '*ArchiveStorageKeyCodecTest'
./gradlew :chainbase:test --tests '*DefaultArchiveStateReaderTest'
./gradlew :framework:test --tests '*JsonRpcArchiveStatePointResolverTest'
./gradlew :framework:test --tests '*ArchiveJsonRpcStateAdapterTest'
./gradlew :framework:test --tests '*TronJsonRpcHistoricalGettersTest'
```

合并前按 java-tron 规则跑：

```bash
./gradlew lint
./gradlew build
```
