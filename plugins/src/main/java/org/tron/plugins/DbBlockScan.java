package org.tron.plugins;

import static org.tron.protos.Protocol.Transaction.Contract.ContractType.ShieldedTransferContract;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.BlockId;
import org.tron.plugins.utils.Sha256Hash;
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

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  private static final  String DB = "block";

  private final AtomicLong last = new AtomicLong(0);
  private final AtomicLong scanTotal = new AtomicLong(0);


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
    try (DBInterface database  = DbTool.getDB(this.db, DB);
         DBIterator iterator = database.iterator()) {
      iterator.seekToFirst();
      long min = new BlockId(Sha256Hash.wrap(iterator.getKey())).getNum();
      iterator.seekToLast();
      long max = new BlockId(Sha256Hash.wrap(iterator.getKey())).getNum();
      long total = max - min + 1;
      spec.commandLine().getOut().format("scan block start from  %d to %d ", min, max).println();
      logger.info("scan block start from {} to {}", min, max);
      try (ProgressBar pb = new ProgressBar("block-scan", total)) {
        for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
          print(iterator.getKey(), iterator.getValue());
          pb.step();
          pb.setExtraMessage("Reading...");
          scanTotal.getAndIncrement();
        }
      }
      spec.commandLine().getOut().format("last block has ShieldedTransferContract : %d",
          last.get()).println();
      spec.commandLine().getOut().format("total scan block size: %d", scanTotal.get()).println();
      logger.info("last block has ShieldedTransferContract : {}", last.get());
      logger.info("total scan block size: {}", scanTotal.get());
    }
    return 0;
  }

  private void print(byte[] k, byte[] v) {
    try {
      Protocol.Block block = Protocol.Block.parseFrom(v);
      long num = block.getBlockHeader().getRawData().getNumber();
      boolean ret = block.getTransactionsList().stream().anyMatch(this::check);
      if (ret) {
        last.set(num);
      }
    } catch (InvalidProtocolBufferException e) {
      logger.error("{},{}", k, v);
    }
  }

  private boolean check(Protocol.Transaction trans) {
    return trans.getRawData().getContractList()
        .stream()
        .anyMatch(c -> c.getType() == ShieldedTransferContract);
  }
}