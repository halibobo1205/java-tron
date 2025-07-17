package org.tron.plugins;

import com.google.common.primitives.Bytes;
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
                  long size = block.getTransactionsList().stream().filter(this::filter).count();
                  print(ByteArray.toLong(number), size);
                }
              }
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          });
    }
    return 0;
  }

  private  void print(long number, long size) {
    if (size == 1) {
      spec.commandLine().getOut().format("%d,%d", number, size).println();
      logger.info("block number: {}, size: {}", number, size);
    }
  }

  private boolean filter(Protocol.Transaction transaction) {
    return transaction.getRawData().getContract(0).getType().equals(
        Protocol.Transaction.Contract.ContractType.TransferAssetContract);
  }
}
