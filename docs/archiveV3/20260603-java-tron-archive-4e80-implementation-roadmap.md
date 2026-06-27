# java-tron Archive：4e80 统一实现路线

日期：2026-06-03

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`，`git status --short` 为空，精确冲突标记扫描无命中。

本文是当前 4e80 源码下的编码主入口。旧 `20260602-*` 蓝图和执行包保留为历史设计资料；实际编码以本文和六个 4e80 模块细化文档为准。

## 1. 权威输入

| 文档 | 用途 |
| --- | --- |
| [当前源码审计入口](./20260603-java-tron-archive-current-source-implementation-audit.md) | 当前基线、旧结论失效点 |
| [完整实现总装计划](./20260604-java-tron-archive-4e80-implementation-assembly-plan.md) | S1-S14 跨模块接口契约、landing 顺序、完成证据 |
| [逐文件实现落点矩阵](./20260604-java-tron-archive-4e80-file-implementation-map.md) | java-tron 物理文件、测试文件、review diff 边界 |
| [分阶段落地执行看板](./20260604-java-tron-archive-4e80-landing-readiness-board.md) | L1-L9 状态、依赖、合入 gate、DONE 证据 |
| [模块测试与验收计划](./20260604-java-tron-archive-4e80-test-verification-plan.md) | 每个模块对照 java-tron 的测试 fixture、验收矩阵、失败判据 |
| [state-root 分支借鉴分析](./20260605-java-tron-state-root-branches-reference-analysis.md) | 对 `feat/481_state_root` 和 `feat/state-trie-4.8.1` 做源码对照，提炼可借鉴的 domain/hook/query/repository/cache/evidence 思路和禁止混入的 header root 口径 |
| [L1 config/no-op/dbName 代码级执行包](./20260604-java-tron-archive-l1-config-noop-dbname-4e80-code-plan.md) | 第一批 patch 的类签名、字段名、测试方法、diff 边界 |
| [L2 Manager lifecycle + txNum 代码级执行包](./20260605-java-tron-archive-l2-manager-txnum-4e80-code-plan.md) | 第二批 patch 的 Manager hook、txNum index、context lifecycle、测试方法和 diff 边界 |
| [L3 ArchiveDomainRegistry 代码级执行包](./20260605-java-tron-archive-l3-domain-registry-4e80-code-plan.md) | 第三批 patch 的 domain/schema、Store binding、codec、dynamic key policy、checksum 和测试边界 |
| [L4 WriteCollector + Storage Semantic Hook 代码级执行包](./20260605-java-tron-archive-l4-write-collector-4e80-code-plan.md) | 第四批 patch 的 raw/store-specific/semantic write hook、collector compression、retry checkpoint 和测试边界 |
| [L5 ArchiveTemporalStore 代码级执行包](./20260605-java-tron-archive-l5-temporal-store-4e80-code-plan.md) | 第五批 patch 的 single archive DB、temporal key/value、apply/getAsOf/unwind、startup verifier 和 txNum persistence |
| [L6 ArchiveStateReader + historical getters 代码级执行包](./20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md) | 第六批 patch 的 historical reader、state point resolver、JSON-RPC adapter、3 个 getter 和测试边界 |
| [L7 CommitmentBuilder 代码级执行包](./20260605-java-tron-archive-l7-commitment-builder-4e80-code-plan.md) | 第七批 patch 的 sidecar root、SMT/node/root codecs、transaction-level root、unwind、rebuild verifier 和 header untouched 测试边界 |
| [4e80 六模块源码对照细化](./20260603-java-tron-archive-4e80-six-modules-source-detail.md) | 六模块总览 |
| [模块 01 ArchiveTxNumIndex](./20260603-java-tron-module-01-txnum-index-4e80-source-deep-dive.md) | txNum 与 block lifecycle |
| [模块 02 ArchiveDomainRegistry](./20260603-java-tron-module-02-domain-registry-4e80-source-deep-dive.md) | domain inventory、codec、root/history policy |
| [模块 03 ArchiveWriteCollector](./20260603-java-tron-module-03-write-collector-4e80-source-deep-dive.md) | Store hook、semantic storage hook、write set |
| [模块 04 ArchiveTemporalStore](./20260603-java-tron-module-04-temporal-store-4e80-source-deep-dive.md) | single archive DB、latest/history/changeset/progress |
| [模块 05 ArchiveStateReader](./20260603-java-tron-module-05-state-reader-4e80-source-deep-dive.md) | historical JSON-RPC state reader |
| [模块 06 CommitmentBuilder](./20260603-java-tron-module-06-commitment-builder-4e80-source-deep-dive.md) | sidecar root、commitment branch state、rebuild verifier |
| [S1/S2 4e80 编码执行包](./20260603-java-tron-archive-s1-s2-4e80-coding-packet.md) | 第一批 patch：配置/no-op/dbName + Manager lifecycle/txNum |
| [S3 ArchiveDomainRegistry 4e80 编码执行包](./20260603-java-tron-archive-s3-domain-registry-4e80-coding-packet.md) | domain registry、Store binding、codec、policy、checksum |
| [S4/S5 WriteCollector 与 Storage Semantic Hook 4e80 编码执行包](./20260603-java-tron-archive-s4-s5-write-collector-storage-4e80-coding-packet.md) | raw Store hook、store-specific hook、storage semantic hook、retry lifecycle |
| [S6/S7 ArchiveTemporalStore 4e80 编码执行包](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md) | ArchiveRawStore、temporal codecs、applyBlock/getAsOf/unwind/startup |
| [S8/S9 ArchiveStateReader 与 JSON-RPC Historical Getters 4e80 编码执行包](./20260603-java-tron-archive-s8-s9-state-reader-rpc-4e80-coding-packet.md) | ArchiveStateReader、StatePoint resolver、eth_getBalance/getCode/getStorageAt historical path |
| [S10/S11 CommitmentBuilder 4e80 编码执行包](./20260603-java-tron-archive-s10-s11-commitment-builder-4e80-coding-packet.md) | archive sidecar root、SMT codecs、root record、commitment branch、rebuild verifier |
| [S12/S13 historical eth_call 4e80 编码执行包](./20260604-java-tron-archive-s12-s13-historical-eth-call-4e80-coding-packet.md) | archive-backed Repository、historical VM dynamic properties、JSON-RPC eth_call historical path |
| [L8 historical eth_call 代码级执行包](./20260605-java-tron-archive-l8-historical-eth-call-4e80-code-plan.md) | 对照 Erigon/java-tron 源码细化 VM 注入、Repository overlay、VMConfig scope、object-form selector 修复和测试 gate |
| [L9 proof/debug API 代码级执行包](./20260605-java-tron-archive-l9-proof-debug-api-4e80-code-plan.md) | 对照 Erigon/java-tron 源码细化 archive-native root/proof/verify、FullNode-only guard、default-off、no `eth_getProof`/`debug_traceCall` |
| [S14 proof/debug API 4e80 编码执行包](./20260604-java-tron-archive-s14-proof-debug-api-4e80-coding-packet.md) | archive-native root/proof/verify debug API，不实现 `eth_getProof`/`debug_traceCall` |

## 2. P0 完成目标

P0 目标是完成一个默认关闭、可回退、可验证的 archive sidecar：

```text
canonical block apply
  -> logical txNum
  -> domain write-set
  -> temporal latest/history/changeset
  -> historical state reader
  -> archive sidecar commitment root
  -> rebuild verifier
```

P0 明确不做：

- 不把 archive root 写入 `BlockHeader.raw.accountStateRoot`。
- 不替换或修改 `BlockHeader.raw.txTrieRoot`。
- 不实现共识 state root。
- 不承诺覆盖 java-tron 全量 Store。
- 不把 historical `eth_call` 静默 fallback 到 latest。
- 不把 archive proof 伪装成 Ethereum `eth_getProof`。

P0 最小外部能力：

| 能力 | P0 行为 |
| --- | --- |
| `eth_getBalance(address, historicalBlock)` | 走 archive state reader |
| `eth_getCode(address, historicalBlock)` | 走 archive state reader |
| `eth_getStorageAt(address, slot, historicalBlock)` | 走 archive state reader |
| `rootAtBlock(blockNum)` | 每个 block finalize 持久化 |
| `rootAtTxNum(txNum)` | 支持查询；默认可从 checkpoint + changeset 计算 |
| fork/unwind | archive sidecar 与 canonical state 同步回退 |

## 3. 当前源码硬约束

### 3.1 配置链路

当前 4e80 源码已经有 `reference.conf` 和 `StorageConfig`，旧文档中的“没有 reference.conf/StorageConfig”结论失效。

| 源码 | 事实 | 实现约束 |
| --- | --- | --- |
| `common/src/main/resources/reference.conf:118` | `balance.history.lookup = false` 默认关闭 | `storage.archive.*` 默认值先加到这里 |
| `framework/src/main/resources/config.conf:6-42` | 用户可见 `storage {}` 示例 | 同步增加 archive 示例，仍默认 false |
| `common/src/main/java/org/tron/core/config/args/StorageConfig.java:21-33` | `StorageConfig` 是 storage bean | 新增 `ArchiveConfig` 嵌套 bean |
| `StorageConfig.java:173-188` | `fromConfig(config)` 绑定并 post-process | archive 参数校验放在 bean post-process |
| `framework/src/main/java/org/tron/core/config/args/Args.java:212-244` | `applyStorageConfig(StorageConfig sc)` 桥接到 runtime `Storage` | 在这里写入 `PARAMETER.storage.archive` |
| `Args.java:713-716` | 初始化 `PARAMETER.storage = new Storage()` 后读取 `StorageConfig` | 不新增分散读取路径 |
| `common/src/main/java/org/tron/common/parameter/CommonParameter.java:432` | runtime 持有 `public Storage storage` | archive runtime 配置挂在 `Storage` 下 |

### 3.2 Manager lifecycle

| 源码 | 当前事实 | 实现约束 |
| --- | --- | --- |
| `Manager.java:1266-1272` | `pushBlock(final BlockCapsule block)` 是 canonical 保存入口 | archive block lifecycle 从这里包裹 |
| `Manager.java:1305-1307` | 非本地产块调用 `block.validateMerkleRoot()` 和 `consensus.receiveBlock(block)` | archive 不接入 tx merkle 校验 |
| `Manager.java:1379-1381` | normal path 在 revoking session 内 `applyBlock(newBlock, txs)` 后 `tmpSession.commit()` | archive flush 只能在 commit 成功后 |
| `Manager.java:1382-1387` | apply/commit 失败会移除 khaos block 并抛出 | archive 必须 abort pending block |
| `Manager.java:1388-1389` | commit 后调用 `blockTrigger` | archive commit 应在 trigger 前，避免 RPC/event 看到 canonical head 但 archive behind |
| `Manager.java:1034-1042` | `eraseBlock()` 先拿 old head，再 `khaosDb.pop()`、`revokingStore.fastPop()` | archive unwind 放在 `fastPop()` 成功后 |
| `Manager.java:1142-1149` | fork replay 新分支也用 revoking session commit | replay 必须走同一 archive begin/commit/abort |
| `Manager.java:1185-1187` | fork 失败恢复旧分支也 commit | recovery replay 也不能漏 archive |

### 3.3 processBlock phase

| 源码 | 当前事实 | txNum phase |
| --- | --- | --- |
| `Manager.java:1851-1854` | balance trace 初始化、block energy 清零 | `BLOCK_PREPARE` |
| `Manager.java:1867` | `HistoryBlockHashUtil.write(this, block)` | `BLOCK_PREPARE` |
| `Manager.java:1873-1887` | 遍历 `block.getTransactions()` 并执行交易 | `USER_TX(txIndex)` |
| `Manager.java:1893-1895` | accountState root push finish/exception finish | 不作为 archive 数据源 |
| `Manager.java:1906-1925` | reward、proposal、consensus apply、dynamic properties | `BLOCK_FINALIZE` |

`txNum` 不能用 filtered `txs` 下标，必须以 `block.getTransactions()` 原始顺序为准。空块也必须产生 `BLOCK_PREPARE` 和 `BLOCK_FINALIZE` 状态点。

### 3.4 Store 与 RPC 边界

| 源码 | 当前事实 | 实现约束 |
| --- | --- | --- |
| `TronStoreWithRevoking.java:78-80` | `getDbName()` 当前返回 `null` | S1 先修为代理底层 `db.getDbName()` |
| `TronStoreWithRevoking.java:89-99` | 通用 `put/delete` 写 revoking DB | S4 挂 generic raw hook |
| `ContractStore.java:31-39` | 直接写 `revokingDB` | S4 做 store-specific hook |
| `Storage.java:46-105` | 合约 storage 物理 key 不可逆 | S5 必须采 semantic `(address, slot)` |
| `TronJsonRpcImpl.java:459/613/637` | state getters 调 `requireLatestBlockTag` | S9 替换 non-latest 分支 |
| `BlockResult.java:101-104` | JSON-RPC block result 暴露 header roots | archive root 不静默塞进 `stateRoot` |

### 3.5 accountStateRoot 边界

当前 `accountStateRoot` 是既有 header 字段，且 `AccountStateEntity` 只覆盖 `address/balance/allowance`。它不覆盖 contract、code、storage、dynamic properties；`AccountStore.delete` 也不触发 account trie delete callback。因此 Module 06 只能借鉴生命周期，不能复用 root 语义。

## 4. 目标包结构

### 4.1 common

```text
common/src/main/java/org/tron/core/config/args/
  StorageConfig.java          // 增加 ArchiveConfig 嵌套 bean
  Storage.java                // 增加 archive runtime 字段
```

### 4.2 chainbase

```text
chainbase/src/main/java/org/tron/core/archive/
  ArchiveConfig.java
  ArchiveService.java
  DefaultArchiveService.java
  NoopArchiveService.java
  ArchivePhase.java
  ArchiveExecutionContext.java
  # StatePoint.java —— 已废弃，统一用 reader/ArchiveStatePoint.java（见 00-ARCHIVE-V3-AUTHORITY-AND-DECISIONS.md §2/§5）
  ArchiveProgress.java

chainbase/src/main/java/org/tron/core/archive/txnum/
  ArchiveTxNumIndex.java
  InMemoryArchiveTxNumIndex.java
  PersistentArchiveTxNumIndex.java
  BlockTxNumRange.java
  TxNumMeta.java

chainbase/src/main/java/org/tron/core/archive/domain/
  ArchiveDomain.java
  ArchiveDomainDescriptor.java
  ArchiveDomainRegistry.java
  DefaultArchiveDomainRegistry.java
  CanonicalKeyCodec.java
  CanonicalValueCodec.java
  RootPolicy.java
  HistoryPolicy.java
  RawHookMode.java

chainbase/src/main/java/org/tron/core/archive/write/
  ArchiveWriteCollector.java
  DefaultArchiveWriteCollector.java
  BlockWriteSet.java
  DomainWrite.java
  ArchiveWriteOp.java
  RawStoreWriteEvent.java
  SemanticStoreWrite.java

chainbase/src/main/java/org/tron/core/archive/store/
  ArchiveRawStore.java
  ArchiveBatch.java
  ArchiveTable.java

chainbase/src/main/java/org/tron/core/archive/temporal/
  ArchiveTemporalStore.java
  DefaultArchiveTemporalStore.java

chainbase/src/main/java/org/tron/core/archive/reader/
  ArchiveStatePoint.java
  ResolvedArchiveStatePoint.java
  ArchiveReadResult.java
  ArchiveReaderException.java
  ArchiveStateReader.java
  DefaultArchiveStateReader.java
  ArchiveStateReaderFactory.java
  ArchiveStorageKeyCodec.java

chainbase/src/main/java/org/tron/core/archive/commitment/
  ArchiveCommitmentBuilder.java
  DefaultArchiveCommitmentBuilder.java
  NoopArchiveCommitmentBuilder.java
  ArchiveCommitmentTree.java
  SparseMerkleArchiveCommitmentTree.java
  ArchiveCommitmentContext.java
  ArchiveRootRecord.java
  ArchiveRootStore.java
  ArchiveRootCoverage.java
  ArchiveCommitmentVerifier.java
```

### 4.3 actuator

```text
actuator/src/main/java/org/tron/core/vm/repository/
  // semantic storage hook 接入点，仍只依赖 chainbase archive API
```

### 4.4 framework

```text
framework/src/main/java/org/tron/core/db/Manager.java
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java
framework/src/main/java/org/tron/core/services/jsonrpc/types/BlockResult.java
```

`framework` 负责 lifecycle/RPC wiring，不承载 archive core 数据结构。

## 5. Landing slices

### S0：基线守护

目标：固定当前 4e80 基线、确认 java-tron 工作区干净。

证明：

```bash
git -C /Users/boson/IdeaProjects/java-tron rev-parse --short=12 HEAD
git -C /Users/boson/IdeaProjects/java-tron status --short
rg -n '^(<<<<<<< .+|=======$|>>>>>>> .+)' /Users/boson/IdeaProjects/java-tron
```

停止条件：HEAD 是 `4e80f8ffa9a2`、status empty、冲突标记扫描无命中。

### S1：配置 + no-op archive service

详细编码包：[java-tron Archive S1/S2：4e80 编码执行包](./20260603-java-tron-archive-s1-s2-4e80-coding-packet.md)。

目标：archive 默认关闭，新增配置和 no-op service，不改变 block apply 行为。

主要改动：

| 文件 | 改动 |
| --- | --- |
| `common/src/main/resources/reference.conf` | 增加 `storage.archive.*` 默认 false |
| `framework/src/main/resources/config.conf` | 增加用户可见 archive 示例，默认 false |
| `StorageConfig.java` | 增加 `ArchiveConfig` 嵌套 bean |
| `Storage.java` | 增加 archive runtime 字段 |
| `Args.java:212-244` | `applyStorageConfig` 桥接 archive 配置 |
| `TronStoreWithRevoking.java:78-80` | `getDbName()` 改为代理底层 DB name |
| `chainbase/src/main/java/org/tron/core/archive/*` | `ArchiveConfig/ArchiveService/NoopArchiveService/ArchivePhase` |

测试：

- `StorageConfig` 默认值和 override 测试。
- `Args` 初始化后 `CommonParameter.storage.archive.enable=false`。
- `TronStoreWithRevoking.getDbName()` 返回底层 DB name。
- `ArchiveService` no-op 调用不写 DB、不改变状态。

停止条件：archive disabled 时现有 fullnode 行为不变。

### S2：Manager lifecycle + in-memory txNum

详细编码包：[java-tron Archive S1/S2：4e80 编码执行包](./20260603-java-tron-archive-s1-s2-4e80-coding-packet.md)。

收窄代码级包：[java-tron Archive L2：Manager lifecycle + txNum 代码级执行包](./20260605-java-tron-archive-l2-manager-txnum-4e80-code-plan.md)。

目标：建立交易级时间坐标，不采集写集、不落 temporal DB。

主要改动：

| 文件 | 改动 |
| --- | --- |
| `ArchiveExecutionContext` | 当前 block/phase/txIndex/txNum |
| `ArchiveTxNumIndex` | 分配和查询 block txNum range |
| `InMemoryArchiveTxNumIndex` | 测试用实现 |
| `DefaultArchiveService` | `beginBlock/commitBlock/abortBlock/unwindBlock/beginTx/endTx` |
| `Manager.java` | normal/replay/recovery/erase lifecycle hook |
| `Manager.processBlock` | `BLOCK_PREPARE`、`USER_TX`、`BLOCK_FINALIZE` phase |

关键顺序：

```text
normal pushBlock:
  archive.beginBlock(newBlock)
  try (session) {
    applyBlock(newBlock, txs)
    session.commit()
  }
  archive.commitBlock(newBlock)     // before blockTrigger
  blockTrigger(...)

failure:
  archive.abortBlock(newBlock)

eraseBlock:
  oldHead = current head
  khaosDb.pop()
  revokingStore.fastPop()
  archive.unwindBlock(oldHead)
```

测试：

- 多交易 block：txNum 顺序为 prepare、user tx 0..N-1、finalize。
- 空块：仍有 prepare/finalize。
- apply 失败：pending txNum 不提交。
- fork replay/recovery：txNum range 连续。
- eraseBlock：archive unwind 到 old head parent。

停止条件：后续模块可以通过 `ArchiveExecutionContext.current()` 拿到 txNum。

### S3：ArchiveDomainRegistry

详细编码包：[java-tron Archive S3：ArchiveDomainRegistry 4e80 编码执行包](./20260603-java-tron-archive-s3-domain-registry-4e80-coding-packet.md)。

收窄代码级包：[java-tron Archive L3：ArchiveDomainRegistry 代码级执行包](./20260605-java-tron-archive-l3-domain-registry-4e80-code-plan.md)。

目标：把 java-tron Store 映射成 archive domains，输出 codec/root/history/raw hook policy。

P0 domains：

| Domain | 来源 | key | value | hook |
| --- | --- | --- | --- | --- |
| `ACCOUNT` | `AccountStore` | address | `AccountCapsule.getData()` | generic |
| `CONTRACT` | `ContractStore` | address | `ContractCapsule.getData()` | store-specific |
| `CODE` | `CodeStore` | address | bytecode | generic |
| `CONTRACT_STORAGE` | semantic hook | address + slot | slot value/tombstone | semantic-only |
| `DYNAMIC_PROPERTIES` | allowlist | property key | raw bytes | generic allowlist |

测试：

- 每个 P0 Store 能查到 descriptor。
- `storage-row` physical key 被排除。
- unknown DB 默认 ignored。
- root policy 和 history policy 可独立配置。

停止条件：Collector 不再散落硬编码 domain 判断。

### S4：WriteCollector raw hooks

详细编码包：[java-tron Archive S4/S5：WriteCollector 与 Storage Semantic Hook 4e80 编码执行包](./20260603-java-tron-archive-s4-s5-write-collector-storage-4e80-coding-packet.md)。

代码级细化：[java-tron Archive L4：WriteCollector + Storage Semantic Hook 代码级执行包](./20260605-java-tron-archive-l4-write-collector-4e80-code-plan.md)。

目标：收集 first-before/final-after 的 deterministic `BlockWriteSet`。

主要改动：

| 文件 | 改动 |
| --- | --- |
| `TronStoreWithRevoking.put/delete` | generic hook |
| `ContractStore/AbiStore/ContractStateStore` | store-specific hook |
| `ArchiveWriteCollector` | begin/end tx、record put/delete、compress |
| `BlockWriteSet` | blockNum、txNum range、writes |

测试：

- 同一 txNum 多次写同 key，只保留 first-before/final-after。
- put 后 delete 变 tombstone。
- archive disabled 时 hook 是 no-op。
- store-specific hook 不漏 contract/code 相关写。

停止条件：S6/S11 可以只消费 `BlockWriteSet`，不用扫描 latest Store。

### S5：Contract storage semantic hook

详细编码包：[java-tron Archive S4/S5：WriteCollector 与 Storage Semantic Hook 4e80 编码执行包](./20260603-java-tron-archive-s4-s5-write-collector-storage-4e80-coding-packet.md)。

代码级细化：[java-tron Archive L4：WriteCollector + Storage Semantic Hook 代码级执行包](./20260605-java-tron-archive-l4-write-collector-4e80-code-plan.md)。

目标：捕获 `(contractAddress, slot)`，不使用不可逆 `storage-row` 物理 key。

主要改动：

| 文件 | 改动 |
| --- | --- |
| `actuator/.../Storage.java` | dirty slot commit 前后记录 semantic key |
| `ArchiveDomainRegistry` | `CONTRACT_STORAGE` 标为 `SEMANTIC_ONLY` |
| `ArchiveWriteCollector` | 接收 semantic write |

测试：

- 同一合约同一 slot 多次写压缩正确。
- 不同合约同 slot 不冲突。
- delete/zero 值 tombstone 语义明确。
- raw `storage-row` hook 不进入 root/history。

停止条件：`eth_getStorageAt(historical)` 有可逆 key 来源。

### S6：ArchiveRawStore + temporal codecs

详细编码包：[java-tron Archive S6/S7：ArchiveTemporalStore 4e80 编码执行包](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md)。

代码级细化：[java-tron Archive L5：ArchiveTemporalStore 代码级执行包](./20260605-java-tron-archive-l5-temporal-store-4e80-code-plan.md)。

目标：建立 single physical `archive` DB 和 temporal key/value 编码。

逻辑表：

```text
LATEST
HISTORY
CHANGESET
TXNUM_BLOCK
TXNUM_META
TXNUM_BY_TXID
PROGRESS
ROOT_RECORD
COMMITMENT_BRANCH
COMMITMENT_META
```

测试：

- key codec lexicographic order 稳定。
- batch put/delete 支持 null tombstone。
- `getAsOf(domain,key,txNum)` 对 put/update/delete 正确。
- progress 不越过实际写入。

停止条件：可以从 `BlockWriteSet` 原子写入 latest/history/changeset/progress。

### S7：Temporal commit/unwind/startup

详细编码包：[java-tron Archive S6/S7：ArchiveTemporalStore 4e80 编码执行包](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md)。

代码级细化：[java-tron Archive L5：ArchiveTemporalStore 代码级执行包](./20260605-java-tron-archive-l5-temporal-store-4e80-code-plan.md)。

目标：让 archive temporal store 跟随 canonical commit 和 unwind。

测试：

- normal commit 后 latest/history/change index 都存在。
- apply 失败无 archive 写入。
- eraseBlock 后 latest 恢复、history/change/root/progress 回退。
- startup verifier 发现 progress/head 不一致时 fail fast 或进入 repair-needed。

停止条件：archive sidecar 与 canonical head 不分叉。

### S8：ArchiveStateReader core

详细编码包：[java-tron Archive S8/S9：ArchiveStateReader 与 JSON-RPC Historical Getters 4e80 编码执行包](./20260603-java-tron-archive-s8-s9-state-reader-rpc-4e80-coding-packet.md)。

代码级细化：[java-tron Archive L6：ArchiveStateReader + historical getters 代码级执行包](./20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md)。

目标：封装 historical account/code/storage 读取，不碰 JSON-RPC。

测试：

- `getAccount(address, blockNum/txNum)` 读旧余额。
- `getCode(address, blockNum/txNum)` 在创建前后正确。
- `getStorage(contract,slot,blockNum/txNum)` 读旧 slot。
- missing 语义：balance 0、code empty、storage zero。

停止条件：reader 不读取 latest Store 推断历史值。

### S9：JSON-RPC historical getters

详细编码包：[java-tron Archive S8/S9：ArchiveStateReader 与 JSON-RPC Historical Getters 4e80 编码执行包](./20260603-java-tron-archive-s8-s9-state-reader-rpc-4e80-coding-packet.md)。

代码级细化：[java-tron Archive L6：ArchiveStateReader + historical getters 代码级执行包](./20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md)。

目标：替换 `eth_getBalance/getCode/getStorageAt` non-latest 分支。

主要改动：

| 文件 | 改动 |
| --- | --- |
| `TronJsonRpcImpl.java:459` | `eth_getBalance` non-latest 走 archive |
| `TronJsonRpcImpl.java:613` | `eth_getStorageAt` non-latest 走 archive |
| `TronJsonRpcImpl.java:637` | `eth_getCode` non-latest 走 archive |
| `requireLatestBlockTag` | 仍用于 `eth_call` 等未接入方法 |

测试：

- block N/N+1 状态不同，查 N 返回旧值。
- archive disabled 时 historical 参数返回明确 unsupported。
- latest 行为保持原路径。

停止条件：三个 historical getter 不再被 `requireLatestBlockTag` 拒绝。

### S10：Sparse Merkle tree core + root codecs

详细编码包：[java-tron Archive S10/S11：CommitmentBuilder 4e80 编码执行包](./20260603-java-tron-archive-s10-s11-commitment-builder-4e80-coding-packet.md)。

代码级细化：[java-tron Archive L7：CommitmentBuilder 代码级执行包](./20260605-java-tron-archive-l7-commitment-builder-4e80-code-plan.md)。

目标：实现 archive-native commitment tree，不依赖 `framework TrieImpl`。

测试：

- empty root 稳定。
- updates 输入顺序不同 root 相同。
- hashed key 排序是唯一生产路径。
- domain-separated hash 不混用 `Hash.EMPTY_TRIE_HASH`。
- put/update/delete/tombstone proof 可验证。

停止条件：tree core 可在内存和 batch overlay 上运行。

### S11：CommitmentBuilder integration + rebuild verifier

详细编码包：[java-tron Archive S10/S11：CommitmentBuilder 4e80 编码执行包](./20260603-java-tron-archive-s10-s11-commitment-builder-4e80-coding-packet.md)。

代码级细化：[java-tron Archive L7：CommitmentBuilder 代码级执行包](./20260605-java-tron-archive-l7-commitment-builder-4e80-code-plan.md)。

目标：把 S10 接入 block commit，与 temporal rows 同 batch 写 root record 和 branch state。

主要约束：

- root record 和 commitment branch state 同 batch 写入。
- root 覆盖范围写入 `coverage`。
- 每 block 持久化 `ROOT_BY_BLOCK`。
- `ROOT_BY_TX` 默认不持久化，但 `rootAtTxNum` 必须可通过 `CHANGESET` on-demand 计算。
- unwind 必须回退 branch state，不只删除 root hash。

测试：

- block root 可重放。
- root rebuild verifier 与落盘 root 一致。
- fork unwind 后 root current 回到 parent。
- 中间 tx root 通过 changeset replay 计算，覆盖 A -> B -> A 场景。
- archive root 不写 `accountStateRoot`。

停止条件：sidecar root 可验证且不参与共识 header。

### S12/S13：historical eth_call

详细编码包：[java-tron Archive S12/S13：historical eth_call 4e80 编码执行包](./20260604-java-tron-archive-s12-s13-historical-eth-call-4e80-coding-packet.md)。

代码级执行包：[java-tron Archive L8：historical eth_call 代码级执行包](./20260605-java-tron-archive-l8-historical-eth-call-4e80-code-plan.md)。

目标：在 archive-backed Repository 上运行 historical `eth_call`。

P0 之前不允许静默 fallback latest。若未完成 S12/S13，`eth_call(non-latest)` 必须继续明确 unsupported。

测试：

- historical account/code/storage overlay 生效。
- constant call 不写 archive。
- dynamic properties 使用 historical view 或明确声明 latest-only 限制。
- object-form `blockNumber`/`blockHash` 不再校验后重写为 latest。
- historical VMConfig static flags 在调用结束后恢复。

### S14：proof/debug API

详细编码包：[java-tron Archive S14：proof/debug API 4e80 编码执行包](./20260604-java-tron-archive-s14-proof-debug-api-4e80-coding-packet.md)。

代码级执行包：[java-tron Archive L9：proof/debug API 代码级执行包](./20260605-java-tron-archive-l9-proof-debug-api-4e80-code-plan.md)。

目标：暴露 archive-native debug API。

允许：

```text
debug_getArchiveRoot
debug_getArchiveProof
debug_verifyArchiveProof
```

不允许：

- 不实现伪 Ethereum `eth_getProof`。
- 不把 archive proof 解释成 block header proof。
- 不在 Solidity/PBFT JSON-RPC 端点暴露该能力；L9 首版只允许 FullNode 且 `storage.archive.debug.enable=true`。
- 不打开 `VMConfig.vmTrace`，不产生 `./vm_trace` 文件。

## 6. 验证矩阵

| 条件 | 证明 |
| --- | --- |
| 默认关闭不改变行为 | no-op tests + Manager/RPC latest 回归 |
| txNum 覆盖 user/system phase | `ArchiveTxNumIndexTest` + Manager lifecycle test |
| domain 映射集中 | `ArchiveDomainRegistryTest` |
| write set deterministic | `DefaultArchiveWriteCollectorTest` |
| storage semantic key 可逆 | contract storage semantic test |
| temporal batch 原子 | `DefaultArchiveTemporalStoreTest` |
| unwind 正确 | `DefaultArchiveTemporalStoreUnwindTest` + `ArchiveTemporalStoreManagerWiringTest` |
| historical getters 不读 latest | `DefaultArchiveStateReaderTest` + `TronJsonRpcHistoricalGettersTest` |
| sidecar root 可重放 | `DefaultArchiveCommitmentBuilderTest` + `ArchiveCommitmentRebuildVerifierTest` |
| 不写 header root | `BlockCapsule`/`BlockResult` regression |
| proof/debug 是 archive-native | `ArchiveProofVerifierTest` + `TronJsonRpcArchiveDebugTest` |

建议 gate：

```bash
./gradlew :common:test --tests '*Storage*'
./gradlew :chainbase:test --tests '*Archive*'
./gradlew :framework:test --tests '*Archive*'
./gradlew :framework:test --tests '*JsonRpc*'
./gradlew checkstyleMain checkstyleTest
./gradlew lint
```

实际 test task 名称要以 java-tron 当前 Gradle module 为准。涉及 Java 代码提交前必须跑 lint；失败测试不能加 skip。

## 7. 编码顺序

推荐先落地：

1. S1：配置/no-op/dbName 修复。
2. S2：Manager lifecycle + in-memory txNum。
3. S3：DomainRegistry。
4. S4/S5：WriteCollector + storage semantic hook。
5. S6/S7：TemporalStore commit/unwind。
6. S8/S9：StateReader + JSON-RPC historical getters。
7. S10/S11：CommitmentBuilder + verifier。
8. S12/S13：historical eth_call。
9. S14/L9：proof/debug。

每个 slice 都应能独立合入。不要一次把 Manager、Store hook、temporal DB、JSON-RPC、root 全部堆到一个 patch。

## 8. 不变量

- Archive 默认关闭。
- Archive hot path 只在 canonical block apply/replay/recovery/unwind 上工作。
- Pending tx、broadcast validation、constant call 不进入 archive。
- Store hook 只收集 pending write set，不直接持久化 archive DB。
- Archive flush 只发生在 revoking session commit 成功后。
- `ArchiveTemporalStore` 和 `CommitmentBuilder` 使用同一 archive batch。
- Historical reader 不从 latest Store 猜历史值。
- Commitment updates 必须按 hashed key 稳定排序。
- Archive root 是 sidecar root，不是 `accountStateRoot` 或 `txTrieRoot`。
- Root coverage、algorithm id、schema version 必须写入 root record。
