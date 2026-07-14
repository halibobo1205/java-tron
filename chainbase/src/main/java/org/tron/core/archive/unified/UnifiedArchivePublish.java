package org.tron.core.archive.unified;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.tron.core.archive.ArchiveException;

/** Immutable description of one block publication across unified archive column families. */
public final class UnifiedArchivePublish {

  private final Entry journal;
  private final Entry cursor;
  private final Entry blockMarker;
  private final List<Mutation> mutations;

  private UnifiedArchivePublish(Entry journal, Entry cursor, Entry blockMarker,
      List<Mutation> mutations) {
    this.journal = journal;
    this.cursor = cursor;
    this.blockMarker = blockMarker;
    this.mutations = Collections.unmodifiableList(new ArrayList<>(mutations));
  }

  public static Builder builder() {
    return new Builder();
  }

  byte[] journalKey() {
    return journal.key;
  }

  byte[] expectedJournalValue() {
    return journal.value;
  }

  byte[] cursorKey() {
    return cursor.key;
  }

  byte[] cursorValue() {
    return cursor.value;
  }

  byte[] blockMarkerKey() {
    return blockMarker.key;
  }

  byte[] blockMarkerValue() {
    return blockMarker.value;
  }

  List<Mutation> mutations() {
    return mutations;
  }

  /** Builder that keeps lifecycle rows explicit and limits arbitrary mutations to publish data. */
  public static final class Builder {

    private Entry journal;
    private Entry cursor;
    private Entry blockMarker;
    private final List<Mutation> mutations = new ArrayList<>();

    private Builder() {
    }

    /** Journal row to compare and delete as part of the publication batch. */
    public Builder journal(byte[] key, byte[] expectedValue) {
      if (journal != null) {
        throw new ArchiveException("UNIFIED_V1 publish journal is already set");
      }
      journal = requiredEntry(key, expectedValue, "publish journal");
      return this;
    }

    /** Published cursor row written to the meta column family. */
    public Builder cursor(byte[] key, byte[] value) {
      if (cursor != null) {
        throw new ArchiveException("UNIFIED_V1 publish cursor is already set");
      }
      Entry candidate = requiredEntry(key, value, "publish cursor");
      if (!Arrays.equals(candidate.key, UnifiedArchiveManifest.publishedCursorKey())) {
        throw new ArchiveException("UNIFIED_V1 publish cursor key is reserved");
      }
      cursor = candidate;
      return this;
    }

    /** Per-block commit marker written to the block-marker column family. */
    public Builder blockMarker(byte[] key, byte[] value) {
      if (blockMarker != null) {
        throw new ArchiveException("UNIFIED_V1 publish block marker is already set");
      }
      blockMarker = requiredEntry(key, value, "publish block marker");
      return this;
    }

    public Builder put(UnifiedArchiveColumnFamily columnFamily, byte[] key, byte[] value) {
      requirePublishDataColumnFamily(columnFamily);
      mutations.add(Mutation.put(columnFamily, key, value));
      return this;
    }

    public UnifiedArchivePublish build() {
      if (journal == null) {
        throw new ArchiveException("UNIFIED_V1 publish journal is required");
      }
      if (cursor == null) {
        throw new ArchiveException("UNIFIED_V1 publish cursor is required");
      }
      if (blockMarker == null) {
        throw new ArchiveException("UNIFIED_V1 publish block marker is required");
      }
      boolean hasIndexRow = false;
      Set<MutationKey> mutationKeys = new HashSet<>();
      for (Mutation mutation : mutations) {
        if (!mutationKeys.add(new MutationKey(mutation))) {
          throw new ArchiveException("UNIFIED_V1 publish contains a duplicate mutation key");
        }
        if (mutation.columnFamily == UnifiedArchiveColumnFamily.INDEX) {
          hasIndexRow = true;
        }
      }
      if (!hasIndexRow) {
        throw new ArchiveException("UNIFIED_V1 publish requires an index row");
      }
      return new UnifiedArchivePublish(journal, cursor, blockMarker, mutations);
    }

    private static void requirePublishDataColumnFamily(
        UnifiedArchiveColumnFamily columnFamily) {
      if (columnFamily == null) {
        throw new ArchiveException("UNIFIED_V1 publish column family is required");
      }
      switch (columnFamily) {
        case INDEX:
        case LATEST:
        case HISTORY:
        case CHANGESET:
        case COMMITMENT:
          return;
        default:
          throw new ArchiveException("UNIFIED_V1 publish cannot directly mutate "
              + columnFamily.getName());
      }
    }
  }

  private static final class MutationKey {

    private final UnifiedArchiveColumnFamily columnFamily;
    private final byte[] key;

    private MutationKey(Mutation mutation) {
      columnFamily = mutation.columnFamily;
      key = mutation.key;
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

  static final class Mutation {

    private final UnifiedArchiveColumnFamily columnFamily;
    private final byte[] key;
    private final byte[] value;

    private Mutation(UnifiedArchiveColumnFamily columnFamily, byte[] key, byte[] value) {
      this.columnFamily = columnFamily;
      this.key = requiredKey(key, "publish mutation");
      this.value = Arrays.copyOf(value, value.length);
    }

    static Mutation put(UnifiedArchiveColumnFamily columnFamily, byte[] key, byte[] value) {
      if (value == null || value.length == 0) {
        throw new ArchiveException("UNIFIED_V1 publish mutation value is required");
      }
      return new Mutation(columnFamily, key, value);
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
  }

  private static final class Entry {

    private final byte[] key;
    private final byte[] value;

    private Entry(byte[] key, byte[] value) {
      this.key = key;
      this.value = value;
    }
  }

  private static Entry requiredEntry(byte[] key, byte[] value, String what) {
    if (value == null || value.length == 0) {
      throw new ArchiveException("UNIFIED_V1 " + what + " value is required");
    }
    return new Entry(requiredKey(key, what), Arrays.copyOf(value, value.length));
  }

  private static byte[] requiredKey(byte[] key, String what) {
    if (key == null || key.length == 0) {
      throw new ArchiveException("UNIFIED_V1 " + what + " key is required");
    }
    return Arrays.copyOf(key, key.length);
  }
}
