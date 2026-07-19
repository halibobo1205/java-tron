# feat/archive-node 多维代码评审报告

- 日期：2026-07-19
- 评审对象：`feat/archive-node`（基于 develop；154 commits、425 文件、+135K 行：
  生产 ~2.95 万行 / 测试 ~2.7 万行 / 设计文档 117 篇）
- 方法：5 路并行深审（捕获热路径 / 时序存储与编码 / DB·发布·恢复 / 查询路径 / 并发原语与编排）
  + 集成点与共识关键文件独立复核 + **x86_64 私链实测**（见 E2E-REPORT.md）
- 功能：Erigon-v3 风格归档 sidecar——per-tx 状态捕获 → solidified 边界发布到单一
  UNIFIED_V1 RocksDB（schema 6，多 CF，单原子 WriteBatch）→ 历史 JSON-RPC
  （eth_getBalance/getCode/getStorageAt/历史 constant-call）→ fail-stop + journal 崩溃恢复。

## 总评

**工程完成度极高。** 全部深审未发现 blocker，未发现导致数据丢失/损坏或错误历史结果的正确性缺陷。
评级：架构与正确性 A；并发 A；性能 B+（有明确可调项）；代码优雅/整洁 B−（God-class 与文档膨胀）。

已被证实的关键安全属性：
1. **禁用即零开销**：`NoopArchiveService` 空实现；共识共享文件中的钩子全部由
   `isCapturingCurrentTx()` / `assetV2ChangeTrackingComplete` 门控，关闭时 inert。
2. **历史查询绝不回落 live**：temporal miss + 全覆盖 ⇒ MISSING ⇒ 正确历史零值；
   中段覆盖 fail-closed。唯一 live 咨询（block tag 解析、canonical hash 对照）在
   `QueryContextHolder.suspend()` 下且受 epoch 保护。
3. **发布原子性**：单 WriteBatch（index/temporal/payload/journal/marker）+ `setSync(true)`；
   preflight 只读；崩溃只能暴露"全发布"或"全日志"。
4. **并发无死锁/无丢唤醒/无租约泄漏**：锁序全局一致
   （mutationBarrier → publicationLock → consistencyLock → backlogMonitor）；
   watchdog generation 协议保证 fail-stop 恰好一次；所有等待 while 守卫 + deadline 有界。
5. **崩溃安全推导严谨**：publishable = min(solidified, latestHeader − revoking − pendingFlush)。
6. **身份绑定**：UUID + 不可变字段 + schema 校验和状态机防库错配；损坏一律 fail-stop。

## 问题清单（按优先级）

| # | 维度 | 严重度 | 位置 | 问题与建议 |
|---|---|---|---|---|
| 1 | 性能 | 主要 | `UnifiedArchiveDb.java:75` | `MAX_OPEN_FILES=512` 对归档随机读偏低（9 CF、海量 SST，table-cache 抖动）。改 -1 或大幅调高；一行修复 |
| 2 | 品质 | 主要 | `DefaultArchiveService.java`（3702 行 / 206 方法 / 6 并发域） | God-class。建议拆分：ResourceAccountant（~600 行计数/背压）、InFlightBuffer、StartupReconciler（~400 行）、ReaderOpener（~600 行）、FatalCoordinator、PublicationCoordinator，主类收敛为薄编排 |
| 3 | 品质/潜在正确性 | 主要 | `DefaultArchiveService.java:2743-3015` | `openResolvedReader` 单方法 ~270 行、6 资源顺序获取、5 个手写 transfer 布尔 + 40 行条件 finally。当前无泄漏，但极易在后续修改中回归；改统一退栈 |
| 4 | 并发/健壮性 | 主要（已缓解） | `ArchiveFatalController.java:160,192` | 最后手段 `Runtime.halt(70)` 被 gate 在外部 handler 安装上；未装 handler 则 fatal 永不 halt。现靠 `Manager.init()` 首行安装兜底。建议 watchdog 在 `failure != null` 时无条件 halt |
| 5 | 性能 | 主要（需评估） | `ArchiveCaptureEngine.java:347-350` | ACCOUNT/CONTRACT 每写在共识线程做完整 proto parse+strip+确定性重序列化（约 2 轮/账户写）。可评估捕获期 raw 比较 + 发布期 canonical 化；需顾及 L4 no-op 检测语义，先基准再改 |
| 6 | 整洁 | 主要 | `docs/archiveV3/`（117 篇 / 7 万行） | 大多为研发过程产物（round-N 清单、coding-packet、deep-dive）。建议只保留权威契约/架构/配置/E2E 结果，其余移出主仓 |
| 7 | 品质 | 次要（双审证实） | `ArchiveRepositoryAdapter.java:285-287` | `deleteContract` 连调 3 次 `reserveOverlay(address)`，超收 VM overlay 预算约 3× |
| 8 | 健壮性 | 次要 | `ArchiveInFlightCodec.java:414-434` | `decodeProof` 缺 `decodeBlock` 具备的逐字段定长校验（无 OOB，fail-closed 依赖下游） |
| 9 | 措辞 | 次要 | `ArchiveTemporalIntegrityCodec` | 纯 SHA-256 完整性摘要被注释称 "authenticated"（无密钥/HMAC）；改 "integrity-checked" 防误解 |
| 10 | 重复 | 次要 | `QueryContext.java:154-306`；Unified vs InMemory 比较器 | `record*/consume*` 双份等价 API；两 store 的 compareRecords/validateRecordInRange 字节级重复（文档要求两者观测等价，重复即漂移风险） |
| 11 | 品质 | 次要 | `ArchivePublisherConfig`（6 构造器）、`DefaultArchiveService`（~12 构造器） | 望远镜构造器，改 builder/参数对象 |
| 12 | nit 若干 | — | — | 死代码（`ArchiveMetrics.safely`、未用 codec 方法、`CURSOR_KEY`、`signal()`）；字节偏移魔数；`getBalance` 硬编码 18L；`commit()` contractStates 缺 null 检查；watchdog 每次 arm 的字符串拼接可延迟到超时路径；`hasArchiveData()` 只读却走写锁；`publishSync` 参数恒 true；`SnapshotManager.add()` 改动需回归确认；config 默认 `publisher.async=false` 与文档"默认异步"不一致 |

注：`" ALLOW_SAME_TOKEN_NAME"` 前导空格为正确行为（匹配真实落盘键），非缺陷。

## 各维度小结

- **功能/正确性（A）**：per-tx 边界 try/finally 完备；`updateFork` 等设计风险点已正确纳入
  BLOCK_FINALIZE；as-of 为严格 inclusive-after；三态 tombstone 全链路保持；恢复对
  空库/genesis/超前/孤儿/重复逐一确定性处理。需持续盯防点：
  `AccountStore.captureAccountAssetTransitions`（3 种 TRC10 采集分支，归并连接依赖
  "物理扫描升序 == TreeSet 字符串序"这一隐式不变量）建议补针对性测试。
- **性能（B+）**：查询侧所有上限强制且校验有限、fillCache=false、慢客户端只占 servlet 线程、
  发布器水位有界。待办集中在 #1（MAX_OPEN_FILES）与 #5（捕获期 canonical 化），
  另有少量 per-op 分配（getAsOf 前缀重建 ~6 次 concat、账户 proto 重复 parse 3-4 次）。
- **并发（A）**：见总评第 4 条；`ArchiveMutationBarrier`（公平 RW 锁 + epoch +
  消费快照后重校验）是全分支最优雅的构件。
- **优雅/整洁（B−）**：核心问题是 #2/#3/#6/#11；测试组织良好
  （71 文件 ~2.7 万行，oracle/差分/崩溃窗口/切链/损坏矩阵齐备，测试:生产 ≈ 0.9:1）。

## 实测验证

x86_64 + JDK21 + RocksDB 5.15.10 私链全流程实测通过（21 oracle × 4 轮重放 + SST 损坏
fail-stop），证实 arm64 门控为保守限制而非技术边界，且 5.15/9.7 运行期能力探测降级真实有效。
详见 `E2E-REPORT.md`。

## 审查限制

静态评审 + 单机 E2E；未跑该分支自带测试套件（本环境 Gradle/平台限制，仅构建了主 jar）；
未做网络同步、长稳与容量压测。上线前仍需真机（arm64/Java17）soak 与大网同步验证。
