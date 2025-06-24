package org.tron.plugins;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.rocksdb.RocksDBException;
import org.tron.plugins.state.StateType;
import org.tron.plugins.trie.TrieImpl2;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;

@Slf4j(topic = "mpt-mock")
@CommandLine.Command(name = "mpt-mock",
    description = "mock mpt data from change-set.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbMptMock implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path for change-set")
  private Path db;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  private static final  String DB = "change-set";

  private static final  String MPT_DB = "mpt";
  private static final  String MPT_INDEX = "mpt-index";

  private static final List<String> stateDbs = Arrays.asList(
      "account", "account-asset",
      "code", "contract", "contract-state", "storage-row",
      "delegation", "DelegatedResource",
      "DelegatedResourceAccountIndex",
      "exchange-v2", "asset-issue-v2",
      "votes", "witness"
  );

  private final Map<Bytes, Bytes> trieEntryList = new HashMap<>();
  private TrieImpl2 trie;


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
          DBInterface mptIndex  = DbTool.getDB(this.db, MPT_INDEX);
         DBIterator iterator = change.iterator()) {
      iterator.seekToFirst();
      long prevNumber = 0;
      trie = new TrieImpl2(Paths.get(this.db.toString(), MPT_DB).toString(), Bytes32.ZERO);
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
          clear();
          trie.commit();
          trie.flush();
          logger.info("block num: {}, root: {}", prevNumber, trie.getRootHashByte32());
          mptIndex.put(ByteArray.fromLong(prevNumber), trie.getRootHash());
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
    trieEntryList.put(Bytes.of(StateType.encodeKey(type, key)), Bytes.of(value));
  }

  public void clear() {
    trieEntryList.forEach((key, value) -> trie.put(key, value));
    trieEntryList.clear();
  }
}
