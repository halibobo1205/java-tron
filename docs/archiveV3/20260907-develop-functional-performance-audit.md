# Archive Functional and Performance Audit Against Develop

Date: 2026-09-07. Review only; no production changes or GitHub writes.

This document records the pre-fix audit. Subsequent implementation and verification
are tracked in `20260907-audit-remediation.md`; the original findings below are
retained as the audit baseline.

## Scope and Baseline

- Branch: `feat/archive-node-JDK25-fsync`.
- HEAD: `99b3969cec363750363eb4c5f6c914502c50c698`.
- Locally available `develop`: `57b7b04f385bc5da1a417b0af75aaca200f41336`.
- Merge base: `f87081b60a0141213c16ed4f0877cc3715bb1f83`.
- Diff: `git diff develop...HEAD`; 537 files, 160,666 insertions and 1,851 deletions,
  including tests, documents, and non-archive changes. No fetch/rebase was performed.
- The existing uncommitted `framework/build.gradle` fsmonitor workaround is excluded
  from the committed audit scope and left untouched. Verification excludes
  `generateGitProperties`.

This is an archive-focused functional/performance review of the branch delta,
not a claim to have independently reviewed every dependency upgrade or every line
of the repository. Standards and specification review were dispatched separately.
The standards helper returned a tool error and no review; the main reviewer
inspected the shared execution paths. The specification helper completed and its
finding was independently checked and reproduced by the main reviewer. Do not
describe this as two completed independent reviews of every path.

## Standards

Sources: `AGENTS.md`, `.codex/memory/CODEX_MEMORY.md`, `CONTRIBUTING.md`, and
`docs/adr/0001-forbid-java-lang-math-via-errorprone.md`.

No additional confirmed hard standards violation is reported from this pass.
The independent standards review is incomplete, so this is not a clean approval
from that reviewer. Existing Checkstyle debt is already documented in
`20260907-full-scrub-review.md`; no fresh lint/style-clean claim is made here.

The archive-off requirement must be stated precisely. Store capture gates avoid
archive previous-value reads and preserve the canonical payload path, but the
whole branch is not byte-for-byte or behaviorally identical to develop: it also
contains Manager fork-error handling, JDK/tooling/dependency changes, math changes,
and SolidityNode transaction-validation changes. These need their own base-diff
validation; they are not new archive defects merely because they differ.

## Spec

### F1 - P2: Historical Code Hash Can Remain Stale After CREATE

Status: **new, confirmed, independently reproduced**.

Location: `actuator/src/main/java/org/tron/core/vm/archive/ArchiveRepositoryAdapter.java:321`.
Requirement: `20260714-archive-from0-production-validation-runbook.md:134`,
historical `eth_call` conformance, "Any mismatch = blocker."

With Constantinople enabled, a constructor can evaluate `EXTCODEHASH(ADDRESS)`
before returning nonempty runtime code. `Program.getCodeHashAt()` caches the
empty-code hash in the contract capsule. Archive `saveCode()` replaces only the
code overlay, without updating the capsule hash. Child commit preserves the stale
hash. A later `EXTCODEHASH` returns it because lazy recomputation only handles an
absent/zero hash, not the nonzero Keccak hash of empty bytes.

Both current and pinned-develop `RepositoryImpl.saveCode()` explicitly recompute
and update that hash under the Constantinople gate. The archive adapter diverges.

Real VM reproduction:

- Constructor: `303f50600060005360016000f3`.
- Parent: `600d6017600039600d60006000f03f60005260206000f3`, followed by the constructor.
- The child returns runtime bytecode `00`; the parent returns its code hash.
- VM result: no exception, no revert.
- Actual: `c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470`.
- Expected: `bc36789e7a1e281436464229828f817d6612f7b477d66591ff96a9e064bcc98a`.

Impact: incorrect results in historical execution using this adapter, including
historical calls and traces taking this path. Success/failure comparison alone
cannot detect a successful execution returning the wrong value. This does not
demonstrate corrupt canonical writes or corrupt persisted archive rows.

Recommendation: match canonical `saveCode()` semantics, retaining proposal gating,
overlay accounting, copy isolation, and child-commit behavior. Add direct adapter
and real CREATE/CREATE2 differential tests plus RPC-level historical call/trace
coverage. The current proof includes real archive VM execution and a direct
canonical repository save/hash comparison, not a full canonical-chain/RPC E2E.

## Main Review: Recovery and Performance

### O1 - P1: Full Scrub Remains Operationally Too Expensive at Scale

Status: **previously known, still open**, not a new finding or new benchmark.

Locations:
`chainbase/src/main/java/org/tron/core/archive/txnum/UnifiedArchiveTxNumIndex.java:631`;
`chainbase/src/main/java/org/tron/core/archive/temporal/UnifiedArchiveTemporalStore.java:1080`.

The INDEX position branch uses an iterator value, then `getPosition()` rereads
the same position and checks other indexes. Temporal block validation also Gets
the current CHANGESET locator despite already having its iterator positioned,
then resolves history and payload references. Full validation retains substantial
random lookup and decompression work across the historical dataset.

The prior local 1,160,000-empty-block fixture slowed to about 112 position rows/s
in a measured interval and was stopped without completing full scrub. Its native
sample was dominated by SST index-block decompression. See
`20260907-full-scrub-review.md` for the exact counters and limitations. Supplied
mainnet logs also show slow later stages, but neither source establishes a
completed mainnet restart duration. Estimates of hours/days are not measurements.

This is a restart-availability blocker for the demonstrated scale, not evidence
that validation returns wrong state. Reuse decoded current rows first, then
evaluate bounded sequential cross-reference validation within one snapshot.
Preserve missing/extra/orphan checks, physical predecessor checks, txId/VM pairing,
payload authentication, and block digests. Do not clear repair evidence or replace
full validation with a last-N-block check to make startup look fast.

### O2 - P2: Resource Refusal Unconditionally Requires Repair Scrub

Status: **previously known, still open**.

Locations: `DefaultArchiveService.java:717`, `:3636`, `:3668`;
`ArchiveServiceFactory.java:203` under `chainbase/src/main/java/org/tron/core/archive/`.

A backpressure/admission failure takes the same durable repair-marker path as
possible corruption. Factory startup sees that marker and forces full scrub even
when `fullScrubOnStartup=false`. An overload can therefore turn into a prolonged
restart outage. Backpressure fail-stop itself is an intentional integrity policy;
the avoidable cost is conflating proven resource refusal with unknown corruption.

Classification must default to repair, and repair necessity must only escalate
across concurrent failures. Today only the first fatal persists its reason; later
failures are suppressed/logged. An old marker saying "backpressure" is therefore
not proof that no later persistent failure occurred. Never infer safety by parsing
that old string. Cover refusal-then-corruption, marker-write failure, active
publisher, recovery clear, shutdown, and watchdog races before changing policy.

### O3 - P2: Normal Startup Bypasses recoveryTimeoutMs

Status: **new, confirmed with a control experiment**.

Locations: `DefaultArchiveService.java:1076`, `:1186`, `:780`;
`framework/src/main/java/org/tron/core/db/Manager.java:514`, `:560`.

Normal Manager initialization runs `initInternal()` and startup reconciliation
before `completeRecovery()`. Reconciliation invokes `validateStartupStorageLocked()`
directly. The watchdog wrapper exists only on the later `completeRecovery()` path;
after successful reconciliation the `startupStorageValidated` flag makes that
wrapped call a no-op. Thus the actual full-scrub callback is not protected by the
configured recovery timeout. Factory range/journal loading also precedes this wrapper.

Paired in-memory test, timeout 30 ms and blocked validator for 300 ms:

| Entry sequence | Fatal during blocked validation | Result after release |
| --- | --- | --- |
| `completeRecovery()` directly | true | timeout exception |
| `reconcileInFlightOnStartup()` then `completeRecovery()` | false | success |

The existing `stalledRecoveryValidationFailsStopOutsideLifecycleCommitLock` test
only exercises the first sequence. This finding explains why that passing test
does not establish a bounded production startup. It is not an assertion that
RocksDB JNI can be safely interrupted.

Recommendation: protect the actual expensive validation entry point once, with
proper lifecycle/fatal ownership, and define whether the budget also covers factory
opening/range/journal loading. Avoid nesting the single-operation journal watchdog
around callbacks that arm it again. Add a Manager-order regression and preserve
repair evidence when timeout wins. Merely increasing the configured timeout does
not fix the missing coverage.

## Optimization Opportunities, Not Additional Confirmed Bugs

1. **Publisher read amplification.** The existing one-key temporal extension
   regression measures 17 RocksDB keys read (`UnifiedArchiveBackendTest:1058`).
   Preflight/preparation reads anchors, latest, history tail and payloads with
   integrity checks. Profile hot dynamic/account keys after SST flush and across
   multiple SSTs before choosing snapshot-local reuse or cache-policy changes.
   This count is fixture-specific, not 17 reads for every record or block.
2. **Canonical write latency.** Async publication removes its large Java critical
   section from ordinary block execution, but each journal append still forces WAL
   sync on the canonical thread. Publication also syncs, and uses the same archive
   RocksDB/WAL resource. CPU, GC, filesystem bandwidth and native write stalls
   remain shared with the process. Do not promise zero effect on block throughput.
   Measure journal, ACK, publish, capture and canonical apply separately; preserve
   journal-before-canonical durability rather than simply disabling fsync.
3. **Historical concurrency.** Finite worker/snapshot/read/overlay budgets and
   epoch validation are present. They limit exposure, not physical CPU/I/O
   contention. Compare archive-off, archive-on/no-query, and archive-on/saturated
   historical calls on identical replay data; collect block p50/p95/p99, backlog,
   CPU/allocation, and I/O before raising query concurrency or cache budgets.
4. **Flush/backpressure coupling needs a targeted test.** Publication is capped by
   `head - revokingSize - pendingFlushCount`. If nonpublishable tail alone reaches
   a soft watermark, awaiting publisher progress before the next canonical session
   can prevent the very flush/finality advancement needed to drain it. Configuration
   accepts independently chosen watermarks and `maxFlushCount` up to 500. This
   boundary was inspected but not reproduced with a real batched-flush node in
   this round; it is not claimed as the cause of the supplied mainnet incident.

There is no new evidence justifying a global/shared canonical cache, weaker
integrity validation, unbounded readers, or another on-disk compatibility layer.

## Verification and Limits

JDK 17 targeted regression, completed in 2m 28s:

| Module | Suites | Tests | Failures/errors/skips |
| --- | ---: | ---: | --- |
| chainbase | 53 | 904 | 0 / 0 / 0 |
| actuator | 11 | 81 | 0 / 0 / 0 |
| framework | 22 | 224 | 0 / 0 / 0 |

JDK 25 equivalent run, completed in 2m 8s: chainbase 904 and actuator 81 passed
without failures. Framework recorded 226 test executions, including **two failed
attempts of the same test**, followed by a passing retry. Gradle reported
`BUILD SUCCESSFUL` because the configured retry policy permits recovery; this is
**not a zero-failure run**. There are 224 distinct framework test cases.

### Q1 - P3: Batch Budget Test Has an Insufficient Concurrency Barrier

Status: **new test-stability finding, observed twice on JDK 25**.

Location: `framework/src/test/java/org/tron/core/services/jsonrpc/JsonRpcServletTest.java:551`.
`concurrentBatchConstructionUsesGlobalResponseByteBudget` waits for two network
writes, assumes the response budget is occupied, then requires the third response
to be a JSON limit error. But both earlier responses can be overflow errors:
batch construction temporarily reserves both subresponse and destination bytes,
and overflow discards/releases its reservations before writing the error.
`BlockingResponse` counts error writes as well as successful writes. Two network
writes therefore do not prove the required retained-budget state.

The mock emits 200 zero bytes, not JSON. If earlier overflow freed enough capacity,
the third request can legitimately reach that mock and return those bytes; parsing
then fails with `CTRL-CHAR, code 0`. The two failed attempts show this symptom, and
the next retry passed. This is not evidence that production serialization created
zero bytes or that archive state/query caches were corrupted. No claim that this
is specific to JDK 25 is made; different scheduling can expose it on other runtimes.

Recommendation: use valid JSON from the mock, and synchronize on two retained
subresponses before batch-copy/overflow can release their reservations. Assert
the actual budget/response outcome rather than using network-write entry as its
proxy. A one-method JDK 25 follow-up with retries disabled failed again with the
same parse exception (1 test, 1 failure, build failed in 22s). Its log is
`/private/tmp/archive-servlet-budget-audit.log`; the temporary retry override is
`/private/tmp/archive-servlet-budget-audit.gradle`. Production and test sources
remain unchanged.

Command used for both runtimes (per-command `JAVA_HOME`):

```sh
./gradlew :chainbase:test :actuator:test :framework:test -x generateGitProperties \
  --tests 'org.tron.core.archive.*' \
  --tests 'org.tron.core.vm.archive.*' \
  --tests 'org.tron.core.db.Manager*Archive*Test' \
  --tests 'org.tron.core.config.DefaultConfigArchiveServiceTest' \
  --tests 'org.tron.core.services.jsonrpc.*Archive*Test' \
  --tests 'org.tron.core.services.jsonrpc.StructLogReconstructorTest' \
  --tests 'org.tron.core.services.jsonrpc.JsonRpcServletTest' \
  --tests 'org.tron.core.jsonrpc.JsonrpcServiceTest' --max-workers=4
```

Additional JDK 17 proof programs used current repository/VM classes, temporary
sources and in-memory repositories, without reading any live node database:

- `/private/tmp/ArchiveStartupTimeoutProbe.java` and
  `/private/tmp/archive-develop-audit-probe.log` reproduce O3 with its positive control.
- `/private/tmp/ArchiveCodeHashProof.java` and independently rerun
  `/private/tmp/archive-codehash-proof-recheck.log` reproduce F1.
- `/private/tmp/archive-develop-audit-probe.gradle` compiles/runs the temporary proofs.
- Suite logs: `/private/tmp/archive-develop-audit-tests.log` and
  `/private/tmp/archive-develop-audit-tests-jdk25.log`.
- The JDK 25 servlet XML including both failed attempts is preserved at
  `/private/tmp/archive-develop-audit-jdk25-servlet.xml` before the isolated rerun.

No new 27-SR run, OS/power-loss/disk-failure matrix, full mainnet scrub, long-running
sync, or archive-on/off throughput benchmark was completed in this round. Existing
tests cover many such logical failure paths, but are not substitutes for those
measurements. The new counterexamples also show that passing the existing suite
does not prove historical correctness or bounded startup.

No new canonical-state corruption or cross-query cache contamination was confirmed
in the inspected paths. That is a bounded review result, not a guarantee of absence.

Summary: Standards has no additional confirmed finding but incomplete independent
coverage; Spec has one new confirmed finding (F1). Main review adds one new confirmed
startup defect (O3) and retains two known operational findings (O1/O2). Verification
also found one flaky test (Q1). Resolve the silent historical hash error and the
demonstrated restart blockers before treating this branch as fully validated for
production archive service.
