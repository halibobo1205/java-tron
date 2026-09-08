# Resumable Startup Validation

Date: 2026-09-08

Branch: `feat/archive-node-JDK25-fsync`; patch base: `0330867a26`.

## Problem and Scope

The previous startup progress logger retained counters only in memory. Restarting
an unfinished full scrub repeated its entire validated prefix, even when the
database had not changed. Increasing the archive cache helps native seek cost,
but does not preserve completed validation work.

This patch adds durable progress to the post-reconcile startup full scrub. It
does not change canonical execution, capture, journal/publication transactions,
historical RPC admission or archive record encodings. It does not clear a repair
marker or turn a failed validation into success.

## Durable Evidence

`archive-validation-progress`, inside the unified archive database directory,
contains a bounded, checksummed record with the stage, last validated key, row
count and log position. It is separate from RocksDB metadata and from java-tron's
checkpoint WAL.

Progress is considered after every 1,024 successfully checked scan rows. The first
eligible cursor is persisted immediately; subsequent cursor writes are separated
by at least five seconds. Stage completion always persists immediately. This
avoids thousands of fsyncs per second on fast scans. A stage row is a block for
`temporal-blocks`, a range for `ranges-and-positions`, or a key/value row for other
stages. The write protocol is:

1. Write and fsync the temporary progress file.
2. Atomically replace the committed progress file.
3. Fsync the parent directory.

An incomplete temporary file never authorizes skipping work. Both files have
fixed names; loads reject symlinks, non-regular files, oversized or truncated
records, bad checksums and invalid field shapes. Progress I/O failure logs a
warning and validation continues without advancing durable progress.
An otherwise valid key wider than the 1,024-byte progress-key budget is still
validated, but does not advance the durable cursor at that row. A later bounded
key or stage completion can save progress normally; the file budget is not a new
constraint on archive data.

Before enabling progress, the archive WAL is synced. The progress record binds
to the snapshot's RocksDB sequence number, schema checksum, database IDENTITY,
real directory path, Java runtime version and SHA-256 fingerprints of the
defining code artifacts used by archive validators. Artifact hashing includes
their helpers/codecs and generated protobuf code, not only entry-point classes.
Only one resumable validator can own a database at a time.

Any ordinary archive mutation advances its RocksDB sequence and invalidates
saved evidence, including a same-height rewrite, unwind or reconcile write.
Changing database identity, path, schema, runtime or validator artifacts also
forces a fresh scan. Sequence changes while validating fail closed. Compaction
alone need not invalidate logical progress.

## Stages and Boundaries

All eleven expensive full-scrub stages participate:

- `index-keyspace`
- `ranges-and-positions`
- `temporal-blocks`
- `history-links`
- `changeset-links`
- `latest-links`
- `anchors`
- `latest-domains`
- `history-domains`
- `changeset-domains`
- `payload-owners`

Earlier completed stages are reused. The interrupted stage seeks inclusively to
its last durable key, rechecking the boundary row. Range and history scans also
restore the physical predecessor needed for adjacency/value-chain checks.
Later stages run normally. No iterator, native snapshot or Java object graph is
serialized.

The factory's inexpensive range-chain scan, metadata checks, tail checks and
canonical startup reconciliation still run. Ordinary explicit full validation
through `validateStartup` remains a fresh scan; only the post-reconcile startup
path uses progress. Completing all stages removes the progress file. The
existing service recovery protocol still controls clearing `repair-required`.
A validation failure discards progress and propagates the failure. This includes
failures found by an independent, non-resumable full scan; cleanup failure must
not replace the original validation exception.

## Operator Behavior

- No new resume configuration is required. It applies when startup already
  requires a full scrub. It does not force a full scrub on a healthy fast start.
- The prior cache change defaults the archive cache to 2 GiB of native memory.
  Existing explicit `storage.archive.db.blockCacheBytes` values still win.
- The old binary's log messages are not durable evidence. Its reported height
  cannot be imported. The first run with this implementation must validate from
  the beginning if no matching progress record exists.
- After this version saves progress, restarting the same binary against the
  unchanged database resumes. Logs include `checkpoint saved`, `checkpoint
  loaded`, `validation resumed` and `validation reused` with stage/row/position.
- An interruption repeats the suffix since the last durable checkpoint plus its
  inclusive boundary row, not the entire prefix. For bounded keys and successful
  writes, eligibility is checked at the first 1,024-row boundary after the five-second
  interval. This is not a strict five-second recovery-time bound: one slow block or
  batch must still finish before its progress can be saved.
- A replacement build or JDK can invalidate progress conservatively. Finish an
  ongoing scrub with the same binary when possible. Do not replace application
  artifacts in place while validation is running. Do not modify the database,
  delete its repair marker or disable integrity checks to force reuse.
- No historical data migration, archive deletion or genesis resync is required
  by this patch. This is not a diagnosis that the operator's existing data is sound.

Progress records attest checks on a particular logical data version. They are
not a substitute for periodic fresh integrity scrubs or protection against new
physical bit rot after a prefix was checked. Unread skipped data is not reread;
RocksDB checksums and archive read-time authentication remain enabled. For a new
independent full scrub, stop the node and remove only the optional
`archive-validation-progress` file, or use a fresh explicit validation run.

## Verification

The fixture has 1,120 blocks, 2,240 change records, 1,101 account keys, recurring
hot-key versions and a persisted repair marker. Every full-scrub stage exceeds
1,024 rows so interruption occurs inside a stage, not only between stages.

- Eleven-stage interruption/reopen matrix: earlier stages have zero repeated
  row checks, the interrupted stage repeats only its boundary/suffix, and later
  stages match the complete-scan row counts.
- Separate JVM processes forcibly killed while holding RocksDB open during
  `temporal-blocks`, `history-links` and `payload-owners`. Reopening performs WAL
  recovery and reuses saved progress without a clean native database close.
- A deletion in an already validated changeset prefix advances the database
  version, invalidates old progress and is detected by a fresh production startup
  validation. Repair-required remains set after the failure.
- Unit coverage includes schema/identity/sequence changes, truncated/corrupt/
  oversized files, interrupted temporary-file writes, symlinks, write failures,
  skipped-stage rejection, early-finish rejection, empty databases, oversized
  valid scan keys, checkpoint write throttling and exclusive validator ownership.

Broad configuration/archive/Manager/historical RPC selections passed before the
final checkpoint-I/O throttling adjustment: 1,418 tests on Temurin 17.0.19 and
1,421 on Temurin 25.0.4, with zero failures/errors/skips. The different totals
reflect three checkpoint tests added between those runs, not skipped coverage.
The JDK 17 archive-only follow-up also passed 932 tests. These selections overlap;
their counts must not be added. They are not repository-wide test runs.

After checkpoint throttling and fresh-scan failure cleanup, focused reruns passed:

| Runtime | Selection | Tests | Failures/errors/skips |
| --- | --- | ---: | ---: |
| Temurin 17.0.19 | Recovery tests and UnifiedArchiveBackendTest | 143 | 0/0/0 |
| Temurin 25.0.4 | Above plus UnifiedArchiveDbTest | 243 | 0/0/0 |

The recovery selection contains 13 checkpoint tests, 11 stage-boundary cases and
four restart/failure tests, including actual forced termination in three stages.
Test retries were disabled.
Root lint/checkstyle and reference-comment validation passed. Direct checkstyle
on changed Java files found no new warnings relative to HEAD; existing warnings
were not hidden or broadly refactored.

### Checkpoint I/O Cost

Same stopped synthetic database, macOS arm64, Temurin 25.0.4, RocksDB 9.7.4,
2 GiB archive cache; 16,384 blocks and 262,144 mixed hot/cold change records:

| Mode | Temporal blocks | Full scrub | Seeks |
| --- | ---: | ---: | ---: |
| Fresh validation without progress persistence | 1,637 ms | 9,104 ms | 7,799,883 |
| Initial prototype: fsync every 1,024 rows | 1,841 ms | 22,779 ms | 7,799,883 |
| Final implementation: throttled cursor writes | 1,644 ms | 9,058 ms | 7,799,883 |

The unconditional-fsync prototype was rejected because of its large overhead on
fast scans. All three runs checked the complete dataset, finished at block 16,383
and retained `repairRequired=true`; none reused saved progress. Each had 35 index
cache misses. The final elapsed time is indistinguishable from baseline in these
single-run measurements, not proof of zero checkpoint overhead. OS caches were
not dropped. Synthetic fixtures are not evidence of mainnet-scale completion time.

## Timing Reproduction

The existing startup payload benchmark accepts `payloadMode=validate-resumable`
to measure fresh full validation with checkpoint persistence enabled:

```sh
./gradlew -I docs/archiveV3/benchmarks/startup-payload.gradle \
  :chainbase:archivePayloadBenchmark -PpayloadMode=validate-resumable \
  -PpayloadPath=/tmp/archive-cache-pressure/unified -x generateGitProperties
```

Use a stopped synthetic database, not a directory owned by a running node.
The original `validate` mode remains a non-resumable full-scan baseline.
