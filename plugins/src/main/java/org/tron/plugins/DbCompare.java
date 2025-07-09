package org.tron.plugins;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
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
  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  DBIterator accountAssetBase;
  DBIterator accountAssetCompare;
  DBInterface accountBase;
  DBInterface accountCompare;

  private long count = 0;

  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }

    accountAssetBase = DbTool.getDB(base, "account-asset").iterator();
    accountAssetCompare = DbTool.getDB(compare, "account-asset").iterator();
    accountBase = DbTool.getDB(base, "account");
    accountCompare = DbTool.getDB(compare, "account");
    boolean result = compare();
    spec.commandLine().getOut().format("compare account cnt %d", count).println();
    logger.info("compare account cnt {}", count);
    return result ? 0 : 1;
  }


  private boolean compare() throws IOException {
    try (DBIterator baseIterator = accountBase.iterator();
         DBIterator dstIterator = accountCompare.iterator()) {
      logger.info("compare account start");
      spec.commandLine().getOut().println("compare account start");
      baseIterator.seekToFirst();
      dstIterator.seekToFirst();
      for (; baseIterator.hasNext() && dstIterator.hasNext();
           baseIterator.next(), dstIterator.next()) {
        byte[] baseValue = baseIterator.getValue();
        byte[] baseKey = baseIterator.getKey();
        byte[] dstKey = dstIterator.getKey();
        byte[] dstValue = dstIterator.getValue();
        if (Arrays.equals(baseKey, dstKey) && notEqual(baseKey, baseValue, dstValue)) {
          return false;
        }
        if (!Arrays.equals(baseKey, dstKey)) {
          byte[] dstValueTmp = accountBase.get(dstKey);
          byte[] baseValueTmp = accountCompare.get(baseKey);
          if (notEqual(baseKey, baseValue, baseValueTmp)) {
            return false;
          }
          if (notEqual(dstKey, dstValueTmp, dstValue)) {
            return false;
          }
        }
      }
      for (; baseIterator.hasNext(); baseIterator.next()) {
        byte[] key = baseIterator.getKey();
        byte[] baseValue = baseIterator.getValue();
        byte[] destValue = accountCompare.get(key);
        if (notEqual(key, baseValue, destValue)) {
          return false;
        }
      }
      for (; dstIterator.hasNext(); dstIterator.next()) {
        byte[] key = dstIterator.getKey();
        byte[] destValue = dstIterator.getValue();
        byte[] baseValue = accountBase.get(key);
        if (notEqual(key, baseValue, destValue)) {
          return false;
        }
      }
    }
    logger.info("compare account end");
    spec.commandLine().getOut().println("compare account end");
    return true;
  }

  private boolean notEqual(byte[] key, byte[] base, byte[] exp) {
    Protocol.Account baseAccount = fillAllAssets(accountAssetBase, ByteArray.toAccount(base));
    Protocol.Account expAccount = fillAllAssets(accountAssetCompare, ByteArray.toAccount(exp));
    boolean ret = !baseAccount.equals(expAccount);
    count++;
    if (ret) {
      spec.commandLine().getOut().format("%s\t%s\t%s.",
          ByteArray.toHexString(key),
          ByteArray.prettyPrint(baseAccount), ByteArray.prettyPrint(expAccount)).println();
      logger.info("{}\t{}\t{}",
          ByteArray.toHexString(key),
          ByteArray.prettyPrint(baseAccount), ByteArray.prettyPrint(expAccount));
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