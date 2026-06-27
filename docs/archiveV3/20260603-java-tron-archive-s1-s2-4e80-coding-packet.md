# java-tron Archive S1/S2：4e80 编码执行包

> ⚠️ **枚举基数已冻结**：`ArchivePhase` 以 **L1/L2 的 4 值**（含 `UNWIND`）为准（本文的 3 值已废弃；`UNWIND` 不被 `TxNumMetaCodec` 持久化）。详见 [00-ARCHIVE-V3-AUTHORITY-AND-DECISIONS.md](00-ARCHIVE-V3-AUTHORITY-AND-DECISIONS.md) §2。

日期：2026-06-03

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`，`git status --short` 为空，精确冲突标记扫描无命中。

归属路线：[java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)

模块来源：[模块 01 ArchiveTxNumIndex：4e80 java-tron 源码对照细化](./20260603-java-tron-module-01-txnum-index-4e80-source-deep-dive.md)

L1 收窄执行包：[java-tron Archive L1：config/no-op/dbName 代码级执行包](./20260604-java-tron-archive-l1-config-noop-dbname-4e80-code-plan.md)

L2 收窄执行包：[java-tron Archive L2：Manager lifecycle + txNum 代码级执行包](./20260605-java-tron-archive-l2-manager-txnum-4e80-code-plan.md)

本文是第一批真实编码执行包，只覆盖：

```text
S1: archive config + no-op service + dbName 修复
S2: Manager lifecycle + in-memory txNum index
```

不采集 Store write-set，不写 temporal history，不改 JSON-RPC，不计算 root。

实际编码时先按 L1 收窄执行包完成 `config/no-op/dbName`，不要在第一批 patch 同时引入 Manager lifecycle 和 txNum。L1 gate 通过后，以 L2 收窄执行包为准实现 Manager lifecycle + in-memory txNum index，本文 S2 部分保留为上游背景和边界约束。

## 1. 当前源码锚点

### 1.1 配置链路

当前 `4e80f8ffa9a2` 已经有 `StorageConfig` bean 和 `reference.conf`，不能按旧 S1/S2 文档走手工 raw `Config` 读取。

| 源码 | 当前事实 | S1 落点 |
| --- | --- | --- |
| `common/src/main/resources/reference.conf:118` | `storage.balance.history.lookup = false` 默认值在 reference 里 | 新增 `storage.archive.*` 默认值 |
| `framework/src/main/resources/config.conf:6-42` | 用户可见 `storage {}` 示例 | 加 archive 示例，默认 false |
| `common/src/main/java/org/tron/core/config/args/StorageConfig.java:21-33` | `StorageConfig` 是 storage bean | 增加 `ArchiveConfig` 嵌套 bean |
| `StorageConfig.java:173-188` | `fromConfig(config)` 绑定 `storage` 节并 post-process | archive 校验放在 `ArchiveConfig.postProcess()` |
| `common/src/main/java/org/tron/core/config/args/Storage.java:56-96` | runtime storage 字段 | 增加 `archive` runtime 字段 |
| `framework/src/main/java/org/tron/core/config/args/Args.java:212-244` | `applyStorageConfig(StorageConfig sc)` 桥接到 `PARAMETER.storage` | 写入 `PARAMETER.storage.setArchive(sc.getArchive())` |
| `Args.java:713-716` | 初始化 `PARAMETER.storage` 后读取 `StorageConfig` | 不新增第二套读取入口 |

### 1.2 Manager lifecycle

| 源码 | 当前事实 | S2 落点 |
| --- | --- | --- |
| `Manager.java:1266-1272` | `pushBlock(final BlockCapsule block)` 是保存 block 入口 | 注入 `ArchiveService` |
| `Manager.java:1305-1307` | 非本地产块先 `validateMerkleRoot()` 和 `consensus.receiveBlock(block)` | archive 不接入该校验 |
| `Manager.java:1379-1381` | normal path 在 revoking session 内 apply 后 commit | archive flush 只能在 `tmpSession.commit()` 后 |
| `Manager.java:1382-1387` | apply/commit 失败会 remove khaos block 并 rethrow | catch 中 `archiveService.abortBlock(newBlock)` |
| `Manager.java:1388-1389` | commit 后 `blockTrigger` | `archiveService.commitBlock(newBlock)` 放在 trigger 前 |
| `Manager.java:1034-1042` | `eraseBlock()` 里 `khaosDb.pop()` 后 `revokingStore.fastPop()` | fastPop 成功后 `archiveService.unwindBlock(oldHeadBlock)` |
| `Manager.java:1142-1149` | fork replay 新分支 session commit | 同样 begin/commit/abort |
| `Manager.java:1185-1187` | fork 失败恢复原分支 session commit | 同样 begin/commit/abort |

### 1.3 processBlock phase

| 源码 | 当前事实 | S2 phase |
| --- | --- | --- |
| `Manager.java:1851-1854` | balance trace 初始化、block energy 清零 | `BLOCK_PREPARE` |
| `Manager.java:1867` | `HistoryBlockHashUtil.write(this, block)` | `BLOCK_PREPARE`，是否进 domain 由 S3 决定 |
| `Manager.java:1873-1887` | 原始 block 交易顺序执行 | `USER_TX(txIndex)` |
| `Manager.java:1906-1925` | reward、proposal、consensus apply、dynamic properties | `BLOCK_FINALIZE` |

`txIndex` 必须按 `block.getTransactions()` 原始循环计数，不能用 filtered `txs` 下标，也不能用 `TransactionCapsule.order`。

## 2. S1 改动清单

### 2.1 配置默认值

修改 `common/src/main/resources/reference.conf`：

```hocon
storage {
  archive {
    enable = false
    db {
      directory = "archive"
    }
    txnum {
      enable = true
    }
    temporal {
      enable = true
    }
    commitment {
      enable = false
      persistTxRoots = false
    }
    coverage = "TVM_STATE_ONLY"
    warnUnclassifiedStoreWrites = true
  }
}
```

修改 `framework/src/main/resources/config.conf`，在 `storage {}` 下增加同样结构的用户可见示例，保持 `enable=false` 和 `commitment.enable=false`。

S1 不新增 CLI 参数。`--archive`、`--archive-db-directory` 这类入口后续单独讨论，避免把第一批 patch 做大。

### 2.2 StorageConfig

修改：

```text
common/src/main/java/org/tron/core/config/args/StorageConfig.java
```

新增字段：

```java
private ArchiveConfig archive = new ArchiveConfig();
```

新增嵌套 bean：

```java
@Getter
@Setter
public static class ArchiveConfig {
  private boolean enable = false;
  private DbConfig db = new DbConfig();
  private TxNumConfig txnum = new TxNumConfig();
  private TemporalConfig temporal = new TemporalConfig();
  private CommitmentConfig commitment = new CommitmentConfig();
  private String coverage = "TVM_STATE_ONLY";
  private boolean warnUnclassifiedStoreWrites = true;

  void postProcess() {
    if (db.directory == null || db.directory.trim().isEmpty()) {
      throw new IllegalArgumentException("storage.archive.db.directory must not be empty");
    }
    if (coverage == null || coverage.trim().isEmpty()) {
      throw new IllegalArgumentException("storage.archive.coverage must not be empty");
    }
  }

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

在 `StorageConfig.fromConfig` 的 post-process 段新增：

```java
sc.archive.postProcess();
```

### 2.3 Storage runtime 字段

修改：

```text
common/src/main/java/org/tron/core/config/args/Storage.java
```

新增：

```java
@Getter
@Setter
private StorageConfig.ArchiveConfig archive = new StorageConfig.ArchiveConfig();
```

不把 `ArchiveConfig` 放在 `chainbase` 后再让 `common` 依赖它。`common` 不能反向依赖 `chainbase`。

### 2.4 Args bridge

修改：

```text
framework/src/main/java/org/tron/core/config/args/Args.java
```

在 `applyStorageConfig(StorageConfig sc)` 内追加：

```java
PARAMETER.storage.setArchive(sc.getArchive());
```

放在普通 storage 字段写入附近即可。不要新增 raw `Config` 读取 helper，也不要绕过 `StorageConfig.fromConfig(config)`。

### 2.5 Archive service skeleton

新增：

```text
chainbase/src/main/java/org/tron/core/archive/ArchivePhase.java
chainbase/src/main/java/org/tron/core/archive/ArchiveService.java
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
chainbase/src/main/java/org/tron/core/archive/ArchiveExecutionContext.java
```

`ArchivePhase`：

```java
public enum ArchivePhase {
  BLOCK_PREPARE,
  USER_TX,
  BLOCK_FINALIZE
}
```

`ArchiveService`：

```java
public interface ArchiveService {
  boolean isEnabled();

  void beginBlock(BlockCapsule block);
  void commitBlock(BlockCapsule block);
  void abortBlock(BlockCapsule block);
  void unwindBlock(BlockCapsule block);

  void beginSystemTx(BlockCapsule block, ArchivePhase phase);
  void beginUserTx(BlockCapsule block, int txIndex, TransactionCapsule tx);
  void endTx();
}
```

`DefaultArchiveService` 先作为唯一 Spring bean：

```java
@Component
public class DefaultArchiveService implements ArchiveService {
  private final ArchiveExecutionContext executionContext = new ArchiveExecutionContext();

  @Override
  public boolean isEnabled() {
    Storage storage = CommonParameter.getInstance().getStorage();
    return storage != null && storage.getArchive() != null && storage.getArchive().isEnable();
  }

  @Override
  public void beginBlock(BlockCapsule block) {
    if (!isEnabled()) {
      return;
    }
    executionContext.beginBlock(block.getNum());
  }

  // S1 methods are no-op when disabled; S2 fills txNum behavior.
}
```

S1 不引入 `NoopArchiveService` 第二个 bean，避免 Spring 多实现注入歧义。默认关闭由 `DefaultArchiveService.isEnabled()` 控制。

### 2.6 dbName 修复

修改：

```text
chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java
```

把：

```java
public String getDbName() {
  return null;
}
```

改为：

```java
public String getDbName() {
  return db.getDbName();
}
```

这是 S3/S4 DomainRegistry 和 Store hook 的前置条件。

## 3. S2 改动清单

### 3.1 txNum 数据对象

新增：

```text
chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveTxNumIndex.java
chainbase/src/main/java/org/tron/core/archive/txnum/InMemoryArchiveTxNumIndex.java
chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveTxPosition.java
chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveBlockRange.java
```

`ArchiveTxPosition`：

```java
public final class ArchiveTxPosition {
  private final long txNum;
  private final long blockNum;
  private final ArchivePhase phase;
  private final int txIndex;
  private final byte[] txId;
}
```

`ArchiveBlockRange`：

```java
public final class ArchiveBlockRange {
  private final long blockNum;
  private final long firstTxNum;
  private final long lastTxNum;
  private final long finalizeTxNum;
  private final int userTxCount;
}
```

`ArchiveTxNumIndex`：

```java
public interface ArchiveTxNumIndex {
  void beginBlock(long blockNum);
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

### 3.2 InMemoryArchiveTxNumIndex 不变量

`InMemoryArchiveTxNumIndex` 必须维护 committed/working 两套计数：

```text
committedNextTxNum: 已提交状态的下一个 txNum
workingNextTxNum: 当前 pending block 内分配中的下一个 txNum
```

行为：

```text
beginBlock:
  workingNextTxNum = committedNextTxNum
  pending positions/range clear

allocate:
  txNum = workingNextTxNum++
  append pending position

commitBlock:
  validate pending blockNum
  persist pending positions/range in memory maps
  committedNextTxNum = workingNextTxNum
  clear pending

abortBlock:
  discard pending
  workingNextTxNum = committedNextTxNum

unwindBlock:
  remove block range and positions
  committedNextTxNum = removedRange.firstTxNum
  workingNextTxNum = committedNextTxNum
```

这能避免 apply 失败后 `nextTxNum` 永久前进。

### 3.3 ArchiveExecutionContext

`ArchiveExecutionContext` 用 `ThreadLocal`，因为 `Manager.pushBlock` 在 synchronized path 中执行，但 Store hook 未来可能从深层调用链读取当前 txNum。

建议接口：

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

S2 只设置上下文；S4 Store hook 再读取它。

### 3.4 DefaultArchiveService S2 行为

`DefaultArchiveService` 增加 `ArchiveTxNumIndex`：

```java
@Component
public class DefaultArchiveService implements ArchiveService {
  private final ArchiveTxNumIndex txNumIndex = new InMemoryArchiveTxNumIndex();
  private final ArchiveExecutionContext executionContext = new ArchiveExecutionContext();

  @Override
  public void beginBlock(BlockCapsule block) {
    if (!isEnabled()) {
      return;
    }
    txNumIndex.beginBlock(block.getNum());
  }

  @Override
  public void beginSystemTx(BlockCapsule block, ArchivePhase phase) {
    if (!isEnabled()) {
      return;
    }
    executionContext.enter(txNumIndex.allocateSystemTx(block.getNum(), phase));
  }

  @Override
  public void beginUserTx(BlockCapsule block, int txIndex, TransactionCapsule tx) {
    if (!isEnabled()) {
      return;
    }
    executionContext.enter(
        txNumIndex.allocateUserTx(block.getNum(), txIndex, tx.getTransactionId().getBytes()));
  }

  @Override
  public void endTx() {
    executionContext.clear();
  }
}
```

S6/S7 会把 `InMemoryArchiveTxNumIndex` 替换为 persistent index。S2 先不要写 DB。

## 4. Manager patch 位置

### 4.1 注入 ArchiveService

在 `Manager.java` imports 增加：

```java
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveService;
```

在字段区增加：

```java
@Autowired
private ArchiveService archiveService;
```

### 4.2 normal pushBlock

在 `Manager.java:1379-1389` normal path 改成以下形状：

```java
long oldSolidNum = getDynamicPropertiesStore().getLatestSolidifiedBlockNum();
archiveService.beginBlock(newBlock);
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

`archiveService.commitBlock(newBlock)` 必须在 `blockTrigger` 前。

### 4.3 eraseBlock

在 `Manager.java:1037-1042` 后追加：

```java
BlockCapsule oldHeadBlock = chainBaseManager.getBlockById(
    getDynamicPropertiesStore().getLatestBlockHeaderHash());
...
khaosDb.pop();
revokingStore.fastPop();
archiveService.unwindBlock(oldHeadBlock);
```

不能放在 `fastPop()` 前。

### 4.4 fork replay

在 `Manager.java:1142-1149` 新分支 replay 中：

```java
BlockCapsule replayBlock = item.getBlk().setSwitch(true);
archiveService.beginBlock(replayBlock);
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

在 `Manager.java:1185-1187` recovery replay 中使用同样形状。

### 4.5 processBlock phases

把 `BLOCK_PREPARE` 包住 block-level writes：

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

用户交易循环：

```java
int txIndex = 0;
for (TransactionCapsule transactionCapsule : block.getTransactions()) {
  archiveService.beginUserTx(block, txIndex, transactionCapsule);
  try {
    rejectExchangeTransaction(transactionCapsule.getInstance());
    ...
    accountStateCallBack.preExeTrans();
    TransactionInfo result = processTransaction(transactionCapsule, block);
    accountStateCallBack.exeTransFinish();
    ...
  } finally {
    archiveService.endTx();
    txIndex++;
  }
}
```

`BLOCK_FINALIZE` 建议从 tx loop 后系统写开始覆盖到 `updateDynamicProperties(block)` 后：

```java
archiveService.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
try {
  merkleContainer.saveCurrentMerkleTreeAsBestMerkleTree(block.getNum());
  block.setResult(transactionRetCapsule);
  ...
  payReward(block);
  ...
  updateDynamicProperties(block);
} finally {
  archiveService.endTx();
}
```

其中 section bloom、recent cache 等是否进入 archive 由 S3/S4 registry policy 排除；S2 只负责给它们一个明确 txNum context。

## 5. S1/S2 测试落点

### 5.1 StorageConfigTest

现有文件：

```text
common/src/test/java/org/tron/core/config/args/StorageConfigTest.java
```

新增用例：

| 测试 | 断言 |
| --- | --- |
| `testArchiveDefaults` | defaultReference 下 `enable=false`、`db.directory=archive`、`txnum.enable=true`、`temporal.enable=true`、`commitment.enable=false`、`persistTxRoots=false`、`coverage=TVM_STATE_ONLY` |
| `testArchiveOverride` | parseString override 能覆盖 enable/db/commitment |
| `testArchiveRejectsEmptyDirectory` | 空 archive db directory 抛 `IllegalArgumentException` |
| `testArchiveRejectsEmptyCoverage` | 空 coverage 抛 `IllegalArgumentException` |

当前 `StorageConfigTest` 仍有旧 `@Test(expected=...)` 用法；新测试建议用 `assertThrows`，但不要为了风格重写无关旧测试。

### 5.2 StorageTest / Args bridge

现有文件：

```text
framework/src/test/java/org/tron/core/config/args/StorageTest.java
```

新增或新建 `ArgsArchiveConfigTest`：

| 测试 | 断言 |
| --- | --- |
| default args | `Args.getInstance().getStorage().getArchive().isEnable() == false` |
| override config | `storage.archive.enable=true` 后 runtime storage 可读 |
| config.conf fallback | `reference.conf` 默认值能 fallback 到 test config |

### 5.3 TronStoreWithRevokingDbNameTest

建议在 framework 测试中新建：

```text
framework/src/test/java/org/tron/core/db/TronStoreWithRevokingDbNameTest.java
```

选择一个现有 concrete store，例如 `AccountStore`，通过 Spring context 取 bean 后断言：

```java
assertEquals("account", accountStore.getDbName());
```

如果 Spring 集成成本高，可以先用 fake `DB<byte[], byte[]>` 构造一个 test subclass，但最终 S3/S4 前需要 concrete store 覆盖。

### 5.4 ArchiveTxNumIndexTest

新建：

```text
framework/src/test/java/org/tron/core/archive/txnum/ArchiveTxNumIndexTest.java
```

测试：

- `commitMultiTxBlockAssignsPrepareUsersFinalize`
- `emptyBlockStillAssignsPrepareAndFinalize`
- `abortDoesNotAdvanceNextTxNum`
- `unwindRemovesBlockAndResetsNextTxNum`
- `txIdLookupDoesNotUseTransactionStore`

### 5.5 ArchiveServiceLifecycleTest

新建：

```text
framework/src/test/java/org/tron/core/archive/ArchiveServiceLifecycleTest.java
```

先测 service 层，不急着做完整 `Manager.pushBlock` 集成：

- disabled 时所有方法 no-op。
- enabled 时 begin/commit 生成 block range。
- beginUserTx 设置 `ArchiveExecutionContext.current()`。
- endTx 清空 context。
- abortBlock 清空 context 并丢弃 pending range。

### 5.6 Manager integration follow-up

S2 最终需要至少一个 `Manager` 集成测试。可参考：

```text
framework/src/test/java/org/tron/core/db/ManagerTest.java
framework/src/test/java/org/tron/core/db/HistoryBlockHashIntegrationTest.java
```

建议新增测试名：

```text
ArchiveManagerHookTest
```

最小断言：

- push 一个空 block，archive range 有 prepare/finalize。
- push 一个多交易 block，txNum 数量等于 `2 + block.getTransactions().size()`。
- `eraseBlock()` 后 range 被移除。

如果第一批 patch 中真实 block 构造成本过高，可以先用 service-level tests 合入 S1，然后 S2 必须补 Manager integration。

## 6. 验证命令

建议按 slice 跑：

```bash
./gradlew :common:test --tests org.tron.core.config.args.StorageConfigTest
./gradlew :framework:test --tests org.tron.core.config.args.StorageTest
./gradlew :framework:test --tests '*Archive*'
./gradlew :framework:test --tests org.tron.core.db.ManagerTest
./gradlew checkstyleMain checkstyleTest
./gradlew lint
```

若 Gradle test pattern 与当前 java-tron task 不匹配，以实际 `./gradlew tasks` 和模块测试目录为准。失败测试必须定位和修复，不能加 skip。

## 7. S1/S2 停止条件

S1 完成必须证明：

- `storage.archive.*` 默认关闭并可 override。
- runtime `CommonParameter.storage.archive` 可读。
- `ArchiveService` disabled 时所有方法 no-op。
- `TronStoreWithRevoking.getDbName()` 返回真实 DB name。
- 没有 archive DB 写入。

S2 完成必须证明：

- normal block 有 prepare/user/finalize txNum。
- empty block 有 prepare/finalize txNum。
- apply/commit 失败不会推进 committed txNum。
- fork replay/recovery replay 会走 archive lifecycle。
- eraseBlock 在 canonical `fastPop()` 成功后 unwind archive。
- `ArchiveExecutionContext` 在 tx 内可读、tx 后清空。

## 8. 不变量

- Archive 默认关闭。
- S1/S2 不采集 Store write-set。
- S1/S2 不写 archive temporal DB。
- S1/S2 不改 JSON-RPC。
- S1/S2 不计算 root。
- txNum 分配只发生在 canonical block apply/replay/recovery 路径。
- pending/broadcast/constant call 不进入 archive。
- 所有 Manager hook 在 archive disabled 时只做轻量 no-op。
