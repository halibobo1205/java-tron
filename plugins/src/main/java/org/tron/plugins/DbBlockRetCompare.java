package org.tron.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;

@Slf4j(topic = "block-ret-compare")
@CommandLine.Command(name = "block-ret-compare",
    description = "scan data from block.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbBlockRetCompare implements Callable<Integer> {

  @CommandLine.Spec
  static CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " ful input path for base")
  private Path base;
  @CommandLine.Parameters(index = "1",
      description = "ful input path for compare")
  private Path compare;
  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  private static final  String DB = "transactionRetStore";

  private final AtomicLong last = new AtomicLong(0);
  private final AtomicLong scanTotal = new AtomicLong(0);


  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }
    if (!Paths.get(base.toString(), DB).toFile().exists()) {
      logger.info(" {} does not exist.", Paths.get(base.toString(), DB));
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(String.format("%s does not exist.", Paths.get(base.toString(), DB))));
      return 404;
    }

    if (!Paths.get(compare.toString(), DB).toFile().exists()) {
      logger.info(" {} does not exist.", Paths.get(compare.toString(), DB));
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(String.format("%s does not exist.", Paths.get(compare.toString(), DB))));
      return 404;
    }
    return compare();
  }


  private int compare() throws RocksDBException, IOException {
    try (DBInterface base  = DbTool.getDB(this.base, DB);
         DBInterface compare = DbTool.getDB(this.compare, DB);
         DBIterator baseIterator = base.iterator();
         DBIterator compareIterator = compare.iterator()) {
      baseIterator.seekToFirst();
      long min = ByteArray.toLong(baseIterator.getKey());
      baseIterator.seekToLast();
      long max = ByteArray.toLong(baseIterator.getKey());

      compareIterator.seekToFirst();
      min = Math.max(min, ByteArray.toLong(compareIterator.getKey()));
      compareIterator.seekToLast();
      max = Math.min(max, ByteArray.toLong(compareIterator.getKey()));

      long total = max - min + 1;
      spec.commandLine().getOut().format("compare block ret from  %d to %d ", min, max).println();
      logger.info("compare block ret start from {} to {}", min, max);
      try (ProgressBar pb = new ProgressBar("block-ret-compare", total)) {
        for (long i = min; i <= max; i++) {
          compare(i, base.get(ByteArray.fromLong(i)), compare.get(ByteArray.fromLong(i)));
          pb.step();
          pb.setExtraMessage("Reading...");
          scanTotal.getAndIncrement();
        }
      }
      spec.commandLine().getOut().format("total scan block ret size: %d", scanTotal.get()).println();
      logger.info("total scan block ret size: {}", scanTotal.get());
    }
    return 0;
  }
  private void compare(long num, byte[] b, byte[] c) {
    if (!Arrays.equals(b, c)) {
      logger.info("block ret {} is different, {}, {}", num, ByteArray.toHexString(b), ByteArray.toHexString(c));
      spec.commandLine().getOut().format("block ret %d is different", num).println();
    }
  }

}