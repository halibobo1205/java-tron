package org.tron.plugins;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.tron.plugins.utils.DBUtils;
import org.tron.plugins.utils.FileUtils;
import picocli.CommandLine;

@Slf4j(topic = "compact")
@CommandLine.Command(name = "compact",
    description = "Compact one or more RocksDB databases.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:Internal error: exception occurred,please check toolkit.log"})
public class DbCompact implements Callable<Integer> {

  static {
    RocksDB.loadLibrary();
  }

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;

  @CommandLine.Parameters(index = "0", defaultValue = "output-directory/database",
      description = "Input database parent path. Default: ${DEFAULT-VALUE}")
  private Path db;

  @CommandLine.Option(names = {"--dbs"}, split = ",",
      description = "Database names to compact. Supports comma-separated values, "
          + "for example: --dbs account-asset,account")
  private List<String> dbs;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  @Override
  public Integer call() {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }
    if (!db.toFile().exists()) {
      logger.info("{} does not exist.", db);
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(String.format("%s does not exist.", db)));
      return 404;
    }
    if (dbs == null || dbs.isEmpty()) {
      String tips = "Specify at least one database: --dbs dbName[,dbName...].";
      logger.info(tips);
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme().errorText(tips));
      return 404;
    }

    List<CompactTask> tasks = dbs.stream()
        .map(String::trim)
        .filter(name -> !name.isEmpty())
        .distinct()
        .map(name -> new CompactTask(db, name))
        .collect(Collectors.toList());
    if (tasks.isEmpty()) {
      String tips = "Specify at least one non-empty database name.";
      logger.info(tips);
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme().errorText(tips));
      return 404;
    }

    long start = System.currentTimeMillis();
    List<CompactResult> results = ProgressBar.wrap(tasks.stream(), "compact task")
        .parallel()
        .map(CompactTask::compact)
        .collect(Collectors.toList());
    results.forEach(this::printInfo);

    List<String> fails = results.stream()
        .filter(result -> !result.success)
        .map(result -> result.name)
        .collect(Collectors.toList());
    long during = (System.currentTimeMillis() - start) / 1000;
    spec.commandLine().getOut().format("compact db done, fails: %s, take %d s.",
        fails, during).println();
    logger.info("database compact use {} seconds total, fails: {}.", during, fails);
    return fails.size();
  }

  private void printInfo(CompactResult result) {
    if (result.success) {
      spec.commandLine().getOut().println(result.message);
    } else {
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(result.message));
    }
  }

  private static class CompactTask {
    private final Path parent;
    private final String name;
    private final Path path;

    private CompactTask(Path parent, String name) {
      this.parent = parent;
      this.name = name;
      this.path = Paths.get(parent.toString(), name);
    }

    private CompactResult compact() {
      long start = System.currentTimeMillis();
      try {
        if (!path.toFile().exists()) {
          return CompactResult.fail(name, String.format("db: %s, fail: %s does not exist.",
              name, path));
        }
        if (!isRocksDb(path)) {
          return CompactResult.fail(name, String.format("db: %s, fail: not a RocksDB database.",
              name));
        }
        if (DBUtils.MARKET_PAIR_PRICE_TO_ORDER.equalsIgnoreCase(name)) {
          return CompactResult.ok(name, String.format("db: %s, skipped.", name));
        }

        logger.info("compact database {} start", name);
        try (Options options = DBUtils.newDefaultRocksDbOptions(false, name)) {
          options.setCreateIfMissing(false);
          try (RocksDB rocks = RocksDB.open(options, path.toString())) {
            rocks.compactRange();
          }
        }
        long during = (System.currentTimeMillis() - start) / 1000;
        logger.info("compact database {} end, take {} s", name, during);
        return CompactResult.ok(name, String.format("db: %s, compacted, take %d s.",
            name, during));
      } catch (Exception e) {
        logger.error("compact database {} fail", name, e);
        return CompactResult.fail(name, String.format("db: %s, fail: %s",
            name, e.getMessage()));
      }
    }

    private static boolean isRocksDb(Path path) {
      String engineFile = path + File.separator + DBUtils.FILE_ENGINE;
      if (!FileUtils.isExists(engineFile)) {
        return false;
      }
      String engine = FileUtils.readProperty(engineFile, DBUtils.KEY_ENGINE);
      return DBUtils.ROCKSDB.equalsIgnoreCase(engine);
    }
  }

  private static class CompactResult {
    private final String name;
    private final boolean success;
    private final String message;

    private CompactResult(String name, boolean success, String message) {
      this.name = name;
      this.success = success;
      this.message = Objects.toString(message, "");
    }

    private static CompactResult ok(String name, String message) {
      return new CompactResult(name, true, message);
    }

    private static CompactResult fail(String name, String message) {
      return new CompactResult(name, false, message);
    }
  }
}
