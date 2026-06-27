# java-tron Archive PR1/PR2 逐文件 Patch 清单

日期：2026-06-02

关联规格：[java-tron Archive PR1/PR2 代码级实现规格](./20260602-java-tron-archive-pr1-pr2-implementation-spec.md)

关联蓝图：[java-tron Archive 状态树实现蓝图](./20260602-java-tron-archive-implementation-blueprint.md)

S1/S2 编码执行包：[java-tron Archive S1/S2 编码执行包](./20260602-java-tron-archive-s1-s2-coding-packet.md)

模块 01 逐文件 Patch 清单：[java-tron Archive 模块 01：ArchiveTxNumIndex 逐文件 Patch 清单](./20260602-java-tron-archive-module-01-txnum-index-patch-checklist.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

本轮复核基线：本地 java-tron `a79693e450`。

## 1. 目标

本文把 PR1/PR2 从“模块规格”进一步落到 java-tron 逐文件 patch 级别：

```text
PR1: 默认关闭的 archive 配置 + no-op ArchiveService + getDbName 前置修复
PR2: ArchiveTxNumIndex + Manager canonical apply / fork unwind 边界 hook
```

合并后只建立交易级时间线，不采集 Store write-set，不写 temporal history，不读历史状态，不计算 root。

必须保持：

1. `storage.archive.enable=false` 默认关闭。
2. 默认关闭时普通 fullnode 行为不变。
3. 开启 archive 后，canonical block apply 能分配稳定、连续、可回滚的 `txNum`。
4. 普通交易、block prepare、block finalize 都有明确 logical tx 边界。
5. fork `eraseBlock()` 后 txNum index 回退到 canonical head。

## 2. 源码事实

| java-tron 位置 | 事实 | 对 PR1/PR2 的含义 |
| --- | --- | --- |
| `common/src/main/java/org/tron/core/config/args/Storage.java:48` | `Storage` 是当前 `storage` 配置模型和 runtime 载体 | `storage.archive.*` 应挂在这里 |
| `Storage.java:53-63` | `Storage` 用常量读取 `storage.*` key | 新增 archive key 常量和读取 helper |
| `framework/src/main/java/org/tron/core/config/args/Args.java:516-564` | `Args` 手工构造 `Storage` 并逐项 set | 需要在这段读取 `storage.archive.*` |
| `common/src/main/java/org/tron/common/parameter/CommonParameter.java:113-132` | 现有 storage CLI 偏 DB 基础配置 | archive CLI 不进 PR1，优先 config file |
| `common/src/main/java/org/tron/common/parameter/CommonParameter.java:479` | 当前只有 `public Storage storage` | P0 通过 `getStorage().getArchive()` 暴露 archive 配置 |
| `framework/src/main/resources/config.conf:8-15` | 显式定义 `storage {}` 默认项 | 可镜像 `storage.archive`，便于用户看到默认 |
| `common/src/main/resources/reference.conf` | 当前本地源码没有该目录/文件 | 不要列为必改文件 |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java:78` | `getDbName()` 返回 `null` | 后续 domain/write hook 识别 Store 会失败，PR1 先修 |
| `framework/src/main/java/org/tron/core/db/Manager.java:1017-1024` | `eraseBlock()` 中 `khaosDb.pop()` 后 `revokingStore.fastPop()` | PR2 在 fastPop 成功后调用 archive unwind |
| `Manager.java:1062` | `applyBlock(block, txs)` 写 block store / ret store / fork state | `commitBlock()` 应晚于外层 session commit |
| `Manager.java:1142-1144` | `switchFork()` replay 新分支时创建 session | 需要同样包 archive block session |
| `Manager.java:1180-1182` | fork 失败后 replay 原分支时创建 session | 也需要同样包 archive block session |
| `Manager.java:1374-1376` | normal `pushBlock()` 创建 `ISession`、`applyBlock` 后 commit | PR2 主 hook 点 |
| `Manager.java:1837` | 交易循环前已有 `BalanceTraceStore.initCurrentBlockBalanceTrace` | 不能只建 `BLOCK_FINALIZE` 系统阶段 |
| `Manager.java:1840` | 交易循环前写 `DynamicPropertiesStore.saveBlockEnergyUsage(0)` | 需要 `BLOCK_PREPARE` 或等价系统阶段 |
| `Manager.java:1858` | 遍历 `block.getTransactions()` | `txIndex` 只能在这里显式维护 |
| `Manager.java:1871` | `processTransaction(transactionCapsule, block)` | 用户交易 txNum 应包住这次执行 |
| `Manager.java:1891` | tx loop 后 `payReward(block)` | 属于 block finalize 系统阶段 |
| `Manager.java:1896` | maintenance proposal 处理 | 属于 block finalize 系统阶段 |
| `Manager.java:1899` | tx loop 后 `consensus.applyBlock(block)` | 属于 block finalize 系统阶段 |
| `Manager.java:1907-1910` | recent cache / dynamic properties 更新 | 属于 block finalize 系统阶段；recent cache 后续 registry 排除 |
| `Manager.java:1914-1917` | section bloom 初始化和写入 | index/cache 类写入，txNum phase 覆盖但 registry 排除 |

当前 `processBlock` 没有旧稿提到的 `HistoryBlockHashUtil.write(this, block)`；不要为不存在的调用点设计 hook。

## 3. 实现顺序

建议按下面顺序拆成小 patch。PR1 可合并 patch 1 到 patch 3；PR2 可合并 patch 4 到 patch 6。

```text
patch 1: ArchiveConfig + config defaults + Args bridge
patch 2: no-op ArchiveService / ArchivePhase
patch 3: TronStoreWithRevoking.getDbName()
patch 4: ArchiveTxNumIndex data model + in-memory implementation
patch 5: DefaultArchiveService 绑定 txNum index 和 execution context
patch 6: Manager canonical apply / tx phase / fork unwind hook
patch 7: tests
```

PR1/PR2 不做：

- 不改 `TronStoreWithRevoking.put/delete`。
- 不新增 temporal DB。
- 不新增 JSON-RPC。
- 不计算 Merkle root / SMT root。
- 不改 Wallet latest 查询路径。
- 不把 archive 数据写入 consensus block header。

## 4. Patch 1：配置模型

### 4.1 新增 `ArchiveConfig`

新增文件：

```text
common/src/main/java/org/tron/core/config/args/ArchiveConfig.java
```

必须放在 `common`，原因是 `CommonParameter` 位于 `common`，不能依赖 `chainbase`。

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

字段边界：

| 字段 | PR1/PR2 使用 | 后续模块使用 |
| --- | --- | --- |
| `enable` | 控制 `ArchiveService.isEnabled()` | 全部 archive 路径开关 |
| `db.directory` | 只保存配置 | PR5 temporal sidecar 路径 |
| `txnum.enable` | 可用于关闭 PR2 txNum 实现 | 通常保持 true |
| `temporal.enable` | PR1/PR2 不使用 | PR5 控制 temporal 写入 |
| `commitment.enable` | PR1/PR2 不使用 | PR7 控制 root 构建 |
| `commitment.persistTxRoots` | PR1/PR2 不使用 | PR7/PR9 控制 per-tx root |
| `coverage` | PR1/PR2 不使用 | PR3/PR4 domain/write 分类 |
| `warnUnclassifiedStoreWrites` | PR1/PR2 不使用 | PR3/PR4 诊断未分类 Store |

### 4.2 修改 `Storage`

文件：

```text
common/src/main/java/org/tron/core/config/args/Storage.java
```

当前没有 `StorageConfig.java`。在 `Storage` key 常量区增加 `storage.archive.*` 配置 key，在 runtime 字段区增加：

```java
private ArchiveConfig archive = new ArchiveConfig();
```

推荐提供：

```java
public static ArchiveConfig getArchiveConfigFromConfig(Config config) {
  ArchiveConfig archive = new ArchiveConfig();
  ...
  return archive;
}
```

理由：

- `storage.archive.*` 是 storage 子域，应由当前 `Storage` 承接。
- 当前 java-tron 的 storage 配置读取是 `Storage` helper + `Args` 手工 set。
- 不要引用不存在的 `ConfigBeanFactory`/`StorageConfig` 路径。

### 4.3 修改 `CommonParameter`

文件：

```text
common/src/main/java/org/tron/common/parameter/CommonParameter.java
```

当前 `CommonParameter.java:479` 已持有 `public Storage storage`。P0 推荐不新增独立 `archive` 字段，而是通过 `CommonParameter.getInstance().getStorage().getArchive()` 读取。若评审要求便捷 getter，可加只读转发方法，不维护第二份 mutable config。

### 4.4 修改 `Args` storage 初始化

文件：

```text
framework/src/main/java/org/tron/core/config/args/Args.java
```

当前没有 `applyStorageConfig(StorageConfig sc)`。在 `Args.java:516-564` 的 storage 初始化段中增加：

```java
PARAMETER.storage.setArchive(Storage.getArchiveConfigFromConfig(config));
```

推荐位置在 `PARAMETER.storage.setMaxFlushCount(...)` 之后、`setDefaultDbOptions(config)` 之前。archive 配置不依赖 RocksDB settings，不应混入 `Storage` 的 property map 处理。

### 4.5 配置文件默认值

文件：

```text
framework/src/main/resources/config.conf
```

在 `storage {}` 下增加：

```hocon
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
```

要求：

- 当前本地源码没有 `common/src/main/resources/reference.conf`，不要列为必改文件。
- `config.conf` 建议显式加入，避免用户看不到新配置。
- 默认 `enable=false` 是硬要求。
- `coverage` 先固定字符串，不在 PR1/PR2 引入 enum 解析，避免把 domain 模块提前拉进来。

### 4.6 CLI 处理策略

当前 `CLIParameter` 把 storage 参数标为 deprecated，`Args` 也有 `DEPRECATED_CLI_TO_CONFIG`。因此 archive 配置应优先走 config file。

推荐 PR1 策略：

1. P0 先不新增 CLI，只支持 `storage.archive.*` 配置文件。
2. 如果必须要 CLI，新增参数也应标注“prefer config file”，并同步 `DEPRECATED_CLI_TO_CONFIG`，避免出现 CLI 和 config 双语义。

如果选择新增 CLI，文件改动如下：

```text
framework/src/main/java/org/tron/core/config/args/CLIParameter.java
framework/src/main/java/org/tron/core/config/args/Args.java
```

参数：

```java
@Parameter(names = {"--archive"}, description = "Enable archive state sidecar")
public boolean archive;

@Parameter(names = {"--archive-db-directory"}, description = "Archive sidecar DB directory")
public String archiveDbDirectory;

@Parameter(names = {"--archive-commitment-enable"}, description = "Enable archive sidecar commitment root")
public boolean archiveCommitmentEnable;

@Parameter(names = {"--archive-persist-tx-roots"}, description = "Persist per-transaction archive roots")
public boolean archivePersistTxRoots;
```

`Args` 需要三处同步：

```text
DEPRECATED_CLI_TO_CONFIG:
  --archive -> storage.archive.enable
  --archive-db-directory -> storage.archive.db.directory
  --archive-commitment-enable -> storage.archive.commitment.enable
  --archive-persist-tx-roots -> storage.archive.commitment.persistTxRoots

CLI assigned override:
  assigned.contains("--archive") -> PARAMETER.getArchive().setEnable(cmd.archive)
  assigned.contains("--archive-db-directory") -> PARAMETER.getArchive().getDb().setDirectory(...)
  assigned.contains("--archive-commitment-enable") -> PARAMETER.getArchive().getCommitment().setEnable(...)
  assigned.contains("--archive-persist-tx-roots") -> PARAMETER.getArchive().getCommitment().setPersistTxRoots(...)
```

评审点：如果 java-tron 希望彻底收敛存储参数到 config file，PR1 应删除 CLI 方案，只保留配置文件。

## 5. Patch 2：no-op ArchiveService

### 5.1 新增包

新增：

```text
chainbase/src/main/java/org/tron/core/archive/
```

`chainbase` 是合适位置，因为：

- `Manager` 在 `framework`，能依赖 `chainbase`。
- `RepositoryImpl` 和 actuator 后续能依赖 `chainbase`。
- `TronStoreWithRevoking` 在 `chainbase`，PR3/PR4 会从这里调用 collector。
- 不会引入 `chainbase -> framework` 反向依赖。

### 5.2 `ArchivePhase`

文件：

```text
chainbase/src/main/java/org/tron/core/archive/ArchivePhase.java
```

推荐不要只定义 `USER_TX` 和 `BLOCK_FINALIZE`。对照 `Manager.processBlock()`，交易循环前已有 store 写，因此 PR2 就应预留 block prepare 阶段：

```java
public enum ArchivePhase {
  BLOCK_PREPARE,
  USER_TX,
  BLOCK_FINALIZE
}
```

含义：

| phase | 包住的 java-tron 代码 |
| --- | --- |
| `BLOCK_PREPARE` | `initCurrentBlockBalanceTrace`、`saveBlockEnergyUsage(0)`、pre-execute setup |
| `USER_TX` | 单个 `processTransaction(transactionCapsule, block)` |
| `BLOCK_FINALIZE` | `payReward`、`proposalController.processProposals`、`consensus.applyBlock`、`updateDynamicProperties`、bloom/recent/cache 更新 |

后续如果要把 maintenance、reward、consensus apply 拆得更细，可扩展 enum；PR2 不需要先拆。

### 5.3 `ArchiveService`

文件：

```text
chainbase/src/main/java/org/tron/core/archive/ArchiveService.java
```

推荐接口：

```java
public interface ArchiveService {
  boolean isEnabled();

  void beginBlock(BlockCapsule block);

  void beginUserTx(BlockCapsule block, TransactionCapsule tx, int txIndex);

  void endUserTx();

  void beginSystemTx(BlockCapsule block, ArchivePhase phase);

  void endSystemTx();

  void commitBlock();

  void abortBlock();

  void unwindBlock(BlockCapsule block);
}
```

暂不加入：

```text
onStorePut / onStoreDelete
```

这些属于 PR3/PR4 WriteCollector。PR1/PR2 只负责时间边界。

### 5.4 `DefaultArchiveService`

文件：

```text
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
```

PR1 行为：

```java
@Component
public class DefaultArchiveService implements ArchiveService {
  @Override
  public boolean isEnabled() {
    return CommonParameter.getInstance().getStorage().getArchive().isEnable();
  }

  @Override
  public void beginBlock(BlockCapsule block) {
  }

  ...
}
```

要求：

- `@Component` 可被 `CommonConfig` 的 `@ComponentScan(basePackages = "org.tron")` 扫到。
- 不要新建多个 `ArchiveService` 实现，避免 Spring 注入歧义。
- PR1 所有方法 no-op，默认关闭和开启都不改状态。
- PR2 在同一个类里注入 txNum index，不增加第二个 bean。

## 6. Patch 3：`TronStoreWithRevoking.getDbName()`

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

这是 PR1 唯一可能影响非 archive 路径的代码。风险可控：

- `db` 是 final。
- `LevelDB`、`RocksDB`、`HashDB`、`ConcurrentHashDB` 都实现 `getDbName()`。
- `SnapshotRoot`、`SnapshotManager` 已经大量依赖底层 db name。

必须补测试。若测试环境不适合打开 LevelDB/RocksDB，优先用 `HashDB` 构造测试 Store。

## 7. Patch 4：ArchiveTxNumIndex

### 7.1 新增文件

```text
chainbase/src/main/java/org/tron/core/archive/ArchiveExecutionContext.java
chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveTxNumIndex.java
chainbase/src/main/java/org/tron/core/archive/txnum/InMemoryArchiveTxNumIndex.java
chainbase/src/main/java/org/tron/core/archive/txnum/TxNumMeta.java
chainbase/src/main/java/org/tron/core/archive/txnum/BlockTxNumRange.java
```

### 7.2 `TxNumMeta`

字段建议：

```java
public class TxNumMeta {
  private final long txNum;
  private final long blockNum;
  private final byte[] blockHash;
  private final int txIndex;
  private final ArchivePhase phase;
  private final byte[] txId;
}
```

约定：

| 字段 | 用户交易 | 系统阶段 |
| --- | --- | --- |
| `txIndex` | block 内从 0 开始 | `-1` |
| `phase` | `USER_TX` | `BLOCK_PREPARE` 或 `BLOCK_FINALIZE` |
| `txId` | `TransactionCapsule.getTransactionId()` | `null` |

### 7.3 `BlockTxNumRange`

字段建议：

```java
public class BlockTxNumRange {
  private final long blockNum;
  private final byte[] blockHash;
  private final long firstTxNum;
  private final long lastTxNum;
  private final long blockStartAsOfTxNum;
  private final long blockEndAsOfTxNum;
  private final int userTxCount;
  private final int systemTxCount;
}
```

PR2 可先用内存保存，PR5 再落 sidecar DB。

### 7.4 `ArchiveExecutionContext`

文件：

```text
chainbase/src/main/java/org/tron/core/archive/ArchiveExecutionContext.java
```

用途：

- 当前 logical tx 的 `TxNumMeta`。
- PR3/PR4 Store write hook 从这里取当前 txNum。
- PR2 只负责 bind/clear，不采集写集。

推荐先用 `ThreadLocal<TxNumMeta>`：

```java
public class ArchiveExecutionContext {
  private final ThreadLocal<TxNumMeta> current = new ThreadLocal<>();

  public boolean active() {
    return current.get() != null;
  }

  public TxNumMeta current() {
    return current.get();
  }

  void bind(TxNumMeta meta) {
    current.set(meta);
  }

  void clear() {
    current.remove();
  }
}
```

`bind/clear` 可以 package-private，避免外部模块随意改上下文；`ArchiveService` 负责生命周期。

### 7.5 `ArchiveTxNumIndex`

接口建议：

```java
public interface ArchiveTxNumIndex {
  long nextTxNum();

  void beginBlock(BlockCapsule block);

  TxNumMeta allocateUserTx(BlockCapsule block, TransactionCapsule tx, int txIndex);

  TxNumMeta allocateSystemTx(BlockCapsule block, ArchivePhase phase);

  void completeBlock(BlockCapsule block);

  void abortBlock(BlockCapsule block);

  void unwindBlock(BlockCapsule block);

  Optional<TxNumMeta> findByTxId(byte[] txId);

  Optional<TxNumMeta> findByTxNum(long txNum);

  Optional<BlockTxNumRange> findBlockRange(long blockNum);
}
```

### 7.6 `InMemoryArchiveTxNumIndex`

PR2 可以先用内存实现：

```java
@Component
public class InMemoryArchiveTxNumIndex implements ArchiveTxNumIndex {
  private long nextTxNum = 0L;
  private PendingBlock pendingBlock;
  private final Map<Long, BlockTxNumRange> blockRanges = new HashMap<>();
  private final Map<WrappedByteArray, TxNumMeta> txIdIndex = new HashMap<>();
  private final Map<Long, TxNumMeta> txNumIndex = new HashMap<>();
}
```

注意 `byte[]` 不能直接做 `HashMap` key。可用 java-tron 现有 byte-array wrapper，或新增不可变包装类；不要用裸 `byte[]`。

`PendingBlock` 保存尚未 commit 的 metas：

```java
private static class PendingBlock {
  private final long blockNum;
  private final byte[] blockHash;
  private final long firstTxNum;
  private long lastTxNum;
  private int userTxCount;
  private int systemTxCount;
  private final List<TxNumMeta> metas = new ArrayList<>();
}
```

分配规则：

```text
beginBlock:
  pending.firstTxNum = nextTxNum

allocateSystemTx(BLOCK_PREPARE):
  txNum = nextTxNum++
  txIndex = -1

allocateUserTx:
  txNum = nextTxNum++
  txIndex = processBlock loop index
  txId = tx.getTransactionId().getBytes()

allocateSystemTx(BLOCK_FINALIZE):
  txNum = nextTxNum++
  txIndex = -1

completeBlock:
  blockStartAsOfTxNum = firstTxNum
  blockEndAsOfTxNum = lastTxNum + 1
  move pending metas to committed maps

abortBlock:
  nextTxNum = pending.firstTxNum
  drop pending

unwindBlock:
  remove committed metas/range for block
  nextTxNum = removedRange.firstTxNum
```

空块策略：

```text
即使 userTxCount=0，也分配 BLOCK_PREPARE 和 BLOCK_FINALIZE。
```

理由：

- java-tron 的空块也可能写 latest header、recent block、dynamic properties、bloom/section 等区块级状态。
- 后续 `BLOCK_END(blockNum)` 不需要特殊处理“无 tx 且无系统写”的区块。
- root/proof/debug 能看到明确 block 边界。

如果后续为了节省空间选择“无写入系统阶段不落 txNum”，必须先让 WriteCollector 能在执行后告知 write_count，再延迟提交 txNum。PR2 不建议这么做。

## 8. Patch 5：DefaultArchiveService 绑定 txNum

PR2 在 `DefaultArchiveService` 中注入：

```java
private final ArchiveTxNumIndex txNumIndex;
private final ArchiveExecutionContext executionContext;
```

生命周期：

```text
beginBlock:
  if disabled: return
  txNumIndex.beginBlock(block)

beginUserTx:
  if disabled: return
  meta = txNumIndex.allocateUserTx(block, tx, txIndex)
  executionContext.bind(meta)

endUserTx:
  executionContext.clear()

beginSystemTx:
  if disabled: return
  meta = txNumIndex.allocateSystemTx(block, phase)
  executionContext.bind(meta)

endSystemTx:
  executionContext.clear()

commitBlock:
  if disabled: return
  txNumIndex.completeBlock(currentBlock)

abortBlock:
  executionContext.clear()
  if disabled: return
  txNumIndex.abortBlock(currentBlock)

unwindBlock:
  executionContext.clear()
  if disabled: return
  txNumIndex.unwindBlock(block)
```

实现注意：

- `DefaultArchiveService` 需要保存当前 pending block 引用，供 `commitBlock/abortBlock` 使用。
- `endUserTx/endSystemTx` 即使 disabled 也可以直接 clear，保证 context 不泄漏。
- `abortBlock()` 必须能在 `beginBlock()` 后、任意阶段失败时调用。
- 如果 `beginBlock()` 之前就失败，`abortBlock()` 应安全 no-op。
- 不要在 `beginUserTx()` 中持久化，PR2 只做内存 index。

## 9. Patch 6：Manager hook

文件：

```text
framework/src/main/java/org/tron/core/db/Manager.java
```

### 9.1 注入 ArchiveService

增加：

```java
@Autowired
private ArchiveService archiveService;
```

导入：

```java
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveService;
```

### 9.2 normal `pushBlock()` session

当前 normal apply：

```java
try (ISession tmpSession = revokingStore.buildSession()) {
  applyBlock(newBlock, txs);
  tmpSession.commit();
}
```

改动规则：

```text
archiveService.beginBlock(newBlock)
applyBlock(newBlock, txs)
tmpSession.commit()
archiveService.commitBlock()
```

如果 `applyBlock()` 或 `tmpSession.commit()` 任一失败：

```text
archiveService.abortBlock()
```

不能在 `applyBlock()` 内部直接 `commitBlock()`，因为 `applyBlock()` 返回时 canonical revoking session 还没 commit。PR5 开始 sidecar flush 后，如果 archive 提前 commit，会出现 archive ahead。

### 9.3 `switchFork()` replay session

`switchFork()` 有两个 replay 位置：

```text
Manager.java:1142-1144  replay new fork branch
Manager.java:1180-1182  switch back replay old branch
```

这两个位置都要使用与 normal `pushBlock()` 同样的 archive block session 规则：

```text
beginBlock -> applyBlock -> tmpSession.commit -> commitBlock
failure -> abortBlock
```

不要只改 normal path。否则节点发生 fork 后，txNum index 会缺新分支 block，后续历史状态坐标错位。

### 9.4 `eraseBlock()` unwind

当前 `eraseBlock()`：

```java
BlockCapsule oldHeadBlock = chainBaseManager.getBlockById(
    getDynamicPropertiesStore().getLatestBlockHeaderHash());
khaosDb.pop();
revokingStore.fastPop();
...
```

改动规则：

```text
先拿 oldHeadBlock
执行 khaosDb.pop()
执行 revokingStore.fastPop()
fastPop 成功后调用 archiveService.unwindBlock(oldHeadBlock)
```

不要在 `fastPop()` 前 unwind archive。否则 canonical DB 回滚失败时，archive 已经提前回退。

如果 `archiveService.unwindBlock(oldHeadBlock)` 抛异常，应让异常向外暴露，而不是吞掉。否则 canonical state 已经回退但 archive 未回退，会产生不可忽略的不一致。现有 `eraseBlock()` catch 只捕获 `ItemNotFoundException | BadItemException`，archive unwind 异常不应塞进这个 catch 后静默。

### 9.5 `processBlock()` 阶段 hook

当前关键顺序：

```text
initCurrentBlockBalanceTrace(block)
saveBlockEnergyUsage(0)
preValidateTransactionSign(txs)
accountStateCallBack.preExecute(block)
for tx:
  rejectExchangeTransaction(tx)
  tx.setBlockNum(num)
  accountStateCallBack.preExeTrans()
  processTransaction(tx, block)
  accountStateCallBack.exeTransFinish()
accountStateCallBack.executePushFinish()
merkleContainer.saveCurrentMerkleTreeAsBestMerkleTree(blockNum)
block.setResult(transactionRetCapsule)
energy update
payReward(block)
proposalController.processProposals()
consensus.applyBlock(block)
fork reset
updateTransHashCache(block)
updateRecentBlock(block)
updateRecentTransaction(block)
updateDynamicProperties(block)
resetCurrentBlockTrace()
sectionBloomStore.write(blockNum)
```

推荐 hook：

```text
beginSystemTx(block, BLOCK_PREPARE)
  initCurrentBlockBalanceTrace
  saveBlockEnergyUsage(0)
  preValidateTransactionSign
  accountStateCallBack.preExecute
endSystemTx()

for txIndex, transaction:
  beginUserTx(block, transaction, txIndex)
    rejectExchangeTransaction
    setBlockNum
    preExeTrans
    processTransaction
    exeTransFinish
  endUserTx()

beginSystemTx(block, BLOCK_FINALIZE)
  transactionRetCapsule.addAllTransactionInfos
  accountStateCallBack.executePushFinish
  merkle save / block result
  energy update
  payReward
  proposal process
  consensus.applyBlock
  fork reset
  recent/cache/dynamic properties/bloom
endSystemTx()
```

每个 `begin*Tx` 必须配 `finally end*Tx`，防止 `ThreadLocal` 泄漏：

```java
archiveService.beginUserTx(block, transactionCapsule, txIndex);
try {
  ...
} finally {
  archiveService.endUserTx();
}
```

错误处理要求：

- 如果某笔交易抛出异常，`endUserTx()` 仍必须执行。
- 外层 block session 会 `abortBlock()`，回收 pending txNum。
- 不要在 `processTransaction()` 内部分配 txNum；那里看不到稳定 `txIndex`，也看不到系统阶段。

### 9.6 提取 helper 降低重复

为了避免 normal path 和 fork replay 写三份 begin/commit/abort，建议在 `Manager` 提取一个私有 helper。

可行形态：

```java
private void beginArchiveBlock(BlockCapsule block) {
  archiveService.beginBlock(block);
}

private void commitArchiveBlock() {
  archiveService.commitBlock();
}

private void abortArchiveBlock() {
  archiveService.abortBlock();
}
```

或提取一个更完整的 `applyBlockWithArchive(...)`。但要注意：完整 helper 如果包住 `tmpSession.commit()`，会碰到 `ISession` 的 try-with-resources 和大量 checked exceptions，改动面可能更大。PR2 推荐先用小 helper，保持现有控制流。

## 10. Patch 7：测试清单

### 10.1 配置测试

文件建议：

```text
common/src/test/java/org/tron/core/config/args/StorageTest.java
framework/src/test/java/org/tron/core/config/args/ArgsArchiveConfigTest.java
```

覆盖：

1. `storage.archive.enable` 默认 false。
2. `storage.archive.db.directory` 默认 `archive`。
3. `storage.archive.txnum.enable` 默认 true。
4. `storage.archive.commitment.enable` 默认 false。
5. `Args` 初始化后 `CommonParameter.getInstance().getStorage().getArchive()` 非空。
6. 如果实现 CLI，验证 CLI 显式覆盖。

### 10.2 Service no-op 测试

文件建议：

```text
chainbase/src/test/java/org/tron/core/archive/DefaultArchiveServiceTest.java
```

覆盖：

- 默认关闭时所有方法可调用、无异常。
- `endUserTx/endSystemTx/abortBlock/unwindBlock` 在没有 pending block 时可安全调用。

### 10.3 `getDbName()` 测试

文件建议：

```text
chainbase/src/test/java/org/tron/core/db/TronStoreWithRevokingDbNameTest.java
```

覆盖：

```text
underlying HashDB name -> store.getDbName()
```

不要依赖真实 RocksDB/LevelDB 路径，避免测试环境噪声。

### 10.4 TxNumIndex 单测

文件建议：

```text
chainbase/src/test/java/org/tron/core/archive/txnum/InMemoryArchiveTxNumIndexTest.java
```

覆盖：

| case | 断言 |
| --- | --- |
| empty block | 有 `BLOCK_PREPARE` / `BLOCK_FINALIZE`，block range 连续 |
| one tx block | `txId -> txNum`、`txNum -> meta`、block range 正确 |
| multi tx block | txIndex 递增，txNum 连续 |
| abort block | `nextTxNum` 回到 begin 前，pending meta 不进入 committed map |
| unwind latest block | 删除 block range 和 txId index，`nextTxNum=firstTxNum` |
| unwind non-latest block | 抛异常或拒绝，防止破坏连续性 |
| repeated beginBlock | 若已有 pending block，应拒绝 |

### 10.5 Manager hook 测试

文件建议：

```text
framework/src/test/java/org/tron/core/db/ManagerArchiveHookTest.java
```

最低覆盖：

1. normal `pushBlock` 成功：调用顺序为 `beginBlock -> BLOCK_PREPARE -> USER_TX* -> BLOCK_FINALIZE -> commitBlock`。
2. `processTransaction` 抛异常：对应 `endUserTx` 执行，最后 `abortBlock`。
3. `eraseBlock`：`revokingStore.fastPop()` 成功后调用 `unwindBlock(oldHeadBlock)`。

如果 Manager 集成测试过重，可以先把 phase 包装逻辑提到小 helper 并单测 helper；但至少要有一个测试验证 `processBlock` 的 txIndex 顺序。

### 10.6 建议执行命令

代码实现后建议至少跑：

```bash
./gradlew :common:test
./gradlew :chainbase:test
./gradlew :framework:test
./gradlew checkstyleMain
```

如果只改 PR1 配置，优先跑 `:common:test` 和 `:framework:test`；如果改 Manager hook，必须跑 `:framework:test`。

## 11. Review Checklist

合并前逐项检查：

- [ ] `storage.archive.enable=false` 在 `config.conf` 默认关闭；不引用当前不存在的 `reference.conf`。
- [ ] `CommonParameter.reset()` 后 archive 配置回到默认值。
- [ ] 默认关闭时 `ArchiveService` 方法全部 no-op。
- [ ] `TronStoreWithRevoking.getDbName()` 返回底层 db name。
- [ ] `ArchivePhase` 至少包含 `BLOCK_PREPARE`、`USER_TX`、`BLOCK_FINALIZE`。
- [ ] `processBlock()` 中所有 `begin*Tx` 都有 `finally end*Tx`。
- [ ] `commitBlock()` 只在 `tmpSession.commit()` 成功后调用。
- [ ] `abortBlock()` 覆盖 `applyBlock()` 失败和 `tmpSession.commit()` 失败。
- [ ] `switchFork()` 两个 replay 分支都包了 archive session。
- [ ] `eraseBlock()` 在 `fastPop()` 成功后 unwind archive。
- [ ] `byte[]` 不作为 Map key 直接使用。
- [ ] empty block、multi-tx block、abort、unwind 都有单测。

## 12. 对后续模块的接口承诺

PR1/PR2 合并后，后续模块只能依赖以下承诺，不应反向改 PR1/PR2 语义：

```text
ArchiveExecutionContext.current()
  -> 当前 canonical logical tx 的 TxNumMeta

ArchiveTxNumIndex.findBlockRange(blockNum)
  -> block start/end asOfTxNum

ArchiveTxNumIndex.findByTxId(txId)
  -> TX_BEFORE/TX_AFTER 的基础 txNum

ArchiveService.unwindBlock(block)
  -> archive sidecar 回退入口
```

PR3/PR4 WriteCollector 使用 `ArchiveExecutionContext` 捕获 write-set。

PR5 TemporalStore 使用 `TxNumMeta.txNum` 写 history/latest/index。

PR6 ArchiveStateReader 使用 `BlockTxNumRange` 和 `txId -> txNum` 解析状态点。

PR7 CommitmentBuilder 使用同一 txNum 序列计算 block-end root 和可选 tx-after root。
