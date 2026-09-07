# Archive Audit Remediation

Date: 2026-09-07.
Branch: `feat/archive-node-JDK25-fsync`.
Base HEAD: `99b3969cec363750363eb4c5f6c914502c50c698`.

Follow-up to `20260907-develop-functional-performance-audit.md`. This report covers
local implementation and verification; committing and pushing are separate,
user-authorized steps. No rebase, GitHub comment, or issue publication was performed.
The pre-existing `framework/build.gradle` fsmonitor workaround is unchanged and
excluded from the archive fix commit.

## Implemented Changes

### F1: Historical Runtime Code Hash

`ArchiveRepositoryAdapter.saveCode` now refreshes the contract code hash under the
same Constantinople gate as `RepositoryImpl`. Child commit merges already-updated
code and contract overlays; it does not invoke the parent's execution-time
`saveCode` before the newly created contract exists there, or hash the code again.
Defensive copies and overlay accounting remain in force.

New regressions cover the pre-Constantinople behavior, the child-commit boundary,
and real TVM CREATE and CREATE2 execution. The constructor reads its own code hash
before returning one-byte STOP runtime code; the parent must return the runtime
hash, not the cached empty-code hash. This is VM-level verification, not a new
mainnet transaction replay or private-chain RPC test.

### O1: Bounded Full-Scrub Lookups

Full scrub uses one snapshot with at most 16 reusable lookup cursors, separated by
column family and first key byte. Small cross-reference reads reuse these cursors;
an exact key comparison is mandatory after seek. Excess cursor families and large
payload reads retain the existing bounded point-read path. This does not create
a historical row cache or change the configured 72 MiB RocksDB block cache.

The current position, changeset locator, and block marker are validated directly
from bounded iterator values. Cross-reference validation, range continuity,
txId/VM pairing, payload authentication, and block digests remain enabled. Ordinary
RPC and publication read views do not enable the new lookup mode.

Tests cover missing keys before/between/after present keys, snapshot stability,
oversized rows, the 16-cursor cap, and resource release. Existing corruption and
oracle suites remain part of regression. The single-key commit-marker fixture now
requires five point reads rather than six; this is a fixture-specific count.

### O2: Conservative Resource-Refusal Classification

Only the hard-watermark and soft-pressure timeout branches of `awaitWriterCapacity`
produce the private resource-admission failure type. The service still fails stop.
It may omit a new repair marker only after closing admission and confirming no
active lifecycle work and no processing or failed publisher. Publisher drain,
processing, and failure state are checked atomically under its monitor because
its lease can close before its failure callback runs.

Unknown errors, I/O failures, watchdog failures, in-flight work, and failed drain
checks still require repair. Later fatal contenders can request and persist repair
even when the primary failure was a clean resource refusal. Marker persistence
remains serialized with startup marker clearing; required persistence failure
does not open normal fatal callback delivery.

Existing repair evidence is never cleared by this classification. Old messages
containing "backpressure" are not interpreted as proof of a clean failure. Such
databases still undergo full scrub. Normal restart reconciliation is not skipped.

### O3: Recovery Watchdog Entry

The watchdog is armed around the actual startup validator, including the
`reconcileInFlightOnStartup` entry used before `completeRecovery` by Manager.
Tests block the validator through both entry sequences and require fatal timeout
delivery. Direct RUNNING construction used by in-memory tests retains its early
validation before watchdog construction.

Scope: this protects the post-open startup-validation callback, not all factory
RocksDB open/range/journal loading time or the entire Manager initialization.

### Q1: Deterministic Batch Budget Test

The test waits for both subresponses to retain their bytes before issuing the
third request, rather than waiting at the later network-write boundary. It uses
valid JSON, repeats the scenario 20 times, and checks that a subsequent request
succeeds after the retained responses are released. Test retries are disabled for
the framework verification runs.

## Million-Block Benchmark

Local macOS arm64, Temurin 17.0.19, 1 GiB benchmark Java heap. Fresh temporary
RocksDB fixture: 1,160,000 synthetic empty blocks, two system positions per block,
persisted markers and ranges, flushed SSTs, and a forced repair marker. Hashes are
synthetic, not mainnet block hashes. No user transactions or temporal state rows.
Fixture generation is excluded from recovery timing.

| Stage | Rows | Elapsed |
| --- | ---: | ---: |
| Factory range validation | 1,160,000 | 916 ms |
| Index keyspace | 3,480,001 | 8,070 ms |
| Ranges and positions | 1,160,000 ranges | 5,970 ms |
| Temporal block markers | 1,160,000 | 4,065 ms |
| Factory plus complete recovery | Entire fixture | 19,114 ms |

The fixture was reopened afterward and checked for the expected final block and
cleared repair marker. The earlier same-sized fixture did not complete and had a
measured slow interval around 112 position rows/s. This is evidence that the
demonstrated empty-block slowdown is addressed, not a mainnet restart SLA or a
precise end-to-end speedup ratio against a completed baseline run.

Local reproduction inputs: `/private/tmp/ArchiveFullScrubBenchmark.java` and
`/private/tmp/archive-full-scrub-benchmark.gradle`. Result log:
`/private/tmp/archive-full-scrub-fixed-benchmark.log`.

## Verification Status

| Runtime | Chainbase | Actuator | Framework | Failures / Errors / Skips |
| --- | ---: | ---: | ---: | --- |
| Temurin 17.0.19 | 912 | 85 | 224 | 0 / 0 / 0 |
| Temurin 25.0.4 | 912 | 85 | 224 | 0 / 0 / 0 |

Each runtime passed 1,221 tests. Chainbase and actuator ran their complete test
tasks; framework ran the archive/Manager/historical-VM/JSON-RPC selection below,
not its entire upstream suite. Framework retries were disabled through a temporary
init script; the batch-budget test additionally exercised its scenario 20 times.
JDK 25 build time was 2m 53s. The final JDK 17 test/style invocation took 2m 57s.

```sh
./gradlew -I /private/tmp/archive-servlet-budget-audit.gradle \
  :chainbase:test :actuator:test :framework:test -x generateGitProperties \
  --tests 'org.tron.core.archive.*' --tests 'org.tron.core.vm.archive.*' \
  --tests 'org.tron.core.db.Manager*Archive*Test' \
  --tests 'org.tron.core.config.DefaultConfigArchiveServiceTest' \
  --tests 'org.tron.core.services.jsonrpc.*Archive*Test' \
  --tests 'org.tron.core.services.jsonrpc.StructLogReconstructorTest' \
  --tests 'org.tron.core.services.jsonrpc.JsonRpcServletTest' \
  --tests 'org.tron.core.jsonrpc.JsonrpcServiceTest' --max-workers=4
```

The repository's `lint`, `checkstyleMain`, and `checkstyleTest` tasks passed.
Those tasks do not check every changed module. A supplemental Checkstyle scan of
the changed chainbase/actuator files reports 148 existing warnings, identical to
the base HEAD when warning source/message multiplicities are compared per file
and shifted overload-reference line numbers are normalized: zero added/removed.
This supplemental task intentionally fails its zero-warning threshold on both
versions; consequently the combined JDK 17 command exits nonzero despite all
tests and normal style tasks passing. Do not report all-path Checkstyle as clean.
`git diff --check` passed.

Local evidence:

- `/private/tmp/archive-audit-fixes-jdk25.log`
- `/private/tmp/archive-audit-fixes-jdk25-results.tar`
- `/private/tmp/archive-audit-fixes-final-all-jdk17.log`
- `/private/tmp/archive-fix-checkstyle.xml`
- `/private/tmp/archive-fix-checkstyle-baseline.xml`

## Remaining Validation

- Full scrub is still proportional to historical data size, with random payload
  and txId reads. Transaction-heavy mainnet data needs its own measurements.
- Busy or uncertain resource failures deliberately still require repair scrub.
- Factory open/range/journal loading is outside the relocated callback watchdog.
- No fresh 27-SR kill/restart, mainnet replay, x86 benchmark, or long-running mixed
  query/write soak was performed for this patch. WAL ordering, fsync policy,
  canonical state mutation, schema, and disk format are unchanged.
