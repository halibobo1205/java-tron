# 模块 06 CommitmentBuilder：4e80 java-tron 源码对照细化

> ⚠️ **部分内容已被冻结契约取代**：commitment 表前缀（`0x06-0x08`）**已废弃**，以 **L7** 为准（`ROOT_RECORD/COMMITMENT_BRANCH/COMMITMENT_NODE = 0x30-0x32`，L5 已预留 0x30 段）；下文 `txTrieRoot` 字段号已更正为 **2**（真值 `Tron.proto:505`）。详见 [00-ARCHIVE-V3-AUTHORITY-AND-DECISIONS.md](00-ARCHIVE-V3-AUTHORITY-AND-DECISIONS.md) §2/§4。

日期：2026-06-03

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 基线：`4e80f8ffa9a2`，`git status --short` 为空，精确冲突标记扫描无命中。

Erigon 源码路径：`/Users/boson/GolandProjects/erigon`

编码执行包：[java-tron Archive S10/S11：CommitmentBuilder 4e80 编码执行包](./20260603-java-tron-archive-s10-s11-commitment-builder-4e80-coding-packet.md)

代码级执行包：[java-tron Archive L7：CommitmentBuilder 代码级执行包](./20260605-java-tron-archive-l7-commitment-builder-4e80-code-plan.md)

## 1. 结论

`CommitmentBuilder` 的目标不是替换 java-tron 当前 block header 里的 root 字段，而是在 archive sidecar DB 中为 archive domains 生成可验证的状态承诺：

```text
domainRoot(domain, asOfTxNum)
globalRoot(asOfTxNum)
rootRecord(blockNum, txNum, globalRoot, domainRoots, coverage, algorithmId)
commitment tree state
```

P0 必须保持：

- 不写 `BlockHeader.raw.accountStateRoot`。
- 不写 `BlockHeader.raw.txTrieRoot`。
- 不改变 `BlockCapsule.validateMerkleRoot()` 和 `AccountStateCallBack` 的共识/历史语义。
- root 覆盖范围显式标记为 archive sidecar，例如 `TVM_STATE_ONLY` 或 `ARCHIVE_DOMAIN_SET_V1`，不能声称是完整 TRON consensus state root。

Erigon 的关键启发不是“某个 trie 算法”，而是闭环：

```text
DomainPut -> TouchKey -> Updates -> ComputeCommitment/Process -> PutBranch -> root state
```

java-tron P0 不能只保存 root hash。只保存 root hash 会导致下一个 block 无法稳定增量构建，也无法在 unwind/rebuild 后复用已折叠的 tree state。至少要同时保存 `ROOT_RECORD` 和 `COMMITMENT_BRANCH`。

## 2. java-tron 当前 root 字段

### 2.1 block header 字段

| java-tron 源码 | 当前事实 | archive 结论 |
| --- | --- | --- |
| `protocol/src/main/protos/core/Tron.proto:504-513` | `BlockHeader.raw` 里有 `txTrieRoot = 2` 和 `accountStateRoot = 11` | 两者都是既有 header 字段，P0 archive 不写 |
| `chainbase/src/main/java/org/tron/core/capsule/BlockCapsule.java:218-230` | `calcMerkleRoot()` 基于交易 `getMerkleHash()` 计算交易 Merkle root | 这是交易列表 root，不是状态 root |
| `BlockCapsule.java:233-244` | `validateMerkleRoot()` 已存在，比较 `calcMerkleRoot()` 与 header `txTrieRoot` | archive root 不接入该校验 |
| `BlockCapsule.java:246-253` | `setMerkleRoot()` 写 `txTrieRoot` | archive root 不调用 |
| `BlockCapsule.java:255-262` | `setAccountStateRoot(byte[] root)` 写 `accountStateRoot` | archive root 不调用 |
| `BlockCapsule.java:278-284` | `getAccountRoot()` 读取 header `accountStateRoot`，空值返回 `Sha256Hash.ZERO_HASH` | 这是现有 account root 语义，不是 archive root |
| `framework/src/main/java/org/tron/core/services/jsonrpc/types/BlockResult.java:101-104` | JSON-RPC block result 把 `txTrieRoot` 暴露为 `transactionsRoot`，把 `accountStateRoot` 暴露为 `stateRoot` | archive sidecar root 不能静默改这里的含义 |

早期旧文档中关于 `validateMerkleRoot()` 缺失的判断已经不适用于 `4e80f8ffa9a2`。当前 `BlockCapsule.validateMerkleRoot()` 已存在，Archive root 仍然不能挂到这个分支，因为它校验的是 `txTrieRoot`。

### 2.2 accountStateRoot 配置链路

| java-tron 源码 | 当前事实 | archive 结论 |
| --- | --- | --- |
| `common/src/main/resources/reference.conf:812` | `allowAccountStateRoot = 0` 默认关闭 | 现有 account root 独立开关 |
| `common/src/main/java/org/tron/common/parameter/CommonParameter.java:379` | `allowAccountStateRoot` runtime 字段 | 不作为 archive commitment 开关 |
| `framework/src/main/java/org/tron/core/config/args/Args.java:462` | 从配置读取 `cc.getAllowAccountStateRoot()` | archive config 应走 `storage.archive.*` |
| `chainbase/src/main/java/org/tron/core/store/DynamicPropertiesStore.java:152-153` | 定义 `ALLOW_ACCOUNT_STATE_ROOT` 动态属性 key | governance 属性只控制现有 account root |
| `DynamicPropertiesStore.java:789-793` | 初始化时保存 `CommonParameter.getAllowAccountStateRoot()` | archive commitment 不复用该属性 |
| `DynamicPropertiesStore.java:2375-2389` | `saveAllowAccountStateRoot()` 与 `allowAccountStateRoot()` | archive sidecar root 要新增独立配置 |

Archive 推荐配置形状：

```hocon
storage {
  archive {
    enable = false
    commitment {
      enable = false
      persistTxRoots = false
      algorithm = "tron-archive-smt-v1"
      coverage = "TVM_STATE_ONLY"
    }
  }
}
```

其中 `persistTxRoots=false` 只表示不为每个 tx 持久化 root 记录；实现仍应支持 `rootAtTxNum(txNum)`，可通过最近 checkpoint 加 changeset 计算，满足“交易级别状态树”的查询/验证能力。

## 3. 现有 accountStateRoot 管线

### 3.1 生命周期

| java-tron 源码 | 当前事实 | 对 Archive 的启发 |
| --- | --- | --- |
| `framework/src/main/java/org/tron/core/db/Manager.java:1636` | 本地产块前 `accountStateCallBack.preExecute(blockCapsule)` | block 开始时加载 parent root |
| `Manager.java:1729-1733` | 每笔交易 session 内 `preExeTrans()`、`processTransaction()`、`exeTransFinish()`、`tmpSession.merge()` | tx 结束后统一 apply dirty entries |
| `Manager.java:1747` | 本地产块末尾 `executeGenerateFinish()` | block 结束生成 root |
| `Manager.java:1751` | 随后 `blockCapsule.setMerkleRoot()` | txTrieRoot 与 accountStateRoot 是不同管线 |
| `Manager.java:1870` | 接收 block 执行前 `accountStateCallBack.preExecute(block)` | push block 也从 parent root 开始 |
| `Manager.java:1885-1887` | 每笔交易 `preExeTrans()`、`processTransaction()`、`exeTransFinish()` | push block 也有 per-tx apply 点 |
| `Manager.java:1893` | `executePushFinish()` 校验 header root | archive sidecar root 不应抛共识拒块，除非显式 verifier 模式 |
| `Manager.java:1895` | 异常时 `exceptionFinish()` | archive builder 也需要 abort pending updates |

Archive 可以复用这个生命周期思想，但不能复用 account root 的数据语义。

### 3.2 accountStateRoot 数据覆盖限制

| java-tron 源码 | 当前事实 | 限制 |
| --- | --- | --- |
| `framework/src/main/java/org/tron/core/db/accountstate/callback/AccountStateCallBack.java:34-42` | 每 tx 结束把 `trieEntryList` 写入 `TrieImpl` | 只消费 account callback |
| `AccountStateCallBack.java:52-72` | 从 parent block header 的 `accountStateRoot` 构造 `TrieImpl` | root 来源是 header 字段 |
| `AccountStateCallBack.java:74-92` | push block 时计算 root 并与 header root 比较 | 现有 root 参与历史 header 语义 |
| `AccountStateCallBack.java:94-105` | generate block 时写 `blockCapsule.setAccountStateRoot(newRoot)` | 会改变 header，archive P0 禁止 |
| `chainbase/src/main/java/org/tron/core/db/accountstate/AccountStateEntity.java:16-22` | 只构造 `address/balance/allowance`，assetV2 相关字段被注释 | 不是完整 `Account` protobuf |
| `chainbase/src/main/java/org/tron/core/db/accountstate/AccountStateCallBackUtils.java:13-22` | `accountCallBack(key,item)` 只处理 `AccountCapsule` | 不覆盖 contract/code/storage/dynamic |
| `AccountStateCallBackUtils.java:24-30` | 只有 `execute && allowGenerateRoot` 时才收集 | 受现有治理开关影响 |
| `chainbase/src/main/java/org/tron/core/store/AccountStore.java:68-88` | `AccountStore.put` 调用 account callback | 只有 put 被纳入 |
| `AccountStore.java:92-104` | `AccountStore.delete` 不调用 `AccountStateCallBack.deleteAccount` | 删除语义不完整 |

因此现有 `accountStateRoot` 只能作为流程参考。Archive commitment 的输入必须来自 Module 03 `ArchiveWriteCollector` 收集的 domain write set，而不是 `AccountStateCallBackUtils`。

### 3.3 TrieImpl 只能参考，不建议直接依赖

| java-tron 源码 | 当前事实 | archive 结论 |
| --- | --- | --- |
| `framework/src/main/java/org/tron/core/trie/TrieImpl.java:33` | `TrieImpl` 位于 `framework` 模块 | `chainbase` archive 核心不应反向依赖 `framework` |
| `TrieImpl.java:144-154` | `put` 中 empty/null value 会转 delete | 可参考 tombstone 语义 |
| `TrieImpl.java:210-214` | `delete` 删除 key | Archive 要显式记录 delete/tombstone |
| `TrieImpl.java:290-292` | root 为空时返回 `Hash.EMPTY_TRIE_HASH` | Archive 要定义自己的 empty root |
| `TrieImpl.java:301-304` | `flush` 编码并持久化 dirty nodes | Archive 也要持久化 branch state |
| `TrieImpl.java:563-568` | `setRoot` 从 root hash 加载 trie | Archive rootAt/rebuild 需要等价能力 |
| `framework/src/main/java/org/tron/core/db/accountstate/storetrie/AccountStateStoreTrie.java:26-42` | 使用 `accountTrie` DB 和 `TrieImpl` 查询 account state | 当前 trie 存储是 accountStateRoot 专用 |

P0 推荐在 `chainbase/src/main/java/org/tron/core/archive/commitment` 内实现 archive-native commitment tree，避免引入 `framework` 模块依赖和现有 account trie 的字段语义。

## 4. Erigon commitment 对照

### 4.1 Erigon 的更新入口

| Erigon 源码 | 当前事实 | java-tron 映射 |
| --- | --- | --- |
| `db/state/execctx/domain_shared.go:817` | `SharedDomains.DomainPut(domain,k,v,txNum,prevVal)` 是 domain 写入口 | Module 03 的 `ArchiveWriteCollector.record(domain,key,before,after,txNum)` |
| `domain_shared.go:830-831` | 在 prev/no-op 判断前先 `TouchKey(domain, key, value)` | java-tron 即使 final-after 等于 before，也要先明确定义是否进入 commitment；建议最终 no-op 不入 changeset，但 collector 要能做 first-before/final-after 判定 |
| `domain_shared.go:833-850` | 读取 prev，prev==v 时跳过 domain 写 | Module 03 应同 txNum 内合并为最终值 |
| `domain_shared.go:870` | 写入 temporal mem domain | Module 04 latest/history/changeset batch |
| `execution/commitment/commitmentdb/commitment_context.go:248-267` | `TouchKey` 按 Accounts/Code/Storage domain 转成 account/code/storage update | java-tron 按 `ArchiveDomainRegistry.rootPolicy` 选择 root domain |

java-tron 的关键差异是没有 Ethereum 固定的 account/storage/code 三元组。DomainRegistry 必须输出 root domain、key codec、value codec、delete 编码和 root 覆盖策略。

### 4.2 Erigon 的计算闭环

| Erigon 源码 | 当前事实 | java-tron 结论 |
| --- | --- | --- |
| `db/state/execctx/domain_shared.go:997-1028` | `SharedDomains.ComputeCommitment` 会先 flush pending deferred updates，再调用 context 计算 root | ArchiveService 在 block commit 前要先把 pending writes 转为 commitment updates |
| `execution/commitment/commitment.go:91-118` | `Trie` 接口暴露 `RootHash`、`ResetContext`、`Process` | java-tron 可以拆成 `ArchiveCommitmentTree` + `ArchiveCommitmentContext` |
| `commitment.go:130-138` | `PatriciaContext` 提供 `Branch/PutBranch/Account` | java-tron context 至少要提供 `branch/getValue/putBranch` |
| `commitmentdb/commitment_context.go:682-705` | `encodeAndStoreCommitmentState` 把 commitment state 写入 branch domain | java-tron 不能只写 root record，必须有 branch/state 表 |
| `commitmentdb/commitment_context.go:781-823` | `TrieContext.Branch` 从 CommitmentDomain 读分支，`PutBranch` 写回 CommitmentDomain | Module 04 archive DB 需要 `COMMITMENT_BRANCH` 逻辑表 |
| `hex_patricia_hashed.go:2755-2880` | `Process` 处理 updates、fold 到 root、最后 `RootHash()` | java-tron builder 应把 hash sort、fold、root 作为原子阶段 |

Erigon 的 `CommitmentDomain` 是独立 domain。java-tron P0 可以在单物理 archive DB 中用 prefix 划分：

```text
0x06 ROOT_RECORD
0x07 COMMITMENT_BRANCH
0x08 COMMITMENT_META
```

这些 prefix 与 Module 04 的 latest/history/changeset/txnum/progress 同库同 batch 写入，避免 temporal state 与 commitment state 分叉。

### 4.3 更新顺序必须按 hashed key

Erigon 在 commitment 更新顺序上有明确约束：

| Erigon 源码 | 当前事实 | java-tron 结论 |
| --- | --- | --- |
| `execution/commitment/commitment.go:1429-1440` | `Updates` 注释说明 trie traversal 必须按 `hashedKey` 排序，否则会产生错误 root | java-tron 不能按 `Map`/Store/key 原始顺序折叠 |
| `commitment.go:1791-1797` | `HashSort` 按 hashed keys 处理更新 | ArchiveCommitmentBuilder 应显式排序 |
| `commitment.go:1972-1985` | `keyUpdateLessFn` 先比 `hashedKey`，再用 `plainKey` tie-break | java-tron 需要同样稳定 tie-break |
| `hex_patricia_hashed.go:2799-2861` | `Process` 通过 `updates.HashSort` 驱动 `followAndUpdate` | root 算法输入顺序必须可复现 |

java-tron 建议定义：

```text
leafPath = H("tron-archive-leaf-v1" || domainId || canonicalKey)
leafValueHash = H(valueCodecId || canonicalValue) 或 TOMBSTONE
sortKey = leafPath || domainId || canonicalKey
```

不要使用 Java `HashMap` 迭代顺序、Store 写入顺序、protobuf map 原始顺序作为 root 输入顺序。

## 5. Archive root 语义

### 5.1 覆盖范围

P0 sidecar root 覆盖 Module 02 P0 domains：

| Domain | 是否进入 root | 说明 |
| --- | --- | --- |
| `ACCOUNT` | 是 | `AccountCapsule.getData()` 的 canonical protobuf bytes |
| `CONTRACT` | 是 | contract metadata，必须由 codec 规范化 |
| `CODE` | 是 | runtime bytecode |
| `CONTRACT_STORAGE` | 是 | semantic `(contractAddress, slot)`，不是 `storage-row` 物理 key |
| `DYNAMIC_PROPERTIES` | 是，allowlist | 只纳入执行语义需要的 key |
| `ABI` | P1 | P0 historical `getBalance/getCode/getStorageAt` 不强制 |
| `CONTRACT_STATE` | P1/P0 optional | 取决于后续 historical `eth_call` 最小闭包 |

root record 必须记录 `coverage`。如果 P0 只覆盖 TVM/Ethereum JSON-RPC 相关状态，就标记为 `TVM_STATE_ONLY`，不要命名为 `FULL_TRON_STATE`。

### 5.2 两层 root

建议采用两层承诺，避免 domain 追加时破坏已有 domain root 语义：

```text
domainRoot[domain] = root(domain leaf tree)
globalRoot = root(global tree of domain descriptors)
```

`globalRoot` 的 leaf value 至少包含：

```text
domainId
domainName
domainRoot
keyCodecId
valueCodecId
rootPolicyVersion
coverage
```

这样 verifier 可以明确判断某个 proof 是哪个 domain、哪套 codec、哪种覆盖范围下的证明。

### 5.3 root 记录

建议新增：

```java
final class ArchiveRootRecord {
  long blockNum;
  long txNum;
  byte[] globalRoot;
  Map<ArchiveDomain, byte[]> domainRoots;
  byte[] parentRoot;
  String algorithmId;
  String coverage;
  int schemaVersion;
  byte[] writeSetHash;
}
```

落盘 key：

```text
ROOT_BY_BLOCK(blockNum) -> ArchiveRootRecord
ROOT_BY_TX(txNum) -> ArchiveRootRecord        // persistTxRoots=true 时持久化
ROOT_CURRENT -> ArchiveRootRecord
COMMITMENT_BRANCH(prefix) -> encoded branch
COMMITMENT_META("state") -> latest blockNum/txNum/root/algorithm/coverage
```

对于“交易级别的状态树”，P0 推荐分两级能力：

| 能力 | 默认 | 说明 |
| --- | --- | --- |
| `rootAtBlock(blockNum)` | 持久化 | 每个 block finalize 必须有 root |
| `rootAtTxNum(txNum)` | 支持查询 | 默认可从 checkpoint + changeset 计算 |
| `persistTxRoots` | false | 开启后每个 logical tx 都落 `ROOT_BY_TX`，成本更高 |
| `proofAtTxNum(domain,key,txNum)` | 支持 | 若没有 tx root 记录，先构建临时 root state 再出 proof |

这样既满足交易级验证语义，又不强制首版把每笔交易 root 全量持久化。

## 6. 与 Module 03/04 的接入点

### 6.1 推荐 commit 顺序

Archive block commit 应该构造成一个原子批次：

```text
ArchiveService.beginBlock(block)
  ArchiveWriteCollector.beginBlock(blockNum)
  ArchiveCommitmentBuilder.beginBlock(parentRootState)

for logical phase:
  ArchiveTxNumIndex.enter(txNum)
  ArchiveWriteCollector.collect(firstBefore/finalAfter)
  ArchiveCommitmentBuilder.stageTx(txNum, changedWrites)

ArchiveService.commitBlock(block)
  writeSet = ArchiveWriteCollector.finishBlock()
  rootState = ArchiveCommitmentBuilder.compute(writeSet, parentCommitmentState)
  ArchiveTemporalStore.apply(writeSet, rootRecord, branchWrites, sameBatch)
  ArchiveTxNumIndex.commitBlockRange(blockNum, minTxNum, maxTxNum, sameBatch)
  ArchiveProgressStore.commit(blockNum, txNum, root, sameBatch)
```

不要在 `tmpSession.commit()` 前把 archive DB 提前提交。java-tron canonical state 成功后，archive batch 才能落库。失败路径调用 `abortBlock()` 丢弃 pending writes 和 pending commitment updates。

### 6.2 从 write set 构建 root

Builder 输入应来自 Module 03 的最终写集：

```text
ArchiveWrite {
  ArchiveDomain domain;
  byte[] canonicalKey;
  byte[] beforeValue;
  byte[] afterValue;
  long txNum;
  ArchiveWriteOp op; // PUT/DELETE
}
```

Builder 不应该再从 latest Store 随机读取“当前值”来决定 root。否则会出现：

- revoking session 尚未提交时读到旧值。
- fork replay/recovery 时读到错误分支状态。
- 同一个 txNum 内多次写入无法保证 first-before/final-after。

如果必须补读未变化 key，只能通过 `ArchiveCommitmentContext` 从 archive temporal view 或 commitment branch state 读取。

### 6.3 tx-level root 构建策略

P0 推荐：

```text
per-block:
  persist ROOT_BY_BLOCK
  persist COMMITMENT_BRANCH after block finalize

per-tx:
  if persistTxRoots:
    after each logical tx, compute and persist ROOT_BY_TX
  else:
    keep in-memory tx checkpoints during current block
    historical rootAtTxNum 从 block checkpoint + changeset replay 计算
```

需要明确 logical tx 包括：

- `BLOCK_PREPARE`
- 每个 `USER_TX(txIndex)`
- `BLOCK_FINALIZE`

否则 reward、maintenance、proposal、dynamic property 等 system writes 会没有交易级状态点。

## 7. Unwind/Rebuild

### 7.1 unwind

Archive unwind 必须同时回退：

```text
LATEST
HISTORY
CHANGESET
TXNUM_INDEX
ROOT_BY_BLOCK / ROOT_BY_TX
COMMITMENT_BRANCH
PROGRESS
```

只删除 root record 不够。`COMMITMENT_BRANCH` 保存的是下一次增量 root 的基础状态，必须回到 parent block 对应状态。

可选实现：

| 方案 | 优点 | 风险 |
| --- | --- | --- |
| 每 block 保存 branch diff | unwind 快 | 写放大高 |
| 定期 checkpoint + changeset rebuild branch | 存储低 | unwind/rebuild 慢 |
| P0 每 block branch state 覆盖写 + root record，unwind 时从 parent checkpoint 重建 | 实现相对简单 | 需要明确 checkpoint 周期 |

P0 可以先选择“root record 每 block + branch checkpoint 周期性保存 + unwind 时从最近 checkpoint replay”，但文档和配置必须说清楚。

### 7.2 rebuild verifier

新增 verifier 建议：

```text
archive.commitment.rebuild(fromBlock, toBlock)
archive.commitment.verifyBlockRoot(blockNum)
archive.commitment.verifyTxRoot(txNum)
```

验证流程：

1. 从 genesis 或 checkpoint 加载 commitment state。
2. 按 txNum 读取 Module 04 changeset。
3. 按 DomainRegistry codec 重建 canonical leaf。
4. 按 hashed key 排序 apply。
5. 与 `ROOT_BY_BLOCK` 或 `ROOT_BY_TX` 比较。

这是后续定位 archive corruption 的必要工具，不能只靠线上写入时的 root。

## 8. 新增类建议

| 类 | package | 职责 |
| --- | --- | --- |
| `ArchiveCommitmentBuilder` | `org.tron.core.archive.commitment` | root 构建门面 |
| `DefaultArchiveCommitmentBuilder` | 同上 | 基于 write set 计算 domain/global root |
| `NoopArchiveCommitmentBuilder` | 同上 | archive/commitment disabled 时无行为 |
| `ArchiveCommitmentTree` | 同上 | archive-native tree 算法接口 |
| `SparseMerkleArchiveCommitmentTree` | 同上 | P0 推荐 binary sparse Merkle tree |
| `ArchiveCommitmentContext` | 同上 | 读写 branch/root/meta 的上下文 |
| `ArchiveCommitmentUpdate` | 同上 | domain/key/value/tombstone 更新 |
| `ArchiveRootRecord` | 同上 | root record protobuf/codec 对象 |
| `ArchiveRootStore` | 同上 | `ROOT_BY_BLOCK/ROOT_BY_TX/ROOT_CURRENT` 读写 |
| `ArchiveProof` | 同上 | proof 数据结构 |
| `ArchiveRootCoverage` | 同上 | `TVM_STATE_ONLY/FULL_DOMAIN_SET` 等枚举 |
| `ArchiveCommitmentVerifier` | 同上 | rebuild/verify/debug |

`ArchiveCommitmentTree` 接口建议：

```java
public interface ArchiveCommitmentTree {
  byte[] emptyRoot();
  ArchiveCommitmentResult apply(
      ArchiveCommitmentContext context,
      List<ArchiveCommitmentUpdate> updates,
      ArchiveCommitmentOptions options);
  ArchiveProof prove(ArchiveCommitmentContext context, ArchiveDomain domain, byte[] canonicalKey);
}
```

`ArchiveCommitmentContext` 至少需要：

```java
byte[] getBranch(byte[] path);
void putBranch(byte[] path, byte[] encoded, byte[] previous);
byte[] getDomainValue(ArchiveDomain domain, byte[] canonicalKey, long txNum);
ArchiveRootRecord getCurrentRoot();
void putRootRecord(ArchiveRootRecord record);
```

## 9. 关键编码规则

### 9.1 canonical key/value

由 `ArchiveDomainRegistry` 输出 codec：

```text
canonicalKey = domain.keyCodec.encode(sourceKey)
canonicalValue = domain.valueCodec.encode(sourceValue)
```

要求：

- protobuf value 必须稳定编码。
- 删除统一编码为 tombstone，不把 `null`、空数组、missing 混用。
- `CONTRACT_STORAGE` 使用 semantic `(address, slot)`，不能使用 `Storage.generateDbKey()` 生成的不可逆 physical row key。
- `DYNAMIC_PROPERTIES` 只接受 allowlist key。

### 9.2 hash 域隔离

建议所有 hash 都带域隔离常量：

```text
H("tron-archive-leaf-v1" || domainId || canonicalKey || valueHash)
H("tron-archive-branch-v1" || left || right)
H("tron-archive-global-v1" || domainId || domainRoot || domainMetaHash)
```

不要把现有 Ethereum trie empty root、TRON tx merkle root、archive sidecar root 混用。

### 9.3 同 txNum 多次写

Module 03 已要求 first-before/final-after。CommitmentBuilder 只处理 final-after：

```text
same txNum:
  put A=1
  put A=2
  delete A

commitment update:
  tombstone(A)
```

这样 root 与 temporal latest/history 一致。

## 10. Proof/Debug API 的边界

P0 可新增 archive-native debug API：

```text
debug_getArchiveRoot(blockOrTx)
debug_getArchiveProof(domain, key, blockOrTx)
debug_verifyArchiveProof(root, proof)
```

不要在 P0 实现或伪装 Ethereum `eth_getProof`：

- TRON account encoding 与 Ethereum account encoding 不同。
- TRON contract/code/storage domain 不是 Ethereum Merkle Patricia Trie 形状。
- P0 sidecar root 不在 block header 中，和 `eth_getProof` 预期不同。

JSON-RPC `BlockResult.stateRoot` 继续来自 `BlockCapsule.getAccountRoot()`。如果需要展示 archive root，应新增字段或 debug API，不要改旧字段含义。

## 11. 测试证据

### 11.1 单元测试

- empty tree root 稳定。
- 相同 updates 不同输入顺序得到相同 root。
- 按 raw key 排序和按 hashed key 排序的测试能暴露差异，生产实现必须用 hashed key。
- put/update/delete/tombstone root 正确。
- 同 txNum 多次写只使用 final-after。
- domainRoot 改变会改变 globalRoot。
- domain metadata/codec version 改变会改变 globalRoot 或被 verifier 拒绝。

### 11.2 java-tron 集成测试

- `allowAccountStateRoot=0` 时 archive commitment 仍可独立启用。
- archive commitment 启用时不调用 `BlockCapsule.setAccountStateRoot()`。
- block header `txTrieRoot/accountStateRoot` 与 archive sidecar root 分别保持各自语义。
- 用户交易修改 account/code/storage 后，`rootAtTxNum(userTx)` 改变。
- `BLOCK_FINALIZE` 修改 dynamic properties 后，finalize txNum root 改变。
- 空块仍产生 prepare/finalize 状态点和 block root。
- fork unwind 后 `ROOT_CURRENT`、`COMMITMENT_BRANCH`、`ArchiveTxNumIndex` 同步回退。
- rebuild verifier 从 changeset 重建 root 与落盘 root 一致。

### 11.3 回归风险测试

- `eth_getBlockByNumber` 的 `stateRoot` 仍来自 header `accountStateRoot`。
- `BlockCapsule.validateMerkleRoot()` 行为不变。
- `AccountStateCallBack.executePushFinish()` 行为不变。
- archive disabled 时所有 builder/store/service 都是 no-op。

## 12. 实现顺序

1. 在 Module 02 的 `ArchiveDomainRegistry` 中补齐 `rootPolicy/keyCodec/valueCodec/coverage`。
2. 在 Module 04 archive DB prefix 中新增 `ROOT_RECORD/COMMITMENT_BRANCH/COMMITMENT_META`。
3. 新增 `NoopArchiveCommitmentBuilder` 与配置链路，默认关闭。
4. 新增 `ArchiveRootRecord` codec 和 `ArchiveRootStore`。
5. 新增 archive-native sparse Merkle tree，先覆盖单 domain。
6. 接入 multi-domain root 和 global root。
7. 接入 `ArchiveService.commitBlock`，root record 与 temporal write batch 同批落盘。
8. 实现 `rootAtBlock/rootAtTxNum` 查询和 rebuild verifier。
9. 最后再考虑 debug proof API。

## 13. 不变量

- Archive root 是 sidecar root，不是 header root。
- Root 覆盖范围必须显式记录。
- CommitmentBuilder 消费 write set，不重新依赖 latest Store 推断状态变化。
- Updates 必须按 hashed key 稳定排序。
- Root record 与 branch state 必须同 batch 写入。
- Unwind 必须回退 branch state，不只回退 root hash。
- `AccountStateCallBack` 只作生命周期参考，不作数据来源。
