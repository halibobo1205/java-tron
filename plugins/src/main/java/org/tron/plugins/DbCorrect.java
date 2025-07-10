package org.tron.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;

@Slf4j(topic = "query")
@CommandLine.Command(name = "correct",
    description = "correct 4156a81439939809fdaaae7548a1de9c20783eb05b data.",
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

  private static final byte[] address =
      ByteArray.fromHexString("4156a81439939809fdaaae7548a1de9c20783eb05b");

  long diff  = 6798960;

  String accountDB = "account";
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
        DBInterface acc = DbTool.getDB(this.path, accountDB);
        DBInterface prop = DbTool.getDB(this.path, peopertyDB)) {

      Protocol.Account account =  Protocol.Account.parseFrom(acc.get(address));
      if (account.getBalance() == 6830960) {
        acc.put(address, account.toBuilder()
            .setBalance(account.getBalance() - diff).build().toByteArray());
        long burnAmount = ByteArray.toLong(prop.get(BURN_TRX_AMOUNT));
        prop.put(BURN_TRX_AMOUNT, ByteArray.fromLong(burnAmount + diff));
        long newBurnAmount = ByteArray.toLong(prop.get(BURN_TRX_AMOUNT));
        long newBalance = Protocol.Account.parseFrom(acc.get(address)).getBalance();
        spec.commandLine().getOut().format("Corrected account %s, new balance: %d, burn amount: %d%n",
            ByteArray.toHexString(address), newBalance, newBurnAmount).println();
      } else if (account.getBalance() == 6830960 - diff) {
        spec.commandLine().getOut().format("Account %s already corrected, balance: %d%n",
            ByteArray.toHexString(address), account.getBalance()).println();
      } else {
        spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
            .errorText(String.format("Account %s has unexpected balance: %d",
                ByteArray.toHexString(address), account.getBalance())));
        return 1;
      }
      return 0;
    }
  }
}