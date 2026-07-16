# UNIFIED_V1 wiring and release requirements

## Decision

Archive is a greenfield feature. No archive-aware binary or archive dataset has been released, so
the product has exactly one persistent layout: `UNIFIED_V1`.

- `storage.archive.enable` remains `false` by default.
- Persistent archive storage is always one `UnifiedArchiveDb`.
- The user-facing layout selector and legacy-adoption mode do not exist.
- There is no migration, bridge release, downgrade path, or alternate persistent backend.
- An existing canonical database may only reopen its matching ACTIVE Unified archive identity.

The identity record still stores `layout=UNIFIED_V1` as an on-disk format discriminator. It is not a
runtime selector.

## Physical model

One RocksDB owns these exact column families: `meta`, `inflight`, `index`, `latest`, `history`,
`changeset`, `block-marker`, and `commitment`.

Block publication writes index rows, temporal rows, the block marker, published cursor, and journal
deletion in one RocksDB `WriteBatch`. Readers bind index and temporal access to one shared snapshot.

## Required invariants

1. Archive-off does not add canonical database reads or writes.
2. A published block is visible entirely or not visible at all.
3. A historical reader never combines rows from different RocksDB sequence numbers.
4. Journal payload, token, acknowledgement, index range, positions and temporal rows agree.
5. Startup accepts a complete valid state or fails closed; it never repairs ambiguous corruption.
6. Missing or wrong archive mounts cannot initialize beside a non-empty canonical database.
7. Unknown column families, keys, schema checksums and identity layouts fail before writes.
8. Unsupported historical VM state reaches the top-level RPC and cannot become a failed child call
   followed by a successful parent result.

## Independent oracle

Correctness is checked against models that do not use Unified key encodings or column-family
routing:

- `DefaultArchiveServiceIncrementalDifferentialTest`: randomized block, publish and unwind model for
  service-level in-flight state.
- `UnifiedArchiveTemporalStoreOracleTest`: in-memory prev-value model versus Unified latest/history/
  changeset behavior across create, update, delete, recreate, mid-chain baseline, snapshot and
  unwind.
- `UnifiedArchiveBackendTest.publishedSequenceMatchesIndependentTxNumAndStateOracle`: explicit
  txNum/range/position and historical-value expectations across multiple published blocks.
- Historical VM, JSON-RPC, Manager switch-fork and domain codec tests provide independent semantic
  assertions above the storage layer.

An alternate production storage implementation is not an oracle. Shared semantic bugs must be
anchored by explicitly calculated expected values.

## Fault matrix

Automated tests cover:

- journal and publish failure before write;
- exception after a forced-sync publish has reached RocksDB;
- rollback batch failure;
- restart with journal payload or WAL-only acknowledgement;
- malformed, missing, mismatched or orphan journal lifecycle rows;
- missing, malformed, mismatched and unknown INDEX/LATEST/HISTORY/CHANGESET/BLOCK_MARKER rows;
- missing or wrong manifest, schema mismatch and unexpected column families;
- concurrent publication while an older shared snapshot remains open;
- real Manager switch-fork and switch-back recovery over persistent Unified storage.

Every case must produce complete recovery or explicit fail-stop/repair-required. Partial
publication and silent fallback to live state are forbidden.

## Remaining activation gates

Unit-level batch and corruption injection do not replace:

1. Process-level SIGKILL around journal acknowledgement and atomic publication.
2. Real ENOSPC during WAL append, flush and compaction.
3. WAL rotation and column-family flush/compaction restart drills.
4. From-genesis private-chain sync with archive enabled.
5. Historical RPC comparison against independently captured state snapshots.
6. Finality-stall/catch-up test and at least 72 hours of mixed-query soak.
7. Archive RocksDB stall metrics, Bloom filters and measured cache/compaction sizing.

## Definition of done

- [x] Unified is the only persistent production path.
- [x] Layout/adoption configuration and alternate persistent implementations are removed.
- [x] Independent temporal and txNum/state oracle tests are green.
- [x] Unit-level atomicity, restart and column-family corruption matrices are green.
- [x] Persistent Manager switch-fork recovery is green.
- [ ] Real process/disk fault matrix is green.
- [ ] From-zero sync, performance and 72-hour soak gates are green.

Code may land with `archive.enable=false` while the final two operational gates are pending.
Archive-on must not be described as production-ready until they pass.
