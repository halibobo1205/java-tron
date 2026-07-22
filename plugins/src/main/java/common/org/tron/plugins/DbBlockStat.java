package org.tron.plugins;

import com.google.protobuf.Any;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.UnknownFieldSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import org.apache.commons.lang3.StringUtils;
import org.rocksdb.RocksDBException;
import org.tron.common.math.StrictMathWrapper;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.DBUtils;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DBIterator;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import org.tron.protos.contract.AccountContract;
import org.tron.protos.contract.AssetIssueContractOuterClass;
import org.tron.protos.contract.BalanceContract;
import org.tron.protos.contract.Common;
import org.tron.protos.contract.ExchangeContract;
import org.tron.protos.contract.MarketContract;
import org.tron.protos.contract.ProposalContract;
import org.tron.protos.contract.ShieldContract;
import org.tron.protos.contract.SmartContractOuterClass;
import org.tron.protos.contract.StorageContract;
import org.tron.protos.contract.VoteAssetContractOuterClass;
import org.tron.protos.contract.WitnessContract;
import picocli.CommandLine;

@Slf4j(topic = "block-stat")
@CommandLine.Command(name = "block-stat",
    description = "Scan the block db and stat unknown fields of transactions."
        + " Unknown fields are counted recursively, including nested messages"
        + " and the contract message unpacked from Contract.parameter(Any).",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbBlockStat implements Callable<Integer> {

  private static final String BLOCK_DB_NAME = "block";
  private static final int BLOCK_NUM_BYTES = 8;
  private static final int BAR_WIDTH = 50;
  private static final int[] THRESHOLDS = {10, 20, 50, 100};
  private static final long[] BUCKET_UPPERS = {10, 20, 50, 100, Long.MAX_VALUE};
  private static final String[] BUCKET_LABELS =
      {"[1,10]", "(10,20]", "(20,50]", "(50,100]", "(100,+)"};

  private static final Map<String, Descriptor> TYPE_REGISTRY = buildTypeRegistry();

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0", defaultValue = "output-directory/database",
      description = "Input path. Default: ${DEFAULT-VALUE}")
  private Path db;

  @CommandLine.Option(names = {"-s", "--start"}, defaultValue = "0",
      description = "start block number, inclusive. Default: ${DEFAULT-VALUE}")
  private long start;

  @CommandLine.Option(names = {"-e", "--end"}, defaultValue = "9223372036854775807",
      description = "end block number, inclusive. Default: latest")
  private long end;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  @Override
  public Integer call() throws Exception {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }
    if (start < 0 || end < start) {
      printErr(String.format("invalid range: [%d, %d].", start, end));
      return 400;
    }
    if (!db.toFile().exists()) {
      logger.info(" {} does not exist.", db);
      printErr(String.format("%s does not exist.", db));
      return 404;
    }
    if (!Paths.get(db.toString(), BLOCK_DB_NAME).toFile().exists()) {
      logger.info(" {} does not contain {} db.", db, BLOCK_DB_NAME);
      printErr(String.format("%s does not contain %s db.", db, BLOCK_DB_NAME));
      return 404;
    }
    final long time = System.currentTimeMillis();
    Stat stat = new Stat();
    try (DBInterface database = DbTool.getDB(this.db, BLOCK_DB_NAME)) {
      scan(database, stat);
    } catch (RocksDBException | IOException e) {
      logger.error("{}", e);
      printErr(e.getMessage());
      return 1;
    }
    print(stat);
    long during = (System.currentTimeMillis() - time) / 1000;
    spec.commandLine().getOut().format("block-stat done, take %d s.", during).println();
    logger.info("block-stat done, take {} s.", during);
    return 0;
  }

  private void scan(DBInterface database, Stat stat) throws IOException {
    long lastNum;
    try (DBIterator iterator = database.iterator()) {
      iterator.seekToLast();
      if (!iterator.valid()) {
        return;
      }
      lastNum = blockNum(iterator.getKey());
    }
    try (DBIterator iterator = database.iterator()) {
      iterator.seek(ByteArray.fromLong(start));
      if (!iterator.hasNext()) {
        return;
      }
      long base = blockNum(iterator.getKey());
      if (base > end) {
        return;
      }
      long target = StrictMathWrapper.min(end, lastNum);
      long span = StrictMathWrapper.max(1, target - base + 1);
      try (ProgressBar pb = new ProgressBarBuilder()
          .setTaskName("block-stat").setInitialMax(span).build()) {
        while (iterator.hasNext()) {
          Map.Entry<byte[], byte[]> entry = iterator.next();
          long num = blockNum(entry.getKey());
          if (num > end) {
            break;
          }
          stat.statBlock(num, entry.getValue());
          pb.stepTo(num - base + 1);
        }
        pb.stepTo(span);
      }
    }
  }

  private void print(Stat stat) {
    List<String> lines = new ArrayList<>();
    lines.add(String.format("scan range: [%d, %s]",
        start, end == Long.MAX_VALUE ? "latest" : end));
    lines.add(String.format("blocks scanned: %d, parse failed: %d", stat.blocks, stat.badBlocks));
    lines.add(String.format("txs scanned: %d", stat.txs));
    double percent = stat.txs == 0 ? 0 : 100.0 * stat.txsWithUnknown / stat.txs;
    lines.add(String.format("txs with unknown fields: %d (%.4f%%)", stat.txsWithUnknown, percent));
    lines.add(String.format("unknown-field occurrences: %d", stat.totalOccurrences));
    lines.add("distribution, unknown-field count per tx:");
    long maxBucket = Arrays.stream(stat.histogram).max().orElse(0);
    for (int i = 0; i < stat.histogram.length; i++) {
      lines.add(String.format("  %-9s: %10d  %s",
          BUCKET_LABELS[i], stat.histogram[i], bar(stat.histogram[i], maxBucket)));
    }
    lines.add("cumulative:");
    for (int i = 0; i < THRESHOLDS.length; i++) {
      lines.add(String.format("  > %-4d: %d", THRESHOLDS[i], stat.overThreshold[i]));
    }
    if (stat.maxCount > 0) {
      lines.add(String.format("max unknown fields in one tx: %d", stat.maxCount));
      lines.add(String.format("  txId : %s", stat.maxTxId));
      lines.add(String.format("  block: %d", stat.maxBlockNum));
    }
    if (stat.unresolvedAnyTypes > 0 || stat.undecodableAnyValues > 0) {
      lines.add(String.format("any parameter: unresolved types %d, undecodable values %d",
          stat.unresolvedAnyTypes, stat.undecodableAnyValues));
    }
    lines.forEach(line -> spec.commandLine().getOut().println(line));
    lines.forEach(line -> logger.info("{}", line));
  }

  private void printErr(String msg) {
    spec.commandLine().getErr().println(spec.commandLine().getColorScheme().errorText(msg));
  }

  /**
   * Whether the Any type name resolves to a known message, exposed for
   * the registry-coverage test against ContractType.
   */
  static boolean canResolveType(String typeName) {
    return TYPE_REGISTRY.containsKey(typeName);
  }

  /**
   * Per-run counters, created once per call so that repeated
   * executions on one command instance never accumulate.
   */
  private static class Stat {

    private long blocks;
    private long badBlocks;
    private long txs;
    private long txsWithUnknown;
    private long totalOccurrences;
    private final long[] histogram = new long[BUCKET_LABELS.length];
    private final long[] overThreshold = new long[THRESHOLDS.length];
    private long maxCount;
    private String maxTxId;
    private long maxBlockNum;
    private long unresolvedAnyTypes;
    private long undecodableAnyValues;

    private void statBlock(long num, byte[] bytes) {
      Protocol.Block block;
      try {
        block = Protocol.Block.parseFrom(bytes);
      } catch (InvalidProtocolBufferException e) {
        badBlocks++;
        logger.warn("parse block {} failed: {}", num, e.getMessage());
        return;
      }
      blocks++;
      for (Protocol.Transaction tx : block.getTransactionsList()) {
        recordTx(num, tx);
      }
    }

    private void recordTx(long num, Protocol.Transaction tx) {
      txs++;
      long count = countMessage(tx);
      if (count == 0) {
        return;
      }
      txsWithUnknown++;
      totalOccurrences += count;
      histogram[bucketIndex(count)]++;
      for (int i = 0; i < THRESHOLDS.length; i++) {
        if (count > THRESHOLDS[i]) {
          overThreshold[i]++;
        }
      }
      if (count > maxCount) {
        maxCount = count;
        maxTxId = DBUtils.getTransactionId(tx).toString();
        maxBlockNum = num;
      }
    }

    /**
     * Count unknown-field occurrences of the message recursively:
     * the message itself, every nested message field, and the contract
     * message unpacked from any google.protobuf.Any field.
     */
    private long countMessage(Message message) {
      long count = countUnknownFieldSet(message.getUnknownFields());
      for (Map.Entry<FieldDescriptor, Object> entry : message.getAllFields().entrySet()) {
        FieldDescriptor field = entry.getKey();
        if (field.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
          continue;
        }
        if (field.isRepeated()) {
          for (Object element : (List<?>) entry.getValue()) {
            count += countChildMessage((Message) element);
          }
        } else {
          count += countChildMessage((Message) entry.getValue());
        }
      }
      return count;
    }

    private long countChildMessage(Message child) {
      long count = countMessage(child);
      if (child instanceof Any) {
        count += countInsideAny((Any) child);
      }
      return count;
    }

    private long countInsideAny(Any any) {
      String typeUrl = any.getTypeUrl();
      String typeName = typeUrl.substring(typeUrl.lastIndexOf('/') + 1);
      Descriptor descriptor = TYPE_REGISTRY.get(typeName);
      if (descriptor == null) {
        unresolvedAnyTypes++;
        logger.debug("unresolved Any type: {}", typeUrl);
        return 0;
      }
      try {
        return countMessage(DynamicMessage.parseFrom(descriptor, any.getValue()));
      } catch (InvalidProtocolBufferException e) {
        undecodableAnyValues++;
        logger.debug("undecodable Any value for type: {}", typeUrl);
        return 0;
      }
    }
  }

  private static long countUnknownFieldSet(UnknownFieldSet unknownFields) {
    long count = 0;
    for (UnknownFieldSet.Field field : unknownFields.asMap().values()) {
      count += field.getVarintList().size();
      count += field.getFixed32List().size();
      count += field.getFixed64List().size();
      count += field.getLengthDelimitedList().size();
      count += field.getGroupList().size();
      for (UnknownFieldSet group : field.getGroupList()) {
        count += countUnknownFieldSet(group);
      }
    }
    return count;
  }

  private static String bar(long value, long max) {
    if (value <= 0 || max <= 0) {
      return "";
    }
    int len = (int) StrictMathWrapper.max(1, value * BAR_WIDTH / max);
    return StringUtils.repeat('#', len);
  }

  private static int bucketIndex(long count) {
    for (int i = 0; i < BUCKET_UPPERS.length - 1; i++) {
      if (count <= BUCKET_UPPERS[i]) {
        return i;
      }
    }
    return BUCKET_UPPERS.length - 1;
  }

  private static long blockNum(byte[] blockId) {
    return ByteArray.toLong(Arrays.copyOf(blockId, BLOCK_NUM_BYTES));
  }

  private static Map<String, Descriptor> buildTypeRegistry() {
    List<FileDescriptor> files = Arrays.asList(
        Protocol.getDescriptor(),
        AccountContract.getDescriptor(),
        AssetIssueContractOuterClass.getDescriptor(),
        BalanceContract.getDescriptor(),
        Common.getDescriptor(),
        ExchangeContract.getDescriptor(),
        MarketContract.getDescriptor(),
        ProposalContract.getDescriptor(),
        ShieldContract.getDescriptor(),
        SmartContractOuterClass.getDescriptor(),
        StorageContract.getDescriptor(),
        VoteAssetContractOuterClass.getDescriptor(),
        WitnessContract.getDescriptor());
    Map<String, Descriptor> types = new HashMap<>();
    for (FileDescriptor file : files) {
      for (Descriptor message : file.getMessageTypes()) {
        types.put(message.getFullName(), message);
      }
    }
    return types;
  }
}
