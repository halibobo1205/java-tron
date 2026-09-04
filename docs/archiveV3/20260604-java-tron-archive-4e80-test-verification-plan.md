# java-tron Archive：4e80 模块测试与验收计划

日期：2026-06-04

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 当前基线：`4e80f8ffa9a2`

上游总装计划：[java-tron Archive：4e80 完整实现总装计划](./20260604-java-tron-archive-4e80-implementation-assembly-plan.md)

逐文件落点：[java-tron Archive：4e80 逐文件实现落点矩阵](./20260604-java-tron-archive-4e80-file-implementation-map.md)

落地执行看板：[java-tron Archive：4e80 分阶段落地执行看板](./20260604-java-tron-archive-4e80-landing-readiness-board.md)

L3 代码级执行包：[java-tron Archive L3：ArchiveDomainRegistry 代码级执行包](./20260605-java-tron-archive-l3-domain-registry-4e80-code-plan.md)

L4 代码级执行包：[java-tron Archive L4：WriteCollector + Storage Semantic Hook 代码级执行包](./20260605-java-tron-archive-l4-write-collector-4e80-code-plan.md)

L5 代码级执行包：[java-tron Archive L5：ArchiveTemporalStore 代码级执行包](./20260605-java-tron-archive-l5-temporal-store-4e80-code-plan.md)

L6 代码级执行包：[java-tron Archive L6：ArchiveStateReader + historical getters 代码级执行包](./20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md)

L7 代码级执行包：[java-tron Archive L7：CommitmentBuilder 代码级执行包](./20260605-java-tron-archive-l7-commitment-builder-4e80-code-plan.md)

本文专门回答“每个模块怎样对照 java-tron 当前源码证明已经实现”。它不是再写一份接口设计，而是把六个模块和 S1-S14 的实现落点翻译为测试 fixture、测试类、端到端场景、失败判据和最终验收门槛。

## 1. 需求边界

issue #6289 在 2026-06-04 复核时仍为 `OPEN`，最近更新时间为 `2026-05-15T08:14:06Z`。P0 直接支持：

```text
eth_getBalance(address, historicalBlock)
eth_getCode(address, historicalBlock)
eth_getStorageAt(address, slot, historicalBlock)
eth_call(args, historicalBlock)
```

P0 不把 archive root 写入共识字段，不实现 Ethereum 语义的 `eth_getProof`，不实现全局 `debug_traceCall`，也不承诺一次覆盖 java-tron 的全部 Store。`debug_getArchiveRoot`、`debug_getArchiveProof`、`debug_verifyArchiveProof` 只能作为 archive-native debug API，并且默认关闭。

验收口径：

- 默认关闭时，java-tron 行为与当前 4e80 一致。
- 开启 archive 后，historical state query 必须命中 archive reader，不能 silent fallback 到 latest。
- canonical block commit 成功后才能 flush archive sidecar。
- unwind/reorg 后 archive sidecar 与 canonical head 同步回退。
- sidecar root 可重建、可验证，但不得写入 `BlockHeader.raw.accountStateRoot`。

## 2. 当前 java-tron 测试基础

当前 `settings.gradle:8-18` 模块：

```text
framework
chainbase
protocol
actuator
consensus
common
example:actuator-example
crypto
plugins
platform
errorprone
```

测试目录事实：

```text
common/src/test/java exists
framework/src/test/java exists
actuator/src/test/java exists
chainbase/src/test does not exist yet
```

`chainbase/build.gradle:17-40` 已有 `test { ... }`，因此 archive core 测试应新增到：

```text
chainbase/src/test/java/org/tron/core/archive/...
```

测试基类选择：

| 基类/方式 | java-tron 当前锚点 | 用途 | archive 使用规则 |
| --- | --- | --- | --- |
| 纯单元测试 | `StorageConfigTest.java:15-180` | 不需要 Spring/DB 的 codec、config、policy | `chainbase` core 首选纯单元，避免不必要 Spring context |
| `BaseMethodTest` | `framework/src/test/java/org/tron/common/BaseMethodTest.java:18-85` | 每个 `@Test` 独立 Spring context 和 temp output | Manager lifecycle、JSON-RPC historical branch、不同 archive config |
| `BaseTest` | `framework/src/test/java/org/tron/common/BaseTest.java:33-96` | 同一 class 共享 context | 只用于只读或不互相污染的回归，不作为 archive 默认基类 |
| `VMTestBase` | `framework/src/test/java/org/tron/common/runtime/vm/VMTestBase.java:15-35` | TVM/Repository fixture，自动创建 funded owner | historical `eth_call`、storage semantic fixture |
| JSON-RPC 现有测试 | `JsonrpcServiceTest.java:524-770` | block tag、state getter、object-form `eth_call` | 扩展或旁路新增 archive tests，证明 non-latest 分支改变 |
| TVM storage fixture | `StorageTest.java:91-186` | 部署 StorageDemo，写入/覆盖/删除 mapping storage | 抽成 archive contract storage 场景 |

自动化代理不得新增 `t.Skip`、`@Ignore`、条件性跳过或从 runner matrix 删除测试。已有 `StorageTest.java:188-190` 的 `@Ignore` 不是 archive 的实现模板。

## 3. 统一场景 Fixture

所有模块测试共享同一套概念场景，避免每个模块各造一份状态模型。

### 3.1 数据常量

建议新增测试 fixture：

```text
chainbase/src/test/java/org/tron/core/archive/fixture/ArchiveTestData.java
chainbase/src/test/java/org/tron/core/archive/fixture/ArchiveScenarioBuilder.java
chainbase/src/test/java/org/tron/core/archive/fixture/FakeArchiveRawStore.java
chainbase/src/test/java/org/tron/core/archive/fixture/FakeArchiveTemporalStore.java
framework/src/test/java/org/tron/core/archive/fixture/ArchiveSpringScenario.java
actuator/src/test/java/org/tron/core/archive/fixture/ArchiveStorageContractFixture.java
```

最小数据：

| 名称 | 值/语义 |
| --- | --- |
| `ADDR_A` | 普通账户，初始余额高，用于 transfer 和 eth_call owner |
| `ADDR_B` | 普通账户，接收转账 |
| `CONTRACT_C` | StorageDemo 合约地址 |
| `SLOT_1` | `mapping(uint => string) int2str` 的语义 slot |
| `VALUE_ABC` | `testPut(1, "abc")` 后的 storage value |
| `VALUE_123` | `testPut(1, "123")` 后的 storage value |
| `ZERO_32` | 删除或不存在时 JSON-RPC 返回 32 字节零 |

`ArchiveStorageContractFixture` 应复用 `StorageTest.java:77-186` 的 Solidity ABI/code 和 `TvmTestUtils.java:84-180` 的部署/执行 helper。这样 `eth_getCode`、`eth_getStorageAt`、`eth_call` 都使用同一个真实 TVM 合约状态。

### 3.2 标准 block 故事

纯单元测试用 `ArchiveScenarioBuilder` 直接构造 write-set；framework/actuator 集成测试再用真实 Manager/TVM 执行其中的关键节点。

| 节点 | 操作 | 预期状态 |
| --- | --- | --- |
| `B0` | genesis | archive disabled/default no-op 下无 sidecar 写入 |
| `B1` | `ADDR_A` funded | `eth_getBalance(A, B1) = initial` |
| `B2` | transfer `A -> B` | `A/B` 历史余额在 `B1/B2` 不同 |
| `B3` | deploy `StorageDemo` | `eth_getCode(C, B2) = 0x`，`eth_getCode(C, B3) != 0x` |
| `B4` | `testPut(1, "abc")` | `eth_getStorageAt(C, SLOT_1, B4) = VALUE_ABC` |
| `B5` | `testPut(1, "123")` | `B4` 仍为 `abc`，`B5` 为 `123` |
| `B6` | `testDelete(1)` | `B6` 为 `ZERO_32`，历史 `B4/B5` 不变 |
| `B7` | empty/finalize-only block | txNum 仍覆盖 block finalize phase |
| `R1` | unwind `B6/B5` 到 `B4` | latest/history/changeset/root/progress 回到 `B4` |
| `R2` | alternate `B5'` 写入 `"xyz"` | `B5'` root 与原 `B5` root 不同 |

不要一开始就写单个超大 E2E。每个模块先证明自己的局部契约，然后用 milestone E2E 串联 `B1-B6`。

## 4. Module 01：ArchiveTxNumIndex

### 4.1 当前源码锚点

| java-tron 文件 | 当前事实 | 测试含义 |
| --- | --- | --- |
| `common/src/main/resources/reference.conf:35-132` | `storage` 默认配置集中在这里，`balance.history.lookup=false` 在 line 118 | archive 开关必须默认 false，并且默认关闭测试应从这里证明 |
| `common/src/main/java/org/tron/core/config/args/StorageConfig.java:21-33` | `StorageConfig` 是 storage bean，line 173-188 绑定并 post-process | archive config 解析、非法参数、默认值用纯单元测 |
| `framework/src/main/java/org/tron/core/config/args/Args.java:148-182` | `setParam` 先读 config，再 CLI override | archive CLI/config 优先级和 runtime 桥接不能分散读取 |
| `framework/src/main/java/org/tron/core/config/args/Args.java:212-244` | `applyStorageConfig(StorageConfig)` 写入 `CommonParameter.storage` | archive runtime config 的唯一桥接点 |
| `framework/src/main/java/org/tron/core/config/args/Args.java:713-716` | 初始化 `PARAMETER.storage` 后调用 `StorageConfig.fromConfig` | 测试必须证明不会在旧 storage 对象上残留配置 |
| `framework/src/main/java/org/tron/core/db/Manager.java:1266` | `pushBlock` 是 normal canonical 保存入口 | begin/commit/abort lifecycle 从这里覆盖 |
| `Manager.java:1034-1041` | `eraseBlock` 成功 `fastPop()` 后回退 canonical state | archive unwind 必须跟在 canonical rewind 成功后 |
| `Manager.java:1148-1149`、`1186-1187` | fork/recovery replay 有独立 `applyBlock` + session commit | txNum source 要区分 NORMAL/REPLAY/RECOVERY |
| `Manager.java:1838` | `processBlock` 执行 tx loop/finalize | 用户交易和 finalize phase 都必须分配 txNum |

### 4.2 应新增/修改的测试

| 测试类 | 模块 | 覆盖 |
| --- | --- | --- |
| `common/src/test/java/org/tron/core/config/args/StorageConfigArchiveTest.java` | common | `storage.archive.enable=false` 默认；enable/path/history/debug 参数解析；非法 tx window/root interval 被拒绝 |
| `chainbase/src/test/java/org/tron/core/archive/NoopArchiveServiceTest.java` | chainbase | disabled service 所有 lifecycle/hook/read/root 方法不写 DB、不抛业务异常 |
| `chainbase/src/test/java/org/tron/core/archive/txnum/ArchiveTxNumIndexTest.java` | chainbase | block range、tx phase、finalize phase、monotonic、duplicate block reject |
| `chainbase/src/test/java/org/tron/core/archive/txnum/PersistentArchiveTxNumIndexTest.java` | chainbase | `block -> [start,end]`、`txNum -> block/txIndex/phase` 可重启读取 |
| `framework/src/test/java/org/tron/core/db/ManagerArchiveLifecycleTest.java` | framework | normal push 成功后 commit archive；canonical failure 后 abort archive |
| `framework/src/test/java/org/tron/core/db/ManagerArchiveContextCleanupTest.java` | framework | 异常后 `ArchiveExecutionContextHolder` 清空，下一 block 不继承旧 txNum |
| `framework/src/test/java/org/tron/core/db/ManagerArchiveTxScopedSessionTest.java` | framework | 借鉴产块 per-tx nested session 的 archive checkpoint，不改变 canonical block 失败语义 |

### 4.3 必测用例

| 用例 | Arrange | Assert |
| --- | --- | --- |
| default-off config | 只加载 `reference.conf` | `CommonParameter.storage.archive.enable=false`；`ArchiveServiceFactory` 返回 noop |
| explicit enable | HOCON 写 `storage.archive.enable=true` 和 temp path | runtime storage 能读到 archive config；非法路径/limit 抛异常 |
| txNum phases | 构造 block 有 2 笔 user tx + finalize | txNum 顺序为 `BLOCK_PREPARE < USER_TX[0] < USER_TX[1] < BLOCK_FINALIZE` |
| canonical commit only | fake archive service 记录 method calls，Manager `pushBlock` 成功 | `commitBlock` 在 revoking session commit 后发生 |
| abort on failure | 让 fake archive service 或 tx validate 抛异常 | canonical DB 不前进；archive 没有 persisted block range；context cleared |
| tx-scoped checkpoint | 第 2 笔 canonical tx 执行失败 | 外层 block 回滚；第 1 笔 tx 的 pending archive writes 不 flush；不能像产块路径一样跳过第 2 笔 |
| replay source | fork/recovery 路径 apply block | txNum 记录 `source=REPLAY` 或 `RECOVERY`，不与 NORMAL 混淆 |
| unwind source | `eraseBlock()` 成功 | archive range/root/progress 回到 old parent |

### 4.4 失败判据

- 任何历史查询依赖 filtered `txs` 下标推导 txNum。
- block finalize 没有 txNum，导致系统写入无法定位。
- archive flush 发生在 canonical commit 之前。
- 异常后 ThreadLocal context 未清理。
- 默认关闭仍创建/写入 archive DB。

## 5. Module 02：ArchiveDomainRegistry

### 5.1 当前源码锚点

| java-tron 文件 | 当前事实 | 测试含义 |
| --- | --- | --- |
| `chainbase/src/main/java/org/tron/core/ChainBaseManager.java:36-69` | import 了主要 Store 类型 | domain inventory 应从当前 Store 清单出发 |
| `ChainBaseManager.java:78-220` | `@Autowired @Getter` 暴露 account、code、contract、storage-row 等 Store | registry 需要绑定实际 dbName 和 domain |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java:56-70` | 构造函数已拿到 dbName 生成 DB | S1 修复 `getDbName()` 后 registry 不能再用硬编码猜测 |
| `TronStoreWithRevoking.java:78-99` | 当前 `getDbName()` 返回 null，put/delete 是通用 hook 点 | registry 测试应证明 dbName 不为空且可定位 domain |
| `ContractStore.java:31-39` | put 会清 ABI 后直接 `revokingDB.put` | registry 必须标为 store-specific，不能只靠 generic hook |
| `AbiStore.java:27-32` | put 直接写 raw bytes | 同上 |
| `ContractStateStore.java:27-32` | put 直接写 raw capsule bytes | 同上 |
| `actuator/src/main/java/org/tron/core/vm/program/Storage.java:46-53` | storage-row physical key 由 addrHash/slot compose | `CONTRACT_STORAGE` domain 必须走 semantic key |

### 5.2 应新增/修改的测试

| 测试类 | 模块 | 覆盖 |
| --- | --- | --- |
| `chainbase/src/test/java/org/tron/core/archive/domain/ArchiveDomainRegistryTest.java` | chainbase | domain id/name/dbName/policy/sourceType 完整清单 |
| `chainbase/src/test/java/org/tron/core/archive/domain/ArchiveCoverageTest.java` | chainbase | P0 covered/unsupported/deferred 响应语义 |
| `chainbase/src/test/java/org/tron/core/archive/domain/ArchiveDomainChecksumTest.java` | chainbase | domain registry checksum 改动可检测 |
| `chainbase/src/test/java/org/tron/core/archive/codec/ArchiveKeyCodecTest.java` | chainbase | account/code/contract/storage semantic key round-trip |
| `framework/src/test/java/org/tron/core/db/ChainBaseArchiveDomainBindingTest.java` | framework | Spring context 中实际 Store dbName 绑定到 registry |

### 5.3 P0 最小 domain

| Domain | 当前 Store/source | P0 状态 | 测试重点 |
| --- | --- | --- | --- |
| `ACCOUNT` | `AccountStore` | covered | address key、balance/account bytes codec |
| `CONTRACT` | `ContractStore` | covered | contract version/codeHash metadata，服务 storage key version 和 historical VM |
| `CODE` | `CodeStore` | covered | 21-byte address key，`eth_getCode` 在 deploy 前后变化 |
| `CONTRACT_STORAGE` | `Storage.java` semantic `(address, slot)` | covered | delete 后 tombstone，不能只存 physical row key |
| `DYNAMIC_PROPERTIES` | `DynamicPropertiesStore` allowlist | covered by key policy | historical VM 需要的执行参数进入 history/root allowlist |
| `ABI` / `CONTRACT_STATE` | `AbiStore`/`ContractStateStore` | partial/deferred by policy | 不影响 P0 getCode/storage getter，但 registry 要显式声明 |
| `BALANCE_HISTORY` | existing `balance.history.lookup` | separate existing feature | 不能混作 archive temporal source |
| 其他 20+ Store | `ChainBaseManager` imports/getters | unsupported/deferred | debug coverage API 返回清楚原因 |

### 5.4 必测用例

| 用例 | Arrange | Assert |
| --- | --- | --- |
| all P0 domains registered | 创建 default registry | `ACCOUNT`、`CONTRACT`、`CODE`、`CONTRACT_STORAGE`、`DYNAMIC_PROPERTIES` 存在，id 稳定 |
| dbName binding | `AccountStore.getDbName()`、`CodeStore.getDbName()` | registry 绑定实际 dbName，不接受 null |
| store-specific domain | 写 `ContractStore.put`、`AbiStore.put` | collector 仍能产生对应 domain 或明确 deferred |
| semantic storage key | address + slot + contract version | key round-trip 后仍是 `(address, slot)`，不泄露 `storage-row` physical key |
| checksum drift | 增删 domain 或改 codec version | checksum 变化；startup verifier 可检测不兼容 |
| unsupported domain response | 查询未覆盖 Store | 返回 unsupported/deferred，不返回假数据 |

### 5.5 失败判据

- domain id 依赖 enum ordinal，插入新 domain 会改变旧 id。
- `CONTRACT_STORAGE` 用 physical `storage-row` key 做历史 key。
- registry 无法解释 Store coverage，debug API 只能返回空。
- `ContractStore`/`AbiStore`/`ContractStateStore` 绕过 generic hook 后无人记录。

## 6. Module 03：ArchiveWriteCollector

### 6.1 当前源码锚点

| java-tron 文件 | 当前事实 | 测试含义 |
| --- | --- | --- |
| `TronStoreWithRevoking.java:89-99` | 通用 put/delete 写入 `revokingDB` | raw hook 应在这里捕获大多数 Store |
| `ContractStore.java:31-39` | store-specific put 会清 ABI 且绕过 super.put | 必须单测 store-specific hook |
| `AbiStore.java:27-32` | direct raw bytes put | 必须单测 |
| `ContractStateStore.java:27-32` | direct raw capsule put | 必须单测 |
| `actuator/.../vm/program/Storage.java:73-94` | `getValue/put` 先操作 rowCache | storage semantic hook 应捕获 dirty semantic writes |
| `Storage.java:96-105` | commit 时 zero value -> delete，否则 put row | archive write 必须记录 tombstone，不丢历史 delete |
| `StorageTest.java:27-75` | rootRepository put/commit/delete 基础 storage 现有测试 | 可新增 archive hook 断言 |
| `StorageTest.java:91-186` | 合约部署、写入、覆盖、删除 | 合约级 semantic storage 集成 fixture |

### 6.2 应新增/修改的测试

| 测试类 | 模块 | 覆盖 |
| --- | --- | --- |
| `chainbase/src/test/java/org/tron/core/archive/write/ArchiveWriteCollectorTest.java` | chainbase | merge、last-write-wins、delete tombstone、deterministic order |
| `chainbase/src/test/java/org/tron/core/archive/write/BlockWriteSetCodecTest.java` | chainbase | write-set encode/decode 稳定 |
| `framework/src/test/java/org/tron/core/db/ArchiveStoreHookTest.java` | framework | generic put/delete 和 store-specific put 都进 collector |
| `actuator/src/test/java/org/tron/core/vm/program/ArchiveStorageSemanticHookTest.java` | actuator | `(address, slot)` 写入/覆盖/删除语义 |
| `framework/src/test/java/org/tron/core/db/ArchiveWriteCollectorFailureTest.java` | framework | retry/abort 不残留半个 block write-set |
| `framework/src/test/java/org/tron/core/db/ArchiveTxScopedCollectorCheckpointTest.java` | framework | 每笔 canonical tx 的 collector begin/end/abort 与 nested session/checkpoint 对齐 |

### 6.3 必测用例

| 用例 | Arrange | Assert |
| --- | --- | --- |
| last write wins | 同一 tx 内 key 写 `v1 -> v2` | write-set 最终 value 为 `v2`，history 仍可按设计选择保留 changes |
| delete tombstone | 写 `v1` 后 delete | change-set 有 old value，latest 删除 |
| deterministic order | 乱序写入多个 domain/key | encoded write-set 按 domain/key/txNum 稳定排序 |
| generic hook | `AccountStore.put/delete` | collector 收到 `ACCOUNT` write |
| store-specific hook | `ContractStore.put`、`AbiStore.put` | collector 收到对应 write 或 deferred metadata，不能沉默 |
| semantic storage | `Storage.put` + `commit` | collector key 是 address + slot，value 是 semantic value/tombstone |
| disabled no-op | archive disabled | hook 快速返回，不改变 store 行为 |
| failure cleanup | block 执行失败 | collector 清空 current block writes |
| canonical tx failure | 第 N 笔 tx 抛异常 | `abortTx` 清掉当前 tx collector buffer，整个 block abort，不跳过失败 tx |

### 6.4 失败判据

- collector 读取 latest store 反推 old value，而不是使用 block/tx context。
- delete 被编码成空 byte array，无法区分 empty value 和 tombstone。
- `Storage.java` 只在 physical `StorageRowStore` hook 记录，丢失 original slot。
- retry block 时复用上一次 failed write-set。
- push/apply 路径复用产块语义跳过失败交易。

## 7. Module 04：ArchiveTemporalStore

### 7.1 当前源码锚点

| java-tron 文件 | 当前事实 | 测试含义 |
| --- | --- | --- |
| `LevelDbDataSourceImpl.java:414-416` | batch 中 `value == null` 转 delete，否则 put | ArchiveRawStore 可统一用 null tombstone 表达 delete |
| `RocksDbDataSourceImpl.java:307-309` | RocksDB batch 同样支持 null delete | LevelDB/RocksDB 语义必须一致 |
| `Manager.java:1380-1381` | normal applyBlock 后 session commit | temporal applyBlock 只能跟随 canonical commit |
| `Manager.java:1034-1041` | eraseBlock fastPop | temporal unwind 要同步 latest/history/changeset/progress |
| `chainbase/build.gradle:17-40` | chainbase 有 test task | temporal core 放 chainbase，新增 test dir |

### 7.2 应新增/修改的测试

| 测试类 | 模块 | 覆盖 |
| --- | --- | --- |
| `chainbase/src/test/java/org/tron/core/archive/store/ArchiveRawStoreTest.java` | chainbase | fake + LevelDB/RocksDB batch smoke，null delete |
| `chainbase/src/test/java/org/tron/core/archive/store/ArchiveStoreKeyCodecTest.java` | chainbase | temporal key prefix、lexicographic order、version byte |
| `chainbase/src/test/java/org/tron/core/archive/store/ArchiveValueCodecTest.java` | chainbase | tombstone/empty/latest/txNum/progress codec |
| `chainbase/src/test/java/org/tron/core/archive/temporal/DefaultArchiveTemporalStoreTest.java` | chainbase | put/update/delete/getAsOf |
| `chainbase/src/test/java/org/tron/core/archive/temporal/DefaultArchiveTemporalStoreUnwindTest.java` | chainbase | latest/history/changeset/root/progress 回退 |
| `chainbase/src/test/java/org/tron/core/archive/startup/ArchiveStartupVerifierTest.java` | chainbase | progress/head mismatch、registry checksum mismatch |
| `framework/src/test/java/org/tron/core/db/ArchiveTemporalStoreManagerWiringTest.java` | framework | Manager commit 与 `eraseBlock` temporal 集成 |

### 7.3 Temporal keyspace

建议单 DB 内至少拆这些 prefix，测试用 key codec 固定顺序：

| Prefix | 内容 | 验收 |
| --- | --- | --- |
| `latest` | domain/key -> current value/tombstone | `getLatest` 与 canonical head 一致 |
| `history` | domain/key/txNum -> previous value | `getAsOf(block/txNum)` 可回放到历史点 |
| `changeset` | txNum -> writes | unwind 可精确恢复 |
| `txnum` | block -> tx range，txNum -> state point | reader 可定位 block/tx |
| `root` | block/txNum -> sidecar root | proof/debug root lookup |
| `progress` | applied head、registry checksum、format version | restart verifier |

### 7.4 必测用例

| 用例 | Arrange | Assert |
| --- | --- | --- |
| create then read | `B1` 写 account A | `getAsOf(A, B1)=v1` |
| update old value | `B2` A 从 `v1 -> v2` | `getAsOf(A, B1)=v1`，`getAsOf(A, B2)=v2` |
| delete | `B3` 删除 key | `B3` 返回 tombstone/zero，`B2` 仍返回 old value |
| multi-domain atomic | 同 block 写 account/code/storage | batch 成功才统一更新 progress |
| batch failure | fake raw store 在中间抛异常 | latest/history/progress 不半更新 |
| unwind one block | 回退 `B3` | latest 恢复到 `B2`，history/changeset/root 删除 `B3` |
| unwind multiple blocks | 回退 `B5/B6` 到 `B4` | `B5/B6` roots 不可查，`B4` 可查 |
| restart verifier ok | progress head == canonical head | service 启动 |
| restart verifier mismatch | progress head > canonical head | 拒绝启动或要求 repair，不 silent continue |

### 7.5 失败判据

- `getAsOf` 扫描全 DB 才能查一个 key。
- applyBlock 先更新 progress 再写 latest/history。
- unwind 只删 root，不恢复 latest。
- LevelDB 和 RocksDB 对 tombstone 语义不同。

## 8. Module 05：ArchiveStateReader

### 8.1 当前源码锚点

| java-tron 文件 | 当前事实 | 测试含义 |
| --- | --- | --- |
| `TronJsonRpc.java:90-108` | `eth_getBalance`、`eth_getStorageAt`、`eth_getCode` 方法声明 | JSON-RPC P0 getter 不新增新 API，修改现有方法分支 |
| `TronJsonRpcImpl.java:383-397` | `requireLatestBlockTag` 当前拒绝非 latest | archive enabled 后 non-latest 分支必须绕过该 guard |
| `TronJsonRpcImpl.java:457-470` | balance 当前只读 `wallet.getAccount` latest | historical path 不能调用 latest wallet account |
| `TronJsonRpcImpl.java:611-631` | storage 当前用 `StorageRowStore` latest physical row | historical path 必须读 temporal semantic storage |
| `TronJsonRpcImpl.java:635-649` | code 当前用 `wallet.getContractInfo` latest | historical path 必须读 archive code domain |
| `JsonRpcApiUtil.java:583-636` | block tag/number parser 已支持 latest/earliest/finalized/safe/pending 语义 | reader 只接收已解析 state point，不复制 parser |
| `JsonrpcServiceTest.java:524-590` | 当前 non-latest state getter 被拒绝 | archive tests 要证明行为从 reject 变为 historical read |

### 8.2 应新增/修改的测试

| 测试类 | 模块 | 覆盖 |
| --- | --- | --- |
| `chainbase/src/test/java/org/tron/core/archive/reader/ArchiveStorageKeyCodecTest.java` | chainbase | `address21 || slot32 || version` key 长度、suffix、无 physical hash |
| `chainbase/src/test/java/org/tron/core/archive/reader/DefaultArchiveStateReaderTest.java` | chainbase | account/code/storage historical read，不读 latest store |
| `framework/src/test/java/org/tron/core/archive/reader/JsonRpcArchiveStatePointResolverTest.java` | framework | latest/earliest/finalized/quantity -> StatePoint，gap/缺块错误 |
| `framework/src/test/java/org/tron/core/services/jsonrpc/ArchiveJsonRpcStateAdapterTest.java` | framework | JSON hex formatting、zero/default 语义、异常映射 |
| `framework/src/test/java/org/tron/core/services/jsonrpc/TronJsonRpcHistoricalGettersTest.java` | framework | `eth_getBalance/getCode/getStorageAt` non-latest integration |
| `framework/src/test/java/org/tron/core/jsonrpc/JsonrpcServiceTest.java` | framework | 保留 latest/tag regression，避免破坏现有 API |

### 8.3 必测用例

| 用例 | Arrange | Assert |
| --- | --- | --- |
| archive disabled historical balance | disabled + block `0x1` | 保持当前错误：quantity/tag not supported |
| archive enabled historical balance | fake reader has A at `B1/B2` | `eth_getBalance(A, B1)` 和 latest 不同 |
| missing historical point | 请求未归档高度 | 返回明确 archive missing/history unavailable 错误，不 fallback latest |
| historical code before deploy | `B2` before deploy | `eth_getCode(C, B2) = 0x` |
| historical code after deploy | `B3` after deploy | `eth_getCode(C, B3) != 0x` |
| historical storage overwrite | `B4/B5` | `B4=abc`，`B5=123` |
| historical storage delete | `B6` | 返回 32 字节 zero；`B5` 仍为 `123` |
| block tag parsing | `earliest/latest/finalized/pending/safe/0xN` | pending/safe 维持当前错误；number/tag 映射到 StatePoint |
| no latest read | fake wallet latest 与 fake archive 不同 | non-latest 返回 archive 值 |

### 8.4 失败判据

- `TronJsonRpcImpl` 对 historical getter 先调用 `requireLatestBlockTag`。
- archive reader 找不到值时读 `wallet` 或 latest Store 补齐。
- `eth_getStorageAt` historical path 使用 `StorageRowStore` physical key。
- `eth_getCode` historical path 读到 `ContractStore` metadata 却没有 runtime code domain。

## 9. Module 06：CommitmentBuilder

### 9.1 当前源码锚点

| java-tron 文件 | 当前事实 | 测试含义 |
| --- | --- | --- |
| `protocol/src/main/protos/core/Tron.proto:505` | block header 有 `txTrieRoot` | archive P0 不修改 tx root |
| `Tron.proto:513` | block header 有 `accountStateRoot` | archive P0 不写 consensus stateRoot |
| `chainbase/src/main/java/org/tron/core/capsule/BlockCapsule.java:249` | `setMerkleRoot()` 写 `txTrieRoot` | commitment builder 不调用它 |
| `BlockCapsule.java:255-258` | `setAccountStateRoot(byte[])` 写 header account root | commitment builder 不调用它 |
| `framework/src/main/java/org/tron/core/services/jsonrpc/types/BlockResult.java` | `stateRoot` 映射 header `accountStateRoot` | JSON block result 不暴露 archive root |
| `framework/src/main/java/org/tron/core/trie/TrieImpl.java:381-429` | 已有 proof 生成能力 | 可借鉴 proof shape，但不能伪装 Ethereum account/storage proof |
| `TrieImpl.java:490-557` | 已有 proof verify | debug proof verifier 可借鉴测试模式 |

### 9.2 应新增/修改的测试

| 测试类 | 模块 | 覆盖 |
| --- | --- | --- |
| `chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentHashTest.java` | chainbase | domain separation、empty root、hash determinism |
| `chainbase/src/test/java/org/tron/core/archive/commitment/CommitmentNodeCodecTest.java` | chainbase | node codec/copy/hash length/collision guards |
| `chainbase/src/test/java/org/tron/core/archive/commitment/SparseMerkleArchiveCommitmentTreeTest.java` | chainbase | put/update/delete/order independence |
| `chainbase/src/test/java/org/tron/core/archive/commitment/RootKeyCodecTest.java` | chainbase | block root/tx root key ordering |
| `chainbase/src/test/java/org/tron/core/archive/commitment/DefaultArchiveCommitmentBuilderTest.java` | chainbase | BlockWriteSet -> domain roots -> global root |
| `chainbase/src/test/java/org/tron/core/archive/commitment/ArchiveCommitmentUnwindTest.java` | chainbase | root current/progress unwind |
| `chainbase/src/test/java/org/tron/core/archive/commitment/ArchiveCommitmentRebuildVerifierTest.java` | chainbase | persisted root 与 rebuild root 一致 |
| `chainbase/src/test/java/org/tron/core/archive/commitment/ArchiveTxRootComputerTest.java` | chainbase | transaction-level root from `CHANGESET` replay |
| `framework/src/test/java/org/tron/core/services/jsonrpc/BlockResultArchiveRootRegressionTest.java` | framework | `stateRoot` 仍来自 header，不是 archive sidecar |

### 9.3 必测用例

| 用例 | Arrange | Assert |
| --- | --- | --- |
| deterministic root | 同一 write-set 不同输入顺序 | root 相同 |
| domain separation | 同 key/value 放 ACCOUNT 与 CODE domain | domain root/global root 不同 |
| update root | `v1 -> v2` | root 改变；rebuild root 等于 persisted root |
| delete root | tombstone delete | root 改变；历史 proof 可证明 tombstone |
| empty block root | `B7` no user writes but finalize txNum exists | root 规则稳定，progress 可前进 |
| transaction root | same key `A -> B -> A` in one block | intermediate tx root changes，block-end root may equal parent |
| reorg root | 原 `B5` 与 alternate `B5'` | roots 不同；unwind 后原 root 不可作为 canonical |
| header untouched | apply archive commitment | `BlockCapsule.setAccountStateRoot` 没被调用；BlockResult stateRoot 不变 |
| rebuild mismatch | 手动篡改 temporal value/root | verifier 报 mismatch，不能 silent pass |

### 9.4 失败判据

- archive root 写入 `BlockHeader.raw.accountStateRoot`。
- root 只 hash latest value，不包含 domain/key/tombstone/type/version。
- proof 只断言 non-null，不验证 corrupt proof 失败。
- rebuild verifier 发现 mismatch 后仍标记 progress 成功。

## 10. Historical eth_call 模块

虽然 `eth_call` 不是六模块之一，但它是 issue #6289 的 P0 外部能力，依赖 Module 05 reader 和 actuator repository adapter，必须单独验收。

### 10.1 当前源码锚点

| java-tron 文件 | 当前事实 | 测试含义 |
| --- | --- | --- |
| `TronJsonRpc.java:162-170` | `eth_call` 方法声明 | 保持 API，改 block param 分支 |
| `TronJsonRpcImpl.java:1001-1051` | object-form blockNumber/blockHash 校验后 line 1037 重写为 latest | archive enabled 后不能重写为 latest |
| `TronJsonRpcImpl.java:557-608` | `call(...)` 当前委托 wallet latest constant call | historical path 需要独立 executor/repository |
| `VMTestBase.java:22-35` | VM 测试开启 debug 并准备 rootRepository | historical executor 可以复用测试上下文 |
| `StorageTest.java:91-186` | StorageDemo 合约 fixture | `eth_call` 应读历史 storage 返回不同结果 |

### 10.2 应新增/修改的测试

| 测试类 | 模块 | 覆盖 |
| --- | --- | --- |
| `actuator/src/test/java/org/tron/core/vm/repository/ArchiveRepositoryAdapterTest.java` | actuator | account/code/storage/dynamic properties 从 reader 读 |
| `actuator/src/test/java/org/tron/core/vm/repository/ArchiveRepositoryChildTest.java` | actuator | child overlay commit、delete tombstone、root final no write |
| `chainbase/src/test/java/org/tron/core/archive/vm/HistoricalVmDynamicPropertiesTest.java` | chainbase | historical dynamic properties 从 archive domain 和 historical block 读 |
| `actuator/src/test/java/org/tron/core/vm/archive/HistoricalConstantCallExecutorTest.java` | actuator | constant call 不写 latest，不污染 repository |
| `framework/src/test/java/org/tron/core/services/jsonrpc/TronJsonRpcEthCallArchiveTest.java` | framework | string block param 和 object-form blockNumber/blockHash |
| `common/src/test/java/org/tron/core/vm/config/VmConfigScopeTest.java` | common/actuator | historical call 后 VM static config restore |

### 10.3 必测用例

| 用例 | Arrange | Assert |
| --- | --- | --- |
| string block number | `eth_call(args, "0x4")` | 用 `B4` storage，返回 `abc` |
| object blockNumber | `eth_call(args, {"blockNumber":"0x5"})` | 不重写 latest，返回 `123` |
| object blockHash | 用 `B4` hash | 定位到 `B4`，返回 `abc` |
| before deploy | `B2` 调合约 | 明确 contract not found 或 empty code 错误，不 latest |
| after delete | `B6` | 读取 delete 后状态 |
| no write | call 执行期间尝试写 | latest temporal/canonical store 不变 |
| VM config restore | call 设置 historical context | call 结束后 static `VMConfig` 恢复 |
| latest leak guard | latest Store fake 设置为 read-fail | historical call 不触发 latest account/contract/code/storage/dynamic store |
| unsupported native/resource path | 触发 P0 未覆盖 domain | 返回明确 internal error，不读 latest |

### 10.4 失败判据

- object-form block param 校验后仍 `blockNumOrTag = latest`。
- historical call 通过 `wallet.triggerConstantContract` 读取 latest。
- historical repository 写入 canonical store。
- call 抛错后 VM static config 未恢复。

## 11. Proof/Debug API 模块

### 11.1 当前源码锚点

| java-tron 文件 | 当前事实 | 测试含义 |
| --- | --- | --- |
| `TronJsonRpc.java:251` | `eth_getTransactionCount` 当前存在但实现返回 method-not-found | P0 不趁机实现 transaction count |
| `TronJsonRpcImpl.java:1399` | `eth_getTransactionCount` message-not-found | regression test 保持 |
| 当前 `TronJsonRpc.java` | 没有 `eth_getProof` | P0 不新增 Ethereum proof API |
| `JsonRpcServlet.java:74-81` | composite service 只有 `TronJsonRpc` | debug API 若加方法，应仍走同一接口并受 config guard |
| `reference.conf:401-427` | JSON-RPC 配置是 node rpc 限额 | archive debug 开关不放这里 |
| `reference.conf:765` | `vmTrace=false` | archive proof/debug 不打开全局 VM trace |
| `VMActuator.java:297-308`、`VMUtils.java:55-98` | VM trace 会保存 trace 文件 | S14 不使用这个文件 trace 通路 |

### 11.2 应新增/修改的测试

| 测试类 | 模块 | 覆盖 |
| --- | --- | --- |
| `common/src/test/java/org/tron/core/config/args/StorageConfigArchiveDebugTest.java` | common | debug default false、FullNode-only 配置、proof limits |
| `chainbase/src/test/java/org/tron/core/archive/commitment/ArchiveRootReaderTest.java` | chainbase | block/tx root lookup from L7 |
| `chainbase/src/test/java/org/tron/core/archive/commitment/ArchiveTxRootComputerTest.java` | chainbase | bounded on-demand tx root from L7 |
| `chainbase/src/test/java/org/tron/core/archive/proof/ArchiveRootResultTest.java` | chainbase | root response invariant: root scope is sidecar, not header |
| `chainbase/src/test/java/org/tron/core/archive/proof/ArchiveProofTargetResolverTest.java` | chainbase | domain name/id and path32 target resolution |
| `chainbase/src/test/java/org/tron/core/archive/proof/ArchiveDomainProofBuilderTest.java` | chainbase | existence/non-existence/tombstone proof |
| `chainbase/src/test/java/org/tron/core/archive/proof/ArchiveGlobalProofBuilderTest.java` | chainbase | domain root included in global root |
| `chainbase/src/test/java/org/tron/core/archive/proof/ArchiveProofVerifierTest.java` | chainbase | valid proof passes，corrupt proof fails |
| `chainbase/src/test/java/org/tron/core/archive/proof/ArchiveProofLimitCheckerTest.java` | chainbase | proof node/value/result limits |
| `framework/src/test/java/org/tron/core/services/jsonrpc/TronJsonRpcArchiveDebugTest.java` | framework | default off、config on、method errors、no `eth_getProof` |
| `framework/src/test/java/org/tron/core/services/jsonrpc/BlockResultArchiveRootRegressionTest.java` | framework | archive root 不进入 `BlockResult.stateRoot` |

### 11.3 必测用例

| 用例 | Arrange | Assert |
| --- | --- | --- |
| debug default off | `storage.archive.debug.enable=false` | `debug_getArchiveRoot` 返回 disabled error |
| root at block | persisted `root(B5)` | 返回 root、block、format version |
| root at tx | persisted/on-demand tx root | bounded range 内成功，超限拒绝 |
| proof existence | `ACCOUNT/A/B2` | verifier true |
| proof non-existence | 未存在 key | verifier true with absence marker |
| proof tombstone | `CONTRACT_STORAGE/C/SLOT_1/B6` | verifier true with tombstone |
| corrupt proof | 改 proof node/root/value 任一字节 | verifier false |
| no eth_getProof | JSON-RPC 调 `eth_getProof` | method-not-found |
| no debug_traceCall | JSON-RPC 调 `debug_traceCall` | method-not-found 或 existing unsupported，不打开 VM trace |

### 11.4 失败判据

- archive proof response 命名或结构伪装成 Ethereum `eth_getProof`。
- debug API 开关放在 `node.jsonrpc`，而不是 `storage.archive.debug`。
- verifier 只校验 root 字段存在。
- 调 proof/debug API 产生 `./vm_trace` 文件。

## 12. 跨模块需求追踪矩阵

| 需求 | 首个证明测试 | 集成证明测试 | 负向测试 |
| --- | --- | --- | --- |
| 默认关闭且 no-op | `StorageConfigArchiveTest`、`NoopArchiveServiceTest` | `ManagerArchiveLifecycleTest` disabled case | disabled 下无 archive DB 写入 |
| txNum 覆盖 user/finalize/unwind | `ArchiveTxNumIndexTest` | `ManagerArchiveLifecycleTest`、`ArchiveTemporalStoreManagerWiringTest` | filtered tx 下标不影响 txNum |
| domain 集中注册 | `ArchiveDomainRegistryTest` | `ChainBaseArchiveDomainBindingTest` | unknown Store 返回 unsupported |
| semantic storage | `ArchiveStorageSemanticHookTest` | `TronJsonRpcHistoricalGettersTest` | physical row key 不能作为 archive key |
| temporal getAsOf | `DefaultArchiveTemporalStoreTest` | historical getters | missing history 不 fallback latest |
| atomic apply/unwind | `DefaultArchiveTemporalStoreUnwindTest` | `ArchiveTemporalStoreManagerWiringTest` | batch failure 无半更新 |
| historical balance/code/storage | `DefaultArchiveStateReaderTest` | `TronJsonRpcHistoricalGettersTest` | archive disabled 保持当前 reject |
| historical eth_call | `ArchiveRepositoryAdapterTest`、`ArchiveRepositoryChildTest`、`HistoricalVmDynamicPropertiesTest`、`VmConfigScopeTest` | `TronJsonRpcEthCallArchiveTest` | object-form 不重写 latest；VMConfig restore；无 latest state 泄漏 |
| sidecar root | `DefaultArchiveCommitmentBuilderTest` | `BlockResultArchiveRootRegressionTest` | 不写 accountStateRoot |
| transaction-level root | `ArchiveTxRootComputerTest` | proof/debug root lookup | `persistTxRoots=false` 仍可 on-demand |
| rebuild verifier | `ArchiveCommitmentRebuildVerifierTest` | startup verifier | mismatch 不 silent pass |
| archive-native proof/debug | `ArchiveProofVerifierTest` | `TronJsonRpcArchiveDebugTest` | 无 `eth_getProof`、无 `debug_traceCall` |

## 13. 分层执行顺序

建议按下面顺序写测试和实现，避免跨模块耦合：

1. `common`：`StorageConfigArchiveTest`，先固定默认值和 config schema。
2. `chainbase`：`NoopArchiveServiceTest`、`ArchiveTxNumIndexTest`，固定 lifecycle 基础接口。
3. `framework`：`ManagerArchiveLifecycleTest`，证明 archive context 和 canonical commit 边界。
4. `chainbase`：`ArchiveDomainRegistryTest`、`ArchiveKeyCodecTest`，固定 domain 和 key schema。
5. `chainbase/framework/actuator`：collector 和 semantic storage hook。
6. `chainbase`：temporal store、raw store、startup verifier。
7. `chainbase/framework`：state reader 和 JSON-RPC historical getters。
8. `chainbase/framework`：commitment root 和 header untouched regression。
9. `actuator/framework`：historical eth_call。
10. `chainbase/framework`：archive proof/debug API。

## 14. 推荐执行命令

按模块：

```bash
./gradlew :common:test --tests '*StorageConfigArchiveTest'
./gradlew :chainbase:test --tests '*ArchiveTxNumIndexTest'
./gradlew :chainbase:test --tests '*ArchiveDomain*Test'
./gradlew :chainbase:test --tests '*ArchiveWriteCollectorTest'
./gradlew :chainbase:test --tests '*ArchiveTemporal*Test'
./gradlew :chainbase:test --tests '*ArchiveCommitment*Test'
./gradlew :chainbase:test --tests '*ArchiveProof*Test'
./gradlew :actuator:test --tests '*ArchiveRepositoryAdapterTest'
./gradlew :actuator:test --tests '*ArchiveRepositoryChildTest'
./gradlew :chainbase:test --tests '*HistoricalVmDynamicPropertiesTest'
./gradlew :common:test --tests '*VmConfigScopeTest'
./gradlew :framework:test --tests '*ManagerArchive*Test'
./gradlew :framework:test --tests '*TronJsonRpcHistorical*Test'
./gradlew :framework:test --tests '*TronJsonRpcEthCallArchiveTest'
./gradlew :framework:test --tests '*TronJsonRpcArchiveDebugTest'
```

milestone gate：

```bash
./gradlew :common:test --tests '*Archive*'
./gradlew :chainbase:test --tests '*Archive*'
./gradlew :actuator:test --tests '*Archive*'
./gradlew :framework:test --tests '*Archive*'
./gradlew checkstyleMain checkstyleTest
./gradlew build
```

如果实际类名未包含 `Archive`，必须在 PR 描述里列出等价测试命令和覆盖的矩阵行。

## 15. 完成定义

P0 不能只以“代码能编译”作为完成。必须同时满足：

- 本文模块测试类都存在，或有等价测试类并映射到追踪矩阵。
- 每个 P0 JSON-RPC 能力至少有一个 historical block 与 latest 不同的断言。
- 每个 negative case 都有测试：disabled、missing history、reorg、header untouched、no `eth_getProof`。
- `BlockResult.stateRoot` 仍来自 header `accountStateRoot`。
- proof verifier 至少覆盖 valid、absence、tombstone、corrupt 四类。
- 所有失败测试通过修实现解决，不通过 skip/mute 处理。
