# java-tron Archive 模块 01：ArchiveTxNumIndex 逐文件 Patch 清单

日期：2026-06-02

> 2026-06-03 更新：本文是旧 `a79693e450` patch 清单，当前实现请先看 [模块 01 ArchiveTxNumIndex：4e80 java-tron 源码对照细化](./20260603-java-tron-module-01-txnum-index-4e80-source-deep-dive.md)。当前源码存在 `StorageConfig.java`、`reference.conf`，且精确冲突标记扫描无命中；本文中“没有 StorageConfig/reference.conf”的描述已失效。

关联设计：[java-tron Archive 模块 01：ArchiveTxNumIndex 细化设计](./20260521-java-tron-archive-module-01-txnum-index.md)

java-tron 源码对照：[模块 01 ArchiveTxNumIndex：java-tron 源码对照](./20260601-java-tron-module-01-txnum-index-java-tron-source-deep-dive.md)

Erigon 源码对照：[模块 01 ArchiveTxNumIndex：Erigon 源码对照深挖](./20260523-java-tron-module-01-txnum-index-erigon-source-deep-dive.md)

PR1/PR2 规格：[java-tron Archive PR1/PR2 代码级实现规格](./20260602-java-tron-archive-pr1-pr2-implementation-spec.md)

PR1/PR2 清单：[java-tron Archive PR1/PR2 逐文件 Patch 清单](./20260602-java-tron-archive-pr1-pr2-patch-checklist.md)

S1/S2 编码执行包：[java-tron Archive S1/S2 编码执行包](./20260602-java-tron-archive-s1-s2-coding-packet.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

本轮复核基线：本地 java-tron `a79693e450`。

## 1. 本文定位

本文把模块 01 单独落到 java-tron 逐文件 patch 级别。PR1/PR2 清单已经覆盖同一批工作，但它是 PR 视角；本文只站在 `ArchiveTxNumIndex` 模块视角，明确：

```text
Archive 默认关闭配置
ArchiveService no-op skeleton
canonical block lifecycle hook
logical txNum 分配
fork unwind 边界
后续模块共享的 StatePoint / ArchiveExecutionContext
```

模块 01 合并后，系统只具备交易级时间线和 archive 生命周期，不采集 Store write-set，不写 temporal history，不读历史状态，不计算 root。

## 2. 模块目标

必须达到：

1. `storage.archive.enable=false` 默认关闭。
2. 默认关闭时 fullnode 行为不变。
3. 开启 archive 后，canonical block apply 分配稳定、连续、可回滚的 `txNum`。
4. 普通交易、block prepare、block finalize 都有 logical tx 边界。
5. fork `eraseBlock()` 后 archive txNum index 回退到 canonical parent。
6. pending transaction、block generation speculative execution、constant call 不进入 archive。
7. 后续模块可以通过 `ArchiveExecutionContext` 获取当前 block/tx/phase。

不做：

- 不改 `TronStoreWithRevoking.put/delete` 采集写。
- 不新增 temporal DB。
- 不新增 historical JSON-RPC。
- 不计算 Merkle root / sparse Merkle root。
- 不写 `BlockHeader.raw.accountStateRoot`。
- 不修改 `txTrieRoot`。

## 3. 源码事实

### 3.1 配置链

| java-tron 位置 | 当前事实 | 模块 01 处理 |
| --- | --- | --- |
| `common/src/main/java/org/tron/core/config/args/Storage.java:48` | `Storage` 是当前 `storage` 配置模型 | 新增 archive 子配置字段或专用 `ArchiveConfig`，由 `Storage` 承载 |
| `Storage.java:53-63` | `Storage` 用常量读取 `storage.*` key | 新增 `storage.archive.*` key 常量和读取 helper |
| `Storage.java:103-145` | `Storage` 持有 runtime 字段 | 增加 archive enable/db/txnum/temporal/commitment 字段 |
| `common/src/main/java/org/tron/common/parameter/CommonParameter.java:479` | 当前有 `public Storage storage` | archive runtime 配置通过 `PARAMETER.storage.getArchive...` 暴露，除非评审要求独立字段 |
| `framework/src/main/java/org/tron/core/config/args/Args.java:516-564` | `Args` 手工构造 `Storage` 并逐项 set | 在这段读取 `storage.archive.*`，不要引用不存在的 `StorageConfig` |
| `framework/src/main/resources/config.conf:8-15` | 用户可见 `storage {}` 示例 | 增加 `archive { ... }` 示例，默认关闭 |
| `common/src/main/resources/reference.conf` | 当前本地源码没有该目录/文件 | 不要在实现清单里要求修改它 |
| `common/src/main/java/org/tron/common/parameter/CommonParameter.java:113-132` | storage CLI 仍存在但偏 DB 基础配置 | S1 不新增 archive CLI |

配置策略：

```text
优先 config-only：storage.archive.*
不新增 --archive 系列 CLI
后续如评审要求 CLI，再单独兼容
```

### 3.2 Store dbName 前置修复

| java-tron 位置 | 当前事实 | 模块 01 处理 |
| --- | --- | --- |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java:78` | `getDbName()` 返回 `null` | 改为 `return db.getDbName()` |
| `chainbase/src/main/java/org/tron/core/db2/common/LevelDB.java:49` | `getDbName()` 返回底层 DB name | 可直接代理 |
| `chainbase/src/main/java/org/tron/core/db2/common/RocksDB.java:50` | `getDbName()` 返回底层 DB name | 可直接代理 |
| `chainbase/src/main/java/org/tron/core/db2/core/SnapshotManager.java:155` | `flushServices` 用 `revokingDB.getDbName()` | null dbName 会污染 flush service key |

这不是 txNum 本身，但 S3/S4 的 registry/write hook 全依赖 dbName。模块 01 先修，避免后续每个模块都返工。

### 3.3 canonical block apply

| java-tron 位置 | 当前事实 | 模块 01 处理 |
| --- | --- | --- |
| `framework/src/main/java/org/tron/core/db/Manager.java:1261-1267` | `pushBlock(final BlockCapsule block)` 是 block 推进入口 | archive begin/end block 挂 normal canonical path |
| `Manager.java:1374` | normal path 创建 `try (ISession tmpSession = revokingStore.buildSession())` | archive pending block 与 revoking session 同生命周期 |
| `Manager.java:1375` | `applyBlock(newBlock, txs)` | apply 前后包 archive block context |
| `Manager.java:1376` | `tmpSession.commit()` | archive commit 必须晚于 canonical commit |
| `Manager.java:1377-1380` | 异常时 remove khaos block 并 rethrow | archive 必须 abort pending block |
| `Manager.java:1062` | `applyBlock(block, txs)` 调 `processBlock` 后写 block/ret/fork | txNum phase 应在 `processBlock` 内，commit 在外层 |

关键约束：

```text
Archive commitBlock 不能放在 applyBlock 内。
applyBlock 可能成功但外层 tmpSession.commit 失败。
```

### 3.4 fork rewind / replay

| java-tron 位置 | 当前事实 | 模块 01 处理 |
| --- | --- | --- |
| `Manager.java:1017-1024` | `eraseBlock()` 处理 canonical head 回退，并在 `khaosDb.pop()` 后调用 `revokingStore.fastPop()` | 加 archive unwind，放在 fastPop 成功后 |
| `Manager.java:1132` | `switchFork()` 对旧分支循环 `eraseBlock()` | 每次 erase 都会触发 archive unwind |
| `Manager.java:1142-1144` | replay 新分支使用 `try (ISession tmpSession = revokingStore.buildSession())` | replay block 同 normal path begin/commit/abort |
| `Manager.java:1180-1182` | fork 失败恢复旧分支也 replay session | recovery replay 也必须包 archive |

不要只改 normal `pushBlock` path。否则 fork 切换后 canonical DB 和 archive txNum index 会分叉。

### 3.5 block 内 logical tx 边界

| java-tron 位置 | 当前事实 | ArchivePhase |
| --- | --- | --- |
| `Manager.java:1824` | `processBlock(block, txs)` 是 block 执行主体 | phase hook 放这里 |
| `Manager.java:1837` | `BalanceTraceStore.initCurrentBlockBalanceTrace(block)` | `BLOCK_PREPARE` |
| `Manager.java:1840` | `DynamicPropertiesStore.saveBlockEnergyUsage(0)` | `BLOCK_PREPARE` |
| `Manager.java:1858` | `for (TransactionCapsule transactionCapsule : block.getTransactions())` | `USER_TX` |
| `Manager.java:1866` | `transactionCapsule.setBlockNum(num)` | 不含 txIndex，archive 自己维护 |
| `Manager.java:1871` | `processTransaction(transactionCapsule, block)` | 用户交易执行 |
| `Manager.java:1891` | `payReward(block)` | `BLOCK_FINALIZE` |
| `Manager.java:1896` | `proposalController.processProposals()` | maintenance/proposal 系统写，属于 `BLOCK_FINALIZE` |
| `Manager.java:1899` | `consensus.applyBlock(block)` | `BLOCK_FINALIZE` |
| `Manager.java:1907-1910` | recent cache/dynamic properties 更新 | `BLOCK_FINALIZE`，其中 dynamic properties 是 root/reader 关注状态 |
| `Manager.java:1914-1917` | section bloom 初始化和写入 | index/cache 类写入，通常不进入 archive root，但 txNum phase 仍覆盖该执行区间 |

当前 `processBlock` 中没有旧稿提到的 `HistoryBlockHashUtil.write(this, block)` 前置写。不要在实现计划里为不存在的调用点保留 hook。

txIndex 必须来自 `block.getTransactions()` 的 full order，不要用 `getVerifyTxs(block)` 的 `txs` 参数。`getVerifyTxs` 会在某些逻辑下过滤/去重，用它做 txIndex 会破坏 canonical transaction order。

### 3.6 必须排除的非 canonical 执行

| java-tron 位置 | 当前事实 | 模块 01 处理 |
| --- | --- | --- |
| `Manager.java:916-922` | `pushTransaction` 建 session 并 `processTransaction(trx, null)` 后 `tmpSession.merge()` | pending validation，不进入 archive |
| `Manager.java:1624-1627` | `generateBlock` 设置本地 block 生成 session并启动 account state callback | speculative，不进入 archive |
| `Manager.java:1718-1722` | block generation 中临时 `processTransaction(trx, blockCapsule)` 并 `tmpSession.merge()` | 只是打包候选，不是 canonical commit |
| `Manager.java:1736-1740` | 本地产块写 `accountStateRoot/txTrieRoot` 并签名 | 生成阶段不写 archive sidecar |
| JSON-RPC constant call path | 会执行 VM 但不提交 canonical Store | 不进入 archive |

archive 只接受 `ArchiveService.beginBlock(...)` 打开的 canonical context。`processTransaction` 本身不应自动启用 archive。

### 3.7 Session 行为

| java-tron 位置 | 当前事实 | 模块 01 处理 |
| --- | --- | --- |
| `SnapshotManager.java:583-585` | `Session.commit()` 调 `snapshotManager.commit()` | canonical commit 成功点 |
| `SnapshotManager.java:588-595` | `Session.revoke()` 回滚 snapshot | archive pending block 也要 abort |
| `SnapshotManager.java:597-604` | `Session.merge()` 只合并 snapshot | pending tx/generate block path 不是 archive commit |
| `SnapshotManager.java:607-610` | `destroy()` 未 commit 时 revoke | try-with-resources 异常时 archive 必须 abort |

模块 01 不改 `ISession` 或 `SnapshotManager`。只在 `Manager` 外围显式调用 archive service。

## 4. Patch 拆分

推荐拆成：

```text
Patch 1: ArchiveConfig + config defaults + Args bridge
Patch 2: ArchiveService / NoopArchiveService / ArchivePhase / StatePoint skeleton
Patch 3: TronStoreWithRevoking.getDbName()
Patch 4: ArchiveTxNumIndex model + in-memory implementation
Patch 5: DefaultArchiveService lifecycle + ArchiveExecutionContext
Patch 6: Manager canonical apply / fork unwind / processBlock phase hook
Patch 7: focused tests
```

若按 java-tron review 习惯压缩 PR，可把 Patch 1-3 作为 PR1，Patch 4-7 作为 PR2。

## 5. Patch 1：ArchiveConfig

### 5.1 新增文件

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

字段语义：

| 字段 | 模块 01 使用 | 后续模块 |
| --- | --- | --- |
| `enable` | 控制 archive 是否启用 | 全部模块 |
| `db.directory` | 保存配置，不打开 DB | 模块 04 |
| `txnum.enable` | 可关闭 txNum 逻辑 | 模块 01 |
| `temporal.enable` | 不使用 | 模块 04 |
| `commitment.enable` | 不使用 | 模块 06 |
| `commitment.persistTxRoots` | 不使用 | PR9/tx root |
| `coverage` | 不解析 enum | 模块 02 |
| `warnUnclassifiedStoreWrites` | 不使用 | 模块 03 |

S1 不做复杂校验。需要 fail-fast 的组合校验放到后续模块：

```text
commitment.enable=true requires temporal.enable=true
persistTxRoots=true requires tx root support
coverage string must map to registry policy
```

### 5.2 修改 Storage runtime config

文件：

```text
common/src/main/java/org/tron/core/config/args/Storage.java
```

当前本地源码没有 `StorageConfig.java` 或 `ConfigBeanFactory` 绑定路径。`Args` 在 `Args.java:516-564` 手工构造 `Storage` 并读取 `storage.*` 配置，因此 archive 配置应沿用当前 `Storage` 模型。

在 key 常量区加入：

```java
private static final String ARCHIVE_ENABLE_CONFIG_KEY = "storage.archive.enable";
private static final String ARCHIVE_DB_DIRECTORY_CONFIG_KEY = "storage.archive.db.directory";
private static final String ARCHIVE_TXNUM_ENABLE_CONFIG_KEY = "storage.archive.txnum.enable";
private static final String ARCHIVE_TEMPORAL_ENABLE_CONFIG_KEY = "storage.archive.temporal.enable";
private static final String ARCHIVE_COMMITMENT_ENABLE_CONFIG_KEY = "storage.archive.commitment.enable";
private static final String ARCHIVE_COMMITMENT_PERSIST_TX_ROOTS_CONFIG_KEY =
    "storage.archive.commitment.persistTxRoots";
private static final String ARCHIVE_COVERAGE_CONFIG_KEY = "storage.archive.coverage";
private static final String ARCHIVE_WARN_UNCLASSIFIED_STORE_WRITES_CONFIG_KEY =
    "storage.archive.warnUnclassifiedStoreWrites";
```

新增 runtime 字段可以用一个嵌套 config 对象，也可以先用字段承载。推荐一个专用对象，仍挂在 `Storage` 下：

```java
@Getter
@Setter
private ArchiveConfig archive = new ArchiveConfig();
```

理由：

- `storage.archive.*` 是 storage 子域。
- 当前 java-tron 的 storage 配置就是 `Storage` runtime class。
- `CommonParameter` 已通过 `getStorage()` 暴露 storage runtime。
- 不要引用不存在的 `StorageConfig` 或 `reference.conf`。

### 5.3 修改 CommonParameter

文件：

```text
common/src/main/java/org/tron/common/parameter/CommonParameter.java
```

当前 `CommonParameter.java:479` 已有：

```text
public Storage storage;
```

P0 推荐不新增独立 `CommonParameter.archive` 字段，而是通过 `CommonParameter.getInstance().getStorage().getArchive()` 读取。这样避免把同一份 storage 子配置复制两份。若评审要求便捷 getter，可增加只读转发方法，而不是维护第二份 mutable 状态。

### 5.4 修改 Args storage 初始化

文件：

```text
framework/src/main/java/org/tron/core/config/args/Args.java
```

当前没有 `applyStorageConfig(StorageConfig sc)`。在 `Args.java:516-564` 的 storage 初始化段中，基础字段设置后加入：

```java
PARAMETER.storage.setArchive(Storage.getArchiveConfigFromConfig(config));
```

建议放在：

```text
PARAMETER.storage.setTxCacheInitOptimization(...)
PARAMETER.storage.setMaxFlushCount(...)
PARAMETER.storage.setArchive(...)
PARAMETER.storage.setDefaultDbOptions(config)
PARAMETER.storage.setPropertyMapFromConfig(config)
```

archive 配置不依赖 RocksDB custom settings，也不应进入 `Storage` property map。也可以拆成多个 `Storage.getArchive...FromConfig(config)` setter，取决于代码风格；关键是不要引用不存在的 `StorageConfig`。

### 5.5 修改配置文件

文件：

```text
framework/src/main/resources/config.conf
```

在 `storage {}` 下加入：

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

- 当前本地源码没有 `common/src/main/resources/reference.conf`，不要把它列为必改文件。
- `config.conf` 中 `storage {}` 是用户可见默认/示例，应完整加入 archive 默认关闭配置。
- 默认关闭是 module 01 的最高优先级约束。

### 5.6 Patch 1 测试

建议测试：

```text
common/src/test/java/org/tron/core/config/args/ArchiveConfigTest.java
framework/src/test/java/org/tron/core/config/args/ArgsArchiveConfigTest.java
```

用例：

1. 默认 config 中 `storage.archive.enable=false`。
2. 显式设置 `enable=true` 能绑定到 `Storage.archive.enable`。
3. `db.directory/txnum/temporal/commitment/coverage` 都能绑定。
4. `Args` 初始化后 `CommonParameter.getInstance().getStorage().getArchive()` 非空。

## 6. Patch 2：ArchiveService skeleton

### 6.1 新增 package

```text
chainbase/src/main/java/org/tron/core/archive/
```

### 6.2 ArchivePhase

新增：

```text
chainbase/src/main/java/org/tron/core/archive/ArchivePhase.java
```

推荐：

```java
public enum ArchivePhase {
  BLOCK_PREPARE,
  USER_TX,
  BLOCK_FINALIZE
}
```

不要在 module 01 拆太细。后续可以扩展：

```text
BLOCK_REWARD
CONSENSUS_APPLY
MAINTENANCE
```

但 P0 使用三个 phase 足够支撑交易级状态点。

### 6.3 StatePoint

新增：

```text
chainbase/src/main/java/org/tron/core/archive/StatePoint.java
```

推荐类型：

```text
LATEST
BLOCK_END(blockNum)
TX_BEFORE(txNum)
TX_AFTER(txNum)
```

module 01 只创建模型，不实现 reader。模块 04/05 使用它。

### 6.4 ArchiveService

新增：

```text
chainbase/src/main/java/org/tron/core/archive/ArchiveService.java
```

推荐接口：

```java
public interface ArchiveService {
  boolean isEnabled();

  void beginBlock(BlockCapsule block);

  void beginSystemTx(BlockCapsule block, ArchivePhase phase);

  void endSystemTx();

  void beginUserTx(BlockCapsule block, TransactionCapsule tx, int txIndex);

  void endUserTx();

  void commitBlock();

  void abortBlock();

  void unwindBlock(long blockNum, byte[] blockHash);

  Optional<ArchiveExecutionContext> currentContext();
}
```

`currentContext()` 是给模块 03 Store hook 用的；module 01 可以先实现 context，但 Store hook 不接入。

### 6.5 NoopArchiveService

新增：

```text
chainbase/src/main/java/org/tron/core/archive/NoopArchiveService.java
```

要求：

- 所有方法 no-op。
- `isEnabled()` 返回 false。
- 不读写 DB。
- 不分配 txNum。
- 不抛异常。

默认关闭时 Manager 可以直接调用 no-op service，避免到处加复杂条件。

### 6.6 ArchiveExecutionContext

新增：

```text
chainbase/src/main/java/org/tron/core/archive/collector/ArchiveExecutionContext.java
```

也可以在 module 01 先放：

```text
chainbase/src/main/java/org/tron/core/archive/ArchiveExecutionContext.java
```

但建议直接放到 `collector` package，因 S4 会扩展它。

字段：

```text
blockNum
blockHash
txNum
txIndex
txId
ArchivePhase phase
boolean canonical
```

对于 system tx：

```text
txIndex = -1
txId = null
phase = BLOCK_PREPARE or BLOCK_FINALIZE
```

### 6.7 Patch 2 测试

用例：

1. `NoopArchiveService` 所有生命周期方法可重复调用不抛异常。
2. `ArchivePhase` enum 顺序不作为编码使用；如果需要编码，新增 explicit code 字段。
3. `StatePoint` 不允许非法组合，例如 `TX_AFTER` 无 txNum。
4. context close 后 `currentContext()` empty。

## 7. Patch 3：getDbName 前置修复

文件：

```text
chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java
```

修改：

```java
@Override
public String getDbName() {
  return db.getDbName();
}
```

原因：

1. `SnapshotManager.add` 用 dbName 建 flush service。
2. S3 registry 需要 dbName 分类 Store。
3. S4 write hook 需要 dbName 映射 domain。

测试：

```text
chainbase/src/test/java/org/tron/core/db/TronStoreWithRevokingTest.java
```

用例：

1. fake DB name 可以透传。
2. LevelDB wrapper name 可以透传。
3. RocksDB wrapper name 可以透传。

不要在这个 patch 改 put/delete 采集逻辑。

## 8. Patch 4：ArchiveTxNumIndex

### 8.1 新增 package

```text
chainbase/src/main/java/org/tron/core/archive/txnum/
```

### 8.2 数据类型

新增：

```text
ArchiveTxNumIndex.java
ArchiveTxNumAllocator.java
TxNumRecord.java
BlockTxNumRange.java
TxIdIndexRecord.java
InMemoryArchiveTxNumIndex.java
ArchiveTxNumException.java
```

可以合并部分 record，保持 PR 小。

### 8.3 TxNumRecord

字段：

```text
long txNum
long blockNum
byte[] blockHash
ArchivePhase phase
int txIndex
byte[] txId
boolean systemTx
```

约束：

- `txNum >= 0`。
- `blockNum >= 0`。
- `txIndex >= 0` only for `USER_TX`。
- system tx 的 `txId == null`。
- block hash 必须复制，不保留外部可变数组。

### 8.4 BlockTxNumRange

字段：

```text
long blockNum
byte[] blockHash
long firstTxNum
long lastTxNumInclusive
int userTxCount
int systemTxCount
```

用途：

- block-end `StatePoint` -> asOfTxNum。
- unwind block 时定位 txNum range。
- 模块 04 持久化 `TXNUM_BLOCK`。

### 8.5 TxIdIndexRecord

字段：

```text
byte[] txId
long txNum
long blockNum
byte[] blockHash
int txIndex
```

只为 `USER_TX` 建 txId index。system tx 没有 txId。

### 8.6 ArchiveTxNumIndex 接口

建议：

```java
public interface ArchiveTxNumIndex {
  void beginBlock(long blockNum, byte[] blockHash);

  TxNumRecord allocateSystemTx(ArchivePhase phase);

  TxNumRecord allocateUserTx(byte[] txId, int txIndex);

  BlockTxNumRange sealBlock(int expectedUserTxCount);

  void commitBlock(BlockTxNumRange range);

  void abortBlock();

  void unwindBlock(long blockNum, byte[] blockHash);

  Optional<BlockTxNumRange> blockRange(long blockNum);

  Optional<TxIdIndexRecord> byTxId(byte[] txId);

  long latestTxNum();
}
```

module 01 可先用 in-memory implementation。S6/S7 再做 persistent implementation。

### 8.7 分配规则

推荐顺序：

```text
BLOCK_PREPARE
USER_TX[0]
USER_TX[1]
...
USER_TX[n-1]
BLOCK_FINALIZE
```

空块：

```text
BLOCK_PREPARE
BLOCK_FINALIZE
```

不能因为 tx count 为 0 就没有 txNum range。否则 block-end state point 无法表示。

### 8.8 duplicate / replay 规则

`beginBlock(blockNum, blockHash)`：

- 如果当前有 uncommitted block，抛异常或先 abort，由 service 保证调用顺序。
- 如果 `blockNum <= latest committed blockNum`，只有 unwind/replay path 合法。
- 同一个 committed blockNum/hash 重复 commit 不应静默覆盖。

`allocateUserTx(txId, txIndex)`：

- txIndex 必须等于当前 user tx count。
- 同一 block 内 duplicate txId 如果 java-tron 允许进入，需要仍然按 txIndex 分配 txNum；`txId -> txNum` 可只保留 canonical first or fail fast，建议 fail fast 并依赖现有 dup validation。

`sealBlock(expectedUserTxCount)`：

- 校验实际 user tx count。
- 生成 `BlockTxNumRange`。
- 不做 persistent commit。

### 8.9 in-memory 实现边界

`InMemoryArchiveTxNumIndex` 用于 PR2 和单测：

```text
TreeMap<Long, BlockTxNumRange> byBlock
Map<WrappedBytes, TxIdIndexRecord> byTxId
TreeMap<Long, TxNumRecord> byTxNum
long nextTxNum
PendingBlock pending
```

它不是生产 archive storage。S7 会把这些表迁移到 single physical archive DB：

```text
TXNUM_BLOCK
TXNUM_BY_TXID
TXNUM_META
```

### 8.10 Patch 4 测试

用例：

1. 空 block 分配 prepare/finalize 两个 txNum。
2. N tx block 分配 `2 + N` 个 txNum。
3. txNum 全局连续。
4. txId index 只存在 user tx。
5. abortBlock 回滚 pending 分配。
6. unwindBlock 删除 block range 和 txId index，并回退 latest。
7. blockHash mismatch unwind 抛异常。

## 9. Patch 5：DefaultArchiveService

### 9.1 新增文件

```text
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
chainbase/src/main/java/org/tron/core/archive/ArchiveBlockSession.java
```

### 9.2 状态机

状态：

```text
IDLE
BLOCK_ACTIVE
TX_ACTIVE
BLOCK_SEALED
REPAIR_NEEDED
```

合法转换：

```text
IDLE -> beginBlock -> BLOCK_ACTIVE
BLOCK_ACTIVE -> beginSystemTx -> TX_ACTIVE -> endSystemTx -> BLOCK_ACTIVE
BLOCK_ACTIVE -> beginUserTx -> TX_ACTIVE -> endUserTx -> BLOCK_ACTIVE
BLOCK_ACTIVE -> seal internal -> BLOCK_SEALED
BLOCK_SEALED -> commitBlock -> IDLE
BLOCK_ACTIVE/TX_ACTIVE/BLOCK_SEALED -> abortBlock -> IDLE
IDLE -> unwindBlock -> IDLE
```

非法：

- nested tx。
- commitBlock without beginBlock。
- beginBlock while previous block active。
- endUserTx when current phase is system。
- beginUserTx without txId。

### 9.3 beginBlock

逻辑：

```text
if disabled: return
capture blockNum/blockHash/txCount
txNumIndex.beginBlock(blockNum, blockHash)
set ThreadLocal block context
```

ThreadLocal 只服务当前 `Manager.pushBlock` synchronized execution。不要把 context 放到 static global mutable object without cleanup。

### 9.4 beginSystemTx / endSystemTx

逻辑：

```text
TxNumRecord record = txNumIndex.allocateSystemTx(phase)
set current tx context
```

`endSystemTx()`：

```text
clear current tx context
append sealed tx metadata to pending block
```

module 01 不采集 writes，所以只是生命周期校验。S4 会在 tx end 时 seal write-set。

### 9.5 beginUserTx / endUserTx

逻辑：

```text
txId = tx.getTransactionId().getBytes()
record = txNumIndex.allocateUserTx(txId, txIndex)
set current tx context
```

`txIndex` 由 `Manager.processBlock` 的 loop local variable 传入，不从 `TransactionCapsule` 读取。

### 9.6 commitBlock

逻辑：

```text
range = txNumIndex.sealBlock(expectedUserTxCount)
txNumIndex.commitBlock(range)
clear ThreadLocal
```

S7 后替换为：

```text
ArchiveBatch batch = rawStore.newBatch()
temporalStore.stageApplyBlock(blockWriteSet, batch)
commitmentBuilder.stageBlockEnd(blockWriteSet, batch)
rawStore.updateByBatch(batch.toRawMap())
```

module 01 只提交 in-memory txNum。

### 9.7 abortBlock

逻辑：

```text
txNumIndex.abortBlock()
clear ThreadLocal
clear pending block session
```

必须幂等。异常 path 可能多次清理。

### 9.8 unwindBlock

逻辑：

```text
txNumIndex.unwindBlock(blockNum, blockHash)
```

S7 后会变成 temporal unwind；S11 后同 batch root unwind。

### 9.9 shouldCollectStoreWrites

module 01 可以先不在接口公开，也可以返回：

```java
return isEnabled() && currentContext().isPresent();
```

但 S4 接通前不要改 Store hook。

### 9.10 Patch 5 测试

用例：

1. disabled service 等价 no-op。
2. enabled service lifecycle 正常。
3. nested tx 抛异常。
4. abort 清 ThreadLocal。
5. commit 清 ThreadLocal。
6. system tx 和 user tx phase/txIndex 正确。

## 10. Patch 6：Manager hook

### 10.1 注入 service

文件：

```text
framework/src/main/java/org/tron/core/db/Manager.java
```

新增字段：

```java
@Autowired
private ArchiveService archiveService;
```

如果 Spring wiring 风险较大，可以在 S1 先提供 bean：

```text
chainbase/src/main/java/org/tron/core/archive/ArchiveServiceFactory.java
```

但推荐直接 Spring component：

- config disabled -> `NoopArchiveService` 或 `DefaultArchiveService` disabled mode。
- config enabled -> `DefaultArchiveService`。

### 10.2 normal pushBlock path

目标位置：

```text
Manager.java:1374-1380
```

建议形状：

```java
archiveService.beginBlock(newBlock);
try (ISession tmpSession = revokingStore.buildSession()) {
  applyBlock(newBlock, txs);
  tmpSession.commit();
  archiveService.commitBlock();
} catch (Throwable throwable) {
  archiveService.abortBlock();
  logger.error(throwable.getMessage(), throwable);
  khaosDb.removeBlk(block.getBlockId());
  throw throwable;
}
```

注意：

- `archiveService.commitBlock()` 在 `tmpSession.commit()` 后。
- `archiveService.abortBlock()` 在 catch 内。
- 如果 `archiveService.commitBlock()` 抛异常，canonical DB 已 commit；S7 启动 verifier 必须能识别 archive behind。module 01 可让节点抛出错误停止，不能静默继续。

### 10.3 switchFork replay path

目标位置：

```text
Manager.java:1142-1144
Manager.java:1180-1182
```

把 replay block 同样包 lifecycle：

```java
archiveService.beginBlock(item.getBlk().setSwitch(true));
try (ISession tmpSession = revokingStore.buildSession()) {
  ...
  applyBlock(item.getBlk().setSwitch(true));
  tmpSession.commit();
  archiveService.commitBlock();
} catch (...) {
  archiveService.abortBlock();
  throw;
}
```

注意 `setSwitch(true)` 会返回 block capsule。避免调用两次导致引用不一致，建议保存局部变量：

```java
BlockCapsule replayBlock = item.getBlk().setSwitch(true);
archiveService.beginBlock(replayBlock);
...
applyBlock(replayBlock);
```

recovery replay path 也一样。

### 10.4 eraseBlock unwind path

目标位置：

```text
Manager.java:1017-1024
```

建议形状：

```java
BlockCapsule oldHeadBlock = chainBaseManager.getBlockById(
    getDynamicPropertiesStore().getLatestBlockHeaderHash());
logger.info("Start to erase block: {}.", oldHeadBlock);
khaosDb.pop();
revokingStore.fastPop();
archiveService.unwindBlock(oldHeadBlock.getNum(), oldHeadBlock.getBlockId().getBytes());
logger.info("End to erase block: {}.", oldHeadBlock);
```

如果 archive unwind 失败：

- 不要静默吞掉。
- module 01 可以进入 repair-needed 或抛异常。
- P0 推荐抛异常并阻止继续 replay，避免 canonical/archive 分叉更深。

### 10.5 processBlock phase hook

目标位置：

```text
Manager.java:1824-1917
```

建议插入：

```java
archiveService.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
try {
  chainBaseManager.getBalanceTraceStore().initCurrentBlockBalanceTrace(block);
  chainBaseManager.getDynamicPropertiesStore().saveBlockEnergyUsage(0);
} finally {
  archiveService.endSystemTx();
}
```

交易 loop：

```java
int txIndex = 0;
for (TransactionCapsule transactionCapsule : block.getTransactions()) {
  ...
  archiveService.beginUserTx(block, transactionCapsule, txIndex);
  try {
    accountStateCallBack.preExeTrans();
    TransactionInfo result = processTransaction(transactionCapsule, block);
    accountStateCallBack.exeTransFinish();
    if (Objects.nonNull(result)) {
      results.add(result);
    }
  } finally {
    archiveService.endUserTx();
  }
  txIndex++;
}
```

finalize：

```java
archiveService.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
try {
  payReward(block);
  ...
  updateDynamicProperties(block);
  Bloom blockBloom = chainBaseManager.getSectionBloomStore()
      .initBlockSection(transactionRetCapsule);
  chainBaseManager.getSectionBloomStore().write(block.getNum());
  block.setBloom(blockBloom);
} finally {
  archiveService.endSystemTx();
}
```

边界选择说明：

- `BLOCK_PREPARE` 应包住 `initCurrentBlockBalanceTrace(block)` 和 `saveBlockEnergyUsage(0)`。
- `USER_TX` 只包单笔交易执行及其回调。
- `BLOCK_FINALIZE` 包住 reward、proposal、consensus apply、recent indexes、dynamic properties 和 section bloom 写入。
- `sectionBloomStore.write` 可不进入 root state，但如果 S4 generic hook 后发现它被采集，应由 registry 标记 ignored。
- 当前 `processBlock` 没有 `HistoryBlockHashUtil.write(this, block)` 调用；不要为旧调用点设计 hook。

### 10.6 不要 hook 的位置

不要在这些地方开始 archive block：

| 源码 | 原因 |
| --- | --- |
| `Manager.pushTransaction` | pending validation |
| `Manager.generateBlock` | local speculative generation |
| `TransactionTrace.exec` | VM call 可能来自 pending/constant/historical call |
| `RepositoryImpl.commit` | 缺少 canonical block/tx boundary |
| `TronStoreWithRevoking.put/delete` | module 03 才接 write collector |

模块 01 的原则：

```text
只有 Manager canonical block path 可以打开 ArchiveExecutionContext。
```

## 11. Patch 7：测试

### 11.1 Config tests

建议：

```text
common/src/test/java/org/tron/core/config/args/ArchiveConfigTest.java
framework/src/test/java/org/tron/core/config/args/ArgsArchiveConfigTest.java
```

用例：

- defaults disabled。
- custom config enabled。
- nested config binding。
- Args bridge。

### 11.2 Service tests

建议：

```text
chainbase/src/test/java/org/tron/core/archive/NoopArchiveServiceTest.java
chainbase/src/test/java/org/tron/core/archive/DefaultArchiveServiceTest.java
```

用例：

- no-op idempotent。
- begin/tx/end/commit lifecycle。
- abort clears pending。
- illegal nested tx。
- missing beginBlock before tx。

### 11.3 TxNum tests

建议：

```text
chainbase/src/test/java/org/tron/core/archive/txnum/InMemoryArchiveTxNumIndexTest.java
```

用例：

- empty block。
- multiple tx block。
- global continuity across blocks。
- abort block。
- unwind block。
- hash mismatch。
- txId lookup。
- block range lookup。

### 11.4 Manager integration tests

建议：

```text
framework/src/test/java/org/tron/core/archive/ArchiveBlockLifecycleTest.java
framework/src/test/java/org/tron/core/archive/ArchiveForkLifecycleTest.java
```

用例：

- normal `pushBlock` 成功后 commitBlock called after canonical commit。
- `applyBlock` 抛异常时 abortBlock called。
- block with 0 tx still has prepare/finalize txNum。
- block with N tx has `N + 2` logical tx。
- `eraseBlock` 调用 unwindBlock with old head block num/hash。
- `switchFork` old branch unwind、新 branch replay 都触发 archive lifecycle。
- `pushTransaction` 不触发 archive。
- `generateBlock` 不触发 archive commit。

### 11.5 Gate

文档级建议：

```text
./gradlew :common:test --tests '*ArchiveConfig*'
./gradlew :chainbase:test --tests '*Archive*'
./gradlew :framework:test --tests '*Archive*'
./gradlew checkstyleMain checkstyleTest -x generateGitProperties
./gradlew lint
```

实际命令按 java-tron 当前 Gradle task 可用性调整。不要新增 `@Ignore`、条件绕过或测试矩阵排除。

## 12. 代码审查检查项

### 12.1 配置

- [ ] `storage.archive.enable=false` 是默认值。
- [ ] `config.conf` 默认关闭 archive；不引用不存在的 `reference.conf`。
- [ ] `ArchiveConfig` 挂在当前 `Storage` runtime class 下，或通过 `Storage` 字段表达。
- [ ] `Args.java:516-564` storage 初始化段读取 `storage.archive.*`。
- [ ] 没有新增 archive CLI。

### 12.2 Lifecycle

- [ ] `ArchiveService` 默认 no-op。
- [ ] normal pushBlock 包 begin/commit/abort。
- [ ] commitBlock 晚于 `tmpSession.commit()`。
- [ ] apply/commit 异常调用 abortBlock。
- [ ] switchFork replay path 也包 archive。
- [ ] eraseBlock 在 `fastPop()` 后 unwind archive。
- [ ] pushTransaction/generateBlock/constant call 不进入 archive。

### 12.3 TxNum

- [ ] block prepare 分配 txNum。
- [ ] 每个 user tx 按 `block.getTransactions()` 顺序分配 txNum。
- [ ] block finalize 分配 txNum。
- [ ] 空块也有 txNum range。
- [ ] txNum 全局连续。
- [ ] txId index 只覆盖 user tx。
- [ ] abort/unwind 回滚 pending/committed txNum。
- [ ] blockHash mismatch 不静默通过。

### 12.4 后续模块契约

- [ ] `ArchiveExecutionContext` 可暴露 block/tx/phase。
- [ ] Store hook 尚未接入，避免半成品 collector。
- [ ] `StatePoint` 可表达 latest/block end/tx before/tx after。
- [ ] module 04 可用 `BlockTxNumRange` 持久化 `TXNUM_BLOCK`。
- [ ] module 05 可用 txNum 解析 historical read。
- [ ] module 06 可用 block-end txNum/root point。

## 13. 风险与处理

| 风险 | 触发点 | 处理 |
| --- | --- | --- |
| archive commit after canonical commit 失败 | `archiveService.commitBlock()` 抛异常 | 节点停止或 repair-needed；不能静默继续 |
| txIndex 用错列表 | 使用 `txs` 而不是 `block.getTransactions()` | 明确只用 block 原始顺序 |
| local block generation 污染 archive | `generateBlock` 内调用 `processTransaction` | archive context 只由 canonical `pushBlock` 打开 |
| pending validation 污染 archive | `pushTransaction` 调 `processTransaction(trx,null)` | block context missing 时 no-op |
| fork replay 漏 archive | 只改 normal path | switchFork 两个 replay loop 都要包 |
| dbName 仍为 null | 忘修 `TronStoreWithRevoking.getDbName()` | S3/S4 inventory tests 会失败 |
| phase 太细导致 PR 膨胀 | 一开始拆 reward/consensus/maintenance | P0 先 `BLOCK_FINALIZE` |

## 14. 与后续模块的交付边界

模块 01 完成后，下游拿到：

```text
ArchiveConfig
ArchiveService
ArchivePhase
StatePoint
ArchiveExecutionContext
ArchiveTxNumIndex
BlockTxNumRange
TxNumRecord
Manager canonical lifecycle hook
```

模块 02 基于 `getDbName()` 和 `ArchiveConfig.coverage` 定义 domain。

模块 03 基于 `ArchiveExecutionContext` 判断 Store write 是否属于当前 logical tx。

模块 04 把 in-memory txNum index 迁移到 persistent `TXNUM_*` logical tables，并实现 `getAsOf`。

模块 05 用 `StatePoint` 和 txNum index 解析 historical read。

模块 06 在 block-end `commitBlock` 路径使用同一 block write-set 计算 archive sidecar root。

## 15. 最小完成定义

模块 01 可以认为完成，当且仅当：

1. archive 默认关闭并有配置测试证明。
2. no-op service 存在，Manager 可以无条件调用。
3. `TronStoreWithRevoking.getDbName()` 返回真实 DB name。
4. normal canonical block 分配 prepare/user/finalize txNum。
5. fork erase/replay 能回滚和重放 txNum。
6. pending/generate/constant 路径没有 archive commit。
7. 所有新增测试无跳过、无条件绕过。
8. 后续模块所需 public API 已稳定，且没有把 write collector/temporal/root 提前半实现。
