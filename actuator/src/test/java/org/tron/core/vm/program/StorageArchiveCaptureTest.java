package org.tron.core.vm.program;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
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
import org.tron.core.capsule.StorageRowCapsule;
import org.tron.core.store.StorageRowStore;

public class StorageArchiveCaptureTest {

  @After
  public void clearCaptureHolder() {
    ArchiveCaptureHolder.clear();
  }

  @Test
  public void commitCapturesExactPhysicalStorageRowKey() {
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
    ArgumentCaptor<byte[]> rowKeyCaptor = ArgumentCaptor.forClass(byte[].class);
    verify(store).put(rowKeyCaptor.capture(), any());
    assertArrayEquals(
        rowKeyCaptor.getValue(),
        record.getCanonicalKey());
    assertArrayEquals(
        ArchiveStorageKeyCodec.contractStorageKey(address, slot.getData(), 2),
        record.getCanonicalKey());
    assertEquals(32, record.getCanonicalKey().length);
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

  @Test
  public void sloadOriginalValueAvoidsSecondPrevReadAtCommit() {
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    ArchiveCaptureEngine engine = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        new DynamicKeyPolicy(), context);
    ArchiveCaptureHolder.set(engine);
    context.enter(new ArchiveTxPosition(
        7L, 1L, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, new byte[] {1}));

    byte[] address = new byte[21];
    address[0] = 0x41;
    DataWord slot = new DataWord(new byte[] {5});
    byte[] rowKey = ArchiveStorageKeyCodec.contractStorageKey(address, slot.getData(), 0);
    byte[] original = new DataWord(new byte[] {7}).getData();
    StorageRowStore store = mock(StorageRowStore.class);
    when(store.get(any())).thenReturn(new StorageRowCapsule(rowKey, original));
    Storage storage = new Storage(address, store);

    assertArrayEquals(original, storage.getValue(slot).getData());
    storage.put(slot, new DataWord(new byte[] {9}));
    storage.commit();

    verify(store, times(1)).get(any());
    assertArrayEquals(original, engine.records().get(0).getPrevValue().getValue());
  }

  @Test
  public void absentSloadAvoidsSecondPrevReadAtCommit() {
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    ArchiveCaptureEngine engine = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        new DynamicKeyPolicy(), context);
    ArchiveCaptureHolder.set(engine);
    context.enter(new ArchiveTxPosition(
        7L, 1L, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, new byte[] {1}));

    byte[] address = new byte[21];
    address[0] = 0x41;
    DataWord slot = new DataWord(new byte[] {5});
    byte[] rowKey = ArchiveStorageKeyCodec.contractStorageKey(address, slot.getData(), 0);
    StorageRowStore store = mock(StorageRowStore.class);
    when(store.get(any())).thenReturn(new StorageRowCapsule(rowKey, null));
    Storage storage = new Storage(address, store);

    assertNull(storage.getValue(slot));
    storage.put(slot, new DataWord(new byte[] {9}));
    storage.commit();

    verify(store, times(1)).get(any());
    assertTrue(engine.records().get(0).getPrevValue().isDeleted());
  }

  @Test
  public void physicalSlotAliasesPreserveLiveWritesAndChainArchivePrev() {
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    ArchiveCaptureEngine engine = new ArchiveCaptureEngine(
        new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog(),
        new DynamicKeyPolicy(), context);
    ArchiveCaptureHolder.set(engine);
    context.enter(new ArchiveTxPosition(
        7L, 1L, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, new byte[] {1}));

    byte[] address = new byte[21];
    address[0] = 0x41;
    byte[] firstBytes = new byte[32];
    byte[] aliasBytes = new byte[32];
    firstBytes[0] = 1;
    aliasBytes[0] = 3;
    firstBytes[31] = aliasBytes[31] = 5;
    DataWord first = new DataWord(firstBytes);
    DataWord alias = new DataWord(aliasBytes);
    StorageRowStore store = mock(StorageRowStore.class);
    when(store.get(any())).thenReturn(null);
    Storage storage = new Storage(address, store);

    storage.put(first, new DataWord(new byte[] {7}));
    storage.put(alias, new DataWord(new byte[] {9}));
    assertArrayEquals(new DataWord(new byte[] {7}).getData(), storage.getValue(first).getData());
    assertArrayEquals(new DataWord(new byte[] {9}).getData(), storage.getValue(alias).getData());
    storage.commit();

    ArgumentCaptor<StorageRowCapsule> rows = ArgumentCaptor.forClass(StorageRowCapsule.class);
    verify(store, times(2)).put(any(), rows.capture());
    assertEquals(2, engine.rawRecordCount());
    assertEquals(1, engine.records().size());
    assertFalse(engine.failure().isPresent());
    assertTrue(engine.records().get(0).getPrevValue().isDeleted());
    byte[] finalLegacyValue = new DataWord(new byte[] {9}).getData();
    assertArrayEquals(finalLegacyValue, rows.getAllValues().get(1).getValue());
    assertArrayEquals(finalLegacyValue, engine.records().get(0).getValue().getValue());
  }

  @Test
  public void childNamespaceSwitchPreservesLegacyLogicalCacheSemanticsWithArchiveOff() {
    ArchiveCaptureHolder.clear();
    byte[] address = new byte[21];
    address[0] = 0x41;
    DataWord slot = new DataWord(new byte[] {5});
    StorageRowStore store = mock(StorageRowStore.class);
    when(store.get(any())).thenReturn(null);

    Storage parent = new Storage(address, store);
    parent.put(slot, new DataWord(new byte[] {7}));
    Storage child = new Storage(parent);
    byte[] deploymentHash = new byte[ArchiveStorageKeyCodec.DEPLOYMENT_HASH_LEN];
    deploymentHash[0] = 3;
    child.generateAddrHash(deploymentHash);
    child.put(slot, new DataWord(new byte[] {9}));
    child.commit();

    ArgumentCaptor<StorageRowCapsule> rows = ArgumentCaptor.forClass(StorageRowCapsule.class);
    verify(store, times(1)).put(any(), rows.capture());
    assertArrayEquals(new DataWord(new byte[] {9}).getData(), rows.getValue().getValue());
  }
}
