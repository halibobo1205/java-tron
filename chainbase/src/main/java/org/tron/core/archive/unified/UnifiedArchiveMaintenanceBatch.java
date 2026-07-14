package org.tron.core.archive.unified;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.tron.core.archive.ArchiveException;

/** Restricted atomic mutations used by temporal maintenance and test-only unwind operations. */
public final class UnifiedArchiveMaintenanceBatch {

  private final List<Mutation> mutations = new ArrayList<>();
  private final Set<MutationKey> keys = new HashSet<>();

  public UnifiedArchiveMaintenanceBatch put(UnifiedArchiveColumnFamily columnFamily,
      byte[] key, byte[] value) {
    requireColumnFamily(columnFamily);
    if (value == null || value.length == 0) {
      throw new ArchiveException("UNIFIED_V1 maintenance value is required");
    }
    add(new Mutation(columnFamily, key, value, false));
    return this;
  }

  public UnifiedArchiveMaintenanceBatch delete(UnifiedArchiveColumnFamily columnFamily,
      byte[] key) {
    requireColumnFamily(columnFamily);
    add(new Mutation(columnFamily, key, null, true));
    return this;
  }

  List<Mutation> mutations() {
    return Collections.unmodifiableList(mutations);
  }

  private void add(Mutation mutation) {
    if (!keys.add(new MutationKey(mutation.columnFamily, mutation.key))) {
      throw new ArchiveException("UNIFIED_V1 maintenance contains a duplicate mutation key");
    }
    mutations.add(mutation);
  }

  private static void requireColumnFamily(UnifiedArchiveColumnFamily columnFamily) {
    if (columnFamily == null) {
      throw new ArchiveException("UNIFIED_V1 maintenance column family is required");
    }
    switch (columnFamily) {
      case INDEX:
      case LATEST:
      case HISTORY:
      case CHANGESET:
      case BLOCK_MARKER:
      case COMMITMENT:
        return;
      default:
        throw new ArchiveException("UNIFIED_V1 maintenance cannot mutate "
            + columnFamily.getName());
    }
  }

  static final class Mutation {

    private final UnifiedArchiveColumnFamily columnFamily;
    private final byte[] key;
    private final byte[] value;
    private final boolean delete;

    private Mutation(UnifiedArchiveColumnFamily columnFamily, byte[] key, byte[] value,
        boolean delete) {
      if (key == null || key.length == 0) {
        throw new ArchiveException("UNIFIED_V1 maintenance key is required");
      }
      this.columnFamily = columnFamily;
      this.key = Arrays.copyOf(key, key.length);
      this.value = value == null ? null : Arrays.copyOf(value, value.length);
      this.delete = delete;
    }

    UnifiedArchiveColumnFamily columnFamily() {
      return columnFamily;
    }

    byte[] key() {
      return key;
    }

    byte[] value() {
      return value;
    }

    boolean isDelete() {
      return delete;
    }
  }

  private static final class MutationKey {

    private final UnifiedArchiveColumnFamily columnFamily;
    private final byte[] key;

    private MutationKey(UnifiedArchiveColumnFamily columnFamily, byte[] key) {
      this.columnFamily = columnFamily;
      this.key = key;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof MutationKey)) {
        return false;
      }
      MutationKey that = (MutationKey) other;
      return columnFamily == that.columnFamily && Arrays.equals(key, that.key);
    }

    @Override
    public int hashCode() {
      return 31 * columnFamily.hashCode() + Arrays.hashCode(key);
    }
  }
}
