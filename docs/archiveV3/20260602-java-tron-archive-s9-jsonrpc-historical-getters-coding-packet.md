# java-tron Archive S9：JSON-RPC Historical Getters 编码执行包

> 2026-06-03 更新：本文是旧 `a79693e450` 编码包。当前 `4e80f8ffa9a2` 的 S8/S9 编码入口请看 [java-tron Archive S8/S9：ArchiveStateReader 与 JSON-RPC Historical Getters 4e80 编码执行包](./20260603-java-tron-archive-s8-s9-state-reader-rpc-4e80-coding-packet.md)，旧行号和部分 RPC 判断不可直接用于编码。

日期：2026-06-02

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

历史前置执行包：

- [S8 ArchiveStateReader Core 编码执行包](./20260602-java-tron-archive-s8-state-reader-core-coding-packet.md)
- [S7 Temporal Commit / GetAsOf / Unwind / Startup 编码执行包](./20260602-java-tron-archive-s7-temporal-commit-unwind-startup-coding-packet.md)

归属规格：

- [PR6 StateReader/JSON-RPC 代码级实现规格](./20260602-java-tron-archive-pr6-state-reader-jsonrpc-implementation-spec.md)
- [模块 05 ArchiveStateReader 逐文件 Patch 清单](./20260602-java-tron-archive-module-05-state-reader-patch-checklist.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 旧文档原始基线：`a79693e450`。

当前 4e80 S8/S9 编码入口：[java-tron Archive S8/S9：ArchiveStateReader 与 JSON-RPC Historical Getters 4e80 编码执行包](./20260603-java-tron-archive-s8-s9-state-reader-rpc-4e80-coding-packet.md)

## 1. 本包目标

S9 把 S8 的 archive reader core 接到 java-tron JSON-RPC 的三个 state getter：

```text
eth_getBalance(address, block)
eth_getCode(address, block)
eth_getStorageAt(address, slot, block)
```

交付边界：

| 范围 | S9 是否交付 | 说明 |
| --- | --- | --- |
| string block tag/quantity 分流 | 是 | `latest` 走旧路径，其他可解析历史 block 走 archive |
| `ArchiveStatePointResolver` | 是 | JSON-RPC block selector -> `StatePoint.blockEnd(N)` |
| `ArchiveJsonRpcStateAdapter` | 是 | reader result -> JSON-RPC hex/default |
| `TronJsonRpc` error annotations | 是 | 三个方法增加 `JsonRpcInternalException` |
| `TronJsonRpcImpl` 三个方法接入 | 是 | 保留 latest Wallet/Store 行为 |
| historical `eth_call` | 否 | PR8/S12/S13 做 |
| EIP-1898 object block selector | 否 | S9 只处理当前接口已有的 string 参数 |
| proof/debug API | 否 | PR9 做 |

核心原则：

```text
latest       -> existing Wallet/Manager/Store path
historical   -> ArchiveStatePointResolver -> ArchiveJsonRpcStateAdapter -> ArchiveStateReader
pending      -> invalid params with existing TAG_PENDING_SUPPORT_ERROR
safe         -> current baseline has no safe tag; malformed quantity -> invalid block number
archive error -> JsonRpcInternalException, never default zero
```

## 2. java-tron 当前源码事实

### 2.1 RPC interface

`/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java:90-108`：

| JSON-RPC method | Java method | 当前 throws |
| --- | --- | --- |
| `eth_getBalance` | `getTrxBalance(String address, String blockNumOrTag)` | `JsonRpcInvalidParamsException` |
| `eth_getStorageAt` | `getStorageAt(String address, String storageIdx, String blockNumOrTag)` | `JsonRpcInvalidParamsException` |
| `eth_getCode` | `getABIOfSmartContract(String contractAddress, String bnOrId)` | `JsonRpcInvalidParamsException` |

注意：`eth_getCode` 的 Java 方法名是 `getABIOfSmartContract`。S9 不建议在同一个 PR 里改名，避免放大调用点和测试 churn；只改签名、注解和实现。

### 2.2 当前 latest-only 判断是方法内联逻辑

当前本地源码没有统一 latest guard helper。三个 state getter 分别内联同一类判断：

```text
if block in {"earliest", "pending", "finalized"}:
    throw TAG_NOT_SUPPORT_ERROR
else if block == "latest":
    run latest Wallet/Store path
else:
    ByteArray.hexToBigInteger(block)
    throw QUANTITY_NOT_SUPPORT_ERROR
```

影响：

- `earliest/finalized/pending` 被 `TAG [earliest | pending | finalized] not supported` 拒绝。
- `safe` 当前没有常量，不在 tag 分支；会被当作 malformed quantity，最终报 `invalid block number`。
- `0x1`、`1` 先由 `ByteArray.hexToBigInteger` 校验为合法 block number，再被 `QUANTITY not supported, just support TAG as latest` 拒绝。
- `abc` 由 `ByteArray.hexToBigInteger` 抛异常后映射为 `invalid block number`。

S9 的三个 state getter 应把这段内联判断替换为 `ArchiveStatePointResolver`。`eth_call` 仍保留当前 inline latest-only 行为，直到 PR8。

### 2.3 当前三个 state getter 的 latest 路径

| Method | 当前入口 | latest path |
| --- | --- | --- |
| balance | `TronJsonRpcImpl.java:394-419` | `addressCompatibleToByteArray` -> `wallet.getAccount` -> missing balance `0x0` |
| storage | `TronJsonRpcImpl.java:536-568` | `wallet.getContract` -> `Storage(address, StorageRowStore)` -> `setContractVersion` -> `generateAddrHash` -> `storage.getValue(new DataWord(slot))` |
| code | `TronJsonRpcImpl.java:572-599` | `wallet.getContractInfo` -> runtime code bytes -> missing `0x` |

这些 latest path 必须原样保留。Historical path 不允许调用：

```text
Wallet.getAccount
Wallet.getContract
Wallet.getContractInfo
Manager.getStorageRowStore
new Storage(address, StorageRowStore)
```

### 2.4 Block parser 事实

当前可复用的 parser 分散在 `JsonRpcApiUtil` 和 `ByteArray`：

| 方法 | 行为 | S9 用法 |
| --- | --- | --- |
| `JsonRpcApiUtil.getByJsonBlockId(String, Wallet)` at `JsonRpcApiUtil.java:518-531` | `pending` 抛 `TAG pending not supported`；empty/latest 返回 `-1`；earliest 返回 `0`；finalized 返回 solid block；其他走 `jsonHexToLong` | 可参考 tag 含义，但不能直接作为 state getter resolver 主逻辑 |
| `ByteArray.hexToBigInteger(String)` at `ByteArray.java:146-151` | `0x...` 按 hex，裸字符串按 decimal | resolver 处理 quantity 时使用 |
| `ByteArray.jsonHexToLong(String)` at `ByteArray.java:154-159` | 要求 `0x` 前缀 | 不用于 state getter quantity，否则裸 decimal 兼容性会变差 |

关键兼容点：当前 state getter 对 quantity 使用 `ByteArray.hexToBigInteger`，因此 S9 支持 historical quantity 时应保留 `0x10 -> 16` 和 `"16" -> 16`。同时要补上当前 latest-only 校验未做的负数/long overflow 检查。

### 2.5 Address、slot、hex 默认值

| 源码 | 行为 |
| --- | --- |
| `JsonRpcApiUtil.addressCompatibleToByteArray` | 接受 ETH 20-byte hex 和 TRON 21-byte hex；20-byte 自动补 `41` 前缀 |
| `ByteArray.fromHexString` | 可去掉 `0x`；奇数长度左补 `0` |
| `DataWord(byte[])` | 小于 32 字节左填充；大于 32 字节抛 `RuntimeException` |
| `ByteArray.toJsonHex(byte[])` | `null` 或 empty -> `0x` |
| `ByteArray.toJsonHex(Long)` | quantity hex，无前导零 |

S9 storage adapter 对用户输入 slot 应复用 `new DataWord(rawSlot).getData()`。如果 raw slot 超过 32 字节，映射为 `JsonRpcInvalidParamsException`。如果 archive 返回的 storage value 超过 32 字节，这是 archive 数据损坏，映射为 `JsonRpcInternalException`。

### 2.6 Error resolver 事实

`JsonRpcErrorResolver` 通过方法上的 `@JsonRpcErrors` 注解匹配异常类型。`JsonRpcInternalException` 是 checked exception。

因此 S9 必须同时修改：

```text
TronJsonRpc.java interface annotation + throws
TronJsonRpcImpl.java implementation throws
相关 tests/mock throws 声明
```

只在 impl 抛 `JsonRpcInternalException` 不够，JSON-RPC error code 也会不稳定。

## 3. Desired runtime flow

### 3.1 `eth_getBalance`

```text
getTrxBalance(address, blockNumOrTag)
  resolved = archiveStatePointResolver.resolveBlockEnd(blockNumOrTag)
  addressData = addressCompatibleToByteArray(address)

  if resolved.isLatest()
    Account request = Account.newBuilder().setAddress(addressData).build()
    Account reply = wallet.getAccount(request)
    return ByteArray.toJsonHex(reply == null ? 0L : reply.getBalance())

  return archiveJsonRpcStateAdapter.getBalance(addressData, resolved.statePoint())
```

### 3.2 `eth_getCode`

```text
getABIOfSmartContract(contractAddress, blockNumOrTag)
  resolved = archiveStatePointResolver.resolveBlockEnd(blockNumOrTag)
  addressData = addressCompatibleToByteArray(contractAddress)

  if resolved.isLatest()
    BytesMessage msg = BytesMessage.newBuilder().setValue(addressData).build()
    SmartContractDataWrapper wrapper = wallet.getContractInfo(msg)
    return wrapper == null ? "0x" : ByteArray.toJsonHex(wrapper.getRuntimecode().toByteArray())

  return archiveJsonRpcStateAdapter.getCode(addressData, resolved.statePoint())
```

### 3.3 `eth_getStorageAt`

```text
getStorageAt(address, storageIdx, blockNumOrTag)
  resolved = archiveStatePointResolver.resolveBlockEnd(blockNumOrTag)
  addressData = addressCompatibleToByteArray(address)

  if resolved.isLatest()
    keep current ContractStore/StorageRowStore/Storage path unchanged

  rawSlot = parseStorageIndex(storageIdx)
  return archiveJsonRpcStateAdapter.getStorageAt(addressData, rawSlot, resolved.statePoint())
```

Historical path 绝不读取 historical `CONTRACT` 后构造 `Storage`。PR4/S5 已经捕获 logical storage key：

```text
CONTRACT_STORAGE key = address21 || slot32 || storageKeyVersion_u8
```

Historical path 需要捕获 `ByteArray.fromHexString(storageIdx)` 的 unchecked decode error，并转成 `JsonRpcInvalidParamsException`。不要让 malformed slot 变成 generic internal error。

## 4. Patch 1：`ResolvedStatePoint`

新增：

```text
framework/src/main/java/org/tron/core/archive/ResolvedStatePoint.java
```

建议接口：

```java
public final class ResolvedStatePoint {
  private final boolean latest;
  private final long blockNum;
  private final StatePoint statePoint;

  public static ResolvedStatePoint latest();

  public static ResolvedStatePoint blockEnd(long blockNum);

  public boolean isLatest();

  public long blockNum();

  public StatePoint statePoint();
}
```

要求：

- `latest()` 使用 `StatePoint.latest()` 或者只在 latest 分支访问 `isLatest()`；不要让 latest path 误开 archive reader。
- `blockEnd(N)` 内部使用 `StatePoint.blockEnd(N)`。
- `statePoint()` 对 latest 的行为要在测试里固定：推荐返回 `StatePoint.latest()`，但 `ArchiveJsonRpcStateAdapter` 不应接收 latest。
- `equals/hashCode/toString` 方便测试。

## 5. Patch 2：`ArchiveStatePointResolver`

新增：

```text
framework/src/main/java/org/tron/core/archive/ArchiveStatePointResolver.java
```

依赖：

```text
Wallet
JsonRpcApiUtil
StatePoint
ResolvedStatePoint
```

建议接口：

```java
public final class ArchiveStatePointResolver {
  private final Wallet wallet;

  public ArchiveStatePointResolver(Wallet wallet);

  public ResolvedStatePoint resolveBlockEnd(String blockNumOrTag)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;
}
```

解析规则：

| 输入 | 行为 |
| --- | --- |
| `"latest"` | `ResolvedStatePoint.latest()`，不要读 blockStore |
| `"earliest"` | `ResolvedStatePoint.blockEnd(0)`，并检查 block 0 header 存在 |
| `"finalized"` | `blockNum = wallet.getSolidBlockNum()`，返回 `blockEnd(blockNum)` |
| `"pending"` | invalid params；推荐使用当前已有的 `TAG_PENDING_SUPPORT_ERROR`，因为 PR6 支持 `earliest/finalized` 后 generic tag 文案会误导 |
| `"safe"` | 当前源码没有 `safe` 常量，按 malformed quantity 返回 `invalid block number`，除非同 PR 增加 `SAFE_STR` |
| quantity `0x...` | `ByteArray.hexToBigInteger(String)` 后转 long |
| decimal `"16"` | `ByteArray.hexToBigInteger(String)`，保留当前兼容性 |
| malformed/negative/overflow | `JsonRpcInvalidParamsException(BLOCK_NUM_ERROR)` |
| `blockNum > head` | `JsonRpcInvalidParamsException("block number is in the future")` |
| resolved block header missing | `JsonRpcInternalException("header not found")` |

建议实现顺序：

```java
if (LATEST_STR.equalsIgnoreCase(blockNumOrTag)) {
  return ResolvedStatePoint.latest();
}

long blockNum;
if (EARLIEST_STR.equalsIgnoreCase(blockNumOrTag)) {
  blockNum = 0L;
} else if (FINALIZED_STR.equalsIgnoreCase(blockNumOrTag)) {
  blockNum = wallet.getSolidBlockNum();
} else if (PENDING_STR.equalsIgnoreCase(blockNumOrTag)) {
  throw new JsonRpcInvalidParamsException(TAG_PENDING_SUPPORT_ERROR);
} else {
  BigInteger parsed;
  try {
    parsed = ByteArray.hexToBigInteger(blockNumOrTag);
  } catch (Exception e) {
    throw new JsonRpcInvalidParamsException("invalid block number");
  }
  if (parsed.signum() < 0 || parsed.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
    throw new JsonRpcInvalidParamsException("invalid block number");
  }
  blockNum = parsed.longValue();
}

Block nowBlock = wallet.getNowBlock();
if (nowBlock == null) {
  throw new JsonRpcInternalException("header not found");
}
long head = nowBlock.getBlockHeader().getRawData().getNumber();
if (blockNum > head) {
  throw new JsonRpcInvalidParamsException("block number is in the future");
}
if (wallet.getBlockByNum(blockNum) == null) {
  throw new JsonRpcInternalException("header not found");
}
return ResolvedStatePoint.blockEnd(blockNum);
```

不要直接使用 `JsonRpcApiUtil.getByJsonBlockId`，否则裸 decimal historical 查询会从支持变成拒绝；也不要调用当前 `Wallet` 不存在的 `getHeadBlockNum()`。

## 6. Patch 3：`ArchiveJsonRpcStateAdapter`

新增：

```text
framework/src/main/java/org/tron/core/services/jsonrpc/archive/ArchiveJsonRpcStateAdapter.java
```

依赖：

```text
ArchiveStateReaderFactory
ArchiveStateReader
ArchiveReadResult
ArchiveReaderException
ByteArray
DataWord
```

建议接口：

```java
public final class ArchiveJsonRpcStateAdapter {
  private final ArchiveStateReaderFactory readerFactory;

  public String getBalance(byte[] address, StatePoint point)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;

  public String getCode(byte[] address, StatePoint point)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;

  public String getStorageAt(byte[] address, byte[] rawSlot, StatePoint point)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;
}
```

### 6.1 Balance

```java
try (ArchiveStateReader reader = readerFactory.open(point)) {
  ArchiveReadResult<AccountCapsule> account = reader.getAccount(address);
  long balance = account.isPresent() ? account.value().getBalance() : 0L;
  return ByteArray.toJsonHex(balance);
} catch (ArchiveReaderException e) {
  throw toJsonRpc(e);
}
```

语义：

- missing account -> `0x0`。
- archive disabled/gap/corrupted/codec error -> internal error，不转 `0x0`。
- 不调用 `account.importAllAsset()` 或 resource processor；historical balance 只读当时 `Account.balance`。

### 6.2 Code

```java
try (ArchiveStateReader reader = readerFactory.open(point)) {
  ArchiveReadResult<byte[]> code = reader.getCode(address);
  if (!code.isPresent() || code.value().length == 0) {
    return "0x";
  }
  return ByteArray.toJsonHex(code.value());
} catch (ArchiveReaderException e) {
  throw toJsonRpc(e);
}
```

语义：

- missing code -> `0x`。
- present empty runtime code -> `0x`，但 reader 层仍是 `PRESENT`。
- P0 `CODE` domain key 是 21-byte contract address，不是 code hash。

### 6.3 Storage

```java
byte[] slot32 = normalizeSlot(rawSlot);
try (ArchiveStateReader reader = readerFactory.open(point)) {
  ArchiveReadResult<byte[]> value = reader.getStorageValue(address, slot32);
  if (!value.isPresent()) {
    return ByteArray.toJsonHex(new byte[32]);
  }
  return ByteArray.toJsonHex(normalizeArchiveStorageValue(value.value()));
} catch (ArchiveReaderException e) {
  throw toJsonRpc(e);
}
```

Helper 语义：

```java
private byte[] normalizeSlot(byte[] rawSlot) throws JsonRpcInvalidParamsException {
  try {
    return new DataWord(rawSlot).getData();
  } catch (RuntimeException e) {
    throw new JsonRpcInvalidParamsException(e.getMessage());
  }
}

private byte[] normalizeArchiveStorageValue(byte[] rawValue)
    throws JsonRpcInternalException {
  try {
    return new DataWord(rawValue).getData();
  } catch (RuntimeException e) {
    throw new JsonRpcInternalException("corrupted archive storage value: " + e.getMessage());
  }
}
```

区别：

- `rawSlot` 来自用户输入，过长是 invalid params。
- `rawValue` 来自 archive，过长是 archive corruption/internal error。
- missing/tombstone storage -> 32-byte zero hex。
- present short value -> left-pad to 32 bytes。

### 6.4 Exception mapping

```java
private JsonRpcException toJsonRpc(ArchiveReaderException e) {
  switch (e.reason()) {
    case FUTURE_STATE:
    case UNSUPPORTED:
      return new JsonRpcInvalidParamsException(e.getMessage());
    case DISABLED:
    case HISTORY_UNAVAILABLE:
    case DOMAIN_NOT_ENABLED:
    case CODEC_ERROR:
    case CORRUPTED:
    default:
      return new JsonRpcInternalException(e.getMessage());
  }
}
```

如果 Java 编译不允许返回共同父类后再 `throw`，拆成：

```java
private void throwJsonRpc(ArchiveReaderException e)
    throws JsonRpcInvalidParamsException, JsonRpcInternalException
```

不要把 `HISTORY_UNAVAILABLE`、`DISABLED`、`CORRUPTED` 映射成默认值。

## 7. Patch 4：修改 `TronJsonRpc`

文件：

```text
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java
```

三个方法都增加 internal error annotation 和 throws。

`eth_getBalance`：

```java
@JsonRpcMethod("eth_getBalance")
@JsonRpcErrors({
    @JsonRpcError(exception = JsonRpcInvalidParamsException.class, code = -32602, data = "{}"),
    @JsonRpcError(exception = JsonRpcInternalException.class, code = -32000, data = "{}"),
})
String getTrxBalance(String address, String blockNumOrTag)
    throws JsonRpcInvalidParamsException, JsonRpcInternalException;
```

同样修改：

```text
eth_getStorageAt -> getStorageAt
eth_getCode      -> getABIOfSmartContract
```

注意：

- 不要重命名 `getABIOfSmartContract`。
- 不要修改 unrelated methods。
- 现有 direct Java tests 可能需要增加 `throws JsonRpcInternalException` 或 catch。

## 8. Patch 5：修改 `TronJsonRpcImpl` wiring

文件：

```text
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java
```

新增字段：

```java
private final ArchiveStatePointResolver archiveStatePointResolver;

@Autowired(required = false)
private ArchiveJsonRpcStateAdapter archiveJsonRpcStateAdapter;
```

constructor 初始化 resolver：

```java
@Autowired
public TronJsonRpcImpl(@Autowired NodeInfoService nodeInfoService, @Autowired Wallet wallet,
                       @Autowired Manager manager) {
  this.nodeInfoService = nodeInfoService;
  this.wallet = wallet;
  this.manager = manager;
  this.archiveStatePointResolver = new ArchiveStatePointResolver(wallet);
  this.sectionExecutor = ExecutorServiceManager.newFixedThreadPool(esName, 5);
}
```

测试 setter：

```java
@VisibleForTesting
void setArchiveJsonRpcStateAdapter(ArchiveJsonRpcStateAdapter adapter) {
  this.archiveJsonRpcStateAdapter = adapter;
}
```

如果需要 fake resolver 测试分支，也可以把 resolver 改为非 final 并加 setter；但推荐用真实 resolver 测 block parsing，adapter fake 只测 archive path 被调用。

Historical 分支需要 adapter：

```java
private ArchiveJsonRpcStateAdapter archiveAdapter()
    throws JsonRpcInternalException {
  if (archiveJsonRpcStateAdapter == null) {
    throw new JsonRpcInternalException("archive json-rpc state adapter is not available");
  }
  return archiveJsonRpcStateAdapter;
}
```

latest 分支不要调用 `archiveAdapter()`，保证普通 fullnode/latest 查询不受 archive bean 影响。

## 9. Patch 6：改 `eth_getBalance`

改前：

```java
if (EARLIEST_STR.equalsIgnoreCase(blockNumOrTag)
    || PENDING_STR.equalsIgnoreCase(blockNumOrTag)
    || FINALIZED_STR.equalsIgnoreCase(blockNumOrTag)) {
  throw new JsonRpcInvalidParamsException(TAG_NOT_SUPPORT_ERROR);
} else if (LATEST_STR.equalsIgnoreCase(blockNumOrTag)) {
  byte[] addressData = addressCompatibleToByteArray(address);
  ...
} else {
  ByteArray.hexToBigInteger(blockNumOrTag);
  throw new JsonRpcInvalidParamsException(QUANTITY_NOT_SUPPORT_ERROR);
}
```

改后：

```java
@Override
public String getTrxBalance(String address, String blockNumOrTag)
    throws JsonRpcInvalidParamsException, JsonRpcInternalException {
  ResolvedStatePoint resolved = archiveStatePointResolver.resolveBlockEnd(blockNumOrTag);
  byte[] addressData = addressCompatibleToByteArray(address);

  if (!resolved.isLatest()) {
    return archiveAdapter().getBalance(addressData, resolved.statePoint());
  }

  Account account = Account.newBuilder().setAddress(ByteString.copyFrom(addressData)).build();
  Account reply = wallet.getAccount(account);
  long balance = reply == null ? 0L : reply.getBalance();
  return ByteArray.toJsonHex(balance);
}
```

行为要求：

- `latest` 和旧实现结果一致。
- `0xN`、`N`、`earliest`、`finalized` 不再被当前 inline latest-only 判断拒绝。
- `pending` 仍 invalid params；`safe` 在当前源码基线下仍是 `invalid block number`。
- archive disabled 只影响 historical 分支。

## 10. Patch 7：改 `eth_getCode`

改后骨架：

```java
@Override
public String getABIOfSmartContract(String contractAddress, String blockNumOrTag)
    throws JsonRpcInvalidParamsException, JsonRpcInternalException {
  ResolvedStatePoint resolved = archiveStatePointResolver.resolveBlockEnd(blockNumOrTag);
  byte[] addressData = addressCompatibleToByteArray(contractAddress);

  if (!resolved.isLatest()) {
    return archiveAdapter().getCode(addressData, resolved.statePoint());
  }

  BytesMessage bytesMessage = BytesMessage.newBuilder()
      .setValue(ByteString.copyFrom(addressData))
      .build();
  SmartContractDataWrapper wrapper = wallet.getContractInfo(bytesMessage);
  if (wrapper == null) {
    return "0x";
  }
  return ByteArray.toJsonHex(wrapper.getRuntimecode().toByteArray());
}
```

行为要求：

- latest path 继续使用 `wallet.getContractInfo`。
- historical path 只读 `CODE` domain。
- missing historical code -> `0x`。
- present empty runtime code -> `0x`。

## 11. Patch 8：改 `eth_getStorageAt`

改后骨架：

```java
@Override
public String getStorageAt(String address, String storageIdx, String blockNumOrTag)
    throws JsonRpcInvalidParamsException, JsonRpcInternalException {
  ResolvedStatePoint resolved = archiveStatePointResolver.resolveBlockEnd(blockNumOrTag);
  byte[] addressByte = addressCompatibleToByteArray(address);

  if (!resolved.isLatest()) {
    byte[] rawSlot = parseStorageIndex(storageIdx);
    return archiveAdapter().getStorageAt(addressByte, rawSlot, resolved.statePoint());
  }

  BytesMessage bytesMessage = BytesMessage.newBuilder()
      .setValue(ByteString.copyFrom(addressByte))
      .build();
  SmartContract smartContract = wallet.getContract(bytesMessage);
  if (smartContract == null) {
    return ByteArray.toJsonHex(new byte[32]);
  }

  StorageRowStore store = manager.getStorageRowStore();
  Storage storage = new Storage(addressByte, store);
  storage.setContractVersion(smartContract.getVersion());
  storage.generateAddrHash(smartContract.getTrxHash().toByteArray());

  DataWord value = storage.getValue(new DataWord(ByteArray.fromHexString(storageIdx)));
  return ByteArray.toJsonHex(value == null ? new byte[32] : value.getData());
}
```

注意：

- latest path 保留原有 slot parse 时机：如果 contract missing，仍直接返回 zero，不因 slot 过长先失败。
- historical path 无法依赖 latest contract metadata，必须先规范 slot，再读 logical archive storage。
- historical path 不构造 `Storage`，不读 `StorageRowStore`。

建议新增 helper：

```java
private byte[] parseStorageIndex(String storageIdx) throws JsonRpcInvalidParamsException {
  try {
    return ByteArray.fromHexString(storageIdx);
  } catch (RuntimeException e) {
    throw new JsonRpcInvalidParamsException(e.getMessage());
  }
}
```

## 12. Patch 9：测试 `ArchiveStatePointResolver`

新增：

```text
framework/src/test/java/org/tron/core/archive/ArchiveStatePointResolverTest.java
```

建议使用 mock/fake `Wallet`，覆盖：

1. `latest` -> `ResolvedStatePoint.latest()`，不调用 `wallet.getBlockByNum`。
2. `earliest` -> block 0 end。
3. `finalized` -> `wallet.getSolidBlockNum()`。
4. hex quantity `0x10` -> block 16 end。
5. decimal quantity `16` -> block 16 end。
6. `pending` -> `TAG_PENDING_SUPPORT_ERROR`。
7. `safe` -> 当前基线下 `invalid block number`。
8. malformed `abc` -> `BLOCK_NUM_ERROR`。
9. negative/overflow -> `BLOCK_NUM_ERROR`。
10. future block -> invalid params `"block number is in the future"`。
11. resolved historical block header missing -> internal `"header not found"`。

## 13. Patch 10：测试 `ArchiveJsonRpcStateAdapter`

新增：

```text
framework/src/test/java/org/tron/core/services/jsonrpc/archive/ArchiveJsonRpcStateAdapterTest.java
```

建议 fake `ArchiveStateReaderFactory` + fake `ArchiveStateReader`，不要启动完整 node。

覆盖：

1. balance missing account -> `0x0`。
2. balance present account -> quantity hex。
3. code missing -> `0x`。
4. code present empty bytes -> `0x`。
5. code present bytes -> `0x...`。
6. storage missing -> 32-byte zero hex。
7. storage present one byte -> left-padded 32-byte hex。
8. storage raw slot 33 bytes -> `JsonRpcInvalidParamsException`。
9. storage archive value 33 bytes -> `JsonRpcInternalException`。
10. `ArchiveReaderException.DISABLED` -> internal。
11. `ArchiveReaderException.HISTORY_UNAVAILABLE` -> internal。
12. `ArchiveReaderException.CORRUPTED` -> internal。
13. `ArchiveReaderException.CODEC_ERROR` -> internal。
14. `ArchiveReaderException.UNSUPPORTED` -> invalid params。
15. reader is closed after each adapter call。

## 14. Patch 11：测试 `TronJsonRpcImpl` 三个 getter

可新增或改造：

```text
framework/src/test/java/org/tron/core/jsonrpc/JsonrpcServiceTest.java
framework/src/test/java/org/tron/core/jsonrpc/JsonRpcArchiveStateGetterTest.java
```

建议测试分层：

### 14.1 Latest path regression

保留现有 latest happy path：

- `getTrxBalance(addr, "latest")` 仍返回旧余额。
- `getStorageAt(accountAddr, "0x0", "latest")` 对非合约仍返回 32 zero bytes。
- `getABIOfSmartContract(accountAddr, "latest")` 仍返回 `0x`。

并加 spy/fake 验证 latest path 不调用 `ArchiveJsonRpcStateAdapter`。

### 14.2 Historical branch routing

用 fake adapter 覆盖：

- `getTrxBalance(addr, "0x1")` 调用 `adapter.getBalance(address21, StatePoint.blockEnd(1))`。
- `getTrxBalance(addr, "1")` 同样走 block 1，保留 decimal compatibility。
- `getTrxBalance(addr, "earliest")` 走 block 0。
- `getTrxBalance(addr, "finalized")` 走 solid block。
- `getABIOfSmartContract(addr, "0x1")` 调用 `adapter.getCode`。
- `getStorageAt(addr, "0x2", "0x1")` 调用 `adapter.getStorageAt`，slot raw 先传 adapter，由 adapter 归一化。
- `getStorageAt(addr, "0xGG", "0x1")` 抛 `JsonRpcInvalidParamsException`，且不调用 adapter。

### 14.3 Unsupported tags

改掉旧断言：

```text
pending -> invalid params，建议固定为当前 state getter 的 tag unsupported 文案或明确记录改为 pending-specific
safe    -> 当前基线下 invalid block number
```

不要继续期望当前源码不存在的 safe 专用错误常量。

### 14.4 Archive internal errors

fake adapter 抛：

- archive disabled -> `JsonRpcInternalException`。
- history unavailable -> `JsonRpcInternalException`。
- corrupted -> `JsonRpcInternalException`。

确认这些错误没有被映射为 `0x0`、`0x`、zero word。

### 14.5 Checked exception fallout

所有 direct Java 调用 tests 需要同步 throws/catch：

- `Create2Test` 里 latest `getStorageAt` 当前只 catch `JsonRpcInvalidParamsException`，S9 后要增加 `JsonRpcInternalException` 或让 test method throws。
- 其他 `new TronJsonRpcImpl(...)` 后调用三个 state getter 的 tests 同步处理 checked exception。

不要添加 `@Ignore`、assume、条件绕过或测试矩阵排除。

## 15. Existing `JsonrpcServiceTest` 迁移点

当前 `/Users/boson/IdeaProjects/java-tron/framework/src/test/java/org/tron/core/jsonrpc/JsonrpcServiceTest.java:491-572` 直接断言三个 state getter 的 `earliest/pending/finalized`，并在 `JsonrpcServiceTest.java:580-599` 覆盖 `eth_call` 同类 latest-only 行为：

```text
earliest/finalized/pending -> TAG_NOT_SUPPORT_ERROR
0x1 -> QUANTITY_NOT_SUPPORT_ERROR
```

S9 后应改成：

| 旧输入 | 新期望 |
| --- | --- |
| `earliest` | historical archive path；如果 archive disabled/fake adapter disabled，则 internal archive disabled |
| `finalized` | historical archive path；solid block end |
| `pending` | `TAG_PENDING_SUPPORT_ERROR` |
| `0x1` | historical archive path；不再 quantity unsupported |
| `abc` | 仍 `invalid block number` |
| `latest` | 仍 latest happy path |

新增 `safe` 用例时，当前基线期望是 `invalid block number`。

如果该集成测试没有注入 archive adapter，推荐把 historical branch 测试移到新的 focused test，通过 setter 注入 fake adapter；原集成测试只保留 latest regression 和 malformed block parser regression。

## 16. PR review checklist

- [ ] 三个 RPC method 的 interface 和 impl 都声明 `JsonRpcInternalException`。
- [ ] 三个 RPC method 的 `@JsonRpcErrors` 都包含 `JsonRpcInternalException -> -32000`。
- [ ] `eth_getCode` 的 Java 方法名仍是 `getABIOfSmartContract`，JSON method 名仍是 `eth_getCode`。
- [ ] 没有继续引用已不存在的统一 latest guard helper；`eth_call` 当前 inline latest-only 判断仍保留。
- [ ] `eth_call` 没有被错误接入 archive reader。
- [ ] `latest` 分支不调用 archive adapter。
- [ ] historical 分支不调用 `Wallet.getAccount/getContract/getContractInfo`。
- [ ] historical storage 不构造 `Storage`，不读 `StorageRowStore`。
- [ ] resolver 使用 `ByteArray.hexToBigInteger` 并补 long/负数校验，不直接用 `JsonRpcApiUtil.getByJsonBlockId`。
- [ ] decimal historical block number 仍可解析。
- [ ] `pending` 使用现有 `TAG_PENDING_SUPPORT_ERROR`；`safe` 在当前基线下仍是 `invalid block number`。
- [ ] archive disabled/gap/corrupt/codec error 不被默认值吞掉。
- [ ] storage 用户 slot 过长是 invalid params，archive value 过长是 internal error。
- [ ] direct-call tests 的 checked exception 声明已更新。
- [ ] 没有新增 test skip、`@Ignore` 或条件绕过。

## 17. 验收命令

定向测试：

```bash
./gradlew :framework:test --tests '*ArchiveStatePointResolverTest'
./gradlew :framework:test --tests '*ArchiveJsonRpcStateAdapterTest'
./gradlew :framework:test --tests '*JsonRpcArchiveStateGetterTest'
./gradlew :framework:test --tests 'org.tron.core.jsonrpc.JsonrpcServiceTest'
./gradlew :framework:test --tests 'org.tron.common.runtime.vm.Create2Test'
```

PR 级建议：

```bash
./gradlew :framework:test --tests '*JsonRpc*'
./gradlew :framework:test --tests '*Archive*'
./gradlew checkstyleMain
```

如果进入提交前检查，按 java-tron 仓库要求扩大到相关模块 test 和 checkstyle。

## 18. 与后续 PR 的边界

S9 完成后，PR8 historical `eth_call` 可以复用：

```text
StatePoint
ResolvedStatePoint
ArchiveStatePointResolver
ArchiveJsonRpcStateAdapter 的 exception mapping
ArchiveStateReaderFactory
```

但 PR8 仍需单独实现：

```text
archive-backed Repository
historical DynamicProperties view
TVM call overlay
TransactionContext historical block metadata
```

不要在 S9 为了复用三个 getter 的 resolver，把 object block selector 校验后静默转成 latest `eth_call`。这会制造错误的历史语义。
