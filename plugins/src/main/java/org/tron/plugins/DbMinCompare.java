package org.tron.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.DBUtils;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;


@Slf4j(topic = "compare")
@CommandLine.Command(name = "min-compare",
    description = "compare data between two path.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:compare diff find,please check toolkit.log"})
public class DbMinCompare implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " input path for base")
  private File base;
  @CommandLine.Parameters(index = "1",
      description = "input path for compare")
  private File compare;
  @CommandLine.Option(names = { "--names"},
       description = "db names for compare")
  private List<String> names;
  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;


  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }
    if (!base.exists()) {
      logger.info(" {} does not exist.", base);
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(String.format("%s does not exist.", base)));
      return 404;
    }
    if (!compare.exists()) {
      logger.info(" {} does not exist.", compare);
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(String.format("%s does not exist.", compare)));
      return 404;
    }

    List<File> files = Arrays.stream(Objects.requireNonNull(base.listFiles()))
        .filter(File::isDirectory)
        .filter(e -> !DBUtils.CHECKPOINT_DB_V2.equals(e.getName()))
        .collect(Collectors.toList());
    // add checkpoint v2 convert
    File cpV2Dir = new File(Paths.get(base.toString(), DBUtils.CHECKPOINT_DB_V2).toString());
    List<File> cpList = new ArrayList<>();
    if (cpV2Dir.exists()) {
      cpList = Arrays.stream(Objects.requireNonNull(cpV2Dir.listFiles()))
          .filter(File::isDirectory)
          .collect(Collectors.toList());
    }
    // filter names if set
    if (names != null && !names.isEmpty()) {
      files = files.stream().filter(o -> names.contains(o.getName())).collect(Collectors.toList());
      cpList = cpList.stream().filter(o ->
          names.contains(o.getName())).collect(Collectors.toList());
    }

    if (files.isEmpty() && cpList.isEmpty()) {
      logger.info("{} does not contain any database.", base);
      spec.commandLine().getOut().format("%s does not contain any database.", base).println();
      return 0;
    }
    final long time = System.currentTimeMillis();
    List<Comparison> services = new ArrayList<>();
    files.forEach(f -> services.add(
        new DbComparison(base.toPath(), compare.toPath(), f.getName())));
    cpList.forEach(f -> services.add(
        new DbComparison(
            Paths.get(base.getPath(), DBUtils.CHECKPOINT_DB_V2),
            Paths.get(compare.getPath(), DBUtils.CHECKPOINT_DB_V2),
            f.getName())));
    List<String> diff = ProgressBar.wrap(services.stream(), "comparison task").parallel().map(
        dbComparison -> {
          try {
            return dbComparison.doCompare() ? null : dbComparison.name();
          } catch (Exception e) {
            logger.error("{}", e);
            spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
                .errorText(e.getMessage()));
            return dbComparison.name();
          }
        }).filter(Objects::nonNull).collect(Collectors.toList());
    long during = (System.currentTimeMillis() - time) / 1000;
    spec.commandLine().getOut().format("compare db done, diff: %s, take %d s.",
        diff, during).println();
    logger.info("database compare use {} seconds total, diff: {}.", during, diff);
    return diff.size();
  }

  interface Comparison {

    boolean doCompare() throws Exception;

    String name();
  }

  static class DbComparison implements Comparison {

    private final String dbName;
    private final Path basePath;
    private final Path dstPath;

    private long baseKeyCount = 0L;
    private long dstKeyCount = 0L;
    private long baseKeyCheckSum = 0L;
    private long dstKeyCheckSum = 0L;
    private long baseValueCheckSum = 0L;
    private long dstValueCheckSum = 0L;

    public DbComparison(Path srcDir, Path dstDir, String name) {
      this.dbName = name;
      this.basePath = srcDir;
      this.dstPath = dstDir;
    }

    @Override
    public boolean doCompare() throws Exception {
      return compare();
    }

    @Override
    public String name() {
      return dbName;
    }


    private boolean compare() throws RocksDBException, IOException {
      try (
          DBInterface base  = DbTool.getDB(this.basePath, this.dbName);
          DBIterator baseIterator = base.iterator();
          DBInterface dst  = DbTool.getDB(this.dstPath, this.dbName);
          DBIterator dstIterator = dst.iterator()) {

        // check
        logger.info("compare database {} start", this.dbName);

        CompletableFuture<Void> dest = CompletableFuture.runAsync(() -> {
          for (dstIterator.seekToFirst(); dstIterator.hasNext(); dstIterator.next()) {
            byte[] key = dstIterator.getKey();
            byte[] value = dstIterator.getValue();
            dstKeyCount++;
            dstKeyCheckSum = byteArrayToIntWithOne(dstKeyCheckSum, key);
            dstValueCheckSum = byteArrayToIntWithOne(dstValueCheckSum, value);
          }
        });

        CompletableFuture<Void> source = CompletableFuture.runAsync(() -> {
          for (baseIterator.seekToFirst(); baseIterator.hasNext(); baseIterator.next()) {
            byte[] key = baseIterator.getKey();
            byte[] value = baseIterator.getValue();
            baseKeyCount++;
            baseKeyCheckSum = byteArrayToIntWithOne(baseKeyCheckSum, key);
            baseValueCheckSum = byteArrayToIntWithOne(baseValueCheckSum, value);
          }
        });
        CompletableFuture<Void> ret = CompletableFuture.allOf(dest, source);
        ret.whenComplete((t, action) -> logger.info(
            "Check database {} end,dstKeyCount {}, dstKeyCheckSum {}, dstValueCheckSum {},"
                + "baseKeyCount {}, baseKeyCheckSum {}, baseValueCheckSum {}",
            dbName, dstKeyCount, dstKeyCheckSum, dstValueCheckSum,
            baseKeyCount, baseKeyCheckSum, baseValueCheckSum));
        ret.join();
        return dstKeyCount == baseKeyCount && dstKeyCheckSum == baseKeyCheckSum
            && dstValueCheckSum == baseValueCheckSum;
      }
    }
  }

  private static long byteArrayToIntWithOne(long sum, byte[] b) {
    for (byte oneByte : b) {
      sum += oneByte;
    }
    return sum;
  }

}
