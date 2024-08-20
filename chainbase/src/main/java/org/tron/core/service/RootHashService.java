package org.tron.core.service;

import com.google.common.collect.Streams;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.context.GlobalContext;
import org.tron.common.error.TronDBException;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.MerkleRoot;
import org.tron.common.utils.Pair;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.db.TronDatabase;
import org.tron.core.store.CorruptedCheckpointStore;

@Slf4j(topic = "DB")
@Component
public class RootHashService {

  private static final byte[] HEADER_KEY = Bytes.concat(simpleEncode("properties"),
    "latest_block_header_number".getBytes());

  private static Optional<CorruptedCheckpointStore> corruptedCheckpointStore = Optional.empty();

  @Autowired
  public RootHashService(@Autowired CorruptedCheckpointStore corruptedCheckpointStore) {
    RootHashService.corruptedCheckpointStore = Optional.ofNullable(corruptedCheckpointStore);
  }

  public static Pair<Optional<Long>, Sha256Hash> getRootHash(Map<byte[], byte[]> rows) {
    AtomicReference<Optional<Long>> height = new AtomicReference<>(Optional.empty());
    List<Sha256Hash> ids = Streams.stream(rows.entrySet()).parallel().map(entry -> {
      if (Arrays.equals(HEADER_KEY, entry.getKey())) {
        height.set(Optional.of(ByteArray.toLong(entry.getValue())));
      }
      return getHash(entry);
    }).sorted().collect(Collectors.toList());
    Sha256Hash actual = MerkleRoot.root(ids);
    long num = height.get().orElseThrow(() -> new TronDBException("blockNum is null"));
    Optional<Sha256Hash> expected = GlobalContext.popBlockHash(num);
    if (expected.isPresent() && !Objects.equals(expected.get(), actual)) {
      corruptedCheckpointStore.ifPresent(TronDatabase::reset);
      corruptedCheckpointStore.ifPresent(store -> store.updateByBatch(rows));
      throw new TronDBException(String.format(
          "Root hash mismatch for blockNum: %s, expected: %s, actual: %s", num, expected, actual));
    }

    return new Pair<>(height.get(), actual);
  }

  private static Sha256Hash getHash(Map.Entry<byte[], byte[]> entry) {
    return  Sha256Hash.of(true, Bytes.concat(entry.getKey(), entry.getValue()));
  }

  private static byte[] simpleEncode(String s) {
    byte[] bytes = s.getBytes();
    byte[] length = Ints.toByteArray(bytes.length);
    byte[] r = new byte[4 + bytes.length];
    System.arraycopy(length, 0, r, 0, 4);
    System.arraycopy(bytes, 0, r, 4, bytes.length);
    return r;
  }
}
