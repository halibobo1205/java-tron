package org.tron.core.archive.txnum;

import java.util.Optional;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.tron.core.archive.ArchiveException;

/**
 * RocksDB-backed persistence for committed block ranges + the committed-txNum cursor, so the
 * block-to-txNum mapping survives restart (the historical-read resolver needs it for old blocks).
 * Commit/unwind write the range and cursor in one atomic batch.
 */
public final class RocksDbArchiveBlockRangeStore implements AutoCloseable {

  static {
    RocksDB.loadLibrary();
  }

  private final Options options;
  private final RocksDB db;

  public RocksDbArchiveBlockRangeStore(String path) {
    this.options = new Options().setCreateIfMissing(true);
    try {
      this.db = RocksDB.open(options, path);
    } catch (RocksDBException e) {
      options.close();
      throw new ArchiveException("failed to open archive block-range store at " + path, e);
    }
  }

  /** Atomically persist a committed block's range and advance the persisted cursor. */
  public void commitRange(ArchiveBlockRange range, long committedNextTxNum) {
    try (WriteBatch batch = new WriteBatch(); WriteOptions writeOptions = new WriteOptions()) {
      batch.put(ArchiveBlockRangeCodec.rangeKey(range.getBlockNum()),
          ArchiveBlockRangeCodec.encodeRange(range));
      batch.put(ArchiveBlockRangeCodec.CURSOR_KEY,
          ArchiveBlockRangeCodec.encodeCursor(committedNextTxNum));
      db.write(writeOptions, batch);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive block-range commit failed", e);
    }
  }

  /** Atomically drop a reverted block's range and rewind the persisted cursor. */
  public void unwindRange(long blockNum, long committedNextTxNum) {
    try (WriteBatch batch = new WriteBatch(); WriteOptions writeOptions = new WriteOptions()) {
      batch.delete(ArchiveBlockRangeCodec.rangeKey(blockNum));
      batch.put(ArchiveBlockRangeCodec.CURSOR_KEY,
          ArchiveBlockRangeCodec.encodeCursor(committedNextTxNum));
      db.write(writeOptions, batch);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive block-range unwind failed", e);
    }
  }

  public Optional<ArchiveBlockRange> getRange(long blockNum) {
    try {
      byte[] value = db.get(ArchiveBlockRangeCodec.rangeKey(blockNum));
      return (value == null) ? Optional.empty()
          : Optional.of(ArchiveBlockRangeCodec.decodeRange(value));
    } catch (RocksDBException e) {
      throw new ArchiveException("archive block-range read failed", e);
    }
  }

  /** The persisted committed-txNum cursor, or 0 if never written (fresh store). */
  public long getCursor() {
    try {
      byte[] value = db.get(ArchiveBlockRangeCodec.CURSOR_KEY);
      return (value == null) ? 0L : ArchiveBlockRangeCodec.decodeCursor(value);
    } catch (RocksDBException e) {
      throw new ArchiveException("archive block-range cursor read failed", e);
    }
  }

  @Override
  public void close() {
    db.close();
    options.close();
  }
}
