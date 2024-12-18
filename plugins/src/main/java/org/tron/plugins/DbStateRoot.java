package org.tron.plugins;

import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.DBUtils;
import org.tron.plugins.utils.MerkleRoot;
import org.tron.plugins.utils.Sha256Hash;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;

@Slf4j(topic = "stateRoot")
@CommandLine.Command(name = "stateRoot",
    description = "stateRoot for tmp.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:Internal error: exception occurred,please check toolkit.log"})
public class DbStateRoot implements Callable<Integer> {


  static {
    RocksDB.loadLibrary();
  }

  private static final byte[] HEADER_KEY = "latest_block_header_number".getBytes();

  private static DBInterface assetStore;
  private static final List<String> stateDbs = Arrays.asList(
      "account", "account-asset", "asset-issue-v2",
      "code", "contract", "contract-state", "storage-row",
      "delegation", "DelegatedResource",
      "exchange-v2",
      "market_account", "market_order", "market_pair_price_to_order", "market_pair_to_price",
      "properties", "proposal",
      "votes", "witness", "witness_schedule"
  );
  private static final byte[] CURRENT_SHUFFLED_WITNESSES = "current_shuffled_witnesses".getBytes();
  private static final String FORK_PREFIX = "FORK_VERSION_";
  private static final String DONE_SUFFIX = "_DONE";
  private static final String ACCOUNT_VOTE_SUFFIX = "-account-vote";
  private static final Set<String> ignoredProperties = Sets.newHashSet(
      "VOTE_REWARD_RATE", "SINGLE_REPEAT", "NON_EXISTENT_ACCOUNT_TRANSFER_MIN",
      "ALLOW_TVM_ASSET_ISSUE", "ALLOW_TVM_STAKE",
      "MAX_VOTE_NUMBER", "MAX_FROZEN_NUMBER", "MAINTENANCE_TIME_INTERVAL",
      "LATEST_SOLIDIFIED_BLOCK_NUM", "BLOCK_NET_USAGE",
      "BLOCK_FILLED_SLOTS_INDEX", "BLOCK_FILLED_SLOTS_NUMBER");



  @CommandLine.Spec
  static CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0", defaultValue = "output-directory/database",
      description = " Input path for db. Default: ${DEFAULT-VALUE}")
  private File src;

  @CommandLine.Option(names = { "--db"},
      description = "db name for show root")
  private String db;


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
    File dbFile = Paths.get(src.toString(), db).toFile();

    if (!dbFile.exists()) {
      logger.info("{} does not contain any database.", src);
      spec.commandLine().getOut().format("%s does not contain any database.", src).println();
      return 0;
    }
    try (DBInterface database = DbTool.getDB(src.toPath(), db)) {
      DBIterator iterator = database.iterator();
      iterator.seekToFirst();
      Map<byte[], byte[]> rows = Streams.stream(iterator).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
      Pair<Optional<Long>, Sha256Hash> rootHash = getRootHash(rows);
      Sha256Hash root = rootHash.getRight();
      long num = rootHash.getLeft().orElse(BigInteger.ZERO.longValue());
      logger.info("{}: {}", num, root);
      spec.commandLine().getOut().println(num + " : " + root);
    } catch (Exception e) {
      logger.error("error: ", e);
      return 1;
    }
    return 0;
  }

  public Pair<Optional<Long>, Sha256Hash> getRootHash(Map<byte[], byte[]> rows) {
    try {
      Map<byte[], byte[]> preparedStateData = preparedStateData(rows);
      AtomicReference<Optional<Long>> height = new AtomicReference<>(Optional.empty());
      List<Sha256Hash> ids = Streams.stream(preparedStateData.entrySet()).parallel().map(entry -> {
        if (Arrays.equals(HEADER_KEY, entry.getKey())) {
          height.set(Optional.of(ByteArray.toLong(entry.getValue())));
        }
        return getHash(entry);
      }).sorted().collect(Collectors.toList());
      Sha256Hash actual = MerkleRoot.root(ids);
      return Pair.of(height.get(), actual);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private Map<byte[], byte[]> preparedStateData(Map<byte[], byte[]> rows)
      throws IOException {
    Map<byte[], byte[]> preparedStateData = new HashMap<>(rows.size());
    for (Map.Entry<byte[], byte[]> e : rows.entrySet()) {
      byte[] key = e.getKey();
      String dbName = simpleDecode(key);
      if (!stateDbs.contains(dbName)) {
        continue;
      }
      byte[] realKey = Arrays.copyOfRange(key, dbName.getBytes().length + Integer.BYTES,
          key.length);
      if ("witness_schedule".equals(dbName) && Arrays.equals(realKey, CURRENT_SHUFFLED_WITNESSES)) {
        continue;
      }
      if ("properties".equals(dbName)) {
        String keyStr = new String(realKey);
        if (ignoredProperties.contains(keyStr)
            || keyStr.startsWith(FORK_PREFIX) || keyStr.endsWith(DONE_SUFFIX)) {
          continue;
        }
      }
      byte[] value = e.getValue();
      byte[] realValue = value.length == 1 ? null : Arrays.copyOfRange(value, 1, value.length);
      if (realValue != null) {
        if ("witness".equals(dbName)) {
          realValue = Protocol.Witness.parseFrom(realValue)
              .toBuilder().clearTotalMissed()
              .build().toByteArray(); // ignore totalMissed
        }
        if ("account".equals(dbName)
            || ("delegation".equals(dbName) && new String(realKey).endsWith(ACCOUNT_VOTE_SUFFIX))) {
          Protocol.Account account = Protocol.Account.parseFrom(realValue);
          Map<String, Long> assets = new TreeMap<>(getAllAssets(account));
          assets.entrySet().removeIf(entry -> entry.getValue() == 0);
          realValue = account.toBuilder().clearAsset().clearAssetV2().clearAssetOptimized()
              .putAllAssetV2(assets)
              .build().toByteArray();
        }
      }
      if (realValue != null) {
        preparedStateData.put(realKey, realValue);
      } else {
        if (DBUtils.Operator.DELETE.getValue() != value[0]) {
          preparedStateData.put(realKey, ByteString.EMPTY.toByteArray());
        }
      }
    }
    return preparedStateData;
  }

  private  Map<String, Long> getAllAssets(Protocol.Account account) {
    try {
      assetStore = DbTool.getDB(src.toString(), "account-asset");
    } catch (IOException | RocksDBException e) {
      throw new RuntimeException(e);
    }
    Map<String, Long> assets = new HashMap<>();
    if (account.getAssetOptimized()) {
      byte[] key = account.getAddress().toByteArray();
      try (DBIterator iterator = assetStore.iterator()) {
        for (iterator.seek(key); iterator.hasNext(); iterator.next()) {
          Map.Entry<byte[], byte[]> entry = iterator.next();
          if (Bytes.indexOf(entry.getKey(), key) == 0) {
            byte[] asset = ByteArray.subArray(entry.getKey(),
                account.getAddress().toByteArray().length, entry.getKey().length);
            assets.put(ByteArray.toStr(asset), Longs.fromByteArray(entry.getValue()));
          } else {
            break;
          }
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    assets.putAll(account.getAssetV2Map());
    return assets;
  }

  private static String simpleDecode(byte[] bytes) {
    byte[] lengthBytes = Arrays.copyOf(bytes, Integer.BYTES);
    int length = Ints.fromByteArray(lengthBytes);
    byte[] value = Arrays.copyOfRange(bytes, Integer.BYTES, Integer.BYTES + length);
    return new String(value);
  }

  private static Sha256Hash getHash(Map.Entry<byte[], byte[]> entry) {
    return  Sha256Hash.of(true, Bytes.concat(entry.getKey(), entry.getValue()));
  }

}
