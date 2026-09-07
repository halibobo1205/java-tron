# Full Scrub Follow-Up Review

Date: 2026-09-07. Branch: `feat/archive-node-JDK25-fsync`.
Baseline: `4d51de921f`. Scope: supplied Claude review and remaining restart cost.

## Assessment

1. Confirmed: resource-admission failures currently share the unconditional
   repair-marker path with possible persistent corruption. This can force a
   full scrub after a backpressure refusal. However, classifying just the
   first fatal is unsafe: `completeFatalFailure` returns early for subsequent
   contenders. Exempting a resource failure could then suppress a later
   corruption marker. The admission gate also precedes only the caller's new
   block; an asynchronous publisher can still be working on older blocks.
2. Confirmed: full-keyspace validation point-reads its current iterator row,
   then performs additional position/range/txId checks. Current-row reads can
   use bounded iterator values; genuine cross-references must still be checked.
   Position values are variable length, unlike range and cursor rows.
3. Confirmed: journal loading needs progress visibility. Its cross-block
   `inFlightVersions` map already memoizes prior values; do not hoist the
   per-block `stagedLatest` map or weaken previous-value-chain checks.
4. Confirmed coverage gap: the repository Checkstyle tasks did not cover
   chainbase. The earlier report has been corrected. Cleanup after a successful
   identity callback followed by an identity-completion failure also needed a test.

The supplied range log measures 1,159,321 rows in 1,415 ms. It does not measure
whole-node restart. The equation involving rejected block 1,168,949 and backlog
9,629 is not a proof of contiguous recovery: rejected height is not necessarily
durable head, count and last height are different quantities, and those logs
come from different moments. Endpoint hashes and canonical reconciliation are
required. Likewise, 67 minutes and 8-20 hours are extrapolations, not measured
end-to-end restart durations.

## Implemented Changes

- Convert five current INDEX-row value reads to bounded iterator reads. Keep
  key checks, decoders, cross-references and complete range coverage validation.
- Add `valueBounded(minBytes, maxBytes, what)` with a maximum 64 KiB probe.
  Reject truncated/oversized rows using the native actual length. Preserve
  owner, close, deadline and actual-value query-budget checks before returning
  an allocated result. Fixed-size `valueExact` behavior is unchanged.
- Allow full startup scrub to use the existing archive-local 72 MiB cache.
  Do not resize, split or pin it. Normal publication/scan/query policies are
  unchanged. **This alone did not solve the large-fixture slowdown; see below.**
- Log `inflight-journal` start/progress/completion and loaded/stale counters.
  Journal loading behavior and its distinct-key lookup cost are unchanged.
- Add a factory cleanup regression for identity/IO/runtime/error failures
  after successful opening. Reopen the actual RocksDB to verify lock release.

No canonical write path, archive schema, fsync setting, recovery-marker policy
or configuration default was changed. The pre-existing `framework/build.gradle`
hunk is unrelated and remains untouched. No fsmonitor socket was deleted.

## Large-Fixture Counterexample

A local arm64/JDK 17 fixture contains 1,160,000 empty blocks, two system positions
per block, valid empty changeset-digest block markers, and a durable repair
marker. Data was flushed to SST files using production DB options. The normal
factory/recovery path ran with `fullScrubOnStartup=false`; the marker correctly
forced `fullScrub=true`.

Observed stages with the changes above:

| Stage | Rows | Elapsed ms | Outcome |
| --- | ---: | ---: | --- |
| ranges | 1,160,000 | 986 | Complete |
| inflight-journal | 0 | 2 | Complete; no backlog in fixture |
| index-keyspace | 1,313,792 | 11,167 | Progress only |
| index-keyspace | 1,340,416 | 247,769 | Progress only; run then stopped |

The position region slowed to approximately 112 rows/s over that interval.
The first million fast range rows must not be used to estimate the remaining
cross-reference-heavy work. A Java stack placed the CPU in `RocksDB.get` from
`getPositionFromCurrentView`. A one-second native sample showed repeated SST
index-block decompression: 747 of 826 sampled Get stacks passed through
`UncompressBlockData` / Snappy. This establishes that cache admission alone is
insufficient on this fixture. It does not establish the exact eviction cause
or a mainnet ETA; no cache sharding/pinning change is justified by this sample.

The benchmark was stopped deliberately after capturing the counterexample.
There is **no completed million-block full-scrub timing** and no claim that its
repair marker cleared. This fixture contains no mainnet account/contract state,
user transactions or in-flight backlog. Smaller successful tests cannot remove
that limitation. Temporary benchmark sources and logs are under `/private/tmp`.

## Remaining Work

### Resource Fatal Classification

Use a default-to-repair classification, allow-listing only independently proven
resource refusals. Keep fail-stop behavior and the existing exit code. Repair
need must escalate monotonically across all fatal contenders, not just the
first. Before implementing, define and test the ordering among marker fsync,
publisher drain, fatal delivery, recovery marker clearing and DB close.

Required cases: resource refusal alone; corruption first; refusal followed by
corruption; concurrent contenders; recovery/clear races; marker write failure;
shutdown sealing; watchdog delivery while publication is in progress. Never
clear an existing marker merely because the original failure was backpressure.
This policy change is intentionally not included in the present patch.

### Full-Scrub Cross-Reference Access

One correction to the supplied review: not every `getPosition` here reads a
different row. In the position branch, `getPosition(positionTxNum)` reads the
same position again, then validates its range and secondary indexes. The
direct iterator-value conversions leave that indirect reread intact. A next
patch can reuse the decoded position, but must preserve the complete committed
position validator, including user/VM pairing and txId/block-index checks. The
current-row regression isolates these validator calls; it does not assert that
the entire full scrub is free of point Gets.

First measure which SST metadata fails to remain resident and why. Evaluate
startup-scoped reusable lookup iterators or coordinated sequential validators
with bounded memory, exact-key matching and one snapshot. Keep every current
orphan/missing/extra row and cross-reference check. Re-measure with multiple SSTs,
user/VM positions, temporal payloads and an actual repair marker before claiming
the restart bottleneck is resolved. Do not use a larger/global/pinned cache or
skip checks as an unmeasured workaround.

## Verification

- JDK 17 targeted DB/backend tests: 196 passed, including seven new tests.
- JDK 17 chainbase archive suite: 879 passed, zero failures/errors/skips,
  including the additional four-category factory cleanup test.
- JDK 25 archive/VM budget and Manager startup, genesis, fork, publication,
  lifecycle, shutdown, configuration and historical state RPC filters:
  904 chainbase tests and 42 framework tests passed, zero failures/errors/skips.
  The repository `lint checkstyleMain checkstyleTest` tasks also passed; their
  limited source coverage is separate from the direct check below.
- The direct Checkstyle run over seven changed chainbase Java files reports
  34 warnings. The same files at baseline report the same 34 findings after
  normalizing shifted line references. No new findings were introduced; the
  files are **not** claimed to be Checkstyle-clean. Existing style debt was not
  mixed into the performance patch.

New regression coverage includes both bounded length edges, corrupt lengths
below/above bounds (including a 1 MiB value), snapshot stability, actual-length
budget accounting, invalid bounds, deadline/owner/closed guards, full-scrub cache
admission and prevention of current-row point Gets. Existing corruption-matrix,
iterator-count, query-cache isolation and publication-cache tests remain active.
