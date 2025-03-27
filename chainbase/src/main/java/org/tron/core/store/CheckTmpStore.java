package org.tron.core.store;

import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Map;
import java.util.Optional;
import java.util.Spliterator;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.tron.common.storage.WriteOptionsWrapper;
import org.tron.common.utils.Pair;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.db.TronDatabase;
import org.tron.core.exception.BadItemException;
import org.tron.core.exception.ItemNotFoundException;
import org.tron.core.service.RootHashService;

@Component
public class CheckTmpStore extends TronDatabase<byte[]> {

  @Autowired
  private StateRootStore stateRootStore;

  @Autowired
  public CheckTmpStore(ApplicationContext ctx) {
    super("tmp");
  }

  @Override
  public void put(byte[] key, byte[] item) {
  }

  @Override
  public void delete(byte[] key) {
  }

  @Override
  public byte[] get(byte[] key)
      throws InvalidProtocolBufferException, ItemNotFoundException, BadItemException {
    return null;
  }

  @Override
  public boolean has(byte[] key) {
    return false;
  }

  @Override
  public void forEach(Consumer action) {

  }

  @Override
  public Spliterator spliterator() {
    return null;
  }

  @Override
  public void updateByBatch(Map<byte[], byte[]> rows, WriteOptionsWrapper writeOptions) {
    Pair<Optional<Long>, Sha256Hash> ret = RootHashService.getRootHash(rows);
    super.updateByBatch(rows, writeOptions);
    ret.getKey().ifPresent(height -> stateRootStore.put(height, ret.getValue()));
  }
}
