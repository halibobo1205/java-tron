# Private-chain fault harness — build report and first findings

**Deliverable:** `docs/archiveV3/harness/` — a committed, re-runnable private-chain fault-injection suite (lib.sh + 5 scenarios + run-all + offline ArchiveProbe). codex's earlier E2E covered the happy path only and lived in `/tmp`; this covers the abnormal scenarios every prior audit round flagged as "no test anywhere", and it stays in the repo.

Scenarios: `smoke` (23 checks), `kill-matrix` (6 durability windows, jdb-driven anchors + probabilistic fallback), `fork-reorg` (2-witness partition/heal), `resource-faults` (ENOSPC / read-only / truncated MANIFEST), `concurrency-under-fault` (query storm across stop/kill/reorg). All 8 files pass `bash -n` on bash 3.2.

## Two product findings from actually running it

These came out of the recon phase (a real 2-witness chain), not from source review — nine review rounds did not surface either.

### F1 [high] A clean SIGTERM on a multi-witness chain reproducibly bricks the restart
Reproduced: 2-witness topology, plain `SIGTERM` to node B, restart fails with
`ArchiveException: archive head block 14 does not match canonical head block 13`
(`Manager.initInternal` → `archiveService.validateCanonicalHead`, `Manager.java:570`).
Mechanism: with ≥2 witnesses `solid < head`, so the canonical DB's restart-recoverable head lags the archive's published head. On the single-witness chain (`solid == head`) the same SIGTERM restart is clean — which is exactly why the existing E2E never saw it. **Every real deployment has many witnesses.** The settled "journal leads canonical by one block, self-detected on restart" asymmetry is supposed to be reconciled at startup; here reconcile runs first (`:565-567`) and validation still rejects.
**Ask for codex:** is the validation head (`solidifiedNum != canonicalHead ? getBlockByNum(solidifiedNum) : canonicalHead`, `:567-570`) the right anchor when the archive legitimately leads solidified? Either reconcile must bring the archive back to the validated anchor, or the anchor must account for the deliberate lead.

### F2 [high] The startup validation failure does not fail-stop — the JVM hangs alive
`validateCanonicalHead` throws a plain `ArchiveException` (`DefaultArchiveService.java:2553`), **not** a `TronError`. The fail-stop machinery (`ExitManager.findTronError` → exit 1, watchdog → halt 70) only routes `TronError`, so this failure escapes `initInternal` as an ordinary bean-init exception and the process stays alive with no block production. Operators see a live-but-dead node instead of the designed fail-stop with `repair-required`.
**Ask for codex:** wrap startup archive-validation failures in `TronError(ARCHIVE_RUNTIME)` (or route them through `markFatal`) so the documented exit-code contract holds. The harness's `resource-faults` and `kill-matrix` scenarios both grade on that contract, so this must land before their verdicts mean anything.

## Harness caveats the first human run must respect
1. **Rebuild the jar first** — `./gradlew :framework:buildFullNodeJar`. The checked-in `FullNode.jar` is stale; the kill-matrix detects this and silently degrades windows w2–w5 from deterministic (jdb anchors) to probabilistic timing.
2. **`kill-matrix` has never run end to end.** Smoke one window first: `./scenario-kill-matrix.sh --windows w3 --prob-iters 1`, and read `PHASE_VERDICT` — `mode=deterministic` means the anchor was actually hit.
3. `fork-reorg` and `resource-faults` were green under their authors but their gates were **tightened afterwards** (baseline deltas, breadcrumb-required fail-stop, transport errors no longer count as fail-closed, probe must report `opened==true`). Both must be re-run.
4. Contract-based oracles need a real solc-compiled contract; the hand-rolled bytecode currently used yields empty runtime code (archive and latest agree, so it proves nothing about storage).
5. Weaker-than-ideal, documented rather than asserted: "orphan journal rolled back exactly once" is inferred from log lines (no unwind counter in `ArchiveMetrics`); the mid-run permission fault is inconclusive by construction (RocksDB writes through already-open descriptors) — the load-bearing check is the read-only restart; ENOSPC legitimately does not set `repair-required`.

## Why the harness is worth trusting more than a green run
The review pass rewrote ~16 assertions that could pass **vacuously** — oracles that compared empty-to-empty and reported RECOVERED, fail-closed checks that accepted `ERR:transport` (i.e. a node that was simply down), fork checks counting log lines with no pre-heal baseline (a 2-witness chain reorgs once during normal peer sync, so they were green regardless), probe checks where unparsable JSON read as "no gaps", and scenarios that printed `_OK` having run zero checks. Every one of those is now a hard failure. A green suite means something; it did not before the fix pass.

## Codex remediation and verification

Both findings are fixed.

The recovered `latestSolidifiedBlockNum` is state from the durable canonical root and may trail a
published archive block whose canonical block is itself durable. Requiring the archive head to equal
that recovered solidified height was therefore the wrong invariant. Startup reconciliation now
always validates the published archive tail against the canonical block at the tail's own height,
even when there are no pending journals. It still rejects a tail above the canonical head, a hash
mismatch, missing canonical coverage, or a broken parent link.

`Manager` no longer performs the incorrect exact-height validation after reconciliation. Archive
failures from this startup phase are converted to `TronError(ARCHIVE_RUNTIME)`, while existing
`TronError` classifications remain unchanged. A genuine published-tail hash mismatch is marked
repair-required by `DefaultArchiveService` before the failure escapes.

Regression verification:

```text
DefaultArchiveServiceTest: 140 tests passed
chainbase org.tron.core.archive.*: passed
ManagerArchive* + ManagerGenesisArchiveTest: passed
framework checkstyleMain + checkstyleTest: passed
```

The rebuilt FullNode jar also passed the real two-witness partition/heal/reorg/restart scenario with
`FORK_ASSERT_RESTART=1`:

```text
A head=10 solid=9 before partition
6 canonical blocks unwound exactly once
25 contiguous archive ranges, blocks 0..24
no stale journal and no repair-required marker
node A restarted cleanly at head 24
FORK_E2E_OK checks=20 passed=19 depth=6 switches=1
```
