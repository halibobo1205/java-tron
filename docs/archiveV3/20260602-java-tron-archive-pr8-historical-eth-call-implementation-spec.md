# java-tron Archive PR8 historical eth_call 代码级实现规格

日期：2026-06-02

> 2026-06-04 更新：本文是旧 PR8 规格。当前 `4e80f8ffa9a2` 的 S12/S13 编码入口请看 [java-tron Archive S12/S13：historical eth_call 4e80 编码执行包](./20260604-java-tron-archive-s12-s13-historical-eth-call-4e80-coding-packet.md)。尤其注意当前源码中 `eth_call` object block 参数会被改写成 `latest`，且 `DynamicPropertiesStore` 构造器为 private，不能直接按旧文档伪造 historical store。

关联总文档：[java-tron 交易级状态树支持：Erigon V2/V3 模型调研](./20260520-java-tron-archive-state-erigon-v2-v3-research.md)

关联实现蓝图：[java-tron Archive 状态树实现蓝图](./20260602-java-tron-archive-implementation-blueprint.md)

前置规格：

- [java-tron Archive S12/S13：historical eth_call 4e80 编码执行包](./20260604-java-tron-archive-s12-s13-historical-eth-call-4e80-coding-packet.md)
- [java-tron Archive PR5 TemporalStore 代码级实现规格](./20260602-java-tron-archive-pr5-temporal-store-implementation-spec.md)
- [java-tron Archive PR6 StateReader/JSON-RPC 代码级实现规格](./20260602-java-tron-archive-pr6-state-reader-jsonrpc-implementation-spec.md)
- [java-tron Archive PR7 CommitmentBuilder 代码级实现规格](./20260602-java-tron-archive-pr7-commitment-builder-implementation-spec.md)

模块 05 逐文件 Patch 清单：[java-tron Archive 模块 05：ArchiveStateReader 逐文件 Patch 清单](./20260602-java-tron-archive-module-05-state-reader-patch-checklist.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

## 1. PR8 目标

PR8 在 PR6 的 `ArchiveStateReader` 上增加 historical `eth_call`。它要让 `eth_call(callArgs, block)` 在历史 block end 状态上执行 TVM constant call，而不是像当前 java-tron 一样只校验 block 参数存在、然后强制走 latest state。

本 PR 做：

1. 保持 `latest` `eth_call` 走现有 `Wallet.triggerConstantContract` 路径。
2. 对 non-latest block 参数启用 archive 路径。
3. 支持 JSON-RPC string block 参数：hex quantity、`earliest`、`finalized`。
4. 支持 EIP-1898 object block 参数：`blockNumber`、`blockHash`。
5. 用 `ArchiveStatePointResolver` 解析为 `BLOCK_END(blockNum)`。
6. 用 `ArchiveStateReader` 打开同一个 `asOfTxNum` 的读 session。
7. 用 archive-backed `Repository` overlay 执行 TVM constant call。
8. 从历史 block header 注入 `NUMBER`、`TIMESTAMP`、`COINBASE`、`PREVHASH` 等 block context。
9. 从历史 `DYNAMIC_PROPERTIES` domain 注入 VM feature flags、energy fee、CPU time limit 等动态参数。
10. 确保 call 中的 SSTORE、transfer、create、internal call 只进入本次 overlay，不写 canonical store，也不写 archive history。

本 PR 不做：

1. 不实现 historical `eth_estimateGas`。
2. 不实现 historical `debug_traceCall`。
3. 不实现 historical contract creation call。
4. 不实现 transfer-only `eth_call`。
5. 不支持 pending/safe tag。
6. 不在缺 domain 时 fallback latest store。
7. 不改变 canonical transaction execution。
8. 不改变 PR7 sidecar root schema。

PR8 的验收目标：

```text
block N:     contract.storage[slot] = A
block N + 1: contract.storage[slot] = B

eth_call(readSlot, block N)     -> A
eth_call(readSlot, block N + 1) -> B
eth_call(readSlot, latest)      -> existing latest path
```

## 2. 源码事实

### 2.1 当前 eth_call 会强制 latest

| 文件 | 位置 | 事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java:1001` | `getCall(CallArguments, Object)` | `eth_call` 入口 |
| `TronJsonRpcImpl.java:1010` | object `blockNumber` | 会 parse block number |
| `TronJsonRpcImpl.java:1019` | block exists check | 会检查 `wallet.getBlockByNum(blockNumber) != null` |
| `TronJsonRpcImpl.java:1023` | object `blockHash` | 会 parse block hash |
| `TronJsonRpcImpl.java:1030` | block exists check | 会检查 `getBlockByJsonHash(blockHash) != null` |
| `TronJsonRpcImpl.java:1037` | `blockNumOrTag = LATEST_STR` | object 参数校验后被强制改成 latest |
| `TronJsonRpcImpl.java:1044` | `requireLatestBlockTag(blockNumOrTag)` | string quantity/tag 仍被 latest-only 校验拒绝 |
| `TronJsonRpcImpl.java:1049` | `call(...)` | 后续只走 latest Wallet path |

结论：

```text
PR8 不能只删除 requireLatestBlockTag。
如果底层仍调用 Wallet.triggerConstantContract，历史参数会继续读 latest Store。
```

### 2.2 当前 call 构造和返回值路径

| 文件 | 位置 | 事实 |
| --- | --- | --- |
| `TronJsonRpcImpl.java:473` | `callTriggerConstantContract` | 构造 `TriggerSmartContract` |
| `TronJsonRpcImpl.java:478` | `triggerCallContract(...)` | 使用 owner、contract、value、data |
| `TronJsonRpcImpl.java:487` | `wallet.createTransactionCapsule` | 构造 `TransactionCapsule` |
| `TronJsonRpcImpl.java:490` | `wallet.triggerConstantContract` | 进入 latest constant call |
| `TronJsonRpcImpl.java:557` | private `call(...)` | 统一处理异常、返回值和 revert data |
| `TronJsonRpcImpl.java:587` | success branch | 拼接 `constantResult` 并返回 hex |
| `TronJsonRpcImpl.java:596` | failed branch | 读取 revert data |

PR8 应抽出可复用的结果编码逻辑，不要复制一套不一致的 revert/error 行为。

建议新增：

```text
JsonRpcCallResultFormatter
  format(TransactionExtention.Builder, Return.Builder) -> String
```

或者把 `TronJsonRpcImpl.call(...)` 拆成：

```text
buildTriggerCall(...)
executeLatestCall(...)
executeArchiveCall(...)
formatCallResult(...)
```

### 2.3 CallArguments 的边界

| 文件 | 位置 | 事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/services/jsonrpc/types/CallArguments.java:30` | `from` | 默认 `0x000...000` |
| `CallArguments.java:33` | `to` | 可为空 |
| `CallArguments.java:36` | `gas` | 注释为 not used |
| `CallArguments.java:39` | `gasPrice` | 注释为 not used |
| `CallArguments.java:42` | `value` | quantity |
| `CallArguments.java:45` | `data` | call data |
| `CallArguments.java:48` | `input` | call input |
| `CallArguments.java:61` | `resolveData()` | `input` 优先于 `data` |
| `CallArguments.java:80` | `to == null` | `getContractType` 把它当 create |
| `CallArguments.java:92` | `wallet.getContract` | 当前合约类型判断会读 latest |

PR8 P0 只支持 historical contract call：

```text
from: required or default zero address
to:   required, historical CONTRACT domain 中必须存在
data/input: optional, input 优先
value: supported, 但只在 overlay 中生效
gas/gasPrice/nonce: 继续不使用
```

不支持：

```text
to == null historical contract creation
to 非合约且 value 非空的 transfer-only call
```

这些场景如果在 historical block 上请求，应返回 JSON-RPC invalid request 或 explicit unsupported，不能通过 `CallArguments.getContractType(wallet)` 去 latest Store 判断。

### 2.4 Wallet constant call 固定使用 latest block 和 latest Store

| 文件 | 位置 | 事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/framework/src/main/java/org/tron/core/Wallet.java:3086` | `triggerConstantContract` | latest constant call 入口 |
| `Wallet.java:3113` | `chainBaseManager.getContractStore()` | 合约存在性检查读 latest `ContractStore` |
| `Wallet.java:3131` | `getBlockByLatestNum(1)` | 执行上下文使用 latest block |
| `Wallet.java:3140` | `new TransactionContext(... StoreFactory.getInstance())` | 使用全局 latest `StoreFactory` |
| `Wallet.java:3142` | `new VMActuator(true)` | 没有 repository 注入点 |
| `Wallet.java:3144` | `validate` | 进入 VMActuator |
| `Wallet.java:3145` | `execute` | 执行 TVM |

结论：

```text
historical eth_call 不能复用 Wallet.triggerConstantContract。
Wallet path 只能保留给 latest。
```

### 2.5 VMActuator 固定从 StoreFactory 创建 latest Repository

| 文件 | 位置 | 事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/actuator/src/main/java/org/tron/core/actuator/VMActuator.java:122` | `ConfigLoader.load(context.getStoreFactory())` | VM feature flags 取 latest `DynamicPropertiesStore` |
| `VMActuator.java:128` | constant fee limit | `feeLimit / latest energyFee` |
| `VMActuator.java:141` | `RepositoryImpl.createRoot(context.getStoreFactory())` | root repository 固定 latest |
| `VMActuator.java:225` | `isConstantCall` branch | constant call 直接 set result and return |
| `VMActuator.java:250` | non-constant commit | 非 constant call 才 root commit |
| `VMActuator.java:455` | call supportVM | 从 `rootRepository.getDynamicPropertiesStore()` 判断 |
| `VMActuator.java:471` | `rootRepository.getContract` | 合约元数据从 repository 读 |
| `VMActuator.java:497` | `rootRepository.getCode` | code 从 repository 读 |
| `VMActuator.java:500` | `getMaxFeeLimit` | 动态参数从 repository 读 |
| `VMActuator.java:516` | `getMaxCpuTimeOfOneTx` | 动态参数从 repository 读 |

好消息：

```text
constant call execute 后不会 rootRepository.commit() 到 store。
```

风险：

```text
validate 阶段已经强绑定 latest ConfigLoader + latest Repository。
```

PR8 必须给 VMActuator 增加 root repository / dynamic properties 注入点。

### 2.6 RepositoryImpl overlay 语义可借鉴但不能直接复用 root

| 文件 | 位置 | 事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/actuator/src/main/java/org/tron/core/vm/repository/Repository.java:18` | `getDynamicPropertiesStore()` | 返回具体 `DynamicPropertiesStore` |
| `Repository.java:26` | `getAccount` | VM 读账户 |
| `Repository.java:50` | `getContract` | VM 读合约元数据 |
| `Repository.java:84` | `getCode` | VM 读 bytecode |
| `Repository.java:88` | `getStorageValue` | VM 读 storage |
| `Repository.java:96` | `newRepositoryChild` | internal call child overlay |
| `Repository.java:100` | `commit` | child overlay merge / root persist |
| `/Users/boson/IdeaProjects/java-tron/actuator/src/main/java/org/tron/core/vm/repository/RepositoryImpl.java:127` | cache fields | account/code/contract/storage 等 cache |
| `RepositoryImpl.java:180` | `newRepositoryChild` | child 共享 StoreFactory，parent 为 repository |
| `RepositoryImpl.java:309` | `getAccount` | parent -> cache -> latest store |
| `RepositoryImpl.java:501` | `getContract` | parent -> cache -> latest store |
| `RepositoryImpl.java:650` | `getCode` | parent -> cache -> latest store |
| `RepositoryImpl.java:681` | `getStorageValue` | 通过 `Storage` 读 latest `StorageRowStore` |
| `RepositoryImpl.java:753` | `commit` | 有 parent 则写 parent cache，无 parent 则写 canonical store |
| `RepositoryImpl.java:1001 / 1008` | `commitStorageCache` | root commit 会 `storage.commit()` 到 `StorageRowStore` |

结论：

- `RepositoryImpl` 的 overlay 思路可复用。
- `RepositoryImpl` 的 root 不能直接用于 archive historical call，因为 root commit 会写 canonical store。
- `RepositoryImpl` 的 storage 读使用 `Storage.compose(...)` 物理 key，而 archive historical storage 应使用 PR3/PR4 固定的 logical key `address21 || slot32 || storageKeyVersion_u8`。

PR8 推荐实现独立 `ArchiveRepositoryAdapter implements Repository`。

### 2.7 Storage physical key 不能用于 archive reader

| 文件 | 位置 | 事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/actuator/src/main/java/org/tron/core/vm/program/Storage.java:46` | `compose` | physical key 由 `addrHash` 和 slot 后半段组合 |
| `Storage.java:47` | contract version 1 | slot 先 `sha3(key)` |
| `Storage.java:68` | `generateAddrHash` | create2 时用 `address || trxId` 生成地址 hash |
| `Storage.java:77` | `store.get(compose(...))` | latest path 读 physical `StorageRowStore` |
| `Storage.java:96` | `commit()` | dirty row 写 `StorageRowStore` |

PR3/PR4 已决定 archive storage domain 使用 semantic logical key：

```text
CONTRACT_STORAGE key = address21 || slot32 || storageKeyVersion_u8
```

PR8 的 `ArchiveRepositoryAdapter.getStorageValue(address, slot)` 必须按 logical key 查询 `ArchiveStateReader`，由 reader/registry 侧解析目标历史点的 `storageKeyVersion_u8`；不能构造 `Storage` 并访问 `StorageRowStore`。

### 2.8 ProgramInvokeFactory 已可接收外部 Repository 和 historical block

| 文件 | 位置 | 事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/actuator/src/main/java/org/tron/core/vm/program/invoke/ProgramInvokeFactory.java:27` | top-level invoke | 参数包含 `Block block` 和 `Repository deposit` |
| `ProgramInvokeFactory.java:83` | balance | `deposit.getBalance(caller)` |
| `ProgramInvokeFactory.java:99` | block context | 从传入 block 读取 parentHash、witness、timestamp、number |
| `ProgramInvokeFactory.java:115` | ProgramInvokeImpl | 把 repository 作为 deposit 注入 VM |
| `ProgramInvokeFactory.java:126` | internal call invoke | internal call 继续接收 child repository |

结论：

```text
只要 VMActuator 能使用 historical blockCap + archive Repository，
NUMBER/TIMESTAMP/COINBASE/PREVHASH 和 state reads 都能走历史视图。
```

### 2.9 DynamicPropertiesStore 是最大接口阻塞点

| 文件 | 位置 | 事实 |
| --- | --- | --- |
| `/Users/boson/IdeaProjects/java-tron/actuator/src/main/java/org/tron/core/vm/config/ConfigLoader.java:16` | `load(StoreFactory)` | 只接收 StoreFactory |
| `ConfigLoader.java:18` | `getDynamicPropertiesStore()` | 固定 latest dynamic store |
| `ConfigLoader.java:21` | `checkForEnergyLimit(ds)` | 需要动态参数 |
| `ConfigLoader.java:22-49` | feature flags | 初始化 TVM feature flags |
| `/Users/boson/IdeaProjects/java-tron/chainbase/src/main/java/org/tron/core/store/DynamicPropertiesStore.java:30` | class | 具体 Store 类 |
| `Repository.java:18` | return type | `Repository` 返回具体 `DynamicPropertiesStore` |

VM/actuator 当前会读取这些动态参数：

| 来源 | 需要的方法 |
| --- | --- |
| `ConfigLoader` | `getAllowMultiSign`、`getAllowTvmTransferTrc10`、`getAllowTvmConstantinople`、`getAllowTvmSolidity059`、`getAllowShieldedTRC20Transaction`、`getAllowTvmIstanbul`、`getAllowTvmFreeze`、`getAllowTvmVote`、`getAllowTvmLondon`、`getAllowTvmCompatibleEvm`、`getAllowHigherLimitForMaxCpuTimeOfOneTx`、`supportUnfreezeDelay`、`getAllowOptimizedReturnValueOfChainId`、`getAllowDynamicEnergy`、`getDynamicEnergyThreshold`、`getDynamicEnergyIncreaseFactor`、`getDynamicEnergyMaxFactor`、`getAllowTvmShangHai`、`getAllowEnergyAdjustment`、`getAllowStrictMath`、`getAllowTvmCancun`、`getConsensusLogicOptimization`、`getAllowTvmBlob`、`getAllowTvmSelfdestructRestriction`、`getAllowTvmOsaka`、`getAllowHardenResourceCalculation` |
| `VMActuator` | `getEnergyFee`、`supportVM`、`getMaxFeeLimit`、`getMaxCpuTimeOfOneTx` |
| `Program` / operations | `getLatestBlockHeaderTimestamp`、`getMinFrozenTime`、`getCurrentCycleNumber`、`getEnergyFee` |
| native/precompiled contracts | `supportUnfreezeDelay`、`supportAllowNewResourceModel`、`getUnfreezeDelayDays`、`getTotalNetLimit`、`getTotalEnergyCurrentLimit` |
| `RepositoryImpl` resource helpers | `getTotalEnergyCurrentLimit`、`getTotalEnergyWeight`、`getTotalNetLimit`、`getLatestBlockHeaderTimestamp`、`getAllowMultiSign` |

结论：

```text
historical eth_call 不能使用 latest DynamicPropertiesStore。
必须引入 archive-backed DynamicProperties view。
```

## 3. 总体实现形态

推荐分成两个小 PR 或一个 PR8 内的两个阶段：

```text
PR8a: VM historical state injection foundation
  -> DynamicPropertiesView
  -> ConfigLoader.load(DynamicPropertiesView)
  -> VMActuator archive execution options
  -> ArchiveRepositoryAdapter skeleton

PR8b: JSON-RPC historical eth_call
  -> ArchiveEthCallExecutor
  -> TronJsonRpcImpl.getCall routing
  -> tests
```

如果代码评审倾向少改 VM 接口，可放在同一个 PR8 中，但实现顺序仍按 PR8a -> PR8b。

端到端路径：

```text
TronJsonRpcImpl.getCall(callArgs, blockParam)
  -> if latest: existing call(...)
  -> else:
       statePoint = ArchiveStatePointResolver.resolveEthCall(blockParam)
       block = ArchiveBlockResolver.resolveCanonicalBlock(statePoint)
       session = ArchiveStateReaderFactory.open(statePoint)
       dynamicView = ArchiveDynamicPropertiesView(session)
       repo = ArchiveRepositoryAdapter.root(session, dynamicView, blockResolver)
       trxCap = ArchiveEthCallExecutor.buildTriggerTx(callArgs)
       context = new TransactionContext(historicalBlock, trxCap, StoreFactory.getInstance(), true, false)
       vm = VMActuator.archiveConstantCall(repo, dynamicView)
       vm.validate(context)
       vm.execute(context)
       return JsonRpcCallResultFormatter.format(...)
```

注意：

- `TransactionContext.storeFactory` 可以继续传 `StoreFactory.getInstance()`，但 archive VMActuator 分支不得用它创建 root repository 或加载 dynamic config。
- 如果选择允许 `storeFactory == null`，必须先确认所有 archive path 都不解引用它；初版不建议传 null。

## 4. 包和文件

### 4.1 chainbase 新增

```text
chainbase/src/main/java/org/tron/core/archive/vm/
  DynamicPropertiesView.java
  ArchiveDynamicPropertiesView.java
  HistoricalCallUnsupportedException.java
  HistoricalCallDomain.java
```

`DynamicPropertiesView` 放 `chainbase` 的原因：

- `DynamicPropertiesStore` 在 `chainbase`，可以实现该接口。
- `actuator` 已依赖 `chainbase`，VM 代码可以引用接口。
- `framework` 也能引用接口。

`ArchiveDynamicPropertiesView` 依赖 PR6：

```text
ArchiveStateReader
ArchiveDomainRegistry
ArchiveTemporalStore
```

### 4.2 actuator 新增和调整

```text
actuator/src/main/java/org/tron/core/vm/repository/ArchiveRepositoryAdapter.java
actuator/src/main/java/org/tron/core/vm/repository/ArchiveStorageOverlay.java
actuator/src/main/java/org/tron/core/vm/repository/UnsupportedHistoricalDomainException.java
actuator/src/main/java/org/tron/core/actuator/VMActuator.java
actuator/src/main/java/org/tron/core/vm/config/ConfigLoader.java
actuator/src/main/java/org/tron/core/vm/repository/Repository.java
actuator/src/main/java/org/tron/core/vm/repository/RepositoryImpl.java
```

如果希望 archive 核心仍集中在 chainbase，也可以把 `ArchiveRepositoryAdapter` 放在 `actuator`，因为 `Repository` 接口本身位于 `actuator`。不要把它放到 `framework`，否则 VM internal call 无法复用。

### 4.3 framework 新增和调整

```text
framework/src/main/java/org/tron/core/services/jsonrpc/archive/
  ArchiveEthCallExecutor.java
  ArchiveEthCallRequest.java
  ArchiveEthCallResult.java
  JsonRpcCallResultFormatter.java
  ArchiveBlockResolver.java

framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java
```

`ArchiveEthCallExecutor` 放 `framework` 的原因：

- 它需要 JSON-RPC 参数、Wallet/Manager、block resolver、result formatting。
- 它调用 actuator 的 VMActuator 和 archive Repository。
- 它不应进入 chainbase，避免 chainbase 依赖 framework。

## 5. DynamicPropertiesView

### 5.1 接口

先不要把 `DynamicPropertiesStore` 大而全地全部接口化。PR8 只定义 TVM historical call 需要的最小读接口。

建议：

```java
public interface DynamicPropertiesView {
  long getAllowMultiSign();
  long getAllowTvmTransferTrc10();
  long getAllowTvmConstantinople();
  long getAllowTvmSolidity059();
  long getAllowShieldedTRC20Transaction();
  long getAllowTvmIstanbul();
  long getAllowTvmFreeze();
  long getAllowTvmVote();
  long getAllowTvmLondon();
  long getAllowTvmCompatibleEvm();
  long getAllowHigherLimitForMaxCpuTimeOfOneTx();
  long getAllowOptimizedReturnValueOfChainId();
  long getAllowDynamicEnergy();
  long getDynamicEnergyThreshold();
  long getDynamicEnergyIncreaseFactor();
  long getDynamicEnergyMaxFactor();
  long getAllowTvmShangHai();
  long getAllowEnergyAdjustment();
  long getAllowStrictMath();
  long getAllowTvmCancun();
  long getConsensusLogicOptimization();
  long getAllowTvmBlob();
  long getAllowTvmSelfdestructRestriction();
  long getAllowTvmOsaka();
  long getAllowHardenResourceCalculation();

  boolean supportUnfreezeDelay();
  boolean supportAllowNewResourceModel();

  boolean supportVM();
  long getEnergyFee();
  long getMaxFeeLimit();
  long getMaxCpuTimeOfOneTx();
  long getLatestBlockHeaderTimestamp();
  int getMinFrozenTime();
  long getCurrentCycleNumber();
  long getUnfreezeDelayDays();
  long getTotalNetLimit();
  long getTotalEnergyCurrentLimit();
  long getTotalEnergyWeight();
}
```

如果编译发现 VM 路径还有额外动态参数，再补接口方法。不要提前把 `DynamicPropertiesStore` 所有几百个方法都搬进去。

### 5.2 DynamicPropertiesStore implements DynamicPropertiesView

`DynamicPropertiesStore` 直接 `implements DynamicPropertiesView`。这让 latest 路径保持零行为变化。

```java
public class DynamicPropertiesStore
    extends TronStoreWithRevoking<BytesCapsule>
    implements DynamicPropertiesView {
}
```

### 5.3 ArchiveDynamicPropertiesView

`ArchiveDynamicPropertiesView` 从 `DYNAMIC_PROPERTIES` domain 读取历史 value。

建议实现：

```text
ArchiveDynamicPropertiesView:
  ReadSession session
  ArchiveDomainRegistry registry
  Map<String, Long> longCache
  Map<String, Boolean> boolCache

  getEnergyFee():
    return readLong("ENERGY_FEE", DynamicPropertiesStore default semantics)
```

重点：

1. key 名称必须由 `ArchiveDomainRegistry` 统一定义。
2. value 解码必须复用 PR2/PR3 的 `CanonicalValueCodec`。
3. 对 java-tron 中带默认值的方法，要么复刻 `DynamicPropertiesStore` 的默认语义，要么要求 archive genesis/bootstrap 写入完整 dynamic properties。
4. 如果缺少 PR8 必需 dynamic key，返回 `DOMAIN_NOT_ENABLED` 或 `MISSING_DYNAMIC_PROPERTY`，不能读 latest store。

建议 PR8 前置新增启动校验：

```text
archive.historicalCall.enable=true
  -> DYNAMIC_PROPERTIES domain enabled
  -> required dynamic keys available at archive start
  -> archive progress covers requested block
```

## 6. ConfigLoader 改造

现状：

```java
public static void load(StoreFactory storeFactory) {
  DynamicPropertiesStore ds = storeFactory.getChainBaseManager().getDynamicPropertiesStore();
  ...
}
```

建议：

```java
public static void load(StoreFactory storeFactory) {
  load(storeFactory.getChainBaseManager().getDynamicPropertiesStore());
}

public static void load(DynamicPropertiesView ds) {
  if (!disable) {
    VMConfig.setVmTrace(CommonParameter.getInstance().isVmTrace());
    if (ds != null) {
      VMConfig.initVmHardFork(checkForEnergyLimit(ds));
      ...
    }
  }
}
```

`ReceiptCapsule.checkForEnergyLimit(...)` 当前参数如果是 `DynamicPropertiesStore`，也要改为 `DynamicPropertiesView` 或增加 overload。

验收：

- latest path 调 `load(StoreFactory)`，行为不变。
- archive path 调 `load(archiveDynamicView)`，不访问 latest StoreFactory。

## 7. Repository 接口改造

### 7.1 最小侵入方案

不建议直接把：

```java
DynamicPropertiesStore getDynamicPropertiesStore();
```

改成：

```java
DynamicPropertiesView getDynamicPropertiesStore();
```

原因：

- 会影响很多 actuator/native contract 代码。
- 有些路径需要 `DynamicPropertiesStore` 的写方法或具体 Store 方法。

推荐新增：

```java
DynamicPropertiesView getDynamicPropertiesView();
```

并保留：

```java
DynamicPropertiesStore getDynamicPropertiesStore();
```

`RepositoryImpl`：

```java
@Override
public DynamicPropertiesView getDynamicPropertiesView() {
  return dynamicPropertiesStore;
}
```

`ArchiveRepositoryAdapter`：

```java
@Override
public DynamicPropertiesView getDynamicPropertiesView() {
  return archiveDynamicPropertiesView;
}

@Override
public DynamicPropertiesStore getDynamicPropertiesStore() {
  throw new UnsupportedOperationException("historical repository has no DynamicPropertiesStore");
}
```

然后把 VM historical path 可能执行到的代码逐步改成 `getDynamicPropertiesView()`。

### 7.2 需要替换的调用

优先替换这些文件中的 VM read-only 路径：

```text
actuator/src/main/java/org/tron/core/actuator/VMActuator.java
actuator/src/main/java/org/tron/core/vm/program/Program.java
actuator/src/main/java/org/tron/core/vm/OperationActions.java
actuator/src/main/java/org/tron/core/vm/PrecompiledContracts.java
actuator/src/main/java/org/tron/core/vm/ChainParameterEnum.java
actuator/src/main/java/org/tron/core/vm/utils/VoteRewardUtil.java
actuator/src/main/java/org/tron/core/vm/utils/FreezeV2Util.java
actuator/src/main/java/org/tron/core/vm/nativecontract/*
```

替换原则：

```text
读动态参数 -> getDynamicPropertiesView()
需要真实 Store 写入 -> 仍用 getDynamicPropertiesStore()，但 historical call 不应触达该路径
```

如果 historical call 触达仍要求真实 Store 的路径，`ArchiveRepositoryAdapter` 应抛出 explicit unsupported，而不是提供 latest store。

## 8. VMActuator 注入点

### 8.1 推荐 API

新增一个 execution options：

```java
public final class VmExecutionOptions {
  private final boolean constantCall;
  private final Repository rootRepositoryOverride;
  private final DynamicPropertiesView dynamicPropertiesViewOverride;
}
```

或者先做更小构造器：

```java
public VMActuator(boolean isConstantCall,
    Repository rootRepositoryOverride,
    DynamicPropertiesView dynamicPropertiesViewOverride) {
  this.isConstantCall = isConstantCall;
  this.rootRepositoryOverride = rootRepositoryOverride;
  this.dynamicPropertiesViewOverride = dynamicPropertiesViewOverride;
}
```

保留现有构造器：

```java
public VMActuator(boolean isConstantCall) {
  this(isConstantCall, null, null);
}
```

### 8.2 validate 改造

现有：

```text
ConfigLoader.load(context.getStoreFactory())
rootRepository = RepositoryImpl.createRoot(context.getStoreFactory())
```

改为：

```text
dynamicView = dynamicPropertiesViewOverride != null
  ? dynamicPropertiesViewOverride
  : context.getStoreFactory().getChainBaseManager().getDynamicPropertiesStore()

ConfigLoader.load(dynamicView)

if constant call && feeLimit > 0:
  maxEnergyLimit = min(maxEnergyLimit, feeLimit / dynamicView.getEnergyFee())

rootRepository = rootRepositoryOverride != null
  ? rootRepositoryOverride
  : RepositoryImpl.createRoot(context.getStoreFactory())
```

保护条件：

```text
rootRepositoryOverride != null && !isConstantCall -> reject
dynamicPropertiesViewOverride != null && rootRepositoryOverride == null -> reject
```

PR8 只允许 archive override 用于 constant call。

### 8.3 execute 不需要改持久化逻辑

`VMActuator.execute` 当前在 `isConstantCall` 时：

```text
context.setProgramResult(result)
return
```

所以 top-level archive call 不会 root commit。internal call child repository 仍可能 `commit()` 到 parent overlay，这是需要保留的。

`ArchiveRepositoryAdapter.commit()` 语义：

```text
child commit  -> merge child overlay into parent overlay
root commit   -> no-op or throw if called outside constant call
```

建议 root commit 直接 no-op，并记录 debug log；如果被 non-constant path 调用，应 assert/throw。

## 9. ArchiveRepositoryAdapter

### 9.1 核心结构

```text
ArchiveRepositoryAdapter implements Repository
  parent: ArchiveRepositoryAdapter?
  session: ArchiveStateReader.ReadSession
  dynamicView: DynamicPropertiesView
  blockResolver: ArchiveBlockResolver

  accountCache: Map<Key, Value<Account>>
  contractCache: Map<Key, Value<SmartContract>>
  contractStateCache: Map<Key, Value<ContractState>>
  codeCache: Map<Key, Value<byte[]>>
  storageCache: Map<AddressKey, Map<SlotKey, Value<byte[]>>>
  dynamicPropertiesCache: Map<Key, Value<byte[]>>
  transientStorage: table
  newContractCache: Set<Key>
```

不要继承 `RepositoryImpl`，原因：

- `RepositoryImpl` private cache 很多，子类难以覆盖完整读写。
- root commit 会写 canonical store。
- storage path 会触达 `StorageRowStore`。

### 9.2 root 读取规则

读取顺序：

```text
own overlay -> parent overlay -> ArchiveStateReader session -> not found
```

每个 read 方法：

```text
getAccount(address):
  if overlay has tombstone: return null
  if overlay has value: return copy
  if parent != null: return parent.getAccount(address)
  return session.getAccount(address)
```

所有返回 capsule/protobuf 都要 copy，避免 VM 修改 base object。

### 9.3 write/overlay 规则

VM 写方法只更新 overlay：

```text
createAccount
updateAccount
createContract
updateContract
saveCode
putStorageValue
addBalance
addTokenBalance
updateDynamicProperty
updateTransientStorageValue
```

root adapter 不写：

```text
AccountStore
ContractStore
CodeStore
StorageRowStore
DynamicPropertiesStore
ArchiveTemporalStore
```

### 9.4 storage 规则

`getStorageValue(address, slot)`：

```text
address21 = TransactionTrace.convertToTronAddress(address)
slot32 = slot.getData()
if overlay[address21][slot32] exists:
  return DataWord(value)
if parent != null:
  return parent.getStorageValue(address21, slot)
return session.getStorageValue(address21, slot32)
```

`putStorageValue(address, slot, value)`：

```text
overlay[address21][slot32] = value.getData()
```

zero value：

- 在 overlay 中可保留 32-byte zero。
- 不需要写 tombstone 到 archive。
- call 结束后 overlay 丢弃。

`getStorage(address)`：

PR8 不建议返回 `Storage`，因为 `Storage` 绑定 `StorageRowStore`。如果 VM path 必须调用 `getStorage`，实现一个 `ArchiveStorageOverlay extends Storage` 会很别扭，因为 `Storage` 构造器需要 `StorageRowStore` 且 `getValue` 非接口化。

建议：

```text
historical call path 中只允许使用 Repository.getStorageValue/putStorageValue。
ArchiveRepositoryAdapter.getStorage(address) 抛 UnsupportedHistoricalDomainException。
```

如果编译或测试发现 TVM 必走 `getStorage`，应先抽出 `StorageView` 接口，而不是伪造 `StorageRowStore`。

### 9.5 contract/account/code domain 映射

| Repository 方法 | Archive domain | key |
| --- | --- | --- |
| `getAccount(address)` | `ACCOUNT` | `address21` |
| `getContract(address)` | `CONTRACT` | `address21` |
| `getContractState(address)` | `CONTRACT_STATE` 或 `CONTRACT` sub-value | `address21` |
| `getCode(address)` | `CODE` | `address21` 或 `codeHash`，由 Registry 固定 |
| `getStorageValue(address, slot)` | `CONTRACT_STORAGE` | `address21 || slot32 || storageKeyVersion_u8` |
| `getDynamicProperty(key)` | `DYNAMIC_PROPERTIES` | raw dynamic key |

如果 PR2 的 P0 domain 未包含 `CONTRACT_STATE`，PR8 要么：

```text
1. 把 ContractState 纳入 PR8 必需 domain；
2. 或确认 historical call 所需字段都在 CONTRACT domain 中。
```

不能在 `getContractState` fallback latest `ContractStateStore`。

### 9.6 资源、投票、资产等扩展 domain

`Repository` 接口还包含：

```text
AssetIssue
DelegatedResource
Votes
Delegation
DelegatedResourceAccountIndex
Witness
TotalNetWeight / TotalEnergyWeight / TotalTronPowerWeight
BlackHoleAddress
BlockByNum
```

PR8 P0 的处理策略：

| 方法族 | 策略 |
| --- | --- |
| `getBlockByNum` | 允许通过 canonical block store/block index 读历史 block data |
| `getWitness` | 如果 TVM path 使用，需新增 `WITNESS` domain；否则 explicit unsupported |
| `getAssetIssue*` | TRC10 transfer path 需要；如果 `allowTvmTransferTrc10` 开启且 call 使用 token，必须 domain 支持或 unsupported |
| `getDelegatedResource*` | native freeze/unfreeze/delegate path 需要；P0 可 unsupported |
| `getVotes`/`getDelegation` | vote/reward native path 需要；P0 可 unsupported |
| total weights | 动态资源计算需要；优先从 `DYNAMIC_PROPERTIES` 或 dedicated domain 读取 |
| `getBlackHoleAddress` | 如果 native path 触达，需从 config/genesis 或 ACCOUNT domain 固定；不能 latest fallback |

错误原则：

```text
historical eth_call unsupported: missing domain DELEGATED_RESOURCE
```

不要返回 null 让 VM 继续跑出难以解释的错误。

## 10. ArchiveEthCallExecutor

### 10.1 入口

```java
public final class ArchiveEthCallExecutor {
  public String execute(CallArguments callArgs, Object blockParam)
      throws JsonRpcInvalidParamsException,
             JsonRpcInvalidRequestException,
             JsonRpcInternalException;
}
```

依赖：

```text
Wallet wallet
Manager manager
ArchiveService archiveService
ArchiveStatePointResolver resolver
ArchiveStateReaderFactory readerFactory
ArchiveDomainRegistry registry
```

### 10.2 block 参数解析

规则：

| 输入 | 行为 |
| --- | --- |
| `"latest"` | 不进入 `ArchiveEthCallExecutor`，走现有 latest path |
| hex quantity | 解析为 `BLOCK_END(blockNum)` |
| `"earliest"` | 解析为 genesis `BLOCK_END(0)`，如果 archive start > 0 则 range error |
| `"finalized"` | 映射 `DynamicPropertiesStore.getLatestSolidifiedBlockNum()` 对应 `BLOCK_END` |
| `"pending"` | unsupported |
| `"safe"` | unsupported，除非 java-tron 后续定义 safe block |
| object `{blockNumber}` | 解析为 `BLOCK_END(blockNum)` |
| object `{blockHash}` | 根据 hash 找 canonical block，再解析为 `BLOCK_END(blockNum)` |

object 参数不能再做：

```text
blockNumOrTag = latest
```

block hash 必须验证 canonical：

```text
block = wallet.getBlockById(hash)
blockByNum = wallet.getBlockByNum(block.num)
if blockByNum.id != hash:
  return block not canonical / block hash not found
```

### 10.3 call 参数校验

P0：

```text
from: addressCompatibleToByteArray(callArgs.getFrom())
to:   must not be null/empty
data: ByteArray.fromHexString(callArgs.resolveData())
value: callArgs.parseValue()
```

historical contract existence：

```text
contract = archiveReader.getContract(to)
if contract == null:
  throw JsonRpcInvalidRequestException("No contract or not a smart contract")
```

不能调用：

```text
callArgs.getContractType(wallet)
wallet.getContract(...)
```

因为这些方法读 latest。

### 10.4 TransactionCapsule 构造

可复用：

```text
JsonRpcApiUtil.triggerCallContract(owner, contract, value, data, 0, null)
wallet.createTransactionCapsule(triggerContract, TriggerSmartContract)
```

`createTransactionCapsule` 只构造 transaction，不应读 state。如果后续源码确认它会读 latest，则 PR8 要新增纯 builder：

```text
ArchiveEthCallExecutor.buildTriggerTransaction(...)
```

并用 protocol builder 直接生成 `TransactionCapsule`。

### 10.5 TransactionContext

```java
BlockCapsule historicalBlock = new BlockCapsule(block);
TransactionContext context = new TransactionContext(
    historicalBlock,
    trxCap,
    StoreFactory.getInstance(),
    true,
    false);
```

`eventPluginLoaded=false`，避免 historical call 触发 event plugin side effects。

### 10.6 VM 执行

```java
ArchiveRepositoryAdapter repo = ArchiveRepositoryAdapter.createRoot(session, dynamicView, blockResolver);
VMActuator vmActuator = new VMActuator(true, repo, dynamicView);
vmActuator.validate(context);
vmActuator.execute(context);
```

执行后沿用 latest path result build：

```text
builder.setEnergyUsed(result.getEnergyUsed())
builder.setEnergyPenalty(result.getEnergyPenaltyTotal())
builder.addConstantResult(result.getHReturn())
builder.addLogs(...)
builder.addInternalTransactions(...)
ret.setStatus(SUCESS/FAILED)
```

然后用统一 formatter 返回 JSON-RPC hex 或 revert error。

## 11. JSON-RPC 接入

`TronJsonRpcImpl.getCall` 改为：

```text
if isLatestBlockParam(blockParamObj):
  parse latest
  existing call(...)
else:
  if archive disabled:
    throw JsonRpcInternalException("archive is disabled")
  return archiveEthCallExecutor.execute(transactionCall, blockParamObj)
```

`isLatestBlockParam`：

```text
String "latest" -> true
HashMap object -> false
quantity/tag != latest -> false
```

历史路径不调用 `requireLatestBlockTag`。

latest object 参数：

- 当前实现 object `{blockNumber/latest?}` 只支持 number/hash 字符串，不支持 object latest。
- PR8 不需要新增 object latest。

异常映射：

| 内部错误 | JSON-RPC |
| --- | --- |
| archive disabled | `JsonRpcInternalException` |
| block not found | `JsonRpcInternalException(NO_BLOCK_HEADER)` |
| block hash not found | `JsonRpcInternalException(NO_BLOCK_HEADER_BY_HASH)` |
| non-canonical block hash | `JsonRpcInvalidParamsException` or internal with explicit message |
| before archive start | `JsonRpcInternalException("archive range unavailable")` |
| archive lag | `JsonRpcInternalException("archive not synced to block ...")` |
| missing domain | `JsonRpcInternalException("historical eth_call unsupported: missing domain ...")` |
| invalid call args | `JsonRpcInvalidRequestException` |
| contract validate | same as latest call |
| revert with data | same as latest call |

## 12. Archive domain 前置要求

PR8 开启条件：

```text
storage.archive.enable = true
storage.archive.historicalCall.enable = true
```

并要求这些 domain 已启用：

| domain | 必需 | 原因 |
| --- | --- | --- |
| `ACCOUNT` | 是 | balance、caller、contract account existence |
| `CONTRACT` | 是 | contract metadata、origin、version、energy percent |
| `CODE` | 是 | VM bytecode |
| `CONTRACT_STORAGE` | 是 | SLOAD/SSTORE overlay |
| `DYNAMIC_PROPERTIES` | 是 | VMConfig、energy fee、CPU limit、feature flags |
| `CONTRACT_STATE` | 待确认 | 如果 `Repository.getContractState` 在 call path 触达 |
| `ASSET_ISSUE` | 条件必需 | TRC10 transfer/token call |
| `DELEGATED_RESOURCE` | 条件必需 | native freeze/delegate paths |
| `VOTES`/`DELEGATION` | 条件必需 | vote/reward paths |
| `WITNESS` | 条件必需 | witness/native paths |

P0 可以只让普通 Solidity storage-read 合约通过；一旦执行触达条件 domain，应显式 unsupported。

启动校验建议：

```text
if historicalCall.enable:
  assert domain ACCOUNT/CONTRACT/CODE/CONTRACT_STORAGE/DYNAMIC_PROPERTIES enabled
  assert registry checksum matches archive data
```

## 13. 和 Erigon 模型的对应关系

| Erigon V2/V3 概念 | PR8 java-tron 对应 |
| --- | --- |
| `HistoryReaderV3` | `ArchiveStateReader.ReadSession` |
| `asOfStateReader` | `ArchiveRepositoryAdapter` root base |
| `StateReader` account/code/storage | `Repository.getAccount/getCode/getStorageValue` |
| execution overlay | `ArchiveRepositoryAdapter` child cache |
| `BlockStateCache` | PR8 暂不实现，仅 session cache |
| `StateReaderBuilder` | `ArchiveEthCallExecutor` + `ArchiveStateReaderFactory` |
| no latest fallback | `DOMAIN_NOT_ENABLED` / explicit unsupported |

核心一致性规则：

```text
同一次 historical eth_call 的所有 account/code/storage/dynamic reads
必须固定在同一个 asOfTxNum 和 registryChecksum。
```

## 14. 实现步骤

### Step 1：动态参数视图

1. 新增 `DynamicPropertiesView`。
2. `DynamicPropertiesStore implements DynamicPropertiesView`。
3. `ConfigLoader.load(StoreFactory)` 委托到 `load(DynamicPropertiesView)`。
4. `ReceiptCapsule.checkForEnergyLimit` 增加 view overload 或改参数类型。
5. 单测 latest path VMConfig 初始化结果不变。

### Step 2：Repository view 方法

1. `Repository` 新增 `getDynamicPropertiesView()`。
2. `RepositoryImpl` 返回 latest `DynamicPropertiesStore`。
3. 将 VM historical call 可能触达的 read-only 动态参数调用替换到 view。
4. 保留需要真实 store 的写路径。

### Step 3：VMActuator 注入点

1. 新增 override constructor/options。
2. validate 使用 override dynamic view load config。
3. validate 使用 override repository 创建 root。
4. 加保护：override 只允许 constant call。
5. 单测 override repo 被调用，StoreFactory latest repo 不被创建。

### Step 4：ArchiveRepositoryAdapter

1. 实现 account/contract/code/storage/dynamic read。
2. 实现 overlay write 和 child commit。
3. root commit no-op。
4. unsupported domain 抛明确异常。
5. 单测 child commit 合并到 parent overlay。

### Step 5：ArchiveEthCallExecutor

1. 解析 block param。
2. 校验 canonical block hash。
3. 构造 historical `TransactionContext`。
4. 执行 archive VMActuator。
5. 复用 formatter 输出结果。

### Step 6：TronJsonRpcImpl 路由

1. latest 保持原路径。
2. non-latest 且 archive disabled 返回明确错误。
3. non-latest 且 archive enabled 走 `ArchiveEthCallExecutor`。
4. object block 参数不再强制 latest。

### Step 7：集成测试

1. 部署读 storage 合约。
2. block N 写 A。
3. block N+1 写 B。
4. historical `eth_call` 分别返回 A/B。
5. latest `eth_call` 与现有路径返回一致。
6. call 后 latest store 和 archive history 没有额外写入。

## 15. 测试清单

### 15.1 参数解析

| 用例 | 断言 |
| --- | --- |
| `eth_call(args, "latest")` | 走 existing Wallet path |
| `eth_call(args, "0x10")` | resolve `BLOCK_END(16)` |
| `eth_call(args, {"blockNumber":"0x10"})` | 不再改成 latest |
| `eth_call(args, {"blockHash":"0x..."})` | resolve canonical block |
| non-canonical hash | 返回 explicit error |
| `pending` | unsupported |
| `safe` | unsupported |

### 15.2 RepositoryAdapter

| 用例 | 断言 |
| --- | --- |
| getAccount historical | 读 `ACCOUNT` domain |
| getContract historical | 读 `CONTRACT` domain |
| getCode historical | 读 `CODE` domain |
| getStorageValue historical | key 为 `address21 || slot32 || storageKeyVersion_u8` |
| putStorageValue | 只写 overlay |
| child commit | child overlay 合并 parent overlay |
| root commit | 不写 canonical store |
| unsupported domain | 明确异常，不 fallback latest |

### 15.3 动态参数

| 用例 | 断言 |
| --- | --- |
| historical energy fee | feeLimit/maxEnergy 使用历史 `ENERGY_FEE` |
| historical feature flag | `VMConfig` 用历史 flag 初始化 |
| missing dynamic key | `MISSING_DYNAMIC_PROPERTY` |
| latest path | `DynamicPropertiesStore` 行为不变 |

### 15.4 TVM block context

合约返回：

```text
block.number
block.timestamp
coinbase / witness
```

断言 historical `eth_call` 使用请求 block，而不是 latest block。

### 15.5 storage 历史

构造：

```text
block N: slot = A
block N+1: slot = B
```

断言：

```text
eth_call(readSlot, N)   -> A
eth_call(readSlot, N+1) -> B
```

### 15.6 overlay 隔离

合约在 call 中执行：

```text
SSTORE slot = C
read slot
```

断言：

1. call 内部可读到 overlay 值 C。
2. call 结束后 historical reader 仍返回 block 状态 A/B。
3. latest `StorageRowStore` 未变化。
4. archive `HISTORY/CHANGESET` 未新增记录。

### 15.7 internal call

合约 A 调合约 B：

```text
A -> B writes storage
B returns value
A reads B/own state
```

断言 child repository commit 合并 overlay，最终不持久化。

### 15.8 错误路径

| 用例 | 断言 |
| --- | --- |
| archive disabled | internal error: archive disabled |
| archive lag | internal error: archive not synced |
| before archive start | range unavailable |
| missing `CONTRACT_STORAGE` | missing domain error |
| missing contract at historical block | same semantic as latest no contract |
| revert with data | 与 latest `eth_call` 返回格式一致 |

## 16. 代码审查清单

1. non-latest `eth_call` 没有任何 `Wallet.triggerConstantContract` 调用。
2. non-latest `eth_call` 没有调用 `CallArguments.getContractType(wallet)`。
3. object block 参数没有被改成 `latest`。
4. historical path 没有调用 `RepositoryImpl.createRoot(StoreFactory)`。
5. historical path 没有 `ConfigLoader.load(StoreFactory)`。
6. historical path 没有读取 latest `AccountStore/ContractStore/CodeStore/StorageRowStore/DynamicPropertiesStore`。
7. `ArchiveRepositoryAdapter.getStorageValue` 使用 logical key。
8. root adapter commit 不写 canonical store。
9. 缺 domain 返回 explicit unsupported。
10. latest `eth_call` 现有行为不变。

## 17. 风险和降级策略

### 17.1 DynamicPropertiesStore 接口面过大

风险：

```text
VM/native paths 读取的动态参数比预估更多。
```

策略：

- 先覆盖 `ConfigLoader`、`VMActuator`、普通 Solidity call 所需方法。
- 每次遇到新增动态参数读取，补 `DynamicPropertiesView`。
- 测试中强制 historical path 的 `getDynamicPropertiesStore()` 抛异常，逼出漏改点。

### 17.2 Native contract 触达未归档 domain

风险：

```text
历史 call 执行 freeze/vote/delegate 等 native/precompiled path。
```

策略：

- P0 返回 unsupported missing domain。
- 后续按 domain 增补 `DELEGATED_RESOURCE/VOTES/WITNESS`。
- 不允许 fallback latest。

### 17.3 Storage API 绑定 concrete Storage

风险：

```text
某些 VM path 调 Repository.getStorage(address) 而不是 getStorageValue。
```

策略：

- 初版 `ArchiveRepositoryAdapter.getStorage` 抛异常并加测试。
- 如果真实路径必须支持，新增 `StorageView` 抽象；不要伪造 `StorageRowStore`。

### 17.4 VMConfig 是全局静态

风险：

```text
ConfigLoader.load(historicalDynamicView) 修改全局 VMConfig，
并发 latest call 或 block apply 可能被历史 flags 干扰。
```

这是 PR8 最大并发风险。

可选方案：

1. 短期：historical `eth_call` 和 canonical execution 使用同一把 VM config lock。进入 historical call 前 load historical config，退出后恢复 latest config。
2. 中期：把 `VMConfig` 从全局静态改为 per-execution `VmRules`，由 `ProgramInvoke` 或 `VMActuator` 持有。

推荐 PR8 P0 做短期锁和恢复，文档化风险；后续单独 PR 改 per-execution rules。

测试必须覆盖：

```text
historical call 后 latest call 仍使用 latest VMConfig
```

## 18. 推荐落地顺序

如果按可评审小 PR 拆分：

1. `actuator, chainbase: introduce DynamicPropertiesView for VM config`
2. `actuator: allow constant VMActuator root repository override`
3. `actuator: add ArchiveRepositoryAdapter overlay`
4. `framework: add ArchiveEthCallExecutor`
5. `jsonrpc: route historical eth_call to archive executor`

如果只能一个 PR：

```text
先交 DynamicPropertiesView + VMActuator injection + adapter tests，
再接 JSON-RPC integration tests。
```

不要先改 `TronJsonRpcImpl.getCall` 放行 block number，否则中间状态容易产生“看似支持历史、实际读 latest”的危险行为。
