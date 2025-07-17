package org.tron.plugins;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Slf4j(topic = "query")
@CommandLine.Command(name = "query-asset",
    description = "query data from DB.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbQueryAsset implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path")
  private Path path;
  @CommandLine.Option(names = { "--key", "-k"}, split = ",",
       description = "key for query")
  private List<String> keys;

  @CommandLine.Option(names = { "--id", "-i"},
      description = "asset for query")
  private String assetId;

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
    try (DBInterface database = DbTool.getDB(this.path, "account-asset")) {
      if (keys != null && !keys.isEmpty()) {
        int addressSize = ByteArray.fromHexString(keys.get(0)).length;
        keys.stream().map(ByteArray::fromHexString).map(k -> Bytes.concat(k, assetId.getBytes()))
            .forEach(k -> print(
                ByteArray.toHexString(ByteArray.subArray(k, 0, addressSize))
                    + ":" + assetId, database.get(k)));
      }
      return 0;
    }
  }
  private void print(String key, byte[] b) {
    spec.commandLine().getOut().format("%s,%d", key, Longs.fromByteArray(b)).println();
    logger.info("{}: {}", key, Longs.fromByteArray(b));
  }
}