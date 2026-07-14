# Archive 写入与历史查询性能加固计划

> 状态：CODE COMPLETE / PERF GATED；ROUND 13 生产路径 HIGH/MEDIUM 已完成修复与聚合回归，
> 50k-block ABBA、真实故障矩阵和 72h soak 尚未执行
>
> 日期：2026-07-13
>
> 分支：feat/archive-node
>
> 范围：archive sidecar 的写入、固化发布、历史读取和运维资源控制；不改变共识状态、
> 区块头、交易执行结果或 archive-off 的数据库访问序列。

## 0. ROUND 10 对抗复审门控

实现期复审推翻了 ROUND 9 的“无剩余 HIGH/MEDIUM”结论。以下项目在本计划完成前必须清零，
否则不能把 async publisher、unified layout 或普通节点自动创建 archive 设为可用生产模式：

- archive-off Manager 钩子必须零额外 DB I/O；固化高度只复用 canonical 主流程已有读取。
- 普通节点不得把缺失挂载、空目录或错误路径初始化为新 archive；identity 必须跨进程串行化，
  init/adopt 只能由运维显式打开一次性配置，并使用持久 claim 幂等 resume。
- 正常启动不得将全量 distinct state key 装入堆；完整 scrub 与普通增量启动校验分离。
- journal acknowledgement、solidified publish 和 stale-journal 清理不得在默认区块线程形成
  多次完整 journal 解码或无界串行 fsync。
- in-flight 背压同时按 block、record、encoded bytes 和可用磁盘约束，soft watermark 必须生效。
- shutdown/drain 不能把尚未 start 的 publisher reservation 误报为 fatal；fatal signal、watchdog
  与有界 join 不得被 repair-marker 或卡死 I/O 阻塞。
- 历史查询使用有限生产默认预算；canonical provider、BLOCKHASH、返回值与 trace 重建纳入同一
  QueryContext。请求级 lease 覆盖执行与有界 response 序列化，序列化完成后在网络
  写出前释放；此时不再持有 reader、snapshot 或 RocksDB handle。
- unified publish API 必须是 typed transition，不能接受调用方任意 META/cursor key；partial init
  必须可按 nonce 幂等 resume。在这两项完成前 factory 不接入 unified。
- 50k-block ABBA benchmark、故障矩阵和 72h soak 没有真实结果前，只能声明“代码实现完成”，
  不能声明“性能验收通过”。

### 当前实现分层

- Item 1-4 已接入 LEGACY_V1 生产同步路径，并由增量差分、archive-off、RocksDB 与账户资产
  集成测试覆盖。
- Item 5 已实现有界 publisher、背压、fatal/drain 与同步恢复；`async=false` 仍是默认值，
  通过 72 小时 soak 前不提升为默认。
- Item 6 已提供隔离的 unified 多 CF 原型和原子 batch 测试，但 factory 不装配。typed publish、
  crash-resumable init、离线 migrator 和真实旧二进制验证完成前不属于生产交付。
- Item 7 的查询准入、预算、指标、路径与 identity 门控已接入生产；独立磁盘吞吐结论仍需
  目标机器上的 ABBA 与 soak 数据。JSON-RPC batch 另有总历史查询数、总墙钟、有界响应构建
  和慢客户端 retained-byte 背压。

## 1. 目标与交付原则

本计划覆盖用户指定的七项：

1. in-flight 状态从反复全量重建改为增量维护。
2. 增加有界请求缓存并复用 snapshot iterator。
3. 复用 AccountStore previous value，并优化 ACCOUNT_ASSET 捕获。
4. 复用 storage original value，减少 dirty slot 的重复读取。
5. 把 solidified publish 从 block-push 热路径迁到有界后台 worker。
6. 为新 archive 提供单 RocksDB、多 column family、单批次原子 publish。
7. 增加查询预算、指标、安全的独立磁盘路径和性能验收基线。

ROUND 1 对抗审查证明，七项优化不能作为一个大改动直接落地。现有实现还存在 storage
物理键不一致、canonical commit 失败后的 orphan journal、mid-chain live read-through
一致性等前置正确性问题。因此交付采用阶段门控：

- 先修正确性前置，再做局部热路径优化。
- async publisher 和 unified layout 始终独立提交、独立开关、独立故障注入。
- 本轮实现不自动迁移已有 archive，不把危险新模式设为默认。
- 每一阶段必须通过 differential、archive-off byte identity、crash/restart 和对抗复审，
  才能进入下一阶段。

## 2. 不可破坏的不变量

1. archive-off 不得新增 canonical Store read/write，不得改变 value bytes、调用顺序和异常语义。
2. canonical session commit 前，当前块 journal 必须以强制 sync 的写入持久化成功。
3. canonical commit 失败时，已 journaled block 必须可在当前进程回滚；进程中断时必须能由
   startup reconcile 判定并回滚，不能遗留指向 canonical head 之后的静默状态。
4. reader 只能看到 index、temporal rows 和 published cursor 同时完成的 block。
5. genesis-complete 请求的 point resolution 与 temporal read 必须来自同一 archive snapshot。
6. mid-chain 请求不得读取普通 live head；没有 temporal/in-flight 证据时返回 UNKNOWN。
   未来若提供 read-through，只能绑定独立 canonical committed view。
7. CONTRACT_STORAGE archive key 必须和 live Storage 使用的最终物理 row key 完全相同，
   包括非 version-1 截断规则和 CREATE2 namespace。
8. 后台失败在内存中立即进入 FATAL；repair marker 尽力强制 sync，但 ENOSPC 时即使 marker
   写失败也不能恢复服务。
9. unwind 只能移除 head；publish 只能移除 oldest in-flight；任何 blockNum、txNum 或 hash
   不匹配都 fail-stop。
10. legacy archive 永不自动原地迁移；迁移失败或磁盘满时源目录保持不变。
11. shutdown 不得跨线程强制 close 正在使用的 reader、snapshot 或 RocksDB native handle。
12. 所有预算超限均返回稳定的资源限制错误，不能伪装成 MISSING、live fallback 或普通 I/O 错误。

## 3. Phase 0：实现前正确性前置

### 3.1 Storage physical-key 协议（temporal manifest schema=7）

当前非 version-1 Storage.compose 只保留 slot 的低 16 字节，而 archive codec 使用完整
32-byte slot。两个高 16 字节不同、低 16 字节相同的逻辑 slot 会映射到同一 live row，
却映射到两个 archive key。这是现有码 bug，Item 4 必须在修复后实施。

修复设计：

- sidecar 不再根据 logical slot/namespace 重算写入键；Storage capture 直接取得 live capsule 的
  physical row key，historical reader 与 canonical committed read view 使用同一物理键协议。
- capture 直接使用并校验 StorageRowCapsule.getRowKey，archive canonical key 中承载物理
  row key，不再独立解释 logical slot。
- historical reader 从同一历史 state point 读取 contract metadata 后计算同一个物理键。
- 同步迁移 ArchiveStorageKeyCodec、ContractStorageKeyCodec、DefaultArchiveStateReader、
  ChainBaseArchiveReadThrough 和 DefaultArchiveDomainCatalog。physical-key read-through 接收 32-byte
  physical key并直接查询指定的 canonical committed StorageRowStore view，不能再解析 86-byte
  semantic key，也不能读取普通 Chainbase.head。
- 当前 temporal manifest 固定为 `schema=7`，domain schema checksum 同时绑定 physical-key codec。
  任何旧版或不兼容 manifest 均拒绝启动并要求从 canonical block replay 重建；旧 alias history
  可能已经歧义，本计划不承诺无损原地转换。
- 更新 archiveV3 权威决策文档，再提交生产代码。

实现处置：本轮没有改变 live `Storage.generateAddrHash` 的 legacy cache/rebind 行为，因为只在
archive 节点清 cache 会造成共识执行分叉，对所有节点修改则超出 sidecar 加固范围。capture
改为直接使用 canonical `StorageRowCapsule.rowKey`；因此 alias、CREATE2/redeploy 或 child copy
即使沿用旧 namespace/cache 语义，archive 记录的仍是 canonical 实际写入的 physical row。
namespace 不可变重构保留为单独的全网 VM 兼容项目，不计入本轮已交付项。

必测：version 0/2 slot alias、version 1、CREATE2 namespace、同地址重部署、reorg replacement，
mid-chain temporal MISSING/floor 前后，以及 live SLOAD 与 historical getter/eth_call/trace
的 differential。

### 3.2 Journal 与 canonical commit 的补偿状态机

当前顺序是 archive commitBlock 成功后再 canonical session commit；后者抛错时 abortBlock
只清 pending allocator，可能留下 committed journal 和 execution index。

修复设计：

- 区分 PENDING、JOURNALED、CANONICAL_COMMITTED、PUBLISHED block 状态。
- commitBlock 返回 journal token。token 不只是高度，必须绑定 height、blockHash、
  generation nonce 和 schema checksum。
- canonical commit 成功后显式 acknowledgeCanonicalCommit。
- canonical commit 抛错时 rollbackJournaledBlock(token) 删除 journal、回滚 execution index
  和增量 in-flight 内存状态。补偿操作可重复。
- acknowledge/rollback 在 archive lock 内 compare-and-update；迟到的旧 token 不得删除同高度
  replacement journal。对已发布块执行 rollback 不是幂等成功，而是排序损坏并进入 FATAL。
- 若进程在 journal 与补偿之间中断，startup 以 canonical height/hash 为权威，回滚 canonical
  head 之后的 journal。
- rollback 不能删除 published block；遇到该状态说明排序不变量已破坏，立即 FATAL。

必测：journal 后 canonical 前抛错、补偿每一步强杀、补偿重复调用、同高度 replacement block、
startup canonical hash mismatch。

### 3.3 持久化写入策略

archive 与 canonical 是两个 DB，cross-CF batch 不能替代跨库 durability ordering。生产
LEGACY_V1 的关键写入不继承单一 `storage.db.sync`：

- activation ledger、in-flight journal、repair marker、migration COMPLETE marker 强制 sync。
- LEGACY_V1 没有独立的 `storage.archive.db.publishSync` 配置键。journal、index、temporal、
  repair marker 和 journal delete 均通过 `ArchiveRocksDbWriteOptions.createForcedSync()` 固定
  `WAL enabled + sync=true`；任一步失败都保留 journal 供 reconcile。
- UNIFIED_V1 隔离原型的 typed publish API 接受显式 `publishSync` 参数，但尚未接入 factory 或
  用户配置。其 published rows、cursor 和 journal delete 位于同一 RocksDB batch；生产接入前
  仍需完成 crash matrix，不能把原型参数描述成已支持的配置键。
- journal delete 必须和 published cursor 位于同一个原子 batch，或发生在可证明已发布之后。
- archive 启用采用 EMPTY -> ACTIVATING -> ACTIVE ledger。ACTIVATING 记录 chainId、schema、
  layout、floor、nextExpectedBlock，并在第一个 canonical block 前强制 sync。
- startup 发现 canonical 已跨过 floor，但 ledger/cursor/journal 无法证明连续覆盖时 fail-stop。
- 首次创建 archive root、子库和 manifest 时，依次 fsync 新目录的 parent、强制 sync ledger，
  再 fsync DB 目录及 archive root。平台不支持目录 fsync 时拒绝激活，不能假装 durability
  已成立。
- 基准矩阵独立覆盖 canonical sync、journal sync、publish sync，不能只测全局 true/false。

### 3.4 Mid-chain 读一致性与 mutation/publish 屏障

普通 Chainbase.head 可能已经包含 pushTransaction 或 generateBlock 的 pending overlay。
仅扩大 block-apply 锁无法得到 canonical committed baseline。因此本轮取消生产路径的普通
live read-through：

- mid-chain temporal MISSING 先查同一 archive 临界区内的 in-flight earliest-prev shield；
  仍无证据时返回明确 UNKNOWN_BEFORE_COVERAGE，不读取普通 Chainbase.head。
- ChainBaseArchiveReadThrough 只有在传入独立 CanonicalCommittedReadView 时才可使用；
  本轮没有该 view 时 production factory 必须禁用它，不以功能完整性换静默错值。
- pending session 已有余额、合约或 storage 修改时，历史 getter、eth_call 和 trace 的结果
  必须不受影响；缺少 baseline 的 key 明确失败。
- mid-chain reader 从 point/index 解析开始到 close 全程持有 fair、writer-preferred archive
  read lock，因此 temporal snapshot、in-flight shield generation、publish 和 shield removal
  不会交叉。
- LEGACY_V1 在 archive lock 内复制并验证 immutable index range，再捕获 temporal snapshot；
  两步完成后 genesis-complete reader可释放锁。UNIFIED_V1 才要求 index/point/temporal 来自
  同一个 RocksDB snapshot。

fork/recovery 与 async publisher 另设 fair、writer-preferred mutation barrier：

- switchFork、erase/replay、startup recovery 在 write lease 内完成；中间不得发布 target。
- publisher 每个 block 取得 read lease，发布后立即释放；writer 排队后新 reader/publisher
  不得插队。
- 锁顺序固定为 lifecycle admission -> mutation barrier -> archive lock。
- backlog 等待只能发生在取得 writer/mutation lease 之前。持锁后发现 hard watermark，
  必须释放后重试或快速 fail-stop，绝不等待 publisher；重入后重验 head、epoch 和容量。

必测：pending session 预先存在、snapshot/publish/shield removal 三点暂停、LEGACY index 与
temporal snapshot 间 publish/unwind、writer starvation、backpressure 死锁、commit/abort/reorg。

### 3.5 服务生命周期

生命周期 phase 与 fatal result 正交建模：

- lifecyclePhase 为 NEW、RECOVERING、RUNNING、DRAINING、CLOSED。
- fatalLatch 是独立的 first-failure-wins AtomicReference，设置后永久保留到进程退出。
- recovery、writer、query 和 publisher 四类 admission/active 计数由 lifecyclePhase 管理。
- phase、admission-open 标志和 active 计数只在同一个 lifecycle mutex 内读写。进入
  RECOVERING 与登记唯一 recovery participant 是一个原子操作，不允许先发布 phase 后加计数。

- admission matrix 固定为：NEW 不准入；RECOVERING 只允许当前 RecoveryLease 及其 genesis
  子流程；RUNNING 且 fatalLatch 为空时才允许普通 WriterLease、QueryLease 和 publisher；
  DRAINING/CLOSED 或 fatalLatch 非空时不准入新任务。只有已进入 STARTED 的 lease 允许完成，
  ACQUIRED_NOT_STARTED 必须在 final recheck 后退出。
- 每个 admission 在 lifecycle mutex 内检查 phase 后还必须重读 fatalLatch；任何 wait/retry
  醒来后重复同一检查。markFatal 先 lock-free 设置 fatalLatch，使并发 admission 立即失败，
  再在释放 archive/mutation lock 后取得 lifecycle mutex，关闭 admission并 signalAll waiter。
- 每个普通 lease 有 ACQUIRED_NOT_STARTED、STARTED、CLOSED 三态。取得 admission 只进入
  ACQUIRED_NOT_STARTED，不代表允许未来开始读写；但 admission 成功时立即增加 reservation
  active count。drain 等待 ACQUIRED_NOT_STARTED 加 STARTED 全部归零，且只在 lease CLOSED
  时递减 reservation。
- Writer/Publisher 在取得最终 mutation/archive locks 后、首次读写前，Query 在取得最终
  archive lock 后、创建 RocksDB snapshot或首次读取前，必须重新进入 lifecycle mutex，
  检查 phase==RUNNING 且 fatalLatch==null，并原子转为 STARTED。
- Query 只有 STARTED 成功后才调用 openReadView/getSnapshot；检查失败时尚未创建 native
  snapshot，只释放 locks/lease。STARTED 后 snapshot 构造失败仍由该 active lease 逆序清理。
- fatal 与 STARTED transition 在同一 lifecycle mutex/atomic protocol 下线性化：先 STARTED
  的任务允许完成或 rollback；先设置 fatalLatch 的任务永远不能 STARTED。
- lifecycle mutex 只做短临界区，任何路径不得持有它等待 mutation/archive lock。允许在已取得
  最终 lock 后短暂重入 lifecycle mutex，因为 markFatal 只在释放 archive lock 后获取该 mutex，
  从而不形成锁环。
- startup 的 RecoveryLease 覆盖 initGenesis、同步 reconcile、genesis/canonical-head 校验
  和 capture-context 清理。空链 genesis 只能作为该 lease 的受控子流程执行，不能取得普通
  WriterLease。
- beginDrain 允许 NEW、RECOVERING、RUNNING 原子 CAS 到 DRAINING，随后关闭 admission、
  取消 waiter 并等待四类 active；drain 不能清空或覆盖 fatal result。
- RecoveryLease 覆盖完整 startup activation，不在 reconcile 后提前释放。activation 顺序为：
  创建 publisher worker但保持 PAUSED、不访问 DB；安装 RPC dispatcher但 archive admission
  保持关闭；完成所有 startup validators。
- 上述组件 ready 后，在 lifecycle mutex 内重验 phase==RECOVERING、fatalLatch==null 和
  startup epoch 未取消，然后一次性把 worker gate/RPC archive admission 标记 ready、
  phase 置 RUNNING，并释放 recovery participant。worker/RPC 只能观察这次发布后的 gate。
- 若 activation 前 phase 已为 DRAINING 或 fatalLatch 非空，RecoveryLease 撤销/停止 PAUSED
  worker和未开放 dispatcher，再释放 participant并 signal drain；不得发布 RUNNING。
- RUNNING transition 与 recovery participant 释放属于同一临界区，不存在 phase 已发布但
  startup components 尚未 ready 的空窗。
- 普通 ArchiveWriterLease 在任何 canonical/Store mutation 之前取得，覆盖 beginBlock、
  全部 Store capture、durable journal、canonical session commit、ack 或 rollback、publish
  request 以及 capture-context 清理的完整 try/finally。DRAINING 不得在该 lease 释放前关闭 DB。
- backlog wait 发生在 WriterLease 之前；容量满足后在 lifecycle mutex 内取得 lease并重验
  phase/capacity，失败则不开始任何 mutation。
- archive-off 返回不可失败的 Noop Recovery/Writer/Query lease，不增加 reservation/active
  计数，不取得 archive lock或创建 snapshot，不做 DB 访问、不改变调用顺序或异常语义。
- DRAINING 中发生失败照常写入空的 fatalLatch。最终退出码只看 fatalLatch，存在 failure
  必须非零。
- FatalShutdownSignal 是预先创建的幂等单槽 AtomicBoolean 加专用 control thread，不使用
  可拒绝 executor queue。markFatal 在 finally 中先 set signal、再唤醒 control thread，
  repair-marker 成败不能跳过 signal。
- control thread 在 archive lock 外调用 idempotent application shutdown request；close/drain
  不等待该 control callback 返回，也不 join 当前 control thread。
- 独立 watchdog 在 fatalLatch 首次设置时开始 fatalShutdownTimeout 计时。若期限内既未进入
  DRAINING/CLOSED，或 control thread 异常退出，则调用可注入 ProcessTerminator.halt(nonzero)；
  生产实现使用 Runtime.halt，测试使用 fake terminator。已有 drain 正在执行视为 signal
  已接管，但超出总 shutdown deadline 同样强制非零终止。
- Manager 在关闭 archive 前先停止网络/block 入口。beginDrain 原子拒绝新 writer、QueryLease
  和 target，唤醒或取消 pending admission，然后在不持有 mutation/archive lock 时等待
  active recovery、writer、QueryLease 和 publisher 全部退出。
- shutdown 顺序为停止入口、关闭 admission、等待 active 四类任务、关闭 archive DB，
  最后才关闭 ChainBase。
- shutdown 超时时若仍有 native reader，不得从其他线程强制 close DB；记录失败并让进程退出
  路径接管。
- reader、view、lease、service close 均 exactly-once 且幂等，构造中途失败按逆序释放，
  保留原异常并添加 suppressed close exception。

必测 NEW->RECOVERING 与 participant 登记暂停点、recovery 每个阶段收到 SIGTERM、genesis
RECOVERING 子流程、worker PAUSED/RPC ready/RUNNING 发布三点 shutdown、activation epoch
取消、ACQUIRED reservation 阶段 drain、lease 已取得但等待 mutation/archive lock 时发生
fatal、final lock 后 STARTED/fatal 线性化、snapshot 构造暂停、fatal-before-snapshot、
fatal 后 admission waiter 全部唤醒且不能取得 lease、整块 writer 在 capture/journal/canonical
commit/ack 各点 drain、publisher fatal 与 active writer/query 并发、repair marker 抛错仍
发 signal、control thread rejection/死亡、watchdog fake halt、用户 shutdown 与 fatal request
并发、NEW/RUNNING/fatal drain、DRAINING 二次失败、archive-off 调用序列和重复 close。

### 3.6 Archive identity 与显式初始化

identity 机制前移到 Phase A，避免独立 archive 磁盘未挂载时把空目录当成新部署。每个 archive
使用独立 UUID；外部 anchor 位于 canonical output/archive-identities/UUID，root identity 位于
archive root。两者都记录 chainId、schema、layout、canonical final path 和 expected floor。

持久化协议：

- 普通 node 只接受外部 anchor 与 root identity 都为 ACTIVE 且字段完全一致。
- `storage.archive.identity.initialize=true` 仅允许 canonical 空链和空 archive root。它先写带
  随机 resume nonce 的 PREPARED anchor并 fsync；重启从唯一匹配的持久 claim 继续，普通运行
  对不匹配、空 root 或缺失 root 一律 fail closed。
- resume 创建并 fsync BOUND root identity、activation ledger和 DB，再把 anchor 置 BOUND；
  完整复验后先把 root、后把 anchor 强制 sync 为 ACTIVE。任一步强杀后只能用同 nonce resume，
  不允许覆盖不同 UUID 或已有内容。
- 已有 legacy 不自动补 anchor。运维显式设置
  `storage.archive.identity.adoptLegacy=true` 后，启动先校验完整 store layout、manifest、range、
  temporal marker/rows、txNum coverage、domain rows 与 in-flight，再用同一
  PREPARED -> BOUND -> ACTIVE 协议注册；adopt 写 identity 元数据之外不改 archive rows。
- adopt/init 的未完成状态会从唯一匹配的持久 claim 幂等 resume。协议级 abort 只能删除能由
  nonce 证明尚未 ACTIVE 的 identity 元数据，不触碰已有 archive 数据。

外部 anchor 已存在但 root 为空，明确表示 mount/data 丢失或未完成操作，不允许 INIT_NEW
重新使用。adopt 成功后应将一次性配置恢复为 false；ACTIVE identity 由普通只读验证路径接管。

## 4. 基线、指标和硬验收门槛

指标按 block 或 request 聚合，禁止每个 key 更新高成本 histogram。至少包括：

- capture prev-read 次数/耗时、records、merged records、raw bytes。
- journal encode/write/sync 耗时和 bytes。
- publisher state、requested/published cursor、lag、oldest journal age、catch-up rate。
- active/pending/rejected query、active snapshot、backend reads、cache hit、duration、deadline。
- trace steps、estimated bytes、response bytes、limit rejection。
- in-flight blocks/records/bytes。
- RocksDB WAL/SST bytes、pending compaction bytes、write stall、disk free。
- layout/schema、migration phase/progress、repair-required 状态。

固定区块语料覆盖 transfer、TRC10、deploy、SSTORE-heavy、freeze/unfreeze、账户删除和
SELFDESTRUCT。benchmark protocol 固定为：

- 同一台隔离 runner、固定 CPU governor、固定 JDK/Gradle/RocksDB 版本和 JVM flags；
  archive 与 canonical 使用预先 trim、剩余空间不低于 30% 的指定 NVMe。
- 每个 trial 清理同一份 clone 数据并等待 compaction/write-stall 归零；记录完整环境 manifest。
- 预热 5k blocks，测量不少于 50k blocks；baseline/candidate 按 ABBA 顺序交错运行 7 组。
- 报告原始样本、median、p95/p99 和 paired bootstrap 95% confidence interval；CI 跨过门槛
  即判定不通过，不能挑选最好一轮。
- 固定查询负载为 32 并发 getter、8 并发 eth_call、2 并发 trace；mid-chain 另以 2 并发
  且 deadline 生效的 profile 测试。

验收门槛：

1. archive-off 的额外 DB read/write 精确为 0，写入 bytes/hash 与基线一致。
2. 仅加入指标时，archive-off block-push p99 回归不超过 1%。
3. 每项优化后的 archive-on block-push p95/p99 不得比优化前同配置回退超过 5%。
4. finality lag 从 1 增至 10k 时，无 solidified advance 的单块维护成本不随 lag 线性增长。
5. publisher catch-up 吞吐至少为峰值 journal 产生速率的 2 倍。
6. 10k-block finality stall 解除后，lag 在生成后续 5k blocks 所需时间内归零；catch-up
   期间 genesis-complete block-push p99 回归不超过 5%，mid-chain profile 不超过 10%。
7. 72 小时 finality-stall/catch-up 与固定混合查询 soak 中，6 小时预热后的 retained heap
   线性回归斜率不超过已分配 heap 的 0.5%/hour，active snapshot 不超过配置上限，输入速率
   低于 catch-up capacity 50% 时 lag 不得连续 10 分钟增长。
8. 每个 journal、published batch、marker、migration rename 窗口强杀或 ENOSPC 后，只允许
   完整恢复或明确 fail-stop。
9. unified 迁移用至少三份真实规模 legacy 数据验证；源摘要不变，目标全 CF 摘要和历史查询一致。
10. 默认开启 async/unified 前完成真实上一版本二进制的启动、回退和误指向演练。

## 5. Item 1：增量维护 in-flight 状态

### 设计

每个 canonical key 使用 append-tail、publish-head、unwind-tail 的 version deque。每个节点
保存 blockNum、txNum、prevValue、value：

- head.prevValue 是 mid-chain reader 的 earliest-prev shield。
- tail.value 是 capture 的 latest/no-op 基线。
- append block 只追加本块 records。
- publish oldest 只弹出对应 head。
- unwind newest 只弹出对应 tail。
- deque 为空时删除 key state。

所有变更在 archive write lock 内。publish/unwind 先完整验证将要弹出的节点与 journal，
再完成 durable store transition，最后执行预先验证且保证不抛异常的内存 transition。
持久化失败时内存保持不变。startup 允许从 journal 全量构建一次，运行时不得全量重建。

execution txNum allocator 不由后台 publisher 裁剪。canonical 线程只在 block commit/abort 完成、
allocator 无 pending 时按 publishedThrough 裁剪。

### 测试

- 与保留的全量 rebuild oracle 做 randomized differential。
- publish oldest/多块、unwind newest、delete/recreate、同 key 跨 tx/block、空块。
- durable write failure 前后验证内存不提前变化。
- blockNum/txNum/hash 错位立即 fail-stop。
- finality stall 10k blocks 的操作数只与本次 append/publish records 相关。

## 6. Item 2：请求级缓存与 snapshot iterator

### 设计

DefaultArchiveStateReader 增加 reader-lifetime raw memo：

- key 是 domain 加不可变的 canonical-key copy，按内容比较，不能直接使用 byte[] 引用。
- 内部 RawArchiveLookup 明确定义四态：PRESENT、TOMBSTONE、MISSING、
  UNKNOWN_BEFORE_COVERAGE。
- MISSING 只表示在 genesis-complete history 中可以证明不存在；UNKNOWN 表示 mid-chain
  temporal 与同 generation in-flight shield 都没有足够证据，两者不得折叠。
- mid-chain temporal MISSING 必须先查询同 generation 的 in-flight shield；仍无证据后缓存
  UNKNOWN，不得调用普通 live read-through。
- UNKNOWN 是确定性结果，可以 memo；reader 在 public typed result 边界把它统一转换成
  ArchiveReaderException.Reason.HISTORY_UNAVAILABLE。I/O、codec、deadline 等真实异常不缓存。
- 所有 getter、VM 和 trace 在统一 reader 边界收到相同 HISTORY_UNAVAILABLE，映射稳定的
  JSON-RPC -32000 和固定消息；不再由消费者用 genesisComplete 加 MISSING 自行推断。
- 输入、缓存和返回值均防御性复制。
- 同时限制 entries 和估算 bytes；满后停止新增，不改变查询结果。
- 可选 decoded account/contract metadata cache 由 benchmark 决定，不作为首批必需优化。

ArchiveTemporalStore.openReadView 改为必须实现的隔离接口，禁止默认 pass-through。
Rocks view 持有 DB snapshot、ReadOptions 和 history iterator。LEGACY_V1 的 point/index 在
archive lock 内先复制为 immutable value，再创建 temporal snapshot；UNIFIED_V1 的 point/index
和 temporal 使用同一个 DB snapshot。view 绑定 owner thread 或串行化 read/close，不跨请求
共享。close 顺序为 iterator、ReadOptions、Snapshot，并 exactly-once。

mid-chain reader 不释放 archive read lock，memo 和 in-flight shield 因而属于同一 generation。
genesis-complete reader 不使用 read-through，创建 snapshot 后可释放 lock。

QueryContext 分别统计 logical reads、backend misses 和 canonical-view read-through DB
operations；本轮 production 未提供 canonical view 时后者必须为 0。
backend budget 只消耗真实 backend work；每次 API/VM step 仍检查 monotonic deadline。

### 测试

- PRESENT/TOMBSTONE/MISSING/UNKNOWN 四态都只访问必要 backend 一次；同内容不同数组命中。
- 修改输入数组或返回数组不会污染 cache。
- repeated UNKNOWN 只查一次 shield；首次真实异常后第二次重新访问 backend。
- account/code/storage/account-asset/dynamic-property 在 getter、eth_call、trace 下统一返回
  HISTORY_UNAVAILABLE；genesis-complete 的真正 MISSING 仍按不存在渲染。
- 重复 SLOAD/account/code/token balance 的 backend 次数。
- cache 达到双预算后结果不变。
- genesis snapshot 后并发 publish/unwind 仍保持同一视图；mid-chain reader 存活期间
  publish/unwind 必须等待。
- pending/packing overlay 已存在时 MISSING 返回 UNKNOWN，不读取 live head。
- view 构造逐点失败、跨线程访问、read/close race、double close、开放 snapshot 归零。

## 7. Item 3：Account 与 ACCOUNT_ASSET 热路径

### previous account 复用

TronStoreWithRevoking 增加受保护的 known-previous capture 入口。AccountStore 每次 put/delete
新读取一次 archive previous，供 ACCOUNT 和 ACCOUNT_ASSET 两种 capture 共用，再交给
super 写入；不能跨多次 put 缓存。history-balance 的独立读取和异常语义不合并。

archive-off 不调用 known-previous 或 asset lookup，继续走原入口。

### effective asset 算法

不能假设所有 mutation 都先调用 importAsset，也不能把 hook 移到 SnapshotRoot 的物理 flush。
候选 ID 来自 old/new account materialized asset IDs；direct protobuf builder 修改也自然反映在
该集合。对每个候选 ID，物理基线 P 最多读取一次：

    map contains id             => map value，包含显式 0
    map missing + optimized     => P
    map missing + unoptimized   => 0

old/new optimized 状态相同的普通 put 只需 materialized union；未出现的物理 ID 两侧都等于 P，
无需扫描。以下情况必须使用 physical prefix 加 previous overlay 合成完整 effective 集合：

- account delete；
- optimized 状态切换；
- 新建 optimized account 且地址已有物理 rows 的防御路径。

这些稀有路径不设置会影响合法区块的硬行数上限，只记录 rows/bytes/耗时和告警。capture API
直接接收 assetId、oldBalance、newBalance，不构建全资产临时 Account protobuf。

### 测试

- physical base 加 overlay 的显式零、map 缺失、materialize 后清除、同 tx 5→8→5。
- optimized/unoptimized 双向切换、首次迁移、direct builder、bulk put、clearAssetV2。
- delete 的物理旧值、overlay 更新/新增/零、100k assets、SELFDESTRUCT transfer-all。
- session abort、reorg replacement，与当前全量展开 oracle differential。
- ACCOUNT 和 ACCOUNT_ASSET 的 prev/value 链字节一致。
- archive-off 精确 DB 调用序列无变化。

## 8. Item 4：Storage original value

Item 4 只能在 storage key schema v2 合入后实施。

original cache 放在 Storage 实例，并以最终物理 row key为键，而不是以 logical DataWord 或单个
StorageRowCapsule 为权威。key 必须使用 WrappedByteArray.copyOf 或等价不可变内容键，value
同样防御性复制，禁止用 byte[] 引用相等：

- 状态为 UNKNOWN、KNOWN_ABSENT、KNOWN_PRESENT(value)。
- SLOAD present/absent 都记录 original；absent 不用 null capsule 表示。
- direct SSTORE 的 UNKNOWN 在 commit 时恰好 fallback read 一次。
- commit 内维护 per-physical-key current value，处理多个 logical slot alias 到同一 row 时，
  prev 链连续。
- 不改变 addrHash、contractVersion 的 legacy rebind/cache 行为；archive original/current cache
  只以实际 physical row key 为权威，禁止从 logical slot 或 deployment hash 另行推导写入键。
- child repository copy 保留 tri-state 且 null-safe；child revert 不泄漏到 parent。
- commit 成功后更新或失效 original，重复 commit 不能使用陈旧值。
- archive capture 未启用时不建立额外 cache、不增加 Store read/write，capsule bytes/hash 不变。

测试覆盖 present/absent SLOAD、直接 SSTORE、两个不同 byte[] 实例映射同一 physical key、
alias、多写、zero delete、child copy/rebind 继续匹配 legacy canonical physical write、
CREATE2/redeploy/reorg、调用方修改数组和 archive-off 调用序列。

## 9. Item 5：有界后台 solidified publisher

### API 与启动

- recoverSynchronouslyTo 用于 startup reconcile、genesis 和 canonical-head 校验，必须阻塞完成。
- publishSolidifiedBlocks 保留同步 oracle。
- requestPublishSolidifiedBlocks 只用于 RUNNING 后的常规 block path。
- async=false 是本轮默认；async=true 先只允许 LEGACY_V1 opt-in。
- worker 仅在同步 recovery 与 RPC 前置校验全部成功后启动。

### target、fork 与 allocator

target 不是单一高度，而是 height、blockHash、canonicalEpoch：

- chain fork erase/replay/recovery 持有 mutation write lease并暂停发布。
- fork 全部成功后只提交一次新 epoch target；失败 target 永不进入 worker。
- worker 每次发布前在 mutation read lease中确认 hash/epoch 仍 canonical。
- worker 不操作 execution allocator，只更新 publishedThrough。
- canonical 线程在 commit/abort 后且 allocator 无 pending 时执行 discard。

### 调度与失败

- queue 只保存常量大小 target；真实 backlog 是 durable journals，不把 records 复制到无界 heap。
- 每次只发布一个 block或一个短时间片，块间释放 archive lock；journal append 优先。
- blocks、journal bytes、oldest age、disk free 设置 soft/hard watermark。hard watermark 的动作
  是可配置 backpressure 或明确 fail-stop，不能静默丢 journal。backpressure 只能在 Manager
  取得 ArchiveWriterLease 和 mutation/write lock 之前等待；持锁后只能释放重试或 fail-stop。
- LEGACY_V1 的 worker 可以异步于 block thread，但 index、temporal 和最后的 journal delete
  均强制 sync 并按此顺序执行。publishSync=false 仅允许 UNIFIED_V1。
- worker 状态纳入统一生命周期。失败先在内存标记 FATAL，释放 lock 后再写 repair marker 和
  调 shutdown callback；repair marker 写失败不恢复运行。
- close 先停止新 target，再等待当前 block 原子边界。未发布 durable journal保留给下次 recovery。

### 测试

- beginBlock(N+1) pending 与 publish(N) 并发不触碰 allocator。
- startup/genesis/reconcile 始终同步，RPC 开放前 cursor 已校验。
- fork replay 中途失败、target epoch 失效、publish/fork race。
- finality stall 后 catch-up 每块释放锁，block append 可取得优先级。
- journal/canonical/publish/delete 每个窗口强杀与 ENOSPC。
- fatal callback、self-join、shutdown timeout、active reader 和 native handle race。
- active canonical writer 中途 shutdown，验证 DRAINING 等待 writer 完成且不提前 close DB。
- hard watermark 在 writer lease 之前等待，验证 publisher 不与 canonical writer互锁。
- async 与同步 oracle 的完整 differential。

## 10. Item 6：统一 RocksDB 与原子 publish

### 发布阶段

UNIFIED_V1 只能在以下条件满足后启用：

1. archiveV3 权威决策更新并评审。
2. 先发布能识别 LEGACY_V1、UNIFIED_V1 和 AUTO 的 bridge 版本。
3. 真实上一版二进制 downgrade/误指向测试证明会明确失败。
4. 离线 migrator、恢复手册和回退演练完成。

本轮 AUTO 只对已按 Phase A identity 协议注册的 ACTIVE legacy 继续使用 legacy；节点永不把
空目录自动解释为新部署。LEGACY_V1 新建与采用分别由一次性的 `identity.initialize` 和
`identity.adoptLegacy` 配置显式触发；UNIFIED_V1 仍不接入 factory。后续版本达到 soak 门槛
并交付独立 migrator 后再讨论默认 unified。

### 布局与 snapshot

UnifiedArchiveDb 独占一个 RocksDB 和所有 handle，column families 遵循权威生命周期分离：

- meta；
- inflight；
- index；
- latest；
- history；
- changeset；
- block-marker；
- commitment，如启用。

publishBlockAtomically 在一个 cross-CF WriteBatch 写 index、latest/history/changeset/marker、
published cursor，并删除 journal。reader 使用同一个 RocksDB snapshot 解析 meta/index
state point 和 temporal rows，不能先读 live index 再创建 temporal snapshot。

所有 UnifiedArchiveDb WriteOptions 强制 disableWAL=false，此项不可配置。publishSync=false
只省略 WAL fsync，不得关闭 WAL；故障注入覆盖各 CF flush/compaction、WAL rotation 和
published batch 前后，验证 batch 丢失时已 sync journal 可重新出现并重放。

activation、journal 和 repair 的 sync 规则沿用 Phase 0；单库原子性不声称解决 canonical/archive
跨库 2PC。

### AUTO 磁盘状态机与旧版本保护

启动前只读探测，不允许在判型前 createIfMissing：

- 空或缺失目录；
- ACTIVE identity 匹配且完整的 legacy；
- ACTIVE identity 匹配且完整的 unified manifest/CF/schema；
- PREPARED、BOUND 或 MIGRATING identity；
- legacy 与 unified 并存；
- 部分 legacy、缺失 manifest、未知非空内容。

只有第二、三种 ACTIVE 状态可继续，其余全部 fail closed并指向对应 init/adopt/migrate
resume 命令。显式 layout、UUID、final path 与磁盘状态不一致也失败。

unified 必须写入全新的 archive root，源 legacy 保留。Unified RocksDB 放在新 root 的
temporal 路径并包含多个非 default CF，使真实旧二进制按 legacy 方式首先打开 temporal 时
因未打开 CF 而失败；该行为必须由集成测试证明，不能只依赖旧二进制不会读取的 manifest。
旧版本只有在 canonical head 尚未越过保留 legacy tail 时，才能 archive-on 指回 legacy root。
一旦 unified 运行后 canonical 已前进，本计划不提供 reverse migration；旧版本回退必须关闭
archive，或从受支持快照重建 legacy，不能把停滞 legacy 当作可继续的历史库。

### 蓝绿离线迁移

- 阶段一由 bridge 节点以正常写模式完成 legacy reconcile，并干净停机。
- 阶段二 migrator 取得独占锁，以 read-only 打开已干净的源并建立摘要；migrator 本身绝不
  repair、publish 或修改源。
- migrator 为 target 生成新的 UUID、resume nonce 和独立 external anchor，不复用或覆盖
  source anchor。target identity 记录 sourceUUID、sourceDigest、finalPath、floor、schema
  和 UNIFIED_V1 layout；source identity/anchor 始终保持 ACTIVE。
- target 使用 PREPARED -> BOUND -> MIGRATING -> ACTIVE。只有 migrate --resume 加相同 nonce
  能推进未完成状态，普通 node 始终拒绝 PREPARED/BOUND/MIGRATING。
- 目标必须不存在，不能与源/canonical 相同、嵌套、symlink alias 或跨设备临时目录。
- 写 target.tmp.uuid 同文件系统 sibling，预检空间；进入阶段二后源始终只读。
- 复制并校验 schema、repair marker、coverage floor、cursor、全部 journal、index 和 temporal
  CF 摘要。
- flush RocksDB，写强制 sync 的 COMPLETE marker，再 flush 并 close handles；随后 fsync
  RocksDB 文件、manifest、临时目录和 destination parent。
- 仅支持 ATOMIC_MOVE 到最终新 target；不支持时中止，不退化普通 rename。move 成功后再次
  fsync destination parent，再从最终路径 read-only 重开并复验 identity、manifest 和摘要，
  全部成功后先强制 sync root identity 为 ACTIVE，再强制 sync target anchor 为 ACTIVE，
  最后重开复验后才向运维报告完成。两次 ACTIVE 之间强杀由 migrate --resume 完成。
- ENOSPC、强杀或校验失败留下显式 INCOMPLETE temp，源字节摘要不变。
- 成功后由运维显式修改 path 和 target UUID 切换；工具不删除、不 rename 源目录或 source
  anchor。

storage schema v1 数据因 alias 歧义不进入该迁移，必须从 canonical block replay 重建。

## 11. Item 7：查询预算、指标与独立磁盘

### QueryLease 与锁顺序

JSON-RPC servlet 在 dispatch 前建立 transport scope；生产 `ArchiveService.openReader` 在取得
archive consistency lock 前申请 QueryLease，并把 lease 的最终释放注册到该 transport scope：

- maxConcurrentQueries 限制 active。
- maxPendingQueries 限制 waiter；默认 acquireTimeoutMs 为 0，快速拒绝。
- snapshot permit 必须在 consistency lock 之前取得。
- `DefaultArchiveStateReaderFactory` 为测试和兼容调用保留公开 unlimited 构造/打开入口；生产装配
  不公开 factory，所有外部历史读取只能经 `ArchiveService.openReader*` 的 admission 路径。
- lookup 只抛 typed limit exception，由最外层 owner exactly-once close；预算代码不得主动
  force-close reader。
- transport owner 持有 lease 到响应被序列化为受 `maxResponseBytes` 约束的有界缓冲；
  内部 getter/VM/trace 只持有 child handle。网络 commit/flush 在 lease 释放后执行，因此慢客户端
  不占 archive query slot，也不延长 native snapshot 生命周期。batch 每个子请求遵循同一边界；
  响应构造和慢网络保留的字节均在每次 write 时增量计入进程级预算，子响应通过 `writeTo` 拼接，
  不创建完整 `toByteArray` 副本。

service 进入 DRAINING 时原子关闭 admission，取消/唤醒 pending acquire，并等待 active
QueryLease 自行退出；等待条件不是 active reader 数。超时不跨线程 close DB。

### 预算与错误

QueryContext 使用 System.nanoTime，并配置：

- maxBackendReadsPerRequest；
- maxLiveReadThroughsPerRequest；
- maxCachedEntries/maxCachedBytes；
- maxConcurrentQueries/maxPendingQueries/maxOpenSnapshots；
- acquireTimeoutMs/deadlineMs；
- maxTraceSteps/maxTraceBytes/maxTraceResponseBytes。

cache hit 不消耗 backend budget；所有调用、VM opcode 和 precompile 前后边界检查 deadline。
historical VM 的最终 deadline 是现有 CPU 限制与请求 deadline 的较小值。ProgramTrace 在
addOp 前检查 steps/bytes，StructLogReconstructor 流式计量 stack/memory/storage 与 response
bytes，避免读预算之外的 OOM。这些限制只作用于 historical archive trace，除非另有独立
live trace 配置。

QueryContext 保存 first-terminal-wins 的 RESOURCE_EXHAUSTED 或 DEADLINE reason。VM 内 limiter
触发专用 HistoricalQueryLimitException 以尽快停止；即使 VMActuator catch-all 把异常转成
普通 VM result，historical executor 也必须在 VM 返回后调用 throwIfTerminated 并重抛 typed
limit。若现有 VM CPU timeout 先发生且 QueryContext 未终止，保留原 VM timeout；若 query
deadline 先标记，则最终固定为 -32005。单个 precompile 无法中途检查时仍受现有 energy/CPU
上限，返回边界立即检查并按先到的 terminal reason 映射。

ArchiveReaderException 增加 RESOURCE_EXHAUSTED 和 DEADLINE。getter、eth_call、trace 的异常
链保持该 reason，统一映射现有 JSON-RPC -32005；corruption/I/O 仍为 -32000，invalid params
保持原码。

生产配置为并发、pending、snapshot、deadline、logical/backend reads、cache、trace steps/bytes
和 response bytes 提供有限默认值；`acquireTimeoutMs=0` 快速拒绝。仅直接构造测试 helper 的
compatibility builder 保留 unlimited，不能由生产 factory 使用。

测试分别覆盖 query deadline 先到、VM CPU limit 先到、trace steps/bytes/response limit、
precompile 边界、VM catch-all 后 terminal 重抛、point resolution/reader 创建/serialization
暂停时 shutdown，以及 pending waiter 在 DRAINING 后不得复活。

### 独立路径

storage.archive.db.directory：

- 先用 Path.isAbsolute 判断；绝对路径不再拼到 output/database。
- 相对路径 normalize 后必须仍位于 canonical archive base。
- 对已存在路径使用 realpath，对待创建路径 canonicalize 最近已存在 parent。
- 兼容默认相对路径仍解析为 `<output>/<storage.db.directory>/archive`；它可以位于 canonical
  DB root 下，但不得与任一真实 property DB 路径重叠。绝对路径拒绝与整个 canonical root、
  legacy 子库、迁移源/目标相同、互为父子或 symlink alias。
- 绝不自动创建不明确的非空目录。

自动测试覆盖 absolute/relative、点段、symlink、同目录、父子、嵌套和 identity anchor 冲突；
权限、跨设备和独立 NVMe E2E 仍属于目标机性能/运维验收，不声明已完成。

## 12. 实施顺序与提交边界

Phase A：正确性前置

1. storage key schema v2、权威文档和 differential tests。
2. journal/canonical compensation state machine。
3. operation-specific sync、activation ledger。
4. archive identity init/adopt/resume 协议。
5. mid-chain fail-closed read semantics、fork/publish barrier、lifecycle 和 shutdown ordering。

Phase B：局部低风险优化

6. 指标与 deterministic benchmark harness。
7. Item 1 incremental in-flight。
8. Item 2 raw memo 与 snapshot iterator。
9. Item 4 physical-key original cache。
10. Item 3 account previous/effective asset，在 mutation audit 完成后合入。

Phase C：资源控制

11. Item 7 QueryLease、typed limits、trace limits 和安全绝对路径；async 仍为 false，
    layout 仍为 AUTO/legacy。

Phase D：异步发布

12. Item 5 在 LEGACY_V1 opt-in；完成 crash、ENOSPC、fork、shutdown 和 72 小时 soak 后才讨论默认。

Phase E：统一布局

13. bridge release、离线 migrator、UNIFIED_V1 同步 publisher、独立新路径、人工切换。
14. 最后测试 async + unified；已有 archive 永不自动迁移。

目标提交边界是每个编号至少一个可独立编译、测试、回滚的提交，Item 3、5、6 不互相混在同一
提交。当前工作树尚未提交，因此本文只记录建议切分，不声明这些提交边界已经实现。
ROUND 10 已修复 ROUND 9 后发现的生产 HIGH/MEDIUM；Item 5 保持 opt-in，Item 6 保持隔离原型，
最终准入以本文件门槛和最新对抗复审为准。

## 13. ROUND 1 发现处置表

- in-flight transition 顺序不明：改为预验证、durable store、non-throwing memory transition。
- async 与 execution allocator 冲突：publisher 不再 discard，canonical 线程安全点裁剪。
- startup/genesis 异步破坏屏障：增加同步 recovery API，worker 最后启动。
- fork target 仅高度不安全：加入 hash/epoch 和 chain-mutation barrier。
- canonical commit 失败留下 orphan journal：新增 journal token/ack/rollback 状态机。
- journal 默认非 sync：operation-specific forced sync。
- storage logical/archive key 不等价：schema v2 作为 Phase A 首项。
- optimized asset effective balance 不完整：明确 P 基线和模式切换 prefix 合成。
- original value 绑定 logical row：改为 Storage 级 physical-key tri-state。
- mid-chain live read-through 混读：ROUND 2 进一步证明 barrier 不足，最终改为禁用普通
  live head fallback；无 canonical committed view 时明确 UNKNOWN。
- trace 可绕过 read budget OOM：增加 step/byte/response 限制。
- semaphore 无界 waiter 与锁反转：QueryLease 先于所有 consistency lock，限制 pending。
- budget 主动 close 导致 double-close：最外层 exactly-once owner。
- shutdown native use-after-close：drain active reader，不跨线程强制 close。
- unified CF 与权威文档冲突：保留 latest/history/changeset 独立 CF并先更新权威决策。
- old binary 不读 manifest：新 root 的 temporal 多 CF 必须让真实旧 binary open 失败。
- migration/路径状态不完整：AUTO 状态机、蓝绿迁移、强制 fsync/atomic move/path alias 拒绝。
- cross-CF batch 不等于查询一致：index/point/temporal 使用同一 DB snapshot。

## 14. ROUND 2 发现处置表

- live baseline 可能包含 pending/packing overlay：禁用普通 Chainbase.head read-through；
  MISSING 无 canonical view 时明确 UNKNOWN。
- temporal snapshot 与 in-flight shield 跨 generation：mid-chain reader 全程持有 fair archive lock。
- LEGACY 无法跨 DB 共用 snapshot：锁内复制 immutable index point 后再建 temporal snapshot。
- backpressure 与 publisher 互锁：容量等待移到 writer/mutation lease 之前，持锁绝不等待。
- shutdown 未等待 canonical writer：新增 writer admission/active 计数并先停止 Manager block 入口。
- LEGACY async durability gap：LEGACY publish 各库和 journal delete 强制 sync。
- 空 archive mount 被误当新部署：显式 init 加外部 identity anchor，普通空目录 fail closed。
- compensation token 会误删 replacement：height/hash/generation/schema CAS。
- reader/publisher 饿死 writer：fair、writer-preferred barrier，publisher 每块让出。
- DRAINING 中失败状态不明：FATAL 优先并要求非零退出。
- directory entry 未 fsync：初始化与 rename 后均 fsync parent，最终路径重开验证。
- publish sync 兼容不明：唯一 publishSync，默认 true；LEGACY 不允许 false。
- migrator reconcile 与只读冲突：bridge 写模式 reconcile 停机后，migrator只读。
- legacy downgrade tail 落后：canonical 前进后旧版本只能 archive-off 或重建。
- storage v2 漏 read-through：五个 codec/reader/catalog 组件同步升级。
- Storage namespace/cache key 不完整：archive 改用 canonical physical 内容键和防御复制；
  会改变 live rebind/cache 语义的 immutable namespace 重构延期为全网 VM 兼容项目。
- QueryLease drain 边界不明：transport-level lease 覆盖 serialization/cancel。
- VM limiter 被 catch-all 吞掉：QueryContext terminal reason 在 historical executor 强制重抛。
- SLO 不可重复：固定 runner/profile、ABBA 七组、bootstrap CI、并发和斜率门槛。

## 15. ROUND 3 发现处置表

- MISSING 与 UNKNOWN 契约矛盾：内部四态，UNKNOWN 可缓存；public reader 统一抛
  HISTORY_UNAVAILABLE，消费者不再自行推断。
- FATAL 与 DRAINING 不可组合：lifecyclePhase 与 fatalLatch 正交，fatal drain 保留非零结果。
- init 未消费状态矛盾：PREPARED -> BOUND -> ACTIVE，只有带 nonce 的显式 resume 可推进。
- 已有 legacy 无 identity：Phase A 提供显式一次性 `identity.adoptLegacy`，启动默认不自动认领。
- migration target 无 anchor：新 target UUID/anchor，记录 source identity/digest，
  PREPARED -> BOUND -> MIGRATING -> ACTIVE，源 anchor 保留。
- unified async publish 漏 WAL 约束：disableWAL=false 强制不可配置，并覆盖 flush/rotation crash。

## 16. ROUND 4 发现处置表

- RECOVERING 无正常 drain：NEW/RECOVERING/RUNNING 均可 CAS 到 DRAINING，recovery 纳入
  active participant，完成时发现 DRAINING 不启动 worker/RPC。
- fatal callback 与 CLOSED 循环等待：hook 只允许非阻塞 enqueue shutdown request；
  CLOSED 不等待应用 shutdown 回调完成。

## 17. ROUND 5 发现处置表

- RECOVERING participant 登记竞态：phase 转换、admission 和 active count 统一在 lifecycle
  mutex 内，RECOVERING 隐含已登记 participant。
- WriterLease 边界不明：普通 lease 覆盖任何 mutation 前到 journal、canonical commit、
  ack/rollback、publish request 和 capture 清理完成；genesis 由 RecoveryLease 子流程承载。
- fatal shutdown 入队可丢：改为不可拒绝的 AtomicBoolean 单槽信号加预启动 control thread；
  repair marker 通过 finally 与 signal 解耦。
- control thread 失效仍可能不退出：独立 watchdog 到期调用可注入 ProcessTerminator.halt。

## 18. ROUND 6 发现处置表

- fatal 后仍可按 RUNNING 准入：所有普通 admission 要求 phase==RUNNING 且 fatalLatch==null，
  markFatal 先原子设置 latch，再在锁外关闭 admission并唤醒 waiter。
- recovery participant 释放后的 activation 空窗：RecoveryLease 覆盖 PAUSED worker、关闭的
  RPC admission 和 validators；同一 mutex 内原子发布 gate/RUNNING并释放 participant。

## 19. ROUND 7 发现处置表

- fatal 只封 admission、未封住已获 lease 的待执行任务：普通 lease 改为
  ACQUIRED_NOT_STARTED -> STARTED -> CLOSED，最终 lock/snapshot 后、首次读写前与
  fatalLatch 在线性化协议中二次准入；未 STARTED 的任务直接退出。

## 20. ROUND 8 发现处置表

- ACQUIRED_NOT_STARTED 未纳入 drain、Query 过早创建 snapshot：admission 立即增加 reservation，
  drain 等待 reserved+STARTED；Query final archive lock 后先 STARTED，再创建 native snapshot。

## 21. ROUND 9 最终准入问题

审查者必须给出可复现时序或代码链路，逐项确认：

1. Phase 0 是否关闭现有 storage alias、orphan journal、pending-overlay read-through 和
   temporal/shield generation 混读。
2. Item 1 在 durable failure、publish head、unwind tail 下是否与 oracle 等价。
3. Item 2 的四态 memo、HISTORY_UNAVAILABLE、snapshot、budget 和 close 是否无混点、泄漏
   或锁反转。
4. Item 3 的 effective asset 公式是否覆盖 direct builder、模式切换、删除和 rollback。
5. Item 4 的 physical-key cache 是否覆盖 alias、child repository 和 CREATE2 namespace。
6. Item 5 的 atomic recovery participant、phase admission matrix、whole-block WriterLease、
   allocator、fork epoch、backpressure、fatal signal/watchdog、drain 和 LEGACY durability
   是否闭合。
7. Item 6 的 init/adopt/migrate identity、WAL、directory fsync、旧版本阻断、AUTO 判型、
   迁移和 request snapshot 是否闭合。
8. Item 7 是否能限制无 archive read 的 trace，transport lease/terminal reason 是否贯穿，
   且错误码和 archive-off 行为保持兼容。
9. 所有 SLO 是否可由自动测试或明确 soak 命令验收。

只有 HIGH/MEDIUM 正确性问题为零，LOW 有明确接受理由和测试后，才进入生产代码实现。

## 22. ROUND 12 最终实现处置与证据

最终对抗复审追加并关闭了以下生产路径问题：

- 写路径、计划/实现一致性、查询资源边界、lifecycle/fatal 四个独立 skeptic lane 的最终复审
  均为生产 `LEGACY_V1` HIGH 0 / MEDIUM 0；结论不包含仍被 gate 的 async 默认启用或 unified
  factory 接入。

- raw LevelDB/RocksDB prefix iterator 的底层读取错误不再伪装成 EOF；RocksDB 到达无效位置时
  强制检查 `status()`，ACCOUNT_ASSET 大前缀在首个 capture failure 后立即停扫。
- identity-backed 生产启动使用 strict existing-store open；子目录挂载丢失、空 store 或缺失
  manifest 不会被重新创建。legacy adoption 使用 persisted floor，并在写 PREPARED claim 前执行
  一次完整只读 scrub；协议在 ACTIVE 前再次 scrub 以覆盖 TOCTOU/crash-resume。
- repair marker 会触发 index、temporal、in-flight 全 raw-keyspace scrub；publisher 捕获
  `Throwable` 并转入 fatal，不允许 worker 静默死亡。
- archive drain 超时后 Manager 立即 fail-stop 并保留 chainbase、revoking store 与 session，
  不再继续拆除仍可能被 active reader 使用的依赖。
- JSON-RPC batch 增加 `maxQueriesPerBatch` 与 `batchDeadlineMs`；每个 sub-request 的 archive
  permit 在有界 serialization 后释放。single、batch 临时子响应和最终 batch 均在每次 write
  时增量占用同一进程级 byte budget，batch 通过 `writeTo` 直接拼接，不创建完整 byte-array
  副本；预算持续持有到慢网络写完。overflow/discard 会替换而不是 reset 大 backing array；
  direct error 的 id/message 和总字节有固定小上限，不能通过错误回显绕过预算。
- fatal 已登记但尚未投递时，controller close 会等待 control thread 越过 delivery-start 边界，
  但不等待应用 callback。callback 阻塞或抛错时 watchdog 保持有效；只有 callback 正常返回后
  close 才能取消 watchdog，正常 shutdown 不再吞掉非零 fail-stop。
- canonical block provider、BLOCKHASH 和 transaction lookup 按复合底层读取数保守计费，避免
  backend-read budget 被高层 API 一次调用掩盖。

已通过的本地验证：

- chainbase archive 全量定向回归，包括 identity、journal、temporal、query、account asset、
  archive-off invariant 与 incremental differential。
- framework archive/历史 VM/JSON-RPC/Manager/DB iterator 聚合回归；包含慢客户端并发 retained
  byte budget、并发 batch 构造预算、极小 response limit、batch permit 释放与 archive drain
  fail-stop。
- actuator Storage archive capture、common StorageConfig、framework main/test checkstyle，及
  `git diff --check`。

明确未解除的 gate：

- Item 5 async publisher 仍默认关闭，等待 crash/ENOSPC/fork 与 72h soak。
- Item 6 unified 仍是 factory 外的隔离原型；typed transition、crash-resumable init、migrator、
  downgrade/旧二进制矩阵完成前禁止生产接入。
- 50k-block ABBA、独立 NVMe、真实磁盘故障矩阵和 72h soak 未运行，因此不能宣称性能验收通过。

## 23. ROUND 13 全量对抗复审与修复计划

本轮按写入/capture、journal/恢复、历史查询/VM、资源/性能四条独立 lane 复审当前
未提交实现，并对每个候选做反证。结论是 archive-off/共识路径没有新的 HIGH；确认问题集中在
archive-on 的空库恢复、启动期旁路写和查询资源边界。以下项目在修改生产代码前冻结为本轮计划。

### 23.1 已确认问题

1. **MEDIUM：空 canonical 可能删除不可判定的 in-flight journal。**
   `reconcileInFlightOnStartup(-1, -1, ...)` 当前把全部 journal 当作 orphan 回滚，包括高度大于
   0 或已经 `CANONICAL_COMMITTED` 的记录。canonical 挂载丢失且 archive 尚无 published block
   时，这会销毁唯一恢复证据。空 canonical 只允许自动清理“唯一、height=0、JOURNALED”的
   genesis 前提交记录；任何其他形态必须在修改 journal 前 fail-stop。

2. **MEDIUM：启动期迁移会在 archive capture 之外改写归档域。**
   genesis journal 完成后，asset/ABI/价格历史/blackhole/Turkish-key/config 等启动更新仍可能写
   ACCOUNT、CONTRACT、ABI 或 rooted dynamic properties。新空链要把这些更新纳入 genesis
   `BLOCK_PREPARE`；已有 canonical 链若仍有会改变归档逻辑状态的待执行迁移，archive 模式必须
   在写入前拒绝启动，要求先完成显式迁移/重建，不允许静默旁路 capture。幂等且字节不变的
   config 写不视为逻辑迁移。

3. **MEDIUM：single JSON-RPC 的响应上限发生在依赖库内部完整分配之后。**
   jsonrpc4j 1.6 的 servlet `handle` 先写入自己的无界 `ByteArrayOutputStream`，再复制到当前
   bounded wrapper；并发大响应仍可在第一份缓冲上造成 OOM。single 路径改为直接调用
   `handleRequest(InputStream, boundedOutput)`，和 batch 共用增量 byte reservation，禁止依赖库
   创建无界中间响应。

4. **MEDIUM：historical trace 的 VM 失败可被稍后采样的查询 deadline 覆盖。**
   `ProgramResult` 已含 `OutOfTimeException`/runtime failure 时仍把执行标记为 successfully
   completed，`finally` 会采样并抛出刚到期的 query deadline。修复为：已产生独立 VM failure
   时保留 trace 失败结果；只有正常 VM 结果才允许最后一次 deadline 采样；此前已经记录的
   archive budget terminal 仍保持最高优先级。

5. **MEDIUM：batch deadline 只是后续 admission 检查，不是批次硬期限。**
   临界点获准的最后一个查询当前还能获得完整 per-query deadline。request scope 要把剩余
   batch 时间传给 `QueryContext`，实际 deadline 取 batch/per-query 较小值，并保留
   `BATCH_DEADLINE` 类型；每个子请求完成 serialization 后再次检查，超时则丢弃部分 batch 并
   返回有界 limit error。

6. **LOW：in-flight full scrub 没有关联 token 与 block journal。**
   token 行目前只验证自身 block number，dangling/mismatched token 可通过 repair scrub。扫描时
   要求对应 block 存在且 token 相等；缺 token 继续保留 pre-token legacy fallback。

7. **LOW：fatal callback 可能先于 repair marker 持久化触发进程退出。**
   fatal controller 改为两阶段：先 arm failure/watchdog，再 forced-sync repair marker，最后开放
   callback delivery；marker 写失败作为 suppressed failure 上报。这样 marker 卡死仍由 watchdog
   fail-stop，正常路径又不会在 marker 前退出。

8. **LOW/PERF：普通账户更新在资产完全不变时仍排序、遍历并可能点读资产。**
   old/new `assetOptimized` 相同且 `assetV2` map 相等时，ACCOUNT_ASSET 规划直接返回；ACCOUNT
   自身 capture 保持不变。模式切换、创建、删除和任何资产 map 变化仍走现有完整合成逻辑。

### 23.2 实现顺序与验收

1. 先补空 canonical 的 preflight 测试：允许单个未确认 genesis；拒绝已确认 genesis、多条、
   height>0，且失败后 journal 字节/条数不变。
2. 将空链启动迁移收进 genesis capture；为非空链增加 pending logical migration fail-closed
   guard，并逐字节验证 Constantinople 三个 VM 配置 key 的 live/archive block-0 一致性。
3. single RPC 改用 bounded stream，测试必须使用真实 `JsonRpcServer`，证明超过限制时内部写入
   不会保留完整响应；同时回归 notification、错误码、慢客户端 retained-byte budget。
4. 修复 trace terminal precedence，并覆盖“`ProgramResult` 内含 CPU OOT，随后 deadline 到期”。
5. 传播 batch 剩余期限到 query context，并覆盖排队耗时、最后一个子请求、serialization 后
   超时以及 unlimited batch/per-query 的组合。
6. 补 token orphan/mismatch scrub、fatal arm/marker/deliver 顺序和 unchanged asset fast-path 测试。
7. 运行 chainbase archive/store/query 全量、framework historical VM/JSON-RPC/Manager 聚合、
   common/actuator 定向回归、checkstyle 与 `git diff --check`；随后再做一轮只读对抗复审。

### 23.3 本轮不解除的性能 gate

- 默认同步 LEGACY publisher 仍会在 block push 路径串行执行 journal fsync 以及 solidified publish
  的 index/temporal/journal-delete durable writes。这是已知、可测的延迟成本，不在缺少
  crash/ENOSPC/fork/72h soak 证据时贸然把 async 改为默认。
- unified 单 DB/多 CF 仍不接入生产 factory；50k-block ABBA、真实 NVMe/磁盘故障和 72h soak
  仍是发布前性能验收项。
- mid-chain reader 目前为保证 temporal view 与 in-flight earliest-prev shield 同 generation，
  会在整个 reader/VM 生命周期持有 consistency read lock；最长可把 block journal writer 阻塞到
  查询 deadline。后续优化必须先提供可共享、带 generation 的 immutable shield snapshot，不能
  只提前释放锁而重新引入跨代混读。验收包含 reorg/publish 并发 differential 和 writer p99。
- trace 的 `maxTraceBytes`/`maxTraceResponseBytes` 是逐请求预算，不是全局堆预算；默认并发下理论
  聚合保留量仍偏高，且 memory hex 在当前计费点前分配。后续要增加 coordinator-owned weighted
  trace permit，并把 memory 编码预扣移到分配前；在完成并发 OOM/GC 压测前不宣称全局内存有界。
- 本轮没有把磁盘逻辑行被外部手工删除后的任意历史点自动修复包装成已解决能力；已有 checksum、
  repair marker 和 full scrub 负责可检测故障，无法证明的静默介质篡改继续按 fail-stop/离线修复
  边界处理。

### 23.4 实现结果与最终证据

23.1 的八项问题均已实现并加入回归覆盖。第二轮对抗复审和聚合回归又关闭了以下边界：

- query permit 在队列中等待时也受 batch 硬期限约束，不会等到 acquire timeout 才退出。
- 已发布 journal 在 canonical preflight 前只加载和校验，不做 temporal 修复或删除；canonical
  匹配后才幂等补齐 commit marker。补齐完成后再运行完整 tail/full scrub，避免恢复所需的
  “缺 marker”状态先被启动校验误判。journal 删除失败保持可重试，不写 repair marker。
- genesis canonical session 使用显式 root commit；节点在 block 1 前正常关闭并重开后，canonical
  block 0 和 archive block 0 均持久存在。genesis price history 也在 capture 内形成 block-0 行。
- 已有链的 archive-visible 启动迁移会先 forced-sync 写入 rebuild-required marker 再拒绝启动；
  纯 marker 或字节不变迁移允许执行。
- JSON-RPC 使用延迟 POJO 包装和直接有界序列化；超限时立即中止 collection 遍历并释放 byte
  reservation。single/batch notification 在 deadline 或外围失败时保持无响应语义。
- Account 写路径只序列化一次 archive value；archive-off 路径保持原调用序列。

最终本地验证全部通过：

- `:chainbase:test` archive/store 聚合：612 项覆盖范围全绿，包含 persistent factory、journal、
  temporal、query、identity、archive-off 与 store 捕获。
- `:framework:test` archive/历史 VM/JSON-RPC/Manager 聚合全绿，包含真实关闭重开 genesis、
  historical trace/eth_call、lazy 大集合中止和 notification/deadline。
- `:common:test`、`:actuator:test` 的 StorageConfig、repository adapter、storage capture 定向回归
  全绿；`:framework:checkstyleMain`、`:framework:checkstyleTest` 全绿。

当前没有未关闭的已确认功能性缺陷。23.3 的同步写尾延迟、mid-chain 长读锁、trace 全局堆配额
以及真实磁盘/长稳压测仍是性能发布 gate，不等同于已复现的正确性 bug。
