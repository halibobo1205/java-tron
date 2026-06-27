# java-tron Archive 模块 06：CommitmentBuilder 细化设计

日期：2026-05-21

关联总文档：[java-tron 交易级状态树支持：Erigon V2/V3 模型调研](./20260520-java-tron-archive-state-erigon-v2-v3-research.md)

源码对照深挖：[模块 06 CommitmentBuilder：Erigon 源码对照深挖](./20260601-java-tron-module-06-commitment-builder-erigon-source-deep-dive.md)

java-tron 源码对照：[模块 06 CommitmentBuilder：java-tron 源码对照](./20260601-java-tron-module-06-commitment-builder-java-tron-source-deep-dive.md)

实现蓝图：[java-tron Archive 状态树实现蓝图](./20260602-java-tron-archive-implementation-blueprint.md)

代码级实现规格：[java-tron Archive PR7 CommitmentBuilder 代码级实现规格](./20260602-java-tron-archive-pr7-commitment-builder-implementation-spec.md)

Proof/Debug API 代码级实现规格：[java-tron Archive PR9 Proof/Debug API 代码级实现规格](./20260602-java-tron-archive-pr9-proof-debug-api-implementation-spec.md)

逐文件 Patch 清单：[java-tron Archive 模块 06：CommitmentBuilder 逐文件 Patch 清单](./20260602-java-tron-archive-module-06-commitment-builder-patch-checklist.md)

前置模块：

- [模块 01：ArchiveTxNumIndex](./20260521-java-tron-archive-module-01-txnum-index.md)
- [模块 02：ArchiveDomainRegistry](./20260521-java-tron-archive-module-02-domain-registry.md)
- [模块 03：ArchiveWriteCollector](./20260521-java-tron-archive-module-03-write-collector.md)
- [模块 04：ArchiveTemporalStore](./20260521-java-tron-archive-module-04-temporal-store.md)
- [模块 05：ArchiveStateReader](./20260521-java-tron-archive-module-05-state-reader.md)

## 1. 模块定位

`CommitmentBuilder` 是 archive 状态系统的 sidecar root 和 proof 构建模块。它消费 `ArchiveWriteCollector` 输出的 `BlockWriteSet`，基于 `ArchiveDomainRegistry` 的 root policy，维护每个 root domain 的 commitment tree，并在指定 `StatePoint` 上生成 `domainRoot` 和 `globalRoot`。

这个模块的关键边界：

```text
CommitmentBuilder 生成的是 archive sidecar root，不是第一阶段的共识区块头 root。
```

它应该先服务三个目标：

1. 让 archive 历史状态有可重复计算的 root。
2. 为 block-end root、tx-level root、proof 提供统一机制。
3. 为未来 TIP/Proposal 将 root 纳入共识做工程验证。

不要在第一阶段把 `CommitmentBuilder` 和 java-tron 共识校验强绑定。先保证 sidecar 可重放、可校验、可修复。

## 2. 职责和非职责

职责：

- 根据 `RootPolicy` 过滤需要进入 root 的 domain writes。
- 维护每个 root domain 的增量 commitment tree。
- 生成 domain root。
- 按 domainId 聚合生成 global root。
- 在 `BLOCK_END`、可选 `TX_AFTER`、step/checkpoint 等 state point 上持久化 root。
- 提供 proof 生成所需的 domain proof 和 global proof。
- 支持 root rebuild、checkpoint、hot unwind、integrity check。
- 标记 root 覆盖范围：partial root、complete archive root、candidate consensus root。

非职责：

- 不决定哪些 Store/domain 进入 root，这属于 `ArchiveDomainRegistry`。
- 不捕获交易写集，这属于 `ArchiveWriteCollector`。
- 不保存 domain latest/history，这属于 `ArchiveTemporalStore`。
- 不解析 RPC blockTag/txTag，这属于 `ArchiveStateReader`。
- 不在第一阶段修改 block header 或共识规则。

## 3. 设计目标

1. 可重放。
   同一条 canonical chain、同一 registry checksum、同一 root algorithm，必须得到相同 root。

2. 增量更新。
   root 计算消费 write-set，不靠每个 block 全量扫描状态。

3. 分域聚合。
   java-tron 状态天然分散，使用 `domainRoot -> globalRoot`，避免单棵大树难以治理和迁移。

4. 可分阶段。
   先 block-end root，再按需 tx-level root/proof，最后才考虑共识 root。

5. 可证明。
   proof 必须包含 domain 内证明和 global domain 证明，不能只返回裸 root。

6. 可恢复。
   节点重启、block apply 失败、hot unwind、segment freeze 都要有明确恢复路径。

## 4. Root 语义

### 4.1 root 类型

| root | 含义 |
|---|---|
| `domainRoot(domainId, statePoint)` | 某个 domain 在 statePoint 的 commitment root |
| `globalRoot(statePoint)` | 所有 `IN_GLOBAL_ROOT` domainRoot 的聚合 root |
| `partialRoot(statePoint)` | 只覆盖部分 domain 的 root，通常用于 PoC/灰度 |
| `candidateConsensusRoot(statePoint)` | 格式和覆盖范围接近未来共识 root，但仍是 sidecar |

PoC 阶段如果只覆盖 `ACCOUNT`、`CONTRACT_CODE`、`CONTRACT_STORAGE`，必须标记为 `partialRoot`。不能把它称为完整 TRON state root。

### 4.2 statePoint

root 应以 `StatePoint` 为 key，而不是裸 `txNum`：

| root state point | 用途 |
|---|---|
| `BLOCK_END(blockNum)` | block 结束后的 archive root，第一阶段主目标 |
| `TX_AFTER(txId)` | 某笔交易结束后的 root，交易级证明目标 |
| `SYSTEM_AFTER(blockNum, phase)` | 系统/维护阶段后的 root，debug 或精确证明 |
| `CHECKPOINT(step)` | 内部增量计算和恢复 |

内部可以存 `asOfTxNum`，但外部 API 和 root 表 key 必须保留 statePoint 语义，避免 before/after 混淆。

### 4.3 与共识区块头的关系

第一阶段：

- root 不进入 block header。
- fullnode/SR 不需要强制计算。
- archive node 可以独立启用。
- root mismatch 不影响共识，只影响 archive sidecar 健康状态。

未来如果纳入共识，需要另行定义：

- 激活高度。
- root algorithm version。
- domain 覆盖范围。
- genesis/base root。
- 节点性能预算。
- root mismatch 的共识处理。

## 5. Root 结构

### 5.1 分域结构

建议使用两层结构：

```text
domainRoot[domainId] = Merkle(domainKey -> domainValue)
globalRoot           = Merkle(domainId -> domainRoot)
```

好处：

- 每个 domain 可以独立演进 codec、segment、proof。
- PoC 可以只启用少数 domain 并明确 partial。
- proof 拆成 domain proof + global proof。
- 多盘和 cold segment 可以按 domain 管理。

### 5.2 domain leaf

domain leaf 输入建议：

```text
domainLeafKey   = HKey(domainKey)
domainLeafValue = HValue(domainValue)
domainLeafHash  = H("tron.archive.domain.leaf.v1" || domainLeafKey || domainLeafValue)
```

说明：

- `domainKey` 和 `domainValue` 是 Registry 的 canonical bytes。
- `domainValue` 应包含或可由 manifest 确认 codec version。
- 删除 key 表示 leaf 不存在，不是 leaf value 为空。
- 如果业务 value 为空 bytes，仍然是存在 leaf，不能和删除混淆。

### 5.3 global leaf

global leaf 输入建议：

```text
globalLeafHash = H("tron.archive.global.leaf.v1" || domainId_u16 || domainRoot)
```

global tree 只包含 `RootPolicy == IN_GLOBAL_ROOT` 的 domain。`DOMAIN_ROOT_ONLY` 的 domainRoot 可以单独持久化，但不进入 globalRoot。

### 5.4 空 domain root

每个 root algorithm 必须定义空 domain root：

```text
emptyDomainRoot = H("tron.archive.domain.empty.v1" || domainId_u16)
```

未启用 domain 和已启用但为空的 domain 不能混淆：

- 未启用 domain：不参与 globalRoot。
- 已启用但为空：参与 globalRoot，domainRoot 为 emptyDomainRoot。

### 5.5 HashSpec

不要在实现中散落 hash 函数。建议定义：

```text
RootAlgorithmDescriptor:
  algorithm_id
  hash_function
  tree_type
  domain_leaf_prefix
  global_leaf_prefix
  branch_prefix
  empty_root_rule
  activation_block
```

PoC 可选择最容易实现和验证的 hash/tree 组合，但必须写入 descriptor 和 root metadata。未来如果要接近 Ethereum proof，可另起 algorithm version，不要静默替换。

## 6. Tree 类型选择

### 6.1 PoC 推荐：排序二叉 Merkle Tree / Sparse Merkle Tree

PoC 阶段建议优先选择工程上可控的结构：

- key 先 hash 成固定长度。
- leaf 按 hashed key 排序。
- 增量更新维护路径节点。
- proof 简单，验证器容易实现。

可选方案：

| tree_type | 优点 | 问题 |
|---|---|---|
| sorted binary Merkle | 实现简单，便于导出和验证 | 增量更新和 range proof 需要额外设计 |
| sparse Merkle tree | key path 固定，proof 简洁 | 深度固定，节点数量和压缩策略要设计 |
| MPT-like trie | 类 Ethereum，proof 语义熟悉 | 实现复杂，和 TRON domain key/value 未必天然匹配 |

建议：

- PoC block-end root 使用 sorted/sparse Merkle。
- 如果未来必须兼容 `eth_getProof`，单独设计 Ethereum-facing proof adapter，不要把 TRON archive root 强行伪装成 Ethereum MPT root。

### 6.2 增量节点存储

不管选择哪种 tree，都需要持久化节点：

```text
node_key = algorithm_id || domain_id || node_path
node_val = node_hash || node_metadata
```

如果只保存 root 不保存节点，则无法生成 proof，也无法高效增量更新，只能每次全量重算。

### 6.3 节点历史

如果要支持历史 proof，需要能定位 statePoint 对应的节点版本。三种策略：

| 策略 | 说明 | 适用 |
|---|---|---|
| 只存 block-end root，不存历史节点 | 最省空间，但只能校验 root，不能生成历史 proof | PoC 查询验证 |
| checkpoint 节点 + replay writes | 从 checkpoint 复原 statePoint 节点 | on-demand tx root/proof |
| 节点也 temporal 化 | 节点按 txNum 记录 history | proof 快，但写放大大 |

推荐分阶段：

1. M4：保存 block-end root + current nodes。
2. M5：保存 checkpoint nodes + compact tx write log，支持按需 tx root/proof。
3. 只有 proof 性能需求明确后，再考虑节点 temporal 化。

## 7. 数据模型

### 7.1 `archive_commitment_domain_state`

当前 domain commitment 状态。

| key | value |
|---|---|
| `algorithm_id || domain_id` | `current_domain_root, latest_asof_tx_num, latest_block_num, node_count, root_policy` |

### 7.2 `archive_commitment_nodes`

当前可增量更新的 tree 节点。

| key | value |
|---|---|
| `algorithm_id || domain_id || node_path` | `node_hash, child_refs, leaf_key_hash?, leaf_value_hash?, metadata` |

具体字段取决于 tree_type。

### 7.3 `archive_commitment_roots`

已发布 root。

| key | value |
|---|---|
| `state_point_key || algorithm_id` | `global_root, root_kind, asof_tx_num, block_num, registry_checksum, domain_root_count, completeness, created_at` |

`root_kind`：

- `BLOCK_END`
- `TX_AFTER`
- `SYSTEM_AFTER`
- `CHECKPOINT`

`completeness`：

- `PARTIAL`
- `COMPLETE_ARCHIVE`
- `CANDIDATE_CONSENSUS`

### 7.4 `archive_commitment_domain_roots`

root 时刻的 domain root。

| key | value |
|---|---|
| `state_point_key || algorithm_id || domain_id` | `domain_root, root_policy, domain_codec_version, key_count, leaf_count` |

保存 domain roots 有两个好处：

- proof 时不必重算 global root 输入。
- registry/root policy 变化时能解释历史 root 的 domain 覆盖范围。

### 7.5 `archive_commitment_checkpoints`

checkpoint 元数据。

| key | value |
|---|---|
| `algorithm_id || checkpoint_asof_tx_num` | `block_num, state_point, node_snapshot_ref, root, checksum, status` |

### 7.6 `archive_commitment_tx_writes`

可选表，用于 on-demand tx root/proof。

| key | value |
|---|---|
| `tx_num` | compact ordered root-domain writes |

说明：

- TemporalStore 能保存历史值，但不一定高效还原某个 tx 的 ordered root updates。
- 如果需要从 checkpoint 快速 replay 到某个 `TX_AFTER`，建议持久化 compact tx root writes。
- 只保存 root domain 的 key/value hash 即可，不必重复保存完整 value。

## 8. 写入流程

### 8.1 applyBlock

第一阶段推荐 block 为原子提交边界：

```text
applyBlock(blockWriteSet):
  begin archive commitment tx

  for txWriteSet in blockWriteSet.txWriteSets:
    applyTxWritesToCurrentTrees(txWriteSet)
    if txRootMode == EVERY_TX:
      persistRoot(TX_AFTER(txId))
    if checkpoint boundary:
      persistCheckpoint()

  persistRoot(BLOCK_END(blockNum))
  update commitment progress

  commit archive commitment tx
```

### 8.2 applyTxWritesToCurrentTrees

```text
applyTxWritesToCurrentTrees(txWriteSet):
  for write in txWriteSet.writes sorted by domainId/domainKey:
    if write.rootPolicy != IN_GLOBAL_ROOT and write.rootPolicy != DOMAIN_ROOT_ONLY:
      continue

    leafKey = hashDomainKey(write.domainKey)

    if write.newValue == null:
      domainTree.delete(write.domainId, leafKey)
    else:
      leafHash = hashLeaf(write.domainKey, write.newValue)
      domainTree.put(write.domainId, leafKey, leafHash)

    markDomainTouched(write.domainId)
```

### 8.3 update global root

每次发布 root 时：

```text
for touched domain:
  domainRoot[domainId] = domainTree.root(domainId)

globalInput = sorted(IN_GLOBAL_ROOT domainId -> domainRoot)
globalRoot = globalTree.computeOrUpdate(globalInput)
```

`DOMAIN_ROOT_ONLY` domain 不进入 globalRoot，但可持久化 domainRoot。

### 8.4 root 发布策略

建议定义 `RootPublishMode`：

| mode | 行为 | 适用 |
|---|---|---|
| `BLOCK_ONLY` | 只持久化 block-end root | M4 首选 |
| `EVERY_TX` | 每个 tx 后都持久化 root | 小规模测试或强 proof 需求 |
| `ON_DEMAND_WITH_CHECKPOINT` | 定期 checkpoint，需要 tx root 时 replay | M5 推荐 |
| `DISABLED` | 不计算 root，只做历史查询 | M2/M3 |

默认路线：

1. M2/M3：`DISABLED` 或 `BLOCK_ONLY` shadow。
2. M4：`BLOCK_ONLY`。
3. M5：`ON_DEMAND_WITH_CHECKPOINT`。
4. 只有明确业务需要时才 `EVERY_TX`。

## 9. tx-level root 策略

### 9.1 为什么不默认 every-tx root

每笔交易都发布 root 会带来：

- root 表膨胀。
- 节点历史或 checkpoint 压力。
- proof 数据维护复杂。
- block 内高频 storage 写导致大量 tree update。

交易级状态树不等于必须永久保存每笔交易 root。更合理的定义是：

```text
每个 TX_AFTER statePoint 都可被确定性计算；是否预先持久化是策略问题。
```

### 9.2 on-demand root

推荐方案：

```text
nearest checkpoint before target tx
  -> load checkpoint nodes
  -> replay compact tx root writes until target tx
  -> compute TX_AFTER root
  -> optionally cache root/proof
```

需要：

- checkpoint node snapshot。
- compact tx root writes。
- deterministic replay order。
- registry/root algorithm descriptor。

### 9.3 tx root cache

对热门 tx 可缓存：

| 表 | key | value |
|---|---|---|
| `archive_commitment_tx_root_cache` | `tx_id or tx_num || algorithm_id` | `root, asof_tx_num, checkpoint_ref, created_at` |

缓存可删除，不影响 correctness。

## 10. Proof 设计

### 10.1 proof 组成

一个完整 state proof 应包含：

```text
StateProof:
  statePoint
  algorithmDescriptor
  registryChecksum
  domainId
  domainKey
  domainValue or non-existence marker
  domainRoot
  domainProof
  globalRoot
  globalProof
  completeness
```

### 10.2 domain proof

domain proof 证明：

```text
domainKey -> domainValue 属于 domainRoot
```

或者证明该 domain key 在该 statePoint 不存在。

非存在证明必须由 tree_type 明确定义。不要只返回 “TemporalStore not found” 作为 proof。

### 10.3 global proof

global proof 证明：

```text
domainId -> domainRoot 属于 globalRoot
```

如果 domain 的 `RootPolicy != IN_GLOBAL_ROOT`，则不能生成 global inclusion proof，只能生成 domain-local proof，并标记 root 不完整。

### 10.4 与 Ethereum `eth_getProof`

TRON archive proof 不应默认声称兼容 Ethereum MPT proof。即使接口名类似，也要明确：

- root algorithm。
- proof encoding。
- domainId。
- address 映射。
- storage key 编码。

如果未来需要 `eth_getProof` 兼容，需要单独定义 Ethereum-compatible view 或证明转换层。

PR9 已把 proof/debug 部分细化到代码级：

- `ArchiveProofService` 查询 block-end root、按需计算 tx-level root、生成 proof、验证 proof。
- `CommitmentTree.prove(path32)` 基于 PR7 sparse tree 生成 domain/global proof。
- `debug_getArchiveRoot`、`debug_getArchiveProof`、`debug_verifyArchiveProof` 默认关闭，只暴露 archive-native proof。
- `debug_traceCall` 复用 PR8 historical execution，但需要 per-call trace capture，不能打开全局 `VMConfig.vmTrace` 并写 `vm_trace` 文件。
- `eth_getProof` 继续作为后续 Ethereum-facing adapter，不在 PR9 中实现。

## 11. Rebuild 和校验

### 11.1 从 latest 重建 current root

用于修复 current commitment nodes：

```text
for each root domain:
  iterator = temporalStore.rangeAsOf(domain, fullRange, LATEST)
  rebuild domainTree from sorted keys
globalRoot = aggregate domainRoots
compare with latest stored root
```

### 11.2 从历史 statePoint 重建 root

用于 verifier：

```text
asOf = txNumIndex.resolve(statePoint)
for each root domain:
  rangeAsOf(domain, fullRange, asOf)
  compute domainRoot
compute globalRoot
compare archive_commitment_roots[statePoint]
```

这可能很慢，但作为离线验证工具必须存在。

### 11.3 checkpoint 校验

每个 checkpoint 应能独立校验：

- checkpoint root 与节点 snapshot root 一致。
- checkpoint asOfTxNum 与 TxNumIndex statePoint 一致。
- checkpoint registry checksum 与 domain codec version 一致。
- 从上一个 checkpoint replay 到当前 checkpoint 得到相同 root。

### 11.4 divergence 处理

如果发现 root mismatch：

- 标记 archive commitment unhealthy。
- 停止发布新的 root/proof。
- 历史查询仍可继续，除非 TemporalStore 也 corruption。
- 提供 rebuild/repair 工具。
- 不影响 java-tron 共识执行。

## 12. Unwind 和恢复

### 12.1 hot unwind

Commitment unwind 目标是恢复到某个 statePoint：

```text
unwindTo(BLOCK_END(targetBlock)):
  if target root/checkpoint exists:
    restore domain trees from checkpoint/root state
  else:
    rebuild from nearest checkpoint before target
  delete roots after target
  update commitment progress
```

如果只保存 current nodes + block roots，而没有历史节点/checkpoint，则 hot unwind 后必须从最近可用 checkpoint 或 genesis/base snapshot 重建 current nodes。

### 12.2 block apply 失败

如果 block apply 中途失败：

- 丢弃本 block 的 in-memory tree updates。
- 不持久化 root。
- 不推进 commitment progress。

如果 root 写入已开始但未完成，重启时通过 marker repair。

### 12.3 commit marker

建议：

| marker | 含义 |
|---|---|
| `COMMITMENT_PENDING(blockNum, blockId)` | 开始计算 block commitment |
| `COMMITMENT_NODES_DONE` | current nodes 更新完成 |
| `COMMITMENT_ROOTS_DONE` | block/tx roots 写完 |
| `COMMITMENT_COMMITTED` | progress 完成 |

重启时：

- pending 未提交：回滚或重建该 block commitment。
- committed：校验 root/progress。
- 中间状态：进入 repair。

## 13. Freeze 和 segment

### 13.1 current nodes 与历史 segment

CommitmentBuilder 的 current nodes 只代表 latest/current root。历史 proof 需要：

- checkpoint node snapshots。
- tx write log。
- 或 temporalized commitment nodes。

建议先不把所有 commitment nodes 做完整历史化，避免写放大。

### 13.2 checkpoint segment

可将 checkpoint nodes freeze：

```text
archive/segments/commitment/
  algorithm/domain/checkpoint_txnum.nodes
  algorithm/domain/checkpoint_txnum.manifest
```

manifest 包含：

- algorithm id。
- domain id。
- checkpoint asOfTxNum。
- root。
- node count。
- checksum。
- registry checksum。

### 13.3 proof on cold checkpoint

生成历史 proof：

```text
load checkpoint segment
replay tx root writes to target
generate proof from reconstructed nodes
```

如果 replay window 太长，需要缩短 checkpoint interval 或缓存 tx roots/proofs。

## 14. Java 接口草案

```java
public interface CommitmentBuilder {
  void applyBlock(BlockWriteSet blockWriteSet);

  CommitmentRoot getRoot(StatePoint statePoint, RootAlgorithmId algorithmId);

  Optional<CommitmentRoot> getDomainRoot(short domainId, StatePoint statePoint, RootAlgorithmId algorithmId);

  StateProof prove(short domainId, QueryKey queryKey, StatePoint statePoint, RootAlgorithmId algorithmId);

  CommitmentRoot computeOnDemandRoot(StatePoint statePoint, RootAlgorithmId algorithmId);

  void checkpoint(StatePoint statePoint);

  void unwindTo(StatePoint statePoint);

  CommitmentIntegrityReport checkIntegrity(CommitmentIntegrityScope scope);
}
```

```java
public record CommitmentRoot(
    StatePoint statePoint,
    long asOfTxNum,
    RootAlgorithmId algorithmId,
    byte[] globalRoot,
    RootCompleteness completeness,
    byte[] registryChecksum,
    List<DomainRootRef> domains) {
}
```

```java
public record DomainRootRef(
    short domainId,
    byte[] domainRoot,
    RootPolicy rootPolicy,
    long leafCount,
    short keyCodecVersion,
    short valueCodecVersion) {
}
```

```java
public record StateProof(
    CommitmentRoot root,
    short domainId,
    byte[] domainKey,
    Optional<byte[]> domainValue,
    byte[] domainRoot,
    List<byte[]> domainProofNodes,
    List<byte[]> globalProofNodes,
    ProofKind proofKind) {
}
```

接口要求：

- `prove` 不直接接受 raw address/slot；查询端先通过 `ArchiveStateReader`/Registry 生成 query key。
- `computeOnDemandRoot` 可以慢，但必须 deterministic。
- `getRoot` 对未持久化 tx root 可返回 not found，或按配置触发 on-demand。

## 15. 与其他模块的接口

### 15.1 ArchiveDomainRegistry

CommitmentBuilder 使用：

- root domain 列表。
- `RootPolicy`。
- domainId 排序。
- codec version。
- registry checksum。
- root algorithm activation。

CommitmentBuilder 不内置 domain 常量。

### 15.2 ArchiveWriteCollector

CommitmentBuilder 消费 `BlockWriteSet`：

```text
TxWrite(domainId, domainKey, newValue, rootPolicy)
```

它不重新扫描 Store，也不从 TemporalStore 推断本 block changed keys。

### 15.3 ArchiveTemporalStore

TemporalStore 用于：

- rebuild root。
- on-demand proof 的 state value 验证。
- checkpoint/rebuild 时 rangeAsOf。

正常 block apply 路径中，CommitmentBuilder 不应依赖 TemporalStore 扫描状态。

### 15.4 ArchiveTxNumIndex

CommitmentBuilder 使用 TxNumIndex 解析 root statePoint：

```text
BLOCK_END(blockNum) -> asOfTxNum
TX_AFTER(txId)      -> asOfTxNum
```

root metadata 必须保存 asOfTxNum，便于一致性校验。

### 15.5 ArchiveStateReader

StateReader 可以读取 root/proof：

- `getRoot(blockTag)`。
- `getProof(address/storage, blockTag)`。
- `debug get domain root`。

但 StateReader 不计算 root。

## 16. PoC 范围

### 16.1 PoC v1：partial block root

覆盖：

- `ACCOUNT`
- `CONTRACT_CODE`
- `CONTRACT_STORAGE`

RootPublishMode：

- `BLOCK_ONLY`

RootCompleteness：

- `PARTIAL`

目标：

- 同一段 replay root 可重复。
- root 能发现 TemporalStore/Collector divergence。
- 不提供正式 proof。

### 16.2 PoC v2：complete block root

覆盖：

- 所有 `IN_GLOBAL_ROOT` domain。

目标：

- block-end `COMPLETE_ARCHIVE` root。
- offline rebuild verifier。
- hot unwind/rebuild 可用。

### 16.3 PoC v3：tx-level root/proof

增加：

- checkpoint nodes。
- compact tx root writes。
- on-demand `TX_AFTER` root。
- domain proof + global proof。

目标：

- 交易级 state proof 可验证。
- proof 明确标注 root algorithm 和 domain schema。

## 17. 边界场景

| 场景 | CommitmentBuilder 行为 |
|---|---|
| root domain key 创建 | 插入 leaf，更新 domainRoot |
| root domain key 删除 | 删除 leaf，更新 domainRoot |
| root domain value 为空 bytes | leaf 存在，valueHash 为空 payload hash |
| no-op write | Collector 不应输出；如果收到则拒绝或忽略并告警 |
| history-only domain write | 不改变 globalRoot |
| domainRootOnly write | 更新 domainRoot，不改变 globalRoot |
| root policy activation | activation 前后 root metadata 记录不同 domain set |
| registry checksum 变化 | 无 migration 时拒绝继续发布 root |
| block apply 失败 | 丢弃 root updates |
| hot unwind | 恢复到目标 root/checkpoint，删除后续 roots |
| on-demand tx root 缺 checkpoint/write log | 返回 unsupported，不伪造 root |
| proof 查询未启用 domain | 返回 domain not in root |
| partial root proof | 标记 partial，不能声明完整 state proof |

## 18. Integrity Check

建议检查：

- root metadata 的 registry checksum 与本地 registry 一致。
- 每个 stored root 的 domain root 列表按 domainId 升序。
- globalRoot 可由 stored domainRoots 重算。
- current nodes root 与 `archive_commitment_domain_state` 一致。
- block-end root 的 asOfTxNum 等于 TxNumIndex `BLOCK_END(blockNum)`。
- root domain writes replay 后得到 stored root。
- `DOMAIN_ROOT_ONLY` domain 未进入 globalRoot。
- partial/complete 标记与 root domain 覆盖范围一致。
- checkpoint root 与 checkpoint nodes 一致。

## 19. 性能设计

### 19.1 写路径成本

每个 root-domain write 产生：

- leaf hash。
- tree path update。
- touched domain 标记。
- root publish 时 global tree update。

需要指标化：

- per-block root-domain write count。
- per-domain touched key count。
- node update count。
- root compute latency。
- checkpoint size。

### 19.2 批量更新

同一 tx/block 中同一 domain 的 writes 已由 Collector 排序。CommitmentBuilder 应按 domain 批量更新 tree，减少随机写。

### 19.3 checkpoint interval

checkpoint interval 在两个成本间权衡：

- checkpoint 越频繁，存储越大，但 on-demand tx proof 越快。
- checkpoint 越稀疏，存储小，但 tx root/proof replay 更慢。

建议 PoC 从 block interval 或固定 txNum step 开始，通过压测调整。

### 19.4 every-tx root 风险

`EVERY_TX` 只建议用于小规模测试：

- 大主网数据下 root 表可能快速膨胀。
- proof 节点历史维护成本高。
- 多盘和 segment 设计会更复杂。

生产默认应优先 `BLOCK_ONLY + ON_DEMAND_WITH_CHECKPOINT`。

## 20. 测试计划

### 20.1 单元测试

- leaf hash determinism。
- domain root update：create/update/delete。
- empty bytes value 与 delete 区分。
- global root domainId 排序。
- `DOMAIN_ROOT_ONLY` 不进入 globalRoot。
- empty domain root。
- partial root 标记。
- root metadata registry checksum。

### 20.2 与 Collector 联测

- 同一 block 多 tx writes 驱动 root 连续变化。
- no-op 不触发 root。
- history-only domain 不触发 root。
- root-domain delete 恢复到预期 root。
- replay 同一 BlockWriteSet 得到相同 root。

### 20.3 与 TemporalStore 联测

- block-end root rebuild：从 `rangeAsOf(BLOCK_END)` 重算与 stored root 一致。
- delete/recreate 历史 statePoint root 正确。
- root mismatch 能定位到 domain。

### 20.4 Unwind 测试

- 生成多个 block root，unwind 到中间 block。
- unwind 后 current root 等于目标 block root。
- unwind 后 replay suffix，root 与原先一致。
- frozen/checkpoint 缺失时返回 repair required。

### 20.5 Proof 测试

- account inclusion proof。
- storage inclusion proof。
- non-existence proof。
- global domain proof。
- partial root proof 标记。
- proof verifier 独立验证 root。

### 20.6 On-demand tx root 测试

- 从 checkpoint replay 到 `TX_AFTER(txId)`。
- replay order 稳定。
- tx root cache 命中/失效。
- 缺 compact tx write log 返回 unsupported。

## 21. 验收标准

M4 级别：

- 可生成 block-end sidecar root。
- root metadata 包含 statePoint、asOfTxNum、algorithmId、registry checksum、completeness。
- replay 同一区间 root 完全一致。
- offline rebuild 能验证 block-end root。
- root 不影响共识执行。

M5 级别：

- checkpoint 可用。
- on-demand tx root 可用。
- domain proof + global proof 可用。
- hot unwind 后 root 状态一致。
- partial/complete/candidate consensus root 语义清楚。

共识候选级别：

- 完整 domain 覆盖。
- root algorithm 固化。
- codec/schema activation 固化。
- 性能满足 SR/fullnode 预算。
- 有 TIP/Proposal 明确启用高度和异常处理。

## 22. 实现顺序建议

1. 定义 `RootAlgorithmDescriptor`、`CommitmentRoot`、`DomainRootRef`。
2. 实现内存版 domain tree，支持 create/update/delete/root。
3. 实现 global root 聚合，按 domainId 升序。
4. 接入 `BlockWriteSet`，过滤 root domain writes。
5. 持久化 `archive_commitment_roots` 和 `archive_commitment_domain_roots`。
6. 标记 partial root，先跑 PoC 三个 domain。
7. 实现 offline rebuild verifier，使用 TemporalStore `rangeAsOf`。
8. 实现 current nodes 持久化和 block-end 增量更新。
9. 实现 hot unwind/rebuild。
10. 实现 checkpoint。
11. 实现 on-demand tx root。
12. 实现 proof。
13. 最后才评估是否需要 every-tx root 或共识集成。

第一版最重要的是 root 语义不要夸大：如果 domain 未完整覆盖，就明确输出 partial root；如果 tx root 没有持久化或无法从 checkpoint 重建，就返回 unsupported。可验证的“不支持”比不可验证的“伪支持”更安全。
