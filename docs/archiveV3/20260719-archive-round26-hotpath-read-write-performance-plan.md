# Archive round-26 hot-path and temporal schema-6 plan

- Date: 2026-07-19
- Branch: `feat/archive-node`
- Persisted layout: `UNIFIED_V1`, schema 6
- Compatibility premise: no archive database has been deployed; no migration, dual-read, or legacy
  format branch is required
- Status: implemented and regression-tested; production soak and fault injection remain release gates

## 1. Principles

1. Archive-off must remain byte-identical and must not perform archive reads.
2. Archive-on publication must not execute on the transaction/block thread by default.
3. Historical queries must be accurate, snapshot-bound, bounded, and isolated from execution.
4. A logical temporal value has exactly one persisted payload owner.
5. Corruption must fail-stop; a missing or invalid reference must never fall back to live state.
6. All rows for one published block remain in one atomic RocksDB `WriteBatch`.
7. Format compatibility is deliberately out of scope because there is no deployed archive data.

## 2. Work items

| Item | Change | Status |
|---|---|---|
| P1 | Make solidified publication asynchronous by default | Done |
| P2 | Keep synchronous publication as an explicit operator override | Done |
| P3 | Route historical JSON-RPC execution/serialization to bounded low-priority workers | Done |
| P4 | Use zero queue/fail-fast saturation and write retained responses on the Servlet thread | Done |
| P5 | Replace duplicated temporal payloads with authenticated references | Done |
| P6 | Update scrub, marker validation, oracle, corruption, and resource estimates | Done |
| P7 | Run from-zero sync, crash/EIO/ENOSPC matrix, concurrent max-cost query, and soak | Pending release gate |

## 3. Schema-6 temporal format

Payload owners:

- `COMMITMENT` anchor: fixed key-bound locator plus one out-of-line payload.
- `CHANGESET`: fixed key-bound locator plus one out-of-line payload for every changed state.

Authenticated references:

- `HISTORY`: fixed reference to the physical predecessor changeset, or to the anchor for the first
  change.
- `LATEST`: fixed reference to the last changeset.
- A reference digest binds source column family/key, linked txNum, target column family/key, and the
  exact target locator. The target locator independently binds its key, txNum, length, and payload.

Removed state:

- `HISTORY` and `LATEST` no longer have entries in `TEMPORAL_PAYLOAD`.
- The unreachable physical `latest-baseline` representation is removed.
- Schema 5 is not read or migrated. Opening any non-schema-6 manifest fails closed.

## 4. Write and read effects

The conservative temporal mutation admission changes from 10 to 6 mutations per record:

- first change of a key: anchor locator/payload (2), history reference (1), changeset
  locator/payload (2), latest reference (1);
- later changes use fewer physical mutations, while admission intentionally remains conservative.

Each newly persisted value is hashed once for its payload-owner locator and is no longer copied into
history and latest payload rows. Extending an existing chain reduced the measured RocksDB key-read
bound in the regression fixture from 22 to 20. Commit-marker validation reduced from 7 reads to 6 by
reusing the anchor already resolved through the first-history reference.

Latest/tail reads validate only fixed anchor locator metadata; they do not materialize an unrelated
anchor payload before resolving the authenticated latest changeset. Publication still validates the
full immutable anchor payload before extending an existing chain so a damaged chain is not extended.

Historical JSON-RPC execution, bounded serialization, query-lease settlement, and PBFT/Solidity
cursor binding run on the archive worker. The retained response keeps its global byte reservation
but is written to the socket by the Servlet thread, so slow clients cannot occupy archive workers.

## 5. Required invariants and tests

- first history reference targets the key's anchor and the anchor origin txNum equals that history
  txNum;
- every later history reference targets the immediately preceding physical history txNum's
  changeset;
- latest targets the physical history tail changeset;
- target locator replacement, reference replacement, payload deletion, payload mutation, malformed
  row size, unknown key, and orphan payload all fail closed;
- repeated same-key changes in one block build anchor -> changeset -> changeset references and keep
  inclusive-after reads identical to the in-memory oracle;
- tombstone, empty-present value, delete/recreate, restart, marker digest, and snapshot isolation
  remain covered;
- query worker saturation, caller interruption, slow-client release, notification behavior,
  PBFT/Solidity cursor propagation, response settlement, deadline, and lifecycle cleanup remain
  covered.

## 6. Release gates not satisfied by unit tests

This change does not by itself establish production readiness. Before enabling archive in a
production node, run a from-zero private-chain/full-sync oracle, block-publication kill points,
restart reconciliation, ENOSPC/EIO and corrupted-file matrices, concurrent maximum-cost historical
queries, and a long-running heap/native/RSS and compaction-latency soak.
