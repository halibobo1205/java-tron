package org.tron.plugins;


import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;


@Slf4j(topic = "db-root")
@CommandLine.Command(name = "scan-account",
    description = "scan-account.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbScanAccount implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0", defaultValue = "output-directory/database",
      description = "Input path. Default: ${DEFAULT-VALUE}")
  private Path db;

  @CommandLine.Option(names = {"--ignore-asset"},
      description = "ignore asset in account, default: ${DEFAULT-VALUE}")
  private boolean ignoreAsset;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  DBInterface accountAsset;
  DBInterface account;

  private static final AtomicLong total = new AtomicLong(0);

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


    accountAsset = DbTool.getDB(db, "account-asset");
    account = DbTool.getDB(db, "account");
    DBIterator iterator = account.iterator();
    iterator.seekToFirst();
    ProgressBar.wrap(iterator, "scan").forEachRemaining(this::scan);
    spec.commandLine().getOut().println("scan account total: " + total.get());
    return 0;
  }



  private Map<String, Long> getAllAssets(Protocol.Account account) {
    Map<String, Long> assets = new TreeMap<>();
    if (ignoreAsset || !account.getAssetOptimized()) {
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
    try (DBIterator iterator = accountAsset.iterator()) {
      Map<byte[], byte[]> result = new HashMap<>();
      for (iterator.seek(key); iterator.valid(); iterator.next()) {
        if (Bytes.indexOf(iterator.getKey(), key) == 0) {
          result.put(iterator.getKey(), iterator.getValue());
        } else {
          return result;
        }
      }
      return result;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void scan(Map.Entry<byte[], byte[]> entry) {
    try {
      Protocol.Account account = Protocol.Account.parseFrom(entry.getValue());
      account.toBuilder().clearAsset().clearAssetV2().clearAssetOptimized()
          .putAllAssetV2(getAllAssets(account));
      if (total.incrementAndGet() % 1000000 == 0) {
        logger.info("scan account total: {}", total.get());
        spec.commandLine().getOut().println("scan account total: " + total.get());
      }
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    }
  }
}