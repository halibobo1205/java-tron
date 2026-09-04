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

### R28-5 - disk completion and backlog wakeup races remained after the first fix

Severity: Medium for the skipped completion; Low for bounded fatal-delivery latency.

The post-fix adversarial pass found three related timing windows:

- `latestCompletedSample()` and `requestSample()` used separate sampler monitor acquisitions. A
  low-space or failed generation completing between them could be skipped when the request started
  the next generation.
- disk-only soft pressure waited for the complete backpressure timeout because filesystem recovery
  does not notify the backlog monitor;
- fatal notification could land after lifecycle validation but before the admission thread entered
  `wait()`, delaying fail-stop by the remaining backlog timeout.

Fix:

- the sampler now atomically returns a completion newer than the service generation or coalesces
  one pending request; a failed or low completion cannot be overwritten by the next generation;
- disk soft pressure rechecks at the bounded one-second sampling interval while retaining the
  original absolute backpressure deadline;
- known fatal state is rejected before probing, and the final lifecycle check runs while holding
  the same backlog monitor used by fatal notification.

Healthy high-space admission remains nonblocking, pressure-zone admission still requires fresh
capacity, and all callers still share one sampler probe.

### R28-6 - corrupt canonical index rows escaped the historical error boundary

Severity: Low; failure classification only.

The cacheless canonical block-id path handled a genuinely missing index row but allowed unchecked
storage/runtime failures, including a malformed hash length, to escape directly. The same unchecked
boundary was possible while resolving a block body by number or id.

Fix:

- all three historical cacheless canonical entry points preserve `ItemNotFoundException` as
  absence;
- other checked and unchecked read failures become `ArchiveException` with their original cause;
- `HistoricalQueryLimitException` is explicitly rethrown before the runtime catch, preserving the
  typed resource-limit mapping instead of degrading `-32005` to `-32000`;
- tests cover block body, block-id-by-height, block-by-id, both canonical-check stages, missing
  values, and typed query-limit propagation.

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

### Ordinary startup versus arbitrary clean deletion

Ordinary startup proves the complete published range chain once, then validates the journal and
published tail. It does not claim to be a full corruption scrub of every historical position and
temporal row. An operator or unknown defect that deletes a mutually consistent set of middle rows
without the maintenance API's repair marker may therefore require `fullScrubOnStartup=true` to be
detected.

This is not reachable through the supported atomic publication, unwind, or maintenance APIs, and
normal kill/WAL recovery cannot create it without violating RocksDB batch atomicity. Making every
ordinary startup walk every historical row would undo the startup scaling fix. Full scrub remains
the explicit physical/logical corruption gate.

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
- a completed low/failed generation cannot be skipped by a newer request;
- disk-only soft-pressure recovery is rechecked before the full backpressure timeout;
- fatal notification cannot be lost between validation and backlog wait;
- compact ACK reads less than 4 KiB while the journal fixture exceeds 512 KiB;
- payload corruption after compact ACK is rejected before publication;
- normal post-reconcile startup does not walk every block range;
- corrupt canonical protobuf is an internal error, while a genuinely missing block remains absent;
- corrupt canonical index rows are internal errors while query-limit exceptions remain typed;
- JSON-RPC archive errors retain their original cause.

Completed repository regression:

```text
./gradlew :common:test :chainbase:test :actuator:test :framework:test \
  -x generateGitProperties \
  --tests '*Archive*' --tests '*Historical*' --tests '*StorageConfigTest'

BUILD SUCCESSFUL in 44s
32 actionable tasks: 1 executed, 31 up-to-date
```

The startup-validation boundary was also rechecked directly: ordinary post-reconcile validation
uses the bounded tail path, while generic startup validation and factory startup still reject a
missing middle published range.

Exact-artifact private-chain evidence:

```text
FullNode.jar SHA-256
9b32248b61912137a0dfca0ecb98e154b5b390ab38e9cfd8ecb6a2ea82f62dd2

evidence root
/private/tmp/java-tron-archive-round28-postfix-e2e-20260723

SCENARIO_OK transactions=20 oracles=15 finalHeight=48
ORACLE_REPLAY_OK count=15
```

The scenario covered account creation/update and transfers, FreezeV2 freeze/unfreeze, voting,
resource delegation/undelegation, TRC10 issue/transfer, contract deployment/call, three historical
storage values including deletion to zero, and `SELFDESTRUCT`. HTTP, JSON-RPC, and gRPC were all
exercised.

Immediately before normal shutdown, the node intentionally had two unsolidified archive blocks
with `repair_required=0` and `publisher_lag_blocks=0`. Restart on the same database replayed all 15
oracles and settled to:

```text
repair_required=0
inflight_blocks=0
publisher_lag_blocks=0
```

The stopped-node Unified DB probe then validated every range, tx position, transaction id,
changeset, commitment, marker, and typed domain:

```text
OFFLINE_PROBE_OK first=0 last=51 changesets=900 tombstones=7
HISTORY=900 CHANGESET=900 LATEST=277 COMMITMENT=277 INFLIGHT=0
```

A real block-boundary `SIGKILL` against the same exact jar observed one durable
`CANONICAL_COMMITTED` journal:

```text
publishedFirst=0 publishedLast=51 inFlight=1
JOURNAL block=52 state=CANONICAL_COMMITTED records=17 txPositions=2
```

Restart recovered without repair mode, replayed all 15 historical oracles, settled in-flight and
publisher lag to zero, and reproduced the same successful offline probe. This test used the normal
artifact and process kill, not a fault-hook jar.

## 5. Remaining release gates

These remain validation gates rather than confirmed state-correctness defects:

1. representative from-zero synchronization on production storage;
2. multi-day block/query soak with p95/p99 block-push, journal, publication, compaction, RSS, and
   native-memory measurements;
3. the WAL/multi-CF crash matrix required before considering non-sync asynchronous publication;
4. multi-node fork, partition, and deep-reorg testing under historical query load;
5. real device latency, ENOSPC, EIO, permission, and power-loss testing on deployment hardware.
