# Archive round-20 long-run lifecycle, resource, and cache review

- Date: 2026-07-18
- Branch: `feat/archive-node`
- Review base: current uncommitted Round 10-19 worktree
- Layout: `UNIFIED_V1` only, schema 5, from-genesis public reads
- Status: SOURCE/TEST FIXES COMPLETE; RUNTIME RELEASE GATES REMAIN

## 1. Objective

Round 20 repeated the adversarial workflow around resource estimates that change after admission,
native RocksDB allocation, long-running lifecycle cleanup, journal mutation, and query-owned cache
objects. The review deliberately asked whether a healthy operation could pass a Java-side hard
watermark and then allocate a second hidden working set, or whether a failed query could leave an
object later consumed by canonical execution.

The review used three independent skeptic tracks:

1. publication/capture resource accounting and concurrent hard-watermark admission;
2. journal acknowledgement/delete, watchdog close, fatal ordering, and corruption evidence;
3. snapshot-bound temporal preparation, exact RocksDB reads, and query/cache ownership.

Every confirmed source issue was reproduced with a focused regression before the final full
matrix. Post-fix reviewers then challenged the accounting formulas, lock order, snapshot lifetime,
TOCTOU protection, and corruption behavior again.

## 2. Required invariants

1. A hard resource watermark includes retained journals, the largest future per-journal operation,
   active publication, active capture, and active journal mutation that can coexist.
2. Publication admission is state-aware. Persisted temporal payloads and their Java/native copies
   are accounted before payload materialization and before the atomic write batch is built.
3. A publication preflight is snapshot-bound, thread-bound, single-use, and always closed. Its
   snapshot is released before synchronous WriteBatch/WAL work starts.
4. Generic exact reads protect against untrusted expected lengths with a bounded probe. A one-read
   exact path is allowed only after the caller has already reserved both Java and native payload
   bytes.
5. Journal acknowledgement and loaded-journal delete include the RocksDB JNI native value plus all
   simultaneously live Java payload copies. Resource reservations are released on every exit.
6. Loaded-journal deletion trusts only a previously validated proof, re-authenticates current
   payload/proof/ack bytes, and atomically compare-deletes all lifecycle rows.
7. Fatal callbacks are never delivered before durable repair evidence has either completed or been
   recorded as the primary/suppressed failure. A callback cannot race ahead and terminate the
   process before the marker barrier settles.
8. Watchdog close cannot leave a timed-out continuation still able to mutate fatal state after the
   owning service has destroyed its resources.
9. Query caches retain immutable raw bytes or request-local values only. Parsed protobuf instances,
   mutable overlays, snapshots, and query failures cannot become shared execution inputs.
10. Archive-off consensus and block-apply behavior remain byte-identical and do not execute any of
    these archive-only accounting, watchdog, cache, or persistence paths.

## 3. Confirmed findings and fixes

| ID | Severity | Confirmed finding | Fix and direct evidence |
|---|---|---|---|
| R20-01 | Medium | An empty INDEX key reached a generic validation error instead of the persistent-corruption classifier. That could weaken repair/fail-stop handling for malformed on-disk index rows. | Empty physical index keys are now classified as persistent corruption. The corruption matrix verifies the typed failure and repair path. |
| R20-02 | High | Watchdog close could observe timeout arming but return while the timed-out continuation was still running. The continuation could then touch service state after close. | Close joins the claimed timeout continuation and preserves the timeout/cleanup failure ordering. Deadline, close-race, and retry tests cover delayed workers. |
| R20-03 | High | Fatal callback delivery could begin before the forced-sync repair marker barrier completed. A terminating callback could prevent durable evidence from reaching disk. | Fatal delivery is sequenced after the repair-marker barrier. Marker failure remains primary evidence, and tests prove the callback is not delivered while the barrier fails or is still blocked. |
| R20-04 | Medium | A shared raw lookup memo retained parsed protobuf objects. A mutable or partially failed query object could be reused by another read path. | The memo stores raw immutable bytes only and parses a fresh object per consumer. Query-local overlays and VM state remain outside shared caches. |
| R20-05 | High | Publication admission used only static journal/row estimates. Loading persisted anchors/latest/history/changeset rows could add a large unaccounted Java and JNI working set after admission. | Temporal publication now has a snapshot-bound locator-only preflight. It computes all Java payload/copy bytes plus the largest sequential native PinnableSlice before any payload Get; the observer can reject while only fixed locators have been read. |
| R20-06 | High | Active capture and a state-aware publication could independently pass the same hard watermark, then coexist above it. No-op captures were also charged like real mutations. | Capture reserves bytes incrementally through the service resource ledger; publication updates its active reservation after state-aware preflight. Every combined transition is checked, no-op values reserve nothing, and waiters are signalled only when usage decreases. |
| R20-07 | Low | Clearing the active capture reservation to zero did not refresh resource gauges, leaving stale operational metrics after abort/commit cleanup. | The zero transition refreshes metrics once, without adding per-store-write metric traversal to the canonical path. |
| R20-08 | High | Journal acknowledgement materialized one Java journal plus one native RocksDB value without active mutation accounting. Loaded delete could simultaneously retain two Java journals and one native value. | ACK reserves `2 * encoded + 8 KiB`; loaded delete reserves `3 * encoded + 8 KiB`. Per-journal steady footprint is `max(publication workspace, delete workspace)`, so every admitted journal can later be removed safely. |
| R20-09 | High | General delete re-decoded and canonical-validated the full journal after startup/put had already authenticated it. Besides redundant CPU, this introduced another difficult-to-bound full payload workspace. | Service rollback/unwind uses `deleteLoadedBlock`: it requires the cached validated proof, performs one exact current-payload read, verifies digest/token/ack, and executes a durable atomic compare-delete. General standalone delete remains available for callers without a loaded proof. |
| R20-10 | Medium | A naive single-Get optimization for exact reads would allocate directly from an untrusted expected length; retaining the generic two-Get path, however, doubled large payload reads during already-accounted publication/delete. | The API is split. `getExact`/`getExactBudgeted` retain a bounded probe for untrusted lengths. `getPreaccountedExact` performs one Get only at the two hard-reserved call sites: temporal preparation and loaded-journal delete. Missing-value tests prove generic reads do not allocate from a forged 256 MiB length. |
| R20-11 | Medium | Publication kept the temporal preflight snapshot pinned through synchronous WriteBatch/WAL even though all snapshot-backed payloads had already been copied into the builder. | Preflight closes immediately after temporal staging and before the atomic batch write. A batch-writer observer now directly asserts `activeReadViews == 0` at the write boundary. |
| R20-12 | Medium | Loaded delete lacked direct same-length mutation regressions for each of journal payload, proof, and acknowledgement. A future optimization could accidentally weaken one comparison without changing row sizes. | Three direct tests mutate each row independently, require typed corruption, and verify that all three lifecycle rows remain durable after rejection. |

## 4. Resource proof after fixes

For an encoded journal of `E` bytes:

- acknowledgement peak: one Java journal plus one RocksDB native value, conservatively
  `2E + 8 KiB`;
- loaded delete peak: the caller's Java journal, the compare-delete Java journal, and one native
  RocksDB value, conservatively `3E + 8 KiB`;
- steady per-journal reservation: the greater of state-aware publication preparation and loaded
  delete workspace;
- active capture, active publication, and active journal mutation are additional concurrent scopes,
  checked against the same saturated hard-watermark calculation.

For temporal publication, preflight sums every Java payload that will remain live, every required
Java copy, and the largest native payload read. Native Gets are sequential under one publication
thread, so native bytes use `max(payload)` rather than `sum(payload)`. The final preparation path
uses one preaccounted Get per payload; locator reads remain fixed-size and bounded.

The generic exact API intentionally still performs a bounded probe and a conditional second Get.
It is used where the expected length may be missing, stale, corrupted, or query-controlled. The
single-Get API is not a general performance shortcut.

## 5. Post-fix adversarial results

1. Independent review found no remaining confirmed lock-order inversion, reservation leak, healthy
   data-path undercount, snapshot leak, or non-atomic loaded-journal delete.
2. ACK and delete active scopes release in `finally`. Steady footprint already dominates ACK, while
   dynamic publication/capture scopes are added rather than substituted.
3. Validated journal proofs enter the cache only after put or complete startup decode/canonical
   validation. Failed scans invalidate the cache and cannot expose a validated prefix.
4. Snapshot validation is followed by a journal-lock-protected persistent compare before delete.
   Payload, proof, and acknowledgement cannot be silently replaced through another production
   mutation between those checks.
5. Publication preflight remains on its original snapshot even if the live database changes before
   staging. It is owner-thread-only and cannot be consumed twice.
6. The atomic publish builder deep-copies staged rows. Closing preflight before WriteBatch/WAL does
   not create a use-after-close dependency.
7. Query-visible raw memos contain bytes only. Historical VM config, overlays, readers, response
   serialization, and protobuf objects remain request-owned and settle before permit release.

## 6. Verification evidence

- Focused backend/service/async publisher matrix: 202 tests green before the final full run.
- Focused untrusted-length, single-read preparation, observer rejection, snapshot ownership,
  capture/publication overlap, ACK/delete accounting, fatal barrier, and watchdog close tests: green.
- New native-peak estimate, batch-write snapshot-release, and three loaded-delete mutation tests:
  green.
- Complete `:chainbase:test --tests 'org.tron.core.archive.*'`: green after all Round 20 changes.
- Actuator archive/historical-VM matrix, including hard VM failure and proof deadline tests: green.
- Framework archive, JSON-RPC, lifecycle, genesis, fork, historical VM, VM-config isolation, and
  RocksDB cacheless-read matrix: green in 1m47s.
- Repository Checkstyle tasks and `git diff --check`: green.

## 7. Residual engine and corruption limitations

1. If an out-of-band writer coherently replaces a temporal payload with a value larger than its
   authenticated locator length, RocksDB JNI may materialize the actual native value before Java
   observes the length mismatch. The operation fail-stops, but the transient native allocation can
   exceed the locator-derived budget. This belongs in the real disk-corruption matrix; reverting all
   prepared reads to two Gets would penalize every healthy publication without preventing hostile
   native allocation in general.
2. The `2E` and `3E` formulas follow the current RocksDB JNI implementation and Java object
   lifetimes. NMT/JFR/native-allocator measurements near the configured hard limit are still
   required on the release JVM and RocksDB build.
3. Atomic WriteBatch behavior is covered by injected failure and restart tests, but a real process
   kill immediately before/during/after WAL sync is still required for journal three-row deletion
   and cross-CF publication.
4. Java admission cannot isolate query and publication I/O at the filesystem, page-cache,
   compaction, WAL, or device layers. A shared-device stall can still stop both; the code guarantee
   is bounded fail-stop evidence, not independent physical progress.
5. ENOSPC, EIO, permission changes, torn MANIFEST/SST/WAL, and coherent multi-row corruption remain
   runtime fault-injection gates.

## 8. Performance conclusion

Healthy publication no longer reads each large persisted temporal payload twice. It performs a
locator-only admission pass, one preaccounted payload preparation pass, releases the snapshot, and
then commits one atomic cross-column-family batch. ACK and loaded delete have explicit finite
working-set reservations. Capture accounting is incremental and no-op writes do not consume archive
capacity.

These changes remove confirmed Java-side memory-accounting holes and redundant full journal or
payload decoding. They do not establish production throughput, forced-sync latency, compaction
behavior, or RSS limits on target hardware.

## 9. Runtime release gates

The production evidence gates remain open:

1. sustained from-zero sync with production heap, RSS, native memory, JFR, block latency, and
   publication backlog measurements;
2. near-hard-limit incompressible journals validating the `2E/3E` native-memory envelope;
3. real WAL/WriteBatch kill points for append, ACK, loaded delete, and cross-CF publication;
4. ENOSPC/EIO/permission/SST/MANIFEST corruption and restart matrices;
5. concurrent maximum-cost historical queries during compaction and forced-sync publication;
6. external authenticated state oracle/root comparison and full-size restart/scrub SLOs.

No production-readiness claim is made by this source/test round alone.
