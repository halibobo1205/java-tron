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

  public static final long DEFAULT_MAX_RETAINED_BYTES = 256L * 1024L * 1024L;
  public static final long DEFAULT_MAX_MUTATIONS = 4_000_000L;

  private final List<Mutation> mutations = new ArrayList<>();
  private final Set<MutationKey> keys = new HashSet<>();
  private final long maxRetainedBytes;
  private final long maxMutations;
  private long retainedBytes;

  public UnifiedArchiveMaintenanceBatch() {
    this(DEFAULT_MAX_RETAINED_BYTES, DEFAULT_MAX_MUTATIONS);
  }

  /** Creates an atomic maintenance batch whose limits are checked before key/value copies. */
  public static UnifiedArchiveMaintenanceBatch bounded(
      long maxRetainedBytes, long maxMutations) {
    return new UnifiedArchiveMaintenanceBatch(maxRetainedBytes, maxMutations);
  }

  private UnifiedArchiveMaintenanceBatch(long maxRetainedBytes, long maxMutations) {
    if (maxRetainedBytes <= 0L || maxMutations <= 0L) {
      throw new IllegalArgumentException("maintenance limits must be positive");
    }
    this.maxRetainedBytes = maxRetainedBytes;
    this.maxMutations = maxMutations;
  }

  public UnifiedArchiveMaintenanceBatch put(UnifiedArchiveColumnFamily columnFamily,
      byte[] key, byte[] value) {
    requireColumnFamily(columnFamily);
    if (value == null || value.length == 0) {
      throw new ArchiveException("UNIFIED_V1 maintenance value is required");
    }
    add(columnFamily, key, value, false);
    return this;
  }

  public UnifiedArchiveMaintenanceBatch delete(UnifiedArchiveColumnFamily columnFamily,
      byte[] key) {
    requireColumnFamily(columnFamily);
    add(columnFamily, key, null, true);
    return this;
  }

  List<Mutation> mutations() {
    return Collections.unmodifiableList(mutations);
  }

  private void add(UnifiedArchiveColumnFamily columnFamily, byte[] key, byte[] value,
      boolean delete) {
    if (key == null || key.length == 0) {
      throw new ArchiveException("UNIFIED_V1 maintenance key is required");
    }
    long nextCount = mutations.size() == Integer.MAX_VALUE
        ? Long.MAX_VALUE : (long) mutations.size() + 1L;
    if (nextCount > maxMutations) {
      throw new ArchiveException("UNIFIED_V1 maintenance exceeds mutation limit "
          + maxMutations);
    }
    long added = addSaturated(64L, key.length);
    added = addSaturated(added, value == null ? 0L : value.length);
    long nextBytes = addSaturated(retainedBytes, added);
    if (nextBytes > maxRetainedBytes) {
      throw new ArchiveException("UNIFIED_V1 maintenance exceeds retained byte limit "
          + maxRetainedBytes + ": estimatedBytes=" + nextBytes);
    }
    Mutation mutation = new Mutation(columnFamily, key, value, delete);
    if (!keys.add(new MutationKey(mutation.columnFamily, mutation.key))) {
      throw new ArchiveException("UNIFIED_V1 maintenance contains a duplicate mutation key");
    }
    mutations.add(mutation);
    retainedBytes = nextBytes;
  }

  private static long addSaturated(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
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
      case TEMPORAL_PAYLOAD:
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
