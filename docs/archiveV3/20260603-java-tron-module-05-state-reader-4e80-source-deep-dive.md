# 模块 05 ArchiveStateReader：4e80 java-tron 源码对照细化

日期：2026-06-03

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

关联总表：[java-tron Archive：4e80 六模块源码对照细化](./20260603-java-tron-archive-4e80-six-modules-source-detail.md)

上游模块：[模块 01 ArchiveTxNumIndex](./20260603-java-tron-module-01-txnum-index-4e80-source-deep-dive.md)、[模块 02 ArchiveDomainRegistry](./20260603-java-tron-module-02-domain-registry-4e80-source-deep-dive.md)、[模块 03 ArchiveWriteCollector](./20260603-java-tron-module-03-write-collector-4e80-source-deep-dive.md)、[模块 04 ArchiveTemporalStore](./20260603-java-tron-module-04-temporal-store-4e80-source-deep-dive.md)

编码执行包：[java-tron Archive S8/S9：ArchiveStateReader 与 JSON-RPC Historical Getters 4e80 编码执行包](./20260603-java-tron-archive-s8-s9-state-reader-rpc-4e80-coding-packet.md)

代码级执行包：[java-tron Archive L6：ArchiveStateReader + historical getters 代码级执行包](./20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`，`git status --short` 为空，精确冲突标记扫描无命中。

## 1. 当前结论

`ArchiveStateReader` 是 archive sidecar 面向 RPC/VM/debug 的历史状态读取门面。它不参与采集，也不直接读 java-tron latest Store；它把 block selector 解析成 `ArchiveStatePoint(blockNum, txNum)`，再从 Module 04 的 `ArchiveTemporalStore` 读取 domain。

P0 只接三个 JSON-RPC historical getter：

```text
eth_getBalance
eth_getCode
eth_getStorageAt
```

`eth_call` 放到 PR8/后续模块，因为当前 4e80 的 constant call 路径会固定 latest block、latest Store 和 `StoreFactory.getInstance()`，需要单独的 historical repository adapter。

最小落地原则：

```text
latest block 参数 -> 保留当前 Wallet/latest 路径
非 latest block 参数 + archive enabled -> ArchiveStateReader
非 latest block 参数 + archive disabled -> 保持当前 unsupported 行为或明确 archive disabled
任何 historical 查询都不能 silent fallback latest
```

## 2. Erigon 对照

Erigon V3 的 historical reader 只围绕 `txNum` 工作：

| Erigon 源码 | 语义 | java-tron 映射 |
| --- | --- | --- |
| `execution/state/history_reader_v3.go:67-69` | `NewHistoryReaderV3(ttx, txNum)` | `ArchiveStateReader(ArchiveTemporalStore, ArchiveStatePoint)` |
| `history_reader_v3.go:107` | `getAsOf(domain,key)` 是统一读取入口 | `ArchiveTemporalStore.getAsOf(domain,key,targetTxNum)` |
| `history_reader_v3.go:192-208` | `ReadAccountData` 读 `AccountsDomain` 并反序列化 | `getAccount` 读 `ACCOUNT` 并构造 `AccountCapsule` |
| `history_reader_v3.go:217-224` | `ReadAccountStorage` 用 `address || slot` composite key | TRON 用 `address21 || slot32 || keyVersion` |
| `history_reader_v3.go:263-278` | `ReadAccountCode/CodeSize` 读 `CodeDomain` | `getCode` 读 `CODE` |

TRON 的差异：

- TRON address 是 21 bytes，JSON-RPC 输入可为 20-byte ETH-like address 或 21-byte TRON address。
- contract storage key 不能用 Erigon 的 `address20 || slot32`，必须带 keyVersion，且 keyVersion 来自 historical `CONTRACT` domain。
- Account/Contract/Code 都是 java-tron capsule/protobuf 编码，不是 Ethereum account RLP/SSZ。

## 3. 当前 JSON-RPC state getter

`framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java` 声明：

| java-tron 源码 | 当前事实 |
| --- | --- |
| `TronJsonRpc.java:90-94` | `eth_getBalance -> getTrxBalance(String address, String blockNumOrTag)` |
| `TronJsonRpc.java:96-101` | `eth_getStorageAt -> getStorageAt(String address, String storageIdx, String blockNumOrTag)` |
| `TronJsonRpc.java:103-108` | `eth_getCode -> getABIOfSmartContract(String contractAddress, String bnOrId)` |
| `TronJsonRpc.java:162-170` | `eth_call -> getCall(CallArguments, Object blockNumOrTag)` |

当前 4e80 的 state getter 限制已经集中到 `requireLatestBlockTag`：

| java-tron 源码 | 当前事实 | Module 05 接法 |
| --- | --- | --- |
| `TronJsonRpcImpl.java:387-397` | `requireLatestBlockTag` 只接受 `latest`，tag 报 unsupported，quantity 报 quantity unsupported | 三个 getter 要替换为 `ArchiveStatePointResolver` 分支 |
| `TronJsonRpcImpl.java:457-470` | `getTrxBalance` 调 guard 后走 `wallet.getAccount` | latest 保留；historical 走 `ArchiveStateReader.getBalance` |
| `TronJsonRpcImpl.java:611-631` | `getStorageAt` 调 guard 后走 `wallet.getContract` + `StorageRowStore` + `Storage` | latest 保留；historical 走 semantic storage key |
| `TronJsonRpcImpl.java:635-649` | `eth_getCode` 的实现名是 `getABIOfSmartContract`，调 guard 后走 `wallet.getContractInfo` | latest 保留；historical 只读 `CODE` domain |

旧文档中“三个方法各自内联 latest-only 判断”的结论不适用于当前 `4e80f8ffa9a2`。

## 4. block selector 解析

当前 parser 已经比旧基线更完整：

| java-tron 源码 | 当前事实 | historical reader 结论 |
| --- | --- | --- |
| `JsonRpcApiUtil.java:55-62` | 定义 `earliest/pending/latest/finalized/safe`，pending/safe 有独立 unsupported 文案 | P0 不要静默把 `safe` 当 finalized |
| `JsonRpcApiUtil.java:568-574` | `isBlockTag` 包含 `safe` | `requireLatestBlockTag` 会把 safe 当 tag 拒绝 |
| `JsonRpcApiUtil.java:583-600` | `parseBlockTag`：latest=head，earliest=0，finalized=solid，pending/safe 抛 unsupported | resolver 可复用 tag 语义 |
| `JsonRpcApiUtil.java:617-635` | `parseBlockNumber(String)` 支持 hex/decimal，拒绝负数和 long overflow | quantity selector 推荐复用这个方法 |
| `JsonRpcApiUtil.java:643-648` | `parseBlockNumber(String, Wallet)` 对非 tag 用严格 `jsonHexToLong` | state getter 不建议用这个重载，否则裸 decimal 行为不同 |
| `Wallet.java:696-702` | `getBlockByNum` 不存在时返回 null | resolver 需要 fail-fast，不能映射到 latest |
| `Wallet.java:715-720` | `getSolidBlockNum/getHeadBlockNum` 分别读 solid/head | finalized/latest tag 的 source |

建议 `ArchiveStatePointResolver`：

```text
resolve(blockNumOrTag, mode = BLOCK_END):
  if latest:
      return LatestStatePoint
  if pending or safe:
      throw existing unsupported error
  if earliest:
      blockNum = 0
  else if finalized:
      blockNum = wallet.getSolidBlockNum()
  else:
      blockNum = JsonRpcApiUtil.parseBlockNumber(blockNumOrTag)

  block = wallet.getBlockByNum(blockNum)
  if block == null:
      throw header not found / invalid block params

  range = txNumIndex.getBlockRange(blockNum)
  return ArchiveStatePoint(blockNum, blockHash, range.finalizeTxNum)
```

`eth_getBalance/getCode/getStorageAt` 当前 Java 接口只接受 `String blockNumOrTag`，没有 EIP-1898 object selector。P0 不改变接口签名；object block hash selector 后续和 `eth_call` 一起处理。

## 5. 当前 latest Wallet 路径

这些 latest 读取不能直接用于 historical path：

| java-tron 源码 | 当前事实 | historical 风险 |
| --- | --- | --- |
| `Wallet.java:332-352` | `getAccount` 从 latest `AccountStore` 读，并用 latest dynamic/account store 更新资源使用展示 | historical balance 不应被 latest resource processor 改写 |
| `Wallet.java:3179-3198` | `getContract` 从 latest `AccountStore/ContractStore/AbiStore` 读 | historical storage 不能用 latest contract version/trxHash |
| `Wallet.java:3208-3241` | `getContractInfo` 从 latest account/contract/abi/code/contract-state stores 读，并用 latest dynamic property catch up contract state | historical `eth_getCode` 不应读 latest contract-state/dynamic |
| `Wallet.java:4380-4408` | `getAccountBalance` 使用 `AccountTraceStore` block-level balance trace | 只覆盖余额，不能替代 archive account state |

Module 05 不应该改这些 Wallet 方法的默认语义。正确做法是在 JSON-RPC state getter 内分支：

```text
latest -> existing Wallet path
historical -> ArchiveStateReader path
```

这样不会影响非 archive 节点，也不会把 historical state query 的一致性托付给 latest Store。

## 6. `eth_getBalance`

当前 latest path：

```text
TronJsonRpcImpl.java:457-470
requireLatestBlockTag(blockNumOrTag)
addressCompatibleToByteArray(address)
wallet.getAccount(account)
return ByteArray.toJsonHex(balance)
```

historical path：

```text
statePoint = resolver.resolve(blockNumOrTag, BLOCK_END)
address21 = JsonRpcApiUtil.addressCompatibleToByteArray(address)
account = archiveStateReader.getAccount(address21, statePoint)
return ByteArray.toJsonHex(account == null ? 0L : account.getBalance())
```

decoding source:

| java-tron 源码 | 当前事实 |
| --- | --- |
| `AccountCapsule.java:64-69` | `AccountCapsule(byte[])` 从 protobuf bytes 恢复 Account |
| `AccountCapsule.java:253-255` | `getData()` 是 `account.toByteArray()` |
| `AccountCapsule.java:326-327` | `getBalance()` 返回 account balance |

不要调用 `Wallet.getAccount` 做 historical balance。它会按 latest dynamic properties 更新资源字段，且读的是 latest account store。

## 7. `eth_getCode`

当前 latest path：

```text
TronJsonRpcImpl.java:635-649
requireLatestBlockTag(blockNumOrTag)
wallet.getContractInfo(bytesMessage)
return contractDataWrapper.getRuntimecode()
```

historical path：

```text
statePoint = resolver.resolve(blockNumOrTag, BLOCK_END)
address21 = JsonRpcApiUtil.addressCompatibleToByteArray(contractAddress)
code = archiveStateReader.getCode(address21, statePoint)
return ByteArray.toJsonHex(code == null ? empty : code)
```

decoding source:

| java-tron 源码 | 当前事实 |
| --- | --- |
| `CodeCapsule.java:28-30` | `CodeCapsule(byte[] code)` 直接包装 runtime code |
| `CodeCapsule.java:37-39` | `getData()` 返回 runtime code bytes |
| `Wallet.java:3225-3230` | latest path missing code 时返回 empty runtime code |

P0 的 historical `eth_getCode` 只需要 `CODE` domain。`CONTRACT` domain 仍应由 reader core 支持，用于 storage key version、debug/proof、historical `eth_call`。

## 8. `eth_getStorageAt`

当前 latest path：

```text
TronJsonRpcImpl.java:611-631
requireLatestBlockTag(blockNumOrTag)
wallet.getContract(bytesMessage)
Storage(address, manager.getStorageRowStore())
storage.setContractVersion(smartContract.getVersion())
storage.generateAddrHash(smartContract.getTrxHash())
storage.getValue(new DataWord(ByteArray.fromHexString(storageIdx)))
return 32-byte zero if missing
```

latest path 的 physical storage key 规则来自 `Storage`：

| java-tron 源码 | 当前事实 |
| --- | --- |
| `Storage.java:26-27` | `contractVersion` 影响 key 组合 |
| `Storage.java:46-53` | `compose(key, addrHash)`；version 1 先 hash slot，再拼 addrHash/slot 片段 |
| `Storage.java:68-70` | `generateAddrHash(trxId)` 支持 create2 address hash |
| `Storage.java:73-82` | latest `getValue` 读 `StorageRowStore` physical key |
| `ContractCapsule.java:129-134` | historical contract 能提供 `trxHash` 与 `version` |
| `DataWord.java:83-91` | slot/value 低于 32 bytes 左填充，超过 32 bytes 抛异常 |

historical path 不能构造 latest `Storage`，也不能访问 `StorageRowStore`。它应读 semantic storage domain：

```text
statePoint = resolver.resolve(blockNumOrTag, BLOCK_END)
address21 = JsonRpcApiUtil.addressCompatibleToByteArray(address)
slot32 = new DataWord(ByteArray.fromHexString(storageIdx)).getData()
contract = archiveStateReader.getContract(address21, statePoint)
keyVersion = storageKeyVersion(contract)  // from historical contract version
value = archiveStateReader.getStorage(address21, slot32, keyVersion, statePoint)
return ByteArray.toJsonHex(value == null ? new byte[32] : value)
```

Module 03/04 必须保证 `CONTRACT_STORAGE` 的 canonical key 与这里一致：

```text
address21 || slot32 || keyVersion_u8
```

storage tombstone/missing 对 JSON-RPC 一律返回 32-byte zero。missing contract 也返回 32-byte zero，与 current latest path line `621-622` 保持一致。

## 9. `eth_call` 为什么后置

当前 4e80 `eth_call` 不只是 latest guard：

| java-tron 源码 | 当前事实 | historical 风险 |
| --- | --- | --- |
| `TronJsonRpcImpl.java:1001-1044` | object block 参数会验证 block 存在，但 line `1037` 强制 `blockNumOrTag = latest`，之后仍调 `requireLatestBlockTag` | block selector 不参与 state |
| `Wallet.java:3112-3118` | call contract 前用 latest `ContractStore` 判断合约存在 | historical deleted/created contract 会错 |
| `Wallet.java:3130-3141` | constant call 固定取 latest block，并用 `StoreFactory.getInstance()` | VM repository 和 dynamic properties 都是 latest |
| `Repository.java:26/50/84/88/92` | VM 读取 account/contract/code/storage/balance 都通过 Repository 接口 | 需要 archive-backed Repository |
| `RepositoryImpl.java:501-512` | `getContract` fallback 到 latest `ContractStore` | historical call 不能复用 |
| `RepositoryImpl.java:650-664` | `getCode` fallback 到 latest `CodeStore` | historical call 不能复用 |
| `RepositoryImpl.java:681-718` | `getStorageValue/getStorage` 构造 latest `StorageRowStore` | historical call 不能复用 |
| `RepositoryImpl.java:731-733` | `getBalance` 走 latest account cache/store | historical call 不能复用 |

PR8 需要 `ArchiveRepositoryAdapter`：

```text
ArchiveRepositoryAdapter(asOfTxNum):
  getAccount       -> ArchiveStateReader.ACCOUNT
  getContract      -> ArchiveStateReader.CONTRACT
  getCode          -> ArchiveStateReader.CODE
  getStorageValue  -> ArchiveStateReader.CONTRACT_STORAGE
  getBalance       -> decoded historical account balance
  write methods    -> in-memory child overlay only
  commit           -> merge child overlay; never write canonical store
```

所以 Module 05 P0 只接 read-only getters；historical `eth_call` 单独成 PR8，避免在未完成 adapter 时错误地执行 latest state。

## 10. ArchiveStateReader API

建议接口：

```java
interface ArchiveStateReader {
  Optional<AccountCapsule> getAccount(byte[] address21, ArchiveStatePoint point);
  long getBalance(byte[] address21, ArchiveStatePoint point);

  Optional<ContractCapsule> getContract(byte[] address21, ArchiveStatePoint point);
  byte[] getCode(byte[] address21, ArchiveStatePoint point);

  byte[] getStorage(
      byte[] address21,
      byte[] slot32,
      int keyVersion,
      ArchiveStatePoint point);

  Optional<BytesCapsule> getDynamicProperty(byte[] key, ArchiveStatePoint point);
}
```

`ArchiveStatePoint`：

```java
final class ArchiveStatePoint {
  enum Kind { LATEST, BLOCK_END, TX_BEFORE, TX_AFTER }

  Kind kind;
  long blockNum;
  byte[] blockHash;
  long txNum;
  String originalSelector;
}
```

P0 JSON-RPC 只需要 `LATEST` 与 `BLOCK_END`。`TX_BEFORE/TX_AFTER` 为 debug、trace、proof API 预留。

## 11. Codec 与 value 语义

Reader 不应让 RPC 层直接接触 temporal raw bytes。建议独立 codec：

| Domain | temporal value | reader decode | missing/tombstone |
| --- | --- | --- | --- |
| `ACCOUNT` | `AccountCapsule.getData()` | `new AccountCapsule(bytes)` | account absent |
| `CONTRACT` | ABI 清理后的 `ContractCapsule.getData()` | `new ContractCapsule(bytes)` | contract absent |
| `CODE` | runtime code bytes | raw byte[] 或 `CodeCapsule` | empty code `0x` |
| `CONTRACT_STORAGE` | 32-byte slot value | 32-byte byte[] | 32-byte zero |
| `DYNAMIC_PROPERTIES` | raw `BytesCapsule` bytes | allowlist-specific decode | absent/default |

Reader 返回值和 RPC 返回值分开：

```text
ArchiveStateReader.getStorage -> byte[32] or null
JsonRpc adapter -> null => new byte[32]
```

这样 proof/debug API 可以区分 missing 与 zero，而 JSON-RPC 保持 Ethereum 兼容输出。

## 12. 第一版实现落点

新增类建议：

| 类 | package | 说明 |
| --- | --- | --- |
| `ArchiveStatePoint` | `org.tron.core.archive.reader` | block/tx selector 解析结果 |
| `ArchiveStatePointResolver` | 同上 | JSON-RPC block tag/quantity -> finalize txNum |
| `ArchiveStateReader` | 同上 | historical domain reader 接口 |
| `DefaultArchiveStateReader` | 同上 | 基于 `ArchiveTemporalStore` 的实现 |
| `ArchiveStateCodec` | 同上 | domain value decode |
| `ArchiveJsonRpcStateAdapter` | `org.tron.core.services.jsonrpc` 或 archive rpc package | 三个 getter 的 historical 分支 |
| `ArchiveRepositoryAdapter` | `org.tron.core.archive.vm` | PR8 historical `eth_call`，P0 可先只设计不接 |

接入顺序：

1. 实现 `ArchiveStatePointResolver`，复用 `JsonRpcApiUtil.parseBlockTag/parseBlockNumber`，并通过 `ArchiveTxNumIndex` 得到 finalize txNum。
2. 实现 `DefaultArchiveStateReader`，只读 `ArchiveTemporalStore`。
3. 改 `getTrxBalance/getABIOfSmartContract/getStorageAt`：latest 保留当前逻辑，historical 走 adapter。
4. archive disabled 时保留当前 latest-only 行为，避免未开启 archive 的节点行为突变。
5. PR8 再改 `getCall` 与 VM repository。

## 13. 测试证据

最小测试必须证明：

| 测试 | 要证明 |
| --- | --- |
| latest balance/code/storage | 仍走当前 Wallet/latest path，返回行为不变 |
| non-latest archive disabled | 不 silent fallback latest；保留 unsupported 或明确 archive disabled |
| quantity parse | hex/decimal、负数、overflow、超长输入按 `JsonRpcApiUtil.parseBlockNumber` 行为 |
| tag parse | `earliest/finalized` 解析到 block finalize txNum；`pending/safe` 仍 unsupported |
| block not found | historical getter 对不存在 block fail-fast |
| historical balance | missing account 返回 `0x0`，存在 account 解 `AccountCapsule` balance |
| historical code | missing code 返回 `0x`，存在 code 返回 runtime code |
| historical storage | missing contract/slot 返回 32-byte zero；slot 输入短于 32 bytes 左填充 |
| storage version | historical contract version 改变时，reader 使用对应 keyVersion |
| no latest read | historical getter test fake Wallet/Store 证明不会调用 latest `Wallet.getAccount/getContractInfo/getContract` |
| txNum resolver | block number 通过 Module 01 `blockNum -> finalizeTxNum`，不是直接用 blockNum |

## 14. 关键风险

1. 只放开 `requireLatestBlockTag` 会让 non-latest 查询读 latest Store，结果错误且很难发现。
2. historical `eth_getStorageAt` 如果用 `StorageRowStore` physical key，会丢失 logical slot 与 historical contract version。
3. `safe` 当前有常量但 `parseBlockTag` 明确 unsupported，不能无测试地映射到 finalized。
4. `Wallet.getAccount` 会用 latest dynamic properties 更新资源展示，不适合 historical balance。
5. `Wallet.getContractInfo` 会读 latest contract-state/dynamic properties，不适合 historical code。
6. `eth_call` 当前 object block 参数最终强制 latest；没有 archive-backed repository 前不能声称支持 historical call。
7. archive disabled 时不能 silent fallback latest，否则用户以为拿到了历史状态。
