# Archive review — round-2 fix checklist (for codex)

**Base:** `feat/archive-node` @ `29f339ec1b` (after your `fix(archive): harden restart and index lifecycle`).
**Provenance:** two adversarial review rounds. Round-1's 3 findings (initGenesis restart-brick / txNum-index OOM / unwrapped prev-value read) — **you already fixed in `29f339ec1b`; re-verified correct + green** (chainbase archive.\* 432/0, ManagerArchiveLifecycle 7/0, framework checkstyle clean). This checklist is what **round-2** surfaced.

**Bottom line:** the archive-OFF / consensus path has **zero critical/high risk** (re-verified: byte-identity, capture isolation, read-guard covers full RPC+VM replay, VM root-commit isolation). The archive *feature* is release-ready **once G2 is fixed**. G6/G4 are acceptable latent debt.

> **Status update (2026-07-12):** codex ran out of budget, so **G2 and G6 were implemented directly on branch `archive-test-hardening`** — G2 in `8d0b50bfba`, G6 in `5cc61a214c`, each with regression tests (framework + chainbase suites green, framework checkstyle clean). **G4 remains optional/deferred** (deliberate safe asymmetry — see §3). Sections 1–2 are retained below as the design record of what was changed and why.

---

## 1. ✅ FIXED (`8d0b50bfba`) — G2 — mid-chain fork-flag TOMBSTONE aborts historical `eth_call` / `debug_traceTransaction`

> **Implemented as recommended:** `resolve(...)` returns `inMemoryDefault` on TOMBSTONE (unconditional, not gated on `genesisComplete`); `resolveArchived(...)` returns `0L` on TOMBSTONE (every flag on that path is a fork/feature gate whose unset value is off). Rather than migrate the ~25 `resolveArchived` getters to `resolve(..., 0L)`, the single `resolveArchived` tombstone branch resolves to 0 uniformly — genesis-seeded config keys (e.g. `MAINTENANCE_TIME_INTERVAL`) are written before coverage and surface as MISSING, not tombstone, so they never legitimately reach that branch. Regression tests: `tombstonedArchivedForkFlagResolvesToOff` (resolveArchived / Cancun), `tombstonedResolveFlagResolvesToDefault` (resolve / Osaka), `tombstonedResolveKeyResolvesToDefaultEvenMidChain` (resolve, `genesisComplete=false`). Two pre-existing tests that pinned the old fail-closed behavior were updated to the corrected semantics.

**Severity:** medium · **Blast radius:** archive-enabled **mid-chain** nodes + their historical-RPC users (fail-closed error, not silent wrong value; no consensus impact).

**Where:** `framework/src/main/java/org/tron/core/services/jsonrpc/HistoricalArchiveVmDynamicProperties.java`
- `resolve(...)` (~L318-321): `if (r.getStatus() == Status.TOMBSTONE) throw CORRUPT_VALUE "... is tombstoned";`
- `resolveArchived(...)` (~L342-344): identical throw.
- **Contrast** `resolveForkStats(...)` (L362-364): `if (r.getStatus() == Status.TOMBSTONE) continue;` — i.e. it *correctly* treats a tombstone as "absent → default". The throw in the other two is the oversight.

**Mechanism (interacts with the Erigon prev-value model — this is why it's subtle):**
1. Mid-chain enablement: `firstArchivedBlock = F > 0`, so `genesisComplete == false` and there is **no** genesis seeding of the fork flags (`saveGenesisArchiveVmProperties` runs only at genesis, `Manager.java:667-669`). A fork flag — e.g. `ALLOW_TVM_CANCUN`, resolved via `resolveArchived` at `HistoricalArchiveVmDynamicProperties.java:229` — is simply **unset** in the base store when archive starts.
2. The proposal **activates it at an in-window block A** (`F <= A`): the store write is the flag's *first* write, so the capture hook reads `prevValue = revokingDB.getUnchecked(key) = null`, and `ArchiveCaptureEngine.prevDomainValue` (`ArchiveCaptureEngine.java:136-141`, "null prev → `normalizeDelete()`") records `history[txNum_A].prevValue = TOMBSTONE`.
3. A historical `eth_call` / `debug_traceTransaction` at **any block T in [F, A)**: `getAsOf(T) = history.higherEntry(T).getValue() = that tombstone` (`InMemoryArchiveTemporalStore.java:154-156`; RocksDb parity `RocksDbArchiveTemporalStore.java:557-561`). `DefaultArchiveStateReader.getRaw` (`:181-187`) consults `readThrough` **only** on `!stored.isPresent()`; a tombstone *is* present, so it is returned directly (readThrough is bypassed), and `resolveArchived` throws `"... is tombstoned"` → the whole VM call aborts.

**Inconsistency (the tell):** the identical logical state "flag unset as of block T" resolves to
- **success** if the flag was first written *before* the archive window (temporal empty → `getAsOf` MISSING → default), vs
- **hard error** if first written *inside* the window (tombstone prev → throw).

So a mid-chain archive node fails historical replay for the **entire pre-activation window of every network upgrade** that lands during the archive's operational life — silently defeating the node's core purpose.

**Fix — treat TOMBSTONE as "known-unset → default", mirroring `resolveForkStats`:**
A tombstone prev-value is a *positive, known* statement "this key was unset as of block T" — strictly stronger than MISSING ("no info / maybe set before coverage"). So it is safe to resolve to the flag's unset/default value **even mid-chain** (unlike MISSING, whose mid-chain throw is correct to keep).
- **`resolve(...)`** (has `inMemoryDefault`): change the TOMBSTONE branch from throw to **`return inMemoryDefault;`** (unconditional — do not gate on `genesisComplete`).
- **`resolveArchived(...)`** (no default param): the flags on this path that are *fork-activation gates* (`ALLOW_TVM_*`, `ALLOW_*`, `DYNAMIC_ENERGY_*`, `ALLOW_ENERGY_ADJUSTMENT`, `ALLOW_STRICT_MATH`, `CONSENSUS_LOGIC_OPTIMIZATION`, `ALLOW_HARDEN_RESOURCE_CALCULATION`, …) all have "unset ⇒ 0/off" semantics, so on TOMBSTONE they should resolve to `0L`. Cleanest: **migrate those getters from `resolveArchived` to `resolve(reader, key, genesisComplete, 0L)`** so the single `resolve` tombstone fix covers them uniformly. Keep `resolveArchived` (throw-on-tombstone) only for genuinely-must-be-present, non-zero-default keys (e.g. `MAINTENANCE_TIME_INTERVAL`) that are seeded at genesis and never legitimately tombstone in-window — you're best placed to classify each.
- **Do NOT** route TOMBSTONE to `readThrough`: `ChainBaseArchiveReadThrough` reads the **live** store, which returns the flag's **current (activated)** value — wrong for a pre-activation historical block.

**Regression test (add to the historical-VM / adapter suite):**
Mid-chain archive (`firstArchivedBlock = 5`); drive a captured proposal that *first-activates* `ALLOW_TVM_CANCUN` at block 6 (prev = absent → tombstone recorded); then `eth_call` / reconstruct VM props at block 5 and assert `getAllowTvmCancun() == 0` (off / default) and the call **completes** — not an `ArchiveReaderException "is tombstoned"`. Mirror `resolveForkStats`'s already-correct handling as the oracle.

---

## 2. ✅ FIXED (`5cc61a214c`) — G6 — `InMemoryArchiveTemporalStore` inherits an unbounded, head-unguarded `unwindBlock`

> **Implemented:** `InMemoryArchiveTemporalStore` now overrides `unwindBlock(range)` with a head-guard — it rejects the range (throws `"... not temporal head"`, state intact) when any retained history txNum exceeds `range.getLastTxNum()` (the InMemory analog of RocksDb's `validateHeadBlock`), then unwinds. Regression tests mirror RocksDb's `unwindBlockRejectsNonHeadBlockAndKeepsLatest`: `unwindBlockRejectsNonHeadBlockAndKeepsState` + `unwindBlockUnwindsHeadBlockAndRevertsLatest`. (InMemory has no block-commit markers, so it does not replicate `validateCommittedBlock`; the divergence that mattered — silent discard of higher blocks on a non-head unwind — is now closed.)

**Severity:** low (latent — no live trigger today) · **Blast radius:** archive-nodes-only.

`InMemoryArchiveTemporalStore` has **no `unwindBlock` override**, so it inherits the interface default `ArchiveTemporalStore.java:80-82` → `unwind(range.getFirstTxNum())` (unbounded above, no head check). `RocksDbArchiveTemporalStore.unwindBlock` (`:586`) instead runs `validateCommittedBlock` + `validateHeadBlock` (fail-stop if any block-commit marker has `blockNum > range.blockNum`) then a **bounded** `unwind(firstTxNum, lastTxNum)`. So on `unwindBlock(non-head)`: RocksDb fail-stops with state intact, while InMemory silently discards *all* history/latest at and above `firstTxNum` (including higher/head blocks) — violating the "the two stores MUST stay observationally identical" contract (`ArchiveTemporalStore.java:18`).

**Why it's latent, not live:** the only production caller, `DefaultArchiveService.unwindPublishedBlocksAfterCanonicalHead` (`:348-360`), unwinds strictly **head-first** in a descending loop, where bounded and unbounded coincide.

**Fix:** override `unwindBlock` in `InMemoryArchiveTemporalStore` to match RocksDb's `validateHeadBlock` + bounded semantics (or at minimum assert head-only), so the reference store can't diverge if a future caller unwinds a non-head block.

---

## 3. [OPTIONAL — info, deliberate design] G4 — archive can end one block ahead of canonical after a rare commit fault

**Severity:** info · **Blast radius:** archive-nodes-only.

`commitArchiveBlockOnlyOrFailStop` commits the archive journal **before** `tmpSession.commit()` (`Manager.java` ~1583). If the canonical commit throws while the JVM survives, `abortArchiveBlockBestEffort → abortBlock` clears the execution context / capture engine / `executionTxNumIndex.abortBlock`, but does **not** unwind the already-remembered in-flight block (moved into `inFlightStore` + `inFlightBlocks` by `rememberInFlight`) → archive is one block ahead of canonical.

**This is the deliberately-chosen safe asymmetry** (the code comment: archive-ahead must fail-stop on restart, never silently skip): it is self-detecting (next `beginBlock(N)` hits `inFlightBlocks.containsKey(N)` → throw; a restart hits `reconcileInFlightOnStartup`), always resolves to a `TronError` fail-stop — **never** silent wrong historical data — and the trigger window (an in-memory revoking-session commit throwing) is near-empty. **Optional hardening:** when `abortBlock` is invoked on an already-committed in-flight block, either unwind it or log/assert loudly. Not release-blocking.

---

## Verified sound — no action needed

- **Round-1's three fixes** (`29f339ec1b`) are correct and covered by your new regression tests.
- **G1 (VM historical props inheriting live values):** NON-issue. `HistoricalArchiveVmDynamicProperties` overrides **42 of 43** `VmDynamicProperties` getters from the archive; the only one left to the live `latest` store is `getEnergyFee`, which is *intentionally* reconstructed from the complete-from-genesis `EnergyPriceHistory`. No result-affecting flag leaks the current value into a historical trace. (This was the round-1 critic's top-flagged risk; the actual override set turned out comprehensive.)
- **G3 (single-block temporal `WriteBatch` atomicity):** clean.
- **G5 (offline `DbArchive` plugin, arm/x86):** clean.
