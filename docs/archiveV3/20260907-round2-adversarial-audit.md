# Archive Adversarial Review: Round 2

Date: 2026-09-07.
Branch: `feat/archive-node-JDK25-fsync`.
Starting HEAD: `39020a16fee7b85063c5bd0fc5b3398abdadce5d`.
Comparison: locally available `develop`,
`57b7b04f385bc5da1a417b0af75aaca200f41336`; `git diff develop...HEAD`.
This is a working report, not a release approval. The user subsequently requested
commit and push of the current repairs; no GitHub comment or issue publication is
authorized. The unrelated local `framework/build.gradle` fsmonitor workaround is
excluded and preserved.

## Requirements and Evidence

| Requirement | Evidence required | Current round |
| --- | --- | --- |
| Archive does not change canonical state execution | Capture gates, exception isolation, canonical write-path comparisons and regressions | Independent static Standards pass and selected JDK 17 regressions completed |
| Historical state and execution are accurate | Independent expected values and canonical/archival execution comparisons, including fork boundaries | Two execution defects reproduced and patched; JDK 17 regression green |
| Reorg, restart and crash remain correct | Journal/canonical ordering, publishability, epoch invalidation, durable-engine fault tests | Main static pass and batch-flush integration; fresh broader fault runs pending |
| Concurrent historical requests cannot leak state | Request-local overlays, snapshot/epoch checks, bounded resource accounting | Spec pass and focused isolation/budget regressions completed; broader fault coverage remains pending |
| No substantive P0/P1 performance or availability defect remains | Reproductions, remediations, subsequent independent challenge and relevant regressions | Soft-watermark progress defect reproduced and patched; goal remains open |

## Standards

Independent reviewer: no confirmed hard violation or judgment finding in the
assigned shared store/capture integration. Inspected generic and specialized
stores, account asset optimization and deletion, canonical codecs, capture
holder/context/engine, physical storage hooks and canonical actuator integration.
This was a static review, not a throughput or fault-test result. It did not cover
the publisher or historical VM implementations assigned to other reviewers.

## Spec

Independent reviewer identified two execution candidates against the runbook's
fork-boundary conformance requirement ("Any mismatch = blocker"):

1. Pre-energy-limit-fork canonical child repositories share a parent's cached
   `Storage`, including mutations from a reverted child. The historical adapter
   always isolates child storage. A parent SLOAD followed by DELEGATECALL to a
   reverting SSTORE may therefore produce different successful return data.
2. Canonical account creation initializes active permissions when multi-sign is
   enabled; the historical adapter creates only a minimal proto. A later call
   to the multi-sign precompile can observe the omitted permission.

Both candidates are now confirmed (R2 and R3).
The preceding CREATE code-hash repair was independently checked and was not
reported again.

## R1: Soft Watermark Can Block Its Own Progress

Status: confirmed and patched; independent patch challenge found no new P0/P1.
Priority: P2, configuration/workload-dependent archive-node availability.

`Manager.pushBlock` calls `awaitWriterCapacity` before `SnapshotManager.buildSession`.
The latter advances pending flushes and makes batched canonical checkpoints
durable. Archive publication is correctly capped at
`head - revokingSize - pendingFlushCount`, also bounded by solidification.

Previously every retained journal contributed to a soft-watermark wait, including
a tail newer than that publishable prefix. Once only that tail remained, the
publisher had nothing to drain. The writer waited for the next flush/finality
advance that it was itself prevented from executing, and eventually failed stop.

The fix consults the publisher's existing bounded target before waiting for a
non-disk soft limit. It does not lower hard limits, bypass disk pressure, publish
unrecoverable blocks, force a canonical flush, or change the WAL/fsync contract.
Capacity waits also recheck at most one second apart: an obsolete target can be
discarded without removing a journal and therefore without a backlog notification.

Evidence:

- Two new unit regressions failed against the starting production code with
  `ResourceAdmissionFailure`: no publishable target, and a fully drained eligible
  prefix with a retained tail still at the soft limit.
- After the patch, the asynchronous publisher, service and publisher-unit suites
  passed 167 tests with no failures/errors/skips. Existing hard-limit, disk,
  timeout, failure and shutdown checks remained in the selection. A stale-target
  cancellation test was added; tests that genuinely expect backpressure now
  install and block an eligible publication target.
- The full chainbase task subsequently passed 915 tests in 53 suites, with zero
  failures/errors/skips, on both Temurin 17.0.19 and 25.0.4. This includes the already-waiting
  capacity transition from an eligible prefix to a retained unpublishable tail.
- Repository `lint`, `checkstyleMain` and `checkstyleTest` passed. Their coverage
  is the repository's configured coverage, not an all-source style-clean claim.
- `ArchiveBatchFlushIntegrationTest` passed using real canonical stores and
  `SnapshotManager`, checkpoint V2 with sync enabled, maxSize 2, maxFlushCount 3,
  soft block limit 3 and hard limit 16. At block 5, two flushes remain pending,
  root balance stays 100 and no archive block is published. At blocks 6 and 9,
  canonical root and published archive advance to blocks 3 and 6 respectively;
  every published historical account balance matches its independent block-number
  expectation, while live balance remains at the current head.
- A second control removed only the new admission guard and reran the real
  batch-flush test with a nonzero one-second timeout: it failed before block 4,
  with three journals and twelve records retained, through the same
  `ResourceAdmissionFailure`. The guard was restored immediately afterward.
- The integration uses in-memory archive stores and reproduces Manager's session,
  journal and acknowledgement order, calling its actual publication-cap method.
  It is not a full `pushBlock`/consensus, persistent-archive or 27-SR crash test.

Local logs: `/private/tmp/archive-round2-backpressure-red.log`,
`/private/tmp/archive-round2-backpressure-green.log`,
`/private/tmp/archive-round2-batch-flush.log`,
`/private/tmp/archive-round2-batch-flush-red.log`,
`/private/tmp/archive-round2-chainbase.log`, `/private/tmp/archive-round2-style.log`.
JDK 25 log: `/private/tmp/archive-round2-chainbase-jdk25.log`.

The private-chain harness accepts `HS_CFG_SOFT_IN_FLIGHT_BLOCKS` (default 32768,
range 1..65536). Setting it to 8 in the existing 27-SR catch-up/batched-flush kill
scenario will exercise soft pressure below the reversible tail size. Syntax
checks passed; the actual fresh private-chain run is still pending.

## R2: Pre-Fork Reverted Child Storage Diverges

Status: confirmed P1 historical-execution correctness defect; repaired, with
focused regression passing and independent post-fix challenge completed.

`ArchiveStorageForkConformanceTest` executes actual TVM instructions against
`RepositoryImpl` and `ArchiveRepositoryAdapter`, using independent fake store
rows/reader state with slot 0 initially equal to 7. The parent reads that slot,
DELEGATECALLs a child executing `SSTORE(0, 1)` followed by REVERT, ignores the
failed child and returns its own SLOAD(0).

Before the energy-limit fork, canonical execution returns 1 through the shared
cached Storage object; archive returns 7 because its child overlay is discarded.
Both outer executions succeed, with the internal transaction rejected. After
the fork, both return 7. The test also checks that neither execution changes the
source row or a separate request's view, and exercises finite VM overlay budgets.
Historical execution must preserve the actual historical rule, not substitute
later, cleaner rollback semantics.

Red run: 2 tests, 1 failure (pre-fork), 1 passing control (post-fork).
Log: `/private/tmp/archive-round2-storage-fork-red.log`.

The adapter now caches request-local per-address storage views. Ancestor lookup
does not warm uncached ancestors. Before the fork, a child shares an existing
ancestor view; after the fork, it copies that view. Committing replaces the
parent's cached view as canonical `RepositoryImpl` does, instead of merging slots
into a potentially newer view. The fork decision uses the executor's local
`VMConfig` snapshot, not the live legacy fork getter. View references, copies and
writes are charged before they can modify shared request-local state.

Nine focused tests passed on Temurin 17.0.19, including real VM warm/cold parent,
committed sibling, nested revert cases on both sides of the fork, cached sibling
replacement and budget-failure isolation. No canonical production class changed.
Green log: `/private/tmp/archive-round2-storage-fork-green.log`.

The independent follow-up found no new P0/P1/P2 counterexample in the storage
patch. It verified that DELEGATECALL/REVERT are in the base opcode table and
contract activation does not depend on ENERGY_LIMIT; this is a reachable fork
combination. It traced historical ConfigLoader snapshot construction, cold-parent
cache topology, commit replacement, query-local lifetime and budget reservations.
`HistoricalStorageForkBoundaryTest` additionally exercises the actual
historical-properties/config-loader entry for BLOCK_END and TX_BEFORE, on both
sides of the energy-limit fork and with opposite live globals. It checks real VM
return data, reverted child execution, source isolation and configuration restore.
Its reader is mocked; it is not a persistent-reader or full-RPC integration test.

## R3: Newly Created Historical Accounts Lack Active Permission

Status: confirmed P2 historical-execution correctness defect; repaired, JDK 17
regression green.

`ArchiveAccountPermissionConformanceTest` runs a real value CALL to a previously
absent address, then invokes the multi-sign precompile with an actual valid
signature and permission ID 2. The canonical repository creates default owner
and active permissions when multi-sign is enabled; the archive adapter's minimal
account does not. Both outer executions succeed, but canonical returns 1 while
archive returns 0. A separate account-proto comparison detects omitted defaults,
including creation time. Owner-permission and multi-sign-disabled controls pass.

The interim full actuator task ran 98 tests: exactly these two new permission
regressions failed, with 96 passing and no errors/skips. The selected framework
task completed 225 tests in 23 suites, all passing without retries. This is not
a fully green build: the command correctly exited nonzero on the actuator tests.
Log: `/private/tmp/archive-round2-vm-framework-interim.log`.

`createNormalAccount` now derives the creation time and multi-sign setting from
historical VM properties, and default active operations from the archived
`ACTIVE_DEFAULT_OPERATIONS` value. It constructs the same owner/active permission
defaults as the canonical repository and charges the resulting request-local
account to the overlay budget. Missing or malformed active operations fail closed;
there is no live-store fallback. The conformance fixture distinguishes default
active operations from available contract types and the previous header's time
from the executing block's timestamp.

## Pre-Commit Verification

The final repair patch passed the selected JDK 17 regression and repository
`lint`, `checkstyleMain` and `checkstyleTest` tasks without retries:

- Chainbase: 53 suites, 915 tests, zero failures/errors/skips. This invocation
  restored the unchanged chainbase task's successful result from Gradle cache;
  its prior actual executions on JDK 17 and 25 are recorded under R1.
- Actuator: 14 suites, 98 tests, zero failures/errors/skips, including all four
  permission conformance tests and nine storage-fork conformance tests.
- Framework: 24 suites, 229 tests, zero failures/errors/skips, including the real
  batch-flush integration and four historical-properties/config-loader fork
  boundary cases. The task uses the archive/VM/Manager/RPC selection, not the
  full framework suite.
- Harness shell syntax and `git diff --check` passed. No new private-chain run
  was performed during this commit preparation.

Log: `/private/tmp/archive-round2-precommit-jdk17.log`.

Temurin 25.0.4 also passed the four new regression classes: 13 actuator and five
framework tests, zero failures/errors/skips. These cover storage/permission
conformance, historical fork loading and real canonical batch flushing.
Log: `/private/tmp/archive-round2-precommit-jdk25.log`.

## Completion Status

The current repairs are being prepared for the user's requested commit and push.
Broader audit completion is not claimed: the new private-chain low-watermark
batch-flush/kill run and remaining fault coverage are still pending. Passing the
focused checks above does not establish absence of all functional or performance
defects.
