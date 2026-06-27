# java-tron Archive L1：config/no-op/dbName 代码级执行包

日期：2026-06-04

关联需求：[tronprotocol/java-tron#6289 Implementation of Archive Node on TRON](https://github.com/tronprotocol/java-tron/issues/6289)

java-tron 源码路径：`/Users/boson/IdeaProjects/java-tron`

java-tron 当前基线：`4e80f8ffa9a2`

上游看板：[java-tron Archive：4e80 分阶段落地执行看板](./20260604-java-tron-archive-4e80-landing-readiness-board.md)

逐文件落点：[java-tron Archive：4e80 逐文件实现落点矩阵](./20260604-java-tron-archive-4e80-file-implementation-map.md)

本文只细化 L1：`config/no-op/dbName`。它把 archive 开关、runtime config、no-op service 边界和 `TronStoreWithRevoking.getDbName()` 修复落成第一批可编码 patch。L1 不做 Manager hook、txNum、Store write hook、temporal DB、JSON-RPC、root/proof。

## 1. L1 完成目标

L1 完成后，java-tron 应满足：

```text
storage.archive.* exists and defaults to disabled
StorageConfig parses archive config through ConfigBeanFactory
Args.applyStorageConfig bridges archive runtime config into CommonParameter.storage
ArchiveService exists with disabled no-op implementation
ArchiveServiceFactory refuses real archive enablement until later landing
TronStoreWithRevoking.getDbName() returns underlying DB name
```

重点是证明“默认关闭不改变行为”和“后续模块有稳定入口”，不是提前实现 archive 功能。

## 2. 当前源码事实

| 文件 | 当前事实 | L1 含义 |
| --- | --- | --- |
| `common/src/main/resources/reference.conf:35-132` | `storage` 默认配置集中在这里；当前没有 `storage.archive` | 新增 archive 默认值 |
| `framework/src/main/resources/config.conf:6-42` | 用户可见 storage 示例 | 同步加 archive 示例，保持 false |
| `common/src/main/java/org/tron/core/config/args/StorageConfig.java:21-33` | `StorageConfig` 是 storage bean | 加 `ArchiveConfig` 嵌套 bean |
| `StorageConfig.java:173-188` | `fromConfig(config)` 绑定 `storage` 并 post-process | 调用 `archive.postProcess()` |
| `common/src/main/java/org/tron/core/config/args/Storage.java:44-112` | runtime storage config 类 | 加 `StorageConfig.ArchiveConfig archive` |
| `framework/src/main/java/org/tron/core/config/args/Args.java:212-244` | `applyStorageConfig(StorageConfig)` 桥接 runtime storage | 写入 `PARAMETER.storage.setArchive(sc.getArchive())` |
| `framework/src/main/java/org/tron/core/config/args/Args.java:713-716` | 重新创建 `PARAMETER.storage` 后绑定 storage | 不新增第二套 raw config 读取 |
| `chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java:56-80` | 构造函数持有 `db`，但 `getDbName()` 返回 null | 改为 `db.getDbName()` |
| `chainbase/src/main/java/org/tron/core/db2/common/DB.java:22` | DB 接口已有 `getDbName()` | `TronStoreWithRevoking` 可直接委托 |
| `LevelDB.java:49-51`、`RocksDB.java:49-52` | 两个实现都返回 data source DB name | dbName 修复可覆盖 LevelDB/RocksDB |

## 3. Patch 边界

### 3.1 允许修改

```text
common/src/main/resources/reference.conf
framework/src/main/resources/config.conf
common/src/main/java/org/tron/core/config/args/StorageConfig.java
common/src/main/java/org/tron/core/config/args/Storage.java
framework/src/main/java/org/tron/core/config/args/Args.java
chainbase/src/main/java/org/tron/core/db/TronStoreWithRevoking.java
chainbase/src/main/java/org/tron/core/archive/ArchiveService.java
chainbase/src/main/java/org/tron/core/archive/ArchiveServiceFactory.java
chainbase/src/main/java/org/tron/core/archive/NoopArchiveService.java
chainbase/src/main/java/org/tron/core/archive/ArchiveException.java
chainbase/src/test/java/org/tron/core/archive/NoopArchiveServiceTest.java
common/src/test/java/org/tron/core/config/args/StorageConfigTest.java
framework/src/test/java/org/tron/core/config/args/StorageArchiveConfigBridgeTest.java
framework/src/test/java/org/tron/core/db/TronStoreWithRevokingArchiveTest.java
```

### 3.2 禁止混入

```text
framework/src/main/java/org/tron/core/db/Manager.java
actuator/src/main/java/org/tron/core/vm/program/Storage.java
chainbase/src/main/java/org/tron/core/store/*Store.java hook changes
ArchiveTxNumIndex
ArchiveDomainRegistry
ArchiveTemporalStore
ArchiveStateReader
CommitmentBuilder
JSON-RPC behavior changes
```

如果 L1 patch 出现以上文件或概念，说明 diff 已经越界。

## 4. Config 结构

### 4.1 HOCON 默认值

在 `reference.conf` 的 `storage {}` 下增加：

```hocon
archive {
  enable = false
  db {
    directory = "archive"
  }
  txnum {
    enable = true
  }
  temporal {
    enable = true
  }
  commitment {
    enable = false
    persistTxRoots = false
  }
  debug {
    enable = false
  }
  coverage = "TVM_STATE_ONLY"
  warnUnclassifiedStoreWrites = true
}
```

同步加到 `framework/src/main/resources/config.conf` 的 `storage {}` 示例中，所有开关保持默认 false/保守值。

使用 `enable` 而不是 `enabled`，因为当前 java-tron config 已有 `node.backup.enable`、`event.subscribe.enable` 等同风格字段。

### 4.2 `StorageConfig` 字段

在 `StorageConfig` 顶层增加：

```java
private ArchiveConfig archive = new ArchiveConfig();
```

建议嵌套类：

```java
@Getter
@Setter
public static class ArchiveConfig {

  private boolean enable = false;
  private DbConfig db = new DbConfig();
  private TxNumConfig txnum = new TxNumConfig();
  private TemporalConfig temporal = new TemporalConfig();
  private CommitmentConfig commitment = new CommitmentConfig();
  private DebugConfig debug = new DebugConfig();
  private String coverage = "TVM_STATE_ONLY";
  private boolean warnUnclassifiedStoreWrites = true;

  void postProcess() {
    if (db.directory == null || db.directory.trim().isEmpty()) {
      throw new IllegalArgumentException("storage.archive.db.directory must not be empty");
    }
    if (coverage == null || coverage.trim().isEmpty()) {
      throw new IllegalArgumentException("storage.archive.coverage must not be empty");
    }
  }

  @Getter
  @Setter
  public static class DbConfig {
    private String directory = "archive";
  }

  @Getter
  @Setter
  public static class TxNumConfig {
    private boolean enable = true;
  }

  @Getter
  @Setter
  public static class TemporalConfig {
    private boolean enable = true;
  }

  @Getter
  @Setter
  public static class CommitmentConfig {
    private boolean enable = false;
    private boolean persistTxRoots = false;
  }

  @Getter
  @Setter
  public static class DebugConfig {
    private boolean enable = false;
  }
}
```

在 `fromConfig` post-process 段追加：

```java
sc.archive.postProcess();
```

不要在 `Args` 里二次读取 `Config`，也不要把 archive config 放到 `NodeConfig.JsonRpcConfig`。

### 4.3 Runtime `Storage`

在 `common/src/main/java/org/tron/core/config/args/Storage.java` 增加：

```java
@Getter
@Setter
private StorageConfig.ArchiveConfig archive = new StorageConfig.ArchiveConfig();
```

这保持 config 对象仍全部在 `common` 模块。不要让 `common` 依赖 `chainbase` 的 archive core 类型。

### 4.4 `Args.applyStorageConfig`

在 `framework/src/main/java/org/tron/core/config/args/Args.java` 的 `applyStorageConfig(StorageConfig sc)` 内追加：

```java
PARAMETER.storage.setArchive(sc.getArchive());
```

建议放在基础 storage 字段桥接之后、dynamic raw storage config 之前。关键是不新增第二条配置链路。

## 5. ArchiveService L1 形状

### 5.1 包位置

```text
chainbase/src/main/java/org/tron/core/archive
```

`chainbase` 已依赖 `common`、`protocol`、`crypto`，可以引用 `StorageConfig.ArchiveConfig`、`BlockCapsule`、`TransactionCapsule`。不要从这里 import `framework` 或 JSON-RPC 类型。

### 5.2 `ArchiveService`

L1 建议只定义 L2 会立即使用的生命周期边界：

```java
package org.tron.core.archive;

import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;

public interface ArchiveService {

  boolean isEnabled();

  void beginBlock(BlockCapsule block);

  void commitBlock(BlockCapsule block);

  void abortBlock(BlockCapsule block);

  void unwindBlock(BlockCapsule block);

  void beginSystemTx(BlockCapsule block, ArchivePhase phase);

  void beginUserTx(BlockCapsule block, int txIndex, TransactionCapsule tx);

  void endTx();
}
```

如果 L1 不引入 `ArchivePhase`，就会在 L2 再改接口；因此 L1 可以同时新增：

```java
public enum ArchivePhase {
  BLOCK_PREPARE,
  USER_TX,
  BLOCK_FINALIZE,
  UNWIND
}
```

### 5.3 `NoopArchiveService`

建议做成 final class 或 enum singleton，所有方法空实现：

```java
package org.tron.core.archive;

import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;

public final class NoopArchiveService implements ArchiveService {

  public static final NoopArchiveService INSTANCE = new NoopArchiveService();

  private NoopArchiveService() {
  }

  @Override
  public boolean isEnabled() {
    return false;
  }

  @Override
  public void beginBlock(BlockCapsule block) {
  }

  @Override
  public void commitBlock(BlockCapsule block) {
  }

  @Override
  public void abortBlock(BlockCapsule block) {
  }

  @Override
  public void unwindBlock(BlockCapsule block) {
  }

  @Override
  public void beginSystemTx(BlockCapsule block, ArchivePhase phase) {
  }

  @Override
  public void beginUserTx(BlockCapsule block, int txIndex, TransactionCapsule tx) {
  }

  @Override
  public void endTx() {
  }
}
```

L1 不建议注册 `DefaultArchiveService` Spring bean。`DefaultArchiveService` 会在 L2/L5 逐步获得 txNum 和 persistence 行为；在 L1 提前注册容易让 `archive.enable=true` 被误认为可用。

### 5.4 `ArchiveServiceFactory`

L1 factory 应该明确阻止真实 enable：

```java
package org.tron.core.archive;

import org.tron.core.config.args.StorageConfig;

public final class ArchiveServiceFactory {

  private ArchiveServiceFactory() {
  }

  public static ArchiveService create(StorageConfig.ArchiveConfig config) {
    if (config == null || !config.isEnable()) {
      return NoopArchiveService.INSTANCE;
    }
    throw new ArchiveException("storage.archive.enable requires archive implementation from later landing");
  }
}
```

这样 L1 允许解析 `storage.archive.enable=true`，但不会让节点以“看似开启、实际 no-op”的方式运行。L2 引入 txNum service 后再放宽 factory 行为。

### 5.5 `ArchiveException`

```java
package org.tron.core.archive;

public class ArchiveException extends RuntimeException {

  public ArchiveException(String message) {
    super(message);
  }

  public ArchiveException(String message, Throwable cause) {
    super(message, cause);
  }
}
```

## 6. `TronStoreWithRevoking.getDbName()`

当前：

```java
@Override
public String getDbName() {
  return null;
}
```

L1 改为：

```java
@Override
public String getDbName() {
  return db.getDbName();
}
```

依据：

- `DB.java:22` 定义了 `String getDbName()`。
- `LevelDB.java:49-51` 返回 `db.getDBName()`。
- `RocksDB.java:49-52` 返回 `db.getDBName()`。

这个修复是 L3 domain registry 的前置条件，但本身不改变 Store 数据行为。

## 7. L1 测试清单

### 7.1 `StorageConfigTest`

文件：

```text
common/src/test/java/org/tron/core/config/args/StorageConfigTest.java
```

新增方法：

| 方法 | Arrange | Assert |
| --- | --- | --- |
| `testArchiveDefaults` | `StorageConfig.fromConfig(ConfigFactory.defaultReference())` | `archive.enable=false`、`db.directory=archive`、`txnum.enable=true`、`temporal.enable=true`、`commitment.enable=false`、`persistTxRoots=false`、`debug.enable=false`、`coverage=TVM_STATE_ONLY` |
| `testArchiveOverride` | HOCON 覆盖 archive subtree | override 全部生效 |
| `testArchiveRejectsEmptyDirectory` | `storage.archive.db.directory=""` | `IllegalArgumentException` |
| `testArchiveRejectsEmptyCoverage` | `storage.archive.coverage=""` | `IllegalArgumentException` |

现有测试使用 JUnit4 风格，保持本地风格即可。不要重写无关 tests。

### 7.2 `StorageArchiveConfigBridgeTest`

文件：

```text
framework/src/test/java/org/tron/core/config/args/StorageArchiveConfigBridgeTest.java
```

建议用 `TemporaryFolder` + `Args.setParam`，测试结束 `Args.clearParam()`。

用例：

| 方法 | Assert |
| --- | --- |
| `defaultArchiveConfigBridgedToRuntimeStorage` | `Args.getInstance().getStorage().getArchive().isEnable() == false` |
| `archiveOverrideBridgedToRuntimeStorage` | 使用临时 config 文件或 test config fallback 后 runtime 可读 override |
| `clearParamDropsRuntimeArchiveConfig` | `Args.clearParam()` 后下一次 setParam 不残留旧 archive config |

如果临时 config 文件构造成本高，L1 可先只保留 default bridge；override 已由 `StorageConfigTest` 覆盖。

### 7.3 `NoopArchiveServiceTest`

文件：

```text
chainbase/src/test/java/org/tron/core/archive/NoopArchiveServiceTest.java
```

`chainbase/src/test` 当前不存在，但 `chainbase/build.gradle:17-40` 已有 test task，可以新增目录。

用例：

| 方法 | Assert |
| --- | --- |
| `noopIsDisabled` | `NoopArchiveService.INSTANCE.isEnabled()` false |
| `noopLifecycleMethodsDoNotThrow` | 所有方法传 null 或简单 mock 不抛异常 |
| `factoryReturnsNoopWhenDisabled` | default `ArchiveConfig` -> `NoopArchiveService.INSTANCE` |
| `factoryRejectsEnabledBeforeImplementation` | `archive.enable=true` -> `ArchiveException` |

### 7.4 `TronStoreWithRevokingArchiveTest`

文件：

```text
framework/src/test/java/org/tron/core/db/TronStoreWithRevokingArchiveTest.java
```

可选两种方式：

| 方式 | 优点 | 断言 |
| --- | --- | --- |
| Spring context + `BaseMethodTest` | 证明真实 Store bean | `chainBaseManager.getAccountStore().getDbName().equals("account")` |
| test subclass | 快、少依赖 | `new TestRevokingTronStore("archive-dbname-test").getDbName()` 返回构造名 |

建议 L1 至少做 test subclass；L3 前再加 Spring binding 测试。现有 `RevokingDbWithCacheNewValueTest.TestRevokingTronStore` 已展示 test subclass 形状。

## 8. L1 验收命令

最小 gate：

```bash
./gradlew :common:test --tests '*StorageConfigTest'
./gradlew :chainbase:test --tests '*NoopArchiveServiceTest'
./gradlew :framework:test --tests '*TronStoreWithRevokingArchiveTest'
./gradlew checkstyleMain checkstyleTest
```

如果新增了 bridge test：

```bash
./gradlew :framework:test --tests '*StorageArchiveConfigBridgeTest'
```

合入前：

```bash
./gradlew build
```

失败测试必须修实现，不能加 skip、`@Ignore` 或条件性 bypass。

## 9. L1 Review Checklist

- [ ] `storage.archive.*` 只在 `storage` config tree 下，不在 `node.jsonrpc` 下。
- [ ] `StorageConfig.fromConfig` 是唯一 parser。
- [ ] `Args.applyStorageConfig` 是唯一 runtime bridge。
- [ ] `common` 没有 import `chainbase`。
- [ ] `chainbase` 没有 import `framework`。
- [ ] `ArchiveServiceFactory` 不让 `archive.enable=true` 静默 no-op。
- [ ] `TronStoreWithRevoking.getDbName()` 委托 `db.getDbName()`。
- [ ] L1 没改 `Manager.java`、TVM `Storage.java`、JSON-RPC。
- [ ] tests 覆盖 default、override、invalid config、noop、dbName。

## 10. 和旧 S1/S2 执行包的差异

[S1/S2 4e80 编码执行包](./20260603-java-tron-archive-s1-s2-4e80-coding-packet.md) 把 S1 和 S2 合在一份文档中，并建议较早引入 `DefaultArchiveService`。本 L1 包把第一批 patch 收窄为：

```text
L1 = config + disabled no-op + dbName
L2 = Manager lifecycle + txNum
```

因此实际编码优先级是：

1. 先按本文完成 L1。
2. L1 gate 通过后，再回到 S1/S2 执行包和 landing board 实现 L2。

这样能避免第一批 patch 同时触碰 config、Spring service、Manager lifecycle 和 txNum 分配，降低 review 和回滚成本。
