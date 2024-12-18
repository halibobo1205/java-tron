package org.tron.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Callable;

import com.google.common.collect.Sets;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDBException;
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
      description = " ful input path for base")
  private File base;
  @CommandLine.Parameters(index = "1",
      description = "ful input path for compare")
  private File compare;
  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  private static final String ACCOUNT_VOTE_SUFFIX = "-account-vote";

  private static final String FORK_PREFIX = "FORK_VERSION_";
  private static final String DONE_SUFFIX = "_DONE";
  private static final Set<String> ignoredProperties = Sets.newHashSet(
      "VOTE_REWARD_RATE", "SINGLE_REPEAT", "NON_EXISTENT_ACCOUNT_TRANSFER_MIN",
      "ALLOW_TVM_ASSET_ISSUE", "ALLOW_TVM_STAKE",
      "MAX_VOTE_NUMBER", "MAX_FROZEN_NUMBER", "MAINTENANCE_TIME_INTERVAL",
      "LATEST_SOLIDIFIED_BLOCK_NUM", "BLOCK_NET_USAGE",
      "BLOCK_FILLED_SLOTS_INDEX", "BLOCK_FILLED_SLOTS_NUMBER");

  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }
    if (!base.exists()) {
      logger.info(" {} does not exist.", base);
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(String.format("%s does not exist.", base)));
      return 404;
    }
    if (!compare.exists()) {
      logger.info(" {} does not exist.", compare);
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(String.format("%s does not exist.", compare)));
      return 404;
    }

    Comparison service = new DbComparison(base.toPath(), compare.toPath());
    return service.doCompare() ? 0 : 1;

  }

  interface Comparison {
    boolean doCompare() throws Exception;
  }

  static class DbComparison implements Comparison {

    private final Path basePath;
    private final Path dstPath;
    private final String name;

    public DbComparison(Path srcDir, Path dstDir) {
      this.basePath = srcDir.getParent();
      this.dstPath = dstDir.getParent();
      this.name =  srcDir.getFileName().toString();
    }

    @Override
    public boolean doCompare() throws Exception {
      return compare();
    }



    private boolean compare() throws RocksDBException, IOException {
      try (
          DBInterface base  = DbTool.getDB(this.basePath, this.name);
          DBIterator baseIterator = base.iterator();
          DBInterface dst  = DbTool.getDB(this.dstPath, this.name);
          DBIterator dstIterator = dst.iterator()) {

        // check
        logger.info("compare {} start", this.name);
        spec.commandLine().getOut().println("compare " + this.name + " start");
        baseIterator.seekToFirst();
        dstIterator.seekToFirst();
        for (; baseIterator.hasNext() && dstIterator.hasNext();
             baseIterator.next(), dstIterator.next()) {
          byte[] baseValue = baseIterator.getValue();
          byte[] baseKey = baseIterator.getKey();
          byte[] dstKey = dstIterator.getKey();
          byte[] dstValue = dstIterator.getValue();
          if (Arrays.equals(baseKey, dstKey) && !compareValue(baseKey, baseValue, dstValue)) {
            spec.commandLine().getOut().format("%s\t%s\t%s.",
                ByteArray.toHexString(baseKey),
                ByteArray.toHexString(baseValue), ByteArray.toHexString(dstValue)).println();
            logger.info("{}\t{}\t{}",
                 ByteArray.toHexString(baseKey),
                 ByteArray.toHexString(baseValue), ByteArray.toHexString(dstValue));
          }
          if (!Arrays.equals(baseKey, dstKey)) {
            byte[] dstValueTmp = base.get(dstKey);
            byte[] baseValueTmp = dst.get(baseKey);
            if (!compareValue(baseKey, baseValue, baseValueTmp)) {
              spec.commandLine().getOut().format("%s\t%s\t%s.",
                  ByteArray.toHexString(baseKey),
                  ByteArray.toHexString(baseValue), ByteArray.toHexString(baseValueTmp)).println();
              logger.info("{}\t{}\t{}",
                  ByteArray.toHexString(baseKey),
                  ByteArray.toHexString(baseValue), ByteArray.toHexString(baseValueTmp));
            }
            if (!compareValue(dstKey, dstValueTmp, dstValue)) {
              spec.commandLine().getOut().format("%s\t%s\t%s.",
                  ByteArray.toHexString(dstKey),
                  ByteArray.toHexString(dstValueTmp), ByteArray.toHexString(dstValue)).println();
              logger.info("{}\t{}\t{}",
                  ByteArray.toHexString(dstKey),
                  ByteArray.toHexString(dstValueTmp), ByteArray.toHexString(dstValue));
            }

          }
        }
        for (; baseIterator.hasNext(); baseIterator.next()) {
          byte[] key = baseIterator.getKey();
          byte[] baseValue = baseIterator.getValue();
          byte[] destValue = dst.get(key);
          if (!compareValue(key, baseValue, destValue)) {
            spec.commandLine().getOut().format("%s\t%s\t%s.",
                ByteArray.toHexString(key),
                ByteArray.toHexString(baseValue), ByteArray.toHexString(destValue)).println();
            logger.info("{}\t{}\t{}",
                ByteArray.toHexString(key),
                ByteArray.toHexString(baseValue), ByteArray.toHexString(destValue));
          }
        }
        for (; dstIterator.hasNext(); dstIterator.next()) {
          byte[] key = dstIterator.getKey();
          byte[] destValue = dstIterator.getValue();
          byte[] baseValue = base.get(key);
          if (!compareValue(key, baseValue, destValue)) {
            spec.commandLine().getOut().format("%s\t%s\t%s.",
                ByteArray.toHexString(key),
                ByteArray.toHexString(baseValue), ByteArray.toHexString(destValue)).println();
            logger.info("{}\t{}\t{}",
                ByteArray.toHexString(key),
                ByteArray.toHexString(baseValue), ByteArray.toHexString(destValue));
          }
        }
      }
      logger.info("compare {} end", this.name);
      spec.commandLine().getOut().println("compare " + this.name + " end");
      return true;
    }

    private boolean compareValue(byte[] key, byte[] base, byte[] exp) {
      if ("account".equals(name)
          || ("delegation".equals(name) && new String(key).endsWith(ACCOUNT_VOTE_SUFFIX))) {
        Protocol.Account baseAccount = ByteArray.toAccount(base);
        Protocol.Account expAccount = ByteArray.toAccount(exp);
        // remove assetOptimized // TODO why?
        if (baseAccount.getAssetOptimized() || expAccount.getAssetOptimized()) {
          baseAccount = baseAccount.toBuilder().clearAsset().clearAssetV2()
              .setAssetOptimized(true).build();
          expAccount = expAccount.toBuilder().clearAsset().clearAssetV2()
              .setAssetOptimized(true).build();
        }
        return baseAccount.equals(expAccount);
      } else if ("properties".equals(name)) {
        String keyStr = new String(key);
        if (ignoredProperties.contains(keyStr)
            || keyStr.startsWith(FORK_PREFIX) || keyStr.endsWith(DONE_SUFFIX)) {
          return true;
        }
        return Arrays.equals(base, exp);
      }  else {
        return Arrays.equals(base, exp);
      }
    }
  }
}