package org.tron.core.archive.domain;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class DynamicKeyPolicyTest {

  private final DynamicKeyPolicy policy = new DynamicKeyPolicy();

  private DynamicKeyDecision decide(String key) {
    return policy.decision(key.getBytes(StandardCharsets.US_ASCII));
  }

  @Test
  public void vmAndFeeConfigKeysAreRooted() {
    assertEquals(RootPolicy.IN_GLOBAL_ROOT, decide("ENERGY_FEE").getRootPolicy());
    DynamicKeyDecision cancun = decide("ALLOW_TVM_CANCUN");
    assertEquals(RootPolicy.IN_GLOBAL_ROOT, cancun.getRootPolicy());
    assertEquals(DynamicKeyClass.VM_CONFIG, cancun.getKeyClass());
    assertEquals(ReaderPolicy.HISTORICAL_VM, cancun.getReaderPolicy());
  }

  @Test
  public void headerCursorsAndPriceHistoryAreHistoryOnly() {
    assertEquals(RootPolicy.HISTORY_ONLY, decide("latest_block_header_number").getRootPolicy());
    assertEquals(RootPolicy.HISTORY_ONLY, decide("ENERGY_PRICE_HISTORY").getRootPolicy());
  }

  @Test
  public void migrationMarkersAndStatisticsAreExcluded() {
    DynamicKeyDecision done = decide("ABI_MOVE_DONE");
    assertEquals(RootPolicy.EXCLUDED, done.getRootPolicy());
    assertEquals(DynamicKeyClass.MIGRATION_MARKER, done.getKeyClass());
    assertEquals(RootPolicy.EXCLUDED, decide("TOTAL_STORAGE_POOL").getRootPolicy());
  }

  @Test
  public void unknownKeyKeepsHistoryButNeverRoots() {
    DynamicKeyDecision d = decide("SOME_FUTURE_KEY");
    assertEquals(DynamicKeyClass.UNKNOWN, d.getKeyClass());
    assertEquals(RootPolicy.EXCLUDED, d.getRootPolicy());
    // Keep history (diagnostic) so a future execution-affecting key is not lost.
    assertEquals(HistoryPolicy.FULL_HISTORY, d.getHistoryPolicy());
  }
}
