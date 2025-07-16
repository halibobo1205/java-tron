package org.tron.plugins;

import com.google.common.primitives.Bytes;
import com.google.protobuf.InvalidProtocolBufferException;
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
import org.tron.protos.Protocol;
import org.tron.protos.contract.BalanceContract;
import picocli.CommandLine;


@Slf4j(topic = "block-scan")
@CommandLine.Command(name = "block-scan",
    description = "scan data from block.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbBlockScan implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path for block")
  private Path db;

  @CommandLine.Option(names = {"--block", "-b"}, split = ",",
      description = "block number to scan")
  private  List<Long> blocks;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  private static final  String DB = "block";

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
    try (DBInterface database  = DbTool.getDB(this.db, DB)) {
      blocks.stream()
          .map(ByteArray::fromLong)
          .forEach(number -> {
            try (DBIterator iterator = database.iterator()) {
              iterator.seek(number);
              if (iterator.valid()) {
                byte[] key = iterator.getKey();
                if (Bytes.indexOf(key, number) == 0) {
                  Protocol.Block block = Protocol.Block.parseFrom(iterator.getValue());
                  block.getTransactionsList().stream().filter(this::filter)
                      .findFirst().map(t -> {
                        try {
                          return t.getRawData().getContract(0).getParameter()
                              .unpack(BalanceContract.TransferContract.class);
                        } catch (InvalidProtocolBufferException e) {
                          throw new RuntimeException(e);
                        }
                      }).ifPresent(transferContract ->
                          print(ByteArray.toLong(number), transferContract));
                }
              }
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          });
    }
    return 0;
  }

  private  void print(long number, BalanceContract.TransferContract transferContract) {
    long amount = transferContract.getAmount();
    String owner = ByteArray.toHexString(transferContract.getOwnerAddress().toByteArray());
    String to = ByteArray.toHexString(transferContract.getToAddress().toByteArray());
    spec.commandLine().getOut().format("%d, %d, %s, %s ", number, amount, owner, to).println();
    logger.info("block number: {}, amount: {}, owner: {}, to: {}",
        number, amount, owner, to);
  }

  private boolean filter(Protocol.Transaction transaction) {
    return transaction.getRawData().getContract(0).getType().equals(
        Protocol.Transaction.Contract.ContractType.TransferContract);

  }
}
