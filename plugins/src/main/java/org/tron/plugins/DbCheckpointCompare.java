package org.tron.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.DBUtils;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;

@Slf4j(topic = "checkpoint-compare")
@CommandLine.Command(name = "checkpoint-compare",
    aliases = {"cc"},
    description = "compare data between two path for checkpoint.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:compare diff find,please check toolkit.log"})
public class DbCheckpointCompare implements Callable<Integer> {

  @CommandLine.Spec
  static CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " ful input path for base")
  private File base;
  @CommandLine.Parameters(index = "1",
      description = "ful input path for compare")
  private File compare;
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

    Comparison service = new DbComparison(base.toPath(), compare.toPath());
    return service.doCompare() ? 0 : 1;

  }

  interface Comparison {
    boolean doCompare() throws Exception;
  }

  static class DbComparison implements Comparison {

    private final Path basePath;
    private final Path dstPath;

    public DbComparison(Path srcDir, Path dstDir) {
      this.basePath = srcDir;
      this.dstPath = dstDir;
    }

    @Override
    public boolean doCompare() throws Exception {
      return compare();
    }



    private boolean compare() throws RocksDBException, IOException {
      try (
          DBInterface base  = DbTool.getDB(this.basePath.getParent(),
              this.basePath.getFileName().toString());
          DBIterator baseIterator = base.iterator();
          DBInterface dst  = DbTool.getDB(this.dstPath.getParent(),
              this.dstPath.getFileName().toString());
          DBIterator dstIterator = dst.iterator()) {

        // check
        logger.info("compare checkpoint start");
        spec.commandLine().getOut().println("compare checkpoint start");
        baseIterator.seekToFirst();
        dstIterator.seekToFirst();
        for (; baseIterator.hasNext() && dstIterator.hasNext();
             baseIterator.next(), dstIterator.next()) {
          byte[] baseValue = baseIterator.getValue();
          byte[] baseKey = baseIterator.getKey();
          String dbName = DBUtils.simpleDecode(baseKey);
          byte[] key = dstIterator.getKey();
          byte[] value = dstIterator.getValue();
          if (!Arrays.equals(baseKey, key) || !Arrays.equals(baseValue, value)) {
            spec.commandLine().getOut().format("%s\t%s\t%s\t%s\t%s.", dbName,
                ByteArray.toHexString(baseKey), ByteArray.toHexString(key),
                ByteArray.toHexString(baseValue), ByteArray.toHexString(value)).println();
            logger.info("{}\t{}\t{}\t{}\t{}", dbName,
                 ByteArray.toHexString(baseKey), ByteArray.toHexString(key),
                 ByteArray.toHexString(baseValue), ByteArray.toHexString(value));
          }
        }
        for (; baseIterator.hasNext(); baseIterator.next()) {
          byte[] key = baseIterator.getKey();
          byte[] value = baseIterator.getValue();
          String dbName = DBUtils.simpleDecode(key);
          spec.commandLine().getOut().format("b\t%s\t%s\t%s.", dbName,
              ByteArray.toHexString(key), ByteArray.toHexString(value)).println();
          logger.info("b\t{}\t{}\t{}", dbName,
              ByteArray.toHexString(key), ByteArray.toHexString(value));
        }
        for (; dstIterator.hasNext(); dstIterator.next()) {
          byte[] key = dstIterator.getKey();
          byte[] value = dstIterator.getValue();
          String dbName = DBUtils.simpleDecode(key);
          spec.commandLine().getOut().format("d\t%s\t%s\t%s.", dbName,
             ByteArray.toHexString(key), ByteArray.toHexString(value)).println();
          logger.info("d\t{}\t{}\t{}", dbName,
              ByteArray.toHexString(key), ByteArray.toHexString(value));
        }
      }
      logger.info("compare checkpoint end");
      spec.commandLine().getOut().println("compare checkpoint end");
      return true;
    }
  }
}
