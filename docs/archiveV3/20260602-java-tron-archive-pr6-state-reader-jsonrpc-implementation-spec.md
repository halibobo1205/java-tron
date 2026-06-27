# java-tron Archive PR6 StateReader/JSON-RPC 代码级实现规格

日期：2026-06-02

关联总文档：[java-tron 交易级状态树支持：Erigon V2/V3 模型调研](./20260520-java-tron-archive-state-erigon-v2-v3-research.md)

关联实现蓝图：[java-tron Archive 状态树实现蓝图](./20260602-java-tron-archive-implementation-blueprint.md)

前置 PR5 规格：[java-tron Archive PR5 TemporalStore 代码级实现规格](./20260602-java-tron-archive-pr5-temporal-store-implementation-spec.md)

S8 ArchiveStateReader core 编码执行包：[java-tron Archive S8：ArchiveStateReader Core 编码执行包](./20260602-java-tron-archive-s8-state-reader-core-coding-packet.md)

S9 JSON-RPC historical getters 编码执行包：[java-tron Archive S9：JSON-RPC Historical Getters 编码执行包](./20260602-java-tron-archive-s9-jsonrpc-historical-getters-coding-packet.md)

后续 PR8 规格：[java-tron Archive PR8 historical eth_call 代码级实现规格](./20260602-java-tron-archive-pr8-historical-eth-call-implementation-spec.md)

逐文件 Patch 清单：[java-tron Archive 模块 05：ArchiveStateReader 逐文件 Patch 清单](./20260602-java-tron-archive-module-05-state-reader-patch-checklist.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 旧文档原始基线：`a79693e450`，当前 4e80 实现请看 2026-06-03 细化文档。

## 1. PR6 目标

PR6 把 PR1-PR5 已经建立的 `txNum/domain/write-set/temporal history` 闭环接到业务读接口。范围必须收敛，不在这一 PR 中尝试历史 VM 执行。

本 PR 做：

1. 新增 `ArchiveStateReader` 和 `ArchiveStateReaderFactory`。
2. 新增 JSON-RPC block 参数到 `StatePoint/asOfTxNum` 的解析器。
3. 接入历史 `eth_getBalance`。
4. 接入历史 `eth_getCode`。
5. 接入历史 `eth_getStorageAt`。
6. 保持 latest 路径走现有 `Wallet/Manager/Store`，避免 archive 读路径影响普通 fullnode 行为。

本 PR 不做：

1. 历史 `eth_call`。
2. 历史 `eth_estimateGas`。
3. `eth_getProof`。
4. overlay reader。
5. transaction-level JSON-RPC 扩展接口。
6. commitment/proof reader。

`eth_call` 的历史执行需要 `ArchiveRepositoryAdapter`、historical dynamic properties 和 `VMActuator` 注入点，这会触碰 TVM 执行上下文，放到 PR8。

## 2. 源码事实

### 2.1 JSON-RPC 当前限制

| 文件 | 位置 | 当前行为 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java` | `eth_getBalance` | 只声明 `JsonRpcInvalidParamsException` |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java` | `eth_getStorageAt` | 只声明 `JsonRpcInvalidParamsException` |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java` | `eth_getCode` | 只声明 `JsonRpcInvalidParamsException` |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java:155-167` | tag/error 常量 | 只有 `earliest/pending/latest/finalized`，无 `safe` 常量；tag unsupported error 是 private |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java:394-419` | `getTrxBalance` | 方法内联 latest-only 判断，再走 `wallet.getAccount` |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java:536-568` | `getStorageAt` | 方法内联 latest-only 判断，再读 latest `ContractStore/StorageRowStore/Storage` |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java:572-599` | `getABIOfSmartContract` | 方法内联 latest-only 判断，再 `wallet.getContractInfo` |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java:1001-1044` | `getCall` | string block 参数仍 latest-only；object block 参数校验存在后强制改成 `latest` |

PR6 的核心改造就是把三个 state getter 的内联 latest-only 判断拆成：

```text
latest       -> 保持现有 latest path
historical   -> ArchiveStateReader path
unsupported  -> 明确错误
```

不要让历史参数静默退化为 latest。

### 2.2 block tag 解析能力

| 文件 | 位置 | 事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/services/jsonrpc/JsonRpcApiUtil.java:518-531` | `getByJsonBlockId` | `pending` 抛 `TAG pending not supported`；empty/latest 返回 `-1`；earliest 返回 `0`；finalized 返回 solid；其他走严格 `jsonHexToLong` |
| `/Users/boson/IdeaProjects/java-tron/common/src/main/java/org/tron/common/utils/ByteArray.java:146-151` | `hexToBigInteger` | 带 `0x` 前缀按 hex，裸字符串按 decimal |
| `/Users/boson/IdeaProjects/java-tron/common/src/main/java/org/tron/common/utils/ByteArray.java:154-159` | `jsonHexToLong` | 要求 `0x` 前缀 |

PR6 的 state getter resolver 应复用当前 direct getter 的 quantity 语义：`ByteArray.hexToBigInteger` 加负数/long overflow 校验。不要直接使用 `getByJsonBlockId` 作为主 parser，否则裸 decimal historical 查询会与当前 state getter 校验行为不一致。

### 2.3 Wallet latest 读路径不能直接复用于历史

| 文件 | 位置 | 事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/Wallet.java:337-350` | `getAccount` | 从 latest `AccountStore` 读账户，并会调用资源处理器更新动态用量视图 |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/Wallet.java:3205-3224` | `getContract` | 从 latest `AccountStore/ContractStore/AbiStore` 读合约 |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/Wallet.java:3234-3268` | `getContractInfo` | 从 latest `AccountStore/ContractStore/AbiStore/CodeStore/ContractStateStore` 读合约、runtime code 和 contract state |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/Wallet.java:3112-3145` | `triggerConstantContract` | 历史 `eth_call` 不能简单复用，合约存在性从 latest `ContractStore` 判断 |

因此 PR6 的历史 `eth_getBalance/code/storage` 必须直接读 archive history，不应调用 `Wallet.getAccount/getContract/getContractInfo`。

### 2.4 Capsule 解码能力

| 文件 | 构造器 | 用途 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/capsule/AccountCapsule.java:64` | `new AccountCapsule(byte[])` | archive `ACCOUNT` value -> Account |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/capsule/ContractCapsule.java:47` | `new ContractCapsule(byte[])` | archive `CONTRACT` value -> SmartContract |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/capsule/CodeCapsule.java:28` | `new CodeCapsule(byte[])` | archive `CODE` value -> raw runtime code |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/capsule/StorageRowCapsule.java:50` | `new StorageRowCapsule(byte[])` | physical storage row value wrapper |
| `/Users/boson/IdeaProjects/java-tron/common/src/main/java/org/tron/common/runtime/vm/DataWord.java:83` | `new DataWord(byte[])` | JSON-RPC slot/value 32-byte normalization |

PR6 的 reader 可以先返回 capsule 或 raw bytes，不需要引入新的 protobuf。

## 3. 语义边界

### 3.1 StatePoint 到 asOfTxNum

PR5 的 `GetAsOf(domain, key, asOfTxNum)` 是 exclusive 读语义：

```text
返回 asOfTxNum 之前最后一个已提交状态
```

因此 PR6 的 JSON-RPC block state 应统一映射到 `BLOCK_END(blockNum)`：

```text
BLOCK_END(blockNum) -> asOfTxNum = blockRange.lastTxNum + 1
```

其他内部 state point：

| StatePoint | asOfTxNum |
| --- | --- |
| `LATEST` | `archiveProgress.nextTxNum` |
| `BLOCK_BEFORE(blockNum)` | `blockRange.firstTxNum` |
| `BLOCK_END(blockNum)` | `blockRange.lastTxNum + 1` |
| `TX_BEFORE(blockNum, txIndex)` | 该 tx 的 `txNum` |
| `TX_AFTER(blockNum, txIndex)` | 该 tx 的 `txNum + 1` |
| `SYSTEM_AFTER(blockNum, phase)` | 对应 system tx 的 `txNum + 1` |

PR6 的 JSON-RPC 只暴露 `LATEST` 和 `BLOCK_END`。`TX_BEFORE/TX_AFTER` 保留给后续 debug/proof API。

### 3.2 block tag 支持策略

| 输入 | PR6 行为 |
| --- | --- |
| `latest` | 走现有 latest path，不经过 archive |
| `earliest` | 解析为 block `0`，走 `BLOCK_END(0)` |
| `finalized` | 解析为 `wallet.getSolidBlockNum()`，走 `BLOCK_END(solidBlockNum)` |
| hex quantity，如 `0x10` | 解析为 block `16`，走 `BLOCK_END(16)` |
| decimal quantity，如 `16` | 复用 `ByteArray.hexToBigInteger` 的裸 decimal 语义，走 `BLOCK_END(16)` |
| `pending` | 继续 invalid params；推荐使用当前已有的 `TAG_PENDING_SUPPORT_ERROR`，因为 PR6 支持 `earliest/finalized` 后 generic tag 文案会误导 |
| `safe` | 当前源码无 `safe` 常量，继续按 malformed quantity 报 `invalid block number`，除非同 PR 明确新增 safe tag 支持和测试 |
| future block | `JsonRpcInvalidParamsException("block number is in the future")` |
| archive 未覆盖的历史块 | `JsonRpcInternalException("archive history is not available for block: ...")` |
| archive 未开启 | `JsonRpcInternalException("archive is not enabled")` |

`archive disabled/gap/corrupted` 不是参数格式错误。PR6 应给 `eth_getBalance/code/storage` 增加 `JsonRpcInternalException` 的声明和 `@JsonRpcError(code = -32000)` 映射。

### 3.3 blockHash 参数

当前 `eth_getBalance/code/storage` 接口签名第三个参数是 `String blockNumOrTag`，不是 EIP-1898 object。PR6 不扩展该接口形态。

如果用户传入 64-byte hash 字符串：

```text
0xabc...
```

现有 state getter 会先用 `ByteArray.hexToBigInteger` 校验，64-byte `0x...` 会解析成大整数。PR6 resolver 应在转 long 前显式做 overflow 校验并报 `BLOCK_NUM_ERROR`，不把 string hash 当作 blockHash。

历史 `eth_call` object 参数在 PR8 处理。PR6 可以额外修复一个兼容性风险：当 `eth_call` 收到 object block 参数并且不是 latest 时，明确返回“historical eth_call is not supported”，不要校验后强制改成 latest。但这个改动会改变已有行为，建议作为 PR6 的可选小项，默认留到 PR8。

## 4. 新增/调整类

### 4.1 chainbase：StatePoint

建议放在：

```text
chainbase/src/main/java/org/tron/core/archive/StatePoint.java
```

Java 版本如果不适合 sealed interface，先用不可变 final class + enum：

```java
public final class StatePoint {
  public enum Kind {
    LATEST,
    BLOCK_BEFORE,
    BLOCK_END,
    TX_BEFORE,
    TX_AFTER,
    SYSTEM_AFTER
  }

  private final Kind kind;
  private final long blockNum;
  private final int txIndex;
  private final ArchivePhase phase;

  public static StatePoint latest();
  public static StatePoint blockBefore(long blockNum);
  public static StatePoint blockEnd(long blockNum);
  public static StatePoint txBefore(long blockNum, int txIndex);
  public static StatePoint txAfter(long blockNum, int txIndex);
  public static StatePoint systemAfter(long blockNum, ArchivePhase phase);
}
```

约束：

- constructor 私有。
- 参数校验在 factory method 内做。
- `LATEST` 不允许携带 blockNum。
- `TX_*` 要求 `txIndex >= 0`。
- `SYSTEM_AFTER` 要求 `phase != USER_TX`。

### 4.2 chainbase：ArchiveReaderException

建议放在：

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveReaderException.java
```

```java
public class ArchiveReaderException extends Exception {
  private final Reason reason;

  public enum Reason {
    DISABLED,
    FUTURE_STATE,
    HISTORY_UNAVAILABLE,
    DOMAIN_NOT_ENABLED,
    CODEC_ERROR,
    CORRUPTED,
    UNSUPPORTED
  }
}
```

RPC 层负责把它映射成 `JsonRpcInvalidParamsException` 或 `JsonRpcInternalException`：

| Reason | RPC exception |
| --- | --- |
| `FUTURE_STATE` | `JsonRpcInvalidParamsException` |
| `UNSUPPORTED` | `JsonRpcInvalidParamsException` |
| `DISABLED` | `JsonRpcInternalException` |
| `HISTORY_UNAVAILABLE` | `JsonRpcInternalException` |
| `DOMAIN_NOT_ENABLED` | `JsonRpcInternalException` |
| `CODEC_ERROR` | `JsonRpcInternalException` |
| `CORRUPTED` | `JsonRpcInternalException` |

### 4.3 chainbase：ArchiveReadResult

建议放在：

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveReadResult.java
```

`ArchiveStateReader` 不直接用 `null` 表示 archive object 缺失。缺失、tombstone、present empty bytes 都需要被区分，尤其是 `CODE` 的 empty bytecode 和 `CONTRACT_STORAGE` 的 zero-like value。

建议接口：

```java
public final class ArchiveReadResult<T> {
  public enum Status {
    PRESENT,
    MISSING
  }

  public Status status();

  public boolean isPresent();

  public T value();

  public static <T> ArchiveReadResult<T> present(T value);

  public static <T> ArchiveReadResult<T> missing();
}
```

约束：

- `present(null)` 必须抛 `IllegalArgumentException`。
- `missing().value()` 必须抛 `IllegalStateException`。
- `byte[]` 类型的 present value 由 reader 返回 defensive copy。
- JSON-RPC adapter 负责把 missing 转成 ETH-compatible 默认值，reader core 不提前补默认值。

### 4.4 chainbase：ArchiveStateReader

建议放在：

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

  ArchiveReadResult<byte[]> getStorageValue(byte[] address, byte[] slot) throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getRaw(ArchiveDomain domain, byte[] key) throws ArchiveReaderException;

  @Override
  void close();
}
```

实现：

```text
DefaultArchiveStateReader
  -> ArchiveTemporalStore.getAsOf(domain, canonicalKey, asOfTxNum)
  -> ArchiveDomainRegistry codec
  -> capsule/raw value
```

返回语义：

| 方法 | missing/tombstone |
| --- | --- |
| `getAccount` | `ArchiveReadResult.missing()` |
| `getContract` | `ArchiveReadResult.missing()` |
| `getCode` | `ArchiveReadResult.missing()`；present empty bytes 仍是 `PRESENT`，RPC adapter 统一转 `0x` |
| `getStorageValue` | `ArchiveReadResult.missing()`；RPC adapter 统一转 32-byte zero |
| `getRaw` | `ArchiveReadResult.missing()` |

`getStorageValue(address, slot)` 的 key 必须使用 PR4 semantic storage hook 捕获的逻辑 key：

```text
canonicalKey = address || normalizedSlot32
```

不要在 reader 里复用 `Storage.compose(key, addrHash)`，因为：

1. `Storage` 的 physical key 依赖合约 version。
2. CREATE2 路径会受 `generateAddrHash(trxId)` 影响。
3. 历史 reader 查询的是 logical storage slot，不应依赖 latest contract metadata。

### 4.5 chainbase：ArchiveStateReaderFactory

建议放在：

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStateReaderFactory.java
```

接口：

```java
public interface ArchiveStateReaderFactory {
  ArchiveStateReader open(StatePoint statePoint) throws ArchiveReaderException;
}
```

默认实现：

```text
DefaultArchiveStateReaderFactory
  dependencies:
    ArchiveConfig
    ArchiveTxNumIndex
    ArchiveTemporalStore
    ArchiveDomainRegistry
    ArchiveTemporalStore.progress()

  open(point):
    if disabled -> DISABLED
    progressSnapshot = temporalStore.progress()
    asOfTxNum = resolve(point, progressSnapshot)
    return new DefaultArchiveStateReader(...)
```

`resolve(point)` 规则：

```java
switch (point.kind()) {
  case LATEST:
    return progress.nextTxNum();
  case BLOCK_END:
    BlockTxNumRange range = txNumIndex.getBlockRange(point.blockNum());
    return range.lastTxNum() + 1;
  case BLOCK_BEFORE:
    BlockTxNumRange range = txNumIndex.getBlockRange(point.blockNum());
    return range.firstTxNum();
  case TX_BEFORE:
    return txNumIndex.getTxNum(point.blockNum(), point.txIndex());
  case TX_AFTER:
    return txNumIndex.getTxNum(point.blockNum(), point.txIndex()) + 1;
  case SYSTEM_AFTER:
    return txNumIndex.getSystemTxNum(point.blockNum(), point.phase()) + 1;
}
```

PR6 必须校验：

- `point.blockNum() <= progress.appliedBlockNum()`。
- `BlockTxNumRange` 存在。
- block hash 如果上层传入过，必须匹配 canonical block hash；PR6 string 参数暂不传 hash。
- `asOfTxNum <= progress.nextTxNum()`。

### 4.5 framework：ArchiveStatePointResolver

建议放在：

```text
framework/src/main/java/org/tron/core/archive/ArchiveStatePointResolver.java
```

职责：

```text
JSON-RPC blockNumOrTag
  -> latest? boolean
  -> StatePoint
  -> block existence/future check
```

接口：

```java
public final class ArchiveStatePointResolver {
  private final Wallet wallet;

  public ArchiveStatePointResolver(Wallet wallet);

  public ResolvedStatePoint resolveBlockEnd(String blockNumOrTag)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;
}
```

返回对象：

```java
public final class ResolvedStatePoint {
  private final boolean latest;
  private final StatePoint statePoint;
  private final long blockNum;
}
```

实现细节：

1. `latest` 直接返回 `ResolvedStatePoint.latest()`。
2. `earliest` 返回 block `0`。
3. `finalized` 返回 `wallet.getSolidBlockNum()`。
4. `pending` 返回 `JsonRpcInvalidParamsException(TAG_PENDING_SUPPORT_ERROR)`。
5. 其他输入用 `ByteArray.hexToBigInteger(blockNumOrTag)`；`0x` 前缀按 hex，裸字符串按 decimal。
6. quantity 解析后校验 `>= 0` 且 `<= Long.MAX_VALUE`。
7. 当前源码无 `safe` 常量；`safe` 会落入 quantity 分支并报 `invalid block number`。
8. head 高度用 `wallet.getNowBlock().getBlockHeader().getRawData().getNumber()`，不要调用当前 `Wallet` 不存在的 `getHeadBlockNum()`。
9. `blockNum > head` 返回 future error。
10. `wallet.getBlockByNum(blockNum) == null` 返回 `JsonRpcInternalException("header not found")`。
11. 返回 `StatePoint.blockEnd(blockNum)`。

注意 `latest` 不应该解析成 `BLOCK_END(head)`，否则 latest RPC 会被 archive progress 限制，也会改变没有 archive 的节点行为。

### 4.6 framework：ArchiveJsonRpcStateAdapter

建议放在：

```text
framework/src/main/java/org/tron/core/services/jsonrpc/archive/ArchiveJsonRpcStateAdapter.java
```

接口：

```java
public final class ArchiveJsonRpcStateAdapter {
  public String getBalance(byte[] address, StatePoint point)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;

  public String getCode(byte[] address, StatePoint point)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;

  public String getStorageAt(byte[] address, byte[] slot, StatePoint point)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;
}
```

实现：

```java
try (ArchiveStateReader reader = readerFactory.open(point)) {
  ArchiveReadResult<AccountCapsule> account = reader.getAccount(address);
  return ByteArray.toJsonHex(account.isPresent() ? account.value().getBalance() : 0L);
}
```

`eth_getCode`：

```java
ArchiveReadResult<byte[]> code = reader.getCode(address);
if (!code.isPresent() || code.value().length == 0) {
  return "0x";
}
return ByteArray.toJsonHex(code.value());
```

`eth_getStorageAt`：

```java
byte[] normalizedSlot = new DataWord(rawSlot).getData();
ArchiveReadResult<byte[]> value = reader.getStorageValue(address, normalizedSlot);
return ByteArray.toJsonHex(value.isPresent()
    ? new DataWord(value.value()).getData()
    : new byte[32]);
```

异常映射：

```java
private JsonRpcException toJsonRpc(ArchiveReaderException e) {
  switch (e.reason()) {
    case FUTURE_STATE:
    case UNSUPPORTED:
      return new JsonRpcInvalidParamsException(e.getMessage());
    default:
      return new JsonRpcInternalException(e.getMessage());
  }
}
```

因为 Java 不允许一个方法同时根据 runtime 分支抛不同 checked exception 而不声明，adapter 方法需要同时声明 `JsonRpcInvalidParamsException` 和 `JsonRpcInternalException`，或者拆成内部 helper 后在 RPC method 中转换。

## 5. TronJsonRpcImpl 改造

### 5.1 依赖注入

在 `TronJsonRpcImpl` 增加 resolver 和 adapter。Resolver 只依赖 `Wallet`，建议在 constructor 中构造，保证现有 `new TronJsonRpcImpl(...)` 测试对 malformed block、latest path 不依赖 Spring 注入：

```java
private final ArchiveStatePointResolver archiveStatePointResolver;

@Autowired(required = false)
private ArchiveJsonRpcStateAdapter archiveJsonRpcStateAdapter;
```

```java
this.archiveStatePointResolver = new ArchiveStatePointResolver(wallet);
```

Adapter 只在 historical 分支需要。`latest` path 不应访问 adapter；如果 historical 查询时 adapter 缺失，返回 `JsonRpcInternalException("archive json-rpc state adapter is not available")`。如果 archive 默认关闭但 bean 存在，底层 readerFactory 在历史查询时返回 `DISABLED`。

### 5.2 接口签名和错误映射

`TronJsonRpc.java` 中三个方法增加 internal error 映射：

```java
@JsonRpcMethod("eth_getBalance")
@JsonRpcErrors({
    @JsonRpcError(exception = JsonRpcInvalidParamsException.class, code = -32602, data = "{}"),
    @JsonRpcError(exception = JsonRpcInternalException.class, code = -32000, data = "{}"),
})
String getTrxBalance(String address, String blockNumOrTag)
    throws JsonRpcInvalidParamsException, JsonRpcInternalException;
```

`eth_getStorageAt` 和 `eth_getCode` 同样调整。`JsonRpcInternalException` 继承 `TronException`，是 checked exception，所以 interface 和 implementation 都必须同步改签名。

### 5.3 eth_getBalance

当前逻辑：

```java
if (EARLIEST_STR.equalsIgnoreCase(blockNumOrTag)
    || PENDING_STR.equalsIgnoreCase(blockNumOrTag)
    || FINALIZED_STR.equalsIgnoreCase(blockNumOrTag)) {
  throw new JsonRpcInvalidParamsException(TAG_NOT_SUPPORT_ERROR);
} else if (LATEST_STR.equalsIgnoreCase(blockNumOrTag)) {
  byte[] addressData = addressCompatibleToByteArray(address);
  Account reply = wallet.getAccount(account);
} else {
  ByteArray.hexToBigInteger(blockNumOrTag);
  throw new JsonRpcInvalidParamsException(QUANTITY_NOT_SUPPORT_ERROR);
}
```

建议改为：

```java
ResolvedStatePoint resolved = archiveStatePointResolver.resolveBlockEnd(blockNumOrTag);
byte[] addressData = addressCompatibleToByteArray(address);

if (resolved.isLatest()) {
  Account account = Account.newBuilder().setAddress(ByteString.copyFrom(addressData)).build();
  Account reply = wallet.getAccount(account);
  return ByteArray.toJsonHex(reply == null ? 0L : reply.getBalance());
}

return archiveJsonRpcStateAdapter.getBalance(addressData, resolved.statePoint());
```

注意：

- latest path 必须保留 `wallet.getAccount` 的现有行为。
- historical path 不调用 `wallet.getAccount`。
- historical balance 只返回 raw historical `Account.balance`，不动态重算资源使用。

### 5.4 eth_getCode

当前逻辑：

```java
if latest:
  SmartContractDataWrapper contractDataWrapper = wallet.getContractInfo(bytesMessage);
  return runtimecode or "0x";
else:
  reject tag/quantity as described above
```

建议改为：

```java
ResolvedStatePoint resolved = archiveStatePointResolver.resolveBlockEnd(bnOrId);
byte[] addressData = addressCompatibleToByteArray(contractAddress);

if (resolved.isLatest()) {
  BytesMessage bytesMessage = BytesMessage.newBuilder()
      .setValue(ByteString.copyFrom(addressData))
      .build();
  SmartContractDataWrapper wrapper = wallet.getContractInfo(bytesMessage);
  return wrapper == null ? "0x" : ByteArray.toJsonHex(wrapper.getRuntimecode().toByteArray());
}

return archiveJsonRpcStateAdapter.getCode(addressData, resolved.statePoint());
```

历史 code 读取 `CODE` domain，不依赖 latest `ContractStore`。如果 code 缺失，返回 `0x`。

### 5.5 eth_getStorageAt

当前逻辑：

```java
if latest:
  SmartContract smartContract = wallet.getContract(bytesMessage);
  Storage storage = new Storage(addressByte, store);
  storage.setContractVersion(smartContract.getVersion());
  storage.generateAddrHash(smartContract.getTrxHash().toByteArray());
  DataWord value = storage.getValue(new DataWord(ByteArray.fromHexString(storageIdx)));
else:
  reject tag/quantity as described above
```

建议改为：

```java
ResolvedStatePoint resolved = archiveStatePointResolver.resolveBlockEnd(blockNumOrTag);
byte[] addressByte = addressCompatibleToByteArray(address);

if (resolved.isLatest()) {
  // 保留现有 latest Storage path
}

return archiveJsonRpcStateAdapter.getStorageAt(
    addressByte,
    parseStorageIndex(storageIdx),
    resolved.statePoint());
```

`parseStorageIndex(storageIdx)` 应捕获 `ByteArray.fromHexString` 的 unchecked decode error，并转成 `JsonRpcInvalidParamsException`。

historical path 中：

```text
storageIdx raw input
  -> new DataWord(raw).getData()
  -> canonicalKey = address21 || slot32 || storageKeyVersion_u8
  -> CONTRACT_STORAGE getAsOf
  -> ArchiveReadResult.missing() -> 32 zero bytes
```

不要构造 `Storage` 或访问 `StorageRowStore`。PR4 已经把 storage 语义写入捕获为 logical key，PR6 必须通过 registry/query codec 消费这个 logical key；`storageKeyVersion_u8` 由目标历史点的 contract storage key semantics 决定。

## 6. ArchiveStateReader 实现细节

### 6.1 `getAsOf` 调用

PR5 的 temporal store 已有：

```java
byte[] getAsOf(ArchiveDomain domain, byte[] canonicalKey, long asOfTxNum)
```

PR6 reader 应只做很薄的一层：

```java
byte[] encoded = temporalStore.getAsOf(domain, key, asOfTxNum);
if (encoded == null) {
  return ArchiveReadResult.missing();
}
byte[] raw = ArchiveValueCodec.decode(encoded);
return ArchiveReadResult.present(domainCodec.decode(raw));
```

如果 PR5 的 store 已经在 `getAsOf` 内完成 tombstone decode，PR6 不重复 decode；但必须在接口文档中固定 `getAsOf` missing/tombstone 映射为 `ArchiveReadResult.missing()`。

### 6.2 canonical key

| Domain | key |
| --- | --- |
| `ACCOUNT` | `address` |
| `CONTRACT` | `address` |
| `CODE` | `address` |
| `CONTRACT_STORAGE` | `address21 || slot32 || storageKeyVersion_u8` |
| `DYNAMIC_PROPERTIES` | raw property key |

PR6 RPC 只使用前四个。`DYNAMIC_PROPERTIES` 给 PR8 historical `eth_call` 预留。

### 6.3 value 解码

| Domain | raw value | reader 返回 |
| --- | --- | --- |
| `ACCOUNT` | `Account` protobuf bytes | `new AccountCapsule(raw)` |
| `CONTRACT` | `SmartContract` protobuf bytes | `new ContractCapsule(raw)` |
| `CODE` | runtime code bytes | raw bytes |
| `CONTRACT_STORAGE` | 32-byte storage value | raw bytes / `DataWord` |
| `DYNAMIC_PROPERTIES` | `BytesCapsule` bytes | raw bytes |

不要在 PR6 引入 JSON 序列化、Java serialization 或二次 protobuf 包装。

### 6.4 同一请求读一致性

`ArchiveStateReaderFactory.open(point)` 必须捕获一次 progress snapshot：

```text
appliedBlockNum
appliedBlockHash
nextTxNum
registryChecksum
```

同一个 `ArchiveStateReader` 内所有 `get*` 使用同一个 `asOfTxNum`。不要每次 `get*` 重新解析 latest/progress。

### 6.5 与 startup verifier 的关系

如果 PR5 startup verifier 标记 archive 状态为：

| 状态 | reader 行为 |
| --- | --- |
| `EMPTY` | 历史查询返回 `HISTORY_UNAVAILABLE` |
| `OK` | 正常 |
| `ARCHIVE_GAP` | 查询 gap 区间返回 `HISTORY_UNAVAILABLE` |
| `REPAIR_REQUIRED` | 所有历史查询返回 `CORRUPTED` |

PR6 不负责 repair，但错误必须可区分，便于 RPC、日志、运维判断。

## 7. 历史 eth_call 的处理

PR6 不实现历史 `eth_call`，原因：

1. `Wallet.triggerConstantContract` 当前走 latest `StoreFactory.getInstance()`。
2. `TransactionContext` 构造依赖 latest block capsule、latest store、latest dynamic properties。
3. TVM 内部会读 `RepositoryImpl`，需要 archive-backed repository 才能保证不混入 latest state。
4. `eth_call` 需要 overlay isolation，不能把模拟写入提交到 archive/latest store。

建议 PR6 对 `eth_call` 只做文档化和测试保护：

| 输入 | PR6 行为 |
| --- | --- |
| `"latest"` | 保持现有行为 |
| `eth_call` historical string quantity/tag | 仍由当前 inline latest-only 判断拒绝 |
| object `{blockNumber: ...}` | 可选：改为明确拒绝 historical，而不是强制 latest |
| object `{blockHash: ...}` | 可选：改为明确拒绝 historical，而不是强制 latest |

如果担心兼容性，PR6 不改 `eth_call` 行为，但必须在 release note/文档中说明：本 PR 只支持三个 state getter 的历史 block 查询。

## 8. 测试设计

### 8.1 Unit：StatePointResolver

新增：

```text
framework/src/test/java/org/tron/core/archive/ArchiveStatePointResolverTest.java
```

覆盖：

1. `latest` 返回 `isLatest=true`。
2. `earliest` 返回 `StatePoint.blockEnd(0)`。
3. `finalized` 使用 `wallet.getSolidBlockNum()`。
4. `0x10` 返回 block `16`。
5. `16` 返回 block `16`，沿用 `ByteArray.hexToBigInteger` 当前 decimal 支持。
6. `pending` 抛 `JsonRpcInvalidParamsException(TAG_PENDING_SUPPORT_ERROR)`。
7. `safe` 在当前基线下抛 `JsonRpcInvalidParamsException("invalid block number")`。
8. future block 抛 invalid params。
9. blockStore 查不到已解析 block 时抛 internal `header not found`。

### 8.2 Unit：ArchiveStateReader

新增：

```text
chainbase/src/test/java/org/tron/core/archive/reader/ArchiveStateReaderTest.java
```

覆盖：

1. account missing -> `ArchiveReadResult.missing()`。
2. account present -> `AccountCapsule.balance` 正确。
3. code missing -> `ArchiveReadResult.missing()`；present empty bytes 仍为 `PRESENT`。
4. storage missing -> `ArchiveReadResult.missing()`，adapter 返回 zero word。
5. storage slot 短 hex 输入通过 `DataWord` 左填充到 32 bytes。
6. tombstone -> `ArchiveReadResult.missing()`。
7. `BLOCK_END(N)` off-by-one：block N 最后一笔 tx 修改后可见。
8. `BLOCK_BEFORE(N+1)` 等于 `BLOCK_END(N)`。
9. 同一 key 同一 block 多次修改，`TX_AFTER(tx1)` 和 `TX_AFTER(tx2)` 区分正确。

### 8.3 Unit：ArchiveJsonRpcStateAdapter

新增：

```text
framework/src/test/java/org/tron/core/services/jsonrpc/archive/ArchiveJsonRpcStateAdapterTest.java
```

覆盖：

1. `getBalance` missing account -> `0x0`。
2. `getBalance` present account -> quantity hex。
3. `getCode` missing -> `0x`。
4. `getCode` present -> `0x...`。
5. `getStorageAt` missing -> 32-byte zero hex。
6. `getStorageAt` present short value -> 32-byte normalized hex。
7. `ArchiveReaderException.DISABLED` -> `JsonRpcInternalException`。
8. `ArchiveReaderException.HISTORY_UNAVAILABLE` -> `JsonRpcInternalException`。

### 8.4 Integration：JSON-RPC history

新增或扩展：

```text
framework/src/test/java/org/tron/core/jsonrpc/JsonRpcArchiveStateTest.java
```

覆盖：

1. archive disabled：
   - `eth_getBalance(addr, "latest")` 仍成功。
   - `eth_getBalance(addr, "0x1")` 返回 archive disabled internal error。
2. historical balance：
   - block 1 给 A 余额。
   - block 2 修改 A 余额。
   - `eth_getBalance(A, "0x1")` 返回 block 1 结束余额。
   - `eth_getBalance(A, "latest")` 返回 latest 余额。
3. historical code：
   - block N 前合约不存在 -> `0x`。
   - deploy 后 block -> runtime code。
4. historical storage：
   - deploy 后写 slot。
   - 下一个 block 改 slot。
   - 查询两个 block 返回不同值。
   - malformed slot hex 返回 `JsonRpcInvalidParamsException`，不调用 archive adapter。
5. latest path unchanged：
   - mock/spy 确认 latest 仍走 Wallet 或结果与旧测试一致。
6. archive gap：
   - 删除或构造缺失 block range。
   - 查询 gap block 返回 `HISTORY_UNAVAILABLE`。

### 8.5 不要添加 skip

如果遇到现有 JSON-RPC 测试因为签名增加 `JsonRpcInternalException` 编译失败，直接修 test method throws 声明。不要添加 `@Ignore`、条件跳过或测试矩阵绕过。

## 9. 代码落点清单

### 9.1 chainbase

新增：

```text
chainbase/src/main/java/org/tron/core/archive/StatePoint.java
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveReaderException.java
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStateReader.java
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStateReaderFactory.java
chainbase/src/main/java/org/tron/core/archive/reader/DefaultArchiveStateReader.java
chainbase/src/main/java/org/tron/core/archive/reader/DefaultArchiveStateReaderFactory.java
```

依赖 PR5 已有：

```text
ArchiveTemporalStore
ArchiveTxNumIndex
ArchiveDomainRegistry
ArchiveDomain
ArchiveValueCodec
ArchiveProgress
```

### 9.2 framework

新增：

```text
framework/src/main/java/org/tron/core/archive/ArchiveStatePointResolver.java
framework/src/main/java/org/tron/core/services/jsonrpc/archive/ArchiveJsonRpcStateAdapter.java
```

修改：

```text
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java
```

### 9.3 测试

新增：

```text
chainbase/src/test/java/org/tron/core/archive/reader/ArchiveStateReaderTest.java
framework/src/test/java/org/tron/core/archive/ArchiveStatePointResolverTest.java
framework/src/test/java/org/tron/core/services/jsonrpc/archive/ArchiveJsonRpcStateAdapterTest.java
framework/src/test/java/org/tron/core/jsonrpc/JsonRpcArchiveStateTest.java
```

## 10. 验收命令

定向测试：

```bash
./gradlew :chainbase:test --tests '*ArchiveStateReaderTest'
./gradlew :framework:test --tests '*ArchiveStatePointResolverTest'
./gradlew :framework:test --tests '*ArchiveJsonRpcStateAdapterTest'
./gradlew :framework:test --tests '*JsonRpcArchiveStateTest'
```

PR 级别建议：

```bash
./gradlew :chainbase:test --tests '*Archive*'
./gradlew :framework:test --tests '*Archive*'
./gradlew :framework:test --tests '*JsonRpc*'
./gradlew checkstyleMain
```

如果改动进入 commit 前，按 java-tron 仓库要求再跑更完整 lint/test；本规格是文档，不要求执行上述命令。

## 11. Code review 检查表

- [ ] `latest` 查询没有依赖 archive。
- [ ] `historical` 查询没有调用 `Wallet.getAccount/getContract/getContractInfo`。
- [ ] `eth_getBalance/code/storage` 的 interface 和 impl 都声明了 `JsonRpcInternalException`。
- [ ] `ArchiveReaderException` 的 reason 到 JSON-RPC error code 映射明确。
- [ ] `BLOCK_END(blockNum)` 使用 `lastTxNum + 1`。
- [ ] storage historical key 使用 `address21 || slot32 || storageKeyVersion_u8`，没有复用 physical row key。
- [ ] missing account -> balance `0x0`。
- [ ] missing code -> `0x`。
- [ ] missing storage -> 32-byte zero。
- [ ] `pending` 沿用现有 `TAG_PENDING_SUPPORT_ERROR`；`safe` 当前仍是 `invalid block number`。
- [ ] archive disabled/gap/corrupted 不被伪装成 latest 或 zero value。
- [ ] 没有添加测试 skip。

## 12. 后续 PR8 前置接口

PR6 结束后，PR8 historical `eth_call` 可以复用：

```text
ArchiveStatePointResolver
ArchiveStateReaderFactory
ArchiveReaderException
StatePoint
```

但 PR8 还需要新增：

```text
ArchiveRepositoryAdapter
ArchiveOverlay
ArchiveStoreFactory
HistoricalTransactionContextFactory
```

PR6 不要提前把这些类做成半成品。先让三个 state getter 的历史读取闭环稳定。
