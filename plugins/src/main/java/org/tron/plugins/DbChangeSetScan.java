package org.tron.plugins;

import com.google.common.primitives.Ints;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Triple;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import picocli.CommandLine;

@Slf4j(topic = "change-set-scan")
@CommandLine.Command(name = "change-set-scan",
    description = "scan data from change-set.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbChangeSetScan implements Callable<Integer> {


  private static final List<String> stateDbs = Arrays.asList(
      "account", "account-asset",
      "code", "contract", "contract-state", "storage-row",
      "delegation", "DelegatedResource",
      "DelegatedResourceAccountIndex",
      "exchange-v2", "asset-issue-v2",
      "votes", "witness"
  );

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = " db path for change-set")
  private Path db;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  private static final  String DB = "change-set";

  private final Map<String, Triple<Long, Long, Long>> tans =
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

    try (DBInterface database  = DbTool.getDB(this.db, DB);
         DBIterator iterator = database.iterator()) {
      iterator.seekToFirst();
      if (!iterator.valid()) {
        spec.commandLine().getOut().println("No data found in the database.");
        logger.info("No data found in the database.");
        return 0;
      }
      for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
        scanTotal.getAndIncrement();
        byte[] key = iterator.getKey();
        byte[] value = iterator.getValue();
        byte[] other = Arrays.copyOfRange(key, Long.BYTES, key.length);
        String dbName = simpleDecode(other);
        if (!stateDbs.contains(dbName)) {
          throw new IllegalArgumentException("Unsupported db name: " + dbName);
        }
        byte[] realKey = Arrays.copyOfRange(other, dbName.getBytes().length + Integer.BYTES,
            other.length);
        tans.computeIfAbsent(dbName, k -> Triple.of(0L, 0L, 0L));
        Triple<Long, Long, Long> current = tans.get(dbName);
        long keySize = realKey.length;
        long count = 1;
        long valueSize = value.length;
        current = Triple.of(
            current.getLeft() + count,
            current.getMiddle() + keySize,
            current.getRight() + valueSize
        );
        tans.put(dbName, current);
      }
      spec.commandLine().getOut().format("total scan block size: %d", scanTotal.get()).println();
      logger.info("total scan block size: {}", scanTotal.get());
      spec.commandLine().getOut().format("chang-set size: %s", tans).println();
      logger.info("chang-set size: {}", tans);
    }
    return 0;
  }

  private static String simpleDecode(byte[] bytes) {
    byte[] lengthBytes = Arrays.copyOf(bytes, Integer.BYTES);
    int length = Ints.fromByteArray(lengthBytes);
    byte[] value = Arrays.copyOfRange(bytes, Integer.BYTES, Integer.BYTES + length);
    return new String(value);
  }
}