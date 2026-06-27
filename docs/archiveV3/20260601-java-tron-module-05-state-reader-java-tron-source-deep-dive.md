# 模块 05 ArchiveStateReader：java-tron 源码对照

日期：2026-06-01

> 2026-06-03 更新：本文是旧 `a79693e450` 源码对照，正文中关于 tag 常量和 getter guard 形态的结论已不适用于当前源码。当前实现请改看 [模块 05 ArchiveStateReader：4e80 java-tron 源码对照细化](./20260603-java-tron-module-05-state-reader-4e80-source-deep-dive.md)。

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

关联设计：[java-tron Archive 模块 05：ArchiveStateReader 细化设计](./20260521-java-tron-archive-module-05-state-reader.md)

Erigon 对照：[模块 05 ArchiveStateReader：Erigon 源码对照深挖](./20260527-java-tron-module-05-state-reader-erigon-source-deep-dive.md)

代码级实现规格：[java-tron Archive PR6 StateReader/JSON-RPC 代码级实现规格](./20260602-java-tron-archive-pr6-state-reader-jsonrpc-implementation-spec.md)

historical eth_call 代码级实现规格：[java-tron Archive PR8 historical eth_call 代码级实现规格](./20260602-java-tron-archive-pr8-historical-eth-call-implementation-spec.md)

逐文件 Patch 清单：[java-tron Archive 模块 05：ArchiveStateReader 逐文件 Patch 清单](./20260602-java-tron-archive-module-05-state-reader-patch-checklist.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 旧文档原始基线：`a79693e450`，当前 4e80 实现请看页头链接。

## 1. 结论

旧 `a79693e450` 源码中，JSON-RPC state 类方法基本只支持 `latest`，且 latest-only 判断分散在各 getter 内。当前 `4e80f8ffa9a2` 已改为 `requireLatestBlockTag` 集中 guard，并且 `JsonRpcApiUtil` 已有 `safe` tag 常量和 block parser helper；当前实现以 4e80 细化文档为准。

ArchiveStateReader 的核心任务是把：

```text
block tag / block number / tx id / tx position
```

解析为：

```text
asOfTxNum
```

再通过 `ArchiveTemporalStore` 读取 `ACCOUNT/CONTRACT/CODE/CONTRACT_STORAGE` 等 domain，给 RPC、debug、历史 `eth_call` 和 proof 工具提供统一历史视图。

PR6 第一阶段最重要的接入点是：

```text
eth_getBalance
eth_getCode
eth_getStorageAt
```

`eth_call at historical block` 放到 PR8，因为当前 `getCall` 的 object block 参数会在校验 block 存在后强制改回 `latest`，并且后续 `Wallet.triggerConstantContract`、`VMActuator`、`RepositoryImpl` 都读 latest store。PR8 的代码级实现规格已经单独收敛到 historical `eth_call` 文档，重点覆盖 `VMActuator` 注入点、`ArchiveRepositoryAdapter`、historical dynamic properties 和 object block 参数不再强制 latest。

## 2. 当前 JSON-RPC state 方法限制

关键源码：

| 位置 | 源码事实 |
| --- | --- |
| `framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java:90-94` | `eth_getBalance` 只声明 `JsonRpcInvalidParamsException` |
| `TronJsonRpc.java:96-101` | `eth_getStorageAt` 只声明 `JsonRpcInvalidParamsException` |
| `TronJsonRpc.java:103-108` | `eth_getCode` 的 Java 方法名是 `getABIOfSmartContract`，只声明 `JsonRpcInvalidParamsException` |
| `TronJsonRpc.java:162-170` | `eth_call` 已声明 request/params/internal 三类异常 |
| `framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java:155-167` | 当前 tag 常量只有 `earliest/pending/latest/finalized`；quantity unsupported 文案在这里 |
| `TronJsonRpcImpl.java:394-419` | `getTrxBalance` 内联判断 tag/latest/quantity；latest 分支走 `wallet.getAccount` |
| `TronJsonRpcImpl.java:536-568` | `getStorageAt` 内联判断；latest 分支走 `wallet.getContract`、`StorageRowStore`、`Storage` |
| `TronJsonRpcImpl.java:572-599` | `getABIOfSmartContract` 内联判断；latest 分支走 `wallet.getContractInfo` |
| `TronJsonRpcImpl.java:1001-1044` | `getCall` string block 参数 latest-only；object block 参数校验存在后强制 `blockNumOrTag = latest` |

这说明：

- state 查询刻意限制 latest，但限制点分散在三个方法里。
- `eth_call` 现在会验证 block 参数存在，但最后强制走 latest 状态。
- PR6 不能只“放行 quantity”，否则底层仍会读 latest store。

ArchiveStateReader 应替换 state 方法中的 latest-only 检查，而不是改 block 查询逻辑。

## 3. 当前 block selector 和基础工具

关键源码：

| 位置 | 源码事实 |
| --- | --- |
| `framework/src/main/java/org/tron/core/services/jsonrpc/JsonRpcApiUtil.java:390-410` | `addressCompatibleToByteArray` 接受 20-byte ETH address 并补 `0x41`，或校验 21-byte TRON address |
| `JsonRpcApiUtil.java:518-531` | `getByJsonBlockId`：empty/latest -> `-1`，earliest -> `0`，finalized -> `wallet.getSolidBlockNum()`，pending -> `TAG pending not supported`，其他严格 `jsonHexToLong` |
| `common/src/main/java/org/tron/common/utils/ByteArray.java:46-56` | `fromHexString` 去 `0x`，奇数长度左补 0 |
| `ByteArray.java:116-118` | `toJsonHex(byte[])` 对 null/empty 返回 `0x` |
| `ByteArray.java:134-135` | `toJsonHex(Long)` 返回 quantity hex |
| `ByteArray.java:146-151` | `hexToBigInteger`：带 `0x` 按 hex，裸字符串按 decimal |
| `ByteArray.java:154-159` | `jsonHexToLong`：要求 `0x` 前缀 |
| `common/src/main/java/org/tron/common/runtime/vm/DataWord.java:82-91` | 低于 32 bytes 左填充，超过 32 bytes 抛 `RuntimeException` |

PR6 resolver 建议直接匹配 `latest/earliest/pending/finalized`，quantity 走 `ByteArray.hexToBigInteger` 并补 `>= 0`、`<= Long.MAX_VALUE` 校验。不要直接使用 `getByJsonBlockId` 作为 state getter parser，否则裸 decimal historical 查询会从当前可校验输入变成 `"Incorrect hex syntax"`。

当前没有 `safe` 常量。`safe` 在三个 state getter 中会落入 quantity 分支并报 `invalid block number`；PR6 不应无测试地新增 safe 语义。

## 4. 当前 Wallet/latest 读取路径

关键源码：

| 位置 | 作用 |
| --- | --- |
| `framework/src/main/java/org/tron/core/Wallet.java:337-350` | `getAccount` 从 latest `AccountStore` 读账户，并用 latest dynamic/account store 更新资源视图 |
| `Wallet.java:691-697` | `getBlockByNum`，不存在时返回 null |
| `Wallet.java:710-712` | `getSolidBlockNum`，从 latest dynamic properties 读 solid block |
| `Wallet.java:3112-3145` | `triggerConstantContract`，latest constant call 入口 |
| `Wallet.java:3205-3224` | `getContract` 从 latest account/contract/abi stores 读 |
| `Wallet.java:3234-3268` | `getContractInfo` 从 latest account/contract/abi/code/contract-state stores 读 |
| `Wallet.java:4355-4370` | `getAccountBalance` 使用 balance trace，不是完整 archive state reader |

Wallet 当前读取的是 latest Store，除 `getAccountBalance` 有特殊 block-level balance trace。ArchiveStateReader 不应直接修改所有 Wallet 方法语义，否则容易影响非 archive 节点和已有 API。

建议新增历史读取门面：

```text
ArchiveStateReaderFactory
ArchiveStateReader
ArchiveRepositoryAdapter
```

RPC state 方法在 archive 开启且 block 参数不是 latest 时走 ArchiveStateReader；latest 保持现有路径。

## 5. 当前 `eth_getBalance`

源码：

```text
TronJsonRpcImpl.getTrxBalance(address, blockNumOrTag)
  -> if earliest/pending/finalized: tag unsupported
  -> else if latest: wallet.getAccount(...)
  -> else: ByteArray.hexToBigInteger(blockNumOrTag); quantity unsupported
```

历史实现建议：

```text
if block tag is latest:
    existing wallet path
else:
    statePoint = resolver.resolve(blockNumOrTag, BLOCK_END)
    account = archiveReader.account(address, statePoint)
    return account.balance or 0
```

注意：

- `getAccountBalance` 现有 gRPC 接口是 block-level balance-only，可以选择后续接 ArchiveStateReader。
- JSON-RPC `eth_getBalance` 应优先支持标准 block tag/quantity。
- historical path 不调用 `account.importAllAsset()` 或 resource processor，直接返回目标 state point 的 `Account.balance`。

## 6. 当前 `eth_getCode`

源码：

```text
TronJsonRpcImpl.getABIOfSmartContract(contractAddress, blockNumOrTag)
  -> if earliest/pending/finalized: tag unsupported
  -> else if latest: wallet.getContractInfo(...)
  -> else: ByteArray.hexToBigInteger(blockNumOrTag); quantity unsupported
```

命名上 `getABIOfSmartContract` 是 java-tron 内部方法名，但 JSON-RPC 注解对应 `eth_getCode`。

历史实现建议：

```text
if latest:
    existing wallet.getContractInfo path
else:
    code = archiveReader.code(contractAddress, statePoint)
    return hex(code)
```

PR6 historical `eth_getCode` 的返回值只需要 `CODE` domain；`CONTRACT` domain 保留给 debug、proof 和 PR8 的 historical VM 上下文。reader core 仍可暴露 `getContract`，但 JSON-RPC adapter 不应为了返回 code 去读 latest `ContractStore`。

## 7. 当前 `eth_getStorageAt`

源码：

```text
TronJsonRpcImpl.getStorageAt(address, storageIdx, blockNumOrTag)
  -> if earliest/pending/finalized: tag unsupported
  -> else if latest:
       wallet.getContract(...)
       Storage(address, StorageRowStore)
       setContractVersion(...)
       generateAddrHash(...)
       storage.getValue(new DataWord(ByteArray.fromHexString(storageIdx)))
  -> else: ByteArray.hexToBigInteger(blockNumOrTag); quantity unsupported
```

历史实现建议：

```text
if latest:
    existing wallet path
else:
    slot = normalize(storageIdx)
    value = archiveReader.storageAt(contractAddress, slot, statePoint)
    return 32-byte hex zero if missing
```

依赖前置：

- `ArchiveDomainRegistry` 固定 storage logical key。
- `ArchiveWriteCollector` 能记录 logical slot。
- `ArchiveTemporalStore` tombstone 对 storage 解释为 zero。
- storage historical key 使用 `address21 || slot32 || storageKeyVersion_u8`，不构造 `Storage`，不访问 `StorageRowStore`。

## 8. 当前 `eth_call`

源码事实：

| 位置 | 行为 |
| --- | --- |
| `TronJsonRpcImpl.java:1001-1044` | `getCall(CallArguments, Object blockParamObj)` |
| `TronJsonRpcImpl.java:967-983` | object `blockNumber` 会 parse 并验证 block 存在 |
| `TronJsonRpcImpl.java:985-994` | object `blockHash` 会验证 block 存在 |
| `TronJsonRpcImpl.java:999` | object 参数最后强制 `blockNumOrTag = LATEST_STR` |
| `TronJsonRpcImpl.java:1006-1023` | string 参数仍只支持 latest |
| `Wallet.java:3112-3145` | `triggerConstantContract` 使用 latest `ContractStore` 做存在性判断 |

也就是说，当前 `eth_call` 的 block 参数只是存在性检查，不影响执行状态。

历史 `eth_call` 需要更深接入：

```text
blockParam -> StatePoint -> asOfTxNum
ArchiveRepositoryAdapter(asOfTxNum)
TVM constant execution reads from archive adapter
```

P0 可以先支持：

- `eth_getBalance`
- `eth_getCode`
- `eth_getStorageAt`

P1 再支持 historical `eth_call`，因为它需要 Repository/VM read path 可插拔。

## 9. ArchiveRepositoryAdapter

当前 Repository 接口包括：

| 方法 | 历史读取需求 |
| --- | --- |
| `getAccount(address)` | 从 `ACCOUNT` domain 读 |
| `getCode(address)` | 从 `CODE` domain 读 |
| `getStorageValue(address, key)` | 从 `CONTRACT_STORAGE` domain 读 |
| `getBalance(address)` | 从历史 Account 解 balance |
| `getContract(address)` 类路径 | 从 `CONTRACT` domain 读 |

关键源码：

| 位置 | 作用 |
| --- | --- |
| `actuator/src/main/java/org/tron/core/vm/repository/Repository.java:26` | `getAccount` |
| `Repository.java:82` | `saveCode` |
| `Repository.java:84` | `getCode` |
| `Repository.java:86` | `putStorageValue` |
| `Repository.java:88` | `getStorageValue` |
| `Repository.java:96` | `newRepositoryChild` |
| `Repository.java:100` | `commit` |

Historical call adapter 可以实现只读 base + child overlay：

```text
ArchiveRepositoryAdapter(asOfTxNum):
  read account/code/storage from ArchiveStateReader
  writes go to in-memory child cache
  commit only merges child into parent cache
  never writes canonical Store or ArchiveTemporalStore
```

这样 `eth_call` 可以执行临时状态变更，但不会污染历史。

## 10. StatePoint 解析

建议 `StatePoint` 使用 java-tron 编译目标更稳妥的 final class + enum：

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
}
```

JSON-RPC 第三参先由 `ArchiveStatePointResolver` 解析成 `ResolvedStatePoint`，latest 直接走旧路径；historical string quantity/tag 只进入 `StatePoint.blockEnd(blockNum)`。`ArchiveStateReader` 不直接解析字符串，它接收已经解析的 `StatePoint/asOfTxNum`：

```text
JsonRpc block param
  -> StatePointResolver
  -> StatePoint.blockEnd(blockNum)
  -> ArchiveStateReaderFactory.resolveAsOfTxNum
  -> ArchiveStateReader
```

这样不同入口共享 off-by-one 规则。

## 11. 与 block tag 的兼容

当前 state getter 兼容矩阵：

| tag/quantity | 当前状态 |
| --- | --- |
| `latest` | 支持 |
| hex quantity | `ByteArray.hexToBigInteger` 校验后 state 方法拒绝 |
| decimal quantity | `ByteArray.hexToBigInteger` 校验后 state 方法拒绝 |
| `earliest` | state 方法当前 generic tag unsupported；PR6 改为 `BLOCK_END(0)` |
| `finalized` | state 方法当前 generic tag unsupported；PR6 改为 `BLOCK_END(wallet.getSolidBlockNum())` |
| `pending` | canonical archive history 不支持 |
| `safe` | 当前源码没有 safe 常量，按 invalid block number 处理 |
| block hash object | 三个 state getter 不支持；`eth_call` object 参数在 PR8 处理 |

Archive 建议：

```text
latest: 现有 Wallet/latest path，不依赖 archive
quantity: BLOCK_END(blockNum)
earliest: BLOCK_END(0)
finalized: BLOCK_END(solidBlockNum)
pending: invalid params，不进入 archive
safe: 当前基线仍 invalid block number，除非同 PR 增加 safe 常量和测试
```

## 12. Reader API 建议

```java
interface ArchiveStateReader {
  StatePoint statePoint();

  long asOfTxNum();

  ArchiveReadResult<AccountCapsule> getAccount(byte[] address) throws ArchiveReaderException;

  ArchiveReadResult<ContractCapsule> getContract(byte[] address) throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getCode(byte[] address) throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getStorageValue(byte[] address, byte[] slot32)
      throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getRaw(ArchiveDomain domain, byte[] canonicalKey)
      throws ArchiveReaderException;
}
```

Factory：

```java
interface ArchiveStateReaderFactory {
  ArchiveStateReader open(StatePoint statePoint) throws ArchiveReaderException;
}
```

内部字段：

```text
asOfTxNum
ArchiveTemporalStore
ArchiveDomainRegistry
codec set
```

## 13. RPC 接入优先级

P0：

- `eth_getBalance(address, blockNumber)`
- `eth_getCode(address, blockNumber)`
- `eth_getStorageAt(address, slot, blockNumber)`
- `latest` 保持现有路径。
- archive 未启用时返回明确 `JsonRpcInternalException`，而不是假装支持或退化成 latest。

P1：

- `eth_call` historical block。
- gRPC `getAccountBalance` 使用 archive 后端。
- block hash state point。

P2：

- tx-level state point 扩展 API。
- debug/proof API。
- historical trace 与 ArchiveStateReader 集成。

## 14. 错误语义

建议区分：

| 场景 | 错误 |
| --- | --- |
| archive 未启用但请求历史 state | archive disabled |
| block 不存在 | block not found |
| block 存在但 archive 未覆盖到 | archive range unavailable |
| block 已被 pruning/freeze 但 segment 缺失 | archive segment missing/corrupt |
| txId 不在 canonical chain | transaction not canonical |
| pending tag | pending historical state unsupported |
| safe tag | 当前基线按 invalid block number；如后续新增 safe，必须明确 historical safe 语义 |

历史 state getter 不要继续返回 “just support TAG as latest”，否则用户无法区分配置问题和数据缺口。

## 15. 测试建议

### 14.1 JSON-RPC block number

构造：

```text
block 1: A balance 100
block 2: A balance 80
```

断言：

- `eth_getBalance(A, 0x1)` 返回 100。
- `eth_getBalance(A, latest)` 返回 80。

### 14.2 Code 历史

合约在 block N 部署：

- `eth_getCode(address, N-1)` 返回 `0x`。
- `eth_getCode(address, N)` 返回 bytecode。

### 14.3 Storage 历史

同一 slot 在 block N/N+1 写不同值：

- `eth_getStorageAt(..., N)` 返回旧值。
- `eth_getStorageAt(..., N+1)` 返回新值。

### 14.4 Historical eth_call（P1/PR8）

合约读取 storage：

- 在 block N call 返回旧 slot。
- 在 block N+1 call 返回新 slot。
- call 不改变 archive latest。

## 16. 关键风险

1. 只改 state getter 的 block 参数判断、但底层仍读 latest，会返回错误历史状态。
2. `eth_call` 当前强制 latest，需要 Repository 读路径可插拔，不能只在 RPC 层改参数。
3. storage logical key 若前置模块没做好，Reader 无法正确读历史 slot。
4. latest 路径和 archive 路径 value codec 不一致，会导致同一个 block latest 与 historical latest 返回不同。
5. pending/constant call 若复用 canonical Store 写路径，可能污染 ArchiveWriteCollector。
