package org.tron.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.iq80.leveldb.DBComparator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.InternalKeyComparator;
import org.iq80.leveldb.impl.InternalUserComparator;
import org.iq80.leveldb.impl.TableCache;
import org.iq80.leveldb.impl.VersionSet;
import org.iq80.leveldb.table.BytewiseComparator;
import org.iq80.leveldb.table.CustomUserComparator;
import org.iq80.leveldb.table.UserComparator;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.DBUtils;
import picocli.CommandLine;

@CommandLine.Command(name = "dump",
    description = "dump db .")
@Slf4j(topic = "dump")
public class DbDump implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;

  @CommandLine.Option(names = {"-d", "--database"},
      defaultValue = "output-directory/database",
      description = "database directory path. Default: ${DEFAULT-VALUE}")
  private Path database;

  @CommandLine.Option(names = {"--db"},
      defaultValue = "account",
      description = "db name")
  private String dbName;

  @CommandLine.Option(names = { "--sst"},
      required = true,
      description = "sst file number")
  private long sst;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  boolean help;

  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }
    this.run();
    return 0;
  }

  private void run() {
    try {
      dump(dbName);
    } catch (Exception e) {
      spec.commandLine().getOut().format("dump db failed: %s", e.getMessage()).println();
      logger.error("dump db failed: ", e);
    }
  }


  private void dump(String dbName) throws IOException {
    Options options = DBUtils.newDefaultLevelDbOptions(dbName);
    File databaseDir = new File(database.toString(), dbName);
    int tableCacheSize = options.maxOpenFiles() - 10;
    InternalKeyComparator internalKeyComparator;
    //use custom comparator if set
    DBComparator comparator = options.comparator();
    UserComparator userComparator;
    if (comparator != null) {
      userComparator = new CustomUserComparator(comparator);
    } else {
      userComparator = new BytewiseComparator();
    }
    internalKeyComparator = new InternalKeyComparator(userComparator);
    TableCache tableCache = new TableCache(databaseDir, tableCacheSize,
        new InternalUserComparator(internalKeyComparator), options.verifyChecksums());
    VersionSet versions = new VersionSet(databaseDir, tableCache, options, internalKeyComparator);
    // load  (and recover) current version
    versions.recover();
    spec.commandLine().getOut().format("print %d.sst content in hex string", sst).println();
    logger.info("print {}.sst content in hex string", sst);
    AtomicLong cnt = new AtomicLong();
    tableCache.newIterator(sst).forEachRemaining(e -> {
      String k = ByteArray.toHexString(e.getKey().getUserKey().getBytes());
      String v = ByteArray.toHexString(e.getValue().getBytes());
      logger.info("k: {}, v: {}", k, v);
      cnt.getAndIncrement();
    });
    spec.commandLine().getOut().format("print %d.sst content done, cnt: %d, see log in detail", sst,
        cnt.get()).println();
    logger.info("print {}.sst content done, cnt: {}", sst, cnt.get());

  }
}
