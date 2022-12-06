package org.tron.core.store;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.core.capsule.DelegatedResourceAccountIndexCapsule;
import org.tron.core.db.TronStoreWithRevoking;

@Component
public class DelegatedResourceAccountIndexStore extends
    TronStoreWithRevoking<DelegatedResourceAccountIndexCapsule> {

  private static final byte[] FROM_PREFIX = {0x01};
  private static final byte[] TO_PREFIX = {0x02};

  @Autowired
  public DelegatedResourceAccountIndexStore(@Value("DelegatedResourceAccountIndex") String dbName) {
    super(dbName, DelegatedResourceAccountIndexCapsule.class);
  }

  @Override
  public DelegatedResourceAccountIndexCapsule get(byte[] key) {
    return getNonEmpty(key);
  }

  private byte[] createKey(byte[] prefix, byte[] address1, byte[] address2) {
    byte[] key = new byte[prefix.length + address1.length + address2.length];
    System.arraycopy(prefix, 0, key, 0, prefix.length);
    System.arraycopy(address1, 0, key, prefix.length, address1.length);
    System.arraycopy(address2, 0, key, prefix.length + address1.length, address2.length);
    return key;
  }

  public void convert(byte[] address) {
    DelegatedResourceAccountIndexCapsule indexCapsule = this.get(address);
    if (indexCapsule == null) {
      // convert complete or have no delegate
      return;
    }
    // convert old data
    List<ByteString> toList = indexCapsule.getToAccountsList();
    for (int i = 0; i < toList.size(); i++) {
      // use index as the timestamp, just to keep index in order
      this.delegate(address, toList.get(i).toByteArray(), i + 1L);
    }

    List<ByteString> fromList = indexCapsule.getFromAccountsList();
    for (int i = 0; i < fromList.size(); i++) {
      // use index as the timestamp, just to keep index in order
      this.delegate(fromList.get(i).toByteArray(), address, i + 1L);
    }
    this.delete(address);
  }

  public void delegate(byte[] from, byte[] to, long time) {
    byte[] fromKey = createKey(FROM_PREFIX, from, to);
    DelegatedResourceAccountIndexCapsule toIndexCapsule =
        new DelegatedResourceAccountIndexCapsule(ByteString.copyFrom(to));
    toIndexCapsule.setTimestamp(time);
    this.put(fromKey, toIndexCapsule);

    byte[] toKey = createKey(TO_PREFIX, to, from);
    DelegatedResourceAccountIndexCapsule fromIndexCapsule =
        new DelegatedResourceAccountIndexCapsule(ByteString.copyFrom(from));
    fromIndexCapsule.setTimestamp(time);
    this.put(toKey, fromIndexCapsule);
  }

  public void unDelegate(byte[] from, byte[] to) {
    byte[] fromKey = createKey(FROM_PREFIX, from, to);
    this.delete(fromKey);
    byte[] toKey = createKey(TO_PREFIX, to, from);
    this.delete(toKey);
  }

  public DelegatedResourceAccountIndexCapsule getIndex(byte[] address) {
    DelegatedResourceAccountIndexCapsule indexCapsule = get(address);
    if (indexCapsule != null) {
      return indexCapsule;
    }

    DelegatedResourceAccountIndexCapsule tmpIndexCapsule =
        new DelegatedResourceAccountIndexCapsule(ByteString.copyFrom(address));
    byte[] key = new byte[FROM_PREFIX.length + address.length];

    System.arraycopy(FROM_PREFIX, 0, key, 0, FROM_PREFIX.length);
    System.arraycopy(address, 0, key, FROM_PREFIX.length, address.length);
    List<DelegatedResourceAccountIndexCapsule> tmpToList =
        new ArrayList<>(this.prefixQuery(key).values());

    tmpToList.sort(Comparator.comparing(DelegatedResourceAccountIndexCapsule::getTimestamp));
    List<ByteString> list = tmpToList.stream()
        .map(DelegatedResourceAccountIndexCapsule::getAccount).collect(Collectors.toList());
    tmpIndexCapsule.setAllToAccounts(list);

    System.arraycopy(TO_PREFIX, 0, key, 0, TO_PREFIX.length);
    System.arraycopy(address, 0, key, TO_PREFIX.length, address.length);
    List<DelegatedResourceAccountIndexCapsule> tmpFromList =
        new ArrayList<>(this.prefixQuery(key).values());
    tmpFromList.sort(Comparator.comparing(DelegatedResourceAccountIndexCapsule::getTimestamp));
    list = tmpFromList.stream().map(DelegatedResourceAccountIndexCapsule::getAccount).collect(
        Collectors.toList());
    tmpIndexCapsule.setAllFromAccounts(list);
    return tmpIndexCapsule;
  }

}