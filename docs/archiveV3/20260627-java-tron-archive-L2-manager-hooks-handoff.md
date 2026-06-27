# java-tron Archive L2 — Manager 共识 hooks 交接执行包

日期：2026-06-27
分支：`claude/vibrant-borg-d3617d`（worktree）
前置：L2 chainbase 侧已完成 + 已 commit（见下）。本包只剩 **Manager.java 共识接线 + enable 档集成测试**。

> ⚠️ **这是共识关键路径（block apply）。所有 hook 在 archive 关闭时是 no-op（默认节点零影响），但 Manager 回归测试跑的就是关闭路径——无法验证 hook 正确性。必须配 enable 档 `ManagerArchiveLifecycleTest` 才能 ship。** 本包是一个独立、需谨慎 + 集成测试的 pass，不要半做（all-or-nothing，见 §0）。

## 0. 为什么 all-or-nothing

`processBlock` 的 phase hooks（PREPARE/USER_TX/FINALIZE 分配 txNum）对**所有** applyBlock 调用路径触发。若只做部分（如 pushBlock begin/commit 但漏 processBlock 分配），enable 时 `commitBlock` 会因"无 prepare/finalize"抛 `ArchiveException`；漏 fork/recovery 的 begin 则 fork reorg 崩。因 `ArchiveServiceFactory` 现在 enable 时返回真 `DefaultArchiveService`，半成品 = "enable 即崩"隐患。**要么全做 + 验证，要么不做（当前状态：enable 返回未被调用的 service，安全）。**

## 1. 已完成（chainbase 侧，已 commit）

| commit | 内容 |
|---|---|
| `fc9f525e` | `txnum/`：ArchiveSource·ArchiveTxPosition·ArchiveBlockRange·ArchiveTxNumIndex·**InMemoryArchiveTxNumIndex**（状态机）·ArchiveExecutionContext·Holder + `ArchiveTxNumIndexTest` |
| `1f4ed409` | `DefaultArchiveService`（enable 分配 txNum+进 context，disable no-op）+ `ArchiveServiceFactory` 放宽（enable→DefaultArchiveService）+ 接口扩 `beginBlock(block, source)` + `DefaultArchiveServiceTest` |

`DefaultArchiveService` 的方法语义已实现并测试（见该类 + `DefaultArchiveServiceTest`）。Manager 只需在正确位置调用它。

## 2. Bean 接线

`framework/.../config/DefaultConfig.java`：加 import `org.tron.core.archive.ArchiveService` / `ArchiveServiceFactory`，并加：

```java
@Bean
public ArchiveService archiveService() {
  return ArchiveServiceFactory.create(Args.getInstance().getStorage().getArchive());
}
```

`Manager.java`：import `org.tron.core.archive.{ArchivePhase, ArchiveService, ArchiveSource}`（插在 `org.tron.core.actuator.*` 与 `org.tron.core.capsule.*` 之间）；字段区（在 `RevokingDatabase revokingStore` 后）加：

```java
@Autowired
private ArchiveService archiveService;
```

## 3. 8 个 hook 点（决策 6 顺序；行号为 HEAD 近似，编码前按 anchor re-grep）

| 路径 | anchor | 放置 |
|---|---|---|
| **pushBlock normal** | `applyBlock(newBlock, txs); tmpSession.commit();` + `blockTrigger(newBlock,...)`（~1388） | `beginBlock(newBlock, NORMAL)` 在 `try(ISession)` 前；catch 首行 `abortBlock(newBlock)`；`commitBlock(newBlock)` 在 try-catch 后、`blockTrigger` 前 |
| **eraseBlock** | `revokingStore.fastPop();`（~1046） | `unwindBlock(oldHeadBlock)` 紧跟 `fastPop()` 后（fastPop 抛则不 unwind） |
| **fork-replay** | `applyBlock(item.getBlk().setSwitch(true));`（~1156，在循环+大 catch+finally 内，catch **rethrow**） | `beginBlock(item.getBlk(), REPLAY)` 在 try 前；catch 首行 `abortBlock(item.getBlk())`；`commitBlock(item.getBlk())` 放 **try 内 `tmpSession.commit()` 之后**（见 §4 陷阱 b） |
| **recovery** | `applyBlock(khaosBlock.getBlk().setSwitch(true));`（~1194，循环内，catch **吞异常 log 后继续**） | `beginBlock(khaosBlock.getBlk(), RECOVERY)` 在 try 前；catch 首行 `abortBlock(khaosBlock.getBlk())`；`commitBlock(khaosBlock.getBlk())` 放 **try 内 commit 之后**（不能放 try-catch 后，否则失败也 commit，见 §4 陷阱 a） |
| **processBlock PREPARE** | `processBlock`（~1853），prep 段：`initCurrentBlockBalanceTrace`→`saveBlockEnergyUsage(0)`→parallel sign→`HistoryBlockHashUtil.write`（在 `try{` 1883 之前） | `beginSystemTx(block, BLOCK_PREPARE)` 包这段，`finally{ endTx(); }`（注意 `preValidateTransactionSign` 中断不能泄漏 context） |
| **processBlock USER_TX** | `for (TransactionCapsule transactionCapsule : block.getTransactions())`（~1888） | 加 `int txIndex=0`；每笔 `beginUserTx(block, txIndex, transactionCapsule)` 在 body 前，`finally{ endTx(); txIndex++; }`。**禁止**用 filtered `txs` 的 index / `TransactionCapsule.order` / 给 pending/broadcast/constant call 分配 |
| **processBlock FINALIZE** | finalize 段：`saveCurrentMerkleTreeAsBestMerkleTree`→`setResult`→`updateDynamicProperties`→`resetCurrentBlockTrace`（tx loop 后，try 内） | `beginSystemTx(block, BLOCK_FINALIZE)` 包这段，`finally{ endTx(); }`（`sectionBloomStore.initBlockSection` L2 可一并纳入以保系统写有 txNum） |

注：`commitBlock(block)` 用 `block.getTransactions().size()` 作 userTxCount，必须 == processBlock 分配的 user 数。fork/recovery 用 `item.getBlk()`/`khaosBlock.getBlk()`（与 applyBlock 同一对象，`setSwitch` 只置 flag 返回 this，getNum/getTransactions 不变）。

## 4. 结构陷阱（我这轮读源码发现的，务必注意）

- **(a) recovery catch 吞异常**：当前 `for(khaosBlock:second)` 的 catch 只 `logger.warn` 不 rethrow、继续循环。所以 recovery 的 `commitBlock` **必须放 try 内 `tmpSession.commit()` 之后**——若放 try-catch 之后，失败块也会被 commit。
- **(b) fork-replay 的 commit 位置**：fork-replay catch rethrow + finally 做 fork-back（finally 内还嵌着 recovery 循环）。把 `commitBlock` 放 **try 内 canonical commit 之后**最简且正确（避免 finally/loop 结构里找"成功点"）。
- **(c) processBlock PREPARE 跨 try 边界**：prep 写一部分在 `try{`(1883) 之前（balance/energy/sign/historyhash）、一部分在 try 内（`resetCurrentMerkleTree`/`preExecute`）。L2 只需把**前半段**包进 PREPARE system tx（plan §8.1 口径）即可。
- **(d) commit-failure 语义**：L2 `commitBlock` 是纯内存、实际不会抛，所以 try-内/外放置差异对 L2 无影响；但 **L5（持久化）要 fail-fast**——届时 normal path 的 `commitBlock` 放 try-catch 之后让异常 propagate 是有意的（决策 6）。L2 实现时留注释提醒 L5 复审。

## 5. enable 档集成测试（ship 的前提）

`framework/src/test/.../ManagerArchiveLifecycleTest.java`（参考 `BaseMethodTest` / `ManagerTest` fixture，`storage.archive.enable=true`）：
- normal pushBlock 成功 → `archiveService` 的 txNumIndex 有该块 range（prepare+users+finalize），**commit 在 canonical commit 之后**。
- pushBlock 失败 → range 不产生（abort）。
- eraseBlock → 该块 range 被 unwind。
- context：每 phase/tx 后 `ArchiveExecutionContext.current()` 清空；异常后也清空。

也加 `ArchiveServiceLifecycleTest`（chainbase，纯单元，已部分被 `DefaultArchiveServiceTest` 覆盖）。

## 6. 验证命令

```bash
# worktree 跑 framework 测试需跳过 jgit 的 git-properties（worktree 不支持）
./gradlew :framework:test --tests 'org.tron.core.db.TronDatabaseTest' \
  --tests 'org.tron.core.db.ManagerArchiveLifecycleTest' -x generateGitProperties
./gradlew :chainbase:test --tests 'org.tron.core.archive.*'
./gradlew lint   # framework checkstyle（chainbase/common 不走 checkstyle）
```

回归：`TronDatabaseTest` 已确认 `getDbName()` 改动无回归（它 extends TronDatabase，非 TronStoreWithRevoking）。

## 7. 权威依据
- 完整 spec：`20260605-java-tron-archive-l2-manager-txnum-4e80-code-plan.md` §6-9
- 决策 6（sidecar / capture@exec / commit@solidified-边界 / 顺序）+ 决策 5 细化（L2-L4 留 chainbase Java 8，arm64 边界落 L5）：`00-ARCHIVE-V3-AUTHORITY-AND-DECISIONS.md`
