# java-tron Archive 模块 05：ArchiveStateReader 细化设计

日期：2026-05-21

关联总文档：[java-tron 交易级状态树支持：Erigon V2/V3 模型调研](./20260520-java-tron-archive-state-erigon-v2-v3-research.md)

源码对照深挖：[模块 05：ArchiveStateReader Erigon 源码对照深挖](./20260527-java-tron-module-05-state-reader-erigon-source-deep-dive.md)

java-tron 源码对照：[模块 05 ArchiveStateReader：java-tron 源码对照](./20260601-java-tron-module-05-state-reader-java-tron-source-deep-dive.md)

实现蓝图：[java-tron Archive 状态树实现蓝图](./20260602-java-tron-archive-implementation-blueprint.md)

代码级实现规格：[java-tron Archive PR6 StateReader/JSON-RPC 代码级实现规格](./20260602-java-tron-archive-pr6-state-reader-jsonrpc-implementation-spec.md)

historical eth_call 代码级实现规格：[java-tron Archive PR8 historical eth_call 代码级实现规格](./20260602-java-tron-archive-pr8-historical-eth-call-implementation-spec.md)

逐文件 Patch 清单：[java-tron Archive 模块 05：ArchiveStateReader 逐文件 Patch 清单](./20260602-java-tron-archive-module-05-state-reader-patch-checklist.md)

前置模块：

- [模块 01：ArchiveTxNumIndex](./20260521-java-tron-archive-module-01-txnum-index.md)
- [模块 02：ArchiveDomainRegistry](./20260521-java-tron-archive-module-02-domain-registry.md)
- [模块 03：ArchiveWriteCollector](./20260521-java-tron-archive-module-03-write-collector.md)
- [模块 04：ArchiveTemporalStore](./20260521-java-tron-archive-module-04-temporal-store.md)

## 1. 模块定位

`ArchiveStateReader` 是 archive 状态系统的历史读取门面。它面向 RPC、JSON-RPC、debug 工具、历史 `eth_call` 和 root/proof 工具，屏蔽底层 `txNum`、domain key、history index、hot/cold segment 等细节。

它负责把外部查询：

```text
eth_getBalance(address, blockTag)
eth_getCode(address, blockTag)
eth_getStorageAt(address, slot, blockTag)
eth_call(call, blockTag)
debug/state query at tx before/after
```

转换成内部查询：

```text
StatePoint -> asOfTxNum
query object -> domainId + domainKey
ArchiveTemporalStore.getAsOf(domainId, domainKey, asOfTxNum)
domain value -> API response / TVM state object
```

如果 `ArchiveTemporalStore` 提供“按 asOfTxNum 读 domain key”的低层能力，`ArchiveStateReader` 提供“按业务语义读历史状态”的高层能力。

## 2. 职责和非职责

职责：

- 解析 RPC/API 的 blockTag、txTag、debug state point。
- 调用 `ArchiveTxNumIndex` 把 `StatePoint` 转成 `asOfTxNum`。
- 调用 `ArchiveDomainRegistry` 把地址、slot、资产 id、动态参数等查询对象编码成 domain key。
- 调用 `ArchiveTemporalStore` 执行 exact/range/prefix 历史读取。
- 解码 domain value 为账户、合约代码、storage word、链参数、TVM state view。
- 提供一次请求内的一致读视图，避免读一半时 archive progress 或 segment manifest 变化。
- 为历史 `eth_call` 提供只读 VM state adapter。
- 统一错误语义：未来状态、archive 起点前、缺失 key、segment corruption、unsupported domain。

非职责：

- 不分配 txNum。
- 不定义 domain schema 或 codec。
- 不捕获写入。
- 不写 latest/history/index。
- 不计算或验证 state root。
- 不处理 pending state；pending 应走 java-tron 现有内存/执行路径，而不是 archive。

## 3. 设计目标

1. 对外语义稳定。
   RPC 层不直接暴露 `txNum + 1` 这种 before/after 细节。

2. 一致视图。
   同一次 `eth_call` 或 batch query 内，所有 domain 都读同一个 `asOfTxNum`、同一个 registry checksum、同一个 segment manifest view。

3. 查询路径清晰。
   exact key 查询走 `getAsOf`；range/prefix 查询有 limit；VM 读通过只读 adapter。

4. 和 live state 分离。
   历史 archive reader 不能意外 fallback 到当前 java-tron Store，否则历史查询会混入当前状态。

5. 错误可解释。
   `NOT_FOUND`、`UNSUPPORTED_BEFORE_ARCHIVE_START`、`FUTURE_STATE`、`CORRUPTED` 必须区分。

## 4. 核心概念

### 4.1 StatePoint

`StatePoint` 来自 `ArchiveTxNumIndex` 模块：

| 外部语义 | StatePoint |
|---|---|
| 历史区块结束状态 | `BLOCK_END(blockNum)` |
| 区块开始状态 | `BLOCK_BEFORE(blockNum)` |
| 交易执行前状态 | `TX_BEFORE(txId)` |
| 交易执行后状态 | `TX_AFTER(txId)` |
| 系统阶段后状态 | `SYSTEM_AFTER(blockNum, phase)` |
| 当前 archive latest | `LATEST` |

`ArchiveStateReader` 的公开方法应接受 `StatePoint` 或 RPC blockTag，不接受裸 `txNum`。裸 `txNum` 只允许在内部 debug 接口使用。

### 4.2 ReadSession

一次读请求应打开一个 `ReadSession`：

```text
ReadSession:
  statePoint
  asOfTxNum
  archiveProgressSnapshot
  registryChecksum
  segmentManifestSnapshot
  per-request cache
```

`eth_call` 尤其需要 session，因为一次调用会多次读账户、代码、storage、动态参数。如果每次读都重新解析 latest/manifest，可能读到不同进度。

### 4.3 QueryKey

查询层不直接暴露 domain raw key，而使用业务 query：

```text
AccountQuery(address)
CodeQuery(address)
StorageQuery(contractAddress, slot)
DynamicPropertyQuery(propertyId)
ContractMetaQuery(address)
```

Registry 负责把 query 编码成 domain key。查询端和写入端必须共用同一套 codec。

### 4.4 HistoricalTvmState

`HistoricalTvmState` 是 `eth_call` / TVM 历史执行使用的只读状态适配器：

- 读账户。
- 读余额。
- 读合约代码。
- 读合约 storage。
- 读合约元数据。
- 读执行需要的动态链参数。
- 禁止写入。

它不产生 archive history，也不修改 live state。

## 5. blockTag / txTag 解析

### 5.1 ETH JSON-RPC blockTag

建议映射：

| blockTag | 解析 |
|---|---|
| number | `BLOCK_END(number)` |
| `latest` | `LATEST`，对应 archive 已应用最高 block，不一定等同节点 head |
| `earliest` | `GENESIS_AFTER` 或 archive 起点；如果非 genesis archive，则返回 unsupported |
| `pending` | 不走 archive；返回 unsupported 或转 live pending reader |
| `finalized` | 映射到 `wallet.getSolidBlockNum()` 对应的 `BLOCK_END` |
| `safe` | 当前 java-tron JSON-RPC 源码没有 safe 常量，PR6 先保持 invalid block number；如后续新增 safe，必须定义与 solid/finalized 的关系 |

注意：

- `eth_getBalance(addr, 100)` 语义是 block 100 结束后的状态。
- `pending` 不能读 archive，因为 archive 只保存 canonical state。
- 如果 archive progress 落后于 node head，`latest` 应明确表示 archive latest，或 RPC 层返回 archive lag 错误；不要 silent fallback live state。

### 5.2 java-tron wallet/http block 参数

历史查询接口可映射：

```text
block_num -> BLOCK_END(block_num)
block_id  -> 先验证 canonical block id，再 BLOCK_END(block_num)
```

如果用户提供 block id，必须确认它是当前 canonical chain 上的 block。非 canonical block 的 historical state 不在 archive scope 内。

### 5.3 debug tx state

交易级 debug 接口：

| 参数 | 解析 |
|---|---|
| `beforeTx(txId)` | `TX_BEFORE(txId)` |
| `afterTx(txId)` | `TX_AFTER(txId)` |
| `blockBefore(blockNum)` | `BLOCK_BEFORE(blockNum)` |
| `blockEnd(blockNum)` | `BLOCK_END(blockNum)` |

如果 txId 不在 canonical index 中，返回 `TX_NOT_FOUND_OR_NON_CANONICAL`。

### 5.4 future 和 archive 起点前

Reader 必须透传 TxNumIndex/TemporalStore 的边界错误：

- 查询高度超过 archive progress：`FUTURE_STATE` 或 `ARCHIVE_NOT_SYNCED_TO_BLOCK`。
- 查询 archive 启用高度前：`UNSUPPORTED_BEFORE_ARCHIVE_START`。
- 查询 txId 不存在：`TX_NOT_FOUND_OR_NON_CANONICAL`。

这些错误不能混成 “account not found”。

## 6. 地址和 key 规范

### 6.1 地址归一

Reader 接受多种地址来源：

- ETH JSON-RPC 的 20-byte hex address。
- TRON hex address，通常 21-byte，`0x41` 前缀。
- base58check TRON address。
- java-tron 内部 raw bytes。

所有账户/合约查询最终归一到 Registry 定义的 canonical 21-byte TRON address：

```text
address21 = 0x41 || evmAddress20
```

规则：

- ETH API 输入 20-byte address，自动补 `0x41`。
- TRON API 输入 base58/21-byte，校验后直接使用。
- 不允许同一查询路径同时尝试 20-byte 和 21-byte fallback，否则可能隐藏 codec bug。

### 6.2 storage slot

`eth_getStorageAt` 的 slot 必须归一为 32-byte：

```text
slot32 = leftPadTo32Bytes(hexQuantityOrData)
domainKey = contractAddress21 || slot32
```

如果 TVM storage key 与 Ethereum slot 编码存在差异，需要在 Registry query codec 中固定规则，并在接口文档里说明。

### 6.3 查询端 codec

Reader 不应手写 key 拼接。应调用 Registry：

```text
domainKey = registry.encodeQueryKey(CONTRACT_STORAGE, StorageQuery(address, slot))
```

这样写入端和读取端共享同一规则。

## 7. 账户/代码/storage 读取

### 7.1 getAccount

流程：

```text
getAccount(address, statePoint):
  session = openSession(statePoint)
  key = registry.encodeQueryKey(ACCOUNT, AccountQuery(address))
  result = temporal.getAsOf(ACCOUNT, key, session.asOfTxNum)
  if not found:
    return empty/non-existent account
  return registry.decodeValue(ACCOUNT, result.value)
```

返回模型应区分：

- account 不存在。
- account 存在但 balance=0。
- account 存在但没有合约代码。

ETH JSON-RPC 的 `eth_getBalance` 对不存在账户通常返回 `0x0`，但内部 Reader 不能丢失 account existence 语义。

### 7.2 getBalance

```text
getBalance(address, statePoint):
  account = getAccount(address, statePoint)
  if account not exists:
    return 0
  return account.balance
```

余额字段必须来自 historical `ACCOUNT` domain，而不是旧的 `balance.history.lookup` 专用表。旧表可以在迁移期作为校验源，不应作为完整 archive reader 的数据源。

### 7.3 getCode

流程：

```text
getCode(address, statePoint):
  key = registry.encodeQueryKey(CONTRACT_CODE, CodeQuery(address))
  value = temporal.getAsOf(CONTRACT_CODE, key, asOfTxNum)
  if not found:
    return empty bytes
  return decode code bytes
```

如果系统采用 `codeHash -> code` 的双层模型，Reader 需要：

1. 从 `ACCOUNT` 或 `CONTRACT_META` 读历史 codeHash。
2. 从 `CONTRACT_CODE` 按 codeHash 或 address 读历史 code bytes。

具体选择由 Registry 的 domain 设计固定，Reader 只走 Registry descriptor，不自行猜测。

### 7.4 getStorageAt

流程：

```text
getStorageAt(contractAddress, slot, statePoint):
  key = registry.encodeQueryKey(CONTRACT_STORAGE, StorageQuery(contractAddress, slot))
  value = temporal.getAsOf(CONTRACT_STORAGE, key, asOfTxNum)
  if not found:
    return 32 zero bytes
  return normalizeStorageWord(value)
```

内部 Reader 应区分 not found 与 zero word；JSON-RPC 层可以都返回 `0x00...00`。

### 7.5 contract metadata

历史 `eth_call` 可能需要读取：

- ABI/contract metadata。
- origin address。
- consume_user_resource_percent。
- code hash。
- contract creation metadata。

Reader 应提供：

```text
getContractMeta(address, statePoint)
```

如果该 domain 未启用，历史 `eth_call` 应返回 explicit unsupported，不应读当前 ContractStore。

### 7.6 dynamic global state

历史 VM 执行可能依赖：

- 能量/带宽价格。
- chain parameter。
- fork/feature activation 状态。
- maintenance cycle 相关参数。

Reader 应提供：

```text
getDynamicProperty(propertyId, statePoint)
getExecutionRules(statePoint)
```

`getExecutionRules` 不能只看当前节点配置；必须从历史 domain 和 block context 推导。

## 8. 历史 eth_call 适配

### 8.1 基本流程

```text
eth_call(call, blockTag):
  statePoint = ArchiveStatePointResolver.resolveBlockEnd(blockTag)
  session = archiveStateReader.openSession(statePoint)
  tvmState = session.asHistoricalTvmState()
  blockContext = historicalBlockContext(blockTag)
  execute call in read-only mode
```

### 8.2 只读要求

`HistoricalTvmState` 必须禁止写入：

- storage write。
- account balance update。
- account create/delete。
- code update。
- dynamic property update。

如果 VM 框架要求可写 state object，应使用 overlay：

```text
historical base state + ephemeral call overlay
```

overlay 只在本次 call 内存在，不进入 archive，也不影响 live state。

### 8.3 block context

`eth_call` 不只需要 state，还需要历史 block context：

- block number。
- timestamp。
- witness/coinbase 等上下文。
- energy/resource 规则。
- chain feature flags。

这些上下文来自 canonical block/header 和 historical dynamic domain。Reader 可以提供 state，RPC/VM adapter 负责组装 block context，但不能使用当前动态参数替代历史参数。

### 8.4 缺失 domain 的处理

如果请求历史 `eth_call`，但必要 domain 未启用：

- 返回 `HISTORICAL_CALL_UNSUPPORTED_MISSING_DOMAIN`。
- 指明缺失 domain，例如 `DYNAMIC_GLOBAL` 或 `CONTRACT_META`。

不能 fallback 当前 Store，否则结果不可验证。

PR8 已把这部分细化到 java-tron 代码级：

- `TronJsonRpcImpl.getCall` 只让 `latest` 继续走现有 Wallet path。
- non-latest `eth_call` 进入 `ArchiveEthCallExecutor`。
- `VMActuator` 增加 constant-call repository/dynamic view 注入点。
- `ArchiveRepositoryAdapter` 用 `ArchiveStateReader.ReadSession` 作为 historical base，并用本次 call overlay 承接临时写入。
- `ConfigLoader` 需要接收 historical `DynamicPropertiesView`，不能从 latest `StoreFactory` 读取 VM feature flags。

## 9. range/prefix 读取

### 9.1 use cases

- debug 查询某合约所有 historical storage。
- proof 构建。
- state export。
- root rebuild 辅助。

### 9.2 接口约束

所有 range/prefix 查询必须：

- 指定 domain。
- 指定 statePoint。
- 指定 prefix/from/to。
- 指定 limit。
- 返回 continuation token。

不提供无限扫描接口给 RPC。

### 9.3 示例

```text
scanContractStorage(address, statePoint, startSlot, limit):
  prefix = registry.encodeStoragePrefix(address)
  from = prefix || startSlot
  to = nextPrefix(prefix)
  temporal.rangeAsOf(CONTRACT_STORAGE, from, to, asOfTxNum, limit)
```

输出 domain key 时，需要用 Registry 解码回业务 key。对于外部 API，slot 应返回 32-byte hex。

## 10. 一致性和隔离

### 10.1 session pinning

`openSession(statePoint)` 时固定：

- `asOfTxNum`。
- temporal progress。
- registry checksum。
- segment manifest snapshot。
- archive start boundary。

后续同一 session 的所有读都使用这份 snapshot。

### 10.2 archive lag

如果 node head 已到 block 200，但 archive progress 只到 block 180：

- `eth_getBalance(addr, 170)` 可以读取。
- `eth_getBalance(addr, 190)` 返回 archive not synced。
- `eth_getBalance(addr, latest)` 的语义必须明确：要么是 archive latest=180，要么返回 lag 错误，由配置决定。

建议默认：`latest` 指 archive latest，同时 response metadata 或日志暴露 archive lag；对严格 ETH 兼容网关，可配置为 lag 时返回错误。

### 10.3 reorg during read

读 session 打开后，即使后台发生 hot unwind：

- session 应继续读旧 snapshot，或返回 retryable error。
- 不能一半读旧 chain、一半读新 chain。

实现上可通过 DB snapshot/transaction、manifest reference counting 或 read lock 达成。

### 10.4 freeze during read

如果读 session 打开后 cold segment 被切换：

- session 使用打开时的 manifest snapshot。
- old segment 文件在 session 关闭前不能删除。
- 如果无法保证，freeze 与 reader 需要互斥。

## 11. 错误模型

建议统一错误：

| 错误 | 含义 | RPC 映射建议 |
|---|---|---|
| `NOT_FOUND` | domain key 在该状态点不存在 | 账户余额返回 0，代码/storage 返回空/零 |
| `UNSUPPORTED_BEFORE_ARCHIVE_START` | 查询早于 archive 起点 | JSON-RPC error |
| `FUTURE_STATE` | 查询超过 archive progress | JSON-RPC error |
| `TX_NOT_FOUND_OR_NON_CANONICAL` | txId 不在 canonical index | JSON-RPC error |
| `DOMAIN_NOT_ENABLED` | 需要的 domain 未启用 | JSON-RPC error |
| `CODEC_ERROR` | 地址/key/value 编解码错误 | JSON-RPC error |
| `CORRUPTED_ARCHIVE` | segment/index/value 不一致 | JSON-RPC error，触发告警 |
| `PENDING_UNSUPPORTED` | pending blockTag 不支持 archive | JSON-RPC error 或走 live reader |

内部 Reader 不应把所有错误折叠成 null。

## 12. 缓存设计

### 12.1 session cache

每个 ReadSession 可缓存：

- account by address。
- code by address/codeHash。
- storage word by address+slot。
- dynamic property by property id。
- decoded contract meta。

缓存 key 必须包含：

```text
asOfTxNum + domainId + domainKey + registryChecksum
```

### 12.2 global cache

全局缓存要谨慎。可缓存：

- code bytes，如果 code 内容按 hash 寻址且不可变。
- registry query codec 结果。
- segment accessor。

不建议缓存 account/storage historical values，除非有严格的 invalidation 和容量控制。

### 12.3 negative cache

可以在 session 内缓存 not found。不要跨 session 长期缓存 not found，因为该 key 可能在更高 state point 创建。

## 13. Java 接口草案

```java
public interface ArchiveStateReader {
  ReadSession openSession(StatePoint statePoint);

  ReadSession openSession(BlockTag blockTag);

  HistoricalAccount getAccount(Address address, StatePoint statePoint);

  BigInteger getBalance(Address address, StatePoint statePoint);

  byte[] getCode(Address address, StatePoint statePoint);

  byte[] getStorageAt(Address contract, Bytes32 slot, StatePoint statePoint);

  HistoricalContractMeta getContractMeta(Address address, StatePoint statePoint);
}
```

```java
public interface ReadSession extends AutoCloseable {
  StatePoint statePoint();

  long asOfTxNum();

  Optional<byte[]> getRaw(short domainId, QueryKey queryKey);

  HistoricalAccount getAccount(Address address);

  byte[] getCode(Address address);

  byte[] getStorageAt(Address contract, Bytes32 slot);

  HistoricalExecutionRules getExecutionRules();

  HistoricalTvmState asTvmState();

  CloseableIterator<DomainKV> range(short domainId, QueryRange range, int limit);

  @Override
  void close();
}
```

```java
public interface HistoricalTvmState {
  HistoricalAccount getAccount(Address address);

  BigInteger getBalance(Address address);

  byte[] getCode(Address address);

  Bytes32 getStorage(Address address, Bytes32 slot);

  HistoricalContractMeta getContractMeta(Address address);

  HistoricalExecutionRules getExecutionRules();

  void putStorage(Address address, Bytes32 slot, Bytes32 value); // throws UnsupportedOperationException
}
```

```java
public sealed interface ReaderResult<T> {
  record Found<T>(T value) implements ReaderResult<T> {}
  record NotFound<T>() implements ReaderResult<T> {}
  record Error<T>(ArchiveReadError error) implements ReaderResult<T> {}
}
```

说明：

- 对业务 API 可以提供抛异常版本；底层最好保留 structured result。
- `ReadSession` 应是 closeable，便于释放 DB snapshot、segment reference、cache。
- `openSession(BlockTag)` 内部调用 TxNumIndex，不允许 RPC 层自己算 asOfTxNum。

## 14. 与其他模块的接口

### 14.1 ArchiveTxNumIndex

Reader 使用：

```text
asOfTxNum = txNumIndex.resolve(statePoint)
```

Reader 不应直接访问 `archive_txnum_by_block` 表。

### 14.2 ArchiveDomainRegistry

Reader 使用 Registry：

- 地址归一。
- query key 编码。
- value 解码。
- domain 是否启用。
- codec version 选择。

Reader 不硬编码 domain key bytes。

### 14.3 ArchiveTemporalStore

Reader 使用：

```text
getAsOf(domainId, domainKey, asOfTxNum)
rangeAsOf(domainId, from, to, asOfTxNum, limit)
```

Reader 不直接读 latest/history/index 表。

### 14.4 CommitmentBuilder

Reader 和 CommitmentBuilder 都可能需要 range/prefix 状态视图，但职责不同：

- Reader 面向 API/VM，按需读取。
- CommitmentBuilder 面向 root/proof，消费 write-set 或 segment iterator。

Reader 可以为 proof 工具提供 state read helper，但不计算 root。

## 15. PoC 范围

### 15.1 PoC v1

目标：历史 exact query。

实现：

- `openSession(BlockEnd/LATEST)`。
- `getBalance`。
- `getCode`。
- `getStorageAt`。
- ETH 20-byte -> TRON 21-byte 地址归一。
- storage slot 32-byte 归一。
- structured error。

不实现：

- 历史 `eth_call`。
- range scan 对外 API。
- pending state。

### 15.2 PoC v2

目标：历史 `eth_call`。

增加：

- `HistoricalTvmState`。
- `getContractMeta`。
- `getDynamicProperty` / `getExecutionRules`。
- per-session cache。
- block context 组装接口。

### 15.3 PoC v3

目标：debug/proof/export。

增加：

- prefix/range session API。
- continuation token。
- storage scan。
- proof helper。
- root rebuild reader view。

## 16. 边界场景

| 场景 | Reader 行为 |
|---|---|
| 不存在账户查余额 | 返回 0，但内部保留 NotFound 语义 |
| 不存在账户查代码 | 返回 empty bytes |
| 不存在 storage slot | JSON-RPC 返回 32-byte zero |
| storage slot 存在且值为 zero | JSON-RPC 同样返回 zero，debug 可区分 existence |
| blockTag 超过 archive progress | 返回 archive not synced/future state |
| blockTag 早于 archive 起点 | 返回 unsupported |
| txId 非 canonical | 返回 tx not found/non-canonical |
| pending blockTag | 不走 archive；返回 unsupported 或 live pending |
| domain 未启用 | 返回 domain not enabled |
| codec 解码失败 | 返回 codec error，触发告警 |
| hot unwind 同时发生 | session snapshot 保持一致或返回 retryable |
| cold segment 缺失 | 返回 corrupted archive |

## 17. 测试计划

### 17.1 单元测试

- blockTag number -> `BLOCK_END`。
- `latest` 解析为 archive latest。
- `pending` 不走 archive。
- tx before/after statepoint 解析。
- ETH 20-byte 地址转 21-byte。
- base58/hex TRON 地址归一。
- storage slot padding。
- not found account/code/storage 的 API 返回。
- future state / archive start 前错误区分。
- domain disabled 错误。

### 17.2 与 TemporalStore 联测

构造历史：

```text
tx10: balance A=1, code C=v1, storage S=1
tx20: balance A=2, storage S=0
tx30: code C=v2
```

验证：

- `BLOCK_END(tx10 block)` 读到 v1/S=1。
- `TX_BEFORE(tx20)` 读到 balance=1。
- `TX_AFTER(tx20)` 读到 balance=2/storage zero。
- `TX_AFTER(tx30)` 读到 code v2。

### 17.3 历史 eth_call 测试

- 合约代码来自历史 code domain，而不是当前 code。
- storage 来自历史 storage domain。
- 动态参数来自历史 dynamic domain。
- 调用 overlay 写入不进入 archive。
- 缺失必要 domain 返回 unsupported。

### 17.4 一致性测试

- 单 session 内多次读取同一 key 命中 cache 且结果一致。
- 读 session 打开后 archive progress 前进，不影响 session 结果。
- freeze 期间打开 session，manifest view 一致。
- reorg/unwind 期间读 session 不混读。

### 17.5 range/prefix 测试

- 合约 storage prefix scan 只返回该合约 key。
- limit 生效。
- continuation token 生效。
- range 输出按 domain key 升序。
- deleted key 不出现在对应 asOfTxNum 的 range 中。

## 18. 验收标准

M2 级别：

- `eth_getBalance`、`eth_getCode`、`eth_getStorageAt` 可通过 ArchiveStateReader 读取历史 blockTag。
- Reader 不直接读 java-tron 当前 Store。
- blockTag/statePoint 到 asOfTxNum 的转换只在 TxNumIndex 中完成。
- not found、future、unsupported、corrupted 错误可区分。

M3 级别：

- `HistoricalTvmState` 支撑历史 `eth_call`。
- 合约元数据和动态参数不从当前 Store fallback。
- session cache 和一致读视图可用。

M4/M5 级别：

- prefix/range 查询可用于 proof/export/root rebuild。
- hot/cold segment 读视图一致。
- archive lag、reorg、freeze 中读行为明确。
- proof/debug API 能暴露 domain/value codec 版本和 statePoint。

## 19. 实现顺序建议

1. 定义 `StatePoint` 到 reader session 的入口，但实际 resolve 委托给 TxNumIndex。
2. 实现地址和 storage slot query codec 的调用，不手写 domain key。
3. 实现 `getRaw(domain, queryKey, statePoint)`。
4. 实现 `getAccount/getBalance/getCode/getStorageAt`。
5. 接入 ETH JSON-RPC blockTag 解析。
6. 增加 structured error 和 RPC error 映射。
7. 增加 per-session cache。
8. 实现 `HistoricalTvmState` 只读 adapter。
9. 接入动态参数/合约元数据 domain。
10. 实现 prefix/range API 和 continuation token。
11. 做 freeze/reorg/session snapshot 一致性测试。

第一版最重要的是阻止错误 fallback：如果历史 domain 不存在或 archive 未覆盖，就明确返回 unsupported。一个“看起来能返回结果但混入当前状态”的 Reader，比直接报错更危险。
