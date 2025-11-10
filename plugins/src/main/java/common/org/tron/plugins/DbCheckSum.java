package org.tron.plugins;


import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.rocksdb.RocksDBException;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.MerkleRoot;
import org.tron.plugins.utils.Sha256Hash;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import org.tron.protos.contract.SmartContractOuterClass;
import picocli.CommandLine;

@Slf4j(topic = "db-root")
@CommandLine.Command(name = "checksum",
    description = "compute checksum.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbCheckSum implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0", defaultValue = "output-directory/database",
      description = "Input path. Default: ${DEFAULT-VALUE}")
  private Path db;

  @CommandLine.Option(names = { "--db"}, split = ",",
      description = "db name for show root")
  private List<String> dbs;

  @CommandLine.Option(names = {"--ignore-asset"},
      description = "ignore asset in account, default: ${DEFAULT-VALUE}")
  private boolean ignoreAsset;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  AtomicReference<Optional<Long>> height = new AtomicReference<>(Optional.empty());
  private static final byte[] HEADER_KEY = "latest_block_header_number".getBytes();
  private static final List<String> stateDbs = Arrays.asList(
      "account", "asset-issue-v2",
      "code", "contract", "contract-state", "storage-row",
      "delegation", "DelegatedResource", "DelegatedResourceAccountIndex",
      "exchange-v2",
      "market_account", "market_order", "market_pair_price_to_order", "market_pair_to_price",
      "properties", "proposal",
      "votes", "witness", "witness_schedule"
  );

  DBIterator assetIterator;

  private static final byte[] CURRENT_SHUFFLED_WITNESSES = "current_shuffled_witnesses".getBytes();
  private static final String FORK_PREFIX = "FORK_VERSION_";
  private static final String DONE_SUFFIX = "_DONE";
  private static final String ACCOUNT_VOTE_SUFFIX = "-account-vote";
  private static final Set<String> IGNORED_PROPERTIES = Sets.newHashSet(
      "VOTE_REWARD_RATE", "SINGLE_REPEAT", "NON_EXISTENT_ACCOUNT_TRANSFER_MIN",
      "ALLOW_TVM_ASSET_ISSUE", "ALLOW_TVM_STAKE",
      "MAX_VOTE_NUMBER", "MAX_FROZEN_NUMBER", "MAINTENANCE_TIME_INTERVAL",
      "LATEST_SOLIDIFIED_BLOCK_NUM", "BLOCK_NET_USAGE",
      "VERSION_NUMBER",
      "SHIELDED_TRANSACTION_FEE",
      "BLOCK_FILLED_SLOTS_INDEX", "BLOCK_FILLED_SLOTS_NUMBER", "BLOCK_FILLED_SLOTS");

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

    // remove not exit
    if (dbs != null) {
      dbs.removeIf(s -> !Paths.get(db.toString(), s).toFile().exists());
    }

    if (dbs == null || dbs.isEmpty()) {
      dbs = stateDbs.stream()
          .filter(s -> Paths.get(db.toString(), s).toFile().exists())
          .collect(Collectors.toList());
    } else if (!dbs.contains("properties")) {
      dbs.add("properties");
    }

    if (dbs.contains("account")) {
      dbs.remove("account-asset");
      assetIterator = DbTool.getDB(db, "account-asset").iterator();
    }
    List<Ret> task = ProgressBar.wrap(dbs.stream(), "root task").parallel()
        .map(this::calcMerkleRoot).collect(Collectors.toList());
    long num = height.get().orElseThrow(() -> new IllegalArgumentException("blockNum is null"));
    spec.commandLine().getOut().println("block number: " + num);
    logger.info("block number: {}", num);
    task.forEach(this::printInfo);
    int code = (int) task.stream().filter(r -> r.code == 1).count();
    if (code > 0) {
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText("There are some errors, please check toolkit.log for detail."));
    }
    spec.commandLine().getOut().println("root task done.");
    if (assetIterator != null) {
      assetIterator.close();
    }
    return code;
  }

  private Ret calcMerkleRoot(String name) {
    Ret info = new Ret();
    try (DBInterface database = DbTool.getDB(this.db, name)) {
      DBIterator iterator = database.iterator();
      iterator.seekToFirst();
      final AtomicReference<Sha256Hash> root = new AtomicReference<>(Sha256Hash.ZERO_HASH);
      Streams.stream(iterator).map(e -> map(e, name)).filter(Objects::nonNull).map(this::getHash)
          .forEach(hash -> root.getAndUpdate(v -> MerkleRoot.computeHash(v, hash)));
      logger.info("db: {}, checksum: {}", database.getName(), root.get());
      info.code = 0;
      info.msg = String.format("db: %s, checksum: %s", database.getName(), root.get());
    } catch (RocksDBException | IOException e) {
      logger.error("calc db {} fail", name, e);
      info.code = 1;
      info.msg = String.format("db: %s,fail: %s",
          name, e.getMessage());
    }
    return info;
  }


  private Map<String, Long> getAllAssets(Protocol.Account account) {
    Map<String, Long> assets = new TreeMap<>();
    if (ignoreAsset) {
      return assets;
    }
    int addressSize = account.getAddress().toByteArray().length;
    if (account.getAssetOptimized()) {
      Map<byte[], byte[]> map = prefixQuery(account.getAddress().toByteArray());
      map.forEach((k, v) -> assets.put(ByteArray.toStr(
          ByteArray.subArray(k, addressSize, k.length)), Longs.fromByteArray(v)));
    }
    assets.putAll(account.getAssetV2Map());
    assets.entrySet().removeIf(entry -> entry.getValue() <= 0);
    return assets;
  }

  public Map<byte[], byte[]> prefixQuery(byte[] key) {
    Map<byte[], byte[]> result = new HashMap<>();
    for (assetIterator.seek(key); assetIterator.valid(); assetIterator.next()) {
      if (Bytes.indexOf(assetIterator.getKey(), key) == 0) {
        result.put(assetIterator.getKey(), assetIterator.getValue());
      } else {
        return result;
      }
    }
    return result;
  }

  private Map.Entry<byte[], byte[]> map(Map.Entry<byte[], byte[]> entry, String name) {
    try {
      switch (name) {
        case "account": {
          Protocol.Account account = Protocol.Account.parseFrom(entry.getValue());
          return new AbstractMap.SimpleEntry<>(entry.getKey(),
              account.toBuilder().clearAsset().clearAssetV2().clearAssetOptimized()
                  .putAllAssetV2(getAllAssets(account))
                  .build().toByteArray());
        }
        case "contract": {
          SmartContractOuterClass.SmartContract contract =
              SmartContractOuterClass.SmartContract.parseFrom(entry.getValue());
          return new AbstractMap.SimpleEntry<>(entry.getKey(),
              contract.toBuilder().clearAbi().build().toByteArray());
        }
        case "witness": {
          return new AbstractMap.SimpleEntry<>(entry.getKey(),
              Protocol.Witness.parseFrom(entry.getValue())
                  .toBuilder().clearTotalMissed()
                  .build().toByteArray());
        }
        case "delegation": {
          String keyStr = new String(entry.getKey());
          if (keyStr.endsWith(ACCOUNT_VOTE_SUFFIX)) {
            return new AbstractMap.SimpleEntry<>(entry.getKey(),
                Protocol.Account.newBuilder().addAllVotes(
                    Protocol.Account.parseFrom(
                        entry.getValue()).getVotesList()).build().toByteArray());
          } else {
            return entry;
          }
        }
        case "properties": {
          String keyStr = new String(entry.getKey());
          if (IGNORED_PROPERTIES.contains(keyStr)
              || keyStr.startsWith(FORK_PREFIX) || keyStr.endsWith(DONE_SUFFIX)) {
            return null;
          }
          if (Arrays.equals(HEADER_KEY, entry.getKey())) {
            height.set(Optional.of(ByteArray.toLong(entry.getValue())));
          }
          return  entry;
        }
        case "witness_schedule": {
          if (Arrays.equals(entry.getKey(), CURRENT_SHUFFLED_WITNESSES)) {
            return null;
          }
          return  entry;
        }
        default:
          return entry;
      }
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException("Failed to parse entry: " + entry, e);
    }
  }

  private Sha256Hash getHash(Map.Entry<byte[], byte[]> entry) {
    return Sha256Hash.of(true,
        Bytes.concat(entry.getKey(), entry.getValue()));
  }

  private void printInfo(Ret ret) {
    if (ret.code == 0) {
      spec.commandLine().getOut().println(ret.msg);
    } else {
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(ret.msg));
    }
  }

  private static class Ret {
    private int code;
    private String msg;
  }
}