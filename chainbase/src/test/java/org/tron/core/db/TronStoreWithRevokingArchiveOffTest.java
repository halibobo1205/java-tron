package org.tron.core.db;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
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
    when(db.getDbName()).thenReturn("archive-off-test-store");
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

  private static final class TestStore extends TronStoreWithRevoking<TestCapsule> {

    private TestStore(DB<byte[], byte[]> db) {
      super(db);
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
