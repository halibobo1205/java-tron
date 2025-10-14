package org.tron.plugins;

import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.tron.plugins.utils.ByteArray;
import org.tron.plugins.utils.db.DBInterface;
import org.tron.plugins.utils.db.DbTool;
import org.tron.protos.Protocol;
import picocli.CommandLine;

@Slf4j(topic = "reward-fast-scan")
@CommandLine.Command(name = "reward",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "n:query failed,please check toolkit.log"})
public class DbReward implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;
  @CommandLine.Parameters(index = "0", description = " database path")
  private Path db;
  @CommandLine.Option(names = {"-k", "--key"}, description = "address in hex")
  private String address;

  @CommandLine.Option(names = {"-s", "--start"})
  private long startCycle;

  @CommandLine.Option(names = {"-e", "--end"})
  private long endCycle;

  @CommandLine.Option(names = {"-h", "--help"}, help = true, description = "display a help message")
  private boolean help;

  private DBInterface delegationStore;

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
    delegationStore = DbTool.getDB(this.db, "delegation");
    return query();
  }

  private int query() {
    Protocol.Account account = getAccountVote(ByteArray.fromHexString(address));
    long reward = getOldReward(account.getVotesList(), 0);
    logger.info("address: {}, reward: {}", address, reward);
    spec.commandLine().getOut().println(
        spec.commandLine().getColorScheme()
            .errorText(String.format("address: %s, reward: %d", address, reward)));

    reward = getOldReward(account.getVotesList(), 1);
    logger.info("address: {}, reward: {}", address, reward);
    spec.commandLine().getOut().println(
        spec.commandLine().getColorScheme()
            .errorText(String.format("address: %s, reward: %d", address, reward)));

    reward = getOldReward(account.getVotesList(), 2);
    logger.info("address: {}, reward: {}", address, reward);
    spec.commandLine().getOut().println(
        spec.commandLine().getColorScheme()
            .errorText(String.format("address: %s, reward: %d", address, reward)));

    return 0;
  }

  private long getReward(long cycle, byte[] address) {
    byte[] reward = delegationStore.get(buildRewardKey(cycle, address));
    if (reward == null) {
      return 0L;
    } else {
      return ByteArray.toLong(reward);
    }
  }

  private long getWitnessVote(long cycle, byte[] address) {
    byte[] vote = delegationStore.get(buildVoteKey(cycle, address));
    if (vote == null) {
      return -1;
    } else {
      return ByteArray.toLong(vote);
    }
  }


  private byte[] buildVoteKey(long cycle, byte[] address) {
    return (cycle + "-" + Hex.toHexString(address) + "-vote").getBytes();
  }

  private byte[] buildRewardKey(long cycle, byte[] address) {
    return (cycle + "-" + Hex.toHexString(address) + "-reward").getBytes();
  }

  public Protocol.Account getAccountVote(byte[] address) {
    Protocol.Account.Builder builder = Protocol.Account.newBuilder();
    List<AbstractMap.SimpleEntry<String, Long>> votes = Arrays.asList(
        new AbstractMap.SimpleEntry<>("4184399fc6a98edc11a6efb146e86a3e153d0a0933", 1000L),
        new AbstractMap.SimpleEntry<>("414d1ef8673f916debb7e2515a8f3ecaf2611034aa", 500L),
        new AbstractMap.SimpleEntry<>("41d25855804e4e65de904faf3ac74b0bdfc53fac76", 100L),
        new AbstractMap.SimpleEntry<>("41c189fa6fc9ed7a3580c3fe291915d5c6a6259be7", 100L),
        new AbstractMap.SimpleEntry<>("41f70386347e689e6308e4172ed7319c49c0f66e0b", 50L),
        new AbstractMap.SimpleEntry<>("412d7bdb9846499a2e5e6c5a7e6fb05731c83107c7", 50L),
        new AbstractMap.SimpleEntry<>("41b3eec71481e8864f0fc1f601b836b74c40548287", 50L),
        new AbstractMap.SimpleEntry<>("4167e39013be3cdd3814bed152d7439fb5b6791409", 50L),
        new AbstractMap.SimpleEntry<>("4192c5d96c3b847268f4cb3e33b87ecfc67b5ce3de", 50L),
        new AbstractMap.SimpleEntry<>("41beab998551416b02f6721129bb01b51fceceba08", 50L),
        new AbstractMap.SimpleEntry<>("41a4475dbd14feb2221f303fc33dc8d0a08f25f445", 50L),
        new AbstractMap.SimpleEntry<>("41b668d4991cd636b694989ebf3fa1a84613d7899e", 50L),
        new AbstractMap.SimpleEntry<>("41c4bc4d7f64df4fd3670ce38e1a60080a50da85cf", 50L),
        new AbstractMap.SimpleEntry<>("41bac7378c4265ad2739772337682183b8864f517a", 50L),
        new AbstractMap.SimpleEntry<>("414a193c92cd631c1911b99ca964da8fd342f4cddd", 50L),
        new AbstractMap.SimpleEntry<>("41b487cdc02de90f15ac89a68c82f44cbfe3d915ea", 50L),
        new AbstractMap.SimpleEntry<>("4138e3e3a163163db1f6cfceca1d1c64594dd1f0ca", 50L),
        new AbstractMap.SimpleEntry<>("41f29f57614a6b201729473c837e1d2879e9f90b8e", 50L),
        new AbstractMap.SimpleEntry<>("41c05142fd1ca1e03688a43585096866ae658f2cb2", 50L),
        new AbstractMap.SimpleEntry<>("41d70365508e5a6fe846ad433af9302779fd5fdb1b", 50L)
    );
    for (AbstractMap.SimpleEntry<String, Long> vote : votes) {
      builder.addVotes(Protocol.Vote.newBuilder()
              .setVoteAddress(ByteString.copyFrom(ByteArray.fromHexString(vote.getKey())))
              .setVoteCount(vote.getValue())
              .build()
      );
    }
    builder.setAddress(ByteString.copyFrom(address));
    return builder.build();
  }

  private long getOldReward(List<Protocol.Vote> votes, int isNew) {
    long reward = 0;
    for (long cycle = startCycle; cycle < endCycle; cycle++) {
      reward += computeReward(cycle, votes, isNew);
    }
    return reward;
  }

  private long computeReward(long cycle, List<Protocol.Vote> votes, int isNew) {
    long reward = 0;
    for (Protocol.Vote vote : votes) {
      byte[] srAddress = vote.getVoteAddress().toByteArray();
      long totalReward = getReward(cycle, srAddress);
      if (totalReward <= 0) {
        continue;
      }
      long totalVote = getWitnessVote(cycle, srAddress);
      if (totalVote == -1 || totalVote == 0) {
        continue;
      }
      long userVote = vote.getVoteCount();
      double voteRate = (double) userVote / totalVote;
      if (isNew == 0) {
        reward += (long) (voteRate * totalReward);
      } else if (isNew == 1) {
        reward += voteRate * totalReward;
      } else if (isNew == 2) {
        double tmp = reward;
        tmp += voteRate * totalReward;
        reward = (long) tmp;
      }

    }
    return reward;
  }
}
