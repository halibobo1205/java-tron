package org.tron.core.vm.program;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Test;
import org.tron.common.runtime.vm.DataWord;
import org.tron.core.archive.ArchiveExecutionContext;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.capture.ArchiveCaptureEngine;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.reader.ArchiveStorageKeyCodec;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.store.StorageRowStore;

public class StorageArchiveCaptureTest {

  @After
  public void clearCaptureHolder() {
    ArchiveCaptureHolder.clear();
  }

  @Test
  public void commitCapturesNormalizedStorageKeyVersion() {
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    ArchiveCaptureEngine engine = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        new DynamicKeyPolicy(), context);
    ArchiveCaptureHolder.set(engine);
    context.enter(new ArchiveTxPosition(
        7L, 1L, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, new byte[] {1}));

    byte[] address = new byte[21];
    address[0] = 0x41;
    address[20] = 0x11;
    StorageRowStore store = mock(StorageRowStore.class);
    when(store.get(any())).thenReturn(null);
    Storage storage = new Storage(address, store);
    storage.setContractVersion(2);
    DataWord slot = new DataWord(new byte[] {5});
    storage.put(slot, new DataWord(new byte[] {9}));

    storage.commit();

    assertEquals(1, engine.records().size());
    ArchiveChangeRecord record = engine.records().get(0);
    assertEquals(ArchiveDomain.CONTRACT_STORAGE, record.getDomain());
    assertArrayEquals(
        ArchiveStorageKeyCodec.contractStorageKey(address, slot.getData(), 2),
        record.getCanonicalKey());
    assertEquals(0, record.getCanonicalKey()[ArchiveStorageKeyCodec.KEY_LEN - 1] & 0xff);
  }

  @Test
  public void commitDoesNotArchiveOrReadPrevOutsideTxContext() {
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    ArchiveCaptureEngine engine = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        new DynamicKeyPolicy(), context);
    ArchiveCaptureHolder.set(engine);

    byte[] address = new byte[21];
    address[0] = 0x41;
    StorageRowStore store = mock(StorageRowStore.class);
    Storage storage = new Storage(address, store);
    storage.setContractVersion(2);
    storage.put(new DataWord(new byte[] {5}), new DataWord(new byte[] {9}));

    storage.commit();

    assertTrue(engine.records().isEmpty());
    assertFalse(engine.failure().isPresent());
    verify(store, never()).get(any());
    verify(store).put(any(), any());
  }

  @Test
  public void commitDuringActiveBlockWithoutTxContextFailsClosed() {
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    ArchiveCaptureEngine engine = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        new DynamicKeyPolicy(), context);
    engine.beginBlockCapture();
    ArchiveCaptureHolder.set(engine);

    byte[] address = new byte[21];
    address[0] = 0x41;
    StorageRowStore store = mock(StorageRowStore.class);
    Storage storage = new Storage(address, store);
    storage.setContractVersion(2);
    storage.put(new DataWord(new byte[] {5}), new DataWord(new byte[] {9}));

    storage.commit();

    assertTrue(engine.records().isEmpty());
    assertTrue(engine.failure().isPresent());
    verify(store, never()).get(any());
    verify(store).put(any(), any());
  }

  @Test
  public void capturePrevReadFailureDoesNotBlockCanonicalWrite() {
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    ArchiveCaptureEngine engine = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        new DynamicKeyPolicy(), context);
    ArchiveCaptureHolder.set(engine);
    context.enter(new ArchiveTxPosition(
        7L, 1L, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, new byte[] {1}));

    byte[] address = new byte[21];
    address[0] = 0x41;
    StorageRowStore store = mock(StorageRowStore.class);
    when(store.get(any())).thenThrow(new IllegalStateException("read failed"));
    Storage storage = new Storage(address, store);
    storage.put(new DataWord(new byte[] {5}), new DataWord(new byte[] {9}));

    storage.commit();

    assertTrue(engine.failure().isPresent());
    assertTrue(engine.records().isEmpty());
    verify(store).put(any(), any());
  }

  @Test
  public void captureKeyFailureDoesNotBlockCanonicalWrite() {
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    ArchiveCaptureEngine engine = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        new DynamicKeyPolicy(), context);
    ArchiveCaptureHolder.set(engine);
    context.enter(new ArchiveTxPosition(
        7L, 1L, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, new byte[] {1}));

    StorageRowStore store = mock(StorageRowStore.class);
    Storage storage = new Storage(new byte[20], store);
    storage.put(new DataWord(new byte[] {5}), new DataWord(new byte[] {9}));

    storage.commit();

    assertTrue(engine.failure().isPresent());
    assertTrue(engine.records().isEmpty());
    verify(store, never()).get(any());
    verify(store).put(any(), any());
  }
}
