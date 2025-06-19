package org.tron.plugins;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.Sha256Hash;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;

@Slf4j(topic = "trans-scan")
@CommandLine.Command(name = "trans-scan",
    description = "scan data from block.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbTransactionScan implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path for block")
  private Path db;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  private static final  String DB = "block";

  private final Map<Protocol.Transaction.Contract.ContractType, AtomicLong> tans =
      new ConcurrentHashMap<>();
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
    for (Protocol.Transaction.Contract.ContractType c :
        Protocol.Transaction.Contract.ContractType.values()) {
      tans.put(c, new AtomicLong(0));
    }
    try (DBInterface database  = DbTool.getDB(this.db, DB);
         DBIterator iterator = database.iterator()) {
      iterator.seekToFirst();
      if (!iterator.valid()) {
        spec.commandLine().getOut().println("No data found in the database.");
        logger.info("No data found in the database.");
        return 0;
      }
      long min = new Sha256Hash.BlockId(Sha256Hash.wrap(iterator.getKey())).getNum();
      iterator.seekToLast();
      long max = new Sha256Hash.BlockId(Sha256Hash.wrap(iterator.getKey())).getNum();
      long total = max - min + 1;
      spec.commandLine().getOut().format("scan block start from  %d to %d ", min, max).println();
      logger.info("scan block start from {} to {}", min, max);
      try (ProgressBar pb = new ProgressBar("block-scan", total)) {
        for (iterator.seek(ByteArray.fromLong(min)); iterator.hasNext(); iterator.next()) {
          print(iterator.getKey(), iterator.getValue());
          pb.step();
          pb.setExtraMessage("Reading...");
          scanTotal.getAndIncrement();
        }
      }
      spec.commandLine().getOut().format("total scan block size: %d", scanTotal.get()).println();
      logger.info("total scan block size: {}", scanTotal.get());
      spec.commandLine().getOut().format("trans size: %s", tans).println();
      logger.info("trans size: {}", tans);
    }
    return 0;
  }

  private  void print(byte[] k, byte[] v) {
    try {
      Protocol.Block block = Protocol.Block.parseFrom(v);
      List<Protocol.Transaction> list = block.getTransactionsList().stream()
              .filter(trans -> trans.getSignatureCount() > 0).collect(Collectors.toList());
      list.forEach(transaction -> {
        Protocol.Transaction.Contract.ContractType c =
            transaction.getRawData().getContract(0).getType();
        tans.get(c).getAndIncrement();
      });
    } catch (InvalidProtocolBufferException e) {
      logger.error("{},{}", k, v);
    }
  }
}
