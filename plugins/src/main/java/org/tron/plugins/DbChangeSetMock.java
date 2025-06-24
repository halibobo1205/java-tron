package org.tron.plugins;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.roaringbitmap.longlong.Roaring64Bitmap;
import org.rocksdb.RocksDBException;
import org.tron.plugins.state.StateType;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;

@Slf4j(topic = "change-set-mock")
@CommandLine.Command(name = "change-set-mock",
    description = "mock change-set data from change-set.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbChangeSetMock implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path for change-set")
  private Path db;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  private static final  String DB = "change-set";

  private static final  String CHANGE_SET = "changeSet";
  private static final  String HISTORY_INDEX = "historyIndex";

  private static final List<String> stateDbs = Arrays.asList(
      "account", "account-asset",
      "code", "contract", "contract-state", "storage-row",
      "delegation", "DelegatedResource",
      "DelegatedResourceAccountIndex",
      "exchange-v2", "asset-issue-v2",
      "votes", "witness"
  );

  private final Map<Bytes, Bytes> changeList = new HashMap<>();


  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(spec.commandLine().getOut());
      return 0;
    }
    return mock();
  }


  private int mock() throws RocksDBException, IOException {
    try (DBInterface change  = DbTool.getDB(this.db, DB);
         DBInterface changeSet  = DbTool.getDB(this.db, CHANGE_SET);
         DBInterface historyIndex  = DbTool.getDB(this.db, HISTORY_INDEX);
         DBIterator iterator = change.iterator()) {
      iterator.seekToFirst();
      long prevNumber = 0;
      if (!iterator.valid()) {
        spec.commandLine().getOut().println("No data found in the database.");
        logger.info("No data found in the database.");
        return 0;
      }
      for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
        final byte[] key = iterator.getKey();
        final byte[] value = iterator.getValue();
        long number = Longs.fromByteArray(Arrays.copyOfRange(key, 0, Long.BYTES));
        if (number < prevNumber) {
          throw new IllegalArgumentException("Invalid pre number: " + ByteArray.toHexString(key));
        }
        if (prevNumber == 0) {
          prevNumber = number;
        } else if (prevNumber != number) {
          clear(prevNumber, changeSet, historyIndex);
          prevNumber = number;
        }
        byte[] other = Arrays.copyOfRange(key, Long.BYTES, key.length);
        String dbName = simpleDecode(other);
        if (!stateDbs.contains(dbName)) {
          throw new IllegalArgumentException("Unsupported db name: " + dbName);
        }
        byte[] realKey = Arrays.copyOfRange(other, dbName.getBytes().length + Integer.BYTES,
            other.length);
        add(StateType.get(dbName), realKey, value);
      }
    }
    return 0;
  }

  private static String simpleDecode(byte[] bytes) {
    byte[] lengthBytes = Arrays.copyOf(bytes, Integer.BYTES);
    int length = Ints.fromByteArray(lengthBytes);
    byte[] value = Arrays.copyOfRange(bytes, Integer.BYTES, Integer.BYTES + length);
    return new String(value);
  }

  private void add(StateType type, byte[] key, byte[] value) {
    changeList.put(Bytes.of(StateType.encodeKey(type, key)), Bytes.of(value));
  }

  public void clear(long prevNumber, DBInterface changeset, DBInterface historyIndex) {
    changeList.forEach((key, value) -> changeset.put(
        Bytes.concatenate(Bytes.ofUnsignedLong(prevNumber), key).toArray(), value.toArray()));
    changeList.keySet().forEach(
        key -> {
          byte[] updateKey = Bytes.concatenate(key, Bytes.ofUnsignedLong(Long.MAX_VALUE)).toArray();
          byte[] indexValue = historyIndex.get(updateKey);
          Roaring64Bitmap historyBitmap = new Roaring64Bitmap();
          if (indexValue != null) {
            try {
              historyBitmap.deserialize(ByteBuffer.wrap(indexValue));
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
          historyBitmap.add(prevNumber);
          historyBitmap.runOptimize();
          historyBitmap.trim();
          long size = historyBitmap.serializedSizeInBytes();
          ByteBuffer value = ByteBuffer.allocate((int) size);
          try {
            historyBitmap.serialize(value);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
          value.flip();
          byte[] serializedData = new byte[value.remaining()];
          value.get(serializedData);
          size = serializedData.length;
          if (size > 2 * 1024) {
            logger.info("historyBitmap size is too large: {}, key: {}", size,
                ByteArray.toHexString(key.toArray()));
            historyIndex.put(
                Bytes.concatenate(key, Bytes.ofUnsignedLong(prevNumber)).toArray(),
                serializedData);
            historyIndex.delete(updateKey);
          } else {
            historyIndex.put(updateKey, serializedData);
          }
        });
    changeList.clear();
  }
}
