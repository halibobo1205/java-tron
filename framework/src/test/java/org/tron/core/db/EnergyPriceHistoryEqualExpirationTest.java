package org.tron.core.db;

import static org.tron.core.utils.ProposalUtil.ProposalType.ENERGY_FEE;

import org.junit.Assert;
import org.junit.Test;
import org.tron.common.BaseTest;
import org.tron.common.TestConstants;
import org.tron.core.capsule.ProposalCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.db.api.EnergyPriceHistoryLoader;
import org.tron.core.services.jsonrpc.JsonRpcApiUtil;
import org.tron.protos.Protocol.Proposal;
import org.tron.protos.Protocol.Proposal.State;

/**
 * End-to-end regression test for equal-expiration proposal ordering. When two ENERGY_FEE proposals
 * share an expiration time, the rebuilt price history must resolve to the lowest-id (final) value,
 * matching live execution order. Guards the descending-id tie-break in
 * {@link org.tron.core.store.ProposalStore#getSpecifiedProposals}.
 */
public class EnergyPriceHistoryEqualExpirationTest extends BaseTest {

  static {
    Args.setParam(new String[] {"--output-directory", dbPath()}, TestConstants.TEST_CONF);
  }

  private void initProposal(long code, long timestamp, long price, State state) {
    long id = chainBaseManager.getDynamicPropertiesStore().getLatestProposalNum() + 1;
    Proposal proposal = Proposal.newBuilder()
        .putParameters(code, price)
        .setExpirationTime(timestamp)
        .setState(state)
        .setProposalId(id)
        .build();
    ProposalCapsule capsule = new ProposalCapsule(proposal);
    chainBaseManager.getProposalStore().put(capsule.createDbKey(), capsule);
    chainBaseManager.getDynamicPropertiesStore().saveLatestProposalNum(id);
  }

  @Test
  public void rebuildsLowestIdValueForEqualExpiration() {
    long expiration = 1600000000000L;
    long lowIdPrice = 15;
    long highIdPrice = 99;
    initProposal(ENERGY_FEE.getCode(), expiration, lowIdPrice, State.APPROVED);  // id N (lower)
    initProposal(ENERGY_FEE.getCode(), expiration, highIdPrice, State.APPROVED); // id N+1 (higher)

    EnergyPriceHistoryLoader loader = new EnergyPriceHistoryLoader(chainBaseManager);
    loader.getEnergyProposals();
    String history = loader.parseProposalsToStr();

    // Live execution applies highest id first and lowest id last, so the lowest id is the final
    // value. The tail-first parseEnergyFee must resolve a post-expiration timestamp to that price.
    Assert.assertEquals(lowIdPrice, JsonRpcApiUtil.parseEnergyFee(expiration + 1, history));
  }
}
