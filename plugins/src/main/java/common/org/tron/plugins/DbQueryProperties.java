package org.tron.plugins;

import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;


@Slf4j(topic = "query")
@CommandLine.Command(name = "query-properties",
    description = "query data from dynamicPropertiesStore.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbQueryProperties implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path for dynamicPropertiesStore")
  private Path db;
  @CommandLine.Option(names = { "--keys"},
       description = "key for query")
  private List<String> keys;
  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  private static final  String DB = "properties";

  private static final String FORK_PREFIX = "FORK_VERSION_";
  private static final String DONE_SUFFIX = "_DONE";
  private static final Set<String> IGNORED_PROPERTIES = Sets.newHashSet(
      "VOTE_REWARD_RATE", "SINGLE_REPEAT", "NON_EXISTENT_ACCOUNT_TRANSFER_MIN",
      "ALLOW_TVM_ASSET_ISSUE", "ALLOW_TVM_STAKE",
      "MAX_VOTE_NUMBER", "MAX_FROZEN_NUMBER", "MAINTENANCE_TIME_INTERVAL",
      "LATEST_SOLIDIFIED_BLOCK_NUM", "BLOCK_NET_USAGE",
      "VERSION_NUMBER",
      "SHIELDED_TRANSACTION_FEE",
      "BLOCK_FILLED_SLOTS_INDEX", "BLOCK_FILLED_SLOTS_NUMBER", "BLOCK_FILLED_SLOTS");

  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }
    if (!db.toFile().exists()) {
      logger.info(" {} does not exist.", db);
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(String.format("%s does not exist.", db)));
      return 404;
    }
    return query();
  }


  private int query() throws RocksDBException, IOException {
    try (
        DBInterface database  = DbTool.getDB(this.db, DB);
        DBIterator iterator = database.iterator()) {

      if (keys != null && !keys.isEmpty()) {
        keys.forEach(k -> print(k, database.get(k.getBytes())));
      } else {
        long c = 0;
        for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
          String keyStr = new String(iterator.getKey());
          if (IGNORED_PROPERTIES.contains(keyStr)
              || keyStr.startsWith(FORK_PREFIX) || keyStr.endsWith(DONE_SUFFIX)) {
            continue;
          }
          c++;
          print(keyStr, iterator.getValue());
        }
        spec.commandLine().getOut().format("total key size: %d", c).println();
        logger.info("total key size: {}", c);
      }
    }
    return 0;
  }

  private void print(String key, byte[] b) {
    String v =  ByteArray.toHexString(b);
    spec.commandLine().getOut().format("%s\t%s", key, v).println();
    logger.info("{}\t{}", key, v);
  }
}