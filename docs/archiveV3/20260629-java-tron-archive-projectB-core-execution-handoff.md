# 项目 B-core 执行交接包（新会话用）

日期：2026-06-29
分支：`feat/archive-node`（base `release_v4.8.2`，本会话 54 commit 全本地未 push）
worktree：`/Users/boson/IdeaProjects/java-tron/.claude/worktrees/vibrant-borg-d3617d`

## 0. 如何无缝接住（新会话开场必读）

1. **memory 自动加载**：`MEMORY.md` + `project_archive_erigon_v3_design.md` 会带入运行态上下文（L1–L8/debug-trace 全貌、Erigon 读/写模型、本项目 B 的立项要点）。
2. **读设计**：`docs/archiveV3/20260629-java-tron-archive-projectB-erigon-temporal-charter.md`（B 的完整设计、为什么、范围、迁移、验收）。
3. **读本执行包**（本文件）：精确代码锚点 + 结构陷阱 + 分片 + 验收。
4. **先复核锚点**：memory/本文的 file:line 是本会话（2026-06-29）事实，开工前用 grep/Read 复核仍在（代码可能已动）。

> 一句话目标：temporal 从「**新值 + floor(seekForPrev,`<=`)**」切到 Erigon-v3「**变更前旧值(history)+ 新值(latest)+ getAsOf 用 next-change(`>=`)+ 无变更则 fall-to-latest**」。改动受限 L4(capture)+L5(temporal)，**reader 契约不变** → L6/L8/debug-trace 业务逻辑零改动。

## 1. Erigon 模型（要实现的语义，源自用户给的读/写原理 PDF）

- **写**：键 `K` 在 txNum `T` 从 old→new：
  - `history[domain,K,T] = oldVal`（变更**前**的值，对应 Erigon `AddPrevValue`）
  - `latest[domain,K] = newVal`
  - `changeset[T,domain,K] = newVal`（txNum 顺序的受影响 key 索引；schema=5 保存
    after-value 用于 startup 校验 latest，oldVal 已在 history 行）
- **读** `getAsOf(domain,K,T)`（契约不变：返回 T 时的值）：
  - 前向 seek 第一条 `history(domain,K,C)`，`C >= T`：命中且仍属 `(domain,K)` → 返回其值（=oldVal(C)=T 时值）。
  - 无命中（K 在 T 之后无任何捕获变更）→ 返回 `latest(domain,K)`（没改过=最新值，**原生 fall-to-latest**）。
  - 命中值是 tombstone → T 之后第一次变更是"创建"→ T 时不存在 → MISSING。
- **unwind**：回退 txNum T 的变更，changeset 定位受影响 key，`latest[K]` 还原为 T
  之前的值（即 `history[K,T]` 的内容）。

## 2. 代码触点（本会话已核实的现状，file:line 为 2026-06-29）

| 文件 | 现状 | B-core 要改成 |
| --- | --- | --- |
| `chainbase/.../db/TronStoreWithRevoking.java` `put`(~96-98) | `revokingDB.put(key,value); ArchiveCaptureHolder.capturePut(getDbName(),key,value)` | 若 `isActive()`：put 前 `byte[] old=revokingDB.getUnchecked(key)`(line 113-114 有此 API)→ `capturePut(dbName,key,old,value)`；archive 关时**完全等价于现状**(只 put) |
| 同上 `delete`(~102-104) | `revokingDB.delete(key); captureDelete(getDbName(),key)` | 若 `isActive()`：delete 前读 old → `captureDelete(dbName,key,old)` |
| `chainbase/.../archive/capture/ArchiveCaptureHolder.java` | `isActive()`(~32)、`capturePut(dbName,key,value)`(~36)、`captureDelete`(~48)、`captureAccountAsset(addr,old,new)`(~61，**已是旧+新模式可借鉴**)、`captureSemanticPut(domain,key,value)`(~74)、`captureSemanticDelete`(~88) | 各方法加 oldVal 形参；新增 `capturesStore(dbName)`(可选，用于在 put 前判定是否需读 old，省非捕获域的读) |
| `chainbase/.../archive/capture/ArchiveCaptureEngine.java` | `capturePut`(~58) 构造 record 入 temporal | 用 oldVal 构造带 prevValue 的 record |
| `chainbase/.../archive/capture/ArchiveChangeRecord.java` | 字段 `(position,domain,canonicalKey,value:DomainValue)`(15-18) | 加 `prevValue`(history 用)；`value` 留作 latest(newVal) |
| `chainbase/.../archive/temporal/RocksDbArchiveTemporalStore.java` | `putChange`(~49-55) latest+history **写同一新值**；`getAsOf`(~67) `seekForPrev` | putChange: history=prevValue、latest=newValue；getAsOf: **前向 iterator.seek(historyKey(d,k,T)) + 前缀判断 + fall-to-latest**；unwind 适配 |
| `chainbase/.../archive/temporal/InMemoryArchiveTemporalStore.java` | `putChange`(~30-35) NavigableMap；`getAsOf`(~39-44) `floorEntry` | 同上：history 存 prevValue、getAsOf `ceilingEntry`(>= T) + fall-to-latest；两实现须语义一致 |
| `chainbase/.../archive/temporal/ArchiveTemporalCodec.java` | latest/history/changeset 单 CF keyspace；当前 RocksDB schema=5 的 changeset value 保存 after-value | value 布局非兼容变化必须 bump manifest schema |
| `captureSemanticPut/Delete` 调用点 | **未枚举，新会话先 grep** `captureSemanticPut\|captureSemanticDelete`（CONTRACT_STORAGE 等）| 各调用点提供 oldVal |
| `chainbase/.../archive/reader/DefaultArchiveStateReader.java` | `getRaw → temporalStore.getAsOf` | **不改**（契约不变）；getStorage 的双 version 探测(本会话已改为优先 PRESENT)保留 |

## 3. 结构陷阱（本会话摸出来的，必看）

1. **读写死耦合，不能分步**：getAsOf 前向 seek **要求** history 存旧值；旧值来自 capture。所以「capture 改存旧值」与「getAsOf 改 next-change」**必须同一提交落**，否则读写不一致、归档坏。
2. **所有捕获点原子切**：capturePut/captureDelete/captureSemanticPut 及其调用点必须**同时**提供旧值；任一域漏切 = 该域读错。**不能按域分片**。
3. **测试 harness 填充语义翻转**：现有 `DefaultArchiveStateReaderTest`、三个集成测试（`HistoricalEthCallSupportIntegrationTest`/`ArchiveStateReadIntegrationTest`/`HistoricalTraceTransactionIntegrationTest`）、temporal 单测都按「putChange 存 T 时的值 + floor 读」构造；新模型要把**填充值放到「下一次变更的 txNum」上、且存旧值**——这些 harness 的 populate 逻辑要整体重写（断言的"返回值"应不变，证明 reader 契约保持）。
4. **archive-gated 是安全底线**：所有改动用 `ArchiveCaptureHolder.isActive()` 门控；**archive 关（默认）时 TronStoreWithRevoking.put/delete 字节级等价现状**（只 put/delete）。blast radius 仅 archive-enabled 节点，默认共识链零影响——开工前后都要用「非 archive 回归」证明这点。
5. **reader 契约必须保持**：getAsOf 仍返回「T 时的值」。L6/L8/debug-trace 的**业务代码不改**（它们走 reader 类型化接口）；只有它们测试的 populate harness 要适配新模型。回归绿 = 契约保持的证明。
6. **每写多一次读**（取 old）是 Erigon 模型固有成本；用 `capturesStore(dbName)` 把 old-read 限定到被捕获的域，避免非捕获域的浪费。
7. **unwind/fork 在旧值模型下的回滚**是 consensus-adjacent lifecycle 关键，重点测（fast-pop/fork-replay/recovery 三源）。

## 4. 分片（charter 的 B1–B4，原子单元是 B1+B2）

- **B1+B2（同一提交，原子）**：ArchiveChangeRecord 加 prevValue；capture 全链（put/delete/semantic + TronStoreWithRevoking 读 old，isActive 门控）；temporal putChange(history=prev/latest=new) + getAsOf(next-change + fall-to-latest)，InMemory+RocksDB 一致。
  - 单测:getAsOf present/next-change/fall-to-latest/tombstone（两实现一致）。
- **B3**：unwind 旧值模型回滚正确 + 重启持久化（PersistentArchiveTxNumIndexTest 风格 + temporal）。
- **B4**：reader 契约回归(L6/L8/debug-trace + reader 单测，populate harness 适配后断言不变)+ mid-chain 集成(任意高度开启:覆盖期内服务、没改过查 latest、`[上次变更,N)` 回溯、`<firstArchivedBlock` 报"最低支持高度 N")+ **ultracode 对抗验证 workflow**(比照 L6/L8 verify:mid-chain 正确性 / unwind 边界 / 新旧值混淆 / 契约破坏 / archive-off 回归)。
- **B-efficiency(B5–B7)**:EliasFano 倒排 + RecSplit + ZSTD + 不可变 step 文件 + N 快照——独立后续里程碑,不阻塞 B-core 验收。

## 5. 约束（来自全局/项目 memory，新会话遵守）

- **lint**:chainbase/actuator 无 checkstyle(用 python 手检:行<=100、新行 ASCII-only、短 import);framework 跑 `:framework:checkstyleMain/checkstyleTest`。commit 前必查。
- **构建/测试**:默认 arm64 JDK 17;worktree 跑测试加 `-x generateGitProperties`(jgit 不支持 worktree)。
- **Math**:新代码用 StrictMathWrapper,不用 Math。
- **单测**:用 `assertThrows`,短 import,不内联全限定名。
- **git**:**永不 push**(用户手动);commit subject-only(无 Co-Authored-By,除非用户要);只在 `feat/archive-node` 上提交。
- **review 先中文摘要**。

## 6. 验收（DONE）

1. temporal 切到 prev-value + next-change + fall-to-latest,InMemory/RocksDB 一致 + 重启持久。
2. **L6/L8/debug-trace 业务代码不改**、全测绿(populate harness 适配后断言不变)= reader 契约保持的证明。
3. mid-chain:任意高度开启可服务覆盖期内查询 + 没改过查 latest + `[上次变更,N)` 回溯;`<firstArchivedBlock` 明确报"最低支持高度 N";残留 gap 仅"覆盖起点前未捕获变更"且有明确错误(no silent wrong)。
4. unwind/fork 回滚正确。
5. archive-off(默认)路径字节级不变(非 archive 回归绿)。
6. 对抗验证 0 blocker。

## 7. 起点状态

- 分支 `feat/archive-node`(从 `claude/vibrant-borg-d3617d` 切出,同指)。
- base `release_v4.8.2`,本会话 **54 commit 全本地未 push**(L1–L6 + L8 + debug_traceCall/traceTransaction + L6/L8 对抗验证修复 + 两份 B 文档)。
- L7 承诺树 / L9 共识 proof:用户指示放弃(或独立后续);debug trace 已基于 L8 交付。
- 之前讨论的「fall-to-latest disambiguator」在 B 下不需要(原生);「N 快照」归入 B7。

## 8. 新会话开场 prompt（给用户复制用）

> 接手 java-tron archive 项目 B-core(Erigon-v3 temporal 模型切换)。先读 `docs/archiveV3/20260629-java-tron-archive-projectB-core-execution-handoff.md` 和同目录 `...projectB-erigon-temporal-charter.md`,复核里面的 file:line 锚点仍有效,然后按 B1+B2(原子)→B3→B4 推进,全程 archive-gated、reader 契约不变、archive-off 字节不变;B4 用对抗验证 workflow。分支 feat/archive-node,worktree 测试加 `-x generateGitProperties`,永不 push。
