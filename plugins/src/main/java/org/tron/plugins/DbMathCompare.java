package org.tron.plugins;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;

@Slf4j(topic = "compare-math")
@CommandLine.Command(name = "compare-math",
    description = "compare data between two path for db.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:compare diff find,please check toolkit.log"})
public class DbMathCompare implements Callable<Integer> {

  private static final String MATH = "math";
  private static final String STRICT_MATH = "strict-math";

  @CommandLine.Spec
  static CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = "  input path for jdk8")
  private File jdk8;
  @CommandLine.Parameters(index = "1",
      description = " input path for jdk17")
  private File jdk17;

  @CommandLine.Parameters(index = "2",
      description = "input path for arm")
  private File arm;
  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }

    try (
        DBInterface jdk8Math = DbTool.getDB(jdk8.toPath(), MATH);
        DBInterface jdk17Math = DbTool.getDB(jdk17.toPath(), MATH);
        final DBInterface armMath = DbTool.getDB(arm.toPath(), MATH);
        DBInterface jdk8StrictMath = DbTool.getDB(jdk8.toPath(), STRICT_MATH);
        DBInterface jdk17StrictMath = DbTool.getDB(jdk17.toPath(), STRICT_MATH);
        DBInterface armStrictMath = DbTool.getDB(arm.toPath(), STRICT_MATH)) {
      List<Comparison> comparisons = new ArrayList<>();
      comparisons.add(new DbComparison(jdk8Math, jdk8StrictMath, "math8-vs-strict8"));
      comparisons.add(new DbComparison(jdk8Math, jdk17Math, "math8-vs-math17"));
      comparisons.add(new DbComparison(jdk8Math, armMath, "math8-vs-mathArm"));
      comparisons.add(new DbComparison(jdk8StrictMath, jdk17StrictMath, "strict8-vs-strict17"));
      comparisons.add(new DbComparison(jdk8StrictMath, armStrictMath, "strict8-vs-strictArm"));
      ProgressBar.wrap(comparisons.stream(), "comparisons task").parallel().forEach(c -> {
        try {
          c.doCompare();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      comparisons.forEach(c -> logger.info("{} diff {} over {}",
          c.getTaskName(), c.getCount(), c.getBlockNumber()));
      comparisons.forEach(c -> spec.commandLine().getOut().println(
          c.getTaskName() + " diff " + c.getCount() + " over " + c.getBlockNumber()));
      return 0;
    }

  }

  interface Comparison {
    void doCompare() throws Exception;

    long getCount();

    String getTaskName();

    long getBlockNumber();
  }

  static class DbComparison implements Comparison {

    private final DBInterface base;
    private final DBInterface dst;
    @Getter
    private final String taskName;
    @Getter
    private long count;
    @Getter
    private long blockNumber;

    public DbComparison(DBInterface base, DBInterface dst, String taskName) {
      this.base = base;
      this.dst = dst;
      this.taskName = taskName;
    }

    @Override
    public void doCompare() throws Exception {
      try (
          DBIterator dstIterator = dst.iterator()) {
        // check
        logger.info("compare {} start", this.taskName);
        for (dstIterator.seekToFirst(); dstIterator.hasNext(); dstIterator.next()) {
          byte[] dstKey = dstIterator.getKey();
          final byte[] dstValue = dstIterator.getValue();
          final byte[] baseValue = base.get(dstKey);
          byte[] b = new byte[Long.BYTES];
          System.arraycopy(dstKey, 0, b, 0, Long.BYTES);
          blockNumber = ByteArray.toLong(b);
          if (!Arrays.equals(baseValue, dstValue)) {
            count++;
            logger.info("{}\t{}\t{}\t{}\t{}",
                ByteArray.toHexString(dstKey),
                ByteArray.toHexString(baseValue), ByteArray.toHexString(dstValue),
                blockNumber, taskName);
          }
        }
      }
      logger.info("compare {} end, diff {} over {}", this.taskName, count, blockNumber);
    }
  }
}
