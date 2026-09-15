package org.tron.core.store;

import com.google.common.collect.Streams;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.core.capsule.ProposalCapsule;
import org.tron.core.db.TronStoreWithRevoking;
import org.tron.core.exception.ItemNotFoundException;
import org.tron.protos.Protocol.Proposal.State;

@Component
public class ProposalStore extends TronStoreWithRevoking<ProposalCapsule> {

  @Autowired
  public ProposalStore(@Value("proposal") String dbName) {
    super(dbName);
  }

  @Override
  public ProposalCapsule get(byte[] key) throws ItemNotFoundException {
    byte[] value = revokingDB.get(key);
    return new ProposalCapsule(value);
  }

  /**
   * get all proposals.
   */
  public List<ProposalCapsule> getAllProposals() {
    return Streams.stream(iterator())
        .map(Map.Entry::getValue)
        .sorted(Comparator.comparingLong(ProposalCapsule::getCreateTime).reversed())
        .collect(Collectors.toList());
  }

  /**
   * Returns proposals in ascending expiration order, ties broken by descending proposal ID.
   *
   * <p>The descending-id tie-break preserves the execution order for equal-expiration proposals:
   * live execution applies the highest id first and the lowest id last (final value), and the
   * energy/bandwidth price-history loaders rebuild from the tail, so the lowest id must be last.
   */
  public List<ProposalCapsule> getSpecifiedProposals(State state, long code) {
    return Streams.stream(iterator())
        .map(Map.Entry::getValue)
        .filter(proposalCapsule -> proposalCapsule.getState().equals(state))
        .filter(proposalCapsule -> proposalCapsule.getParameters().containsKey(code))
        .sorted(Comparator.comparingLong(ProposalCapsule::getExpirationTime)
            .thenComparing(Comparator.comparingLong(ProposalCapsule::getID).reversed()))
        .collect(Collectors.toList());
  }
}
