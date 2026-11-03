package org.tron.core.db2.core;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.tron.core.db2.common.Key;
import org.tron.core.db2.common.Value;

public final class StoreLayers {

  private final SnapshotImpl[] newestFirst;
  private final Set<Snapshot> members;
  private final SnapshotRoot root;

  StoreLayers(List<SnapshotImpl> newestFirst, SnapshotRoot root) {
    this.newestFirst = newestFirst.toArray(new SnapshotImpl[0]);
    Set<Snapshot> identity = Collections.newSetFromMap(new IdentityHashMap<>());
    identity.addAll(newestFirst);
    this.members = Collections.unmodifiableSet(identity);
    this.root = root;
  }

  public boolean contains(Snapshot layer) {
    return layer != null && members.contains(layer);
  }

  public byte[] get(byte[] key) {
    Key wrapped = Key.of(key);
    for (SnapshotImpl layer : newestFirst) {
      Value value = layer.getDb().get(wrapped);
      if (value != null) {
        return value.getBytes();
      }
    }
    return root.getDb().get(key);
  }
}
