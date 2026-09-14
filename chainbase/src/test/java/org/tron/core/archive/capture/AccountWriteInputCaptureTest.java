package org.tron.core.archive.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.After;
import org.junit.Test;
import org.tron.core.archive.ArchiveExecutionContext;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.capture.ArchiveCaptureHolder.AccountAssetPlanningScope;
import org.tron.core.archive.capture.ArchiveCaptureHolder.AccountWriteInput;
import org.tron.core.archive.codec.AccountCanonicalValueCodec;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainDescriptor;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.protos.Protocol.Account;

public class AccountWriteInputCaptureTest {

  @After
  public void clearHolder() {
    ArchiveCaptureHolder.clear();
  }

  @Test
  public void typedCaptureOnlyParsesPreviousAccountBytes() {
    AccountCanonicalValueCodec codec = spy(new AccountCanonicalValueCodec());
    DefaultArchiveDomainCatalog catalog = spy(new DefaultArchiveDomainCatalog());
    ArchiveDomainDescriptor original = catalog.descriptorFor(ArchiveDomain.ACCOUNT);
    when(catalog.descriptorFor(ArchiveDomain.ACCOUNT)).thenReturn(new ArchiveDomainDescriptor(
        original.getDomain(), original.getSourceDbName(), original.getKeyCodec(), codec,
        original.getRootPolicy(), original.getHistoryPolicy(), original.getReaderPolicy()));
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    context.enter(position(41L));
    ArchiveCaptureEngine engine = new ArchiveCaptureEngine(new DefaultArchiveDomainRegistry(),
        catalog, new DynamicKeyPolicy(), context);
    byte[] previous = account(1L).toByteArray();
    AccountWriteInput input = AccountWriteInput.freeze(account(2L));

    engine.captureAccountPut("account", address(), previous, input);

    verify(codec, times(1)).normalizePut(any(byte[].class));
    verify(codec).normalizePut(previous);
    verify(codec).normalizeAccount(input.getAccount());
    assertEquals(1, engine.rawRecordCount());
    assertEquals(1, engine.records().size());
    assertFalse(engine.failure().isPresent());
  }

  @Test
  public void typedCaptureStillAdmitsBeforeNormalizing() {
    AccountCanonicalValueCodec codec = spy(new AccountCanonicalValueCodec());
    DefaultArchiveDomainCatalog catalog = spy(new DefaultArchiveDomainCatalog());
    ArchiveDomainDescriptor original = catalog.descriptorFor(ArchiveDomain.ACCOUNT);
    when(catalog.descriptorFor(ArchiveDomain.ACCOUNT)).thenReturn(new ArchiveDomainDescriptor(
        original.getDomain(), original.getSourceDbName(), original.getKeyCodec(), codec,
        original.getRootPolicy(), original.getHistoryPolicy(), original.getReaderPolicy()));
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    context.enter(position(41L));
    ArchiveCaptureEngine engine = new ArchiveCaptureEngine(new DefaultArchiveDomainRegistry(),
        catalog, new DynamicKeyPolicy(), context, 100L, 1L);
    ArchiveCaptureHolder.set(engine);

    ArchiveCaptureHolder.captureAccountPut("account", address(), new byte[] {(byte) 0xff},
        AccountWriteInput.freeze(account(2L)));

    assertTrue(engine.failure().isPresent());
    assertTrue(engine.failure().get().getCause().getMessage()
        .contains("pipeline resource watermark"));
    verify(codec, never()).normalizePut(any(byte[].class));
    verify(codec, never()).normalizeAccount(any(Account.class));
    assertEquals(0, engine.rawRecordCount());
    assertTrue(engine.records().isEmpty());
  }

  @Test
  public void typedAndRawCaptureKeepIdenticalBudgetsRecordsAndFailureBoundaries() {
    CaptureRun baseline = run(false, Long.MAX_VALUE, Long.MAX_VALUE);
    assertFalse(baseline.engine.failure().isPresent());
    assertEquals(10, baseline.engine.rawRecordCount());
    assertEquals(8, baseline.engine.records().size());
    assertEquivalent(baseline, run(true, Long.MAX_VALUE, Long.MAX_VALUE));

    for (long records = 1L; records <= 10L; records++) {
      assertEquivalent(run(false, records, Long.MAX_VALUE), run(true, records, Long.MAX_VALUE));
    }
    for (long bytes : new TreeSet<>(baseline.bytes)) {
      if (bytes <= 124L) {
        continue;
      }
      for (long limit = bytes - 1L; limit <= bytes + 1L; limit++) {
        assertEquivalent(run(false, Long.MAX_VALUE, limit), run(true, Long.MAX_VALUE, limit));
      }
    }
  }

  @Test
  public void typedCaptureDoesNotBypassClassificationOrFirstFailure() {
    CaptureRun run = new CaptureRun(100L, Long.MAX_VALUE);
    ArchiveCaptureHolder.set(run.engine);
    AccountWriteInput input = AccountWriteInput.freeze(account(1L));
    ArchiveCaptureHolder.captureAccountPut("unknown-account-store", address(), null, input);
    Throwable failure = run.engine.failure().get();
    assertTrue(failure.getCause().getMessage().contains("not classified"));
    ArchiveCaptureHolder.captureAccountPut("contract", address(), null, input);
    assertTrue(failure == run.engine.failure().get());
    assertEquals(0, run.engine.rawRecordCount());
    run.engine.clear();
    ArchiveCaptureHolder.captureAccountPut("contract", address(), null, input);
    assertEquals("account input requires the ACCOUNT domain",
        run.engine.failure().get().getCause().getMessage());
    assertTrue(run.engine.records().isEmpty());
  }

  private static CaptureRun run(boolean typed, long records, long bytes) {
    CaptureRun run = new CaptureRun(records, bytes);
    ArchiveCaptureHolder.set(run.engine);
    byte[] address = address();
    byte[] asset = "1000001".getBytes(StandardCharsets.US_ASCII);
    Account previous = null;
    long[] balances = {1L, 2L, 1L, 0L, 3L};
    for (int i = 0; i < balances.length; i++) {
      run.context.clear();
      run.context.enter(position(i < 2 ? 41L : 40L + i));
      Account next = i == 3 ? null : account(balances[i]);
      byte[] oldBytes = previous == null ? null : previous.toByteArray();
      AccountWriteInput input = next == null ? null : AccountWriteInput.freeze(next);
      byte[] newBytes = input == null ? null : input.getBytes();
      if (next == null) {
        ArchiveCaptureHolder.captureDelete("account", address, oldBytes);
      } else if (typed) {
        ArchiveCaptureHolder.captureAccountPut("account", address, oldBytes, input);
      } else {
        ArchiveCaptureHolder.capturePut("account", address, oldBytes, newBytes);
      }
      try (AccountAssetPlanningScope scope =
          ArchiveCaptureHolder.openAccountAssetPlanning(address, oldBytes, newBytes)) {
        if (scope.isActive()) {
          ArchiveCaptureHolder.captureAccountAsset(address, asset,
              previous == null ? 0L : previous.getBalance(), balances[i]);
        }
      }
      previous = next;
    }
    return run;
  }

  private static void assertEquivalent(CaptureRun expected, CaptureRun actual) {
    assertEquals(expected.bytes, actual.bytes);
    assertEquals(expected.records, actual.records);
    assertEquals(expected.engine.rawRecordCount(), actual.engine.rawRecordCount());
    assertEquals(expected.engine.rawRecordBytes(), actual.engine.rawRecordBytes());
    assertEquals(expected.engine.failure().isPresent(), actual.engine.failure().isPresent());
    if (expected.engine.failure().isPresent()) {
      assertEquals(expected.engine.failure().get().getMessage(),
          actual.engine.failure().get().getMessage());
      assertEquals(expected.engine.failure().get().getCause().getMessage(),
          actual.engine.failure().get().getCause().getMessage());
    }
    List<ArchiveChangeRecord> wanted = expected.engine.records();
    List<ArchiveChangeRecord> got = actual.engine.records();
    assertEquals(wanted.size(), got.size());
    for (int i = 0; i < wanted.size(); i++) {
      assertEquals(wanted.get(i).getTxNum(), got.get(i).getTxNum());
      assertEquals(wanted.get(i).getDomain(), got.get(i).getDomain());
      assertArrayEquals(wanted.get(i).getCanonicalKey(), got.get(i).getCanonicalKey());
      assertTrue(wanted.get(i).getPrevValue().contentEquals(got.get(i).getPrevValue()));
      assertTrue(wanted.get(i).getValue().contentEquals(got.get(i).getValue()));
    }
  }

  private static Account account(long balance) {
    return Account.newBuilder().setBalance(balance).putAssetV2("1000001", balance).build();
  }

  private static byte[] address() {
    byte[] address = new byte[21];
    address[0] = 0x41;
    return address;
  }

  private static ArchiveTxPosition position(long txNum) {
    return new ArchiveTxPosition(txNum, 7L, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, null);
  }

  private static final class CaptureRun {

    private final List<Long> bytes = new ArrayList<>();
    private final List<Long> records = new ArrayList<>();
    private final ArchiveExecutionContext context = new ArchiveExecutionContext();
    private final ArchiveCaptureEngine engine;

    private CaptureRun(long maxRecords, long maxBytes) {
      engine = new ArchiveCaptureEngine(new DefaultArchiveDomainRegistry(),
          new DefaultArchiveDomainCatalog(), new DynamicKeyPolicy(), context,
          maxRecords, maxBytes, bytes::add, records::add);
      engine.beginBlockCapture(123L);
      context.enter(position(41L));
    }
  }
}
