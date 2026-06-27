# java-tron Archive 端到端实现矩阵与 PR 执行队列

日期：2026-06-02

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

> 2026-06-03 源码重校准：本文保留为旧执行矩阵，部分源码锚点基于 `a79693e450`，已不再匹配当前本地源码。当前权威基线是 `4e80f8ffa9a2`；当前源码存在 `common/src/main/resources/reference.conf` 和 `StorageConfig.java`，且精确冲突标记扫描无命中。编码主入口以 [java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md) 为准，逐模块源码对照以 [java-tron Archive：4e80 六模块源码对照细化](./20260603-java-tron-archive-4e80-six-modules-source-detail.md) 为准。

实现蓝图：[java-tron Archive 状态树实现蓝图](./20260602-java-tron-archive-implementation-blueprint.md)

落地路线图：[java-tron Archive 状态树：Erigon 源码深挖后的落地路线图](./20260601-java-tron-archive-erigon-source-synthesis-implementation-roadmap.md)

六模块本地源码对照实现总表：[java-tron Archive：六个模块本地源码对照实现总表](./20260602-java-tron-archive-module-by-module-java-tron-implementation-map.md)

S1/S2 编码执行包：[java-tron Archive S1/S2 编码执行包](./20260602-java-tron-archive-s1-s2-coding-packet.md)

模块 01 逐文件 Patch 清单：[java-tron Archive 模块 01：ArchiveTxNumIndex 逐文件 Patch 清单](./20260602-java-tron-archive-module-01-txnum-index-patch-checklist.md)

S3 ArchiveDomainRegistry 编码执行包：[java-tron Archive S3：ArchiveDomainRegistry 编码执行包](./20260602-java-tron-archive-s3-domain-registry-coding-packet.md)

S4 ArchiveWriteCollector 编码执行包：[java-tron Archive S4：ArchiveWriteCollector 编码执行包](./20260602-java-tron-archive-s4-write-collector-coding-packet.md)

S5 Contract Storage semantic hook 编码执行包：[java-tron Archive S5：Contract Storage Semantic Hook 编码执行包](./20260602-java-tron-archive-s5-contract-storage-semantic-coding-packet.md)

S6 ArchiveRawStore + temporal codecs 编码执行包：[java-tron Archive S6：ArchiveRawStore + Temporal Codecs 编码执行包](./20260602-java-tron-archive-s6-raw-store-temporal-codecs-coding-packet.md)

S7 Temporal commit/unwind/startup 编码执行包：[java-tron Archive S7：Temporal Commit / GetAsOf / Unwind / Startup 编码执行包](./20260602-java-tron-archive-s7-temporal-commit-unwind-startup-coding-packet.md)

S8 ArchiveStateReader core 编码执行包：[java-tron Archive S8：ArchiveStateReader Core 编码执行包](./20260602-java-tron-archive-s8-state-reader-core-coding-packet.md)

S9 JSON-RPC historical getters 编码执行包：[java-tron Archive S9：JSON-RPC Historical Getters 编码执行包](./20260602-java-tron-archive-s9-jsonrpc-historical-getters-coding-packet.md)

S10 Sparse Merkle tree core + root codecs 编码执行包：[java-tron Archive S10：Sparse Merkle Tree Core + Root Codecs 编码执行包](./20260602-java-tron-archive-s10-sparse-merkle-root-codecs-coding-packet.md)

S11 CommitmentBuilder integration + rebuild verifier 编码执行包：[java-tron Archive S11：CommitmentBuilder Integration + Rebuild Verifier 编码执行包](./20260602-java-tron-archive-s11-commitment-builder-integration-rebuild-coding-packet.md)

## 1. 本文定位

前面的文档已经把 Erigon V2/V3 模型、java-tron 源码入口、六个模块设计、PR1-PR9 代码级规格和逐文件 patch 清单全部拆开。本文只做一件事：

```text
把这些分散规格收敛成一份可执行实现矩阵。
```

它不是替代六个模块文档，而是编码前的总排程：

1. 明确 issue #6289 的每项诉求由哪个模块/PR 覆盖。
2. 明确能力阶段 PR1-PR9 与实际提交到 java-tron 的小 PR slice 的对应关系。
3. 明确每个 slice 的 java-tron 文件落点、前置条件、测试证据和停止条件。
4. 明确哪些范围仍然不能进入 P0，避免实现时扩大到共识 root、全量 25 DB 或多盘分段。

## 2. 当前外部需求复核

截至 2026-06-02，issue #6289 仍为 Open / In Progress。需求主线没有变化：

| issue #6289 诉求 | 本方案处理 |
| --- | --- |
| Archive node 支持历史状态查询 | PR1-PR6 建立 txNum、write-set、temporal history、StateReader |
| `eth_getBalance` 按历史 block 查询 | PR6 historical JSON-RPC getter |
| `eth_getCode` 按历史 block 查询 | PR6 historical JSON-RPC getter |
| `eth_getStorageAt` 按历史 block 查询 | PR6 historical JSON-RPC getter |
| `eth_call` 在历史状态上执行 | PR8 archive-backed Repository + historical VM context |
| TRON state 分散在多个 DB，需要组织成 stateRoot | 模块 02 registry + 模块 03 collector + 模块 06 sidecar root |
| stateRoot 不参与共识，避免影响 SR/fullnode | PR7 `rootScope=ARCHIVE_SIDECAR`，不写 `accountStateRoot` |
| Archive 存储量巨大，需要考虑分段/多盘 | PR5 P0 先 single physical `archive` DB，分段作为后续 segment abstraction |
| 性能影响必须可控 | archive 默认关闭；开启后先做同步 sidecar batch，后续再做 segment/async 优化 |

P0 的关键取舍：

```text
先完成 TVM_STATE_ONLY 的 archive sidecar。
先支持历史查询正确性。
先不做共识 root。
先不承诺全量 25 DB root。
先不拆多物理库。
```

## 3. java-tron 本地约束

本地 java-tron `AGENTS.md` 要求先遵守 `.codex/memory/CODEX_MEMORY.md`。对本任务有直接影响的规则：

| 约束 | 对实现队列的影响 |
| --- | --- |
| PR scope focused on one problem | 文档 PR1-PR9 是能力阶段，真正落地应拆成更小 landing slices |
| 非测试改动建议小于 10 文件 | 每个 slice 控制在一个明确边界，避免一次改完 Manager、Store、RPC、VM、root |
| Java 代码提交前运行 `./gradlew lint` | 所有涉及 Java 代码的 slice 都把 lint 作为最终 gate |
| import/checkstyle 变化要跑 `checkstyleMain checkstyleTest -x generateGitProperties` | 新增 package、测试或改 imports 的 slice 必跑 |
| 不运行 `git push`、不发 GitHub 评论 | 本实现计划只产出本地 patch/文档，PR 文案由用户自行发布 |
| JUnit 4 expected exception 使用 `assertThrows` | 新测试全部按该规则写 |
| 不加测试 skip | 失败测试必须修或交出复现，不用 skip 静默绕过 |

## 4. 完成定义

P0 完成不能只看“代码能编译”。需要同时满足以下证据：

| 编号 | 完成条件 | 证明方式 |
| --- | --- | --- |
| D1 | archive 默认关闭时 fullnode 行为不变 | no-op 单测 + 关键 Manager/Store/RPC 回归 |
| D2 | canonical block apply 期间每个 user/system phase 都有稳定 txNum | TxNumIndex 单测 + Manager lifecycle 集成测试 |
| D3 | state domain 映射不靠硬编码散落在 hook 中 | Registry 单测 + Store coverage 测试 |
| D4 | account/contract/code/storage 写能生成 deterministic BlockWriteSet | collector 单测 + contract storage semantic 测试 |
| D5 | temporal store 同 batch 原子持久化 latest/history/change index/progress | ArchiveTemporalStore 单测 + crash/progress verifier 测试 |
| D6 | switch fork / erase block 能 unwind archive sidecar | Manager integration test |
| D7 | historical `eth_getBalance/getCode/getStorageAt` 不读 latest store | JSON-RPC 单测或集成测试，构造 state changed 后读旧 block |
| D8 | block-end sidecar root 可重放、可校验、可 unwind | CommitmentBuilder 单测 + rebuild verifier 测试 |
| D9 | historical `eth_call` 使用 archive-backed Repository，不静默 fallback latest | PR8 integration test |
| D10 | root/proof/debug API 不伪装成共识/Ethereum proof | PR9 API tests + method naming review |

## 5. 能力阶段与实际 landing slices

已有文档使用 PR1-PR9 表示能力阶段。由于 java-tron PR 规则要求小而聚焦，实际落地建议再拆成 S0-S14。

| Landing slice | 能力阶段 | 目标 | 主要模块 |
| --- | --- | --- | --- |
| S0 | Milestone 0 | baseline source/test harness 确认 | 源码定位 |
| S1 | PR1 | archive config + no-op service skeleton | 模块 01 |
| S2 | PR2 | Manager lifecycle + txNum in-memory index | 模块 01 |
| S3 | 模块 02 | ArchiveDomainRegistry + codecs | 模块 02 |
| S4 | PR3 | raw Store hook + BlockWriteSet collector | 模块 03 |
| S5 | PR4 | contract storage semantic hook | 模块 03 |
| S6 | PR5 | archive raw store + temporal key/value codecs | 模块 04 |
| S7 | PR5 | temporal commit/unwind/startup verifier | 模块 04 |
| S8 | PR6 | ArchiveStateReader core | 模块 05 |
| S9 | PR6 | JSON-RPC historical getters | 模块 05 |
| S10 | PR7 | sparse Merkle tree core + root codecs | 模块 06 |
| S11 | PR7 | CommitmentBuilder integration + rebuild verifier | 模块 06 |
| S12 | PR8 | archive-backed Repository foundation | historical eth_call |
| S13 | PR8 | JSON-RPC historical `eth_call` | historical eth_call |
| S14 | PR9 | archive-native proof/debug APIs | proof/debug |

如果评审接受较大 PR，可以把相邻 slice 合并；但实现顺序不要变。

## 6. Slice 明细

### S0：Baseline 和验证基线

目标：

```text
确认当前 java-tron 分支、Gradle/JDK、关键源码路径和测试入口。
```

不改代码。建议先记录：

| 项 | 命令或证据 |
| --- | --- |
| java-tron HEAD | `git -C /Users/boson/IdeaProjects/java-tron rev-parse --short HEAD` |
| worktree 干净度 | `git -C /Users/boson/IdeaProjects/java-tron status --short` |
| Gradle wrapper 可用 | `cd /Users/boson/IdeaProjects/java-tron && ./gradlew --version` |
| 配置解析测试基线 | `./gradlew :common:test --tests org.tron.core.config.args.StorageTest` 或新增等价 `ArgsArchiveConfigTest` |
| Store/VM/RPC 相关测试基线 | 按后续 slice 逐步补 |

停止条件：

- 当前分支已有未理解的相关改动。
- Gradle/JDK 环境无法跑最小测试。

### S1：Archive config + no-op service skeleton

能力阶段：PR1。

参考文档：

- [PR1/PR2 代码级实现规格](./20260602-java-tron-archive-pr1-pr2-implementation-spec.md)
- [PR1/PR2 逐文件 Patch 清单](./20260602-java-tron-archive-pr1-pr2-patch-checklist.md)

目标：

```text
引入 archive 默认关闭配置和 no-op ArchiveService，不改变 block apply 行为。
```

主要文件落点：

| java-tron 文件 | 动作 |
| --- | --- |
| `common/src/main/java/org/tron/core/config/args/Storage.java` | 在当前 storage 配置模型中增加 `ArchiveConfig archive` 和 `storage.archive.*` helper |
| `framework/src/main/resources/config.conf` | 增加示例配置，默认关闭 |
| `common/src/main/java/org/tron/common/parameter/CommonParameter.java` | 继续通过 `public Storage storage` 暴露 archive runtime config |
| `framework/src/main/java/org/tron/core/config/args/Args.java` | 在 `Args.java:516-564` 的 storage 初始化段读取 archive 配置 |
| `framework/src/main/java/org/tron/core/config/args/CLIParameter.java` | P0 可不加 CLI；若评审要求再加 deprecated 参数 |
| `chainbase/src/main/java/org/tron/core/archive/ArchiveService.java` | 新增接口 |
| `chainbase/src/main/java/org/tron/core/archive/NoopArchiveService.java` | 默认实现 |
| `chainbase/src/main/java/org/tron/core/archive/ArchiveConfig.java` | 配置快照 |

测试：

| 测试 | 覆盖 |
| --- | --- |
| `common/src/test/java/org/tron/core/config/args/StorageTest.java` 或新增 `ArgsArchiveConfigTest` | `config.conf` default + user override |
| archive config unit test | `enable=false`、db directory、commitment 子配置 |
| no-op service test | 所有 hook 方法可调用且不产生 state |

验收：

- `storage.archive.enable=false` 是默认值。
- `ArchiveService` no-op 不读写 DB。
- 不触碰 Manager block apply。

### S2：Manager lifecycle + TxNumIndex

能力阶段：PR2。

目标：

```text
在 canonical block apply 中建立交易级时间坐标，但 archive 仍可 no-op。
```

主要文件落点：

| java-tron 文件 | 动作 |
| --- | --- |
| `framework/src/main/java/org/tron/core/db/Manager.java` | `pushBlock/processBlock/applyBlock/eraseBlock/switchFork` hook |
| `chainbase/src/main/java/org/tron/core/archive/ArchivePhase.java` | `BLOCK_PREPARE/USER_TX/BLOCK_FINALIZE/...` |
| `chainbase/src/main/java/org/tron/core/archive/StatePoint.java` | `LATEST/BLOCK_END/TX_BEFORE/TX_AFTER` |
| `chainbase/src/main/java/org/tron/core/archive/txnum/ArchiveTxNumIndex.java` | txNum 分配和解析接口 |
| `chainbase/src/main/java/org/tron/core/archive/txnum/InMemoryArchiveTxNumIndex.java` | P0 内存实现 |
| `chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java` | begin/end block/tx lifecycle |

必须覆盖的 Manager 源码事实：

| 源码事实 | 约束 |
| --- | --- |
| `Manager.pushBlock` 是 canonical block 入口 | archive begin/end block 必须挂这里或其调用链 |
| `Manager.processBlock` 遍历 tx | user txNum 必须按实际执行顺序分配 |
| normal block 在 revoking session 内执行 | sidecar flush 必须等 session commit 成功 |
| `eraseBlock/switchFork` 会回退 canonical chain | archive 必须跟随 unwind |

测试：

| 测试 | 覆盖 |
| --- | --- |
| TxNumIndex unit | block range、tx index、system phase、lexicographic key |
| Manager lifecycle unit/integration | success path、failed block abort、erase block |
| no-op regression | archive disabled 时 hook 不影响原执行 |

验收：

- `BLOCK_END(blockNum)` 解析到 exclusive `asOfTxNum`。
- `TX_BEFORE/TX_AFTER` 明确区分。
- block apply 失败不会留下 committed archive progress。

### S3：ArchiveDomainRegistry + codecs

能力阶段：模块 02。

参考文档：

- [模块 02 Patch 清单](./20260602-java-tron-archive-module-02-domain-registry-patch-checklist.md)

目标：

```text
把 java-tron 分散 Store 映射成稳定 archive domain，并固定 canonical key/value codec。
```

主要文件落点：

| java-tron 文件 | 动作 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/archive/domain/ArchiveDomain.java` | domain id/name |
| `chainbase/src/main/java/org/tron/core/archive/domain/ArchiveDomainDescriptor.java` | codec/root/history policy |
| `chainbase/src/main/java/org/tron/core/archive/domain/ArchiveDomainRegistry.java` | registry 接口 |
| `chainbase/src/main/java/org/tron/core/archive/domain/DefaultArchiveDomainRegistry.java` | P0 domain 映射 |
| `chainbase/src/main/java/org/tron/core/archive/domain/CanonicalKeyCodec.java` | key canonicalization |
| `chainbase/src/main/java/org/tron/core/archive/domain/CanonicalValueCodec.java` | value canonicalization |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java` | 修正/暴露 `getDbName()` 供 registry 使用 |

P0 domain：

| Domain | 来源 | PR7 RootPolicy |
| --- | --- | --- |
| `ACCOUNT` | `AccountStore` | PR7 TVM root 时 `IN_GLOBAL_ROOT` |
| `CONTRACT` | `ContractStore` | PR7 TVM root 时 `IN_GLOBAL_ROOT` |
| `CODE` | `CodeStore` | PR7 TVM root 时 `IN_GLOBAL_ROOT` |
| `CONTRACT_STORAGE` | semantic storage hook | PR7 TVM root 时 `IN_GLOBAL_ROOT` |
| `DYNAMIC_PROPERTIES` | allowlist keys | `DOMAIN_ROOT_ONLY` 或 allowlist 后 `IN_GLOBAL_ROOT` |

测试：

- domain id 不超过/超过 255 的编码测试，固定 `u16 domainId`。
- dbName -> domain 映射。
- excluded index store 不进入 state domain。
- canonical value roundtrip。
- unknown store policy。

验收：

- 后续 Store hook 不允许自己硬编码 dbName。
- registry 能清楚表达 `TVM_STATE_ONLY` coverage，不冒充全量 25 DB。

### S4：Raw Store hook + BlockWriteSet collector

能力阶段：PR3。

参考文档：

- [PR3/PR4 WriteCollector 规格](./20260602-java-tron-archive-pr3-pr4-write-collector-implementation-spec.md)
- [模块 03 Patch 清单](./20260602-java-tron-archive-module-03-write-collector-patch-checklist.md)
- [S4 ArchiveWriteCollector 编码执行包](./20260602-java-tron-archive-s4-write-collector-coding-packet.md)

目标：

```text
在通用 Store put/delete 路径捕获 before/after，形成 deterministic TxWriteSet/BlockWriteSet。
```

主要文件落点：

| java-tron 文件 | 动作 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java` | raw put/delete hook |
| `chainbase/src/main/java/org/tron/core/archive/collector/ArchiveWriteCollector.java` | collector 接口 |
| `chainbase/src/main/java/org/tron/core/archive/collector/DefaultArchiveWriteCollector.java` | in-memory collector |
| `chainbase/src/main/java/org/tron/core/archive/collector/TxWriteSet.java` | tx 写集合 |
| `chainbase/src/main/java/org/tron/core/archive/collector/BlockWriteSet.java` | block 写集合 |
| `chainbase/src/main/java/org/tron/core/archive/collector/DomainWrite.java` | domain/key/before/after |
| `chainbase/src/main/java/org/tron/core/store/ContractStore.java` | 直接 `revokingDB.put` 特例必须统一 hook |

测试：

- active context 内写入被捕获。
- no active context 只计数/告警，不破坏现有写。
- same key 多次写最终压缩顺序确定。
- `ContractStore.put` ABI 清理后的实际落盘值被捕获。
- codec error fail 当前 block apply，不静默 corrupt sidecar。

验收：

- PR3 不持久化 temporal history。
- PR3 不把 `TransactionStore` 这类索引 store 纳入 state domain。
- `BlockWriteSet` 是 PR5 和 PR7 的唯一写输入。

### S5：Contract storage semantic hook

能力阶段：PR4。

参考文档：

- [S5 Contract Storage semantic hook 编码执行包](./20260602-java-tron-archive-s5-contract-storage-semantic-coding-packet.md)

目标：

```text
把 TVM storage 写从 physical StorageRow key 转成 logical address + slot32 + storageKeyVersion。
```

主要文件落点：

| java-tron 文件 | 动作 |
| --- | --- |
| `actuator/src/main/java/org/tron/core/vm/program/Storage.java` | `commit()` 发 semantic storage write |
| `chainbase/src/main/java/org/tron/core/archive/collector/SemanticStoreWrite.java` | `contractStorage(address, slot, before, after, physicalKey, storageKeyVersion)` |
| `chainbase/src/main/java/org/tron/core/archive/domain/DefaultArchiveDomainRegistry.java` | `storage-row` physical store excluded |

关键约束：

- `CONTRACT_STORAGE` 只走 semantic path。
- key 固定为 `address21 || slot32 || storageKeyVersion_u8`。
- zero storage 归一为 `afterValue=null` tombstone。
- `Storage.getValue` 对 missing row 可能 NPE；新增 before 读取应避开现有易错路径。
- 只在 root repository 落盘 commit 时采集，不能把 child repository 中间态污染 archive。

测试：

- write/update/delete storage。
- same slot 多次写。
- delegatecall/callcode 场景 logical owner 正确。
- physical storage-row raw hook 不进入 `CONTRACT_STORAGE`。

验收：

- PR6/PR8 的 historical storage reader 可以直接用 address + slot 查 archive。

### S6：ArchiveRawStore + temporal codecs

能力阶段：PR5 前半。

参考文档：

- [PR5 TemporalStore 规格](./20260602-java-tron-archive-pr5-temporal-store-implementation-spec.md)
- [模块 04 Patch 清单](./20260602-java-tron-archive-module-04-temporal-store-patch-checklist.md)
- [S6 ArchiveRawStore + Temporal Codecs 编码执行包](./20260602-java-tron-archive-s6-raw-store-temporal-codecs-coding-packet.md)

目标：

```text
建立 single physical archive DB 的 key/value schema 和 batch API。
```

主要文件落点：

| java-tron 文件 | 动作 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveRawStore.java` | raw get/put/delete/batch |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveBatch.java` | same-batch staging，delete 用 null value |
| `chainbase/src/main/java/org/tron/core/archive/store/DefaultArchiveRawStore.java` | 封装 `DbSourceInter<byte[]>` |
| `chainbase/src/main/java/org/tron/core/archive/store/TreeMapArchiveRawStore.java` | 单测用 fake raw store |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveTable.java` | `META/TXNUM_BLOCK/TXNUM_BY_TXID/TXNUM_META/LATEST/HISTORY/CHANGESET` prefixes |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveKeyCodec.java` | big-endian key codec |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveValueCodec.java` | tombstone/value codecs |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveProgressCodec.java` | progress row codec |

P0 physical DB：

```text
one physical archive DB
0x01 meta/progress
0x10 txnum by block
0x11 txnum by txId
0x12 txnum meta by txNum
0x20 latest
0x21 history before-value
0x22 changeset
0x30+ commitment root reserved for PR7
```

测试：

- key ordering。
- tombstone encoding。
- malformed key/value decode。
- batch write atomicity with fake raw store。
- progress monotonicity。

验收：

- 不新增独立 `archive-root` DB。
- `domainId` 用 `u16`。
- PR7 能把 root rows 放进同一 `ArchiveBatch`。

### S7：Temporal commit/unwind/startup verifier

能力阶段：PR5 后半。

参考文档：

- [S7 Temporal Commit / GetAsOf / Unwind / Startup 编码执行包](./20260602-java-tron-archive-s7-temporal-commit-unwind-startup-coding-packet.md)

目标：

```text
把 BlockWriteSet 原子写入 latest/history/change index/progress，并支持 unwind。
```

主要文件落点：

| java-tron 文件 | 动作 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/archive/store/ArchiveTemporalStore.java` | temporal API |
| `chainbase/src/main/java/org/tron/core/archive/store/DefaultArchiveTemporalStore.java` | apply/getAsOf/unwind |
| `chainbase/src/main/java/org/tron/core/archive/store/ChangedKey.java` | decode/scan changeset |
| `chainbase/src/main/java/org/tron/core/archive/txnum/PersistentArchiveTxNumIndex.java` | temporal-backed txNum query |
| `framework/src/main/java/org/tron/core/db/Manager.java` | after canonical session commit flush sidecar |
| `framework/src/main/java/org/tron/core/archive/ArchiveStartupVerifier.java` | startup progress check |

关键语义：

```text
HISTORY(domain,key,txNum) stores before-value.
getAsOf(domain,key,asOfTxNum) seeks first txNum >= asOfTxNum.
if found, return before-value; else return latest.
```

测试：

- single key multi tx。
- same block same key 多次写。
- tombstone/missing。
- block failure does not advance progress。
- unwind block restores latest/history/progress。
- startup detects archive ahead/behind/corrupt。

验收：

- `applyBlock` 有 latest overlay，支持同 block 多 tx 修改同 key。
- S7 复用 S6 `ArchiveKeyCodec`，不新增 `TemporalKeyCodec`。
- 空 archive 不能在已有高区块节点上被标记为 OK。

验收：

- `GetAsOf` 只接受 TxNumIndex 解析后的 `asOfTxNum`。
- `BLOCK_END` 使用 exclusive as-of 语义，不引入 `BLOCK_AFTER`。

### S8：ArchiveStateReader core

能力阶段：PR6 前半。

参考文档：

- [PR6 StateReader/JSON-RPC 规格](./20260602-java-tron-archive-pr6-state-reader-jsonrpc-implementation-spec.md)
- [模块 05 Patch 清单](./20260602-java-tron-archive-module-05-state-reader-patch-checklist.md)
- [S8 ArchiveStateReader Core 编码执行包](./20260602-java-tron-archive-s8-state-reader-core-coding-packet.md)

目标：

```text
提供 archive-backed account/code/storage reader，不接 JSON-RPC。
```

主要文件落点：

| java-tron 文件 | 动作 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStateReader.java` | reader interface |
| `chainbase/src/main/java/org/tron/core/archive/reader/DefaultArchiveStateReader.java` | temporal store adapter |
| `chainbase/src/main/java/org/tron/core/archive/reader/ArchiveStateReaderFactory.java` | point -> reader |
| `chainbase/src/main/java/org/tron/core/archive/reader/ArchiveReadResult.java` | present/missing；corrupt/codec error 走 reader exception |

测试：

- `BLOCK_END` read account。
- read code missing vs empty。
- read storage zero vs missing。
- corrupt codec error 不转 default。

验收：

- reader 不读 latest Store。
- reader 不把所有 missing 提前转默认值，保留给 RPC/PR8 判断。
- reader 使用 `ArchiveReadResult` 区分 missing、present empty、present zero；codec error 不转 default。

### S9：JSON-RPC historical getters

能力阶段：PR6 后半。

参考文档：

- [S9 JSON-RPC Historical Getters 编码执行包](./20260602-java-tron-archive-s9-jsonrpc-historical-getters-coding-packet.md)
- [PR6 StateReader/JSON-RPC 规格](./20260602-java-tron-archive-pr6-state-reader-jsonrpc-implementation-spec.md)
- [模块 05 Patch 清单](./20260602-java-tron-archive-module-05-state-reader-patch-checklist.md)

目标：

```text
让 eth_getBalance、eth_getCode、eth_getStorageAt 支持历史 block number。
```

主要文件落点：

| java-tron 文件 | 动作 |
| --- | --- |
| `framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java` | three getter 分支 |
| `framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpc.java` | error 声明按现有框架调整 |
| `framework/src/main/java/org/tron/core/archive/ResolvedStatePoint.java` | latest/historical point result |
| `framework/src/main/java/org/tron/core/archive/ArchiveStatePointResolver.java` | block tag/quantity -> `StatePoint.blockEnd(N)` |
| `framework/src/main/java/org/tron/core/services/jsonrpc/archive/ArchiveJsonRpcStateAdapter.java` | reader result -> JSON-RPC defaults/errors |
| `framework/src/main/java/org/tron/core/services/jsonrpc/JsonRpcApiUtil.java` | 参考现有 latest/earliest/finalized/pending 语义；quantity 用 `ByteArray.hexToBigInteger` 保持裸 decimal 兼容 |

当前源码入口：

| RPC | 本地入口 |
| --- | --- |
| `eth_getBalance` | `TronJsonRpcImpl.getTrxBalance` |
| `eth_getStorageAt` | `TronJsonRpcImpl.getStorageAt` |
| `eth_getCode` | `TronJsonRpcImpl.getABIOfSmartContract` |
| `eth_call` | 保持 latest-only，留给 S12/S13 |

测试：

- latest 仍走原路径。
- historical block 走 archive reader。
- `0xN` 和裸 decimal `N` 都能解析为 historical block。
- `pending` 保留 unsupported invalid params；当前源码无 `safe` 常量，`safe` 仍按 invalid block number，除非同 PR 明确新增 safe 支持。
- archive disabled 返回明确错误。
- block gap/corrupt 返回 internal archive error。
- state changed after queried block 时，historical 结果不等于 latest。

验收：

- 不删除 `eth_call` 的 latest 限制。
- 不把 object block 参数校验后静默读 latest。
- `TronJsonRpc` 和 `TronJsonRpcImpl` 三个方法都声明 `JsonRpcInternalException`。
- historical storage 不构造 `Storage`，不读 `StorageRowStore`。

### S10：Sparse Merkle tree core + root codecs

能力阶段：PR7 前半。

参考文档：

- [S10 Sparse Merkle Tree Core + Root Codecs 编码执行包](./20260602-java-tron-archive-s10-sparse-merkle-root-codecs-coding-packet.md)
- [PR7 CommitmentBuilder 规格](./20260602-java-tron-archive-pr7-commitment-builder-implementation-spec.md)
- [模块 06 Patch 清单](./20260602-java-tron-archive-module-06-commitment-builder-patch-checklist.md)

目标：

```text
实现 archive-native content-addressed binary sparse Merkle tree 和 RootRecord codecs。
```

主要文件落点：

| java-tron 文件 | 动作 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/archive/commitment/SparseMerkleTree.java` | tree core |
| `chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentHash.java` | domain separated hash |
| `chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentUpdate.java` | normalized write |
| `chainbase/src/main/java/org/tron/core/archive/commitment/RootRecord.java` | block root record |
| `chainbase/src/main/java/org/tron/core/archive/commitment/DomainRootRecord.java` | domain root |
| `chainbase/src/main/java/org/tron/core/archive/commitment/RootKeyCodec.java` | `0x30+` keys |
| `chainbase/src/main/java/org/tron/core/archive/commitment/RootRecordCodec.java` | root/domain/current record values |
| `chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentNodeCodec.java` | node/leaf record values |
| `chainbase/src/main/java/org/tron/core/archive/commitment/LeafMetadataGuard.java` | `ROOT_LEAF(path32)` collision guard |

必须固定：

```text
rootScope = ARCHIVE_SIDECAR
consensusParticipation = NONE
domainId = u16
tree node = content-addressed immutable
ROOT_LEAF key = path32, canonicalKey stored in LeafRecord
no node GC in PR7
```

禁止事项：

- 不 import `framework` 下的 `TrieImpl` 到 `chainbase`。
- 不写 `BlockHeader.raw.accountStateRoot`。
- 不把 `txTrieRoot` 当状态 root。

测试：

- deterministic root。
- shuffled updates same root。
- domainId = 256/65535。
- zero/tombstone deletes leaf。
- content-addressed node lookup。
- same-block staged node overlay。
- path collision guard。
- malformed node/root record。

验收：

- standalone tree 测试不需要 Manager。
- root schema 与 PR5 `ArchiveTable` 兼容。

### S11：CommitmentBuilder integration + rebuild verifier

能力阶段：PR7 后半。

目标：

```text
把 BlockWriteSet 转为 block-end sidecar root，并和 temporal rows 同 batch 提交。
```

参考文档：

- [S11 CommitmentBuilder Integration + Rebuild Verifier 编码执行包](./20260602-java-tron-archive-s11-commitment-builder-integration-rebuild-coding-packet.md)
- [S10 Sparse Merkle Tree Core + Root Codecs 编码执行包](./20260602-java-tron-archive-s10-sparse-merkle-root-codecs-coding-packet.md)

主要文件落点：

| java-tron 文件 | 动作 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentBuilder.java` | block/tx root API |
| `chainbase/src/main/java/org/tron/core/archive/commitment/DefaultCommitmentBuilder.java` | implementation |
| `chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentNormalizer.java` | domain canonicalization |
| `chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentVerifier.java` | rebuild/checkIntegrity |
| `chainbase/src/main/java/org/tron/core/archive/ArchiveStartupVerifier.java` | root progress checks |
| `framework/src/main/java/org/tron/core/db/Manager.java` | stage temporal + root rows into same batch |

测试：

- block-end root exists for empty block。
- root-included domain 未改也写 `ROOT_DOMAIN`。
- unwind restores `ROOT_CURRENT` and `ROOT_LEAF` metadata。
- startup detects root missing/ahead/behind/mismatch。
- rebuilding from temporal history matches stored `ROOT_BLOCK`。

验收：

- commitment disabled 时 PR1-PR6 行为不变。
- existing archive DB 首次开启 commitment 不能从 empty root 继续写，必须 rebuild/bootstrap。

### S12：Archive-backed Repository foundation

能力阶段：PR8 前半。

参考文档：

- [S12/S13 historical eth_call 4e80 编码执行包](./20260604-java-tron-archive-s12-s13-historical-eth-call-4e80-coding-packet.md)
- [PR8 historical eth_call 规格](./20260602-java-tron-archive-pr8-historical-eth-call-implementation-spec.md)

目标：

```text
给 TVM constant call 注入 historical state，不接 JSON-RPC 行为。
```

主要文件落点：

| java-tron 文件 | 动作 |
| --- | --- |
| `actuator/src/main/java/org/tron/core/vm/repository/Repository.java` | 最小接口兼容或 adapter 需求 |
| `actuator/src/main/java/org/tron/core/vm/repository/RepositoryImpl.java` | 不直接改成 archive，保留 latest |
| `actuator/src/main/java/org/tron/core/vm/repository/ArchiveRepositoryAdapter.java` | historical read + simulation overlay |
| `actuator/src/main/java/org/tron/core/actuator/VMActuator.java` | root repository / dynamic view 注入点 |
| `chainbase/src/main/java/org/tron/core/archive/reader/ArchiveDynamicPropertiesView.java` | TVM 必需动态参数 |

关键风险：

- VM path 如果隐式读 latest dynamic properties，会导致 historical call 不可信。
- `RepositoryImpl` storage 使用 physical `Storage.compose`，archive adapter 必须用 logical `address21 || slot32 || storageKeyVersion_u8`。
- overlay 只用于 constant call，不能提交到 real store。

测试：

- historical repository read account/code/storage。
- simulation writes 不污染 archive。
- missing required dynamic property 返回明确错误。
- latest `RepositoryImpl` 行为不变。

验收：

- 不通过 `Wallet.triggerConstantContract` 直接复用 latest path。

### S13：JSON-RPC historical eth_call

能力阶段：PR8 后半。

目标：

```text
让 eth_call(callArgs, historical block) 在 archive-backed Repository 上执行 constant call。
```

主要文件落点：

| java-tron 文件 | 动作 |
| --- | --- |
| `framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java` | `eth_call` 分支 |
| `framework/src/main/java/org/tron/core/Wallet.java` | 如需抽出 pure call builder，只做最小变更 |
| `actuator/src/main/java/org/tron/core/actuator/VMActuator.java` | 使用 S12 注入点 |

测试：

- latest call 仍走原路径。
- historical call 返回旧状态下的结果。
- revert/error 编码与 latest path 一致。
- unsupported tag 明确报错。
- archive disabled/gap 不 fallback latest。

验收：

- issue #6289 的四个 Ethereum-compatible API 在 P0 范围内全部有历史能力。

### S14：Archive-native proof/debug APIs

能力阶段：PR9。

参考文档：

- [S14 proof/debug API 4e80 编码执行包](./20260604-java-tron-archive-s14-proof-debug-api-4e80-coding-packet.md)
- [PR9 Proof/Debug API 规格](./20260602-java-tron-archive-pr9-proof-debug-api-implementation-spec.md)

目标：

```text
暴露 archive-native root/proof/debug 能力，不伪装成 Ethereum consensus proof。
```

主要文件落点：

| java-tron 文件 | 动作 |
| --- | --- |
| `framework/src/main/java/org/tron/core/services/jsonrpc/TronJsonRpcImpl.java` | debug/archive namespace 方法 |
| `chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentProof.java` | proof model |
| `chainbase/src/main/java/org/tron/core/archive/commitment/CommitmentProofVerifier.java` | local verifier |
| `chainbase/src/main/java/org/tron/core/archive/reader/ArchiveDebugReader.java` | tx-level replay/debug |

验收：

- API 名称明确是 archive/debug，不是 `eth_getProof`。
- root/proof 标记 `ARCHIVE_SIDECAR`。
- proof 只证明 archive sidecar 数据一致性，不证明共识有效性。

## 7. Issue #6289 traceability

| 需求 | 最早可验收 slice | 完整 slice | 说明 |
| --- | --- | --- | --- |
| historical balance query | S9 | S9 | 依赖 S1-S8 |
| historical code query | S9 | S9 | 依赖 `CONTRACT/CODE` domain |
| historical storage query | S9 | S9 | 依赖 S5 logical storage key |
| historical `eth_call` | S13 | S13 | 依赖 S8/S9 reader 与 S12 VM injection |
| world state trie/root | S11 | S14 | S11 sidecar root；S14 proof/debug |
| stateRoot not consensus | S10 | S14 | 所有 root record 带 `ARCHIVE_SIDECAR` |
| dispersed DB collection | S3/S4 | 后续 widened coverage | P0 只 TVM_STATE_ONLY |
| performance impact control | S1 | all | default false + batch + no consensus root |
| storage expansion/multi-disk | S6 | future segment PR | P0 不拆物理库，但保留 table prefix/segment abstraction |

## 8. 跨 slice 不变量

这些不变量一旦破坏，后续实现会返工。

1. Archive 默认关闭，关闭时 hook 不产生业务行为。
2. Archive sidecar 不能在 canonical revoking session 成功前持久化。
3. `txNum` 全局单调，block range 和 system phase 可解析。
4. `StatePoint.BLOCK_END` 映射到 exclusive `asOfTxNum`，不引入 `BLOCK_AFTER`。
5. `domainId` 是 `u16`，所有 key/value/hash preimage 一致。
6. Store hook 只通过 `ArchiveDomainRegistry` 判断 domain。
7. `CONTRACT_STORAGE` 使用 logical key `address21 || slot32 || storageKeyVersion_u8`，不使用 physical storage-row key。
8. `BlockWriteSet` 是 TemporalStore 和 CommitmentBuilder 的唯一输入。
9. temporal rows 和 root rows 写入同一个 physical `archive` DB batch。
10. PR7 root 不写 `accountStateRoot`，不参与共识。
11. historical JSON-RPC 不 fallback latest。
12. startup verifier 必须能发现 archive ahead/behind/corrupt。

## 9. 测试矩阵

最小测试按 slice 扩展，不建议等全部实现完再跑总 lint。

| Slice | 建议测试 |
| --- | --- |
| S1 | `./gradlew :common:test --tests org.tron.core.config.args.StorageTest` 或 archive config focused test |
| S1 | archive config/no-op service unit tests |
| S2 | TxNumIndex unit tests + Manager lifecycle focused tests |
| S3 | DomainRegistry/codec tests |
| S4 | Store hook/collector tests |
| S5 | Storage semantic hook tests，优先复用 `StorageTest`、`HistoryBlockHashVmTest` 风格 |
| S6 | ArchiveRawStore key/value/batch tests |
| S7 | TemporalStore getAsOf/unwind/startup verifier tests |
| S8 | ArchiveStateReader unit tests |
| S9 | JSON-RPC getter historical tests |
| S10 | SparseMerkleTree/root codec tests |
| S11 | CommitmentBuilder integration/rebuild tests |
| S12 | ArchiveRepositoryAdapter/VMActuator injection tests |
| S13 | historical `eth_call` integration tests |
| S14 | proof/debug API verifier tests |

每个涉及 Java 的 slice 最终 gate：

```text
./gradlew lint
./gradlew checkstyleMain checkstyleTest -x generateGitProperties
```

如果 slice 只改 `chainbase`，可以先跑 focused tests，再跑最终 gate。若触碰 `framework` RPC/Manager 或 `actuator` VM，至少补对应模块 test。

## 10. 已知未决项

这些不阻塞 P0，但实现前必须在对应 slice 的 PR 描述或测试中写清。

| 未决项 | 当前处理 | 最早需要定案 |
| --- | --- | --- |
| P0 是否覆盖全部 25 DB | 不覆盖，只 `TVM_STATE_ONLY` | S3 |
| `DYNAMIC_PROPERTIES` 哪些 key 进入 historical `eth_call` | allowlist，不全量 | S12 |
| 首次开启 archive 如何处理旧链 | 从启用点开始，旧历史不可查；全量回填另做 bootstrap | S7 |
| 现有 archive DB 首次开启 commitment | 必须 rebuild/bootstrap，不能从 empty root 续写 | S11 |
| tx-level root 是否持久化 | P0 block-end root；tx root on-demand 或配置关闭 | S11/S14 |
| 多盘/segment | P0 single DB；保留 segment abstraction | S6 后续 |
| root 是否参与共识 | 明确不参与 | S10 |
| `eth_getTransactionCount/eth_getProof` | 不进 P0 | S14 或后续治理 |

## 11. 推荐下一步编码入口

如果下一步开始落 java-tron 代码，不建议直接实现 Store hook 或 root。推荐先做 S1 + S2：

```text
S1 archive config/no-op service
S2 Manager lifecycle + TxNumIndex
```

原因：

1. 它们把交易级时间坐标固定下来。
2. archive 默认关闭时可做强回归。
3. 后续 registry、collector、temporal、reader、root 都依赖同一个 lifecycle。
4. 改动面相对最小，便于符合 java-tron 的小 PR 规则。

S1/S2 编码前还要做一次本地基线：

```text
cd /Users/boson/IdeaProjects/java-tron
git status --short
./gradlew :common:test --tests org.tron.core.config.args.StorageTest
```

如果基线失败，先记录失败原因，不要在 archive PR 中混入无关修复。
