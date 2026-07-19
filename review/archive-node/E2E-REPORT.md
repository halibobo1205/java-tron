# feat/archive-node x86_64 私链 E2E 验证报告

- 日期：2026-07-19
- 被测分支：`feat/archive-node` @ `d8aad6aec`（docs(archive): record schema 6 E2E results）
- 环境：**Linux x86_64 + OpenJDK 21 + RocksDB 5.15.10（x86 默认映射）+ Gradle 8.14.3**
- 结论：**五个阶段全部通过（21/21 oracle × 4 轮重放 + 损坏注入 fail-stop）**，未发现生产代码缺陷。
- 意义：作者自测（macOS aarch64 / Java 17 / RocksDB 9.7.4）之外的**第二套独立软硬件栈**验证；
  证实 arm64 门控只是保守限制，代码本身跨栈可用（含 RocksDB 5.15 运行期能力探测降级）。

## 1. 与目标栈的偏差（必须如实声明）

本环境无法提供 arm64 + Java 17，经作者授权采用临时补丁在 x86_64 + JDK 21 上运行：

| 补丁 | 文件 | 行为 |
|---|---|---|
| P1 | `build.gradle` | arch/Java 配对检查从硬失败改为告警（仅本验证构建） |
| P2 | `Arch.throwIfUnsupportedJavaVersion` | 增加 `-Dtron.arch.skipJavaCheck=true` 显式旁路 |
| P3 | `ArchiveServiceFactory` | 增加 `-Dtron.archive.allowNonArm64=true` 显式旁路 |
| 构建兼容 | 各 build.gradle | Gradle 8.14 兼容性小修（classifier/baseName/xml.enabled/html.destination → 新 API；跳过 worktree 下的 generateGitProperties） |

完整 diff 见 `temp-guard-patches.patch`（10 文件，+30/−21）。**这些补丁不改变任何归档业务逻辑。**

值得记录的运行期证据：RocksDB 5.15 缺原生查询 deadline 时，代码探测后自动降级——
`archive RocksDB does not support native query deadlines; Java deadline checks remain active around native reads`
（`UnifiedArchiveDb.java:1031`）。5.15/9.7 双兼容是真实现，非纸面声明。

## 2. 链配置

单 SR 私链（p2p discovery 关闭）、`db.engine=ROCKSDB`、出块 3s；
`storage.archive.enable=true`、`identity.initialize=true`、`publisher.async=true`；
committee 开启：合约、SameTokenName、DelegateResource、TvmTransferTrc10、Constantinople、
Solidity059、Istanbul、MultiSign、**AssetOptimization=1**（特意打开以压测 TRC10
资产采集最复杂的 prefix-scan 分支）、NewResourceModel=1 + unfreezeDelayDays=14。

## 3. 交易场景（14 笔，覆盖 8 类域写入）

转账×3（含新账户创建）、FreezeV2（BANDWIDTH + TRON_POWER）、投票、TRC10 发行+转账、
合约部署×2（手写字节码：可读写 slot0 的 fallback 合约 STOR2；SELFDESTRUCT 合约）、
storage 变迁 `111 → 222 → 0`、SELFDESTRUCT 触发、尾部转账。

## 4. Oracle 断言（21 项，全部带精确期望或首采冻结值）

- `eth_getBalance`：账户存在前 `0x0`；每笔转账后精确 sun 值；后续写入后**早期高度重查不变**
- `eth_getStorageAt`：部署时全零 → 111 → 222 → 0，各历史高度精确命中
- `eth_call`（历史 constant call）：读 slot0 在 set111/set222/set0 高度分别返回 111/222/0
- `eth_getCode`：部署前 `0x` → 精确 runtime 字节 → SELFDESTRUCT 后 `0x`（边界高度各一条）

## 5. 阶段结果

| 阶段 | 内容 | 结果 |
|---|---|---|
| E | 场景执行 + oracle 初采 | **21/21** |
| F | 优雅重启（SIGTERM）→ 全量重放 | **21/21** |
| G | 交易流进行中 **SIGKILL** → 崩溃恢复 → 全量重放 → 继续出块 | **21/21**，head 恢复推进 |
| H | `fullScrubOnStartup=true` 全量完整性校验重启 → 重放 | **21/21** |
| 损坏注入 | 最大 SST 中间字节翻转 + scrub 启动 | **fail-stop ✓** `block checksum mismatch` → `ArchiveFatalController` → `ARCHIVE_RUNTIME(1)` 退出，HTTP 未就绪即拒绝服务 |
| I | 恢复备份 → 重启 → 重放 | **21/21** |

## 6. 结论与限制

- 功能、崩溃恢复、完整性 fail-stop、历史查询精确性在 x86_64/JDK21/RocksDB5.15 栈上全部复现，
  与分支自带的 arm64 E2E 报告结论一致。
- 限制：单机单 SR、低负载、短历史（~200 块）；未做网络同步、长稳、容量压测；
  JDK21 非目标运行时（目标 17）。**不构成生产容量认证。**
- 建议：既然 x86 实测可用，可考虑把 `ArchiveServiceFactory` 的 arm64 硬门改为
  "非 arm64 默认拒绝 + 显式配置开关放行"，并在 CI 增加 x86 archive 冒烟测试。

## 7. 复现步骤

```bash
pip install tronpy
python3 setup_keys.py          # 生成 keys.json + 渲染 private-archive.conf
./node.sh start                # 起链（需先按 temp-guard-patches.patch 构建 FullNode.jar）
python3 run_scenario.py        # Phase E：场景 + oracle 采集
./node.sh stop && ./node.sh start
python3 replay_oracles.py F    # Phase F
./node.sh kill9 && ./node.sh start
python3 replay_oracles.py G    # Phase G
```
