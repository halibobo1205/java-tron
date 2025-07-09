package org.tron.plugins;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;

@Slf4j(topic = "query")
@CommandLine.Command(name = "query",
    description = "query data from DB.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbQuery implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path")
  private Path path;
  @CommandLine.Option(names = { "--keys"},
       description = "key for query")
  private List<String> keys;

  @CommandLine.Option(names = { "--db"},
      description = "db")
  private String db;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }
    if (!path.toFile().exists()) {
      logger.info(" {} does not exist.", path);
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(String.format("%s does not exist.", path)));
      return 404;
    }
    return query();
  }


  private int query() throws RocksDBException, IOException {
    try (
        DBInterface database = DbTool.getDB(this.path, db)) {

      if (keys != null && !keys.isEmpty()) {
        keys.forEach(k -> print(k, database.get(ByteArray.fromHexString(k))));
      }
      return 0;
    }
  }

  private void print(String key, byte[] b) {
    spec.commandLine().getOut().format("%s", key).println();
    Message message;
    try {
      switch (db) {
        case "account":
          message = Protocol.Account.parseFrom(b);
          break;
        case "witness": {
          message = Protocol.Witness.parseFrom(b);
          break;
        }
        default:
          throw new RuntimeException("Unsupported db: " + db);
      }
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException("Failed to parse entry: " + key, e);
    }
    spec.commandLine().getOut().format("%s%n", ByteArray.prettyPrint(message)).println();
    logger.info("{}: {}", key, ByteArray.prettyPrint(message));
  }
}