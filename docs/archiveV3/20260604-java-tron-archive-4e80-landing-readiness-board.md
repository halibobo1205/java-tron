# java-tron Archive：4e80 分阶段落地执行看板

日期：2026-06-04

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 当前基线：`4e80f8ffa9a2`

上游路线：[java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)

总装计划：[java-tron Archive：4e80 完整实现总装计划](./20260604-java-tron-archive-4e80-implementation-assembly-plan.md)

逐文件落点：[java-tron Archive：4e80 逐文件实现落点矩阵](./20260604-java-tron-archive-4e80-file-implementation-map.md)

测试与验收：[java-tron Archive：4e80 模块测试与验收计划](./20260604-java-tron-archive-4e80-test-verification-plan.md)

state-root 分支参考：[java-tron state-root 分支借鉴分析](./20260605-java-tron-state-root-branches-reference-analysis.md)

L1 代码级执行包：[java-tron Archive L1：config/no-op/dbName 代码级执行包](./20260604-java-tron-archive-l1-config-noop-dbname-4e80-code-plan.md)

L2 代码级执行包：[java-tron Archive L2：Manager lifecycle + txNum 代码级执行包](./20260605-java-tron-archive-l2-manager-txnum-4e80-code-plan.md)

L3 代码级执行包：[java-tron Archive L3：ArchiveDomainRegistry 代码级执行包](./20260605-java-tron-archive-l3-domain-registry-4e80-code-plan.md)

L4 代码级执行包：[java-tron Archive L4：WriteCollector + Storage Semantic Hook 代码级执行包](./20260605-java-tron-archive-l4-write-collector-4e80-code-plan.md)

L5 代码级执行包：[java-tron Archive L5：ArchiveTemporalStore 代码级执行包](./20260605-java-tron-archive-l5-temporal-store-4e80-code-plan.md)

L6 代码级执行包：[java-tron Archive L6：ArchiveStateReader + historical getters 代码级执行包](./20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md)

L7 代码级执行包：[java-tron Archive L7：CommitmentBuilder 代码级执行包](./20260605-java-tron-archive-l7-commitment-builder-4e80-code-plan.md)

L8 代码级执行包：[java-tron Archive L8：historical eth_call 代码级执行包](./20260605-java-tron-archive-l8-historical-eth-call-4e80-code-plan.md)

L9 代码级执行包：[java-tron Archive L9：proof/debug API 代码级执行包](./20260605-java-tron-archive-l9-proof-debug-api-4e80-code-plan.md)

本文是编码前执行控制面：把 L1-L9/S1-S14 拆成可合入的 landing board，明确每一步的状态、输入、输出、禁止混入的内容、进入下一步的证据。它用于后续真正修改 `/Users/boson/IdeaProjects/java-tron` 时逐项推进。

## 1. 当前实现状态

以 2026-06-04 本地 java-tron 源码复核为准：

```text
java-tron HEAD = 4e80f8ffa9a2
storage.archive.* = not implemented
ArchiveService / ArchiveTxNum / ArchiveDomain / ArchiveTemporal = not implemented
ArchiveStateReader / archive commitment / archive proof = not implemented
```

源码中存在的 `ArchiveManifest` 只在 plugin packaging 路径：

```text
plugins/src/main/java/arm/org/tron/plugins/ArchiveManifest.java
plugins/src/main/java/x86/org/tron/plugins/ArchiveManifest.java
plugins/src/test/java/org/tron/plugins/leveldb/ArchiveManifestTest.java
```

它不是 issue #6289 的 archive state sidecar 实现，不计入本文完成证据。

## 2. 状态定义

| 状态 | 含义 |
| --- | --- |
| `READY` | 源码锚点、设计文档、测试要求都清楚，可以开始写该 slice |
| `PLANNED` | 依赖前置 slice 的接口或测试产物，暂不应直接编码 |
| `BLOCKED` | 缺少必须决策或外部条件；当前没有这种状态 |
| `DONE` | java-tron 源码已有对应实现，测试和 gate 已通过 |

当前没有任何 L1-L9 slice 达到 `DONE`。原因不是文档缺失，而是 java-tron 还没有 archive state sidecar 源码产物。

## 3. 总体依赖图

```text
L1 config/no-op/dbName
  -> L2 Manager lifecycle + txNum
    -> L3 ArchiveDomainRegistry
      -> L4 ArchiveWriteCollector + storage semantic hook
        -> L5 ArchiveTemporalStore
          -> L6 ArchiveStateReader + historical getters
            -> L8 historical eth_call
          -> L7 CommitmentBuilder + rebuild verifier
            -> L9 proof/debug API
```

实现原则：

- L1 必须先落，因为它证明默认关闭和 package boundaries。
- L2 必须早于 collector，否则 Store hook 没有 block/tx context。
- L3 必须早于 collector/temporal/root/proof，否则 domain id 和 key codec 会漂移。
- L5 必须早于 historical getter 和 root builder，否则读路径和 root 都没有权威历史存储。
- L8 只能在 L6 后做，因为 historical VM 需要 archive-backed state reader。
- L9 只能在 L7 后做，因为 proof/debug 依赖 sidecar root。

## 4. Landing Board

| Landing | 状态 | 对应 slice | 交付产物 | 前置条件 | 解锁下一步的证据 |
| --- | --- | --- | --- | --- | --- |
| [L1 config/no-op/dbName](./20260604-java-tron-archive-l1-config-noop-dbname-4e80-code-plan.md) | `READY` | S1 | archive config、no-op service、`getDbName()` 修复 | 当前 4e80 源码 | default-off tests、no-op tests、dbName test |
| [L2 Manager lifecycle + txNum](./20260605-java-tron-archive-l2-manager-txnum-4e80-code-plan.md) | `PLANNED` | S2 | block lifecycle context、txNum index、Manager hooks | L1 DONE | normal/failure/replay/unwind lifecycle tests |
| [L3 ArchiveDomainRegistry](./20260605-java-tron-archive-l3-domain-registry-4e80-code-plan.md) | `PLANNED` | S3 | domain descriptor、codec、coverage、checksum | L1 DONE；L2 interfaces stable | registry/codec/checksum tests |
| [L4 WriteCollector + semantic hook](./20260605-java-tron-archive-l4-write-collector-4e80-code-plan.md) | `PLANNED` | S4/S5 | raw/store-specific/semantic write collection | L2 + L3 DONE | deterministic write-set and storage semantic tests |
| [L5 ArchiveTemporalStore](./20260605-java-tron-archive-l5-temporal-store-4e80-code-plan.md) | `PLANNED` | S6/S7 | raw store、temporal tables、unwind/startup verifier | L2-L4 DONE | apply/getAsOf/unwind/progress tests |
| [L6 StateReader + historical getters](./20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md) | `PLANNED` | S8/S9 | reader、state point resolver、3 个 JSON-RPC getter | L5 DONE | historical balance/code/storage tests |
| [L7 CommitmentBuilder](./20260605-java-tron-archive-l7-commitment-builder-4e80-code-plan.md) | `PLANNED` | S10/S11 | sidecar root、tx root computer、root records、rebuild verifier | L3-L5 DONE | deterministic root/rebuild/header untouched tests |
| [L8 historical eth_call](./20260605-java-tron-archive-l8-historical-eth-call-4e80-code-plan.md) | `PLANNED` | S12/S13 | archive repository adapter、historical constant executor、VMConfig scope | L6 DONE；L7 coverage recommended | `eth_call` historical storage differs from latest；object-form 不重写 latest |
| [L9 proof/debug API](./20260605-java-tron-archive-l9-proof-debug-api-4e80-code-plan.md) | `PLANNED` | S14 | archive-native root/proof/verify debug API、FullNode-only guard、proof verifier | L7 DONE | proof verifier + default-off JSON-RPC tests；no `eth_getProof`/`debug_traceCall` |

## 5. L1 Entry Packet

### 5.1 目标

把 archive 能力接入配置和 package 边界，但保持完全 no-op。

### 5.2 当前源码锚点

| 文件 | 当前事实 |
| --- | --- |
| `common/src/main/resources/reference.conf:35-132` | `storage` 默认配置集中在这里 |
| `framework/src/main/resources/config.conf:6-42` | 用户可见 storage 示例 |
| `common/src/main/java/org/tron/core/config/args/StorageConfig.java:21-33` | storage bean 主类 |
| `StorageConfig.java:173-188` | `fromConfig(config)` 绑定并 post-process |
| `framework/src/main/java/org/tron/core/config/args/Args.java:212-244` | `applyStorageConfig(StorageConfig)` 桥接 runtime storage |
| `framework/src/main/java/org/tron/core/config/args/Args.java:713-716` | 初始化 `PARAMETER.storage` 后绑定 storage config |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java:56-80` | 已保存底层 DB，但 `getDbName()` 当前返回 null |

### 5.3 允许修改

```text
common/src/main/resources/reference.conf
framework/src/main/resources/config.conf
common/src/main/java/org/tron/core/config/args/StorageConfig.java
common/src/main/java/org/tron/core/config/args/Storage.java
framework/src/main/java/org/tron/core/config/args/Args.java
chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java
chainbase/src/main/java/org/tron/core/archive/...
```

### 5.4 禁止混入

```text
Manager lifecycle hook
Store write hook
Archive DB persistence
JSON-RPC behavior change
Root/proof/debug API
```

### 5.5 测试和 gate

```bash
./gradlew :common:test --tests '*StorageConfig*Test'
./gradlew :chainbase:test --tests '*NoopArchiveServiceTest'
./gradlew :framework:test --tests '*TronStoreWithRevokingArchiveTest'
./gradlew checkstyleMain checkstyleTest
```

### 5.6 DONE 证据

- `storage.archive.enable` 默认 false。
- archive config 非法 limit/path 在 config 层被拒绝。
- `ArchiveServiceFactory` disabled 返回 `NoopArchiveService`。
- no-op service 的 lifecycle/hook/read/root/proof 方法不写 DB。
- `TronStoreWithRevoking.getDbName()` 返回真实 DB name。

## 6. L2 Entry Packet

代码级细化：[java-tron Archive L2：Manager lifecycle + txNum 代码级执行包](./20260605-java-tron-archive-l2-manager-txnum-4e80-code-plan.md)

### 6.1 目标

在 canonical block 生命周期上建立 archive context 和 txNum，但仍不持久化历史 state。

### 6.2 当前源码锚点

| 文件 | 当前事实 |
| --- | --- |
| `Manager.java:1266` | `pushBlock(final BlockCapsule block)` 是 normal canonical 入口 |
| `Manager.java:1380-1381` | `applyBlock(newBlock, txs)` 后 `tmpSession.commit()` |
| `Manager.java:1034-1041` | `eraseBlock()` 成功 `revokingStore.fastPop()` |
| `Manager.java:1148-1149`、`1186-1187` | fork/recovery replay 有独立 commit |
| `Manager.java:1838` | `processBlock` 执行 block |
| `Manager.java:1873-1886` | 逐笔交易执行位置 |
| `Manager.java:1906-1925` | reward/cache/recent/dynamic properties finalize |

### 6.3 允许修改

```text
framework/src/main/java/org/tron/core/db/Manager.java
chainbase/src/main/java/org/tron/core/archive/ArchiveService.java
chainbase/src/main/java/org/tron/core/archive/ArchiveExecutionContext.java
chainbase/src/main/java/org/tron/core/archive/ArchiveExecutionContextHolder.java
chainbase/src/main/java/org/tron/core/archive/ArchiveSource.java
chainbase/src/main/java/org/tron/core/archive/txnum/...
```

### 6.4 禁止混入

```text
Store put/delete capture
Temporal DB writes
Commitment root calculation
JSON-RPC historical reads
```

### 6.5 测试和 gate

```bash
./gradlew :chainbase:test --tests '*ArchiveTxNumIndexTest'
./gradlew :framework:test --tests '*ManagerArchiveLifecycleTest'
./gradlew :framework:test --tests '*ManagerArchiveContextCleanupTest'
./gradlew checkstyleMain checkstyleTest
```

### 6.6 DONE 证据

- prepare/user/finalize phase 都有 txNum。
- block tx range 可查询。
- failure 后 `abortBlock` 被调用且 context cleared。
- fork/replay/recovery source 不被误标成 normal。
- unwind 只在 canonical `fastPop()` 成功后触发。

## 7. L3 Entry Packet

代码级细化：[java-tron Archive L3：ArchiveDomainRegistry 代码级执行包](./20260605-java-tron-archive-l3-domain-registry-4e80-code-plan.md)

### 7.1 目标

固定 archive domain、codec、coverage 和 checksum，成为 collector、reader、root、proof 的共同语义层。

### 7.2 当前源码锚点

| 文件 | 当前事实 |
| --- | --- |
| `ChainBaseManager.java:36-69` | import 主要 Store 类型 |
| `ChainBaseManager.java:78-220` | Spring 注入并暴露 account/code/contract/storage 等 Store |
| `ContractStore.java:31-39` | direct `revokingDB.put`，需要 store-specific policy |
| `AbiStore.java:27-32` | direct raw bytes put |
| `ContractStateStore.java:27-32` | direct capsule put |
| `actuator/.../vm/program/Storage.java:46-53` | storage-row physical key 不是 archive semantic key |

### 7.3 允许修改

```text
chainbase/src/main/java/org/tron/core/archive/domain/...
chainbase/src/main/java/org/tron/core/archive/codec/...
chainbase/src/main/java/org/tron/core/archive/ArchiveService.java
```

### 7.4 禁止混入

```text
Actual Store hooks
Archive DB persistence
JSON-RPC historical reads
Root/proof implementation
```

### 7.5 测试和 gate

```bash
./gradlew :chainbase:test --tests '*ArchiveDomain*Test'
./gradlew :chainbase:test --tests '*ArchiveDomainCodecsTest'
./gradlew :chainbase:test --tests '*DynamicKeyPolicyTest'
./gradlew :chainbase:test --tests '*RegistryChecksumTest'
./gradlew checkstyleMain checkstyleTest
```

### 7.6 DONE 证据

- P0 domain 至少包含 `ACCOUNT`、`CONTRACT`、`CODE`、`CONTRACT_STORAGE`、`DYNAMIC_PROPERTIES`。
- domain id 稳定，不依赖 enum ordinal。
- unsupported/deferred domain 有明确 coverage response。
- contract storage key 是 semantic `(address, slot)`，不是 physical row key。
- registry checksum 可检测 schema/domain drift。

## 8. L4 Entry Packet

代码级细化：[java-tron Archive L4：WriteCollector + Storage Semantic Hook 代码级执行包](./20260605-java-tron-archive-l4-write-collector-4e80-code-plan.md)

### 8.1 目标

捕获 canonical block 执行过程中的 domain write-set，但仍不写 archive DB。

### 8.2 当前源码锚点

| 文件 | 当前事实 |
| --- | --- |
| `TronStoreWithRevoking.java:89-99` | 通用 put/delete hook 点 |
| `ContractStore.java:31-39` | 绕过 super.put 的 store-specific 写 |
| `AbiStore.java:27-32` | 绕过 super.put 的 store-specific 写 |
| `ContractStateStore.java:27-32` | 绕过 super.put 的 store-specific 写 |
| `Storage.java:73-94` | `getValue/put` 在 rowCache 操作 semantic slot |
| `Storage.java:96-105` | commit 时 zero value 转 delete |
| `StorageTest.java:91-186` | 现成合约 storage 写/覆盖/删除 fixture |

### 8.3 允许修改

```text
chainbase/src/main/java/org/tron/core/archive/write/...
chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java
chainbase/src/main/java/org/tron/core/store/ContractStore.java
chainbase/src/main/java/org/tron/core/store/AbiStore.java
chainbase/src/main/java/org/tron/core/store/ContractStateStore.java
chainbase/src/main/java/org/tron/core/store/StorageRowStore.java
framework/src/main/java/org/tron/core/db/Manager.java
actuator/src/main/java/org/tron/core/vm/program/Storage.java
```

### 8.4 禁止混入

```text
ArchiveTemporalStore persistence
Commitment root
JSON-RPC historical behavior
```

### 8.5 测试和 gate

```bash
./gradlew :chainbase:test --tests '*DefaultArchiveWriteCollectorTest'
./gradlew :framework:test --tests '*ArchiveGenericStoreHookTest'
./gradlew :framework:test --tests '*ArchiveSpecialStoreHookTest'
./gradlew :framework:test --tests '*ArchiveRetryLifecycleTest'
./gradlew :actuator:test --tests '*ArchiveStorageSemanticHookTest'
./gradlew checkstyleMain checkstyleTest
```

### 8.6 DONE 证据

- write-set deterministic order。
- last-write-wins 和 tombstone 语义明确。
- disabled archive 下 hook 快速 no-op。
- generic Store hook 和 store-specific hook 都有测试。
- contract storage write-set 记录 address + slot + value/tombstone。
- retry 只回滚 VM-attempt 写入，不丢 pre-exec bandwidth/memo/resource 写。
- failed block 不残留 collector state。

## 9. L5 Entry Packet

代码级细化：[java-tron Archive L5：ArchiveTemporalStore 代码级执行包](./20260605-java-tron-archive-l5-temporal-store-4e80-code-plan.md)

### 9.1 目标

把 L4 write-set 持久化为 single archive DB 的 latest/history/changeset/txnum/progress，并支持 unwind/startup verification。

### 9.2 当前源码锚点

| 文件 | 当前事实 |
| --- | --- |
| `LevelDbDataSourceImpl.java:414-416` | batch null value 表示 delete |
| `RocksDbDataSourceImpl.java:307-309` | RocksDB batch 同样支持 null delete |
| `Manager.java:1380-1381` | canonical commit 成功位置 |
| `Manager.java:1034-1041` | canonical unwind 成功位置 |
| `chainbase/build.gradle:17-40` | chainbase 可新增 core tests |

### 9.3 允许修改

```text
chainbase/src/main/java/org/tron/core/archive/store/...
chainbase/src/main/java/org/tron/core/archive/temporal/...
chainbase/src/main/java/org/tron/core/archive/startup/...
chainbase/src/main/java/org/tron/core/archive/txnum/PersistentArchiveTxNumIndex.java
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
chainbase/src/main/java/org/tron/core/archive/ArchiveService.java
chainbase/src/main/java/org/tron/core/archive/ArchiveServiceFactory.java
framework/src/main/java/org/tron/core/db/Manager.java
```

### 9.4 禁止混入

```text
JSON-RPC historical getter changes
Commitment root algorithm
Proof/debug API
```

### 9.5 测试和 gate

```bash
./gradlew :chainbase:test --tests '*ArchiveRawStoreTest'
./gradlew :chainbase:test --tests '*ArchiveStoreKeyCodecTest'
./gradlew :chainbase:test --tests '*ArchiveValueCodecTest'
./gradlew :chainbase:test --tests '*DefaultArchiveTemporalStoreTest'
./gradlew :chainbase:test --tests '*DefaultArchiveTemporalStoreUnwindTest'
./gradlew :chainbase:test --tests '*PersistentArchiveTxNumIndexTest'
./gradlew :chainbase:test --tests '*ArchiveStartupVerifierTest'
./gradlew :framework:test --tests '*ArchiveTemporalStoreManagerWiringTest'
./gradlew checkstyleMain checkstyleTest
```

### 9.6 DONE 证据

- `getAsOf(domain,key,Bn)` 覆盖 create/update/delete。
- latest/history/changeset/progress 在同一 block apply 中原子提交。
- batch failure 不产生半更新。
- unwind one/multiple blocks 恢复 latest 并删除被 unwind 的 changes/root/progress。
- restart verifier 能检测 progress head 和 canonical head mismatch。

## 10. L6 Entry Packet

### 10.1 目标

接入 `eth_getBalance`、`eth_getCode`、`eth_getStorageAt` 的 historical path。至此 issue #6289 的 4 个 P0 API 完成 3 个。

代码级执行包：[java-tron Archive L6：ArchiveStateReader + historical getters 代码级执行包](./20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md)

### 10.2 当前源码锚点

| 文件 | 当前事实 |
| --- | --- |
| `TronJsonRpc.java:90-108` | 三个 P0 getter 已有接口 |
| `TronJsonRpcImpl.java:383-397` | `requireLatestBlockTag` 当前拒绝 non-latest |
| `TronJsonRpcImpl.java:457-470` | balance 当前读 latest wallet account |
| `TronJsonRpcImpl.java:611-631` | storage 当前读 latest physical `StorageRowStore` |
| `TronJsonRpcImpl.java:635-649` | code 当前读 latest contract info |
| `JsonRpcApiUtil.java:583-636` | block tag/number parser 已有 |
| `JsonrpcServiceTest.java:524-590` | 当前 non-latest state getter regression tests |

### 10.3 允许修改

```text
chainbase/src/main/java/org/tron/core/archive/reader/...
framework/src/main/java/org/tron/core/archive/reader/JsonRpcArchiveStatePointResolver.java
framework/src/main/java/org/tron/core/services/jsonrpc/ArchiveJsonRpcStateAdapter.java
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java
framework/src/main/java/org/tron/core/services/jsonrpc/types/... only if response model needs it
```

### 10.4 禁止混入

```text
eth_call historical execution
Commitment/proof API
Consensus/header root changes
```

### 10.5 测试和 gate

```bash
./gradlew :chainbase:test --tests '*ArchiveStorageKeyCodecTest'
./gradlew :chainbase:test --tests '*DefaultArchiveStateReaderTest'
./gradlew :framework:test --tests '*JsonRpcArchiveStatePointResolverTest'
./gradlew :framework:test --tests '*ArchiveJsonRpcStateAdapterTest'
./gradlew :framework:test --tests '*TronJsonRpcHistoricalGettersTest'
./gradlew :framework:test --tests 'org.tron.core.jsonrpc.JsonrpcServiceTest'
./gradlew checkstyleMain checkstyleTest
```

### 10.6 DONE 证据

- non-latest getter 不调用 `requireLatestBlockTag` 的 latest-only branch。
- latest path 保持当前行为。
- disabled archive 下 historical request 保持当前 reject 或明确 disabled error。
- missing history 不 fallback latest。
- archive gap/corrupt 映射 `JsonRpcInternalException`，缺失对象才渲染 zero/empty。
- balance/code/storage 都有“requested historical state 与 latest 不同”的断言。

## 11. L7 Entry Packet

### 11.1 目标

基于 temporal state 构建 archive sidecar root，并提供 rebuild verifier。root 不参与共识。

代码级执行包：[java-tron Archive L7：CommitmentBuilder 代码级执行包](./20260605-java-tron-archive-l7-commitment-builder-4e80-code-plan.md)

### 11.2 当前源码锚点

| 文件 | 当前事实 |
| --- | --- |
| `protocol/src/main/protos/core/Tron.proto:505` | header 有 `txTrieRoot` |
| `Tron.proto:513` | header 有 `accountStateRoot` |
| `BlockCapsule.java:249` | `setMerkleRoot()` 写 tx root |
| `BlockCapsule.java:255-258` | `setAccountStateRoot(byte[])` 写 account root |
| `BlockResult.java` | JSON `stateRoot` 来源是 header `accountStateRoot` |
| `TrieImpl.java:381-429` | 已有 proof build 可借鉴 |
| `TrieImpl.java:490-557` | 已有 proof verify 可借鉴 |

### 11.3 允许修改

```text
chainbase/src/main/java/org/tron/core/archive/commitment/...
chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java
framework/src/test/java/org/tron/core/services/jsonrpc/BlockResultArchiveRootRegressionTest.java
```

### 11.4 禁止混入

```text
BlockHeader.raw.accountStateRoot writes
BlockHeader.raw.txTrieRoot writes
eth_getProof
debug_getArchiveProof
```

### 11.5 测试和 gate

```bash
./gradlew :chainbase:test --tests '*ArchiveCommitment*Test'
./gradlew :chainbase:test --tests '*SparseMerkleArchiveCommitmentTreeTest'
./gradlew :chainbase:test --tests '*ArchiveCommitmentRebuildVerifierTest'
./gradlew :chainbase:test --tests '*ArchiveTxRootComputerTest'
./gradlew :framework:test --tests '*BlockResultArchiveRootRegressionTest'
./gradlew checkstyleMain checkstyleTest
```

### 11.6 DONE 证据

- identical write-set different input order yields same root。
- domain separation 改变 root。
- update/delete/tombstone 改变 root 且 rebuild 一致。
- `rootAtTxNum` 可通过 `CHANGESET` 回放计算中间交易状态树。
- `BlockResult.stateRoot` regression 证明 header root 未被 archive root 替换。
- root mismatch fail-fast，不更新 progress 为成功。

## 12. L8 Entry Packet

### 12.1 目标

完成 historical `eth_call`，使 issue #6289 的 4 个 P0 JSON-RPC API 全部可用。

代码级执行包：[java-tron Archive L8：historical eth_call 代码级执行包](./20260605-java-tron-archive-l8-historical-eth-call-4e80-code-plan.md)。

### 12.2 当前源码锚点

| 文件 | 当前事实 |
| --- | --- |
| `TronJsonRpc.java:162-170` | `eth_call` 已有接口 |
| `TronJsonRpcImpl.java:1001-1051` | object block param 当前校验后重写为 latest |
| `TronJsonRpcImpl.java:557-608` | call 当前委托 latest wallet constant call |
| `VMTestBase.java:22-35` | VM 测试已有 rootRepository/funded owner |
| `StorageTest.java:91-186` | StorageDemo 合约 fixture 可复用 |

### 12.3 允许修改

```text
actuator/src/main/java/org/tron/core/vm/repository/ArchiveRepositoryAdapter.java
actuator/src/main/java/org/tron/core/vm/repository/ArchiveRepositoryChild.java
actuator/src/main/java/org/tron/core/vm/archive/HistoricalConstantCallExecutor.java
common/src/main/java/org/tron/core/vm/config/... if scoped VM config needs support
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java
framework/src/main/java/org/tron/core/services/jsonrpc/HistoricalEthCallSupport.java
```

### 12.4 禁止混入

```text
Proof/debug API
Global VM trace behavior
Canonical Store writes
eth_getTransactionCount
```

### 12.5 测试和 gate

```bash
./gradlew :actuator:test --tests '*ArchiveRepositoryAdapterTest'
./gradlew :actuator:test --tests '*ArchiveRepositoryChildTest'
./gradlew :chainbase:test --tests '*HistoricalVmDynamicPropertiesTest'
./gradlew :common:test --tests '*VmConfigScopeTest'
./gradlew :actuator:test --tests '*HistoricalConstantCallExecutorTest'
./gradlew :framework:test --tests '*TronJsonRpcEthCallArchiveTest'
./gradlew checkstyleMain checkstyleTest
```

### 12.6 DONE 证据

- string block number 和 object-form `blockNumber` 都不会重写 latest。
- object-form `blockHash` 定位到正确 historical block。
- historical call 返回旧 storage/code，且与 latest 不同。
- call 不写 canonical store 或 temporal latest。
- VM static config 在成功/异常后恢复。

## 13. L9 Entry Packet

### 13.1 目标

提供 archive-native debug root/proof/verify API，默认关闭，明确不实现 Ethereum `eth_getProof` 或 `debug_traceCall`。

代码级执行包：[java-tron Archive L9：proof/debug API 代码级执行包](./20260605-java-tron-archive-l9-proof-debug-api-4e80-code-plan.md)。

### 13.2 当前源码锚点

| 文件 | 当前事实 |
| --- | --- |
| `TronJsonRpc.java:251` | `eth_getTransactionCount` 当前只是 method-not-found |
| `TronJsonRpcImpl.java:1399` | transaction count 返回 unavailable |
| `TronJsonRpc.java` | 当前没有 `eth_getProof` |
| `JsonRpcServlet.java:74-81` | composite service 只有 `TronJsonRpc` |
| `reference.conf:401-427` | JSON-RPC 配置树是 node rpc 限额，不是 archive debug 开关 |
| `reference.conf:765` | `vmTrace=false` 默认 |
| `VMActuator.java:297-308`、`VMUtils.java:55-98` | 全局 VM trace 会写 trace 文件 |

### 13.3 允许修改

```text
common/src/main/resources/reference.conf
common/src/main/java/org/tron/core/config/args/StorageConfig.java
common/src/main/java/org/tron/core/config/args/Storage.java
chainbase/src/main/java/org/tron/core/archive/proof/...
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java
framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java
framework/src/main/java/org/tron/core/services/jsonrpc/ArchiveDebugFacade.java
framework/src/main/java/org/tron/core/services/jsonrpc/ArchiveDebugAccessGuard.java
framework/src/main/java/org/tron/core/services/jsonrpc/ArchiveProofJsonAdapter.java
framework/src/main/java/org/tron/core/services/jsonrpc/types/Archive*Json*.java
```

### 13.4 禁止混入

```text
eth_getProof
debug_traceCall
eth_getTransactionCount implementation
VMConfig.vmTrace global enablement
vm_trace file output
```

### 13.5 测试和 gate

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
./gradlew checkstyleMain checkstyleTest
```

### 13.6 DONE 证据

- debug API 默认 disabled。
- proof verifier 覆盖 existence、absence、tombstone、corrupt proof。
- JSON-RPC `eth_getProof` method-not-found。
- JSON-RPC `debug_traceCall` 未实现或仍 unsupported。
- proof/debug 调用不创建 `./vm_trace` 文件。

## 14. Cross-Slice Stop Rules

遇到以下情况必须停在当前 slice 修正，不进入下一 slice：

| 问题 | 为什么阻断 |
| --- | --- |
| 默认关闭行为变了 | 后续所有 archive 功能都会污染 fullnode 默认行为 |
| txNum 不能覆盖 finalize/unwind | temporal/history/root 无法精确定位系统写入 |
| domain id 不稳定 | historical data 格式不可升级 |
| storage 只记录 physical row key | 无法支持 `eth_getStorageAt(address, slot, block)` |
| temporal apply 非原子 | crash/restart 后 archive 和 canonical state 分叉 |
| historical reader fallback latest | P0 API 结果会看似可用但语义错误 |
| root 写入 header | 违反 issue #6289 当前 P0 非共识 sidecar 约束 |
| proof/debug 伪装 Ethereum proof | 对外 API 语义错误，后续难以兼容 |

## 15. PR 切分建议

| PR | 包含 landing | 最大 diff 边界 |
| --- | --- | --- |
| PR-A | L1 | common config + chainbase no-op + dbName tests |
| PR-B | L2 | Manager lifecycle + txNum，不能碰 Store hook |
| PR-C | L3 | domain registry/codec/coverage/checksum |
| PR-D | L4 | collector + generic/store-specific/semantic hook |
| PR-E | L5 | raw/temporal/progress/unwind/startup |
| PR-F | L6 | reader + balance/code/storage historical JSON-RPC |
| PR-G | L7 | sidecar root + rebuild verifier + header untouched tests |
| PR-H | L8 | historical `eth_call` |
| PR-I | L9 | archive-native proof/debug |

如果为了降低 review 风险，L4 可以再拆为 `collector core`、`generic/store-specific hook`、`contract storage semantic hook` 三个 PR；L5 可以拆为 `raw store/key codecs`、`temporal apply/getAsOf`、`unwind/startup verifier` 三个 PR。

## 16. 当前未完成清单

这些不是文档任务，而是 java-tron 源码实现任务。只有它们全部有源码和测试证据后，才能说 archive P0 实现完成。

| 项 | 当前状态 |
| --- | --- |
| `storage.archive.*` config | missing |
| `ArchiveService` and noop/default implementation | missing |
| `TronStoreWithRevoking.getDbName()` archive-ready fix | missing |
| `ArchiveExecutionContext` and txNum index | missing |
| Manager archive lifecycle hook | missing |
| Domain registry and codecs | missing |
| Write collector and Store hooks | missing |
| Contract storage semantic hook | missing |
| Single archive temporal DB | missing |
| Persistent txNum/progress/startup verifier | missing |
| Historical state reader | missing |
| Historical `eth_getBalance/getCode/getStorageAt` | missing |
| Sidecar commitment root and rebuild verifier | missing |
| Historical `eth_call` | missing |
| Archive-native proof/debug API | missing |

## 17. Completion Audit Template

每次声称某个 landing `DONE` 前，按这个模板补证据：

```text
Landing:
java-tron commit:
Files changed:
Tests run:
Checkstyle:
Default-off regression:
Negative tests:
Known non-P0 exclusions:
Evidence links:
Reviewer risk:
```

整个目标完成前，必须再执行：

```bash
./gradlew :common:test --tests '*Archive*'
./gradlew :chainbase:test --tests '*Archive*'
./gradlew :actuator:test --tests '*Archive*'
./gradlew :framework:test --tests '*Archive*'
./gradlew checkstyleMain checkstyleTest
./gradlew build
```

如果测试类名不含 `Archive`，完成审计必须逐项映射到 [模块测试与验收计划](./20260604-java-tron-archive-4e80-test-verification-plan.md) 的跨模块需求追踪矩阵。
