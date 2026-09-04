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

  private static final long ENTRY_OVERHEAD_BYTES = 48L;
  private static final long MUTATION_OVERHEAD_BYTES = 64L;

  private final Entry journal;
  private final Entry journalToken;
  private final Entry acknowledgement;
  private final Entry cursor;
  private final Entry blockMarker;
  private final List<Mutation> mutations;

  private UnifiedArchivePublish(Entry journal, Entry journalToken, Entry acknowledgement,
      Entry cursor, Entry blockMarker, List<Mutation> mutations) {
    this.journal = journal;
    this.journalToken = journalToken;
    this.acknowledgement = acknowledgement;
    this.cursor = cursor;
    this.blockMarker = blockMarker;
    this.mutations = Collections.unmodifiableList(new ArrayList<>(mutations));
  }

  public static Builder builder() {
    return new Builder(Long.MAX_VALUE, Long.MAX_VALUE);
  }

  /** Creates a builder with pre-copy limits for retained bytes and mutation cardinality. */
  public static Builder builder(long maxRetainedBytes, long maxMutations) {
    return new Builder(maxRetainedBytes, maxMutations);
  }

  byte[] journalKey() {
    return journal.key;
  }

  byte[] expectedJournalValue() {
    return journal.value;
  }

  byte[] journalTokenKey() {
    return journalToken.key;
  }

  byte[] expectedJournalTokenValue() {
    return journalToken.value;
  }

  byte[] acknowledgementKey() {
    return acknowledgement.key;
  }

  byte[] expectedAcknowledgementValue() {
    return acknowledgement.value;
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
    private Entry journalToken;
    private Entry acknowledgement;
    private Entry cursor;
    private Entry blockMarker;
    private final List<Mutation> mutations = new ArrayList<>();
    private final long maxRetainedBytes;
    private final long maxMutations;
    private long retainedBytes;
    private long mutationCount;

    private Builder(long maxRetainedBytes, long maxMutations) {
      if (maxRetainedBytes <= 0L || maxMutations <= 0L) {
        throw new IllegalArgumentException("publish limits must be positive");
      }
      this.maxRetainedBytes = maxRetainedBytes;
      this.maxMutations = maxMutations;
    }

    /** Journal row to compare and delete as part of the publication batch. */
    public Builder journal(byte[] key, byte[] expectedValue) {
      if (journal != null) {
        throw new ArchiveException("UNIFIED_V1 publish journal is already set");
      }
      reserveEntry(key, expectedValue, "publish journal");
      journal = requiredEntry(key, expectedValue, "publish journal");
      return this;
    }

    /** Compact token header paired atomically with the immutable journal payload. */
    public Builder journalToken(byte[] key, byte[] expectedValue) {
      if (journalToken != null) {
        throw new ArchiveException("UNIFIED_V1 publish journal token is already set");
      }
      reserveEntry(key, expectedValue, "publish journal token");
      journalToken = requiredEntry(key, expectedValue, "publish journal token");
      return this;
    }

    /** Canonical-commit acknowledgement required before the journal can be published. */
    public Builder acknowledgement(byte[] key, byte[] expectedValue) {
      if (acknowledgement != null) {
        throw new ArchiveException("UNIFIED_V1 publish acknowledgement is already set");
      }
      reserveEntry(key, expectedValue, "publish acknowledgement");
      acknowledgement = requiredEntry(key, expectedValue, "publish acknowledgement");
      return this;
    }

    /** Published cursor row written to the meta column family. */
    public Builder cursor(byte[] key, byte[] value) {
      if (cursor != null) {
        throw new ArchiveException("UNIFIED_V1 publish cursor is already set");
      }
      reserveEntry(key, value, "publish cursor");
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
      reserveEntry(key, value, "publish block marker");
      blockMarker = requiredEntry(key, value, "publish block marker");
      return this;
    }

    public Builder put(UnifiedArchiveColumnFamily columnFamily, byte[] key, byte[] value) {
      requirePublishDataColumnFamily(columnFamily);
      reserveMutation(key, value, false);
      mutations.add(Mutation.put(columnFamily, key, value));
      return this;
    }

    public Builder delete(UnifiedArchiveColumnFamily columnFamily, byte[] key) {
      requirePublishDataColumnFamily(columnFamily);
      reserveMutation(key, null, true);
      mutations.add(Mutation.delete(columnFamily, key));
      return this;
    }

    /** Fails before preparation allocates rows that cannot fit this publication builder. */
    public Builder requireAdditionalCapacity(long estimatedRetainedBytes,
        long estimatedMutations, String what) {
      if (estimatedRetainedBytes < 0L || estimatedMutations < 0L) {
        throw new IllegalArgumentException("publish capacity estimate must be non-negative");
      }
      String operation = what == null ? "UNIFIED_V1 publish" : what;
      long projectedBytes = addSaturated(retainedBytes, estimatedRetainedBytes);
      if (projectedBytes > maxRetainedBytes) {
        throw new ArchiveException(operation + " exceeds retained byte limit "
            + maxRetainedBytes + ": estimatedBytes=" + projectedBytes);
      }
      long projectedMutations = addSaturated(mutationCount, estimatedMutations);
      if (projectedMutations > maxMutations) {
        throw new ArchiveException(operation + " exceeds mutation limit "
            + maxMutations + ": estimatedMutations=" + projectedMutations);
      }
      return this;
    }

    public UnifiedArchivePublish build() {
      if (journal == null) {
        throw new ArchiveException("UNIFIED_V1 publish journal is required");
      }
      if (journalToken == null) {
        throw new ArchiveException("UNIFIED_V1 publish journal token is required");
      }
      if (acknowledgement == null) {
        throw new ArchiveException("UNIFIED_V1 publish acknowledgement is required");
      }
      if (Arrays.equals(journal.key, journalToken.key)
          || Arrays.equals(journal.key, acknowledgement.key)
          || Arrays.equals(journalToken.key, acknowledgement.key)) {
        throw new ArchiveException("UNIFIED_V1 publish journal lifecycle keys must be distinct");
      }
      if (!Arrays.equals(journalToken.value, acknowledgement.value)) {
        throw new ArchiveException(
            "UNIFIED_V1 publish acknowledgement must match its token header");
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
        if (!mutation.delete
            && mutation.columnFamily == UnifiedArchiveColumnFamily.INDEX) {
          hasIndexRow = true;
        }
      }
      if (!hasIndexRow) {
        throw new ArchiveException("UNIFIED_V1 publish requires an index row");
      }
      return new UnifiedArchivePublish(
          journal, journalToken, acknowledgement, cursor, blockMarker, mutations);
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
        case TEMPORAL_PAYLOAD:
        case COMMITMENT:
          return;
        default:
          throw new ArchiveException("UNIFIED_V1 publish cannot directly mutate "
              + columnFamily.getName());
      }
    }

    private void reserveEntry(byte[] key, byte[] value, String what) {
      requireEntryInput(key, value, what);
      reserveBytes(ENTRY_OVERHEAD_BYTES, key.length, value.length);
    }

    private void reserveMutation(byte[] key, byte[] value, boolean delete) {
      if (key == null || key.length == 0) {
        throw new ArchiveException("UNIFIED_V1 publish mutation key is required");
      }
      if (!delete && (value == null || value.length == 0)) {
        throw new ArchiveException("UNIFIED_V1 publish mutation value is required");
      }
      long nextCount = mutationCount == Long.MAX_VALUE
          ? Long.MAX_VALUE : mutationCount + 1L;
      if (nextCount > maxMutations) {
        throw new ArchiveException("UNIFIED_V1 publish exceeds mutation limit "
            + maxMutations);
      }
      reserveBytes(MUTATION_OVERHEAD_BYTES, key.length, value == null ? 0L : value.length);
      mutationCount = nextCount;
    }

    private void reserveBytes(long overhead, long keyBytes, long valueBytes) {
      long added = addSaturated(addSaturated(overhead, keyBytes), valueBytes);
      long next = addSaturated(retainedBytes, added);
      if (next > maxRetainedBytes) {
        throw new ArchiveException("UNIFIED_V1 publish exceeds retained byte limit "
            + maxRetainedBytes + ": estimatedBytes=" + next);
      }
      retainedBytes = next;
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
    private final boolean delete;

    private Mutation(UnifiedArchiveColumnFamily columnFamily, byte[] key, byte[] value,
        boolean delete) {
      this.columnFamily = columnFamily;
      this.key = requiredKey(key, "publish mutation");
      this.value = value == null ? null : Arrays.copyOf(value, value.length);
      this.delete = delete;
    }

    static Mutation put(UnifiedArchiveColumnFamily columnFamily, byte[] key, byte[] value) {
      if (value == null || value.length == 0) {
        throw new ArchiveException("UNIFIED_V1 publish mutation value is required");
      }
      return new Mutation(columnFamily, key, value, false);
    }

    static Mutation delete(UnifiedArchiveColumnFamily columnFamily, byte[] key) {
      return new Mutation(columnFamily, key, null, true);
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

  private static final class Entry {

    private final byte[] key;
    private final byte[] value;

    private Entry(byte[] key, byte[] value) {
      this.key = key;
      this.value = value;
    }
  }

  private static Entry requiredEntry(byte[] key, byte[] value, String what) {
    requireEntryInput(key, value, what);
    return new Entry(requiredKey(key, what), Arrays.copyOf(value, value.length));
  }

  private static void requireEntryInput(byte[] key, byte[] value, String what) {
    if (key == null || key.length == 0) {
      throw new ArchiveException("UNIFIED_V1 " + what + " key is required");
    }
    if (value == null || value.length == 0) {
      throw new ArchiveException("UNIFIED_V1 " + what + " value is required");
    }
  }

  private static byte[] requiredKey(byte[] key, String what) {
    if (key == null || key.length == 0) {
      throw new ArchiveException("UNIFIED_V1 " + what + " key is required");
    }
    return Arrays.copyOf(key, key.length);
  }

  private static long addSaturated(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }
}
