# Archive round 29 remediation for Claude review

Date: 2026-07-28

## 1. Review objective

This round applies the following correctness priorities:

1. Archive capture must not change canonical transaction or block execution.
2. Captured and queried historical state must be exact; uncertainty must fail closed.
3. Fork unwind, restart and abnormal process termination must not silently publish mixed state.
4. Historical query concurrency must not share mutable state or starve canonical execution.
5. x86_64 and arm64 use the same RocksDB implementation and archive disk semantics.

This document records the implementation delta and asks Claude to review the claims
adversarially. It is not a production-readiness declaration.

## 2. Implemented changes

### 2.1 Capture work moved out of TVM read/opcode timing

Affected code:

- `AccountStore`
- `AccountCapsule`
- `Storage`

Changes:

- Removed archive-only SHA-256 baseline tracking from every `AccountStore.get()`.
- Removed mutable asset-change hints from `AccountCapsule`.
- `AccountStore.put()` now reads the actual previous canonical account once and computes the exact
  `assetV2` effective-state delta at the write boundary.
- An unchanged asset map and unchanged optimization mode skip physical account-asset reads.
- Removed storage original-value tracking from `SLOAD`/`SSTORE` activity. Dirty storage rows read
  their previous canonical value immediately before the canonical store mutation in
  `Storage.commit()`.
- Physical storage-key aliases within one commit are still chained through a local
  `currentValues` map.
- Archive preparation failures are recorded as archive capture failures; the canonical write still
  follows its original path.

Expected effect:

- Archive-off behavior has no new reads, hashes or capsule metadata.
- Archive-on no longer adds SHA/JCE work to every account read or tracking work to every storage
  access.
- The remaining archive cost is paid only for canonical writes at commit boundaries.

### 2.2 Historical cryptography isolated from canonical validation

Affected code:

- `PrecompiledContracts.BatchValidateSign`
- `PrecompiledContracts.VerifyTransferProof`

Changes:

- Historical repository detection no longer depends on `constantCall`; historical transaction
  trace replay is recognized even when the VM is not marked as a constant call.
- Historical batch-signature verification executes request-locally and checks the archive deadline
  before and after each signature. It never submits to the canonical validation worker pool.
- Historical shielded transfer proof verification uses a dedicated five-thread daemon pool with a
  bounded queue of 64 and `Thread.MIN_PRIORITY`.
- Queue saturation, interruption and deadlines fail the historical query. They do not fall back to
  canonical workers or caller-runs execution.
- Timed-out historical proof futures are cancelled and removed from the dedicated queue on a
  best-effort basis.

The low OS thread priority is advisory. The hard isolation properties are the separate executor,
bounded queue, admission failure and query deadline.

### 2.3 Historical transaction replay checks the exact receipt result

Affected code:

- new `VmResultCodeMapper`
- `RuntimeImpl`
- `HistoricalDebugTraceExecutor`
- `HistoricalDebugTraceResult`
- `HistoricalDebugTraceSupport`

Changes:

- Extracted the existing consensus receipt mapping from `RuntimeImpl` into one shared mapper.
- Canonical execution still uses that exact mapper.
- Historical replay now returns its exact `contractResult`.
- Recorded and replayed transaction results must match exactly. For example,
  `ILLEGAL_OPERATION` no longer matches a replay that produced `OUT_OF_ENERGY` merely because both
  are failures.

Any mismatch returns an internal historical-query failure rather than a plausible but incorrect
trace.

### 2.4 Genesis abnormal-termination fence

Affected code:

- `DynamicPropertiesStore`
- `Manager`
- `DynamicKeyPolicy`

Changes:

- Before archive-enabled genesis mutates canonical stores, an
  `ARCHIVE_GENESIS_COMMIT_MARKER` is written as `INTENT` with the exact genesis hash.
- After the forced genesis revoking session commits to canonical root stores, the marker is
  replaced with `COMMITTED`.
- Archive journal acknowledgement occurs only after `COMMITTED` is persisted.
- Startup rejects all of the following and marks archive rebuild required:
  - canonical blocks exist but the marker is absent;
  - canonical genesis exists but the marker is not an exact `COMMITTED` marker for its hash;
  - no canonical blocks exist but any marker remains.
- The marker is excluded from historical dynamic-property capture.

This is a conservative process-crash/SIGKILL fence. It does not turn multiple independent
canonical RocksDB stores into a hardware-level atomic transaction.

### 2.5 Account-asset membership corruption now fails closed

Affected code:

- `ArchiveTemporalCodec`
- `ArchiveTemporalStore` / `ArchiveTemporalReadView`
- `UnifiedArchiveTemporalStore`
- `DefaultArchiveStateReader`

Changes:

- Renamed the internal enumeration contract from `scanLatestCanonicalKeys` to
  `scanKnownCanonicalKeys`.
- Persistent enumeration scans mutable `LATEST` membership and immutable first-observation
  `ANCHOR` membership from the same RocksDB snapshot.
- Count, ordering and key bytes must match exactly before account asset keys are returned.
- Missing or mismatched evidence throws `ArchiveException`; historical SELFDESTRUCT replay cannot
  silently enumerate an incomplete TRC10 asset set.

Coherent deletion of both `LATEST` and `ANCHOR`, together with every other local trace of the same
key, cannot be detected without an external commitment or backup. This remains an explicit
physical-corruption boundary.

### 2.6 RocksDB aligned across x86_64 and arm64

Affected code:

- root `build.gradle`
- `ArchiveServiceFactory`
- `ArchiveRocksReadOptions`
- `UnifiedArchiveDb`
- platform `MarketOrderPriceComparatorForRocksDB`
- dependency verification metadata

Changes:

- Both architectures now use RocksDB JNI `9.7.4`.
- Removed the x86 archive-enable rejection and RocksDB 5.15 reflection compatibility.
- Native read deadlines, I/O timeouts and `closeE()` are direct 9.7.4 calls.
- The RocksDB market comparator has one common `AbstractComparator` implementation.
- Comparator input is read through `ByteBuffer.duplicate()`, preserving caller buffer positions and
  supporting direct native buffers.

No archive schema migration or legacy archive backend is retained. Existing canonical x86 RocksDB
directories are still a project-level upgrade concern because this changes the canonical JNI
dependency as well as archive.

### 2.7 Round-9 audit follow-up

The subsequent round-9 invariant audit found no live correctness defect. Its actionable coverage
gaps were closed before this remediation was committed:

- removed the final archive-cleanup dead import from `AccountCapsule`;
- executed a real historical `BatchValidateSign` call with
  `Repository.isHistoricalArchive() == true` and `constantCall == false`, verifying that the
  canonical executor's submitted-task count does not change;
- injected account-asset `CORRUPT_INDEX` into historical SELFDESTRUCT and verified that replay ends
  with `UnsupportedHistoricalStateException`, without an internal transaction or account deletion;
- exercised a physical TRC10 row absent from the account protobuf, lazy-imported and changed it,
  then verified both the captured `7 -> 12` transition and the `SnapshotRoot` physical result;
- fixed the InMemory oracle expectation that membership whose only change was unwound is no longer
  enumerable, even though its restored latest baseline is a tombstone;
- made the direct-buffer comparator test use `capacity > limit`, so reading capacity instead of
  remaining bytes would fail.

## 3. Verification completed

ARM64 / JDK 17:

```text
./gradlew :actuator:test -x generateGitProperties \
  --tests 'org.tron.core.vm.VerifyTransferProofDeadlineTest' \
  --tests 'org.tron.core.vm.program.ProgramHistoricalSelfDestructTest' \
  --tests 'org.tron.core.vm.program.VmResultCodeMapperTest' \
  --tests 'org.tron.core.vm.program.StorageArchiveCaptureTest'
BUILD SUCCESSFUL

./gradlew :framework:test -x generateGitProperties \
  --tests 'org.tron.core.db.ManagerGenesisArchiveTest' \
  --tests 'org.tron.core.db.ManagerGenesisArchiveLifecycleTest' \
  --tests 'org.tron.core.services.jsonrpc.HistoricalEthCallSupportIntegrationTest' \
  --tests 'org.tron.common.runtime.RuntimeImplMockTest' \
  --tests 'org.tron.common.utils.DBKeyComparatorTest'
BUILD SUCCESSFUL

./gradlew :framework:test -x generateGitProperties \
  --tests 'org.tron.common.runtime.vm.BatchValidateSignContractTest' \
  --tests 'org.tron.common.runtime.vm.PrecompiledContractsVerifyProofTest' \
  --tests 'org.tron.core.db.AccountAssetStoreTest'
BUILD SUCCESSFUL

./gradlew :chainbase:test -x generateGitProperties \
  --tests 'org.tron.core.archive.*' \
  --tests 'org.tron.core.store.AccountStoreArchiveCaptureTest'
BUILD SUCCESSFUL

./gradlew :framework:checkstyleMain :framework:checkstyleTest \
  -x generateGitProperties
BUILD SUCCESSFUL
```

x86_64 / Oracle JDK 8 under Rosetta:

```text
arch -x86_64 ./gradlew --no-daemon \
  :framework:compileTestJava :chainbase:test :framework:test \
  -x generateGitProperties \
  --tests 'org.tron.core.archive.temporal.UnifiedArchiveTemporalStoreOracleTest' \
  --tests 'org.tron.common.utils.DBKeyComparatorTest'

Building for architecture: x86_64, Java version: 1.8
BUILD SUCCESSFUL
```

The x86 run loaded RocksDB 9.7.4 JNI and exercised both the unified temporal-store oracle and the
direct-buffer comparator.

## 4. Requested adversarial review

Please review these points independently instead of trusting the implementation claims above:

1. Prove archive-off canonical behavior remains byte-identical in `AccountStore`, `Storage`,
   `RuntimeImpl` and all precompile paths.
2. Try stale `AccountCapsule`, optimized/non-optimized asset transitions, account deletion and
   physical account-asset rows that are absent from protobuf maps.
3. Try multiple logical storage slots that resolve to the same physical key and verify the
   prev-value chain after removing opcode-time baselines.
4. Compare every branch and precedence rule in `VmResultCodeMapper` with the former
   `RuntimeImpl.setResultCode`.
5. Verify historical transaction trace replay cannot enter canonical signature/proof executors,
   including when `constantCall == false`.
6. Saturate and time out the historical proof pool; confirm no caller-runs fallback, no canonical
   pool use and no stale task result contaminates a later query.
7. Inject termination before INTENT, after INTENT, during canonical genesis writes, after
   `commitToRoot`, after COMMITTED and before archive journal acknowledgement.
8. Delete or mutate only one side of `LATEST`/`ANCHOR` and verify all account-asset enumeration
   consumers fail closed from a single snapshot.
9. Re-run x86_64/JDK8 on the actual Debian production image. Include canonical RocksDB open/write,
   archive start/restart, fork unwind and SIGKILL recovery, not only compilation.
10. Check whether changing the global x86 RocksDB JNI from 5.15.10 to 9.7.4 violates any supported
    OS/glibc or pre-existing canonical database contract outside archive.

## 5. Remaining validation gates

The code-level and local native tests above are green. Before claiming production readiness, still
run the private-chain fault matrix on each target deployment architecture:

- sustained sync with archive enabled and concurrent historical queries;
- fork/reorg unwind while queries hold snapshots;
- SIGKILL at journal, canonical commit, publication and genesis marker boundaries;
- ENOSPC, permission loss, WAL/SST corruption and partial-file visibility;
- clean restart and repeated historical snapshot comparison;
- x86_64 Debian/JDK8 native deployment smoke and long-run resource measurements.

Expected behavior under uncertainty is recovery to a proven state or fail-stop with rebuild
required. Silent fallback to live state or partial historical results is never acceptable.
