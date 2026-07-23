# Archive round 28 workflow adversarial review results

## 1. Scope and decision rule

- Date: 2026-07-23
- Baseline: `a3d48341370b542057ca4eebf0d0475d27c9ce60`
- Persisted layout: `UNIFIED_V1` only
- Compatibility premise: no deployed archive database and no migration requirement

This round used three independent read-only adversarial lanes plus a local source-level
reconciliation:

1. journal, publication, disk admission, recovery, and native-resource lifecycle;
2. historical query accuracy, snapshot/canonical epoch races, VM replay, and error mapping;
3. runtime interference, startup scaling, boundedness, and unnecessary complexity.

A change was accepted only when the current call chain proved both the cost or failure and the
post-fix correctness boundary. In particular, this round did not trade a forced durability
boundary for a theoretical throughput gain without a matching crash matrix.

## 2. Confirmed findings and fixes

### R28-1 - stale healthy disk samples still blocked the block path

Severity: Medium, archive-enabled nodes only.

The sampler cached capacity for one second, while a normal block interval is about three seconds.
Consequently, every healthy block normally found the cache stale and synchronously waited for
`FileStore.getUsableSpace()`. Moving the probe to a worker had bounded a stalled JNI/filesystem
call, but the caller still waited for it. The old test appended two blocks inside one second and
therefore did not cover real block cadence.

Fix:

- a stale high-water sample starts one coalesced asynchronous probe and lets the journal path
  continue;
- a stale pressure-zone sample still waits for a fresh result before reserving another journal;
- a pending asynchronous probe has one global deadline; a stalled probe cannot authorize
  unbounded stale-high writes;
- asynchronous probe failures are harvested by the next writer admission and persist repair/fatal
  evidence;
- disk generation, sample time, and capacity are read as one service-side snapshot;
- conditional sampling reuses a concurrently completed generation instead of launching a second
  pressure probe.

The final forced-sync journal write remains authoritative. A stale high sample may admit bounded
work while a probe runs, but an actual ENOSPC/EIO journal failure still precedes canonical commit
and triggers fail-stop.

### R28-2 - compact ACK reread and hashed the complete journal on the block thread

Severity: Medium, archive-enabled nodes only.

After the forced-sync journal batch and canonical commit, ACK fetched the complete journal value
from RocksDB, allocated its Java representation, and recomputed SHA-256 before writing the compact
proof row. The same payload is already bound to a proof in the atomic forced-sync append, and it is
again checked during startup and by exact byte comparison in atomic publication before any
historical visibility.

Fix:

- ACK now validates only the retained proof token, persisted compact token row, and conflicting ACK
  state;
- it writes only the compact WAL-enabled ACK row;
- full payload validation remains mandatory during startup scan and publication;
- ACK resource admission now accounts for compact lifecycle rows instead of two complete payload
  copies.

This changes detection time, not the visible-state contract. A payload damaged after append can be
ACKed, but publication cannot expose it, startup cannot accept it, and either path marks the
archive unusable. Tests inject a rewrite between append and ACK and prove publication retains the
journal, publishes no range, and persists repair-required state.

### R28-3 - normal startup scanned every published range twice

Severity: Medium for mature archive startup time; no runtime block-path effect.

`UnifiedArchiveTxNumIndex` construction validated the complete contiguous range chain. After
canonical journal reconciliation, `UnifiedArchiveBackend.validateStartup(false, ...)` repeated the
same O(number of blocks) range walk before checking the temporal tail.

Fix:

- construction retains the complete range-chain validation;
- ordinary post-reconcile validation rechecks repair state, cursor, last range, last positions, and
  temporal tail only;
- full scrub and repair-required startup still perform complete index and temporal validation.

Reconcile can only mutate the tail through checked publication/unwind APIs. A ticker-backed
regression with 32 ranges proves ordinary post-reconcile validation no longer scales its iterator
`next` calls with chain length.

### R28-4 - historical storage failures lost their diagnostic identity

Severity: Low; no wrong result.

Two related mappings hid node faults:

- malformed canonical block protobuf was caught as a generic `StoreException`, converted to
  `null`, and returned as `invalid params: block header not found`;
- archive reader, snapshot, and VM failures were wrapped in `JsonRpcInternalException` without
  their cause.

Fix:

- only `ItemNotFoundException` maps a cacheless canonical lookup to `null`;
- malformed canonical blocks become archive internal errors with the original store exception;
- archive/VM JSON-RPC conversion retains the cause while preserving the client-facing error class
  and message.

## 3. Adversarially rejected or deferred candidates

### Async publication `sync=false`

The interference channel is real: asynchronous publication and the next forced-sync journal share
one RocksDB write queue and WAL, so a large publication fsync can raise block-push tail latency.
WAL-enabled, non-sync publication is theoretically recoverable from the previously forced-sync
journal.

It is deliberately not changed in this round. Production archive is currently arm64-only and uses
RocksDB 9.7.4, so that JNI/runtime and the deployment filesystem must first pass kill/power-loss
points across WAL rotation, multi-column-family flush, manifest install, and a publication batch
followed or not followed by another sync journal. Until that matrix proves that recovery always
yields either the complete publication or the intact prior journal, publication remains
`sync=true`. RocksDB 5.15 testing becomes relevant only if x86 archive support is introduced.

### Constructor cleanup after `Thread.start()` failure

A later worker start can theoretically fail after an earlier daemon worker started. The trigger is
process-level thread exhaustion or a runtime security failure during node startup; startup already
fails and the process is not serviceable. Adding injectable thread factories and a second
construction state machine solely for this terminal path would increase lifecycle complexity more
than it improves a recoverable production case. Normal construction, close, and factory failure
paths remain owned and tested.

### Other disproved candidates

- archive-off performs no archive DB read, fsync, worker admission, or archive lock;
- asynchronous disk requests remain single-flight and generation ordered;
- a stalled disk probe cannot authorize writes past its deadline;
- compact ACK cannot make corrupt payload visible;
- publication still atomically writes index, temporal rows, marker, cursor, and journal deletion;
- ordinary post-reconcile tail validation does not replace full scrub or repair startup;
- historical readers retain one Unified snapshot and settle against canonical/mutation epochs;
- no historical missing state falls back to live state;
- query, trace/VM, response, cache, and snapshot resource limits remain bounded.

## 4. Verification

Focused red/green coverage includes:

- healthy stale sample is nonblocking;
- pressure sample blocks for fresh capacity;
- async probe failure and stall become repair-required fail-stop;
- concurrent completed generation prevents a redundant pressure probe;
- compact ACK reads less than 4 KiB while the journal fixture exceeds 512 KiB;
- payload corruption after compact ACK is rejected before publication;
- normal post-reconcile startup does not walk every block range;
- corrupt canonical protobuf is an internal error, while a genuinely missing block remains absent;
- JSON-RPC archive errors retain their original cause.

Completed repository regression:

```text
./gradlew :common:test :chainbase:test :actuator:test :framework:test \
  -x generateGitProperties \
  --tests '*Archive*' --tests '*Historical*' --tests '*StorageConfigTest'

BUILD SUCCESSFUL in 2m 2s
```

The startup-validation boundary was also rechecked directly: ordinary post-reconcile validation
uses the bounded tail path, while generic startup validation and factory startup still reject a
missing middle published range.

Exact-artifact private-chain and restart evidence remains a follow-up release-gate run; this review
does not represent it as completed.

## 5. Remaining release gates

These remain validation gates rather than confirmed state-correctness defects:

1. representative from-zero synchronization on production storage;
2. multi-day block/query soak with p95/p99 block-push, journal, publication, compaction, RSS, and
   native-memory measurements;
3. the WAL/multi-CF crash matrix required before considering non-sync asynchronous publication;
4. multi-node fork, partition, and deep-reorg testing under historical query load;
5. real device latency, ENOSPC, EIO, permission, and power-loss testing on deployment hardware.
