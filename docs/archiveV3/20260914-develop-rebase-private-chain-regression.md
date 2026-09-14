# Develop rebase: archive private-chain regression

Date: 2026-09-14
Status: COMPLETE. Main suite 8/8 passed; four additional private-chain runs passed.

## Artifact and scope

- Branch: `feat/archive-node-JDK25-fsync`.
- HEAD at test start: `0695b7898b5893ede49c8f9a9d398f2aaa2c296d`.
- Develop ancestor: `243b693acc9b4427f15e667fa50f265205f49aac`.
- The tested working tree also included the then-uncommitted rebase adapters and A-lite
  account-input reuse. Consequently, that base commit alone does not reproduce the tested artifact.
- Fresh build: `./gradlew --console=plain :framework:buildFullNodeJar -x generateGitProperties`.
- FullNode.jar SHA-256: `2dea1def495018538783518ac7189956dfbdd396ee714438d6d8b500bbbff6cc`.
- Build/unit tests: JDK 17. Private-chain JVMs: Temurin 25.0.4, macOS arm64.
- Nodes use fresh temporary databases, run-local random keys, and no public peers.
- Historical accuracy, trace, kill and single-process fault scenarios use one process signing
  for 27 SRs. Fork scenarios use their explicit two-SR topology. Catch-up uses two processes
  sharing a 27-SR genesis, split 26+1; the source has archive disabled.

The scope is archive correctness after rebasing onto develop, including the then-uncommitted
account-input reuse. It is not a mainnet-scale performance or release-qualification claim.

## Results

| Check | Result | Evidence |
| --- | --- | --- |
| Fresh jar build | PASS | Current working-tree classes included |
| Chainbase archive/account-capture tests | PASS | 966 tests, zero failures/errors/skips |
| Actuator historical VM/SELFDESTRUCT tests | PASS | 67 tests, zero failures/errors/skips |
| Framework archive/RPC tests | PASS | 179 tests, zero failures/errors/skips |
| Upstream contract-hash guard and EXTCODEHASH | PASS | Five additional tests covering the 4.8.2.2 activation gate and opcode behavior |
| Framework checkstyleMain/checkstyleTest | PASS | Both tasks completed |
| Harness key/config tests | PASS | Single-SR, 27-SR and 26+1 HOCON bindings |
| Semantic breakpoint tests | PASS | Four resolver unit tests and ANCHOR_SELFTEST_OK |
| gRPC | PASS | getNowBlock and getBlockByNum returned the same canonical block at height 75 |
| Smoke and clean restart | PASS | 23 checks; five historical oracles preserved across restart |
| Historical accuracy | PASS | 17 transactions, 1,280 live samples; 57 passing verdicts, four explicit information-only limitations |
| Fork/reorg and restart | PASS | Six blocks unwound exactly once; 25 contiguous ranges; orphan state absent |
| Kill matrix | PASS | Six windows; five recovered with identical historical oracles, genesis-marker window explicitly failed closed |
| Disk-resource faults | PASS | 12 passing checks and three INFO verdicts across disk-space admission, permission loss and MANIFEST truncation |
| Concurrent queries under faults | PASS | Five phases including setup; 24 workers; seven-block reorg; no skips |
| Catch-up, default soft watermark | PASS | 32 checks; maxFlushCount=5; softInFlightBlocks=32768; canonical block 81 matches source |
| Catch-up, softInFlightBlocks=8 | PASS | 32 checks; runtime flushCount=maxFlushCount=5; canonical block 80 matches source |
| Main-suite trace | PASS | 328 checks; 27 SRs; physical assets; funded SELFDESTRUCT at height 38; identical replay after restart |
| Additional trace, inline TRC10 assets | PASS | 327 checks including funded SELFDESTRUCT and restart |
| Additional trace, physical account-asset storage | PASS | 328 checks, including flush and physical-layout assertion before destruction |
| Full-scrub startup and clean restart | PASS | 23 smoke checks; every full validation stage completed; five historical oracles survived restart |

The unit-test total is 1,217 with zero failures, errors or skips. The main suite ran from
15:19:01 to 16:04:02 (UTC+08:00), taking 2,701 seconds. Its final driver verdict is:

```text
PRIVATE_CHAIN_FAULT_SUITE_OK scenarios=8 passed=8 failed=0 inconclusive=0 skipped=0 elapsed=2701s
```

All four additional runs also passed: low-watermark catch-up, inline-asset trace,
physical-asset trace and full-scrub smoke/restart. Expected fail-closed outcomes and the
permission observation's information-only limitation are detailed below; a passing scenario
does not mean every injected condition recovered or every sub-check was conclusive.

No archive functional regression was found in the tested working-tree artifact. This is
evidence for the covered scenarios, not proof that every chain state or failure is covered.
The jar checksum remained unchanged throughout the run. The test nodes exited and the
temporary disk image was detached. No commit or push was performed during the regression run.

## What the checks establish

Historical state is compared against transaction amounts and fees, fixed contract bytecode,
and live values sampled at the corresponding height. Storage exercises `0 -> 111 -> 222 -> 0`.
The reward scenario spans maintenance, a newly elected non-genesis SR, and a real withdrawal:
the historical balance change equals the independently recorded withdrawal receipt.

The fork test creates an orphan-only transfer with an orphan TAPOS reference. After the induced
reorg, both nodes agree on the replacement heights, no historical query serves the orphan balance,
and pre-fork historical values remain unchanged. Node A also restarts successfully afterward.

The kill matrix covers steady-state interruption, journal-before-canonical-commit,
canonical-commit-before-acknowledgement, acknowledgement-before-publication, atomic publication,
and genesis-before-COMMITTED-marker. Windows w1-w5 recover; all six historical oracles remain
identical and offline range/journal probes remain clean. The unfinalized canonical tail can
roll back to durable state. Window w6 deliberately refuses startup with ARCHIVE_RUNTIME and
repair-required, which is the expected fail-closed outcome, not successful recovery.

Disk-space admission is exercised on a real 256 MiB mounted image. At 3,551,232 free bytes,
below the configured 8 MiB hard reserve, the node reports ResourceAdmissionFailure and exits.
After space is returned it recovers without index gaps. This tests the reserve guard, not
an actual failed RocksDB write syscall. An unwritable archive directory is refused on restart;
restoring permissions allows recovery with 82 contiguous published ranges. Finally, truncating
the stopped archive's MANIFEST from 1,137 to 568 bytes causes an explicit initialization
fail-stop. No selected disk-fault case was skipped.

The concurrent-fault scenario completes 157,948 request attempts: 21,640 successful result
comparisons, 19,206 explicit RPC errors and 117,102 transport failures during the two process-stop
phases. Every phase has zero wrong values, unexpected results, malformed responses and request
timeouts. There are 21 oracles for the single-node phases and eight for the reorg phase.
Clean-stop and SIGKILL restarts reproduce all 21 oracles. The concurrent reorg unwinds seven
blocks and reproduces all eight oracles. Active snapshots, active queries and pending queries
settle to zero, and repair-required remains zero. Request counts include expected failures and
must not be interpreted as successful-query throughput.

The low-watermark catch-up test inspects `SnapshotManager` through JDWP while it is stopped
after checkpoint creation and before refresh. Both runtime counters are five at SIGKILL.
After restart, six independent source-node balance oracles match, canonical block 80 matches,
and the offline probe finds no block/txNum gaps, stale published journals or repair marker.
Its final published archive head is 60; the unsolidified tail is not required to be empty.
The main-suite repeat uses the default soft watermark of 32,768. The same runtime batch
counters are five at the kill point, with target archive tail 24 and source head 56. It restarts
to READY in about four seconds, matches all six history oracles, and continues to canonical
height 81 with the same block ID as the source. The final probe has 62 contiguous published
ranges (0..61), no txNum gaps and no repair-required marker.

Trace checks include MSTORE8 memory, transaction-before storage, struct logs, callTracer,
zero-balance SELFDESTRUCT, and a victim funded with 123456 SUN and two TRC10 assets.
The two asset amounts are 111 and 222. A fixed TOKENBALANCE reader checks the victim and
beneficiary at pre/post-destruction heights, and rechecks live balances after replay.
The whole trace query set is repeated after a clean process restart.

An additional smoke run enables `fullScrub=true` explicitly. On restart, ranges/positions,
temporal blocks, history/changeset/latest links, anchors, all three domain scans and payload
ownership all complete before the same historical queries are repeated. This is a small-database
correctness check, not an estimate of mainnet full-scrub duration.

## Harness corrections and additions

1. Restored the pre-rebase catch-up script correction from the retained stash. Its old text
   counter missed quoted local-witness keys. The replacement uses the actual HOCON parser
   and `HarnessKeysTest`, including key identity and ordering, not merely line counts.
2. Extended `scenario-debug-trace.sh` with two TRC10 assets and nonzero TRX destruction.
   `DT_ASSET_OPTIMIZATION=0` exercises inline assets; the default `1` enables physical assets.
3. The initial new fixture tried an ordinary TRX transfer into a version-one contract. Canonical
   validation correctly rejected it. Funding was moved into the deployment's `call_value`;
   the rejected precondition is not an archive defect. The failed attempt's artifacts are retained.

No production source changes have been made during this regression run.

## Reproduction

Build with the project's configured JDK. For the private chains, put JDK 25's `bin` directory
first on PATH, then run from the repository root:

```bash
HS_SKIP_BUILD=1 HS_KEEP_WORKDIR=1 HS_CFG_WITNESS_COUNT=27 \
FORK_ASSERT_RESTART=1 FAULT_SCENARIOS='enospc permission truncation' \
bash docs/archiveV3/harness/run-all.sh \
  --out-dir /private/tmp/archive-rebase-e2e-20260914 \
  --jar "$PWD/framework/build/libs/FullNode.jar"

HS_SKIP_BUILD=1 HS_KEEP_WORKDIR=1 HS_CFG_SOFT_IN_FLIGHT_BLOCKS=8 \
ARCHIVE_HARNESS_PORT_OFFSET=4000 \
bash docs/archiveV3/harness/run-all.sh catchup-batch-flush-kill \
  --out-dir /private/tmp/archive-rebase-catchup-soft8-20260914

HS_SKIP_BUILD=1 HS_KEEP_WORKDIR=1 HS_CFG_WITNESS_COUNT=27 \
ARCHIVE_HARNESS_PORT_OFFSET=4000 DT_ASSET_OPTIMIZATION=0 \
bash docs/archiveV3/harness/run-all.sh debug-trace \
  --out-dir /private/tmp/archive-rebase-trace-unoptimized-r2-20260914

HS_SKIP_BUILD=1 HS_KEEP_WORKDIR=1 HS_CFG_WITNESS_COUNT=27 \
ARCHIVE_HARNESS_PORT_OFFSET=4000 DT_ASSET_OPTIMIZATION=1 \
bash docs/archiveV3/harness/run-all.sh debug-trace \
  --out-dir /private/tmp/archive-rebase-trace-physical-20260914

HS_SKIP_BUILD=1 HS_KEEP_WORKDIR=1 HS_CFG_WITNESS_COUNT=27 \
ARCHIVE_HARNESS_PORT_OFFSET=4000 HS_CFG_ARCHIVE_FULL_SCRUB=true \
bash docs/archiveV3/harness/run-all.sh smoke \
  --out-dir /private/tmp/archive-rebase-full-scrub-20260914
```

Run the two trace variants sequentially because they share a port band. Use fresh output
directories for later reproductions.
Primary logs are `<out-dir>/<scenario>.log`; per-node data and request/response evidence remain
under the same output root. Unit XML reports are preserved there in `chainbase-unit-results`,
`actuator-unit-results`, `framework-archive-unit-results` and `framework-upstream-hash-unit-results`.
These directories contain local test credentials and are not
committed. The earlier rejected funding fixture is under
`/private/tmp/archive-rebase-trace-unoptimized-20260914`.

## Limits

- The history harness explicitly records four INFO verdicts: historical allowance and reward
  dynamic-property values have no direct public historical getter. Their economic effects are
  checked through balances/withdrawal, not presented as full field-by-field archive oracles.
- Offline `ArchiveProbe` checks range and journal structure; it is not an independent complete
  comparison of every temporal value. Historical state oracles and storage unit tests complement it.
- Mid-run permission revocation is explicitly INCONCLUSIVE: already-open file descriptors
  continued writing for the 240-second observation window. The deterministic read-only restart
  and subsequent restored-permission recovery passed; this is not proof that every runtime I/O
  permission failure was injected. The other two disk-fault INFO verdicts only report repair markers.
- The concurrent-fault storm has real varying historical balance oracles. Its storage target
  is an ordinary account, its `eth_call` oracle is an explicit non-contract error, and its trace
  oracle is the deterministic rejection of a non-TVM transfer. It exercises these admission/error
  paths under concurrency, not expensive successful TVM replay under concurrent process faults.
  Successful historical contract calls and traces are covered separately by the trace scenarios.
- Local short-chain results do not establish mainnet-scale disk usage, startup latency,
  sustained throughput, every governance combination, or physical hardware power-loss behavior.
