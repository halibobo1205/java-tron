# java-tron Archive 设计文档复审与调整记录

日期：2026-06-09

范围：

- `docs/plans` 下 java-tron archive/state-root 相关设计文档。
- 当前权威层：`20260604-*4e80*` 总装/看板/测试/逐文件矩阵，以及 `20260604/20260605-java-tron-archive-l1..l9-*code-plan.md`。
- 早期 `202605*`、`20260601*`、`20260602*` 文档按背景和推导材料处理；若与 4e80/L1-L9 冲突，以 4e80/L1-L9 为准。
- 本地 java-tron 源码只做抽样复核，不改代码。

## 1. 复审结论

总体设计方向不用推倒重写：

- P0 仍是 non-consensus archive sidecar。
- archive 默认关闭，不能改变当前 java-tron 行为。
- historical `eth_getBalance`、`eth_getCode`、`eth_getStorageAt`、`eth_call` 必须走 archive reader/executor，不 silent fallback latest。
- archive root/proof/debug 是 sidecar 语义，不写 `BlockHeader.raw.accountStateRoot`，不伪装 Ethereum `eth_getProof`。
- `feat/state-trie-4.8.1` 只作为区块级 MPT 实现参考，不能替代交易级 txNum/temporal changeset/root 方案。

需要调整的是若干“落地时会分叉”的细节口径，已在当前权威文档中做了小范围修正。

## 2. 已修正文档口径

| 调整点 | 结论 | 已更新文档 |
| --- | --- | --- |
| archive config 字段名 | 统一使用 `storage.archive.enable`，不是 `enabled`。理由是 L1 已明确采用 java-tron 现有 `enable` 风格，代码 getter 对应 `isEnable()`。 | `20260604-java-tron-archive-4e80-test-verification-plan.md`、`20260604-java-tron-archive-4e80-landing-readiness-board.md`、`20260605-java-tron-state-root-branches-reference-analysis.md`、`20260605-java-tron-archive-l5-temporal-store-4e80-code-plan.md` |
| `CODE` domain key | P0 固定为 21-byte contract address，不再保留 “address or code hash” 摆动。抽样复核本地 `RepositoryImpl.saveCode(address, code)` 和 `commitCodeCache`，当前 `CodeStore` key 就是 address。 | `20260605-java-tron-archive-l3-domain-registry-4e80-code-plan.md` |
| root policy 命名 | 口径统一为 **4 值** `IN_GLOBAL_ROOT / DOMAIN_ROOT_ONLY / HISTORY_ONLY / EXCLUDED`（2026-06-26 决策 1：保 4 值；`DOMAIN_ROOT_ONLY` = 算 domainRoot 但暂不并入 globalRoot，用于逐域 shadow-then-promote）；`DYNAMIC_PROPERTIES` 还需要 key-level policy；旧的 `NOT_ROOTED` 说法过粗。 | `20260604-java-tron-archive-4e80-file-implementation-map.md`、`00-ARCHIVE-V3-AUTHORITY-AND-DECISIONS.md` §3 |
| tombstone 语义 | L5 应保留 typed tombstone：`ArchiveStoredValue = PRESENT / TOMBSTONE / MISSING`。L6 JSON-RPC state getter 可以把 tombstone 按 missing/zero 渲染，但 proof/debug 仍可看见 tombstone 状态。 | `20260605-java-tron-archive-l5-temporal-store-4e80-code-plan.md`、`20260605-java-tron-archive-l6-state-reader-4e80-code-plan.md` |

## 3. 仍需实现前确认

1. 本地 java-tron 基线已漂移。

   当前抽样时 `/Users/boson/IdeaProjects/java-tron` 在 `fix/jsonformat-parser-robustness`，HEAD 为 `c9a99d3b216c`，不是文档中的 `4e80f8ffa9a2`。实现前必须跑 L0 baseline guard；如果不是 4e80，应刷新源码行号和局部锚点。

2. `StatePoint` 旧名与 `ArchiveStatePoint` 新名要避免双实现。

   早期文档大量使用 `StatePoint`。4e80/L6/L8/L9 已收敛到 `ArchiveStatePoint` / `ResolvedArchiveStatePoint`。编码时不要同时落两个平行模型；旧 `StatePoint` 只表示概念背景。

3. `DYNAMIC_PROPERTIES` allowlist 要从 `ConfigLoader` 和 VM dynamic reads 反推，不能只靠手写猜测。

   L3 已要求 key-level policy 和 checksum。实现前建议用 `ConfigLoader`、`DynamicPropertiesStore` getter、historical VM 所需配置列出最小 allowlist，并把 allowlist 变更纳入 registry checksum。

4. L7 rebuild 从 LATEST 扫描时必须跳过 tombstone。

   L5 现在保留 latest tombstone row 以便区分 deleted/never existed。L7 root normalizer 应把 tombstone 当成 absent/delete，不把 tombstone leaf 纳入 sidecar state root。

## 4. 模块级复审

| 模块 | 结论 | 需要注意 |
| --- | --- | --- |
| L1 config/no-op/dbName | 设计可落地 | `ArchiveServiceFactory` 在只有 L1 时拒绝 `enable=true` 是合理的，避免“开启但实际 no-op”。 |
| L2 Manager lifecycle + txNum | 设计可落地 | archive commit 晚于 canonical commit，这意味着 archive apply 失败后只能 fail-fast，不能假装继续服务 historical read。 |
| L3 ArchiveDomainRegistry | 已收口 | `CODE` key 固定 address；`DYNAMIC_PROPERTIES` 必须 key-level policy；unknown dbName 不能自动进入 root。 |
| L4 WriteCollector | 设计可落地 | 可以借鉴产块 per-tx nested session 做 tx write-set checkpoint，但 push/apply 路径不能跳过失败交易；retry checkpoint 只回滚 VM attempt 写集，不能清掉 retry 前 bandwidth/memo/resource 写。 |
| L5 ArchiveTemporalStore | 已调整 | 使用 typed stored value；latest tombstone row 保留，root/rebuild 层负责当作 absent。 |
| L6 ArchiveStateReader | 已调整 | reader 可保留 tombstone status，JSON-RPC adapter 再映射为 missing/zero。historical storage 仍必须先读 historical `CONTRACT` version。 |
| L7 CommitmentBuilder | 设计可落地 | 不写 header root；root normalizer 必须消费 L3 policy，并跳过 tombstone latest rows。 |
| L8 historical `eth_call` | 设计可落地 | 风险主要在 VM static config scope 和 hidden latest dynamic properties read。 |
| L9 proof/debug API | 设计可落地 | proof 对 missing/tombstone 返回 absence proof；不要新增 `eth_getProof` 或打开 `debug_traceCall`。 |

## 5. 后续执行建议

先按 L1-L5 落最小闭环：config/no-op、txNum、domain registry、write collector、temporal store。完成后用 L6 的 three historical getters 验证“不 fallback latest”。L7-L9 可以继续保留为后续 root/proof/debug 能力，不要阻塞 P0 historical state read 的主线。

实现前最后检查：

- `storage.archive.enable` 在所有新增配置、测试、文档中一致。
- `ACCOUNT/CONTRACT/CODE/CONTRACT_STORAGE/DYNAMIC_PROPERTIES` 是 P0 domain 名称；旧 `CONTRACT_CODE` 只当历史别名。
- `ArchiveStatePoint` 是唯一对外历史状态点模型。
- tombstone、missing、present empty bytes 三者在 L5/L6/L7/L9 的转换规则明确。
- 当前 java-tron 分支和文档基线一致，或文档行号已刷新。
