package org.tron.core.store;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.common.utils.ByteArray;
import org.tron.core.db.TronDatabase;
import org.tron.core.db.common.iterator.DBIterator;
import org.tron.core.db2.common.WrappedByteArray;
import org.tron.protos.Protocol;

@Component
public class AccountAssetStore extends TronDatabase<byte[]> {

  @Autowired
  protected AccountAssetStore(@Value("account-asset") String dbName) {
    super(dbName);
  }

  @Override
  public void put(byte[] key, byte[] item) {
    dbSource.putData(key, item);
  }

  @Override
  public void delete(byte[] key) {
    dbSource.deleteData(key);
  }

  @Override
  public byte[] get(byte[] key) {
    return dbSource.getData(key);
  }

  @Override
  public boolean has(byte[] key) {
    return dbSource.getData(key) != null;
  }

  public void putAccount(Protocol.Account account) {
    Map<byte[], byte[]> assets = convert(getAssets(account));
    if (!assets.isEmpty()) {
      updateByBatch(assets);
    }
  }

  public void deleteAccount(byte[] key) {
    Map<byte[], byte[]> assets = convert(getDeletedAssets(key));
    if (!assets.isEmpty()) {
      updateByBatch(assets);
    }
  }

  public Map<WrappedByteArray, WrappedByteArray> getAssets(Protocol.Account account) {
    Map<WrappedByteArray, WrappedByteArray> assets = new HashMap<>();
    account.getAssetV2Map().forEach((k, v) -> {
      byte[] key = Bytes.concat(account.getAddress().toByteArray(), k.getBytes());
      if (v == 0) {
        assets.put(WrappedByteArray.of(key), WrappedByteArray.of(null));
      } else {
        assets.put(WrappedByteArray.of(key), WrappedByteArray.of(Longs.toByteArray(v)));
      }
    });
    return assets;
  }

  public Map<WrappedByteArray, WrappedByteArray> getDeletedAssets(byte[] key) {
    Map<WrappedByteArray, WrappedByteArray> assets = new HashMap<>();
    prefixQuery(key).forEach((k, v) ->
            assets.put(WrappedByteArray.of(k.getBytes()), WrappedByteArray.of(null)));
    return assets;
  }

  public static Map<byte[], byte[]> convert(Map<WrappedByteArray, WrappedByteArray> map) {
    Map<byte[], byte[]> assets = new HashMap<>();
    map.forEach((k, v) -> assets.put(k.getBytes(), v.getBytes()));
    return assets;
  }

  public long getBalance(Protocol.Account account, byte[] key) {
    if (!account.getAssetOptimized()) {
      return 0;
    }
    return getBalance(account.getAddress().toByteArray(), key);
  }

  public long getBalance(byte[] address, byte[] assetId) {
    byte[] value = get(Bytes.concat(address, assetId));
    if (ArrayUtils.isEmpty(value)) {
      return 0;
    }
    return Longs.fromByteArray(value);
  }

  /** Physical rows only, without applying an Account assetV2 overlay. */
  public Map<String, Long> getPhysicalAssets(byte[] address) {
    Map<String, Long> assets = new HashMap<>();
    prefixQuery(address).forEach((key, value) -> {
      byte[] bytes = key.getBytes();
      byte[] assetId = ByteArray.subArray(bytes, address.length, bytes.length);
      assets.put(new String(assetId, StandardCharsets.US_ASCII), Longs.fromByteArray(value));
    });
    return assets;
  }

  /** Streams physical rows in database key order without materializing the full account prefix. */
  public long scanPhysicalAssets(byte[] address, PhysicalAssetConsumer consumer) {
    if (address == null || consumer == null) {
      throw new NullPointerException("address and consumer are required");
    }
    long rows = 0L;
    try (DBIterator iterator = dbSource.rawIterator()) {
      iterator.seek(address);
      while (iterator.hasNext()) {
        Map.Entry<byte[], byte[]> entry = iterator.next();
        byte[] key = entry.getKey();
        if (!startsWith(key, address)) {
          break;
        }
        byte[] assetId = Arrays.copyOfRange(key, address.length, key.length);
        consumer.accept(assetId, Longs.fromByteArray(entry.getValue()));
        rows++;
      }
      return rows;
    } catch (IOException e) {
      throw new IllegalStateException("account-asset prefix iterator close failed", e);
    }
  }

  private static boolean startsWith(byte[] value, byte[] prefix) {
    if (value == null || value.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (value[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  @FunctionalInterface
  public interface PhysicalAssetConsumer {

    void accept(byte[] assetId, long balance);
  }

  public Map<String, Long> getAllAssets(Protocol.Account account) {
    Map<String, Long> assets = new HashMap<>();
    if (account.getAssetOptimized()) {
      assets.putAll(getPhysicalAssets(account.getAddress().toByteArray()));
    }
    account.getAssetV2Map().forEach((k, v) -> assets.put(k, v));
    return assets;
  }
}
