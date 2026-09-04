# java-tron state-root 分支借鉴分析

日期：2026-06-05

分析对象：

- `https://github.com/halibobo1205/java-tron/tree/feat/481_state_root`
- `https://github.com/halibobo1205/java-tron/tree/feat/state-trie-4.8.1`

临时 checkout：

```text
/private/tmp/java-tron-481-state-root
/private/tmp/java-tron-state-trie-481
```

本文只用于 archive sidecar 设计借鉴，不表示要合并这两个分支。当前 P0 仍以 issue #6289 的非共识 archive sidecar 为边界：

```text
rootScope = ARCHIVE_SIDECAR
consensusParticipation = NONE
no BlockHeader.raw.accountStateRoot write
no JSON-RPC BlockResult.stateRoot replacement
transaction-level history required
```

用户确认口径：`feat/state-trie-4.8.1` 是 **MPT 实现**，且是 **区块级别** 的状态树；这里只作为实现参考，不把它当成交易级 archive sidecar 方案。

## 1. 分支摘要

| 分支 | HEAD | merge-base | diff 面 | 结论 |
| --- | --- | --- | --- | --- |
| `feat/481_state_root` | `6c086fbdd590a0d227b0f11f7d7bef32acf81493` | `35b1c4f8f9b7480186ba2cb6c34dfdd474d8174e` | 39 files, +1194/-218 | 可借鉴 checkpoint root 校验、独立 root store、corrupted batch 保存；不能借鉴 header 临时 root、ad-hoc state DB 过滤和 block-level only root |
| `feat/state-trie-4.8.1` | `6b6a744801e07aae272f903a60a1e85882e3226c` | `25b35f514833c0a7024e42beeb2c03c539580138` | 518 files, +49198/-4534 | 区块级 MPT 实现参考；可借鉴 `StateType` domain、Store hook、read-only Store facade、historical VM Repository、baseline snapshot + trie delta；不能借鉴 header `archive_root/accountStateRoot`、vendored Besu/Tuweni 全量引入和非 tx-level root |

## 2. feat/481_state_root

### 2.1 做了什么

该分支不是完整 historical state trie。它做的是 block/checkpoint 批量写入时的 root hash 校验和 root 持久化：

```text
incoming/generated block carries BlockHeader.state_root
  -> TronNetDelegate caches expected root in ThreadLocal GlobalContext
  -> CheckPointV2Store/CheckTmpStore updateByBatch computes actual root from rows
  -> mismatch stores corrupted checkpoint batch and throws
  -> success stores root in StateRootStore(blockNum -> root)
  -> RPC block query adds state_root back to returned block
```

源码证据：

| 文件 | 事实 | 借鉴点 |
| --- | --- | --- |
| `RootHashService.java:42-50` | 固定 `stateDbs` 白名单 | archive 的 rooted domain 必须集中声明，不能散落在 root builder |
| `RootHashService.java:71-91` | 从 checkpoint rows 计算 sorted leaf Merkle root，并和 `GlobalContext` 里的 expected root 比对 | L7 rebuild/root verifier 可在 batch 边界输出 expected/actual mismatch |
| `RootHashService.java:97-148` | 对 witness、account asset、delegation/account-vote、properties 做规范化和过滤 | L3/L7 必须把 value normalizer 变成可审计 registry policy，不能 ad-hoc 写在 root service |
| `StateRootStore.java:14-37` | 独立 `state-root` DB，以 block height 存 root | L7 `ArchiveRootRecord` 用独立 table/prefix 存 sidecar root 是合理方向 |
| `CorruptedCheckpointStore.java:14-44` | mismatch 时保留 corrupted checkpoint batch | L7 `ArchiveCommitmentRebuildVerifier` 可增加 mismatch evidence snapshot |
| `TronNetDelegate.java:313-324` | 接收 block 后读取 `stateRoot`，再 clear 掉继续处理 | 只可作为“外部 root 见证”思路，P0 不把 root 放进 block header |
| `RpcApiService.java:352-359` | 查询 block 时把 `StateRootStore` 的 root 补回 `BlockHeader.state_root` | P0 不这么做；debug API 应显式返回 archive root |
| `Tron.proto` | `BlockHeader` 增加 `bytes state_root = 3` | P0 禁止修改共识/网络 block header schema |

### 2.2 可借鉴

1. **Root evidence store**

`CorruptedCheckpointStore` 的思想可以借鉴为 `ArchiveRootMismatchEvidenceStore`：当 rebuild verifier 或 startup verifier 发现 `expectedRoot != actualRoot`，保存 blockNum、txNum range、domain、expected、actual、sample changeset keys，便于离线排障。

P0 不需要保存完整 batch。保存完整 batch 可能暴涨磁盘，也可能泄漏大量历史值。建议只保存有限 sample 和 root computation metadata。

2. **独立 root table**

`StateRootStore(blockNum -> root)` 和我们 L7 `ArchiveRootRecord(blockNum, blockHash, finalizeTxNum, globalRoot, domainRoots, coverage)` 方向一致。可以借鉴“root 不塞进业务 Store，而是独立持久化”的切分。

3. **root verifier 要靠规范化输入**

`RootHashService` 里对 witness missed count、account asset map、properties fork flags 做了过滤。这个分支暴露了一个关键事实：java-tron 里很多 Store 值包含非业务状态或派生状态。P0 必须把这些规则提升到 `ArchiveDomainRegistry` 的 codec/normalizer，而不是在 `CommitmentBuilder` 里散写 if/else。

### 2.3 不可照搬

| 做法 | 为什么不照搬 |
| --- | --- |
| `BlockHeader.state_root` 临时传递后 clear | 改网络/proto 语义，且 root 来源不是 archive sidecar |
| RPC block query 自动把 root 补回 header | 会让调用方误以为这是共识/header root |
| `MerkleRoot.root(sorted(hash(key||value)))` | 只能做 batch fingerprint，不支持 proof、不支持 domain root、不支持 tx-level root |
| ThreadLocal `GlobalContext` 传 expected root | block apply/replay/fork/recovery 并发语义脆弱；archive 应显式用 `ArchiveExecutionContext` |
| checkpoint batch 级 root | 没有 transaction-level as-of root，不能满足交易级状态树 |

## 3. feat/state-trie-4.8.1：区块级 MPT 参考

### 3.1 做了什么

该分支是区块级 world-state MPT 原型，不是交易级 archive sidecar：

```text
StateType domain byte
  -> TronStoreWithRevoking put/delete hook
  -> WorldStateCallBack collects trieEntryList
  -> TrieImpl2 wraps Besu StoredMerklePatriciaTrie
  -> block end commit/flush and set BlockHeader.archive_root
  -> WorldStateQueryInstance reads root with genesis baseline fallback
  -> Account/Storage/Dynamic StateStore facades
  -> RepositoryStateImpl drives historical eth_call
  -> JSON-RPC historical getters use wallet.get*(blockNumber)
```

它在 block finish 后得到一个 block-level root，不能直接回答 `rootAtTxNum(txNum)`，也不能替代 L5 temporal changeset 或 L7 transaction-level root 计算。

源码证据：

| 文件 | 事实 | 借鉴点 |
| --- | --- | --- |
| `StateType.java:7-74` | 用 1 byte domain id 映射 java-tron DB name，并以 `domainByte || key` 编码 | L3 `ArchiveDomainRegistry` 应固定 numeric domain id、dbName、canonical key codec |
| `TronStoreWithRevoking.java:77-115` | 构造时用 `getDbName()` 解析 `StateType`；`put/delete` 调 `WorldStateCallBack.callBack(type,key,value,op)` | L4 generic hook 落点正确，但要输出 `BlockWriteSet`，不能直接写 trie |
| `WorldStateCallBack.java:47-88` | 处理 put/delete/tombstone，并拆出 account asset 子状态 | L4/L3 应显式支持 derived semantic writes 和 delete marker |
| `WorldStateCallBack.java:116-139` | 每笔交易前后调用 `clear()` 把 pending entries 写入 trie | L2/L4 可以借鉴 tx 边界 hook，但 P0 要记录 txNum changeset，不只 flush root |
| `WorldStateCallBack.java:141-170` | block preExecute 从 parent root 建 trie，block finish commit/flush 后写 `archiveRoot` 到 block | L7 可借鉴 parent-root incrementality，不能写 header |
| `TrieImpl2.java:36-150` | Besu `StoredMerklePatriciaTrie` + `MerkleStorage`，支持 get/put/delete/commit/flush | P0 可以研究 proof/visitor，但当前规划仍用 archive SMT sidecar |
| `TrieImpl2.java:151-176` | 支持 range query `entriesFrom(start,end)` | L6/L9 对 prefix/range proof 的后续扩展可参考 |
| `WorldStateGenesis.java:75-110` | archive DB 已存在但开关关闭/不连续时直接报错 | L1/L5 startup verifier 需要类似 fail-fast，不 silent fallback |
| `WorldStateGenesis.java:174-250` | 首次启用时复制现有 state DB，记录 genesis height | L5 可借鉴 baseline checkpoint 概念，但不能取代 temporal history |
| `WorldStateQueryInstance.java:55-66` | trie miss 回退到 `WorldStateGenesis` baseline，`UInt256.ZERO` 表示删除 | L6 可借鉴 baseline+delta query 和 tombstone 语义 |
| `WorldStateQueryInstance.java:68-214` | 为 account/code/contract/storage/dynamic/delegation 等提供 typed getter | L6 `ArchiveStateReader` 可保持 typed reader，避免 JSON-RPC 直接拼 bytes |
| `AccountStateStore.java` / `StorageRowStateStore.java` / `DynamicPropertiesStateStore.java` | read-only Store facade，写操作抛 `UnsupportedOperationException` | L8 archive-backed Repository 应 fail-fast 禁止写 canonical/archive DB |
| `RepositoryStateImpl.java:106-124` | `createRoot(root)` 创建 historical root repository，child repository 只叠加 cache | L8 `ArchiveRepositoryAdapter` 可以借鉴 root+child overlay 结构 |
| `RepositoryStateImpl.java:912-1039` | 顶层 historical repository commit 无 parent 时抛 unsupported | L8 constant call 顶层 commit 必须 fail-fast |
| `VMActuator.java:130-141` | constant call 且 historical block 时用 `RepositoryStateImpl.createRoot(block.archiveRoot)` | L8 VM 注入点可借鉴，但 root 来源改为 archive sidecar `ArchiveStatePoint` |
| `TronJsonRpcImpl.java:420/579/614` | historical balance/storage/code getter 走 wallet historical path | L6 JSON-RPC 分流方向一致 |
| `Wallet.java:373-455` | historical account/resources 用 StateStore facade 重建 resource processor | L6/L8 对资源类 dynamic properties 需要 typed historical facade |
| `Wallet.java:4789-4828` | historical code/storage 使用 root query，并复用 `Storage` slot 解析 | L6 storage reader 可复用 slot 解析思路，但 canonical key 必须是 `(address, slot)` semantic key |
| `ChainBaseManager.java:389-401/448-455` | 按 root 缓存 `WorldStateQueryInstance` | L6/L8 可以有 bounded reader/session cache |
| `KeyValueMerkleCacheStorage.java:26-98` | 按 `StateType` 分组 MPT node cache | L7 可借鉴 domain-aware cache/metrics |

### 3.2 可借鉴设计

1. **Domain id 必须比 dbName 更权威**

`StateType` 把 java-tron DB name 映射为稳定 byte id。L3 应采用类似结构，但字段更完整：

```text
domainId
domainName
javaTronDbName
rootPolicy
keyCodec
valueCodec
normalizer
coverage
```

`StateType.UNDEFINED` 对应我们 L3 的 `UNSUPPORTED/EXCLUDED`，不能让 unknown Store 默认进 root。

2. **Store hook 位置正确，但输出层级要改**

`TronStoreWithRevoking.put/delete` 是 generic Store 写入入口，state-trie 分支在这里直接回调 `WorldStateCallBack`。L4 应沿用这个插入点，但回调目标应是：

```text
ArchiveWriteCollector.recordRawWrite(domain, canonicalKey, before, after, txNum, phase)
```

不能直接更新 trie，因为 P0 还需要 temporal history、changeset、unwind 和 tx-level root。

3. **derived semantic writes 要成为 registry policy**

state-trie 分支把 account asset 从 account capsule 拆成 `AccountAsset` 子状态。这说明某些 java-tron value 需要拆分/规范化。L3/L4 应把这些规则定义成 `ArchiveDomainDescriptor` 的 semantic hook 或 value normalizer。

首批 P0 可以只覆盖 TVM state，但必须在文档里把 asset/delegation/vote 归类为后续 domain，避免被 generic `account` 值意外纳入 root。

4. **Baseline + delta 可降启动成本**

`WorldStateGenesis` 复制当前 state DB 作为 baseline，再用 trie delta 记录之后变更。这个思路适合变成 L5/L7 的可选 checkpoint：

```text
ArchiveBaselineSnapshot(height, blockHash, domain set, source checksum)
  + temporal changeset after baseline
  + root records after baseline
```

但它不能替代 transaction-level temporal history；baseline 之前的交易级查询应明确 unsupported，或需要从创世重放构建。

5. **typed historical reader 比 byte blob reader 更好维护**

`WorldStateQueryInstance` 返回 `AccountCapsule`、`ContractCapsule`、`CodeCapsule`、`StorageRowCapsule` 等 typed result。这里借鉴的是查询 facade，不是区块级 MPT root 本身。L6 应保留 typed API：

```text
readAccount(point, address) -> HistoricalAccount
readContract(point, address) -> HistoricalContract
readCode(point, address) -> HistoricalCode
readStorage(point, address, slot) -> HistoricalStorageSlot
```

JSON-RPC adapter 只负责渲染 hex/default，不负责解释 raw bytes。

6. **historical Repository 应是 read-only root + child overlay**

`RepositoryStateImpl` 的 root repository 读取 historical state，child repository 缓存 VM 写入，顶层 commit 无 parent 时抛异常。L8 可以借鉴这个 shape，要求：

```text
ArchiveRepositoryAdapter(root reader)
  -> ArchiveRepositoryChild overlay
  -> commit child only merges into parent overlay
  -> top-level commit throws or no-op with explicit constant-call guard
```

7. **domain-aware cache/metrics**

`KeyValueMerkleCacheStorage` 按 `StateType` 缓存 trie node。L7/L9 可以把 cache key 设计成：

```text
domainId + rootVersion + nodeHash/path
```

并按 domain 打 metrics，避免 storage/code/account 混在一个 cache 里无法诊断。

### 3.3 不可照搬

| 做法 | 为什么不照搬 |
| --- | --- |
| `BlockHeader.archive_root = 3` | 改 proto/header 语义；P0 sidecar root 不能混入 block |
| `BlockHeader.raw.accountStateRoot` 相关 proposal/committee 开关 | P0 不参与共识，不应引入 governance proposal |
| vendored `state-trie-jdk8` 全量 Besu/Tuweni | diff 巨大，维护、license、checkstyle、JDK 兼容和安全审计成本高；P0 已规划轻量 sidecar SMT |
| `WorldStateCallBack` singleton + mutable `execute` | block/replay/fork/constant call 并发边界不清晰；P0 应用 `ArchiveExecutionContext` 显式绑定 |
| 每笔 tx 前后只 flush trie，不持久 tx-level changeset | 不能满足交易级状态树和 `rootAtTxNum` |
| `UInt256.ZERO` 作为通用 delete marker | 和真实 zero 值冲突风险大；P0 tombstone 必须有专用 envelope |
| `StorageRow` physical key 作为 storage reader 主入口 | P0 `CONTRACT_STORAGE` 必须用 `(address, slot)` semantic key；physical row key 只能作为辅助 |
| startup 发现 archive DB 时要求删除或 reset header root | P0 startup verifier 只能 fail-fast/report，不应自动改 block store |
| JSON-RPC historical getter 直接由 `allowStateRoot` 开关控制 | P0 应用 `storage.archive.enable` + reader coverage + explicit unsupported error |

## 4. 对当前 L1-L9 规划的修改要求

| 规划 | 需要吸收的思路 | 明确禁止 |
| --- | --- | --- |
| L3 ArchiveDomainRegistry | 参考 `StateType`，把 dbName/domainId/keyCodec/valueNormalizer/rootPolicy 固定成表 | 不允许 unknown Store 自动进入 root |
| L4 WriteCollector | 参考 `TronStoreWithRevoking.put/delete` hook 和 derived account asset 处理 | 不直接写 trie；不使用 singleton mutable callback |
| L5 TemporalStore | 参考 `WorldStateGenesis` baseline snapshot，作为未来 checkpoint 优化 | 不用 baseline 替代 tx-level temporal history |
| L6 StateReader | 参考 `WorldStateQueryInstance` typed getters 和 tombstone fallback 语义 | 不从 header root 或 latest Store 推断历史状态 |
| L7 CommitmentBuilder | 参考 parent-root incremental update、domain-aware cache、mismatch evidence | 不写 `BlockHeader.archive_root/accountStateRoot`；不采用 batch fingerprint root |
| L8 historical eth_call | 参考 `RepositoryStateImpl` read-only root + child overlay、VMActuator repository injection | 不直接依赖 header `archive_root`；top-level commit 不能写任何 DB |
| L9 proof/debug | 可以研究 Besu `Proof`/visitor，但 P0 proof 仍以 L7 sidecar commitment node records 为准 | 不暴露 Ethereum `eth_getProof`，不把 branch root 当 header proof |

## 5. 推荐落文档口径

当前文档应新增一条统一约束：

```text
state-root 分支是实现参考，不是 P0 架构来源。feat/state-trie-4.8.1 是区块级 MPT 参考，不能替代交易级 archive sidecar。P0 只吸收 domain/hook/query/repository/cache/evidence 思路；所有 header root、proto root、governance root、block-result root、non-tx-level root 都禁止混入。
```

具体更新：

1. L4 增加 branch-derived hook 约束：`TronStoreWithRevoking` hook 参考 state-trie，但输出 `BlockWriteSet`。
2. L7 增加 root strategy 约束：可借鉴 parent-root incremental update 和 mismatch evidence，不借鉴 header root 或 flat MerkleRoot。
3. L8 增加 historical repository 参考：`RepositoryStateImpl` 的 read-only facade/child overlay 可借鉴，root source 必须换成 `ArchiveStatePoint`。
4. L9 增加 proof 边界：Besu MPT proof 可研究，不改变 P0 sidecar proof format。

## 6. 后续可选实验

这些不进入 P0：

- 以 `state-trie-jdk8` 的 Besu MPT 做一个 isolated benchmark，对比 L7 SMT 的 update/proof 成本。
- 设计 `ArchiveBaselineSnapshot`，评估从任意高度开始构建 archive sidecar 的启动成本。
- 为 `ArchiveDomainRegistry` 增加 asset/delegation/vote 的 derived domain 规格。
- 给 L7 verifier 增加 `ArchiveRootMismatchEvidenceStore`。
