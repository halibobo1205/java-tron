# Archive round-12 performance, lifecycle, and interference review

- Date: 2026-07-17
- Branch: `feat/archive-node`
- Review base: `7e0fb04aa3`
- Layout: `UNIFIED_V1` only
- Status: IMPLEMENTED; FOCUSED, AGGREGATE, AND CHECKSTYLE GATES PASSED

## 1. Objective

Round 12 continued the adversarial archive review with three independent lenses:

1. query cache, bounded reads, native IO, and allocation behavior;
2. lifecycle, lock ordering, close/retry, fork, and standalone unwind behavior;
3. publication, startup reconciliation, full scrub, and copy amplification.

The coordinator reproduced the confirmed findings, added regression tests before changing behavior,
and rechecked the interaction between historical queries and canonical block execution. This round
does not treat source-level complexity reduction as measured production throughput.

## 2. Required invariants

1. A from-empty archive-enabled node must publish block 0 and reopen with
   `firstArchivedBlock == 0`.
2. A genesis-complete historical reader may retain immutable archive snapshots, but must not retain
   an execution/fork mutation lease or consistency lock for VM lifetime.
3. A mid-chain reader must retain the locks needed by its in-flight shield and fail closed rather
   than read a mutable live head.
4. Query deadlines must cover Java admission waits and be propagated to native RocksDB reads where
   the supported binding exposes deadline controls.
5. Historical point reads must not admit query data into the shared RocksDB block cache by default.
6. Closing a resource unsuccessfully must remain observable; a retry cannot silently report success.
7. Every public canonical rewind path must participate in archive writer lifecycle and mutation
   exclusion.
8. Startup validation and optional full scrub must keep snapshot/iterator cardinality bounded as
   chain height grows.
9. Publication must reject excessive retained bytes or mutation cardinality before defensive Java
   copies and the native `WriteBatch` allocation.
10. Archive-off and consensus state transitions must remain unchanged.

## 3. Confirmed findings

| ID | Severity | Finding | Consequence |
|---|---|---|---|
| R12-1 | High | Java deadline checks did not reach RocksDB `ReadOptions`; a blocked JNI read could outlive the request deadline while archive locks remained held. | A stuck disk read could delay fork, and on a mid-chain archive could delay publication, beyond the configured request deadline. |
| R12-2 | Medium | Public standalone `Manager.eraseBlock()` rewound canonical state without entering archive writer lifecycle. | Concurrent close could begin after canonical `fastPop()` and before archive unwind, leaving the two sides inconsistent. |
| R12-3 | Medium | Failed close paths were marked closed before propagating the resource failure. | A second close could silently return even though native/archive cleanup had failed. |
| R12-4 | Medium | Startup in-flight validation opened a temporal read view for each first-seen key. | Restart cost and native snapshot/iterator setup scaled with journal key cardinality. |
| R12-5 | Medium | Full scrub opened per-block views and copied every changeset key into a `HashSet`. | Snapshot/iterator setup scaled with block count and one corrupt/high-cardinality block could create avoidable heap pressure before reporting failure. |
| R12-6 | Medium | Publication builder limits were checked after some encoded rows and defensive copies had already accumulated. | A large but locally valid block could create excessive Java/native batch pressure before fail-stop admission. |
| R12-7 | Low | `acquireReadGuard()` reserved lifecycle work but never started its lease. | A guard admitted before drain could block close while being unable to complete its protected read. |
| R12-8 | Low | Temporal publication sorting copied canonical keys inside comparator calls. | High-cardinality blocks caused avoidable `O(R log R * keyBytes)` allocation. |
| R12-9 | Low | Query point reads used a new probe buffer and two native Gets even for common small values. | Repeated JNI/allocation overhead on the main historical read path. |

## 4. Implementation

### 4.1 Query IO and cache isolation

- Query snapshots now use explicit `fillCache=false`; scan/maintenance views retain their separate
  cache policy.
- A read view reuses a bounded 64 KiB probe. Values no larger than the probe complete in one native
  Get, and query/hard limits are checked before exact larger Java allocation.
- The remaining request time is propagated into query `ReadOptions`. The ARM RocksDB binding's
  `setDeadline` and `setIoTimeout` methods are invoked reflectively so the source remains compilable
  with the older x86 binding used by development builds.
- An enabled archive rejects `deadlineMs = -1`; a query can no longer be configured with an
  unbounded Java-side wait.
- Native timeout/error translation checks the monotonic query deadline before exposing a generic
  storage error, preserving deadline classification once time has elapsed.

Native RocksDB/OS support remains best effort: the setting bounds supported native operations but
is not a proof that every filesystem stall can be forcibly cancelled.

### 4.2 Query versus execution and fork mutation

- Genesis-complete unified readers now release both the consistency read lock and shared mutation
  lease after resolving the state point and capturing the immutable snapshot.
- The same release behavior was applied to the non-unified compatibility path used by tests.
- Mid-chain readers intentionally retain both protections because the in-flight earliest-prev
  shield must remain in one mutation generation.
- Production initialization was rechecked end to end: empty canonical storage journals, commits,
  publishes, and validates genesis block 0. Integration tests now assert both
  `firstArchivedBlock == 0` and `reader.isGenesisComplete()` before and after restart.
- Standalone `Manager.eraseBlock()` now acquires writer capacity, a writer lifecycle lease, and the
  exclusive mutation lease before canonical pop and archive unwind. Fork switching continues to
  reuse its outer lease through the private helper.

### 4.3 Lifecycle and close failure semantics

- Read guards now share a started thread-local lifecycle/read-lock state with a reference count.
  A guard admitted before drain may finish, while a new outer guard is rejected after drain begins.
- `DefaultArchiveService.close()` records a terminal close failure and rethrows it on every later
  call. Drain timeout remains retryable because resource shutdown has not started.
- Unified database and txNum index close paths preserve the same sticky-failure contract. The index
  is marked closed only after its database close succeeds.
- Resource failures are still aggregated so one close exception does not skip remaining cleanup.

### 4.4 Startup and full-scrub resource bounds

- Startup journal-chain validation opens one temporal read view and uses it for every previous-value
  lookup.
- Full startup validation opens one scan snapshot and binds index and temporal checks to that same
  database generation.
- Marker and changeset scans now advance in lockstep, and per-block row reads reuse the existing
  iterator. Snapshot and iterator creation no longer grows with the number of committed blocks.
- Removed the per-block copied-key `HashSet`; RocksDB physical key uniqueness supplies duplicate-key
  exclusion, while explicit row-count overflow checks retain fail-stop behavior.

The iterator-count regression measured the previous implementation at 21 iterators for one block
and 36 for sixteen blocks. The corrected implementation keeps the count constant across those
cases.

### 4.5 Publication admission and allocation

- `UnifiedArchivePublish.Builder` now receives retained-byte and mutation-count limits.
- Saturating counters reject an oversized entry before copying its key/value and reject excess
  mutation cardinality before adding more encoded batch work.
- Production derives the publication bounds from configured publisher hard in-flight byte/record
  limits, with a bounded allowance for system/index rows.
- Changeset sorting compares internal canonical keys without exposing or copying them.
- Journal byte-for-byte comparison remains in the synchronous publication path. It is deliberately
  retained until a compact journal proof has equivalent crash/corruption evidence.

## 5. Added adversarial coverage

- query versus scan `fillCache` policies;
- one native Get for a small bounded query value;
- native deadline and IO timeout propagation;
- rejection of an unlimited archive query deadline;
- from-genesis unified reader not blocking exclusive fork mutation;
- mid-chain reader continuing to block mutation until close;
- production genesis floor and complete-history classification before and after restart;
- standalone canonical erase participating in writer lifecycle while close races it;
- admitted nested read guards finishing during drain;
- sticky service and native database close failures;
- one startup temporal view for the complete in-flight journal chain;
- full-scrub iterator creation independent of block count;
- publication retained-byte rejection before oversized entry copy;
- publication mutation-cardinality rejection before adding the mutation.

## 6. Adversarial conclusions

### Query impact on execution

- The intended from-empty production deployment is genesis-complete. After snapshot capture, its
  historical VM execution does not hold archive consistency or mutation locks and therefore does
  not serialize normal block publication or fork mutation at the Java lock layer.
- Query point reads do not populate the shared archive block cache.
- Queries and execution still share the filesystem, RocksDB background work, OS page cache, and
  device queue. The code removes lock/cache poisoning found in this round, but cannot claim zero IO
  latency interference without workload measurements.
- A deliberately mid-chain test archive retains reader-lifetime locks. This is a correctness mode,
  not the supported from-empty production target.

### Execution impact on queries

- Publication cannot mutate a reader's RocksDB snapshot.
- Fork generation changes cannot invalidate an already captured genesis-complete temporal snapshot;
  canonical block identity is rechecked before the RPC result escapes.
- Close/drain waits for admitted readers, but new readers are rejected after drain begins.
- Publication limits fail-stop the archive sidecar before uncontrolled copy/batch growth; they do
  not change canonical transaction execution semantics.

## 7. Residual risks and optimization gates

The following are intentionally not reported as closed production-performance claims:

1. **Large HISTORY row preload.** HISTORY lookup first seeks an iterator to a variable-sized value.
   RocksDB may read/decompress the containing data block before Java applies the bounded point-read
   limit. A strict native-memory boundary requires a fixed-size locator keyspace and a separate
   payload column family, plus publication/startup row-size enforcement.
2. **Large-value second Get.** A legal temporal value above 64 KiB still needs a probe and exact Get.
   Reader-local decoded memoization prevents repeated domain/key reads in normal RPC execution;
   increasing the probe requires allocation and RSS evidence first.
3. **Synchronous journal verification.** Publication still re-encodes and compares the complete
   journal. Replacing this with length/digest proof requires a versioned durable format and a crash
   matrix proving equal or stronger corruption detection.
4. **Full-scrub scan constants.** Views/iterators are now bounded, but optional full scrub still
   decodes some HISTORY/CHANGESET data in multiple validation passes. A one-pass merge join is a
   future maintenance-startup optimization, not a block execution issue.
5. **Native deadline limits.** `ReadOptions` deadlines are propagated, but filesystem, kernel, or
   device failures may not be immediately cancellable on every platform. Fail-stop recovery and
   external process supervision remain necessary.
6. **Shared-device contention.** Block apply p50/p95/p99, RocksDB stalls, fsync latency, compaction,
   block cache hit rate, page-cache pressure, Java allocation, and native RSS still require a
   private-chain workload/JFR/ticker run at target hardware and maximum configured block size.
7. **Publication expansion.** The retained-byte estimate is conservative and bounded, not an exact
   native `WriteBatch` byte count. A lower operational limit may be appropriate after profiling.
8. **Journal decode copies.** Preflight, construction, and semantic validation still repeat some
   metadata traversal/copying. This is linear and startup-only; an ownership-transfer decoder can
   be considered after memory profiling.

## 8. Verification

Focused red/green tests for every implemented item passed. The first aggregate chainbase run also
caught a stale reflection binding in the incremental differential oracle after
`latestWithInFlight` gained a shared read-view parameter. The test was migrated to one explicit
temporal snapshot, its two differential cases passed, and the complete 560-test archive suite then
passed.

Final successful gates:

```text
./gradlew :chainbase:test -x generateGitProperties \
  --tests 'org.tron.core.archive.*'

./gradlew :actuator:test -x generateGitProperties \
  --tests 'org.tron.core.vm.archive.*'

./gradlew :framework:test -x generateGitProperties \
  --tests 'org.tron.core.archive.*' \
  --tests 'org.tron.core.db.ManagerArchiveLifecycleTest' \
  --tests 'org.tron.core.db.ManagerGenesisArchiveLifecycleTest' \
  --tests 'org.tron.core.db.ManagerGenesisArchiveTest' \
  --tests 'org.tron.core.db.ManagerMockTest' \
  --tests 'org.tron.core.services.jsonrpc.CallArgumentsTest' \
  --tests 'org.tron.core.services.jsonrpc.Historical*' \
  --tests 'org.tron.core.services.jsonrpc.TronJsonRpcArchive*' \
  --tests 'org.tron.core.vm.archive.*'

./gradlew :common:test -x generateGitProperties \
  --tests 'org.tron.core.config.args.StorageConfigTest'

./gradlew :framework:checkstyleMain :framework:checkstyleTest \
  -x generateGitProperties

git diff --check
```

No directly reproducible archive correctness, lifecycle, query-cache, or Java-lock interference
defect remains from the Round 12 finding set. Section 7 remains an explicit architecture,
hostile-disk, and production-measurement boundary rather than a claim that those risks are solved.
