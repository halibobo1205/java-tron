# Archive Startup Scan Performance

## Scope

Branch: `feat/archive-node-JDK25-fsync`, based on `8eb2bbb20f`.

A mainnet restart stack showed `UnifiedArchiveReadView.getExact` inside
`UnifiedArchiveTxNumIndex.validateRangeCoverage`, reached through identity floor
inspection. A single stack sample cannot establish an ETA or prove a deadlock.

Two independently verifiable costs were present:

1. Identity validation constructed and closed an index, scanning all committed
   ranges. Factory construction then reopened the same DB and repeated that scan.
2. The range iterator already held each row, but validation issued a separate
   RocksDB Get for every row with scan cache filling disabled.

## Changes

- Open the production DB/index once inside the authenticated identity callback.
  Verify the identity floor using those same adapters and retain the handle.
- Preserve shared identity locks through payload validation. Close the opened
  service if releasing an identity lock fails. Persistent corruption is still
  marked on the same DB handle that detected it.
- Read fixed-size range values using `RocksIterator.value(byte[])`. The native
  result reports the actual length; short and oversized rows still fail closed.
  This is not the unbounded `iterator.value()` API. The helper caps allocation
  at 64 KiB and preserves query budget, deadline and owner-thread checks.
- Keep all range adjacency, schema, shape, floor, cursor and position checks.
  First/last range reads also reuse their current iterator row.
- Log startup scan stages, completed row counts, current position and elapsed
  milliseconds. Long scans sample time every 1,024 rows and report after at
  least five seconds. Completion is logged only after successful validation.

No database format, fsync setting, repair policy or canonical state-write path
is changed. No new configuration is required.

## Verification

JDK 17, local arm64:

- `:chainbase:test --tests 'org.tron.core.archive.*'`: 871 passed.
- Manager archive startup/genesis/lifecycle/publication/shutdown/fork tests,
  default archive configuration tests and historical state RPC integration:
  40 passed.
- `lint checkstyleMain checkstyleTest -x generateGitProperties`: passed.
- New regressions cover one DB open on authenticated restart, identity floor
  mismatch and handle cleanup, no point Get for middle ranges, corrupt fixed
  row lengths, snapshot consistency and pre-read query budget enforcement.
- The publication point-read regression changes from 19 to 17 reads because
  range endpoints now use iterator values; its exact-count assertion remains.

JDK 25.0.4 rerun with archive, Manager archive, default configuration and state
integration test filters: 896 chainbase tests and 42 framework tests passed,
with zero failures, errors or skips. The wider filters also include historical
VM budget tests. Compilation reports existing JDK deprecation/native-access
warnings; there are no test failures.

## Measurement Boundaries

A temporary local fixture contains 1,160,000 contiguous block ranges and two
system positions per block, flushed to SST files using the production DB options.
It has no mainnet account/contract history or in-flight backlog.

On this fixture, 10,000-range passes took 34,698 ms and 36,706 ms with point Gets,
versus 26 ms and 14 ms with bounded iterator values. Each pass decodes the
same rows and checks block ordering, txNum adjacency and schema. A full old-path
pass was stopped after more than three minutes; it did not produce a full-run
timing. These are local measurements, not a mainnet restart-time guarantee.

The new bounded-iterator pass over all 1,160,000 ranges took 818 ms. A separate
real index construction, including DB open, tail checks and mandatory full
range validation, took 934 ms (866 ms logged for its range-validation stage).
These runs used a warmed filesystem cache; neither result includes temporal
full scrub, canonical recovery, journal reconciliation or node service startup.

## Remaining Cost

Mandatory range coverage validation remains linear in archived block count.
An existing repair-required marker or explicit full-scrub configuration still
requires the heavier post-reconcile index and temporal integrity scans. This
change does not skip, clear or weaken those checks. Their stage logs distinguish
that work from the mandatory range scan; a mainnet restart must still be timed
end to end before declaring the operational restart problem fully resolved.
