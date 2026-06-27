# java-tron Archive L2：Manager lifecycle + txNum 代码级执行包

日期：2026-06-05

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 当前基线：`4e80f8ffa9a2`

上游看板：[java-tron Archive：4e80 分阶段落地执行看板](./20260604-java-tron-archive-4e80-landing-readiness-board.md)

L1 执行包：[java-tron Archive L1：config/no-op/dbName 代码级执行包](./20260604-java-tron-archive-l1-config-noop-dbname-4e80-code-plan.md)

逐文件落点：[java-tron Archive：4e80 逐文件实现落点矩阵](./20260604-java-tron-archive-4e80-file-implementation-map.md)

本文只细化 L2：`Manager lifecycle + txNum`。它在 L1 的 archive config/no-op/dbName 基础上，为 canonical block apply、fork replay、recovery replay、eraseBlock unwind 和 `processBlock` 三段 phase 建立 archive execution context 与 logical txNum。L2 仍不采集 Store write-set，不写 temporal DB，不改 JSON-RPC，不计算 root。

## 1. L2 完成目标

L2 完成后，java-tron 应满足：

```text
ArchiveService can allocate txNum in canonical block execution
ArchiveExecutionContext exposes current ArchiveTxPosition during each phase
InMemoryArchiveTxNumIndex tracks pending/committed block ranges
normal pushBlock success commits txNum range only after canonical commit
normal pushBlock failure aborts pending range and clears context
fork replay and recovery replay use explicit archive source
eraseBlock unwinds archive only after canonical fastPop succeeds
disabled archive remains lightweight no-op
```

L2 的输出是“可定位每个 block/tx/system phase 的 logical txNum”。后续 L4 Store hook 只读取当前 context，不负责分配 txNum。

## 2. 当前源码事实

| 文件 | 当前事实 | L2 含义 |
| --- | --- | --- |
| `framework/src/main/java/org/tron/core/db/Manager.java:1266-1272` | `pushBlock(final BlockCapsule block)` 是 normal canonical 入口 | 注入并调用 `ArchiveService` |
| `Manager.java:1378-1389` | normal path 在 `applyBlock(newBlock, txs)` 后 `tmpSession.commit()`，再 `blockTrigger` | archive commit 必须在 canonical commit 后、`blockTrigger` 前 |
| `Manager.java:1382-1386` | apply/commit 失败会移除 khaos block、清 trigger cache、rethrow | catch 中必须 `archiveService.abortBlock(newBlock)` |
| `Manager.java:1034-1042` | `eraseBlock()` 中 `khaosDb.pop()` 后 `revokingStore.fastPop()` | `unwindBlock(oldHeadBlock)` 必须在 `fastPop()` 成功后 |
| `Manager.java:1142-1149` | fork replay 新分支有独立 `applyBlock(...setSwitch(true))` + commit | source 标记为 `REPLAY` |
| `Manager.java:1185-1187` | fork 失败后 recovery replay 原分支 | source 标记为 `RECOVERY` |
| `Manager.java:1838-1867` | `processBlock` 开头有 balance trace、energy reset、HistoryBlockHash | `BLOCK_PREPARE` phase |
| `Manager.java:1873-1891` | 逐笔遍历 `block.getTransactions()` | `USER_TX` phase，txIndex 来自原始 block tx 顺序 |
| `Manager.java:1897-1927` | merkle tree、result、energy、reward、proposal、consensus、cache、dynamic properties | `BLOCK_FINALIZE` phase |
| `BaseMethodTest.java:18-85` | 每个 test method 独立 Spring context/temp output | Manager archive lifecycle tests 首选它 |
| `ManagerTest.java:115-169` | Manager 测试已有 `BaseMethodTest`、consensus start、witness 初始化示例 | 可复用 Manager lifecycle fixture |

### 2.1 产块 per-tx session 的借鉴边界

`Manager.generateBlock()` 在产块时先建立一个 block 级临时 session，然后每挑一笔 pending/repush transaction 都再开一个 nested `ISession`：

```text
generateBlock
  -> session.setValue(revokingStore.buildSession())       // block candidate scope
  -> for each candidate tx:
       try (ISession tmpSession = revokingStore.buildSession()) {
         accountStateCallBack.preExeTrans()
         processTransaction(trx, blockCapsule)
         accountStateCallBack.exeTransFinish()
         tmpSession.merge()
       } catch (...) {
         skip candidate tx
       }
  -> session.reset()
```

这个模式可以借鉴为 archive 的 **tx boundary/scoped checkpoint**，但不能照搬为 canonical block apply 的失败语义。产块路径只是试执行和挑交易；`BlockHandleImpl.produce()` 后续仍会调用 `manager.pushBlock(blockCapsule)`，真正上链还是走 `pushBlock -> applyBlock -> processBlock`。

推荐 L2/L4 后续实现采用：

```text
pushBlock outer block session unchanged
  -> processBlock
       -> BLOCK_PREPARE context
       -> for each canonical tx:
            archive.beginTx(txIndex, txId)
            optional nested ISession / archive collector checkpoint
            processTransaction(...)
            archive.endTx()
            nestedSession.merge()
       -> BLOCK_FINALIZE context
  -> outer tmpSession.commit()
  -> archive commitBlock after canonical commit
```

规则：

- 不能把接收区块中的失败交易“跳过”；任何 canonical tx 失败仍然使整个 block invalid，并回滚外层 block session。
- nested tx session 只用于隔离 tx write-set、retry checkpoint 和 archive diagnostics，不改变最终 canonical state。
- archive sidecar 仍只能在外层 canonical commit 成功后 flush。

## 3. Patch 边界

### 3.1 前置条件

L2 只能在 L1 已经 `DONE` 后开始。也就是说源码中应已有：

```text
storage.archive.* config
ArchiveService interface
NoopArchiveService
ArchiveServiceFactory
ArchiveException
TronStoreWithRevoking.getDbName() fix
```

### 3.2 允许修改

```text
framework/src/main/java/org/tron/core/db/Manager.java
chainbase/src/main/java/org/tron/core/archive/ArchiveService.java
chainbase/src/main/java/org/tron/core/archive/ArchiveServiceFactory.java
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
chainbase/src/main/java/org/tron/core/archive/ArchiveExecutionContext.java
chainbase/src/main/java/org/tron/core/archive/ArchiveExecutionContextHolder.java
chainbase/src/main/java/org/tron/core/archive/ArchiveSource.java
chainbase/src/main/java/org/tron/core/archive/ArchivePhase.java
chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveTxNumIndex.java
chainbase/src/main/java/org/tron/core/archive/txnum/InMemoryArchiveTxNumIndex.java
chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveTxPosition.java
chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveBlockRange.java
chainbase/src/test/java/org/tron/core/archive/txnum/ArchiveTxNumIndexTest.java
chainbase/src/test/java/org/tron/core/archive/ArchiveServiceLifecycleTest.java
framework/src/test/java/org/tron/core/db/ManagerArchiveLifecycleTest.java
framework/src/test/java/org/tron/core/db/ManagerArchiveContextCleanupTest.java
```

### 3.3 禁止混入

```text
TronStoreWithRevoking put/delete hook
chainbase/src/main/java/org/tron/core/archive/write/...
chainbase/src/main/java/org/tron/core/archive/temporal/...
actuator/src/main/java/org/tron/core/vm/program/Storage.java
framework/src/main/java/org/tron/core/services/jsonrpc/...
commitment/root/proof/debug classes
```

如果 L2 patch 修改 Store hook、TVM storage、JSON-RPC 或 temporal persistence，说明 diff 已越界。

## 4. L2 Core Types

### 4.1 `ArchiveSource`

包：

```text
chainbase/src/main/java/org/tron/core/archive/ArchiveSource.java
```

建议：

```java
public enum ArchiveSource {
  NORMAL,
  REPLAY,
  RECOVERY,
  UNWIND
}
```

含义：

| Source | 使用位置 |
| --- | --- |
| `NORMAL` | `pushBlock` normal canonical path |
| `REPLAY` | fork switch 到新分支 |
| `RECOVERY` | fork switch 失败后恢复旧分支 |
| `UNWIND` | `eraseBlock` |

### 4.2 `ArchivePhase`

L1 已可先定义 enum；L2 固定语义：

```java
public enum ArchivePhase {
  BLOCK_PREPARE,
  USER_TX,
  BLOCK_FINALIZE,
  UNWIND
}
```

`UNWIND` 不代表 block apply 中的 tx，而是为 unwind changeset/progress 预留统一 context。

### 4.3 `ArchiveTxPosition`

包：

```text
chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveTxPosition.java
```

建议字段：

```java
public final class ArchiveTxPosition {
  private final long txNum;
  private final long blockNum;
  private final ArchivePhase phase;
  private final ArchiveSource source;
  private final int txIndex;
  private final byte[] txId;
}
```

约定：

| 字段 | 规则 |
| --- | --- |
| `txNum` | 全局单调 logical tx number |
| `blockNum` | canonical block number |
| `phase` | prepare/user/finalize/unwind |
| `source` | normal/replay/recovery/unwind |
| `txIndex` | user tx 为原始 block tx index；system phase 用 `-1` |
| `txId` | user tx 保存 hash bytes；system phase 用空数组 |

实现注意：

- 构造函数 defensive copy `txId`。
- getter 返回 defensive copy。
- 不依赖 Lombok 也可以；如果用 Lombok，仍要处理 byte array copy。

### 4.4 `ArchiveBlockRange`

包：

```text
chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveBlockRange.java
```

建议字段：

```java
public final class ArchiveBlockRange {
  private final long blockNum;
  private final long firstTxNum;
  private final long lastTxNum;
  private final long prepareTxNum;
  private final long finalizeTxNum;
  private final int userTxCount;
  private final ArchiveSource source;
}
```

约定：

- empty block 也必须有 prepare/finalize，所以 `firstTxNum <= lastTxNum`。
- `prepareTxNum` 是 block 内第一个 txNum。
- `finalizeTxNum` 是 block 内最后一个 txNum。
- `lastTxNum` 等于 `finalizeTxNum`，保留 `lastTxNum` 方便 range scan。

### 4.5 `ArchiveTxNumIndex`

包：

```text
chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveTxNumIndex.java
```

建议接口：

```java
public interface ArchiveTxNumIndex {

  void beginBlock(long blockNum, ArchiveSource source);

  ArchiveTxPosition allocateSystemTx(long blockNum, ArchivePhase phase);

  ArchiveTxPosition allocateUserTx(long blockNum, int txIndex, byte[] txId);

  ArchiveBlockRange commitBlock(long blockNum, int userTxCount);

  void abortBlock(long blockNum);

  void unwindBlock(long blockNum);

  Optional<ArchiveBlockRange> getBlockRange(long blockNum);

  Optional<ArchiveTxPosition> getPosition(long txNum);

  OptionalLong findTxNumByBlockAndIndex(long blockNum, int txIndex);

  OptionalLong findTxNumByTxId(byte[] txId);
}
```

L2 只需要 in-memory 实现；L5 会替换为 persistent txNum index。

### 4.6 `InMemoryArchiveTxNumIndex`

包：

```text
chainbase/src/main/java/org/tron/core/archive/txnum/InMemoryArchiveTxNumIndex.java
```

必须维护：

```text
committedNextTxNum
workingNextTxNum
pendingBlockNum
pendingSource
pendingPositions
blockRanges
positionsByTxNum
txNumByBlockAndIndex
txNumByTxId
```

状态机：

| 方法 | 行为 |
| --- | --- |
| `beginBlock` | 无 pending；`workingNextTxNum = committedNextTxNum`；清 pending；记录 block/source |
| `allocateSystemTx` | block 匹配 pending；phase 只能 prepare/finalize；分配 txNum；txIndex=-1 |
| `allocateUserTx` | block 匹配 pending；txIndex >= 0；分配 txNum；保存 txId lookup |
| `commitBlock` | pending block 匹配；生成 `ArchiveBlockRange`；持久到 in-memory maps；`committedNextTxNum = workingNextTxNum`；清 pending |
| `abortBlock` | pending block 匹配或无 pending；丢弃 pending；`workingNextTxNum = committedNextTxNum` |
| `unwindBlock` | block 必须已 committed；删除 range 和 positions；`committedNextTxNum = removed.firstTxNum` |

边界：

- 同一时刻只允许一个 pending block。
- duplicate `beginBlock` 必须抛 `ArchiveException`。
- commit 时必须已有 prepare 和 finalize；否则抛异常。
- user tx count 必须与实际 allocated user positions 数量一致。

## 5. Execution Context

### 5.1 `ArchiveExecutionContext`

包：

```text
chainbase/src/main/java/org/tron/core/archive/ArchiveExecutionContext.java
```

建议：

```java
public final class ArchiveExecutionContext {
  private final ThreadLocal<ArchiveTxPosition> current = new ThreadLocal<>();

  public void enter(ArchiveTxPosition position) {
    current.set(position);
  }

  public Optional<ArchiveTxPosition> current() {
    return Optional.ofNullable(current.get());
  }

  public void clear() {
    current.remove();
  }
}
```

L2 只设置/清理 context。L4 Store hook 读取 `current()`。

### 5.2 `ArchiveExecutionContextHolder`

如果后续 Store hook 需要从不同 bean 读取统一 context，建议加 holder：

```java
public final class ArchiveExecutionContextHolder {
  private static final ArchiveExecutionContext CONTEXT = new ArchiveExecutionContext();

  private ArchiveExecutionContextHolder() {
  }

  public static ArchiveExecutionContext get() {
    return CONTEXT;
  }
}
```

L2 需要测试异常后 `current().isPresent() == false`。

## 6. DefaultArchiveService L2 行为

L1 的 `ArchiveServiceFactory` 在 `enable=true` 时拒绝真实 archive。L2 开始可以允许构造 `DefaultArchiveService`，但仍不写 DB。

建议类：

```text
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
```

核心字段：

```java
private final ArchiveTxNumIndex txNumIndex;
private final ArchiveExecutionContext executionContext;
private boolean enabled;
```

构造：

```java
public DefaultArchiveService(boolean enabled) {
  this(enabled, new InMemoryArchiveTxNumIndex(), ArchiveExecutionContextHolder.get());
}

DefaultArchiveService(boolean enabled, ArchiveTxNumIndex txNumIndex,
    ArchiveExecutionContext executionContext) {
  this.enabled = enabled;
  this.txNumIndex = txNumIndex;
  this.executionContext = executionContext;
}
```

方法语义：

| 方法 | enabled=false | enabled=true |
| --- | --- | --- |
| `beginBlock(block, source)` | no-op | `txNumIndex.beginBlock(block.getNum(), source)` |
| `beginSystemTx(block, phase)` | no-op | allocate position, enter context |
| `beginUserTx(block, txIndex, tx)` | no-op | allocate position, enter context |
| `endTx()` | clear context | clear context |
| `commitBlock(block)` | no-op | commit pending block |
| `abortBlock(block)` | clear context | clear context + abort pending |
| `unwindBlock(block)` | no-op | `txNumIndex.unwindBlock(block.getNum())` |

建议 `ArchiveService` 接口从 L1 的 `beginBlock(BlockCapsule block)` 扩展为：

```java
void beginBlock(BlockCapsule block, ArchiveSource source);
```

这样 Manager 不需要通过隐含上下文猜 source。

## 7. Manager Hook 设计

### 7.1 注入

`Manager.java` imports 增加：

```java
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveService;
import org.tron.core.archive.ArchiveSource;
```

字段区增加：

```java
@Autowired
private ArchiveService archiveService;
```

如果 Spring 多实现冲突，L2 应保持只有一个 concrete bean；`NoopArchiveService` 不做 `@Component`，由 factory/Default service 控制 disabled。

### 7.2 normal pushBlock

当前锚点：`Manager.java:1378-1389`。

目标形状：

```java
long oldSolidNum = getDynamicPropertiesStore().getLatestSolidifiedBlockNum();
archiveService.beginBlock(newBlock, ArchiveSource.NORMAL);
try (ISession tmpSession = revokingStore.buildSession()) {
  applyBlock(newBlock, txs);
  tmpSession.commit();
} catch (Throwable throwable) {
  archiveService.abortBlock(newBlock);
  logger.error(throwable.getMessage(), throwable);
  khaosDb.removeBlk(block.getBlockId());
  clearSolidityContractTriggerCache(block.getNum());
  throw throwable;
}
archiveService.commitBlock(newBlock);
long newSolidNum = getDynamicPropertiesStore().getLatestSolidifiedBlockNum();
blockTrigger(newBlock, oldSolidNum, newSolidNum);
```

规则：

- `beginBlock` 放在 revoking session 前。
- `commitBlock` 放在 `tmpSession.commit()` 后。
- `commitBlock` 放在 `blockTrigger` 前，确保 event/trigger 读取时 archive progress 已跟上 canonical block。
- catch 中必须 abort，不能只依赖 `finally endTx`。

### 7.3 fork replay

当前锚点：`Manager.java:1142-1149`。

不要多次调用 `item.getBlk().setSwitch(true)` 产生难以跟踪的实例。建议：

```java
BlockCapsule replayBlock = item.getBlk().setSwitch(true);
archiveService.beginBlock(replayBlock, ArchiveSource.REPLAY);
try (ISession tmpSession = revokingStore.buildSession()) {
  if (!replayBlock.validateSignature(getDynamicPropertiesStore(), getAccountStore())) {
    throw new ValidateSignatureException(...);
  }
  applyBlock(replayBlock);
  tmpSession.commit();
} catch (...) {
  archiveService.abortBlock(replayBlock);
  ...
}
archiveService.commitBlock(replayBlock);
```

规则：

- `archiveService.abortBlock` 只在 replay block 已 begin 后调用。
- fork failure cleanup 原有逻辑不能被 archive catch 吞掉。
- replay source 必须保存在 txNum positions/range。

### 7.4 recovery replay

当前锚点：`Manager.java:1185-1187`。

目标形状同 replay，但 source 为 `ArchiveSource.RECOVERY`：

```java
BlockCapsule recoveryBlock = khaosBlock.getBlk().setSwitch(true);
archiveService.beginBlock(recoveryBlock, ArchiveSource.RECOVERY);
try (ISession tmpSession = revokingStore.buildSession()) {
  applyBlock(recoveryBlock);
  tmpSession.commit();
} catch (...) {
  archiveService.abortBlock(recoveryBlock);
  ...
}
archiveService.commitBlock(recoveryBlock);
```

### 7.5 eraseBlock unwind

当前锚点：`Manager.java:1034-1042`。

目标形状：

```java
BlockCapsule oldHeadBlock = chainBaseManager.getBlockById(
    getDynamicPropertiesStore().getLatestBlockHeaderHash());
logger.info("Start to erase block: {}.", oldHeadBlock);
khaosDb.pop();
revokingStore.fastPop();
archiveService.unwindBlock(oldHeadBlock);
logger.info("End to erase block: {}.", oldHeadBlock);
```

规则：

- 不能放在 `fastPop()` 前。
- 如果 `getBlockById` 或 `fastPop()` 抛异常，不 unwind archive。
- L2 unwind 只操作 in-memory txNum；L5 才 unwind latest/history/changeset/progress。

## 8. `processBlock` Phase Hook

### 8.1 `BLOCK_PREPARE`

当前锚点：`Manager.java:1851-1867`。

把 block-level pre tx writes 包进 system tx：

```java
archiveService.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
try {
  chainBaseManager.getBalanceTraceStore().initCurrentBlockBalanceTrace(block);
  chainBaseManager.getDynamicPropertiesStore().saveBlockEnergyUsage(0);
  ...
  HistoryBlockHashUtil.write(this, block);
} finally {
  archiveService.endTx();
}
```

如果 `preValidateTransactionSign(txs)` 中断，不应泄漏 context。

### 8.2 `USER_TX`

当前锚点：`Manager.java:1873-1891`。

必须从原始 block tx loop 分配 txIndex：

```java
int txIndex = 0;
for (TransactionCapsule transactionCapsule : block.getTransactions()) {
  archiveService.beginUserTx(block, txIndex, transactionCapsule);
  try {
    rejectExchangeTransaction(transactionCapsule.getInstance());
    ...
    TransactionInfo result = processTransaction(transactionCapsule, block);
    ...
  } finally {
    archiveService.endTx();
    txIndex++;
  }
}
```

禁止：

- 使用 filtered `txs` 的 index。
- 使用 `TransactionCapsule.order`。
- 在 pending transaction/broadcast/constant call 分配 txNum。

### 8.3 `BLOCK_FINALIZE`

当前锚点：`Manager.java:1897-1927`。

建议从 `merkleContainer.saveCurrentMerkleTreeAsBestMerkleTree` 包到 `resetCurrentBlockTrace`：

```java
archiveService.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
try {
  merkleContainer.saveCurrentMerkleTreeAsBestMerkleTree(block.getNum());
  block.setResult(transactionRetCapsule);
  ...
  updateDynamicProperties(block);
  chainBaseManager.getBalanceTraceStore().resetCurrentBlockTrace();
} finally {
  archiveService.endTx();
}
```

`sectionBloomStore.initBlockSection(transactionRetCapsule)` 是否纳入 finalize context 可在 L3/L4 按 domain policy 决定；L2 可先把它也放在 finalize context 内，保证系统写有 txNum。

## 9. L2 测试计划

### 9.1 `ArchiveTxNumIndexTest`

文件：

```text
chainbase/src/test/java/org/tron/core/archive/txnum/ArchiveTxNumIndexTest.java
```

纯单元，不需要 Spring。

| 测试方法 | 断言 |
| --- | --- |
| `commitMultiTxBlockAssignsPrepareUsersFinalize` | 2 user tx block 分配 4 个 txNum：prepare、user0、user1、finalize |
| `emptyBlockStillAssignsPrepareAndFinalize` | empty block 仍有 prepare/finalize |
| `abortDoesNotAdvanceNextTxNum` | abort 后下一 block 从原 committedNextTxNum 开始 |
| `unwindRemovesBlockAndResetsNextTxNum` | unwind 删除 block range 和 positions，nextTxNum 回到 range first |
| `rejectsNestedPendingBlock` | begin 未 commit/abort 前再次 begin 抛异常 |
| `rejectsCommitWithoutFinalize` | 没 finalize 不允许 commit |
| `findTxNumByTxIdUsesDefensiveCopy` | 修改传入 txId byte array 不影响 lookup |
| `recordsArchiveSource` | NORMAL/REPLAY/RECOVERY 被写入 range/position |

### 9.2 `ArchiveServiceLifecycleTest`

文件：

```text
chainbase/src/test/java/org/tron/core/archive/ArchiveServiceLifecycleTest.java
```

纯单元，使用 fake `BlockCapsule` 成本高时可以先让 `DefaultArchiveService` 暴露 package-private long blockNum helper；但最终接口测试要覆盖 `BlockCapsule`。

| 测试方法 | 断言 |
| --- | --- |
| `disabledServiceIsNoop` | disabled 下 begin/commit/abort/unwind 不产生 range |
| `beginUserTxEntersContext` | enabled 下 beginUserTx 后 `ArchiveExecutionContext.current()` 存在 |
| `endTxClearsContext` | endTx 后 context empty |
| `abortClearsContextAndPendingRange` | abort 清 context 且 pending 不可查 |
| `commitCreatesBlockRange` | commit 后 block range 可查 |

### 9.3 `ManagerArchiveLifecycleTest`

文件：

```text
framework/src/test/java/org/tron/core/db/ManagerArchiveLifecycleTest.java
```

继承 `BaseMethodTest`，因为每个 test 都会修改 DB/state：

```java
public class ManagerArchiveLifecycleTest extends BaseMethodTest {
  @Override
  protected String[] extraArgs() {
    return new String[]{"--p2p-disable", "true"};
  }
}
```

如果需要启用 archive config，优先用 test config file，而不是 CLI 新增参数。

建议测试：

| 测试方法 | Arrange | Assert |
| --- | --- | --- |
| `pushEmptyBlockCommitsPrepareAndFinalizeRange` | 复用 `ManagerTest`/`BlockGenerate` 构造一个合法空 block | archive range 有 2 个 txNum |
| `pushBlockWithUserTransactionsUsesOriginalBlockOrder` | 构造 block 原始 tx > filtered tx | txIndex 仍按 `block.getTransactions()` |
| `pushBlockFailureAbortsPendingRange` | 制造 validate/apply failure | pending range 不存在，context empty |
| `eraseBlockUnwindsAfterFastPop` | push 后 erase | range 被移除 |
| `disabledArchiveDoesNotChangePushBlock` | archive disabled | pushBlock 行为与当前测试一致 |

如果真实 block 构造成本高，先用 service-level tests 合入 L2 core；但 L2 不能最终完成，直到至少一个 Manager integration test 通过。

### 9.4 `ManagerArchiveContextCleanupTest`

文件：

```text
framework/src/test/java/org/tron/core/db/ManagerArchiveContextCleanupTest.java
```

重点：

- `processTransaction` 抛异常后 context cleared。
- `preValidateTransactionSign` 中断/异常后 context cleared。
- `BLOCK_FINALIZE` 抛 `BadBlockException` 后 context cleared。

这些测试可以用 fake/injected archive service 记录 calls。如果当前 Manager 不方便注入 fake service，L2 可以先给 `ArchiveService` bean 加 testing accessor，或通过 Spring test context 替换 bean；不要用静态全局 mock 污染其他测试。

## 10. 验收命令

单 slice gate：

```bash
./gradlew :chainbase:test --tests '*ArchiveTxNumIndexTest'
./gradlew :chainbase:test --tests '*ArchiveServiceLifecycleTest'
./gradlew :framework:test --tests '*ManagerArchiveLifecycleTest'
./gradlew :framework:test --tests '*ManagerArchiveContextCleanupTest'
./gradlew checkstyleMain checkstyleTest
```

回归：

```bash
./gradlew :framework:test --tests '*ManagerTest'
```

合入前：

```bash
./gradlew build
```

失败测试必须修实现，不能加 skip、`@Ignore`、条件性 bypass 或从 test pattern 中移除。

## 11. L2 Review Checklist

- [ ] L2 没有修改 Store put/delete hook。
- [ ] L2 没有写 archive DB。
- [ ] L2 没有改 JSON-RPC 行为。
- [ ] `txIndex` 来自 `block.getTransactions()` 原始顺序。
- [ ] prepare、user、finalize 都能分配 txNum。
- [ ] empty block 也有 prepare/finalize。
- [ ] normal success 只在 canonical commit 后 commit archive range。
- [ ] normal failure 会 abort pending range 并清 context。
- [ ] replay/recovery source 有显式记录。
- [ ] unwind 在 `revokingStore.fastPop()` 后执行。
- [ ] disabled archive no-op 不改变 Manager 当前行为。

## 12. 停止条件

出现以下任一问题，不能进入 L3：

| 问题 | 原因 |
| --- | --- |
| failure 后 `committedNextTxNum` 前进 | 后续 history/root 会出现不可恢复空洞 |
| context 在异常后未清理 | 后续 Store hook 会把非本 tx 写入错误 txNum |
| txIndex 使用 filtered `txs` | JSON-RPC 和 trace 对原始 block tx index 的定位会错 |
| replay/recovery 与 normal 混淆 | reorg/recovery 审计无法解释 archive 写入来源 |
| unwind 在 fastPop 前执行 | canonical unwind 失败时 archive 会先回退，导致分叉 |

## 13. 和 L1/S1-S2 文档的关系

L1 执行包收窄了第一批 patch：只做 config/no-op/dbName。

本文是第二批 patch：

```text
L2 = Manager lifecycle + in-memory txNum + execution context
```

实际编码顺序：

1. L1 gate 全部通过。
2. 按本文实现 L2 core 和 service-level tests。
3. 补 Manager integration tests。
4. L2 gate 通过后，才进入 L3 `ArchiveDomainRegistry`。
