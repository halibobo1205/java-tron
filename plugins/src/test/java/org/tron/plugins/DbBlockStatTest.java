package org.tron.plugins;

import com.google.common.primitives.Bytes;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.DBUtils;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import org.tron.protos.contract.BalanceContract.TransferContract;
import picocli.CommandLine;

@Slf4j
public class DbBlockStatTest {

  private static final String BLOCK_DB = "block";
  private static final Protocol.Transaction BIG_TX = txWithRawUnknown(4, 105);

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  CommandLine cli = new CommandLine(new Toolkit());

  @Test
  public void testBlockStatForLevelDB() throws IOException, RocksDBException {
    testBlockStat(DbTool.DbType.LevelDB);
  }

  @Test
  public void testBlockStatForRocksDB() throws IOException, RocksDBException {
    testBlockStat(DbTool.DbType.RocksDB);
  }

  private void testBlockStat(DbTool.DbType dbType) throws IOException, RocksDBException {
    File database = newBlockDb(dbType);
    String result = execute(0, "db", "block-stat", database.toString());
    Assert.assertTrue(result.contains("blocks scanned: 2, parse failed: 0"));
    Assert.assertTrue(result.contains("txs scanned: 4"));
    Assert.assertTrue(result.contains("txs with unknown fields: 3"));
    // 3 + 15 + 105
    Assert.assertTrue(result.contains("unknown-field occurrences: 123"));
    Assert.assertTrue(result.contains("> 10  : 2"));
    Assert.assertTrue(result.contains("> 20  : 1"));
    Assert.assertTrue(result.contains("> 50  : 1"));
    Assert.assertTrue(result.contains("> 100 : 1"));
    Assert.assertTrue(result.contains("max unknown fields in one tx: 105"));
    Assert.assertTrue(result.contains(DBUtils.getTransactionId(BIG_TX).toString()));
    Assert.assertTrue(result.contains("block: 2"));
  }

  @Test
  public void testScanRangeForLevelDB() throws IOException, RocksDBException {
    testScanRange(DbTool.DbType.LevelDB);
  }

  @Test
  public void testScanRangeForRocksDB() throws IOException, RocksDBException {
    testScanRange(DbTool.DbType.RocksDB);
  }

  private void testScanRange(DbTool.DbType dbType) throws IOException, RocksDBException {
    File database = newBlockDb(dbType);

    // scan block 2 only
    String result = execute(0, "db", "block-stat", database.toString(), "--start", "2");
    Assert.assertTrue(result.contains("txs scanned: 2"));
    Assert.assertTrue(result.contains("txs with unknown fields: 2"));
    Assert.assertTrue(result.contains("max unknown fields in one tx: 105"));

    // scan block 1 only
    result = execute(0, "db", "block-stat", database.toString(), "--end", "1");
    Assert.assertTrue(result.contains("txs scanned: 2"));
    Assert.assertTrue(result.contains("txs with unknown fields: 1"));
    Assert.assertTrue(result.contains("max unknown fields in one tx: 3"));

    // invalid range
    execute(400, "db", "block-stat", database.toString(), "--start", "3", "--end", "1");
  }

  /**
   * Every ContractType must resolve to a message descriptor in the
   * type registry, so the Any parameter of any stored transaction can
   * be unpacked and scanned. CustomContract and GetContract are enum
   * placeholders that never got a message definition nor an actuator,
   * so no valid on-chain transaction can carry them.
   */
  @Test
  public void testRegistryCoversAllContractTypes() {
    for (Protocol.Transaction.Contract.ContractType type
        : Protocol.Transaction.Contract.ContractType.values()) {
      if (type == Protocol.Transaction.Contract.ContractType.UNRECOGNIZED
          || type == Protocol.Transaction.Contract.ContractType.CustomContract
          || type == Protocol.Transaction.Contract.ContractType.GetContract) {
        continue;
      }
      Assert.assertTrue("missing descriptor for ContractType." + type.name(),
          DbBlockStat.canResolveType("protocol." + type.name()));
    }
    // legacy storage contracts, removed from the enum but kept in
    // storage_contract.proto, still resolve for historical blocks
    Assert.assertTrue(DbBlockStat.canResolveType("protocol.BuyStorageContract"));
    Assert.assertTrue(DbBlockStat.canResolveType("protocol.SellStorageContract"));
    Assert.assertTrue(DbBlockStat.canResolveType("protocol.BuyStorageBytesContract"));
  }

  @Test
  public void testHelp() {
    String[] args = new String[] {"db", "block-stat", "-h"};
    Assert.assertEquals(0, cli.execute(args));
  }

  @Test
  public void testEmpty() throws IOException {
    File file = folder.newFolder();
    File database = Paths.get(file.getPath(), "database").toFile();
    execute(404, "db", "block-stat", database.toString());
    Assert.assertTrue(database.mkdirs());
    execute(404, "db", "block-stat", database.toString());
  }

  /**
   * Two blocks: block 1 holds a clean tx and a tx with 3 raw-level
   * unknown fields; block 2 holds a tx with 15 unknown fields inside
   * the Any parameter and a tx with 105 raw-level unknown fields.
   */
  private File newBlockDb(DbTool.DbType dbType) throws IOException, RocksDBException {
    File file = folder.newFolder();
    File database = Paths.get(file.getPath(), "database").toFile();
    Assert.assertTrue(database.mkdirs());
    try (DBInterface blockDb = DbTool.getDB(database.toString(), BLOCK_DB, dbType)) {
      blockDb.put(blockKey(1),
          block(1, txWithRawUnknown(1, 0), txWithRawUnknown(2, 3)).toByteArray());
      blockDb.put(blockKey(2),
          block(2, txWithAnyUnknown(3, 15), BIG_TX).toByteArray());
    }
    return database;
  }

  private String execute(int expectedCode, String... args) {
    StringWriter out = new StringWriter();
    cli.setOut(new PrintWriter(out));
    Assert.assertEquals(expectedCode, cli.execute(args));
    return out.toString();
  }

  private static byte[] blockKey(long num) {
    return Bytes.concat(ByteArray.fromLong(num), new byte[24]);
  }

  private static Protocol.Block block(long num, Protocol.Transaction... txs) {
    Protocol.Block.Builder block = Protocol.Block.newBuilder()
        .setBlockHeader(Protocol.BlockHeader.newBuilder()
            .setRawData(Protocol.BlockHeader.raw.newBuilder().setNumber(num)));
    for (Protocol.Transaction tx : txs) {
      block.addTransactions(tx);
    }
    return block.build();
  }

  /**
   * A transaction whose raw_data carries n unknown fields.
   */
  private static Protocol.Transaction txWithRawUnknown(long timestamp, int n) {
    Protocol.Transaction.raw raw = Protocol.Transaction.raw.newBuilder()
        .setTimestamp(timestamp)
        .setUnknownFields(unknownFields(n))
        .build();
    return Protocol.Transaction.newBuilder().setRawData(raw).build();
  }

  /**
   * A transaction whose contract parameter(Any) unpacks to a
   * TransferContract carrying n unknown fields.
   */
  private static Protocol.Transaction txWithAnyUnknown(long timestamp, int n) {
    TransferContract transfer = TransferContract.newBuilder()
        .setOwnerAddress(ByteString.copyFrom(new byte[21]))
        .setToAddress(ByteString.copyFrom(new byte[21]))
        .setAmount(1)
        .build()
        .toBuilder().setUnknownFields(unknownFields(n)).build();
    Protocol.Transaction.Contract contract = Protocol.Transaction.Contract.newBuilder()
        .setType(Protocol.Transaction.Contract.ContractType.TransferContract)
        .setParameter(Any.pack(transfer))
        .build();
    Protocol.Transaction.raw raw = Protocol.Transaction.raw.newBuilder()
        .setTimestamp(timestamp)
        .addContract(contract)
        .build();
    return Protocol.Transaction.newBuilder().setRawData(raw).build();
  }

  private static UnknownFieldSet unknownFields(int n) {
    UnknownFieldSet.Builder builder = UnknownFieldSet.newBuilder();
    for (int i = 0; i < n; i++) {
      builder.addField(1000 + i, UnknownFieldSet.Field.newBuilder().addVarint(i).build());
    }
    return builder.build();
  }
}
