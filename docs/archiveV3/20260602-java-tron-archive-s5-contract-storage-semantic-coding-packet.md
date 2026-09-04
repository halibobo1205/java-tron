# java-tron Archive S5：Contract Storage Semantic Hook 编码执行包

> 2026-06-03 更新：本文是旧 `a79693e450` 编码包。当前 `4e80f8ffa9a2` 的 S4/S5 编码入口请看 [java-tron Archive S4/S5：WriteCollector 与 Storage Semantic Hook 4e80 编码执行包](./20260603-java-tron-archive-s4-s5-write-collector-storage-4e80-coding-packet.md)，旧行号和部分路径不可直接用于编码。

本文把 S5/PR4 的 `CONTRACT_STORAGE` semantic hook 落到可编码粒度。S4 已完成 raw Store hook 和 collector 模型；S5 只补 TVM storage 的语义事件来源，把 java-tron 的 physical `storage-row` 写转换为 archive 可查询的 logical `(contractAddress, slot32)` 写。

关联文档：

- 当前 4e80 S4/S5 编码入口：[java-tron Archive S4/S5：WriteCollector 与 Storage Semantic Hook 4e80 编码执行包](./20260603-java-tron-archive-s4-s5-write-collector-storage-4e80-coding-packet.md)
- S4 WriteCollector 编码执行包：[java-tron Archive S4：ArchiveWriteCollector 编码执行包](./20260602-java-tron-archive-s4-write-collector-coding-packet.md)
- S3 DomainRegistry 编码执行包：[java-tron Archive S3：ArchiveDomainRegistry 编码执行包](./20260602-java-tron-archive-s3-domain-registry-coding-packet.md)
- PR3/PR4 规格：[java-tron Archive PR3/PR4 WriteCollector 代码级实现规格](./20260602-java-tron-archive-pr3-pr4-write-collector-implementation-spec.md)
- 模块 03 patch checklist：[java-tron Archive 模块 03：ArchiveWriteCollector 逐文件 Patch 清单](./20260602-java-tron-archive-module-03-write-collector-patch-checklist.md)
- java-tron 源码对照：[模块 03 ArchiveWriteCollector：java-tron 源码对照](./20260601-java-tron-module-03-write-collector-java-tron-source-deep-dive.md)

## 1. S5 交付边界

S5 交付：

```text
Storage.commit()
  -> SemanticStoreWrite.contractStorage(address21, slot32, beforeValue, afterValue)
  -> DefaultArchiveWriteCollector.onSemanticWrite(...)
  -> DomainWrite(CONTRACT_STORAGE, address21 || slot32 || storageKeyVersion_u8, before, after)
```

S5 不交付：

```text
ArchiveTemporalStore persistence
archive state root
historical RPC
raw storage-row physical key decoding
full storage trie/proof
```

S5 完成后应能证明：

1. `CONTRACT_STORAGE` 的 canonical key 固定是 `address21 || slot32 || storageKeyVersion_u8`。
2. raw `storage-row` put/delete 不会生成 `CONTRACT_STORAGE` write。
3. child repository、VM revert、exception path 不会输出最终 storage write。
4. zero storage 归一成 tombstone/null：`absent -> zero` 是 no-op，`nonzero -> zero` 是 delete。
5. create2 只影响 physical row key；contractVersion 先归一为 `storageKeyVersion_u8`，不把 raw physical key 写进 archive key。
6. delegatecall/callcode 使用 context address 作为 storage owner，不使用 code address。

## 2. java-tron 源码事实

### 2.1 `Storage` 内部结构

| 源码 | 事实 | S5 结论 |
| --- | --- | --- |
| `Storage.java:18-19` | `rowCache` 是 `Map<DataWord, StorageRowCapsule>` | map key 就是 logical slot |
| `Storage.java:20-25` | `Storage` 持有 `addrHash`、`store`、`address` | `address` 是 storage owner |
| `Storage.java:26-27` | `contractVersion` 控制 physical key compose | 归一成 `storageKeyVersion_u8` 进入 canonical key suffix，raw physical key 不进入 canonical key |
| `Storage.java:29-33` | constructor 用原始 address 生成 `addrHash` | `Storage.address` 保留 owner address |
| `Storage.java:35-43` | copy constructor clone `address/addrHash/rowCache` | child repository deep copy 保留 logical slot |
| `Storage.java:46-53` | `compose` 对 physical row key 做截断/拼接，version 1 先 hash slot | physical key 不能反推 logical slot |
| `Storage.java:61-70` | create2 用 `addrHash(address, trxHash)` 改 physical prefix | create2 更证明 physical key 不能当 archive key |
| `Storage.java:73-83` | `getValue` missing row 返回 null | reader 语义应把 missing 当 zero |
| `Storage.java:86-93` | `put` 只改 rowCache，不直接落盘 | 不在这里发最终 `DomainWrite` |
| `Storage.java:96-105` | `commit` dirty row 为 zero 时 delete，非 zero 时 put | S5 semantic hook 放在这里 |

### 2.2 Repository 落盘边界

| 源码 | 事实 | S5 结论 |
| --- | --- | --- |
| `RepositoryImpl.java:660-664` | `putStorageValue` 只调用 `storage.put(key,value)` | 这里只是 intent/cache，不是最终落盘 |
| `RepositoryImpl.java:673-686` | `getStorageInternal` 转成 Tron 21-byte address 并缓存 `Storage` | S5 address 应使用 21-byte owner address |
| `RepositoryImpl.java:690-713` | root 创建 `new Storage(address, getStorageRowStore())`；设置 contractVersion/create2 addrHash | S5 可以从 `Storage` 读 address/contractVersion |
| `RepositoryImpl.java:753-770` | `commit()` 依次 flush caches | storage 在 root commit 阶段处理 |
| `RepositoryImpl.java:1001-1008` | `deposit != null` 时只写 parent cache；`deposit == null` 才 `storage.commit()` | S5 hook 只会在 root repository persistence 阶段触发 |

### 2.3 TVM storage owner

| 源码 | 事实 | S5 结论 |
| --- | --- | --- |
| `Program.java:1281-1284` | SSTORE 通过 `getContextAddress()` 写 storage | owner 是 context address |
| `Program.java:1444-1446` | SLOAD 也用 `getContextAddress()` | S5 write/read owner 必须一致 |
| `Program.java:1015-1020` | CALLCODE/DELEGATECALL 时 `contextAddress = senderAddress`，否则 code address | delegatecall/callcode 写调用者 storage |
| `Program.java:1131-1139` | child ProgramInvoke 使用 `new DataWord(contextAddress)` | child execution 延续 context owner |
| `ContractState.java:34-36` | `ContractState.address = programInvoke.getContractAddress()` | listener/callback 中 address 是 context owner |
| `ContractState.java:150-154` | `putStorageValue` 先 trace listener，再 repository put | trace listener 不是 archive canonical source |

### 2.4 `DataWord` 语义

| 源码 | 事实 | S5 结论 |
| --- | --- | --- |
| `DataWord.java:47` | word size 是 32 | slot/value 都按 32 bytes |
| `DataWord.java:162-164` | `getData()` 返回内部 array | `SemanticStoreWrite` 必须 clone |
| `DataWord.java:268-272` | `isZero()` 遍历 32 bytes | zero value 归一为 delete/tombstone |
| `DataWord.java:459-460` | `clone()` 复制 data | `Storage.put` 已经从 Program 层传 clone |

## 3. Canonical Storage Domain

### 3.1 canonical key

`CONTRACT_STORAGE` canonical key 固定为：

```text
address21 || slot32 || storageKeyVersion_u8
```

其中：

- `address21`：TRON 21-byte address，来自 `Storage.address`。
- `slot32`：logical slot，来自 `rowCache` 的 `DataWord` key。
- `storageKeyVersion_u8`：由 `Storage.contractVersion` 归一得到；`0x00` 表示 raw slot 语义，`0x01` 表示 java-tron contractVersion 1 语义。

不要加入：

- physical `storage-row` key；
- raw `contractVersion` 整数；
- create2 `trxHash`；
- `addrHash`；
- code address。

原因：physical 信息只影响 java-tron Store 布局；历史查询按 `address, slot` 定位后，还需要当前/目标历史点的 `storageKeyVersion_u8` 选择同一套 storage key 语义。

### 3.2 canonical value

`CONTRACT_STORAGE` value 只接受两种形态：

```text
null     -> tombstone/delete/zero
32 bytes -> non-zero storage word
```

归一规则：

```java
byte[] normalizeStorageValue(byte[] value) {
  if (value == null || DataWord.isZero(value)) {
    return null;
  }
  if (value.length != DataWord.WORD_SIZE) {
    throw new ArchiveException("contract storage value must be 32 bytes");
  }
  return value.clone();
}
```

这样 collector 的通用归并规则自然成立：

| 物理状态变化 | semantic before | semantic after | 输出 |
| --- | --- | --- | --- |
| absent -> zero | null | null | no-op |
| absent -> nonzero | null | value32 | create |
| nonzero A -> nonzero B | A | B | update |
| nonzero A -> zero | A | null | delete |
| nonzero A -> A | A | A | no-op |

不要把 32-byte zero 当作普通 afterValue 交给 temporal/root。否则 `absent -> zero` 会制造无意义 history，root 也会错误包含 zero slot。

## 4. S5 分片

建议拆成 5 个小 patch：

```text
S5a: SemanticStoreWrite.contractStorage 完整字段和归一规则
S5b: StorageRowStore 暴露 ArchiveService
S5c: Storage.commit semantic hook
S5d: registry mapSemanticWrite(CONTRACT_STORAGE) 和 raw storage-row ignore tests
S5e: VM/repository storage lifecycle tests
```

如果 S4 已经实现 `SemanticStoreWrite` 和 `onSemanticWrite`，S5a 只补 contract storage factory 和 validation。

## 5. 文件级改动

新增/修改：

```text
chainbase/src/main/java/org/tron/core/archive/collector/SemanticStoreWrite.java
chainbase/src/main/java/org/tron/core/archive/domain/DefaultArchiveDomainRegistry.java
chainbase/src/main/java/org/tron/core/store/StorageRowStore.java
actuator/src/main/java/org/tron/core/vm/program/Storage.java
framework/src/test/java/org/tron/core/archive/collector/ArchiveContractStorageSemanticWriteTest.java
framework/src/test/java/org/tron/common/runtime/vm/ArchiveStorageHookTest.java
```

不建议修改：

```text
actuator/src/main/java/org/tron/core/vm/repository/RepositoryImpl.java
```

除非后续发现必须传递额外 ArchiveService dependency。P0 推荐通过 `StorageRowStore` 获取 `ArchiveService`，避免改变 `RepositoryImpl` 的手工构造路径。

## 6. `SemanticStoreWrite`

S5 推荐结构：

```java
public final class SemanticStoreWrite {
  public enum Kind {
    CONTRACT_STORAGE
  }

  private final Kind kind;
  private final byte[] address;
  private final byte[] logicalSlot;
  private final byte[] beforeValue;
  private final byte[] afterValue;
  private final byte[] physicalKey;
  private final int storageKeyVersion;
  private final String source;

  public static SemanticStoreWrite contractStorage(
      byte[] address,
      byte[] logicalSlot,
      byte[] beforeValue,
      byte[] afterValue,
      byte[] physicalKey,
      int storageKeyVersion) {
    ...
  }
}
```

字段语义：

| 字段 | 是否进入 canonical key/value | 用途 |
| --- | --- | --- |
| `kind` | no | dispatch |
| `address` | key | 21-byte owner address |
| `logicalSlot` | key | 32-byte TVM slot |
| `storageKeyVersion` | key | 1-byte normalized java-tron storage key semantics |
| `beforeValue` | value | normalized null/nonzero32 |
| `afterValue` | value | normalized null/nonzero32 |
| `physicalKey` | no | debug/assertion |
| `source` | no | diagnostics |

验证：

- `address.length == 21`。
- `logicalSlot.length == 32`。
- `storageKeyVersion == 0 || storageKeyVersion == 1`。
- `beforeValue == null || beforeValue.length == 32`。
- `afterValue == null || afterValue.length == 32`。
- zero before/after 归一为 null。
- clone 所有 byte arrays。

`storageKeyVersion` 是 domain key 的业务语义 suffix，不是 archive codec 版本。`physicalKey` 只保留给测试和诊断，不参与 canonical key。

## 7. `StorageRowStore` 暴露 ArchiveService

文件：

```text
chainbase/src/main/java/org/tron/core/store/StorageRowStore.java
```

新增：

```java
@Autowired(required = false)
private ArchiveService archiveService = ArchiveService.NOOP;

public ArchiveService getArchiveService() {
  return archiveService == null ? ArchiveService.NOOP : archiveService;
}
```

注意：

- 不要让 `Storage` 成为 Spring bean。
- 不要在 `RepositoryImpl` 上加 `@Autowired ArchiveService`。
- `ArchiveService.NOOP` 避免 test/manual construction 下 NPE。

可选修复：

```java
@Override
public StorageRowCapsule get(byte[] key) {
  StorageRowCapsule row = getUnchecked(key);
  if (row != null) {
    row.setRowKey(key);
  }
  return row;
}
```

这是现有 missing row NPE 风险。若 S5 测试触发，可作为同 PR 的局部安全修复；不要用 `@Ignore` 或跳过测试绕过。

## 8. `Storage.commit` semantic hook

文件：

```text
actuator/src/main/java/org/tron/core/vm/program/Storage.java
```

当前：

```java
public void commit() {
  rowCache.forEach((DataWord rowKey, StorageRowCapsule row) -> {
    if (row.isDirty()) {
      if (new DataWord(row.getValue()).isZero()) {
        this.store.delete(row.getRowKey());
      } else {
        this.store.put(row.getRowKey(), row);
      }
    }
  });
}
```

推荐：

```java
public void commit() {
  rowCache.forEach((DataWord logicalSlot, StorageRowCapsule row) -> {
    if (!row.isDirty()) {
      return;
    }

    byte[] physicalKey = row.getRowKey();
    StorageRowCapsule oldRow = store.getUnchecked(physicalKey);
    byte[] beforeValue = normalizeStorageValue(oldRow == null ? null : oldRow.getValue());
    byte[] rawAfter = row.getValue();
    byte[] afterValue = normalizeStorageValue(rawAfter);

    ArchiveService archiveService = store.getArchiveService();
    if (archiveService != null && archiveService.shouldCollectStoreWrites()) {
      archiveService.onSemanticWrite(SemanticStoreWrite.contractStorage(
          address,
          logicalSlot.getData(),
          beforeValue,
          afterValue,
          physicalKey,
          storageKeyVersion(contractVersion)));
    }

    if (afterValue == null) {
      this.store.delete(physicalKey);
    } else {
      this.store.put(physicalKey, row);
    }
  });
}
```

关键点：

- before 必须在 physical put/delete 前读。
- before 使用 `store.getUnchecked(physicalKey)`，不要用 `store.get(physicalKey)`。
- `logicalSlot.getData()` 传入后由 `SemanticStoreWrite` clone。
- `address` 传入后由 `SemanticStoreWrite` clone。
- physical delete 判断使用 normalized `afterValue == null`，和 `new DataWord(rawAfter).isZero()` 等价。
- raw `storage-row` base hook 仍会看到 put/delete；registry 必须 ignore。

如果不想在 `Storage` 中新增 private normalize helper，也可放到 `SemanticStoreWrite.contractStorage` 内。推荐两处都保持简单：

```java
private static byte[] normalizeStorageValue(byte[] value) {
  if (value == null || DataWord.isZero(value)) {
    return null;
  }
  if (value.length != DataWord.WORD_SIZE) {
    throw new ArchiveException("contract storage value must be 32 bytes");
  }
  return value;
}
```

由 factory clone，`Storage.commit` 不需要 clone。

## 9. Registry semantic mapping

S3/S4 registry 增加：

```java
StoreWriteMapping mapSemanticWrite(SemanticStoreWrite write);
```

`CONTRACT_STORAGE` mapping：

```text
domain = CONTRACT_STORAGE
domainKey = address21 || logicalSlot32
beforeValue = write.beforeValue
afterValue = write.afterValue
```

`storage-row` raw mapping：

```text
mapStoreWrite(dbName=storage-row) -> IGNORED / reason=SEMANTIC_ONLY
```

collector 不允许自己写：

```java
if ("storage-row".equals(dbName)) ...
```

所有判断来自 `ArchiveDomainRegistry`。

## 10. 生命周期约束

### 10.1 child repository

`RepositoryImpl.commitStorageCache`：

```java
if (deposit != null) {
  deposit.putStorage(address, storage);
} else {
  storage.commit();
}
```

S5 只在 `Storage.commit()` 发 semantic write，因此 child repository commit 不会输出最终 write。child writes 只有合并到 root repository 并最终 commit 时才进入 collector。

### 10.2 VM revert/exception

`VMActuator.execute` 在 exception/revert 时不走 `rootRepository.commit()`。因此 S5 放在 `Storage.commit()` 可以自然避开 revert/exception 中间态。

禁止：

```text
RepositoryImpl.putStorageValue -> final DomainWrite
Storage.put                  -> final DomainWrite
ContractState.putStorageValue -> final DomainWrite
```

这些位置都可能位于 child repository 或 reverted VM frame 内。

### 10.3 constant/pre-exec/broadcast

即使 `Storage.commit()` 被非 canonical 路径触发，也必须经过：

```java
archiveService.shouldCollectStoreWrites()
```

S4 定义该方法只在 archive enabled + active canonical tx context 时为 true。

### 10.4 delegatecall/callcode

SSTORE/SLOAD 使用 `Program.getContextAddress()`。`CALLCODE/DELEGATECALL` 下 context address 是 caller/sender address，不是 code address。

S5 测试必须证明：

```text
codeAddress != contextAddress
DomainWrite.key uses contextAddress || slot32
```

如果构造完整 delegatecall 集成测试成本高，先用 Program/Repository 层单测覆盖 context owner，集成测试列入 P1。

## 11. 测试矩阵

### 11.1 `SemanticStoreWrite` unit

文件：

```text
framework/src/test/java/org/tron/core/archive/collector/ArchiveContractStorageSemanticWriteTest.java
```

用例：

1. address 必须 21 bytes。
2. logical slot 必须 32 bytes。
3. nonzero before/after 保持 32 bytes。
4. zero before 归一为 null。
5. zero after 归一为 null。
6. input byte arrays 后续修改不影响 event。
7. physicalKey 不参与 canonical key，storageKeyVersion 参与 canonical key suffix。

### 11.2 registry semantic mapping

用例：

1. `SemanticStoreWrite.contractStorage(addr,slot,null,value)` 输出 `CONTRACT_STORAGE`。
2. `domainKey == addr || slot || storageKeyVersion_u8`。
3. before/after zero 已归一为 null。
4. raw `StoreWriteEvent(dbName=storage-row)` 返回 ignored。
5. raw `storage-row` ignored stats 不影响 semantic mapping。

### 11.3 `Storage.commit` unit

文件：

```text
framework/src/test/java/org/tron/core/archive/collector/ArchiveStorageCommitHookTest.java
```

用例：

1. dirty nonzero row：先读 before，再 `onSemanticWrite`，再 `store.put`。
2. dirty zero row：先读 before，再 `onSemanticWrite(after=null)`，再 `store.delete`。
3. absent -> zero：collector 最终 no-op。
4. unchanged loaded row 不 dirty：不发 semantic write。
5. archive disabled/no active tx：不读取 before，不发 semantic write。
6. `StorageRowStore.get` missing row 不 NPE，或 S5 hook 绕开它。

### 11.4 repository lifecycle

文件：

```text
framework/src/test/java/org/tron/common/runtime/vm/ArchiveStorageHookTest.java
```

用例：

1. `putStorageValue` 后不 commit：不发 final semantic write。
2. child repository commit 到 parent：不发 final semantic write。
3. root repository commit：发 final semantic write。
4. VM revert：不发 storage semantic write。
5. same tx same slot 多次写：collector 输出 first-before/final-after。
6. nonzero -> zero：collector 输出 delete/tombstone。

### 11.5 TVM integration

可复用现有：

```text
framework/src/test/java/org/tron/common/runtime/vm/StorageTest.java
```

新增 archive 专用用例或新文件：

1. 合约 `SSTORE` 非零值，collector 收到 `address21 || slot32 || storageKeyVersion_u8`。
2. 覆盖同一 slot，输出 final value。
3. delete slot，输出 tombstone/null。
4. raw `storage-row` hook 不产生第二条 `CONTRACT_STORAGE`。
5. `contractVersion=1` 时 physical key 变化，canonical key suffix 为 `0x01`。
6. create2 `generateAddrHash(trxHash)` 后 physical key 变化，logical key 不变。
7. delegatecall/callcode 使用 context owner address。

不要新增 `@Ignore` 或等价跳过。

## 12. 验收检索

实现后建议检索：

```text
rg -n 'putStorageValue\\(|Storage\\.put\\(' actuator/src/main/java/org/tron/core/vm
rg -n 'onSemanticWrite|SemanticStoreWrite\\.contractStorage' actuator/src/main/java chainbase/src/main/java
rg -n 'storage-row|CONTRACT_STORAGE' chainbase/src/main/java/org/tron/core/archive
rg -n 'physicalKey|contractVersion|addrHash' chainbase/src/main/java/org/tron/core/archive
```

期望：

- `RepositoryImpl.putStorageValue` 不输出 final `DomainWrite`。
- `Storage.commit` 是 production semantic storage write 的唯一来源。
- `CONTRACT_STORAGE` mapping 使用 `address21 || slot32 || storageKeyVersion_u8`。
- `physicalKey/addrHash` 只作为 debug/test metadata，不参与 canonical domain key；raw `contractVersion` 只能通过 `storageKeyVersion_u8` 归一进入 key。
- collector 包没有硬编码 `storage-row` mapping。

## 13. S5 出口到 S6/S7/S9

S5 完成后，`BlockWriteSet` 中应出现：

```text
TxWriteSet(USER_TX txNum=N)
  DomainWrite(
    domain=CONTRACT_STORAGE,
    key=address21 || slot32 || storageKeyVersion_u8,
    beforeValue=null|nonzero32,
    afterValue=null|nonzero32
  )
```

S6 TemporalStore 可直接按 `(domainId, key, txNum)` 存 history。

S7 CommitmentBuilder 可把 `afterValue == null` 作为 deletion，从 global TVM state root 移除该 slot。

S9 historical storage reader 可按 RPC 输入 `address, slot, block/tx` 先解析目标历史点的 storage key version，再查 `CONTRACT_STORAGE(address21 || slot32 || storageKeyVersion_u8)`，不需要理解 java-tron physical `storage-row` key。

## 14. S5 完成标准

- [ ] `SemanticStoreWrite.contractStorage` validates address21/slot32/value32。
- [ ] zero before/after 归一为 null。
- [ ] canonical key 是 `address21 || slot32 || storageKeyVersion_u8`。
- [ ] `physicalKey` 不参与 canonical key；raw `contractVersion` 只归一为 `storageKeyVersion_u8`。
- [ ] `StorageRowStore` 提供 no-op-safe `ArchiveService` accessor。
- [ ] `Storage.commit()` 在 physical put/delete 前读取 before 并发 semantic write。
- [ ] archive disabled/no active tx 时不读取 before。
- [ ] child repository commit 不发最终 semantic write。
- [ ] VM revert/exception 不发最终 semantic write。
- [ ] raw `storage-row` 被 registry ignored，不重复生成 `CONTRACT_STORAGE`。
- [ ] create2 只改变 physical key；contractVersion 只改变 `storageKeyVersion_u8` suffix。
- [ ] delegatecall/callcode 使用 context owner address。
- [ ] S5 不写 temporal DB，不计算 root。
