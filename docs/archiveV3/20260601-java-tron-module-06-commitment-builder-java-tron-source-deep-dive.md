# 模块 06 CommitmentBuilder：java-tron 源码对照

日期：2026-06-01

> 2026-06-03 更新：本文是旧 `a79693e450` 源码对照。当前 `4e80f8ffa9a2` 的 Module 06 权威入口请看 [模块 06 CommitmentBuilder：4e80 java-tron 源码对照细化](./20260603-java-tron-module-06-commitment-builder-4e80-source-deep-dive.md)。旧行号和旧配置模型不可直接用于编码。

关联设计：[java-tron Archive 模块 06：CommitmentBuilder 细化设计](./20260521-java-tron-archive-module-06-commitment-builder.md)

Erigon 对照：[模块 06 CommitmentBuilder：Erigon 源码对照深挖](./20260601-java-tron-module-06-commitment-builder-erigon-source-deep-dive.md)

代码级实现规格：[java-tron Archive PR7 CommitmentBuilder 代码级实现规格](./20260602-java-tron-archive-pr7-commitment-builder-implementation-spec.md)

Proof/Debug API 代码级实现规格：[java-tron Archive PR9 Proof/Debug API 代码级实现规格](./20260602-java-tron-archive-pr9-proof-debug-api-implementation-spec.md)

逐文件 Patch 清单：[java-tron Archive 模块 06：CommitmentBuilder 逐文件 Patch 清单](./20260602-java-tron-archive-module-06-commitment-builder-patch-checklist.md)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

旧文档原复核基线：本地 java-tron `a79693e450`。当前实现请以 `4e80f8ffa9a2` 细化文档为准。

PR7 规格已基于本文件进一步收敛：不直接复用 `framework` 模块中的 `TrieImpl`，而是在 `chainbase` archive 包内实现 content-addressed binary sparse Merkle tree；`TrieImpl/accountStateRoot` 只作为流程参考。

PR9 规格继续基于本文件的 proof/debug 章节收敛：首版只暴露 archive-native `debug_getArchiveRoot/debug_getArchiveProof/debug_verifyArchiveProof` 和默认关闭的 `debug_traceCall`，不实现 Ethereum-compatible `eth_getProof`。

## 1. 结论

java-tron 当前有两类 root：

1. `txTrieRoot`：区块交易列表 Merkle root。
2. `accountStateRoot`：可配置的账户状态 root，但只覆盖账户状态的一个简化实体。

这两者都不能直接满足交易级 archive global state root：

- `txTrieRoot` 不是状态 root。
- `accountStateRoot` 不是完整执行状态 root，也不是 tx-level root。

因此 `CommitmentBuilder` 第一阶段应生成 archive sidecar root，而不是直接改共识区块头。它消费 `ArchiveWriteCollector` 的 write set 和 `ArchiveDomainRegistry` 的 root policy，在 block/tx 状态点生成：

```text
domainRoot(domain, asOfTxNum)
globalRoot(asOfTxNum)
```

后续如果要进入共识字段，再通过单独 TIP/治理开关升级。

## 2. 当前区块头 root 字段

关键源码：

| 位置 | 作用 |
| --- | --- |
| `protocol/src/main/protos/core/Tron.proto:504-513` | `BlockHeader.raw.txTrieRoot/accountStateRoot` |
| `chainbase/src/main/java/org/tron/core/capsule/BlockCapsule.java:218-230` | `calcMerkleRoot()` |
| `BlockCapsule.java:233-244` | `validateMerkleRoot()` |
| `BlockCapsule.java:246-253` | `setMerkleRoot()` |
| `BlockCapsule.java:255-262` | `setAccountStateRoot(byte[] root)` |
| `framework/src/main/java/org/tron/core/services/jsonrpc/types/BlockResult.java:101-104` | JSON-RPC block result 暴露 `transactionsRoot/stateRoot` |

`txTrieRoot` 已经是共识区块字段，当前 `BlockCapsule.validateMerkleRoot()` 已存在并校验交易 Merkle root。Archive sidecar root 不应复用这个字段，也不接入这个校验。

`accountStateRoot` 已有字段，但当前语义不是 archive root。直接把 archive global root 塞进去会破坏现有节点兼容性和历史语义。

## 3. 现有 accountStateRoot 管线

关键源码：

| 位置 | 作用 |
| --- | --- |
| `common/src/main/resources/reference.conf:812` | `allowAccountStateRoot = 0` 默认关闭 |
| `framework/src/main/java/org/tron/core/config/args/Args.java:462` | 从配置读取 `cc.getAllowAccountStateRoot()` |
| `chainbase/src/main/java/org/tron/core/store/DynamicPropertiesStore.java:789-793` | 缺省时保存 `CommonParameter.getAllowAccountStateRoot()` |
| `chainbase/src/main/java/org/tron/core/store/DynamicPropertiesStore.java:2375-2378` | `saveAllowAccountStateRoot` |
| `DynamicPropertiesStore.java:2380-2389` | `allowAccountStateRoot` |
| `framework/src/main/java/org/tron/core/db/accountstate/callback/AccountStateCallBack.java:23` | `AccountStateCallBack` |
| `AccountStateCallBack.java:34` | `preExeTrans` |
| `AccountStateCallBack.java:38` | `exeTransFinish` |
| `AccountStateCallBack.java:45` | `deleteAccount` |
| `AccountStateCallBack.java:52-72` | `preExecute(block)` |
| `AccountStateCallBack.java:74-92` | `executePushFinish` |
| `AccountStateCallBack.java:94-105` | `executeGenerateFinish`，最终调用 `blockCapsule.setAccountStateRoot(newRoot)` |
| `chainbase/src/main/java/org/tron/core/store/AccountStore.java:68-88` | `put` 后调用 `accountStateCallBackUtils.accountCallBack(key, item)` |
| `AccountStore.java:92-104` | `delete` 不调用 `AccountStateCallBack.deleteAccount` |

管线顺序：

```text
Manager.processBlock
  accountStateCallBack.preExecute(block)
  for tx:
    accountStateCallBack.preExeTrans()
    processTransaction(...)
    accountStateCallBack.exeTransFinish()
  accountStateCallBack.executePushFinish()
```

接收外部区块时，`Manager.processBlock` 在 `Manager.java:1855` 调用 `preExecute(block)`，每笔交易在 `Manager.java:1870-1872` 执行 `preExeTrans/processTransaction/exeTransFinish`，最后在 `Manager.java:1878` 调用 `executePushFinish()`。本地产块时，`Manager.generateBlock` 在 `Manager.java:1627` 调用 `preExecute(blockCapsule)`，每笔待打包交易在 `Manager.java:1719-1721` 执行同样的 per-tx 生命周期，最后在 `Manager.java:1736` 调用 `executeGenerateFinish()`，再于 `Manager.java:1740` 写 `txTrieRoot`。

这个顺序对 Archive CommitmentBuilder 有参考价值：

- block 前加载 parent root。
- tx 后批量 apply dirty entries。
- block 结束校验或写入 root。

但现有实现只覆盖账户，不能直接复用为全局 root。

## 4. AccountStateEntity 的限制

关键源码：

| 位置 | 作用 |
| --- | --- |
| `chainbase/src/main/java/org/tron/core/db/accountstate/AccountStateEntity.java:16` | 从 `Account` 设置字段 |
| `AccountStateEntity.java:24` | parse |
| `AccountStateEntity.java:37` | setAccount |

`AccountStateEntity` 构造只设置：

```text
address
balance
allowance
```

这不是完整账户状态。对 archive root 来说至少还需要考虑：

- 完整 `Account` protobuf。
- 合约元信息。
- code bytes 或 code hash。
- contract storage。
- 影响执行的 dynamic properties。
- 资源、投票、TRC10、治理等 Store 的纳入策略。

另外，当前 `AccountStore.put` 会触发 account callback，但 `AccountStore.delete` 不会调用 `AccountStateCallBack.deleteAccount`。这进一步说明现有 `accountStateRoot` 是历史轻量功能，不能作为 issue #6289 所需的完整 archive 状态树语义来源。

因此现有 `accountStateRoot` 可以作为“账户余额类轻量 root”的历史功能，但不是 archive global root。

## 5. TrieImpl 可复用边界

关键源码：

| 位置 | 作用 |
| --- | --- |
| `framework/src/main/java/org/tron/core/trie/TrieImpl.java:33` | `TrieImpl` |
| `TrieImpl.java:144` | `put` |
| `TrieImpl.java:206-213` | `delete` |
| `TrieImpl.java:286-288` | `getRootHash` |
| `TrieImpl.java:297-305` | `flush` |
| `TrieImpl.java:559-564` | `setRoot` |
| `framework/src/main/java/org/tron/core/db/accountstate/storetrie/AccountStateStoreTrie.java:19` | trie store |
| `AccountStateStoreTrie.java:35` | `getAccount` |
| `AccountStateStoreTrie.java:39` | 按 rootHash 读取 account |

可复用：

- trie put/delete/root 的基础实现。
- trie node store 的接口经验。
- 按 rootHash 读取历史 trie 的模式。

需要谨慎：

- Erigon V3 倾向 commitment 计算与 domain history 解耦，未必需要每 tx 持久化完整 trie nodes。
- java-tron `TrieImpl` 当前用于 account state root，不一定适合多 domain/global root 的 key hashing 规则。
- 若直接每 tx 构建完整 trie，性能和存储成本可能过高。

建议 P0：

```text
先做 block-level sidecar root；
root 输入来自 ArchiveTemporalStore/WriteSet；
trie/hash 细节封装在 CommitmentBuilder；
每 tx root 可选持久化，默认只按需求计算或按配置采样。
```

## 6. Root domain 设计

`ArchiveDomainRegistry` 应告诉 CommitmentBuilder 哪些 domain 进入 root：

P0 建议：

| Domain | root policy | 说明 |
| --- | --- | --- |
| `ACCOUNT` | included | 账户主体 |
| `CONTRACT` | included | 合约元信息 |
| `CODE` | included | 合约 code |
| `CONTRACT_STORAGE` | included | 合约 storage |
| `DYNAMIC_PROPERTIES` | key-filtered | 只纳入执行参数，不纳入 latest cursor |

P1/P2 再扩展：

```text
VOTES
WITNESS
DELEGATED_RESOURCE
ASSET/TRC10
EXCHANGE/MARKET
ZK/SHIELDED
```

在 root 对外承诺前，必须明确 root coverage：

```text
archiveRootCoverage = TVM_STATE_ONLY | FULL_TRON_EXECUTION_STATE
```

否则不同人会把 root 理解成不同范围。

## 7. Root key 编码

建议 global root key 包含 domain id：

```text
globalTrieKey = hash(domainId || canonicalKey)
globalTrieValue = hash(canonicalValue)
```

或者两层 root：

```text
domainRoot[domain] = trie(canonicalKey -> canonicalValue)
globalRoot = trie(domainId -> domainRoot)
```

两层 root 优点：

- domain 可独立验证。
- domain schema migration 更清晰。
- 可只重算变更 domain。

两层 root 缺点：

- proof 多一层。
- 实现略复杂。

建议 P0 采用两层 root：

```text
domain trie: domain local key/value
global trie: domain id -> domain root
```

这与 DomainRegistry 的边界一致，也方便 P1 增加 domain。

## 8. 与 tx-level root 的关系

交易级状态树有两个层级：

```text
state history: 每 tx 都能读
commitment root: 每个 StatePoint 都能算/查 root
```

P0 不建议持久化每个 txNum 的完整 root：

- 成本高。
- 初期需求通常是历史读和 block-level proof。
- 可以先通过 changeset 从最近 checkpoint 重放计算 tx-level root。

建议：

```text
blockRootTable:
  blockNum -> globalRoot after block finalize

optionalTxRootTable:
  txNum -> globalRoot after tx
```

配置：

```text
archive.commitment.persistTxRoots = false by default
archive.commitment.checkpointInterval = N blocks
```

## 9. 写入流程

输入：

```text
BlockWriteSet
  TxWriteSet(txNum, writes)
```

流程：

```text
load parent checkpoint root
for txWriteSet in block:
    for domainWrite in writes:
        if registry.rootIncluded(domain, key):
            domainTrie[domain].put/delete(key, value)
    if persistTxRoots:
        txRootTable.put(txNum, globalRoot())
after block:
    blockRootTable.put(blockNum, globalRoot())
    rootMeta.put(blockNum, domainRoots, coverage, schemaVersion)
```

注意：

- root 应基于 after value。
- delete 对应 trie delete，不是 put empty bytes，除非 domain codec 明确 empty 是有效值。
- `DYNAMIC_PROPERTIES` 必须先过 key filter。

## 10. 与现有 accountStateRoot 的共存

建议不替换现有 `accountStateRoot`：

```text
accountStateRoot: 保持当前语义和开关
archiveGlobalRoot: sidecar DB 保存
```

共存策略：

- 如果 `allowAccountStateRoot` 开启，继续执行现有回调。
- Archive CommitmentBuilder 独立执行。
- 可增加 debug 校验：ACCOUNT domain 中 balance/allowance 子集 root 与现有 accountStateRoot 的可解释关系，但不要要求相等。

未来如果要把 archive root 放入区块头：

1. 新字段或重新定义现有字段需要 TIP。
2. 需要网络治理开关。
3. 需要所有节点在同一高度切换。
4. 需要明确 root coverage 和 codec version。

## 11. 验证 / proof

P0 root 表：

```text
archive_block_root:
  blockNum -> globalRoot, domainRoots, schemaVersion, coverage
```

P1 proof：

```text
getProof(domain, key, statePoint):
  asOfTxNum = txIndex.resolve(statePoint)
  root = commitmentRoot(asOfTxNum)
  value = stateReader.get(domain, key, asOfTxNum)
  proof = trie.prove(domain, key)
```

如果不持久化 tx root，tx-level proof 需要：

1. 找最近 block checkpoint。
2. replay changesets 到目标 txNum。
3. 输出临时 root/proof。

这对 debug 可接受，对高频 RPC 不适合。

PR9 的代码级实现采用 `TX_ON_DEMAND`：从 `ROOT_BLOCK(blockNum - 1)` 开始 replay 当前 block 的 `CHANGESET`，每个 changed key 的 after-value 通过 `ArchiveStateReader.atTxNum(txNum)` 读取，再生成临时 root/proof。超过配置的 replay 上限时返回 unsupported，不做无限慢查询。

## 12. 崩溃恢复

CommitmentBuilder 需要进度：

```text
archive_commitment_meta:
  appliedBlockNum
  appliedBlockHash
  rootSchemaVersion
  coverage
  lastGlobalRoot
```

启动校验：

- `appliedBlockHash` 与 txNum/temporal progress 一致。
- root schema version 与 DomainRegistry 一致。
- 如果 TemporalStore ahead，重算 missing roots。
- 如果 Commitment ahead，unwind root tables。

## 13. 测试建议

### 13.1 root determinism

同一段 block replay 两次：

```text
globalRoot(block N) must equal
domainRoot(ACCOUNT, block N) must equal
domainRoot(CONTRACT_STORAGE, block N) must equal
```

### 13.2 domain inclusion

构造交易：

- transfer：只 ACCOUNT root 变化。
- deploy：ACCOUNT/CONTRACT/CODE root 变化。
- storage write：CONTRACT_STORAGE root 变化。
- dynamic property：按 key policy 决定是否 root 变化。

### 13.3 delete

测试：

- storage slot 写非零后归零，root 回到删除语义。
- account delete/recreate。
- code/contract 删除如果存在对应语义。

### 13.4 与现有 accountStateRoot 共存

开启 `allowAccountStateRoot`：

- 现有 `accountStateRoot` 仍由 `AccountStateCallBack` 写入。
- archive sidecar root 独立存在。
- 两者不互相覆盖。

### 13.5 reorg

reorg 后：

- old fork root 不再是 canonical latest。
- root table unwind 到 common ancestor。
- 新 fork replay 后 root 与独立 replay 一致。

## 14. 实现优先级

P0：

- sidecar block-level `globalRoot`。
- P0 domains root inclusion。
- root schema/coverage 元数据。
- 与 TemporalStore 同步 apply/unwind。

P1：

- 可选 tx-level root 持久化。
- proof API。
- root checkpoint/replay。

P2：

- 完整 TRON execution state coverage。
- cold segment root/proof。
- 共识字段升级方案。

## 15. 关键风险

1. 把 `txTrieRoot` 当状态 root，是概念错误。
2. 把现有 `accountStateRoot` 当 archive root，会遗漏 contract/code/storage。
3. `AccountStateEntity` 不是完整 Account，不能作为 ACCOUNT canonical value。
4. root coverage 不明确会导致跨节点 root 不可比较。
5. 每 tx root 默认全量持久化可能过早引入巨大成本。
6. `DYNAMIC_PROPERTIES` 如果不做 key filter，会把 latest cursor 类节点状态纳入 root。
