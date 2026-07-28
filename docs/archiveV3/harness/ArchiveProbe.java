/*
 * Offline structural probe for a UNIFIED_V1 archive RocksDB.
 *
 * WHY THIS EXISTS
 *   The archive exposes no "unwound blocks" / "rolled back journal" counter (the full
 *   ArchiveMetrics.incrementWork key list contains no unwind or rollback type), so the only way to
 *   assert "the orphan journal was rolled back exactly once" and "txNum allocation is gap-free
 *   after re-capture" is to read the committed index directly.  There is no offline archive CLI in
 *   this repo (plugins/.../ArchiveManifest.java and DbArchive.java are unrelated LevelDB-manifest
 *   tools), so the harness ships this probe.
 *
 * WHAT IT READS (raw RocksDB, read-only, no production code paths)
 *   INDEX  cf : 0x10 || blockNum(8,BE)            -> encoded ArchiveBlockRange
 *               0x01 || "first-block"             -> firstBlock(8,BE)
 *   META   cf : 0x01 || "repair-required"         -> repair reason blob
 *   INFLIGHT cf: 0x40/0x41/0x42 || blockNum(8,BE) -> journal / ack / token record
 *               (ArchiveInFlightCodec.java:24-26,47-77)
 *
 * NOTE ON IN-FLIGHT RECORDS
 *   Journal records for blocks that are not yet published legitimately survive a clean shutdown —
 *   that is what the journal is for, and startup reconciliation republishes them.  So a non-empty
 *   in-flight column family is NOT a violation.  What is a violation is a journal record for a
 *   block the index already published: publication deletes the journal entry, so a leftover at or
 *   below the published head means a rolled-back or superseded journal was never cleaned up.  That
 *   is the assertion this probe makes, and it is what "the orphan journal was rolled back" looks
 *   like from disk.  Pass --require-empty-inflight when the caller knows the archive was drained.
 *
 *   Key/value layout mirrors chainbase/src/main/java/org/tron/core/archive/txnum/
 *   ArchiveBlockRangeCodec.java (prefixes at :34-38, range value layout in encodeRange/decodeRange).
 *   The contiguity rules mirror UnifiedArchiveTxNumIndex.validateAdjacentRanges /
 *   validateFirstRange, so a violation here is the same violation the node would raise on a full
 *   scrub.
 *
 * USAGE
 *   javac -cp <FullNode.jar> -d <outdir> ArchiveProbe.java
 *   java  -cp <FullNode.jar>:<outdir> ArchiveProbe <archive-unified-dir> \
 *         [--require-genesis-complete] [--require-empty-inflight]
 *
 *   <archive-unified-dir> is <data>/database/archive/unified (the directory holding CURRENT).
 *
 * OUTPUT
 *   One JSON object on stdout.  Exit codes:
 *     0  structurally consistent (and genesis-complete when that was required)
 *     1  a structural violation was found (gaps, repair-required, leftover in-flight, ...)
 *     2  usage error
 *     3  the database could not be opened at all
 *
 * The probe never writes.  It must be run against a stopped node; RocksDB read-only mode does not
 * take the DB lock, but a live writer's newest state may not be visible.
 */

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;

public final class ArchiveProbe {

  private static final byte META_PREFIX = 0x01;
  private static final byte TXNUM_BLOCK_PREFIX = 0x10;
  private static final byte IN_FLIGHT_BLOCK_PREFIX = 0x40;
  private static final byte IN_FLIGHT_ACK_PREFIX = 0x41;
  private static final byte IN_FLIGHT_TOKEN_PREFIX = 0x42;
  private static final int RANGE_MIN_LENGTH = 50;
  private static final String[] SOURCE_NAMES = {"NORMAL", "REPLAY", "RECOVERY"};

  private ArchiveProbe() {
  }

  public static void main(String[] args) {
    if (args.length < 1) {
      System.err.println("usage: ArchiveProbe <archive-unified-dir> [--require-genesis-complete]");
      System.exit(2);
      return;
    }
    String path = args[0];
    boolean requireGenesisComplete = false;
    boolean requireEmptyInFlight = false;
    for (int i = 1; i < args.length; i++) {
      if ("--require-genesis-complete".equals(args[i])) {
        requireGenesisComplete = true;
      } else if ("--require-empty-inflight".equals(args[i])) {
        requireEmptyInFlight = true;
      } else {
        System.err.println("unknown option: " + args[i]);
        System.exit(2);
        return;
      }
    }
    if (!new File(path).isDirectory()) {
      emit(openFailure(path, "not a directory"));
      System.exit(3);
      return;
    }

    RocksDB.loadLibrary();
    Probe probe;
    try {
      probe = read(path);
    } catch (Throwable failure) {
      emit(openFailure(path, describe(failure)));
      System.exit(3);
      return;
    }

    List<String> violations = probe.violations(requireGenesisComplete, requireEmptyInFlight);
    Map<String, Object> out = probe.toMap(path);
    out.put("violations", violations);
    out.put("ok", violations.isEmpty());
    emit(out);
    System.exit(violations.isEmpty() ? 0 : 1);
  }

  private static Map<String, Object> openFailure(String path, String detail) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("path", path);
    out.put("opened", false);
    out.put("openError", detail);
    out.put("ok", false);
    out.put("violations", List.of("archive database could not be opened: " + detail));
    return out;
  }

  // ---------------------------------------------------------------- reading

  private static Probe read(String path) throws Exception {
    List<byte[]> names = RocksDB.listColumnFamilies(new Options(), path);
    if (names.isEmpty()) {
      names = new ArrayList<>();
      names.add(RocksDB.DEFAULT_COLUMN_FAMILY);
    }
    List<ColumnFamilyDescriptor> descriptors = new ArrayList<>(names.size());
    for (byte[] name : names) {
      descriptors.add(new ColumnFamilyDescriptor(name));
    }
    List<ColumnFamilyHandle> handles = new ArrayList<>(names.size());
    DBOptions options = new DBOptions().setCreateIfMissing(false)
        .setCreateMissingColumnFamilies(false);
    RocksDB db = RocksDB.openReadOnly(options, path, descriptors, handles);
    try {
      Map<String, ColumnFamilyHandle> byName = new LinkedHashMap<>();
      for (int i = 0; i < names.size(); i++) {
        byName.put(new String(names.get(i), StandardCharsets.US_ASCII), handles.get(i));
      }
      Probe probe = new Probe();
      probe.columnFamilies = new ArrayList<>(byName.keySet());

      ColumnFamilyHandle index = byName.get("index");
      ColumnFamilyHandle meta = byName.get("meta");
      ColumnFamilyHandle inflight = byName.get("inflight");
      if (index == null || meta == null) {
        probe.structuralError = "archive database is missing the index/meta column families";
        return probe;
      }

      byte[] repair = db.get(meta, metaKey("repair-required"));
      probe.repairRequired = repair != null;
      probe.repairReason = repair == null ? null : printable(repair);

      byte[] firstBlock = db.get(index, metaKey("first-block"));
      probe.firstBlockMarker = firstBlock == null ? -1L : longAt(firstBlock, 0);

      readRanges(db, index, probe);
      if (inflight != null) {
        readInFlight(db, inflight, probe);
      }
      return probe;
    } finally {
      for (ColumnFamilyHandle handle : handles) {
        handle.close();
      }
      db.close();
    }
  }

  /**
   * Classifies every surviving journal record.  A record whose block number is at or below the
   * published head is stale: publication deletes the journal entry, so such a row means a
   * superseded (orphan) journal was never cleaned up.
   */
  private static void readInFlight(RocksDB db, ColumnFamilyHandle cf, Probe probe) {
    long publishedHead = probe.lastRange == null ? -1L : probe.lastRange.blockNum;
    try (ReadOptions readOptions = new ReadOptions().setFillCache(false);
        RocksIterator it = db.newIterator(cf, readOptions)) {
      for (it.seekToFirst(); it.isValid(); it.next()) {
        byte[] key = it.key();
        probe.inFlightKeys++;
        if (key.length != 1 + Long.BYTES) {
          continue;
        }
        long blockNum = longAt(key, 1);
        switch (key[0]) {
          case IN_FLIGHT_BLOCK_PREFIX:
            probe.inFlightBlockRecords++;
            break;
          case IN_FLIGHT_ACK_PREFIX:
            probe.inFlightAckRecords++;
            break;
          case IN_FLIGHT_TOKEN_PREFIX:
            probe.inFlightTokenRecords++;
            break;
          default:
            continue;
        }
        if (probe.inFlightMinBlock < 0L || blockNum < probe.inFlightMinBlock) {
          probe.inFlightMinBlock = blockNum;
        }
        if (blockNum > probe.inFlightMaxBlock) {
          probe.inFlightMaxBlock = blockNum;
        }
        if (publishedHead >= 0L && blockNum <= publishedHead
            && !probe.staleInFlightBlocks.contains(blockNum)) {
          probe.staleInFlightBlocks.add(blockNum);
        }
      }
    }
  }

  /**
   * Walks every {@code 0x10 || blockNum} range row in ascending block order and applies the same
   * adjacency rules the node applies in UnifiedArchiveTxNumIndex.validateAdjacentRanges.
   */
  private static void readRanges(RocksDB db, ColumnFamilyHandle index, Probe probe) {
    try (ReadOptions readOptions = new ReadOptions().setFillCache(false);
        RocksIterator it = db.newIterator(index, readOptions)) {
      Range previous = null;
      for (it.seek(new byte[] {TXNUM_BLOCK_PREFIX}); it.isValid(); it.next()) {
        byte[] key = it.key();
        if (key.length == 0 || key[0] != TXNUM_BLOCK_PREFIX) {
          break;
        }
        if (key.length != 1 + Long.BYTES) {
          probe.malformedRangeKeys++;
          continue;
        }
        long keyBlockNum = longAt(key, 1);
        Range range = decodeRange(it.value());
        if (range == null) {
          probe.malformedRangeValues++;
          continue;
        }
        if (range.blockNum != keyBlockNum) {
          probe.keyValueBlockMismatches.add(keyBlockNum + "!=" + range.blockNum);
        }
        probe.rangeCount++;
        probe.sourceHistogram.merge(sourceName(range.source), 1L, Long::sum);
        if (probe.firstRange == null) {
          probe.firstRange = range;
        }
        probe.lastRange = range;
        long span = range.lastTxNum - range.firstTxNum + 1L;
        if (span < (long) range.userTxCount + 2L) {
          probe.spanViolations.add("block " + range.blockNum + " span=" + span
              + " userTxCount=" + range.userTxCount);
        }
        if (previous != null) {
          if (range.blockNum != previous.blockNum + 1L) {
            probe.blockGaps.add(previous.blockNum + "->" + range.blockNum);
          }
          if (range.firstTxNum != previous.lastTxNum + 1L) {
            probe.txNumGaps.add("block " + range.blockNum + " firstTxNum=" + range.firstTxNum
                + " expected=" + (previous.lastTxNum + 1L));
          }
        }
        previous = range;
      }
    }
  }

  // ---------------------------------------------------------------- decoding

  private static Range decodeRange(byte[] value) {
    if (value == null || value.length < RANGE_MIN_LENGTH || value[0] != 0x01) {
      return null;
    }
    Range range = new Range();
    range.blockNum = longAt(value, 1);
    range.firstTxNum = longAt(value, 9);
    range.lastTxNum = longAt(value, 17);
    range.prepareTxNum = longAt(value, 25);
    range.finalizeTxNum = longAt(value, 33);
    range.userTxCount = intAt(value, 41);
    range.source = value[45] & 0xff;
    int blockHashLen = intAt(value, 46);
    if (blockHashLen < 0 || value.length < 50 + blockHashLen) {
      return null;
    }
    range.blockHash = Arrays.copyOfRange(value, 50, 50 + blockHashLen);
    return range;
  }

  private static byte[] metaKey(String name) {
    byte[] ascii = name.getBytes(StandardCharsets.US_ASCII);
    byte[] key = new byte[ascii.length + 1];
    key[0] = META_PREFIX;
    System.arraycopy(ascii, 0, key, 1, ascii.length);
    return key;
  }

  private static long longAt(byte[] bytes, int offset) {
    long value = 0L;
    for (int i = 0; i < Long.BYTES; i++) {
      value = (value << 8) | (bytes[offset + i] & 0xffL);
    }
    return value;
  }

  private static int intAt(byte[] bytes, int offset) {
    int value = 0;
    for (int i = 0; i < Integer.BYTES; i++) {
      value = (value << 8) | (bytes[offset + i] & 0xff);
    }
    return value;
  }

  private static String sourceName(int ordinal) {
    return ordinal >= 0 && ordinal < SOURCE_NAMES.length
        ? SOURCE_NAMES[ordinal] : ("UNKNOWN_" + ordinal);
  }

  private static String hex(byte[] bytes) {
    if (bytes == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xf, 16));
      sb.append(Character.forDigit(b & 0xf, 16));
    }
    return sb.toString();
  }

  private static String printable(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(b >= 0x20 && b < 0x7f ? (char) b : '.');
    }
    return sb.toString();
  }

  private static String describe(Throwable failure) {
    String message = failure.getMessage();
    return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }

  // ---------------------------------------------------------------- model

  private static final class Range {
    long blockNum;
    long firstTxNum;
    long lastTxNum;
    long prepareTxNum;
    long finalizeTxNum;
    int userTxCount;
    int source;
    byte[] blockHash;
  }

  private static final class Probe {
    List<String> columnFamilies = new ArrayList<>();
    String structuralError;
    boolean repairRequired;
    String repairReason;
    long firstBlockMarker = -1L;
    long inFlightKeys;
    long inFlightBlockRecords;
    long inFlightAckRecords;
    long inFlightTokenRecords;
    long inFlightMinBlock = -1L;
    long inFlightMaxBlock = -1L;
    List<Long> staleInFlightBlocks = new ArrayList<>();
    long rangeCount;
    long malformedRangeKeys;
    long malformedRangeValues;
    Range firstRange;
    Range lastRange;
    List<String> blockGaps = new ArrayList<>();
    List<String> txNumGaps = new ArrayList<>();
    List<String> spanViolations = new ArrayList<>();
    List<String> keyValueBlockMismatches = new ArrayList<>();
    Map<String, Long> sourceHistogram = new TreeMap<>();

    List<String> violations(boolean requireGenesisComplete, boolean requireEmptyInFlight) {
      List<String> out = new ArrayList<>();
      if (structuralError != null) {
        out.add(structuralError);
        return out;
      }
      if (repairRequired) {
        out.add("repair-required is set: " + repairReason);
      }
      if (!staleInFlightBlocks.isEmpty()) {
        out.add("stale journal record(s) at already-published height(s) " + staleInFlightBlocks
            + "; a superseded journal was not rolled back");
      }
      if (requireEmptyInFlight && inFlightKeys > 0L) {
        out.add("in-flight column family still holds " + inFlightKeys
            + " record(s) although the caller required a drained archive");
      }
      if (malformedRangeKeys > 0L) {
        out.add(malformedRangeKeys + " malformed range key(s)");
      }
      if (malformedRangeValues > 0L) {
        out.add(malformedRangeValues + " malformed range value(s)");
      }
      out.addAll(prefix("non-contiguous block range: ", blockGaps));
      out.addAll(prefix("non-contiguous txNum range: ", txNumGaps));
      out.addAll(prefix("range span smaller than userTxCount+2: ", spanViolations));
      out.addAll(prefix("range key/value block mismatch: ", keyValueBlockMismatches));
      if (rangeCount > 0L && firstRange != null) {
        if (firstBlockMarker >= 0L && firstBlockMarker != firstRange.blockNum) {
          out.add("first-block marker " + firstBlockMarker
              + " disagrees with the physically first range " + firstRange.blockNum);
        }
        if (requireGenesisComplete && firstRange.firstTxNum != 0L) {
          out.add("archive is not genesis-complete: first range firstTxNum="
              + firstRange.firstTxNum);
        }
        if (requireGenesisComplete && firstRange.blockNum != 0L) {
          out.add("archive is not genesis-complete: first range block=" + firstRange.blockNum);
        }
      } else if (requireGenesisComplete) {
        out.add("archive holds no committed ranges");
      }
      return out;
    }

    private static List<String> prefix(String prefix, List<String> items) {
      List<String> out = new ArrayList<>(items.size());
      for (String item : items) {
        out.add(prefix + item);
      }
      return out;
    }

    Map<String, Object> toMap(String path) {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("path", path);
      out.put("opened", true);
      out.put("columnFamilies", columnFamilies);
      out.put("repairRequired", repairRequired);
      out.put("repairReason", repairReason);
      out.put("inFlightKeys", inFlightKeys);
      out.put("inFlightBlockRecords", inFlightBlockRecords);
      out.put("inFlightAckRecords", inFlightAckRecords);
      out.put("inFlightTokenRecords", inFlightTokenRecords);
      out.put("inFlightMinBlock", inFlightMinBlock);
      out.put("inFlightMaxBlock", inFlightMaxBlock);
      out.put("staleInFlightBlocks", staleInFlightBlocks);
      out.put("firstBlockMarker", firstBlockMarker);
      out.put("rangeCount", rangeCount);
      out.put("minBlock", firstRange == null ? -1L : firstRange.blockNum);
      out.put("maxBlock", lastRange == null ? -1L : lastRange.blockNum);
      out.put("firstTxNum", firstRange == null ? -1L : firstRange.firstTxNum);
      out.put("lastTxNum", lastRange == null ? -1L : lastRange.lastTxNum);
      out.put("headBlockHash", lastRange == null ? null : hex(lastRange.blockHash));
      out.put("sourceHistogram", sourceHistogram);
      out.put("blockGaps", blockGaps);
      out.put("txNumGaps", txNumGaps);
      out.put("spanViolations", spanViolations);
      return out;
    }
  }

  // ---------------------------------------------------------------- output

  @SuppressWarnings("unchecked")
  private static void emit(Map<String, Object> map) {
    StringBuilder sb = new StringBuilder();
    writeValue(sb, map);
    System.out.println(sb);
  }

  @SuppressWarnings("unchecked")
  private static void writeValue(StringBuilder sb, Object value) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof Map) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        writeString(sb, entry.getKey());
        sb.append(':');
        writeValue(sb, entry.getValue());
      }
      sb.append('}');
    } else if (value instanceof List) {
      sb.append('[');
      boolean first = true;
      for (Object item : (List<Object>) value) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        writeValue(sb, item);
      }
      sb.append(']');
    } else if (value instanceof Number || value instanceof Boolean) {
      sb.append(value);
    } else {
      writeString(sb, String.valueOf(value));
    }
  }

  private static void writeString(StringBuilder sb, String text) {
    sb.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    sb.append('"');
  }
}
