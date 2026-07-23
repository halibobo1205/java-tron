# Archive round 27 workflow adversarial review results

## 1. Scope and review rule

Baseline: `de9a8234d1` plus the fixes in this review.

The review used four independent read-only lanes and two post-fix adversarial verifiers:

1. canonical block/transaction hot paths and archive-off cost;
2. historical-query concurrency, cache isolation, VM/thread-local isolation, and epoch races;
3. journal, publication, reorg, startup reconcile, shutdown, and native-resource lifecycle;
4. complexity, dead code, diagnostics, metrics, configuration, and operator guidance.

A candidate was changed only when its call chain and impact were source-provable. Suggestions that
added migration code, polling threads, caches, new error-code contracts, or speculative hot-path
optimizations without evidence were rejected.

## 2. Confirmed findings and fixes

### 2.1 Shipped configuration could publish on the block thread

`framework/src/main/resources/config.conf` set `publisher.async=false` while the Java and reference
defaults were `true`. Enabling archive from that sample made solidified publication, temporal/index
construction, and the synchronous RocksDB publication batch run on the canonical block thread.

Fix:

- set `publisher.async=true`;
- expose `jsonRpcWorkerThreads=2` in the sample;
- add a resource-level parity test.

### 2.2 RocksDB statistics had cost but no production consumer

Prometheus enablement created native RocksDB `Statistics`, but runtime export had already been
removed because property/statistics JNI has no enforceable deadline. The runbook still named series
that could never be published.

Fix:

- production initialize/open/resume never enables native statistics;
- an explicit test-only bridge retains ticker-based I/O regression tests;
- remove the dead RocksDB metric hooks;
- use RocksDB `LOG`, host I/O metrics, query latency, and RSS in the runbook;
- do not add a polling thread.

### 2.3 Operational metric states could be starved

The bounded reporter protected block/query callers, but a continuously non-empty FIFO could delay
coalesced state indefinitely. The first fix alternated events and states; adversarial re-review then
found a second starvation path between in-flight, query-admission, and scalar states.

Fix:

- alternate ordinary events with state reports;
- round-robin the three state sources;
- coalesce and publish the cumulative `metrics_dropped_reports` state;
- treat its nonzero process-lifetime value as a sticky reliability alert;
- add saturated-queue and repeated-in-flight-update tests.

No Prometheus call, native read, or filesystem operation was added to the block/query thread.

### 2.4 Existing disk samples were not observable

`DefaultArchiveService` already sampled usable space with a timeout and generation ordering, but
never published `disk_free_bytes`.

Fix: publish only an accepted new sample after releasing `diskSampleMonitor`. This reuses the
existing sample and adds no filesystem call or archive consistency-lock hold.

### 2.5 Failure diagnostics lost the useful exception

Manager's protected warning/error helpers logged only the throwable class. Fork and block failures
therefore lost message, cause, and stack. A duplicate conditional branch also threw the same error
on both sides. Journal failure text incorrectly said canonical state had already changed.

Fix:

- log the complete `Throwable` inside the existing fail-isolated logging boundary;
- remove the duplicate throw branch;
- use a pre-canonical-commit message only for journal creation;
- retain post-canonical fail-stop wording for ACK, publication, and unwind.

### 2.6 Storage and operator cleanup

- Explicitly enable dynamic level compaction for every archive CF. Both bundled RocksDB 5.15 and
  9.7 Java APIs expose the setter/getter.
- Give schema/layout and identity failures a correct remedy: restore a canonical/archive backup
  compatible with the running build, or rebuild both together from empty directories.
- Remove unreachable `P1_DOMAIN`, `TX_AFTER`, and `SYSTEM_AFTER` states.
- Remove a Manager fail-stop wrapper whose only caller was its own test.
- Remove stale trace wording and mark the historical E2E's nonexistent
  `StructLogReconstructorTest` selector as non-coverage.

## 3. Adversarial conclusions

No archive state-correctness, atomicity, reorg, startup-reconcile, shutdown, or historical-query
concurrency defect survived the review.

The following concerns were specifically disproved:

- historical reads use one Unified snapshot with `fillCache=false`; canonical resolver reads are
  also cacheless, and archive does not share its 72 MiB block cache with canonical databases;
- query worker saturation fails fast and never runs historical work on the caller/block thread;
- query scopes restore VM configuration and thread-local state in `finally`;
- selector epochs are checked after canonical resolution and again before response settlement;
- publication atomically writes index, temporal rows, marker, cursor, and journal deletion in one
  RocksDB batch;
- ACK loss is reconciled from canonical state on restart;
- publisher/reorg and repair-clear/fatal transitions have mutually exclusive lifecycle barriers;
- archive-off performs no archive DB read, hash, lock, queue, or fsync;
- schema 6 is not ambiguous: physical layout and archive-policy checksum are independently
  validated and mismatches fail closed.

Rejected changes:

- no layout-schema bump for a policy-checksum change;
- no background RocksDB metric poller;
- no split or configurable archive block cache without runtime evidence;
- no change to `ARCHIVE_RUNTIME`, because fork recovery uses that classification;
- no SHA-256 `ThreadLocal` optimization before a benchmark shows provider lookup matters;
- no migration/legacy compatibility code for formats that were never deployed.

## 4. Verification

Repository regression:

```text
./gradlew :common:test :chainbase:test :actuator:test :framework:test \
  -x generateGitProperties \
  --tests '*Archive*' --tests '*Historical*' --tests '*StorageConfigTest'

BUILD SUCCESSFUL
```

Targeted DB/backend/service/factory/metrics/Manager tests and framework checkstyle also passed.

Fresh private-chain E2E:

```text
FullNode SHA-256:
2794d0392b1cfec981101212eaa61ffe6172b3cd1b5b3643536a1a8c525171b9

SCENARIO_OK transactions=20 oracles=15 finalHeight=57
ORACLE_REPLAY_OK count=15

Before shutdown:
repair_required=0, publisher_lag_blocks=0, inflight_blocks=2

After no-witness restart:
repair_required=0, publisher_lag_blocks=0, inflight_blocks=0

OFFLINE_PROBE_OK first=0 last=71 changesets=1127 tombstones=7
```

The scenario covered account changes, transfers, resource freeze/unfreeze, voting, delegation,
TRC10 issue/transfer, contract deployment/calls/storage transitions, and `SELFDESTRUCT`. All 15
historical balance/code/storage/constant-call oracles were replayed after restart. The strict
offline probe validated the complete Unified index, temporal chains, payloads, markers, and domain
rows.

Test root:

```text
/private/tmp/java-tron-archive-head-e2e-20260723-attempt2
```

## 5. Remaining runtime gates

These are validation gates, not confirmed code defects:

- a multi-day from-zero sync/soak with production disk and realistic historical-query traffic;
- publication catch-up, compaction/write amplification, and p95/p99 block-processing deltas;
- forced low-space sampling latency and real device-stall behavior on the deployment filesystem;
- CPU, OS page-cache, and disk-bandwidth contention under sustained historical VM/precompile load.

Static review, focused regression, fresh-chain oracle replay, restart reconciliation, and strict
offline validation are clean. Production capacity still has to be demonstrated by the soak and
from-zero synchronization runbook.
