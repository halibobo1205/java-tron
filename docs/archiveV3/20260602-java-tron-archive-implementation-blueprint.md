# java-tron Archive 状态树实现蓝图

日期：2026-06-02

> 2026-06-03 源码重校准：本文保留为旧蓝图，部分 java-tron 源码锚点基于 `a79693e450`，已不再匹配当前本地源码。当前权威基线是 `/Users/boson/IdeaProjects/java-tron` 的 `4e80f8ffa9a2`；该源码存在 `StorageConfig.java`、`reference.conf`，且精确冲突标记扫描无命中。编码主入口请看 [java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)，逐模块源码对照请看 [java-tron Archive：4e80 六模块源码对照细化](./20260603-java-tron-archive-4e80-six-modules-source-detail.md)。

关联总文档：[java-tron 交易级状态树支持：Erigon V2/V3 模型调研](./20260520-java-tron-archive-state-erigon-v2-v3-research.md)

关联路线图：[java-tron Archive 状态树：Erigon 源码深挖后的落地路线图](./20260601-java-tron-archive-erigon-source-synthesis-implementation-roadmap.md)

端到端实现矩阵：[java-tron Archive 端到端实现矩阵与 PR 执行队列](./20260602-java-tron-archive-end-to-end-implementation-matrix.md)

六模块本地源码对照实现总表：[java-tron Archive：六个模块本地源码对照实现总表](./20260602-java-tron-archive-module-by-module-java-tron-implementation-map.md)

S1/S2 编码执行包：[java-tron Archive S1/S2 编码执行包](./20260602-java-tron-archive-s1-s2-coding-packet.md)

S3 ArchiveDomainRegistry 编码执行包：[java-tron Archive S3：ArchiveDomainRegistry 编码执行包](./20260602-java-tron-archive-s3-domain-registry-coding-packet.md)

S4 ArchiveWriteCollector 编码执行包：[java-tron Archive S4：ArchiveWriteCollector 编码执行包](./20260602-java-tron-archive-s4-write-collector-coding-packet.md)

S5 Contract Storage semantic hook 编码执行包：[java-tron Archive S5：Contract Storage Semantic Hook 编码执行包](./20260602-java-tron-archive-s5-contract-storage-semantic-coding-packet.md)

S6 ArchiveRawStore + temporal codecs 编码执行包：[java-tron Archive S6：ArchiveRawStore + Temporal Codecs 编码执行包](./20260602-java-tron-archive-s6-raw-store-temporal-codecs-coding-packet.md)

S7 Temporal commit/unwind/startup 编码执行包：[java-tron Archive S7：Temporal Commit / GetAsOf / Unwind / Startup 编码执行包](./20260602-java-tron-archive-s7-temporal-commit-unwind-startup-coding-packet.md)

S8 ArchiveStateReader core 编码执行包：[java-tron Archive S8：ArchiveStateReader Core 编码执行包](./20260602-java-tron-archive-s8-state-reader-core-coding-packet.md)

S9 JSON-RPC historical getters 编码执行包：[java-tron Archive S9：JSON-RPC Historical Getters 编码执行包](./20260602-java-tron-archive-s9-jsonrpc-historical-getters-coding-packet.md)

S10 Sparse Merkle tree core + root codecs 编码执行包：[java-tron Archive S10：Sparse Merkle Tree Core + Root Codecs 编码执行包](./20260602-java-tron-archive-s10-sparse-merkle-root-codecs-coding-packet.md)

S11 CommitmentBuilder integration + rebuild verifier 编码执行包：[java-tron Archive S11：CommitmentBuilder Integration + Rebuild Verifier 编码执行包](./20260602-java-tron-archive-s11-commitment-builder-integration-rebuild-coding-packet.md)

PR1/PR2 代码级规格：[java-tron Archive PR1/PR2 代码级实现规格](./20260602-java-tron-archive-pr1-pr2-implementation-spec.md)

PR1/PR2 逐文件 Patch 清单：[java-tron Archive PR1/PR2 逐文件 Patch 清单](./20260602-java-tron-archive-pr1-pr2-patch-checklist.md)

模块 01 逐文件 Patch 清单：[java-tron Archive 模块 01：ArchiveTxNumIndex 逐文件 Patch 清单](./20260602-java-tron-archive-module-01-txnum-index-patch-checklist.md)

模块 02 逐文件 Patch 清单：[java-tron Archive 模块 02：ArchiveDomainRegistry 逐文件 Patch 清单](./20260602-java-tron-archive-module-02-domain-registry-patch-checklist.md)

模块 03 逐文件 Patch 清单：[java-tron Archive 模块 03：ArchiveWriteCollector 逐文件 Patch 清单](./20260602-java-tron-archive-module-03-write-collector-patch-checklist.md)

模块 04 逐文件 Patch 清单：[java-tron Archive 模块 04：ArchiveTemporalStore 逐文件 Patch 清单](./20260602-java-tron-archive-module-04-temporal-store-patch-checklist.md)

模块 05 逐文件 Patch 清单：[java-tron Archive 模块 05：ArchiveStateReader 逐文件 Patch 清单](./20260602-java-tron-archive-module-05-state-reader-patch-checklist.md)

模块 06 逐文件 Patch 清单：[java-tron Archive 模块 06：CommitmentBuilder 逐文件 Patch 清单](./20260602-java-tron-archive-module-06-commitment-builder-patch-checklist.md)

PR3/PR4 WriteCollector 代码级规格：[java-tron Archive PR3/PR4 WriteCollector 代码级实现规格](./20260602-java-tron-archive-pr3-pr4-write-collector-implementation-spec.md)

PR5 TemporalStore 代码级规格：[java-tron Archive PR5 TemporalStore 代码级实现规格](./20260602-java-tron-archive-pr5-temporal-store-implementation-spec.md)

PR6 StateReader/JSON-RPC 代码级规格：[java-tron Archive PR6 StateReader/JSON-RPC 代码级实现规格](./20260602-java-tron-archive-pr6-state-reader-jsonrpc-implementation-spec.md)

PR7 CommitmentBuilder 代码级规格：[java-tron Archive PR7 CommitmentBuilder 代码级实现规格](./20260602-java-tron-archive-pr7-commitment-builder-implementation-spec.md)

PR8 historical eth_call 当前 4e80 编码入口：[java-tron Archive S12/S13：historical eth_call 4e80 编码执行包](./20260604-java-tron-archive-s12-s13-historical-eth-call-4e80-coding-packet.md)

PR8 historical eth_call 历史规格：[java-tron Archive PR8 historical eth_call 代码级实现规格](./20260602-java-tron-archive-pr8-historical-eth-call-implementation-spec.md)

PR9 Proof/Debug API 当前 4e80 编码入口：[java-tron Archive S14：proof/debug API 4e80 编码执行包](./20260604-java-tron-archive-s14-proof-debug-api-4e80-coding-packet.md)

PR9 Proof/Debug API 历史规格：[java-tron Archive PR9 Proof/Debug API 代码级实现规格](./20260602-java-tron-archive-pr9-proof-debug-api-implementation-spec.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：本文正文原按本地 `a79693e450` 复核六个模块源码对照，属于历史蓝图；当前权威基线是 `4e80f8ffa9a2`，且源码已经存在 `common/src/main/resources/reference.conf` 和 `StorageConfig.java`。

## 1. 目标

本文把前面的 Erigon 调研、六个模块设计、java-tron 源码对照收敛成可实施的 java-tron patch 蓝图。目标不是一次性覆盖 issue 中所有 DB，而是先做一个能证明交易级 archive 状态闭环的 PoC：

```text
canonical block apply
  -> txNum
  -> write-set
  -> temporal history
  -> historical state reader
  -> block-end sidecar root
```

P0 验收目标：

1. 多交易区块内可以查询 `TX_BEFORE/TX_AFTER` 状态。
2. `eth_getBalance`、`eth_getCode`、`eth_getStorageAt` 能按历史 block number 读取。
3. Archive 数据跟随 normal apply、switch fork、erase block 回退。
4. block-end archive sidecar root 可重放、可校验。
5. 默认关闭，不影响普通 fullnode 行为。

## 2. 当前源码事实

### 2.1 模块依赖

java-tron Gradle 模块关系：

```text
common
protocol
crypto
chainbase -> protocol/common/crypto
actuator  -> chainbase/protocol/crypto
framework -> chainbase/actuator/consensus/protocol
```

源码证据：

| 文件 | 事实 |
| --- | --- |
| `/Users/boson/IdeaProjects/java-tron/settings.gradle` | 包含 `framework`、`chainbase`、`protocol`、`actuator`、`common` 等模块 |
| `/Users/boson/IdeaProjects/java-tron/chainbase/build.gradle` | `chainbase` 依赖 `protocol/common/crypto` |
| `/Users/boson/IdeaProjects/java-tron/actuator/build.gradle` | `actuator` 依赖 `chainbase` |
| `/Users/boson/IdeaProjects/java-tron/framework/build.gradle` | `framework` 依赖 `chainbase/actuator/consensus` |

结论：

```text
Archive 核心接口和状态结构放 chainbase。
RPC/Manager 接入放 framework。
VM semantic hook 放 actuator，但只能依赖 chainbase 中的 Archive API。
配置基础字段放 common/framework 的现有 Args/CommonParameter 路径。
```

这样不会引入 `chainbase -> framework` 或 `actuator -> framework` 的反向依赖。

### 2.2 canonical apply 和 session

关键源码：

| 位置 | 作用 |
| --- | --- |
| `framework/src/main/java/org/tron/core/db/Manager.java:1261-1267` | `pushBlock` |
| `Manager.java:1374` | normal block apply 外层 `try (ISession tmpSession = revokingStore.buildSession())` |
| `Manager.java:1375-1376` | `applyBlock(newBlock, txs)` 后 `tmpSession.commit()` |
| `Manager.java:1062-1076` | `applyBlock(block, txs)` |
| `Manager.java:1824` | `processBlock(block, txs)` |
| `Manager.java:1094` | `switchFork` |
| `Manager.java:1017-1024` | `eraseBlock` |
| `chainbase/src/main/java/org/tron/core/db2/core/SnapshotManager.java:119` | `buildSession` |
| `SnapshotManager.java:170` | `merge` |
| `SnapshotManager.java:567` | `Session` |
| `SnapshotManager.java:598` | `Session.merge()` |
| `SnapshotManager.java:583-585` | `Session.commit()` |

关键实现约束：

- `processBlock/applyBlock` 在外层 revoking session 内执行。
- `tmpSession.commit()` 在 `applyBlock` 返回后执行。
- Archive sidecar 不能在 Store hook 时直接持久化，否则 block 后续失败会造成 archive ahead。
- P0 应在内存中构建 `PendingArchiveBlock`，只有 `tmpSession.commit()` 成功后才 flush sidecar DB。

## 3. 包结构

### 3.1 chainbase 新增包

建议新增：

```text
chainbase/src/main/java/org/tron/core/archive/
  ArchiveService.java
  ArchiveConfig.java
  ArchivePhase.java
  StatePoint.java
  ArchiveProgress.java

chainbase/src/main/java/org/tron/core/archive/domain/
  ArchiveDomain.java
  ArchiveDomainDescriptor.java
  ArchiveDomainRegistry.java
  CanonicalKeyCodec.java
  CanonicalValueCodec.java
  RootPolicy.java
  HistoryPolicy.java

chainbase/src/main/java/org/tron/core/archive/txnum/
  ArchiveTxNumIndex.java
  TxNumAllocator.java
  TxNumMeta.java
  BlockTxNumRange.java

chainbase/src/main/java/org/tron/core/archive/collector/
  ArchiveExecutionContext.java
  ArchiveWriteCollector.java
  DomainWrite.java
  TxWriteSet.java
  BlockWriteSet.java
  StoreWriteEvent.java

chainbase/src/main/java/org/tron/core/archive/store/
  ArchiveTemporalStore.java
  ArchiveKvStore.java
  ArchiveKeyCodec.java
  ArchiveTable.java

chainbase/src/main/java/org/tron/core/archive/reader/
  ArchiveStateReader.java
  ArchiveStateReaderFactory.java
  ArchiveReaderException.java

chainbase/src/main/java/org/tron/core/archive/commitment/
  CommitmentBuilder.java
  ArchiveRootStore.java
  RootRecord.java
```

放在 `chainbase` 的原因：

- `TronStoreWithRevoking` 在 `chainbase`，Store-level hook 需要直接调用 collector。
- `RepositoryImpl` 在 `actuator`，只能依赖 `chainbase`。
- `Manager` 和 JSON-RPC 在 `framework`，也能依赖 `chainbase`。

### 3.2 framework 接入包

建议新增：

```text
framework/src/main/java/org/tron/core/archive/
  ArchiveBlockApplyHook.java
  ArchiveStatePointResolver.java

framework/src/main/java/org/tron/core/services/jsonrpc/archive/
  ArchiveJsonRpcStateAdapter.java
```

职责：

- `ArchiveBlockApplyHook` 包装 `Manager` 中 block apply、commit、abort、unwind。
- `ArchiveStatePointResolver` 把 JSON-RPC block tag/quantity 解析成 `StatePoint`；EIP-1898 object/hash selector 留给 PR8。
- `ArchiveJsonRpcStateAdapter` 给 `TronJsonRpcImpl` 提供历史 state 方法。

### 3.3 actuator 接入点

不新增大量类，先在现有类中调用 chainbase API：

```text
actuator/src/main/java/org/tron/core/vm/repository/RepositoryImpl.java
actuator/src/main/java/org/tron/core/vm/program/Storage.java
```

职责：

- `Storage.commit` 发出 storage semantic write；`RepositoryImpl.putStorageValue` 不能输出最终 `DomainWrite`。
- `RepositoryImpl.saveCode` 可发出 code semantic write，但 P0 可以先靠 Store hook 捕获 code bytes。

## 4. 配置

### 4.1 config.conf + Storage runtime config

历史 `a79693e450` 结论认为当前源码没有 `common/src/main/resources/reference.conf` 和 `StorageConfig.java`；该结论已经失效。当前 `4e80f8ffa9a2` 的 archive 配置应以 [4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md) 和 [S1/S2 4e80 编码执行包](./20260603-java-tron-archive-s1-s2-4e80-coding-packet.md) 为准，挂到：

```text
reference.conf
  -> StorageConfig.fromConfig(config)
  -> Args.applyStorageConfig(StorageConfig)
  -> CommonParameter.storage.archive
```

旧蓝图中的历史落点如下，只作为过期背景：

| 文件 | 处理 |
| --- | --- |
| `common/src/main/java/org/tron/core/config/args/Storage.java:48` | 在当前 `Storage` 配置模型中新增 `ArchiveConfig archive` |
| `Storage.java:405-470` | 增加 `storage.archive.*` 解析 helper |
| `framework/src/main/java/org/tron/core/config/args/Args.java:516-564` | 在手工构造 `CommonParameter.storage` 的段落填充 archive config |
| `common/src/main/java/org/tron/common/parameter/CommonParameter.java:479` | 继续通过 `CommonParameter.storage` 暴露 runtime config |
| `framework/src/main/resources/config.conf` | 添加用户可见默认值并保持 `storage.archive.enable=false` |

`config.conf` 建议新增：

```hocon
storage {
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
}
```

默认关闭，避免影响普通节点。

### 4.2 CommonParameter

在 `common/src/main/java/org/tron/common/parameter/CommonParameter.java` 增加：

```java
public boolean archiveEnabled = false;
public Archive archive = new Archive();

public static class Archive {
  private boolean enable;
  private String dbDirectory;
  private boolean temporalEnable;
  private boolean commitmentEnable;
  private boolean persistTxRoots;
  private String coverage;
  private boolean warnUnclassifiedStoreWrites;
}
```

实际实现可参考现有 `Storage` 配置类风格，不必强制内嵌类。

### 4.3 Args

S1/S2 编码执行包已经把配置策略收敛为 config-only：优先只支持 `storage.archive.*` 配置文件，不新增 `--archive` 系列 CLI。原因是当前 `CLIParameter` 中 storage 类 CLI 已整体标为 deprecated，新增 archive CLI 会制造第二套配置语义。

如果评审后续明确要求 CLI 兼容，再单独增加：

```text
--archive                         -> storage.archive.enable
--archive-db-directory            -> storage.archive.db.directory
--archive-commitment-enable       -> storage.archive.commitment.enable
--archive-persist-tx-roots        -> storage.archive.commitment.persistTxRoots
```

并同步 `CLIParameter`、`Args.DEPRECATED_CLI_TO_CONFIG` 和 CLI assigned override。不要把这部分放进 S1 的必做范围。

实现原则：

- `archive.enable=false` 时所有 Archive hook 是 no-op。
- `archive.enable=true` 且 DB 初始化失败，节点应启动失败，不应静默降级。
- `commitment.enable=false` 时仍可做 txNum/write-set/history/reader。

## 5. DB schema

### 5.1 DB 划分

P0 只有一个 physical sidecar DB：

```text
archive
```

内部用 table prefix 拆成逻辑表；这些不是独立 physical DB：

| 逻辑表 | 用途 |
| --- | --- |
| `META` | schema/progress/registry checksum |
| `TXNUM_BLOCK` | blockNum -> block txNum range |
| `TXNUM_BY_TXID` | txId -> txNum |
| `TXNUM_META` | txNum -> tx metadata |
| `LATEST` | domain/key current value |
| `HISTORY` | domain/key/txNum before-value |
| `CHANGESET` | txNum/domain/key changed marker |
| `0x30+ root tables` | PR7 commitment/root rows |

PR5 的实现规格进一步收敛为：P0 先使用一个 physical `archive` DB，通过 table prefix 承载 `meta/txnum/state` 逻辑表，保证 `state + txnum + progress` 能在单个 batch 内原子提交；PR7 已继续沿用这个约束，不拆独立 physical `archive-root` DB。

PR7 的实现规格进一步收敛为：P0 继续使用同一个 physical `archive` DB，通过 `0x30+` table prefix 承载 commitment meta、block/domain root、content-addressed root node、`ROOT_CURRENT` 和 `ROOT_LEAF` metadata，确保 temporal rows 与 root rows 可以同 batch 提交。`archive-root` 独立 physical DB 继续延后。

不要挂到 `SnapshotManager` 的短期 revoking 链里；Archive 自己用 pending block + commit/unwind 保证一致性。

### 5.2 key 编码规则

统一使用：

```text
u8 tablePrefix
u16 domainId, only for domain-scoped tables
u64 big-endian blockNum/txNum
u32 big-endian length
raw bytes
```

`ByteArray.fromLong` 在 java-tron 内已有广泛使用，可复用，但实现前要用单测固定 lexicographic order。

### 5.3 archive-meta

```text
0x01 "schemaVersion"       -> u32
0x01 "appliedBlockNum"     -> u64
0x01 "appliedBlockHash"    -> bytes
0x01 "nextTxNum"           -> u64
0x01 "coverage"            -> string
```

### 5.4 txnum logical tables

```text
0x10 | blockNum_u64                -> BlockTxNumRange
0x11 | txIdLen_u32 | txId          -> txNum_u64
0x12 | txNum_u64                   -> TxNumMeta
```

### 5.5 temporal state logical tables

```text
0x20 | domainId_u16 | keyLen_u32 | key              -> lastTxNum_u64 | nullableValue
0x21 | domainId_u16 | keyLen_u32 | key | txNum_u64  -> before nullableValue
0x22 | txNum_u64 | domainId_u16 | keyLen_u32 | key  -> change marker
```

`GetAsOf(domain,key,asOfTxNum)`：

```text
seek first 0x21|domainId|keyLen|key|txNum where txNum >= asOfTxNum
if found with same prefix:
    return beforeValue
else:
    return latest
```

### 5.6 archive-root logical tables

```text
0x30 | key                         -> RootMetaRecord
0x31 | blockNum                    -> RootRecord
0x32 | u16 domainId | blockNum     -> DomainRootRecord
0x33 | txNum                       -> RootRecord, optional
0x34 | algorithmId | treeKind | u16 domainId | nodeHash -> NodeRecord
0x35 | algorithmId | treeKind | u16 domainId          -> CurrentRootRecord
0x36 | algorithmId | u16 domainId | keyLen | key       -> LeafRecord
```

P0 只要求 block-end root，tx root on-demand。所有 root 表仍写入同一个 physical `archive` DB；不要在 PR7 拆出独立 `archive-root` DB。

## 6. 核心接口

### 6.1 ArchiveService

```java
public interface ArchiveService {
  boolean isEnabled();

  void beginBlock(BlockCapsule block);

  void beginUserTx(BlockCapsule block, TransactionCapsule tx, int txIndex);

  void endUserTx();

  void beginSystemTx(BlockCapsule block, ArchivePhase phase);

  void endSystemTx();

  boolean shouldCollectStoreWrites();

  void onStoreWrite(StoreWriteEvent event);

  void onSemanticWrite(SemanticStoreWrite write);

  void commitBlock();

  void abortBlock();

  void unwindBlock(long blockNum, byte[] blockHash);
}
```

### 6.2 ArchiveExecutionContext

```java
public final class ArchiveExecutionContext {
  public boolean active();
  public long blockNum();
  public byte[] blockHash();
  public long txNum();
  public int txIndex();
  public ArchivePhase phase();
  public byte[] txId();
}
```

实现建议：

- P0 可以用 `ThreadLocal`，因为 `Manager.pushBlock` 是 synchronized，canonical apply 主路径单线程。
- 后续如果并行执行交易，改成显式 context 传递。
- `try/finally` 必须 clear，避免 pending/constant call 污染。

### 6.3 ArchivePhase

```java
public enum ArchivePhase {
  USER_TX,
  BLOCK_FINALIZE
}
```

P0 不拆 `payReward/proposal/consensus/dynamicProperties`，统一进入 `BLOCK_FINALIZE`。P1 再拆：

```text
BLOCK_REWARD
MAINTENANCE
CONSENSUS_APPLY
DYNAMIC_PROPERTIES
BLOOM_INDEX
```

### 6.4 ArchiveDomainRegistry

P0 domain：

```java
ACCOUNT(1, "account")
CONTRACT(2, "contract")
CODE(3, "code")
CONTRACT_STORAGE(4, "storage-row")
DYNAMIC_PROPERTIES(5, "properties")
```

Descriptor 字段：

```java
record ArchiveDomainDescriptor(
    ArchiveDomain domain,
    String sourceDbName,
    HistoryPolicy historyPolicy,
    RootPolicy rootPolicy,
    CanonicalKeyCodec keyCodec,
    CanonicalValueCodec valueCodec,
    boolean semanticOnly
) {}
```

`CONTRACT_STORAGE` 建议 `RawHookMode.SEMANTIC_ONLY`，只接受 `onSemanticWrite(SemanticStoreWrite.contractStorage(...))`，避免只拿到 physical row key。

## 7. Manager 接入

### 7.1 normal apply

当前源码：

```java
try (ISession tmpSession = revokingStore.buildSession()) {
  applyBlock(newBlock, txs);
  tmpSession.commit();
}
```

建议改为：

```java
archiveService.beginBlock(newBlock);
try (ISession tmpSession = revokingStore.buildSession()) {
  applyBlock(newBlock, txs);
  tmpSession.commit();
  archiveService.commitBlock();
} catch (Throwable throwable) {
  archiveService.abortBlock();
  throw throwable;
}
```

注意：

- `beginBlock` 可以放在 session 内或外，但 pending write 不落盘。
- `commitBlock` 必须在 `tmpSession.commit()` 成功之后。
- `abortBlock` 必须覆盖 `processBlock/applyBlock/tmpSession.commit` 任一阶段失败。

### 7.2 processBlock 交易边界

当前源码在 `Manager.java:1858` 遍历交易，并在 `Manager.java:1870-1872` 用 account-state callback 包围 `processTransaction`。建议：

```java
int txIndex = 0;
for (TransactionCapsule transactionCapsule : block.getTransactions()) {
  archiveService.beginUserTx(block, transactionCapsule, txIndex);
  try {
    accountStateCallBack.preExeTrans();
    TransactionInfo result = processTransaction(transactionCapsule, block);
    accountStateCallBack.exeTransFinish();
  } finally {
    archiveService.endUserTx();
  }
  txIndex++;
}
```

### 7.3 block finalize

当前 `processBlock` 在交易循环后执行：

```text
accountStateCallBack.executePushFinish()
EnergyProcessor update
payReward(block)
proposalController.processProposals()
consensus.applyBlock(block)
updateTransHashCache
updateRecentBlock
updateRecentTransaction
updateDynamicProperties
section bloom write
```

P0 建议：

```java
archiveService.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
try {
  accountStateCallBack.executePushFinish();
  ...
  updateDynamicProperties(block);
} finally {
  archiveService.endSystemTx();
}
```

是否把 `updateRecentBlock/updateRecentTransaction/section bloom` 纳入 domain root 由 Registry 决定。它们一般是索引/cache，不应进入 root，但可以被 collector 诊断到。

### 7.4 eraseBlock / switchFork

当前 `eraseBlock`：

```java
oldHeadBlock = chainBaseManager.getBlockById(latestHash)
khaosDb.pop()
revokingStore.fastPop()
```

建议：

```java
BlockCapsule oldHeadBlock = ...
khaosDb.pop();
revokingStore.fastPop();
archiveService.unwindBlock(oldHeadBlock.getNum(), oldHeadBlock.getBlockId().getBytes());
```

要求：

- `unwindBlock` 校验 `archive_txnum_by_block` 的 block hash。
- 如果 hash 不匹配，Archive 必须进入 repair-needed 状态，不能静默继续。
- switch fork 中所有 `eraseBlock()` 都会触发 archive unwind。

### 7.5 崩溃修复

由于 java-tron 当前 canonical DB 和 sidecar archive DB 没有跨 DB 原子事务，启动时必须校验：

```text
archive.appliedBlockNum/hash == chain latest block num/hash
```

如果 archive ahead：

```text
unwind archive to chain latest
```

如果 archive behind：

```text
进入 read-only gap 或触发 backfill/replay
```

P0 可以先拒绝启动并提示 repair 命令；P1 再自动 backfill。

## 8. Store hook

### 8.1 TronStoreWithRevoking

当前源码：

| 位置 | 事实 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java:78` | `getDbName()` 当前返回 `null` |
| `TronStoreWithRevoking.java:88-93` | `put(byte[] key, T item)` |
| `TronStoreWithRevoking.java:97-98` | `delete(byte[] key)` |

必须先修复：

```java
@Override
public String getDbName() {
  return db.getDbName();
}
```

然后接入 collector：

```java
@Autowired(required = false)
private ArchiveService archiveService;

@Override
public void put(byte[] key, T item) {
  if (Objects.isNull(key) || Objects.isNull(item)) {
    return;
  }
  byte[] after = item.getData();
  if (archiveService != null && archiveService.shouldCollectStoreWrites()) {
    byte[] before = revokingDB.getUnchecked(key);
    archiveService.onStoreWrite(StoreWriteEvent.put(getDbName(), key, before, after));
  }
  revokingDB.put(key, after);
}

@Override
public void delete(byte[] key) {
  if (archiveService != null && archiveService.shouldCollectStoreWrites()) {
    byte[] before = revokingDB.getUnchecked(key);
    archiveService.onStoreWrite(StoreWriteEvent.delete(getDbName(), key, before));
  }
  revokingDB.delete(key);
}
```

### 8.2 ContractStore 特例

`ContractStore.put` 会清空 ABI 后直接 `revokingDB.put`。P0 需要改成最终仍走统一 hook，避免 `CONTRACT` domain 漏采。

原则：

```text
Archive 记录实际落盘 bytes，不记录调用方传入的原始 SmartContract。
```

### 8.3 Storage semantic hook

当前 storage physical key 来自：

| 位置 | 事实 |
| --- | --- |
| `actuator/src/main/java/org/tron/core/vm/program/Storage.java:46` | `compose(key, addrHash)` |
| `Storage.java:96` | `commit()` |
| `Storage.java:100` | zero value -> `store.delete(rowKey)` |
| `Storage.java:102` | non-zero -> `store.put(rowKey,row)` |

建议在 `Storage.commit` 中补：

```java
archiveService.onSemanticWrite(
    SemanticStoreWrite.contractStorage(
        address, logicalSlot, before, after, physicalKey, contractVersion));
```

如果 `Storage` 当前没有保存原始 address/logical slot，需要在 `Storage.put(DataWord key, DataWord value)` 的 row cache 中保留 slot 元数据。不要在 `RepositoryImpl.putStorageValue(address,key,value)` 直接输出最终 `DomainWrite`，否则 child repository 或 VM revert 的中间态可能污染 archive；该方法最多只能记录 intent，并且必须等 root `Storage.commit()` 时确认。

P0 推荐直接在 `Storage.commit` 输出 `SemanticStoreWrite.contractStorage(...)`。`RepositoryImpl.putStorageValue` 和 `Storage.put` 都只代表 cache/intent，不能作为最终 archive write 来源。

## 9. TemporalStore 实现

### 9.1 applyBlock

```java
void applyBlock(BlockWriteSet blockWriteSet) {
  for (TxWriteSet tx : blockWriteSet.txWrites()) {
    txNumIndex.putTx(tx.meta());
    for (DomainWrite write : tx.writes()) {
      temporalStore.putHistory(write.domain(), write.key(), tx.txNum(), write.before());
      temporalStore.putChange(tx.txNum(), write.domain(), write.key());
      temporalStore.putLatest(write.domain(), write.key(), write.after());
    }
  }
  txNumIndex.putBlockRange(blockRange);
  meta.putProgress(blockNum, blockHash, nextTxNum);
}
```

注意顺序：

1. 先写 history/change/latest。
2. 再写 txNum block range。
3. 最后写 progress。

启动恢复时，以 progress 为权威。

### 9.2 getAsOf

```java
Optional<byte[]> getAsOf(ArchiveDomain domain, byte[] key, long asOfTxNum) {
  Optional<HistoryEntry> next = history.firstChangeAtOrAfter(domain, key, asOfTxNum);
  if (next.isPresent()) {
    return decodeTombstone(next.get().beforeValue());
  }
  return latest.get(domain, key);
}
```

### 9.3 unwindBlock

```java
BlockTxNumRange range = txNumIndex.getBlockRange(blockNum);
for (long txNum = range.lastTxNum(); txNum >= range.firstTxNum(); txNum--) {
  for (ChangedKey key : temporalStore.changedKeys(txNum)) {
    byte[] before = temporalStore.getHistory(key.domain(), key.key(), txNum);
    temporalStore.putLatest(key.domain(), key.key(), before);
    temporalStore.deleteHistory(key.domain(), key.key(), txNum);
    temporalStore.deleteChange(txNum, key.domain(), key.key());
  }
  txNumIndex.deleteTxMeta(txNum);
}
txNumIndex.deleteBlockRange(blockNum);
meta.rewindToPreviousBlock();
```

## 10. StateReader 接入

### 10.1 reader factory

```java
ArchiveStateReader reader = archiveStateReaderFactory.at(StatePoint.blockEnd(blockNum));
```

Reader 负责：

- domain codec -> capsule/protobuf。
- tombstone -> RPC 默认值。
- storage missing -> zero word。
- account missing -> null/zero balance。

### 10.2 JSON-RPC

替换点：

| RPC | 当前位置 | 改造 |
| --- | --- | --- |
| `eth_getBalance` | `TronJsonRpcImpl.java:457` | 非 latest 走 ArchiveStateReader |
| `eth_getStorageAt` | `TronJsonRpcImpl.java:611` | 非 latest 走 ArchiveStateReader |
| `eth_getCode` | `TronJsonRpcImpl.java:635` | 非 latest 走 ArchiveStateReader |
| `eth_call` | `TronJsonRpcImpl.java:1001` | PR8 使用 ArchiveRepositoryAdapter + historical VM context |

不要简单删除 `requireLatestBlockTag`。正确方式：

```text
latest -> existing Wallet path
historical -> archive path
archive disabled -> clear error
```

## 11. CommitmentBuilder

P0 只做 block-end sidecar root：

```text
blockNum -> globalRoot
domainId -> domainRoot
coverage = TVM_STATE_ONLY
schemaVersion = 1
```

输入：

```text
BlockWriteSet after values
```

输出：

```text
RootRecord(blockNum, blockHash, globalRoot, domainRoots, coverage, schemaVersion)
```

实现策略：

- PR7 采用 content-addressed binary sparse Merkle tree，不直接进入共识。
- `TrieImpl` 位于 `framework`，PR7 不从 `chainbase` 直接复用它；未来如需复用，应先做独立 trie 包下沉重构。
- temporal rows 与 root rows 继续写入同一个 physical `archive` DB batch。
- root record 必须标记 `ARCHIVE_SIDECAR`，不能冒充 `accountStateRoot`。

## 12. PR 拆分

### PR 1：配置和 no-op 框架

内容：

- `storage.archive.*` 配置。
- `ArchiveService` no-op 实现。
- `ArchiveExecutionContext`。
- `TronStoreWithRevoking.getDbName()` 修复。

测试：

- 默认关闭时现有 Store put/delete 行为不变。
- 配置解析单测。

### PR 2：TxNumIndex

内容：

- `ArchiveTxNumIndex`。
- `Manager.processBlock` begin/end tx hook。
- `BLOCK_FINALIZE` phase。
- in-memory txNum index；PR5 再持久化到 single physical `archive` DB 的 txnum logical tables。

测试：

- empty block。
- multi-tx block。
- txId -> txNum。
- block range。

### PR 3：WriteCollector P0

内容：

- Store-level put/delete hook。
- `ACCOUNT/CONTRACT/CODE/DYNAMIC_PROPERTIES` raw domain。
- same-key-in-tx 压缩。
- unclassified Store diagnostics。

测试：

- TransferActuator 账户写。
- Contract deploy code/contract 写。
- block finalize dynamic properties 写。

### PR 4：CONTRACT_STORAGE semantic hook

内容：

- `RepositoryImpl/Storage` semantic hook。
- logical slot key。
- zero storage tombstone。

测试：

- storage write/read history。
- storage zero delete。
- same tx multi-write slot。

### PR 5：TemporalStore

内容：

- latest/history/changeset。
- `GetAsOf`。
- apply/unwind。
- progress meta。

测试：

- before-value chain。
- tx-level `asOfTxNum`。
- unwind block。
- restart progress check。

### PR 6：StateReader + JSON-RPC block history

内容：

- `ArchiveStateReaderFactory`。
- historical `eth_getBalance`。
- historical `eth_getCode`。
- historical `eth_getStorageAt`。
- `ArchiveStatePointResolver` 负责把 `latest/earliest/finalized/quantity` 转成 `StatePoint`。
- `eth_call` historical 继续延后到 PR8，不在 PR6 中静默退化成 latest。

测试：

- block N/N+1 balance。
- code before/after deploy。
- storage before/after write。
- archive disabled error。

### PR 7：CommitmentBuilder block root

内容：

- block-end sidecar root。
- root records。
- rebuild from latest/history.
- content-addressed sparse Merkle nodes。
- temporal rows 与 root rows 同 batch。
- hot unwind 恢复 `ROOT_CURRENT` 和 `ROOT_LEAF` metadata，不写共识 `accountStateRoot`。

测试：

- incremental root == rebuild root。
- reorg root unwind。
- root deterministic replay。

### PR 8：historical eth_call

内容：

- `ArchiveRepositoryAdapter`。
- `eth_call` historical block。
- overlay isolation。
- `DynamicPropertiesView` 和 `ArchiveDynamicPropertiesView`。
- `ConfigLoader.load(DynamicPropertiesView)`。
- `VMActuator` constant-call root repository 注入点。
- `ArchiveEthCallExecutor`。
- object block 参数不再校验后强制 latest。
- P0 只支持 historical `TriggerSmartContract` 合约调用；contract creation、transfer-only call、estimateGas、traceCall 延后。

测试：

- contract reads historical storage。
- call does not mutate archive/latest Store。
- historical dynamic properties/VMConfig 使用请求高度。
- `block.number` / `block.timestamp` 使用 historical block context。
- missing domain 返回 explicit unsupported，不 fallback latest。

### PR 9：Proof/Debug API

内容：

- archive-native `debug_getArchiveRoot`。
- archive-native `debug_getArchiveProof`。
- archive-native `debug_verifyArchiveProof`。
- tx-level root on-demand replay。
- domain proof + global domain proof。
- 默认关闭的 `debug_traceCall` trace capture。
- 不实现 Ethereum-compatible `eth_getProof`，避免把 TRON archive sidecar root 伪装成 Ethereum MPT proof。

测试：

- block-end root 查询。
- account/storage existence proof。
- storage zero/missing non-existence proof。
- `TX_BEFORE/TX_AFTER` on-demand root 区分。
- proof verifier 离线验证。
- debug API 默认关闭。
- traceCall 不写 `vm_trace` 文件。

## 13. 最小测试矩阵

### 13.1 Unit

```text
ArchiveKeyCodecTest
ArchiveTxNumIndexTest
ArchiveDomainRegistryTest
ArchiveWriteCollectorTest
ArchiveTemporalStoreTest
ArchiveStateReaderTest
CommitmentBuilderTest
ArchiveEthCallExecutorTest
ArchiveProofServiceTest
ArchiveProofVerifierTest
```

### 13.2 Integration

建议放在 `framework/src/test/java/org/tron/core/archive/`：

```text
ArchiveBlockApplyTest
ArchiveReorgTest
ArchiveJsonRpcStateTest
ArchiveContractStorageTest
ArchiveCommitmentTest
ArchiveHistoricalEthCallTest
ArchiveProofDebugApiTest
ArchiveTraceCallTest
```

利用现有测试基类：

| 文件 | 用途 |
| --- | --- |
| `framework/src/test/java/org/tron/common/BaseTest.java` | Spring context 测试 |
| `framework/src/test/java/org/tron/common/BaseMethodTest.java` | 方法级独立 DB |
| `framework/src/test/java/org/tron/core/db/ManagerTest.java` | block manager 参考 |
| `framework/src/test/java/org/tron/core/jsonrpc/JsonRpcTest.java` | JSON-RPC 参考 |
| `framework/src/test/java/org/tron/common/runtime/vm/RepositoryTest.java` | VM Repository 参考 |

### 13.3 命令

实现过程中每个 PR 至少跑定向测试：

```bash
./gradlew :framework:test --tests '*Archive*'
./gradlew :framework:test --tests 'org.tron.core.db2.ChainbaseTest'
./gradlew :framework:test --tests 'org.tron.core.jsonrpc.JsonRpcTest'
./gradlew :actuator:test --tests '*VMActuatorTest'
```

合并前跑：

```bash
./gradlew lint
./gradlew build
```

不要通过 skip 规避失败测试。

## 14. 实现不变量

1. Archive 默认关闭，关闭时不改变 consensus apply、Store 写、RPC latest 行为。
2. txNum 只由 canonical block order、tx order、固定 phase 决定。
3. pending transaction、broadcast validation、constant call 不进入 Archive。
4. Store hook 只写 pending block 内存，不直接持久化 sidecar。
5. Archive sidecar flush 只发生在 revoking session commit 成功后。
6. Reorg/eraseBlock 必须显式 unwind Archive。
7. `GetAsOf` 只接收 `ArchiveTxNumIndex` 解析后的 `asOfTxNum`。
8. root 必须带 `coverage/schemaVersion/rootScope`。
9. P0 root 是 sidecar root，不写入 block header。
10. 未归类 Store 写不能静默忽略；P0 可 warn，root 对外承诺前必须有 policy。

## 15. 当前最小可编码切入

如果下一步进入代码，建议先做 PR 1 + PR 2 的最小 patch：

1. 在 `reference.conf`、`StorageConfig`、`CommonParameter.storage`、`Args.applyStorageConfig` 当前配置链路加 archive 配置，默认 false。
2. 在 `chainbase` 加 `ArchiveService` no-op 和 context。
3. 修复 `TronStoreWithRevoking.getDbName()`。
4. 在 `Manager.processBlock` 加 begin/end tx hook，但 no-op 实现下行为不变。
5. 加 TxNumIndex 内存实现和单测，不急着持久化全部 temporal history。

这一步风险最低，能先把交易级坐标钉死。只要 txNum 坐标不稳定，后面的 history/root 都会返工。

PR 1/PR 2 的逐文件改动、类骨架、`Manager` hook、配置桥接和测试用例已细化在 [java-tron Archive PR1/PR2 代码级实现规格](./20260602-java-tron-archive-pr1-pr2-implementation-spec.md)。

PR 3/PR 4 的 Store-level write collector、`ContractStore` 特例、storage logical slot semantic hook、write-set 压缩和测试用例已细化在 [java-tron Archive PR3/PR4 WriteCollector 代码级实现规格](./20260602-java-tron-archive-pr3-pr4-write-collector-implementation-spec.md)。

PR 5 的 temporal history 持久化、single physical archive DB、`GetAsOf`、unwind、batch 原子性和 startup verifier 已细化在 [java-tron Archive PR5 TemporalStore 代码级实现规格](./20260602-java-tron-archive-pr5-temporal-store-implementation-spec.md)。

PR 6 的 `ArchiveStateReader`、JSON-RPC block 参数解析、`eth_getBalance`、`eth_getCode`、`eth_getStorageAt` 历史读取和历史 `eth_call` 边界已细化在 [java-tron Archive PR6 StateReader/JSON-RPC 代码级实现规格](./20260602-java-tron-archive-pr6-state-reader-jsonrpc-implementation-spec.md)。

PR 7 的 block-end archive sidecar root、RootRecord schema、content-addressed sparse Merkle tree、同 batch 原子提交、unwind 和 rebuild 校验已细化在 [java-tron Archive PR7 CommitmentBuilder 代码级实现规格](./20260602-java-tron-archive-pr7-commitment-builder-implementation-spec.md)。

PR 8 的 historical `eth_call`、archive-backed Repository overlay、历史动态参数视图、`VMActuator` 注入点、block object 参数处理和 overlay 隔离测试，当前 `4e80f8ffa9a2` 编码以 [java-tron Archive S12/S13：historical eth_call 4e80 编码执行包](./20260604-java-tron-archive-s12-s13-historical-eth-call-4e80-coding-packet.md) 为准；旧规格保留为历史参考。

PR 9 的 archive-native root/proof/debug API、tx-level root on-demand replay、proof verifier、debug method guard 和 traceCall 边界，当前 `4e80f8ffa9a2` 编码以 [java-tron Archive S14：proof/debug API 4e80 编码执行包](./20260604-java-tron-archive-s14-proof-debug-api-4e80-coding-packet.md) 为准；旧规格保留为历史参考。
