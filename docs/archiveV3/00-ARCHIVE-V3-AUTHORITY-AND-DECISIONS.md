# ArchiveV3 权威契约冻结 + P0 决策（实现前必读）

日期：2026-06-26
状态：**契约冻结已生效；7 项决策 + 域注册表已于 2026-06-26 全部拍板（见 §3 + 域注册表 section）**
依据：本目录 [校验报告](20260626-java-tron-archive-design-verification-report.md)（对真实 Erigon v3 + java-tron 源码逐条核实）。

> 这是 `docs/archiveV3/` 的**唯一事实源（single source of truth）**。开始写代码前先读本文件；任何与本文件冲突的设计文档**以本文件为准**。

## 0. 2026-07-18 实现态修订（覆盖下文早期物理与查询草案）

当前尚未上线、无需兼容旧 archive 数据，以下实现态结论覆盖本文后续保留的早期方案推演：

1. 物理布局只有 `UNIFIED_V1` schema 5：一个 RocksDB、多个固定 column family，index、
   temporal、payload、journal 与 marker 的块发布使用一个原子 `WriteBatch`。不存在可选
   legacy 布局或自动迁移路径。
2. 对外历史状态点只有 `ArchiveStatePoint`。block selector、canonical hash 与 tx lookup
   直接在 `ArchiveJsonRpcStateAdapter` / `ArchiveService.open*Reader` 内解析；已删除未装配的
   `ResolvedArchiveStatePoint` 和 `JsonRpcArchiveStatePointResolver`。
3. 公共历史查询要求从 genesis 完整覆盖。temporal miss 不得读取 live latest：完整覆盖渲染
   MISSING，mid-chain 覆盖 fail-closed。已删除 `ArchiveReadThrough` 与
   `ChainBaseArchiveReadThrough`。
4. 生产查询使用统一 RocksDB snapshot，并在响应提交前后校验 canonical mutation epoch；
   不再提供外部 `ReadGuard` 协议。查询 snapshot `fillCache=false`，不会污染执行热缓存。
5. archive-enabled 查询的并发、等待、deadline、backend value/read bytes、VM steps、VM
   overlay 与 response 等上限必须全部有限。默认单请求 VM overlay 上限为
   32 MiB。
6. temporal locator 的格式上限仍为 256 MiB，但生产可存 payload 上限为 32 MiB，给发布时
   同时存活的 Java/JNI/native 表示预留 8 倍工作集；默认查询单值上限为 4 MiB。

下文决策 5/6 中关于拆分 DB、live latest read-through、`ResolvedArchiveStatePoint` 和通用
`ReadGuard` 的描述仅保留为历史决策背景，不再定义当前实现。

---

## 1. 权威分层与基线（FROZEN）

- **权威层**：`20260603/04/05` 的 4e80 看板 + `L1..L9` code-plan。其余 `202605xx / 20260601 / 20260602` 文档为**推导材料**——可读其推理，**但不得从中复制任何标识符（类名/枚举/前缀/字段号）**；一切以 4e80/L 层为准（依 20260609 复审 §1）。
- **基线漂移良性**：文档基线 `4e80f8ffa9a2` 是当前 HEAD `3a9ccfe48c` 的**直接祖先**，落后 14 commits、纯快进。计划可直接落 HEAD，**编码前按方法名 re-grep 刷行号即可，无需重规划**。
- **干净起点**：全仓 `ArchiveService/ArchiveTxNumIndex/storage.archive` 零命中——Archive 尚未实现，L1-L9 全为新增。
- **目标平台（决策 5）**：P0 **只面向 arm64 现代栈**（Java 17 + RocksDB 9.7.4）。基础层（L1 接口/配置/noop）Java 8 兼容、到处都编；真实现（L2-L9）arm64-only 编译单元、可用 Java 17。x86_64（Java 8 + RocksDB 5.15.10）本期 archive 不可启用（`enable=true` 被拒），留作后续。

---

## 2. 跨文档契约冻结表（FROZEN — 实现照此，不照冲突文档）

| 契约 | **冻结值（权威方）** | 被废弃的写法（勿用） |
|---|---|---|
| **物理表前缀 + 历史键序**（M4） | **L5**：`LATEST=0x20 / HISTORY=0x21 / CHANGESET=0x22 / ROOT_*=0x30-0x32`；历史键**升序 txNum**，seek = `historyKey(txNum+1)` | deep-dive 的 `0x01-0x07 + txNumDesc`（降序）—— 字节布局+cursor 相反，DB 互不可读 |
| **域 ID 表**（M2） | **见本文「域注册表」section**；id = **u16 flat 顺序**（2026-06-26 决策，作废旧 `0x01XX`）；如 `code=0x0007`、`storage-row=0x0016`、`contract-state=0x0020`、`abi=0x0006` | S3/L3 旧的 `0x01XX` / `0x0101=ABI` / `0x0101=CONTRACT_STATE` 方案**全部作废** |
| **RawHookMode 枚举**（M2） | **L3**：含 `GENERIC_TRON_STORE_ALLOWLIST` 与 `IGNORE_RAW` | S3 的 `{…, IGNORED, UNCLASSIFIED}`（缺 ALLOWLIST → 无法表达 DYNAMIC_PROPERTIES 的 root 包含） |
| **commitment 表前缀**（M6） | **L7**：`0x30-0x32`（`ROOT_RECORD / COMMITMENT_BRANCH / COMMITMENT_NODE`，L5 已为其预留 0x30 段） | deep-dive 的 `0x06-0x08`；patch-checklist 的 `COMMITMENT_META=0x30`（与 L7 `ROOT_RECORD=0x30` 直接撞） |
| **Reader Status/Reason 枚举**（M5） | **L6**：`Status = {PRESENT, TOMBSTONE, MISSING}`；Reason 用 L6 命名（`ARCHIVE_DISABLED` 等） | S8/S9 的 `Status = {PRESENT, MISSING}`（缺 TOMBSTONE，违反 06-09 typed-tombstone 强制） |
| **codec 类名**（M3） | **L5**：store-key codec = `ArchiveStoreKeyCodec`；域 codec = `ArchiveKeyCodec`（两者分离、不碰撞） | S6/S7 类体里把 store codec 仍写成 `ArchiveKeyCodec`（与 L3 域 codec 同名） |
| **ArchivePhase 基数**（M1） | **L1/L2**：**4 值**（含 `UNWIND`；`UNWIND` 不被 `TxNumMetaCodec` 持久化） | s1-s2 的 3 值 |
| **config key 风格** | `storage.archive.enable`（getter `isEnable()`）；`storage.archive.debug.enable` | `enabled` / `debug.enabled`（HOCON 静默忽略未知 key → 配置看似生效实则没生效） |
| **状态点模型** | `ArchiveStatePoint`（唯一对外历史状态点模型） | `StatePoint` / `ResolvedStatePoint` / `ResolvedArchiveStatePoint`（选择器解析已收口进 service/adapter） |
| **typed tombstone 三态** | `empty bytes ≠ TOMBSTONE ≠ MISSING`；tombstone **不进 root**；L5 `ArchiveStoredValue` 是值包装，状态枚举挂 L6 `ArchiveReadResult.Status` | 把三态混为 missing/zero；把枚举挂错类 |

---

## 3. P0 决策（7 项，2026-06-26 全部已拍板）

### 决策 1 — RootPolicy 枚举基数（4 值 vs 3 值）

- **背景**：L3/S3 用 **4 值** `{IN_GLOBAL_ROOT, DOMAIN_ROOT_ONLY, HISTORY_ONLY, EXCLUDED}`；但 06-09 design-review 与 file-impl-map 用 **3 值**（无 `DOMAIN_ROOT_ONLY`）。`DOMAIN_ROOT_ONLY` = 该域计算自己的 `domainRoot` 但**暂不并入** `globalRoot`，用于逐域灰度（shadow-then-promote）。
- **选项 A（保 4 值）**：把"算 root 但暂不并入 global"显式建模为一等 policy，支持 25-DB 逐域并入 global root 的灰度迁移。代价：把 design-review/file-map 的 3 值表述同步改回 4 值。
- **选项 B（收 3 值）**：枚举更简；灰度用 builder 层"是否并入 global"的布尔开关实现，而非 policy 值。
- **建议：A（保 4 值）**。逐域灰度是本项目核心策略（基础研究 §6.6 即强调"逐域迁移、某些域可先只做 history 不进 global root"），`DOMAIN_ROOT_ONLY` 把这一阶段显式化比隐式布尔更清晰可测。
- **DECISION（2026-06-26 已拍板）：选 A — 保 4 值（含 `DOMAIN_ROOT_ONLY`）。后续须把 design-review §30/§73、file-impl-map:204 的 3 值表述同步改回 4 值。**

### 决策 2 — TRC10 余额覆盖方案（BLOCKER）

- **背景**：资产优化开启时（`getAllowAccountAssetOptimizationFromRoot()==1`），`SnapshotRoot.put` 把 TRC10 余额 `clearAsset()` 从 Account proto 剥离、单写 `AccountAssetStore`（`extends TronDatabase`，无 revoking，写在 **flush 层**）。现 hook 分类法（GENERIC/STORE_SPECIFIC/SEMANTIC_ONLY/IGNORE_RAW）**无类目可捕获**这条路径 → ACCOUNT 域采到的余额零 TRC10，历史 `eth_getBalance(TRC10)` 与"完整 root"皆错。
- **共同前提（A/B 都要做）**：捕获每个 `(account, assetId)→balance` 变更。~~原设想需 hook flush 层~~ → 核源码后**改为 L4 per-tx 语义 hook**（见下方 DECISION 实现细化），不需碰 flush 层。
- **选项 A（account-asset 作 ACCOUNT 域的 SEMANTIC_BACKING）**：类比 storage-row 之于 CONTRACT_STORAGE，把 `(account, assetId)->balance` 作为 ACCOUNT 历史值的语义背书。语义上 TRC10 本属账户；但会让 ACCOUNT 域值编码 + canonical 化更复杂（已因 map 字段棘手，见决策 4）。
- **选项 B（新增独立 `ACCOUNT_ASSET` 域，进 P0 `IN_GLOBAL_ROOT` + `FULL_HISTORY`）**：key = `address || assetId`，value = `balance`。与 Erigon 多域风格一致、可独立灰度；key 空间与 CONTRACT_STORAGE（`address||deploymentHash||slot`）相近，codec/reader 可复用。代价：多一个域 + 需保证同账户两处一致。
- **建议：B（独立 ACCOUNT_ASSET 域）**。更贴合 domain registry 的可灰度/可独立 root 哲学，且避免把 ACCOUNT 域值编码推得更难。
- **DECISION（2026-06-26 已拍板）：选 B — 新增独立 `ACCOUNT_ASSET` 域，进 P0 `IN_GLOBAL_ROOT` + `FULL_HISTORY`。**

**实现细化（2026-06-26，已核源码）**

| 项 | 定论 | 依据 |
|---|---|---|
| 数据源 | **只采 `assetV2`(field 56，按 assetId)；忽略 `asset`(field 6，legacy by-name)** | `getAssets:61` 优化 flush 只迭代 `getAssetV2Map()`；`getBalance:94`/`getAssetV2:864` 读路径亦 assetV2 → **assetV2 是节点唯一权威表示**。pre-fork V2 方法 asset+assetV2 双写镜像（`addAssetAmountV2:747-750`）、post-fork 只写 assetV2 |
| canonicalKey | `address(21) ‖ assetId_bytes` | `getAssets:62`（`Bytes.concat(address, assetId)`） |
| canonicalValue | 8-byte big-endian long；**`balance==0` → tombstone(absent)，非 present-zero** | `getAssets:63 v==0→null`、`:66 Longs.toByteArray` |
| hook 位置 | **L4 per-tx 语义 hook（ACCOUNT 写时），不 hook flush 层** | `clearAsset` 只在 `SnapshotRoot` flush-to-root 那层（`:76/:139`），块内 `SnapshotImpl` 不清，asset 在 per-tx 边界仍在 capsule |
| **变更检测** | ❌ **不可用"assetV2 map presence"**——`importAsset` 懒加载的条目会**携带到下一笔**（tx2 从内存 head 读到 tx1 写入的 `assetV2`）。✅ 对每条 asset 做 **value-diff vs archive latest-overlay**，no-op 丢弃 carry-over | `AccountCapsule.addAssetAmountV2/getAssetV2:733/864` 先 `importAsset`；carry-over 实证 |
| before-value | latest-overlay 命中用之（含本块前序 tx，read-your-writes）；首次碰 → **read-through `AccountAssetStore.getBalance`** | 优化开时磁盘 account 已 clearAsset，不能用 prev-account capsule |
| 可选优化 | 直接 hook `addAssetAmountV2/reduceAssetAmountV2` 拿精确被改集合，省 carry-over 条目无效 diff；但 **value-diff 为主**（更鲁棒） | — |

**连带补决策 4**：**ACCOUNT 域 canonical value 必须剥掉 `asset`(field 6) / `assetV2`(field 56) / `asset_optimized`(field 60) 三字段**，资产一律进 ACCOUNT_ASSET。否则资产优化开/关会让同一状态产生不同 ACCOUNT 值 → 跨节点 root 不一致；剥掉后 archive 表示与本地 optimization 配置无关。

### 决策 3 — getAsOf 语义约定（全局二选一）

- **背景**：L5/L6 用 **inclusive-after**（`BLOCK_END = finalizeTxNum`，caller 不 +1）；旧 PR5/CP-2602 用 **exclusive-before**（`BLOCK_END = lastTxNum+1`）。各自自洽，**混用会静默读到 N+1 块的 BLOCK_PREPARE 态**。Erigon 本体是 before-tx 语义。
- **选项 A（inclusive-after，L5/L6）**：对外用 `ArchiveStatePoint` 屏蔽 off-by-one（基础研究 §6.3 本意），caller 永不手动 +1；内部实现仍用 before-tx seek。
- **选项 B（exclusive-before，PR5）**：贴近 Erigon 原生 GetAsOf 数值。
- **建议：A（inclusive-after，以 L5/L6 为准）**。落地要点：(1) 在 06-09 doc + reader 加不变量"getAsOf 是 inclusive-AFTER，永不与 lastTxNum+1 同用"；(2) 加测试断言 `eth_getBalance(N)` = 块 N post-finalize 且 ≠ N+1 prepare；(3) 块末解析需复现 Erigon `blockNumber+1`（= `maxTxNum(N)+2`）路径以对齐 Erigon RPC，而非字面 `maxTxNum(N)+1`（见校验报告 C13）。
- **DECISION（2026-06-26 已拍板）：选 A — inclusive-after（以 L5/L6 为准）。落地三要点照建议执行：(1) 加不变量"getAsOf 是 inclusive-AFTER，永不与 lastTxNum+1 同用"；(2) 加测试 `eth_getBalance(N)`=块 N post-finalize 且 ≠ N+1 prepare；(3) 块末解析复现 Erigon `blockNumber+1`(=`maxTxNum(N)+2`)。在 PR5/PR9/CP-2602 显式弃用 exclusive+lastTxNum+1。**

### 决策 4 — canonical 值编码策略（root 跨节点可复现的硬要求）

- **背景**：ACCOUNT/CONTRACT 域值 = 重序列化 protobuf（含 7 个 map 字段）。Java protobuf map 按 hash 序迭代 → 非确定字节 → (a) 破坏 no-op 检测、产伪 HISTORY 行；(b) commitment root 跨节点/重启不可复现、proof 失效。本分支无 `accountStateRoot` canonicalizer 可复用。
- **选项 A（per-domain canonicalizing codec）**：每个含 map 的域写 codec，序列化前把 map 条目按 key 字节序排序后重编码（确定字节），并规范化 protobuf 默认值/未知字段。
- **选项 B（存 store 原始落盘 bytes，不重序列化）**：直接用 store 的 `getData()`。但 Account 经 `clearAsset` 等变换后"原始 bytes"未必稳定，且含资产优化分支差异。
- **建议：A（per-domain canonicalizing codec）**。这是 root 可复现的硬要求，须在 L4（采集 no-op 检测）与 L7（root hash）落地前定义；测试：shuffled map 插入序断言相同 canonical bytes + 相同 root（覆盖 ACCOUNT/CONTRACT）。**绝不用 raw `toByteArray()`**。
- **DECISION（2026-06-26 已拍板）：选 A — per-domain canonicalizing codec（含 map 的域序列化前按 key 字节序排序重编码 + 规范化 protobuf 默认值/未知字段）。须在 L4（no-op 检测）与 L7（root hash）落地前定义；带 shuffled-map 决定性测试。**
- **补（决策 2 细化）**：**ACCOUNT 域 canonical value 须剥掉 `asset`(6) / `assetV2`(56) / `asset_optimized`(60) 三字段**（资产进 ACCOUNT_ASSET 域），使 root 与本地资产优化配置无关。同理凡"本地存储优化产物字段"（如此类 flag）都应在 canonical 编码里规范化掉。

### 决策 5 — Archive 物理后端 + 目标平台（历史方案，当前物理布局以 §0 为准）

- **背景**：x86_64 是 **legacy 工具链**（Java 8 + RocksDB 5.15.10 + 老 protoc，几乎肯定为老生产 OS/glibc + Java 8 主网兼容而钉）；arm64 是现代栈（Java 17 + RocksDB 9.7.4）。**全局**把 x86_64 bump 到 9.7.4 会动共识路径 `RocksDbDataSourceImpl` + 冒老 OS 主网兼容风险 → 属项目级基础设施决策，不塞进 archive 本期。
- **DECISION（2026-07-09 修订）：archive P0 仍只面向 arm64 现代栈；当前实现采用兼容 RocksDB 5.15/9.7 的单 keyspace/单 CF 编码，`temporal` / `inflight` / `index` 拆为独立 DB path。多 CF、BlobDB、Zstd/compaction/bloom/prefix、SST ingest 是 P0 之后的物理优化，不作为当前验收门。** x86_64 仍留作后续（archive 跑稳后再随 x86 上现代工具链 / 回移）。
  3. **多盘**用 `cf_paths`（HISTORY 容量盘 / LATEST·ROOT 快盘）；真·冷段 freeze 留 M6。
  4. **模块拆分（关键，与 L1 契合）**：`ArchiveService` 接口 + `NoopArchiveService` + 配置 bean + `ArchiveServiceFactory` 放**基础模块、Java 8 源码级、到处都编**（默认关闭；x86 上 `enable=true` → factory 拒绝"本构建不支持 archive"）。L2-L9 真实现（RocksDB-CF / temporal / reader / commitment）放 **arm64-only 编译单元（Java 17 + RocksDB 9.7.4）**，x86 构建排除。L1 现有"接口 + noop + factory 拒绝 enable"设计本就支持这种拆分。
  5. **真实现可用 Java 17**（arm64-only）；只有 Java-8 基础那层必须 **Java 8 兼容**。
  6. **细化（2026-06-26，L2 落地后确认）**：arm64-only 边界**落在 L5（RocksDB 9.7.4）**——L2-L4（txNum index / domain registry / write-collector）是**纯内存 Java、无 RocksDB 依赖**，与 L1 一同留 **chainbase（Java 8、x86/arm 都编）**；只有 **L5-L9（temporal store / reader / commitment，依赖 RocksDB-CF）进 arm64-only 模块**。"x86 archive 禁用"由 L5 模块在 x86 构建缺席、`ArchiveServiceFactory` 拒 enable 强制（L2-L4 阶段 enable 仅供 arm 开发自测）。故 **L1-L4 须 Java 8 兼容，L5-L9 可 Java 17**。
- **代价/后续**：x86 迁移"是否容易"取决于 x86 是否跟进现代栈——若 x86 上 Java17+9.7.4，纳入模块即可；若仍 Java8/5.15.10，需真 backport（降 Java 语法 + 丢 BlobDB）。建议把"x86 统一现代工具链"作为项目独立 initiative 跟踪。

**实现细化（2026-06-26）— CF 划分 + BlobDB 阈值**

**5 个 CF**（`table_u8` 前缀在 CF 内仍区分逻辑表；`domainId` 留 key 里）：

| CF | 含逻辑表 | 访问模式 | 调优 | BlobDB |
|---|---|---|---|---|
| `latest` | LATEST | 点查热，截至 solidified | 点查优化 / block-cache 优先 / LZ4 | 开 |
| `history` | HISTORY | append 重、超大、key 序、GetAsOf range-seek、几乎不删 | Zstd / 大 block / prefix bloom(`domainId\|key`) / 偏 append compaction | 开 |
| `changeset` | CHANGESET | append、txNum 序、决策 6 后**冷**（仅 tx-root 重放 / 罕见修复）、可剪枝 | Zstd / FIFO-ish | 开 |
| `index` | TXNUM_BLOCK + TXNUM_BY_TXID + TXNUM_META + META + PROGRESS | 小、点查 | 点查优化 / 无压缩 | 关 |
| `commitment` | ROOT_RECORD + COMMITMENT_BRANCH + COMMITMENT_META(L7) | 中量、content-addressed、块边界写 | 独立 compaction | 关 |

- **不合并理由**：CHANGESET 与 HISTORY **key 序相反**（txNum-first vs key-first）且生命周期不同 → 不能并；3 个 TXNUM + META + PROGRESS 都小 → 并 `index`；root + 2 个 commitment 表 → 并 `commitment`。**5 个**落在"5–7"目标内，不按 domain 切（否则几百 CF 内存爆）。
- **BlobDB 阈值**：在 `latest`/`history`/`changeset` 三个 value-bearing CF 开，**`min_blob_size ≈ 1KB`（可调）**——account(剥资产后~100-500B)/storage(32B)/asset(8B)/dynprop 留 **inline**，**只 CODE/大 ABI 分离进 blob**（消其在 append-only LSM 的反复重写写放大）；开 `enable_blob_garbage_collection`；上线前用真实 code/abi/account 尺寸分布 profile 微调。`index`/`commitment` 全小值 → 不开。

### 决策 6 — Archive 持久化模型（sidecar/capture/solidified 仍有效；live query read-through 已由 §0 废止）

维持 sidecar，不替换共识执行态存储；三件事定死：

1. **维持 sidecar / 否决 "domain=state 换心脏"**。现有 stores + `SnapshotManager` 是共识执行态，**不动**（理由：共识风险 / DPoS 实时出块性能预算 / ~25 store + revoking 层 blast radius / TB 级迁移；见 `20260520 §6.1`、校验报告）。"domain=state、干掉旧层"留作**长期北极星**，需独立 TIP/proposal + 性能验证，不进本期。
2. **Capture = 交易级 @ 执行时（不可省）**。内存快照层只保留**块级**最终值（`SnapshotImpl` 是 HashMap，同块内 `K=v1→v2` 只剩 v2；per-tx session 在 generate 路径 `merge()` 即折叠、在 apply 路径直接写块头快照）→ **事后捞不出 tx 级**。L4 WriteCollector 必须在 per-tx 边界（generate 的 per-tx merge 点 / apply 的 `processTransaction` 边界）抓每笔 `firstBefore/finalAfter` 进 **in-flight buffer**。
3. **Persist = solidified 边界 + Latest = read-through（消重复）**。in-flight buffer 覆盖可逆窗口（~19 块不可逆 lag）；块 **solidified 后才落 archive 盘** → 冻进 archive 的数据**永不 unwind**，CHANGESET-driven unwind 从热路径降级为罕见修复/校验工具。当前值 / GetAsOf 未变兜底**读现有 store**（read-through），不为可逆 tip 存热副本；Archive LATEST 只需"截至最后 solidified 块"的 canonical 快照（将来可缩成 `lastTxNum 索引 + read-through`）。

**L4 待办（L2 落地发现，2026-06-27）— `updateFork` 的 txNum 归属**：`Manager.applyBlock` 在 `processBlock` 返回后（即 archive `endTx` 之后、`BLOCK_FINALIZE` 上下文之外）还有几个写：`blockStore`/`blockIndexStore`/`transactionRetStore`（均 EXCLUDED 域 → 无所谓）+ **`updateFork`**（`Manager.java:~1084`）。`updateFork` 可能写 `DYNAMIC_PROPERTIES`（rooted）却**无 archive 上下文/无 txNum**。L4 落地前须确认 `updateFork` 是否写 rooted 动态属性；若是，二选一：(a) 把它移进 `processBlock` 的 `BLOCK_FINALIZE` 段内，或 (b) L4 显式把这些写归属到块末 finalize txNum。注：`payReward`（块奖励）+ 维护 `consensus.applyBlock`（witness 重排 / proposal 生效）**已在 `BLOCK_FINALIZE` 上下文内**（`Manager.java:1943/1951`），无此问题。

### 决策 7 — Root/Proof 模型 + 覆盖 + 域分类原则 + id 方案（2026-06-26 已拍板）

**确认 L7/s10/L9 已成熟的 root/proof 模型**（不重造）：

1. **两层聚合**：`domainRoot(domain,txNum)` → `globalRoot(txNum)`；`ArchiveRootRecord{globalRoot, domainRoots, coverage, blockHash, finalizeTxNum}`。
2. **256-bit binary 稀疏 Merkle 树**，content-addressed branch；hash = **keccak（`org.tron.common.crypto.Hash.sha3`）**；自定义 empty-hash chain；algorithm 版本化（`tron-archive-smt-keccak-v1`，`algorithmId|treeKind|domainId`）；**globalRoot 绑 registry checksum**（schema 漂移即 proof 失效）。
3. **二段式 native proof**（domain 树内 sibling path + 证明 `DomainRootRecord` 被 globalRoot 收录）+ **absence proof**；返回前自验；标 `ARCHIVE_SIDECAR`。
4. **debug API**：`debug_getArchiveRoot / getArchiveProof / verifyArchiveProof`，**FullNode-only + default-off + `storage.archive.debug.enable`**，只读 archive root/node/value。
5. **明确不做**：`eth_getProof`（method-not-found）、`debug_traceCall` / `vmTrace`、不写 header `txTrieRoot/accountStateRoot`、high-QPS 公共 proof 服务。

**域分类原则**：block-tx 驱动 + canonical + 共识有意义 → `IN_GLOBAL_ROOT`（见域注册表，**17 个**）；**ABI = 元数据例外** → `HISTORY_ONLY`；派生/索引/receipt/一次性/启动态（即便被区块写）→ `EXCLUDED`。→ **globalRoot 即近乎完整 TRON state root**，不只 ETH-compat 子集。

**id 方案**：`domainId` = **u16 flat 顺序**，**append-only 永不重排/复用**（作废旧 `0x01XX`；见域注册表）。

**时序**：**L7-L9 留 P1**——P0 先 L1-L6 历史读闭环（用 L6 三个 historical getter 验"不 fallback latest"）；root/proof 建在其上。build L7 时按本决策 + 域注册表 `IN_GLOBAL_ROOT` 集（17 域）计算。256-deep binary SMT proof 最坏偏大但稀疏压缩后≈log N，debug API 非高 QPS 可接受（若日后要更小 proof 再议 nibble-patricia）。

- **下游改动**：L2 提交时机 每块 → solidified（capture 仍 per-tx）；L5 unwind 降级 + LATEST 范围=solidified + 新增 in-flight buffer 组件；§2/§6 相应注记。
- **代价**：historical 查询到不可逆点（~19 块）才可见（可接受；最近 ~19 块 latest 走现有 store）；read-through 轻度耦合现有 store 编码。
- **DECISION（2026-06-26 已拍板）：选 A — 见上三条。**

---

## 域注册表（ArchiveDomain — L3 完整分类，落实决策 1/2/5/7）

id = **u16 flat 顺序**（决策，作废旧 `0x01XX`）；保留第一版枚举 id（widen 到 u16）。RawHookMode：`GENERIC` / `SEMANTIC`（语义钩子）/ `ALLOWLIST`（key 级）/ `IGNORE_RAW`（不 raw 采）。**非 revoking store ⇒ 永不 GENERIC**（见决策硬规则）。

### 捕获域（进 archive key，按 domainId 排序聚合 root）

**分类原则（2026-06-26 决策 7）**：**block-tx 驱动 + canonical + 共识有意义的真状态 → `IN_GLOBAL_ROOT`**；唯一例外 **ABI**（被区块写但纯 client 元数据、不影响 VM/共识）→ `HISTORY_ONLY`。派生/索引/receipt/一次性/启动态即便被区块写也 EXCLUDED（见下）。

| id(u16) | dbName | 域名 | RootPolicy | HistoryPolicy | RawHookMode |
|---|---|---|---|---|---|
| 0x0001 | account | ACCOUNT | **IN_GLOBAL_ROOT** | FULL_HISTORY | GENERIC + SEM(剥 asset/assetV2/optimized) |
| 0x0002 | account-asset | ACCOUNT_ASSET | **IN_GLOBAL_ROOT** | FULL_HISTORY | **SEMANTIC**(从 account.assetV2;store 本体 IGNORE_RAW + read-through) |
| 0x0005 | asset-issue-v2 | ASSET_ISSUE | **IN_GLOBAL_ROOT** | FULL_HISTORY | GENERIC |
| 0x0006 | abi | ABI | **HISTORY_ONLY**（元数据） | FULL_HISTORY | GENERIC |
| 0x0007 | code | CODE | **IN_GLOBAL_ROOT** | FULL_HISTORY | GENERIC |
| 0x0008 | contract | CONTRACT | **IN_GLOBAL_ROOT** | FULL_HISTORY | GENERIC |
| 0x0009 | delegation | DELEGATION | **IN_GLOBAL_ROOT** | FULL_HISTORY | GENERIC |
| 0x000a | DelegatedResource | DELEGATED_RESOURCE | **IN_GLOBAL_ROOT** | FULL_HISTORY | GENERIC |
| 0x000c | exchange | EXCHANGE | **IN_GLOBAL_ROOT** | FULL_HISTORY | GENERIC |
| 0x000d | exchange-v2 | EXCHANGE_V2 | **IN_GLOBAL_ROOT** | FULL_HISTORY | GENERIC |
| 0x000f | market_account | MARKET_ACCOUNT | **IN_GLOBAL_ROOT** | FULL_HISTORY | GENERIC |
| 0x0010 | market_order | MARKET_ORDER | **IN_GLOBAL_ROOT** | FULL_HISTORY | GENERIC |
| 0x0014 | properties | DYNAMIC_PROPERTIES | **IN_GLOBAL_ROOT**(key 级) | FULL_HISTORY | **ALLOWLIST** |
| 0x0015 | proposal | PROPOSAL | **IN_GLOBAL_ROOT** | FULL_HISTORY | GENERIC |
| 0x0016 | storage-row | CONTRACT_STORAGE | **IN_GLOBAL_ROOT** | FULL_HISTORY | **SEMANTIC**(精确 32-byte `Storage.compose` physical row key) |
| 0x0017 | votes | VOTES | **IN_GLOBAL_ROOT** | FULL_HISTORY | GENERIC |
| 0x0018 | witness | WITNESS | **IN_GLOBAL_ROOT** | FULL_HISTORY | GENERIC |
| 0x0020 | contract-state | CONTRACT_STATE | **IN_GLOBAL_ROOT** | FULL_HISTORY | GENERIC（EnergyFactor，共识级执行态） |

**IN_GLOBAL_ROOT = 17 个**（全部 canonical block-tx 驱动状态 → **globalRoot 即近乎完整的 TRON state root**，不只 ETH-compat 子集）。**唯一 HISTORY_ONLY = abi**（client 元数据，共识无关）。`witness_schedule(0x19)` 移入 EXCLUDED（维护时由 votes 算 top-N，可重建）。

### EXCLUDED（显式登记 + 理由，过 L3 覆盖测试；不进 archive key）

| dbName(含旧 id) | 理由 |
|---|---|
| reward-vi | **一次性不可变 + 自带根**（`RewardViCalService` MAIN_NET_ROOT/IS_DONE）；read-through |
| nullifier(0x13) / IncrementalMerkleTree(0x0e) | shielded（`allowShieldedTRC20Transaction=0` 默认禁用 + zk-private，非 ETH-compat 范围） |
| witness_schedule(0x19) | 派生：维护时由 votes 算 top-N active 集，可由 votes 重建（active_witnesses 死 key current_shuffled 同此） |
| account-index(0x03) / accountid-index(0x04) / DelegatedResourceAccountIndex(0x0b) / market_pair_to_price(0x12) / market_pair_price_to_order(0x11) | 派生索引（id 保留但不捕获，IGNORE_RAW） |
| section-bloom | log bloom 派生 |
| account-trace / balance-trace | 旧 balance.history.lookup 数据，被新 archive 取代 |
| transactionHistoryStore / transactionRetStore | receipt/结果，非状态 |
| tree-block-index / latest_block_header_* | 链指针/索引 |
| asset-issue(v1) | legacy(post-fork dead) |
| zkProof / checkpoint(CheckPointV2) / tmp(CheckTmp) / common / common-database / pbft-sign-data / state_flag | 操作性/共识签名缓存，非状态 |

> 注：第一版枚举给 index 域留了 id（0x03/0x04/0x0b/0x11/0x12）——保留为 reserved（registry/RawHookMode 绑定用），但 policy=EXCLUDED、永不进 archive key。0x06 原 gap 现分配给 ABI。

---

## 4. 已应用的必修事实更正（doc-hygiene）

| 更正 | 文件 | 说明 |
|---|---|---|
| `txTrieRoot = 4` → **`= 2`** | `20260603-…module-06-…4e80-source-deep-dive.md` | 真值 `Tron.proto:505 txTrieRoot = 2`、`:513 accountStateRoot = 11`；L7 的 =2 本就对 |
| `debug.enabled` → **`debug.enable`** | `…4e80-test-verification-plan.md`、`…4e80-implementation-roadmap.md` | HOCON 静默忽略未知 key |
| `StatePoint.java` → **`ArchiveStatePoint.java`** | `…4e80-implementation-roadmap.md`（包列表） | 删并行模型，统一 ArchiveStatePoint |
| milestone-0 "无 reference.conf/StorageConfig" | `20260601-…milestone-0-source-map.md`（顶部 banner） | 两文件 4e80 即存在；`StorageConfig` 由 #6615 用 **ConfigBeanFactory** 引入 → 配置走 L1 的 ConfigBeanFactory 路径，**勿抄 milestone-0 的旧手工解析口径** |
| Erigon C04 / C13（不在文档内改，记此供实现参考） | — | C04：Erigon 普通域 GetAsOf history-miss **回落 latest**（`domain.go:1410-1422`），仅 Commitment 早退——java-tron"不回落"是**有意分歧**，须文档化并验存量账户仍读得出值。C13：见决策 3 落地要点 (3) |

被 supersede 的冲突文件已在其**顶部加 banner** 指回本文件（M4/M6 deep-dive、S3、S6-S7、S8-S9、s1-s2、foundational、milestone-0）。

---

## 5. Forbidden-for-copy-paste 名录（旧标识符，勿带进实现）

| 旧（禁用） | 新（权威） |
|---|---|
| `CONTRACT_CODE` | `CODE` |
| `CONTRACT_META` | `CONTRACT` |
| `DYNAMIC_GLOBAL` | `DYNAMIC_PROPERTIES` |
| `TRC10_ASSET / VOTE_WITNESS / DELEGATION_RESOURCE`（基础研究里的猜想域） | 非 P0 域；P0 域集合见 L3 |
| `StatePoint / ResolvedStatePoint / ResolvedArchiveStatePoint` | `ArchiveStatePoint`；选择器解析由 archive service/adapter 完成 |
| ~~`DOMAIN_ROOT_ONLY`~~（决策 1 选 A） | **保留为合法 RootPolicy 值**，不再 forbidden |
| `getRawValue`（不存在的方法） | `getUnchecked`（`TronStoreWithRevoking.java:108`） |
| `enabled / debug.enabled` | `enable / debug.enable` |

---

## 6. 实现起步顺序（逐切片就绪度）

| 切片 | 现在能否开工 | 前置 |
|---|---|---|
| **L1** config/no-op/dbName | ✅ **可立即开工** | 注册 `storage.archive` bean 进 `ConfigParityGateTest`（#6803/#6810，4e80 后新增的构建门）+ 每 key 加 reference.conf 注释 + `:common:test --tests '*ConfigParityGateTest'` 入验收门；用 ConfigBeanFactory（勿抄 milestone-0）。**决策 5**：L1 是**基础层、Java 8 兼容、x86/arm 都编**，default-off，x86 上 `enable=true` 由 factory 拒绝；L2-L9 真实现进 arm64-only 编译单元 |
| **L2** Manager+txNum | ✅ 可（刷行号） | 统一 ArchivePhase=4 值；**决策 6**：提交时机 每块→solidified（capture 仍 per-tx）；确认 #6833 新 rollback-trigger 后 archive commit 仍先于触发发射 |
| **L3** domain registry | ✅ 决策已定，可开工 | RootPolicy=4 值；TRC10=新增 `ACCOUNT_ASSET` 域 + flush-层 hook 类目；域 ID/RawHookMode 冻结到 L3 |
| **L4** write collector | ✅ L3 后可开工 | canonical 编码=per-domain codec（决策 4）；**决策 6**：tx-level capture @ per-tx 边界 → in-flight buffer |
| **L5** temporal store | ✅ 决策已定，可开工 | M4 前缀冻结到 L5；getAsOf=inclusive-after（决策 3）；**决策 6**：persist@solidified / unwind 降级 / LATEST=solidified 快照 + tip read-through / 新增 in-flight buffer；**物理后端待决策 5** |
| **L6** state reader | ✅ 决策已定 | getAsOf=inclusive-after；落地处理 C04（"不回落 latest"=有意分歧，须验存量账户）/ C13（块末 +2）；reader 枚举冻结到 L6 |
| **L7-L9** root/proof/debug | ⏸ 非 P0 阻塞（决策 7：留 P1） | 后置，不挡 P0 历史读主线；build 时按域注册表 17 个 IN_GLOBAL_ROOT 域算 root |

**建议**：7 项决策 + 域注册表已定。**L1 立即开工**（验证 archive 默认关闭不改现网行为），随后 **L2→L3→L4→L5→L6** 拉通最小历史读闭环，用 L6 三个 historical getter 验"不 fallback latest"。L7-L9 留 P1。

---
*本文件随决策回填更新。决策填好后通知我，我据此推进 L1-L6。*
