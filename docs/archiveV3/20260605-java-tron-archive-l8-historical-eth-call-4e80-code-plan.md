# java-tron Archive L8：historical eth_call 代码级执行包

日期：2026-06-05

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`

Erigon 源码路径：`/Users/boson/GolandProjects/erigon`

上游总路线：[java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)

来源大包：[java-tron Archive S12/S13：historical eth_call 4e80 编码执行包](./20260604-java-tron-archive-s12-s13-historical-eth-call-4e80-coding-packet.md)

state-root 分支参考：[java-tron state-root 分支借鉴分析](./20260605-java-tron-state-root-branches-reference-analysis.md)

前置执行包：

- [L2 Manager lifecycle + txNum 代码级执行包](./20260605-java-tron-archive-l2-manager-txnum-4e80-code-plan.md)：提供 block finalize txNum、block hash/num 到 txNum range 的索引。
- [L3 ArchiveDomainRegistry 代码级执行包](./20260605-java-tron-archive-l3-domain-registry-4e80-code-plan.md)：提供 `ACCOUNT`、`CONTRACT`、`CODE`、`CONTRACT_STORAGE`、`DYNAMIC_PROPERTIES` 的 domain id、key/value codec 和 root policy。
- [L4 WriteCollector + Storage Semantic Hook 代码级执行包](./20260605-java-tron-archive-l4-write-collector-4e80-code-plan.md)：提供 per logical tx write set，尤其是 contract storage semantic key。
- [L5 ArchiveTemporalStore 代码级执行包](./20260605-java-tron-archive-l5-temporal-store-4e80-code-plan.md)：提供 archive DB、`getAsOf(domain,key,txNum)`、block range、startup progress。
- [L6 ArchiveStateReader + historical getters 代码级执行包](./20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md)：提供 block-end `ArchiveStatePoint`、historical account/code/storage reader 和 JSON-RPC state getter 分流模型。
- [L7 CommitmentBuilder 代码级执行包](./20260605-java-tron-archive-l7-commitment-builder-4e80-code-plan.md)：提供 root/progress coverage，可作为 historical call 的覆盖范围验证依据。

本文只做 L8 规划，不修改 java-tron 源码。目标是把 `eth_call(args, historicalBlock)` 细化到类、方法、包边界、VM 注入点、Repository overlay、动态参数、错误语义、测试 gate 和 review checklist。

## 1. L8 定位

L8 把 archive sidecar 的 historical state 接入 TVM constant call：

```text
eth_call(args, blockParam)
  -> parse block selector without rewriting to latest
  -> resolve ArchiveStatePoint(blockNum, blockHash, finalizeTxNum)
  -> open ArchiveStateReader(point)
  -> build ArchiveRepositoryAdapter(root, point, reader)
  -> run VMActuator as constant call on historical block env
  -> encode result exactly like current eth_call latest path
```

L8 交付：

```text
HistoricalEthCallBlockSelector
HistoricalEthCallSupport / ArchiveJsonRpcCallAdapter
HistoricalConstantCallRequest / HistoricalConstantCallResult
HistoricalConstantCallExecutor
VmDynamicProperties minimal interface
VmConfigSnapshot / VmConfigScope
HistoricalVmDynamicProperties
ArchiveRepositoryAdapter root
ArchiveRepositoryChild overlay
ArchiveRepositoryStorage overlay
VMActuator historical repository injection point
TronJsonRpcImpl eth_call latest/historical branch
result/revert encoding helper
```

L8 不交付：

```text
eth_estimateGas historical support
/wallet/triggerconstantcontract historical support
historical contract creation call
transfer-only eth_call without contract code
state override / block override
pending/safe historical call
transaction-index intra-block eth_call
proof/debug API
native resource precompile full historical coverage
historical execution trace
archive write-back from VM overlay
consensus accountStateRoot participation
```

L8 的核心约束：

```text
1. latest selector 保持 java-tron 4e80 现有 Wallet.triggerConstantContract 行为。
2. non-latest selector 不能 silent fallback latest。
3. object-form blockNumber/blockHash 不能被改写成 latest。
4. historical VM account/contract/code/storage/dynamic property 只能来自 archive reader 或 historical block header。
5. VM 内部写入只进 in-memory overlay，顶层 constant call 完成后丢弃。
6. P0 未覆盖的 Repository/native/resource domain 必须显式 unsupported，不能偷读 latest Store。
7. historical VMConfig 静态开关必须 scoped restore，不能污染同进程后续 latest call。
```

补充参考口径：`feat/state-trie-4.8.1` 是区块级 MPT 实现；L8 只参考它的 `RepositoryStateImpl.createRoot(root)`、read-only `AccountStateStore/StorageRowStateStore/DynamicPropertiesStateStore` facade、child repository overlay 和 top-level commit unsupported 形状。L8 的 root source 必须是 `ArchiveStatePoint -> ArchiveRootRecord/ArchiveStateReader`，不能依赖该分支写入 block header 的 `archive_root`。

## 2. Erigon 源码依据

### 2.1 eth_call 保留 block selector

| Erigon 源码 | 当前事实 | java-tron L8 映射 |
| --- | --- | --- |
| `rpc/jsonrpc/eth_call.go:68-92` | `Call` 接收 `requestedBlock *rpc.BlockNumberOrHash`；只有 nil 时才默认 `latestNumOrHash` | `TronJsonRpcImpl.getCall` 必须保留用户传入的 string/object selector；只有缺省才 latest |
| `eth_call.go:103-109` | 先用 `headerByNumberOrHash` 解析 header，header 缺失直接错误 | L8 先解析 `blockNumber/blockHash`，缺块报 `NO_BLOCK_HEADER`/`NO_BLOCK_HEADER_BY_HASH` |
| `eth_call.go:111-119` | 执行前检查 prune history 和 block executed | L8 检查 archive enable、state point coverage、L5 progress、L7 coverage |
| `eth_call.go:121-126` | `CreateStateReader` 后把 reader 传给 `transactions.DoCall` | L8 用 `ArchiveStateReader` 构造 `ArchiveRepositoryAdapter` 后传给 `HistoricalConstantCallExecutor` |
| `eth_call.go:135-139` | revert 映射为带 return data 的错误 | L8 复用 java-tron 现有 `TransactionExtention` result/revert 编码 |

关键启发：

```text
RPC layer resolves block and state reader.
Execution layer consumes an already-resolved reader.
The call path does not reinterpret historical selector as latest.
```

java-tron L8 不能继续当前行为：

```text
{"blockNumber":"0x10"} -> validate block exists -> blockNumOrTag = "latest"
{"blockHash":"0x..."} -> validate hash exists  -> blockNumOrTag = "latest"
```

### 2.2 getters 和 call 共用 state point 解析

| Erigon 源码 | 当前事实 | java-tron L8 映射 |
| --- | --- | --- |
| `rpc/jsonrpc/eth_accounts.go:41-72` | `stateReaderAt` 封装 begin tx、canonical block、prune、executed、reader 创建 | L6/L8 共用 `ArchiveStatePointResolver` 和 coverage checker |
| `eth_accounts.go:76-90` | `GetBalance` 只消费 reader，不自己读 latest state | `HistoricalConstantCallExecutor` 只消费 `ArchiveRepositoryAdapter`，不碰 `Wallet.getContract` |
| `eth_accounts.go:125-143` | `GetCode` 与 balance 使用相同 state reader | L8 `EXTCODE*`、contract code、call target code 都读同一个 archive reader |
| `eth_accounts.go:207-237` | storage getter 使用同一个 reader 的 `ReadAccountStorage` | L8 `SLOAD` 用 L4 semantic key + L6 storage reader |

java-tron 的等价形状：

```text
HistoricalEthCallBlockSelector
  -> ArchiveStatePointResolver.resolve(blockParam)
  -> ArchiveStateReaderFactory.open(point)
  -> ArchiveRepositoryAdapter(reader, point)
```

不要让 `TronJsonRpcImpl` 自己判断 historical contract 是否存在；存在性判断放到 executor/repository，并从 archive `CONTRACT` domain 读。

### 2.3 HistoryReaderV3 的 as-of 坐标

| Erigon 源码 | 当前事实 | java-tron L8 映射 |
| --- | --- | --- |
| `execution/state/history_reader_v3.go:57-69` | `HistoryReaderV3` 构造时绑定 `txNum` | `ArchiveRepositoryAdapter` 构造时绑定 `ArchiveStatePoint.finalizeTxNum` |
| `history_reader_v3.go:71-87` | in-batch / block-cache 变体先读 overlay，再读 DB history | L8 child repository 先读本次 VM overlay，再读 parent/root archive reader |
| `history_reader_v3.go:95-107` | `getAsOf(domain,key)` 是所有历史读统一入口 | L8 root adapter 所有 historical miss 都走 L6 `ArchiveStateReader` |
| `history_reader_v3.go:192-208` | account missing/empty 返回 nil，decode error 返回 error | `ArchiveRepositoryAdapter.getAccount` missing 返回 null，codec error 抛 internal |
| `history_reader_v3.go:217-229` | storage key 是 composite，ok=false 表示 missing | L8 storage key 是 `address21 || slot32 || storageKeyVersion_u8`，missing 返回 zero/null |
| `history_reader_v3.go:263-278` | code 读 `CodeDomain`，code size 是派生能力 | L8 `getCode` 读 `CODE` domain；`EXTCODESIZE` 由 code length 派生 |

L8 的 child overlay 相当于 Erigon call execution 的 in-memory state：

```text
root archive state at block-end
  + top-level callValue balance move
  + SSTORE writes during call
  + internal CALL/CREATE/SELFDESTRUCT side effects inside VM
  = in-memory overlay visible to deeper calls
```

但 overlay 生命周期只到本次 `eth_call` 返回，不进入 L5 archive DB，也不进入 java-tron latest Store。

## 3. java-tron 4e80 源码事实

### 3.1 当前 eth_call 入口会吞掉 historical selector

| java-tron 源码 | 当前事实 | L8 动作 |
| --- | --- | --- |
| `TronJsonRpcImpl.java:1001-1003` | `getCall(CallArguments,Object)` 是 `eth_call` 入口 | 在这里新增 latest/historical 分流 |
| `TronJsonRpcImpl.java:1005-1037` | object 参数支持 `blockNumber`/`blockHash`，但校验后 line 1037 强制 `blockNumOrTag = LATEST_STR` | 删除 silent rewrite；保留原始 selector 进入 resolver |
| `TronJsonRpcImpl.java:1044` | 当前总是 `requireLatestBlockTag(blockNumOrTag)` | latest 分支保留；historical 分支绕开 |
| `TronJsonRpcImpl.java:1046-1050` | 解析 from/to/value/data 后调用 `call(...)` | historical 分支解析同样参数，但调用 `HistoricalEthCallSupport.call` |
| `TronJsonRpcImpl.java:557-608` | `call(...)` 把 `TransactionExtention` 转 JSON-RPC success/revert | 抽成共享 `encodeConstantCallResult`，latest/historical 都复用 |

L8 后推荐分支：

```java
public String getCall(CallArguments args, Object blockParam)
    throws JsonRpcInvalidParamsException, JsonRpcInvalidRequestException,
    JsonRpcInternalException {
  EthCallBlockSelector selector = ethCallBlockSelector.parse(blockParam);
  if (selector.isLatest()) {
    return callLatest(args);
  }
  return historicalEthCallSupport.call(args, selector);
}
```

`selector.isLatest()` 只对 `latest` 或缺省 true。`{"blockNumber":"latest"}` 这种 object-form 是否接受要显式测试；P0 建议与 Erigon object parser 对齐，只接受 quantity 或 hash，tag 继续走 string form，避免 object parser 口径扩大。

### 3.2 latest Wallet constant call 固定读最新状态

| java-tron 源码 | 当前事实 | L8 结论 |
| --- | --- | --- |
| `TronJsonRpcImpl.java:473-496` | `callTriggerConstantContract` 构造 trigger 后调用 `wallet.triggerConstantContract` | historical path 不调用 |
| `Wallet.java:3086-3094` | `triggerConstantContract` 是 latest constant call pipeline | latest 分支保留 |
| `Wallet.java:3112-3118` | call contract 前用 latest `ContractStore` 判断合约存在 | historical 合约存在性必须读 archive `CONTRACT` |
| `Wallet.java:3130-3141` | `callConstantContract` 取 latest block，并用 `StoreFactory.getInstance()` | historical block env 和 repository 必须由 L8 注入 |
| `Wallet.java:3142-3145` | `new VMActuator(true)` 后 `validate/execute` | L8 可复用 VMActuator，但必须提供 historical root repository |
| `Wallet.java:3155-3176` | `ProgramResult` 写入 `TransactionExtention`/`Return` | L8 executor 复用这段结果组装语义 |

可复用：

| 源码 | 可复用原因 |
| --- | --- |
| `JsonRpcApiUtil.java:94-108` | `triggerCallContract` 只构造 protobuf，不读 Store |
| `Wallet.java:476-498` | `createTransactionCapsule` 对 `TriggerSmartContract` 构造交易壳，不执行 Store 读 |
| `CallArguments.java:61-65` | `resolveData` 只做 `input/data` precedence 和 hex 校验 |
| `CallArguments.java:108-110` | `parseValue` 只解析 quantity |

不可复用：

| 源码 | 风险 |
| --- | --- |
| `CallArguments.java:70-106` | `getContractType(wallet)` 用 latest `wallet.getContract` 判断 call/create/transfer |
| `Wallet.java:3179-3198` | `getContract` 用 latest account/contract/abi stores |
| `Wallet.java:3208-3241` | `getContractInfo` 混合 latest account/contract/code/state/dynamic |
| `TronJsonRpcImpl.java:613-631` | latest `getStorageAt` 构造 `StorageRowStore` physical key |

L8 historical 参数规则：

```text
from:
  保持 CallArguments 默认 zero address 行为。

to:
  P0 required。
  缺失表示 create call，P0 返回 invalid request 或 unsupported historical create call。

data/input:
  复用 resolveData，input 优先于 data。

value:
  解析为 long。
  P0 允许对合约 call 带 callValue；转账只在 overlay 内发生。
  to 非合约且 value 非空的 transfer-only eth_call 不支持。

gas/gasPrice/nonce:
  与 4e80 latest eth_call 一样忽略。

tokenValue/tokenId:
  JSON-RPC CallArguments 当前没有字段；P0 不扩展。
```

### 3.3 VMActuator 当前总是创建 latest Repository

| java-tron 源码 | 当前事实 | L8 动作 |
| --- | --- | --- |
| `VMActuator.java:122-125` | `ConfigLoader.load(context.getStoreFactory())` 写全局 `VMConfig` | historical path 改用 `VmDynamicProperties` + `VmConfigScope` |
| `VMActuator.java:127-132` | constant feeLimit 用 latest `DynamicPropertiesStore.getEnergyFee()` | historical path 用 `HistoricalVmDynamicProperties.getEnergyFee()` |
| `VMActuator.java:140-142` | `rootRepository = RepositoryImpl.createRoot(context.getStoreFactory())` | historical path 注入 `ArchiveRepositoryAdapter` |
| `VMActuator.java:152-154` | constant call executor type 为 `ET_PRE_TYPE` | historical constant call 保持 `ET_PRE_TYPE`，但 blockCap 是 historical block |
| `VMActuator.java:225-232` | constant call 有异常时 set runtimeError 后 return，不 commit | historical path 保持不 commit |
| `VMActuator.java:455-523` | call path 从 repository 读 supportVM、contract、code、feeLimit、caller、cpu time | repository/dynamic 必须来自 archive/historical |
| `VMActuator.java:544-549` | callValue/tokenValue 会写 repository | 写入 overlay，调用结束丢弃 |

不要把 `Repository` 字段塞进 `TransactionContext`。`TransactionContext` 在 `chainbase`，`Repository` 在 `actuator`；反向依赖会破坏模块边界。

推荐最小注入点：

```java
public class VMActuator implements Actuator2 {
  private final Repository injectedRootRepository;
  private final VmDynamicProperties injectedVmProperties;

  public VMActuator(boolean isConstantCall) {
    this(isConstantCall, null, null);
  }

  static VMActuator historicalConstantCall(Repository repository,
      VmDynamicProperties vmProperties) {
    return new VMActuator(true, repository, vmProperties);
  }

  private boolean hasInjectedHistoricalState() {
    return injectedRootRepository != null;
  }
}
```

`validate` 内部拆分：

```java
private VmDynamicProperties loadVmProperties(TransactionContext context) {
  if (hasInjectedHistoricalState()) {
    ConfigLoader.load(injectedVmProperties);
    return injectedVmProperties;
  }
  ConfigLoader.load(context.getStoreFactory());
  return context.getStoreFactory().getChainBaseManager().getDynamicPropertiesStore();
}

private Repository prepareRootRepository(TransactionContext context) {
  if (hasInjectedHistoricalState()) {
    return injectedRootRepository;
  }
  return RepositoryImpl.createRoot(context.getStoreFactory());
}
```

这个改动保持 latest constructor 和 latest behavior 不变，只给 historical executor 一条显式入口。

### 3.4 RepositoryImpl latest 读路径不可继承

| java-tron 源码 | 当前事实 | L8 约束 |
| --- | --- | --- |
| `RepositoryImpl.java:150-177` | root repo 从 `StoreFactory.ChainBaseManager` 取 latest stores | `ArchiveRepositoryAdapter` 不继承 `RepositoryImpl` |
| `RepositoryImpl.java:309-325` | `getAccount` parent miss 读 latest `AccountStore` | root miss 读 archive `ACCOUNT` |
| `RepositoryImpl.java:329-350` | `getDynamicProperty` parent miss 读 latest dynamic store | root miss 读 archive `DYNAMIC_PROPERTIES` |
| `RepositoryImpl.java:501-518` | `getContract` parent miss 读 latest `ContractStore` | root miss 读 archive `CONTRACT` |
| `RepositoryImpl.java:650-669` | `getCode` parent miss 读 latest `CodeStore` | root miss 读 archive `CODE` |
| `RepositoryImpl.java:681-718` | storage 读 latest `StorageRowStore`/`Storage` | root miss 读 archive semantic `CONTRACT_STORAGE` |
| `RepositoryImpl.java:731-733` | balance 从 account capsule 派生 | archive adapter 同样从 historical account 派生 |
| `RepositoryImpl.java:766-783` | root commit 会写 latest stores | archive root commit no-op；child commit 只 merge parent overlay |

L8 root repository 必须是独立实现：

```text
ArchiveRepositoryAdapter implements Repository
  parent = null
  reader = ArchiveStateReader(point)
  overlay = local mutable maps
  dynamic = HistoricalVmDynamicProperties

ArchiveRepositoryChild implements Repository
  parent = Repository
  overlay = local mutable maps
  dynamic = parent.dynamic
```

继承 `RepositoryImpl` 的风险太高：一个没有 override 的方法就会读 latest store 或写 latest store。

### 3.5 VM 内部普通 opcode 可通过 Repository 封闭

| java-tron 源码 | 当前事实 | L8 映射 |
| --- | --- | --- |
| `ProgramInvokeFactory.java:68-118` | call invoke 的 balance、block env、data 都从 deposit/block/tx 派生 | deposit 用 archive repo；block 用 historical block |
| `Program.java:1291-1294` | `EXTCODE*` code 走 `invoke.getDeposit().getCode` | archive repo `getCode` |
| `Program.java:1296-1317` | `EXTCODEHASH` 读 account/contract/code，并可能 update contract codeHash | update 只进 overlay |
| `Program.java:1345-1355` | `BLOCKHASH` 走 `contractState.getBlockByNum(index)` | archive repo 可用 historical block reader 或 wallet block store 只读历史块 |
| `Program.java:1363-1365` | `BALANCE` 走 `getContractState().getBalance` | archive repo `getBalance` |
| `Program.java:1444-1447` | `SLOAD` 走 `getContractState().getStorageValue` | archive repo storage overlay + archive reader |
| `Program.java:1281-1285` | `SSTORE` 走 `putStorageValue` | overlay only |
| `Program.java:834-969`、`1029-1181` | internal call/create 使用 `newRepositoryChild` 和 child `commit` | child commit merge parent overlay；root final discard |

这些路径可以在 P0 支持：

```text
CALL / CALLCODE / DELEGATECALL / STATICCALL
BALANCE / SELFBALANCE
EXTCODESIZE / EXTCODECOPY / EXTCODEHASH
SLOAD / SSTORE
LOG*
BLOCKHASH / COINBASE / TIMESTAMP / NUMBER
BASEFEE/GASPRICE as TVM energyFee
```

需要谨慎或 P0 unsupported：

| 路径 | 风险 |
| --- | --- |
| `Program.java:630-679` selfdestruct freeze/resource merge | 直接构造 `BandwidthProcessor(ChainBaseManager.getInstance())` 和 latest account store |
| `VMActuator.java:558-590`、`600-765` 非 constant energy accounting helper | constant call 主路径通常跳过，但 create/value/resource 分支可能触发 |
| native contract processors | 多处 `ChainBaseManager.getInstance()` 或 latest stores |
| vote/reward helpers | 需要 votes/delegation/witness 历史域 |
| TRC10 asset operations | 需要 asset issue store/domain 和 dynamic store |
| `LogInfoTriggerParser` | 事件插件 ABI lookup 读 latest `StoreFactory.getInstance()`；historical call 应关闭 event listener |

P0 策略：

```text
普通合约读调用支持。
触发 native/resource/vote/TRC10/asset/witness/delegation 未覆盖路径时 fail-fast unsupported。
不要为了兼容这些路径读取 latest store。
```

## 4. 包边界与目标文件

### 4.1 common

```text
common/src/main/java/org/tron/core/vm/config/
  VmDynamicProperties.java
  VmConfigSnapshot.java
  VmConfigScope.java
```

`VmDynamicProperties` 放在 common，因为：

- `VMConfig` 已在 common。
- `ConfigLoader` 在 actuator，可依赖 common。
- `DynamicPropertiesStore` 在 chainbase，可实现 common interface。
- `Repository` 在 actuator，可返回 common interface，避免 chainbase 依赖 actuator。

最小接口：

```java
public interface VmDynamicProperties {
  boolean supportVM();

  long getEnergyFee();
  long getMaxFeeLimit();
  long getMaxCpuTimeOfOneTx();
  long getLatestBlockHeaderTimestamp();

  long getTotalNetLimit();
  long getTotalNetWeight();
  long getTotalEnergyCurrentLimit();
  long getTotalEnergyWeight();
  long getTotalTronPowerWeight();

  long getUnfreezeDelayDays();
  boolean supportUnfreezeDelay();
  boolean supportAllowNewResourceModel();

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

  long getCurrentCycleNumber();
}
```

`ConfigLoader` 新增：

```java
public static void load(VmDynamicProperties ds) {
  VMConfig.setVmTrace(CommonParameter.getInstance().isVmTrace());
  VMConfig.initVmHardFork(checkForEnergyLimit(ds));
  ...
}
```

`checkForEnergyLimit` 当前接受 `DynamicPropertiesStore`，需要提取一个只读 helper，使它接受 `VmDynamicProperties` 或必要字段。

`VmConfigSnapshot` 捕获：

```text
CommonParameter.ENERGY_LIMIT_HARD_FORK
VMConfig.vmTrace()
VMConfig.allowTvmTransferTrc10()
VMConfig.allowTvmConstantinople()
VMConfig.allowMultiSign()
VMConfig.allowTvmSolidity059()
VMConfig.allowShieldedTRC20Transaction()
VMConfig.allowTvmIstanbul()
VMConfig.allowTvmFreeze()
VMConfig.allowTvmVote()
VMConfig.allowTvmLondon()
VMConfig.allowTvmCompatibleEvm()
VMConfig.allowHigherLimitForMaxCpuTimeOfOneTx()
VMConfig.allowTvmFreezeV2()
VMConfig.allowOptimizedReturnValueOfChainId()
VMConfig.allowDynamicEnergy()
VMConfig.getDynamicEnergyThreshold()
VMConfig.getDynamicEnergyIncreaseFactor()
VMConfig.getDynamicEnergyMaxFactor()
VMConfig.allowTvmShanghai()
VMConfig.allowEnergyAdjustment()
VMConfig.allowStrictMath()
VMConfig.allowTvmCancun()
VMConfig.disableJavaLangMath()
VMConfig.allowTvmBlob()
VMConfig.allowTvmSelfdestructRestriction()
VMConfig.allowTvmOsaka()
VMConfig.allowHardenResourceCalculation()
```

`VmConfigScope`：

```java
try (VmConfigScope ignored = VmConfigScope.enter(historicalVmProperties)) {
  vmActuator.validate(context);
  vmActuator.execute(context);
}
```

关闭时恢复 snapshot。测试必须证明 historical call 退出后 latest VMConfig 回到进入前状态。

### 4.2 chainbase

```text
chainbase/src/main/java/org/tron/core/archive/vm/
  HistoricalVmDynamicProperties.java
  HistoricalVmDynamicPropertyKeys.java
  HistoricalCallUnsupportedException.java
```

`HistoricalVmDynamicProperties` 输入：

```text
ArchiveStateReader reader
ArchiveStatePoint point
BlockCapsule historicalBlock
CommonParameter defaults
```

读取规则：

```text
latestBlockHeaderTimestamp:
  直接来自 historicalBlock.header.raw.timestamp，不从 latest dynamic store 读。

supportVM / getEnergyFee / getMaxFeeLimit / getMaxCpuTimeOfOneTx / hardfork flags:
  先读 archive DYNAMIC_PROPERTIES domain。
  若该 key 在 L3 标记为 protocol default before first write，可用 default。
  若 required key 缺失且没有 default，抛 ArchiveStateUnavailableException。

currentCycleNumber / total weights / resource model fields:
  P0 普通 eth_call 可读。
  若 VM 触发需要但 archive 未覆盖，抛 HistoricalCallUnsupportedException。
```

`HistoricalCallUnsupportedException` 语义：

```text
表示 historical VM 执行触达 P0 未覆盖 domain 或 native latest-only 路径。
JSON-RPC 映射为 -32000 internal error。
不能映射为 invalid params，也不能返回 zero。
```

### 4.3 actuator

```text
actuator/src/main/java/org/tron/core/vm/repository/
  ArchiveRepositoryAdapter.java
  ArchiveRepositoryChild.java
  ArchiveRepositoryOverlay.java
  ArchiveStorageOverlay.java
  UnsupportedArchiveRepositoryAccess.java

actuator/src/main/java/org/tron/core/vm/archive/
  HistoricalConstantCallExecutor.java
  HistoricalConstantCallRequest.java
  HistoricalConstantCallResult.java
  HistoricalVmExecutionException.java
```

`ArchiveRepositoryAdapter`：

```text
implements Repository
does not extend RepositoryImpl
root reader = ArchiveStateReader
root point = ArchiveStatePoint
overlay = mutable copy-on-write maps
vmProperties = HistoricalVmDynamicProperties
blockProvider = historical block lookup for BLOCKHASH
```

`ArchiveRepositoryChild`：

```text
parent Repository
overlay local maps
commit() merges overlay into parent via put* methods
root final commit() no-op
```

`HistoricalConstantCallExecutor` 不放 framework，原因：

- 它需要 `VMActuator`、`Repository`、`TransactionContext`、`ProgramResult`，属于 actuator execution boundary。
- framework 只做 RPC 参数和 JSON-RPC 错误映射。
- chainbase 不能依赖 actuator。

### 4.4 framework

```text
framework/src/main/java/org/tron/core/services/jsonrpc/
  HistoricalEthCallBlockSelector.java
  HistoricalEthCallSupport.java
  ConstantCallResultEncoder.java
  TronJsonRpcImpl.java
```

`HistoricalEthCallSupport` 输入：

```text
CallArguments args
HistoricalEthCallBlockSelector selector
```

输出：

```text
String JSON-RPC hex result
throws JsonRpcInvalidParamsException
throws JsonRpcInvalidRequestException
throws JsonRpcInternalException
```

它负责把 framework 的 `CallArguments` 转成 actuator 的 `HistoricalConstantCallRequest`，但不执行 VM 逻辑。

## 5. ArchiveRepositoryAdapter 详细设计

### 5.1 overlay 数据结构

```java
final class ArchiveRepositoryOverlay {
  Map<Key, Value<Account>> accounts;
  Map<Key, Value<SmartContract>> contracts;
  Map<Key, Value<ContractState>> contractStates;
  Map<Key, Value<byte[]>> codes;
  Map<Key, ArchiveStorageOverlay> storageByAddress;
  Map<Key, Value<byte[]>> dynamicProperties;
  Set<Key> newContracts;
  HashBasedTable<Key, Key, Value<byte[]>> transientStorage;
}
```

注意：

- overlay value 必须 defensive copy。
- tombstone/delete 要显式表达，不能用 map miss 表示 deleted。
- `Value.Type` 语义可复用现有 `Value`，但不要调用 `RepositoryImpl.commit*`。
- storage overlay 要能区分 `missing`、`zero/tombstone`、`present non-zero`。

### 5.2 account

读：

```text
getAccount(address):
  1. normalize address with TransactionTrace.convertToTronAddress where current code does so
  2. overlay account hit:
       tombstone -> null
       present -> defensive AccountCapsule copy
  3. parent != null -> parent.getAccount(address)
  4. root -> reader.getAccount(address21)
```

写：

```text
createAccount/updateAccount/putAccount/putAccountValue:
  write overlay only
```

余额：

```text
getBalance(address):
  account = getAccount(address)
  return account == null ? 0 : account.getBalance()

addBalance(address,value):
  create normal account in overlay if missing
  check underflow
  update overlay account
```

P0 允许 value transfer inside call，因为它只影响 overlay。

### 5.3 contract/code

读：

```text
getContract(address):
  overlay -> parent -> reader.getContract(address21)

getCode(address):
  overlay -> parent -> reader.getCode(address21)
```

写：

```text
createContract/updateContract/saveCode/deleteContract:
  write overlay only
```

`Program.getCodeHashAt` 当前如果 contract codeHash empty 会：

```text
compute hash
contract.setCodeHash(codeHash)
updateContract(tronAddr, contract)
```

这在 historical path 只能写 overlay。测试要覆盖：

```text
contract codeHash missing in archive
eth_call EXTCODEHASH computes hash
second read in same call sees overlay codeHash
after call archive/latest store unchanged
```

### 5.4 storage

读：

```text
getStorageValue(address, slot):
  1. normalize address to 21 bytes
  2. overlay storage hit -> return copy
  3. parent != null -> parent.getStorageValue(address, slot)
  4. root:
       contract = getContract(address)
       if contract == null -> null
       version = contract.getContractVersion()
       key = address21 || slot32 || version_u8
       value = reader.getStorageValue(address21, slot32, version)
       missing/tombstone -> null or DataWord.ZERO according current Storage semantics
```

写：

```text
putStorageValue(address, slot, value):
  overlay storage[address][slot] = value copy
```

不要构造 `new Storage(address, StorageRowStore)`。historical storage 必须用 L4 semantic key，而不是 latest physical key。

`getStorage(address)`：

P0 有两个选择：

```text
Option A:
  return ArchiveRepositoryStorage implements Storage-like wrapper
  但 Storage 是 concrete class，构造需要 StorageRowStore，扩展成本高。

Option B:
  支持 VM hot path 的 getStorageValue/putStorageValue；
  getStorage(address) 若被非 hot path 调用则 unsupported。
```

建议 P0 用 Option B，并通过测试和 `rg` 证明 constant call hot path 不依赖 `getStorage(address)` 直接返回完整 storage object。后续如 proof/debug 需要 storage iteration，再补 archive-native iterator。

### 5.5 dynamic properties

`Repository` 新增默认方法：

```java
default VmDynamicProperties getVmDynamicProperties() {
  return getDynamicPropertiesStore();
}
```

`ArchiveRepositoryAdapter`：

```java
@Override
public VmDynamicProperties getVmDynamicProperties() {
  return historicalVmDynamicProperties;
}

@Override
public DynamicPropertiesStore getDynamicPropertiesStore() {
  throw new UnsupportedArchiveRepositoryAccess("DynamicPropertiesStore is latest-only; use getVmDynamicProperties");
}
```

然后 VM hot path 把 `getDynamicPropertiesStore()` 替换为 `getVmDynamicProperties()`。

允许保留的 `getDynamicPropertiesStore()`：

```text
RepositoryImpl 内部 latest implementation
非 historical VM 可达路径
测试 helper
```

停止条件：

```bash
rg -n 'getDynamicPropertiesStore\\(\\)' actuator/src/main/java/org/tron/core/vm
```

所有 historical constant-call 可达命中都要替换或明确 unsupported。

### 5.6 block lookup

`Program.getBlockHash(index)` 会调用 `contractState.getBlockByNum(index)`。

P0 设计：

```text
ArchiveRepositoryAdapter.getBlockByNum(num):
  if num is within 256 blocks before historical block:
    use read-only block provider to fetch canonical historical block by num
    verify block hash/num canonical if L2 block index available
    return BlockCapsule
  else:
    return null
```

这里可用 `Wallet.getBlockByNum` 或 `ChainBaseManager.getBlockStore` 只读历史块，但必须满足：

- 只读 block metadata，不读 latest account/contract/storage state。
- blockNum/hash 缺失时返回 null，让 opcode 返回 zero。
- 不用于 state point fallback。

如果评审要求完全不碰 `Wallet`，则在 L2/L5 提供 `ArchiveBlockHeaderReader`。

### 5.7 unsupported methods

`Repository` 接口很大，P0 必须显式分类：

支持：

```text
createAccount
getAccount
updateAccount
getDynamicProperty
updateDynamicProperty
deleteContract
createContract
getContract
getContractState
updateContract
updateContractState
putNewContract
isNewContract
saveCode
getCode
putStorageValue
getStorageValue
getBalance
addBalance
newRepositoryChild
setParent
commit
putAccount / putCode / putContract / putContractState / putStorage / putAccountValue
putDynamicProperty
getBlockByNum
getHeadSlot / getSlotByTimestampMs
```

Maybe support if DYNAMIC/ACCOUNT domains cover enough:

```text
getAccountLeftEnergyFromFreeze
getAccountEnergyUsage
getAccountEnergyUsageBalanceAndRestoreSeconds
getAccountNetUsageBalanceAndRestoreSeconds
calculateGlobalEnergyLimit
getTotalNetWeight
getTotalEnergyWeight
getTotalTronPowerWeight
```

P0 unsupported unless L3/L4 explicitly includes domains:

```text
AssetIssueCapsule getAssetIssue
AssetIssueStore / AssetIssueV2Store getters
DelegationStore getter
DelegatedResource*
Votes*
Witness*
AccountVote*
token balance / TRC10 asset balance
black hole address from AccountStore
resource total mutations
full Storage object iteration
```

Unsupported message pattern：

```text
historical eth_call requires archive domain <DOMAIN>, but L8 P0 does not cover it
```

不要返回 null 让 VM 继续用错误状态执行。

## 6. HistoricalConstantCallExecutor 流程

### 6.1 request

```java
public final class HistoricalConstantCallRequest {
  private final ArchiveStatePoint point;
  private final BlockCapsule historicalBlock;
  private final byte[] ownerAddress;
  private final byte[] contractAddress;
  private final long callValue;
  private final byte[] data;
  private final long feeLimit;
}
```

`feeLimit`：

```text
保持 latest eth_call 当前 fee limit 口径。
如果当前 Wallet.createTransactionCapsule 未设置 feeLimit，则使用默认 0。
VMActuator constant call 会按 maxEnergyLimitForConstant 和 feeLimit/energyFee 取 min。
```

如需与 latest `callTriggerConstantContract` 完全一致，先复核 `TransactionCapsule` 默认 feeLimit，再在 L8 测试里固定。

### 6.2 execution

```text
execute(request):
  1. open ArchiveStateReader at point
  2. build HistoricalVmDynamicProperties(reader, point, historicalBlock)
  3. build ArchiveRepositoryAdapter(reader, point, vmProps, blockProvider)
  4. verify target contract exists in archive repo
  5. build TriggerSmartContract with JsonRpcApiUtil.triggerCallContract
  6. build TransactionCapsule with Wallet.createTransactionCapsule
  7. build TransactionContext(historicalBlock, txCap, StoreFactory.getInstance(), true, false)
     StoreFactory remains for legacy constructor shape only; historical VMActuator must not read it.
  8. enter VmConfigScope(vmProps)
  9. VMActuator.historicalConstantCall(rootRepository, vmProps).validate(context)
 10. VMActuator.execute(context)
 11. convert ProgramResult to TransactionExtention + Return
 12. discard root overlay and close reader
```

第 7 步是过渡方案。更干净的长期方案是新增 actuator 内部 context，不依赖 `TransactionContext.storeFactory`。P0 为降低改动量可先保留 `TransactionContext`，但必须测试 historical path 不调用：

```text
context.getStoreFactory().getChainBaseManager().getAccountStore()
context.getStoreFactory().getChainBaseManager().getContractStore()
context.getStoreFactory().getChainBaseManager().getCodeStore()
context.getStoreFactory().getChainBaseManager().getStorageRowStore()
context.getStoreFactory().getChainBaseManager().getDynamicPropertiesStore()
```

### 6.3 result assembly

从 `Wallet.callConstantContract` 抽出 helper：

```java
public final class ConstantCallResultBuilder {
  public static Transaction buildTransactionExtension(
      TransactionCapsule txCap,
      ProgramResult result,
      TransactionExtention.Builder builder,
      Return.Builder retBuilder,
      boolean isEstimating) {
    ...
  }
}
```

或先在 `Wallet` 内新增 package-visible static helper：

```java
static Transaction fillConstantCallResult(TransactionCapsule trxCap,
    ProgramResult result,
    TransactionExtention.Builder builder,
    Return.Builder retBuilder,
    boolean isEstimating)
```

要求：

- latest path 复用 helper，避免两套 result semantics。
- historical path success/revert/internal error 与 latest `call()` 输出一致。
- `tryDecodeRevertReason` 仍只在 framework result encoder 中处理。

`TronJsonRpcImpl.call(...)` 拆分：

```java
private String callLatest(...)
private String encodeConstantCallResult(TransactionExtention.Builder trxExtBuilder,
    Return.Builder retBuilder)
```

historical executor 返回：

```java
HistoricalConstantCallResult {
  TransactionExtention transactionExtension;
  Return returnValue;
}
```

framework 复用同一个 encoder。

## 7. JSON-RPC block selector

### 7.1 parser

```java
final class HistoricalEthCallBlockSelector {
  enum Kind { LATEST, BLOCK_NUMBER, BLOCK_HASH }
  Kind kind;
  String raw;
  long blockNumber;
  Sha256Hash blockHash;
}
```

输入规则：

```text
blockParam missing/null:
  latest

String:
  "latest" -> latest
  "earliest" -> block 0
  "finalized" -> solid block
  quantity -> block number
  "pending"/"safe" -> unsupported as current JsonRpcApiUtil does

Object:
  {"blockNumber":"0xN"} -> BLOCK_NUMBER
  {"blockHash":"0x..."} -> BLOCK_HASH
  both present -> invalid request
  neither present -> invalid request
  wrong type -> invalid params
```

P0 object-form does not accept `requireCanonical` unless java-tron JSON-RPC already supports it elsewhere. If later added:

```text
requireCanonical=true:
  verify hash equals canonical block hash at that number.
requireCanonical=false:
  for archive sidecar P0 still only supports canonical history.
```

### 7.2 archive disabled behavior

L6 getters preserved default-off compatibility by letting non-latest fall through to `requireLatestBlockTag` where needed.

L8 has a different issue: current object-form `eth_call` silently latest after validating historical block. Keeping that behavior would preserve a bug. L8 should choose:

```text
latest selector:
  existing latest path

non-latest selector + archive disabled:
  explicit error: historical eth_call requires archive node
  no latest fallback

non-latest selector + archive enabled:
  resolve ArchiveStatePoint
```

This is a behavior correction for object-form historical eth_call. Add regression test so future changes cannot reintroduce silent latest.

### 7.3 error mapping

| Condition | JSON-RPC error |
| --- | --- |
| malformed block object | `JsonRpcInvalidRequestException(JSON_ERROR)` |
| malformed block number/hash | `JsonRpcInvalidParamsException` |
| missing block header by number | `JsonRpcInternalException(NO_BLOCK_HEADER)` |
| missing block header by hash | `JsonRpcInternalException(NO_BLOCK_HEADER_BY_HASH)` |
| archive disabled for non-latest | `JsonRpcInternalException("historical eth_call requires archive node")` |
| archive point older than retained history | `JsonRpcInternalException("archive history unavailable at block ...")` |
| archive corrupt/codec error | `JsonRpcInternalException("archive state corrupt ...")` |
| unsupported VM domain/path | `JsonRpcInternalException("historical eth_call unsupported: ...")` |
| contract missing at historical point | match latest validation wording: `JsonRpcInvalidRequestException("Smart contract is not exist.")` or `ContractValidateException` mapping |
| VM revert with return data | same as latest `JsonRpcInternalException(message, data)` |

Contract missing choice:

```text
Wallet.triggerConstantContract currently throws ContractValidateException("Smart contract is not exist.")
TronJsonRpcImpl.call catches ContractValidateException and maps invalid request.
L8 should preserve that wording for historical missing target contract.
```

## 8. Integration with L6/L7

### 8.1 StatePoint

L8 consumes L6:

```text
ArchiveStatePoint {
  blockNum
  blockHash
  finalizeTxNum
  pointKind = BLOCK_END
}
```

P0 historical `eth_call` is block-end only：

```text
eth_call(args, "0xN") executes after all transactions in block N.
```

Transaction-level state point is future:

```text
eth_call(args, {"blockNumber":"0xN","transactionIndex":"0xI"})
```

Not in L8 P0.

### 8.2 Coverage

Before executing:

```text
ArchiveTemporalStore progress >= point.finalizeTxNum
Required domains covered at point:
  ACCOUNT
  CONTRACT
  CODE
  CONTRACT_STORAGE
  DYNAMIC_PROPERTIES
Optional block hash provider has historical block header.
If L7 enabled:
  root coverage record includes required domain set.
```

如果 L7 未启用但 L6/L5 已有 temporal coverage：

```text
L8 can run without commitment root,
but must mark response path as unverified by root in logs/metrics if debug metrics exist.
```

不要把 L7 root mismatch 映射成 silent latest。strict archive mode 可选择拒绝 historical call；default 建议拒绝 corrupt/gap，避免返回无法信任的结果。

## 9. 测试计划

### 9.1 unit tests by module

```text
ArchiveRepositoryAdapterTest
  getAccount reads ACCOUNT domain at point
  getContract reads CONTRACT domain at point
  getCode reads CODE domain at point
  getStorageValue uses address21||slot32||version
  no latest AccountStore/ContractStore/CodeStore/StorageRowStore fallback
  missing account balance returns 0
  missing contract returns null
  codec corrupt throws internal/corrupt exception

ArchiveRepositoryChildTest
  child write visible to child
  child commit visible to parent overlay
  root commit does not write latest store
  delete tombstone hides parent/root value
  SSTORE then SLOAD in same call sees overlay
  internal CALL child commit merges storage/account/code changes

HistoricalVmDynamicPropertiesTest
  reads required flags from DYNAMIC_PROPERTIES at point
  latestBlockHeaderTimestamp comes from historical block
  missing required key fails
  protocol default before first write is honored only for allowlisted keys

VmConfigScopeTest
  snapshot captures all VMConfig flags used by ConfigLoader
  historical scope loads historical flags
  close restores previous flags after success
  close restores previous flags after exception
```

### 9.2 executor tests

```text
HistoricalConstantCallExecutorTest
  calls view function reading storage changed after target block; returns historical value
  EXTCODEHASH/EXTCODESIZE use historical code, not latest code
  block.number/timestamp/coinbase come from historical block
  callValue transfer affects overlay during execution, not latest store
  revert return data encoded like latest path
  contract missing at historical point maps to ContractValidateException-compatible error
  unsupported native/resource path maps to HistoricalCallUnsupportedException
  event listener disabled; no latest ABI Store read
```

### 9.3 framework JSON-RPC tests

```text
TronJsonRpcEthCallArchiveTest
  eth_call(args, "latest") uses existing latest Wallet path
  eth_call(args, "0xN") uses historical executor
  eth_call(args, {"blockNumber":"0xN"}) is not rewritten to latest
  eth_call(args, {"blockHash":"0x..."}) resolves by hash and is not rewritten to latest
  object with both blockNumber and blockHash is invalid request
  object with missing block is NO_BLOCK_HEADER / NO_BLOCK_HEADER_BY_HASH
  archive disabled + non-latest returns explicit historical archive error
  archive gap/corrupt returns internal error, no default value
  missing target contract at historical point keeps latest error wording
  latest result/revert regression remains unchanged

JsonrpcServiceTest regression
  existing eth_call latest cases still pass
  historical object-form no longer silently latest
```

### 9.4 leak detection tests

用 spy/fake stores：

```text
latest AccountStore throws if read
latest ContractStore throws if read
latest CodeStore throws if read
latest StorageRowStore throws if read
latest DynamicPropertiesStore throws if read from historical VM path
```

historical call 应通过。若触发 latest store，测试失败。

### 9.5 gate commands

```bash
./gradlew :actuator:test --tests '*ArchiveRepositoryAdapterTest'
./gradlew :actuator:test --tests '*ArchiveRepositoryChildTest'
./gradlew :chainbase:test --tests '*HistoricalVmDynamicPropertiesTest'
./gradlew :common:test --tests '*VmConfigScopeTest'
./gradlew :actuator:test --tests '*HistoricalConstantCallExecutorTest'
./gradlew :framework:test --tests '*TronJsonRpcEthCallArchiveTest'
./gradlew :framework:test --tests 'org.tron.core.jsonrpc.JsonrpcServiceTest'
./gradlew checkstyleMain checkstyleTest
```

本轮只写规划，不运行这些命令。

## 10. Review checklist

编码评审时逐项确认：

- `TronJsonRpcImpl.getCall` 不再把 object-form historical selector 改成 `latest`。
- latest `eth_call` 仍走 `Wallet.triggerConstantContract`。
- historical `eth_call` 不调用 `Wallet.triggerConstantContract`、`Wallet.callConstantContract`、`CallArguments.getContractType(wallet)`。
- `ArchiveRepositoryAdapter` 不继承 `RepositoryImpl`。
- historical root repository 没有 latest store fallback。
- child repository commit 只 merge parent overlay。
- root repository final commit 不落盘。
- `VMActuator` historical path 不读 `context.getStoreFactory().getChainBaseManager().getDynamicPropertiesStore()`。
- `ConfigLoader.load(VmDynamicProperties)` 覆盖所有当前 `ConfigLoader.load(StoreFactory)` 写入的 VM flags。
- `VmConfigScope` finally restore，异常路径也 restore。
- `DYNAMIC_PROPERTIES` required key 缺失不会默默用 latest/default。
- `getStorageValue` 使用 L4 semantic key：`address21 || slot32 || version_u8`。
- `getStorage(address)` 没有误构造 latest `StorageRowStore`。
- `BLOCKHASH` 只读 historical block header，不参与 state fallback。
- event listener 在 historical call 中关闭。
- unsupported native/resource/vote/TRC10 paths fail-fast，不读 latest。
- result/revert encoding 只有一份共享 helper。
- archive disabled/gap/corrupt 不 fallback latest。
- tests 覆盖 object-form blockNumber/blockHash no silent latest。

## 11. 后续扩展

L8 P0 完成后，可独立追加：

```text
L8.1 transaction-index eth_call:
  use L2 txNum for intra-block point and L5 CHANGESET replay.

L8.2 state override:
  layer JSON-RPC state override above ArchiveRepositoryAdapter overlay.

L8.3 historical estimateGas:
  reuse HistoricalConstantCallExecutor with binary search gas semantics.

L8.4 native/resource domain coverage:
  extend L3/L4/L5 domains for votes/delegation/witness/assets.

L8.5 trace/debug:
  expose historical VM trace using same archive repository.
```

这些扩展不能混入 P0；P0 的验收重点是历史 selector 真实生效、普通合约读调用可用、无 latest state 泄漏、VMConfig 不污染全局。
