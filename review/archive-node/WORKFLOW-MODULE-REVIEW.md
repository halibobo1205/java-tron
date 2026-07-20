# feat/archive-node 深度逐模块 · 对抗式复核报告（Workflow）

- 方法：Workflow 编排 23 个 agent —— 11 个模块各一个深度审查 agent（产结构化发现）→ 各模块一个对抗 agent（逐条尝试证伪）→ 1 个综合 agent。REFUTED 的发现自动剔除。
- 输入上下文：三轮前序审查 + 对抗式 reconciliation 的全部已知结论已喂给审查 agent，只找**新**问题（全部 11 条 isNovel=true）。
- 结果：**11 模块，11 条存活，15 条被对抗证伪。0 blocker / 0 major / 1 minor / 10 nit，无正确性或并发缺陷。**
- 人工复核：唯一 minor 项（AccountStore.java:76 SHA-256）已由 Opus 亲验 `AccountStore.get():74-76`、`Sha256Hash.java:146/168/182`、`AccountCapsule.java:724/741` 属实。

---

# java-tron feat/archive-node 深度逐模块对抗式复核 — 最终报告

## 1. 总体结论

**11 条发现通过对抗式验证存活（REFUTED 已剔除）。按最终定级：0 blocker、0 major、1 minor、10 nit。** 没有任何一条是正确性或并发缺陷 —— 唯一的 minor 是一处消费级性能项，且完全受 archive 开关门控，默认/未启用节点零成本。全部 11 条均为本轮新发现（isNovel=true）。主题高度集中在两类：(a) archive 捕获/存储热路径上的性能微优化（多为 archive-gated），(b) 死代码与 fail-closed / 规范一致性瑕疵。需要如实指出：**原本 7 条 minor 中有 6 条在对抗验证中被降级为 nit**，其中 **4 条的"建议修复"被判定为 fixWouldHarm=true**（照搬会削弱既有不变量或直接破坏已有测试）。总体质量良好，无需紧急处置；真正值得做的只有一处性能微优化 + 若干零风险清理 + 两处必须在部署前落地的 schema 相关项。

## 2. 存活发现一览

按 finalSeverity 排序（blocker>major>minor>nit）。全部为新发现，故均标 ★。⚠ 标记表示建议修复有害（fixWouldHarm=true）。

| module | severity | verdict | dimension | file:line | 一句话问题 |
|---|---|---|---|---|---|
| ★ capture | **minor** | CONFIRMED | performance | AccountStore.java:76 | 捕获期每次账户 get/put 都新建 MessageDigest 做 SHA-256（每次 JCE provider 查找），一次读改写付 3 次全量哈希，在共识线程上 |
| ★ txnum-codec | nit | DOWNGRADED | performance | UnifiedArchiveTxNumIndex.java:631 | ⚠ 启动校验无条件全量遍历所有 range，并对迭代器已定位的 key 再做冗余 point-get（O(N) 启动开销） |
| ★ unified-db | nit | DOWNGRADED | performance | UnifiedArchiveDb.java:1078 | ⚠ 历史查询与异步发布共用单个 72MB 可淘汰 block cache，index/filter 块 churn 理论上增加发布端 I/O |
| ★ domain-identity | nit | DOWNGRADED | performance | DynamicKeyPolicy.java:157 | ⚠ 5 个已知 DYNAMIC_PROPERTIES key 落入 UNKNOWN→FULL_HISTORY，非可查询数据被逐块全历史捕获 |
| ★ domain-identity | nit | DOWNGRADED | quality | DefaultArchiveDomainCatalog.java:174 | catalog checksum 的 dynamic-key 段按插入序（非规范）序列化，源码重排即改变 schema 指纹 |
| ★ jsonrpc-hooks | nit | DOWNGRADED | quality | ArchiveJsonRpcStateAdapter.java:123 | ⚠ 历史 getter 不支持 EIP-1898 对象块参数，adapter 的 blockHash 重载为不可达死代码 |
| ★ temporal | nit | DOWNGRADED | quality | InMemoryArchiveTemporalStore.java:64 | InMemory oracle 缺少 Unified 强制的跨块 append-only txNum 守卫（仅畸形输入可触发，test-only） |
| ★ temporal | nit | CONFIRMED | quality | ArchiveTemporalRowValidator.java:20 | validate 在 null/空守卫前先解引用 key[0]，破坏统一 fail-closed 异常类型（当前不可达） |
| ★ temporal | nit | CONFIRMED | quality | ArchiveTemporalCodec.java:82 | 死代码 codec 助手 historyKeyOfPrefix / historySeekBefore 零调用者 |
| ★ txnum-codec | nit | CONFIRMED | quality | ArchiveBlockRangeCodec.java:47 | 死且误导的 CURSOR_KEY 常量与真实 published-cursor key 不符（潜在陷阱） |
| ★ orchestrator | nit | CONFIRMED | quality | DefaultArchiveService.java:1637 | 私有方法 latestInFlight 从未被调用（死代码） |

**去重说明**：4 条死代码项（historyKeyOfPrefix/historySeekBefore、CURSOR_KEY、latestInFlight、resolveReader 重载）分属不同文件、非重复，仅主题聚类；domain-identity 两条（捕获策略完整性 vs checksum 规范化）、temporal 三条互不重叠。无真实重复项可合并。

## 3. minor 及以上详述

### 3a. 唯一 minor 项

**[minor · CONFIRMED · fixWouldHarm=false] capture — 捕获热路径逐账户 SHA-256（AccountStore.java:76）**
证据：捕获激活时 `AccountStore.get()` 对每次读都无条件调用 `enableAssetV2ChangeTracking` → `Sha256Hash.hash(true, previousValue)`（AccountCapsule:724），`put()` 写后再 rebase 一次，且 put 校验路径 `hasCompleteAssetV2ChangeTrackingFor`（:741）再哈希一次；一次读改写共 3 次全量 SHA-256，每次都走 `Sha256Hash.newDigest()`→`MessageDigest.getInstance("SHA-256")` 的 JCE provider 查找。这发生在区块 apply（共识）线程上、且账户 get/put 是每块最频繁操作，只读账户的 get 侧基线哈希完全被浪费。
**安全修复**：保留一个 ThreadLocal 复用的 MessageDigest（捕获限共识线程，ThreadLocal 安全），消除每次 provider 查找，字节级语义不变——这是发现自身首推的最小修复。更激进方案（保留字节引用 + `Arrays.equals`，或延迟到首次 `markAssetV2Changed` 才建基线）更具侵入性、依赖"存储返回不可变字节"的假设，不建议作首选。所有改动保持在既有 archive 门控内，disabled 节点不受影响。

### 3b. 被降级为 nit 的原 minor 项（含 fixWouldHarm 警示）

以下 6 条原定级 minor、验证后降为 nit。因 4 条的建议修复有害，此处明确标注。

**[原 minor→nit · DOWNGRADED · ⚠fixWouldHarm=true] txnum-codec · validateRangeCoverage（UnifiedArchiveTxNumIndex.java:631）**
机制属实（无 `full` 门控、对迭代器已定位 key 再 `getExact`、range append-only 故 N=链长），但这是**每进程启动一次的校验开销，不在共识/发布每块热路径上**；O(N) 是"校验 N 段连续"这一有意不变量的内在成本。两个主修复均有害：用 `iterator.value()` 替代 `getExact` 会丢掉 getExact 的 64KB 有界读与精确长度校验（正是检测磁盘损坏的路径）；把邻接遍历门控到 `full` 会漏检链中间缺段并直接破坏 `normalStartupValidationRejectsMissingMiddleRange` 测试。**仅"单次 stagePublication 内缓存 getLastRange 结果"这一微优化是安全的。**

**[原 minor→nit · DOWNGRADED · ⚠fixWouldHarm=true] unified-db · 共用 block cache（UnifiedArchiveDb.java:1078）**
标题的两个承重论断均被证伪：该 blockCache 是**每个 archive-DB 自建的 LRUCache，与主链共识 RocksDB 实例完全不共享**；且发布端 SST 读发生在**异步 BoundedArchivePublisher 守护线程，非区块生产线程**。存活的仅是"历史查询与异步发布端共用一个 72MB 可淘汰 cache"这一有意的内存上界权衡（`usesEvictableIndexAndFilterCache` 等断言已固化此配置）。建议的 cache 拆分 / pin index-filter 会破坏这些已测断言，故有害——按文档化风险对待即可。

**[原 minor→nit · DOWNGRADED · ⚠fixWouldHarm=部分true] domain-identity · 5 个 key 落入 UNKNOWN→FULL_HISTORY（DynamicKeyPolicy.java:157）**
事实链全部成立：`BLOCK_FILLED_SLOTS`、`BLOCK_FILLED_SLOTS_INDEX`、`BURN_TRX_AMOUNT`、`STORAGE_EXCHANGE_TAX_RATE`、`SET_BLACKHOLE_ACCOUNT_PERMISSION` 未枚举，落入 `unknownDecision`→FULL_HISTORY 被逐块全历史捕获（前两者每块写）。但 **UNKNOWN→保留历史是被文档化并单测固化的有意 fail-safe 契约**，5 个 key 均非执行相关，代价仅为存储、在 Erigon 式每块全量 delta 面前微不足道。**安全部分**：像 `state_flag`/`TOTAL_*` 一样为这 5 个 key 显式加 `excluded()` 条目（schema-affecting，须部署前落地）；**有害部分**：proposedFix 里"增加 build-time 完整性测试让未来新 key 失败构建"会把有意的容错网变成脆弱门，与既有契约冲突，**不要做**。

**[原 minor→nit · DOWNGRADED · fixWouldHarm=false] domain-identity · checksum 非规范排序（DefaultArchiveDomainCatalog.java:174）**
结构性差异属实：catalog 的 domain 段按 id 排序，但 dynamic-key 段按 LinkedHashMap 插入序折叠，而姊妹 `DefaultArchiveDomainRegistry` 刻意 TreeMap 排序以保证与插入序无关。但两段在运行期均确定，非真实不确定性 bug（`checksumIsDeterministicAndCoversSchema` 已证两实例相等）；真实后果仅是"改构造器顺序而不 bump 版本、且对已部署 archive"时 fail-closed 拒绝启动——属可在部署前规避的窄 footgun。**安全修复**：对 dynamic-key 段也做 TreeMap / 按 pattern 排序使其规范化（不破坏任何测试，值变更在部署前无害），建议部署前落地。

**[原 minor→nit · DOWNGRADED · ⚠fixWouldHarm=true（删除式）] jsonrpc-hooks · EIP-1898 + 死重载（ArchiveJsonRpcStateAdapter.java:123）**
"getter 与 eth_call 在 EIP-1898 上分歧"的标题框架被历史证伪：在 merge-base 处 getter 本就只收 `String`、eth_call 本就收 `Object`，此分歧是**既存上游行为、非本特性引入**，且 fail-closed（invalid-params，绝不返回错误数据）。真正存活的仅是琐碎死代码：单参 `resolveReader(String)` 完全无调用者，hash-binding 重载仅测试可达。删除式修复会破坏 `ArchiveJsonRpcStateAdapterTest:212`（除非连测试一起删）并移除 eth_call 平价所需的 hash-binding 管线；补全式（给 getter 上 EIP-1898）则改变长期存在的 string-only getter 契约。**二选一，勿留半接线状态。**

**[原 minor→nit · DOWNGRADED · fixWouldHarm=false] temporal · InMemory oracle 缺 append-only 守卫（InMemoryArchiveTemporalStore.java:64）**
裸代码差异属实（`putChange` 无 append-only 检查，`Unified.prepare` 在 line 353 强制），但"可观测 parity gap"被证伪：oracle 测试对两存储走同一喂入 helper，任何非单调输入会让 Unified 先在 353 抛异常，分歧永不表现为查询答案不同，仅是校验严格度差异（超出 oracle 目标）；且该存储 test-only、生产只发单调 txNum。**可选**的 oracle 忠实度硬化：在 `putChange` 里对已有历史的 key 拒绝 `txNum ≤ 当前最大历史 txNum`，镜像 Unified line 353。非生产修复。

> 其余 4 条为原生 nit（#3 rowvalidator null 守卫、#4/#6/#8 死代码），性质见第 4 节，均无正确性影响、修复零风险。

## 4. 净可执行清单

真正值得做的很少，分四档：

**A. 值得做（安全、零/低风险）**
- 唯一实质性能项：为捕获路径 asset-V2 tracking 引入 ThreadLocal 复用的 MessageDigest（#1 capture），消除共识线程每次 get/put 的 JCE provider 查找。语义不变、archive 门控内。
- 死代码清理：删除 `historyKeyOfPrefix`/`historySeekBefore`（#4）、删除 `CURSOR_KEY` 并把误导性测试断言改为对 `publishedCursorKey()`/`META_PREFIX`（#6）、删除私有 `latestInFlight`（#8）。
- fail-closed 一致性：在 `ArchiveTemporalRowValidator.validate` 的 switch 前加 null/空 key 守卫抛 `ArchiveException`（#3），纯附加、当前不可达。

**B. 部署前必须落地（schema-affecting，会改变 checksum）**
- 规范化 catalog checksum 的 dynamic-key 段排序（#10）。
- 为 5 个遗漏的 DYNAMIC_PROPERTIES key 补 `excluded()` 条目（#9 安全部分）——**但不要加 build-time 完整性门**。
- 两项都改动 schema 指纹，须在任何 archive 部署之前、作为 schema 版本化的一部分落地。

**C. 明确不要做（fixWouldHarm）**
- 不要用 `iterator.value()` 替换 validateRangeCoverage 的 `getExact`，也不要把邻接遍历门控到 `full`（#5）——会削弱损坏检测并破坏测试；仅可做 stagePublication 内 getLastRange 缓存。
- 不要拆分 / pin archive block cache（#7）——破坏已测内存上界不变量，属有意权衡。
- 不要给 #9 加 build-time 完整性测试；不要对 #11 做裸删除（破坏测试）。

**D. 决策项（非必须）**
- #11：二选一——要么给历史 getter 补全 EIP-1898，要么删除死重载并同删对应测试；当前半接线可暂留。
- #2：若追求 oracle 忠实度可为 InMemory 加 append-only 守卫；纯 test-only，非生产。

**一句话**：本轮无正确性/并发缺陷，实质工作量极小——一处 ThreadLocal 哈希优化 + 几处死代码删除 + 两处部署前的 schema 规范化即可收尾；其余多为验证后降级的 nit，且近半数"显而易见的修复"其实有害，切勿照搬。