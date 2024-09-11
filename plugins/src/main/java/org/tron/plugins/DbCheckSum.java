package org.tron.plugins;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.Sha256Hash;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;



@Slf4j(topic = "checksum")
@CommandLine.Command(name = "checksum",
    description = "checksum for state-db.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:Internal error: exception occurred,please check toolkit.log"})
public class DbCheckSum implements Callable<Integer> {
  static {
    RocksDB.loadLibrary();
  }

  private static final List<String> stateDbs = Arrays.asList(
      "account", "account-asset",
      "asset-issue", "asset-issue-v2",
      "code", "contract", "contract-state",
      "delegation", "DelegatedResource",
      "exchange", "exchange-v2",
      "market_account", "market_order", "market_pair_price_to_order", "market_pair_to_price",
      "properties",
      "proposal",
      "storage-row",
      "votes",
      "witness", "witness_schedule"
      );


  @CommandLine.Spec
  static CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0", defaultValue = "output-directory/database",
      description = " Input path for db. Default: ${DEFAULT-VALUE}")
  private File src;


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
    List<File> files = Arrays.stream(Objects.requireNonNull(src.listFiles()))
        .filter(File::isDirectory)
        .filter(e -> stateDbs.contains(e.getName()))
        .collect(Collectors.toList());

    if (files.isEmpty()) {
      logger.info("{} does not contain any database.", src);
      spec.commandLine().getOut().format("%s does not contain any database.", src).println();
      return 0;
    }
    final long time = System.currentTimeMillis();
    List<Checksum> services = new ArrayList<>();
    files.forEach(f -> services.add(
        new DbChecksum(src.getPath(), f.getName())));
    List<String> ret = ProgressBar.wrap(services.stream(), "checksum task").parallel().map(
        Checksum::doChecksum).sorted().collect(Collectors.toList());
    long during = (System.currentTimeMillis() - time) / 1000;
    ret.forEach(s -> spec.commandLine().getOut().println(s));
    logger.info("database checksum use {} seconds total.", during);
    return 0;
  }

  interface Checksum {

    String doChecksum();

    String name();
  }

  static class DbChecksum implements Checksum {
    private final String srcDir;
    private final String dbName;

    private BigInteger srcDbKeyCount = BigInteger.ZERO;
    private Sha256Hash srcDbKeySum = Sha256Hash.ZERO_HASH;
    private Sha256Hash srcDbValueSum = Sha256Hash.ZERO_HASH;

    public DbChecksum(String srcDir, String name) {
      this.srcDir = srcDir;
      this.dbName = name;
    }

    @Override
    public String doChecksum() {
      try {
        long startTime = System.currentTimeMillis();
        String result = check();
        long etime = System.currentTimeMillis();
        logger.info("Checksum database {} successful end with {} key-value {} minutes",
            this.dbName, this.srcDbKeyCount, (etime - startTime) / 1000.0 / 60);
        return result;
      } catch (RocksDBException | IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public String name() {
      return dbName;
    }

    private String check() throws RocksDBException, IOException {
      try (
          DBInterface db = DbTool.getDB(Paths.get(srcDir), dbName);
          DBIterator iterator = db.iterator()) {
        // check
        logger.info("check database {} start", this.dbName);
        for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
          byte[] key = iterator.getKey();
          byte[] value = iterator.getValue();
          srcDbKeyCount = srcDbKeyCount.add(BigInteger.ONE);
          srcDbKeySum = checkSum(srcDbKeySum, key);
          srcDbValueSum = checkSum(srcDbValueSum, value);
        }
        logger.info("Check database {} end srcDbKeyCount {}, srcDbKeySum {}, srcDbValueSum {}",
            dbName, srcDbKeyCount, srcDbKeySum, srcDbValueSum);
        return String.format(
            " %s: srcDbKeyCount %d, srcDbKeySum %s, srcDbValueSum %s",
            dbName, srcDbKeyCount, srcDbKeySum, srcDbValueSum);
      }
    }
  }


  private static Sha256Hash checkSum(Sha256Hash sum, byte[] b) {
    return Sha256Hash.of(true, sum.getByteString().concat(ByteString.copyFrom(b)).toByteArray());
  }

}
