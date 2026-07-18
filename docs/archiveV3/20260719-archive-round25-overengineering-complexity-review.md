# Archive round-25 overengineering review: adversarial adjudication

- Date: 2026-07-19
- Branch: `feat/archive-node`
- Baseline head: `8e1602cc1cae`
- Layout: `UNIFIED_V1` only, schema 5, from-genesis public reads
- Status: PARTIALLY IMPLEMENTED; NO FORMAT CHANGE; NO PRODUCTION-READINESS CLAIM

## 1. Objective and review rule

Round 25 challenged the archive implementation for accidental complexity. This follow-up reviewed
the review itself before changing code. Every proposed simplification had to pass four questions:

1. Is the allegedly dead or duplicate path absent from the complete repository caller graph?
2. Does it duplicate responsibility at the same failure boundary, rather than defend a different
   boundary?
3. Can the replacement invariant be stated and tested?
4. Does the change preserve upstream public behavior and the schema-5 recovery checks?

The original review was directionally useful, but several findings combined unrelated concerns or
treated deliberate test/recovery seams as production generality. Applying it literally would have
removed startup corruption checks, weakened the fault matrix, changed JSON-RPC validation
precedence, and broken an upstream VM trace API.

## 2. Executive conclusion

The safe simplification set is smaller than the original document claimed.

Implemented now:

1. remove the second in-memory txNum allocator replay from unified publication and validate the
   immutable journal directly;
2. enforce at the Manager boundary that an enabled archive returns a durable journal token;
3. inject one historical repository into `VMActuator` and obtain its VM properties from that same
   repository;
4. remove unreachable getter hash forwarding while preserving the public `Object` parameter and
   its tested error precedence;
5. remove dead aliases, wrappers, capture helpers, the future coverage DTO, and branch-only trace
   residue;
6. preserve the upstream `ProgramTrace.addOp` return contract.

Not implemented:

- no archive schema, identity checksum, or persisted row was removed;
- no startup scrub, temporal coverage check, maintenance corruption hook, resource gate, or config
  rejection was weakened;
- `DefaultArchiveService` was not converted to enabled-only, because its in-memory construction is
  the cross-module archive oracle rather than an unused production state;
- registry/catalog and the lifecycle protocol were not collapsed across distinct ownership and
  failure boundaries.

## 3. Finding-by-finding adjudication

### R25-01 - REVISED: interface defaults are a latent hazard, not a current High bug

The current production factory is closed over `DefaultArchiveService` and `NoopArchiveService`.
Making interface methods abstract would add no-op boilerplate to the latter and would not prevent a
future implementation from supplying an explicit no-op. It therefore does not enforce durability.

The useful invariant is at the caller that orders canonical state and the archive journal:

- when `archiveService.isEnabled()` is true, `commitBlockJournaled` must return a non-null durable
  token;
- a violation is converted to the existing archive fail-stop `TronError` path;
- archive-off may continue returning null.

This postcondition is now enforced by `Manager.journalArchiveBlockOnlyOrFailStop`, including the
genesis path. Critical default methods remain for compatibility with the disabled implementation.

### R25-02 - PARTIALLY ACCEPTED: remove duplicate allocator state, retain recovery rows

The persistent unified index replayed every already-validated journal position through another
`InMemoryArchiveTxNumIndex` before writing it. That second mutable allocator and its success/failure
reset state were redundant.

Unified publication now validates the immutable block directly against:

- range shape, schema checksum, source, block number and block hash;
- exact prepare/user/finalize order and position count;
- contiguous txNums and user indexes;
- valid and unique user txIds, both within the block and against committed history;
- the persisted published cursor and previous block range.

The index then stages the same schema-5 range, position, txId, first-block and cursor rows in the
same atomic publication batch. There is no mutable publication state to reset after failure.

The original proposal to delete position and txId rows was not accepted in this round. Position
rows currently support full startup scrub, journal/index equivalence, transaction selectors, and
temporal txNum coverage validation. TxId rows and transaction-reader APIs have no public RPC caller
today, but deleting them is a schema decision and must be evaluated together with those startup
checks. It must not be smuggled into a local refactor.

### R25-03 - DEFERRED: disabled production wiring and in-memory oracle are different concerns

Production does use `NoopArchiveService` when archive is disabled. The boolean-bearing
`DefaultArchiveService` constructors are nevertheless used extensively to build in-memory archive
oracles across chainbase and framework tests. Removing them now would move, not remove, substantial
state setup and would make cross-module tests depend on RocksDB and identity I/O.

An enabled-only production constructor may still be worthwhile, but it should be introduced with a
shared test fixture and constructor-call migration. It is not a correctness fix and is not part of
this change.

### R25-04 - REVISED: closed integration is preferable to another false abstraction

The `DefaultArchiveService` cast in genesis coverage validation exposes closed production wiring,
but adding a generic service method used by only that concrete implementation would merely move
the cast behind a wider interface. The unanchored factory path is also an intentional test seam;
production always uses identity anchoring.

Keep the current closed wiring until either a second enabled implementation exists or genesis
validation can move behind a smaller, independently useful capability. Do not claim runtime
pluggability in the meantime.

### R25-05 - REJECTED AS STATED: registry and catalog are related, not duplicate objects

The registry classifies every database name, including exclusions and capture mode. The catalog
maps captured domains to codecs and read policies. Their overlap is checked deliberately and their
checksums detect different kinds of drift. A single source definition might reduce metadata
repetition, but it must preserve those separate views and deterministic checksum ownership.

No schema metadata was changed in this round.

### R25-06 - REJECTED: fixed config is a fail-closed compatibility contract

The authority document freezes key names such as `storage.archive.debug.enable` and preserves the
four-value root-policy taxonomy. Configuration binding rejects unsupported values and the factory
revalidates programmatic construction that bypasses binding. These checks defend different entry
paths.

Removing reserved keys or one validation layer would turn an explicit rejection into a silently
ignored or partially accepted configuration. No config surface was removed.

### R25-07 - PARTIALLY ACCEPTED: one VM state view, unchanged JSON binder contract

`ArchiveRepositoryAdapter` already owns the historical `VmDynamicProperties`. `VMActuator` now
accepts only the injected repository and obtains properties from it, preventing independently
mutable repository/protocol views.

The original recommendation to change getter block parameters from `Object` to `String` was
rejected. Tests intentionally require object/map forms to fail before wallet lookup with the exact
JSON-RPC invalid-params behavior. The public signatures remain `Object`; only the internal
always-null requested-hash carrier was removed. Hash-capable adapter overloads remain because they
directly test canonical mismatch behavior and may be used below the JSON binding layer.

### R25-08 - DEFERRED: maintenance APIs are bounded fault-injection and repair seams

Maintenance batches and proof-bounded durable mutation helpers are used to construct corruption
states for the disk-failure matrix. They are production-sealed and provide bounded copying,
duplicate-key rejection, and atomic repair-marker behavior. Moving all of this into test sources
would either duplicate the physical encoding or weaken realistic RocksDB fault tests.

Visibility can be tightened in a dedicated API audit. Wholesale removal is not justified.

### R25-09 - DEFERRED: admission gates are distinct; DTO cleanup is optional

Pre-journal, persisted-state, and final-batch admission protect different retained-memory windows.
The formulas can be shared further, but replacing local value objects and constructor overloads
with builders does not by itself reduce runtime states. No resource gate was collapsed.

### R25-10 - PARTIALLY ACCEPTED: remove branch residue, preserve upstream and schema contracts

Removed:

- future-only `ArchiveCoverage`;
- unused query aliases and coordinator aliases;
- dead Manager commit wrappers;
- branch-only `Op.energyCost` and the return-valued `Program.saveOpTrace` chain;
- unused known-previous capture helpers.

Preserved:

- the upstream `ProgramTrace.addOp` return value;
- identity inspection/test construction used by identity protocol tests;
- metrics fault injection used to isolate callback failures;
- semantic codec types that document key domains;
- authority-frozen root/history policy values and checksum taxonomy.

Removing a method merely because the repository does not consume its return value is unsafe when
the method predates the branch or is public. The upstream comparison corrected this part of the
original review.

### R25-11 - ACCEPTED NARROWLY: delete only unused capture helpers

`putWithKnownArchivePrevious` and `deleteWithKnownArchivePrevious` had no caller and were removed.
Store-specific capture hooks remain separate because their previous-read timing, ABI/value
transformation and key admission differ on consensus-adjacent paths.

### R25-12 - ACCEPTED NARROWLY: delete dead validation, preserve ordered routing

The unused `ArchiveJsonRpcStateAdapter.validateArchiveAvailable` method was removed. Broader routing
centralization was deferred because malformed-selector, size, availability, canonical-mismatch and
query-limit errors have tested precedence across RPC, adapter and executor boundaries.

## 4. Complexity intentionally retained

The following mechanisms survived adversarial review because they protect distinct states or crash
windows:

1. journal-before-canonical, acknowledgement, rollback and startup reconciliation;
2. root/payload identity anchoring;
3. bounded codec preflight and proof-bound journal deletion;
4. native close barriers and sticky unknown-release state;
5. query admission, request scope, transport scope and snapshot permits;
6. cacheless historical repository/VM routing;
7. per-transaction capture and immutable in-flight positions;
8. full startup index/temporal scrubs, corruption taxonomy and repair marker;
9. all three publication resource-admission points;
10. package-owned maintenance hooks used by realistic disk corruption tests.

Archive-off still performs no archive previous-value reads and opens no archive database. Cheap
no-op lifecycle calls and the capture-holder check are not candidates for extra hot-path branching
without benchmark evidence.

## 5. Persisted-format decision

This round deliberately keeps `UNIFIED_V1` schema 5 byte-compatible. The simplification removes an
in-memory publication replay but stages exactly the existing index and temporal mutations.

A future schema-removal proposal must provide replacements for all of these consumers before
deleting position or txId rows:

1. startup full scrub and key/value consistency;
2. journal-to-published-index equivalence;
3. temporal txNum coverage validation;
4. block/transaction selector behavior;
5. corruption localization and repair diagnostics.

That proposal should include measured write-amplification and storage savings. "No current public
RPC caller" is not enough evidence to remove recovery metadata.

## 6. Verification

Verification completed on 2026-07-19:

- `:chainbase:compileJava :actuator:compileJava :framework:compileJava`: passed;
- `:chainbase:test` for all `org.tron.core.archive.*` tests plus the archive-off store hook suite:
  passed;
- `:actuator:test` for `VMActuator`, archive repository, contract-state capture and storage capture:
  passed;
- `:framework:test` for archive query budgets, Manager lifecycle/genesis/fork/shutdown, historical
  VM/dynamic properties, state reads, JSON-RPC routing/limits/transport and ordinary VM trace:
  passed;
- `:framework:checkstyleMain :framework:checkstyleTest`: passed (`chainbase` and `actuator` do not
  apply the Checkstyle plugin);
- `git diff --check`: passed.

New regressions invoke unified index publication directly to reject duplicate user txIds within one
immutable journal block, and reject a null durable token from an enabled service at the Manager
boundary.

This source/refactor review does not replace from-zero sync, oracle comparison, crash/restart kill
points, ENOSPC/EIO and file-corruption matrices, concurrent maximum-cost queries, or long-running
heap/native/RSS soak.
