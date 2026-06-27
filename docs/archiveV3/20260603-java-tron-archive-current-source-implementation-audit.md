# java-tron Archive 当前源码审计入口

日期：2026-06-03

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

当前源码基线：`4e80f8ffa9a2`，`git status --short` 为空。

## 1. 先决结论

这轮重新对齐后，前面按 `a79693e450` 和 `a771d440d9f7` 写下的若干结论已经失效。当前本地源码存在 `common/src/main/resources/reference.conf`、`common/src/main/java/org/tron/core/config/args/StorageConfig.java`，并且 `Args` 通过 `StorageConfig.fromConfig(config)` 和 `applyStorageConfig(StorageConfig sc)` 桥接到 `CommonParameter.storage`。

当前工作区是干净的，并且精确冲突标记扫描没有命中：

```bash
git -C /Users/boson/IdeaProjects/java-tron rev-parse --short=12 HEAD
# 4e80f8ffa9a2

git -C /Users/boson/IdeaProjects/java-tron status --short
# no output

rg -n '^(<<<<<<< .+|=======$|>>>>>>> .+)' /Users/boson/IdeaProjects/java-tron
# no output
```

因此当前阶段可以直接以 `4e80f8ffa9a2` 为源码对照基线继续细化实现。早前 `a771d440d9f7` 快照中的冲突标记结论不再适用于当前源码。

## 2. 当前权威细化文档

本轮新增的逐模块源码对照文档是：

- [java-tron Archive：4e80 六模块源码对照细化](./20260603-java-tron-archive-4e80-six-modules-source-detail.md)
- [java-tron Archive：4e80 统一实现路线](./20260603-java-tron-archive-4e80-implementation-roadmap.md)
- [java-tron Archive：4e80 完整实现总装计划](./20260604-java-tron-archive-4e80-implementation-assembly-plan.md)
- [java-tron Archive：4e80 逐文件实现落点矩阵](./20260604-java-tron-archive-4e80-file-implementation-map.md)
- [java-tron Archive：4e80 分阶段落地执行看板](./20260604-java-tron-archive-4e80-landing-readiness-board.md)
- [java-tron Archive：4e80 模块测试与验收计划](./20260604-java-tron-archive-4e80-test-verification-plan.md)
- [java-tron Archive L1：config/no-op/dbName 代码级执行包](./20260604-java-tron-archive-l1-config-noop-dbname-4e80-code-plan.md)
- [java-tron Archive L2：Manager lifecycle + txNum 代码级执行包](./20260605-java-tron-archive-l2-manager-txnum-4e80-code-plan.md)
- [java-tron Archive L3：ArchiveDomainRegistry 代码级执行包](./20260605-java-tron-archive-l3-domain-registry-4e80-code-plan.md)
- [java-tron Archive L4：WriteCollector + Storage Semantic Hook 代码级执行包](./20260605-java-tron-archive-l4-write-collector-4e80-code-plan.md)
- [java-tron Archive L5：ArchiveTemporalStore 代码级执行包](./20260605-java-tron-archive-l5-temporal-store-4e80-code-plan.md)
- [java-tron Archive L6：ArchiveStateReader + historical getters 代码级执行包](./20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md)
- [java-tron Archive L7：CommitmentBuilder 代码级执行包](./20260605-java-tron-archive-l7-commitment-builder-4e80-code-plan.md)
- [java-tron Archive L8：historical eth_call 代码级执行包](./20260605-java-tron-archive-l8-historical-eth-call-4e80-code-plan.md)
- [java-tron Archive L9：proof/debug API 代码级执行包](./20260605-java-tron-archive-l9-proof-debug-api-4e80-code-plan.md)
- [java-tron state-root 分支借鉴分析](./20260605-java-tron-state-root-branches-reference-analysis.md)
- [java-tron Archive S1/S2：4e80 编码执行包](./20260603-java-tron-archive-s1-s2-4e80-coding-packet.md)
- [java-tron Archive S3：ArchiveDomainRegistry 4e80 编码执行包](./20260603-java-tron-archive-s3-domain-registry-4e80-coding-packet.md)
- [java-tron Archive S4/S5：WriteCollector 与 Storage Semantic Hook 4e80 编码执行包](./20260603-java-tron-archive-s4-s5-write-collector-storage-4e80-coding-packet.md)
- [java-tron Archive S6/S7：ArchiveTemporalStore 4e80 编码执行包](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md)
- [java-tron Archive S8/S9：ArchiveStateReader 与 JSON-RPC Historical Getters 4e80 编码执行包](./20260603-java-tron-archive-s8-s9-state-reader-rpc-4e80-coding-packet.md)
- [java-tron Archive S10/S11：CommitmentBuilder 4e80 编码执行包](./20260603-java-tron-archive-s10-s11-commitment-builder-4e80-coding-packet.md)
- [java-tron Archive S12/S13：historical eth_call 4e80 编码执行包](./20260604-java-tron-archive-s12-s13-historical-eth-call-4e80-coding-packet.md)
- [java-tron Archive S14：proof/debug API 4e80 编码执行包](./20260604-java-tron-archive-s14-proof-debug-api-4e80-coding-packet.md)
- [模块 01 ArchiveTxNumIndex：4e80 java-tron 源码对照细化](./20260603-java-tron-module-01-txnum-index-4e80-source-deep-dive.md)
- [模块 02 ArchiveDomainRegistry：4e80 java-tron 源码对照细化](./20260603-java-tron-module-02-domain-registry-4e80-source-deep-dive.md)
- [模块 03 ArchiveWriteCollector：4e80 java-tron 源码对照细化](./20260603-java-tron-module-03-write-collector-4e80-source-deep-dive.md)
- [模块 04 ArchiveTemporalStore：4e80 java-tron 源码对照细化](./20260603-java-tron-module-04-temporal-store-4e80-source-deep-dive.md)
- [模块 05 ArchiveStateReader：4e80 java-tron 源码对照细化](./20260603-java-tron-module-05-state-reader-4e80-source-deep-dive.md)
- [模块 06 CommitmentBuilder：4e80 java-tron 源码对照细化](./20260603-java-tron-module-06-commitment-builder-4e80-source-deep-dive.md)

这份文档覆盖六个模块：

| 模块 | 当前落点 |
| --- | --- |
| Module 01 `ArchiveTxNumIndex` | `Manager` block lifecycle + logical tx phase |
| Module 02 `ArchiveDomainRegistry` | `ChainBaseManager` Store inventory + domain policy |
| Module 03 `ArchiveWriteCollector` | `TronStoreWithRevoking` generic hook + store-specific hook + storage semantic hook；已有 4e80 细化文档 |
| Module 04 `ArchiveTemporalStore` | single physical archive DB + latest/history/changeset/txnum/progress；已有 4e80 细化文档 |
| Module 05 `ArchiveStateReader` | historical `eth_getBalance/getCode/getStorageAt`，`eth_call` 后置；已有 4e80 细化文档 |
| Module 06 `CommitmentBuilder` | sidecar root，不写 `BlockHeader.raw.accountStateRoot`；已有 4e80 细化文档 |

## 3. 当前源码事实总表

| 领域 | 当前源码事实 | 实现含义 |
| --- | --- | --- |
| 配置默认 | `common/src/main/resources/reference.conf:118` 有 `balance.history.lookup = false`，`framework/src/main/resources/config.conf:38` 同步给默认配置 | archive 默认值应先进入 `reference.conf`，保持默认关闭 |
| 配置 bean | `StorageConfig.java:21` 定义 bean，`fromConfig` 在 line 173 | archive config 应新增为 `StorageConfig.ArchiveConfig` 或等价嵌套 bean |
| 配置桥接 | `Args.java:212-243` 现有 `applyStorageConfig(StorageConfig sc)`，`Args.java:715-716` 从 config 构造并桥接 | 在该桥接方法内写入 `CommonParameter.storage.archive` |
| canonical commit | `Manager.java:1379-1381` 在 revoking session 内 `applyBlock(newBlock, txs)` 后 `tmpSession.commit()` | archive batch 只能在 canonical commit 成功后 flush |
| canonical unwind | `Manager.java:1034-1041` 的 `eraseBlock()` 先拿 old head，再 `khaosDb.pop()` 与 `revokingStore.fastPop()` | archive unwind 放在 `fastPop()` 成功后 |
| fork replay | `Manager.java:1142/1149`、`1185/1187` 有 replay session commit | fork/recovery 分支不能漏 archive lifecycle |
| tx phase | `Manager.java:1838` 是 `processBlock`，`1873` 遍历 `block.getTransactions()`，`1886` 执行单笔交易 | txNum 不用 filtered `txs` 下标，必须在 canonical block tx loop 显式维护 |
| block finalize | `Manager.java:1906` reward，`1922-1925` cache/recent/dynamic properties | system/finalize 写入必须分配 txNum |
| generic store hook | `TronStoreWithRevoking.java:78` 当前 `getDbName()` 返回 `null`；`89-99` 是通用 put/delete | S1 先修 dbName，S4 再挂 raw hook |
| store-specific hook | `ContractStore.java:31-39`、`AbiStore.java:27-32`、`ContractStateStore.java:27-32` 直接写 `revokingDB` | Module 02 必须把这些标成 `STORE_SPECIFIC` |
| storage semantic hook | `actuator/.../Storage.java:46-53` 生成不可逆 physical row key，`96-105` commit dirty rows | `CONTRACT_STORAGE` 必须采 semantic `(address, slot)`，不能用 `storage-row` raw key |
| temporal batch | `LevelDbDataSourceImpl.java:404-418` 与 `RocksDbDataSourceImpl.java:301-309` 支持 null-delete batch | `ArchiveBatch.delete` 可统一落为 null tombstone |
| JSON-RPC latest guard | `TronJsonRpcImpl.java:387-397` `requireLatestBlockTag` 拒绝非 latest；`457/611/635` 三个 state getter 调用它 | Module 05 先替换这三个入口的 non-latest 分支 |
| existing account root | `AccountStateCallBack.java:52-71` 用 `TrieImpl`，`94-103` 可写 block header root；`Tron.proto:513` 定义 header 字段 | Module 06 做 archive sidecar root，P0 不写共识 header |

## 4. 下一步顺序

1. 以 `reference.conf` + `StorageConfig` + `Args.applyStorageConfig` 为配置链路，修正旧文档里“没有 StorageConfig/reference.conf”的描述。
2. 按新细化文档从 Module 01/02 开始落 S1/S2/S3：config/no-op service、dbName、Manager lifecycle、domain registry。
3. temporal store 可稳定 `getAsOf` 后接入 JSON-RPC 和 sidecar root。
4. 按 [S1/S2 4e80 编码执行包](./20260603-java-tron-archive-s1-s2-4e80-coding-packet.md) 进入第一批实现：默认关闭配置、no-op service、dbName 修复和 Manager lifecycle/txNum。
5. S1/S2 后接 [S3 ArchiveDomainRegistry 4e80 编码执行包](./20260603-java-tron-archive-s3-domain-registry-4e80-coding-packet.md)，先把 Store/domain/codec/policy 固定住。
6. S3 后接 [S4/S5 WriteCollector 与 Storage Semantic Hook 4e80 编码执行包](./20260603-java-tron-archive-s4-s5-write-collector-storage-4e80-coding-packet.md)，把 raw Store hook、store-specific hook、storage semantic hook 和 retry lifecycle 收束到当前 4e80 源码。
7. S4/S5 后接 [S6/S7 ArchiveTemporalStore 4e80 编码执行包](./20260603-java-tron-archive-s6-s7-temporal-store-4e80-coding-packet.md)，固定 single archive DB、temporal key/value schema、apply/getAsOf/unwind/startup。
8. S6/S7 后接 [S8/S9 ArchiveStateReader 与 JSON-RPC Historical Getters 4e80 编码执行包](./20260603-java-tron-archive-s8-s9-state-reader-rpc-4e80-coding-packet.md)，把 reader core 和三个 read-only JSON-RPC historical getter 接起来。
9. S8/S9 后接 [S10/S11 CommitmentBuilder 4e80 编码执行包](./20260603-java-tron-archive-s10-s11-commitment-builder-4e80-coding-packet.md)，把 archive sidecar root、SMT codecs、root records、commitment branch state 和 rebuild verifier 接入同一 archive batch。
10. S10/S11 后接 [S12/S13 historical eth_call 4e80 编码执行包](./20260604-java-tron-archive-s12-s13-historical-eth-call-4e80-coding-packet.md) 和 [L8 historical eth_call 代码级执行包](./20260605-java-tron-archive-l8-historical-eth-call-4e80-code-plan.md)，把 archive-backed Repository、historical VM dynamic properties、VMConfig scope 和 JSON-RPC `eth_call` historical 分支接起来。
11. S12/S13 后接 [S14 proof/debug API 4e80 编码执行包](./20260604-java-tron-archive-s14-proof-debug-api-4e80-coding-packet.md) 和 [L9 proof/debug API 代码级执行包](./20260605-java-tron-archive-l9-proof-debug-api-4e80-code-plan.md)，暴露默认关闭、FullNode-only 的 archive-native root/proof/verify debug API，明确不实现 `eth_getProof` 和 `debug_traceCall`。
