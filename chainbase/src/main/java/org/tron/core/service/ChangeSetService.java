package org.tron.core.service;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.error.TronDBException;
import org.tron.common.utils.ByteArray;
import org.tron.core.db2.common.Value;

@Slf4j(topic = "DB")
public class ChangeSetService {

  private static final byte[] HEADER_KEY = "latest_block_header_number".getBytes();

  private static final List<String> stateDbs = Arrays.asList(
      "account", "account-asset",
      "code", "contract", "contract-state", "storage-row",
      "delegation", "DelegatedResource",
      "DelegatedResourceAccountIndex",
      "exchange-v2", "asset-issue-v2",
      "votes", "witness"
  );

  public static Map<byte[], byte[]> preparedChangeSet(Map<byte[], byte[]> rows) {
    Map<byte[], byte[]> changesData = new HashMap<>(rows.size());
    AtomicReference<Optional<Long>> height = new AtomicReference<>(Optional.empty());
    for (Map.Entry<byte[], byte[]> e : rows.entrySet()) {
      byte[] key = e.getKey();
      String dbName = simpleDecode(key);
      byte[] value = e.getValue();
      byte[] realValue = value.length == 1 ? null : Arrays.copyOfRange(value, 1, value.length);
      byte[] realKey = Arrays.copyOfRange(key, dbName.getBytes().length + Integer.BYTES,
          key.length);
      if ("properties".equals(dbName) && Arrays.equals(HEADER_KEY, realKey)) {
        height.set(Optional.of(ByteArray.toLong(realValue)));
      }
      if (!stateDbs.contains(dbName)) {
        continue;
      }

      if (realValue != null) {
        changesData.put(key, realValue);
      } else {
        if (Value.Operator.DELETE.getValue() != value[0]) {
          changesData.put(key, ByteString.EMPTY.toByteArray());
        }
      }
    }
    long num = height.get().orElseThrow(() -> new TronDBException("blockNum is null"));
    return changesData.entrySet().stream().collect(Collectors.toMap(
        e -> Bytes.concat(ByteArray.fromLong(num), e.getKey()),
        Map.Entry::getValue
    ));
  }

  private static String simpleDecode(byte[] bytes) {
    byte[] lengthBytes = Arrays.copyOf(bytes, Integer.BYTES);
    int length = Ints.fromByteArray(lengthBytes);
    byte[] value = Arrays.copyOfRange(bytes, Integer.BYTES, Integer.BYTES + length);
    return new String(value);
  }
}
