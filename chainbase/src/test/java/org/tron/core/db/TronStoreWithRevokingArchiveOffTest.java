package org.tron.core.db;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.archive.ArchiveExecutionContext;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.capture.ArchiveCaptureEngine;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.capsule.ProtoCapsule;
import org.tron.core.config.args.Storage;
import org.tron.core.db2.common.DB;

public class TronStoreWithRevokingArchiveOffTest {

  private DB<byte[], byte[]> db;
  private TestStore store;
  private Storage previousStorage;

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() {
    ArchiveCaptureHolder.clear();
    previousStorage = CommonParameter.getInstance().getStorage();
    if (previousStorage == null) {
      CommonParameter.getInstance().storage = new Storage();
    }
    db = mock(DB.class);
    when(db.getDbName()).thenReturn("witness");
    store = new TestStore(db);
  }

  @After
  public void tearDown() {
    ArchiveCaptureHolder.clear();
    CommonParameter.getInstance().storage = previousStorage;
  }

  @Test
  public void putAndDeleteKeepCanonicalBytesAndSkipArchiveReads() {
    byte[] key = new byte[] {1, 2, 3};
    byte[] value = new byte[] {4, 5, 6};

    store.put(key, new TestCapsule(value));
    store.delete(key);

    verify(db).put(same(key), same(value));
    verify(db).remove(same(key));
    verify(db, never()).get(any(byte[].class));
  }

  @Test
  public void archivePrevReadFailureDoesNotEscapeCanonicalPut() {
    byte[] key = new byte[] {1, 2, 3};
    byte[] value = new byte[] {4, 5, 6};
    ArchiveCaptureEngine engine = enableCapture();
    when(db.get(any(byte[].class))).thenThrow(new IllegalStateException("prev read failed"));

    store.put(key, new TestCapsule(value));

    verify(db).put(same(key), same(value));
    assertTrue(engine.failure().isPresent());
    assertTrue(engine.records().isEmpty());
  }

  @Test
  public void archivePrevReadFailureDoesNotEscapeCanonicalDelete() {
    byte[] key = new byte[] {1, 2, 3};
    ArchiveCaptureEngine engine = enableCapture();
    when(db.get(any(byte[].class))).thenThrow(new IllegalStateException("prev read failed"));

    store.delete(key);

    verify(db).remove(same(key));
    assertTrue(engine.failure().isPresent());
    assertTrue(engine.records().isEmpty());
  }

  @Test
  public void knownPreviousPutDoesNotReadPreviousTwice() {
    byte[] key = new byte[] {1, 2, 3};
    byte[] oldValue = new byte[] {4};
    byte[] newValue = new byte[] {5};
    enableCapture();
    when(db.get(key)).thenReturn(oldValue);

    store.putWithSinglePreviousRead(key, new TestCapsule(newValue));

    verify(db, times(1)).get(key);
    verify(db).put(same(key), same(newValue));
  }

  @Test
  public void knownPreviousDeleteDoesNotReadPreviousTwice() {
    byte[] key = new byte[] {1, 2, 3};
    enableCapture();
    when(db.get(key)).thenReturn(new byte[] {4});

    store.deleteWithSinglePreviousRead(key);

    verify(db, times(1)).get(key);
    verify(db).remove(same(key));
  }

  private static ArchiveCaptureEngine enableCapture() {
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    context.enter(new ArchiveTxPosition(
        1, 1, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, new byte[32]));
    ArchiveCaptureEngine engine = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        new DynamicKeyPolicy(), context);
    ArchiveCaptureHolder.set(engine);
    return engine;
  }

  private static final class TestStore extends TronStoreWithRevoking<TestCapsule> {

    private TestStore(DB<byte[], byte[]> db) {
      super(db);
    }

    private void putWithSinglePreviousRead(byte[] key, TestCapsule value) {
      ArchivePreviousValue previous = readArchivePreviousValue(getDbName(), key);
      putWithKnownArchivePrevious(key, value, previous);
    }

    private void deleteWithSinglePreviousRead(byte[] key) {
      ArchivePreviousValue previous = readArchivePreviousValue(getDbName(), key);
      deleteWithKnownArchivePrevious(key, previous);
    }
  }

  public static final class TestCapsule implements ProtoCapsule<byte[]> {

    private final byte[] value;

    public TestCapsule(byte[] value) {
      this.value = value;
    }

    @Override
    public byte[] getData() {
      return value;
    }

    @Override
    public byte[] getInstance() {
      return value;
    }
  }
}
