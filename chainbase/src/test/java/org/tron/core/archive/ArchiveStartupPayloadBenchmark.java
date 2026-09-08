package org.tron.core.archive;

import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.FlushOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.Statistics;
import org.rocksdb.TickerType;
import org.tron.common.utils.ReflectUtils;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveSchemaChecksum;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.temporal.UnifiedArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.archive.txnum.UnifiedArchiveTxNumIndex;
import org.tron.core.archive.unified.UnifiedArchiveDb;
import org.tron.core.archive.unified.UnifiedArchiveTestMaintenance;
import org.tron.protos.Protocol.Account;

/** Opt-in, synthetic payload benchmark. Not a mainnet workload or a normal unit test. */
public final class ArchiveStartupPayloadBenchmark {

  private static final int KEY_COUNT = 32768;
  private static final int CHANGES_PER_BLOCK = 16;

  private ArchiveStartupPayloadBenchmark() {
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3) {
      throw new IllegalArgumentException("generate|validate DB_PATH [BLOCKS]");
    }
    Path path = Paths.get(args[1]);
    ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
    byte[] checksum = ArchiveSchemaChecksum.of(new DefaultArchiveDomainRegistry(), catalog);
    if ("generate".equals(args[0])) {
      int blocks = args.length == 3 ? Integer.parseInt(args[2]) : 16384;
      if (blocks <= 0) {
        throw new IllegalArgumentException("blocks must be positive");
      }
      generate(path, catalog, checksum, blocks);
    } else if ("validate".equals(args[0])) {
      validate(path, catalog, checksum);
    } else {
      throw new IllegalArgumentException("unknown benchmark mode: " + args[0]);
    }
  }

  private static void generate(Path path, ArchiveDomainCatalog catalog,
      byte[] checksum, int blocks) throws Exception {
    Files.createDirectories(path.toAbsolutePath().getParent());
    try (UnifiedArchiveDb db = UnifiedArchiveDb.initialize(path, checksum);
        FlushOptions flush = new FlushOptions().setWaitForFlush(true)) {
      UnifiedArchiveTxNumIndex index = new UnifiedArchiveTxNumIndex(db, checksum, false, true);
      UnifiedArchiveTemporalStore temporal = new UnifiedArchiveTemporalStore(db, catalog);
      UnifiedArchiveInFlightStore journals = new UnifiedArchiveInFlightStore(db, catalog);
      UnifiedArchiveBackend backend = new UnifiedArchiveBackend(db, index, temporal);
      DomainValue[] previous = new DomainValue[KEY_COUNT];
      Arrays.fill(previous, DomainValue.tombstone());
      RocksDB raw = ReflectUtils.getFieldValue(db, "db");
      List<ColumnFamilyHandle> handles = ReflectUtils.getFieldValue(db, "allHandles");
      Random random = new Random(20260908L);
      long started = System.nanoTime();
      for (int number = 0; number < blocks; number++) {
        long first = number * 4L;
        byte[] txId = hash(number + 1L);
        ArchiveBlockRange range = new ArchiveBlockRange(number, first, first + 3L,
            first, first + 3L, hash(number), 1, ArchiveSource.NORMAL, checksum);
        ArchiveTxPosition prepare = position(first, number, ArchivePhase.BLOCK_PREPARE, null);
        ArchiveTxPosition user = position(first + 1L, number, ArchivePhase.USER_TX, txId);
        ArchiveTxPosition vm = position(first + 2L, number, ArchivePhase.USER_TX_VM, txId);
        ArchiveTxPosition finalize =
            position(first + 3L, number, ArchivePhase.BLOCK_FINALIZE, null);
        List<ArchiveChangeRecord> records = new ArrayList<>();
        for (int offset = 0; offset < CHANGES_PER_BLOCK; offset++) {
          long ordinal = (long) number * CHANGES_PER_BLOCK + offset;
          int slot = (int) (ordinal % KEY_COUNT);
          ArchiveDomain domain = (slot & 3) == 3
              ? ArchiveDomain.CONTRACT_STORAGE : ArchiveDomain.ACCOUNT;
          DomainValue value = nextValue(random, domain, slot, ordinal);
          ArchiveTxPosition phase = offset == 0 ? finalize : vm;
          records.add(new ArchiveChangeRecord(phase, domain, key(domain, slot),
              previous[slot], value));
          previous[slot] = value;
        }
        ArchiveInFlightBlock block = new ArchiveInFlightBlock(range,
            Arrays.asList(prepare, user, vm, finalize), records);
        journals.putBlock(block);
        journals.acknowledgeBlock(block.getJournalToken());
        backend.publishBlock(block.withJournalState(
            ArchiveInFlightBlock.JournalState.CANONICAL_COMMITTED));
        journals.onBlockPublished(number);
        if ((number + 1) % 1024 == 0 || number + 1 == blocks) {
          raw.flush(flush, handles);
          System.out.println("PAYLOAD_FIXTURE blocks=" + (number + 1) + " records="
              + ((number + 1L) * CHANGES_PER_BLOCK) + " elapsedMs=" + elapsedMillis(started));
        }
      }
      index.markRepairRequired("synthetic payload benchmark requires full validation");
      raw.flush(flush, handles);
    }
  }

  private static void validate(Path path, ArchiveDomainCatalog catalog, byte[] checksum) {
    try (UnifiedArchiveDb db = UnifiedArchiveTestMaintenance.openWithStatistics(path, checksum)) {
      UnifiedArchiveTxNumIndex index = new UnifiedArchiveTxNumIndex(db, checksum, false, true);
      UnifiedArchiveBackend backend = new UnifiedArchiveBackend(db, index,
          new UnifiedArchiveTemporalStore(db, catalog));
      Statistics statistics = ReflectUtils.getFieldValue(db, "statistics");
      long gets = statistics.getTickerCount(TickerType.NUMBER_KEYS_READ);
      long seeks = statistics.getTickerCount(TickerType.NUMBER_DB_SEEK);
      long misses = statistics.getTickerCount(TickerType.BLOCK_CACHE_INDEX_MISS);
      long started = System.nanoTime();
      backend.validateStartup(true, true);
      System.out.println("PAYLOAD_SCRUB_OK elapsedMs=" + elapsedMillis(started)
          + " pointReads=" + (statistics.getTickerCount(TickerType.NUMBER_KEYS_READ) - gets)
          + " seeks=" + (statistics.getTickerCount(TickerType.NUMBER_DB_SEEK) - seeks)
          + " indexCacheMisses="
          + (statistics.getTickerCount(TickerType.BLOCK_CACHE_INDEX_MISS) - misses)
          + " lastBlock=" + index.getLastArchivedBlock()
          + " repairRequired=" + index.hasRepairRequired());
    }
  }

  private static ArchiveTxPosition position(long txNum, long block, ArchivePhase phase,
      byte[] txId) {
    return new ArchiveTxPosition(txNum, block, phase, ArchiveSource.NORMAL,
        txId == null ? -1 : 0, txId);
  }

  private static DomainValue nextValue(Random random, ArchiveDomain domain, int slot,
      long ordinal) {
    if (ordinal >= KEY_COUNT && (ordinal / KEY_COUNT) % 3 == 1 && slot % 11 == 0) {
      return DomainValue.tombstone();
    }
    int bytes = domain == ArchiveDomain.CONTRACT_STORAGE
        ? 32 : slot % 1024 == 0 ? 96 * 1024 : 384;
    byte[] payload = new byte[bytes];
    random.nextBytes(payload);
    if (domain == ArchiveDomain.CONTRACT_STORAGE) {
      return DomainValue.present(payload);
    }
    return DomainValue.present(Account.newBuilder().setBalance(ordinal + 1L)
        .setAccountName(ByteString.copyFrom(payload)).build().toByteArray());
  }

  private static byte[] key(ArchiveDomain domain, int slot) {
    byte[] key = new byte[domain == ArchiveDomain.CONTRACT_STORAGE ? 32 : 21];
    key[0] = 0x41;
    ByteBuffer.wrap(key, key.length - Integer.BYTES, Integer.BYTES).putInt(slot);
    return key;
  }

  private static byte[] hash(long number) {
    byte[] hash = new byte[32];
    ByteBuffer.wrap(hash, hash.length - Long.BYTES, Long.BYTES).putLong(number);
    return hash;
  }

  private static long elapsedMillis(long started) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
  }
}
