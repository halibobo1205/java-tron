package org.tron.plugins;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;

@Slf4j(topic = "compare")
@CommandLine.Command(name = "compare",
    description = "compare data between two path for db.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:compare diff find,please check toolkit.log"})
public class DbCompare implements Callable<Integer> {

  @CommandLine.Spec
  static CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0",
      description = "input path for base")
  private Path base;
  @CommandLine.Parameters(index = "1",
      description = "input path for compare")
  private Path compare;

  @CommandLine.Option(names = { "-s", "--start"},
      description = "start key")
  private String key;
  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  DBInterface assetBase;
  DBInterface assetCompare;

  private long count = 0;

  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }

    assetBase = DbTool.getDB(base, "account-asset");
    assetCompare = DbTool.getDB(compare, "account-asset");
    boolean result = compare();
    spec.commandLine().getOut().format("compare asset diff cnt %d", count).println();
    logger.info("compare asset diff cnt {}", count);
    return result ? 0 : 1;
  }


  private boolean compare() throws IOException {
    try (DBIterator baseIterator = assetBase.iterator();
         DBIterator dstIterator = assetCompare.iterator()) {
      logger.info("compare account start");
      spec.commandLine().getOut().println("compare account start");
      baseIterator.seekToFirst();
      dstIterator.seekToFirst();
      if (key != null && !key.isEmpty()) {
        byte[] startKey = ByteArray.fromHexString(key);
        baseIterator.seek(startKey);
        dstIterator.seek(startKey);
        // skip start key
        if (Arrays.equals(baseIterator.getKey(), startKey)) {
          baseIterator.next();
        }
        if (Arrays.equals(dstIterator.getKey(), startKey)) {
          dstIterator.next();
        }
      }
      for (; baseIterator.hasNext() && dstIterator.hasNext();
           baseIterator.next(), dstIterator.next()) {
        byte[] baseValue = baseIterator.getValue();
        byte[] baseKey = baseIterator.getKey();
        byte[] dstKey = dstIterator.getKey();
        byte[] dstValue = dstIterator.getValue();
        if (Arrays.equals(baseKey, dstKey) && notEqual(baseKey, baseValue, dstValue)) {
          //return false;
        }
        if (!Arrays.equals(baseKey, dstKey)) {
          byte[] dstValueTmp = assetBase.get(dstKey);
          byte[] baseValueTmp = assetCompare.get(baseKey);
          if (notEqual(baseKey, baseValue, baseValueTmp)) {
            //return false;
          }
          if (notEqual(dstKey, dstValueTmp, dstValue)) {
           // return false;
          }
        }
      }
      for (; baseIterator.hasNext(); baseIterator.next()) {
        byte[] key = baseIterator.getKey();
        byte[] baseValue = baseIterator.getValue();
        byte[] destValue = assetCompare.get(key);
        if (notEqual(key, baseValue, destValue)) {
          //return false;
        }
      }
      for (; dstIterator.hasNext(); dstIterator.next()) {
        byte[] key = dstIterator.getKey();
        byte[] destValue = dstIterator.getValue();
        byte[] baseValue = assetBase.get(key);
        if (notEqual(key, baseValue, destValue)) {
         // return false;
        }
      }
    }
    logger.info("compare account end");
    spec.commandLine().getOut().println("compare account end");
    return true;
  }

  private boolean notEqual(byte[] key, byte[] base, byte[] exp) {
    long baseAmount = base == null ? 0 : Longs.fromByteArray(base);
    long expAmount = exp == null ? 0 : Longs.fromByteArray(exp);
    boolean ret = !(baseAmount == expAmount);
    if (ret) {
      count++;
      String address = ByteArray.toHexString(ByteArray.subArray(key, 0, 21));
      String assetId = ByteArray.toStr(ByteArray.subArray(key, 21, key.length));
      spec.commandLine().getOut()
          .format("%s,%s,%d,%d", address, assetId, baseAmount, expAmount).println();
      logger.info("address: {}, assetId: {}, base: {}, exp: {}",
          address, assetId, baseAmount, expAmount);
    }
    return ret;
  }

  private Protocol.Account fillAllAssets(DBIterator iterator,
                                         Protocol.Account account) {
    Map<String, Long> assets = new TreeMap<>();
    int addressSize = account.getAddress().toByteArray().length;
    if (account.getAssetOptimized()) {
      Map<byte[], byte[]> map = prefixQuery(iterator, account.getAddress().toByteArray());
      map.forEach((k, v) -> assets.put(ByteArray.toStr(
          ByteArray.subArray(k, addressSize, k.length)), Longs.fromByteArray(v)));
    }
    assets.putAll(account.getAssetV2Map());
    assets.entrySet().removeIf(entry -> entry.getValue() <= 0);
    return account.toBuilder().clearAsset().clearAssetV2().clearAssetOptimized()
        .putAllAssetV2(assets)
        .build();
  }

  private Map<byte[], byte[]> prefixQuery(DBIterator iterator, byte[] key) {
    Map<byte[], byte[]> result = new HashMap<>();
    for (iterator.seek(key); iterator.valid(); iterator.next()) {
      if (Bytes.indexOf(iterator.getKey(), key) == 0) {
        result.put(iterator.getKey(), iterator.getValue());
      } else {
        return result;
      }
    }
    return result;
  }
}