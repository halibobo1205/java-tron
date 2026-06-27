# 模块 02 ArchiveDomainRegistry：Erigon 源码对照深挖

日期：2026-05-25

关联设计文档：[java-tron Archive 模块 02：ArchiveDomainRegistry 细化设计](./20260521-java-tron-archive-module-02-domain-registry.md)

前置源码对照：[模块 01 ArchiveTxNumIndex：Erigon 源码对照深挖](./20260523-java-tron-module-01-txnum-index-erigon-source-deep-dive.md)

## 1. 调研范围

本轮深入 `ArchiveDomainRegistry`，对照 Erigon V3 源码确认 domain 的枚举、schema 注册、history/index 配置、domain 依赖、写入映射和 codec 边界，并把这些源码行为映射回 java-tron 的 registry 设计。

主要源码：

- [`db/kv/tables.go`](../../db/kv/tables.go)
- [`db/state/statecfg/statecfg.go`](../../db/state/statecfg/statecfg.go)
- [`db/state/statecfg/state_schema.go`](../../db/state/statecfg/state_schema.go)
- [`db/state/statecfg/version_schema.go`](../../db/state/statecfg/version_schema.go)
- [`db/state/statecfg/version_schema_gen.go`](../../db/state/statecfg/version_schema_gen.go)
- [`db/state/aggregator.go`](../../db/state/aggregator.go)
- [`db/state/domain.go`](../../db/state/domain.go)
- [`db/state/execctx/domain_shared.go`](../../db/state/execctx/domain_shared.go)
- [`execution/state/rw_v3.go`](../../execution/state/rw_v3.go)
- [`db/kv/temporal/kv_temporal.go`](../../db/kv/temporal/kv_temporal.go)

## 2. 核心结论

1. Erigon 的 domain registry 是编译期 schema，不是运行期可变配置。
   domain id 固定在 `kv.Domain` 枚举中，schema 固定在 `statecfg.Schema`，启动时由 `statecfg.Configure` 注册到 Aggregator。

2. Erigon 明确区分 state domains、receipt/cache domains、standalone inverted indices。
   `StateDomains = accounts/storage/code/commitment`；receipt 和 rcache 也是 temporal domain，但不在 StateDomains；log/traces 是独立 inverted index。这对 java-tron 的 root/history/cache 分层很有参考价值。

3. Domain descriptor 不只包含 name/table，还包含 history、index、accessor、compression、large value、snapshot 禁用、版本等。
   java-tron 的 `ArchiveDomainRegistry` 不能只是一张 `store -> domainId` 表，还必须包含 codec、history policy、root policy、snapshot policy 和 schema version。

4. Erigon 的 Store 到 domain 映射不是集中 registry，而是写在 state writer 里。
   `Writer.UpdateAccountData` 写 `AccountsDomain`，`UpdateAccountCode` 写 `CodeDomain`，`WriteAccountStorage` 写 `StorageDomain`。这适合 Ethereum 三域模型，但 java-tron 有 25+ Store，不能照搬散落式映射，必须建立集中 inventory 和 binding。

5. Erigon 的 key/value codec 是手写且 domain-specific。
   Account 使用 `accounts.SerialiseV3`，storage key 是 `address20 || slot32`，code key 是 address。java-tron 必须把这些 codec 显式放进 registry，尤其要处理 TRON 21-byte 地址和 protobuf/capsule 稳定性。

6. Erigon 把 commitment 作为 domain 注册，但其 history/snapshot 默认禁用。
   这说明“可作为 domain 管理”和“必须完整历史化/进入 root”是两个独立策略。java-tron 的 `RootPolicy` / `HistoryPolicy` 设计是必要的。

7. Erigon 的 domain 依赖关系被显式注册。
   `AccountsDomain -> CommitmentDomain`、`StorageDomain -> CommitmentDomain` 的依赖用于文件范围一致性检查。java-tron 也需要 domain dependency / root dependency，避免 root domain 与 state domain 冷热 segment 对不齐。

## 3. Erigon 的 domain 枚举和表命名

### 3.1 表名

`db/kv/tables.go` 为每个 domain 定义四类表：

```text
AccountVals / AccountHistoryKeys / AccountHistoryVals / AccountIdx
StorageVals / StorageHistoryKeys / StorageHistoryVals / StorageIdx
CodeVals    / CodeHistoryKeys    / CodeHistoryVals    / CodeIdx
CommitmentVals / CommitmentHistoryKeys / CommitmentHistoryVals / CommitmentIdx
ReceiptVals / ReceiptHistoryKeys / ReceiptHistoryVals / ReceiptIdx
```

这对应模块 04 `ArchiveTemporalStore` 的三类结构：

- latest/domain values。
- history values。
- inverted index。

对 java-tron 的建议：

```text
domain_latest(domainId, domainKey)
domain_history_vals(domainId, domainKey, txNum)
domain_history_idx(domainId, domainKey)
```

物理表可以按 domain 拆，也可以统一表加 domainId 前缀；关键是 registry 必须声明每个 domain 的 value/history/index 配置。

### 3.2 Domain enum

Erigon 固定：

```text
AccountsDomain   = 0
StorageDomain    = 1
CodeDomain       = 2
CommitmentDomain = 3
ReceiptDomain    = 4
RCacheDomain     = 5
```

并定义：

```text
StateDomains = [AccountsDomain, StorageDomain, CodeDomain, CommitmentDomain]
```

注意：

- `ReceiptDomain` 和 `RCacheDomain` 是 domain，但不在 `StateDomains`。
- `CommitmentDomain` 在 `StateDomains`，但其 history/snapshot 默认禁用。
- log address/topic 和 traces from/to 是 standalone inverted index，不是 state domain。

java-tron 对应设计：

- `IN_GLOBAL_ROOT`、`DOMAIN_ROOT_ONLY`、`HISTORY_ONLY`、`EXCLUDED` 要分开。
- receipt/log/trace/cache 不应默认进入 state root。
- commitment/root 节点可以作为内部 domain，但不等于普通 application state domain。

## 4. DomainCfg / HistCfg 说明

`db/state/statecfg/statecfg.go` 里 `DomainCfg` 包含：

| 字段 | 含义 |
|---|---|
| `Name` | domain enum |
| `ValuesTable` | domain values 表 |
| `Accessors` | domain accessor，如 BTree、HashMap、Existence |
| `Compression` / `CompressCfg` | domain 文件压缩策略 |
| `LargeValues` | value 是否大对象 |
| `ReplaceKeysInValues` | commitment domain 特殊优化 |
| `FileVersion` | domain 文件版本 |
| `Hist` | 历史配置 |

`HistCfg` 包含：

| 字段 | 含义 |
|---|---|
| `ValuesTable` | history values 表 |
| `HistoryLargeValues` | history value 是否大对象 |
| `SnapshotsDisabled` | 禁用 history snapshot 文件 |
| `HistoryDisabled` | 完全跳过 history 写入 |
| `HistoryIdx` | 对应 inverted index enum |
| `IiCfg` | key -> txNum index 配置 |
| `FileVersion` | history 文件版本 |

java-tron 的 `ArchiveDomainRegistry` 需要吸收这些信息，建议每个 domain descriptor 至少包含：

```text
domainId
name
rootPolicy
historyPolicy
latestTable or storage group
historyTable
indexTable
keyCodec
valueCodec
largeValue
prefixScan
snapshotPolicy
compressionPolicy
schemaVersion
activationBlock
```

如果 descriptor 只定义 domainId/name，后面 TemporalStore、StateReader、CommitmentBuilder 会各自发明隐式规则，最终难以验证。

## 5. Schema 注册流程

### 5.1 Configure

`statecfg.Configure` 逐个注册：

```text
RegisterDomain(accounts)
RegisterDomain(storage)
RegisterDomain(code)
RegisterDomain(commitment)
RegisterDomain(receipt)
RegisterDomain(rcache)
RegisterII(log addr/topic/traces)
AddDependency(accounts, commitment)
AddDependency(storage, commitment)
```

这就是 Erigon 的 registry bootstrap。

java-tron 对应：

- 启动 archive 时应加载 domain descriptors。
- 注册 root/history/cache domains。
- 注册 standalone indices。
- 注册 domain dependency。
- 计算 registry checksum。
- 校验本地 archive 数据的 registry checksum。

### 5.2 RegisterDomain

`Aggregator.RegisterDomain` 会：

- 如果全局 disableHistory，强制关闭 history 和 index。
- 创建 `NewDomain(cfg, stepSize, stepsInFrozenFile, dirs, logger)`。
- 保存到 `a.d[cfg.Name]`。
- 为 domain history 和 inverted index 注册依赖。

java-tron 对应：

- `ArchiveDomainRegistry` 不能只给写入端用，也要给 TemporalStore 初始化 domain 存储结构。
- 全局 archive mode 可以影响所有 domain 的 historyPolicy，但必须写入 meta，不能隐式变化。
- Domain registry 的 checksum 应覆盖全局开关，否则同一数据不同节点解释会不一致。

## 6. 具体 domain 配置对照

### 6.1 AccountsDomain

配置：

- values table：`AccountVals`
- compression：无
- accessors：BTree + Existence
- history values：`AccountHistoryVals`
- history index：`AccountsHistoryIdx`
- history large values：false

写入：

- `Writer.UpdateAccountData` 用 `accounts.SerialiseV3(account)` 编码。
- domain key 是 address value。

java-tron 对应：

- `ACCOUNT` domain 的 value codec 必须明确，不要直接依赖不稳定 Java object。
- AccountCapsule 字段多，建议先定义 canonical `account_v1`，并明确哪些字段进入 root。
- 地址建议统一成 21-byte TRON address，而不是 Erigon 的 20-byte Ethereum address。

### 6.2 StorageDomain

配置：

- values table：`StorageVals`
- compression：key compression
- accessors：BTree + Existence
- history index：`StorageHistoryIdx`

写入：

- key 是 `address20 || slot32`。
- zero/empty storage value 会执行 `DomainDel`。
- `DomainDelPrefix(StorageDomain, address)` 用于合约创建/账户删除时清理 storage。

java-tron 对应：

- `CONTRACT_STORAGE` key 应固定为 `contractAddress21 || slot32`。
- 必须支持 prefix delete / prefix scan。
- storage zero 值和删除语义要统一；如果 TVM 语义中 zero 等价删除，则 TemporalStore 写 delete，否则必须保留 zero leaf。
- 合约重建/删除需要 domain-level prefix tombstone 或展开为逐 slot 删除。Erigon 用 `DomainDelPrefix`，java-tron 需要评估存储成本。

### 6.3 CodeDomain

配置：

- values table：`CodeVals`
- compression：value compression
- large values：true
- history large values：true

写入：

- key 是 address。
- value 是 code bytes。

java-tron 对应：

- `CONTRACT_CODE` 应声明 `largeValue=true`。
- 如果 java-tron 以 code hash 存代码，registry 需要固定 domain key 是 address 还是 codeHash。
- `eth_getCode(address, blockTag)` 更适合 address -> historical code；如果底层按 codeHash 去重，可在 Registry/StateReader 里定义双层读取，不要让 API 自己猜。

### 6.4 CommitmentDomain

配置：

- values table：`CommitmentVals`
- compression：key compression
- accessors：HashMap
- `ReplaceKeysInValues=true`
- history/snapshot 默认禁用

依赖：

- AccountsDomain -> CommitmentDomain
- StorageDomain -> CommitmentDomain

含义：

- commitment 是 domain，但不是普通 application state。
- commitment 的 history 策略和 account/storage 不同。
- commitment 文件必须与依赖 domain 对齐。

java-tron 对应：

- `CommitmentBuilder` 的节点/root 可以单独作为 internal domain。
- 不应默认把 commitment nodes 当普通 historical state 查询。
- 如果要支持历史 proof，先用 checkpoint/replay，而不是把所有 commitment nodes 全量 temporal 化。
- Registry 要支持 `INTERNAL_COMMITMENT` 或类似分类。

### 6.5 ReceiptDomain / RCacheDomain / Standalone Index

Erigon 将 receipt、rcache 作为 domain，但不放入 `StateDomains`。Log topic/address、trace from/to 是 standalone inverted indices。

java-tron 对应：

- receipt/log/event/trace 更适合 `HISTORY_ONLY` 或 standalone index。
- 它们可以服务 RPC 查询，但不应进入 global state root。
- Registry 应能表达“不是 root domain，但有 history/index/segment”的对象。

## 7. Store 到 domain 映射的位置

Erigon 的映射不在 schema 文件里，而在写入路径中：

| 写入函数 | Domain |
|---|---|
| `UpdateAccountData` | `AccountsDomain` |
| `UpdateAccountCode` | `CodeDomain` |
| `DeleteAccount` | `AccountsDomain`，并联动 code/storage |
| `WriteAccountStorage` | `StorageDomain` |
| `CreateContract` | `DomainDelPrefix(StorageDomain, address)` |
| `ApplyStateWrites` | 将 VersionedWrites 分解到账户、代码、storage domain |

这对 Erigon 是可接受的，因为 Ethereum 状态模型天然就是 account/storage/code 三域；写入路径集中在 state writer。

java-tron 不应照搬这种方式：

- java-tron 的 canonical state 分布在多类 Store。
- Actuator、VM、maintenance、resource、governance 都可能写状态。
- 如果把映射散落到每个 actuator/store，很容易漏。

因此模块 02 里的 centralized `StoreBinding` / inventory 是必要的。

## 8. DomainPut 的 no-op、prevValue 和 delete 语义

`SharedDomains.DomainPut`：

- 禁止 `v == nil`。
- 如果 `prevVal == nil`，从 latest 读取 prev。
- 对 accounts/storage/code/commitment，如果 prev 与 new 相同，直接 no-op。
- 写入 `TemporalMemBatch.DomainPut(domain, key, value, txNum, prevVal)`。

`DomainDel`：

- 读取 prevValue。
- 删除 account 时会删除 storage prefix 和 code。
- code prev 不存在时直接 no-op。
- 最终写入 `TemporalMemBatch.DomainDel`。

java-tron 对应：

- Registry 只负责编码 value，删除应由 Collector/TemporalStore 表达，不要把 `null` value 编码成普通 value。
- no-op 判断应在 canonical domain value bytes 层做。
- 删除账户/合约时是否联动 code/storage 必须在 registry 或 collector 规则中显式化。
- `prevValue` 可以由 Collector/TemporalStore 读取，但必须以 domain canonical key/value 为准。

## 9. Parallel executor 对 registry/collector 的启发

`BlockStateCache` 的注释非常关键：

- parallel executor 的 per-tx writes 先进入 block-level cache。
- cache 的 `writeLog` 记录每个 Write/Delete 的原始 txNum。
- block boundary flush 时按 writeLog 顺序重放，每条仍使用原来的 per-tx txNum。
- 如果 flush 时统一盖 block finalize txNum，会把 per-tx history 合并成一条，打坏历史读。

这虽然更偏模块 03，但对模块 02 也有启发：

- domain mapping 的主键必须是 `domainId + domainKey`，而不是原始 Store key。
- collector/flush 不能丢失 per-tx domain mapping。
- java-tron 如果将状态写先缓存在 Snapshot/Revoking 层，最终 flush 仍必须保留每条 logical tx 的 domainId/key/value。

## 10. Version / Schema 管理

Erigon 使用生成的 `version_schema_gen.go` 和 `version_schema.go` 管理 domain/history/index 文件版本：

- account/code/storage/commitment 等各自有 `.kv`、`.bt`、`.kvi`、`.v`、`.vi`、`.ef`、`.efi` 版本。
- `SchemeMinSupportedVersions` 记录各 domain 文件后缀的最小支持版本。

java-tron 不一定要复制 Erigon 的文件后缀体系，但必须有同等能力：

```text
domain schema version
key codec version
value codec version
history/index format version
segment file version
activation block
min supported version
```

这也强化了模块 02 设计里的 `registryChecksum`：checksum 应覆盖 domain descriptor、binding、codec version、root/history policy 和 activation。

## 11. java-tron 模块 02 设计修正建议

### 11.1 DomainDescriptor 增补字段

建议从 Erigon 的 `DomainCfg` 衍生出 java-tron descriptor：

```text
DomainDescriptor:
  domainId
  name
  rootPolicy
  historyPolicy
  storageClass: STATE | INTERNAL_COMMITMENT | QUERY_CACHE | STANDALONE_INDEX
  keyCodec
  valueCodec
  largeValue
  prefixDeleteSupported
  prefixScanSupported
  latestAccessor: BTREE | HASH | EXISTENCE | NONE
  historyIndexAccessor
  compressionPolicy
  snapshotPolicy
  dependencies
  activationBlock
  schemaVersion
```

### 11.2 Root domain 和 query/cache domain 分离

Erigon 的 `StateDomains` 与 receipt/cache/index 的分离应明确进入 java-tron registry：

```text
ACCOUNT / CONTRACT_STORAGE / CONTRACT_CODE / ... -> root candidates
RECEIPT / LOG / TRACE / ACCOUNT_TRACE -> history/query only
COMMITMENT_NODES -> internal commitment
LOCAL_NODE_METADATA -> excluded
```

### 11.3 StoreBinding 必须可审计

Erigon 没有集中 StoreBinding，因为 Ethereum 状态写入口少。java-tron 必须建立：

```text
store_id + operation + key shape -> domain mapping
```

并做覆盖测试：

- 所有 state Store 都有 binding 或 explicit exclusion。
- 新增 Store 时测试失败。
- excluded 必须有 reason。

### 11.4 Prefix delete 是一等能力

Erigon 的 storage cleanup 依赖 `DomainDelPrefix(StorageDomain, address)`。java-tron 对合约销毁、重建、账户删除、资源解绑等场景也可能需要 prefix delete。

Registry 需要为每个 domain 标注：

```text
prefixDeleteSupported
prefixKeyLayout
deleteExpansionStrategy
```

否则 Collector/TemporalStore 不知道是写一个 prefix tombstone，还是展开成逐 key 删除。

### 11.5 Codec 稳定性比 Erigon 更难

Erigon 的 account codec 是 Go 里的 `accounts.SerialiseV3`，状态字段有限。java-tron 的 protobuf/capsule 状态更复杂：

- map/repeated 排序。
- 默认值。
- unknown fields。
- 新旧版本字段。
- Store value 是否含派生缓存。

源码对照后更建议 java-tron 第一阶段不要直接把 `Capsule.toByteArray()` 当 root value，除非逐类证明稳定。至少 root domain 应有 canonical encoder。

## 12. 建议补充到模块 02 的接口

结合 Erigon 源码，建议增加：

```java
DomainDescriptor descriptor(short domainId);

Collection<DomainDescriptor> rootStateDomains();

Collection<DomainDescriptor> historyOnlyDomains();

Collection<DomainDescriptor> standaloneIndices();

Collection<DomainDependency> dependencies();

boolean supportsPrefixDelete(short domainId);

byte[] encodePrefixDeleteKey(short domainId, Object query);

CodecVersion codecVersion(short domainId, long blockNum);
```

并在 registry checksum 中纳入：

```text
domain descriptor
store binding
codec versions
dependency graph
root/history/snapshot policy
activation block
```

## 13. 下一步源码对照问题

如果继续深入模块 02，可补三项：

1. 追 `accounts.SerialiseV3` / `DeserialiseV3` 的具体字段和稳定性，作为 java-tron AccountCapsule canonical codec 的参考。

2. 追 `TemporalMemBatch.DomainPut` 如何把 domain changes 转成 history/index，确认 no-op、prevVal、creation marker 的边界。这会同时服务模块 03/04。

3. 追 `DomainDelPrefix` 的实现成本和语义，判断 java-tron 是应该使用 prefix tombstone，还是在 delete 时展开所有历史 key。

## 14. 对 java-tron 的落地判断

模块 02 原设计方向是正确的，但源码对照后要把“registry”理解得更宽：

```text
ArchiveDomainRegistry = domain schema + store binding + codec catalog + root/history policy + dependency graph + version/activation metadata
```

它不是简单枚举。对 java-tron 来说，Registry 是防止状态树漏 Store、codec 不稳定、root 覆盖范围说不清的核心工程控制面。

PoC 可以只实现 3 个 domain，但必须同时产出 observe-mode inventory，持续统计未映射 Store。只有当 strict 模式下所有 canonical state Store 都有 binding 或明确排除时，才能把 root 从 `PARTIAL` 升级为 `COMPLETE_ARCHIVE`。
