# java-tron Archive S12/S13：historical eth_call 4e80 编码执行包

日期：2026-06-04

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`，`git status --short` 为空，精确冲突标记扫描无命中。

Erigon 源码路径：`/Users/boson/GolandProjects/erigon`

归属路线：[java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)

前置依赖：

- [S8/S9 ArchiveStateReader 与 JSON-RPC Historical Getters 4e80 编码执行包](./20260603-java-tron-archive-s8-s9-state-reader-rpc-4e80-coding-packet.md)：`ArchiveStatePoint`、`ArchiveStatePointResolver`、`ArchiveStateReader`。
- [S10/S11 CommitmentBuilder 4e80 编码执行包](./20260603-java-tron-archive-s10-s11-commitment-builder-4e80-coding-packet.md)：root/progress verifier 可证明 historical point 覆盖范围。

S12/S13 把 archive sidecar state 接入 TVM constant call，让 `eth_call(args, historicalBlock)` 在历史 block-end 状态上执行，而不是验证历史 block 后继续读 latest Store。

## 1. 交付边界

S12 交付 archive-backed VM state foundation：

```text
ArchiveStatePoint
  -> ArchiveStateReader
  -> ArchiveRepositoryAdapter implements Repository
  -> child repository overlay
  -> VmDynamicProperties historical view
  -> HistoricalConstantCallExecutor
```

S13 交付 JSON-RPC historical `eth_call` wiring：

```text
eth_call(args, blockParam)
  -> latest selector: existing Wallet.triggerConstantContract path
  -> historical selector: ArchiveStatePointResolver
  -> HistoricalConstantCallExecutor
  -> same result/revert encoding as current call()
```

本批次不交付：

- 不改 `eth_estimateGas`。
- 不改 `/wallet/triggerconstantcontract`。
- 不支持 historical contract creation call，也不支持 transfer-only `eth_call`。
- 不在 archive disabled 时 fallback latest。
- 不在 archive gap/corrupt 时返回默认值。
- 不把 VM 产生的 overlay writes 写回 archive DB 或 canonical Store。
- 不解决所有 native/precompile resource Store 域；缺少域时必须明确 unsupported。

完成条件：

1. `eth_call(args, "latest")` 仍走当前 latest path，返回、revert、error 行为不变。
2. `eth_call(args, "0xN")`、`eth_call(args, {"blockNumber":"0xN"})`、`eth_call(args, {"blockHash":"0x..."})` 不再被改写成 latest。
3. historical call 的 account、contract、code、storage、dynamic property 读取都来自 archive reader 或历史 block store。
4. historical call 不调用 `Wallet.triggerConstantContract`、`Wallet.callConstantContract`、latest `ContractStore`、latest `StorageRowStore`。
5. constant call 中产生的 balance/storage/account/code writes 只进入 child overlay，最终丢弃。
6. 如果 VM 读到 P0 未覆盖的 domain，例如 witness/delegated resource/votes，返回明确 JSON-RPC internal error，不能读 latest。
7. VMConfig 静态开关在 historical 执行期间使用 historical dynamic properties，并在退出后恢复。

## 2. 4e80 源码锚点

### 2.1 当前 JSON-RPC eth_call 会吞掉历史参数

| 源码 | 当前事实 | S13 约束 |
| --- | --- | --- |
| `TronJsonRpc.java:162-170` | `eth_call` 映射到 `getCall(CallArguments,Object)` | public API 不需要改签名 |
| `TronJsonRpcImpl.java:1001-1003` | `getCall` 是当前 `eth_call` 入口 | historical 分支从这里接入 |
| `TronJsonRpcImpl.java:1005-1037` | object 参数支持 `blockNumber`/`blockHash`，但校验存在后 line 1037 强制 `blockNumOrTag = latest` | 必须保留 object block selector，不能改写 latest |
| `TronJsonRpcImpl.java:1044` | 调 `requireLatestBlockTag(blockNumOrTag)` | latest 分支保留；historical 分支绕开这个 latest-only guard |
| `TronJsonRpcImpl.java:1046-1050` | 解析 from/to/value/data 后调用 `call(...)` | historical 分支也复用参数解析，但调用新的 executor |
| `TronJsonRpcImpl.java:383-397` | `requireLatestBlockTag` 对 non-latest tag/quantity 报 unsupported | S13 后它继续给未支持方法使用，不再挡 historical `eth_call` |

`getCall` 当前的 object 参数行为尤其危险：

```text
{"blockNumber":"0x10"} -> validates block 16 exists -> blockNumOrTag = "latest"
{"blockHash":"0x..."} -> validates hash exists     -> blockNumOrTag = "latest"
```

这会让调用者以为执行了 historical call，实际读的是 latest。S13 的第一条验收就是移除这个 silent rewrite。

### 2.2 当前 call() 结果编码可复用

| 源码 | 当前事实 | S13 约束 |
| --- | --- | --- |
| `TronJsonRpcImpl.java:557-608` | `call(...)` 负责把 `TransactionExtention` 转 JSON-RPC hex，revert 时带 data | historical executor 应返回同构结果对象，复用同一编码逻辑 |
| `TronJsonRpcImpl.java:527-551` | `tryDecodeRevertReason` 解析 `Error(string)` | 不复制一套不同 revert 文案 |
| `TronJsonRpcImpl.java:587-595` | success 时合并 `constantResultList` | historical success 同样返回 `0x...` |
| `TronJsonRpcImpl.java:596-603` | failure 有 return data 时抛 `JsonRpcInternalException(message,data)` | historical failure 对齐 |

建议把当前 `call()` 拆成两段：

```java
private String callLatest(...)
private String encodeConstantCallResult(TransactionExtention.Builder trxExtBuilder,
    Return.Builder retBuilder)
```

latest path 调 `callLatest`，historical path 构造 builder 后复用 `encodeConstantCallResult`。

### 2.3 Wallet constant call 固定 latest

| 源码 | 当前事实 | S12/S13 结论 |
| --- | --- | --- |
| `TronJsonRpcImpl.java:473-496` | `callTriggerConstantContract` 构造 trigger 后调用 `wallet.triggerConstantContract` | historical path 不调用 |
| `Wallet.java:3086-3094` | `triggerConstantContract` 进入 latest constant call pipeline | historical path 不调用 |
| `Wallet.java:3112-3118` | call contract 前用 latest `ContractStore` 判断合约存在 | historical 创建/删除后的合约会判断错 |
| `Wallet.java:3130-3141` | `callConstantContract` 取 latest block，并用 `StoreFactory.getInstance()` | block env、repository、dynamic properties 全是 latest |
| `Wallet.java:3142-3145` | `new VMActuator(true)` 后 `validate/execute` | S12 可以复用 VMActuator，但必须注入 historical repository 和 block |
| `Wallet.java:3155-3176` | 把 `ProgramResult` 写进 `TransactionExtention`/ret | historical executor 需要复用这段结果组装语义 |

`Wallet.createTransactionCapsule` 对 TriggerSmartContract 可复用：

| 源码 | 当前事实 | S12/S13 约束 |
| --- | --- | --- |
| `Wallet.java:476-498` | `CreateSmartContract`/`TriggerSmartContract` 不走普通 actuator validate；最后 `setTransaction(trx)` | historical path 可以复用交易壳构造 |
| `JsonRpcApiUtil.java:94-108` | `triggerCallContract(...)` 只构造 protobuf，不读 Store | historical path 可以复用 |

不能复用 `CallArguments.getContractType(wallet)`：

| 源码 | 当前事实 | S13 约束 |
| --- | --- | --- |
| `CallArguments.java:70-106` | 根据 latest `wallet.getContract` 判断 `to` 是合约还是 transfer | historical path 不能调用 |
| `CallArguments.java:80-87` | `to == null` 时视为 create | P0 historical create call unsupported |
| `CallArguments.java:88-103` | `to` 非合约且 value 非空时视为 transfer | P0 transfer-only historical call unsupported |

S13 的 historical 参数规则：

```text
from: required by current defaulting logic; missing keeps current default zero address
to: required and must be a contract at historical StatePoint
data/input: same resolveData precedence as current CallArguments
value: parsed, but P0 only supports contract call; value transfer inside constant overlay is allowed if VM path supports it
gas/gasPrice/nonce: same as current eth_call, ignored by java-tron path
```

### 2.4 VMActuator 当前总是创建 latest Repository

| 源码 | 当前事实 | S12 约束 |
| --- | --- | --- |
| `VMActuator.java:122-125` | `ConfigLoader.load(context.getStoreFactory())` 刷全局 `VMConfig` | historical call 需要 historical config scope |
| `VMActuator.java:127-132` | constant call feeLimit 计算读 latest `StoreFactory.DynamicPropertiesStore.energyFee` | 改为 historical vm dynamic properties |
| `VMActuator.java:140-142` | `rootRepository = RepositoryImpl.createRoot(context.getStoreFactory())` | historical executor 必须能绕开该 latest root repository 创建 |
| `VMActuator.java:152-154` | constant call executor type 为 `ET_PRE_TYPE` | historical constant call 可保持 ET_PRE_TYPE，但 blockCap 是 historical block |
| `VMActuator.java:225-232` | constant call 有异常时 set runtimeError 后 return，不 commit | historical path 保持不 commit |
| `VMActuator.java:250-260` | normal 非 constant 才 commit root repository | historical constant call 不落盘 |
| `VMActuator.java:455-474` | `call()` 检查 supportVM、contract 存在 | 这些检查要读 historical repository |
| `VMActuator.java:497-523` | 读 code、feeLimit/maxFeeLimit、caller account、maxCpuTime、创建 ProgramInvoke | 这些状态/动态参数都必须来自 historical view |
| `VMActuator.java:544-549` | callValue/tokenValue 会对 repository overlay 做 transfer | overlay 写后丢弃 |

不要把 `Repository` override 字段直接加到 `TransactionContext`。`TransactionContext` 位于 `chainbase`，而 `Repository` 接口位于 `actuator`；直接引用会让 chainbase 反向依赖 actuator。P0 应新增 actuator 侧的 `HistoricalVmCallContext`，让 `HistoricalConstantCallExecutor` 直接驱动 VMActuator，而不是把 VM repository 类型塞进 chainbase。

推荐 P0 路线：

```text
actuator:
  HistoricalConstantCallExecutor builds:
    BlockCapsule historicalBlock
    TransactionCapsule trigger tx
    ArchiveRepositoryAdapter rootRepository
    HistoricalVmDynamicProperties vmProps

  VMActuator gains package-private/test-visible:
    validateWithRepository(TransactionContext context, Repository repository, VmDynamicProperties vmProps)

framework:
  TronJsonRpcImpl calls HistoricalConstantCallExecutor for non-latest eth_call
```

如果评审更偏好通用 context 注入，再把 `Repository` 所属接口下沉到 chainbase/common；否则不要把 actuator 类型引入 chainbase。

### 2.5 RepositoryImpl latest 读路径不可复用

| 源码 | 当前事实 | S12 约束 |
| --- | --- | --- |
| `RepositoryImpl.java:150-177` | root repository 从 `StoreFactory.ChainBaseManager` 取所有 latest stores | historical adapter 不使用这些 stores 读 state |
| `RepositoryImpl.java:309-325` | `getAccount` parent miss 后读 latest `AccountStore` | archive adapter 读 `ACCOUNT` domain |
| `RepositoryImpl.java:329-350` | `getDynamicProperty` parent miss 后读 latest dynamic store | archive adapter 读 `DYNAMIC_PROPERTIES` domain |
| `RepositoryImpl.java:501-518` | `getContract` parent miss 后读 latest `ContractStore` | archive adapter 读 `CONTRACT` domain |
| `RepositoryImpl.java:650-669` | `getCode` parent miss 后读 latest `CodeStore` | archive adapter 读 `CODE` domain |
| `RepositoryImpl.java:681-718` | `getStorageValue/getStorage` 构造 latest `StorageRowStore`/`Storage` | archive adapter 直接按 `(address,slot)` 读 `CONTRACT_STORAGE` |
| `RepositoryImpl.java:731-733` | `getBalance` 从 latest account cache/store 得 balance | archive adapter 从 historical account capsule 取 |
| `RepositoryImpl.java:766-783` | `commit()` 把 cache 写 parent 或 latest stores | historical root repository commit 必须 no-op 或只合并 child overlay，不能落盘 |

P0 `ArchiveRepositoryAdapter` 不应继承 `RepositoryImpl`。继承会很容易误用 latest fallback。

## 3. Erigon 对照不变量

| Erigon 源码 | 事实 | java-tron 映射 |
| --- | --- | --- |
| `rpc/jsonrpc/eth_call.go:69-92` | `Call` 保留用户请求的 `BlockNumberOrHash`，默认才用 latest | `getCall` 不能把 object block 参数改写 latest |
| `eth_call.go:103-121` | 先解析 header、检查 prune/executed，再创建 state reader | S13 先解析 `ArchiveStatePoint` 和 coverage，再打开 archive reader |
| `eth_call.go:126` | `transactions.DoCall` 消费已创建的 state reader | java-tron `HistoricalConstantCallExecutor` 消费 `ArchiveRepositoryAdapter` |
| `eth_accounts.go:44-72` | getters 和 call 共用 state reader 解析流程 | S13 复用 S8/S9 `ArchiveStatePointResolver` |
| `history_reader_v3.go:67-76` | history reader 以 txNum 为 as-of coordinate | java-tron 用 block finalize txNum 执行 block-end `eth_call` |
| `history_reader_v3.go:95-107` | reader 可链 in-memory overlay、batch、DB history | java-tron child repository overlay 先读本次 call writes，再读 archive reader |

结论：

```text
Block selector resolution, state reader creation, and VM execution must be separated.
The RPC layer should not directly read latest Store to decide historical state.
The VM should receive an already-resolved historical repository/view.
```

## 4. 目标文件与包边界

### 4.1 common

```text
common/src/main/java/org/tron/core/vm/config/
  VmDynamicProperties.java
  VmDynamicPropertiesSnapshot.java
  VmConfigSnapshot.java
  VmConfigScope.java
```

`VmDynamicProperties` 是 VM 执行需要的最小动态参数接口，不是完整 `DynamicPropertiesStore` 抽象。

### 4.2 chainbase

```text
chainbase/src/main/java/org/tron/core/archive/reader/
  ArchiveStatePoint.java
  ResolvedArchiveStatePoint.java
  ArchiveStatePointResolver.java
  ArchiveStateReader.java
  ArchiveStateReaderFactory.java

chainbase/src/main/java/org/tron/core/archive/vm/
  HistoricalVmDynamicProperties.java
  HistoricalCallUnsupportedException.java
```

`HistoricalVmDynamicProperties` 只从 archive `DYNAMIC_PROPERTIES` domain 和 historical block header 派生值。

### 4.3 actuator

```text
actuator/src/main/java/org/tron/core/vm/repository/
  ArchiveRepositoryAdapter.java
  ArchiveRepositoryChild.java
  UnsupportedArchiveRepositoryAccess.java

actuator/src/main/java/org/tron/core/vm/archive/
  HistoricalConstantCallExecutor.java
  HistoricalConstantCallRequest.java
  HistoricalConstantCallResult.java
  HistoricalVmContext.java
```

`ArchiveRepositoryAdapter` 实现 `Repository`，但不继承 `RepositoryImpl`。

### 4.4 framework

```text
framework/src/main/java/org/tron/core/services/jsonrpc/
  TronJsonRpcImpl.java
  HistoricalEthCallSupport.java
```

`framework` 只做 RPC 参数解析、latest/historical 路由、错误映射和结果编码。

## 5. VmDynamicProperties 最小接口

`DynamicPropertiesStore` 构造器是 private Spring 构造：

| 源码 | 当前事实 | 结论 |
| --- | --- | --- |
| `DynamicPropertiesStore.java:262-263` | `private DynamicPropertiesStore(@Value("properties") String dbName)` | historical view 不适合通过子类伪造 |
| `ConfigLoader.java:16-50` | 读取 `DynamicPropertiesStore` 后写全局 `VMConfig` | 需要接口化 historical rule source |
| `VMConfig.java:16-66` | TVM hardfork/rule flags 都是 static | P0 需要 snapshot/scope 恢复 |

新增接口：

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

`DynamicPropertiesStore implements VmDynamicProperties`，因为这些方法当前已经存在或可薄封装。

`Repository` 增加：

```java
default VmDynamicProperties getVmDynamicProperties() {
  return getDynamicPropertiesStore();
}
```

然后把 VM hot path 中直接用于执行规则/能量计算的 `getDynamicPropertiesStore()` 替换为 `getVmDynamicProperties()`：

| 文件 | 替换范围 |
| --- | --- |
| `VMActuator.java` | `supportVM/getMaxFeeLimit/getMaxCpuTimeOfOneTx/getEnergyFee` |
| `RepositoryImpl.java` | `calculateGlobalEnergyLimit/getHeadSlot/getTotal*Weight` 内部可保持 latest store，因为它只服务 latest impl；archive adapter 自己实现 |
| `Program.java` | `getLatestBlockHeaderTimestamp`、`getCurrentCycleNumber`、resource/vote helper 涉及 VM dynamic 的调用 |
| `PrecompiledContracts.java` | `supportUnfreezeDelay/supportAllowNewResourceModel` 等 VM precompile 读取 |
| `ChainParameterEnum.java` | 改用 `Repository.getVmDynamicProperties()` |
| `ConfigLoader.java` | 新增 `load(VmDynamicProperties)` 重载，latest `load(StoreFactory)` 委托它 |

停止条件：

```bash
rg -n 'getDynamicPropertiesStore\\(\\)' actuator/src/main/java/org/tron/core/vm
```

允许剩余项只在 non-VM storage/store plumbing 中，不能在 historical constant-call 可达路径上。

## 6. VMConfigScope

当前 `VMConfig` 是全局 static：

| 源码 | 当前事实 |
| --- | --- |
| `VMConfig.java:16-66` | hardfork/rule flags 是 static booleans/longs |
| `ConfigLoader.java:16-50` | 每次 `VMActuator.validate` 都根据 latest dynamic store 改写 static |

P0 采用串行锁 + snapshot/restore：

```java
public final class VmConfigScope implements AutoCloseable {
  private static final ReentrantLock LOCK = new ReentrantLock();
  private final VmConfigSnapshot previous;

  public static VmConfigScope enter(VmDynamicProperties historical) {
    LOCK.lock();
    VmConfigSnapshot previous = VmConfigSnapshot.capture();
    ConfigLoader.load(historical);
    return new VmConfigScope(previous);
  }

  @Override
  public void close() {
    previous.restore();
    LOCK.unlock();
  }
}
```

要求：

- historical `eth_call` 进入 VM 前持有 scope。
- `finally` 必须 restore latest 前的 VMConfig。
- concurrent historical calls 串行。
- canonical block execution 也可能调用 `ConfigLoader.load(StoreFactory)`；P0 需要共享同一把 lock 或在 block execution 入口不并发 historical VM。

后续优化：

```text
把 VMConfig static 读改成 per-execution VmRules，消除全局锁。
```

但 P0 不应为了完美 rules 模型阻塞 historical call 的最小闭环。

## 7. HistoricalVmDynamicProperties

输入：

```text
ArchiveStateReader reader
ArchiveStatePoint point
BlockCapsule historicalBlock
```

读取来源：

| 方法 | 来源 |
| --- | --- |
| `getLatestBlockHeaderTimestamp()` | historical block header timestamp |
| `getEnergyFee()` | `DYNAMIC_PROPERTIES: ENERGY_FEE` as-of point |
| `getMaxFeeLimit()` | `DYNAMIC_PROPERTIES: MAX_FEE_LIMIT` |
| `getMaxCpuTimeOfOneTx()` | `DYNAMIC_PROPERTIES: MAX_CPU_TIME_OF_ONE_TX` |
| TVM allow flags | corresponding `DYNAMIC_PROPERTIES` keys |
| total weights/limits | `DYNAMIC_PROPERTIES` domain |
| `getCurrentCycleNumber()` | `DYNAMIC_PROPERTIES: CURRENT_CYCLE_NUMBER` if captured |

Missing key policy：

| key type | P0 behavior |
| --- | --- |
| key required by current VM execution | throw `HistoricalCallUnsupportedException("missing dynamic property ...")` |
| key for disabled feature not reached | may remain unread |
| key with java-tron default fallback in `DynamicPropertiesStore` | only use fallback if current source method itself uses fallback, and document it |

不要读 latest `DynamicPropertiesStore` 补缺口。

## 8. ArchiveRepositoryAdapter

### 8.1 构造

```java
public final class ArchiveRepositoryAdapter implements Repository {
  private final ArchiveStateReader reader;
  private final ArchiveStatePoint point;
  private final VmDynamicProperties vmProps;
  private final BlockLookup blockLookup;
  private final ArchiveRepositoryOverlay overlay;
}
```

`BlockLookup` 可由 framework/chainbase 注入，只读 canonical block store：

```java
interface BlockLookup {
  BlockCapsule getBlockByNum(long num);
}
```

Block store 是 immutable/canonical view；用于 `BLOCKHASH` 和 `CHAINID` 可接受，但必须通过 canonical check。

### 8.2 支持的读方法

| Repository 方法 | P0 实现 |
| --- | --- |
| `getAccount(address)` | overlay first；miss 后 `reader.getAccount(address, point)` |
| `getBalance(address)` | `getAccount` 后取 balance，missing 0 |
| `getContract(address)` | overlay first；miss 后 `reader.getContract(address, point)` |
| `getCode(address)` | overlay first；miss 后 `reader.getCode(address, point)`，missing empty/null 与 latest path 对齐 |
| `getStorageValue(address,key)` | overlay first；miss 后 `reader.getStorage(address,key, point)` |
| `getDynamicProperty(key)` | `reader.getDynamicProperty(key, point)` |
| `getVmDynamicProperties()` | historical `vmProps` |
| `getBlockByNum(num)` | canonical block lookup |
| `getHeadSlot()` | derived from historical header timestamp |
| `getSlotByTimestampMs(timestamp)` | same formula as `RepositoryImpl.java:991-995` |
| `getTotalNetWeight/getTotalEnergyWeight/getTotalTronPowerWeight` | `vmProps` |
| `calculateGlobalEnergyLimit(account)` | same formula as `RepositoryImpl.java:967-985` but using `vmProps` |

### 8.3 支持的 overlay 写方法

这些写只服务 VM 内部 constant call，不落盘：

| Repository 方法 | P0 实现 |
| --- | --- |
| `newRepositoryChild()` | 返回 child overlay，parent 指向当前 adapter |
| `commit()` | child -> parent overlay merge；root commit no-op |
| `createAccount/updateAccount/addBalance` | overlay account cache |
| `createContract/updateContract/saveCode` | overlay contract/code cache |
| `putStorageValue` | overlay storage cache |
| `deleteContract` | overlay tombstone |
| `putNewContract/isNewContract` | overlay marker |
| `updateDynamicProperty` | root historical call should normally not use；if child uses, overlay only |

Constant call `VMActuator.execute` 在 line `225-232` 对 constant call 直接 return，不会 root commit；但内部 CALL/CREATE 可能用 child repository commit 合并到 parent overlay。P0 必须支持 child overlay，否则 nested call 会读不到同一次 VM 内刚写入的临时状态。

### 8.4 明确 unsupported 的方法

未纳入 P0 archive domains 的方法抛 `HistoricalCallUnsupportedException`：

```text
getAssetIssue/getAssetIssueStore/getAssetIssueV2Store
getDelegatedResource/getDelegatedResourceAccountIndex
getVotes/getDelegation/getAccountVote/getBeginCycle/getEndCycle
getWitness
getTokenBalance/addTokenBalance
getBlackHoleAddress if not configured as dynamic/static historical value
```

触发这些方法时，JSON-RPC 返回：

```text
-32000 historical eth_call unsupported: missing archive domain <DOMAIN_OR_METHOD>
```

不要落回 `ChainBaseManager.getInstance()` 或 latest store。

## 9. HistoricalConstantCallExecutor

### 9.1 Request

```java
public final class HistoricalConstantCallRequest {
  private final byte[] ownerAddress21;
  private final byte[] contractAddress21;
  private final long callValue;
  private final byte[] data;
  private final ArchiveStatePoint statePoint;
  private final BlockCapsule historicalBlock;
}
```

### 9.2 Execution flow

```text
validate args:
  to must not be null
  archive reader must show contract exists at statePoint
  code must exist / non-empty according to latest behavior

build TriggerSmartContract with JsonRpcApiUtil.triggerCallContract
build TransactionCapsule with wallet.createTransactionCapsule(trigger, TriggerSmartContract)
set feeLimit for constant call if current latest path does so

open ArchiveStateReader(point)
build HistoricalVmDynamicProperties
build ArchiveRepositoryAdapter

with VmConfigScope.enter(historicalVmProps):
  run VMActuator historical validate/execute with:
    historical BlockCapsule
    transaction capsule
    archive repository
    isConstantCall=true

convert ProgramResult to TransactionExtention/Return builders using latest result rules
return builders to TronJsonRpcImpl encoder
```

### 9.3 Contract existence

Do not use `Wallet.triggerConstantContract` line `3112-3118` latest check.

Historical check:

```java
ContractCapsule contract = archiveRepository.getContract(contractAddress);
if (contract == null) {
  throw new ContractValidateException("Smart contract is not exist.");
}
```

Then `VMActuator.call()` will perform the same check again through historical repository. This duplicate check is acceptable and improves error parity.

### 9.4 Block env

Use the requested historical block:

| EVM/TVM env | Source |
| --- | --- |
| `NUMBER` | historical block header number |
| `TIMESTAMP` | historical block header timestamp / 1000 |
| `COINBASE` | historical block header witness address |
| `PREVHASH` | historical block parent hash |
| `BLOCKHASH(n)` | canonical block lookup through repository |

Current latest path uses latest block at `Wallet.java:3130-3141`; S13 must not do that for historical calls.

## 10. JSON-RPC routing

### 10.1 Resolver behavior

Reuse S8/S9 `ArchiveStatePointResolver` and extend it for object block params:

| input | behavior |
| --- | --- |
| `"latest"` or omitted | latest path |
| `"earliest"` | block 0 block-end point |
| `"finalized"` | solid block block-end point |
| `"safe"` | keep current unsupported |
| `"pending"` | keep current unsupported |
| `"0xN"` | block N block-end point |
| `{"blockNumber":"0xN"}` | block N block-end point |
| `{"blockHash":"0x..."}` | resolve canonical block by hash, then block-end point |

If block does not exist, preserve current error shape where possible:

| case | error |
| --- | --- |
| numeric missing | `NO_BLOCK_HEADER` internal error |
| hash missing | `NO_BLOCK_HEADER_BY_HASH` internal error |
| future block | no fallback; internal or invalid params, consistent with existing tests |
| archive disabled | `historical eth_call unsupported: archive disabled` |
| archive gap | `historical eth_call unavailable: archive progress ...` |

### 10.2 getCall pseudo-code

```java
public String getCall(CallArguments args, Object blockParamObj) {
  ResolvedArchiveStatePoint resolved = archiveStatePointResolver.resolveEthCall(blockParamObj);
  if (resolved.isLatest()) {
    requireLatestBlockTag("latest");
    return callLatest(...);
  }

  if (!archiveService.isEnabled()) {
    throw new JsonRpcInternalException("historical eth_call unsupported: archive disabled");
  }

  byte[] owner = addressCompatibleToByteArray(args.getFrom());
  byte[] to = addressCompatibleToByteArray(args.getTo());
  byte[] data = ByteArray.fromHexString(args.resolveData());

  HistoricalConstantCallResult result = historicalCallExecutor.execute(
      new HistoricalConstantCallRequest(owner, to, args.parseValue(), data, resolved.point()));

  return encodeConstantCallResult(result.trxExtBuilder(), result.retBuilder());
}
```

Do not call:

```text
CallArguments.getContractType(wallet)
Wallet.triggerConstantContract(...)
Wallet.callConstantContract(...)
requireLatestBlockTag(nonLatest)
```

in the historical branch.

## 11. Archive domain requirements

S12/S13 requires S3 domain registry to include or explicitly reject:

| Domain | Required for | P0 |
| --- | --- | --- |
| `ACCOUNT` | caller balance, account existence, contract account, BALANCE opcode | required |
| `CONTRACT` | contract metadata, origin address, consume resource percent, code hash, version | required |
| `CODE` | root call code, EXTCODECOPY/EXTCODESIZE, nested call code | required |
| `CONTRACT_STORAGE` | SLOAD/SSTORE overlay base | required |
| `DYNAMIC_PROPERTIES` | VM flags, energy fee, limits, block timestamp-derived values | required allowlist |
| `CONTRACT_STATE` | vote/cycle contract state helpers | P1 unless precompile reached |
| `WITNESS` | `isSRCandidate` precompile/path | P1 unsupported |
| `VOTES/DELEGATION/DELEGATED_RESOURCE` | vote/reward/freeze helpers | P1 unsupported |
| `ASSET_ISSUE` | TRC10 paths | P1 unsupported |

P0 should add startup validation:

```text
if storage.archive.ethCall.enable:
  require domains ACCOUNT, CONTRACT, CODE, CONTRACT_STORAGE, DYNAMIC_PROPERTIES enabled
  require dynamic property allowlist contains all VmDynamicProperties keys
```

## 12. Error mapping

| Exception | JSON-RPC mapping |
| --- | --- |
| `JsonRpcInvalidParamsException` from bad block/data/address | `-32602` |
| `JsonRpcInvalidRequestException` from unsupported call shape | `-32600` |
| `ContractValidateException` | same as latest `call()` invalid request behavior |
| `HistoricalCallUnsupportedException` | `-32000` with explicit unsupported message |
| `ArchiveReaderException` gap/corrupt | `-32000` |
| `Program.OutOfTimeException` | same as latest `call()` |
| revert with data | same `JsonRpcInternalException(message, data)` as latest |

Message examples:

```text
historical eth_call unsupported: archive disabled
historical eth_call unsupported: contract creation call
historical eth_call unsupported: transfer-only call
historical eth_call unsupported: missing archive domain WITNESS
historical eth_call unavailable: archive progress block=100 requested=120
historical eth_call unavailable: missing dynamic property MAX_FEE_LIMIT
```

## 13. Patch slices

### S12a：VmDynamicProperties and VMConfig scope

Files:

```text
common/src/main/java/org/tron/core/vm/config/VmDynamicProperties.java
common/src/main/java/org/tron/core/vm/config/VmDynamicPropertiesSnapshot.java
common/src/main/java/org/tron/core/vm/config/VmConfigSnapshot.java
common/src/main/java/org/tron/core/vm/config/VmConfigScope.java
chainbase/src/main/java/org/tron/core/store/DynamicPropertiesStore.java
actuator/src/main/java/org/tron/core/vm/config/ConfigLoader.java
common/src/main/java/org/tron/core/vm/config/VMConfig.java
```

Tests:

- `VmConfigSnapshotTest`: capture/restore all current flags.
- `ConfigLoaderVmDynamicPropertiesTest`: loading fake historical props sets expected VMConfig flags.
- concurrency test: nested/parallel historical scopes serialize and restore.

### S12b：Repository VM dynamic accessor

Files:

```text
actuator/src/main/java/org/tron/core/vm/repository/Repository.java
actuator/src/main/java/org/tron/core/vm/repository/RepositoryImpl.java
actuator/src/main/java/org/tron/core/vm/program/ContractState.java
actuator/src/main/java/org/tron/core/vm/ChainParameterEnum.java
actuator/src/main/java/org/tron/core/vm/PrecompiledContracts.java
actuator/src/main/java/org/tron/core/actuator/VMActuator.java
```

Tests:

- fake repository with fake `VmDynamicProperties` proves VM hot path uses fake values.
- `rg` guard test or unit assertion to prevent historical path direct latest dynamic reads.

### S12c：ArchiveRepositoryAdapter

Files:

```text
actuator/src/main/java/org/tron/core/vm/repository/ArchiveRepositoryAdapter.java
actuator/src/main/java/org/tron/core/vm/repository/ArchiveRepositoryChild.java
actuator/src/main/java/org/tron/core/vm/repository/ArchiveRepositoryOverlay.java
actuator/src/main/java/org/tron/core/vm/repository/UnsupportedArchiveRepositoryAccess.java
chainbase/src/main/java/org/tron/core/archive/vm/HistoricalCallUnsupportedException.java
```

Tests:

- `getAccount/getBalance/getContract/getCode/getStorageValue` read archive fake reader.
- child overlay sees parent archive state, child writes override reads.
- child commit merges to parent overlay; root commit does not write raw store.
- unsupported latest-only store methods throw explicit exception.

### S12d：HistoricalConstantCallExecutor

Files:

```text
actuator/src/main/java/org/tron/core/vm/archive/HistoricalConstantCallExecutor.java
actuator/src/main/java/org/tron/core/vm/archive/HistoricalConstantCallRequest.java
actuator/src/main/java/org/tron/core/vm/archive/HistoricalConstantCallResult.java
actuator/src/main/java/org/tron/core/vm/archive/HistoricalVmContext.java
actuator/src/main/java/org/tron/core/actuator/VMActuator.java
```

Tests:

- executor uses historical block env number/timestamp.
- executor rejects missing historical contract without latest Store call.
- executor returns same success bytes as latest for identical state.
- revert with data preserves latest encoding.

### S13a：eth_call block param resolver

Files:

```text
chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStatePointResolver.java
framework/src/main/java/org/tron/core/services/jsonrpc/HistoricalEthCallSupport.java
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java
```

Tests:

- string latest -> latest route.
- string quantity -> historical route.
- object blockNumber -> historical route; not rewritten latest.
- object blockHash -> canonical historical route.
- pending/safe keep unsupported.

### S13b：TronJsonRpcImpl historical branch

Files:

```text
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java
framework/src/test/java/org/tron/core/services/jsonrpc/TronJsonRpcImplEthCallArchiveTest.java
```

Tests:

- latest `eth_call` still invokes existing Wallet path.
- historical `eth_call` invokes `HistoricalConstantCallExecutor`.
- archive disabled historical call returns explicit error.
- historical branch does not call `CallArguments.getContractType(wallet)`.
- historical branch does not call `Wallet.triggerConstantContract`.

## 14. End-to-end test matrix

| Scenario | Expected |
| --- | --- |
| contract slot = A at block N, slot = B at block N+1 | `eth_call(readSlot,N)=A`, `eth_call(readSlot,N+1)=B` |
| same request with `"latest"` | existing latest path result |
| object `{blockNumber:"0xN"}` | same as `"0xN"`, no latest rewrite |
| object `{blockHash:"0x..."}` canonical | same as matching block number |
| object `{blockHash:"0x..."}` unknown | `NO_BLOCK_HEADER_BY_HASH` |
| contract created at N+1, call at N | smart contract not exist |
| contract deleted/tombstoned at N+1, call at N+2 | smart contract not exist |
| missing storage slot | 32-byte zero if contract exists and function returns it |
| revert with reason | same error data and decoded reason style as latest |
| unsupported precompile/domain reached | `historical eth_call unsupported: missing archive domain ...` |
| archive progress behind requested block | explicit unavailable error |

No test may use `t.Skip`/skip gates.

## 15. Implementation checklist

- [ ] `eth_call` object block params no longer rewrite to latest.
- [ ] latest `eth_call` path remains byte-for-byte compatible in observable JSON-RPC behavior.
- [ ] historical branch never calls `Wallet.triggerConstantContract`.
- [ ] historical branch never calls `CallArguments.getContractType(wallet)`.
- [ ] `ArchiveRepositoryAdapter` does not inherit `RepositoryImpl`.
- [ ] archive repository root commit does not write canonical Store or archive Store.
- [ ] child repository overlay supports nested VM calls.
- [ ] VM dynamic properties come from historical view.
- [ ] VMConfig static state is restored after historical call success/failure.
- [ ] missing archive domain fails explicitly.
- [ ] historical block env uses requested block, not latest head.
- [ ] historical code/storage/account reads use `ArchiveStatePoint.txNum`.
- [ ] tests include blockNumber object, blockHash object, and numeric string selectors.

## 16. Commands

Targeted tests after implementation:

```bash
./gradlew :common:test --tests '*VmConfig*Test'
./gradlew :actuator:test --tests '*ArchiveRepository*Test'
./gradlew :actuator:test --tests '*HistoricalConstantCallExecutorTest'
./gradlew :framework:test --tests '*EthCallArchive*Test'
```

Before PR:

```bash
./gradlew build
```

If touching checkstyle-sensitive files, also run:

```bash
./gradlew checkstyleMain checkstyleTest
```

## 17. Main risks

1. `DynamicPropertiesStore` concrete return type tempts latest fallback. Mitigation: `VmDynamicProperties` minimal interface and tests proving fake historical values are used.
2. VMConfig static flags are process-global. Mitigation: P0 lock/snapshot/restore; later per-execution rules.
3. Native/precompile paths read domains outside ACCOUNT/CONTRACT/CODE/STORAGE. Mitigation: explicit unsupported exceptions until domains are captured.
4. Nested calls need overlay semantics. Mitigation: implement child repository commit-to-parent before JSON-RPC wiring.
5. object block parameter rewrite is easy to miss. Mitigation: dedicated test where latest state differs from requested block.
