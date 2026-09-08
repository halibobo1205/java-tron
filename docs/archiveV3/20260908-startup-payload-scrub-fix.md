# Startup Payload Scrub Fix

Date: 2026-09-08. Branch: `feat/archive-node-JDK25-fsync`.
Baseline: `2921adb115`; the affected read path is unchanged from deployed `39020a16`.

## Incident and Cause

The supplied mainnet log reports `fullScrub=true`, with the `temporal-blocks` stage
at 411,648 blocks after 59,284,231 ms. The supplied main-thread stack stops at
`getExactBudgeted -> RocksDB.get` while resolving the payload behind a history
reference. Main-thread CPU time almost equals its lifetime. This establishes a
CPU-heavy running scan, not evidence of a deadlock or corrupted historical values.
One Java stack cannot identify the exact native operation consuming that CPU.

The prior lookup-cursor optimization covered `getExact` and `getBounded` but missed
`getExactBudgeted`, which loads temporal payloads. The million-empty-block fixture
had no temporal state payloads, so its successful timing did not validate this
path. Upgrading from `39020a16` to the baseline alone does not fix this omission.

## Change and Boundaries

- Full-scrub views now use their existing exact-key snapshot seek cursors for
  payload reads, including values larger than 64 KiB.
- A native value probe is capped at 64 KiB. The persisted locator length must
  match the actual native length before a larger Java result is allocated.
  Missing, oversized and truncated rows retain their fail-closed behavior.
- The existing maximum of 16 lookup cursors is unchanged. Probe storage is bounded
  by 16 times 64 KiB; this is not a bound on all native iterator or payload memory.
  Hitting the cursor limit falls back to the previous bounded point-read path.
- Only validation views without an attached query context use this new path.
  Historical RPC budgets, cache admission and point reads are unchanged.
- No checksum/reference checks are removed. No canonical writes, archive schema,
  disk encoding, WAL/fsync policy, repair-marker policy or cache sizing changes.
- The unrelated local `framework/build.gradle` modification is not part of this fix.

## Regression Evidence

The new full-scrub regression publishes five versions through the production
backend: a small account, large compressible and incompressible accounts, deletion,
and recreation. Each block is flushed and the database is reopened. Before the
fix, this test fails with 62 native point reads; after the fix it passes with zero
point reads and byte-identical historical results.

Additional tests cover exact-key misses and snapshot isolation, 64 KiB boundaries,
1 MiB values, bogus 256 MiB locator lengths, invalid lengths, cursor-limit fallback,
owner/closed/deadline guards, and query budgets. Existing payload corruption and
cross-reference matrices remain required.

## Payload Benchmark

The opt-in `ArchiveStartupPayloadBenchmark` generates deterministic synthetic data
through journal acknowledgement and atomic archive publication. Its default fixture
has 16,384 blocks, 262,144 account/storage changes, 32,768 distinct domain keys,
user and VM-prestate positions, repeated versions, deletions and recreation.
Account payloads include random bytes and occasional 96 KiB values to exercise the
large-value path; they are not sampled mainnet account-size statistics. Every
1,024 blocks it flushes all column families to create multiple SSTs.

Generate into a new, disposable path, never a node database:

```sh
./gradlew -I docs/archiveV3/benchmarks/startup-payload.gradle \
  :chainbase:archivePayloadBenchmark -PpayloadMode=generate \
  -PpayloadPath=/tmp/archive-payload-benchmark/unified -x generateGitProperties
./gradlew -I docs/archiveV3/benchmarks/startup-payload.gradle \
  :chainbase:archivePayloadBenchmark -PpayloadMode=validate \
  -PpayloadPath=/tmp/archive-payload-benchmark/unified -x generateGitProperties
```

For a controlled baseline, the runner accepts `-PpayloadBaselineSource=DIR` containing
the baseline `UnifiedArchiveReadView.java`. It compiles that class separately and
places it ahead of the current runtime classes. The generation and all other
validation code are identical. Each validation opens a fresh RocksDB instance;
the OS page cache is not flushed. The benchmark measures full archive integrity
validation, not node startup or canonical reconciliation. It deliberately retains
its repair marker and never clears repair evidence.

### Default Cache

Local macOS arm64, Temurin 17.0.19, RocksDB 9.7.4, 1 GiB Java heap, production
72 MiB archive cache. The generated fixture occupies approximately 132 MiB and
has 50 SST files immediately after generation. Both runs finish all validation
stages and retain the benchmark repair marker as intended.

| Read implementation | Full validation | Temporal blocks | Native point reads |
| --- | ---: | ---: | ---: |
| Baseline | 12,943 ms | 2,722 ms | 2,197,307 |
| Fixed | 13,066 ms | 2,811 ms | 0 |

There is no demonstrated default-cache speedup on this fixture. Eliminating the
point reads prevents repeated lookup setup when metadata is not retained; it is
not a promise to accelerate every cache-resident scan.

### Cache-Pressure Control

A separate test-only copy of `UnifiedArchiveDb.java` changes only
`SHARED_BLOCK_CACHE_BYTES` from 72 MiB to 1 MiB. Both baseline and fixed controls
load that copy; the baseline additionally loads the old read-view class. Neither
the repository production constant nor any node configuration is changed. This
is an artificial eviction stress, not a measured mainnet cache hit rate.

The old control reproduces the supplied Java call chain exactly through
`readBlockRows -> decodeReferenceRow -> getExactBudgeted -> RocksDB.get`. In a
one-second native sample, 776 of 811 sampled Get stacks pass through index-block
decompression and Snappy. This establishes repeated index decompression in the
local counterexample, not directly in the remote mainnet process.

| Read implementation, 1 MiB test cache | Temporal blocks | Full validation |
| --- | --- | --- |
| Baseline | Incomplete; last logged 9,216/16,384 at 224,689 ms | Timed out at the 240 s process limit |
| Fixed | All 16,384 in 28,740 ms | All stages in 123,663 ms; zero native point reads |

The timeout is a censored baseline, not a completed duration. The fixed control
still incurs 362,137 index-cache misses and substantial history-link traversal
cost. This fixes the demonstrated point-read omission, not every possible
full-scrub bottleneck, and cannot establish a mainnet restart SLA.

Local evidence is retained under `/private/tmp/archive-payload-*`: generation,
default-cache baseline/fixed logs, stress baseline/fixed logs, and the baseline
thread/native samples. The stress source copies and the baseline read-view source
are also retained there. These test-only source copies are not production changes.

## Suite Verification

Both JDK 17.0.19 and JDK 25.0.4 completed 1,248 selected tests each: chainbase 925,
actuator 98, framework 225; zero failures, errors or skips. Automatic retries were
disabled. The selection
covers archive storage/capture/query/recovery, historical VM execution, Manager
archive lifecycle/forks, historical RPCs and the servlet. This is not the entire
repository test suite.

The repository `lint`, `checkstyleMain`, and `checkstyleTest` tasks passed. A direct
scan of the two changed production classes and three test/benchmark classes has
seven warnings, the same as the baseline after normalizing shifted line references:
zero added or removed warnings. The new benchmark class has no warnings. The
supplemental style tasks allow warnings so that this explicit baseline comparison
can run; this is not an all-path zero-warning claim. `git diff --check` passed.

JDK 17 verification took 2m 40s; JDK 25 took 2m 42s, including compilation and style
tasks. Benchmark timings above are JDK 17 only. Runtime logs and JUnit XML archives
are stored under `/private/tmp/archive-payload-verify-jdk*` and
`/private/tmp/archive-payload-jdk*-test-results.tar.gz`.

## Deployment

Keep the current main-chain and archive directories and configuration. After
verification and deployment of the fix, restart normally. The incomplete scan has
no resume checkpoint, so it starts again; an existing repair marker still forces
full scrub. Do not remove that marker to bypass validation. These changes do not
require reindexing or genesis resynchronization. Mainnet completion time remains
unmeasured until the affected node is restarted on the fixed build.
