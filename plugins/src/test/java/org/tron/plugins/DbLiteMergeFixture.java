package org.tron.plugins;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.rules.TemporaryFolder;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.DBUtils;
import org.tron.plugins.utils.Sha256Hash;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;

final class DbLiteMergeFixture {

  static final long SNAPSHOT_MIN = 4;
  static final long SNAPSHOT_MAX = 5;
  static final String STALE_FILE = "STALE";
  private static final String INFO_FILE = "info.properties";
  private static final String LATEST_BLOCK = "latest_block_header_number";
  private static final List<String> ARCHIVE_DBS = Collections.unmodifiableList(Arrays.asList(
      "block", "block-index", "trans", "transactionRetStore", "transactionHistoryStore"));

  final Path lite;
  final Path history;
  final Path movedRoot;
  final CommandLine cli;
  private final Path config;
  private final long historyMax;
  private final StringWriter output = new StringWriter();
  private final StringWriter error = new StringWriter();
  private List<String> movedDbs = Collections.emptyList();

  DbLiteMergeFixture(TemporaryFolder folder, long historyMax)
      throws IOException, RocksDBException {
    this.historyMax = historyMax;
    lite = Files.createDirectories(folder.newFolder("lite").toPath().resolve("database"))
        .toRealPath();
    history = folder.newFolder("history").toPath().toRealPath();
    movedRoot = Files.createDirectories(folder.newFolder("moved").toPath().resolve("database"))
        .toRealPath();
    config = folder.getRoot().toPath().resolve("move.conf");
    cli = new CommandLine(new Toolkit());
    cli.setOut(new PrintWriter(output, true));
    cli.setErr(new PrintWriter(error, true));
    initializeStores(lite);
    initializeStores(history);
    putBlock(lite, 0);
    for (long number = SNAPSHOT_MIN; number <= SNAPSHOT_MAX; number++) {
      putBlock(lite, number);
    }
    for (long number = 0; number <= historyMax; number++) {
      putBlock(history, number);
    }
    DbTool.getDB(lite.toString(), "properties", DbTool.DbType.RocksDB)
        .put(ByteArray.fromString(LATEST_BLOCK), ByteArray.fromLong(SNAPSHOT_MAX));
    DbTool.getDB(lite.toString(), DBUtils.TMP, DbTool.DbType.RocksDB);
    writeInfo(lite, SNAPSHOT_MIN);
    writeInfo(history, historyMax);
    DbTool.close();
  }

  static List<String> archiveDbs() {
    return ARCHIVE_DBS;
  }

  void migrate(List<String> databases) throws IOException {
    movedDbs = databases;
    String destination = movedRoot.getParent().toRealPath().toString();
    String properties = databases.stream()
        .map(db -> "{ name = \"" + db + "\", path = \"" + destination + "\" }")
        .collect(Collectors.joining(",\n", "storage.properties = [\n", "\n]\n"));
    Files.write(config, properties.getBytes(StandardCharsets.UTF_8));
    assertEquals(error.toString(), 0, cli.execute("db", "mv", "-d",
        lite.getParent().toString(), "-c", config.toString()));
    assertLinks();
    for (String db : databases) {
      Files.write(movedDirectory(db).resolve(STALE_FILE), new byte[] {1});
    }
  }

  int executeMerge() {
    return cli.execute("db", "lite", "-o", "merge", "-fn", lite.toString(),
        "-ds", history.toString());
  }

  String output() {
    return output.toString();
  }

  String error() {
    return error.toString();
  }

  Path movedDirectory(String db) {
    return movedRoot.resolve(db);
  }

  List<Path> backupDirectories() throws IOException {
    try (Stream<Path> entries = Files.list(lite)) {
      return entries.filter(entry -> entry.getFileName().toString().startsWith(".bak_"))
          .collect(Collectors.toList());
    }
  }

  void assertLinks() throws IOException {
    for (String db : movedDbs) {
      Path link = lite.resolve(db);
      assertTrue(db, Files.isSymbolicLink(link));
      assertEquals(db, movedDirectory(db).toRealPath(), link.toRealPath());
    }
  }

  void assertMergedData() throws IOException, RocksDBException {
    assertLinks();
    assertFalse(Files.exists(lite.resolve(INFO_FILE)));
    assertTrue(backupDirectories().isEmpty());
    assertTrue(output(), output().contains("Merge history finished"));
    for (String db : ARCHIVE_DBS) {
      Path database = movedDbs.contains(db) ? movedRoot : lite;
      assertFalse(db, Files.exists(database.resolve(db).resolve(STALE_FILE)));
      assertEquals(db, DbTool.DbType.RocksDB, DbTool.getDbType(database.toString(), db));
      assertStoreData(database, db);
    }
  }

  static byte[] value(String db, long number) {
    switch (db) {
      case "block-index":
        return blockId(number);
      case "block":
        return block(number).toByteArray();
      case "trans":
        return ByteArray.fromLong(number);
      case "transactionRetStore":
        return Protocol.TransactionRet.newBuilder().setBlockNumber(number)
            .addTransactioninfo(transactionInfo(number)).build().toByteArray();
      case "transactionHistoryStore":
        return transactionInfo(number).toByteArray();
      default:
        throw new IllegalArgumentException("Unexpected archive database: " + db);
    }
  }

  static byte[] key(String db, long number) {
    switch (db) {
      case "block":
        return blockId(number);
      case "trans":
      case "transactionHistoryStore":
        return DBUtils.getTransactionId(transaction(number)).getBytes();
      default:
        return ByteArray.fromLong(number);
    }
  }

  private void assertStoreData(Path database, String db) throws IOException, RocksDBException {
    DBInterface store = DbTool.getDB(database.toString(), db);
    boolean containsGenesis = "block".equals(db) || "block-index".equals(db);
    if (containsGenesis) {
      assertArrayEquals(db + " genesis", value(db, 0), store.get(key(db, 0)));
    }
    for (long number = 1; number <= SNAPSHOT_MAX; number++) {
      assertArrayEquals(db + " block " + number, value(db, number), store.get(key(db, number)));
    }
    // Existing trimming excludes transactionHistoryStore; this test preserves that behavior.
    boolean retainsExtra = "transactionHistoryStore".equals(db) && historyMax > SNAPSHOT_MAX;
    long expectedEntries = SNAPSHOT_MAX + (containsGenesis ? 1 : 0);
    if (retainsExtra) {
      assertArrayEquals(db + " retained extra", value(db, historyMax),
          store.get(key(db, historyMax)));
      expectedEntries += historyMax - SNAPSHOT_MAX;
    } else {
      assertNull(db + " extra", store.get(key(db, SNAPSHOT_MAX + 1)));
    }
    assertEquals(db + " entry count", expectedEntries, store.size());
  }

  private static void initializeStores(Path database) throws IOException, RocksDBException {
    for (String db : ARCHIVE_DBS) {
      DbTool.getDB(database.toString(), db, DbTool.DbType.RocksDB);
    }
  }

  private static void putBlock(Path database, long number) throws IOException, RocksDBException {
    for (String db : ARCHIVE_DBS) {
      if (number != 0 || "block".equals(db) || "block-index".equals(db)) {
        DbTool.getDB(database.toString(), db).put(key(db, number), value(db, number));
      }
    }
  }

  private static void writeInfo(Path database, long number) throws IOException {
    String property = DBUtils.SPLIT_BLOCK_NUM + "=" + number + "\n";
    Files.write(database.resolve(INFO_FILE), property.getBytes(StandardCharsets.UTF_8));
  }

  private static Protocol.Transaction transaction(long number) {
    Protocol.Transaction.raw raw = Protocol.Transaction.raw.newBuilder()
        .setTimestamp(number).setExpiration(number + 1).build();
    return Protocol.Transaction.newBuilder().setRawData(raw).build();
  }

  private static Protocol.TransactionInfo transactionInfo(long number) {
    ByteString id = ByteString.copyFrom(DBUtils.getTransactionId(transaction(number)).getBytes());
    return Protocol.TransactionInfo.newBuilder().setId(id).setBlockNumber(number).build();
  }

  private static Protocol.Block block(long number) {
    Protocol.BlockHeader.raw raw = Protocol.BlockHeader.raw.newBuilder()
        .setNumber(number).setTimestamp(number + 1).build();
    Protocol.BlockHeader header = Protocol.BlockHeader.newBuilder().setRawData(raw).build();
    Protocol.Block.Builder block = Protocol.Block.newBuilder().setBlockHeader(header);
    if (number != 0) {
      block.addTransactions(transaction(number));
    }
    return block.build();
  }

  private static byte[] blockId(long number) {
    byte[] header = block(number).getBlockHeader().getRawData().toByteArray();
    byte[] hash = Sha256Hash.of(header).getBytes();
    byte[] height = ByteArray.fromLong(number);
    System.arraycopy(height, 0, hash, 0, height.length);
    return hash;
  }
}
