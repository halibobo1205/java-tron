# Archive round-8 adversarial review and fix plan

- Date: 2026-07-16
- Branch: `feat/archive-node`
- Review base: `1f0f197f93`
- Layout: `UNIFIED_V1` only
- Status: IMPLEMENTED, FOCUSED REGRESSION PASSED

## 1. Objective

Re-audit the complete archive implementation after round 7, with independent correctness,
concurrency, recovery, performance, resource-ownership, and maintainability lenses. Every proposed
optimization is challenged against fail-stop and historical-read invariants before implementation.

## 2. Invariants

1. Archive-off execution remains unchanged.
2. Journal persistence precedes canonical commit; acknowledgement follows canonical commit.
3. Index, temporal rows, marker, cursor, and journal deletion become visible atomically.
4. Startup either reconstructs one contiguous canonical archive or fails promptly.
5. Historical reads never fall back to current live state without proven historical evidence.
6. One reader observes one storage generation.
7. Query and native-resource limits remain bounded through success, failure, and shutdown.
8. Performance changes must not weaken codec, canonical-hash, prev-value-chain, or deadline checks.

## 3. Confirmed findings

| ID | Severity | Finding | Consequence |
|---|---|---|---|
| R8-1 | High | Persisted block/tx coordinates accept `Long.MAX_VALUE`, while allocation, cursor, adjacency, and closed-range loops use `+1`/`++`. | A crafted/corrupt range can wrap negative, corrupt the cursor, or hang startup/full scrub instead of failing promptly. |
| R8-2 | Medium | Startup limits stale-published and active journals separately for block/record counts, although both sets are retained concurrently. Only bytes are checked in aggregate. | A restart can retain up to roughly twice the configured block/record hard limit. |
| R8-3 | Medium | Several reader/factory/shutdown cleanup paths catch only `RuntimeException` or stop after the first close failure. | An `Error` can leave a read lock, snapshot, query lease, or later native owner unreleased, obscuring the original failure and stalling shutdown. |
| R8-4 | Medium | Historical reader memoization caches raw bytes only. `getAccount`, `getContract`, and `getContractState` reparse immutable protobufs; each SLOAD calls `getContract` again. | Historical contract execution pays repeated protobuf parsing and allocation despite a cache hit. Canonical block execution is unaffected. |
| R8-5 | Low | `HistoricalArchiveVmDynamicProperties` inherits a latest-delegating implementation and overrides the current interface manually. | A future `VmDynamicProperties` method can silently inherit live/latest state instead of causing a compile-time completeness failure. |

## 4. Areas re-reviewed with no confirmed defect

- Manager journal/canonical/ack/publication ordering.
- Startup canonical hash validation and acknowledgement reconstruction.
- Fork replay and in-flight unwind ordering.
- Unified cross-column-family atomic publication and snapshot sequence sharing.
- Mid-chain earliest-prev read-through shield.
- Publisher/lifecycle/mutation/query lock order and drain protocol.
- Query terminal-failure propagation and response/snapshot lease ownership.
- Archive-off store hooks and capture-failure isolation.
- RocksDB Bloom filters, scan views, and bounded statistics probes added in round 7.

No reproducible deadlock, cross-generation read, archive-off regression, or silent latest-state
fallback survived the adversarial pass.

## 5. Implementation slices

### Slice A - finite coordinate domain and checked continuation

1. Reserve `Long.MAX_VALUE` as a cursor/sentinel only; the maximum real block or tx coordinate is
   `Long.MAX_VALUE - 1`.
2. Centralize coordinate validation.
3. Reject the reserved value in persisted range/position/journal keys and payload validation.
4. Permit a committed cursor of `Long.MAX_VALUE` only when the last real tx coordinate is
   `Long.MAX_VALUE - 1`; reject any further allocation before increment.
5. Validate block numbers before adjacency arithmetic.
6. Add boundary tests for codec encode/decode, in-flight validation, allocator exhaustion, and
   block-number exhaustion.

### Slice B - aggregate startup journal limits

1. Track total startup blocks and records across stale-published and active journals.
2. Apply configured hard block, record, and byte limits to the aggregate retained set.
3. Keep stale/active breakdowns in the failure message.
4. Preserve runtime backlog accounting: stale journals are not charged after successful startup
   reconciliation because they are cleanup work, not unpublished backlog.
5. Add mixed stale+active tests for both block and record limits.

### Slice C - failure-complete resource cleanup

1. Make `DefaultArchiveStateReader.close()` run both snapshot close and `onClose` for every
   `Throwable`, preserving the first failure and suppressing later distinct failures.
2. Make factory-owned snapshot construction close the view for `RuntimeException` and `Error`.
3. Make service reader-open wrappers preserve the original failure while closing owned readers.
4. Make service shutdown attempt every archive owner close even when one throws `Error`, then
   rethrow the first failure with suppression.
5. Apply the same distinct-failure suppression guard to unified read views.
6. Add tests proving lock/release callbacks still run after an `Error` and the original failure
   remains primary.

### Slice D - bounded decoded protobuf memoization

1. Keep immutable decoded protobufs inside the existing per-reader LRU entries.
2. Return a fresh mutable capsule wrapper for every call; never expose a shared mutable capsule.
3. Reserve a conservative decoded-object allowance in the existing cache-byte estimate, so this
   does not create a second unbounded cache.
4. Reuse the decoded `SmartContract` for repeated SLOAD storage-key construction.
5. Eviction and malformed-value handling remove both raw and decoded state together.
6. Add tests for protobuf identity reuse, mutable-wrapper isolation, backend-read stability, and
   eviction behavior.

### Slice E - compile-time historical VM property completeness

1. Make `HistoricalArchiveVmDynamicProperties` implement `VmDynamicProperties` directly.
2. Own `energyFee` and its default locally.
3. Remove the latest-delegating historical base and all live-store constructor dependencies.
4. Update eth-call, trace, genesis validation, tests, and stale documentation.
5. Rely on Java interface implementation checks so a future getter cannot silently fall back to
   latest.

## 6. Adversarially rejected changes

### Remove deep journal codec validation from the produce path

Rejected for this round. `UnifiedArchiveTemporalStore.stagePublication` does not independently
prove every domain value is canonical. Removing `ArchiveInFlightValidator` from `putBlock` would
remove the only complete in-process publication boundary, even though it costs duplicate parsing.
A future optimization needs a typed, trusted-canonical record boundary plus benchmark evidence.

### Sample deadlines only every N VM opcodes

Deferred. The current historical-only cost can be measured, but deleting pre/post opcode checks
changes deadline overshoot semantics and weakens protection around non-interruptible precompiles.
No canonical block path is affected.

### Cache `FileStore`, add shared native cache, or change compaction defaults

Deferred pending representative from-zero sync measurements and an explicit native-memory budget.
Caching a `FileStore` can also hide mount replacement, weakening disk-safety checks.

### Presize journal encoding from retained-heap estimates

Rejected. The retained estimate is intentionally conservative and can cause a large eager
allocation. Exact encoded-size calculation is acceptable only if profiling shows this allocation
is material.

## 7. Verification gates

1. Focused red/green tests for every slice: passed.
2. Focused `:chainbase:test` codec, tx-number, in-flight, reader, and service suites: passed.
3. Focused `:framework:test` historical archive/VM/JSON-RPC and genesis lifecycle suites: passed.
4. Full module archive suites and checkstyle remain required before release qualification.
5. Full archive E2E startup/restart/fault matrix remains the release gate; this source review is not
   a substitute for a long-running from-zero sync.

## 8. Completion rule

Round 8 closes only when:

1. Every confirmed finding is implemented and tested.
2. The focused and module regressions pass.
3. A second adversarial pass finds no new actionable correctness, concurrency, recovery, resource,
   or unbounded-memory issue.
4. Remaining performance ideas are explicitly measurement-gated rather than presented as defects.
