package org.tron.core.db;

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import javax.annotation.Resource;
import org.junit.Test;
import org.tron.common.BaseTest;
import org.tron.common.TestConstants;
import org.tron.core.capsule.ProposalCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.store.ProposalStore;
import org.tron.protos.Protocol.Proposal;
import org.tron.protos.Protocol.Proposal.State;

/**
 * Sort-behaviour regression tests for {@link ProposalStore}. Guards the fix that replaced the
 * never-return-0 comparators (`? 1 : -1`) with contract-safe {@code Comparator.comparingLong}.
 *
 * <p>Each test uses a disjoint proposal-id range and asserts only on its own ids, so the tests are
 * independent of each other despite BaseTest sharing one DB per class.
 */
public class ProposalStoreSortTest extends BaseTest {

  @Resource
  private ProposalStore proposalStore;

  static {
    Args.setParam(new String[] {"--output-directory", dbPath()}, TestConstants.TEST_CONF);
  }

  private void put(long id, long createTime, long expirationTime, State state, int paramKey) {
    Proposal proposal = Proposal.newBuilder()
        .setProposalId(id)
        .setCreateTime(createTime)
        .setExpirationTime(expirationTime)
        .setState(state)
        .putParameters(paramKey, 1L)
        .build();
    proposalStore.put(Long.toString(id).getBytes(), new ProposalCapsule(proposal));
  }

  private List<Long> idsInRange(List<ProposalCapsule> list, long lo, long hi) {
    return list.stream()
        .map(ProposalCapsule::getID)
        .filter(id -> id >= lo && id <= hi)
        .collect(toList());
  }

  @Test
  public void getAllProposals_ordersByCreateTimeDescending() {
    put(101, 100L, 0L, State.PENDING, 1);
    put(102, 300L, 0L, State.PENDING, 1);
    put(103, 200L, 0L, State.PENDING, 1);
    // newest (largest createTime) first
    assertEquals(Arrays.asList(102L, 103L, 101L),
        idsInRange(proposalStore.getAllProposals(), 101, 103));
  }

  @Test
  public void getSpecifiedProposals_ordersByExpirationAscending() {
    put(201, 0L, 300L, State.PENDING, 7);
    put(202, 0L, 100L, State.PENDING, 7);
    put(203, 0L, 200L, State.PENDING, 7);
    // soonest expiration first
    assertEquals(Arrays.asList(202L, 203L, 201L),
        idsInRange(proposalStore.getSpecifiedProposals(State.PENDING, 7), 201, 203));
  }

  @Test
  public void getSpecifiedProposals_equalExpiration_breaksTiesByIdDescending() {
    // Equal-expiration proposals must be returned highest-id-first. Live execution applies the
    // highest id first and the lowest id last (final value), and the price-history loaders rebuild
    // from the tail -- so the lowest id must be last. The pre-fix ascending-id order inverted this
    // and reconstructed the wrong energy/bandwidth price. This test fails on that broken order.
    put(401, 0L, 500L, State.APPROVED, 9);
    put(402, 0L, 500L, State.APPROVED, 9);
    put(403, 0L, 500L, State.APPROVED, 9);
    assertEquals(Arrays.asList(403L, 402L, 401L),
        idsInRange(proposalStore.getSpecifiedProposals(State.APPROVED, 9), 401, 403));
  }

  @Test
  public void getAllProposals_manyEqualCreateTime_returnsAllWithoutThrowing() {
    long lo = 1000;
    // >= 32 elements exercises TimSort's merge path. All-equal keys form one run (no merge), so
    // this does NOT reproduce the old never-return-0 throw -- that form is caught at compile time
    // by ComparatorNeverReturnsZero. Here we only lock the runtime invariant: every equal-time
    // proposal is returned, no exception.
    int n = 40;
    for (int i = 0; i < n; i++) {
      put(lo + i, 500L, 0L, State.PENDING, 1);
    }
    assertEquals(n, idsInRange(proposalStore.getAllProposals(), lo, lo + n - 1).size());
  }
}
