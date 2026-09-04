# java-tron Archive 设计文档校验报告 (Verification / 校验)

日期：2026-06-26
位置：本文件与被校验的 **80 份设计文档同处 `docs/archiveV3/`**（同目录，报告内对设计文档的引用均为同目录文件名）。
方法：多智能体 workflow `wcbxq86hx`（37 agents / ~3.0M tokens / 636 tool-calls）+ `SynA-foundational` 补跑（agent `a46512035343502c3`）逐条对真实 Erigon v3 + java-tron 源码核实，主控对所有"承重"结论做第二轮独立复核（见 §0.3）。
状态：**核实已完成**（11 簇 + SynA 补跑全部回填；全部承重结论经主控第一手坐实）。

---

## 0. 校验范围与方法

对 `docs/archiveV3/` 下 **80 份**设计文档（约 6.3 万行；源自 `java-tron-archive-research-docs-20260626.zip`，原始路径 `docs/plans/`）做交叉核实：java-tron 参考 Erigon v3 实现**交易级 (txNum-level) Archive 节点**（[tronprotocol/java-tron#6289](https://github.com/tronprotocol/java-tron/issues/6289)）。报告内 `file:line` 形式的 java-tron 源码引用均相对仓库根（如 `chainbase/...`、`protocol/...`）；Erigon 引用相对其独立仓库 `/Users/boson/GolandProjects/erigon`。

### 0.1 三条独立证据线

1. **Erigon 源码保真度** — 文档对 Erigon v3 模型/文件/语义的断言，逐条比对真实 Erigon 源码。
2. **java-tron 源码保真度** — 文档对 java-tron 现有代码的断言，逐条比对真实 java-tron 源码。
3. **跨文档一致性 / 设计自洽 / 25-DB 覆盖 / 落地就绪度** — 命名漂移、off-by-one、reorg/unwind、canonical 序列化、基线漂移、构建门等。

裁决码：`CONFIRMED / REFUTED / PARTIAL / OUTDATED(语义对、行号漂移) / UNVERIFIABLE(前瞻性新代码)`，均附 `file:line` 证据。

### 0.2 参考基线（第一手确认）

| 基线 | 值 | 确认 |
|---|---|---|
| Erigon 仓库 | `/Users/boson/GolandProjects/erigon` @ `v3.0.0-beta1-3822-g4ccd6f64a6` | `git describe` |
| java-tron 仓库 | 本 worktree @ `3a9ccfe48c`（master 线） | `git log` |
| 文档目标基线 | java-tron `4e80f8ffa9a2`（"4e80" 系列），**是 HEAD 的直接祖先，落后 14 commits，纯快进** | `git merge-base --is-ancestor` = YES |
| 权威分层 | `20260603/04/05` 4e80 看板 + `L1..L9` code-plan 为准；早期 `202605*/0601*/0602*` 为推导材料 | 20260609 复审 §1 |

### 0.3 主控独立复核日志（"反复核实"）—— 所有承重结论均第一手坐实

> ⚠️ **更正 workflow 报告的一处过保守表述**：合成报告页脚称"Erigon 锚点不在本环境、无法复核、属最高风险未核实类"。这是**不准确的**——Erigon 仓库确实存在，主控已亲自逐条核实下列承重 Erigon 结论（部分 verifier agent 因沙箱/路径原因未能读到 Erigon，但产出 Erigon 结论的那些 agent 读的是真源码，下表逐条复现了它们的行号）。

| 结论 | 主控独立证据 | 复核结果 |
|---|---|---|
| **C04** Erigon 普通域 GetAsOf history-miss **回落 latest**，仅 CommitmentDomain 早退 | `db/state/domain.go:1410-1422` 普通域 fall through 到 `GetLatest`；`:1405-1408` `if dt.name == kv.CommitmentDomain { return nil,false,nil }` | ✅ REFUTED 成立——**L5 把语义读反了** |
| **C13** eth_getBalance(N) 实际 = `GetAsOf(maxTxNum(N)+2)` 而非 `+1` | `rpc/rpchelper/helper.go:189 blockNumber+1`；`:197/:235 txNum=minTxNum+txnIndex+ /*1 system txNum*/ 1` | ✅ off-by-one 属实 |
| **C10** `DomainPut` 真签名含 `roTx kv.TemporalTx`（文档漏第 2 参） | `db/state/execctx/domain_shared.go:817 func (sd *SharedDomains) DomainPut(domain kv.Domain, roTx kv.TemporalTx, k,v []byte, txNum uint64, prevVal []byte)` | ✅ 漏参属实 |
| **proto 字段号** txTrieRoot=2 / accountStateRoot=11（deep-dive 写的 4 是错的，L7 的 2 对） | `protocol/.../Tron.proto:505 bytes txTrieRoot = 2`；`:513 bytes accountStateRoot = 11` | ✅ deep-dive 错、L7 对 |
| **TRC10 覆盖 BLOCKER** 资产优化开启时 TRC10 余额被剥离写 account-asset、绕过 hook | `SnapshotRoot.java:46 getAllowAccountAssetOptimizationFromRoot()==1`、`:76/:139 item.clearAsset()`、`:146 assetStore.updateByBatch`、`:86 deleteAccount`；`AccountAssetStore extends TronDatabase`（无 revoking）；`AccountCapsule.java:821 clearAsset()` | ✅ 覆盖缺口属实 |
| **getRawValue 杜撰** | 全仓 `getRawValue` 命中 **0**；真方法 `TronStoreWithRevoking.java:108 getUnchecked` | ✅ 方法名杜撰 |
| **构建门 MAJOR** `ConfigParityGateTest` 存在 | `common/src/test/java/org/tron/core/config/args/ConfigParityGateTest.java` | ✅ 风险属实 |
| **现有余额历史能力** `storage.balance.history.lookup` | `MiscConfig.java:22 historyBalanceLookup`；`Args.java:99 --history-balance-lookup` | ✅ |
| **Archive 尚未实现** | 全仓 `ArchiveService/ArchiveTxNumIndex/storage.archive` 命中 **0** | ✅ 纯前瞻设计 |

**结论：workflow 的全部承重裁决经主控独立复核均成立。报告可信。** 唯一需修正的是页脚那句"Erigon 未复核"的过保守措辞，以及补跑 `SynA-foundational` 簇（早期 extract agent 返回了占位 stub，见附录 C）。

### 0.4 已第一手确认的 grounding facts

- ✅ Erigon v3 全部 11 个被引源文件存在于所引路径。
- ✅ java-tron 现有被引类全部存在（`RepositoryImpl/CodeStore/DynamicPropertiesStore/StorageRowStore/ContractStore/AccountStore/Manager/TransactionTrace`）。
- ✅ **Archive 功能本仓库尚未实现** → 全部文档为前瞻性设计；只有"现有代码"断言可硬核实。

---

## 1. 中文执行摘要

**总体结论：设计在概念上忠于 Erigon v3，且与 java-tron 当前源码高度对齐——但语料目前"不可直接进入编码"。** 跨 11 簇、约 90+ 条源声明：Erigon 24 CONFIRMED / 4 PARTIAL / 1 REFUTED；java-tron 60 CONFIRMED / 6 PARTIAL / 1 OUTDATED——refute/partial 几乎都集中在"行号漂移"和少量语义读错。真正阻断的是 **跨文档硬契约不一致（7 个 BLOCKER）**：同为 4e80 基线的"S 编码包"与"L 代码计划"两族在枚举、物理表前缀、域 ID 上彼此冲突，照不同文档实现会产出**互不兼容的磁盘布局和无法互验的 root**；外加一个 **25-DB 覆盖 BLOCKER（TRC10 余额）** 和两处 **Erigon 语义读错**。

**编码前必须先修的 Top 5**

1. **统一物理表前缀与历史键排序（M4）** — `module-04-deep-dive` 用 `0x01-0x07 + txNumDesc`，权威 L5 用 `0x20/0x21/0x22 + 0x30-0x32 + 升序 txNum`。两套字节布局不兼容。**冻结 L5，给 deep-dive 打 supersession banner。**
2. **统一 M2 域 ID / RawHookMode / RootPolicy 三个枚举**（S3 vs L3）：`0x0101` 在 L3 是 CONTRACT_STATE、在 S3 是 ABI；S3 缺 `GENERIC_TRON_STORE_ALLOWLIST` 致 DYNAMIC_PROPERTIES root 包含无法表达。**一律以 L3 为准。**
3. **修正 getAsOf off-by-one 语义冲突**：L5/L6（inclusive-after，BLOCK_END=finalizeTxNum，无 +1）vs 旧 PR5/CP-2602（exclusive-before，lastTxNum+1）。各自自洽，**混用会静默读到下一个块的状态**。二选一并清除另一套措辞。
4. **修两处 Erigon 读错**：(a) eth_getBalance 块末实际 `maxTxNum(N)+2`（经 `blockNumber+1`），非 `+1`，对系统/奖励合约账户会读错；(b) Erigon 普通域 GetAsOf **history-miss 时回落 latest**，只有 Commitment 不回落——L5 读反了。java-tron 若坚持"不回落 latest"须**显式记为有意分歧**，并验证未改动存量账户仍读得出值。
5. **补 TRC10 覆盖 BLOCKER**：资产优化开启时 `AccountAssetStore` 持有从 Account proto 剥离的 TRC10 余额，但当前划 IGNORE_RAW/不进 P0 root，写入走 SnapshotRoot flush 层、绕过所有 hook——**现 hook 分类法无法捕获**。修复前不得声称历史 eth_getBalance(TRC10) 或"完整 TRON state root"正确。

**另两条须先于编码处理**

- **canonical 值编码（MAJOR）**：ACCOUNT/CONTRACT 域值 = 重序列化 protobuf（含 7 个 map 字段），Java protobuf map 按 hash 序迭代→非确定→破坏 no-op 检测 + commitment root 跨节点不可复现。必须定义 per-domain canonicalizing codec。
- **L1 构建门（MAJOR）**：4e80 之后新增 `ConfigParityGateTest`（#6803/#6810）pin 每个 (section,bean) 元组；L1 新增 `storage.archive.*` 子树但**计划从未提注册此 bean 元组 + 注释覆盖**，会自测绿却炸 `:common:test` PR 门。

**基线漂移良性**：4e80 是 HEAD 直接祖先、落后 14 commits、纯快进，计划可直接落 HEAD，**只需刷行号，无需重规划**。

---

## 2. Erigon 源码保真度裁决

整体高度健康；非 CONFIRMED 几乎都是行号漂移或文档侧前瞻断言，少数是真语义读错（已在 §0.3 独立坐实）。

| 集群 | CONFIRMED | PARTIAL | REFUTED |
|---|---|---|---|
| M1 | 3 | 1 | 0 |
| M2 | 2 | 0 | 0 |
| M3 | 3 | 1 | 0 |
| M4 | 3 | 0 | 0 |
| M5 | 3 | 0 | **1** |
| M6 | 5 | 0 | 0 |
| SynA-foundational | 7 | 0 | 0 |
| SynB-4e80 | 2 | 1 | 0 |
| CP-2602 | 3 | 0 | 0 |
| CP-4e80 | 2 | 0 | 0 |
| **合计** | **33** | **3** | **1** |

**关键更正（doc → 真 file:line，★=主控独立复核）**

| # | Doc 声明 | 实际证据 | 性质 |
|---|---|---|---|
| 1 ★ | (REFUTED, C04) 普通域 GetAsOf history-miss 不读 latest | `domain.go:1410-1422` 回落 GetLatest；`:1405-1408` 仅 Commitment 早退；`kv_interface.go:449-467` GetAsOf 含 latest、HistorySeek 不含 | 语义读反 |
| 2 ★ | (PARTIAL, C13) eth_getBalance(N)=`GetAsOf(maxTxNum(N)+1)` | 实际 `+2`：`helper.go:189 blockNumber+1`、`:197/:235 minTxNum+txnIndex+1` | 公式 off-by-one |
| 3 ★ | (PARTIAL, C10) `DomainPut(domain,k,v,txNum,prevVal)` | 真签名 `domain_shared.go:817` 含 `roTx kv.TemporalTx`（第 2 参漏） | 签名漏参 |
| 4 | (PARTIAL) computeAndCheckCommitmentV3 / Configure 文件位置 | 在 `exec3.go:746-794`（非 domain_shared.go）、`statecfg/state_schema.go:37-74`（非 aggregator.go） | 锚点漂移 |

Erigon 侧**忠实**的核心（已多 agent + 主控抽样确认）：txNum 一等公民 + MaxTxNum 映射；GetAsOf before-tx + creation/deletion marker（empty=创建=NOT_FOUND）；inverted-index `>=` seek；域 latest/history/index 三分 + Commitment 作一等域、per-block（非 batch）计算且持久化全 continuation state；SharedDomains intra-block read-your-writes 按原 per-write txNum 回放、DomainPut prevVal no-op、AccountsDomain→storage-prefix+code 删除级联。

---

## 3. java-tron 源码保真度裁决

| 集群 | CONFIRMED | PARTIAL | OUTDATED |
|---|---|---|---|
| M1 | 7 | 1 | 0 |
| M2 | 6 | 0 | 0 |
| M3 | 5 | 2 | 1 |
| M4 | 5 | 0 | 0 |
| M5 | 4 | 1 | 0 |
| M6 | 5 | 2 | 0 |
| SynB/PR/CP-2602/CP-4e80 | 28 | 0 | 0 |
| SynA-foundational | 7 | 0 | 1 |
| **合计** | **67** | **6** | **2** |

**关键更正（★=主控独立复核）**

| # | Doc 声明 | 实际证据 | 性质 |
|---|---|---|---|
| 5 ★ | (PARTIAL, c2) txTrieRoot 字段号=4（deep-dive） | `Tron.proto:505 txTrieRoot = 2`、`:513 accountStateRoot = 11`；L7 的 =2 正确 | 字段号错 |
| 6 ★ | (PARTIAL, M3) before-value 从 `getRawValue` 读 | 该方法全仓不存在；须 `getUnchecked`（`TronStoreWithRevoking.java:108`） | 杜撰方法名 |
| 7 | (PARTIAL, C05) `getDbName()==null` 污染 flushServices key | `SnapshotManager.java:155-157` 按 `Chainbase.getDbName()`(=真实库名如 'trans') keying，不走 null 路径 | 因果链证伪 |
| 8 | (OUTDATED, M3) Manager 费用/exec/retry 行号 1544-1561 | 实际 1548-1565，语义不变 | 纯行号漂移 |
| 9 | (PARTIAL, C08) ContractCapsule 解析失败仅 LOG | `ContractCapsule.java:51` logger 行被注释，catch 体为空（零日志）；仅 AccountCapsule 真打 debug | 行为细节错 |
| 10 | (PARTIAL, c6) reference.conf allowAccountStateRoot:812 | 实际 :836（行漂移）；语义/默认 0 确认 | 行号漂移 |

---

## 4. 跨文档一致性 / 命名漂移

驱动结论的是**两份同为 4e80 基线、互不 supersede 的文档族**（"S 编码包" vs "L 代码计划"）在硬契约上分叉。

| 契约 | 冲突 | 权威方 | 证据 |
|---|---|---|---|
| **物理表前缀+历史键序** (M4) `[BLOCKER]` | deep-dive `0x01-0x07+txNumDesc` vs L5 `LATEST 0x20/HIST 0x21/CS 0x22/ROOT 0x30-0x32 + 升序 txNum+1 seek`——磁盘字节+cursor 代码相反，**DB 互不可读** | **L5** | `module-04-deep-dive:78-84` vs `l5:382-393,434/464/478` |
| **域 ID 表** (M2) `[BLOCKER]` | `0x0101`=CONTRACT_STATE(L3) vs =ABI(S3)；root 按数字 ID 排序聚合、错位→proof 不可验 | **L3** | `s3:195-204` vs `l3:259-271` |
| **RawHookMode 枚举** (M2) `[BLOCKER]` | S3 缺 ALLOWLIST vs L3 含 `GENERIC_TRON_STORE_ALLOWLIST/IGNORE_RAW`；缺 ALLOWLIST→无法表达 DYNAMIC_PROPERTIES root 包含 | **L3** | `s3:267-272` vs `l3:311-317` |
| **RootPolicy 基数** (M2) `[BLOCKER]` | L3/S3 四值（含 `DOMAIN_ROOT_ONLY`）vs 06-09/file-map 三值——**元文档与它声称总结的代码计划自相矛盾** | **需裁决** | `l3:290-295`,`s3:220-224` vs `design-review:30,73`,`file-map:204` |
| **commitment 表前缀** (M6) `[BLOCKER]` | deep-dive `0x06-0x08` vs L7/s10-s11 `0x30-0x32`；patch-checklist `COMMITMENT_META=0x30` 与 L7 `ROOT_RECORD=0x30` 直接撞 | **L7** | `module-06-deep-dive:163-165` vs `l7:277,391-393` |
| **Reader Status/Reason 枚举** (M5) `[BLOCKER]` | S8/S9 `{PRESENT,MISSING}`（无 TOMBSTONE）vs L6 `{PRESENT,TOMBSTONE,MISSING}`；缺 TOMBSTONE 违反 06-09 typed-tombstone 强制 | **L6** | `s8-s9:203-205` vs `l6:410-413,444-460` |
| **codec 类名碰撞** (M3) `[MAJOR]` | S6/S7 文件名 `ArchiveStoreKeyCodec` 但类体仍 `ArchiveKeyCodec`，与 L3 域 codec 同名碰撞 | **L5** | `s6-s7:167/212/358` vs `l5:137,447` |
| **ArchivePhase 基数** (M1) `[MAJOR]` | s1-s2 三值 vs L1/L2 四值（含 `UNWIND`，但不被持久化） | **L1/L2** | `s1-s2-4e80:221-225` vs `l1:257-262` |
| **config key `enable`** `[MAJOR]` | test-plan `storage.archive.debug.enabled=false` 残留；HOCON 静默忽略未知 key | **`debug.enable`** | `test-plan:532` vs `l9:316`,`l1:133` |
| **StatePoint 模型** `[MAJOR]` | 4e80 roadmap 包列表仍有 `StatePoint.java`（06-09 禁止）；PR6 用 `ResolvedStatePoint` | **ArchiveStatePoint/ResolvedArchiveStatePoint** | `roadmap:158` vs `design-review:39,41` |
| **tombstone 挂载类** `[INFO]` | 三态语义一致；仅 design-review 把 `PRESENT/TOMBSTONE/MISSING` 误挂 `ArchiveStoredValue`（实在 L6 `ArchiveReadResult.Status`） | 澄清即可 | `design-review:31` vs `l5:138,580-584` |
| **milestone-0 config 机制错误** `[MAJOR]` ★ | milestone-0 称"无 `reference.conf`/`StorageConfig.java`、走旧手工解析路径"——两文件 4e80 即存在，且 `StorageConfig` 正是 #6615 用 `ConfigBeanFactory` **替换**手工解析后引入的；照抄会接错机制 | **L1（ConfigBeanFactory 风格）** | `milestone-0 §3.1/§4.1` vs `c977f826ba(#6615)`、`StorageConfig.java`、`reference.conf` |
| **stale 域名 + StatePoint + DOMAIN_ROOT_ONLY** `[INFO]` | `CONTRACT_CODE/CONTRACT_META/DYNAMIC_GLOBAL`、`StatePoint`、root-policy `DOMAIN_ROOT_ONLY` 仅存于 05-20/06-01/06-02 基础层 | 登记 forbidden 防 copy-paste | `module-02:96,206,208`、`roadmap:214`、`matrix:280` |

---

## 5. 状态覆盖完整性（25-DB）

L3 要求每个已知 dbName 显式分类。比对暴露：

| 缺口 | 严重度 | 实质 | 证据 |
|---|---|---|---|
| **AccountAssetStore TRC10 余额** ★ | **BLOCKER** | 资产优化开启时 `SnapshotRoot.put` 把 TRC10 单独写 account-asset 并 `clearAsset()` 从持久化 Account proto 移除→ACCOUNT 域采到的值零 TRC10；account-asset 被划 IGNORE_RAW/不进 P0 root，且 `AccountAssetStore extends TronDatabase`（无 revoking、写在 flush 层）——**现 hook 分类法无类目可捕获**。所有资产优化账户历史 eth_getBalance(TRC10)/"完整 root"皆错 | `SnapshotRoot.java:46/68-90/124-146`、`AccountAssetStore.java:18`、`AccountCapsule.java:821`、`l3 §11:458` |
| **ContractStateStore EnergyFactor** | **MAJOR** | `Program.java:2371-2380` 读 `getEnergyFactor()` 缩放能耗——共识级执行态，历史 eth_call 须复现；L3 划 P1+HISTORY_ONLY，未记"历史 eth_call 能耗会发散" | `Program.java:2371-2380`、`l3 §11:457` |
| **DelegationStore + RewardViStore 奖励** | **MAJOR** | reward 是 WITHDRAWREWARD 余额变更输入；reward-vi 被划普通 'excluded'，低估其为"延后执行态" | `DelegationStore.java:35-47`、`RewardViStore.java`、`l3 §16.3:791` |
| **zkProof/common-database/tmp/checkpoint 四库缺 EXCLUDED 登记** | **MINOR** | `allKnownStoreBindingsAreExplicitlyClassified` 覆盖测试会失败/静默漏；可排除但须显式登记理由 | `l3 §16.3:774-792` |
| **DYNAMIC_PROPERTIES allowlist 是静态快照** | **INFO** | 设计最强部分，但 ~25 key 静态列表 vs 每版新增 ALLOW_TVM_*/fork flag；漏入新 flag 会静默当 UNKNOWN→EXCLUDED，破坏跨 fork 历史 eth_call。建议加"新 key 失败构建"门 | `l3 §12.2:534-562` |

---

## 6. 设计自洽风险登记册

| 严重度 | 风险 | 要点 | 证据 |
|---|---|---|---|
| **BLOCKER×7** | 跨文档硬契约不一致 | 见 §4，照不同文档建出互不兼容 DB / 不可验 root | §4 |
| **BLOCKER** | TRC10 余额不在 root/不被采集 | §5 第 1 行 | §5 |
| **MAJOR** | ACCOUNT/CONTRACT 值=重序列化 protobuf（含 7 map 字段） | map 迭代序非确定→(a) no-op 检测失效产伪 HISTORY 行；(b) commitment root 跨节点/重启不可复现、proof 失效。本分支无 accountStateRoot canonicalizer 可复用 | `AccountCapsule.java:253-254`、`Tron.proto:147-187`、`l7 §9` |
| **MAJOR** | getAsOf off-by-one 约定冲突 | L5/L6（inclusive-after）vs PR5/CP-2602（exclusive-before，lastTxNum+1）混用→静默读 N+1 块 BLOCK_PREPARE 态；06-09 只标了 StatePoint 双模型、未标此语义翻转 | `l5 §16-17`、`l6 §5.1:340` |
| **MAJOR** | eth_getBalance 块末 txNum=+2 而非 +1 ★ | 须复现 Erigon `blockNumber+1` 路径；对 N+1 init syscall 触及的系统/奖励合约账户，naive +1 读 stale | `helper.go:189,235`、`history_reader_v3.go:153` |
| **MAJOR** | Erigon 普通域 GetAsOf latest-fallback 读反 ★ | L5 推理建立在错误前提；java-tron"不回落"须改记有意分歧并验存量账户 | `domain.go:1405-1422` |
| **MINOR** | commitBlock 在 canonical commit 后抛异常无 handler | hook 在 try/catch 外，异常裸穿 pushBlock（带 reorg/khaos 副作用），与 fail-fast 意图不符 | `l2 §7.2:466-481`、`Manager.java:1382-1392` |
| **MINOR** | unwind 扫描行上限可能在维护块误触 REPAIR_REQUIRED | 维护块所有 finalize 写共享一个 txNum、witness 重计票产生大 changeset；固定 config 上限会误判腐败、阻塞合法 reorg。须从块自身 write-stats 派生上限 | `l5 §19:881-885`、`MaintenanceManager.java:57-89` |
| **INFO（确认无误）** | intra-block 可见性 / before-value 来源 | L4 从 live revoking view `getUnchecked` 读 before；L5 用 `latestOverlay`（DomainWriteKey 非 raw byte[]）——A→B→A 正确 | `l4 §6.1,§15`、`l5 §15` |
| **INFO（确认无误）** | block-finalize 单 txNum 折叠 | 块末 root/getAsOf 涵盖全部系统写，忠于 Erigon block-boundary system txNum；仅无法在 payReward 与 maintenance 间出中间 root（设计未声称） | `Manager.java:1901-1935`、`l2 §8.1-8.3` |
| **INFO（确认无误）** | L4 retry-checkpoint 论证略夸大 | 首次 OUT_OF_TIME VM 尝试根本不到 Store hook（VMActuator 只在成功路径 commit）；承重的是保住 pre-exec 资源写而非 rollback | `Manager.java:1548-1565`、`VMActuator.java:234-250` |

---

## 7. Erigon 保真度评估

| 类别 | 项 |
|---|---|
| **忠实** | txNum 一等公民+MaxTxNum 映射（min=maxTxNum(N-1)+1）；GetAsOf before-tx + history before-value + 创建/删除 marker；inverted-index `>=` seek + exact-prefix guard；域三分 + Commitment 一等域、per-block 计算且持久化全 continuation state、hashedKey 排序；SharedDomains intra-block read-your-writes 按原 per-write txNum 回放、DomainPut no-op、AccountsDomain 删除级联 |
| **误解→必修** | (a) eth_getBalance 块末=+1（真 +2）★；(b) 普通域 GetAsOf history-miss 不读 latest（真：读，仅 Commitment 不读）★ |
| **有理分歧（须显式记录）** | java-tron archive reader "no latest fallback" 严格策略（须验未改动存量账户仍解析持久值，按 Erigon by-construction 二者本应相等）；P0 延后 delete-cascade 改 explicit-tombstone；0x00 tombstone codec vs Erigon zero-length marker；三行 commitment 表 vs Erigon 单 CommitmentDomain-by-prefix；显式存 min+max txNum |
| **危险命名** | `getAfterTx` P0 别名无 Erigon 对应符号且 before/after 朝向相反——须显式标 before-tx 朝向，文档化 TX_AFTER(N)=GetAsOf(N+1) |

---

## 8. 落地就绪度 & 基线漂移

**基线漂移良性**：`4e80f8ffa9a2` 是 HEAD `3a9ccfe48c` 直接祖先（ancestor=YES），落后 14 commits、纯线性快进。计划可直接落 HEAD，**只需刷行号**。干净基线：grep `ArchiveService/storage.archive` 全仓零命中。

| 项 | 严重度 | 状态 |
|---|---|---|
| **NEW `ConfigParityGateTest`（#6803/#6810）** ★ | **MAJOR** | pin 每 (section,bean) 元组，drift 即 PR 失败。L1 新增 `storage.archive.*` + `StorageConfig.ArchiveConfig` bean 正是它所管，但 L1 计划（§4/§7）**从未提注册此 bean 元组**、也未提 comment-coverage（#6834）→L1 会自测绿但炸 `:common:test`。修：L1 加注册任务 + 每 key 注释 + `:common:test --tests '*ConfigParityGateTest'` 入验收门 |
| **L1/L3/L4/L5/L7/L9 核心锚点精确** | **INFO** | 这些文件自 4e80 起 0 提交、字节相同：`getDbName()@78`、RepositoryImpl commit fan-out（saveCode@638/commit@766/commitAccountCache@997）、ChainBaseManager store 字段、VMActuator commit@250/260 全精确，可直接按计划行号编码 |
| **Manager.java +60 行（#6833/#6819）** | **MINOR** | L2 承重锚点存活：normal-path 块在 1381-1392、eraseBlock@1034/fastPop@1041 精确、commitBlock-before-blockTrigger 顺序仍成立；processBlock 阶段 +4。作者应瞄一眼 #6833 新 rollback-trigger 确认 archive commitBlock 仍先于触发发射 |
| **JsonRpcApiUtil +85 行（#6842）** | **MINOR** | L6/L8 最大漂移（~+50）：isBlockTag 568→618、parseBlockTag 583→633。逻辑完好（latest→head, finalized→solid, 拒 pending/safe）。编码前按方法名 re-grep |
| **JsonrpcServiceTest +130 行** | **MINOR** | L6 须保留的"拒非-latest getter"断言（524-590）churn 最重、最可能 stale，编辑前按方法名重定位 |
| **L1→L9 时序自洽** | **INFO** | 依赖链无前向引用，每片有完整 Java 接口签名/包路径/allowed-forbidden 文件列表/状态机表/测试矩阵/gradle 验收命令，无片过于含糊 |

**并发注记**（PR8/PR9 自标）：`VMConfig` 全局 static、`ConfigLoader.load(historicalView)` 改全局→历史 eth_call 可能腐蚀并发 latest 执行；缓解=共享 VM config 锁+restore，per-execution VmRules 列 future work。编码前须确认 VMConfig 确为全局 static。

---

## 9. 优先级行动清单

### P0 — 冻结契约 + 修语义读错（不做完不得编码）

1. **冻结 M4 物理布局为 L5**（0x20/0x21/0x22+0x30-0x32，升序 txNum，`historySeekAfterKey=historyKey(txNum+1)`）；给 `module-04-deep-dive` 打 supersession banner。
2. **冻结 M2 域 ID 表为 L3**（CONTRACT_STATE=0x0101, ABI=0x0102…），改 S3 枚举体；加 (id,name) 回归测试 pin。
3. **统一 RawHookMode=L3**（含 GENERIC_TRON_STORE_ALLOWLIST/IGNORE_RAW），**裁决 RootPolicy 基数**（保 4 值改元文档，或删 DOMAIN_ROOT_ONLY 改 L3/S3）。
4. **冻结 M6 commitment 前缀=L7（0x30-0x32）**，banner deep-dive 0x06-0x08；改 module-06-dive `txTrieRoot=2`。
5. **统一 reader 枚举=L6**（S8/S9 加 TOMBSTONE 入 Status，采 L6 Reason 名）。
6. **二选一 getAsOf 约定**（建议 L5/L6 inclusive-after，BLOCK_END=finalizeTxNum，无 caller +1）；PR5/PR9/CP-2602 显式弃用 exclusive+lastTxNum+1；06-09 doc 补"getAsOf 是 inclusive-AFTER，永不与 lastTxNum+1 同用"；加 reader 测试断言 eth_getBalance(N)=块 N post-finalize 且 ≠ N+1 prepare。
7. **修两处 Erigon 读错**：StatePoint 块末解析改 `txNum=minTxNum(N+1)+1=maxTxNum(N)+2`（复现 `blockNumber+1`，限 archive reader factory）；L5 文本改正"普通域 GetAsOf 回落 latest，仅 Commitment 不回落"，java-tron 不回落策略改记有意分歧并验存量账户。
8. **解决 TRC10 覆盖 BLOCKER**：把 account-asset 作 ACCOUNT 域 SEMANTIC_BACKING（如 storage-row 之于 CONTRACT_STORAGE），或新增 ACCOUNT_ASSET 进 P0 IN_GLOBAL_ROOT+FULL_HISTORY；为 SnapshotRoot/flush 层资产剥离加新 hook 类目。修复前不得声称历史 eth_getBalance(TRC10)/完整 root 正确。
9. **定义 canonical 值编码**：no-op 检测与 root hash 走 per-domain canonicalizing codec（按 key 排序 map 条目重编码），绝不用 raw `toByteArray()`；测试：shuffled map 插入序断言相同 canonical bytes 与相同 root（覆盖 ACCOUNT/CONTRACT）。

### P1 — 构建门 + 命名收敛 + 覆盖补全

10. **L1 加 ConfigParityGateTest 注册任务** + 每新 key 加 reference.conf 注释 + 入 `:common:test` 验收门。
11. **统一 config key**：test-plan:532 改 `storage.archive.debug.enable=false`；pre-impl 清单加 grep `.enabled` 抓残留。
12. **StatePoint 模型收敛**：roadmap:158 `StatePoint.java`→`ArchiveStatePoint.java`；确认全仓 `ResolvedArchiveStatePoint`。
13. **修 codec 名碰撞**：S6/S7 body（212/358/361/863）改 `ArchiveStoreKeyCodec`，`ArchiveKeyCodec` 仅留 L3 域 codec。
14. **统一 ArchivePhase=4 值**（加 UNWIND 或 banner s1-s2-4e80），文档化 UNWIND 不被 TxNumMetaCodec 持久化。
15. **覆盖补全**：contract-state 在 PoC-v2 提升为采集域或显式记"历史 eth_call 能耗会发散"；reward-vi 改 'deferred execution-state'；§16.3 补四库排除理由。
16. **DYNAMIC_PROPERTIES allowlist 加 key 级 enforcement 测试**（新 ALLOW_TVM_*/fork getter 未分类即失败构建）。

### P2 — 健壮性 + 锚点刷新 + 文档卫生

17. **commitBlock 失败转确定性 fail-stop**（自有 try，log fatal + 持久 REPAIR_REQUIRED + halt），不裸穿 pushBlock。
18. **unwind 行上限从块自身 write-stats 派生**（非固定 config），加维护块大 changeset unwind 测试断言无误触 REPAIR_REQUIRED。
19. **编码前刷锚点**：JsonRpcApiUtil/Wallet/Manager/JsonrpcServiceTest 按方法名 re-grep（勿信 cited 行号）；L1-L9 header 基线 4e80→3a9ccfe48c。
20. **文档卫生**：05-21 module-02 顶加"superseded names"提示；澄清 tombstone 枚举挂 L6 `ArchiveReadResult.Status`；登记 stale 域名为 forbidden。

---

## 附录 A. 逐簇核实计票

| 簇 | 文档数 | Erigon (C/P/R) | java-tron (C/P/O) | 簇内问题数 |
|---|---|---|---|---|
| M1-txnum-index | 6 | 3/1/0 | 7/1/0 | 2 |
| M2-domain-registry | 6 | 2/0/0 | 6/0/0 | 0（一致性问题入 §4） |
| M3-write-collector | 6 | 3/1/0 | 5/2/1 | 4 |
| M4-temporal-store | 6 | 3/0/0 | 5/0/0 | 0（一致性 BLOCKER 入 §4） |
| M5-state-reader | 6 | 3/0/**1** | 4/1/0 | 2 |
| M6-commitment-builder | 6 | 5/0/0 | 5/2/0 | 2 |
| SynA-foundational | 6 | 7/0/0 | 7/0/1 | 1 REFUTED(C1) + 3 STALE 命名（补跑完成，见附录 C） |
| SynB-4e80-boards | 9 | 2/1/0 | 5/0/0 | 1 |
| PR-specs | 8 | — | 全 CONFIRMED | 0 |
| CP-2602-packets | 10 | 3/0/0 | 全 CONFIRMED | 0 |
| CP-4e80-packets | 11 | 2/0/0 | 全 CONFIRMED | 0 |

## 附录 B. 方法学局限与已知缺口

1. **部分 verifier agent 未能读到 Erigon 仓库**（沙箱/路径），导致合成报告页脚出现"Erigon 未复核"的过保守措辞。**已由主控独立复核纠正**（§0.3）：Erigon 仓库存在，全部承重 Erigon 结论（C04/C13/C10 + GetAsOf/DomainPut 锚点）已第一手坐实。
2. **`SynA-foundational` 簇早期 extract agent 返回占位 stub**（"Test minimal payload"），未真正核实该 6 份基础/推导文档。**已重启背景 agent `a46512035343502c3` 补跑完成**（见附录 C）：Erigon 基础全过、java-tron 锚点 CONFIRMED（含行漂移）、新增 1 个 REFUTED（milestone-0 config 机制错误，已入 §4）。SynA 全为低权威推导材料，**不改变 §1-§9 任何承重判断**。
3. **断言抽样上限**：每簇 extract cap ~18 条最重要断言，非全量；低权威早期文档的细枝末节未逐条核实（已被权威层 supersede 的内容价值低）。

## 附录 C. SynA-foundational 补跑结果（已完成）

背景 agent `a46512035343502c3` 已补跑 6 份基础/推导文档（20260520 基础研究 / 20260601 erigon-synthesis-roadmap / 20260601 milestone-0-source-map / 20260602 blueprint / end-to-end-matrix / module-by-module-map）。

**计票**：Erigon 7 CONFIRMED（E1-E7 全过）；java-tron 7 CONFIRMED + 1 OUTDATED(J2，module-by-module map 三处行号错、milestone-0 的对)（J8 语义对、proto accountStateRoot 行 off-by-1）；一致性 1 REFUTED(C1) + 1 CONFIRMED(C2)；命名 3 项 STALE(D1-D3)。

**关键结论**

1. **Erigon 基础完全忠实**——txNum 一等公民、域模型、GetAsOf before-tx、commitment-as-domain 全部 CONFIRMED（`tables.go:81-83`、`kv_interface.go:449-453`、`domain.go:1405-1422`、`rw_v3.go:389`、`exec3.go:747`）。**特别地：基础文档 20260520:178 自己就写了"latest value fallback"**——即基础层与"普通域 GetAsOf 回落 latest"的正确事实**一致**；§2 的 C04 读反**只是 L5/M5 的局部错误，不是基础层错误**。adversarial grep "never fallback" = 0 命中。

2. **🔴 新硬错误 C1（REFUTED，HIGH，主控已独立坐实 ★）**：`milestone-0-source-map`（20260601）称"源码无 `reference.conf` / 没有 `StorageConfig.java`"并指示把 archive config 加到旧的手工解析 `Storage`/`Args` 路径。**两文件均存在且在 4e80 基线即存在**；`StorageConfig.java` 由 `c977f826ba`="refactor(config): replace manual parsing with **ConfigBeanFactory** binding (#6615)" 引入，**是 4e80 的祖先**。即：milestone-0 指向的正是被 #6615 **刻意移除**的手工解析机制。照其 §3.1/§4.1 config-wiring 逐字实现会接错机制。**缓解**：权威层 L1 已用 `storage.archive.enable`+`isEnable()` 的 ConfigBeanFactory 风格（与现有 config 一致），实现以 L1 为准、**勿抄 milestone-0 的 config 章节**。
   - 主控独立证据：`common/src/main/resources/reference.conf` 与 `common/.../config/args/StorageConfig.java` 均存在；`git cat-file -e 4e80f8ffa9a2:<两文件>` 均 EXISTS；`c977f826ba` 是 4e80 祖先。

3. **STALE 命名（forbidden-for-copy-paste，仅存于 05-20/06-01/06-02）**：域名 `CONTRACT_CODE→CODE`、`CONTRACT_META→CONTRACT`、`DYNAMIC_GLOBAL→DYNAMIC_PROPERTIES`；类型 `StatePoint→ArchiveStatePoint`；root-policy `DOMAIN_ROOT_ONLY`（非权威，权威是 `EXCLUDED`）。`storage.archive.enable` 已与权威一致、无冲突。

4. **行号漂移（LOW）**：module-by-module map 的 TransactionRet/BalanceTrace/AccountTrace 行号错（milestone-0 的对）；Manager.java 漂 ~14-20 行；语义全 intact。编码前按方法名 re-grep。

**SynA 对承重判断的影响**：零。SynA 全为低权威推导材料，其唯一硬错误 C1 已被权威层 L1 规避。新增价值是把 §4 命名漂移登记册补全（milestone-0 config 机制错误 + foundational 域名/StatePoint/DOMAIN_ROOT_ONLY 来源定位）。

---
*本报告核实完成。所有承重结论（含 SynA 的 C1）均由主控第一手独立复核坐实。workflow `wcbxq86hx`（11 簇）+ SynA 补跑 `a46512035343502c3` 全部回填完毕。*
