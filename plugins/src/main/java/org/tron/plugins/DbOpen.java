package org.tron.plugins;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDB;
import org.tron.plugins.utils.DBUtils;
import picocli.CommandLine;
import picocli.CommandLine.Option;

@Slf4j(topic = "open")
@CommandLine.Command(name = "open", description = "A helper to open rocksdb cost.")
public class DbOpen implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;

  @Option(names = {"-d", "--database-directory"},
      defaultValue = "output-directory/database",
      description = "java-tron database directory. Default: ${DEFAULT-VALUE}")
  private String databaseDirectory;

  @Option(names = {"-h", "--help"})
  private boolean help;


  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }

    File dbDirectory = new File(databaseDirectory);
    if (!dbDirectory.exists()) {
      spec.commandLine().getErr().format("Directory %s does not exist.",
          databaseDirectory).println();
      logger.info("Directory {} does not exist.", databaseDirectory);
      return 404;
    }

    if (!dbDirectory.isDirectory()) {
      spec.commandLine().getErr().format(" %s is not directory.",
          databaseDirectory).println();
      logger.info("{} is not directory.", databaseDirectory);
      return 405;
    }

    List<File> files = Arrays.stream(Objects.requireNonNull(dbDirectory.listFiles()))
        .filter(File::isDirectory)
        .filter(e -> !DBUtils.CHECKPOINT_DB_V2.equals(e.getName()))
        .collect(Collectors.toList());

    // add checkpoint v2 convert
    File cpV2Dir = new File(Paths.get(dbDirectory.toString(), DBUtils.CHECKPOINT_DB_V2).toString());
    List<File> cpList = new ArrayList<>();
    if (cpV2Dir.exists()) {
      cpList = Arrays.stream(Objects.requireNonNull(cpV2Dir.listFiles()))
          .filter(File::isDirectory)
          .collect(Collectors.toList());
    }

    if (files.isEmpty() && cpList.isEmpty()) {
      spec.commandLine().getErr().format("Directory %s does not contain any database.",
          databaseDirectory).println();
      logger.info("Directory {} does not contain any database.", databaseDirectory);
      return 0;
    }


    List<File> dbs = new ArrayList<>();
    dbs.addAll(files);
    dbs.addAll(cpList);
    dbs.sort(Comparator.comparing(File::getName));
    List<Path> paths = dbs.stream().map(File::toPath).collect(Collectors.toList());
    long total = 0;
    for (Path path : paths) {
      final long time = System.currentTimeMillis();
      try (RocksDB db = DBUtils.newRocksDb(path)) {
        long cost = System.currentTimeMillis() - time;
        total += cost;
        logger.info("{} cost:{}ms", path.getFileName(), cost);
        spec.commandLine().getOut().format("%s cost:%dms\n", path.getFileName(), cost).flush();
      }
    }
    logger.info("Total cost:{}ms", total);
    spec.commandLine().getOut().format("Total cost:%dms\n", total).flush();
    return 0;
  }

}