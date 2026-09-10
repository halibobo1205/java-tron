package org.tron.core.db;

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Resource;
import org.junit.Test;
import org.tron.common.BaseTest;
import org.tron.common.TestConstants;
import org.tron.core.capsule.ExchangeCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.store.ExchangeStore;
import org.tron.protos.Protocol;

/**
 * Sort-behaviour regression tests for {@link ExchangeStore#getAllExchanges()}. Guards the fix that
 * replaced the never-return-0 comparator (`? 1 : -1`) with contract-safe
 * {@code Comparator.comparingLong(...).reversed()} (createTime descending).
 *
 * <p>Each test uses a disjoint exchange-id range and asserts only on its own ids.
 */
public class ExchangeStoreSortTest extends BaseTest {

  @Resource
  private ExchangeStore exchangeStore;

  static {
    Args.setParam(new String[] {"--output-directory", dbPath()}, TestConstants.TEST_CONF);
  }

  private void put(long id, long createTime) {
    Protocol.Exchange exchange = Protocol.Exchange.newBuilder()
        .setExchangeId(id)
        .setCreateTime(createTime)
        .setCreatorAddress(ByteString.copyFromUtf8("Address" + id))
        .build();
    ExchangeCapsule capsule = new ExchangeCapsule(exchange);
    exchangeStore.put(capsule.createDbKey(), capsule);
  }

  private List<Long> idsInRange(List<ExchangeCapsule> list, long lo, long hi) {
    return list.stream()
        .map(ExchangeCapsule::getID)
        .filter(id -> id >= lo && id <= hi)
        .collect(toList());
  }

  @Test
  public void getAllExchanges_ordersByCreateTimeDescending() {
    put(101, 100L);
    put(102, 300L);
    put(103, 200L);
    // newest (largest createTime) first
    assertEquals(Arrays.asList(102L, 103L, 101L),
        idsInRange(exchangeStore.getAllExchanges(), 101, 103));
  }

  @Test
  public void getAllExchanges_manyEqualCreateTime_returnsAllWithoutThrowing() {
    long lo = 1000;
    // >= 32 elements exercises TimSort's merge path. All-equal keys form one run (no merge), so
    // this does NOT reproduce the old never-return-0 throw -- that form is caught at compile time
    // by ComparatorNeverReturnsZero. Here we only lock the runtime invariant: every equal-time
    // exchange is returned, no exception.
    int n = 40;
    for (int i = 0; i < n; i++) {
      put(lo + i, 500L);
    }
    assertEquals(n, idsInRange(exchangeStore.getAllExchanges(), lo, lo + n - 1).size());
  }
}
