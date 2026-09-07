# Archive Adversarial Review: Round 3

Date: 2026-09-07.
Branch: `feat/archive-node-JDK25-fsync`.
Starting HEAD: `3f90948fff`.
Comparison: locally available `develop`,
`57b7b04f385bc5da1a417b0af75aaca200f41336`; `git diff develop...HEAD`.

The previous round reproduced and repaired a P1 historical storage-fork
divergence, a P2 account-permission divergence and a P2 unpublishable-tail
backpressure stall. Those repairs and their regressions are committed. This
round challenges the resulting implementation; it does not assume that a green
unit-test selection proves all archive behavior correct.

The unrelated `framework/build.gradle` fsmonitor workaround remains untouched.
The user subsequently requested a local commit and push to `origin`. GitHub
issues, comments and other public review writes remain prohibited.

## Requirements and Evidence

| Requirement | Evidence needed | Current status |
| --- | --- | --- |
| No change to canonical/archive-off state execution | Capture gates, failure isolation and canonical ordering review | Independent Standards review completed without a new P0/P1 |
| Accurate historical state and execution | Canonical execution comparisons and independent live/transaction oracles | Prior round regressions green; independent Spec review completed without a new finding |
| Correct fork, restart and crash behavior | Actual process faults with durable canonical/archive state comparisons | Batch-flush/kill and broader matrix passed their stated contracts; genesis window deliberately fails closed |
| No dirty values or cache poisoning under concurrent queries | Snapshot/epoch/thread-local review and concurrent fault matrix | Independent review and fresh runtime matrix completed; no wrong values or unsettled query counters observed |
| No substantive P0/P1 functional or performance findings remain | Completed independent challenges, validated remediations and relevant regressions | No new confirmed P0/P1 remains in this review; coverage limits are explicit below |

## Standards

No new confirmed substantive P0/P1 invariant or performance finding.

Hard-rule finding, P3: `ConfigLoader.isForkActive` uses deprecated `Maths.ceil`,
contrary to `CODEX_MEMORY.md`'s rule against new `Maths` usage. The calculation
mirrors canonical `ForkController`; no numerical divergence is demonstrated.
Independent follow-up confirmed both current wrapper branches delegate to
`StrictMath.ceil`, including ARM/x86. A replacement is numerically equivalent
but removes a repeated property read; it is not an operational identity claim.
This non-blocking style cleanup is deferred from the test-tooling commit.

Inspected generic/specialized capture gates, canonical VM configuration,
Manager commit/genesis/switchFork/eraseBlock, SnapshotManager publication caps,
journal/ACK/rollback and fatal-drain handling, unified atomic publication,
index/temporal validation, query snapshot ownership and response epochs,
transport settlement, factory identity/path/sealing and both architecture-specific
`DbArchive` tools. The latter are unchanged upstream manifest tooling, not new
Unified archive maintenance endpoints.

Challenges to payload/proof revalidation, stale-epoch responses and full-scrub
iterator reuse did not establish new failures. No runtime tests or measurements
were performed by this reviewer. Factory open/range/journal loading remains
outside the recovery-callback watchdog, as previously documented.

## Spec

No new reportable finding at `3f90948fff`. The governing runbook criterion is
"Any mismatch = blocker."

Independently challenged cold/warm ancestor storage, reverted children,
pre/post-fork sharing, slot aliasing and contract-creation namespaces. Compared
remaining adapter methods with canonical RepositoryImpl, including permissions,
token overlays, resource calculations, delegation/rewards and SELFDESTRUCT.
No reachable counterexample to either recent VM repair was established.

Also inspected historical defaults, fork/config isolation, transaction pre-VM
positioning and same-block boundaries, trace outcomes and request-owned collectors,
snapshot ownership, response epoch validation, thread-local restoration, sticky
terminal failures, read/overlay budgets and isolated precompile execution. No
demonstrated cross-request state leak or live-state fallback was found.

This was a static pass. Repeated protobuf decoding and sustained concurrent-query
allocation costs remain unmeasured, not established defects. Current authority
overrides earlier legacy/read-through design notes. Commitment/proof APIs are
explicitly rejected by the current production factory and are not claimed as
shipped functionality.

## Runtime Artifact

Built the current source with Temurin 25.0.4 using
`:framework:buildFullNodeJar -x generateGitProperties`.
Build log: `/private/tmp/archive-round3-fullnode-build.log`.

The harness uses `framework/build/libs/FullNode.jar`, SHA-256:
`7059e01b9b1a8679792e39e29986957fbabe4364ab7e5f58eccd531efac61cb0`.
The repository-root `build/libs/FullNode.jar` is a different, older artifact and
was not used for this run.

## Catch-Up, Batched Flush and SIGKILL

Command environment: Temurin 25.0.4, `HS_SKIP_BUILD=1`, `HS_KEEP_WORKDIR=1`,
`HS_CFG_SOFT_IN_FLIGHT_BLOCKS=8`.
Scenario: `docs/archiveV3/harness/scenario-catchup-batch-flush-kill.sh`.
Log: `/private/tmp/archive-round3-catchup-kill.log`.
Artifacts:
`/private/tmp/archive-round3-privatechain/catchup-batch-flush-kill-20260907-120858-14164`.

The fresh private chain has 27 genesis witnesses. An archive-off source owns
26 SR keys; the archive-enabled catch-up node owns one, uses
`maxFlushCount=5` and a soft in-flight block watermark of 8. All peers are local.
The scenario records live balances around two actual transfers before killing
the catch-up node at the semantic checkpoint-before-refresh breakpoint. Runtime
batch variables, recovered hashes, historical balances and offline archive
structure must all be checked before a passing verdict is recorded.

First verdict: INCONCLUSIVE, exit 2. At source height 50/solidified 32, the harness
could not resolve its checkpoint breakpoint and cleaned up without starting or
killing the target. This is not a product verdict.

JDK 25 `javap -p -l` explicitly warns that it will not print line/local-variable
tables without `-c`. The jar contains line metadata; the resolver's invocation
was wrong. It now invokes `javap -p -c -l`, preserving all source-uniqueness,
line-offset, exact-owner and runtime breakpoint guards.

Four real compiled-fixture tests cover a normal method, a lambda, missing debug
metadata and ambiguous statements. On JDK 25 before the fix, both valid-anchor
cases failed and both rejection controls passed. After the fix, all four pass
on JDK 17 and JDK 25. The existing production-source anchor self-test also passes
on JDK 25, including all six registered descriptors. The actual catch-up anchor
resolves to `SnapshotManager.flush:370`, zero source/jar offset.

Independent review also identified a pre-existing limit of these guards: source
statements can be reordered without changing method/line-table membership, so
a stale jar could resolve a different operation at the accepted line. This was
statically derived, not reproduced in this round. The README now explicitly
requires rebuilding after semantic source edits. This round uses a freshly built,
hashed jar with unchanged production sources; adding `-c` does not relax the
existing guards or claim complete semantic-equivalence detection.

A fresh run with the same jar and scenario parameters completed successfully:
`/private/tmp/archive-round3-catchup-kill-retest.log` and
`/private/tmp/archive-round3-privatechain/catchup-batch-flush-kill-20260907-121403-18883`.

Result: `CATCHUP_BATCH_FLUSH_KILL_OK checks=33`, exit 0. JDB observed
`flushCount=5`, `maxFlushCount=5` at `SnapshotManager.flush:370` before SIGKILL.
The archive tail was 24 while source head was 55. The target restarted READY in
approximately four seconds, then reconnected after the peer's normal recent-
disconnect cooldown. Hashes at height 55 and, after continued production, height
82 matched the source. All six independently recorded balances at heights 8, 9
and 29 matched. After clean target shutdown, the offline probe found 63 contiguous
ranges (0..62), no structural violations/block gaps/txNum gaps/stale published
journals and no repair marker. This is a small private-chain result, not a mainnet
restart time or throughput bound.

## Broader Private-Chain Matrix

The same built jar was copied to
`/private/tmp/archive-round3-FullNode-3f90948fff.jar` so subsequent builds cannot
replace the artifact under a running test. Six scenarios ran serially:
smoke, history-accuracy, fork-reorg, kill-matrix, resource-faults and
concurrency-under-fault. `HS_CFG_WITNESS_COUNT=27`; scenarios with their own fork
topology retain that topology. No hung-acceptance or no-transaction shortcut
was supplied. Timeout: 3600 seconds per scenario. The kill matrix's default
contract accepts either correct recovery or an explicit fail-stop, not recovery
alone.

Driver log: `/private/tmp/archive-round3-full-suite.log`.
Artifacts: `/private/tmp/archive-round3-full-suite`.
Driver verdict: `PRIVATE_CHAIN_FAULT_SUITE_OK scenarios=6 passed=6 failed=0
inconclusive=0 skipped=0 elapsed=2118s`, exit 0. Individual INFO and inconclusive
injection checks were reviewed separately and are retained below; the driver's
zero inconclusive count applies to scenario-level verdicts only.

Scenario results:

| Scenario | Result | Detail |
| --- | --- | --- |
| smoke | PASS, exit 0 | `SMOKE_E2E_OK checks=23`, five historical oracles repeated after restart |
| history-accuracy | PASS, exit 0 | `HISTORY_ACCURACY_OK checks=61 pass=57 fail=0 info=4`, 1306 live samples, 17 transactions, withdrawal proof present |
| fork-reorg | PASS, exit 0 | `FORK_E2E_OK checks=19 passed=18 depth=6 switches=1`, one INFO records the normal SIGTERM exit |
| kill-matrix | PASS, exit 0 | `KILL_MATRIX_OK checks=6 windows=6`: w1..w5 RECOVERED; w6 explicit FAILSTOP |
| resource-faults | PASS, exit 0 | `FAULT_E2E_OK checks=12 passed=9 cases=2`, three INFO entries retained below |
| concurrency-under-fault | PASS, exit 0 | `CONCURRENCY_E2E_OK phases=5 passed=5 skipped=0`, 24 workers across baseline, stop, kill and reorg |

History-accuracy replayed public state through height 80, including transfers and
fees, code/storage/eth_call, SELFDESTRUCT and a non-genesis SR's reward withdrawal.
Four INFO checks remain explicit: archived allowance values and reward-related
dynamic properties have no direct historical RPC projection. Live allowance
samples are not counted as proof of those archived rows. The withdrawn balance
effect is independently checked using the canonical receipt. The final offline
probe checks system phase positions and committed index structure.

The fork topology uses two witnesses, verified in the generated configs. After
the induced partition healed, six erased blocks each appeared exactly once;
five re-captured historical heights agreed with the independent peer and none
served the orphan-only transfer. The offline index had 25 contiguous ranges,
no stale published journals and no repair marker. This run did not enable the
optional additional post-reorg restart; restart coverage comes from the other
scenarios.

Kill windows w2..w6 were reached by validated JDB breakpoints; w1 kills during
steady publication. The first five preserve the recorded historical values and
pass quiesced structural probes with no repair marker. At the genesis
commitToRoot/COMMITTED-marker window, w6 refuses startup with
`ARCHIVE_RUNTIME(1)` and a repair marker. This is fail-closed behavior, not
automatic recovery or proof that every interruption needs no operator action.
The w5 breakpoint is the Java atomic-publication call boundary; it does not
prove interruption inside a particular RocksDB native instruction or physical
power-loss durability.

Resource faults selected the default space-exhaustion and permission cases.
Filling a separate 256 MiB test image to 3,555,328 free bytes triggered the hard
free-space admission guard and explicit `ARCHIVE_RUNTIME(1)` exit. Returning
space allowed restart; the offline probe found no gaps or repair marker.
This exercises disk-pressure admission, not a forced native write errno.

After chmod, the running node kept producing for the 240-second observation
period because existing descriptors remained writable. That mid-run write-failure
injection is **INCONCLUSIVE**, even though the scenario-level result is PASS.
Restart while the directory remained read-only explicitly failed archive startup;
after restoring permissions it recovered with 82 contiguous ranges and no repair
marker. The other two INFO entries state that no repair marker was present.
The opt-in MANIFEST truncation case was not selected; no truncation/device-loss
coverage is claimed for this run.

Concurrency results:

| Phase | Requests | Result responses | Allowed RPC errors | Transport failures |
| --- | ---: | ---: | ---: | ---: |
| baseline | 13015 | 5602 | 7413 | 0 |
| clean-stop | 78349 | 3399 | 4082 | 70868 |
| SIGKILL | 64817 | 2533 | 4440 | 57844 |
| reorg | 12049 | 5215 | 6834 | 0 |

Every phase had zero wrong values, unexpected successful results, malformed
responses and request timeouts. Transport failures are expected while a node
is stopped and are not counted as successful queries. All 21 fixed responses
matched after both restarts; eight pre-fork responses matched after a seven-block
reorg. Active snapshots/queries/pending queries settled to zero and repair-required
remained zero. This tests concurrent response stability, not production throughput.
The independent numerical accuracy evidence is supplied by the history scenario;
successful TVM traces are covered separately below, not by the transfer-error
trace oracle used in this concurrent workload.

## Successful Historical Debug Traces

The existing concurrency scenario's `debug_traceTransaction` oracle intentionally
uses a plain transfer and expects rejection. It exercises separate trace permits,
not successful TVM execution. The new `scenario-debug-trace.sh` uses real
contracts with independent chosen-bytecode and canonical receipt expectations.

Temurin 25 run: `/private/tmp/archive-round3-debug-trace.log`.
Artifacts:
`/private/tmp/archive-round3-debug-trace/debug-trace-20260907-122506-47067`.
The framework jar had the same SHA-256 as the immutable matrix artifact above.
Result: `DEBUG_TRACE_OK checks=215 witnesses=27 storageHeights=6,8 destroyHeight=11`,
exit 0, both node shutdowns clean. The scenario retains request/response pairs,
receipts, contract coordinates and jar/script digests.

Both structured logs and callTracer pass for historical `debug_traceCall` and
`debug_traceTransaction`, before and after a clean restart. Exact opcode order,
pre-MSTORE8 stack/memory and full 33-byte output are checked. Storage advances
from 0 to 111 to 222; the second transaction must still read 111 while current
state is 222. Both tracers replay SELFDESTRUCT successfully and historical code
is checked on both sides of deletion. Unavailable heights and `latest` trace
requests are rejected with the expected error; live storage/code remains
unchanged by tracing. Repair-required and publication-failure metrics remain 0.

Boundaries: the SELFDESTRUCT fixture has zero balance and no assets. This does
not cover Cancun variants, nested traces, TRC10 enumeration or sustained trace
load. Those are not inferred from the 215 passing checks.

## Completion Status

This review round is complete. Two independent source reviews and the completed
runtime challenges found no new confirmed substantive P0/P1 functional or
performance defect at the reviewed baseline. The prior production fixes remained
unchanged during these runs. The confirmed new defect was in JDK 25 fault-injection
tooling; its fix and compiled-fixture regressions are included with the new
successful-trace scenario. Bash syntax checks, JDK 17/25 fixture tests and
`git diff --check` pass; the suite discovers the new executable scenario.

The P3 math-wrapper style cleanup is deferred. Mainnet-scale mixed-state startup
profiling, sustained workload qualification, the explicit unsupported/direct-read
coverage gaps and the unsuccessful mid-run chmod injection are not converted
into claims of correctness. No whole-branch proof, release-readiness or mainnet
throughput guarantee is made by this report.
