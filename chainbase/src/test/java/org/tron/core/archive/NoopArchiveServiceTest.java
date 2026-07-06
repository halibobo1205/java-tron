package org.tron.core.archive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.tron.common.arch.Arch;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveSchemaChecksum;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.temporal.RocksDbArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.archive.txnum.PersistentArchiveTxNumIndex;
import org.tron.core.archive.txnum.RocksDbArchiveBlockRangeStore;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.config.args.StorageConfig;
import org.tron.protos.Protocol.Account;

public class NoopArchiveServiceTest {

  private static final byte[] SCHEMA_CHECKSUM = ArchiveSchemaChecksum.of(
      new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog());

  @Test
  public void noopServiceIsDisabledAndDoesNothing() {
    ArchiveService service = NoopArchiveService.INSTANCE;
    assertFalse(service.isEnabled());
    // Every lifecycle callback is a no-op; invoking them (even with null) must not throw.
    service.beginBlock(null, ArchiveSource.NORMAL);
    service.beginSystemTx(null, ArchivePhase.BLOCK_PREPARE);
    service.beginUserTx(null, 0, null);
    service.endTx();
    service.commitBlock(null);
    service.abortBlock(null);
    service.unwindBlock(null);
    service.validateCanonicalHead(null);
  }

  @Test
  public void factoryReturnsNoopWhenDisabled() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    assertFalse(config.isEnable());
    assertSame(NoopArchiveService.INSTANCE, ArchiveServiceFactory.create(config));
  }

  @Test
  public void factoryClearsCaptureHolderWhenDisabled() {
    StorageConfig.ArchiveConfig enabledConfig = new StorageConfig.ArchiveConfig();
    enabledConfig.setEnable(true);
    ArchiveService enabled = createArchive(enabledConfig);
    try {
      assertTrue(ArchiveCaptureHolder.isActive());

      StorageConfig.ArchiveConfig disabledConfig = new StorageConfig.ArchiveConfig();
      assertSame(NoopArchiveService.INSTANCE, ArchiveServiceFactory.create(disabledConfig));

      assertFalse(ArchiveCaptureHolder.isActive());
    } finally {
      enabled.close();
    }
  }

  @Test
  public void closingOlderEnabledServiceDoesNotClearNewerCaptureHolder() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    ArchiveService older = createArchive(config);
    ArchiveService newer = createArchive(config);
    try {
      assertTrue(ArchiveCaptureHolder.isActive());
      assertThrows(ArchiveException.class, older::validateAvailable);
      older.close();
      assertTrue(ArchiveCaptureHolder.isActive());
    } finally {
      newer.close();
    }
  }

  @Test
  public void factoryReturnsNoopWhenConfigNull() {
    assertSame(NoopArchiveService.INSTANCE, ArchiveServiceFactory.create(null));
  }

  @Test
  public void factoryReturnsEnabledDefaultServiceWhenEnabled() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);

    ArchiveService service = createArchive(config);

    assertTrue(service instanceof DefaultArchiveService);
    assertTrue(service.isEnabled());
  }

  @Test
  public void factoryRejectsArchiveEnabledOnUnsupportedPlatform() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    try (MockedStatic<Arch> arch = mockStatic(Arch.class)) {
      arch.when(Arch::isArm64).thenReturn(false);

      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> ArchiveServiceFactory.create(config));

      assertTrue(ex.getMessage().contains("archive is not supported"));
    }
  }

  @Test
  public void factoryRejectsArchiveEnabledWithTxnumDisabled() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    config.getTxnum().setEnable(false);
    assertThrows(ArchiveException.class, () -> createArchive(config));
  }

  @Test
  public void factoryRejectsArchiveEnabledWithTemporalDisabled() {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    config.getTemporal().setEnable(false);
    assertThrows(ArchiveException.class, () -> createArchive(config));
  }

  @Test
  public void factoryInstallsPersistentStoreWhenPathSupplied() throws IOException {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    Path dir = Files.createTempDirectory("archive-factory-test");
    try {
      DefaultArchiveService service =
          (DefaultArchiveService) createArchive(config, dir.toString());
      assertTrue(service.getTemporalStore() instanceof RocksDbArchiveTemporalStore);
      assertTrue(service.getTxNumIndex() instanceof PersistentArchiveTxNumIndex);
      assertNotNull(service.getReaderFactory());
      service.close(); // must release both RocksDB stores cleanly
    } finally {
      deleteRecursively(dir.toFile());
    }
  }

  @Test
  public void factoryReopensCommittedPersistentArchiveWithTemporalMarker() throws IOException {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    Path dir = Files.createTempDirectory("archive-factory-reopen-test");
    try {
      DefaultArchiveService service =
          (DefaultArchiveService) createArchive(config, dir.toString());
      BlockCapsule block = new BlockCapsule(1, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY);
      service.beginBlock(block, ArchiveSource.NORMAL);
      service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
      service.endTx();
      service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
      service.endTx();
      service.commitBlock(block);
      service.close();

      DefaultArchiveService reopened =
          (DefaultArchiveService) createArchive(config, dir.toString());
      reopened.close();
    } finally {
      deleteRecursively(dir.toFile());
    }
  }

  @Test
  public void factoryRejectsIndexRangeWithoutTemporalCommitMarker() throws IOException {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    Path dir = Files.createTempDirectory("archive-factory-crash-test");
    RocksDbArchiveBlockRangeStore index =
        new RocksDbArchiveBlockRangeStore(dir.resolve("index").toString());
    try {
      ArchiveBlockRange range = new ArchiveBlockRange(
          7, 0, 1, 0, 1, blockHash(7), 0, ArchiveSource.NORMAL);
      index.commitRange(withChecksum(range), 2);
    } finally {
      index.close();
    }
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> createArchive(config, dir.toString()));
      assertTrue(ex.getMessage().contains("commit marker missing"));
    } finally {
      deleteRecursively(dir.toFile());
    }
  }

  @Test
  public void factoryRejectsSchemaChecksumMismatchBeforeTemporalCrossChecks()
      throws IOException {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    Path dir = Files.createTempDirectory("archive-factory-schema-test");
    RocksDbArchiveBlockRangeStore index =
        new RocksDbArchiveBlockRangeStore(dir.resolve("index").toString());
    try {
      ArchiveBlockRange range = new ArchiveBlockRange(
          7, 0, 1, 0, 1, blockHash(7), 0, ArchiveSource.NORMAL);
      index.commitRange(withChecksum(range, differentChecksum()), 2);
    } finally {
      index.close();
    }
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> createArchive(config, dir.toString()));
      assertTrue(ex.getMessage().contains("schema checksum mismatch"));
    } finally {
      deleteRecursively(dir.toFile());
    }
  }

  @Test
  public void factoryRejectsNonEmptyTemporalWithEmptyIndex() throws IOException {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    Path dir = Files.createTempDirectory("archive-factory-orphan-temporal-test");
    ArchiveBlockRange orphan = new ArchiveBlockRange(
        7, 0, 1, 0, 1, blockHash(7), 0, ArchiveSource.NORMAL);
    RocksDbArchiveTemporalStore temporal =
        new RocksDbArchiveTemporalStore(dir.resolve("temporal").toString());
    try {
      temporal.putBlockChanges(orphan, Collections.emptyList());
    } finally {
      temporal.close();
    }

    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> createArchive(config, dir.toString()));
      assertTrue(ex.getMessage().contains("temporal store is non-empty"));
    } finally {
      deleteRecursively(dir.toFile());
    }
  }

  @Test
  public void factoryRejectsTemporalCommitMarkerWithoutIndexRange() throws IOException {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    Path dir = Files.createTempDirectory("archive-factory-orphan-marker-test");
    ArchiveBlockRange indexRange = new ArchiveBlockRange(
        7, 0, 1, 0, 1, blockHash(7), 0, ArchiveSource.NORMAL);
    ArchiveBlockRange orphan = new ArchiveBlockRange(
        8, 2, 3, 2, 3, blockHash(8), 0, ArchiveSource.NORMAL);
    RocksDbArchiveTemporalStore temporal =
        new RocksDbArchiveTemporalStore(dir.resolve("temporal").toString());
    try {
      temporal.putBlockChanges(orphan, Collections.emptyList());
    } finally {
      temporal.close();
    }
    RocksDbArchiveBlockRangeStore index =
        new RocksDbArchiveBlockRangeStore(dir.resolve("index").toString());
    try {
      index.commitRange(withChecksum(indexRange), 2);
    } finally {
      index.close();
    }

    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> createArchive(config, dir.toString()));
      assertTrue(ex.getMessage().contains("no index range for block 8"));
    } finally {
      deleteRecursively(dir.toFile());
    }
  }

  @Test
  public void factoryRejectsEarlierIndexRangeWithoutTemporalCommitMarker() throws IOException {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    Path dir = Files.createTempDirectory("archive-factory-early-marker-test");
    ArchiveBlockRange first = new ArchiveBlockRange(
        7, 0, 1, 0, 1, blockHash(7), 0, ArchiveSource.NORMAL);
    ArchiveBlockRange last = new ArchiveBlockRange(
        8, 2, 3, 2, 3, blockHash(8), 0, ArchiveSource.NORMAL);
    RocksDbArchiveTemporalStore temporal =
        new RocksDbArchiveTemporalStore(dir.resolve("temporal").toString());
    try {
      temporal.putBlockChanges(last, Collections.emptyList());
    } finally {
      temporal.close();
    }
    RocksDbArchiveBlockRangeStore index =
        new RocksDbArchiveBlockRangeStore(dir.resolve("index").toString());
    try {
      index.commitRange(withChecksum(first), 2);
      index.commitRange(withChecksum(last), 4);
    } finally {
      index.close();
    }
    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> createArchive(config, dir.toString()));
      assertTrue(ex.getMessage().contains("commit marker missing for block 7"));
    } finally {
      deleteRecursively(dir.toFile());
    }
  }

  @Test
  public void factoryRejectsCursorThatDoesNotMatchLastRange() throws IOException {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    Path dir = Files.createTempDirectory("archive-factory-cursor-test");
    ArchiveBlockRange range = new ArchiveBlockRange(
        7, 0, 1, 0, 1, blockHash(7), 0, ArchiveSource.NORMAL);

    RocksDbArchiveTemporalStore temporal =
        new RocksDbArchiveTemporalStore(dir.resolve("temporal").toString());
    try {
      temporal.putBlockChanges(range, Collections.emptyList());
    } finally {
      temporal.close();
    }
    RocksDbArchiveBlockRangeStore index =
        new RocksDbArchiveBlockRangeStore(dir.resolve("index").toString());
    try {
      index.commitRange(withChecksum(range), 2);
    } finally {
      index.close();
    }
    overwriteArchiveCursor(dir.resolve("index"), 1); // corrupt: expected lastTxNum + 1 = 2

    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> createArchive(config, dir.toString()));
      assertTrue(ex.getMessage().contains("cursor"));
    } finally {
      deleteRecursively(dir.toFile());
    }
  }

  @Test
  public void factoryRejectsIndexRangeAfterTemporalUnwindRemovedMarker() throws IOException {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    Path dir = Files.createTempDirectory("archive-factory-unwind-crash-test");
    ArchiveBlockRange range = new ArchiveBlockRange(
        7, 0, 1, 0, 1, blockHash(7), 0, ArchiveSource.NORMAL);

    RocksDbArchiveTemporalStore temporal =
        new RocksDbArchiveTemporalStore(dir.resolve("temporal").toString());
    try {
      temporal.putBlockChanges(range, Collections.emptyList());
      temporal.unwindBlock(range);
    } finally {
      temporal.close();
    }
    RocksDbArchiveBlockRangeStore index =
        new RocksDbArchiveBlockRangeStore(dir.resolve("index").toString());
    try {
      index.commitRange(withChecksum(range), 2);
    } finally {
      index.close();
    }

    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> createArchive(config, dir.toString()));
      assertTrue(ex.getMessage().contains("commit marker missing"));
    } finally {
      deleteRecursively(dir.toFile());
    }
  }

  @Test
  public void factoryRejectsTemporalHistoryOutsideCommittedIndexRange() throws IOException {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    Path dir = Files.createTempDirectory("archive-factory-orphan-history-test");
    try {
      DefaultArchiveService service =
          (DefaultArchiveService) createArchive(config, dir.toString());
      BlockCapsule block = new BlockCapsule(1, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY);
      service.beginBlock(block, ArchiveSource.NORMAL);
      service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
      service.endTx();
      service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
      service.endTx();
      service.commitBlock(block);
      service.close();

      RocksDbArchiveTemporalStore temporal =
          new RocksDbArchiveTemporalStore(dir.resolve("temporal").toString());
      try {
        temporal.putChange(new ArchiveChangeRecord(
            new ArchiveTxPosition(99, 99, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, null),
            ArchiveDomain.ACCOUNT, new byte[] {0x01},
            DomainValue.tombstone(), DomainValue.present(new byte[] {0x02})));
      } finally {
        temporal.close();
      }

      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> createArchive(config, dir.toString()));
      assertTrue(ex.getMessage().contains("history txNum"));
    } finally {
      deleteRecursively(dir.toFile());
    }
  }

  @Test
  public void factoryReopensAfterUnwindingOnlyArchivedBlock() throws IOException {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    Path dir = Files.createTempDirectory("archive-factory-unwind-empty-test");
    ArchiveBlockRange range = new ArchiveBlockRange(
        7, 0, 1, 0, 1, blockHash(7), 0, ArchiveSource.NORMAL);
    ArchiveChangeRecord change = new ArchiveChangeRecord(
        new ArchiveTxPosition(0, 7, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1, null),
        ArchiveDomain.ACCOUNT, new byte[] {0x41},
        DomainValue.tombstone(), DomainValue.present(new byte[] {0x01}));

    RocksDbArchiveTemporalStore temporal =
        new RocksDbArchiveTemporalStore(dir.resolve("temporal").toString());
    try {
      temporal.putBlockChanges(range, Collections.singletonList(change));
      temporal.unwindBlock(range);
      assertFalse(temporal.hasDataBeyondManifest());
    } finally {
      temporal.close();
    }
    RocksDbArchiveBlockRangeStore index =
        new RocksDbArchiveBlockRangeStore(dir.resolve("index").toString());
    try {
      index.commitRange(withChecksum(range), 2, Arrays.asList(
          new ArchiveTxPosition(0, 7, ArchivePhase.BLOCK_PREPARE, ArchiveSource.NORMAL, -1, null),
          new ArchiveTxPosition(1, 7, ArchivePhase.BLOCK_FINALIZE, ArchiveSource.NORMAL, -1,
              null)));
      index.unwindRange(withChecksum(range), 0);
    } finally {
      index.close();
    }

    try {
      DefaultArchiveService reopened =
          (DefaultArchiveService) createArchive(config, dir.toString());
      reopened.close();
    } finally {
      deleteRecursively(dir.toFile());
    }
  }

  @Test
  public void factoryRejectsPersistentRepairMarkerAfterFatalCommitFailure() throws IOException {
    StorageConfig.ArchiveConfig config = new StorageConfig.ArchiveConfig();
    config.setEnable(true);
    Path dir = Files.createTempDirectory("archive-factory-repair-marker-test");
    RocksDbArchiveTemporalStore persistentTemporal =
        new RocksDbArchiveTemporalStore(dir.resolve("temporal").toString());
    persistentTemporal.close();
    RocksDbArchiveBlockRangeStore blockRangeStore =
        new RocksDbArchiveBlockRangeStore(dir.resolve("index").toString());
    PersistentArchiveTxNumIndex txNumIndex =
        new PersistentArchiveTxNumIndex(blockRangeStore, SCHEMA_CHECKSUM);
    DefaultArchiveService service = new DefaultArchiveService(true, txNumIndex,
        ArchiveExecutionContextHolder.get(), new FailingTemporalStore());
    BlockCapsule block = new BlockCapsule(1, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY);
    service.beginBlock(block, ArchiveSource.NORMAL);
    service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
    service.getCaptureEngine().capturePut(
        "account", new byte[21], null, Account.newBuilder().setBalance(1).build().toByteArray());
    service.endTx();
    assertThrows(ArchiveException.class, () -> service.commitBlock(block));
    service.close();

    try {
      ArchiveException ex = assertThrows(ArchiveException.class,
          () -> createArchive(config, dir.toString()));
      assertTrue(ex.getMessage().contains("requires repair"));
    } finally {
      deleteRecursively(dir.toFile());
    }
  }

  @After
  public void clearCaptureHolder() {
    // An enabled DefaultArchiveService installs a static capture engine; clear between tests.
    ArchiveCaptureHolder.clear();
  }

  private static void deleteRecursively(File f) {
    File[] children = f.listFiles();
    if (children != null) {
      for (File c : children) {
        deleteRecursively(c);
      }
    }
    f.delete();
  }

  private static ArchiveService createArchive(StorageConfig.ArchiveConfig config) {
    try (MockedStatic<Arch> arch = mockArm64()) {
      return ArchiveServiceFactory.create(config);
    }
  }

  private static ArchiveService createArchive(StorageConfig.ArchiveConfig config,
      String archiveDir) {
    try (MockedStatic<Arch> arch = mockArm64()) {
      return ArchiveServiceFactory.create(config, archiveDir);
    }
  }

  private static MockedStatic<Arch> mockArm64() {
    MockedStatic<Arch> arch = mockStatic(Arch.class);
    arch.when(Arch::isArm64).thenReturn(true);
    return arch;
  }

  private static byte[] blockHash(int seed) {
    byte[] hash = new byte[ArchiveBlockRange.BLOCK_HASH_LENGTH];
    hash[ArchiveBlockRange.BLOCK_HASH_LENGTH - 1] = (byte) seed;
    return hash;
  }

  private static ArchiveBlockRange withChecksum(ArchiveBlockRange range) {
    return withChecksum(range, SCHEMA_CHECKSUM);
  }

  private static ArchiveBlockRange withChecksum(ArchiveBlockRange range, byte[] schemaChecksum) {
    return new ArchiveBlockRange(range.getBlockNum(), range.getFirstTxNum(),
        range.getLastTxNum(), range.getPrepareTxNum(), range.getFinalizeTxNum(),
        range.getBlockHash(), range.getUserTxCount(), range.getSource(), schemaChecksum);
  }

  private static byte[] differentChecksum() {
    byte[] checksum = Arrays.copyOf(SCHEMA_CHECKSUM, SCHEMA_CHECKSUM.length);
    checksum[checksum.length - 1] ^= 0x01;
    return checksum;
  }

  private static void overwriteArchiveCursor(Path indexDir, long cursor) {
    RocksDB.loadLibrary();
    try (Options options = new Options().setCreateIfMissing(false);
        RocksDB rawDb = RocksDB.open(options, indexDir.toString())) {
      rawDb.put(new byte[] {0x01, 'c', 'u', 'r', 's', 'o', 'r'}, Longs.toByteArray(cursor));
    } catch (RocksDBException e) {
      throw new ArchiveException("failed to overwrite archive cursor for test", e);
    }
  }

  private static final class FailingTemporalStore
      implements org.tron.core.archive.temporal.ArchiveTemporalStore {

    @Override
    public void putChange(ArchiveChangeRecord record) {
      throw new ArchiveException("temporal failed");
    }

    @Override
    public void putChanges(List<ArchiveChangeRecord> records) {
      throw new ArchiveException("temporal failed");
    }

    @Override
    public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey, long txNum) {
      return Optional.empty();
    }

    @Override
    public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
      return Optional.empty();
    }

    @Override
    public void unwind(long fromTxNum) {
    }
  }
}
