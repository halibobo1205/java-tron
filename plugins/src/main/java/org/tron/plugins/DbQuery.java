package org.tron.plugins;

import com.google.common.collect.Lists;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.DBUtils;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;


@Slf4j(topic = "query")
@CommandLine.Command(name = "query",
    aliases = {"q"},
    description = "query data from db.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbQuery implements Callable<Integer> {

  enum Type {
    hex(""), block("block"),
    account("account"), exchange("exchange,exchange-v2");

    final List<String> dbs;

    Type(String dbs) {
      this.dbs = Lists.newArrayList(dbs.split(","));
    }
  }

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path for query")
  private Path db;
  @CommandLine.Option(names = { "--keys"},
       description = "key for query in hex")
  private List<String> keys;
  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

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
        DBInterface database  = DbTool.getDB(this.db.getParent(),
            this.db.getFileName().toString())) {
      if (keys != null && !keys.isEmpty()) {
        keys.stream().map(ByteArray::fromHexString).forEach(k -> print(k, database.get(k)));
      }
    }
    return 0;
  }

  private  void print(byte[] k, byte[] b) {
    String dbName;
    if (this.db.endsWith(DBUtils.TMP) || this.db.getParent().endsWith(DBUtils.CHECKPOINT_DB_V2)) {
      dbName = DBUtils.simpleDecode(k);
      k = Arrays.copyOfRange(k, dbName.getBytes().length + 4, k.length);
      b = b.length == 1 ? new byte[0] : Arrays.copyOfRange(b, 1, b.length);
    } else {
      dbName = this.db.getFileName().toString();
    }
    String key = ByteArray.toHexString(k);
    if (b == null || b.length == 0) {
      spec.commandLine().getOut().format("%s\t%s", dbName, key).println();
      logger.info("{}\t{}", dbName, key);
    } else {
      Type type = Arrays.stream(Type.values()).filter(t -> t.dbs.contains(dbName))
          .findFirst().orElse(Type.hex);
      String json;
      switch (type) {
        case block: json = ByteArray.toJson(ByteArray.toBlock(b));
        break;
        case account: json = ByteArray.toJson(ByteArray.toAccount(b));
          break;
        case exchange: json = ByteArray.toJson(ByteArray.toExchange(b));
          break;
        default: json = ByteArray.toHexString(b);
          break;
      }
      spec.commandLine().getOut().format("%s\t%s\t%s", dbName, key, json).println();
      logger.info("{}\t{}\t{}", dbName, key, json);
    }
  }
}
