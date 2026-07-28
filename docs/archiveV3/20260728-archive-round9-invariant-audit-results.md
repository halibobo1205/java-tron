# Archive round-9 invariant audit results (for codex)

**Audited state:** HEAD `2e33b36140` **plus the uncommitted round-29 remediation** (~37 modified + 4 new files).
**Mandate (user's four invariants):** I1 canonical state execution must never be affected · I2 archive data must be exactly accurate · I3 fork/unwind, restart and abnormal kill must stay correct · I4 query concurrency must never corrupt execution — no poisoned caches, no dirty data.
**Deployment calibration applied:** archive feature branch for a third-party-style fullnode, not merging to main now; RocksDB version choice / on-disk compat / downgrade explicitly out of scope (user-approved); market (DEX) proposal never activated, so comparator ordering carries no data-reordering risk — only plain comparator correctness was audited.
**Provenance:** 8 probes → 3-lens adversarial verification → critic; 63 agents, 0 errors. 18 raw → 3 survived → **dedup 2 real items, both non-defects (one cosmetic, one test-coverage gap)**. claude independently re-verified the load-bearing I1 claims.

## Invariant verdicts

| Invariant | Verdict |
|---|---|
| **I1 canonical execution untouched** | **PROVEN-WITH-EXCEPTIONS** — `AccountStore.get()` is byte-identical to `origin/develop`; the per-read SHA-256 baseline is gone with no residue; archive-off `Storage.commit` executes exactly one archive statement (`requireCurrentTx`) proven to be a no-op when `engine == null`, with the canonical `store.delete/put` unchanged and in the same order. Every exception is a deliberate fail-stop or a missing test, never a wrong behavior. |
| **I2 archive data exact** | **PROVEN-WITH-EXCEPTIONS** — `Storage.commit` reads `prevSlotValue` strictly BEFORE the canonical mutation and chains physical-key aliases through a request-local `currentValues` map, with `containsKey` distinguishing absent from present-with-null so put-then-delete and delete-then-put both fold correctly; the write-boundary assetV2 delta is pinned by 20 tests. **Uncomfortable residual:** the new delta rests on an unasserted premise (below). |
| **I3 fork/restart/kill** | **PROVEN-WITH-EXCEPTIONS** — INTENT precedes `buildSession`, COMMITTED is written after `commitToRoot` (which retreats with `applySnapshot=false`, so `close()` cannot revoke it), archive ack follows COMMITTED; the three startup rejection rules are literal in `validateArchiveGenesisCommitMarkerPresence`. |
| **I4 concurrency / no dirty data** | **PROVEN** (capacity caveat only) — the historical proof pool is a 6-arg `ThreadPoolExecutor` (⇒ AbortPolicy by construction) with a repo-wide grep confirming **no** CallerRuns/Discard/custom handler anywhere, so saturation surfaces as `RESOURCE_EXHAUSTED` and can never reach the canonical pools; every per-query object (futures, latch, task byte[] copies) is call-local. |

## Findings (2 after dedup — neither is a live defect)

1. **[info] Dead `import java.util.Arrays;`** — `AccountCapsule.java:32`. The round-29 cleanup removed 4 of 5 orphaned imports; this one survives with zero `Arrays.` usages. Harmless (chainbase isn't checkstyled, no UnusedImports rule), but deleting it makes `git diff origin/develop` on that file a **one-hunk proof** of archive-off byte identity. *(Found independently by two dimensions.)*
2. **[low-medium] Precompile historical-detection wiring is untested** — `VerifyTransferProofDeadlineTest.java:34`. The behavior is correct today, but only the pure predicate is asserted; the wiring that makes `isHistoricalArchiveCall()` true for trace replay with `constantCall == false` has no test, so a future revert of that load-bearing line stays green. Fix: drive `BatchValidateSign.execute` / `VerifyTransferProof.execute` through a Repository stub with `isHistoricalArchive()==true` **and** `constantCall==false`, asserting the canonical pools are never touched.

## Verdicts on codex's 10 requested claims
**Fully substantiated (4):** #1 archive-off byte identity (one cosmetic caveat = the dead import) · #2 stale capsule / optimized transitions / deletion / rows-absent-from-map (all four have named tests) · #3 physical-key aliasing + prev-value chain after removing opcode baselines · **#4 `VmResultCodeMapper` — the strongest-evidenced item of the round: branch-for-branch identical to the deleted `RuntimeImpl.setResultCode`, verified by direct body diff.**
**Partially (4):** #5 (source chain proven, no test) · #6 (structure proven: AbortPolicy by construction; empirical saturation/timeout run missing) · #7 (window analysis holds by source reading; no real crash-injection test between `commitToRoot` and `saveArchiveGenesisCommitComplete`) · #8 (store level substantiated; **consumer-level propagation unproven** — no test drives the ArchiveException through reader → adapter → SELFDESTRUCT replay).
**Not source-verifiable (2):** #9 real x86 Debian/JDK8 run and #10 OS/glibc matrix — need actual hardware; #10 is additionally out of scope per the deployment calibration.

**The recurring pattern across all four partials is identical:** the mechanism is right, but the test asserts the helper in isolation instead of the wiring, so a future revert of the load-bearing line stays green.

## Residual audit risk (thinnest evidence)
- **Highest-value single-finder premise (I2):** the new capture model assumes *"physical account-asset rows are mutated only by `SnapshotRoot` between flushes, and every asset changed within a flush window is carried in the session account's assetV2 map."* A violation would be **silently wrong rather than fail-closed** — the one place in this round where the failure mode is not fail-stop. Worth an explicit invariant check or test.
- The 152-combination result-code differential was run against a temp copy that was then deleted; only the finder's word remains (claude independently re-derived branch equivalence by direct diff, which is the stronger evidence).
- `UnifiedArchiveTemporalStoreOracleTest` covers forward-only history; the InMemory predicate flip (`latest != null` → `!history.isEmpty()`) is observable only after an in-memory unwind, which no test exercises.
- `DBKeyComparatorTest`'s direct-buffer case allocates capacity == limit, so it cannot distinguish `remaining()` from `capacity()`; production always uses RocksDB's reused buffers where capacity > limit.
- The dual LATEST+ANCHOR scan charges `reserveScanResultKey` **twice per key** against the monotonic `maxVmOverlayBytes` budget — halves the enumerable TRC10 set before the limit trips. Capacity-only, undocumented.

## claude's own spot-check (independent of the agents)
`AccountStore.getUnchecked` is overridden to `return get(key)` — i.e. empty value → `null`, whereas the inherited `TronStoreWithRevoking.getUnchecked` → `of(value)` → `new AccountCapsule(new byte[0])` → **non-null** empty capsule (`Account.parseFrom(empty)` succeeds). Five canonical callers use it (`MortgageService:245`, `Commons:73,168`, `AbstractActuator:102`, `Manager:1037`), some of which dereference the result immediately.
**This override already exists at HEAD — it is NOT introduced by the round-29 delta**, and it arguably makes `getUnchecked` consistent with upstream `get()`. Whether it is reachable depends on whether `revokingDB.getUnchecked` can ever return a zero-length (non-null) account value. **Ask for codex:** confirm that case is impossible (then it is a no-op alignment worth a comment), or that the five callers tolerate `null`.

## Remediation verification (claude)

`c7a41aafcc fix(archive): harden state accuracy and x86 support` (43 files, +910/−564; round-29 delta + round-9 fixes squashed, working tree clean). All three recommended items verified landed; suites green (chainbase archive+store, actuator vm, framework AccountAssetStore/BatchValidateSign/jsonrpc/genesis-archive).

1. ✅ **The unasserted premise is now asserted** — `AccountAssetStoreTest.archiveCapturesLazyImportedPhysicalAssetMutationThroughAccountFlush` writes a physical asset row directly (optimized mode, bypassing the protobuf map), lets it lazy-import through the account flush, mutates it, and asserts the captured record is `prev=7 → new=12`. This is exactly the "physical rows mutate outside the assetV2 map" path whose failure mode was silently-wrong. `AccountStore` additionally documents at three points that `SnapshotRoot` may migrate/delete physical rows, so the diff must precede the canonical mutation — the premise moved from implicit to stated-and-tested.
2. ✅ **Wiring test delivered at both levels** — `BatchValidateSignContractTest.historicalRepositoryBypassesCanonicalWorkersWhenNotConstantCall` mocks `isHistoricalArchive()==true` with `setConstantCall(false)`, drives the real `execute`, and asserts the canonical pool's `getTaskCount()` is unchanged (reflection-obtained). Plus the four-combination `usesCanonicalWorkers` truth table. The "asserts the helper, not the wiring" pattern is closed for this fix.
3. ✅ **Dead `Arrays` import removed** — `AccountCapsule` now differs from upstream by the single intentional hunk.

Bonus: `AccountStore` −42 and `Storage` −57 lines — the archive footprint on the canonical hot path shrank further.

**Still open from round-9 (evidence-strengthening, not defects):** consumer-level LATEST/ANCHOR corruption propagation test (claim #8), and a real crash injection between `commitToRoot` and `saveArchiveGenesisCommitComplete` (claim #7). Plus the user decision on claims #9/#10 (real x86 Debian/JDK8 fault matrix).

## Bottom line
Round 29 holds up. The two largest-blast-radius changes carry the strongest proofs (`VmResultCodeMapper` branch-identical; archive-off canonical paths byte-identical to upstream). No user invariant is broken. Everything actionable is a **test-coverage** or **hygiene** item, plus one premise worth asserting. Recommended order: (1) delete the dead import; (2) close the precompile wiring test; (3) assert the account-asset mutation premise; (4) extend LATEST/ANCHOR corruption coverage to the consumer level; (5) add the genesis crash-injection and pool-saturation tests; (6) document the doubled overlay-byte charge. Claims #9/#10 remain a user decision: whether the real x86 Debian/JDK8 fault matrix is a gate for this deployment.
