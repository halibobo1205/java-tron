# java-tron Archive PR1/PR2 代码级实现规格

日期：2026-06-02

> 2026-06-03 更新：本文是旧 `a79693e450` 规格。当前 `4e80f8ffa9a2` 的 S1/S2 编码入口请以 [java-tron Archive S1/S2：4e80 编码执行包](./20260603-java-tron-archive-s1-s2-4e80-coding-packet.md) 为准。当前源码已有 `common/src/main/resources/reference.conf` 和 `StorageConfig.java`，旧行号和旧配置链路不可直接用于编码。

关联实现蓝图：[java-tron Archive 状态树实现蓝图](./20260602-java-tron-archive-implementation-blueprint.md)

逐文件 patch 清单：[java-tron Archive PR1/PR2 逐文件 Patch 清单](./20260602-java-tron-archive-pr1-pr2-patch-checklist.md)

S1/S2 编码执行包：[java-tron Archive S1/S2 编码执行包](./20260602-java-tron-archive-s1-s2-coding-packet.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

## 1. 范围

本文只细化前两个 PR：

```text
PR 1: archive 默认关闭配置 + no-op ArchiveService 框架
PR 2: ArchiveTxNumIndex + Manager 交易边界 hook
```

这两步的目标是把交易级时间坐标钉死，先不接入 Store write-set，不改变状态读，不计算 root。

合并后的行为要求：

1. `storage.archive.enable=false` 默认关闭。
2. 默认关闭时，现有 fullnode 行为、Store 写入、block apply、JSON-RPC latest 结果不变。
3. 开启 archive 后，节点能为 canonical block apply 分配稳定 `txNum`。
4. empty block、multi-tx block、block prepare、block finalize 都有明确 txNum range。
5. fork erase block 时 txNum index 能回退。

## 2. 源码证据

| 位置 | 事实 |
| --- | --- |
| `common/src/main/java/org/tron/core/config/args/Storage.java:48` | `Storage` 是当前 `storage` 配置模型和 runtime 载体 |
| `Storage.java:53-63` | `Storage` 用常量读取 `storage.*` key |
| `framework/src/main/java/org/tron/core/config/args/Args.java:516-564` | `Args` 手工构造 `Storage` 并逐项 set |
| `common/src/main/java/org/tron/common/parameter/CommonParameter.java:113-132` | storage CLI 仍存在但偏 DB 基础配置 |
| `common/src/main/java/org/tron/common/parameter/CommonParameter.java:626` | CLI 已有 `--history-balance-lookup` |
| `common/src/main/java/org/tron/core/config/CommonConfig.java:27` | `@ComponentScan(basePackages = "org.tron")` |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java:78` | `getDbName()` 当前返回 `null` |
| `framework/src/main/java/org/tron/core/db/Manager.java:1379-1381` | normal apply 使用 `try (ISession tmpSession = revokingStore.buildSession())` 并在 `applyBlock` 后 commit |
| `Manager.java:1068` | `applyBlock` 调 `processBlock` |
| `Manager.java:1851` | 交易循环前已有 `BalanceTraceStore.initCurrentBlockBalanceTrace(block)` |
| `Manager.java:1854` | 交易循环前写 `DynamicPropertiesStore.saveBlockEnergyUsage(0)` |
| `Manager.java:1867` | `HistoryBlockHashUtil.write(this, block)` |
| `Manager.java:1873` | `processBlock` 遍历 `block.getTransactions()` |
| `Manager.java:1881` | `transactionCapsule.setBlockNum(num)` |
| `Manager.java:1886` | `processTransaction(transactionCapsule, block)` |
| `Manager.java:1906` | tx loop 后 `payReward(block)` |
| `Manager.java:1911` | maintenance proposal 处理 |
| `Manager.java:1914` | tx loop 后 `consensus.applyBlock(block)` |
| `Manager.java:1922-1925` | recent cache / dynamic properties 更新 |
| `Manager.java:1929-1930` | section bloom 初始化和写入 |
| `Manager.java:1034-1042` | `eraseBlock()` 处理 fork 回退，并在 `khaosDb.pop()` 后调用 `revokingStore.fastPop()` |

## 3. PR 1：配置和 no-op Archive 框架

### 3.1 改动文件

```text
framework/src/main/resources/config.conf
common/src/main/java/org/tron/core/config/args/Storage.java
common/src/main/java/org/tron/common/parameter/CommonParameter.java
framework/src/main/java/org/tron/core/config/args/Args.java
common/src/main/java/org/tron/core/config/args/ArchiveConfig.java
chainbase/src/main/java/org/tron/core/archive/ArchivePhase.java
chainbase/src/main/java/org/tron/core/archive/ArchiveService.java
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java
common/src/test/java/org/tron/core/config/args/StorageTest.java
framework/src/test/java/org/tron/core/config/args/ArgsArchiveConfigTest.java
framework/src/test/java/org/tron/core/db/TronStoreWithRevokingDbNameTest.java
```

### 3.2 配置结构

在 `config.conf` 的 `storage {}` 下新增：

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

当前 `4e80f8ffa9a2` 本地源码已有 `common/src/main/resources/reference.conf`。`storage.archive.*` 默认值应先加入 `reference.conf`，并在 `framework/src/main/resources/config.conf` 中给出用户可见示例，两处都保持默认关闭。

### 3.3 ArchiveConfig 和 Storage

新增独立配置类：

```text
common/src/main/java/org/tron/core/config/args/ArchiveConfig.java
```

不要放在 `chainbase`，因为 `CommonParameter` 位于 `common` 模块，不能依赖 `chainbase`。

```java
package org.tron.core.config.args;

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

然后在 `Storage` 增加字段：

```java
private ArchiveConfig archive = new ArchiveConfig();
```

说明：

- 这里复用当前 `Storage` runtime/config 模型，因为配置路径是 `storage.archive.*`。
- 不放进零散全局字段，因为 archive 是完整 storage 子域。

### 3.4 CommonParameter

当前 `CommonParameter` 已有 `Storage storage`。P0 推荐不新增独立 archive 字段，通过 `CommonParameter.getInstance().getStorage().getArchive()` 读取。

若评审要求便捷 getter，可以增加只读转发方法；不要维护第二份 mutable archive 配置。

### 3.5 Args storage 初始化

在 `Args.java:516-564` 的 storage 初始化段中增加：

```java
PARAMETER.storage.setArchive(Storage.getArchiveConfigFromConfig(config));
```

S1/S2 编码执行包已进一步收敛：PR1 先不新增 archive CLI override，只做 config file 读取。当前 `CLIParameter` 中 storage 参数整体是 deprecated 风格；archive 是新功能，不应在第一批 patch 中继续扩大 CLI 面。

如果后续评审要求 CLI 兼容，再单独按 `assigned.contains(...)` 风格增加 `--archive` 系列 override，并同步 `DEPRECATED_CLI_TO_CONFIG`。

### 3.6 CLIParameter

当前 `CLIParameter` 已把 storage 参数标为 deprecated，并建议使用 config file。PR1/S1 只支持 `storage.archive.*` 配置文件。如果评审要求提供 CLI，再作为单独兼容 patch 在 `CLIParameter` 加：

```java
@Parameter(names = {"--archive"}, description = "Enable archive state sidecar")
public boolean archive;

@Parameter(names = {"--archive-db-directory"}, description = "Archive sidecar DB directory")
public String archiveDbDirectory;

@Parameter(names = {"--archive-commitment-enable"},
    description = "Enable archive sidecar commitment root")
public boolean archiveCommitmentEnable;

@Parameter(names = {"--archive-persist-tx-roots"},
    description = "Persist per-transaction archive roots")
public boolean archivePersistTxRoots;
```

这些不是共识参数，不应写入 `DynamicPropertiesStore`。

如果新增 CLI，也要同步 `Args.DEPRECATED_CLI_TO_CONFIG`，把这些参数映射到 `storage.archive.*`，避免 CLI 和配置文件形成两套语义。

### 3.7 ArchiveService

新增接口：

```java
package org.tron.core.archive;

import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;

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

PR 1 不接 Store write hook，所以暂不放 `onStorePut/onStoreDelete`，避免提前扩大改动面。

### 3.8 ArchivePhase

```java
package org.tron.core.archive;

public enum ArchivePhase {
  BLOCK_PREPARE,
  USER_TX,
  BLOCK_FINALIZE
}
```

`BLOCK_PREPARE` 是 java-tron 源码对照后的修正点。当前 `Manager.processBlock()` 在普通交易循环之前已经写 `BalanceTraceStore` 和 `DynamicPropertiesStore.saveBlockEnergyUsage(0)`；如果 PR2 只保留 `BLOCK_FINALIZE`，PR3/PR4 接 Store write hook 时会漏掉这些系统写。当前源码没有旧稿中的 `HistoryBlockHashUtil.write(this, block)` 调用。

### 3.9 DefaultArchiveService

```java
package org.tron.core.archive;

import org.springframework.stereotype.Component;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;

@Component
public class DefaultArchiveService implements ArchiveService {

  @Override
  public boolean isEnabled() {
    return CommonParameter.getInstance().getStorage().getArchive().isEnable();
  }

  @Override
  public void beginBlock(BlockCapsule block) {
  }

  @Override
  public void beginUserTx(BlockCapsule block, TransactionCapsule tx, int txIndex) {
  }

  @Override
  public void endUserTx() {
  }

  @Override
  public void beginSystemTx(BlockCapsule block, ArchivePhase phase) {
  }

  @Override
  public void endSystemTx() {
  }

  @Override
  public void commitBlock() {
  }

  @Override
  public void abortBlock() {
  }

  @Override
  public void unwindBlock(BlockCapsule block) {
  }
}
```

PR 2 会在同一个实现中补 txNum 功能，不新增第二个 bean，避免 Spring 多实现注入冲突。

### 3.10 TronStoreWithRevoking.getDbName 修复

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

这是 PR 1 唯一会影响非 archive 路径的代码变更。风险很低，因为 `db` 是 final，并且底层 `LevelDB/RocksDB/HashDB` 都已有 `getDbName()`。

### 3.11 PR 1 测试

#### Storage archive config cases

路径：

```text
common/src/test/java/org/tron/core/config/args/StorageTest.java
framework/src/test/java/org/tron/core/config/args/ArgsArchiveConfigTest.java
```

测试点：

1. 默认配置 `archive.enable=false`。
2. 默认 `archive.db.directory=archive`。
3. config override `storage.archive.enable=true` 可覆盖为 true。
4. config override `storage.archive.db.directory=custom-archive` 可覆盖目录。

建议直接测试 `Storage.getArchiveConfigFromConfig(config)` 或等价 helper；`ArgsArchiveConfigTest` 再验证 `Args` 初始化后 `CommonParameter.getInstance().getStorage().getArchive()` 可用。

#### TronStoreWithRevokingDbNameTest

路径：

```text
framework/src/test/java/org/tron/core/db/TronStoreWithRevokingDbNameTest.java
```

可用一个测试 Store：

```java
private static class BytesStore extends TronStoreWithRevoking<BytesCapsule> {
  private BytesStore(DB<byte[], byte[]> db) {
    super(db);
  }
}
```

用 `HashDB` 或 `LevelDB` 初始化，断言：

```text
store.getDbName() equals underlying db name
```

不要为这个测试新增 skip。若 LevelDB 在 arm64 不可用，优先用 `HashDB`。

## 4. PR 2：ArchiveTxNumIndex 和 Manager hook

### 4.1 改动文件

```text
chainbase/src/main/java/org/tron/core/archive/ArchiveExecutionContext.java
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveTxNumIndex.java
chainbase/src/main/java/org/tron/core/archive/txnum/InMemoryArchiveTxNumIndex.java
chainbase/src/main/java/org/tron/core/archive/txnum/TxNumMeta.java
chainbase/src/main/java/org/tron/core/archive/txnum/BlockTxNumRange.java
framework/src/main/java/org/tron/core/db/Manager.java
framework/src/test/java/org/tron/core/archive/ArchiveTxNumIndexTest.java
framework/src/test/java/org/tron/core/archive/ArchiveManagerHookTest.java
```

PR 2 可以先用内存 index，PR 3/PR 5 再接 sidecar DB。理由：

- 先固定 hook 和语义。
- 减少首个实现 PR 的存储复杂度。
- 单测能覆盖 txNum 分配、off-by-one 和 block range。

### 4.2 数据结构

#### TxNumMeta

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

- `txIndex >= 0` 表示用户交易。
- `txIndex = -1` 表示系统阶段。
- `txId = null` 表示系统 tx。

#### BlockTxNumRange

```java
public class BlockTxNumRange {
  private final long blockNum;
  private final byte[] blockHash;
  private final long firstTxNum;
  private final long lastTxNum;
  private final int userTxCount;
  private final int systemTxCount;
}
```

#### ArchiveExecutionContext

```java
public class ArchiveExecutionContext {
  private final ThreadLocal<TxNumMeta> current = new ThreadLocal<>();

  public boolean active();

  public TxNumMeta current();

  void bind(TxNumMeta meta);

  void clear();
}
```

P0 用 `ThreadLocal` 合理，因为 canonical `pushBlock` 当前在 `synchronized (this)` 内推进。后续并行执行再改显式 context。

### 4.3 ArchiveTxNumIndex 接口

```java
public interface ArchiveTxNumIndex {
  long nextTxNum();

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

### 4.4 InMemoryArchiveTxNumIndex

初版字段：

```java
private long nextTxNum = 0L;
private PendingBlock pendingBlock;
private final Map<Long, BlockTxNumRange> blockRanges = new HashMap<>();
private final Map<WrappedByteArray, TxNumMeta> txIdIndex = new HashMap<>();
private final Map<Long, TxNumMeta> txNumIndex = new HashMap<>();
```

`PendingBlock`：

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

allocateUserTx:
  meta.txNum = nextTxNum++
  txIndex = loop index
  phase = USER_TX
  txId = tx.getTransactionId().getBytes()

allocateSystemTx:
  meta.txNum = nextTxNum++
  txIndex = -1
  phase = BLOCK_PREPARE or BLOCK_FINALIZE
  txId = null

completeBlock:
  persist pending metas into maps
  block range = [firstTxNum, nextTxNum - 1]

abortBlock:
  nextTxNum = pending.firstTxNum
  clear pending

unwindBlock:
  find block range
  delete txNumIndex and txIdIndex entries in range
  nextTxNum = range.firstTxNum
  delete block range
```

注意 empty block：

- 即使没有用户交易，也必须分配 `BLOCK_PREPARE` 和 `BLOCK_FINALIZE` txNum。
- 因此 block range 不为空。

### 4.5 DefaultArchiveService PR 2 行为

```java
@Component
public class DefaultArchiveService implements ArchiveService {
  private final ArchiveTxNumIndex txNumIndex;
  private final ArchiveExecutionContext executionContext;

  @Autowired
  public DefaultArchiveService(ArchiveTxNumIndex txNumIndex,
      ArchiveExecutionContext executionContext) {
    this.txNumIndex = txNumIndex;
    this.executionContext = executionContext;
  }

  @Override
  public void beginBlock(BlockCapsule block) {
    if (!isEnabled()) {
      return;
    }
    txNumIndex.beginBlock(block);
  }

  @Override
  public void beginUserTx(BlockCapsule block, TransactionCapsule tx, int txIndex) {
    if (!isEnabled()) {
      return;
    }
    TxNumMeta meta = txNumIndex.allocateUserTx(block, tx, txIndex);
    executionContext.bind(meta);
  }

  @Override
  public void endUserTx() {
    executionContext.clear();
  }
}
```

`endUserTx/endSystemTx` 即使 archive disabled 也可以 clear，防止异常残留。

### 4.6 Spring bean

由于 `CommonConfig` 已 `@ComponentScan(basePackages = "org.tron")`，可直接：

```java
@Component
public class ArchiveExecutionContext { ... }

@Component
public class InMemoryArchiveTxNumIndex implements ArchiveTxNumIndex { ... }

@Component
public class DefaultArchiveService implements ArchiveService { ... }
```

后续接持久化 index 时，可以保留接口，替换实现。

### 4.7 Manager 注入

在 `Manager` 字段区新增：

```java
@Autowired
private ArchiveService archiveService;
```

需要 import：

```java
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveService;
```

### 4.8 normal apply commit/abort hook

当前 normal apply：

```java
try (ISession tmpSession = revokingStore.buildSession()) {
  applyBlock(newBlock, txs);
  tmpSession.commit();
} catch (Throwable throwable) {
  ...
  throw throwable;
}
```

PR 2 修改为：

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
  clearSolidityContractTriggerCache(block.getNum());
  throw throwable;
}
```

对 `switchFork` 中 replay 新 fork 的 `try (ISession tmpSession...)` 也要同样加 begin/commit/abort。

注意：

- `beginBlock` 必须在 `applyBlock` 前。
- `commitBlock` 必须在 `tmpSession.commit()` 成功后。
- `abortBlock` 必须在 catch 里。

### 4.9 processBlock 阶段 hook

`processBlock()` 不能只包用户交易循环。当前源码在用户交易前后都有系统写：

```text
BLOCK_PREPARE:
  initCurrentBlockBalanceTrace(block)
  saveBlockEnergyUsage(0)
  preValidateTransactionSign(txs)
  accountStateCallBack.preExecute(block)

USER_TX:
  for transaction in block.getTransactions():
    rejectExchangeTransaction(transaction)
    transaction.setBlockNum(num)
    accountStateCallBack.preExeTrans()
    processTransaction(transaction, block)
    accountStateCallBack.exeTransFinish()

BLOCK_FINALIZE:
  transactionRetCapsule.addAllTransactionInfos(results)
  accountStateCallBack.executePushFinish()
  merkle save / block result
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
  block.setBloom(blockBloom)
```

推荐 PR2 直接按三段包裹，避免 PR3/PR4 接 Store write hook 时再重切边界。

#### BLOCK_PREPARE

在交易循环前增加：

```java
archiveService.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
try {
  chainBaseManager.getBalanceTraceStore().initCurrentBlockBalanceTrace(block);
  chainBaseManager.getDynamicPropertiesStore().saveBlockEnergyUsage(0);
  merkleContainer.resetCurrentMerkleTree();
  accountStateCallBack.preExecute(block);
} finally {
  archiveService.endSystemTx();
}
```

如果 `preValidateTransactionSign(txs)` 保持在 prepare 内，它也会占用 `BLOCK_PREPARE` txNum，但没有 Store write 也没关系；后续 write_count 可以是 0。

#### USER_TX

当前循环：

```java
for (TransactionCapsule transactionCapsule : block.getTransactions()) {
  ...
  accountStateCallBack.preExeTrans();
  TransactionInfo result = processTransaction(transactionCapsule, block);
  accountStateCallBack.exeTransFinish();
  ...
}
```

修改为：

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

`txIndex++` 应放在 finally 之后，保持实际处理顺序清晰。若 `processTransaction` 抛异常，整个 block abort，`abortBlock` 会回退 `nextTxNum`。

#### BLOCK_FINALIZE

交易循环后需要保证 empty block 也有 txNum。推荐完整包裹：

```java
archiveService.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
try {
  transactionRetCapsule.addAllTransactionInfos(results);
  accountStateCallBack.executePushFinish();
  ...
  payReward(block);
  ...
  updateDynamicProperties(block);
  ...
  block.setBloom(blockBloom);
} finally {
  archiveService.endSystemTx();
}
```

这样 PR3 接 Store hook 时不会漏 block finalize 写。若为降低 PR2 改动面临时只包 `accountStateCallBack.executePushFinish()`，必须在 PR3 前补齐，否则 block-end 历史状态和 root 都会缺写。

### 4.11 eraseBlock unwind

当前：

```java
BlockCapsule oldHeadBlock = ...
khaosDb.pop();
revokingStore.fastPop();
```

修改为：

```java
BlockCapsule oldHeadBlock = ...
khaosDb.pop();
revokingStore.fastPop();
archiveService.unwindBlock(oldHeadBlock);
```

P0 `unwindBlock` 对 archive disabled 是 no-op。

### 4.12 PR 2 测试

#### ArchiveTxNumIndexTest

纯 unit，不需要 Spring。

测试：

1. empty block 分配 `BLOCK_PREPARE` 和 `BLOCK_FINALIZE` 两个系统 txNum。
2. 三笔交易区块分配 `prepare=0, tx0=1, tx1=2, tx2=3, finalize=4`。
3. `findByTxId(tx0)` 返回 `txNum=1, txIndex=0`。
4. `findBlockRange(block)` 返回 `[0,4]`。
5. `abortBlock` 后 `nextTxNum` 回到 begin 前。
6. `unwindBlock` 删除 block range 和 txId index。

#### ArchiveManagerHookTest

继承 `BaseMethodTest`，通过测试配置文件开启 `storage.archive.enable=true`。不要用 `--archive`，S1/S2 不新增 archive CLI：

```java
storage.archive.enable = true
```

测试目标：

- 推一个包含多交易的 block 后，`ArchiveTxNumIndex` 中有 block range。
- empty block 也有 block range。
- `eraseBlock()` 后 block range 删除。

如果构造真实 block 成本过高，PR 2 可以先 spy `ArchiveService` 验证 hook 调用顺序，但最终必须补真实 apply 集成测试。

#### 默认关闭测试

一个 Spring 测试：

```text
archive disabled:
  ArchiveService.isEnabled() == false
  push/apply block 不产生 txNum range
```

## 5. PR 1/PR 2 不做的事

明确不做：

- 不接 `TronStoreWithRevoking.put/delete` write collector。
- 不写 temporal history。
- 不改 `eth_getBalance/eth_getCode/eth_getStorageAt`。
- 不实现 root/proof。
- 不做 cold segment。
- 不更改 `accountStateRoot` 或区块头。

这些进入 PR 3 之后。

## 6. 风险和防线

| 风险 | 防线 |
| --- | --- |
| archive disabled 仍影响主路径 | `isEnabled()` no-op，默认配置测试 |
| Spring 多个 ArchiveService bean | PR 1/2 只保留 `DefaultArchiveService` 一个实现 |
| CommonParameter 依赖 chainbase | 配置类放 common 模块 |
| empty block 没 txNum | PR 2 强制 `BLOCK_PREPARE` 和 `BLOCK_FINALIZE` txNum |
| txIndex 使用错误字段 | 只用 `processBlock` loop index，不用 `TransactionCapsule.order` |
| block apply 失败后 nextTxNum 前进 | catch 中 `archiveService.abortBlock()` |
| switch fork replay 没 begin/commit | normal apply 和 switch fork replay 两处都接 hook |
| eraseBlock 只回滚 revokingDB | `archiveService.unwindBlock(oldHeadBlock)` |
| 后续 Store hook 漏 finalize | PR 2 system tx 包住完整 finalize 区间 |

## 7. 代码审查清单

PR 1：

- `storage.archive.*` 默认值在 `reference.conf` 和 `config.conf` 中默认关闭。
- `CommonParameter` 没有引入 `chainbase` 类型。
- `ArchiveService` 方法都是 no-op，不触发 DB 写。
- `TronStoreWithRevoking.getDbName()` 返回真实 DB 名。
- 默认关闭测试覆盖。

PR 2：

- 所有 `beginUserTx` 都有 finally `endUserTx`。
- 所有 `beginSystemTx` 都有 finally `endSystemTx`。
- `commitBlock` 只在 `tmpSession.commit()` 后调用。
- catch 路径调用 `abortBlock`。
- `eraseBlock` 调用 `unwindBlock`。
- empty block 有 `BLOCK_PREPARE` 和 `BLOCK_FINALIZE` range。
- multi-tx block 的 txNum 与 block transaction order 一致。

## 8. 建议执行顺序

1. 先改配置和 `ArchiveService` no-op。
2. 跑 `Storage`/`ArgsArchiveConfigTest` 的 archive 配置用例。
3. 修 `TronStoreWithRevoking.getDbName()`。
4. 跑 `TronStoreWithRevokingDbNameTest` 和 `ChainbaseTest`。
5. 加 `ArchiveTxNumIndex` 内存实现。
6. 跑 `ArchiveTxNumIndexTest`。
7. 接 `Manager` hook。
8. 跑 `ArchiveManagerHookTest`。
9. 最后跑：

```bash
./gradlew :framework:test --tests '*Archive*'
./gradlew :framework:test --tests 'org.tron.core.db2.ChainbaseTest'
./gradlew :framework:test --tests 'org.tron.core.db.ManagerTest'
./gradlew lint
```
