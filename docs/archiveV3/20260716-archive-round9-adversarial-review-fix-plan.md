# Archive round-9 adversarial review and fix plan

- Date: 2026-07-16
- Branch: `feat/archive-node`
- Review base: `e4cac64823`
- Layout: `UNIFIED_V1` only
- Status: IMPLEMENTED, TARGETED AND MODULE REGRESSION PASSED

## 1. Objective

Re-audit the current archive implementation after round 8, concentrating on failure boundaries that
cross canonical-chain mutation and archive lifecycle hooks. The pass also rechecked unified storage
atomicity, startup reconciliation, historical VM fidelity, query/native-resource ownership, RPC
selector validation, and archive-off isolation.

## 2. Required invariants

1. A failed fork switch restores the original canonical branch or fail-stops.
2. Every partially started archive block is aborted before the same block can be retried.
3. Journal persistence precedes canonical commit and acknowledgement follows canonical commit.
4. Startup accepts only one contiguous canonical archive generation or fails promptly.
5. Historical reads never silently fall back to current live state.
6. Query leases, snapshots, decoded caches, and native readers remain bounded and are always closed.
7. Archive-off execution and canonical consensus behavior remain unchanged.

## 3. Confirmed finding

| ID | Severity | Finding | Consequence |
|---|---|---|---|
| R9-1 | High | `Manager.switchForkWithArchiveLease` called `archiveService.beginBlock(..., REPLAY)` before entering the compensation `try/catch/finally`. | If replay begin failed after the old branch was erased, the exception bypassed branch restoration and left the canonical chain at the common ancestor. |

### 3.1 Failure sequence

1. The old canonical branch is erased back to the common ancestor.
2. Replay begins for the preferred fork.
3. `archiveService.beginBlock` throws.
4. The exception occurs outside the replay compensation region.
5. The old branch is not re-applied and the canonical head remains at the ancestor.

This is an archive-enabled fork-recovery defect. It does not affect archive-off execution, but it
can interrupt canonical progress on an archive node and requires a restart or manual recovery.

### 3.2 Reproduction

`ManagerArchiveSwitchForkTest.replayBeginFailureRestoresOldCanonicalBranch` uses a real unified
archive service and a real fork. A proxy injects one failure at `beginBlock(REPLAY)`.

Before the fix, all six Gradle retry attempts failed the same assertion: the canonical head was the
common ancestor instead of the previous canonical head. This made the finding deterministic rather
than theoretical.

## 4. Implementation

1. Move replay `beginBlock` inside the existing replay compensation region.
2. Move recovery `beginBlock` inside the recovery fail-stop region.
3. Move ordinary append `beginBlock` inside its rollback/abort region.
4. Assign genesis archive cleanup responsibility before calling `beginBlock`, so a future partial
   begin failure also invokes `abortBlock`.
5. Preserve the original exception and existing journal rollback, Khaos cleanup, canonical restore,
   and fail-stop behavior.

No archive storage format, RPC contract, consensus rule, or archive-off hook changed.

## 5. Added regression coverage

### Fork replay begin failure

`ManagerArchiveSwitchForkTest.replayBeginFailureRestoresOldCanonicalBranch` verifies:

- the injected failure remains the primary exception;
- the replay hook was actually reached;
- the previous canonical head is restored;
- restored branch journals are rebuilt with `ArchiveSource.RECOVERY`.

### Ordinary append partial begin failure

`ManagerArchiveLifecycleTest.partialArchiveBeginFailureCleansOrdinaryAppendForRetry` delegates to
the real archive `beginBlock`, throws after allocator/capture initialization, and verifies:

- the failed append does not advance the canonical head;
- archive execution context and partial block state are cleaned;
- the exact same block succeeds on retry with the real service restored.

### Switch-back recovery partial begin failure

`ManagerArchiveSwitchForkTest.recoveryBeginFailureFailsStopAndAbortsPartialArchiveBlock` injects a
failure after the real recovery `beginBlock` has initialized allocator/capture state and verifies:

- recovery failure becomes `TronError(ARCHIVE_RUNTIME)`;
- the archive exception remains the primary cause;
- execution context and the partial recovery block are aborted;
- the same recovery block can begin and abort again after restoring the real service.

## 6. Re-reviewed areas with no confirmed defect

- Unified cross-column-family publication, snapshot binding, unwind, marker, latest, history, and
  changeset invariants.
- In-flight journal validation, earliest-prev shielding, startup reconciliation, and identity
  anchoring.
- Historical VM unsupported-domain handling and terminal archive-error propagation.
- Dynamic-property completeness after direct `VmDynamicProperties` implementation.
- Query admission, deadline checks, batch waiting, snapshot/read-view ownership, and shutdown drain.
- Historical RPC canonical-hash rechecks and malformed/unsupported selector failure behavior.
- TxNum execution allocator abort/retry behavior and persistent index pruning.
- Archive-off byte-path isolation and capture failure containment.

No additional reproducible silent latest-state fallback, cross-generation read, resource leak,
unbounded heap growth, or archive-off regression survived this pass.

## 7. Rejected or downgraded candidates

### Mutable capsule memoization leak

Rejected. Reader cache entries retain immutable protobuf values, while each public read constructs a
fresh mutable capsule wrapper. A caller cannot mutate a later caller's cached account or contract.

### Deadline arithmetic wrap

Rejected for practical configured ranges. Deadline checks use monotonic nanoseconds and signed
difference comparison correctly; configured budgets cannot approach the wrap interval.

### TxNum/temporal unwind mismatch

Rejected after checking block-range adjacency, marker publication, latest restoration, changeset
deletion, and snapshot visibility together. Existing tests cover repeated changes, tombstones,
multi-delete unwind, and reorg paths.

### `plugins/.../DbArchive.java`

Not part of the transaction-state archive implementation. It is the existing database manifest
archive utility and must not be treated as a second legacy archive backend.

## 8. Verification

The following gates passed:

```text
:framework:test
  ManagerArchiveSwitchForkTest
  ManagerArchiveLifecycleTest
  ManagerGenesisArchiveLifecycleTest
  ManagerGenesisArchiveTest

:chainbase:test :actuator:test :framework:test
  org.tron.core.archive.*
  org.tron.core.vm.archive.*
  StructLogReconstructorTest
  ArchiveJsonRpcStateAdapterTest

:framework:checkstyleMain
:framework:checkstyleTest
git diff --check
```

The focused red test failed deterministically before the fix and passed after it. All aggregate
archive tests and framework checkstyle checks are green.

## 9. Remaining release qualification

No further source-level blocker was confirmed in round 9. The remaining work is operational
qualification rather than a known code defect:

1. Production-scale from-zero synchronization with CPU, heap, native memory, disk write
   amplification, and publisher lag measurements.
2. A 72-hour archive-on soak under mixed historical RPC and block-import load.
3. Capacity-limited ENOSPC, filesystem I/O error, and power-loss fault matrices on target storage.
4. Target-hardware RocksDB block-cache, Bloom-filter, compaction, and async-publisher sizing.

Possible later optimizations should remain benchmark-gated. In particular, consolidating the three
Manager begin/abort compensation blocks may reduce duplication, but should only be done with the
current fork, retry, and fail-stop tests retained as executable invariants.
