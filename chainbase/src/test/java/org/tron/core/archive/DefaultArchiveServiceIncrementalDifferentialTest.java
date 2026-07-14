package org.tron.core.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.After;
import org.junit.Test;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.archive.temporal.ArchiveTemporalReadView;
import org.tron.core.archive.temporal.ArchiveTemporalStore;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.InMemoryArchiveTxNumIndex;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.protos.Protocol.Account;

public class DefaultArchiveServiceIncrementalDifferentialTest {

  private static final long SEED = 0x51A7E1F11A6L;
  private static final int RANDOM_STEPS = 750;
  private static final int MAX_IN_FLIGHT_BLOCKS = 12;
  private static final int KEY_COUNT = 5;
  private static final byte[][] KEYS = accountKeys();
  private static final Method LATEST_WITH_IN_FLIGHT = privateMethod(
      "latestWithInFlight", ArchiveDomain.class, byte[].class);
  private static final Method READ_THROUGH_IN_FLIGHT = privateMethod(
      "readThroughInFlight", ArchiveDomain.class, byte[].class, long.class);

  @After
  public void clearCaptureHolder() {
    ArchiveCaptureHolder.clear();
  }

  @Test
  public void randomizedTransitionsMatchFullRebuildOracle() throws Exception {
    Random random = new Random(SEED);
    try (DifferentialFixture fixture = new DifferentialFixture()) {
      // Exercise an empty-block run before state-bearing blocks exist.
      fixture.appendEmptyBlock();
      assertDifferential(fixture, "seed=" + SEED + " empty-1");
      fixture.appendEmptyBlock();
      assertDifferential(fixture, "seed=" + SEED + " empty-2");
      fixture.appendEmptyBlock();
      assertDifferential(fixture, "seed=" + SEED + " empty-3");
      fixture.publishOldest(2);
      assertDifferential(fixture, "seed=" + SEED + " empty-publish-head");
      fixture.unwindNewest();
      assertDifferential(fixture, "seed=" + SEED + " empty-unwind-tail");

      // Same key twice in one tx, again in the next tx, then delete and recreate next block.
      fixture.appendBlock(
          () -> {
            fixture.put(0, 100);
            fixture.put(0, 101);
          },
          () -> fixture.delete(0));
      assertDifferential(fixture, "seed=" + SEED + " repeated-write-delete");
      fixture.appendBlock(
          () -> {
            fixture.put(0, 102);
            fixture.put(0, 103);
          },
          () -> {
            fixture.delete(1);
            fixture.put(1, 104);
          });
      assertDifferential(fixture, "seed=" + SEED + " recreate");

      for (int step = 0; step < RANDOM_STEPS; step++) {
        int roll = random.nextInt(100);
        String operation;
        if (fixture.inFlightSize() == 0
            || fixture.inFlightSize() < MAX_IN_FLIGHT_BLOCKS && roll < 60) {
          fixture.appendRandomBlock(random);
          operation = "append";
        } else if (roll < 82) {
          int count = Math.min(fixture.inFlightSize(), 1 + random.nextInt(3));
          fixture.publishOldest(count);
          operation = "publish-oldest-" + count;
        } else {
          fixture.unwindNewest();
          operation = "unwind-newest";
        }
        assertDifferential(fixture,
            "seed=" + SEED + " step=" + step + " operation=" + operation);
      }

      if (fixture.inFlightSize() > 0) {
        fixture.publishOldest(fixture.inFlightSize());
      }
      assertDifferential(fixture, "seed=" + SEED + " final-publish-all");
    }
  }

  @Test
  public void durableFailuresDoNotAdvanceIncrementalState() {
    assertJournalPutFailureLeavesMemoryUnchanged();
    assertJournalDeleteFailureLeavesMemoryUnchanged();
    assertTemporalPublishFailureLeavesMemoryUnchanged();
  }

  private static void assertJournalPutFailureLeavesMemoryUnchanged() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    FailingInFlightStore store = new FailingInFlightStore();
    DefaultArchiveService service = service(index, temporal, store);
    try {
      publishEmptyBaseline(service, 5, 1);
      commitAccountChange(service, block(6, 2), KEYS[0], account(10), account(20));
      RebuildOracle before = RebuildOracle.from(temporal, store);

      store.failPuts = true;
      ArchiveException failure = assertThrows(ArchiveException.class,
          () -> commitAccountChange(
              service, block(7, 3), KEYS[0], account(20), account(30)));

      assertTrue(failure.getMessage().contains("injected durable journal put failure"));
      assertEquals(1, store.loadBlocks().size());
      assertOracleEquals(before, RebuildOracle.from(temporal, store), "journal put failure");
      assertIncrementalMatchesOracle(service, index, before, "journal put failure");
    } finally {
      service.close();
    }
  }

  private static void assertJournalDeleteFailureLeavesMemoryUnchanged() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    FailingInFlightStore store = new FailingInFlightStore();
    DefaultArchiveService service = service(index, temporal, store);
    BlockCapsule newest = block(7, 13);
    try {
      publishEmptyBaseline(service, 5, 11);
      commitAccountChange(service, block(6, 12), KEYS[0], account(10), account(20));
      commitAccountChange(service, newest, KEYS[0], account(20), account(30));
      RebuildOracle before = RebuildOracle.from(temporal, store);

      store.failDeletes = true;
      ArchiveException failure = assertThrows(
          ArchiveException.class, () -> service.unwindBlock(newest));

      assertTrue(failure.getMessage().contains("injected durable journal delete failure"));
      assertEquals(2, store.loadBlocks().size());
      assertOracleEquals(before, RebuildOracle.from(temporal, store), "journal delete failure");
      assertIncrementalMatchesOracle(service, index, before, "journal delete failure");
    } finally {
      service.close();
    }
  }

  private static void assertTemporalPublishFailureLeavesMemoryUnchanged() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    FailingTemporalStore temporal = new FailingTemporalStore();
    FailingInFlightStore store = new FailingInFlightStore();
    DefaultArchiveService service = service(index, temporal, store);
    try {
      publishEmptyBaseline(service, 5, 21);
      commitAccountChange(service, block(6, 22), KEYS[0], account(10), account(20));
      RebuildOracle before = RebuildOracle.from(temporal, store);

      temporal.failBlockWrites = true;
      ArchiveException failure = assertThrows(
          ArchiveException.class, () -> service.publishSolidifiedBlocks(6));

      assertTrue(failure.getMessage().contains("injected durable temporal publish failure"));
      assertFalse(index.getBlockRange(6).isPresent());
      assertEquals(1, store.loadBlocks().size());
      assertOracleEquals(before, RebuildOracle.from(temporal, store),
          "temporal publish failure");
      assertIncrementalMatchesOracle(service, index, before, "temporal publish failure");
    } finally {
      service.close();
    }
  }

  private static void assertDifferential(DifferentialFixture fixture, String context)
      throws Exception {
    RebuildOracle oracle = RebuildOracle.from(fixture.temporal, fixture.store);
    assertOracleEquals(fixture.expectedOracle(), oracle, context + " independent model");
    assertEquals(context + " journal block count",
        fixture.inFlightSize(), fixture.store.loadBlocks().size());
    assertIncrementalMatchesOracle(fixture.service, fixture.index, oracle, context);
    assertReaderMatchesEarliestPrev(fixture.service, fixture.index, oracle, context);
  }

  private static void assertIncrementalMatchesOracle(DefaultArchiveService service,
      InMemoryArchiveTxNumIndex index, RebuildOracle oracle, String context) {
    long publishedPointTxNum = index.getNextTxNum() - 1;
    for (int key = 0; key < KEY_COUNT; key++) {
      Optional<DomainValue> actualLatest = invokeDomainValue(
          LATEST_WITH_IN_FLIGHT, service, ArchiveDomain.ACCOUNT, KEYS[key]);
      assertDomainValueEquals(
          oracle.latest.get(key), actualLatest, context + " latest key=" + key);

      Optional<DomainValue> actualEarliest = invokeDomainValue(
          READ_THROUGH_IN_FLIGHT, service, ArchiveDomain.ACCOUNT, KEYS[key],
          publishedPointTxNum);
      assertDomainValueEquals(
          oracle.earliestPrev.get(key), actualEarliest, context + " earliest-prev key=" + key);
    }
  }

  private static void assertReaderMatchesEarliestPrev(DefaultArchiveService service,
      InMemoryArchiveTxNumIndex index, RebuildOracle oracle, String context) throws Exception {
    boolean hasExpectedShield = false;
    for (Optional<DomainValue> value : oracle.earliestPrev) {
      hasExpectedShield |= value.isPresent();
    }
    if (!hasExpectedShield) {
      return;
    }
    long publishedHead = index.getLastArchivedBlock();
    ArchiveBlockRange range = index.getBlockRange(publishedHead)
        .orElseThrow(AssertionError::new);
    ArchiveStatePoint point = ArchiveStatePoint.blockEnd(
        publishedHead, range.getBlockHash(), range.getFinalizeTxNum());
    try (ArchiveStateReader reader = service.getReaderFactory().open(point)) {
      for (int key = 0; key < KEY_COUNT; key++) {
        Optional<DomainValue> expected = oracle.earliestPrev.get(key);
        if (!expected.isPresent()) {
          continue;
        }
        ArchiveReadResult<?> actual = reader.getAccount(KEYS[key]);
        if (expected.get().isDeleted()) {
          assertEquals(context + " reader earliest-prev key=" + key,
              ArchiveReadResult.Status.TOMBSTONE, actual.getStatus());
        } else {
          assertEquals(context + " reader earliest-prev key=" + key,
              ArchiveReadResult.Status.PRESENT, actual.getStatus());
          assertArrayEquals(context + " reader earliest-prev bytes key=" + key,
              expected.get().getValue(),
              ((AccountCapsule) actual.getValue()).getData());
        }
      }
    }
  }

  private static void assertOracleEquals(RebuildOracle expected, RebuildOracle actual,
      String context) {
    for (int key = 0; key < KEY_COUNT; key++) {
      assertDomainValueEquals(expected.latest.get(key), actual.latest.get(key),
          context + " durable latest key=" + key);
      assertDomainValueEquals(expected.earliestPrev.get(key), actual.earliestPrev.get(key),
          context + " durable earliest-prev key=" + key);
    }
  }

  private static void assertDomainValueEquals(Optional<DomainValue> expected,
      Optional<DomainValue> actual, String context) {
    assertEquals(context + " presence", expected.isPresent(), actual.isPresent());
    if (!expected.isPresent()) {
      return;
    }
    assertEquals(context + " tombstone",
        expected.get().isDeleted(), actual.get().isDeleted());
    assertArrayEquals(context + " bytes",
        expected.get().getValue(), actual.get().getValue());
  }

  @SuppressWarnings("unchecked")
  private static Optional<DomainValue> invokeDomainValue(Method method, Object target,
      Object... arguments) {
    try {
      return (Optional<DomainValue>) method.invoke(target, arguments);
    } catch (IllegalAccessException e) {
      throw new AssertionError("cannot invoke incremental state test observation", e);
    } catch (InvocationTargetException e) {
      throw new AssertionError("incremental state test observation failed", e.getCause());
    }
  }

  private static Method privateMethod(String name, Class<?>... parameterTypes) {
    try {
      Method method = DefaultArchiveService.class.getDeclaredMethod(name, parameterTypes);
      method.setAccessible(true);
      return method;
    } catch (NoSuchMethodException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static DefaultArchiveService service(InMemoryArchiveTxNumIndex index,
      ArchiveTemporalStore temporal, ArchiveInFlightStore store) {
    return new DefaultArchiveService(
        true, index, new ArchiveExecutionContext(), temporal, store,
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog());
  }

  private static void publishEmptyBaseline(DefaultArchiveService service, long blockNum,
      int generation) {
    commitEmptyBlock(service, block(blockNum, generation));
    service.publishSolidifiedBlocks(blockNum);
  }

  private static void commitEmptyBlock(DefaultArchiveService service, BlockCapsule block) {
    service.beginBlock(block, ArchiveSource.NORMAL);
    service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    service.commitBlock(block, 0);
  }

  private static void commitAccountChange(DefaultArchiveService service, BlockCapsule block,
      byte[] key, byte[] previous, byte[] value) {
    service.beginBlock(block, ArchiveSource.NORMAL);
    service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
    if (value == null) {
      service.getCaptureEngine().captureDelete("account", key, previous);
    } else {
      service.getCaptureEngine().capturePut("account", key, previous, value);
    }
    service.endTx();
    service.commitBlock(block, 0);
  }

  private static BlockCapsule block(long blockNum, int generation) {
    byte[] parent = new byte[32];
    parent[0] = (byte) (generation >>> 24);
    parent[1] = (byte) (generation >>> 16);
    parent[2] = (byte) (generation >>> 8);
    parent[3] = (byte) generation;
    return new BlockCapsule(
        blockNum, Sha256Hash.wrap(parent), 1L, ByteString.EMPTY);
  }

  private static byte[] account(long balance) {
    return Account.newBuilder().setBalance(balance).build().toByteArray();
  }

  private static byte[][] accountKeys() {
    byte[][] keys = new byte[KEY_COUNT][];
    for (int i = 0; i < keys.length; i++) {
      keys[i] = new byte[21];
      keys[i][0] = 0x41;
      keys[i][20] = (byte) (i + 1);
    }
    return keys;
  }

  @FunctionalInterface
  private interface CapturePlan {
    void capture();
  }

  private static final class DifferentialFixture implements AutoCloseable {

    private final InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    private final InMemoryArchiveTemporalStore temporal = new InMemoryArchiveTemporalStore();
    private final FailingInFlightStore store = new FailingInFlightStore();
    private final DefaultArchiveService service = service(index, temporal, store);
    private final Deque<ModelBlock> inFlight = new ArrayDeque<>();
    private final Map<Integer, byte[]> current = new HashMap<>();
    private final Map<Integer, DomainValue> archiveLatest = new HashMap<>();
    private Map<Integer, DomainValue> activeEarliestPrev;
    private long nextBlockNum = 6;
    private int nextGeneration = 101;
    private long nextBalance = 1_000;

    private DifferentialFixture() {
      current.put(0, account(10));
      current.put(2, account(20));
      current.put(4, account(30));
      publishEmptyBaseline(service, 5, 100);
    }

    private int inFlightSize() {
      return inFlight.size();
    }

    private void appendEmptyBlock() {
      appendBlock(() -> {
      }, () -> {
      });
    }

    private void appendRandomBlock(Random random) {
      if (random.nextInt(6) == 0) {
        appendEmptyBlock();
        return;
      }
      int prepareOperations = 1 + random.nextInt(3);
      int finalizeOperations = random.nextInt(4);
      appendBlock(
          () -> captureRandomOperations(random, prepareOperations),
          () -> captureRandomOperations(random, finalizeOperations));
    }

    private void appendBlock(CapturePlan prepare, CapturePlan finalize) {
      Map<Integer, byte[]> stateBefore = copyState(current);
      Map<Integer, DomainValue> archiveLatestBefore = copyDomainState(archiveLatest);
      Map<Integer, DomainValue> earliestPrev = new HashMap<>();
      BlockCapsule block = block(nextBlockNum, nextGeneration++);
      activeEarliestPrev = earliestPrev;
      try {
        service.beginBlock(block, ArchiveSource.NORMAL);
        service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
        prepare.capture();
        service.endTx();
        service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
        finalize.capture();
        service.endTx();
        service.commitBlock(block, 0);
      } finally {
        activeEarliestPrev = null;
      }
      inFlight.addLast(new ModelBlock(
          block, stateBefore, archiveLatestBefore, earliestPrev));
      nextBlockNum++;
    }

    private void publishOldest(int count) {
      if (count <= 0 || count > inFlight.size()) {
        throw new IllegalArgumentException("invalid publish count " + count);
      }
      ModelBlock target = null;
      int offset = 0;
      for (ModelBlock block : inFlight) {
        target = block;
        if (++offset == count) {
          break;
        }
      }
      service.publishSolidifiedBlocks(target.block.getNum());
      for (int i = 0; i < count; i++) {
        inFlight.removeFirst();
      }
    }

    private void unwindNewest() {
      ModelBlock newest = inFlight.peekLast();
      if (newest == null) {
        throw new IllegalStateException("no in-flight block to unwind");
      }
      service.unwindBlock(newest.block);
      inFlight.removeLast();
      current.clear();
      current.putAll(copyState(newest.stateBefore));
      archiveLatest.clear();
      archiveLatest.putAll(copyDomainState(newest.archiveLatestBefore));
      nextBlockNum = newest.block.getNum();
    }

    private void captureRandomOperations(Random random, int count) {
      for (int i = 0; i < count; i++) {
        int key = random.nextInt(KEY_COUNT);
        switch (random.nextInt(6)) {
          case 0:
            put(key, nextBalance++);
            break;
          case 1:
            delete(key);
            break;
          case 2:
            put(key, nextBalance++);
            put(key, nextBalance++);
            break;
          case 3:
            delete(key);
            put(key, nextBalance++);
            break;
          case 4:
            captureSameValue(key);
            break;
          default:
            put(key, nextBalance++);
            delete(key);
            break;
        }
      }
    }

    private void put(int key, long balance) {
      byte[] previous = current.get(key);
      byte[] value = account(balance);
      recordCapture(key, previous, value);
      service.getCaptureEngine().capturePut("account", KEYS[key], previous, value);
      current.put(key, value);
    }

    private void delete(int key) {
      byte[] previous = current.get(key);
      recordCapture(key, previous, null);
      service.getCaptureEngine().captureDelete("account", KEYS[key], previous);
      current.remove(key);
    }

    private void captureSameValue(int key) {
      byte[] value = current.get(key);
      recordCapture(key, value, value);
      if (value == null) {
        service.getCaptureEngine().captureDelete("account", KEYS[key], null);
      } else {
        service.getCaptureEngine().capturePut("account", KEYS[key], value, value);
      }
    }

    private void recordCapture(int key, byte[] previous, byte[] value) {
      if (activeEarliestPrev == null) {
        throw new IllegalStateException("capture executed outside an active model block");
      }
      activeEarliestPrev.putIfAbsent(key, previous == null
          ? DomainValue.tombstone()
          : DomainValue.present(previous));
      archiveLatest.put(key, value == null
          ? DomainValue.tombstone()
          : DomainValue.present(value));
    }

    private RebuildOracle expectedOracle() {
      List<Optional<DomainValue>> latest = new ArrayList<>();
      List<Optional<DomainValue>> earliestPrev = new ArrayList<>();
      for (int key = 0; key < KEY_COUNT; key++) {
        latest.add(Optional.ofNullable(archiveLatest.get(key)));
        earliestPrev.add(Optional.empty());
      }
      for (ModelBlock block : inFlight) {
        for (Map.Entry<Integer, DomainValue> entry : block.earliestPrev.entrySet()) {
          int key = entry.getKey();
          if (!earliestPrev.get(key).isPresent()) {
            earliestPrev.set(key, Optional.of(entry.getValue()));
          }
        }
      }
      return new RebuildOracle(latest, earliestPrev);
    }

    @Override
    public void close() {
      service.close();
    }
  }

  private static Map<Integer, byte[]> copyState(Map<Integer, byte[]> state) {
    Map<Integer, byte[]> copy = new HashMap<>();
    for (Map.Entry<Integer, byte[]> entry : state.entrySet()) {
      copy.put(entry.getKey(), Arrays.copyOf(entry.getValue(), entry.getValue().length));
    }
    return copy;
  }

  private static Map<Integer, DomainValue> copyDomainState(
      Map<Integer, DomainValue> state) {
    Map<Integer, DomainValue> copy = new HashMap<>();
    for (Map.Entry<Integer, DomainValue> entry : state.entrySet()) {
      DomainValue value = entry.getValue();
      copy.put(entry.getKey(), value.isDeleted()
          ? DomainValue.tombstone()
          : DomainValue.present(value.getValue()));
    }
    return copy;
  }

  private static final class ModelBlock {

    private final BlockCapsule block;
    private final Map<Integer, byte[]> stateBefore;
    private final Map<Integer, DomainValue> archiveLatestBefore;
    private final Map<Integer, DomainValue> earliestPrev;

    private ModelBlock(BlockCapsule block, Map<Integer, byte[]> stateBefore,
        Map<Integer, DomainValue> archiveLatestBefore,
        Map<Integer, DomainValue> earliestPrev) {
      this.block = block;
      this.stateBefore = stateBefore;
      this.archiveLatestBefore = archiveLatestBefore;
      this.earliestPrev = earliestPrev;
    }
  }

  private static final class RebuildOracle {

    private final List<Optional<DomainValue>> latest;
    private final List<Optional<DomainValue>> earliestPrev;

    private RebuildOracle(List<Optional<DomainValue>> latest,
        List<Optional<DomainValue>> earliestPrev) {
      this.latest = latest;
      this.earliestPrev = earliestPrev;
    }

    private static RebuildOracle from(ArchiveTemporalStore temporal,
        ArchiveInFlightStore store) {
      List<Optional<DomainValue>> latest = new ArrayList<>();
      List<Optional<DomainValue>> earliestPrev = new ArrayList<>();
      for (byte[] key : KEYS) {
        latest.add(temporal.latest(ArchiveDomain.ACCOUNT, key));
        earliestPrev.add(Optional.empty());
      }
      List<ArchiveInFlightBlock> blocks = new ArrayList<>(store.loadBlocks());
      blocks.sort(Comparator.comparingLong(block -> block.getRange().getBlockNum()));
      for (ArchiveInFlightBlock block : blocks) {
        for (ArchiveChangeRecord record : block.getRecords()) {
          if (record.getDomain() != ArchiveDomain.ACCOUNT) {
            continue;
          }
          int key = keyIndex(record.getCanonicalKey());
          if (key < 0) {
            continue;
          }
          if (!earliestPrev.get(key).isPresent()) {
            earliestPrev.set(key, Optional.of(record.getPrevValue()));
          }
          latest.set(key, Optional.of(record.getValue()));
        }
      }
      return new RebuildOracle(latest, earliestPrev);
    }

    private static int keyIndex(byte[] canonicalKey) {
      for (int i = 0; i < KEYS.length; i++) {
        if (Arrays.equals(KEYS[i], canonicalKey)) {
          return i;
        }
      }
      return -1;
    }
  }

  private static final class FailingInFlightStore implements ArchiveInFlightStore {

    private final InMemoryArchiveInFlightStore delegate = new InMemoryArchiveInFlightStore();
    private boolean failPuts;
    private boolean failDeletes;

    @Override
    public List<ArchiveInFlightBlock> loadBlocks() {
      return delegate.loadBlocks();
    }

    @Override
    public void putBlock(ArchiveInFlightBlock block) {
      if (failPuts) {
        throw new ArchiveException("injected durable journal put failure");
      }
      delegate.putBlock(block);
    }

    @Override
    public void deleteBlock(long blockNum) {
      if (failDeletes) {
        throw new ArchiveException("injected durable journal delete failure");
      }
      delegate.deleteBlock(blockNum);
    }
  }

  private static final class FailingTemporalStore implements ArchiveTemporalStore {

    private final InMemoryArchiveTemporalStore delegate = new InMemoryArchiveTemporalStore();
    private boolean failBlockWrites;

    @Override
    public void putChange(ArchiveChangeRecord record) {
      delegate.putChange(record);
    }

    @Override
    public void putChanges(List<ArchiveChangeRecord> records) {
      delegate.putChanges(records);
    }

    @Override
    public void putBlockChanges(ArchiveBlockRange range, List<ArchiveChangeRecord> records) {
      if (failBlockWrites) {
        throw new ArchiveException("injected durable temporal publish failure");
      }
      delegate.putBlockChanges(range, records);
    }

    @Override
    public void validateCommittedBlock(ArchiveBlockRange range) {
      delegate.validateCommittedBlock(range);
    }

    @Override
    public void reconcilePublishedBlock(ArchiveBlockRange range,
        List<ArchiveChangeRecord> records) {
      delegate.reconcilePublishedBlock(range, records);
    }

    @Override
    public Optional<DomainValue> getAsOf(ArchiveDomain domain, byte[] canonicalKey,
        long txNum) {
      return delegate.getAsOf(domain, canonicalKey, txNum);
    }

    @Override
    public Optional<DomainValue> latest(ArchiveDomain domain, byte[] canonicalKey) {
      return delegate.latest(domain, canonicalKey);
    }

    @Override
    public ArchiveTemporalReadView openReadView() {
      return delegate.openReadView();
    }

    @Override
    public void unwind(long fromTxNum) {
      delegate.unwind(fromTxNum);
    }

    @Override
    public void unwindBlock(ArchiveBlockRange range) {
      delegate.unwindBlock(range);
    }
  }
}
