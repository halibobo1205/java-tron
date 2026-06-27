# java-tron Archive S1/S2 编码执行包

日期：2026-06-02

> 2026-06-03 更新：本文是旧 `a79693e450` 执行包。当前 `4e80f8ffa9a2` 的 S1/S2 编码入口请以 [java-tron Archive S1/S2：4e80 编码执行包](./20260603-java-tron-archive-s1-s2-4e80-coding-packet.md) 为准。当前源码已有 `common/src/main/resources/reference.conf` 和 `StorageConfig.java`，旧行号和旧配置链路不可直接用于编码。

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

旧执行包原复核基线：本地 java-tron `a79693e450`。

端到端矩阵：[java-tron Archive 端到端实现矩阵与 PR 执行队列](./20260602-java-tron-archive-end-to-end-implementation-matrix.md)

能力规格：[java-tron Archive PR1/PR2 代码级实现规格](./20260602-java-tron-archive-pr1-pr2-implementation-spec.md)

逐文件清单：[java-tron Archive PR1/PR2 逐文件 Patch 清单](./20260602-java-tron-archive-pr1-pr2-patch-checklist.md)

模块 01 逐文件 Patch 清单：[java-tron Archive 模块 01：ArchiveTxNumIndex 逐文件 Patch 清单](./20260602-java-tron-archive-module-01-txnum-index-patch-checklist.md)

## 1. 本文定位

本文是编码前的 S1/S2 执行包。它不再讨论全局架构，而是回答：

```text
从当前 /Users/boson/IdeaProjects/java-tron 源码出发，
第一批真实 patch 应该改哪些文件、按什么顺序改、怎么验证。
```

S1/S2 对应端到端矩阵中的两个 landing slices：

| Slice | 能力阶段 | 目标 |
| --- | --- | --- |
| S1 | PR1 | archive config + no-op service skeleton |
| S2 | PR2 | Manager lifecycle + txNum in-memory index |

S1/S2 合并后仍然不采集 Store write-set、不写 temporal history、不读 historical RPC、不计算 root。它只建立后续模块所需的交易级时间坐标和 archive 生命周期。

## 2. 当前源码复核结果

本轮重新对照了本地 java-tron 源码，以下事实应作为 S1/S2 的当前基线。

### 2.1 本地工作规则

`/Users/boson/IdeaProjects/java-tron/AGENTS.md` 要求先读 `.codex/memory/CODEX_MEMORY.md`。对 S1/S2 有影响的规则：

| 规则 | S1/S2 处理 |
| --- | --- |
| PR scope focused on one problem | S1/S2 可以拆成两个 PR，或一个 PR 内两个 commit |
| 非测试改动建议小于 10 文件 | S1 控制在 config + skeleton；S2 控制在 txnum + Manager |
| Java 改动前提交前运行 `./gradlew lint` | 最终 gate 必跑 |
| import/test 变动跑 `checkstyleMain checkstyleTest -x generateGitProperties` | S1/S2 都会新增类和测试，必跑 |
| JUnit expected exception 使用 `assertThrows` | 新测试不要用 `@Test(expected=...)` |
| 不加 test skip | 失败就修或记录 blocker |

### 2.2 配置链路

| 文件 | 当前事实 | S1 影响 |
| --- | --- | --- |
| `common/src/main/java/org/tron/core/config/args/Storage.java:48` | `Storage` 是当前 `storage` 配置模型和 runtime 载体 | `storage.archive.*` 应挂在 `Storage` 下 |
| `Storage.java:53-63` | `Storage` 用常量读取 `storage.*` key | 新增 `storage.archive.*` key 常量和读取 helper |
| `Storage.java:103-145` | `Storage` 持有 runtime 字段 | 增加 archive enable/db/txnum/temporal/commitment 字段或嵌套 `ArchiveConfig` |
| `framework/src/main/resources/config.conf:8-15` | 用户可见 `storage {}` 示例；当前没有 archive 子树 | 加 archive 示例，默认 false |
| `common/src/main/resources/reference.conf` | 当前本地源码没有该目录/文件 | 不要把它列为必改文件 |
| `common/src/main/java/org/tron/common/parameter/CommonParameter.java:479` | 当前有 `public Storage storage` | P0 通过 `getStorage().getArchive()` 暴露 archive 配置 |
| `framework/src/main/java/org/tron/core/config/args/Args.java:516-564` | `Args` 手工构造 `Storage` 并逐项 set | S1 在这段读取 `storage.archive.*` |
| `common/src/main/java/org/tron/common/parameter/CommonParameter.java:113-132` | storage CLI 仍存在但偏 DB 基础配置 | S1 先不新增 archive CLI，避免扩大语义面 |

结论：

```text
S1 采用 config-only。
不新增 --archive / --archive-db-directory 等 CLI。
如果后续评审明确要求 CLI，再作为单独兼容 patch。
```

### 2.3 Manager 生命周期

| 文件位置 | 当前事实 | S2 影响 |
| --- | --- | --- |
| `Manager.java:1266-1272` | `pushBlock(final BlockCapsule block)` 是 canonical block 保存入口 | S2 hook 必须进入 canonical path |
| `Manager.java:1275` | `pushBlock` 在 `synchronized (this)` 内推进 | P0 可以用 `ThreadLocal` archive context |
| `Manager.java:1379` | normal path 使用 `try (ISession tmpSession = revokingStore.buildSession())` | archive block flush 必须在 `tmpSession.commit()` 成功后 |
| `Manager.java:1380` | normal path 调 `applyBlock(newBlock, txs)` | `beginBlock` 应在 apply 前或 apply 内统一包裹 |
| `Manager.java:1381` | `tmpSession.commit()` 提交 canonical revoking session | `commitBlock` 不能早于这一行 |
| `Manager.java:1382-1387` | apply/commit 异常会 remove khaos block 并 rethrow | archive 必须 `abortBlock` |
| `Manager.java:1034-1042` | `eraseBlock()` 中 `khaosDb.pop()` 后 `revokingStore.fastPop()` | S2 在 `fastPop()` 成功后调用 archive unwind |
| `Manager.java:1142-1149` | `switchFork()` replay 新分支使用 revoking session | replay 分支也要同样 commit/abort archive |
| `Manager.java:1185-1187` | fork 失败恢复旧分支也使用 revoking session | recovery replay 同样要 commit/abort archive |
| `Manager.java:1838` | `processBlock(block, txs)` 是 tx 循环所在方法 | `beginUserTx/endUserTx` 最小改这里 |
| `Manager.java:1851` | tx loop 前 `BalanceTraceStore.initCurrentBlockBalanceTrace(block)` | 这些前置写需要 `BLOCK_PREPARE` phase |
| `Manager.java:1854` | tx loop 前 `saveBlockEnergyUsage(0)` | 同上 |
| `Manager.java:1867` | `HistoryBlockHashUtil.write(this, block)` | `BLOCK_PREPARE` phase |
| `Manager.java:1873` | 遍历 `block.getTransactions()` | `txIndex` 应用局部 int 维护，不能用 filtered `txs` 的 index 代替 |
| `Manager.java:1906` | tx loop 后 `payReward(block)` | 属于 `BLOCK_FINALIZE` phase |
| `Manager.java:1911` | maintenance proposal 处理 | 属于 `BLOCK_FINALIZE` phase |
| `Manager.java:1914` | `consensus.applyBlock(block)` | 属于 `BLOCK_FINALIZE` phase |
| `Manager.java:1922-1925` | recent cache / dynamic properties 更新 | 属于 `BLOCK_FINALIZE` phase；recent cache 后续 registry 排除 |
| `Manager.java:1929-1930` | section bloom 初始化和写入 | index/cache 类写入，txNum phase 覆盖但 registry 排除 |

当前 `processBlock` 在 `Manager.java:1867` 有 `HistoryBlockHashUtil.write(this, block)`；S2 应把它纳入 `BLOCK_PREPARE` phase。

### 2.4 Store dbName 前置修复

| 文件 | 当前事实 | S1/S2 影响 |
| --- | --- | --- |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java:78` | `getDbName()` 返回 `null` | S1 应先修成 `return db.getDbName()` |
| `chainbase/src/main/java/org/tron/core/db2/common/DB.java:22` | DB 接口已有 `getDbName()` | 可直接代理 |
| `LevelDB.java:49` | 返回底层 DB name | 可用 |
| `RocksDB.java:50` | 返回底层 DB name | 可用 |
| `Chainbase.java:46` | 已代理 `head.getDbName()` | `SnapshotManager.add` 也依赖 dbName |

这个修复不是 archive hook 本身，但后续 Registry/Collector 都依赖它。如果 S1 不修，S3/S4 会出现无法分类 Store 的返工。

## 3. S1 改动包

S1 目标：

```text
默认关闭 archive，增加 config bean 和 no-op service，不触碰 Manager。
```

建议实际 patch 顺序：

1. `ArchiveConfig` 配置类。
2. `Storage.archive` 字段和 config defaults。
3. `Args.java:516-564` storage 初始化段读取 `storage.archive.*`。
4. `ArchiveService` / `NoopArchiveService` / `ArchivePhase` skeleton。
5. `TronStoreWithRevoking.getDbName()` 修复。
6. focused tests。

### 3.1 新增 `ArchiveConfig`

新增：

```text
common/src/main/java/org/tron/core/config/args/ArchiveConfig.java
```

推荐结构：

```java
package org.tron.core.config.args;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ArchiveConfig {
  private boolean enable = false;
  private DbConfig db = new DbConfig();
  private TxNumConfig txnum = new TxNumConfig();
  private TemporalConfig temporal = new TemporalConfig();
  private CommitmentConfig commitment = new CommitmentConfig();
  private String coverage = "TVM_STATE_ONLY";
  private boolean warnUnclassifiedStoreWrites = true;

  @Getter
  @Setter
  public static class DbConfig {
    private String directory = "archive";
  }

  @Getter
  @Setter
  public static class TxNumConfig {
    private boolean enable = true;
  }

  @Getter
  @Setter
  public static class TemporalConfig {
    private boolean enable = true;
  }

  @Getter
  @Setter
  public static class CommitmentConfig {
    private boolean enable = false;
    private boolean persistTxRoots = false;
  }
}
```

S1 不做复杂校验。`coverage` 拼写和枚举化放到 S3 Registry 或 PR3/PR4 前。

### 3.2 修改 `Storage`

文件：

```text
common/src/main/java/org/tron/core/config/args/Storage.java
```

当前 `4e80f8ffa9a2` 已有 `StorageConfig.java`。实现时应优先在 `StorageConfig` 增加 archive 嵌套 bean，并在 `Storage` runtime 字段区增加 archive config：

```java
private ArchiveConfig archive = new ArchiveConfig();
```

如果保留 helper，推荐让它消费 `StorageConfig.ArchiveConfig` 或等价 bean，而不是重新分散读取 raw `Config`：

```java
public static ArchiveConfig getArchiveConfigFromConfig(Config config) {
  ArchiveConfig archive = new ArchiveConfig();
  archive.setEnable(config.hasPath(ARCHIVE_ENABLE_CONFIG_KEY)
      && config.getBoolean(ARCHIVE_ENABLE_CONFIG_KEY));
  ...
  return archive;
}
```

原因：

- `storage.archive.*` 是 storage 子域。
- 当前 java-tron 的 storage 配置读取是 `Storage` 静态 helper + `Args` 手工 set。
- 不要引用不存在的 `ConfigBeanFactory`/`StorageConfig` 路径。

### 3.3 修改配置文件

文件：

```text
framework/src/main/resources/config.conf
```

在 `storage {}` 下、`balance.history.lookup` 后加入：

```hocon
  archive {
    enable = false
    db.directory = "archive"
    txnum.enable = true
    temporal.enable = true
    commitment.enable = false
    commitment.persistTxRoots = false
    coverage = "TVM_STATE_ONLY"
    warnUnclassifiedStoreWrites = true
  }
```

当前 `4e80f8ffa9a2` 本地源码已有 `common/src/main/resources/reference.conf`。`storage.archive.*` 默认值应先加入 `reference.conf`，`config.conf` 只保留用户可见示例，两处都必须保持默认关闭。

### 3.4 修改 `CommonParameter`

文件：

```text
common/src/main/java/org/tron/common/parameter/CommonParameter.java
```

当前 `CommonParameter.java:479` 已持有 `public Storage storage`。P0 推荐不新增独立 `archive` 字段，而是通过：

```text
CommonParameter.getInstance().getStorage().getArchive()
```

读取 archive 配置。若评审要求便捷 getter，可加只读转发方法，不维护第二份 mutable config。

注意：

- 当前 `CommonParameter.storage` 没有 setter，`Args` 直接赋值。
- `CommonParameter.reset()` 里已经会清理 `storage`，S1 需要保证重新初始化 `Storage` 时 archive 回到默认值。

### 3.5 修改 `Args` storage 初始化

文件：

```text
framework/src/main/java/org/tron/core/config/args/Args.java
```

当前没有 `applyStorageConfig(StorageConfig sc)`。在 `Args.java:516-564` 的 storage 初始化段中，基础 storage 字段设置后增加：

```java
PARAMETER.storage.setArchive(Storage.getArchiveConfigFromConfig(config));
```

建议位置在：

```java
PARAMETER.storage.setMaxFlushCount(Storage.getSnapshotMaxFlushCountFromConfig(config));
PARAMETER.storage.setArchive(Storage.getArchiveConfigFromConfig(config));
PARAMETER.storage.setDefaultDbOptions(config);
```

不要在 S1 增加 CLI override：

- `CLIParameter` 现有 storage CLI 已 deprecated。
- archive 是新功能，直接支持 config file 更符合当前配置迁移方向。
- CLI support 会增加 `DEPRECATED_CLI_TO_CONFIG`、`CLIParameter`、`applyCLIParams` 三处同步，建议单独做。

### 3.6 新增 archive skeleton

新增包：

```text
chainbase/src/main/java/org/tron/core/archive/
```

最小类：

```text
ArchivePhase.java
ArchiveService.java
NoopArchiveService.java
ArchiveException.java
```

`ArchivePhase`：

```java
public enum ArchivePhase {
  BLOCK_PREPARE,
  USER_TX,
  BLOCK_FINALIZE
}
```

`ArchiveService` S1 最小接口：

```java
public interface ArchiveService {
  ArchiveService NOOP = new NoopArchiveService();

  boolean isEnabled();

  default void beginBlock(BlockCapsule block) {
  }

  default void beginUserTx(BlockCapsule block, TransactionCapsule tx, int txIndex) {
  }

  default void endUserTx() {
  }

  default void beginSystemTx(BlockCapsule block, ArchivePhase phase) {
  }

  default void endSystemTx() {
  }

  default void commitBlock() {
  }

  default void abortBlock() {
  }

  default void unwindBlock(BlockCapsule block) {
  }
}
```

`NoopArchiveService`：

```java
public final class NoopArchiveService implements ArchiveService {
  @Override
  public boolean isEnabled() {
    return false;
  }
}
```

说明：

- default methods 让 Manager hook 在 S2 前也可编译演进。
- S1 不接 Spring bean，不接 Manager，避免配置 skeleton PR 过大。
- S2 再引入 `DefaultArchiveService` 和 txNum index。

### 3.7 修复 `TronStoreWithRevoking.getDbName`

文件：

```text
chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java
```

当前：

```java
@Override
public String getDbName() {
  return null;
}
```

改为：

```java
@Override
public String getDbName() {
  return db.getDbName();
}
```

这是 S1 的独立低风险修复。它不改变 DB 写入内容，只修复接口返回值。

### 3.8 S1 测试

修改或新增：

| 测试文件 | 用例 |
| --- | --- |
| `common/src/test/java/org/tron/core/config/args/StorageTest.java` 或现有 storage config 测试 | archive defaults |
| `common/src/test/java/org/tron/core/config/args/StorageTest.java` 或现有 storage config 测试 | archive override |
| `framework/src/test/java/org/tron/core/db/TronStoreWithRevokingDbNameTest.java` | `getDbName()` 返回构造 dbName |
| `chainbase/src/test/java/...` | 当前根目录没有 `chainbase/src/test`，不建议为 S1 单独新建模块测试目录 |

`Storage` 新用例示例：

```java
@Test
public void testArchiveDefaults() {
  ArchiveConfig archive = Storage.getArchiveConfigFromConfig(config);
  assertFalse(archive.isEnable());
  assertEquals("archive", archive.getDb().getDirectory());
  assertTrue(archive.getTxnum().isEnable());
  assertTrue(sc.getArchive().getTemporal().isEnable());
  assertFalse(sc.getArchive().getCommitment().isEnable());
  assertFalse(sc.getArchive().getCommitment().isPersistTxRoots());
  assertEquals("TVM_STATE_ONLY", sc.getArchive().getCoverage());
  assertTrue(sc.getArchive().isWarnUnclassifiedStoreWrites());
}
```

用例命名保持现有风格，不要给旧的 `@Test(expected=...)` 顺手重构。

## 4. S2 改动包

S2 目标：

```text
在 Manager canonical apply path 中建立 block/tx/system phase 坐标。
```

建议实际 patch 顺序：

1. `StatePoint` / txNum record model。
2. `ArchiveTxNumIndex` interface + in-memory implementation。
3. `ArchiveExecutionContext` / `PendingArchiveBlock`。
4. `DefaultArchiveService`。
5. Manager hook normal path。
6. Manager hook switchFork replay/recovery 和 eraseBlock unwind。
7. focused tests。

### 4.1 StatePoint 与 txNum 模型

新增包：

```text
chainbase/src/main/java/org/tron/core/archive/txnum/
```

建议类：

```text
ArchiveTxNumIndex.java
InMemoryArchiveTxNumIndex.java
ArchiveTxRecord.java
ArchiveBlockRange.java
ArchiveTxType.java
ResolvedStatePoint.java
```

`StatePoint` 放在上层包：

```text
chainbase/src/main/java/org/tron/core/archive/StatePoint.java
```

最小枚举/模型：

```java
public enum ArchiveTxType {
  BLOCK_PREPARE,
  USER,
  BLOCK_FINALIZE
}
```

```java
public final class ArchiveTxRecord {
  private final long txNum;
  private final long blockNum;
  private final int txIndex;
  private final ArchiveTxType type;
  private final byte[] txId;
  private final byte[] blockHash;
}
```

S2 的 `txIndex` 约定：

| type | txIndex |
| --- | --- |
| `BLOCK_PREPARE` | `-1` |
| `USER` | block transaction index |
| `BLOCK_FINALIZE` | `block.getTransactions().size()` |

不要用 `getVerifyTxs(block)` 返回的 `txs` list index 作为 archive index。archive 用户可见顺序应以 `block.getTransactions()` 为准。

### 4.2 ArchiveTxNumIndex 接口

建议接口：

```java
public interface ArchiveTxNumIndex {
  long beginBlock(BlockCapsule block);

  ArchiveTxRecord allocateSystemTx(BlockCapsule block, ArchivePhase phase);

  ArchiveTxRecord allocateUserTx(BlockCapsule block, TransactionCapsule tx, int txIndex);

  ArchiveBlockRange endBlock(BlockCapsule block);

  void commitBlock(BlockCapsule block);

  void abortBlock(BlockCapsule block);

  void unwindBlock(long blockNum, byte[] blockHash);

  ResolvedStatePoint resolve(StatePoint point);
}
```

P0 in-memory 实现只需满足测试和 S2 lifecycle。PR5 才持久化到 `ArchiveRawStore`。

### 4.3 InMemoryArchiveTxNumIndex 语义

必须固定：

| 场景 | 语义 |
| --- | --- |
| empty block | 仍分配 `BLOCK_PREPARE` 和 `BLOCK_FINALIZE` |
| multi-tx block | txNum 顺序为 prepare -> user txs -> finalize |
| block apply failure | `abortBlock` 丢弃 pending range，`nextTxNum` 回到 begin 前 |
| fork erase | `unwindBlock` 删除该 block range，`nextTxNum` 回到 previous last + 1 |
| switchFork replay | replay 新分支分配新 canonical range |

P0 不要求 txNum 在 fork 前后保持同一物理编号。要求是 canonical head 下可解析、可回退、单调。

### 4.4 Execution context

新增：

```text
chainbase/src/main/java/org/tron/core/archive/ArchiveExecutionContext.java
chainbase/src/main/java/org/tron/core/archive/PendingArchiveBlock.java
chainbase/src/main/java/org/tron/core/archive/PendingArchiveTx.java
```

P0 可以用 `ThreadLocal`：

```java
private static final ThreadLocal<PendingArchiveBlock> CURRENT_BLOCK = new ThreadLocal<>();
private static final ThreadLocal<PendingArchiveTx> CURRENT_TX = new ThreadLocal<>();
```

理由：

- `Manager.pushBlock` 在 `synchronized (this)` 内推进。
- P0 不做并行 execution collector。
- PR3 Store hook 能通过 context 判断是否 active。

退出规则：

| 方法 | 必须清理 |
| --- | --- |
| `endUserTx` | `CURRENT_TX.remove()` |
| `endSystemTx` | `CURRENT_TX.remove()` |
| `commitBlock` | `CURRENT_BLOCK.remove()` |
| `abortBlock` | `CURRENT_TX.remove()` + `CURRENT_BLOCK.remove()` |

### 4.5 DefaultArchiveService

新增：

```text
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
```

职责：

1. 持有 `ArchiveConfig`。
2. 持有 `ArchiveTxNumIndex`。
3. `isEnabled()` 返回 `archive.enable && archive.txnum.enable`。
4. `beginBlock` 创建 pending block。
5. `beginUserTx/beginSystemTx` 分配 txNum 并设置 current tx。
6. `commitBlock` 提交 index pending range。
7. `abortBlock` 回滚 index pending range 并清 context。
8. `unwindBlock` 调 txNumIndex unwind。

S2 不要引入 temporal/root 的依赖。

### 4.6 Manager hook：normal apply

当前 normal path：

```java
try (ISession tmpSession = revokingStore.buildSession()) {
  applyBlock(newBlock, txs);
  tmpSession.commit();
} catch (Throwable throwable) {
  ...
  throw throwable;
}
```

S2 推荐包成 helper，避免 normal path、switchFork replay、recovery replay 复制三套 archive commit/abort：

```java
private void applyBlockWithArchive(BlockCapsule block, List<TransactionCapsule> txs)
    throws ... {
  archiveService.beginBlock(block);
  boolean committed = false;
  try (ISession tmpSession = revokingStore.buildSession()) {
    applyBlock(block, txs);
    tmpSession.commit();
    archiveService.commitBlock();
    committed = true;
  } finally {
    if (!committed) {
      archiveService.abortBlock();
    }
  }
}
```

注意：

- `commitBlock()` 必须在 `tmpSession.commit()` 后。
- `abortBlock()` 必须覆盖 `applyBlock` 异常和 `tmpSession.commit()` 异常。
- helper 的 throws 列表可以先沿用调用点的 checked exceptions，避免吞异常。

如果不抽 helper，也必须保证三处 session path 语义一致。

### 4.7 Manager hook：processBlock phases

`processBlock` 当前前置写在 tx loop 前直接执行。S2 最小 hook：

```java
archiveService.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
try {
  chainBaseManager.getBalanceTraceStore().initCurrentBlockBalanceTrace(block);
  chainBaseManager.getDynamicPropertiesStore().saveBlockEnergyUsage(0);
} finally {
  archiveService.endSystemTx();
}
```

但是 `preValidateTransactionSign(txs)` 不写 Store，可放在 prepare phase 内或外。为了减少行为包裹范围，推荐：

1. `consensus.validBlock(block)` 保持在 archive phase 外。
2. `BalanceTraceStore.initCurrentBlockBalanceTrace`、`saveBlockEnergyUsage` 放入 `BLOCK_PREPARE`。
3. `preValidateTransactionSign(txs)` 可保持在 phase 外，因为它不应写 state。
4. 当前源码没有 `HistoryBlockHashUtil.write(this, block)`，不要为旧调用点保留 hook。

用户 tx loop：

```java
int txIndex = 0;
for (TransactionCapsule transactionCapsule : block.getTransactions()) {
  archiveService.beginUserTx(block, transactionCapsule, txIndex);
  try {
    ...
    TransactionInfo result = processTransaction(transactionCapsule, block);
    ...
  } finally {
    archiveService.endUserTx();
  }
  txIndex++;
}
```

`txIndex++` 必须在 finally 后推进，避免异常时 index 状态混乱。

finalize phase：

```java
archiveService.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
try {
  payReward(block);
  ...
  block.setBloom(blockBloom);
} finally {
  archiveService.endSystemTx();
}
```

是否把 `block.setResult(transactionRetCapsule)` 包进 finalize：

- S2 不采集 writes，影响不大。
- PR3/PR4 后如果 result store 写被分类为 excluded，仍无状态影响。
- 建议从 `transactionRetCapsule.addAllTransactionInfos(results)` 后开始 finalize，保持 tx loop 和 block final writes 分离。

### 4.8 Manager hook：fork unwind

`eraseBlock()` 当前：

```java
khaosDb.pop();
revokingStore.fastPop();
```

S2 增加：

```java
archiveService.unwindBlock(oldHeadBlock);
```

建议放在 `revokingStore.fastPop()` 成功之后：

```java
khaosDb.pop();
revokingStore.fastPop();
archiveService.unwindBlock(oldHeadBlock);
```

理由：

- canonical DB 已确认回退后再回退 archive。
- 如果 `fastPop()` 失败，不应先回退 archive。

风险：

- 如果 `archiveService.unwindBlock` 失败，canonical DB 已经回退。S2 的 in-memory index 可 fail-fast；PR5 持久化后要设计 repair verifier。
- S2 文档和测试要覆盖 unwind exception 的策略。P0 建议抛出，避免 archive progress 静默错位。

### 4.9 S2 测试

优先新增纯 unit test，避免一开始就跑重型 block integration。

| 测试文件 | 用例 |
| --- | --- |
| `framework/src/test/java/org/tron/core/archive/txnum/InMemoryArchiveTxNumIndexTest.java` | empty block range |
| 同上 | multi tx block range |
| 同上 | abort restores nextTxNum |
| 同上 | unwind removes last block |
| 同上 | resolve `BLOCK_END/TX_BEFORE/TX_AFTER` |
| `framework/src/test/java/org/tron/core/archive/DefaultArchiveServiceTest.java` | context begin/end/abort cleanup |
| Manager focused test | archive disabled no-op |

如果把 archive classes 放在 `chainbase`，测试放哪：

- 当前根源码没有 `chainbase/src/test`。
- 最小方案：把 focused tests 放到 `framework/src/test/java/org/tron/core/archive/...`，通过 framework test classpath 访问 chainbase main classes。
- 后续如果项目愿意启用 chainbase module tests，再迁移。

## 5. S1/S2 文件清单

### S1 非测试文件

| 文件 | 动作 |
| --- | --- |
| `common/src/main/java/org/tron/core/config/args/ArchiveConfig.java` | 新增 |
| `common/src/main/java/org/tron/core/config/args/Storage.java` | 增加 `archive` field/helper |
| `framework/src/main/resources/config.conf` | 增加用户可见 `storage.archive.*` 示例 defaults |
| `common/src/main/java/org/tron/common/parameter/CommonParameter.java` | P0 无需独立 archive 字段；必要时加只读转发 getter |
| `framework/src/main/java/org/tron/core/config/args/Args.java` | 在 storage 初始化段读取 archive config |
| `chainbase/src/main/java/org/tron/core/archive/ArchivePhase.java` | 新增 |
| `chainbase/src/main/java/org/tron/core/archive/ArchiveService.java` | 新增 |
| `chainbase/src/main/java/org/tron/core/archive/NoopArchiveService.java` | 新增 |
| `chainbase/src/main/java/org/tron/core/archive/ArchiveException.java` | 新增 |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java` | `getDbName()` 修复 |

S1 非测试文件超过 10 个。如果要严格遵守 java-tron 建议，可以拆成：

```text
S1a: config only
S1b: archive skeleton + getDbName
```

### S2 非测试文件

| 文件 | 动作 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/archive/StatePoint.java` | 新增 |
| `chainbase/src/main/java/org/tron/core/archive/PendingArchiveBlock.java` | 新增 |
| `chainbase/src/main/java/org/tron/core/archive/PendingArchiveTx.java` | 新增 |
| `chainbase/src/main/java/org/tron/core/archive/ArchiveExecutionContext.java` | 新增 |
| `chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java` | 新增 |
| `chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveTxType.java` | 新增 |
| `chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveTxRecord.java` | 新增 |
| `chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveBlockRange.java` | 新增 |
| `chainbase/src/main/java/org/tron/core/archive/txnum/ResolvedStatePoint.java` | 新增 |
| `chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveTxNumIndex.java` | 新增 |
| `chainbase/src/main/java/org/tron/core/archive/txnum/InMemoryArchiveTxNumIndex.java` | 新增 |
| `framework/src/main/java/org/tron/core/db/Manager.java` | lifecycle hook |

S2 也超过 10 个非测试文件。建议拆成：

```text
S2a: txnum model/index/context
S2b: DefaultArchiveService + Manager hook
```

## 6. 验证命令

编码前基线：

```bash
cd /Users/boson/IdeaProjects/java-tron
git status --short
./gradlew :common:test --tests org.tron.core.config.args.StorageTest
```

S1 focused tests：

```bash
./gradlew :common:test --tests org.tron.core.config.args.StorageTest
./gradlew :framework:test --tests org.tron.core.db.TronStoreWithRevokingDbNameTest
```

S2 focused tests：

```bash
./gradlew :framework:test --tests 'org.tron.core.archive.*'
```

最终 gate：

```bash
./gradlew lint
./gradlew checkstyleMain checkstyleTest -x generateGitProperties
```

如果本地 arm64 环境触发 LevelDB guard，按 java-tron memory：只有 LevelDB 相关测试才用 x86_64 JDK 8 per-command prefix，不改全局 `JAVA_HOME`。

## 7. S1/S2 合并验收

S1 完成：

- [ ] `storage.archive.enable=false` 在 `config.conf` 默认关闭。
- [ ] `Storage.getArchiveConfigFromConfig` 或等价 helper 能读取 defaults 和 override。
- [ ] `CommonParameter.getInstance().getStorage().getArchive()` 可用。
- [ ] `ArchiveService.NOOP.isEnabled()` 返回 false。
- [ ] `TronStoreWithRevoking.getDbName()` 返回底层 DB name。
- [ ] 没有新增 archive CLI 参数，或如果新增，已同步 deprecated CLI map。

S2 完成：

- [ ] empty block 产生 prepare/finalize range。
- [ ] multi-tx block txNum 顺序稳定。
- [ ] block apply 异常会 `abortBlock` 并清 ThreadLocal context。
- [ ] `commitBlock` 只在 canonical revoking session commit 成功后执行。
- [ ] `eraseBlock` 在 `fastPop()` 成功后 unwind archive。
- [ ] switchFork replay 和 recovery replay 都走同一个 archive apply helper。
- [ ] archive disabled 时 Manager hook 只调用 no-op，不改变现有 block apply。

## 8. 不进入 S1/S2 的范围

不要在 S1/S2 中加入：

- `TronStoreWithRevoking.put/delete` hook。
- `ArchiveDomainRegistry`。
- `BlockWriteSet`。
- temporal DB / `ArchiveRawStore`。
- historical JSON-RPC。
- `eth_call`。
- root / sparse Merkle tree。
- `accountStateRoot` 或 block header 修改。

这些属于后续 S3-S14。S1/S2 的价值是把时间轴钉牢，后续所有 state/history/root 都接同一个 lifecycle。
