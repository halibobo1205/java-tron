package org.tron.core.archive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteOptions;

/** RocksDB-backed durable journal for not-yet-solidified archive blocks. */
public final class RocksDbArchiveInFlightStore implements ArchiveInFlightStore {

  static {
    RocksDB.loadLibrary();
  }

  private final Options options;
  private final RocksDB db;

  public RocksDbArchiveInFlightStore(String path) {
    this.options = new Options().setCreateIfMissing(true);
    RocksDB opened = null;
    try {
      opened = RocksDB.open(options, path);
      validateOrInstallManifest(opened);
      this.db = opened;
    } catch (RocksDBException e) {
      closeQuietly(opened);
      options.close();
      throw new ArchiveException("failed to open archive in-flight store at " + path, e);
    } catch (RuntimeException e) {
      closeQuietly(opened);
      options.close();
      throw e;
    }
  }

  private static void closeQuietly(RocksDB db) {
    if (db != null) {
      db.close();
    }
  }

  private static void validateOrInstallManifest(RocksDB db) throws RocksDBException {
    byte[] manifest = db.get(ArchiveInFlightCodec.manifestKey());
    if (manifest != null) {
      if (!ArchiveInFlightCodec.manifestMatches(manifest)) {
        throw new ArchiveException("archive in-flight manifest mismatch");
      }
      validateCurrentKeyspace(db);
      return;
    }
    if (!isEmpty(db)) {
      throw new ArchiveException("archive in-flight store is non-empty but missing manifest");
    }
    try (WriteOptions writeOptions = ArchiveRocksDbWriteOptions.create()) {
      db.put(writeOptions, ArchiveInFlightCodec.manifestKey(),
          ArchiveInFlightCodec.manifestValue());
    }
  }

  private static boolean isEmpty(RocksDB db) {
    try (RocksIterator it = db.newIterator()) {
      it.seekToFirst();
      return !it.isValid();
    }
  }

  private static void validateCurrentKeyspace(RocksDB db) {
    byte[] blockPrefix = ArchiveInFlightCodec.blockPrefix();
    try (RocksIterator it = db.newIterator()) {
      it.seekToFirst();
      while (it.isValid()) {
        byte[] key = it.key();
        if (Arrays.equals(key, ArchiveInFlightCodec.manifestKey())) {
          it.next();
          continue;
        }
        if (!ArchiveInFlightCodec.startsWith(key, blockPrefix)) {
          throw new ArchiveException("archive in-flight store has unknown key prefix");
        }
        ArchiveInFlightCodec.blockNumOfKey(key);
        it.next();
      }
    }
  }

  @Override
  public List<ArchiveInFlightBlock> loadBlocks() {
    List<ArchiveInFlightBlock> blocks = new ArrayList<>();
    byte[] prefix = ArchiveInFlightCodec.blockPrefix();
    try (RocksIterator it = db.newIterator()) {
      it.seek(prefix);
      while (it.isValid() && ArchiveInFlightCodec.startsWith(it.key(), prefix)) {
        long blockNum = ArchiveInFlightCodec.blockNumOfKey(it.key());
        ArchiveInFlightBlock block = ArchiveInFlightCodec.decodeBlock(it.value());
        if (block.getRange().getBlockNum() != blockNum) {
          throw new ArchiveException("archive in-flight block key/value mismatch for block "
              + blockNum);
        }
        blocks.add(block);
        it.next();
      }
      return blocks;
    }
  }

  @Override
  public void putBlock(ArchiveInFlightBlock block) {
    try (WriteOptions writeOptions = ArchiveRocksDbWriteOptions.create()) {
      db.put(writeOptions, ArchiveInFlightCodec.blockKey(block.getRange().getBlockNum()),
          ArchiveInFlightCodec.encodeBlock(block));
    } catch (RocksDBException e) {
      throw new ArchiveException("archive in-flight put failed", e);
    }
  }

  @Override
  public void deleteBlock(long blockNum) {
    try (WriteOptions writeOptions = ArchiveRocksDbWriteOptions.create()) {
      db.delete(writeOptions, ArchiveInFlightCodec.blockKey(blockNum));
    } catch (RocksDBException e) {
      throw new ArchiveException("archive in-flight delete failed", e);
    }
  }

  @Override
  public void close() {
    db.close();
    options.close();
  }
}
