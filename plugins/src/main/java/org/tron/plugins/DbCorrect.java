package org.tron.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;

@Slf4j(topic = "query")
@CommandLine.Command(name = "correct",
    description = "correct BURN_TRX_AMOUNT data.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbCorrect implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path")
  private Path path;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  long diff  = 6798960;

  String peopertyDB = "properties";

  private static final byte[] BURN_TRX_AMOUNT = "BURN_TRX_AMOUNT".getBytes();

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
    return correct();
  }


  private int correct() throws RocksDBException, IOException {
    try (
        DBInterface prop = DbTool.getDB(this.path, peopertyDB)) {
      long burnAmount = ByteArray.toLong(prop.get(BURN_TRX_AMOUNT));
      prop.put(BURN_TRX_AMOUNT, ByteArray.fromLong(burnAmount + diff));
      long newBurnAmount = ByteArray.toLong(prop.get(BURN_TRX_AMOUNT));
      spec.commandLine().getOut().format("Corrected burn amount: %d%n", newBurnAmount).println();
    }
    return 0;
  }
}