# Startup Cache Pressure and Recovery

Date: 2026-09-08

Reported binary: `87b7b3a0f86ccfcb29035b5106a7f4b1a3430ed0`.
Patch base: `0330867a26` on `feat/archive-node-JDK25-fsync`.

## Evidence and Limits

Two main-thread samples, 38 seconds apart, show `RocksIterator.seek0Jni` below
`UnifiedArchiveTemporalStore.readBlockRows` and `readIntegrityRow` line 1168.
That call reads a HISTORY reference, not the large temporal payload targeted by
the previous patch. Main-thread CPU increased by 37,999.82 ms during the interval.
This is consistent with CPU-intensive native seeks, not a Java monitor deadlock.
The samples alone do not identify which native operation consumes the CPU or
prove that block-cache misses are the mainnet bottleneck.

The old resource-admission fatal path could persist `repair-required`. A
programmatic exit is therefore sufficient to trigger full startup validation;
it does not require a power failure or `kill -9`. Current code avoids that marker
only for a provably quiescent resource-admission failure. Existing markers cannot
be cleared just by matching their primary message: concurrent or suppressed
failures were not classified by the old writer.

`fullScrubOnStartup=false` does not override a persisted repair marker. This patch
does not change that policy or establish that the operator's database is corrupt.

## Controlled Experiment

The synthetic fixture contains 16,384 blocks and 262,144 change records, using
32 recurring hot keys plus 32,768 cold keys, with 75% hot-key updates. It includes
ACCOUNT and CONTRACT_STORAGE values, tombstones and recreation, and occasional
96 KiB account payloads. It is built through journal, acknowledgement and
publication APIs, with periodic SST flushes and a persisted repair marker.

Each run opens the same fixture afresh and performs every full-scrub stage.
These are local single-run measurements, not mainnet timings. The OS page cache
was not dropped. The small native-cache setting deliberately creates pressure.

| Implementation | Native cache | Temporal blocks | Full scrub | Index cache misses |
| --- | ---: | ---: | ---: | ---: |
| Original read logic | 1 MiB | 17,585 ms | 73,763 ms | 239,601 |
| Hot-key prefetch prototype | 1 MiB | 16,864 ms | 72,309 ms | 239,585 |
| Prefetch plus current-row reuse prototype | 1 MiB | 16,690 ms | 71,491 ms | 239,584 |
| Original read logic | 72 MiB | 2,607 ms | 13,116 ms | 135 |

The prototypes reduced seek counts but barely improved elapsed time. They were
discarded. Changing only cache capacity produced a roughly 5.6x full-scrub
improvement in this pressure experiment. This does not demonstrate a 5.6x gain
from 72 MiB to a larger cache on mainnet. The mainnet working set and actual
native profile still need measurement.

The final implementation was rerun using the public cache-capacity option,
without shadow classes or the discarded prototypes:

| Configured cache | Temporal blocks | Full scrub | Index cache misses | Seeks |
| --- | ---: | ---: | ---: | ---: |
| 1 MiB | 17,579 ms | 72,926 ms | 239,585 | 7,799,883 |
| 72 MiB | 2,585 ms | 12,442 ms | 135 | 7,799,883 |

Both completed at block 16,383 with `repairRequired=true`. The identical seek
counts confirm that this patch changes cache capacity rather than removing
cross-checks. These reruns have the same synthetic-workload limitations above.

## Implementation

- Add `storage.archive.db.blockCacheBytes`, a positive byte count. Following the
  operator's requested capacity increase, the default is now 2,147,483,648 bytes
  (2 GiB), up from the original 72 MiB. Explicit configuration still overrides it.
- Pass the budget through the service factory for both new and existing databases.
- Keep one cache shared by archive column families. Do not share it with chain
  databases, pin more metadata, change cache admission, or create per-CF caches.
- Log the selected capacity when opening the archive database.
- Preserve checksums, reference authentication, full-scrub stages, repair-marker
  handling, snapshot isolation, fsync policy and all on-disk encodings.

The budget is native memory, outside `-Xmx`. It is not a strict process RSS ceiling:
the existing non-strict LRU behavior, pinned iterator blocks, memtables, table
readers and other native allocations also require memory. Capacity remains in
effect after startup; this is not a temporary startup-only allocation.

## Operator Procedure

1. Build the patched branch. The reported binary does not accept the new key.
2. Check host RAM, `-Xmx`, process/container limits and other native allocations.
   With no explicit override, the patched binary selects 2 GiB. An existing
   72 MiB or 512 MiB override must be removed or updated to use that capacity:

   ```hocon
   storage.archive.db.blockCacheBytes = 2147483648
   ```

3. Stop the node through its normal shutdown path and confirm process exit before
   starting the new binary against the same canonical and archive directories.
   No resync, schema migration, database deletion or repair-marker removal is
   required by this patch. Existing shutdown/recovery checks still apply.
4. Confirm the logged `capacityBytes`, and compare successive `temporal-blocks`
   progress intervals. Monitor RSS and swapping. Increasing capacity without
   sufficient RAM can make recovery worse.
5. If throughput remains poor, collect a native CPU profile and cache statistics
   on the real workload before changing the algorithm again. Do not treat the
   synthetic speedup as a startup-duration guarantee or bypass full validation.

The reported old binary does not retain durable scan progress. The subsequent
[resumable startup patch](20260908-resumable-startup-validation.md) now preserves
newly completed validation work. It cannot recover progress from old log messages.

## Reproduction

Use the same fixture for both validation runs; do not run two processes against
the same RocksDB directory. These commands are for an isolated synthetic path,
never a running node's data directory.

```sh
./gradlew -I docs/archiveV3/benchmarks/startup-payload.gradle \
  :chainbase:archivePayloadBenchmark -PpayloadMode=generate \
  -PpayloadPath=/tmp/archive-cache-pressure/unified \
  -PpayloadBlocks=16384 -PpayloadHotKeys=32 -x generateGitProperties

./gradlew -I docs/archiveV3/benchmarks/startup-payload.gradle \
  :chainbase:archivePayloadBenchmark -PpayloadMode=validate \
  -PpayloadPath=/tmp/archive-cache-pressure/unified \
  -PpayloadCacheBytes=1048576 -x generateGitProperties

./gradlew -I docs/archiveV3/benchmarks/startup-payload.gradle \
  :chainbase:archivePayloadBenchmark -PpayloadMode=validate \
  -PpayloadPath=/tmp/archive-cache-pressure/unified \
  -PpayloadCacheBytes=75497472 -x generateGitProperties
```

`PAYLOAD_SCRUB_OK` reports elapsed time, selected cache capacity, seek count,
index cache misses, final block and marker state. Validation deliberately retains
the fixture's repair marker; the benchmark does not authorize clearing it.

## Verification Scope

Focused tests cover default/explicit configuration, non-positive rejection,
cache sizing on initialization and reopen, existing published rows and repair
markers, and full-scrub rejection of a tampered HISTORY reference after resizing.
The archive regression suites also cover publication, restart/reorg behavior and
historical RPC isolation. Mainnet-scale startup latency remains unverified.

Initial configuration-patch results before the requested 2 GiB default increase
(macOS arm64, RocksDB 9.7.4):

| Runtime | common | chainbase | actuator | framework | Total |
| --- | ---: | ---: | ---: | ---: | ---: |
| Temurin 17.0.19 | 144 | 930 | 98 | 222 | 1,394 |
| Temurin 25.0.4 | 144 | 930 | 98 | 222 | 1,394 |

Both selections finished with zero failures, errors or skipped tests. These are
the configuration/archive/Manager archive/historical RPC selections, not every
test in the repository. Test retries were disabled. `lint`, project
`checkstyleMain`/`checkstyleTest`, the reference-comment check and `git diff --check`
passed. Direct checkstyle on the changed Java files found the same eight existing
warnings as HEAD, with no new warnings.

After increasing the default to 2 GiB, Temurin 25.0.4 passed the focused
`StorageConfigTest`, `ArchiveServiceFactoryTest`, `UnifiedArchiveDbTest` and
`UnifiedArchiveBackendTest` selection: 382 tests, zero failures/errors/skips.
This covers the default byte count above `Integer.MAX_VALUE`, explicit smaller
overrides, database reopen and full-scrub corruption detection. Lint, project
checkstyle and the reference-comment check passed again; direct style comparison
still found no new warnings. The timing tables above remain the earlier explicit
1 MiB/72 MiB experiments, not measurements of the new 2 GiB default on mainnet.
