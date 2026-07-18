# Archive round-15 resource, long-run, and interference review

- Date: 2026-07-18
- Branch: `feat/archive-node`
- Review base: current uncommitted Round 14 worktree
- Layout: `UNIFIED_V1` only, schema 5
- Status: SOURCE/TEST REVIEW COMPLETE; RUNTIME RELEASE GATES REMAIN

## 1. Objective

Round 15 targets the release gates that source-level correctness tests do not close by themselves:

1. distinguish the 256 MiB format/configuration ceiling from the smaller effective block-admission
   ceiling and from the real Java/JNI/native peak;
2. prove that normal publish, restart, reorg, abort, and async paths retain no state proportional to
   historical chain height;
3. find code-controlled query/execution interference beyond unavoidable filesystem and device
   sharing;
4. remove only abstractions and compatibility branches whose production call graph is proven dead.

## 2. Required invariants

1. Every allocation derived from persistent or RPC-controlled length has a validated bound before
   allocation, including startup, ACK, publication, maintenance, and query decode.
2. Admission accounts for simultaneously live Java values and native batch copies closely enough
   that an admitted block cannot predictably OOM a correctly sized production heap.
3. In-memory maps, queues, deques, counters, thread-locals, metrics, and proof caches track bounded
   active work rather than chain height or total request count.
4. Failed scan, publish, rollback, close, and fatal transitions cannot leave a reusable stale cache
   capability or a counter that suppresses later backpressure.
5. Query snapshots, iterators, caches, and memoized values cannot alter execution-visible state or
   admit untrusted query data into publication point-read caches.
6. Execution and publication may consume shared device bandwidth, but no query-controlled lock or
   permit may indefinitely block canonical progress after its deadline.
7. A removal or API contraction must be supported by the production call graph and preserve the
   archive-off and consensus paths.

## 3. Resource model under review

The following ceilings are deliberately separate:

- temporal format payload ceiling: 256 MiB;
- publisher hard in-flight retained bytes: 256 MiB by default;
- publication preflight: conservative per-record key/value working-set multipliers plus journal,
  index, and mutation allowances;
- RocksDB DB write buffer: 128 MiB;
- archive block cache: 72 MiB;
- RocksDB WAL bound: 256 MiB.

Normal block admission can reject a value well below the format ceiling because the publication
preflight reserves multiple simultaneous representations. Maintenance and corruption-recovery
paths must still be reviewed against the full format ceiling. Native `WriteBatch` ownership is not
automatically represented by Java retained-byte accounting.

## 4. Adversarial workflow

1. Trace ownership and copies for capture, journal encode/write, ACK, temporal preparation,
   `UnifiedArchivePublish`, RocksDB `WriteBatch`, startup decode, and query decode.
2. Run long sequential publish, bounded in-flight, abort/re-mine, restart, and async drain oracles;
   inspect every persistent Java collection and counter before and after settlement.
3. Stress query snapshot permits, mutation/publication locks, cache-fill policy, iterator ownership,
   deadline seals, and per-reader memoization against concurrent canonical progress.
4. Build a production call graph for suspected dead compatibility surfaces before proposing any
   deletion.
5. Fix confirmed defects with focused regressions, then run independent post-fix reviewers and the
   aggregate archive test matrix.

## 5. Verification gates

- focused maximum-resource and copy-count tests;
- sequential publication beyond configured in-flight block count;
- abort/re-mine and restart counter oracles;
- concurrent query/publication/fork deadline tests;
- chainbase, actuator, framework, and common archive suites;
- main/test checkstyle and `git diff --check`;
- at least two independent post-fix adversarial reviews with no confirmed issue.

## 6. Findings

| ID | Severity | Confirmed defect | Disposition |
|---|---|---|---|
| R15-01 | High | In-flight backlog reserved only retained journal objects. Future temporal preparation, publish-builder copies, and native batch ownership were not represented, so many individually admissible blocks could create a publication peak above the hard byte limit. | Added one saturated resource estimator shared by capture, in-flight, and temporal preparation. The final model reserves all retained journals plus the largest pending publication working set; publication is serialized. Startup includes active and stale journals. |
| R15-02 | High | Current-block capture used an independent hard limit. A backlog just below the hard watermark could coexist with another almost-hard capture before commit rejected it. Account-asset derivation also parsed old/new Account protos before any transient allowance. | `beginBlock` snapshots the existing in-flight resource total into the capture baseline. Every raw record is checked against backlog + current capture + active transient parse bytes before codec parsing/copying. Account-asset parsing uses a scoped allowance released in `finally`. |
| R15-03 | High | A proof-bound temporal locator could claim a payload up to the format ceiling. Exact query reads allocated that claimed Java array before proving the physical value had that size; a corrupt locator could force a 256 MiB allocation per admitted query. | Exact reads now preflight the query budget, use at most a 64 KiB native length probe, verify the actual length, account the materialized bytes, and only then allocate the exact array. Missing or mismatched values never allocate the claimed payload. |
| R15-04 | High | Startup journal recovery bounded encoded payload and final decoded retention separately. The encoded payload remains live while decoded records are created, so previous decoded blocks + current payload + current decoded block could exceed the hard limit. | Journal scan now subtracts the live payload from the remaining decode-retention budget. A boundary test passes at exactly `payload + decoded retained`; lower limits reject during allocation-free preflight. The payload reference is explicitly cleared before the next scan iteration. |
| R15-05 | High | Historical VM copy-on-write maps were per request but had no byte/allocation ceiling. A contract generating many distinct storage or transient-storage writes could retain memory proportional to VM steps across every concurrent archive query. | Added finite `maxVmOverlayBytes` configuration (32 MiB default), terminal `VM_OVERLAY_BYTES` accounting, metrics, and pre-map-insertion reservations in the historical repository. The outer executor restores the exact terminal budget failure if a nested VM catches it. |
| R15-06 | Medium | The 256 MiB temporal format ceiling was also treated as a practical stored-value ceiling, while publication may retain roughly eight payload representations across Java preparation, builder copies, and native `WriteBatch`. | Kept 256 MiB as the corruption/format ceiling but limited production stored payloads to 32 MiB. Factory validation rejects a larger query single-value limit and a publisher hard limit above the format ceiling. Default query single-value allocation remains 4 MiB. |
| R15-07 | Medium | Archive-enabled configuration still accepted selected `-1` query limits, including admission wait. A request can enter the coordinator before its per-query deadline exists, so an unlimited acquire timeout is not bounded by that deadline. | Production factory validation now requires every concurrency, wait, deadline, backend allocation, VM, trace-retention, and response dimension to be finite. In-memory compatibility builders may still use `UNLIMITED`. |
| R15-08 | Medium | Temporal decode, full scrub, and reader memoization made avoidable full payload copies. At the upper bound those copies materially widened Java peak memory. | Added direct range-to-`DomainValue` construction, allocation-free payload validation/digest views, size-only codec validation, and one-copy ownership transfer into reader memo entries. |
| R15-09 | Low | Five compatibility abstractions had no production caller and represented superseded semantics: live archive read-through, an external consistency guard, and a second JSON-RPC selector resolver/state-point model. | Deleted `ArchiveReadThrough`, `ChainBaseArchiveReadThrough`, `ArchiveService.ReadGuard`, `JsonRpcArchiveStatePointResolver`, and `ResolvedArchiveStatePoint`, plus tests that only exercised those unreachable branches. Current adapter/snapshot/epoch tests remain. |
| R15-10 | High | The first backlog fix summed one publication working set per pending block even though publication is lock-serialized. Two admissible journals could therefore be fail-stopped at `2R + 2P` although the real bound is `2R + P`. | Added a counted publication-footprint multiset. Admission, startup, metrics, add, and remove now use `sum(retained journals) + max(pending publication)`, with exact-bound and return-to-zero tests. |
| R15-11 | High | Capture accumulated the complete future pipeline estimate for every raw write before same-transaction/same-key coalescing. Repeated writes could deterministically reject a valid block even though only the first previous value and final value survive. | Split retained capture state from the normalized future pipeline. Pre-normalization admission bounds current retained state plus the transient input; post-normalization accounting replaces the prior coalesced record estimate and still bounds the future journal/publication phase. Raw operation count remains cumulative. |
| R15-12 | Medium | A configured `Long.MAX_VALUE` deadline saturated to the runtime unlimited sentinel, while a finite `Long.MAX_VALUE` additive budget could not detect the next unit after its counter saturated. The same edge existed in batch admission and retained-trace reservation. | Production rejects millisecond durations outside the representable nanosecond range. Runtime deadline state now has an explicit configured bit, and additive counters detect overflow from the pre-add value even when the observed value saturates. |
| R15-13 | Low | The conservative `getAsOf` cost of 10 omitted the second RocksDB Get used after a 64 KiB length probe for each large integrity payload. The worst historical predecessor path can perform four such payload reads. | Raised the precharged conservative cost to 14: four locator reads, up to eight payload Gets, and two iterator operations. Small-value requests remain deliberately overcharged rather than under-limited. |
| R15-14 | High | While async publication writes the head without the consistency lock, a tail block may encode and write its journal concurrently. The steady-state formula `Rtotal + max(Ppending)` did not include that second transient working set, especially for an empty block with no record-level capture reservation. | Track the exact active head-publication estimate from before releasing the consistency lock until publish returns. Tail admission now checks both steady state and `Rtotal + activePublication + candidatePublication`; a blocked-publish regression proves the former exact-bound case is rejected. |
| R15-15 | Low | At exactly `Long.MAX_VALUE` active snapshots, rejection was correct but `activeSnapshots + 1` wrapped the reported observation negative before the explicit saturation guard. | Compute the diagnostic observation with saturation before both finite-limit and absolute-overflow guards; the regression asserts `Long.MAX_VALUE`, never a negative count. |

## 7. Long-run and interference result

No additional confirmed chain-height leak or query-controlled canonical stall was found after the
fixes:

- `validatedProofs` follows live journals and is removed only after successful atomic publication;
  failed scans clear the whole capability cache.
- production `UnifiedArchiveTxNumIndex` reads committed rows from RocksDB; its execution-only
  in-memory allocator is rewound or discarded with the active/replayed block and does not retain
  committed history.
- in-flight block/version/resource counters are added and removed together. A counted multiset
  tracks duplicate publication footprints without rescanning the backlog; publication completion
  asserts that journals, footprint counts, and `inFlightResourceBytes` return to zero.
- query snapshots use `fillCache=false`, are bounded by snapshot permits, and are owned by the
  reader through response settlement. Iterator and snapshot closure remains idempotent.
- unified queries do not hold the service consistency lock during execution. Fork/startup mutation
  changes the epoch; the result is rejected before response commit instead of blocking canonical
  progress for the query lifetime.
- the only unavoidable interference left is shared filesystem/page-cache/device bandwidth and
  RocksDB background work. No query-controlled Java cache, publication lock, or unbounded waiter
  remains on canonical block execution.

## 8. API contraction proof

Repository-wide production and test call-graph searches found no caller for the five removed
surfaces. Current behavior remains covered at the real entry points:

- `latest` bypasses archive; historical selectors route through `ArchiveJsonRpcStateAdapter`;
- public historical state requires from-genesis coverage and fails before state lookup for a
  mid-chain archive;
- block/transaction resolution, unified snapshot creation, and temporal reads share one query
  context and snapshot generation;
- canonical epoch is checked before exposing the reader and after response serialization;
- lifecycle and query leases keep close/drain correct without an external `ReadGuard`.

The authority document now records these implementation-state decisions so older planning packets
cannot reintroduce the deleted models.

## 9. Verification evidence

Completed focused gates:

- 258 chainbase resource, journal, temporal, reader, and service tests;
- focused exact-bound tests for coalesced capture, serialized publication footprints, concurrent
  async publication/tail journaling, saturated counters/deadlines, large payload read cost, and VM
  overlay allocation;
- common `StorageConfigTest` for finite/default query limits;
- actuator archive repository and historical constant/trace executor tests;
- framework JSON-RPC state, eth-call, trace, batch serialization, epoch, switch-fork, genesis, and
  manager lifecycle tests;
- the aggregate chainbase + actuator + framework archive matrix completed successfully after the
  final fixes (`BUILD SUCCESSFUL`, 2m27s);
- configured `framework:checkstyleMain` and `framework:checkstyleTest` completed successfully;
- two independent final post-fix reviewers confirmed the active-publication resource formula and
  saturated query/snapshot boundaries with no remaining confirmed issue;
- `git diff --check`.

## 10. Residual release gates

1. Run maximum admitted block/query workloads under production heap sizing with RSS, native-memory
   tracking, JFR allocation profiles, RocksDB statistics, and forced full GC observations. Static
   estimates deliberately remain conservative but are not a substitute for measured JNI/native
   ownership.
2. Repeat kill-at-journal/ACK/publish windows and the real disk fault matrix: ENOSPC, EIO,
   permission loss, truncated/corrupt WAL/SST/MANIFEST, torn directory state, and restart retry.
3. Run sustained from-zero sync and SR production workloads long enough to cover compaction,
   checkpoint growth, query concurrency, solidification lag, and restart-time range validation.
4. Full scrub and complete range-chain validation remain proportional to archive size. Production
   restart SLOs need measured data before authenticated checkpoints or offline scrub scheduling are
   designed.
5. Coherent deletion of every locator, payload, history, changeset, latest, and anchor witness for
   one logical key cannot be distinguished from a never-existing key without an independent state
   commitment. That is a commitment/oracle release item, not another local-row checksum fix.
6. Valid but pathological protobufs can expand beyond their wire size during canonicalization.
   Capture now gates input before parsing and the practical payload ceiling is lower, but maximum
   Account/Contract decode expansion still needs measured corpus/fuzz evidence.
7. Archive byte limits do not include fixed RocksDB block cache, write buffers, JVM base heap, or
   the operating-system page cache. Deployment memory guidance must budget these fixed pools plus
   bounded concurrent query and publisher work; setting the hard in-flight limit equal to total
   process memory is invalid.

No production-readiness claim is made by this source/test round alone.
