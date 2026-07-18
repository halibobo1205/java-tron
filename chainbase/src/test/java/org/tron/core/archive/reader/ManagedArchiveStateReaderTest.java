package org.tron.core.archive.reader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchiveMutationLease;
import org.tron.core.archive.ArchiveSnapshotReleaseException;
import org.tron.core.archive.ArchiveWorkLease;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.ArchiveSnapshotPermit;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryLease;

public class ManagedArchiveStateReaderTest {

  @Test
  public void constructorRejectsNullResponseValidatorBeforeTakingOwnership() {
    assertThrows(NullPointerException.class, () -> new ManagedArchiveStateReader(
        mock(ArchiveStateReader.class), mock(ArchiveWorkLease.class),
        mock(QueryLease.class), mock(ArchiveSnapshotPermit.class),
        mock(ArchiveMutationLease.class), null, ignored -> { }));
  }

  @Test
  public void foreignCloseDoesNotWaitBehindOwnerCleanup() throws Exception {
    CountDownLatch ownerReady = new CountDownLatch(1);
    CountDownLatch startOwnerClose = new CountDownLatch(1);
    CountDownLatch delegateCloseEntered = new CountDownLatch(1);
    CountDownLatch releaseDelegateClose = new CountDownLatch(1);
    ArchiveStateReader delegate = mock(ArchiveStateReader.class);
    doAnswer(invocation -> {
      delegateCloseEntered.countDown();
      assertTrue(releaseDelegateClose.await(2L, TimeUnit.SECONDS));
      return null;
    }).when(delegate).close();
    ArchiveWorkLease lifecycleLease = mock(ArchiveWorkLease.class);
    ArchiveMutationLease mutationLease = mock(ArchiveMutationLease.class);
    ArchiveSnapshotPermit snapshotPermit = mock(ArchiveSnapshotPermit.class);
    QueryLease queryLease = mock(QueryLease.class);
    when(queryLease.getContext()).thenReturn(
        new QueryContext(ArchiveQueryLimits.unlimited()));
    AtomicReference<ManagedArchiveStateReader> reader = new AtomicReference<>();
    AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
    Thread owner = new Thread(() -> {
      try {
        reader.set(new ManagedArchiveStateReader(
            delegate, lifecycleLease, queryLease, snapshotPermit,
            mutationLease, () -> { }, ignored -> { }));
        ownerReady.countDown();
        startOwnerClose.await();
        reader.get().close();
      } catch (Throwable failure) {
        ownerFailure.set(failure);
      }
    }, "archive-managed-reader-owner");
    owner.start();
    assertTrue(ownerReady.await(1L, TimeUnit.SECONDS));
    startOwnerClose.countDown();
    assertTrue(delegateCloseEntered.await(1L, TimeUnit.SECONDS));

    FutureTask<Throwable> foreignClose = new FutureTask<>(() -> {
      try {
        reader.get().close();
        return null;
      } catch (Throwable failure) {
        return failure;
      }
    });
    Thread foreign = new Thread(foreignClose, "archive-managed-reader-foreign");
    foreign.start();
    try {
      assertTrue(foreignClose.get(1L, TimeUnit.SECONDS) instanceof ArchiveException);
    } finally {
      releaseDelegateClose.countDown();
      foreign.join(1_000L);
      owner.join(2_000L);
    }

    assertNull(ownerFailure.get());
    verify(snapshotPermit).close();
    verify(mutationLease).close();
    verify(lifecycleLease).close();
    verify(queryLease).close();
  }

  @Test
  public void ordinaryDelegateCloseFailureDoesNotRetainSnapshotPermit() {
    AssertionError primary = new AssertionError("delegate close failed");
    ArchiveStateReader delegate = mock(ArchiveStateReader.class);
    doAnswer(invocation -> {
      throw primary;
    }).when(delegate).close();
    ArchiveSnapshotPermit snapshotPermit = mock(ArchiveSnapshotPermit.class);
    ArchiveMutationLease mutationLease = mock(ArchiveMutationLease.class);
    ArchiveWorkLease lifecycleLease = mock(ArchiveWorkLease.class);
    QueryLease queryLease = mock(QueryLease.class);
    QueryContext queryContext = new QueryContext(ArchiveQueryLimits.unlimited());
    when(queryLease.getContext()).thenReturn(queryContext);
    AtomicReference<Throwable> fatal = new AtomicReference<>();
    ManagedArchiveStateReader reader = new ManagedArchiveStateReader(
        delegate, lifecycleLease, queryLease, snapshotPermit,
        mutationLease, () -> { }, fatal::set);

    AssertionError first = assertThrows(AssertionError.class, reader::close);
    reader.close();

    assertSame(primary, first);
    assertSame(primary, queryContext.getRecordedFailure());
    assertNull(fatal.get());
    assertFalse(ArchiveSnapshotReleaseException.contains(primary));
    verify(delegate, times(1)).close();
    verify(snapshotPermit, never()).retainAfterUncertainRelease();
    verify(snapshotPermit, times(1)).close();
    verify(mutationLease, times(1)).close();
    verify(lifecycleLease, times(1)).close();
    verify(queryLease, times(1)).close();
  }

  @Test
  public void uncertainNativeReleaseRetainsPermitAndSignalsFatalHandler() {
    AssertionError nativeFailure = new AssertionError("injected native release failure");
    ArchiveSnapshotReleaseException uncertain = new ArchiveSnapshotReleaseException(
        "snapshot release uncertain", nativeFailure);
    ArchiveStateReader delegate = mock(ArchiveStateReader.class);
    doAnswer(invocation -> {
      throw uncertain;
    }).when(delegate).close();
    ArchiveSnapshotPermit snapshotPermit = mock(ArchiveSnapshotPermit.class);
    ArchiveMutationLease mutationLease = mock(ArchiveMutationLease.class);
    ArchiveWorkLease lifecycleLease = mock(ArchiveWorkLease.class);
    QueryLease queryLease = mock(QueryLease.class);
    when(queryLease.getContext()).thenReturn(
        new QueryContext(ArchiveQueryLimits.unlimited()));
    AtomicReference<Throwable> fatal = new AtomicReference<>();
    ManagedArchiveStateReader reader = new ManagedArchiveStateReader(
        delegate, lifecycleLease, queryLease, snapshotPermit,
        mutationLease, () -> { }, fatal::set);

    assertSame(uncertain,
        assertThrows(ArchiveSnapshotReleaseException.class, reader::close));

    assertSame(uncertain, fatal.get());
    verify(snapshotPermit).retainAfterUncertainRelease();
    verify(snapshotPermit).close();
    verify(mutationLease).close();
    verify(lifecycleLease).close();
    verify(queryLease).close();
  }

  @Test
  public void suppressionDisabledOrdinaryFailureDoesNotRetainSnapshotPermit() {
    RuntimeException primary = new SuppressionDisabledException();
    ArchiveStateReader delegate = mock(ArchiveStateReader.class);
    doAnswer(invocation -> {
      throw primary;
    }).when(delegate).close();
    ArchiveSnapshotPermit snapshotPermit = mock(ArchiveSnapshotPermit.class);
    QueryLease queryLease = mock(QueryLease.class);
    when(queryLease.getContext()).thenReturn(
        new QueryContext(ArchiveQueryLimits.unlimited()));
    AtomicReference<Throwable> fatal = new AtomicReference<>();
    ManagedArchiveStateReader reader = new ManagedArchiveStateReader(
        delegate, mock(ArchiveWorkLease.class), queryLease, snapshotPermit,
        mock(ArchiveMutationLease.class), () -> { }, fatal::set);

    assertSame(primary, assertThrows(RuntimeException.class, reader::close));

    assertFalse(ArchiveSnapshotReleaseException.contains(primary));
    assertNull(fatal.get());
    verify(snapshotPermit, never()).retainAfterUncertainRelease();
    verify(snapshotPermit).close();
    verify(queryLease).close();
  }

  private static final class SuppressionDisabledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private SuppressionDisabledException() {
      super("suppression disabled", null, false, false);
    }
  }
}
