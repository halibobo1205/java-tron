# java-tron Archive L6：ArchiveStateReader + historical getters 代码级执行包

日期：2026-06-05

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`

上游总路线：[java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)

上游源码细化：[模块 05 ArchiveStateReader：4e80 java-tron 源码对照细化](./20260603-java-tron-module-05-state-reader-4e80-source-deep-dive.md)

来源大包：[java-tron Archive S8/S9：ArchiveStateReader 与 JSON-RPC Historical Getters 4e80 编码执行包](./20260603-java-tron-archive-s8-s9-state-reader-rpc-4e80-coding-packet.md)

前置执行包：

- [L2 Manager lifecycle + txNum 代码级执行包](./20260605-java-tron-archive-l2-manager-txnum-4e80-code-plan.md)：提供 persistent `txNum` 和 block range。
- [L3 ArchiveDomainRegistry 代码级执行包](./20260605-java-tron-archive-l3-domain-registry-4e80-code-plan.md)：提供 domain id、canonical key/value codec、domain policy。
- [L4 WriteCollector + Storage Semantic Hook 代码级执行包](./20260605-java-tron-archive-l4-write-collector-4e80-code-plan.md)：提供 ACCOUNT、CONTRACT、CODE、CONTRACT_STORAGE semantic write set。
- [L5 ArchiveTemporalStore 代码级执行包](./20260605-java-tron-archive-l5-temporal-store-4e80-code-plan.md)：提供 `ArchiveTemporalStore.getAsOf`、history/latest/changeset/progress 和 persistent txNum index。

本文只做 L6 规划，不修改 java-tron 源码。目标是把后续编码 patch 细化到类、方法、错误语义、测试用例和验收 gate。

## 1. L6 定位

L6 是 archive sidecar 的第一个 read-facing 模块。

它消费 L5 产物：

```text
ArchiveTemporalStore
PersistentArchiveTxNumIndex
ArchiveDomainRegistry
ArchiveValueCodec
ArchiveStoreKeyCodec
ArchiveStorageSemanticHook 产出的 CONTRACT_STORAGE semantic key
```

它向上提供：

```text
ArchiveStateReader
ArchiveStatePointResolver
ArchiveJsonRpcStateAdapter
eth_getBalance historical path
eth_getCode historical path
eth_getStorageAt historical path
```

它不参与：

```text
write collection
temporal apply/unwind
archive commitment/root/proof
historical eth_call
VM Repository 替换
```

L6 的核心原则：

```text
latest selector      -> 保留 java-tron 4e80 当前 Wallet/latest path
archive disabled    -> non-latest 保留当前 reject，或返回显式 archive disabled error
archive enabled     -> non-latest 解析成 ArchiveStatePoint，再读 ArchiveTemporalStore
archive gap/corrupt -> JSON-RPC internal error
missing object      -> 按 JSON-RPC state getter 语义渲染 zero/empty
```

禁止 silent fallback：

```text
historical query 不能在 archive 缺失时改读 latest Store
historical query 不能用 latest ContractStore 推断 storage key version
historical query 不能用 StorageRowStore physical key 读取 storage
historical query 不能把 codec/corrupt error 渲染成 missing
```

## 2. Erigon 源码依据

### 2.1 HistoryReaderV3 的状态点模型

Erigon V3 historical reader 构造时绑定 `txNum`：

| Erigon 源码 | 事实 | java-tron L6 映射 |
| --- | --- | --- |
| `execution/state/history_reader_v3.go:57-65` | `HistoryReaderV3` 持有 `txNum`、`TemporalTx`、可选 in-memory domains/cache | `DefaultArchiveStateReader` 持有 `ArchiveTemporalStore` 和 `ArchiveStatePoint` |
| `execution/state/history_reader_v3.go:67-69` | `NewHistoryReaderV3(ttx, txNum)` 只需要 temporal tx 和 txNum | `ArchiveStateReaderFactory.open(point)` 只选择 target txNum，不接 latest Store |
| `execution/state/history_reader_v3.go:95-153` | `getAsOf(domain,key)` 统一读取入口；可先查 block cache/shared domains，再落到 `ttx.GetAsOf` | java-tron P0 不需要 in-flight cache，直接走 `ArchiveTemporalStore.getAsOf(domain,key,txNum)` |

Erigon 的约束不是“读某个 block store”，而是“所有历史读最终落到 domain/key/txNum”。java-tron 应保持同样形状：

```text
JSON-RPC block selector
  -> ArchiveStatePoint(blockNum, blockHash, finalizeTxNum)
  -> ArchiveStateReader(point)
  -> ArchiveTemporalStore.getAsOf(domain, canonicalKey, finalizeTxNum)
```

不要让 RPC 层自己计算 `+1`、`before/after` 或 history table cursor。txNum 语义集中在 `ArchiveStatePoint` 和 reader factory。

### 2.2 Erigon account/code/storage reader

| Erigon 源码 | 事实 | java-tron L6 映射 |
| --- | --- | --- |
| `history_reader_v3.go:192-208` | `ReadAccountData` 读 `AccountsDomain`，missing/empty 返回 nil，present 再反序列化 | `getAccount(address21)` 读 `ACCOUNT`，missing 返回 `ArchiveReadResult.missing()`，present 构造 `AccountCapsule` 并校验 `getInstance()` |
| `history_reader_v3.go:217-229` | `ReadAccountStorage` 用 `address || slot` composite key 调 `getAsOf(StorageDomain, composite)` | TRON 用 `address21 || slot32 || storageKeyVersion_u8`，`storageKeyVersion` 来自 historical `CONTRACT` |
| `history_reader_v3.go:263-272` | `ReadAccountCode` 用 address 读 `CodeDomain`，返回 raw code bytes | `getCode(address21)` 读 `CODE` domain，missing 和 present empty 在 RPC 层都渲染为 `0x` |
| `history_reader_v3.go:275-278` | `ReadAccountCodeSize` 是 code bytes length 的派生能力 | L6 可不暴露 code size；后续 `eth_call`/debug 需要时再加 |

Erigon latest reader 的 storage path 也只用 domain canonical key：

| Erigon 源码 | 事实 | java-tron L6 映射 |
| --- | --- | --- |
| `execution/state/rw_v3.go:268-296` | storage write 用 `composite := address || key` 写 `StorageDomain` | java-tron L4 已把 storage semantic key 定为 `address21 || slot32 || version` |
| `execution/state/rw_v3.go:1495-1527` | latest storage reader 用同样 composite key 查 latest domain | L6 historical reader 必须复用 L4/L5 canonical key，不读 `StorageRowStore` |
| `execution/state/rw_v3.go:1529-1543` | latest code reader 用 address 查 `CodeDomain` | L6 historical code reader 读 `CODE` domain |

### 2.3 不照搬 Erigon 的部分

Erigon 的 `address || slot` 是 Ethereum 20-byte address + 32-byte storage slot。TRON 不可照搬：

```text
Ethereum / Erigon:
  storage key = address20 || slot32

TRON / java-tron L6:
  storage key = address21 || slot32 || storageKeyVersion_u8
```

差异来源：

- TRON canonical address 是 21 bytes，JSON-RPC 输入可为 20-byte ETH-like 或 21-byte TRON 地址。
- java-tron latest `Storage` 对 version 1 contract 会 hash slot，并且 create2 场景把 `address || trxHash` 参与 addrHash。
- Archive sidecar 不能保存 latest physical key，否则无法稳定服务 historical read 和 commitment。
- L4 已将 archive storage key 归一到 semantic key，并把 physical key 规则隔离在 latest execution path。

## 3. java-tron 4e80 源码事实

### 3.1 JSON-RPC 接口与实现

| java-tron 源码 | 当前事实 | L6 动作 |
| --- | --- | --- |
| `framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java:90-94` | `eth_getBalance` 只声明 `JsonRpcInvalidParamsException` | Java 接口新增 `JsonRpcInternalException` annotation/throws；JSON-RPC 方法名和参数不变 |
| `TronJsonRpc.java:96-101` | `eth_getStorageAt` 同上 | 同上 |
| `TronJsonRpc.java:103-108` | `eth_getCode` 的实现方法名是 `getABIOfSmartContract` | 不在 L6 改名，只补错误声明和实现分支 |
| `TronJsonRpcImpl.java:165-170` | 已有 `TAG_NOT_SUPPORT_ERROR`、`QUANTITY_NOT_SUPPORT_ERROR`、`NO_BLOCK_HEADER` | resolver/adapter 复用或保持兼容文案 |
| `TronJsonRpcImpl.java:387-397` | `requireLatestBlockTag` 只接受 latest，tag/quantity 都抛 invalid params | L6 三个 getter 不能在 historical path 之前调用它 |
| `TronJsonRpcImpl.java:457-470` | `getTrxBalance` guard 后调用 `wallet.getAccount` | latest 保留；historical 改走 `ArchiveJsonRpcStateAdapter.getBalance` |
| `TronJsonRpcImpl.java:611-631` | `getStorageAt` guard 后调用 latest `wallet.getContract` + `StorageRowStore` + `Storage` | latest 保留；historical 不构造 `Storage` |
| `TronJsonRpcImpl.java:635-649` | `eth_getCode` guard 后调用 `wallet.getContractInfo` | latest 保留；historical 读 archive `CODE` |

推荐方法分支：

```java
public String getTrxBalance(String address, String blockNumOrTag)
    throws JsonRpcInvalidParamsException, JsonRpcInternalException {
  if (archiveJsonRpcStateAdapter.shouldUseArchive(blockNumOrTag)) {
    return archiveJsonRpcStateAdapter.getBalance(address, blockNumOrTag);
  }

  requireLatestBlockTag(blockNumOrTag);
  ...
}
```

`shouldUseArchive` 不能简单等于 `!latest`。它需要包含配置判断：

```text
latest                         -> false
non-latest + archive disabled  -> false，交回 requireLatestBlockTag 保留当前 reject
non-latest + archive enabled   -> true
```

这样默认关闭时 `JsonrpcServiceTest` 的旧断言不需要全部翻转。

### 3.2 block selector parser

| java-tron 源码 | 当前事实 | L6 动作 |
| --- | --- | --- |
| `JsonRpcApiUtil.java:55-62` | 定义 `earliest/pending/latest/finalized/safe` 和 pending/safe unsupported 文案 | resolver 复用 tag 常量，不复制字符串 |
| `JsonRpcApiUtil.java:568-574` | `isBlockTag` 包含 `safe` | `safe` 不能被误当 quantity |
| `JsonRpcApiUtil.java:583-600` | `parseBlockTag`：latest=head、earliest=0、finalized=solid、pending/safe 抛 unsupported | archive enabled 下支持 earliest/finalized，pending/safe 仍拒绝 |
| `JsonRpcApiUtil.java:617-635` | `parseBlockNumber(String)` 支持 hex/decimal，拒绝 null/负数/overflow | historical quantity 用这个方法 |
| `JsonRpcApiUtil.java:643-648` | `parseBlockNumber(String, Wallet)` 对 non-tag 用严格 `jsonHexToLong` | L6 state getter 不用这个重载，避免改变当前 bare decimal 行为 |
| `Wallet.java:696-702` | `getBlockByNum` 缺失时返回 null | resolver 缺块必须 fail，不 fallback latest |
| `Wallet.java:715-720` | `getSolidBlockNum/getHeadBlockNum` 读 solid/head | finalized/latest 的 source |

L6 不接 EIP-1898 object selector。当前三个 Java 方法只接受 `String blockNumOrTag`，object-form block selector 留给 `eth_call`/debug 后续模块统一做。

### 3.3 latest Wallet path 禁区

这些方法只能保留在 latest 分支：

| java-tron 源码 | 当前事实 | historical 风险 |
| --- | --- | --- |
| `Wallet.java:332-355` | `getAccount` 读 latest `AccountStore`，并用 latest dynamic/account store 更新资源字段 | historical balance 被 latest resource processor 污染 |
| `Wallet.java:3179-3198` | `getContract` 读 latest account/contract/abi stores | historical storage key version 可能取到最新版本 |
| `Wallet.java:3208-3241` | `getContractInfo` 读 latest account/contract/abi/code/contract-state/dynamic | historical code 会混入 latest runtime code 和 latest contract state |
| `TronJsonRpcImpl.java:625-631` | `getStorageAt` latest path 构造 `Storage` 并读 physical row | historical storage 会读错 key space |

L6 reader 包不能依赖 `Wallet`。`Wallet` 只允许出现在 framework 层 resolver 里，用于解析 block/tag 和读取 block header。

### 3.4 capsule/value decode

| java-tron 源码 | 当前事实 | L6 decode 规则 |
| --- | --- | --- |
| `AccountCapsule.java:64-69` | `AccountCapsule(byte[])` parse 失败只 log，`account` 可能是 null | reader 构造后必须检查 `getInstance()`，null 映射 `CODEC_ERROR` |
| `AccountCapsule.java:253-259` | `getData()`/`getInstance()` 是 protobuf Account | `ACCOUNT` value 保存/读取 `getData()` bytes |
| `AccountCapsule.java:326-327` | `getBalance()` 返回 long | adapter 渲染 balance |
| `ContractCapsule.java:47-52` | parse 失败不抛，`smartContract` 可能是 null | reader 必须检查 `getInstance()` |
| `ContractCapsule.java:129-134` | 可取 `trxHash` 和 `contractVersion` | historical storage 只用 version suffix，不用 `trxHash` |
| `CodeCapsule.java:28-44` | raw bytecode wrapper | reader 可直接返回 code bytes |
| `DataWord.java:83-91` | slot/value 超过 32 bytes 抛 RuntimeException | 用户输入 slot 超长映射 invalid params；archive value 超长映射 internal/corrupt |
| `DataWord.java:268-274` | zero 判定按 32-byte word | storage missing/tombstone 渲染 32-byte zero |

### 3.5 Storage physical key 禁区

| java-tron 源码 | 当前事实 | L6 结论 |
| --- | --- | --- |
| `actuator/src/main/java/org/tron/core/vm/program/Storage.java:46-53` | physical key 使用 addrHash + slot suffix；version 1 会 hash slot | historical archive key 不使用 physical compose |
| `Storage.java:61-70` | create2 场景会把 `address || trxHash` 参与 addrHash | archive semantic key 不加入 trxHash |
| `Storage.java:73-83` | `getValue` 从 `StorageRowStore` 读 physical row | historical reader 禁止访问 `StorageRowStore` |

L6 storage canonical key 固定为：

```text
address21 || slot32 || storageKeyVersion_u8
```

`storageKeyVersion_u8` 的来源：

```text
historical CONTRACT domain value
  -> ContractCapsule
  -> smartContract.getVersion()
  -> version == 1 ? 0x01 : 0x00
```

如果 historical `CONTRACT` missing：

```text
getStorage(address, slot, point) -> MISSING
RPC eth_getStorageAt           -> 32-byte zero
```

不能用 latest `wallet.getContract` 补 version。

## 4. 文件级落点

### 4.1 chainbase reader core

新增：

```text
chainbase/src/main/java/org/tron/core/archive/reader/
  ArchiveStatePoint.java
  ResolvedArchiveStatePoint.java
  ArchiveReadResult.java
  ArchiveReaderException.java
  ArchiveStateReader.java
  DefaultArchiveStateReader.java
  ArchiveStateReaderFactory.java
  ArchiveStorageKeyCodec.java
```

依赖方向：

```text
chainbase/archive/reader
  -> chainbase/archive/domain
  -> chainbase/archive/temporal
  -> chainbase/capsule
  -> protocol protobuf classes

chainbase/archive/reader
  -X-> framework
  -X-> Wallet
  -X-> Manager
  -X-> JsonRpcApiUtil
  -X-> StorageRowStore
```

### 4.2 framework resolver/adapter

新增：

```text
framework/src/main/java/org/tron/core/archive/reader/
  JsonRpcArchiveStatePointResolver.java

framework/src/main/java/org/tron/core/services/jsonrpc/
  ArchiveJsonRpcStateAdapter.java
```

修改：

```text
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java
framework/src/main/java/org/tron/core/services/jsonrpc/JsonRpcApiUtil.java   inspect/only if helper extraction is needed
```

`JsonRpcArchiveStatePointResolver` 放在 `framework/src/main/java/org/tron/core/archive/reader`，因为它依赖 `Wallet` 和 JSON-RPC block selector 语义。`ArchiveJsonRpcStateAdapter` 放在 jsonrpc service 包，因为它负责把 reader 结果渲染成 JSON hex 和 JSON-RPC exception。

## 5. 核心数据结构

### 5.1 ArchiveStatePoint

文件：

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

语义：

| Kind | 含义 | temporal read |
| --- | --- | --- |
| `BLOCK_END` | block finalize 完成后的状态 | `getAsOf(..., finalizeTxNum)` |
| `TX_BEFORE` | 某用户交易执行前状态 | `getBeforeTx(..., userTxNum)`，L6 预留 |
| `TX_AFTER` | 某用户交易执行后状态 | `getAfterTx(..., userTxNum)`，L6 预留 |
| `SYSTEM_AFTER` | block system/finalize tx 后状态 | `getAfterTx(..., systemTxNum)`，L6 预留 |

L6 JSON-RPC historical getters 只创建 `BLOCK_END`。

字段规则：

```text
blockNum  >= 0
txNum     >= 0
blockHash null only for non-block internal state point
blockHash copied defensively
```

### 5.2 ResolvedArchiveStatePoint

文件：

```text
chainbase/src/main/java/org/tron/core/archive/reader/ResolvedArchiveStatePoint.java
```

建议用途：

```text
resolver 返回值需要区分 latest 和 archive point
```

结构：

```java
public final class ResolvedArchiveStatePoint {
  public enum Mode {
    LATEST,
    ARCHIVE
  }

  private final Mode mode;
  private final ArchiveStatePoint point;
}
```

也可以用两个 factory 方法：

```java
ResolvedArchiveStatePoint.latest()
ResolvedArchiveStatePoint.archive(ArchiveStatePoint point)
```

约束：

```text
mode == LATEST  -> point == null
mode == ARCHIVE -> point != null
```

### 5.3 ArchiveReadResult

文件：

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveReadResult.java
```

建议结构：

```java
public final class ArchiveReadResult<T> {
  public enum Status {
    PRESENT,
    TOMBSTONE,
    MISSING
  }

  public static <T> ArchiveReadResult<T> present(T value);
  public static <T> ArchiveReadResult<T> tombstone();
  public static <T> ArchiveReadResult<T> missing();

  public boolean isPresent();
  public T getValue();
}
```

不用裸 `Optional` 的原因：

- `missing account` 在 `eth_getBalance` 渲染为 `0x0`。
- `missing code` 和 `present empty code` 都渲染为 `0x`，但 debug/proof 后续可能需要区分。
- `missing storage` 和 `present zero storage` 都渲染为 32-byte zero。
- `tombstone` 表示 L5 明确记录过删除；JSON-RPC state getter 按 missing 渲染，proof/debug 可以保留该状态。
- codec/corrupt error 不能伪装成 missing。

### 5.4 ArchiveReaderException

文件：

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveReaderException.java
```

Reason：

```text
ARCHIVE_DISABLED
HISTORY_UNAVAILABLE
DOMAIN_UNSUPPORTED
CODEC_ERROR
CORRUPT_VALUE
INTERNAL_IO
```

映射原则：

| Reason | JSON-RPC 映射 | 说明 |
| --- | --- | --- |
| `ARCHIVE_DISABLED` | `JsonRpcInternalException` 或交给 latest-only guard 保持 invalid params | 取决于调用路径；默认关闭建议保留当前 reject |
| `HISTORY_UNAVAILABLE` | `JsonRpcInternalException` | block/txNum 不在 archive 覆盖范围 |
| `DOMAIN_UNSUPPORTED` | `JsonRpcInternalException` | registry/schema 不完整 |
| `CODEC_ERROR` | `JsonRpcInternalException` | protobuf parse null、domain codec 失败 |
| `CORRUPT_VALUE` | `JsonRpcInternalException` | storage value > 32 bytes 等 |
| `INTERNAL_IO` | `JsonRpcInternalException` | raw store/temporal store 异常 |

`JsonRpcInvalidParamsException` 只用于用户参数错误：

```text
bad address
bad slot
bad block number
pending/safe unsupported
block header not found if existing API treats it as invalid params
```

## 6. Reader API

文件：

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStateReader.java
```

建议接口：

```java
public interface ArchiveStateReader extends AutoCloseable {
  ArchiveStatePoint getPoint();

  ArchiveReadResult<AccountCapsule> getAccount(byte[] address)
      throws ArchiveReaderException;

  ArchiveReadResult<ContractCapsule> getContract(byte[] address)
      throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getCode(byte[] address)
      throws ArchiveReaderException;

  ArchiveReadResult<byte[]> getStorage(byte[] address, byte[] slot)
      throws ArchiveReaderException;

  @Override
  void close();
}
```

输入约束：

```text
address length == 21
slot length == 32
```

返回约束：

```text
getAccount missing  -> ArchiveReadResult.missing()
getContract missing -> ArchiveReadResult.missing()
getCode missing     -> ArchiveReadResult.missing()
getCode present     -> defensive copy of raw code bytes
getStorage missing  -> ArchiveReadResult.missing()
getStorage present  -> defensive copy, length <= 32
```

不要把 JSON-RPC zero/empty 渲染塞进 `ArchiveStateReader`。reader 只表达 archive domain 中是否存在对象和 raw value 是什么。

## 7. DefaultArchiveStateReader 实现

文件：

```text
chainbase/src/main/java/org/tron/core/archive/reader/DefaultArchiveStateReader.java
```

构造依赖：

```java
public DefaultArchiveStateReader(
    ArchiveTemporalStore temporalStore,
    ArchiveDomainRegistry domainRegistry,
    ArchiveStatePoint point) {
  ...
}
```

内部辅助：

```java
private ArchiveReadResult<byte[]> getRaw(ArchiveDomainName domain, byte[] key)
    throws ArchiveReaderException {
  ArchiveDomainDescriptor descriptor = domainRegistry.require(domain);
  ArchiveStoredValue stored = temporalStore.getAsOf(descriptor, key, point.getTxNum());
  return toReadResult(stored);
}
```

`getRaw` 必须做：

```text
1. 校验 point.kind 在 L6 支持范围内。
2. 校验 domain 存在且 reader policy 支持 historical read。
3. 调 `ArchiveTemporalStore.getAsOf`，并把 `PRESENT/TOMBSTONE/MISSING` 映射到 `ArchiveReadResult`。
4. 把 temporal store 的 gap/corrupt/io error 转成 `ArchiveReaderException`。
5. 对 present bytes 做 defensive copy。
```

### 7.1 getAccount

流程：

```text
validate address21
raw = getRaw(ACCOUNT, address21)
if missing/tombstone -> missing
capsule = new AccountCapsule(raw)
if capsule.getInstance() == null -> CODEC_ERROR
return present(capsule)
```

不要调用：

```text
Wallet.getAccount
AccountStore.get
BandwidthProcessor
EnergyProcessor
```

### 7.2 getContract

流程：

```text
validate address21
raw = getRaw(CONTRACT, address21)
if missing/tombstone -> missing
capsule = new ContractCapsule(raw)
if capsule.getInstance() == null -> CODEC_ERROR
return present(capsule)
```

不要调用：

```text
Wallet.getContract
ContractStore.get
AbiStore.get
```

L6 不需要 ABI。`CONTRACT` 只用于 storage key version，后续 debug/proof/eth_call 可复用。

### 7.3 getCode

流程：

```text
validate address21
raw = getRaw(CODE, address21)
if missing/tombstone -> missing
return present(copy(raw))
```

不要调用：

```text
Wallet.getContractInfo
CodeStore.get
ContractCapsule.setRuntimecode
```

### 7.4 getStorage

流程：

```text
validate address21
slot32 = normalizeSlot(slot)
contract = getContract(address21)
if contract missing -> missing
version = contract.getValue().getContractVersion()
key = ArchiveStorageKeyCodec.contractStorageKey(address21, slot32, version)
raw = getRaw(CONTRACT_STORAGE, key)
if missing/tombstone -> missing
if raw.length > 32 -> CORRUPT_VALUE
return present(copy(raw))
```

`contract missing -> storage missing` 是 RPC state getter 语义，不是 archive gap。它表示 requested block 中该 address 没有 contract metadata，因此 slot 读为 zero。

`raw.length == 0`：

```text
如果 L5 返回 tombstone，L6 state getter adapter 应按 missing/zero 渲染；proof/debug 可以保留 tombstone 状态。
如果某个旧数据路径返回 empty bytes，reader 也可以当 missing/zero，但必须在 L5 codec 文档中统一。
```

推荐：L5 `getAsOf` 返回 typed `ArchiveStoredValue`，L6 将 tombstone 转为 `ArchiveReadResult.tombstone()`，JSON-RPC adapter 再按 missing 渲染。

## 8. ArchiveStorageKeyCodec

文件：

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStorageKeyCodec.java
```

职责：

```text
把 RPC slot + historical contract version 转成 CONTRACT_STORAGE canonical key
```

建议 API：

```java
public final class ArchiveStorageKeyCodec {
  public static byte[] contractStorageKey(byte[] address, byte[] slot, int contractVersion);
  public static byte storageKeyVersion(int contractVersion);
}
```

规则：

```text
address.length == 21
slot.length == 32
storageKeyVersion = contractVersion == 1 ? 0x01 : 0x00
key.length = 21 + 32 + 1 = 54
```

禁止：

```text
Hash.sha3(slot)
Hash.sha3(address)
Hash.sha3(address || trxHash)
Storage.compose(...)
Storage.generateAddrHash(...)
```

这些都属于 latest physical storage key 规则，不属于 archive semantic key。

## 9. ArchiveStateReaderFactory

文件：

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStateReaderFactory.java
```

建议接口：

```java
public interface ArchiveStateReaderFactory {
  ArchiveStateReader open(ArchiveStatePoint point) throws ArchiveReaderException;
}
```

默认实现可以是：

```text
DefaultArchiveStateReaderFactory
  -> ArchiveTemporalStore
  -> ArchiveDomainRegistry
```

也可以先把 factory 做成简单 class，不额外抽接口。是否抽接口取决于 framework 测试是否需要 fake reader factory。

推荐为了 `TronJsonRpcHistoricalGettersTest` 易测，保留接口：

```java
public final class DefaultArchiveStateReaderFactory implements ArchiveStateReaderFactory {
  ...
}
```

## 10. StatePoint Resolver

### 10.1 接口归属

reader core 不依赖 JSON-RPC；resolver 可以拆两层：

```text
chainbase/archive/reader/ArchiveStatePointResolver.java
framework/archive/reader/JsonRpcArchiveStatePointResolver.java
```

接口：

```java
public interface ArchiveStatePointResolver {
  ResolvedArchiveStatePoint resolveBlockEnd(String blockNumOrTag)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;
}
```

如果不希望 chainbase 依赖 JSON-RPC exception，则接口放 framework：

```text
framework/src/main/java/org/tron/core/archive/reader/ArchiveStatePointResolver.java
```

推荐方案：

```text
chainbase 不放 resolver interface
framework 放 JsonRpcArchiveStatePointResolver
```

理由：

- 解析 JSON-RPC tag 是 framework 职责。
- `JsonRpcInvalidParamsException` 不应进入 chainbase。
- L8 historical eth_call 可以复用 framework resolver，必要时再抽无异常的 core resolver。

### 10.2 JsonRpcArchiveStatePointResolver

文件：

```text
framework/src/main/java/org/tron/core/archive/reader/JsonRpcArchiveStatePointResolver.java
```

构造依赖：

```java
public JsonRpcArchiveStatePointResolver(
    Wallet wallet,
    PersistentArchiveTxNumIndex txNumIndex,
    ArchiveService archiveService) {
  ...
}
```

`ArchiveService` 或 `ArchiveManager` 用于判断 archive enabled/ready。实际类型跟 L1/L2 定稿保持一致。

核心流程：

```text
resolveBlockEnd(blockNumOrTag):
  if latest:
      return ResolvedArchiveStatePoint.latest()

  if archive disabled:
      return ResolvedArchiveStatePoint.latestOnlyUnsupported()
      或由 adapter 直接返回 disabled=false，让 TronJsonRpcImpl 调 requireLatestBlockTag

  if pending or safe:
      throw JsonRpcInvalidParamsException(existing unsupported)

  if earliest:
      blockNum = 0
  else if finalized:
      blockNum = wallet.getSolidBlockNum()
  else:
      blockNum = JsonRpcApiUtil.parseBlockNumber(blockNumOrTag)

  block = wallet.getBlockByNum(blockNum)
  if block == null:
      throw JsonRpcInvalidParamsException(NO_BLOCK_HEADER)

  range = txNumIndex.getBlockRange(blockNum)
  if range missing:
      throw JsonRpcInternalException("archive history unavailable")

  return archive(BLOCK_END(blockNum, blockHash, range.finalizeTxNum))
```

`latest` 必须 bypass archive。即使 archive enabled，`eth_getBalance(..., latest)` 也保持当前 `wallet.getAccount` 语义。

### 10.3 disabled mode

默认关闭是重要兼容性边界。

推荐实现：

```text
ArchiveJsonRpcStateAdapter.shouldUseArchive(blockNumOrTag):
  if latest -> false
  if archive disabled -> false
  return true

TronJsonRpcImpl method:
  if shouldUseArchive(blockNumOrTag):
      return archiveAdapter.get...
  requireLatestBlockTag(blockNumOrTag)
  return existing latest logic
```

这样 disabled mode 下，当前 non-latest 错误保持：

```text
tag      -> TAG [earliest | pending | finalized | safe] not supported
quantity -> QUANTITY not supported, just support TAG as latest
```

如果后续希望 archive disabled 返回更明确的 internal error，需要单独改 `JsonrpcServiceTest`，不建议混入 L6 首版。

## 11. ArchiveJsonRpcStateAdapter

文件：

```text
framework/src/main/java/org/tron/core/services/jsonrpc/ArchiveJsonRpcStateAdapter.java
```

职责：

```text
1. 判断 historical getter 是否应走 archive。
2. 解析 address/slot。
3. 调 resolver 得到 ArchiveStatePoint。
4. 打开 ArchiveStateReader。
5. 把 reader result 渲染为 JSON-RPC hex。
6. 把 ArchiveReaderException 映射为 JsonRpcInternalException。
```

建议接口：

```java
public final class ArchiveJsonRpcStateAdapter {
  public boolean shouldUseArchive(String blockNumOrTag);

  public String getBalance(String address, String blockNumOrTag)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;

  public String getCode(String address, String blockNumOrTag)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;

  public String getStorageAt(String address, String storageIdx, String blockNumOrTag)
      throws JsonRpcInvalidParamsException, JsonRpcInternalException;
}
```

输入解析：

```text
address -> JsonRpcApiUtil.addressCompatibleToByteArray(address)
slot    -> ByteArray.fromHexString(storageIdx) -> new DataWord(...) -> getData()
block   -> JsonRpcArchiveStatePointResolver.resolveBlockEnd(blockNumOrTag)
```

`DataWord` 构造可能因为 slot > 32 bytes 抛 `RuntimeException`。adapter 必须 catch 并转成 `JsonRpcInvalidParamsException`。

### 11.1 getBalance 渲染

流程：

```text
address21 = addressCompatibleToByteArray(address)
point = resolver.resolveBlockEnd(blockNumOrTag)
reader = readerFactory.open(point)
account = reader.getAccount(address21)
if missing -> return ByteArray.toJsonHex(0L)
return ByteArray.toJsonHex(account.getValue().getBalance())
```

缺失 account 是状态不存在，不是 archive gap。

### 11.2 getCode 渲染

流程：

```text
address21 = addressCompatibleToByteArray(contractAddress)
point = resolver.resolveBlockEnd(blockNumOrTag)
reader = readerFactory.open(point)
code = reader.getCode(address21)
if missing -> return "0x"
return ByteArray.toJsonHex(code.getValue())
```

present empty code 也会返回 `0x`。

### 11.3 getStorageAt 渲染

流程：

```text
address21 = addressCompatibleToByteArray(address)
slot32 = normalizeSlot(storageIdx)
point = resolver.resolveBlockEnd(blockNumOrTag)
reader = readerFactory.open(point)
value = reader.getStorage(address21, slot32)
if missing -> return ByteArray.toJsonHex(new byte[32])
return ByteArray.toJsonHex(leftPad32(value.getValue()))
```

`leftPad32` 规则：

```text
len == 32 -> return copy
len <  32 -> left pad with zeros to 32
len >  32 -> JsonRpcInternalException(corrupt archive storage value)
```

可以复用 `new DataWord(value).getData()`，但要把 RuntimeException 映射为 internal/corrupt。

## 12. TronJsonRpcImpl 修改点

### 12.1 字段注入

当前 `TronJsonRpcImpl` 已注入大量 service/store。L6 新增：

```java
@Autowired
private ArchiveJsonRpcStateAdapter archiveJsonRpcStateAdapter;
```

如果 archive 模块默认关闭时不创建 bean，则要提供 no-op adapter：

```text
NoopArchiveJsonRpcStateAdapter.shouldUseArchive(...) -> false
```

推荐 L1/L2 就提供 default-off no-op service，L6 只注入接口。

### 12.2 eth_getBalance

当前：

```java
requireLatestBlockTag(blockNumOrTag);
byte[] addressData = addressCompatibleToByteArray(address);
Account reply = wallet.getAccount(account);
...
```

L6：

```java
if (archiveJsonRpcStateAdapter.shouldUseArchive(blockNumOrTag)) {
  return archiveJsonRpcStateAdapter.getBalance(address, blockNumOrTag);
}

requireLatestBlockTag(blockNumOrTag);
...
```

latest path 下原代码不动。

### 12.3 eth_getStorageAt

当前：

```java
requireLatestBlockTag(blockNumOrTag);
byte[] addressByte = addressCompatibleToByteArray(address);
SmartContract smartContract = wallet.getContract(bytesMessage);
StorageRowStore store = manager.getStorageRowStore();
Storage storage = new Storage(addressByte, store);
storage.setContractVersion(smartContract.getVersion());
storage.generateAddrHash(smartContract.getTrxHash().toByteArray());
DataWord value = storage.getValue(new DataWord(ByteArray.fromHexString(storageIdx)));
```

L6：

```java
if (archiveJsonRpcStateAdapter.shouldUseArchive(blockNumOrTag)) {
  return archiveJsonRpcStateAdapter.getStorageAt(address, storageIdx, blockNumOrTag);
}

requireLatestBlockTag(blockNumOrTag);
...
```

historical path 不能复用任何 `Storage` 对象。

### 12.4 eth_getCode

当前 Java 方法名：

```java
getABIOfSmartContract(String contractAddress, String blockNumOrTag)
```

L6 不改名，只在方法开头加 archive branch：

```java
if (archiveJsonRpcStateAdapter.shouldUseArchive(blockNumOrTag)) {
  return archiveJsonRpcStateAdapter.getCode(contractAddress, blockNumOrTag);
}

requireLatestBlockTag(blockNumOrTag);
...
```

### 12.5 eth_call 保持不变

`TronJsonRpcImpl.getCall` 当前 object block param 校验后仍会把 block param 重写为 `latest`。L6 不碰它。

禁止在 L6 做：

```text
eth_call historical branch
Repository adapter
VM dynamic properties snapshot
actuator execution path
```

这些属于 L8。

## 13. TronJsonRpc 接口签名

文件：

```text
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java
```

三个方法新增 internal error annotation：

```java
@JsonRpcErrors({
    @JsonRpcError(exception = JsonRpcInvalidParamsException.class, code = -32602, data = "{}"),
    @JsonRpcError(exception = JsonRpcInternalException.class, code = -32000, data = "{}"),
})
String getTrxBalance(String address, String blockNumOrTag)
    throws JsonRpcInvalidParamsException, JsonRpcInternalException;
```

`getStorageAt`、`getABIOfSmartContract` 同样处理。

外部 JSON-RPC API 不变：

```text
eth_getBalance(address, block)
eth_getStorageAt(address, slot, block)
eth_getCode(address, block)
```

只增加 historical archive 错误能被 JSON-RPC 框架按 internal error 返回。

## 14. 错误语义

### 14.1 invalid params

继续使用 `JsonRpcInvalidParamsException`：

```text
address 格式错误
slot 格式错误
slot > 32 bytes
block number 格式错误
block number overflow
pending/safe unsupported
block header not found（如果沿用现有 block API 口径）
archive disabled 且调用回落到 requireLatestBlockTag 的 non-latest request
```

### 14.2 internal error

使用 `JsonRpcInternalException`：

```text
archive enabled 但 block txNum range missing
ArchiveTemporalStore.getAsOf 抛 IO/internal
domain registry 缺 ACCOUNT/CONTRACT/CODE/CONTRACT_STORAGE
protobuf capsule decode 后 instance == null
storage value length > 32
temporal history/checksum/corrupt
```

### 14.3 missing state

不是错误：

| 对象 | reader result | RPC result |
| --- | --- | --- |
| account missing | `MISSING` | `0x0` |
| code missing | `MISSING` | `0x` |
| contract missing for storage | `MISSING` | 32-byte zero |
| storage slot missing | `MISSING` | 32-byte zero |

关键区别：

```text
missing object       -> requested historical state 下对象不存在
history unavailable  -> archive 没有能力回答这个 state point
```

后者必须报错，不能返回 zero/empty。

## 15. 测试计划

### 15.1 chainbase 单元测试

新增：

```text
chainbase/src/test/java/org/tron/core/archive/reader/DefaultArchiveStateReaderTest.java
chainbase/src/test/java/org/tron/core/archive/reader/ArchiveStorageKeyCodecTest.java
```

`DefaultArchiveStateReaderTest` 覆盖：

| 用例 | Arrange | Assert |
| --- | --- | --- |
| `getAccountAtPoint` | fake temporal ACCOUNT 在 txNum 10/20 返回不同 balance | point 10/20 读到不同 account |
| `missingAccountIsNotError` | ACCOUNT missing | result missing |
| `corruptAccountIsCodecError` | ACCOUNT value 非 protobuf | `ArchiveReaderException.CODEC_ERROR` |
| `getCodeAtPoint` | CODE 在 deploy 前 missing、deploy 后 present | deploy 前 missing，deploy 后 bytes |
| `getContractAtPoint` | CONTRACT present | version 可读 |
| `storageUsesHistoricalContractVersion0` | CONTRACT version 0，slot same | key suffix `0x00` |
| `storageUsesHistoricalContractVersion1` | CONTRACT version 1，slot same | key suffix `0x01` |
| `storageDoesNotUseLatestContractVersion` | fake temporal 只暴露历史 CONTRACT | 不需要 latest contract |
| `storageMissingContractReturnsMissing` | CONTRACT missing | storage result missing |
| `storageValueTooLongIsCorrupt` | CONTRACT_STORAGE value 33 bytes | `CORRUPT_VALUE` |
| `historyGapIsNotMissing` | temporal store 返回 history unavailable | `HISTORY_UNAVAILABLE` |

`ArchiveStorageKeyCodecTest` 覆盖：

```text
address 20 bytes rejected
address 21 bytes accepted
slot 31/33 bytes rejected
slot 32 bytes accepted
version 0 -> suffix 0
version 1 -> suffix 1
version 2 -> suffix 0
key length == 54
key prefix == address21 || slot32
no hash/physical compose
```

### 15.2 framework resolver 测试

新增：

```text
framework/src/test/java/org/tron/core/archive/reader/JsonRpcArchiveStatePointResolverTest.java
```

覆盖：

| 用例 | Assert |
| --- | --- |
| latest returns latest mode | 不查 txNum index |
| earliest maps block 0 | 返回 block 0 finalizeTxNum |
| finalized maps solid block | 使用 `wallet.getSolidBlockNum()` |
| hex quantity maps block | `0x2a` 解析为 42 |
| decimal quantity maps block | `42` 解析为 42，保持 `parseBlockNumber(String)` 兼容 |
| pending unsupported | invalid params |
| safe unsupported | invalid params |
| missing block | invalid params/header not found |
| missing txNum range | internal archive history unavailable |
| archive disabled | 返回 latest-only unsupported mode 或让 adapter 不启用 archive |

### 15.3 framework adapter 测试

新增：

```text
framework/src/test/java/org/tron/core/services/jsonrpc/ArchiveJsonRpcStateAdapterTest.java
```

覆盖：

| 用例 | Assert |
| --- | --- |
| balance missing account | `0x0` |
| balance present account | `ByteArray.toJsonHex(balance)` |
| code missing | `0x` |
| code present empty | `0x` |
| code present bytes | `0x...` |
| storage missing contract | 32-byte zero |
| storage missing slot | 32-byte zero |
| storage present short value | left-pad to 32 bytes |
| storage present 32 bytes | unchanged |
| storage corrupt >32 | internal error |
| bad address | invalid params |
| bad slot | invalid params |
| reader codec error | internal error |
| shouldUseArchive latest | false |
| shouldUseArchive non-latest disabled | false |
| shouldUseArchive non-latest enabled | true |

### 15.4 JSON-RPC integration/regression

新增：

```text
framework/src/test/java/org/tron/core/services/jsonrpc/TronJsonRpcHistoricalGettersTest.java
```

修改/保留：

```text
framework/src/test/java/org/tron/core/jsonrpc/JsonrpcServiceTest.java
```

`TronJsonRpcHistoricalGettersTest` 覆盖：

| 用例 | Assert |
| --- | --- |
| `eth_getBalance(latest)` | 仍调用 latest path |
| `eth_getBalance(0x1)` archive enabled | 返回 fake archive balance，不等于 latest |
| `eth_getCode(0x1)` before deploy | `0x` |
| `eth_getCode(0x2)` after deploy | 返回 fake runtime code |
| `eth_getStorageAt(0x1)` | 返回 historical slot，不等于 latest physical store |
| missing historical point | JSON-RPC internal error |
| archive disabled non-latest | 保持当前 quantity/tag unsupported |
| pending/safe | unsupported |

`JsonrpcServiceTest` 当前 4e80 在 `JsonrpcServiceTest.java:524-590` 已断言 non-latest state getter 被拒绝。L6 不能直接删除这些断言。建议拆成两组：

```text
archive disabled/current behavior regression:
  non-latest state getter still rejects

archive enabled historical behavior:
  moved to TronJsonRpcHistoricalGettersTest
```

## 16. Patch 拆分顺序

### L6a：reader model + exceptions

文件：

```text
ArchiveStatePoint.java
ResolvedArchiveStatePoint.java
ArchiveReadResult.java
ArchiveReaderException.java
ArchiveStorageKeyCodec.java
```

测试：

```text
ArchiveStorageKeyCodecTest
```

验收：

```text
不引入 framework 依赖
不触碰 TronJsonRpcImpl
```

### L6b：DefaultArchiveStateReader

文件：

```text
ArchiveStateReader.java
DefaultArchiveStateReader.java
ArchiveStateReaderFactory.java
```

测试：

```text
DefaultArchiveStateReaderTest
```

验收：

```text
account/contract/code/storage 都通过 temporal getAsOf
storage key version 来自 historical CONTRACT
missing/gap/corrupt 区分清楚
```

### L6c：JSON-RPC resolver

文件：

```text
JsonRpcArchiveStatePointResolver.java
```

测试：

```text
JsonRpcArchiveStatePointResolverTest
```

验收：

```text
latest bypass archive
earliest/finalized/quantity 能解析到 block-end txNum
pending/safe/缺块/gap 错误明确
```

### L6d：ArchiveJsonRpcStateAdapter

文件：

```text
ArchiveJsonRpcStateAdapter.java
Noop adapter 或 disabled service wiring
```

测试：

```text
ArchiveJsonRpcStateAdapterTest
```

验收：

```text
渲染语义与 JSON-RPC 一致
reader exception 映射 internal
bad input 映射 invalid params
```

### L6e：TronJsonRpcImpl 接入

文件：

```text
TronJsonRpc.java
TronJsonRpcImpl.java
```

测试：

```text
TronJsonRpcHistoricalGettersTest
JsonrpcServiceTest disabled regression
```

验收：

```text
latest 行为不变
archive disabled non-latest 行为不变
archive enabled non-latest 命中 adapter
eth_call 仍不支持 historical
```

## 17. 验证命令

L6 局部 gate：

```bash
./gradlew :chainbase:test --tests '*ArchiveStorageKeyCodecTest'
./gradlew :chainbase:test --tests '*DefaultArchiveStateReaderTest'
./gradlew :framework:test --tests '*JsonRpcArchiveStatePointResolverTest'
./gradlew :framework:test --tests '*ArchiveJsonRpcStateAdapterTest'
./gradlew :framework:test --tests '*TronJsonRpcHistoricalGettersTest'
./gradlew :framework:test --tests 'org.tron.core.jsonrpc.JsonrpcServiceTest'
./gradlew checkstyleMain checkstyleTest
```

L6 不要求跑 full build，但合入前至少需要：

```bash
./gradlew :chainbase:test
./gradlew :framework:test
./gradlew checkstyleMain checkstyleTest
```

如果后续把 reader 接到 L5 actual `ArchiveRawStore`，再补：

```bash
./gradlew :chainbase:test --tests '*ArchiveTemporalStoreManagerWiringTest'
```

## 18. Review checklist

代码 review 时逐项检查：

- `DefaultArchiveStateReader` 没有 import `Wallet`、`Manager`、`StorageRowStore`。
- `ArchiveJsonRpcStateAdapter` 是唯一负责 JSON hex 渲染的 archive state getter 层。
- `TronJsonRpcImpl` 三个 getter 的 latest 分支和 4e80 原逻辑等价。
- archive disabled 下 non-latest request 没有悄悄变成 archive error，除非测试同步改口径。
- archive enabled 下 non-latest request 不调用 `requireLatestBlockTag`。
- `getStorage` 先读 historical `CONTRACT` 再构造 `CONTRACT_STORAGE` key。
- `CONTRACT_STORAGE` key 长度为 54，suffix 只有 0 或 1。
- storage historical path 没有 `new Storage(...)`。
- missing object 和 history gap 的测试都存在。
- `JsonRpcInternalException` annotation 已添加到 `TronJsonRpc.java`。
- `eth_call` 没被混入 L6。

## 19. 与后续模块的接口

### 19.1 给 L8 historical eth_call

L8 需要复用：

```text
ArchiveStatePoint
ArchiveStateReader
ArchiveStateReaderFactory
JsonRpcArchiveStatePointResolver
```

但 L8 还需要额外能力：

```text
Archive-backed Repository
historical DynamicProperties view
historical Account/Contract/Storage mutation sandbox
TVM constant call state adapter
object-form block selector
```

L6 不提前实现这些。

### 19.2 给 L7 CommitmentBuilder

L7 不应依赖 JSON-RPC adapter，但可复用：

```text
ArchiveDomainRegistry
ArchiveTemporalStore
ArchiveStorageKeyCodec
```

如果 L7 rebuild verifier 需要按 point 读取 account/code/storage，可复用 `ArchiveStateReader`，但 root 构建本身应直接遍历 temporal latest/domain data，不通过 RPC adapter。

## 20. 风险与收敛

### 风险 1：disabled mode 错误口径

当前 state getter non-latest 会报 latest-only unsupported。archive disabled 后如果改成 `archive disabled` internal error，会破坏已有 regression。

收敛：

```text
L6 首版默认 disabled 仍走 requireLatestBlockTag
archive enabled 才进入 resolver/reader
```

### 风险 2：storage key version 取错来源

如果从 latest `wallet.getContract` 取 version，历史 slot 会在 contract version 变化或 create2 场景下读错。

收敛：

```text
reader.getStorage 必须先 reader.getContract(point)
测试 fake latest version 与 historical version 不同
断言仍命中 historical suffix
```

### 风险 3：missing 与 gap 混淆

archive 未覆盖某高度时返回 zero 会制造看似正确的假结果。

收敛：

```text
ArchiveTemporalStore 明确返回 HISTORY_UNAVAILABLE
ArchiveReadResult 只表示对象 missing/present
reader exception 表示能力失败
```

### 风险 4：code domain 与 contract metadata 混用

`Wallet.getContractInfo` 当前会把 latest CodeStore runtime code 塞进 wrapper。historical `eth_getCode` 如果读 CONTRACT 而不是 CODE，会拿不到准确 runtime code。

收敛：

```text
eth_getCode historical path 只读 CODE domain
CONTRACT domain 只服务 storage version/debug metadata
```

## 21. DONE 定义

L6 完成必须同时满足：

1. `DefaultArchiveStateReader` 可按 `ArchiveStatePoint` 读取 ACCOUNT、CONTRACT、CODE、CONTRACT_STORAGE。
2. `eth_getBalance/latest`、`eth_getCode/latest`、`eth_getStorageAt/latest` 保持 4e80 当前路径。
3. archive disabled 时 non-latest state getter 保持当前 reject，或有明确测试说明新的 disabled error 口径。
4. archive enabled 时 non-latest state getter 命中 archive reader。
5. historical getter 请求未覆盖高度时返回 internal error，不 fallback latest。
6. missing account/code/storage 按 JSON-RPC state getter 语义返回 zero/empty。
7. historical storage 不访问 `StorageRowStore`，不使用 physical key，不使用 latest contract version。
8. `eth_call(non-latest)` 仍保持未实现/unsupported，不被 L6 半接入。
9. L6 局部测试和 checkstyle gate 通过。
