package org.tron.plugins;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
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
    Protocol.Account account = getAccountVote(startCycle, ByteArray.fromHexString(address));
    long reward = getOldReward(account.getVotesList(), true);
    logger.info("address: {}, reward: {}", address, reward);
    spec.commandLine().getOut().println(
        spec.commandLine().getColorScheme()
            .errorText(String.format("address: %s, reward: %d", address, reward)));

    reward = getOldReward(account.getVotesList(), false);
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

  public Protocol.Account getAccountVote(long cycle, byte[] address) {
    for (int i = 0; i < 4708; i++) {
      byte[] data = delegationStore.get(buildAccountVoteKey(cycle, address));
      if (data != null) {
        logger.info("find account vote at cycle {}, address {}", cycle, Hex.toHexString(address));
        spec.commandLine().getOut().println(
            spec.commandLine().getColorScheme()
                .errorText(String.format("find account vote at cycle %d, address %s",
                    cycle, Hex.toHexString(address))));
        return ByteArray.toAccount(data);
      }
    }
    return null;
  }

  private byte[] buildAccountVoteKey(long cycle, byte[] address) {
    return (cycle + "-" + Hex.toHexString(address) + "-account-vote").getBytes();
  }

  private long getOldReward(List<Protocol.Vote> votes, boolean isNew) {
    long reward = 0;
    for (long cycle = startCycle; cycle < endCycle; cycle++) {
      reward += computeReward(cycle, votes, isNew);
    }
    return reward;
  }

  p

  private long computeReward(long cycle, List<Protocol.Vote> votes, boolean isNew) {
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
      if (isNew) {
        reward += (long) (voteRate * totalReward);
      } else {
        reward += voteRate * totalReward;
      }

    }
    return reward;
  }
}
