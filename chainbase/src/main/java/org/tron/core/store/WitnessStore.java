package org.tron.core.store;

import com.google.common.collect.Streams;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.config.Parameter;
import org.tron.core.db.TronStoreWithRevoking;

@Slf4j(topic = "DB")
@Component
public class WitnessStore extends TronStoreWithRevoking<WitnessCapsule> {

  @Autowired
  DynamicPropertiesStore dynamicPropertiesStore;

  @Autowired
  protected WitnessStore(@Value("witness") String dbName) {
    super(dbName);
  }

  /**
   * get all witnesses.
   */
  public List<WitnessCapsule> getAllWitnesses() {
    return Streams.stream(iterator())
        .map(Entry::getValue)
        .collect(Collectors.toList());
  }

  @Override
  public WitnessCapsule get(byte[] key) {
    byte[] value = revokingDB.getUnchecked(key);
    return ArrayUtils.isEmpty(value) ? null : new WitnessCapsule(value);
  }

  public List<WitnessCapsule> getWitnessStandby() {
    List<WitnessCapsule> ret = getTopSortedWitnesses(
        Parameter.ChainConstant.WITNESS_STANDBY_LENGTH);
    // trim voteCount = 0
    ret.removeIf(w -> w.getVoteCount() < 1);
    return ret;
  }

  /**
   * get all sorted witnesses.
   */
  public List<WitnessCapsule> getAllSortedWitnesses() {
    List<WitnessCapsule> all = getAllWitnesses();
    sortWitnesses(all);
    return all;
  }

  public List<WitnessCapsule> getTopSortedWitnesses(int limit) {
    List<WitnessCapsule> all = getAllSortedWitnesses();
    if (all.size() > limit) {
      return new ArrayList<>(all.subList(0, limit));
    } else {
      return new ArrayList<>(all);
    }
  }

  public void sortWitnesses(List<WitnessCapsule> witnesses) {
    witnesses.sort(Comparator.comparingLong(WitnessCapsule::getVoteCount).reversed()
        .thenComparing(dynamicPropertiesStore.allowWitnessSortOpt()
            ? Comparator.comparing(WitnessCapsule::createReadableString).reversed()
            : Comparator.comparingInt((WitnessCapsule w) -> w.getAddress().hashCode()).reversed()));
  }
}
