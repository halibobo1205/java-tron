# 项目 B 立项：java-tron Archive temporal 切换到 Erigon-v3「旧值 + next-change + fall-to-latest」模型

日期：2026-06-29
分支：`feat/archive-node`（base `release_v4.8.2`）
关联：[tronprotocol/java-tron#6289 Archive Node](https://github.com/tronprotocol/java-tron/issues/6289)
参考：Erigon3 读原理 / 写原理（`AddPrevValue` + 倒排索引 + `HistorySeek(>=)` + `GetLatest`）

## 0. 一句话

把 archive temporal 层从当前的「**新值 + floor(`<=`) 查询**」模型，切换到 Erigon-v3 的「**变更前旧值 + next-change(`>=`) 查询 + 无变更则查 latest**」模型；改动**受限于 L4(capture)+ L5(temporal store)**，L6/L8/debug-trace 经 reader 类型化接口**零改动**。

## 1. 背景与动机

当前会话已交付 L1–L6 + L8 + debug_traceCall/traceTransaction，底层 temporal 用的是新值/floor 模型（`getAsOf = seekForPrev`，history 与 latest 存同一个新值）。对 **genesis-complete** 归档完全正确。

但对照 Erigon-v3 的实际实现，java-tron 现模型在三点上弱于 Erigon：

1. **任意高度开启 / mid-chain**：新值/floor 模型对「覆盖起点之前最后写入」的 key 一律返回 MISSING，无法服务；Erigon 的「首个被捕获变更的旧值能回溯到上一次真实变更」让它能多服务一段。
2. **fall-to-latest 不是原生**：现模型需要额外的 disambiguator 才能实现「没改过 → 查 latest」；Erigon 是模型自带（无 next-change ⇒ 没改过 ⇒ latest）。
3. **存储效率**：现模型逐 txNum KV，没有倒排索引压缩；Erigon 用 EliasFano 倒排 + ZSTD + 不可变 step 文件，是其「scalable / efficient」的核心。

本项目把 temporal 对齐 Erigon，作为 Erigon-v3 archive 的正式底座。

## 2. 现状 vs 目标

| 维度 | java-tron 现状 | Erigon-v3 / 目标 |
| --- | --- | --- |
| history 存什么 | 变更的**新值**（`putChange` 把新值同时写 latest + history） | 变更的**旧值**（`AddPrevValue`，`history[key,changeTxNum]=preValue`） |
| 查询方向 | `seekForPrev`（`<=` T 的 floor）→ 新值 | seek `>=` T 的第一次变更 → 其旧值 |
| 无记录时 | MISSING | `GetLatest`（值未变 = 最新值） |
| 倒排索引 | 无（靠 history key 排序） | `.efi`(RecSplit MPHF) + `.ef`(EliasFano) |
| 文件/压缩 | 单 RocksDB CF | 不可变 step 文件(1 step = 1,562,500 txNum，64 step/file) + ZSTD |
| commitment(L7) | 未做 | step 边界 `ComputeCommitment` |

现状关键代码：
- `chainbase/.../archive/capture/ArchiveCaptureHolder.capturePut(dbName, key, value)`：只传新值；但已有 `captureAccountAsset(addressKey, oldAccount, newAccount)` 的旧+新模式可借鉴。
- `chainbase/.../db/TronStoreWithRevoking.put` line 98：`ArchiveCaptureHolder.capturePut(getDbName(), key, value)`。
- `chainbase/.../archive/temporal/RocksDbArchiveTemporalStore.putChange`：latest 与 history 写同一个 value；`getAsOf = seekForPrev`。
- `ArchiveTemporalCodec`：latest `0x00`、history `0x01||domainId||key||txNum(8,BE)`、changeset `0x02||txNum||domainId||key`。

## 3. 核心洞见：B 分两层

**B-core（模型层，改动小、收益大）**：旧值 + next-change + fall-to-latest。
- 拿到「任意高度开启 + 没改过查 latest + mid-chain 回溯」三个能力。
- 不需要 EliasFano/step 文件——**仅靠现有 RocksDB history key 排序的前向 seek 即可实现 next-change**。

**B-efficiency（存储层，改动大、为规模）**：EliasFano 倒排 + RecSplit MPHF + ZSTD + 不可变 step 文件 + merge。
- Erigon 的可扩展性来源；对功能/正确性非必需。

> 关键解耦：L6/L8/debug-trace 都走 reader 的**类型化接口**（getAccount/getContract/getCode/getStorage/getDynamicProperty），其内部只依赖 `getAsOf` 的**契约**（「返回 T 时的值」）。只要 B 保持该契约，**上层零改动**。所以 B 可独立推进、独立验证，不浪费已交付的 L6/L8/debug-trace。

## 4. 设计

### 4.1 写路径（capture 改为捕获旧值）

变更 `K`（old→new）发生在 txNum `T`：
- `history[domain, K, T] = oldVal`（变更前的值）
- `latest[domain, K] = newVal`（当前值，fall-to-latest 用）
- `changeset[T, domain, K] = oldVal`（unwind 用，见 4.4）

实现要点：
- `TronStoreWithRevoking.put`：在 `super.put` 之前先 `byte[] old = getUnchecked(key)`，把 `old` 一起交给 capture（新增 `capturePutWithPrev(dbName, key, old, new)`，或扩展现 `capturePut`）。**每次写多一次读**——这是 Erigon 模型的固有成本，可接受。
- `ArchiveChangeRecord` 增加 `prevValue` 字段（或区分 history-value=prev / latest-value=new）。
- semantic put（CONTRACT_STORAGE 等）同理：`captureSemanticPut` 需带旧值。
- delete：`history[K,T] = oldVal`，`latest[K] = tombstone`。

### 4.2 读路径（getAsOf 改为 next-change + fall-to-latest）

`getAsOf(domain, K, T)`（契约不变：返回 T 时的值）：
```
seek 第一条 history 记录 (domain, K, C) with C >= T   // 前向 seek，不是 seekForPrev
if 命中且仍属于 (domain,K):
    return history value (= oldVal of change C = T 时的值)
else:   // K 在 T 之后没有任何被捕获的变更
    return latest(domain, K)   // 没改过 ⇒ T 时值 == 最新值（fall-to-latest，原生）
```
- 「前向 seek」用现有 RocksDB：`db.newIterator(); it.seek(historyKey(domain,K,T))`；判断 key 前缀仍是 `(domain,K)`。
- tombstone 旧值表示「T 之后第一次变更是『创建』，即 T 时不存在」→ 返回 MISSING/空。

### 4.3 任意高度开启 / mid-chain（B 如何天然支持）

覆盖 `[N, head]`，查 `K` 在 `M`：
- `K` 在 `[M, head]` 改过(被捕获) → 命中 next-change，返回其旧值 = M 时值。✅（包括 `M < N` 但首个捕获变更 `>= N` 的旧值能回溯到上一次真实变更的情形 → 比现模型多服务 `[上次变更, N)` 段）。
- `K` 在 `[M, head]` 没改过 → fall-to-latest。✅
- **残留 gap**：`K` 在 `(M, N)` 改过(覆盖起点之前、未捕获) → 首个捕获变更的旧值是该未捕获变更之后的值，≠ M 时值 → 错。这是「覆盖前数据缺失」的固有边界，Erigon 同样有；用 `firstArchivedBlock` floor（`< N` 直接报「最低支持高度 N」）+ B-efficiency 的快照可进一步收窄。

### 4.4 unwind 语义（用 history 旧值回滚 latest）

回退 txNum `T` 的变更：`latest[K]` 应恢复为 T 时的旧值 = `history[K,T]`（或更早）。changeset 提供 (T → 受影响 K)；逐 K 把 latest 还原为该 K 在 T 之前的值（history 中 `< T` 的最近一条的「新值」… 注意：旧值模型下回滚需要的是「T 之前的值」，等价于 `history[K, T]` 的内容即为 T 之前的值）。**unwind 正确性是 B 的重点验证项**（下方测试）。

### 4.5（B-efficiency）Erigon 存储优化

- 倒排索引：每 key 的变更 txNum 列表用 **EliasFano** 压缩，**RecSplit** MPHF 做 key→offset；对应 Erigon 的 `.ef/.efi`。
- 历史值文件 `.v` + `.vi`(Golomb-Rice)；最新值文件 `.kv` + `.bt`(B-Tree)/`.kvei`。
- 不可变 **step 文件**（1 step = 1,562,500 txNum）+ **ZSTD** + merge/freeze；prune distance、reorg depth 等参数对齐。
- 这一层是规模化优化，**B-core 落地并验证后再上**。

## 5. 范围

**改（L4/L5）**：
- `TronStoreWithRevoking.put`（读旧值 + 传 capture）
- `ArchiveCaptureHolder` / `ArchiveCaptureEngine`（旧+新）
- `ArchiveChangeRecord`（prevValue）
- `RocksDbArchiveTemporalStore` / `InMemoryArchiveTemporalStore`（putChange 写旧值到 history、getAsOf 前向 seek + fall-to-latest、unwind）
- `ArchiveTemporalCodec`（如需调整 value 布局）

**不改（契约不变）**：
- `ArchiveStateReader` / `DefaultArchiveStateReader`（getAccount/...，只依赖 getAsOf 契约）
- L6 `ArchiveJsonRpcStateAdapter`、L8 `Historical*`、debug trace —— **零改动**（前提：getAsOf 契约保持「返回 T 时的值」）。
- 但：B 之后这些上层会**自动获得** fall-to-latest / mid-chain 能力（因为 getAsOf 行为增强）。届时 L6 的 mid-chain「一律拒绝」门、L8/trace 的 mid-chain gap 可改为依赖 B 的原生能力（顺带收口）。

## 6. 兼容与迁移

- **value 语义变更（新值→旧值）**：旧代码写的归档与新代码不兼容 → 需 **schema 版本 bump** + 现有 dev 归档**重建**。归档未上生产，安全。
- 重建：从 genesis 重放（或从 live state 做 N 快照，见 B-efficiency）。
- catalog/registry checksum 应纳入 value-codec 版本，避免误读旧归档。

## 7. 分片实施计划

**B-core**
- **B1**：`ArchiveChangeRecord` + capture 链路改为携带旧值（`capturePutWithPrev` / semantic 旧值）；latest 仍新值。单测：capture 产出 (oldVal→history, newVal→latest)。
- **B2**：temporal `putChange` 写 history=oldVal / latest=newVal；`getAsOf` 改前向 seek + fall-to-latest。单测:present/next-change/fall-to-latest/tombstone（InMemory + RocksDB 一致）。
- **B3**：unwind 在旧值模型下的回滚正确性 + 重启持久化。
- **B4**：reader 契约回归（L6/L8/debug-trace 全测套跑绿，证明上层零改动）+ mid-chain 能力测试（任意高度开启 + 没改过查 latest + `[上次变更,N)` 回溯）+ 对抗验证。

**B-efficiency**（B-core 验证后）
- **B5**：EliasFano 倒排 + RecSplit MPHF（`.ef/.efi`）。
- **B6**：不可变 step 文件 + ZSTD + merge/freeze；`.v/.vi`、`.kv/.bt/.kvei`。
- **B7**：N 快照(backfill) 让 mid-chain 残留 gap 也可服务；prune/reorg 参数对齐。

## 8. 风险与开放问题

1. **每写多一次读**（取旧值）对区块 apply 吞吐的影响——需基准；Erigon 接受此成本。
2. **unwind 在旧值模型下的边界**（fork/fast-pop）——B3 重点验证，是 consensus-adjacent lifecycle 的关键。
3. **getAsOf 契约的「增强」是否对某上层产生意外**——B4 用全测套回归确认；理论上只增不减（mid-chain 多服务 + fall-to-latest）。
4. **schema 迁移**——确认无生产归档；定版本号 + 拒读旧版本。
5. **B-efficiency 的 EliasFano/RecSplit/step 文件** 是大工程，是否值得 vs 直接 RocksDB——按规模需求决定，B-core 不依赖它。
6. semantic 域（CONTRACT_STORAGE 等）取旧值的成本与正确性（version 探测 + 旧值）。

## 9. 测试与验证策略

- 单测:capture 旧值、getAsOf 三态(next-change/fall-to-latest/tombstone)、InMemory↔RocksDB 一致、unwind 回滚、重启持久化。
- 契约回归:**L6/L8/debug-trace 现有全部测试必须不改而绿**（证明 reader 契约不变）。
- mid-chain 集成:任意高度开启 + 没改过查 latest + `[上次变更,N)` 回溯正确 + `< N` floor 报错。
- 对抗验证(ultracode workflow):mid-chain 正确性、unwind 边界、新旧值混淆、契约破坏——比照 L6/L8 verify 的强度。
- 性能基准:写吞吐(多一次读)、getAsOf 延迟。

## 10. 验收标准（DONE）

1. temporal 切到旧值 + next-change + fall-to-latest，InMemory/RocksDB 一致、重启持久。
2. L6/L8/debug-trace **不改代码**全测绿（reader 契约证明不变）。
3. mid-chain:任意高度开启可服务覆盖期内查询 + 没改过查 latest；`< N` 明确报「最低支持高度 N」；残留 gap 仅限「覆盖起点前的未捕获变更」并有明确错误（no silent wrong）。
4. unwind/fork 回滚正确。
5. 对抗验证 0 blocker。
6. （B-efficiency 为独立后续里程碑，不阻塞 B-core 验收。）

## 11. 与既有工作的关系

- L1–L3(config/txNum/domain)、L6(reader+RPC)、L8(eth_call)、debug trace：**全部复用**，B 只换 L4/L5 内核。
- L7 承诺树：独立,且 B 的旧值/倒排底座更贴合 Erigon 的 step-边界 commitment;B-efficiency 与 L7 可协同设计。
- 之前讨论的「N 快照」「fall-to-latest disambiguator」：disambiguator 在 B 下不再需要(原生);N 快照归入 B7。
