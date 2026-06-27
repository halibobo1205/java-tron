# java-tron Archive L4：WriteCollector + Storage Semantic Hook 代码级执行包

日期：2026-06-05

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 当前基线：`4e80f8ffa9a2`

上游路线：[java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)

落地执行看板：[java-tron Archive：4e80 分阶段落地执行看板](./20260604-java-tron-archive-4e80-landing-readiness-board.md)

逐文件落点：[java-tron Archive：4e80 逐文件实现落点矩阵](./20260604-java-tron-archive-4e80-file-implementation-map.md)

前置 L2：[java-tron Archive L2：Manager lifecycle + txNum 代码级执行包](./20260605-java-tron-archive-l2-manager-txnum-4e80-code-plan.md)

前置 L3：[java-tron Archive L3：ArchiveDomainRegistry 代码级执行包](./20260605-java-tron-archive-l3-domain-registry-4e80-code-plan.md)

上游 S4/S5 包：[java-tron Archive S4/S5：WriteCollector 与 Storage Semantic Hook 4e80 编码执行包](./20260603-java-tron-archive-s4-s5-write-collector-storage-4e80-coding-packet.md)

模块调研：[模块 03 ArchiveWriteCollector：4e80 java-tron 源码对照细化](./20260603-java-tron-module-03-write-collector-4e80-source-deep-dive.md)

state-root 分支参考：[java-tron state-root 分支借鉴分析](./20260605-java-tron-state-root-branches-reference-analysis.md)

## 1. 本执行包定位

L4 是第一个真正观察 canonical execution 写入的 slice。它只把当前 block/tx 内发生的 Store 写入转换为内存 `BlockWriteSet`，不持久化 archive DB，不实现 historical read，不计算 root。

L4 解决的问题：

```text
ArchiveExecutionContext(txNum, phase)
  + ArchiveDomainRegistry(domain schema)
  + java-tron Store/TVM write hooks
  -> BlockWriteSet
       -> TxWriteSet(txNum)
          -> DomainWrite(domain, canonicalKey, firstBefore, finalAfter)
```

L4 完成后，L5 `ArchiveTemporalStore` 可以直接消费 `BlockWriteSet` 并写 latest/history/changeset。

补充参考口径：`feat/state-trie-4.8.1` 在 `TronStoreWithRevoking.put/delete` 调 `WorldStateCallBack.callBack(type,key,value,op)`，证明 generic Store hook 插入点可用；但 L4 只能输出 `BlockWriteSet`，不能像该分支一样直接写 trie 或依赖 singleton mutable callback。`feat/481_state_root` 在 checkpoint rows 上做 root fingerprint，也证明 root 输入必须先经过 domain/value normalizer；这些 normalizer 必须来自 L3 registry。

## 2. 完成目标

L4 `DONE` 必须证明：

- archive disabled 或没有 active archive context 时，Store hook 快速 no-op，不读取 before-value。
- archive enabled 且 context active 时，P0 raw Store 写入会被 registry 映射为 `DomainWrite`。
- `TronStoreWithRevoking.put/delete` 的 generic hook 覆盖 `ACCOUNT`、`CODE`、`DYNAMIC_PROPERTIES` 等 generic Store。
- `ContractStore`、`AbiStore`、`ContractStateStore` 的 direct `revokingDB.put` 路径不会漏采。
- `CONTRACT_STORAGE` 只来自 `Storage.commit()` 的 semantic hook，raw `storage-row` 不产生 storage domain write。
- 同一 `txNum + domain + canonicalKey` 内多次写入只输出 `firstBefore` 和 `finalAfter`。
- no-op 写入 `firstBefore == finalAfter` 在 `TxWriteSet` 输出前被剔除或明确标记 skipped。
- retry 只回滚 VM attempt 期间的写集，不丢失 retry 前已经发生且不会重放的 bandwidth/memo/resource 写入。
- block apply 失败、canonical session 未 commit、fork recovery 失败时，pending write-set 全部丢弃。
- `BlockWriteSet` 和 `TxWriteSet` 输出顺序稳定，不依赖 `HashMap` iteration。

## 3. Erigon 对照结论

| Erigon 源码 | 事实 | java-tron L4 借鉴 |
| --- | --- | --- |
| `db/state/execctx/domain_shared.go:817-870` | `DomainPut(domain, tx, key, value, txNum, prevVal)` 按 domain/key/txNum 写 pending domain batch；`prevVal == nil` 时从 latest 读取 | java-tron collector 输出必须带 domain/key/txNum/before/after；before 最好在 hook 处明确提供 |
| `domain_shared.go:840-850` | before 和 after 相同可跳过 | L4 accumulator 可在 tx end 删除 no-op |
| `domain_shared.go:878-908` | delete 用 `DomainDel`，account delete 还会 cascade storage/code | java-tron P0 暂不做 cascade，但 delete/tombstone 必须显式表达 |
| `domain_shared.go:934-969` | storage 支持 prefix delete | java-tron 暂不从 physical key cascade storage，后续合约删除需要单独 semantic 规则 |
| `db/state/temporal_mem_batch.go:132-147` | pending batch 同时更新 latest 和 history writer | java-tron L4 只产生内存写集，L5 再写 latest/history/changeset |
| `execution/state/rw_v3.go:268-300` | storage key 是 `address || slot` semantic composite，不是底层表 key | java-tron `storage-row` physical key 不可逆，必须由 semantic hook 输出 `address21 || slot32 || version` |

迁移原则：保留 Erigon 的 domain write 语义，不照搬底层数据库实现。

## 4. 非目标

L4 不做：

- 不新增 archive physical DB。
- 不实现 `ArchiveTemporalStore.applyBlock()`。
- 不实现 `getAsOf`、historical JSON-RPC、historical `eth_call`。
- 不计算 sidecar root，不写 root record。
- 不修改 `BlockHeader.raw.accountStateRoot`。
- 不把 P1/P2 Store 默认纳入 P0 root。
- 不用 raw `storage-row` physical key 作为 `CONTRACT_STORAGE` canonical key。
- 不在 collector 内硬编码 domain 决策。所有 domain/policy 判断来自 L3 registry。

## 5. 前置条件

| 前置 | 依赖原因 |
| --- | --- |
| L1 `ArchiveService` default-off/no-op 已存在 | Store hook 需要 disabled fast path |
| L1 `TronStoreWithRevoking.getDbName()` 返回真实 DB name | generic hook 要按 dbName 查 registry |
| L2 `ArchiveExecutionContext` 可暴露当前 txNum/phase/source | collector 要把写入归属到 txNum |
| L2 `Manager.processBlock` 已覆盖 `BLOCK_PREPARE/USER_TX/BLOCK_FINALIZE` | finalize 写入必须有 context |
| L3 `ArchiveDomainRegistry`、codec、dynamic key policy 已稳定 | collector 不做 domain hardcode |

如果 L1/L2/L3 任一项未完成，L4 可以先写纯 collector 单测，但不能接 Store hook。

## 6. 当前 java-tron 源码事实

### 6.1 Generic Store hook 点

`chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java`：

| 源码 | 当前事实 | L4 约束 |
| --- | --- | --- |
| `TronStoreWithRevoking.java:47-54` | superclass 已有 Spring 注入字段和底层 `DB<byte[], byte[]> db` | 可在 superclass 注入 `ArchiveService` |
| `TronStoreWithRevoking.java:56-70` | 构造器用 dbName 创建底层 DB | L1 修复后 `getDbName()` 应透传该 dbName |
| `TronStoreWithRevoking.java:77-80` | 当前 `getDbName()` 返回 null | L4 不能在此状态下启用 generic hook |
| `TronStoreWithRevoking.java:88-95` | `put(byte[], T)` null guard 后 `revokingDB.put(key, item.getData())` | hook 在 `revokingDB.put` 之前读取 before/after |
| `TronStoreWithRevoking.java:97-99` | `delete(byte[])` 直接 `revokingDB.delete(key)`，当前没有 null guard | hook 不能改变 delete 原行为 |
| `TronStoreWithRevoking.java:107-115` | `getUnchecked` 会尝试 parse capsule，BadItem 返回 null | L4 raw before 更推荐直接用 `revokingDB.getUnchecked(key)` |

L4 raw hook 应优先读取 raw bytes：

```text
before = revokingDB.getUnchecked(key)
after = item.getData()
```

不要用 `getUnchecked(key).getData()` 作为唯一方案，因为它会经过 capsule parse，可能把 raw bytes 解析失败误认为 tombstone。

### 6.2 Store-specific 绕过点

| Store | 源码 | 当前事实 | L4 hook |
| --- | --- | --- | --- |
| `ContractStore` | `ContractStore.java:31-39` | 清 ABI 后直接 `revokingDB.put(key, item.getData())` | 在 clear ABI 后、put 前发 `STORE_SPECIFIC` raw put |
| `AbiStore` | `AbiStore.java:27-32` | overload `put(byte[], byte[])` 直接写 raw bytes | 显式 hook，P1/debug，不进 P0 root |
| `ContractStateStore` | `ContractStateStore.java:27-32` | override `put` 直接 `revokingDB.put` | 显式 hook，P1/historical VM candidate |
| `CodeStore` | `CodeStore.java:13-23` | 未 override `put/delete` | generic hook 覆盖 |

不要把 `ContractStore.put()` 简单改成 `super.put()` 来复用 generic hook。`ContractStore` 当前 value 规范是 ABI-cleared bytes，L4 必须在 store-specific hook 中保留这个事实。

### 6.3 普通 actuator 直写 Store

`actuator/src/main/java/org/tron/core/actuator/TransferActuator.java`：

| 源码 | 写入 |
| --- | --- |
| `TransferActuator.java:55` | 新账户时 `accountStore.put(toAddress, toAccount)` |
| `TransferActuator.java:60` | 扣 owner 余额 |
| `TransferActuator.java:64` | 非 blackhole optimization 时写 blackhole |
| `TransferActuator.java:66` | 给收款账户加余额 |

结论：不能只 hook TVM repository。普通 actuator、资源扣减、block finalize 都要靠 Store-level hook。

### 6.4 TVM Repository commit 边界

`actuator/src/main/java/org/tron/core/vm/repository/RepositoryImpl.java`：

| 源码 | 当前事实 | L4 归属 |
| --- | --- | --- |
| `RepositoryImpl.java:638-646` | `saveCode` 写 code cache，并可能更新 contract codeHash | 最终 `commitCodeCache/commitContractCache` 触发 Store hook |
| `RepositoryImpl.java:673-677` | `putStorageValue(address,key,value)` 写 `Storage` cache | 只记录 intent，不是最终落盘 |
| `RepositoryImpl.java:766-782` | `commit()` 依次提交 account/code/contract/contract-state/storage/dynamic 等 cache | root repository commit 是最终写边界 |
| `RepositoryImpl.java:997-1004` | account cache 写 `AccountStore.put` | generic ACCOUNT |
| `RepositoryImpl.java:1009-1016` | code cache 写 `CodeStore.put` | generic CODE |
| `RepositoryImpl.java:1021-1031` | ABI 缺失时写 `AbiStore.put`，再写 `ContractStore.put` | store-specific ABI/CONTRACT |
| `RepositoryImpl.java:1037-1045` | contract-state cache 写 `ContractStateStore.put` | store-specific CONTRACT_STATE |
| `RepositoryImpl.java:1050-1058` | root repository 才 `storage.commit()` | semantic CONTRACT_STORAGE |
| `RepositoryImpl.java:1063-1069` | dynamic cache 写 `DynamicPropertiesStore.put` | generic DYNAMIC_PROPERTIES + key policy |

设计原则：

```text
Store hook = 完整性来源
Storage.commit semantic hook = CONTRACT_STORAGE logical key/value 来源
RepositoryImpl = 尽量不改，避免把 archive 依赖灌进手工构造的 Repository
```

可以借鉴 `Manager.generateBlock()` 的 per-tx nested session 模式给 collector 做 tx 级 checkpoint：每笔 canonical tx 开始时 `ArchiveWriteCollector.beginTx(txNum)`，成功后 `endTx/merge`，失败时 `abortTx` 并由外层 block session 回滚 canonical state。注意这只是 archive write-set 的边界模型，不允许在 push/apply 路径像产块一样跳过失败交易。

### 6.5 VM revert/exception 不应污染 final write-set

`actuator/src/main/java/org/tron/core/actuator/VMActuator.java`：

| 源码 | 当前事实 |
| --- | --- |
| `VMActuator.java:225-231` | constant call 直接 return，不 commit repository |
| `VMActuator.java:234-248` | exception/revert 时清理结果，不执行 `rootRepository.commit()` |
| `VMActuator.java:249-250` | 非异常非 revert 才 `rootRepository.commit()` |
| `VMActuator.java:259-260` | 非 Constantinople 分支也在成功路径 commit |

L4 hook 放在 Store/Storage commit 边界，因此正常不会采到 revert 的 repository cache intent。测试仍要覆盖：revert/exception 不产生 storage semantic write。

### 6.6 Storage physical key 不可逆

`actuator/src/main/java/org/tron/core/vm/program/Storage.java`：

| 源码 | 当前事实 | L4 规则 |
| --- | --- | --- |
| `Storage.java:18-25` | `rowCache` key 是 logical `DataWord`，`address` 是 storage owner | semantic key 可在 `Storage.commit()` 构造 |
| `Storage.java:26-27` | `contractVersion` 只有 setter | L4 需要 helper 读取 storage key version |
| `Storage.java:46-53` | `compose(key, addrHash)` 生成 physical key，version 1 会 hash slot | physical key 不进 canonical key |
| `Storage.java:61-70` | create2 可用 `address || trxHash` 改 `addrHash` | physical key 不能反推 address |
| `Storage.java:73-83` | `getValue` 用 physical key 读 Store，再用 logical key 放入 rowCache | logical slot 只在 Storage 层可见 |
| `Storage.java:86-93` | `put` 只更新 rowCache | 这是 intent/cache，不是最终写 |
| `Storage.java:96-105` | `commit` 遍历 dirty row，zero 删除、非 zero put | semantic hook 放在这里 |

`StorageRowCapsule.java:67-69` 的 `setValue` 会覆盖 rowValue 并标 dirty，所以 before 不能从 dirty row 自身读取，必须在 `Storage.commit()` 写 Store 前从 `StorageRowStore` 的 revoking view 读取。

### 6.7 Manager tx lifecycle、retry、finalize

`framework/src/main/java/org/tron/core/db/Manager.java`：

| 源码 | 当前事实 | L4 规则 |
| --- | --- | --- |
| `Manager.java:1521-1524` | block 内 tx 初始化 balance trace，`trxCap.setInBlock(true)` | tx context 必须已经 active |
| `Manager.java:1544-1546` | consume bandwidth/multisign/memo fee | 这些写入发生在 VM retry 前，不能被 retry 清掉 |
| `Manager.java:1548-1550` | 第一次 `trace.init/check/exec` | VM attempt checkpoint 应在这之前设置 |
| `Manager.java:1552-1561` | `trace.checkNeedRetry()` 后再次 `trace.init/check/exec/setResult` | rollback 到 VM checkpoint，而不是清空整个 tx |
| `Manager.java:1567-1572` | finalization 和 TransactionStore 写入 | finalization 仍属当前 tx；TransactionStore registry excluded |
| `Manager.java:1873-1887` | 遍历原始 `block.getTransactions()` | txIndex 与 L2 一致 |
| `Manager.java:1906-1925` | reward/proposal/consensus/dynamic/cache writes | `BLOCK_FINALIZE` context 必须覆盖 |

这点修正了早期“retry 丢弃整笔交易 write-set”的粗略说法。正确模型是：

```text
pre-exec writes       -> remain
VM attempt writes #1  -> rollback on retry
VM attempt writes #2  -> remain
post-exec finalization writes -> remain
```

## 7. Patch 边界

### 7.1 允许新增

```text
chainbase/src/main/java/org/tron/core/archive/write/
chainbase/src/test/java/org/tron/core/archive/write/
framework/src/test/java/org/tron/core/archive/write/
actuator/src/test/java/org/tron/core/archive/write/
```

### 7.2 允许修改

```text
chainbase/src/main/java/org/tron/core/archive/ArchiveService.java
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
chainbase/src/main/java/org/tron/core/archive/NoopArchiveService.java
chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java
chainbase/src/main/java/org/tron/core/store/ContractStore.java
chainbase/src/main/java/org/tron/core/store/AbiStore.java
chainbase/src/main/java/org/tron/core/store/ContractStateStore.java
chainbase/src/main/java/org/tron/core/store/StorageRowStore.java
actuator/src/main/java/org/tron/core/vm/program/Storage.java
framework/src/main/java/org/tron/core/db/Manager.java
```

`Manager.java` 只允许加 VM retry checkpoint/rollback 调用；不能在 L4 里重做 L2 lifecycle。

### 7.3 禁止修改

```text
actuator/src/main/java/org/tron/core/vm/repository/RepositoryImpl.java
framework/src/main/java/org/tron/core/services/jsonrpc/*
chainbase/src/main/java/org/tron/core/archive/store/*
chainbase/src/main/java/org/tron/core/archive/temporal/*
chainbase/src/main/java/org/tron/core/archive/commitment/*
```

如果实现 L4 时必须改 `RepositoryImpl`，需要先写 ADR，因为这会把 archive dependency 带入手工构造 repository 的深层路径。

## 8. 包结构

推荐以 `write` 作为包名，统一放置 collector 数据模型：

```text
org.tron.core.archive.write
  ArchiveValue
  ArchiveWriteOp
  ArchiveWriteSource
  RawStoreWriteEvent
  SemanticStoreWrite
  DomainWriteKey
  DomainWrite
  DomainWriteAccumulator
  TxWriteMeta
  TxWriteSet
  BlockWriteSet
  WriteCollectStats
  ArchiveWriteCollector
  DefaultArchiveWriteCollector
  ArchiveWriteException
```

早期文档中出现的 `collector` package 可视为同一模块的旧命名。实际编码以本 L4 包名为准，避免 `write` 与 `collector` 两套目录并存。

## 9. 核心数据模型

### 9.1 ArchiveValue

`ArchiveValue` 用显式 tombstone 表达 delete/missing，collector 内部不直接用裸 `null` 表达业务语义。

```java
public final class ArchiveValue {
  private final byte[] bytes;

  public static ArchiveValue tombstone();

  public static ArchiveValue of(byte[] bytes);

  public boolean isTombstone();

  public byte[] bytesOrNull();
}
```

规则：

- `of(byte[])` defensive copy。
- `bytesOrNull()` defensive copy。
- `tombstone()` 可用 singleton，但对外仍不可变。
- `byte[0]` 和 tombstone 不是同一个语义。domain-specific codec 决定空 bytes 是否合法。

### 9.2 RawStoreWriteEvent

```java
public enum ArchiveWriteOp {
  PUT,
  DELETE
}

public enum ArchiveWriteSource {
  GENERIC_TRON_STORE,
  STORE_SPECIFIC,
  SEMANTIC
}

public final class RawStoreWriteEvent {
  private final String dbName;
  private final ArchiveWriteOp op;
  private final byte[] rawKey;
  private final ArchiveValue beforeValue;
  private final ArchiveValue afterValue;
  private final ArchiveWriteSource source;
}
```

构造规则：

- `dbName` 必须非空。enabled context 下 dbName null 是 L1 gate 漏洞，应 hard diagnostic。
- `rawKey` 必须 clone。
- `DELETE.afterValue = ArchiveValue.tombstone()`。
- `beforeValue` 来自 `revokingDB.getUnchecked(rawKey)` 的 raw bytes。
- `storage-row` raw event 只允许 diagnostic，不映射为 `CONTRACT_STORAGE`。

### 9.3 SemanticStoreWrite

```java
public final class SemanticStoreWrite {
  public enum Kind {
    CONTRACT_STORAGE
  }

  private final Kind kind;
  private final byte[] address;
  private final byte[] logicalKey;
  private final ArchiveValue beforeValue;
  private final ArchiveValue afterValue;
  private final byte[] physicalKey;
  private final int storageKeyVersion;

  public static SemanticStoreWrite contractStorage(...);
}
```

校验：

| 字段 | 要求 |
| --- | --- |
| `address` | 21 bytes TRON address |
| `logicalKey` | 32 bytes TVM slot |
| `beforeValue` | tombstone 或 32-byte word |
| `afterValue` | tombstone 或 32-byte non-zero word |
| `physicalKey` | 可 null，只用于诊断 |
| `storageKeyVersion` | P0 只允许 `0` 或 `1` |

storage value 归一：

```text
null                  -> tombstone
32-byte zero           -> tombstone
32-byte non-zero       -> value
other non-null length  -> ArchiveWriteException
```

### 9.4 DomainWrite

```java
public final class DomainWriteKey {
  private final ArchiveDomain domain;
  private final byte[] canonicalKey;
}

public final class DomainWrite {
  private final ArchiveDomain domain;
  private final byte[] canonicalKey;
  private final ArchiveValue firstBefore;
  private final ArchiveValue finalAfter;
  private final long firstSequence;
  private final long lastSequence;
  private final EnumSet<ArchiveWriteSource> sources;
  private final boolean skippedNoop;
}
```

`firstSequence/lastSequence` 是 tx 内 collector sequence，用于诊断和测试排序，不是持久化 txNum。

### 9.5 TxWriteSet / BlockWriteSet

```java
public final class TxWriteMeta {
  private final long blockNum;
  private final long txNum;
  private final int txIndex;
  private final ArchivePhase phase;
  private final ArchiveSource source;
  private final byte[] txId;
}

public final class TxWriteSet {
  private final TxWriteMeta meta;
  private final List<DomainWrite> writes;
  private final WriteCollectStats stats;
}

public final class BlockWriteSet {
  private final long blockNum;
  private final byte[] blockHash;
  private final long firstTxNum;
  private final long lastTxNum;
  private final List<TxWriteSet> txs;
}
```

排序规则：

- `BlockWriteSet.txs` 按 `txNum` 升序。
- `TxWriteSet.writes` 按 `domain.id`、`canonicalKey` unsigned lexicographic 升序。
- stats 不参与 root/temporal 写入顺序。

### 9.6 WriteCollectStats

```java
public final class WriteCollectStats {
  private final int rawEvents;
  private final int semanticEvents;
  private final int ignoredRawEvents;
  private final int unknownDbNameEvents;
  private final int beforeMismatchEvents;
  private final int noopWrites;
  private final int retryRollbacks;
}
```

stats 用于 focused tests 和 debug API，不作为共识或 root 输入。

## 10. Collector API

```java
public interface ArchiveWriteCollector {
  void beginBlock(ArchiveBlockContext blockContext);

  void beginTx(ArchiveTxPosition txPosition);

  void markRetryCheckpoint();

  void rollbackToRetryCheckpoint();

  void onRawPut(RawStoreWriteEvent event);

  void onRawDelete(RawStoreWriteEvent event);

  void onSemanticWrite(SemanticStoreWrite write);

  TxWriteSet endTx();

  void abortTx();

  BlockWriteSet finishBlock();

  void abortBlock();
}
```

约束：

- collector 只依赖 `ArchiveDomainRegistry` 和 L2 context value objects。
- collector 不依赖 Store、Manager、Repository。
- `markRetryCheckpoint()` 在当前 tx accumulator 内记录 sequence/checkpoint。
- `rollbackToRetryCheckpoint()` 删除 checkpoint 后产生的 writes/events，保留 checkpoint 前写入。
- `abortTx()` 删除当前 tx 全部 writes。
- `finishBlock()` 只能在没有 active tx 时调用。

## 11. ArchiveService 扩展

在 L2 service 基础上补 Store/semantic hook facade：

```java
public interface ArchiveService {
  boolean isEnabled();

  boolean hasActiveWriteContext();

  void markRetryCheckpoint();

  void rollbackToRetryCheckpoint();

  void onRawStorePut(String dbName, byte[] rawKey, byte[] beforeValue, byte[] afterValue,
      ArchiveWriteSource source);

  void onRawStoreDelete(String dbName, byte[] rawKey, byte[] beforeValue,
      ArchiveWriteSource source);

  void onSemanticWrite(SemanticStoreWrite write);
}
```

实现规则：

- disabled 或 no context：所有 hook 方法立即返回。
- `onRawStore*` 不在 Store 层判断 domain；内部交给 registry。
- `commitBlock()` 在 canonical revoking session commit 成功后调用 collector `finishBlock()`，把 `BlockWriteSet` 暂存给 L5。
- `abortBlock()` 丢弃 collector pending block/tx state。
- `NoopArchiveService` 方法必须零副作用。

## 12. Hook 设计

### 12.1 TronStoreWithRevoking 注入

在 superclass 中注入 service，避免每个 Store 单独注入：

```java
@Autowired(required = false)
private ArchiveService archiveService;

protected ArchiveService archiveService() {
  return archiveService == null ? NoopArchiveService.instance() : archiveService;
}
```

如果 L1/L2 已采用 `DefaultArchiveService` 单实现 default-off，而不是 `NoopArchiveService.instance()`，这里按实际 L1 产物调整。关键是不允许测试或非 Spring 构造路径 NPE。

### 12.2 Generic put

目标形状：

```text
put(key, item):
  if key == null or item == null:
    return

  service = archiveService()
  after = item.getData()

  if service.isEnabled() && service.hasActiveWriteContext():
    before = revokingDB.getUnchecked(key)
    service.onRawStorePut(getDbName(), key, before, after, GENERIC_TRON_STORE)

  revokingDB.put(key, after)
```

要求：

- disabled/no context 时不能读取 `before`。
- `after` 取一次即可；event 内部 clone。
- 不改变原有 null guard 行为。

### 12.3 Generic delete

目标形状：

```text
delete(key):
  service = archiveService()

  if key != null && service.isEnabled() && service.hasActiveWriteContext():
    before = revokingDB.getUnchecked(key)
    service.onRawStoreDelete(getDbName(), key, before, GENERIC_TRON_STORE)

  revokingDB.delete(key)
```

注意：当前 `delete(byte[] key)` 没有 null guard。L4 hook 可以在 hook 侧保护 `key != null`，但不能新增提前 return 改变原 delete 行为。

### 12.4 ContractStore put

目标形状：

```text
put(key, item):
  if key == null or item == null:
    return

  if item has ABI:
    item = clearAbi(item)

  after = item.getData()

  if archive active:
    before = revokingDB.getUnchecked(key)
    archiveService().onRawStorePut(getDbName(), key, before, after, STORE_SPECIFIC)

  revokingDB.put(key, after)
```

测试必须证明 after 是 clear ABI 后 bytes，而不是入参原始 contract bytes。

### 12.5 AbiStore / ContractStateStore put

目标形状：

```text
AbiStore.put(key, value):
  if key == null or value == null:
    return
  if archive active:
    before = revokingDB.getUnchecked(key)
    archiveService().onRawStorePut(getDbName(), key, before, value, STORE_SPECIFIC)
  revokingDB.put(key, value)

ContractStateStore.put(key, item):
  if key == null or item == null:
    return
  after = item.getData()
  if archive active:
    before = revokingDB.getUnchecked(key)
    archiveService().onRawStorePut(getDbName(), key, before, after, STORE_SPECIFIC)
  revokingDB.put(key, after)
```

这两个 Store 在 L3 中不属于 P0 root，但必须有 explicit binding 或 diagnostic，避免后续 historical VM 扩展时发现漏采路径。

## 13. Storage semantic hook

### 13.1 StorageRowStore helper

`Storage` 不是 Spring bean，但持有 `StorageRowStore store`。推荐在 `StorageRowStore` 增加最小 helper：

```java
public ArchiveService getArchiveService() {
  return archiveService();
}

public byte[] getRawValue(byte[] key) {
  StorageRowCapsule row = getUnchecked(key);
  if (row == null || row.getInstance() == null) {
    return null;
  }
  return row.getValue();
}
```

如果实现需要返回 capsule，也必须避免把 absent key 表达成 present capsule with `rowValue == null`。

### 13.2 Storage key version helper

`Storage.contractVersion` 当前只有 setter。L4 增加私有 helper：

```java
private int storageKeyVersion() {
  return contractVersion == 1 ? 1 : 0;
}
```

不要把 raw `contractVersion` int 直接写进 canonical key。

### 13.3 Storage.commit hook 顺序

目标形状：

```text
commit():
  for each (logicalSlot, row) in rowCache:
    if !row.isDirty():
      continue

    physicalKey = row.getRowKey()
    beforeRaw = store.getRawValue(physicalKey)
    before = normalizeStorageValue(beforeRaw)
    after = normalizeStorageValue(row.getValue())

    service = store.getArchiveService()
    if service.isEnabled() && service.hasActiveWriteContext():
      service.onSemanticWrite(
          SemanticStoreWrite.contractStorage(
              address,
              logicalSlot.getData(),
              before,
              after,
              physicalKey,
              storageKeyVersion()))

    if after.isTombstone():
      store.delete(physicalKey)
    else:
      store.put(physicalKey, row)
```

顺序原因：

- before 必须在 physical Store put/delete 前读取。
- semantic hook 必须在 raw `storage-row` hook 前触发。
- raw `storage-row` hook 随后的 event 由 L3 registry 识别为 semantic backing/ignored。

### 13.4 CONTRACT_STORAGE canonical form

```text
canonicalKey = address21 || logicalSlot32 || storageKeyVersion_u8
value        = 32-byte non-zero word
delete       = tombstone
```

禁止进入 canonical key：

- physical row key
- `addrHash`
- create2 `trxHash`
- code address
- raw `contractVersion`

## 14. Retry checkpoint

### 14.1 为什么不能清空整个 tx

`Manager.processTransaction()` 中，bandwidth/multisign/memo fee 在 `trace.init/exec` 之前已经消费：

```text
consumeBandwidth(...)
consumeMultiSignFee(...)
consumeMemoFee(...)

trace.init(...)
trace.exec()
trace.setResult()
if trace.checkNeedRetry():
  trace.init(...)
  trace.exec()
  trace.setResult()
```

这些 pre-exec 写入不会因为 VM retry 重放。若 L4 在 retry 前清空整个 tx accumulator，会丢掉真实 canonical 写入。

### 14.2 Manager hook 形状

推荐在第一次 VM attempt 前设置 checkpoint：

```text
consumeBandwidth(...)
consumeMultiSignFee(...)
consumeMemoFee(...)

archiveService.markRetryCheckpoint()

trace.init(...)
trace.checkIsConstant()
trace.exec()

if trace.checkNeedRetry():
  archiveService.rollbackToRetryCheckpoint()
  trace.init(...)
  trace.checkIsConstant()
  trace.exec()
```

语义：

- checkpoint 前写入保留。
- checkpoint 后、retry 前写入丢弃。
- 第二次 attempt 的写入保留。
- `trace.finalization()` 之后写入保留。

### 14.3 测试要求

`ArchiveRetryLifecycleTest` 至少证明：

- pre-exec fee/account writes 在 retry 后仍存在。
- fake first VM attempt 的 storage/contract writes 被 rollback。
- second VM attempt writes 出现在 final `TxWriteSet`。
- retry stats 加一。

如果实现时发现 first attempt 已经写入 canonical revoking view 且无法回滚，不能靠 collector 丢弃掩盖，应先补 focused canonical-state 测试并重新设计 retry 边界。

## 15. Accumulator 规则

同一 tx 内 accumulator key：

```text
ArchiveDomain + canonicalKey
```

归并：

| 已有 | 新 event | 结果 |
| --- | --- | --- |
| none | before=A, after=B | firstBefore=A, finalAfter=B |
| firstBefore=A, finalAfter=B | before=B, after=C | firstBefore=A, finalAfter=C |
| firstBefore=A, finalAfter=B | before=X, after=C | firstBefore=A, finalAfter=C，stats.beforeMismatch++ |
| firstBefore=A, finalAfter=A | tx end | no-op/skipped |
| tombstone -> value -> tombstone | tx end | no-op if original tombstone |
| value -> tombstone -> value2 | tx end | firstBefore=value, finalAfter=value2 |

before mismatch 不能静默吞掉。它通常表示 hook 顺序、retry checkpoint、store-specific before 读取或 revoking session 生命周期存在问题。

## 16. DynamicProperties handling

Generic hook 会捕获 `properties` raw writes，但 mapping 必须走 L3 `DynamicKeyPolicy`：

```text
dbName = properties
  -> StoreBinding(DYNAMIC_PROPERTIES, GENERIC_TRON_STORE_ALLOWLIST)
  -> dynamicKeyDecision(rawKey)
```

规则：

- root allowlist key 可进入 `DomainWrite`，root 是否消费由 L7 决定。
- history-only key 可进入 `DomainWrite`，但 `RootPolicy` 不是 `IN_GLOBAL_ROOT`。
- excluded key 只计 stats，不产出 write。
- unknown key 按 L3 策略 history diagnostic 或 excluded diagnostic，不默认进 root。

L4 不得在 hook 中写 `if (Arrays.equals(key, ENERGY_FEE))` 这类判断。

## 17. Block lifecycle

L2 的 block lifecycle 应包裹 L4 collector：

```text
beginBlock(block)
  collector.beginBlock(...)

beginTx(BLOCK_PREPARE)
  collector.beginTx(...)
  ...
endTx()
  collector.endTx()

beginTx(USER_TX)
  collector.beginTx(...)
  processTransaction(...)
endTx()
  collector.endTx()

beginTx(BLOCK_FINALIZE)
  collector.beginTx(...)
  payReward/proposal/consensus/dynamic writes
endTx()
  collector.endTx()

canonical session commit succeeds
commitBlock(block)
  writeSet = collector.finishBlock()
  // L5 consumes it later

failure
  abortBlock(block)
  collector.abortBlock()
```

约束：

- Store hook 看到 active block 但没有 active tx 时，enabled 模式应 hard diagnostic。
- `abortBlock()` 必须清掉 active tx 和 block accumulators。
- `commitBlock()` 不写 temporal DB，这是 L5 的职责。

## 18. 实现顺序

### L4.1 Write model

新增：

```text
ArchiveValue
ArchiveWriteOp
ArchiveWriteSource
RawStoreWriteEvent
SemanticStoreWrite
DomainWriteKey
DomainWrite
TxWriteMeta
TxWriteSet
BlockWriteSet
WriteCollectStats
ArchiveWriteException
```

测试：

- `archiveValueDefensivelyCopiesBytes`
- `archiveValueTombstoneIsDistinctFromEmptyBytes`
- `rawStoreWriteEventClonesKeyAndValues`
- `semanticStorageWriteValidatesAddressSlotAndVersion`
- `semanticStorageZeroNormalizesToTombstone`
- `txWriteSetSortsByDomainIdAndKey`

### L4.2 DefaultArchiveWriteCollector

新增：

```text
ArchiveWriteCollector
DefaultArchiveWriteCollector
DomainWriteAccumulator
```

测试：

- `sameKeyMultiplePutsKeepFirstBeforeAndFinalAfter`
- `putThenDeleteProducesTombstone`
- `createThenDeleteIsNoop`
- `deleteMissingKeyIsNoop`
- `beforeMismatchIsReported`
- `rollbackToRetryCheckpointKeepsPreCheckpointWrites`
- `rollbackToRetryCheckpointDropsAttemptWrites`
- `finishBlockRejectsActiveTx`
- `abortBlockClearsPendingWrites`

### L4.3 ArchiveService facade

修改：

```text
ArchiveService
DefaultArchiveService
NoopArchiveService
```

测试：

- `disabledServiceIgnoresRawWrites`
- `enabledNoContextDoesNotReadBeforeInStoreHook`
- `servicePassesRawWritesToCollector`
- `commitBlockExposesPendingBlockWriteSet`
- `abortBlockDiscardsPendingBlockWriteSet`

### L4.4 Generic Store hook

修改：

```text
TronStoreWithRevoking.put
TronStoreWithRevoking.delete
```

测试：

- `genericPutCapturesRawBeforeAndAfter`
- `genericDeleteCapturesRawBeforeAndTombstone`
- `disabledGenericPutDoesNotReadBefore`
- `noContextGenericPutDoesNotReadBefore`
- `nullDbNameInEnabledContextIsDiagnostic`
- `deleteNullKeyDoesNotChangeExistingDeleteSemantics`

### L4.5 Store-specific hooks

修改：

```text
ContractStore.put
AbiStore.put(byte[], byte[])
ContractStateStore.put
```

测试：

- `contractStoreHookUsesAbiClearedBytes`
- `abiStoreRawBytesPutIsCaptured`
- `contractStateStoreDirectPutIsCaptured`
- `p1StoreSpecificWritesDoNotEnterP0RootWrites`

### L4.6 Storage semantic hook

修改：

```text
StorageRowStore
Storage
```

测试：

- `storageCommitCreateEmitsSemanticWrite`
- `storageCommitUpdateEmitsFirstBeforeAndFinalAfter`
- `storageCommitZeroEmitsTombstone`
- `storageCommitAbsentToZeroIsNoop`
- `storageCommitUsesLogicalSlotNotPhysicalKey`
- `storageCommitIncludesStorageKeyVersion`
- `rawStorageRowWriteIsIgnoredByRegistry`
- `revertDoesNotEmitSemanticStorageWrite`

### L4.7 Manager retry checkpoint

修改：

```text
Manager.processTransaction
```

测试：

- `retryKeepsPreExecutionWrites`
- `retryDropsFirstVmAttemptWrites`
- `retryKeepsSecondVmAttemptWrites`
- `failedBlockAbortsCollector`

## 19. 测试文件落点

纯单测：

```text
chainbase/src/test/java/org/tron/core/archive/write/ArchiveValueTest.java
chainbase/src/test/java/org/tron/core/archive/write/SemanticStoreWriteTest.java
chainbase/src/test/java/org/tron/core/archive/write/DefaultArchiveWriteCollectorTest.java
chainbase/src/test/java/org/tron/core/archive/write/TxWriteSetOrderingTest.java
```

Store/Spring wiring：

```text
framework/src/test/java/org/tron/core/archive/write/ArchiveGenericStoreHookTest.java
framework/src/test/java/org/tron/core/archive/write/ArchiveSpecialStoreHookTest.java
framework/src/test/java/org/tron/core/archive/write/ArchiveRetryLifecycleTest.java
```

TVM storage semantic：

```text
actuator/src/test/java/org/tron/core/archive/write/ArchiveStorageSemanticHookTest.java
```

如果 actuator test 无法独立装配 `StoreFactory/Manager`，可暂放到 framework test，并在文件名保留 `ArchiveStorageSemanticHookTest`。

可复用 fixture：

| 现有测试 | 用途 |
| --- | --- |
| `framework/src/test/java/org/tron/common/runtime/vm/StorageTest.java:91-186` | `StorageDemo` 合约，覆盖 put/overwrite/delete |
| `framework/src/test/java/org/tron/common/runtime/TvmTestUtils.java:84-184` | deploy/trigger/process helper |
| `framework/src/test/java/org/tron/common/BaseMethodTest.java` | Spring context/Manager fixture |

## 20. Gate 命令

Focused gate：

```bash
./gradlew :chainbase:test --tests '*ArchiveValueTest'
./gradlew :chainbase:test --tests '*SemanticStoreWriteTest'
./gradlew :chainbase:test --tests '*DefaultArchiveWriteCollectorTest'
./gradlew :chainbase:test --tests '*TxWriteSetOrderingTest'
./gradlew :framework:test --tests '*ArchiveGenericStoreHookTest'
./gradlew :framework:test --tests '*ArchiveSpecialStoreHookTest'
./gradlew :framework:test --tests '*ArchiveRetryLifecycleTest'
./gradlew :actuator:test --tests '*ArchiveStorageSemanticHookTest'
```

Regression gate：

```bash
./gradlew :framework:test --tests '*ManagerTest'
./gradlew :framework:test --tests '*StorageTest'
./gradlew checkstyleMain checkstyleTest
```

Pre-merge gate：

```bash
./gradlew build
```

不要通过新增 skip 绕过失败测试。失败说明 hook 改变了默认行为或 fixture 装配需要修正。

## 21. Review checklist

- disabled/no context fast path 不读取 before。
- generic hook 使用 raw `revokingDB.getUnchecked`，不是 capsule parse 后的 `getUnchecked`.
- `delete` hook 不改变原有 null 行为。
- `getDbName()` null 在 enabled context 下不是 silent ignored。
- Store hook 不硬编码 domain。
- `ContractStore` hook 发生在 clear ABI 之后。
- `AbiStore.put(byte[], byte[])` 和 `ContractStateStore.put` 有 hook。
- `Storage.commit()` semantic hook 使用 logical slot。
- `Storage.commit()` before 从 Store revoking view 读取，不从 dirty row 读取。
- `CONTRACT_STORAGE` key 是 `address21 || slot32 || version_u8`。
- raw `storage-row` 不产出 storage domain write。
- retry rollback 到 checkpoint，不清空 pre-exec 写入。
- block abort 清空 pending collector state。
- output list 排序稳定。
- no-op writes 不进入 L5 temporal input，或明确标记且 L5 忽略。

## 22. 停止条件

L4 可以标记 `DONE` 的证据：

- `DefaultArchiveWriteCollectorTest` 证明 first-before/final-after、retry checkpoint、sorting、no-op 规则。
- `ArchiveGenericStoreHookTest` 证明 generic Store hook default-off fast path 和 enabled capture。
- `ArchiveSpecialStoreHookTest` 证明 direct `revokingDB.put` Store 不漏。
- `ArchiveStorageSemanticHookTest` 证明 storage semantic key/value/tombstone 正确。
- `ArchiveRetryLifecycleTest` 证明 retry 不丢 pre-exec 写入且丢弃 first attempt 写入。
- L1/L2/L3 regression tests 仍通过。
- 未新增 temporal DB、RPC、root/proof 行为。

若这些证据缺失，不能进入 L5 `ArchiveTemporalStore`。

## 23. 与 L5 的交付接口

L5 只能消费：

```text
BlockWriteSet
  blockNum
  blockHash
  firstTxNum
  lastTxNum
  List<TxWriteSet>
```

L5 不应再读取 java-tron Store hook、Repository、Storage rowCache，也不应重新做 domain mapping。若 L5 需要判断 root/history/read policy，只能通过 L3 `ArchiveDomainRegistry` 和 L4 `DomainWrite`。
