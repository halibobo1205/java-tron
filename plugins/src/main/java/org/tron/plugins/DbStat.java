package org.tron.plugins;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.DBUtils;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;

@Slf4j(topic = "stat")
@CommandLine.Command(name = "stat",
    description = "stat info.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:Internal error: exception occurred,please check toolkit.log"})
public class DbStat implements Callable<Integer> {

  @CommandLine.Spec
  static CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0", defaultValue = "output-directory/database",
      description = " Input path for leveldb. Default: ${DEFAULT-VALUE}")
  private File src;
  @CommandLine.Option(names = {"-dbs"},
      converter = ListConverter.class)
  private List<String> dbs;

  @CommandLine.Option(names = {"-h", "--help"})
  private boolean help;


  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }
    if (!src.exists()) {
      logger.info(" {} does not exist.", src);
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(String.format("%s does not exist.", src)));
      return 404;
    }

    List<Stater> staters = Arrays.stream(Objects.requireNonNull(src.listFiles()))
        .filter(File::isDirectory)
        .filter(e -> !DBUtils.CHECKPOINT_DB_V2.equals(e.getName()))
        .filter(e -> dbs.isEmpty() || dbs.contains(e.getName()))
        .map(f -> new DbStater(src.getPath(), f.getName()))
        .collect(Collectors.toList());
    ProgressBar.wrap(staters.stream(), "stat task").parallel().forEach(Stater::doStat);
    return 0;
  }


  static class ListConverter implements CommandLine.ITypeConverter<List<String>> {
    ListConverter() {
    }

    public List<String> convert(String value) {
      if (value == null || value.isEmpty()) {
        return new ArrayList<>();
      }
      String[] parts = value.split(",");
      return Arrays.stream(parts)
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .collect(Collectors.toList());
    }
  }

  interface Stater {

    void doStat();

    String name();
  }

  static class DbStater implements Stater {
    private final String srcDir;
    private final String dbName;

    private long keyCount = 0L;
    private long keySize = 0L;
    private long valueSize = 0L;

    private boolean safe;

    public DbStater(String srcDir, String name ) {
      this.srcDir = srcDir;
      this.dbName = name;
    }

    @Override
    public void doStat() {
      try (
          DBInterface db = DbTool.getDB(srcDir, dbName);
          DBIterator iterator = db.iterator()) {
        for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
          byte[] key = iterator.getKey();
          byte[] value = iterator.getValue();
          keyCount++;
          keySize += key.length;
          valueSize += value.length;
        }
        logger.info("{},{},{},{},{}",
            dbName, keyCount, keySize, valueSize, (keySize + valueSize) * 1.0 / keyCount);
        spec.commandLine().getOut().format("%s,%d,%d,%d,%.2f%n",
            dbName, keyCount, keySize, valueSize, (keySize + valueSize) * 1.0 / keyCount);
      } catch (RocksDBException | IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public String name() {
      return dbName;
    }
  }

}
