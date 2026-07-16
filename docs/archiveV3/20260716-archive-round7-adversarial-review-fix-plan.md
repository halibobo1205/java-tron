# Archive round-7 adversarial review and fix plan

- Date: 2026-07-16
- Branch: `feat/archive-node`
- Review base: `54b3be8ae0`
- Layout: `UNIFIED_V1` only
- Status: IMPLEMENTED AND VERIFIED - the plan review passed and sections 10-12 record the result

## 1. Objective

Review the complete archive implementation along three axes:

1. Functional correctness and fail-closed behavior.
2. Runtime and production-scale performance.
3. Implementation clarity, ownership, and maintainability.

The review treats the current worktree as authoritative. There is no released legacy archive
layout and no compatibility requirement for an unused pre-release schema.

## 2. Invariants that must not regress

The implementation must preserve all of the following:

1. Archive-disabled execution remains behaviorally and byte-for-byte equivalent to upstream paths.
2. Archive capture failures are isolated from consensus mutation and are converted into archive
   fail-stop state.
3. A historical read never falls back to current live state when historical evidence is missing.
4. Nested historical VM execution cannot swallow an archive terminal failure and return a result.
5. Journal persistence precedes canonical commit; acknowledgement follows canonical commit; a
   published block becomes visible atomically with index, temporal rows, marker, cursor, and
   journal deletion.
6. Startup reconciliation either reconstructs one valid contiguous state or refuses to serve.
7. A reader sees one RocksDB sequence across index and temporal state.
8. The duplicate txId check and temporal prev-value-chain check remain hard corruption guards.
9. Metrics and tuning helpers never affect archive correctness or fail-stop decisions.
10. No new native resource may outlive its owning database or be closed while RocksDB can use it.

## 3. Review result

### 3.1 Areas with no new functional defect

The following paths were re-reviewed and remain structurally sound:

- Manager journal/canonical/acknowledgement/publication ordering.
- Startup reconcile, stale published-journal cleanup, and canonical-head validation.
- Real `switchFork` replay and in-flight unwind ordering.
- Unified cross-column-family atomic publication.
- Snapshot-backed index plus temporal reads.
- PRESENT/TOMBSTONE/MISSING rendering and mid-chain fail-closed behavior.
- Historical VM property reconstruction and nested terminal-failure propagation.
- Query admission, deadline, per-request resource budgets, snapshot ownership, and drain.
- Archive-off store hooks and capture exception isolation.
- Block marker, changeset, history, latest, cursor, and txNum validation.

This is not a production-scale performance claim. It only means no additional wrong-answer or
cross-consensus-path defect survived the review.

### 3.2 Confirmed findings

| ID | Severity | Finding | Consequence |
|---|---|---|---|
| R7-1 | Medium | `UnifiedArchiveInFlightStore.forEachBlock` decodes and retains every journal payload before invoking the first consumer callback. | Startup can exhaust heap before `DefaultArchiveService` applies its configured journal byte/record limits. |
| R7-2 | Medium | Trace bytes are bounded per request but not across concurrent admitted requests. | Eight default requests can retain several hundred MiB of raw trace objects concurrently. |
| R7-3 | Medium | `UnifiedArchiveDb` uses stock column-family options: no Bloom filter, no RocksDB stall/compaction telemetry, and scan reads use cache-filling `ReadOptions`. | Mature INDEX/LATEST point misses become expensive; startup scrubs and unwind scans evict useful point-read cache data; production tuning lacks evidence. |
| R7-4 | Low | `validateInFlightAppend` forces a filesystem free-space query for every canonical block. | Adds a synchronous filesystem call to the block path even when the cached sample is fresh and far above the watermark. |
| R7-5 | Low | `TOKEN_UPDATE_DONE` is the only one-time dynamic-property migration marker not classified as `NO_ARCHIVE`. | It creates useless history and write amplification and makes the policy inconsistent with its sibling markers. |
| R7-6 | Low | Unified DB ownership is implicit and several comments still describe removed legacy or mid-chain production behavior. | Resource ownership and the actual production architecture are harder to audit than necessary. |

### 3.3 Performance observations that remain guards, not defects

The following costs are deliberate correctness checks and must not be removed:

- One negative txId lookup per published user transaction.
- One latest-value point read per first unique changed key in a published block.
- Full codec validation when durable journal bytes are loaded from disk.
- Full startup scrub when explicitly configured or when repair state requires it.

R7-3 reduces their physical cost without weakening them.

## 4. Implementation slices

### Slice A - bounded two-pass startup journal scan

Priority: must land before the review cycle closes.

Files:

- `chainbase/src/main/java/org/tron/core/archive/UnifiedArchiveInFlightStore.java`
- `chainbase/src/main/java/org/tron/core/archive/unified/UnifiedArchiveDb.java`
- `chainbase/src/test/java/org/tron/core/archive/UnifiedArchiveBackendTest.java`

Plan:

1. Add a scan-oriented unified snapshot view with `fillCache=false`.
2. Keep one snapshot open across both passes so validation and delivery see identical bytes.
3. Pass 1 validates the complete INFLIGHT keyspace and lifecycle alignment:
   - every payload key is known;
   - every payload has exactly one matching token;
   - acknowledgement is optional but, when present, matches the token;
   - no token or acknowledgement is orphaned;
   - block key/value, journal state, schema, domain, and record semantics are valid.
4. Retain at most one decoded block during pass 1.
5. Invoke no consumer callback during pass 1.
6. Pass 2 re-decodes one validated block at a time, folds acknowledgement state, invokes the
   consumer, and releases the block before advancing.
7. Keep `loadBlocks()` as the explicitly collecting compatibility API; production startup
   continues to use `forEachBlock()`.

Required tests:

- Corruption in the final payload, token, acknowledgement, or unknown key invokes zero callbacks.
- Missing token and orphan lifecycle rows invoke zero callbacks.
- Valid blocks are delivered in block order with the correct folded journal state.
- A large journal set is delivered incrementally rather than through an aggregate block list.
- Iterator failure remains fail-stop.

Rejected alternative:

- Emitting callbacks during a single validation pass. It violates the existing invariant that no
  validated prefix is exposed before the entire journal keyspace has passed validation.

### Slice B - process-wide retained trace budget

Priority: must land before enabling sustained public trace traffic.

Files:

- `chainbase/src/main/java/org/tron/core/archive/query/ArchiveQueryLimits.java`
- `chainbase/src/main/java/org/tron/core/archive/query/ArchiveQueryCoordinator.java`
- `chainbase/src/main/java/org/tron/core/archive/query/QueryContext.java`
- `chainbase/src/main/java/org/tron/core/archive/query/HistoricalQueryLimitException.java`
- `chainbase/src/main/java/org/tron/core/archive/ArchiveMetrics.java`
- `chainbase/src/main/java/org/tron/core/archive/ArchiveServiceFactory.java`
- `common/src/main/java/org/tron/core/config/args/StorageConfig.java`
- `common/src/main/resources/reference.conf`
- `framework/src/main/resources/config.conf`

Plan:

1. Add `storage.archive.query.maxRetainedTraceBytes`.
2. Use a finite default of 256 MiB. `-1` remains the unlimited sentinel.
3. Keep the existing 64 MiB per-request trace limit unchanged.
4. Make `ArchiveQueryCoordinator` own one lock-free aggregate reservation counter.
5. `QueryContext.recordTraceBytes` first enforces the per-request limit, then reserves the same
   delta against the coordinator-wide limit before the corresponding trace object is allocated.
6. Add `RETAINED_TRACE_BYTES` as a typed `RESOURCE_EXHAUSTED` limit.
7. Release the exact reserved amount only when the query lease is finally released. If a lease is
   held by an open snapshot or response transport scope, the reservation remains held.
8. Failed reservations do not increment the aggregate counter.
9. Expose current retained trace bytes as a low-cardinality archive gauge.

Required tests:

- Two queries independently below 64 MiB cannot exceed the aggregate limit together.
- Closing one lease makes its reservation available to another query.
- Closing a lease while a snapshot is active does not release the reservation early.
- Repeated close and failed reservation cannot underflow or leak the counter.
- Standalone/unlimited `QueryContext` behavior remains unchanged.
- Config defaults, key validation, equality, builder copying, and factory mapping include the new
  value.

Rejected alternatives:

- Lowering only `maxConcurrentQueries`. Non-trace calls should not lose concurrency because trace
  objects are expensive.
- Relying on the JVM OOM killer or HTTP response limit. Raw trace objects exist before response
  serialization and can exceed the response limit.

### Slice C - safe RocksDB point-read and scan tuning

Priority: land before the large from-zero sync.

Files:

- `chainbase/src/main/java/org/tron/core/archive/unified/UnifiedArchiveDb.java`
- `chainbase/src/main/java/org/tron/core/archive/unified/UnifiedArchiveReadView.java`
- `chainbase/src/main/java/org/tron/core/archive/UnifiedArchiveInFlightStore.java`
- `chainbase/src/main/java/org/tron/core/archive/txnum/UnifiedArchiveTxNumIndex.java`
- `chainbase/src/main/java/org/tron/core/archive/temporal/UnifiedArchiveTemporalStore.java`
- `chainbase/src/main/java/org/tron/core/archive/ArchiveMetrics.java`

Plan:

1. Install a full-key `BloomFilter(10, false)` on every unified column family.
2. Retain each native Bloom filter until after the database and column-family options close.
3. Cache index/filter blocks through the existing default RocksDB block cache, where they remain
   evictable. Do not pin L0 blocks and do not introduce a large shared cache in this slice.
4. Add `openScanView()` using the same snapshot semantics as `openReadView()` but with
   `fillCache=false`.
5. Route full keyspace validation, journal scans, full scrubs, and unwind scans through scan views.
   Historical request readers continue using cache-filling point-read views.
6. Enable RocksDB `Statistics` only when Prometheus metrics are enabled, using
   `EXCEPT_DETAILED_TIMERS` to avoid detailed-timer overhead.
7. Retain and close the native `Statistics` object explicitly.
8. After unified publication or maintenance, at a bounded ten-second interval, export deltas for:
   - stall micros;
   - Bloom filter useful;
   - block-cache hit and miss;
   - compaction read/write bytes;
   - flush write bytes.
9. Export gauges by summing CF-scoped pending-compaction bytes with saturation and reading DB-wide
   running compaction/flush counts once. Never report a default-CF value as if it represented a
   CF-scoped whole-database total.
10. Any statistics/property/reporting failure is caught and cannot fail archive reads or writes.

Required tests:

- Unified DB opens, writes, reopens, and validates with the configured table format.
- Bloom filter and native statistics owners remain alive until DB close.
- Point-read views fill cache; scan views do not.
- Full scrub, journal validation, and unwind behavior stay identical through scan views.
- Metrics-disabled operation creates no statistics owner and remains behaviorally unchanged.
- Metrics failures do not escape a database operation.

Compatibility:

- The selected APIs exist in the branch's RocksDB 5.15.10 compile surface and the arm64
  RocksDB 9.7.4 runtime surface.
- Bloom filters and cache policy do not change the on-disk key/value schema and are safe across
  restart in either direction.

Explicitly deferred:

- A shared 256 MiB-1 GiB native `LRUCache`: requires an operator memory-budget decision.
- Pinned L0 index/filter blocks: reconsider only with that explicitly sized shared cache.
- `maxBackgroundJobs` and dynamic-level compaction: require representative sync measurements.
- Prefix extractors: require a complete total-order-seek audit.
- WAL recycling: rejected because the old RocksDB runtime has a known recovery-risk class.

### Slice D - remove unconditional per-block disk sampling

Priority: land with Slice C.

Files:

- `chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java`
- `chainbase/src/test/java/org/tron/core/archive/DefaultArchiveServiceTest.java`

Plan:

1. Calculate the required free-space threshold before sampling.
2. Use the existing one-second cached sample in the normal path.
3. Force a fresh sample only when the cached value is at or near the required threshold.
4. Keep the durable journal write authoritative: a real ENOSPC/write failure still triggers the
   existing fail-stop path even if the cache was stale-high.
5. Preserve startup and hard-watermark forced checks where no recent sample exists.

Required tests:

- Repeated healthy appends inside the sample interval do not query the filesystem per block.
- A near-threshold cached sample is refreshed and can reject the append.
- A stale-high sample followed by a durable journal failure still marks archive fatal.
- Disk metrics continue to report real samples, not projected values.

### Slice E - classify the remaining migration marker

Priority: land before any production archive database is created.

Files:

- `chainbase/src/main/java/org/tron/core/archive/domain/DynamicKeyPolicy.java`
- `chainbase/src/test/java/org/tron/core/archive/domain/DynamicKeyPolicyTest.java`
- schema-checksum assertions affected by the policy change

Plan:

1. Classify `TOKEN_UPDATE_DONE` as `MIGRATION_MARKER`.
2. Set root policy to `EXCLUDED`, history policy to `NO_ARCHIVE`, and reader policy to
   `INTERNAL_ONLY`.
3. Record that this intentionally changes the pre-release schema checksum.

Required tests:

- All five one-time migration markers have identical exclusion policy.
- The schema checksum changes deterministically and remains stable across process restart.
- Unknown future keys still retain diagnostic history and remain excluded from the root.

### Slice F - bounded implementation-quality cleanup

Priority: land after functional/performance slices.

Files:

- `chainbase/src/main/java/org/tron/core/archive/DefaultArchiveService.java`
- `chainbase/src/main/java/org/tron/core/archive/UnifiedArchiveBackend.java`
- `chainbase/src/main/java/org/tron/core/archive/txnum/UnifiedArchiveTxNumIndex.java`
- nearby reader and VM historical-call comments

Plan:

1. Replace stale class-level text that says a legacy RocksDB store supersedes the in-memory store.
2. Document the current shared-DB close order explicitly:
   - in-flight adapter: no-op close;
   - temporal adapter: no-op close;
   - txNum index: final shared DB owner.
3. Add assertions or tests that the shared DB closes once and only after all snapshot views drain.
4. Remove comments that claim historical VM flags use the live latest baseline.
5. Remove production-mid-chain wording where the production factory only permits a from-zero
   archive, while retaining test-only abstractions that still exercise the generic reader model.

Deferred refactors:

- Splitting the 2,300-line `DefaultArchiveService` into lifecycle, publisher, recovery, and query
  components.
- Replacing its telescoping constructors with a builder.
- Parsed-protobuf memoization in `DefaultArchiveStateReader`.

Those are real maintainability or performance opportunities, but they touch many test call sites
and should follow the production validation run rather than share a correctness hardening patch.

## 5. Planned implementation order

1. Slice A: startup memory safety.
2. Slice B: aggregate query memory safety.
3. Slice C: Bloom filters, scan views, and RocksDB telemetry.
4. Slice D: canonical-path disk sampling.
5. Slice E: pre-release schema cleanup.
6. Slice F: ownership and comment cleanup.

Each slice must keep focused tests green before the next slice starts.

## 6. Verification matrix

### Focused unit and integration tests

```text
./gradlew :chainbase:test -x generateGitProperties \
  --tests 'org.tron.core.archive.*' \
  --tests 'org.tron.core.archive.query.*' \
  --tests 'org.tron.core.archive.unified.*' \
  --tests 'org.tron.core.archive.temporal.*' \
  --tests 'org.tron.core.archive.domain.DynamicKeyPolicyTest'

./gradlew :actuator:test -x generateGitProperties \
  --tests 'org.tron.core.vm.archive.*' \
  --tests 'org.tron.core.vm.program.*Archive*'

./gradlew :framework:test -x generateGitProperties \
  --tests 'org.tron.core.archive.*' \
  --tests 'org.tron.core.services.jsonrpc.*Archive*' \
  --tests 'org.tron.core.services.jsonrpc.Historical*'
```

### Static verification

```text
./gradlew :framework:checkstyleMain :framework:checkstyleTest \
  -x generateGitProperties

git diff --check
```

`chainbase` and `common` do not expose checkstyle tasks in this build; their Java sources are
compiled by the archive regression suites.

### Behavioral regression

Re-run the private-chain scenario from
`docs/archiveV3/20260716-archive-private-chain-e2e-results.md`:

- 20 transaction functional coverage.
- 15 historical oracles before and after restart.
- graceful shutdown with a non-solidified tail.
- journal/canonical/acknowledgement/publication crash windows.
- corrupt MANIFEST/SST/history/marker/in-flight rows.
- strict offline reopen.

### Performance evidence

Before claiming improvement:

1. Compare block-push latency with archive disabled and enabled.
2. Compare publisher catch-up throughput before and after Bloom filters.
3. Record Bloom-useful, cache hit/miss, stall micros, and pending compaction bytes.
4. Run concurrent trace requests until the aggregate trace limit rejects new growth.
5. Confirm rejection is typed and heap remains bounded.

## 7. Completion criteria

The implementation is complete only when:

1. All six slices are implemented or explicitly removed from scope by a reviewed decision.
2. Startup journal delivery retains no aggregate payload list before budget enforcement.
3. Concurrent traces cannot exceed the configured aggregate retained-byte budget.
4. Point lookups have Bloom filters and scans do not populate the point-read cache.
5. RocksDB pressure is observable without changing correctness behavior.
6. Healthy block appends do not force one filesystem capacity query per block.
7. `TOKEN_UPDATE_DONE` is excluded before the first production archive database is created.
8. Focused tests, archive regressions, checkstyle, and diff checks pass.
9. The private-chain restart/fault/corruption matrix still passes.
10. Remaining production gates are stated without claiming completion:
    - large from-zero sync;
    - 72-hour soak;
    - capacity-limited real ENOSPC mount;
    - power-loss below the process/filesystem boundary;
    - hardware-specific cache and compaction sizing.

## 8. No-change decisions

The implementation must not:

- remove duplicate txId detection;
- remove temporal prev-value-chain validation;
- enable the async publisher by default before soak evidence;
- add a large shared RocksDB cache without an explicit native-memory budget;
- enable WAL recycling;
- add a prefix extractor without auditing every seek mode;
- weaken full-scrub corruption checks;
- make metrics or statistics failures fatal;
- change archive-disabled store behavior;
- claim commitment mode is implemented.

## 9. Adversarial plan review

The plan was reviewed through four independent lenses:

1. Correctness lens:
   Can any slice expose a validated prefix, weaken atomicity, alter historical semantics, or make
   archive-off behavior observable?
2. Crash/recovery lens:
   Can a new cache, two-pass scan, metric owner, or disk-sampling shortcut change what survives a
   crash or what startup accepts?
3. Resource/lifecycle lens:
   Can aggregate counters leak, native objects close too early, snapshots cross threads, or scan
   views retain unbounded state?
4. Performance-skeptic lens:
   Does a proposed optimization merely move cost, add per-op synchronization, pollute a different
   cache, or lack a metric that can prove benefit?

### 9.1 Concerns raised and resolutions

1. Two-pass journal validation could validate one generation and deliver another.
   Resolution: both passes use one immutable RocksDB snapshot. No callback runs until the complete
   first pass succeeds.
2. A three-prefix scan could miss unknown keys between known prefix ranges.
   Resolution: pass 1 includes a complete keyspace classification scan in addition to aligned
   payload/token/acknowledgement iterators.
3. Aggregate trace accounting could release bytes while serialization still owns the result.
   Resolution: release remains tied to final `QueryLease` release; snapshot and transport scopes
   already defer that release.
4. A failed aggregate reservation could be subtracted during close.
   Resolution: `QueryContext` tracks separately the bytes successfully reserved from the
   coordinator. Close releases only that counter.
5. A global lock on every trace opcode would make the memory guard a trace-performance regression.
   Resolution: use an atomic compare-and-set counter, not the coordinator admission lock. The
   existing per-request accounting and first-terminal ordering remain unchanged.
6. Bloom filters without cached metadata would preload one filter per table reader and grow native
   memory with SST count.
   Resolution: cache index/filter blocks so they remain evictable.
7. Pinning L0 metadata into the default small cache could exceed the intended native-memory
   envelope during an L0 stall.
   Resolution: L0 pinning was removed from this slice and deferred until a shared cache is sized.
8. RocksDB statistics could add detailed timing overhead or expose only the default CF.
   Resolution: use `EXCEPT_DETAILED_TIMERS`, bounded sampling, ticker deltas, saturated per-CF
   aggregation where required, and one read for DB-wide properties. Reporter/property failures are
   isolated.
9. Sampling properties from every point read or reader-open would put monitoring on request and
   publication hot paths.
   Resolution: point reads and reader-open perform no sampling. Publication/maintenance trigger a
   sample at most once per ten seconds, and close performs one final sample.
10. Cached free-space data could allow one append after another process consumes the disk.
   Resolution: the cache is advisory for at most the existing one-second interval; the WAL write
   remains authoritative and its failure still triggers fail-stop. Near-threshold values force a
   fresh sample.
11. `TOKEN_UPDATE_DONE` changes the schema checksum.
    Resolution: the product has no released archive database. The change is deliberately made now,
    before the first production database, and is covered by deterministic checksum tests.
12. A broad service split or builder conversion could bury correctness changes in constructor
    churn.
    Resolution: structural refactoring remains deferred; this round only corrects ownership
    documentation and close-order tests.

### 9.2 Verdict

No unresolved concern weakens archive atomicity, fail-closed behavior, archive-off behavior, or
native-resource ownership. The plan is approved for implementation in the order listed in
section 5.

## 10. Implementation record

All six slices were implemented.

1. Slice A:
   `UnifiedArchiveInFlightStore.forEachBlock` now performs complete key/lifecycle validation and
   incremental delivery in two passes over one scan-oriented RocksDB snapshot. No callback runs
   before the whole keyspace passes validation, and production startup no longer retains the
   aggregate decoded journal set.
2. Slice B:
   `ArchiveQueryCoordinator` owns a process-wide retained-trace reservation counter. The default
   aggregate limit is 256 MiB, reservations live until the final query lease release, and
   `RETAINED_TRACE_BYTES` is a typed resource-exhausted failure. The zero-valued metric is
   published when the coordinator starts, so zero use is distinguishable from a missing metric.
   Gauge publication is sampled at roughly 1/256 of the configured limit (capped at 1 MiB) and
   forced on release/rejection, avoiding a Prometheus label lookup on every traced opcode while
   leaving the admission counter exact.
3. Slice C:
   every Unified column family has a retained `BloomFilter(10, false)`, index/filter blocks remain
   cacheable and evictable, full scans use `fillCache=false`, and RocksDB statistics are created
   only when Prometheus is enabled. Stall, Bloom, cache, compaction, flush, and pending-work values
   are sampled outside point-read paths at a bounded interval.
4. Slice D:
   normal block appends reuse the one-second free-space sample and force a refresh only near the
   configured threshold. The durable journal write remains the authoritative ENOSPC boundary.
5. Slice E:
   `TOKEN_UPDATE_DONE` is classified with the other one-time migration markers as
   `MIGRATION_MARKER / EXCLUDED / NO_ARCHIVE / INTERNAL_ONLY`. This intentionally changes the
   unused pre-release schema checksum.
6. Slice F:
   Unified shared-database close ownership and adapter no-op closes are documented and tested;
   stale legacy and mid-chain production comments were removed without broad service refactoring.

No deferred shared-cache, compaction-thread, prefix-extractor, WAL-recycling, service-split, or
protobuf-memoization work was folded into this patch.

## 11. Verification record

### 11.1 Automated regression

The following passed on the final worktree:

```text
./gradlew :chainbase:test -x generateGitProperties \
  --tests 'org.tron.core.archive.*'

./gradlew :actuator:test :framework:test -x generateGitProperties \
  --tests 'org.tron.core.vm.archive.*' \
  --tests 'org.tron.core.vm.program.*Archive*' \
  --tests 'org.tron.core.archive.*' \
  --tests 'org.tron.core.services.jsonrpc.*Archive*' \
  --tests 'org.tron.core.services.jsonrpc.Historical*' \
  --tests 'org.tron.core.services.jsonrpc.StructLogReconstructorTest'

./gradlew :framework:checkstyleMain :framework:checkstyleTest \
  -x generateGitProperties

git diff --check
```

The final retained-trace metric change was additionally covered by
`ArchiveMetricsTest` and `ArchiveQueryCoordinatorTest`.

Compatibility verification also passed:

- arm64, Java 17, RocksDB 9.7 runtime and archive test execution;
- x86_64 compile surface, Java 8, RocksDB 5.15.10.

### 11.2 Final-artifact private-chain E2E

The exact final artifact was:

```text
framework/build/libs/FullNode.jar
SHA-256 52584cc81c1d63e3f63a7637696838a66c0cf508e8af7c4745373a07a8df512c
```

It was started from an empty directory as one SR with HTTP, JSON-RPC, gRPC, and Prometheus
enabled. The existing scenario completed:

```text
SCENARIO_OK transactions=20 oracles=15 finalHeight=57
ORACLE_REPLAY_OK count=15
```

Coverage included account creation and balance transitions, FreezeV2 resources, witness voting,
delegation, TRC10 issue/transfer, contract deployment, storage `111 -> 222 -> 0`, historical
`eth_call`, code/storage getters, and `SELFDESTRUCT`.

After graceful shutdown, canonical recovery stopped at block 62. Startup reconciliation removed
the non-recoverable tail, all 15 oracles replayed again, and health metrics reported:

```text
inflight_blocks=0
publisher_lag_blocks=0
repair_required=0
retained_trace_bytes=0
```

The strict offline probe reported:

```text
OFFLINE_PROBE_OK first=0 last=62 changesets=1156 tombstones=7
CF_COUNTS {META=2, INFLIGHT=0, INDEX=230, LATEST=281, HISTORY=1156,
CHANGESET=1156, BLOCK_MARKER=63, COMMITMENT=0}
```

### 11.3 SIGKILL recovery

The final artifact was also killed with `SIGKILL` while two
`CANONICAL_COMMITTED` in-flight journals were durable:

```text
publishedLast=74
JOURNAL block=75 state=CANONICAL_COMMITTED
JOURNAL block=76 state=CANONICAL_COMMITTED
```

The canonical database recovered through block 74. Startup removed the two journal-tail blocks,
served all 15 historical oracles correctly, left no in-flight rows or repair marker, and passed
the strict offline probe:

```text
OFFLINE_PROBE_OK first=0 last=74 changesets=1318 tombstones=7
```

### 11.4 Round-7 corruption rerun

Three corruption cases directly touching the new journal and scan paths were rerun against
independent copies:

1. An unknown INFLIGHT key was rejected before HTTP startup with
   `UNIFIED_V1 in-flight store has an unknown key`.
2. A malformed HISTORY value was rejected during startup/full validation and triggered archive
   fail-stop.
3. An active SST was modified in place; full scrub surfaced a RocksDB iterator error and triggered
   archive fail-stop.

The broader protocol-identity, MANIFEST, missing-marker, read-only, and repair-marker matrix
recorded in `20260716-archive-private-chain-e2e-results.md` remains applicable; Round 7 did not
weaken those paths.

### 11.5 Metrics observed

The live final-artifact run exposed non-zero Bloom/cache activity and the new pressure gauges:

```text
rocksdb_bloom_filter_useful=9
rocksdb_block_cache_hit=19
rocksdb_block_cache_miss=8
rocksdb_pending_compaction_bytes=0
rocksdb_running_compactions=0
rocksdb_running_flushes=0
retained_trace_bytes=0
```

All 30 observed historical queries completed below the 5 ms histogram bucket on this small
private chain. This is correctness and instrumentation evidence, not a production throughput
benchmark. The public JSON-RPC method binding intentionally does not expose `debug_traceCall` or
`debug_traceTransaction`; aggregate trace concurrency and exact reservation release are therefore
proved deterministically in coordinator/query-context tests rather than through the HTTP surface.

## 12. Completion audit

| Criterion | Evidence | Result |
|---|---|---|
| Six implementation slices | Section 10 plus current source/tests | PASS |
| Bounded startup journal memory | Two-pass snapshot scan and corruption-before-callback tests | PASS |
| Aggregate retained trace bound | Coordinator CAS budget, typed rejection, deferred-release tests | PASS |
| Bloom point reads and non-polluting scans | Unified DB owner/cache-policy tests and live Bloom metrics | PASS |
| RocksDB pressure observability | Live gauges/counters and failure-isolation tests | PASS |
| No per-block forced capacity syscall | healthy/near-threshold/stale-high durable-failure tests | PASS |
| Migration marker policy | policy and capture exclusion tests | PASS |
| Regression/static verification | Section 11.1 | PASS |
| Restart/fault/corruption behavior | Sections 11.2-11.4 | PASS |
| Production-scale limits stated honestly | below | PASS |

The Round-7 review/implementation cycle is complete. The following remain release gates rather
than missing Round-7 implementation:

- representative large from-zero synchronization;
- 72-hour sustained-load soak;
- capacity-limited filesystem ENOSPC;
- power-loss testing below the process/filesystem boundary;
- target-hardware native-cache, background-job, and compaction sizing;
- a supported commitment implementation and its own validation matrix.
