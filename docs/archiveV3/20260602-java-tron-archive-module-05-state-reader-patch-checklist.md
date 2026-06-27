# java-tron Archive 模块 05：ArchiveStateReader 逐文件 Patch 清单

日期：2026-06-02

> 2026-06-03 更新：本文是旧 `a79693e450` patch 清单。当前 `4e80f8ffa9a2` 的 Module 05 编码入口请先看 [java-tron Archive S8/S9：ArchiveStateReader 与 JSON-RPC Historical Getters 4e80 编码执行包](./20260603-java-tron-archive-s8-s9-state-reader-rpc-4e80-coding-packet.md)，旧行号不可直接用于编码。

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

关联设计：[java-tron Archive 模块 05：ArchiveStateReader 细化设计](./20260521-java-tron-archive-module-05-state-reader.md)

java-tron 源码对照：[模块 05 ArchiveStateReader：4e80 java-tron 源码对照细化](./20260603-java-tron-module-05-state-reader-4e80-source-deep-dive.md)

Erigon 源码对照：[模块 05 ArchiveStateReader：Erigon 源码对照深挖](./20260527-java-tron-module-05-state-reader-erigon-source-deep-dive.md)

代码级实现规格：[java-tron Archive PR6 StateReader/JSON-RPC 代码级实现规格](./20260602-java-tron-archive-pr6-state-reader-jsonrpc-implementation-spec.md)

S8/S9 4e80 编码执行包：[java-tron Archive S8/S9：ArchiveStateReader 与 JSON-RPC Historical Getters 4e80 编码执行包](./20260603-java-tron-archive-s8-s9-state-reader-rpc-4e80-coding-packet.md)

S8 历史编码执行包：[java-tron Archive S8：ArchiveStateReader Core 编码执行包](./20260602-java-tron-archive-s8-state-reader-core-coding-packet.md)

S9 历史编码执行包：[java-tron Archive S9：JSON-RPC Historical Getters 编码执行包](./20260602-java-tron-archive-s9-jsonrpc-historical-getters-coding-packet.md)

后续 historical eth_call 规格：[java-tron Archive PR8 historical eth_call 代码级实现规格](./20260602-java-tron-archive-pr8-historical-eth-call-implementation-spec.md)

前置 patch 清单：

- [模块 01：ArchiveTxNumIndex 逐文件 Patch 清单](./20260602-java-tron-archive-module-01-txnum-index-patch-checklist.md)
- [模块 02：ArchiveDomainRegistry 逐文件 Patch 清单](./20260602-java-tron-archive-module-02-domain-registry-patch-checklist.md)
- [模块 03：ArchiveWriteCollector 逐文件 Patch 清单](./20260602-java-tron-archive-module-03-write-collector-patch-checklist.md)
- [模块 04：ArchiveTemporalStore 逐文件 Patch 清单](./20260602-java-tron-archive-module-04-temporal-store-patch-checklist.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 旧文档原始基线：`a79693e450`，当前 4e80 实现请看页头链接。

## 1. 模块目标

模块 05 把 PR1-PR5 的 `StatePoint -> txNum -> domain key -> temporal getAsOf` 闭环接到业务读接口。P0 只支持 historical state getter：

```text
eth_getBalance(address, block)
eth_getCode(address, block)
eth_getStorageAt(address, slot, block)
```

P0 不实现 historical `eth_call`。`eth_call` 需要 archive-backed `Repository`、historical dynamic properties、VMActuator 注入点和 overlay 隔离，单独进入 PR8。

本模块必须满足 issue #6289 的核心读取目标：

- archive 节点能在不从 genesis 重放的情况下查询历史状态。
- ETH-compatible state getter 能按历史 block 高度读取。
- TRON 分散 state DB 通过 domain registry 编码，reader 不直接访问 latest Store。
- stateRoot 暂不参与共识；reader 只读取 archive sidecar history。

## 2. 当前源码事实

### 2.1 JSON-RPC state getter 仍是 latest-only，但当前没有统一 guard

| 文件 | 位置 | 源码事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java:90` | `eth_getBalance` | 只声明 `JsonRpcInvalidParamsException` |
| `TronJsonRpc.java:96` | `eth_getStorageAt` | 只声明 `JsonRpcInvalidParamsException` |
| `TronJsonRpc.java:103` | `eth_getCode` | 只声明 `JsonRpcInvalidParamsException` |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java:155-167` | tag/error 常量 | 只有 `earliest/pending/latest/finalized`；没有 `safe` 常量；`TAG_NOT_SUPPORT_ERROR` 是 private |
| `TronJsonRpcImpl.java:394-419` | `getTrxBalance` | 方法内联判断：`earliest/pending/finalized` 抛 tag unsupported；`latest` 走 `wallet.getAccount`；quantity 只校验后抛 quantity unsupported |
| `TronJsonRpcImpl.java:536-568` | `getStorageAt` | 方法内联判断；`latest` 走 `wallet.getContract`、`manager.getStorageRowStore()`、`Storage` |
| `TronJsonRpcImpl.java:572-599` | `getABIOfSmartContract` | 方法内联判断；`latest` 走 `wallet.getContractInfo` |

当前本地源码里没有统一 latest guard helper。PR6 的改造点是把三个 state getter 当前的内联判断替换为统一 resolver：

```text
latest     -> 保持现有 Wallet/latest Store 路径
historical -> ArchiveStateReader 路径
unsupported/invalid -> 清晰 JSON-RPC 错误
```

### 2.2 block 参数 parser 现状：可复用但不足以直接替代 state getter

| 文件 | 位置 | 源码事实 |
| --- | --- | --- |
| `JsonRpcApiUtil.java:518-531` | `getByJsonBlockId(String, Wallet)` | `pending` 抛 `TAG_PENDING_SUPPORT_ERROR`；空或 `latest` 返回 `-1`；`earliest` 返回 `0`；`finalized` 返回 `wallet.getSolidBlockNum()`；其他输入走严格 `ByteArray.jsonHexToLong` |
| `JsonRpcApiUtil.java:3-7` | static imports | 只导入 `earliest/finalized/latest/pending` 和 pending error；没有 `safe` |
| `/Users/boson/IdeaProjects/java-tron/common/src/main/java/org/tron/common/utils/ByteArray.java:146-151` | `hexToBigInteger` | 带 `0x` 按 16 进制解析；不带前缀按 10 进制解析 |
| `ByteArray.java:154-159` | `jsonHexToLong` | 要求 `0x` 前缀，按 16 进制转 long |

`ArchiveStatePointResolver` 不应按旧文档调用已删除的 block-tag helper。建议 resolver 显式匹配 `LATEST_STR/EARLIEST_STR/PENDING_STR/FINALIZED_STR`，quantity 分支复用 `ByteArray.hexToBigInteger` 并补上 `>= 0`、`<= Long.MAX_VALUE` 校验。不要直接使用 `getByJsonBlockId` 作为 state getter parser，否则裸 decimal 会从当前可校验输入变成 `"Incorrect hex syntax"`。

### 2.3 地址和 storage slot 规范已有基础工具

| 文件 | 位置 | 源码事实 |
| --- | --- | --- |
| `JsonRpcApiUtil.java:396` | `addressCompatibleToByteArray` | 20-byte ETH address 自动补 `0x41`，21-byte TRON address 校验前缀 |
| `ByteArray.java:46` | `fromHexString` | 去掉 `0x`，奇数长度左补 0 |
| `ByteArray.java:116` | `toJsonHex(byte[])` | 空 bytes 返回 `0x` |
| `ByteArray.java:134` | `toJsonHex(Long)` | quantity hex 返回 `0x...` |
| `DataWord.java:83` | `new DataWord(byte[])` | 小于 32 字节左填充，大于 32 字节抛异常 |

PR6 historical path 继续复用 `addressCompatibleToByteArray` 和 `DataWord`，不要新增一套地址/slot 解析逻辑。

### 2.4 Wallet/latest 路径不能复用于历史

| 文件 | 位置 | 源码事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/Wallet.java:337-350` | `getAccount` | 从 latest `AccountStore` 读账户，并用 latest dynamic/account store 更新资源视图 |
| `Wallet.java:3205-3224` | `getContract` | 从 latest `AccountStore/ContractStore/AbiStore` 读合约 |
| `Wallet.java:3234-3268` | `getContractInfo` | 从 latest `AccountStore/ContractStore/AbiStore/CodeStore/ContractStateStore` 读合约、runtime code 和合约状态 |
| `Wallet.java:4355-4370` | `getAccountBalance` | 只服务 balance trace，不是完整 archive state reader |

historical `eth_getBalance/code/storage` 不应调用这些 Wallet 方法。latest path 必须继续使用它们，以保持普通 fullnode 行为不变。

### 2.5 storage 物理 key 是 historical reader 的禁区

| 文件 | 位置 | 源码事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/actuator/src/main/java/org/tron/core/vm/program/Storage.java:46` | `compose` | physical key 由 `addrHash` 和 slot 后 16 bytes 组合 |
| `Storage.java:47` | contract version 1 | slot 先 `sha3(key)` |
| `Storage.java:68` | `generateAddrHash` | create2 时 `address || trxId` 影响 addrHash |
| `Storage.java:77` | `getValue` | latest path 读 physical `StorageRowStore` |
| `TronJsonRpcImpl.java:553-558` | `getStorageAt` | latest path 构造 `Storage(address, StorageRowStore)` 并设置 contract version/trxHash |

PR3/PR4 已要求 archive storage domain 捕获 logical key：

```text
CONTRACT_STORAGE key = address21 || slot32 || storageKeyVersion_u8
```

模块 05 historical storage reader 必须消费这个 logical key，不能构造 `Storage` 或访问 `StorageRowStore`。

### 2.6 historical eth_call 的阻塞点已确认

| 文件 | 位置 | 源码事实 |
| --- | --- | --- |
| `TronJsonRpcImpl.java:1001-1044` | `getCall` | `eth_call` 入口；string block 参数仍是 latest-only |
| `TronJsonRpcImpl.java:967-999` | object block 参数 | `blockNumber/blockHash` 只做存在性校验，然后强制 `blockNumOrTag = latest` |
| `Wallet.java:3112-3145` | `triggerConstantContract` | latest constant call 入口；合约存在性从 latest `ContractStore` 判断 |
| `Wallet.java:3148` | `callConstantContract` | 后续走 latest transaction context |
| `VMActuator.java:122` | config | `ConfigLoader.load(context.getStoreFactory())` |
| `VMActuator.java:141` | repository | `RepositoryImpl.createRoot(context.getStoreFactory())` |
| `ConfigLoader.java:16` | config loader | 只接收 latest `StoreFactory` |
| `Repository.java:18` | dynamic store | 返回具体 `DynamicPropertiesStore` |
| `RepositoryImpl.java:681` | storage read | 通过 `Storage` 物理 key 读 latest `StorageRowStore` |

PR6 不要尝试复用 `Wallet.triggerConstantContract` 来做 historical `eth_call`。PR8 必须新增 archive-backed repository 和 historical dynamic view。

## 3. P0 接口不变量

### 3.1 StatePoint 命名

PR6 使用 `BLOCK_END`，不要引入 `BLOCK_AFTER` 或 `blockAfter`。原因是 PR5 `getAsOf` 是 exclusive as-of 语义：

```text
getAsOf(domain, key, asOfTxNum) 返回 asOfTxNum 之前最后一个已提交状态
```

因此 block N 结束状态应映射为：

```text
BLOCK_END(N) -> blockRange.lastTxNum + 1
```

### 3.2 PR6 暴露的 StatePoint 范围

| 外部输入 | P0 StatePoint |
| --- | --- |
| `latest` | latest path，不经过 archive |
| `earliest` | `BLOCK_END(0)` |
| `finalized` | `BLOCK_END(wallet.getSolidBlockNum())` |
| hex quantity | `BLOCK_END(parsedBlockNum)` |
| decimal quantity | `BLOCK_END(parsedBlockNum)` |
| `pending` | 继续 invalid params；推荐使用当前已有的 `TAG_PENDING_SUPPORT_ERROR`，因为 PR6 支持 `earliest/finalized` 后 generic tag 文案会误导 |
| `safe` | 当前源码没有 `safe` 常量，state getter 会落入 `ByteArray.hexToBigInteger("safe")` 并报 `invalid block number`；PR6 不应无测试地新增 `safe` 语义 |

`TX_BEFORE/TX_AFTER/SYSTEM_AFTER` 保留给 debug/proof/PR9。`eth_call` 的 EIP-1898 object block 参数保留给 PR8。

### 3.3 asOfTxNum 映射

`ArchiveStateReaderFactory.open(point)` 内部解析：

| StatePoint | asOfTxNum |
| --- | --- |
| `LATEST` | `progress.nextTxNum` |
| `BLOCK_BEFORE(blockNum)` | `blockRange.firstTxNum` |
| `BLOCK_END(blockNum)` | `blockRange.lastTxNum + 1` |
| `TX_BEFORE(blockNum, txIndex)` | `txNumIndex.getTxNum(blockNum, txIndex)` |
| `TX_AFTER(blockNum, txIndex)` | `txNumIndex.getTxNum(blockNum, txIndex) + 1` |
| `SYSTEM_AFTER(blockNum, phase)` | `txNumIndex.getSystemTxNum(blockNum, phase) + 1` |

RPC 层不允许自己做 `+1`。

### 3.4 missing/tombstone 语义

`ArchiveTemporalStore.getAsOf` 对 missing 或 tombstone 可返回 nullable raw value；`ArchiveStateReader` 必须统一包装成 `ArchiveReadResult.missing()`。JSON-RPC adapter 再映射成 ETH-compatible 默认值：

| reader 结果 | JSON-RPC 返回 |
| --- | --- |
| `ArchiveReadResult.missing()` account | balance `0x0` |
| `ArchiveReadResult.missing()` code | `0x` |
| `ArchiveReadResult.missing()` storage | 32-byte zero |
| tombstone account -> `missing()` | balance `0x0` |
| tombstone code -> `missing()` | `0x` |
| tombstone storage -> `missing()` | 32-byte zero |

不要在 `ArchiveStateReader` 里把所有 missing 都提前转成默认值，否则 PR8/proof/debug 会丢失 object existence 语义。

## 4. Patch 1：`StatePoint`

新增：

```text
chainbase/src/main/java/org/tron/core/archive/StatePoint.java
```

建议实现为不可变 final class：

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

  public static StatePoint latest();
  public static StatePoint blockBefore(long blockNum);
  public static StatePoint blockEnd(long blockNum);
  public static StatePoint txBefore(long blockNum, int txIndex);
  public static StatePoint txAfter(long blockNum, int txIndex);
  public static StatePoint systemAfter(long blockNum, ArchivePhase phase);
}
```

实现要求：

- constructor 私有。
- `blockNum >= 0`。
- `txIndex >= 0`。
- `SYSTEM_AFTER` 不允许 `phase == USER_TX`。
- `LATEST` 不携带 block/tx 字段。
- 提供 `equals/hashCode/toString`，方便测试和日志。
- 不使用 Java sealed interface，除非确认 java-tron 编译目标支持。

测试：

```text
chainbase/src/test/java/org/tron/core/archive/StatePointTest.java
```

覆盖：

- factory method 正确设置 kind 和字段。
- 负 block、负 txIndex、非法 phase 抛异常。
- `LATEST` 字段访问不会产生伪 blockNum。

## 5. Patch 2：`ArchiveReaderException`

新增：

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveReaderException.java
```

建议 reason：

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

映射规则：

| Reason | JSON-RPC exception |
| --- | --- |
| `FUTURE_STATE` | `JsonRpcInvalidParamsException` |
| `UNSUPPORTED` | `JsonRpcInvalidParamsException` |
| `DISABLED` | `JsonRpcInternalException` |
| `HISTORY_UNAVAILABLE` | `JsonRpcInternalException` |
| `DOMAIN_NOT_ENABLED` | `JsonRpcInternalException` |
| `CODEC_ERROR` | `JsonRpcInternalException` |
| `CORRUPTED` | `JsonRpcInternalException` |

要求：

- exception message 包含 block/state point 和 reason，方便 RPC 日志定位。
- 不把 `HISTORY_UNAVAILABLE` 映射成 account missing。
- `CODEC_ERROR` 用于 archive value 无法解码，不用于用户输入格式错误。

## 6. Patch 3：`ArchiveReadResult` + `ArchiveStateReader`

新增：

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveReadResult.java
```

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

要求：

- `present(null)` 抛 `IllegalArgumentException`，不要让调用方重新猜 `null`。
- `missing().value()` 抛 `IllegalStateException`。
- 对 `byte[]` 的 present value，reader 返回 defensive copy。
- JSON-RPC adapter 才能把 missing 转成 `0x`、`0x0`、32-byte zero；reader core 不补默认值。

新增：

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStateReader.java
```

建议接口：

```java
public interface ArchiveStateReader extends AutoCloseable {
  StatePoint statePoint();

  long asOfTxNum();

  ArchiveReadResult<AccountCapsule> getAccount(byte[] address) throws ArchiveReaderException;

  ArchiveReadResult<ContractCapsule> getContract(byte[] address) throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getCode(byte[] address) throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getStorageValue(byte[] address, byte[] slot32) throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getRaw(ArchiveDomain domain, byte[] canonicalKey)
      throws ArchiveReaderException;

  @Override
  void close();
}
```

实现约束：

- `ArchiveStateReader` 本身就是一次 read session。
- `asOfTxNum`、progress snapshot、registry checksum 在 `open` 时固定。
- 所有返回的 `byte[]` 必须 copy。
- `getStorageValue` 要求 `slot32.length == 32`。
- `getRaw` 只给 debug/proof 内部使用，不给普通 RPC 直接拼 domain key。
- 不暴露 latest Store、Manager、Wallet。

可选增强：

```java
ArchiveProgress progressSnapshot();
long registryChecksum();
```

如果 PR5 的 progress/checksum 类型已经稳定，可以放进接口；否则先放在默认实现字段中，只通过日志和测试访问。

## 7. Patch 4：`DefaultArchiveStateReader`

新增：

```text
chainbase/src/main/java/org/tron/core/archive/reader/DefaultArchiveStateReader.java
```

依赖：

```text
ArchiveTemporalStore
ArchiveDomainRegistry
StatePoint
asOfTxNum
ArchiveProgress snapshot
registryChecksum snapshot
```

读取流程：

```text
getAccount(address):
  key = registry.encodeQueryKey(ACCOUNT, AccountQuery(address))
  raw = temporalStore.getAsOf(ACCOUNT, key, asOfTxNum)
  if raw == null: return ArchiveReadResult.missing()
  account = new AccountCapsule(raw)
  if account.getInstance() == null: throw CODEC_ERROR
  return ArchiveReadResult.present(account)

getContract(address):
  key = registry.encodeQueryKey(CONTRACT, ContractQuery(address))
  raw = temporalStore.getAsOf(CONTRACT, key, asOfTxNum)
  if raw == null: return ArchiveReadResult.missing()
  contract = new ContractCapsule(raw)
  if contract.getInstance() == null: throw CODEC_ERROR
  return ArchiveReadResult.present(contract)

getCode(address):
  key = registry.encodeQueryKey(CODE, CodeQuery(address))
  raw = temporalStore.getAsOf(CODE, key, asOfTxNum)
  if raw == null: return ArchiveReadResult.missing()
  return ArchiveReadResult.present(copy(raw))

getStorageValue(address, slot32):
  key = registry.encodeQueryKey(CONTRACT_STORAGE, StorageQuery(address, slot32))
  raw = temporalStore.getAsOf(CONTRACT_STORAGE, key, asOfTxNum)
  if raw == null: return ArchiveReadResult.missing()
  return ArchiveReadResult.present(copy(raw))
```

重要约束：

- Reader 不手写 `address21 || slot32 || storageKeyVersion_u8`，必须通过 registry query codec。
- Reader 不调用 `Storage.compose(...)`。
- Reader 不访问 `StorageRowStore`。
- Reader 不调用 `Wallet.getAccount/getContract/getContractInfo`。
- Reader 不二次解码 PR5 `ArchiveValueCodec`。如果 PR5 `getAsOf` 暴露的是 encoded nullable value，则先在 PR5 收敛接口：PR6 只接收业务 raw value 或 `null`。
- `AccountCapsule(byte[])` 和 `ContractCapsule(byte[])` 构造后要检查 `getInstance()` 是否为 `null`；解析失败返回 `CODEC_ERROR`，不能返回 missing。

## 8. Patch 5：`ArchiveStateReaderFactory`

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

```text
ArchiveConfig
ArchiveTxNumIndex
ArchiveTemporalStore
ArchiveDomainRegistry
ArchiveTemporalStore.progress()
ArchiveStartupVerifier status
```

`open(point)` 顺序：

1. 如果 archive disabled，返回 `DISABLED`。
2. 读取一次 progress snapshot。
3. 读取一次 registry checksum snapshot。
4. 如果 startup verifier 状态为 `REPAIR_REQUIRED`，返回 `CORRUPTED`。
5. 根据 `StatePoint` 解析 `asOfTxNum`。
6. 校验 requested block 不超过 `progress.appliedBlockNum`。
7. 校验 `asOfTxNum <= progress.nextTxNum`。
8. 校验 block range 存在；不存在返回 `HISTORY_UNAVAILABLE`。
9. 返回 `DefaultArchiveStateReader`。

gap 处理：

| verifier 状态 | reader 行为 |
| --- | --- |
| `EMPTY` | historical 查询返回 `HISTORY_UNAVAILABLE` |
| `OK` | 正常 |
| `ARCHIVE_GAP` | 查询 gap 区间返回 `HISTORY_UNAVAILABLE` |
| `REPAIR_REQUIRED` | 所有 historical 查询返回 `CORRUPTED` |

不要在 factory 内自动 backfill、repair 或 fallback latest。

## 9. Patch 6：`ArchiveStatePointResolver`

新增：

```text
framework/src/main/java/org/tron/core/archive/ArchiveStatePointResolver.java
```

建议返回对象：

```java
public final class ResolvedStatePoint {
  private final boolean latest;
  private final StatePoint statePoint;
  private final long blockNum;
}
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

解析规则：

1. `latest` 直接返回 `isLatest=true`，不解析成 `BLOCK_END(head)`。
2. `earliest` 返回 block `0`。
3. `finalized` 返回 `wallet.getSolidBlockNum()`。
4. `pending` 返回 `JsonRpcInvalidParamsException(TAG_PENDING_SUPPORT_ERROR)`。
5. 其他输入调用 `ByteArray.hexToBigInteger(blockNumOrTag)`，带 `0x` 按 16 进制，裸数字按 10 进制。
6. quantity 解析后显式校验 `signum >= 0` 且 `<= Long.MAX_VALUE`，否则返回 `JsonRpcInvalidParamsException("invalid block number")`。
7. 当前源码无 `safe` 常量；`safe` 按 malformed quantity 返回 `invalid block number`，除非同 PR 增加 `SAFE_STR` 和兼容性测试。
8. head 高度用 `wallet.getNowBlock().getBlockHeader().getRawData().getNumber()`，不要调用当前 `Wallet` 不存在的 `getHeadBlockNum()`。
9. `blockNum > head` 返回 `JsonRpcInvalidParamsException("block number is in the future")`。
10. `wallet.getBlockByNum(blockNum) == null` 返回 `JsonRpcInternalException("header not found")`。
11. 返回 `StatePoint.blockEnd(blockNum)`。

不要做：

- 不把 64-byte string 当 block hash。三个 state getter 的第三参是 string block number/tag，不是 EIP-1898 object。
- 不让 `latest` 依赖 archive progress。
- 不直接调用 `JsonRpcApiUtil.getByJsonBlockId` 作为 resolver 主逻辑；它对裸 decimal 和 state getter 当前行为不一致。

测试：

```text
framework/src/test/java/org/tron/core/archive/ArchiveStatePointResolverTest.java
```

必须覆盖：

- `latest` 返回 latest。
- `earliest` 返回 block 0。
- `finalized` 使用 `wallet.getSolidBlockNum()`。
- `0x10` 返回 16。
- `16` 返回 16。
- `pending` 抛 `TAG_PENDING_SUPPORT_ERROR`。
- `safe` 在当前源码基线下抛 `invalid block number`；如新增 `SAFE_STR`，必须同步改测试和 error 文档。
- future block 是 invalid params。
- block store missing 是 internal error。
- 使用 `ByteArray.hexToBigInteger` 的裸 decimal 兼容性。

## 10. Patch 7：`ArchiveJsonRpcStateAdapter`

新增：

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

  public String getStorageAt(byte[] address, byte[] rawSlot, StatePoint point)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;
}
```

实现细节：

```text
getBalance:
  try reader = readerFactory.open(point)
  account = reader.getAccount(address)
  return ByteArray.toJsonHex(account.isPresent() ? account.value().getBalance() : 0L)

getCode:
  code = reader.getCode(address)
  if !code.isPresent() || code.value().length == 0: return "0x"
  return ByteArray.toJsonHex(code.value())

getStorageAt:
  slot32 = new DataWord(rawSlot).getData()
  value = reader.getStorageValue(address, slot32)
  if !value.isPresent(): return ByteArray.toJsonHex(new byte[32])
  return ByteArray.toJsonHex(new DataWord(value.value()).getData())
```

异常规则：

- `TronJsonRpcImpl.parseStorageIndex(storageIdx)` 捕获 malformed hex，映射为 `JsonRpcInvalidParamsException`。
- `ArchiveJsonRpcStateAdapter` 内 `DataWord(rawSlot)` 如果 rawSlot 大于 32 字节，映射为 `JsonRpcInvalidParamsException`。
- `ArchiveReaderException.FUTURE_STATE/UNSUPPORTED` 映射 invalid params。
- archive disabled/gap/corrupt/domain missing/codec error 映射 internal error。
- `getStorageAt` 的 archive value 大于 32 字节是 archive codec 错误，映射 internal error。

测试：

```text
framework/src/test/java/org/tron/core/services/jsonrpc/archive/ArchiveJsonRpcStateAdapterTest.java
```

覆盖 missing 默认值、storage 左填充、exception reason 映射、reader close。Malformed slot hex 属于 `TronJsonRpcImpl` 的 `parseStorageIndex` 测试。

## 11. Patch 8：修改 `TronJsonRpc`

修改：

```text
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java
```

三个方法都增加 internal error：

```java
@JsonRpcErrors({
    @JsonRpcError(exception = JsonRpcInvalidParamsException.class, code = -32602, data = "{}"),
    @JsonRpcError(exception = JsonRpcInternalException.class, code = -32000, data = "{}"),
})
String getTrxBalance(String address, String blockNumOrTag)
    throws JsonRpcInvalidParamsException, JsonRpcInternalException;
```

同样修改：

```text
eth_getStorageAt
eth_getCode
```

注意：

- `JsonRpcInternalException` 是 checked exception，interface 和 implementation 必须同步。
- 现有 tests/mock 实现也要更新 throws 声明。
- 不要修改 unrelated RPC method 的 error annotations。

## 12. Patch 9：修改 `TronJsonRpcImpl`

修改：

```text
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java
```

新增依赖：

```java
private final ArchiveStatePointResolver archiveStatePointResolver;

@Autowired(required = false)
private ArchiveJsonRpcStateAdapter archiveJsonRpcStateAdapter;
```

constructor 中构造 resolver：

```java
this.archiveStatePointResolver = new ArchiveStatePointResolver(wallet);
```

Adapter 只在 historical 分支需要。`latest` path 不访问 adapter；historical 查询时 adapter 缺失返回 `JsonRpcInternalException("archive json-rpc state adapter is not available")`。

测试可见 setter：

```java
@VisibleForTesting
void setArchiveJsonRpcStateAdapter(ArchiveJsonRpcStateAdapter adapter)
```

### 12.1 `eth_getBalance`

改造形态：

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

### 12.2 `eth_getCode`

latest path 保持现有 `wallet.getContractInfo`：

```java
if (resolved.isLatest()) {
  BytesMessage bytesMessage = BytesMessage.newBuilder()
      .setValue(ByteString.copyFrom(addressData))
      .build();
  SmartContractDataWrapper wrapper = wallet.getContractInfo(bytesMessage);
  return wrapper == null ? "0x" : ByteArray.toJsonHex(wrapper.getRuntimecode().toByteArray());
}

return archiveJsonRpcStateAdapter.getCode(addressData, resolved.statePoint());
```

historical path 不读 latest `ContractStore/CodeStore`。

### 12.3 `eth_getStorageAt`

latest path 保持现有 physical storage 逻辑：

```text
wallet.getContract -> Storage(address, StorageRowStore) -> setContractVersion -> generateAddrHash
```

historical path：

```java
return archiveJsonRpcStateAdapter.getStorageAt(
    addressByte,
    parseStorageIndex(storageIdx),
    resolved.statePoint());
```

`parseStorageIndex(storageIdx)` 应捕获 `ByteArray.fromHexString` 的 unchecked decode error，并转成 `JsonRpcInvalidParamsException`。

不要在 historical 分支：

- 调 `wallet.getContract`。
- 构造 `Storage`。
- 读 `manager.getStorageRowStore()`。
- 使用 contract version 或 trx hash 生成 physical row key。

### 12.4 `eth_call` 在 PR6 的边界

PR6 不实现 historical `eth_call`。因此：

- 保留 latest `eth_call` 现有路径。
- 不放开 `getCall` 当前的 inline latest-only 判断，除非同一个 PR 已经实现 PR8 的 archive executor。
- 如果 PR6 触碰 object block 参数分支，不能继续保留 “校验 block 后强制 latest” 作为 historical 行为；应改成 explicit unsupported，或把改动留到 PR8。

## 13. Patch 10：chainbase reader 测试

新增：

```text
chainbase/src/test/java/org/tron/core/archive/reader/ArchiveStateReaderTest.java
```

建议用 fake `ArchiveTemporalStore` 和 fake registry，不启动完整 node。

覆盖：

1. `getAccount` missing -> `ArchiveReadResult.missing()`。
2. `getAccount` present -> `AccountCapsule.balance` 正确。
3. account protobuf 乱码 -> `CODEC_ERROR`。
4. `getContract` present -> `ContractCapsule` 正确。
5. `getCode` missing -> `ArchiveReadResult.missing()`。
6. `getCode` present 返回 copy，调用方修改不污染 cache。
7. `getStorageValue` 要求 32-byte slot。
8. storage missing -> `ArchiveReadResult.missing()`。
9. storage tombstone -> `ArchiveReadResult.missing()`。
10. `getRaw` 只按传入 domain/key 调 temporal store。

factory 测试：

1. disabled -> `DISABLED`。
2. empty/gap -> `HISTORY_UNAVAILABLE`。
3. repair required -> `CORRUPTED`。
4. `BLOCK_END(N)` 使用 `lastTxNum + 1`。
5. `BLOCK_BEFORE(N + 1)` 等于 `BLOCK_END(N)`。
6. future block -> `FUTURE_STATE`。
7. 同一 reader 多次读使用同一个 progress snapshot。

## 14. Patch 11：framework/RPC 测试

新增：

```text
framework/src/test/java/org/tron/core/archive/ArchiveStatePointResolverTest.java
framework/src/test/java/org/tron/core/services/jsonrpc/archive/ArchiveJsonRpcStateAdapterTest.java
framework/src/test/java/org/tron/core/jsonrpc/JsonRpcArchiveStateTest.java
```

`JsonRpcArchiveStateTest` 覆盖：

- archive disabled 时 `eth_getBalance(addr, "latest")` 仍成功。
- archive disabled 时 `eth_getBalance(addr, "0x1")` 返回 internal archive disabled。
- historical balance block 1/block 2 返回不同值。
- historical code 部署前返回 `0x`，部署后返回 runtime code。
- historical storage slot 修改前后返回不同 32-byte value。
- malformed storage slot hex 返回 invalid params，且不调用 archive adapter。
- latest path 和旧测试结果一致。
- `pending` 沿用现有 `TAG_PENDING_SUPPORT_ERROR`；`safe` 当前仍是 `invalid block number`。
- archive gap 返回 internal history unavailable。
- historical path 没有调用 `Wallet.getAccount/getContract/getContractInfo`。

不要添加任何 test skip、`@Ignore`、条件绕过或测试矩阵排除。

## 15. 验收命令

定向测试：

```bash
./gradlew :chainbase:test --tests '*StatePointTest'
./gradlew :chainbase:test --tests '*ArchiveStateReaderTest'
./gradlew :framework:test --tests '*ArchiveStatePointResolverTest'
./gradlew :framework:test --tests '*ArchiveJsonRpcStateAdapterTest'
./gradlew :framework:test --tests '*JsonRpcArchiveStateTest'
```

PR 级建议：

```bash
./gradlew :chainbase:test --tests '*Archive*'
./gradlew :framework:test --tests '*Archive*'
./gradlew :framework:test --tests '*JsonRpc*'
./gradlew checkstyleMain
```

如果进入提交前检查，按 java-tron 仓库要求扩大到相关模块 test 和 checkstyle。

## 16. Code review 检查表

- [ ] `StatePoint` 只使用 `BLOCK_END`，没有 `BLOCK_AFTER/blockAfter`。
- [ ] `BLOCK_END(blockNum)` 解析为 `lastTxNum + 1`。
- [ ] JSON-RPC `latest` 仍走现有 Wallet/latest path。
- [ ] historical `eth_getBalance/code/storage` 不调用 `Wallet.getAccount/getContract/getContractInfo`。
- [ ] historical `eth_getStorageAt` 不构造 `Storage`，不读 `StorageRowStore`。
- [ ] address 解析复用 `addressCompatibleToByteArray`。
- [ ] quantity 解析复用 `ByteArray.hexToBigInteger` 并补 long/负数校验，裸 decimal 兼容性未丢失。
- [ ] `pending` 和当前无 `safe` 常量的错误语义已用测试固定。
- [ ] archive disabled/gap/corrupted 不被映射为 zero/default value。
- [ ] `ArchiveStateReader` 返回 `ArchiveReadResult.missing()`，不提前转 RPC 默认值。
- [ ] RPC adapter 负责 `0x0`、`0x`、32-byte zero。
- [ ] interface 和 impl 都声明 `JsonRpcInternalException`。
- [ ] reader session 固定 progress/asOfTxNum，不每次 read 重新解析。
- [ ] PR6 没有半成品 `ArchiveRepositoryAdapter` 或 VMActuator 注入点。
- [ ] 如果 PR6 触碰 `eth_call`，historical 参数不能继续静默读 latest；如果不触碰，则该风险明确留到 PR8。
- [ ] 没有新增 test skip。

## 17. PR8 交接边界

PR6 完成后，PR8 可复用：

```text
StatePoint
ArchiveStatePointResolver
ArchiveReaderException
ArchiveStateReader
ArchiveStateReaderFactory
ArchiveJsonRpcStateAdapter 的 exception mapping 经验
```

PR8 仍需新增：

```text
ArchiveEthCallExecutor
ArchiveRepositoryAdapter
DynamicPropertiesView
ArchiveDynamicPropertiesView
ConfigLoader.load(DynamicPropertiesView)
VMActuator repository/dynamic view override
JsonRpcCallResultFormatter
ArchiveBlockResolver
```

PR8 的关键规则：

- latest `eth_call` 继续用 `Wallet.triggerConstantContract`。
- non-latest `eth_call` 必须用 archive-backed repository。
- historical block context 来自 canonical historical block。
- dynamic properties 来自 `DYNAMIC_PROPERTIES` archive domain。
- call overlay 只在本次请求内存在，不写 latest Store，也不写 archive history。
