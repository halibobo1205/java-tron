# Archive round-24 ThreadLocal, native close, journal codec, and resource review

- Date: 2026-07-18
- Branch: `feat/archive-node`
- Review base: current uncommitted Round 10-23 worktree
- Layout: `UNIFIED_V1` only, schema 5, from-genesis public reads
- Status: CONFIRMED SOURCE/TEST ISSUES FIXED; NATIVE CORRUPTION AND RUNTIME GATES REMAIN

## 1. Objective

Round 24 repeated the adversarial workflow across four coupled boundaries:

1. canonical capture admission, tx-position allocation, and global in-flight accounting;
2. durable journal proof, codec preflight, startup reconstruction, ACK, rollback, and delete;
3. RocksJava ThreadLocal capability installation and ordered native-owner shutdown;
4. historical query isolation from canonical execution caches, locks, and resource ownership.

Two independent post-fix tracks reviewed journal/codec/native-memory behavior and service
close/resource behavior. Findings were reproduced against the current worktree, fixed, and then
reviewed again.

## 2. Required invariants

1. A partially installed production capability cannot remain in a worker ThreadLocal.
2. Native resources close only after all owners above them have closed positively. Unknown JNI
   release outcomes are terminal and retain dependent owners until restart.
3. The RocksDB owner closes through `closeE` when available and remains binary-compatible with the
   x86 RocksDB 5.15 line where only inherited `close` is available.
4. Record, byte, position, journal, and native-read capacity is admitted before the corresponding
   retained object or value is materialized.
5. A journal proof is durable only after complete canonical validation. Startup may replace repeat
   protobuf parsing with proof-bound shape validation only for that proof generation.
6. Proof, token, ACK, block key, block number, position list, record txNum, schema checksum, payload
   length, and payload digest must all bind to the same journal generation.
7. Startup scan peak for prior retained bytes `P`, encoded payload `E`, decoded retained bytes `R`,
   and validation workspace `V` is admitted as `P + max(2E, E + R + V)`. The `2E` term covers the
   Java destination and RocksDB native value during `Get`.
8. Loaded journal delete holds no caller payload copy. Its workspace is the fixed allowance plus
   one Java and one native encoded payload, or `base + 2E`.
9. Close clears capture, proof cache, backlog maps, execution index, and counters only after every
   adapter, final DB owner, and lifecycle close succeeds. A terminal owner failure retains this
   Java evidence while detaching the failed service from global capture.
10. Historical queries cannot populate canonical repositories or block caches, inherit capture
    state, or retain canonical mutation ownership while executing the VM.

## 3. Confirmed findings and fixes

| ID | Severity | Confirmed finding | Fix and direct evidence |
|---|---|---|---|
| R24-01 | Medium | A hostile or allocation-failing `ThreadLocal.set` could install the production DB capability and then throw, leaving later work on the same thread spuriously authorized. | Capability installation now rolls back with `remove` before rethrow. Tests inject a partial-install failure and verify that no permit survives. |
| R24-02 | High | Native DB close behavior differed between RocksDB 9.7 and the x86 5.15 dependency, while ordinary `close` could hide the checked `closeE` result on newer RocksJava. | A startup-resolved method handle prefers `closeE` and falls back to `close`. ARM/Java 17 and AMD64/Java 8 dependency configurations both compile. |
| R24-03 | High | Native close composition could continue into a dependency after a column-family handle, DB, option, filter, cache, or statistics owner returned an unknown release result. | Close now follows explicit dependency barriers. Unknown outcomes use `ArchiveNativeResourceReleaseException`, become sticky, and stop lower-owner release. Fault-injection tests cover every layer and repeated close. |
| R24-04 | Medium | Journal decode accepted loose boolean/state encodings and did not fully prove record-position order and binding before object construction. | Codec preflight accepts only exact encodings, validates ordered positions and txNum bindings, and reserves temporary decode bytes before construction. |
| R24-05 | Medium | The hard record limit covered persisted backlog but not unique records still retained by the active capture engine. | The first unique changeset key reserves the global active-record count before constructing its record, rolls back on failure, and does not double-charge same-key merges. |
| R24-06 | Medium | Re-entering `beginSystemTx` or `beginUserTx` could reserve position bytes or advance the execution allocator before the active-context error was detected. | The context precondition now runs before reservation and allocation. Failure-path tests prove no allocator or resource side effect. |
| R24-07 | Low | The oldest-in-flight metric read a mutable `TreeMap` outside its owning monitor. | A volatile scalar is maintained under `backlogMonitor` on load, append, publish, unwind, rollback, and successful close; metric reads no longer touch the map. |
| R24-08 | Medium | Successful close retained large Java backlog/index state, while a failed final owner close could clear active capture and proof-cache evidence too early. | Successful close clears all local maps/counters/index state. Failure retains them; the in-flight proof cache clears only through `ownerCloseSucceeded` after the final DB owner and lifecycle close complete. |
| R24-09 | High | A pre-canonical journal proof could be decoded as a current proof, allowing startup to skip canonical protobuf parsing without a durable attestation that parsing had ever succeeded. | Proof encoding is v2 with a new canonical-validation digest domain. v1 is rejected, including a true v1 digest placed in a v2 envelope. `putBlock` performs full validation before any proof or row write. |
| R24-10 | Medium | Startup proof validation reparsed protobuf object graphs outside the byte budget, while a simple per-record estimate missed the largest later key/string workspace. | Canonical codecs expose proof-bound validation that performs null/shape checks without parsing. Codec preflight reserves final retained bytes plus the maximum validation workspace across all records, including dynamic-property key copy and UTF-16 conversion. |
| R24-11 | Medium | Journal scan reserved one encoded payload even though RocksDB `Get` temporarily owns a native value while the Java destination is live. | Scan now rejects unless both `2E` native-read peak and `E + R + V` decode/validation peak fit after earlier retained blocks. Exact-bound tests exercise both sides. |
| R24-12 | Medium | An empty-buffer `containsKey` probe still caused RocksDB to materialize the native value, so an orphan oversized payload could allocate before proof admission. | Payload existence probes were removed. Loaded/direct deletes first inspect bounded lifecycle rows; direct delete requires an already validated proof and never probes an unqualified payload. No-lifecycle idempotent delete leaves any orphan payload for startup validation. |
| R24-13 | Medium | Loaded delete retained a caller journal payload while compare-and-delete allocated a second Java payload and a native value. | The store passes only the validated proof and lifecycle rows. The DB rereads exactly one payload under the journal mutation lock, verifies its digest, and deletes the three rows in one forced-sync batch. |
| R24-14 | Low | The verified-delete API could allocate from an arbitrary verifier length without an explicit caller admission bound. | The API now requires `maxPayloadBytes` and rejects the proof length before any lifecycle or payload read. A DB-level test proves all three rows remain unchanged on rejection. |
| R24-15 | Low | Foreign-schema journal proof was rejected only after the encoded payload had been read. | Block number and current schema are checked from the bounded proof before payload allocation; the range schema is still rechecked after decode. |
| R24-16 | Low | Archive-enabled store hooks repeatedly allocated `Optional` wrappers for the same execution ThreadLocal state. | Capture uses allocation-free `hasCurrent`/`currentOrNull` access. The existing optional API remains for non-hot callers and tests. |
| R24-17 | Test | A concurrency regression signaled only that its worker thread started, then used a one-second spin to infer lease acquisition. Full-suite scheduling could miss that window. | The test now waits up to five seconds for the observable coordinator lease and sleeps between samples. The production barrier behavior is unchanged. |
| R24-18 | Low | The verified journal APIs sampled a stateful verifier's expected payload length once for admission and again at allocation, leaving a time-of-check/time-of-use gap. | Admission now freezes the single sampled length as an `int` and passes it to the only payload allocation. Stateful-verifier tests prove rejection and success each read the length exactly once. |
| R24-19 | Low | The ACK API verified the journal payload without an explicit caller-provided maximum, unlike the repaired delete API. | ACK now requires `maxPayloadBytes`, rejects an oversized proof before lifecycle or payload reads, and shares the same frozen-length admission path as verified delete. |

## 4. Journal proof and resource result

The write path performs complete structural, key-policy, and canonical value validation before
creating the v2 proof. A malformed or non-canonical value leaves no payload, token, or ACK row. The
proof binds key, payload length, payload digest, block identity, generation nonce, and schema.

Startup first decodes the bounded proof, rejects the proof version, block identity, schema, and
declared length, then admits the Java/native read peak. Only then does it read and hash the payload,
decode its structure, bind positions and records, check the ACK, and apply proof-bound codec
validation. This keeps protobuf object graphs out of startup reconstruction while preserving a
durable reason that full parsing previously succeeded.

Delete accepts only a proof already produced by a successful write or startup scan. The lifecycle
rows are re-read, the DB rereads and hashes one payload under the per-journal mutation lock, and the
forced-sync batch removes payload, token, and ACK together. A changed token, ACK, length, digest, or
generation fails without deleting any row.

## 5. Query and execution interference result

No source-level toxic-cache path was confirmed:

- historical VM calls construct a per-request `ArchiveRepositoryAdapter`; child writes remain in
  its in-memory copy-on-write overlay;
- the adapter's block-hash memo belongs to that request and cannot reach canonical execution;
- Unified historical snapshots and journal/maintenance reads use cacheless RocksDB read options;
- selector reads use wallet APIs that explicitly bypass canonical caches;
- query ThreadLocals are attached only for the request scope and internal canonical work suspends
  them where required;
- archive capture uses a separate canonical execution context and is detached during terminal
  close;
- query admission occurs before snapshot ownership, and the canonical mutation lease is released
  after snapshot capture and checked again before response settlement.

The hot-path change in this round removes several `Optional` allocations per captured store write.
Archive-off still exits at the holder before previous-state reads, normalization, hashing, journal
work, or record accounting. Archive-on still deliberately performs full canonical validation at
journal commit; this is a correctness defense and remains a benchmark/soak item rather than being
replaced with an unproven trust flag.

## 6. Verification evidence

- Complete chainbase suite: 855 tests, 0 skipped, 0 failures, 0 errors.
- `UnifiedArchiveBackendTest`: 104 tests green.
- `DefaultArchiveServiceTest`: 127 tests green.
- `UnifiedArchiveDbTest`: 77 tests green.
- Focused codec, validator, capture, execution-context, proof-domain, close-failure, journal-delete,
  and concurrency regressions: green.
- ARM/aarch64, Java 17, RocksDB 9.7 compile and tests: green.
- AMD64 dependency configuration, Java 8, RocksDB 5.15 compile: green.
- Repository main/test Checkstyle and `git diff --check`: green.
- Service close/resource reviewer found no remaining confirmed issue after the fixes.
- Journal/codec reviewer found the native probe, `2E`, verifier-length TOCTOU, and ACK-cap omissions;
  all were reproduced and fixed. Its final reverse review found no remaining concrete issue.

## 7. Residual native and release gates

1. RocksJava 5.15 has no API that reports an exact value length without allowing RocksDB to
   materialize that value. A coherently oversized value inserted by offline mutation or a damaged
   but still readable SST may therefore consume native memory before Java receives the mismatch.
   Legitimate production writers are permit-bound and size-admitted; this residual requires real
   corrupted-SST/offline-write fault injection and an external memory limit/supervisor.
2. The same limitation applies to a coherently oversized iterator key on the x86 5.15 line. Normal
   keys are fixed/prefix-bounded, but arbitrary damaged native records cannot be proven bounded by
   Java before RocksDB exposes them.
3. Unknown iterator, ReadOptions, snapshot, handle, DB, option, filter, cache, or statistics close
   outcomes intentionally pin dependent ownership until restart. JNI fault injection must verify
   RSS/SST behavior and alarms.
4. Shared filesystem queues, page cache, compaction, WAL, and JNI scheduling can still couple
   historical-query latency to publication and canonical execution even without Java cache or lock
   poisoning.
5. Full canonical validation at journal commit must be benchmarked at maximum real block state
   churn before any provenance-attestation optimization is considered.
6. From-zero sync, external state/root oracle comparison, crash/restart kill points, ENOSPC/EIO,
   WAL/MANIFEST/SST corruption, maximum-cost concurrent queries, heap/native-memory soak, and restart
   scrub SLO remain production release gates.

No production-readiness claim is made by this source/test round alone.
