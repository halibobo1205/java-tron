package org.tron.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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
  @CommandLine.Option(names = {"--detail"},
      description = "when scan all key ,if print value")
  private boolean detail;
  @CommandLine.Option(names = {"--hex"}, defaultValue = "true",
      description = "print value formatted.  Default: ${DEFAULT-VALUE}")
  private boolean hex;
  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  private static final  String DB = "properties";


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
        keys.stream().map(String::getBytes).forEach(k -> print(k, database.get(k)));
      } else {
        long c = 0;
        for (iterator.seekToFirst(); iterator.hasNext(); iterator.next(), c++) {
          print(iterator.getKey(), detail ? iterator.getValue() : new byte[0]);
        }
        spec.commandLine().getOut().format("total key size: %d", c).println();
        logger.info("total key size: {}", c);
      }
    }
    return 0;
  }

  private  void print(byte[] k, byte[] b) {
    String v = hex ? ByteArray.toHexString(b) : ByteArray.toStr(b);
    String key = new String(k);
    if (b.length == 0) {
      spec.commandLine().getOut().format("%s", key).println();
      logger.info("{}", key);
    } else {
      int i = ByteArray.toInt(b);
      long l = ByteArray.toLong(b);
      spec.commandLine().getOut().format("%s\t%s\t%s\t%s", key, i, l, v).println();
      logger.info("{}\t{}\t{}\t{}", key, i, l, v);
    }
  }
}
