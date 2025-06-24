package org.tron.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.rocksdb.RocksDBException;
import org.tron.plugins.state.StateType;
import org.tron.plugins.trie.TrieImpl2;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;

@Slf4j(topic = "mpt-query")
@CommandLine.Command(name = "mpt-query",
    description = "query history data from mpt.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbMptQuery implements Callable<Integer> {

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

  private static final  String MPT = "mpt";
  private static final  String INDEX = "mpt-index";

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
    try (DBInterface index  = DbTool.getDB(this.db, INDEX)) {
      byte[] targetKey = StateType.encodeKey(StateType.get(database), ByteArray.fromHexString(key));
      Bytes32 targetRoot = Bytes32.wrap(index.get(ByteArray.fromLong(block)));
      TrieImpl2 trie = new TrieImpl2(Paths.get(this.db.toString(), MPT).toString(), targetRoot);
      long startTime = System.currentTimeMillis();
      Bytes value = trie.get(Bytes.wrap(targetKey));
      spec.commandLine().getOut().printf(
          "Query mpt time: %d ms%n", System.currentTimeMillis() - startTime);
      if (value != null) {
        if ("account".equalsIgnoreCase(database)) {
          startTime = System.currentTimeMillis();
          Protocol.Account account = Protocol.Account.parseFrom(value.toArray());
          spec.commandLine().getOut().printf(
              "parseFrom completed key: %s, block: %d, balance: %d in %d ms.%n",
              key, block, account.getBalance(), System.currentTimeMillis() - startTime);
        }
      } else {
        throw new IllegalArgumentException(
            "No data found for key: " + key + ", block: " + block);
      }
    }
    return 0;
  }
}
