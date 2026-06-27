# java-tron Archive：4e80 逐文件实现落点矩阵

日期：2026-06-04

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 当前基线：`4e80f8ffa9a2`

上游总装计划：[java-tron Archive：4e80 完整实现总装计划](./20260604-java-tron-archive-4e80-implementation-assembly-plan.md)

测试与验收：[java-tron Archive：4e80 模块测试与验收计划](./20260604-java-tron-archive-4e80-test-verification-plan.md)

落地执行看板：[java-tron Archive：4e80 分阶段落地执行看板](./20260604-java-tron-archive-4e80-landing-readiness-board.md)

L1 代码级执行包：[java-tron Archive L1：config/no-op/dbName 代码级执行包](./20260604-java-tron-archive-l1-config-noop-dbname-4e80-code-plan.md)

L2 代码级执行包：[java-tron Archive L2：Manager lifecycle + txNum 代码级执行包](./20260605-java-tron-archive-l2-manager-txnum-4e80-code-plan.md)

L3 代码级执行包：[java-tron Archive L3：ArchiveDomainRegistry 代码级执行包](./20260605-java-tron-archive-l3-domain-registry-4e80-code-plan.md)

L4 代码级执行包：[java-tron Archive L4：WriteCollector + Storage Semantic Hook 代码级执行包](./20260605-java-tron-archive-l4-write-collector-4e80-code-plan.md)

L5 代码级执行包：[java-tron Archive L5：ArchiveTemporalStore 代码级执行包](./20260605-java-tron-archive-l5-temporal-store-4e80-code-plan.md)

L6 代码级执行包：[java-tron Archive L6：ArchiveStateReader + historical getters 代码级执行包](./20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md)

L7 代码级执行包：[java-tron Archive L7：CommitmentBuilder 代码级执行包](./20260605-java-tron-archive-l7-commitment-builder-4e80-code-plan.md)

本文把 S1-S14 细化为 java-tron 物理文件落点。它用于后续编码分支拆任务、review diff、检查包依赖方向，避免把 archive core、Manager lifecycle、TVM、JSON-RPC 和 proof/debug 混进同一个不可审查 patch。

## 1. 当前模块事实

`settings.gradle` 当前模块：

```text
common
protocol
crypto
chainbase
actuator
consensus
framework
plugins
platform
errorprone
example:actuator-example
```

与 archive 相关的依赖方向：

```text
common
  <- chainbase
  <- actuator
  <- framework

chainbase
  <- actuator
  <- framework

actuator
  <- framework
```

放置规则：

- 配置 bean 放 `common`。
- archive core、domain、collector、temporal、commitment、proof core 放 `chainbase`。
- TVM repository adapter 和 historical constant call executor 放 `actuator`。
- Manager lifecycle 和 JSON-RPC wiring 放 `framework`。
- `chainbase` 不得 import `framework` 或 `actuator`。
- `actuator` 不得依赖 `framework` JSON-RPC 类型。
- `framework` adapter 只做参数解析、异常映射和 service 调用。

当前测试目录事实：

```text
common/src/test/java exists
framework/src/test/java exists
actuator/src/test/java exists
chainbase/src/test does not exist yet
```

`chainbase/build.gradle` 已定义 `test { ... }`，因此后续可以新增：

```text
chainbase/src/test/java/org/tron/core/archive/...
```

## 2. L1：配置/no-op/dbName

来源：[S1/S2 4e80 编码执行包](./20260603-java-tron-archive-s1-s2-4e80-coding-packet.md)

代码级细化：[L1 config/no-op/dbName 代码级执行包](./20260604-java-tron-archive-l1-config-noop-dbname-4e80-code-plan.md)

### 修改文件

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `common/src/main/resources/reference.conf` | modify | 在 `storage` 节新增 `archive` 默认配置，所有开关默认 false |
| `framework/src/main/resources/config.conf` | modify | 用户可见示例，同步默认 false |
| `common/src/main/java/org/tron/core/config/args/StorageConfig.java` | modify | 新增 `ArchiveConfig`、`ArchiveHistoryConfig`、`ArchiveCommitmentConfig`、`ArchiveDebugConfig` 嵌套 bean 和 post-process |
| `common/src/main/java/org/tron/core/config/args/Storage.java` | modify | runtime storage config 增加 archive 字段和 getter/setter |
| `common/src/main/java/org/tron/common/parameter/CommonParameter.java` | modify | 继续通过 `storage` 暴露 archive runtime config，不新增全局散字段 |
| `framework/src/main/java/org/tron/core/config/args/Args.java` | modify | 在 `applyStorageConfig(StorageConfig)` 桥接 archive config |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java` | modify | `getDbName()` 从底层 data source/RevokingDB 返回真实 DB name，不再返回 null |

### 新增文件

| 文件 | 说明 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/archive/ArchiveService.java` | no-op service 接口，定义 lifecycle/hook/read/root/proof 的最小边界 |
| `chainbase/src/main/java/org/tron/core/archive/NoopArchiveService.java` | archive disabled 默认实现 |
| `chainbase/src/main/java/org/tron/core/archive/ArchiveServiceFactory.java` | 根据 config 创建 noop/default service |
| `chainbase/src/main/java/org/tron/core/archive/ArchivePhase.java` | prepare/user/finalize/unwind phase enum |
| `chainbase/src/main/java/org/tron/core/archive/ArchiveException.java` | archive core checked/runtime exception 基类，按当前 java-tron 异常风格决定 |

### 测试文件

| 文件 | 说明 |
| --- | --- |
| `common/src/test/java/org/tron/core/config/args/StorageConfigTest.java` | 扩展默认值、override、非法限额测试 |
| `chainbase/src/test/java/org/tron/core/archive/NoopArchiveServiceTest.java` | 新建；证明 no-op 不写 DB、不抛异常 |
| `framework/src/test/java/org/tron/core/db/TronStoreWithRevokingArchiveTest.java` | 新建或合并现有 Store 测试；证明 dbName 可用 |

### 验收

```bash
./gradlew :common:test --tests '*StorageConfigTest'
./gradlew :chainbase:test --tests '*NoopArchiveServiceTest'
./gradlew :framework:test --tests '*TronStoreWithRevokingArchiveTest'
```

## 3. L2：Manager lifecycle + txNum

来源：[S1/S2 4e80 编码执行包](./20260603-java-tron-archive-s1-s2-4e80-coding-packet.md)

代码级细化：[L2 Manager lifecycle + txNum 代码级执行包](./20260605-java-tron-archive-l2-manager-txnum-4e80-code-plan.md)

### 修改文件

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `framework/src/main/java/org/tron/core/db/Manager.java` | modify | 在 normal push/apply、fork replay、recovery、eraseBlock 接入 archive lifecycle；tx loop 可借鉴产块 per-tx nested session 做 archive checkpoint，但不能跳过 canonical failed tx |
| `chainbase/src/main/java/org/tron/core/archive/ArchiveService.java` | modify | 增加 `beginBlock`、`beginTx`、`endTx`、`commitBlock`、`abortBlock`、`unwindBlock` |
| `chainbase/src/main/java/org/tron/core/archive/NoopArchiveService.java` | modify | 实现新增 lifecycle 方法 |
| `chainbase/src/main/java/org/tron/core/archive/ArchivePhase.java` | modify | 固定 `BLOCK_PREPARE`、`USER_TX`、`BLOCK_FINALIZE`、`UNWIND` |

### 新增文件

| 文件 | 说明 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/archive/ArchiveExecutionContext.java` | 当前 block/phase/txIndex/txNum/source context |
| `chainbase/src/main/java/org/tron/core/archive/ArchiveExecutionContextHolder.java` | ThreadLocal 或 scoped holder；必须清理 |
| `chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveTxNumIndex.java` | txNum 分配/查询接口 |
| `chainbase/src/main/java/org/tron/core/archive/txnum/InMemoryArchiveTxNumIndex.java` | L2 测试实现 |
| `chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveTxNumRange.java` | block range value object |
| `chainbase/src/main/java/org/tron/core/archive/ArchiveSource.java` | NORMAL/REPLAY/RECOVERY/UNWIND |

### 测试文件

| 文件 | 说明 |
| --- | --- |
| `chainbase/src/test/java/org/tron/core/archive/txnum/ArchiveTxNumIndexTest.java` | txNum monotonic/range tests |
| `framework/src/test/java/org/tron/core/db/ManagerArchiveLifecycleTest.java` | normal/failure/replay/unwind lifecycle |
| `framework/src/test/java/org/tron/core/db/ManagerArchiveContextCleanupTest.java` | failure 后 context cleared |
| `framework/src/test/java/org/tron/core/db/ManagerArchiveTxScopedSessionTest.java` | tx-scoped checkpoint mirrors producer session shape without changing push/apply failure semantics |

### 验收

```bash
./gradlew :chainbase:test --tests '*ArchiveTxNumIndexTest'
./gradlew :framework:test --tests '*ManagerArchive*Test'
```

## 4. L3：ArchiveDomainRegistry

来源：[S3 ArchiveDomainRegistry 4e80 编码执行包](./20260603-java-tron-archive-s3-domain-registry-4e80-coding-packet.md)

代码级细化：[L3 ArchiveDomainRegistry 代码级执行包](./20260605-java-tron-archive-l3-domain-registry-4e80-code-plan.md)

### 修改文件

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `chainbase/src/main/java/org/tron/core/ChainBaseManager.java` | inspect/minimal modify | 如需集中暴露 Store inventory，只加只读访问，不把 registry 逻辑写进 manager |
| `chainbase/src/main/java/org/tron/core/store/AccountStore.java` | inspect | registry 绑定 ACCOUNT domain |
| `chainbase/src/main/java/org/tron/core/store/ContractStore.java` | inspect | registry 绑定 CONTRACT store-specific hook |
| `chainbase/src/main/java/org/tron/core/store/CodeStore.java` | inspect | registry 绑定 CODE domain |
| `chainbase/src/main/java/org/tron/core/store/DynamicPropertiesStore.java` | inspect | registry 绑定 allowlist DYNAMIC_PROPERTIES |
| `chainbase/src/main/java/org/tron/core/store/StorageRowStore.java` | inspect | raw storage-row 排除，后续只走 semantic storage |

### 新增文件

| 文件 | 说明 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/archive/domain/ArchiveDomainRegistry.java` | registry 入口 |
| `chainbase/src/main/java/org/tron/core/archive/domain/DefaultArchiveDomainRegistry.java` | 当前 P0 domain definitions |
| `chainbase/src/main/java/org/tron/core/archive/domain/ArchiveDomainDescriptor.java` | domain metadata |
| `chainbase/src/main/java/org/tron/core/archive/domain/ArchiveDomainId.java` | numeric ids；0 reserved global |
| `chainbase/src/main/java/org/tron/core/archive/domain/ArchiveDomainName.java` | enum/string constants |
| `chainbase/src/main/java/org/tron/core/archive/domain/ArchiveRootPolicy.java` | IN_GLOBAL_ROOT / DOMAIN_ROOT_ONLY / HISTORY_ONLY / EXCLUDED（4 值，2026-06-26 决策 1）；`DYNAMIC_PROPERTIES` 还需要 key-level policy |
| `chainbase/src/main/java/org/tron/core/archive/domain/ArchiveHistoryPolicy.java` | HISTORY / LATEST_ONLY / IGNORED |
| `chainbase/src/main/java/org/tron/core/archive/domain/ArchiveHookPolicy.java` | GENERIC / STORE_SPECIFIC / SEMANTIC_ONLY / IGNORED |
| `chainbase/src/main/java/org/tron/core/archive/domain/ArchiveCoverage.java` | PARTIAL_TRON_DOMAINS 等 |
| `chainbase/src/main/java/org/tron/core/archive/domain/RegistryChecksum.java` | deterministic checksum |
| `chainbase/src/main/java/org/tron/core/archive/codec/ArchiveKeyCodec.java` | key codec interface |
| `chainbase/src/main/java/org/tron/core/archive/codec/ArchiveValueCodec.java` | value codec interface |
| `chainbase/src/main/java/org/tron/core/archive/codec/AccountKeyCodec.java` | ACCOUNT key codec |
| `chainbase/src/main/java/org/tron/core/archive/codec/ContractKeyCodec.java` | CONTRACT key codec |
| `chainbase/src/main/java/org/tron/core/archive/codec/CodeKeyCodec.java` | CODE key codec |
| `chainbase/src/main/java/org/tron/core/archive/codec/ContractStorageKeyCodec.java` | `(address, slot)` codec |
| `chainbase/src/main/java/org/tron/core/archive/codec/DynamicPropertyKeyCodec.java` | dynamic property allowlist key codec |

### 测试文件

| 文件 | 说明 |
| --- | --- |
| `chainbase/src/test/java/org/tron/core/archive/domain/ArchiveDomainRegistryTest.java` | domain inventory/policies/checksum |
| `chainbase/src/test/java/org/tron/core/archive/codec/ArchiveKeyCodecTest.java` | account/contract/code/storage key round-trip |
| `chainbase/src/test/java/org/tron/core/archive/domain/ArchiveCoverageTest.java` | coverage response semantics |

### 验收

```bash
./gradlew :chainbase:test --tests '*ArchiveDomain*Test'
./gradlew :chainbase:test --tests '*ArchiveKeyCodec*Test'
```

## 5. L4：WriteCollector + storage semantic hook

来源：[S4/S5 WriteCollector 与 Storage Semantic Hook 4e80 编码执行包](./20260603-java-tron-archive-s4-s5-write-collector-storage-4e80-coding-packet.md)

代码级细化：[L4 WriteCollector + Storage Semantic Hook 代码级执行包](./20260605-java-tron-archive-l4-write-collector-4e80-code-plan.md)

### 修改文件

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java` | modify | generic `put/delete` hook |
| `chainbase/src/main/java/org/tron/core/store/ContractStore.java` | modify | store-specific hook for direct revokingDB writes |
| `chainbase/src/main/java/org/tron/core/store/AbiStore.java` | modify | store-specific hook for direct `revokingDB.put(byte[], byte[])` |
| `chainbase/src/main/java/org/tron/core/store/ContractStateStore.java` | modify | store-specific hook for direct revokingDB writes |
| `chainbase/src/main/java/org/tron/core/store/CodeStore.java` | inspect | confirm generic hook catches code bytes |
| `chainbase/src/main/java/org/tron/core/store/StorageRowStore.java` | modify | expose raw before-value helper for semantic storage hook |
| `actuator/src/main/java/org/tron/core/vm/program/Storage.java` | modify | emit semantic `(contractAddress, slot)` write before dirty commit |
| `chainbase/src/main/java/org/tron/core/archive/ArchiveService.java` | modify | add raw/semantic write hook methods |
| `chainbase/src/main/java/org/tron/core/archive/NoopArchiveService.java` | modify | hook methods no-op |
| `framework/src/main/java/org/tron/core/db/Manager.java` | modify | add tx-scoped collector checkpoint around canonical tx loop and VM retry checkpoint/rollback calls around retry path |

### 新增文件

| 文件 | 说明 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/archive/write/ArchiveWriteCollector.java` | collector interface |
| `chainbase/src/main/java/org/tron/core/archive/write/DefaultArchiveWriteCollector.java` | compression implementation |
| `chainbase/src/main/java/org/tron/core/archive/write/ArchiveValue.java` | immutable value/tombstone wrapper |
| `chainbase/src/main/java/org/tron/core/archive/write/RawStoreWriteEvent.java` | generic raw store event |
| `chainbase/src/main/java/org/tron/core/archive/write/SemanticStoreWrite.java` | semantic storage event |
| `chainbase/src/main/java/org/tron/core/archive/write/BlockWriteSet.java` | block-level output |
| `chainbase/src/main/java/org/tron/core/archive/write/TxWriteSet.java` | tx-level output |
| `chainbase/src/main/java/org/tron/core/archive/write/TxWriteMeta.java` | txNum/phase/source metadata |
| `chainbase/src/main/java/org/tron/core/archive/write/DomainWrite.java` | domain/key before/after |
| `chainbase/src/main/java/org/tron/core/archive/write/ArchiveWriteOp.java` | PUT/DELETE |
| `chainbase/src/main/java/org/tron/core/archive/write/WriteCollectStats.java` | diagnostics and retry counters |

### 测试文件

| 文件 | 说明 |
| --- | --- |
| `chainbase/src/test/java/org/tron/core/archive/write/DefaultArchiveWriteCollectorTest.java` | deterministic compression |
| `framework/src/test/java/org/tron/core/db/ArchiveGenericStoreHookTest.java` | generic hooks |
| `framework/src/test/java/org/tron/core/db/ArchiveSpecialStoreHookTest.java` | direct revokingDB store-specific hooks |
| `framework/src/test/java/org/tron/core/db/ArchiveRetryLifecycleTest.java` | retry checkpoint semantics |
| `framework/src/test/java/org/tron/core/db/ArchiveTxScopedCollectorCheckpointTest.java` | tx begin/end/abort collector checkpoint semantics |
| `actuator/src/test/java/org/tron/core/vm/program/ArchiveStorageSemanticHookTest.java` | `(address, slot)` semantic writes |

### 验收

```bash
./gradlew :chainbase:test --tests '*DefaultArchiveWriteCollectorTest'
./gradlew :framework:test --tests '*ArchiveGenericStoreHookTest'
./gradlew :framework:test --tests '*ArchiveSpecialStoreHookTest'
./gradlew :framework:test --tests '*ArchiveRetryLifecycleTest'
./gradlew :actuator:test --tests '*ArchiveStorageSemanticHookTest'
```

## 6. L5：Temporal persistence

来源：[S6/S7 ArchiveTemporalStore 4e80 编码执行包](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md)

代码级细化：[L5 ArchiveTemporalStore 代码级执行包](./20260605-java-tron-archive-l5-temporal-store-4e80-code-plan.md)

### 修改文件

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `framework/src/main/java/org/tron/core/db/Manager.java` | modify | commit/unwind 调 `DefaultArchiveService` persistence |
| `chainbase/src/main/java/org/tron/core/archive/ArchiveService.java` | modify | expose committed write-set/progress APIs only if needed |
| `chainbase/src/main/java/org/tron/core/archive/ArchiveServiceFactory.java` | modify | create persistent service when enabled |
| `chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java` | modify | finish collector block and apply temporal store |
| `chainbase/src/main/java/org/tron/core/archive/NoopArchiveService.java` | modify | keep temporal methods no-op |

### 新增文件

| 文件 | 说明 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveRawStore.java` | single physical archive DB abstraction |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveBatch.java` | atomic batch |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveDbFactory.java` | LevelDB/RocksDB creation |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveTable.java` | logical prefix enum |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveStoreKeyCodec.java` | LATEST/HISTORY/CHANGESET key codec |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveValueCodec.java` | tombstone/present value codec |
| `chainbase/src/main/java/org/tron/core/archive/store/LatestValueCodec.java` | latest value codec |
| `chainbase/src/main/java/org/tron/core/archive/store/TxNumMetaCodec.java` | txNum metadata codec |
| `chainbase/src/main/java/org/tron/core/archive/store/BlockTxNumRangeCodec.java` | block range codec |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveProgress.java` | progress record |
| `chainbase/src/main/java/org/tron/core/archive/temporal/ArchiveTemporalStore.java` | temporal API |
| `chainbase/src/main/java/org/tron/core/archive/temporal/DefaultArchiveTemporalStore.java` | apply/getAsOf/unwind implementation |
| `chainbase/src/main/java/org/tron/core/archive/temporal/VersionedValue.java` | latest value with lastTxNum |
| `chainbase/src/main/java/org/tron/core/archive/temporal/ArchiveTemporalException.java` | gap/corrupt exceptions |
| `chainbase/src/main/java/org/tron/core/archive/txnum/PersistentArchiveTxNumIndex.java` | TXNUM_BLOCK/TXNUM_META/TXNUM_BY_TXID backed index |
| `chainbase/src/main/java/org/tron/core/archive/startup/ArchiveStartupVerifier.java` | startup consistency checks |
| `chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java` | persistent service implementation |

### 测试文件

| 文件 | 说明 |
| --- | --- |
| `chainbase/src/test/java/org/tron/core/archive/store/ArchiveRawStoreTest.java` | fake/LevelDB/RocksDB batch smoke |
| `chainbase/src/test/java/org/tron/core/archive/store/ArchiveStoreKeyCodecTest.java` | lexicographic temporal key order |
| `chainbase/src/test/java/org/tron/core/archive/store/ArchiveValueCodecTest.java` | value/latest/progress codecs |
| `chainbase/src/test/java/org/tron/core/archive/temporal/DefaultArchiveTemporalStoreTest.java` | put/update/delete/getAsOf |
| `chainbase/src/test/java/org/tron/core/archive/temporal/DefaultArchiveTemporalStoreUnwindTest.java` | unwind latest/history/change |
| `chainbase/src/test/java/org/tron/core/archive/txnum/PersistentArchiveTxNumIndexTest.java` | persisted mapping |
| `chainbase/src/test/java/org/tron/core/archive/startup/ArchiveStartupVerifierTest.java` | progress/head mismatch |
| `framework/src/test/java/org/tron/core/db/ArchiveTemporalStoreManagerWiringTest.java` | Manager commit/unwind integration |

### 验收

```bash
./gradlew :chainbase:test --tests '*ArchiveRawStoreTest'
./gradlew :chainbase:test --tests '*ArchiveStoreKeyCodecTest'
./gradlew :chainbase:test --tests '*ArchiveValueCodecTest'
./gradlew :chainbase:test --tests '*DefaultArchiveTemporalStoreTest'
./gradlew :chainbase:test --tests '*DefaultArchiveTemporalStoreUnwindTest'
./gradlew :chainbase:test --tests '*PersistentArchiveTxNumIndexTest'
./gradlew :chainbase:test --tests '*ArchiveStartupVerifierTest'
./gradlew :framework:test --tests '*ArchiveTemporalStoreManagerWiringTest'
```

## 7. L6：ArchiveStateReader + historical getters

来源：[S8/S9 ArchiveStateReader 与 JSON-RPC Historical Getters 4e80 编码执行包](./20260603-java-tron-archive-s8-s9-state-reader-rpc-4e80-coding-packet.md)

代码级执行包：[java-tron Archive L6：ArchiveStateReader + historical getters 代码级执行包](./20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md)

### 修改文件

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java` | modify | `getTrxBalance`、`getStorageAt`、`getABIOfSmartContract` non-latest 分支接 archive |
| `framework/src/main/java/org/tron/core/services/jsonrpc/JsonRpcApiUtil.java` | inspect/modify | 复用 block tag/number parsing，避免 duplicate parser |
| `framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java` | modify | 外部 JSON-RPC API 不变，Java throws/errors annotation 增加 `JsonRpcInternalException` |

### 新增文件

| 文件 | 说明 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStatePoint.java` | block/tx state point |
| `chainbase/src/main/java/org/tron/core/archive/reader/ResolvedArchiveStatePoint.java` | latest/archive resolved point |
| `chainbase/src/main/java/org/tron/core/archive/reader/ArchiveReadResult.java` | present/missing result wrapper |
| `chainbase/src/main/java/org/tron/core/archive/reader/ArchiveReaderException.java` | archive reader error reason |
| `chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStateReader.java` | historical reader interface |
| `chainbase/src/main/java/org/tron/core/archive/reader/DefaultArchiveStateReader.java` | temporal-backed reader |
| `chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStateReaderFactory.java` | reader factory for framework adapter/tests |
| `chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStorageKeyCodec.java` | `address21 || slot32 || version` codec |
| `framework/src/main/java/org/tron/core/archive/reader/JsonRpcArchiveStatePointResolver.java` | framework resolver using Wallet/BlockStore |
| `framework/src/main/java/org/tron/core/services/jsonrpc/ArchiveJsonRpcStateAdapter.java` | render balance/code/storage results |

### 测试文件

| 文件 | 说明 |
| --- | --- |
| `chainbase/src/test/java/org/tron/core/archive/reader/ArchiveStorageKeyCodecTest.java` | storage semantic key suffix/length |
| `chainbase/src/test/java/org/tron/core/archive/reader/DefaultArchiveStateReaderTest.java` | reader does not read latest |
| `framework/src/test/java/org/tron/core/archive/reader/JsonRpcArchiveStatePointResolverTest.java` | block selector -> archive state point |
| `framework/src/test/java/org/tron/core/services/jsonrpc/ArchiveJsonRpcStateAdapterTest.java` | render semantics |
| `framework/src/test/java/org/tron/core/services/jsonrpc/TronJsonRpcHistoricalGettersTest.java` | balance/code/storage JSON-RPC integration |
| `framework/src/test/java/org/tron/core/jsonrpc/JsonrpcServiceTest.java` | archive disabled/latest regression |

### 验收

```bash
./gradlew :chainbase:test --tests '*ArchiveStorageKeyCodecTest'
./gradlew :chainbase:test --tests '*DefaultArchiveStateReaderTest'
./gradlew :framework:test --tests '*JsonRpcArchiveStatePointResolverTest'
./gradlew :framework:test --tests '*ArchiveJsonRpcStateAdapterTest'
./gradlew :framework:test --tests '*TronJsonRpcHistoricalGettersTest'
./gradlew :framework:test --tests 'org.tron.core.jsonrpc.JsonrpcServiceTest'
```

## 8. L7：CommitmentBuilder

来源：[S10/S11 CommitmentBuilder 4e80 编码执行包](./20260603-java-tron-archive-s10-s11-commitment-builder-4e80-coding-packet.md)

代码级执行包：[java-tron Archive L7：CommitmentBuilder 代码级执行包](./20260605-java-tron-archive-l7-commitment-builder-4e80-code-plan.md)

### 修改文件

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveTable.java` | modify | add ROOT_RECORD / COMMITMENT_BRANCH / COMMITMENT_META prefixes |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveStoreKeyCodec.java` | modify | root key codecs |
| `chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java` | modify | temporal + commitment same batch |
| `chainbase/src/main/java/org/tron/core/archive/ArchiveService.java` | modify | root reader / no-op commitment API surface |
| `chainbase/src/main/java/org/tron/core/capsule/BlockCapsule.java` | test-only inspect | Do not call `setAccountStateRoot` for archive |
| `framework/src/main/java/org/tron/core/services/jsonrpc/types/BlockResult.java` | test-only inspect | Do not alter `stateRoot` semantics |

### 新增文件

| 文件 | 说明 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/archive/commitment/ArchiveCommitmentAlgorithm.java` | algorithm descriptor |
| `chainbase/src/main/java/org/tron/core/archive/commitment/ArchiveRootCoverage.java` | root coverage enum |
| `chainbase/src/main/java/org/tron/core/archive/commitment/ArchiveTreeId.java` | tree identity |
| `chainbase/src/main/java/org/tron/core/archive/commitment/ArchiveTreeKind.java` | domain/global tree kind |
| `chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentHash.java` | domain-separated hash |
| `chainbase/src/main/java/org/tron/core/archive/commitment/SparseMerkleArchiveCommitmentTree.java` | SMT core |
| `chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentNodeRecord.java` | node model |
| `chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentNodeCodec.java` | node codec |
| `chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentNodeStore.java` | content-addressed node store |
| `chainbase/src/main/java/org/tron/core/archive/commitment/ArchiveCommitmentContext.java` | tree IO context |
| `chainbase/src/main/java/org/tron/core/archive/commitment/DefaultArchiveCommitmentContext.java` | archive DB backed context |
| `chainbase/src/main/java/org/tron/core/archive/commitment/ArchiveRootRecord.java` | block/tx root record |
| `chainbase/src/main/java/org/tron/core/archive/commitment/DomainRootRecord.java` | domain roots |
| `chainbase/src/main/java/org/tron/core/archive/commitment/CurrentRootRecord.java` | current root record |
| `chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentProgressRecord.java` | root progress |
| `chainbase/src/main/java/org/tron/core/archive/commitment/RootKeyCodec.java` | ROOT_BY_BLOCK/ROOT_BY_TX/current |
| `chainbase/src/main/java/org/tron/core/archive/commitment/RootValueNormalizer.java` | normalize domain values for root |
| `chainbase/src/main/java/org/tron/core/archive/commitment/ArchiveCommitmentBuilder.java` | builder interface |
| `chainbase/src/main/java/org/tron/core/archive/commitment/NoopArchiveCommitmentBuilder.java` | disabled builder |
| `chainbase/src/main/java/org/tron/core/archive/commitment/DefaultArchiveCommitmentBuilder.java` | block-end root integration |
| `chainbase/src/main/java/org/tron/core/archive/commitment/ArchiveCommitmentRebuildVerifier.java` | rebuild from archive LATEST |
| `chainbase/src/main/java/org/tron/core/archive/commitment/ArchiveTxRootComputer.java` | on-demand transaction-level root |
| `chainbase/src/main/java/org/tron/core/archive/commitment/ArchiveRootReader.java` | root lookup facade |

### 测试文件

| 文件 | 说明 |
| --- | --- |
| `chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentHashTest.java` | domain separation/empty roots |
| `chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentNodeCodecTest.java` | node codec/copy/collision guards |
| `chainbase/src/test/java/org/tron/core/archive/commitment/SparseMerkleArchiveCommitmentTreeTest.java` | put/update/delete/order independence |
| `chainbase/src/test/java/org/tron/core/archive/commitment/RootKeyCodecTest.java` | root key ordering |
| `chainbase/src/test/java/org/tron/core/archive/commitment/DefaultArchiveCommitmentBuilderTest.java` | BlockWriteSet -> root |
| `chainbase/src/test/java/org/tron/core/archive/commitment/ArchiveCommitmentUnwindTest.java` | root current/progress unwind |
| `chainbase/src/test/java/org/tron/core/archive/commitment/ArchiveCommitmentRebuildVerifierTest.java` | rebuild equals committed |
| `chainbase/src/test/java/org/tron/core/archive/commitment/ArchiveTxRootComputerTest.java` | on-demand txNum root from changeset |
| `framework/src/test/java/org/tron/core/services/jsonrpc/BlockResultArchiveRootRegressionTest.java` | `stateRoot` unchanged |

### 验收

```bash
./gradlew :chainbase:test --tests '*CommitmentHashTest'
./gradlew :chainbase:test --tests '*SparseMerkleArchiveCommitmentTreeTest'
./gradlew :chainbase:test --tests '*DefaultArchiveCommitmentBuilderTest'
./gradlew :chainbase:test --tests '*ArchiveTxRootComputerTest'
./gradlew :framework:test --tests '*BlockResultArchiveRootRegressionTest'
```

## 9. L8：historical eth_call

来源：

- [S12/S13 historical eth_call 4e80 编码执行包](./20260604-java-tron-archive-s12-s13-historical-eth-call-4e80-coding-packet.md)
- [L8 historical eth_call 代码级执行包](./20260605-java-tron-archive-l8-historical-eth-call-4e80-code-plan.md)

### 修改文件

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `actuator/src/main/java/org/tron/core/vm/repository/Repository.java` | minimal modify | Add minimal accessor/interface only if current methods cannot expose historical dynamic values |
| `actuator/src/main/java/org/tron/core/vm/repository/RepositoryImpl.java` | inspect/minimal modify | Keep latest path; do not turn it into archive repository |
| `actuator/src/main/java/org/tron/core/actuator/VMActuator.java` | modify | Add injection point for archive repository/dynamic view |
| `common/src/main/java/org/tron/core/vm/config/VMConfig.java` | modify | snapshot/restore or scoped override support |
| `actuator/src/main/java/org/tron/core/vm/config/ConfigLoader.java` | modify | Add `load(VmDynamicProperties)` overload |
| `framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java` | modify | `eth_call` historical block selector branch |
| `framework/src/main/java/org/tron/core/services/jsonrpc/types/CallArguments.java` | inspect | Reuse parsing, avoid calling `getContractType(wallet)` on historical path |

### 新增文件

| 文件 | 说明 |
| --- | --- |
| `common/src/main/java/org/tron/core/vm/config/VmDynamicProperties.java` | VM dynamic property minimal interface |
| `common/src/main/java/org/tron/core/vm/config/VmConfigSnapshot.java` | VMConfig static snapshot |
| `common/src/main/java/org/tron/core/vm/config/VmConfigScope.java` | VMConfig scoped load/restore helper |
| `chainbase/src/main/java/org/tron/core/archive/vm/HistoricalVmDynamicProperties.java` | historical dynamic properties backed by archive `DYNAMIC_PROPERTIES` |
| `chainbase/src/main/java/org/tron/core/archive/vm/HistoricalCallUnsupportedException.java` | fail-fast for uncovered native/resource paths |
| `actuator/src/main/java/org/tron/core/vm/repository/ArchiveRepositoryAdapter.java` | historical reads + simulation overlay |
| `actuator/src/main/java/org/tron/core/vm/repository/ArchiveRepositoryChild.java` | nested call overlay if needed |
| `actuator/src/main/java/org/tron/core/vm/repository/ArchiveRepositoryOverlay.java` | copy-on-write account/contract/code/storage overlay |
| `actuator/src/main/java/org/tron/core/vm/repository/UnsupportedArchiveRepositoryAccess.java` | latest-only repository method guard |
| `actuator/src/main/java/org/tron/core/vm/archive/HistoricalConstantCallExecutor.java` | archive-backed constant call |
| `actuator/src/main/java/org/tron/core/vm/archive/HistoricalConstantCallRequest.java` | executor input |
| `actuator/src/main/java/org/tron/core/vm/archive/HistoricalConstantCallResult.java` | executor output |
| `framework/src/main/java/org/tron/core/services/jsonrpc/HistoricalEthCallBlockSelector.java` | string/object block selector parser |
| `framework/src/main/java/org/tron/core/services/jsonrpc/HistoricalEthCallSupport.java` | JSON-RPC adapter to executor |
| `framework/src/main/java/org/tron/core/services/jsonrpc/ConstantCallResultEncoder.java` | shared latest/historical result encoder |

### 测试文件

| 文件 | 说明 |
| --- | --- |
| `common/src/test/java/org/tron/core/vm/config/VmConfigScopeTest.java` | static config restore |
| `chainbase/src/test/java/org/tron/core/archive/vm/HistoricalVmDynamicPropertiesTest.java` | DYNAMIC_PROPERTIES historical view |
| `actuator/src/test/java/org/tron/core/vm/repository/ArchiveRepositoryAdapterTest.java` | account/code/storage/dynamic reads |
| `actuator/src/test/java/org/tron/core/vm/repository/ArchiveRepositoryChildTest.java` | child overlay commit/tombstone/no root write |
| `actuator/src/test/java/org/tron/core/vm/archive/HistoricalConstantCallExecutorTest.java` | constant call no write |
| `framework/src/test/java/org/tron/core/services/jsonrpc/TronJsonRpcEthCallArchiveTest.java` | historical `eth_call` branch |

### 验收

```bash
./gradlew :common:test --tests '*VmConfig*Test'
./gradlew :chainbase:test --tests '*HistoricalVmDynamicPropertiesTest'
./gradlew :actuator:test --tests '*ArchiveRepositoryAdapterTest'
./gradlew :actuator:test --tests '*ArchiveRepositoryChildTest'
./gradlew :actuator:test --tests '*HistoricalConstantCallExecutorTest'
./gradlew :framework:test --tests '*TronJsonRpcEthCallArchiveTest'
```

## 10. L9：proof/debug API

来源：

- [S14 proof/debug API 4e80 编码执行包](./20260604-java-tron-archive-s14-proof-debug-api-4e80-coding-packet.md)
- [L9 proof/debug API 代码级执行包](./20260605-java-tron-archive-l9-proof-debug-api-4e80-code-plan.md)

### 修改文件

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `common/src/main/resources/reference.conf` | modify | add `storage.archive.debug.*` default false/limits |
| `common/src/main/java/org/tron/core/config/args/StorageConfig.java` | modify | add debug config fields/post-process |
| `common/src/main/java/org/tron/core/config/args/Storage.java` | modify | runtime holder for archive debug flags/limits |
| `framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java` | modify | add `debug_getArchiveRoot`、`debug_getArchiveProof`、`debug_verifyArchiveProof` |
| `framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java` | modify | thin methods delegating to adapter; source/config guard |
| `framework/src/main/java/org/tron/core/services/jsonrpc/JsonRpcErrorResolver.java` | inspect | Existing annotation mapping should be enough |
| `framework/src/main/java/org/tron/core/services/jsonrpc/types/BlockResult.java` | test-only inspect | Do not add archive root to existing block result |
| `chainbase/src/main/java/org/tron/core/archive/commitment/ArchiveRootReader.java` | consume | L9 proof/debug uses L7 root lookup, does not redefine it |
| `chainbase/src/main/java/org/tron/core/archive/commitment/ArchiveTxRootComputer.java` | consume | L9 proof/debug uses L7 on-demand tx root, does not redefine it |

### 新增文件

| 文件 | 说明 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/archive/proof/ArchiveProofService.java` | proof service interface |
| `chainbase/src/main/java/org/tron/core/archive/proof/DefaultArchiveProofService.java` | orchestrates root/domain/global proof |
| `chainbase/src/main/java/org/tron/core/archive/proof/ArchiveRootQuery.java` | root query model |
| `chainbase/src/main/java/org/tron/core/archive/proof/ArchiveProofRequest.java` | request model |
| `chainbase/src/main/java/org/tron/core/archive/proof/ArchiveProofTarget.java` | target model |
| `chainbase/src/main/java/org/tron/core/archive/proof/ArchiveRootResult.java` | root response model |
| `chainbase/src/main/java/org/tron/core/archive/proof/ArchiveProof.java` | proof response |
| `chainbase/src/main/java/org/tron/core/archive/proof/ArchiveDomainProof.java` | domain proof |
| `chainbase/src/main/java/org/tron/core/archive/proof/ArchiveGlobalProof.java` | global proof |
| `chainbase/src/main/java/org/tron/core/archive/proof/ArchiveProofNode.java` | proof node |
| `chainbase/src/main/java/org/tron/core/archive/proof/ArchiveProofVerifier.java` | offline-ish verifier |
| `chainbase/src/main/java/org/tron/core/archive/proof/ArchiveProofVerificationResult.java` | verifier result |
| `chainbase/src/main/java/org/tron/core/archive/proof/ArchiveDomainProofBuilder.java` | domain proof builder |
| `chainbase/src/main/java/org/tron/core/archive/proof/ArchiveGlobalProofBuilder.java` | global proof builder |
| `chainbase/src/main/java/org/tron/core/archive/proof/ArchiveProofLimitChecker.java` | node/value/result limits |
| `chainbase/src/main/java/org/tron/core/archive/proof/ArchiveProofException.java` | typed proof errors |
| `framework/src/main/java/org/tron/core/services/jsonrpc/ArchiveDebugFacade.java` | JSON-RPC facade orchestration |
| `framework/src/main/java/org/tron/core/services/jsonrpc/ArchiveDebugAccessGuard.java` | FullNode/config/source guard |
| `framework/src/main/java/org/tron/core/services/jsonrpc/ArchiveProofJsonAdapter.java` | chainbase proof model to JSON model |
| `framework/src/main/java/org/tron/core/services/jsonrpc/types/ArchiveRootJsonRequest.java` | JSON model |
| `framework/src/main/java/org/tron/core/services/jsonrpc/types/ArchiveProofJsonRequest.java` | JSON model |
| `framework/src/main/java/org/tron/core/services/jsonrpc/types/ArchiveVerifyProofJsonRequest.java` | JSON model |
| `framework/src/main/java/org/tron/core/services/jsonrpc/types/ArchiveRootJsonResult.java` | JSON result |
| `framework/src/main/java/org/tron/core/services/jsonrpc/types/ArchiveProofJsonResult.java` | JSON result |
| `framework/src/main/java/org/tron/core/services/jsonrpc/types/ArchiveProofVerificationJsonResult.java` | JSON result |

### 测试文件

| 文件 | 说明 |
| --- | --- |
| `chainbase/src/test/java/org/tron/core/archive/commitment/ArchiveRootReaderTest.java` | root lookup from L7 |
| `chainbase/src/test/java/org/tron/core/archive/commitment/ArchiveTxRootComputerTest.java` | persisted/on-demand tx root from L7 |
| `common/src/test/java/org/tron/core/config/args/StorageConfigArchiveDebugTest.java` | debug default false and limit config |
| `chainbase/src/test/java/org/tron/core/archive/proof/ArchiveRootResultTest.java` | root response invariants |
| `chainbase/src/test/java/org/tron/core/archive/proof/ArchiveProofTargetResolverTest.java` | domain/path target resolution |
| `chainbase/src/test/java/org/tron/core/archive/proof/ArchiveDomainProofBuilderTest.java` | existence/non-existence/tombstone |
| `chainbase/src/test/java/org/tron/core/archive/proof/ArchiveGlobalProofBuilderTest.java` | domain root in global root |
| `chainbase/src/test/java/org/tron/core/archive/proof/ArchiveProofVerifierTest.java` | corrupt proof invalid |
| `chainbase/src/test/java/org/tron/core/archive/proof/ArchiveProofLimitCheckerTest.java` | proof node/value/result limits |
| `framework/src/test/java/org/tron/core/services/jsonrpc/TronJsonRpcArchiveDebugTest.java` | default off, FullNode-only, method-not-found for `eth_getProof` |
| `framework/src/test/java/org/tron/core/services/jsonrpc/BlockResultArchiveRootRegressionTest.java` | archive root is not exposed as block `stateRoot` |

### 验收

```bash
./gradlew :common:test --tests '*StorageConfigArchiveDebugTest'
./gradlew :chainbase:test --tests '*ArchiveRootResultTest'
./gradlew :chainbase:test --tests '*ArchiveProofTargetResolverTest'
./gradlew :chainbase:test --tests '*ArchiveDomainProofBuilderTest'
./gradlew :chainbase:test --tests '*ArchiveGlobalProofBuilderTest'
./gradlew :chainbase:test --tests '*ArchiveProofVerifierTest'
./gradlew :chainbase:test --tests '*ArchiveProofLimitCheckerTest'
./gradlew :framework:test --tests '*TronJsonRpcArchiveDebugTest'
./gradlew :framework:test --tests '*BlockResultArchiveRootRegressionTest'
```

## 11. Do-not-touch / regression anchors

| 文件 | 规则 |
| --- | --- |
| `protocol/src/main/protos/core/Tron.proto` | P0 不改 block header root 字段 |
| `chainbase/src/main/java/org/tron/core/capsule/BlockCapsule.java` | 不为 archive 调 `setAccountStateRoot` |
| `framework/src/main/java/org/tron/core/services/jsonrpc/types/BlockResult.java` | `stateRoot` 继续等于 header `accountStateRoot` |
| `framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java` | P0 不新增 `eth_getProof` |
| `actuator/src/main/java/org/tron/core/vm/VM.java` | S14 不打开全局 `VMConfig.vmTrace()` |
| `actuator/src/main/java/org/tron/core/vm/VMUtils.java` | S14 不写 `./vm_trace` 文件 |

## 12. Review checklist by diff

每个 PR review 时按文件检查：

- Config PR only touches config/no-op/dbName files.
- Manager lifecycle PR does not add Store hook persistence.
- Domain registry PR does not read/write archive DB.
- Write collector PR does not change JSON-RPC behavior.
- Temporal PR does not build roots.
- Reader PR does not change `eth_call`.
- Commitment PR does not write header root.
- historical eth_call PR does not implement proof/debug.
- proof/debug PR does not implement `eth_getProof` or `debug_traceCall`.

## 13. Full gate

单 slice gate 通过后，进入 milestone gate：

```bash
./gradlew :common:test --tests '*Archive*'
./gradlew :chainbase:test --tests '*Archive*'
./gradlew :actuator:test --tests '*Archive*'
./gradlew :framework:test --tests '*Archive*'
./gradlew checkstyleMain checkstyleTest
./gradlew build
```

如果实际测试类名不是 `*Archive*`，以当前实现类名为准，但必须覆盖本文列出的证据项。失败测试不能通过 skip 处理。
