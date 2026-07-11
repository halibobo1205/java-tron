package org.tron.core.store;

import java.util.Objects;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.db.TronStoreWithRevoking;

@Slf4j(topic = "DB")
@Component
public class ContractStateStore extends TronStoreWithRevoking<ContractStateCapsule> {

  @Autowired
  private ContractStateStore(@Value("contract-state") String dbName) {
    super(dbName);
  }

  @Override
  public ContractStateCapsule get(byte[] key) {
    return getUnchecked(key);
  }

  @Override
  public void put(byte[] key, ContractStateCapsule item) {
    if (Objects.isNull(key) || Objects.isNull(item)) {
      return;
    }

    byte[] value = item.getData();
    // L4 archive: contract-state is STORE_SPECIFIC and bypasses the base put hook. Read the pre-put
    // value (Erigon prev-value) only when archived; default path is a plain put.
    String dbName = getDbName();
    boolean capture = ArchiveCaptureHolder.capturesStore(dbName);
    ArchivePreviousValue previous = capture ? readArchivePreviousValue(dbName, key) : null;
    revokingDB.put(key, value);
    if (capture && previous.isAvailable()) {
      ArchiveCaptureHolder.capturePut(dbName, key, previous.getValue(), value);
    }
  }

}
