package org.tron.plugins;

import com.google.common.primitives.Longs;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
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
import org.tron.protos.Protocol;
import picocli.CommandLine;

@Slf4j(topic = "change-set-query")
@CommandLine.Command(name = "change-set-query",
    description = "query history data from change-set.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbChangeSetQuery implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path for change-set")
  private Path db;

  @CommandLine.Option(names = {"-db", "--database"}, required = true,
      description = "the database to query, e.g. account, code, contract, etc.")
  private String database;

  @CommandLine.Option(names = {"-k", "--key"}, required = true,
      description = "the key to query, it should be in hex format, e.g. 0x1234567890abcdef")
  private String key;

  @CommandLine.Option(names = {"-b", "--block"},  required = true,
      description = "block number to query, default is 0, which means the latest block")
  private long block;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

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

  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(spec.commandLine().getOut());
      return 0;
    }
    return qurey();
  }


  private int qurey() throws RocksDBException, IOException {
    if (stateDbs.contains(database)) {
      spec.commandLine().getOut().printf(
          "Querying database: %s, key: %s, block: %d%n", database, key, block);
    } else {
      throw new IllegalArgumentException("Unsupported database: " + database);
    }
    try (DBInterface changeSet  = DbTool.getDB(this.db, CHANGE_SET);
         DBInterface historyIndex  = DbTool.getDB(this.db, HISTORY_INDEX)) {

      byte[] targetKey = StateType.encodeKey(StateType.get(database), ByteArray.fromHexString(key));
      byte[] targetIndexKey = Bytes.concatenate(Bytes.of(targetKey),
          Bytes.ofUnsignedLong(block)).toArray();
      DBIterator iterator = historyIndex.iterator();
      long startTime = System.currentTimeMillis();
      iterator.seek(targetIndexKey);
      spec.commandLine().getOut().printf("Seek completed key: %s, block: %d in %d ms. %n",
          key, block, System.currentTimeMillis() - startTime);
      if (!iterator.hasNext()) {
        return 0;
      }
      byte[] indexKey =  iterator.getKey();
      long prevNumber = Longs.fromByteArray(
          Arrays.copyOfRange(indexKey, indexKey.length - Long.BYTES, indexKey.length));

      if (prevNumber < block) {
        throw new IllegalArgumentException(
           "Block number " + block + " is greater than the previous number: " + prevNumber);
      }

      Roaring64Bitmap historyBitmap = new Roaring64Bitmap();
      startTime = System.currentTimeMillis();
      historyBitmap.deserialize(ByteBuffer.wrap(iterator.getValue()));
      spec.commandLine().getOut().printf("Read history bitmap for key: %s, block: %d in %d ms. %n",
          key, prevNumber, System.currentTimeMillis() - startTime);

      startTime = System.currentTimeMillis();
      long result = ceiling(historyBitmap, block);
      spec.commandLine().getOut().printf("Ceiling completed for key: %s, block: %d in %d ms. %n",
          key, block,  System.currentTimeMillis() - startTime);
      if (result  != -1) {
        byte[] changeSetKey = Bytes.concatenate(
            Bytes.ofUnsignedLong(result), Bytes.of(targetKey)).toArray();
        startTime = System.currentTimeMillis();
        byte[] value = changeSet.get(changeSetKey);
        spec.commandLine().getOut().printf(
            "Get value completed for key: %s, block: %d in %d ms. %n",
            key, result, System.currentTimeMillis() - startTime);
        if ("account".equalsIgnoreCase(database)) {
          startTime = System.currentTimeMillis();
          Protocol.Account account = Protocol.Account.parseFrom(value);
          spec.commandLine().getOut().printf(
              "parseFrom completed key: %s, block: %d, balance: %d in %d ms.%n",
              key, result, account.getBalance(), System.currentTimeMillis() - startTime);
        }
      } else {
        throw new IllegalArgumentException(
            "No data found for key: " + key + ", block: " + block + ", prevNumber: " + prevNumber);
      }
    }
    return 0;
  }

  /**
   * Find the smallest value in the bitmap that is greater than or equal to the target.
   *
   * @param bitmap The Roaring64Bitmap to search.
   * @param target The target value to find the ceiling for.
   * @return The smallest value in the bitmap that is greater than or equal to the target,
   *         or -1 if no such value exists.
   */
  public long ceiling(Roaring64Bitmap bitmap, long target) {
    if (bitmap.isEmpty()) {
      return -1;
    }
    if (bitmap.contains(target)) {
      return target;
    }
    long rank = bitmap.rankLong(target);
    if (rank == bitmap.getLongCardinality()) {
      return -1;
    }
    return bitmap.select(rank);
  }
}
