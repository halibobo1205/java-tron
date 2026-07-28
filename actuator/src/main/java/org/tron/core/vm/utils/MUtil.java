package org.tron.core.vm.utils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.vm.VMUtils;
import org.tron.core.vm.archive.ArchiveRepositoryAdapter;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.Program.OutOfTimeException;
import org.tron.core.vm.repository.Repository;
import org.tron.protos.Protocol;

public class MUtil {


  private MUtil() {
  }

  public static void transfer(Repository deposit, byte[] fromAddress, byte[] toAddress, long amount)
      throws ContractValidateException {
    if (0 == amount) {
      return;
    }
    VMUtils.validateForSmartContract(deposit, fromAddress, toAddress, amount);
    deposit.addBalance(toAddress, amount);
    deposit.addBalance(fromAddress, -amount);
  }

  public static void transferAllToken(Repository deposit, byte[] fromAddress, byte[] toAddress) {
    if (deposit.isHistoricalArchive()) {
      transferAllToken(
          deposit, fromAddress, toAddress, deposit.getTokenBalances(fromAddress));
      return;
    }
    AccountCapsule fromAccountCap = deposit.getAccount(fromAddress);
    Protocol.Account.Builder fromBuilder = fromAccountCap.getInstance().toBuilder();
    AccountCapsule toAccountCap = deposit.getAccount(toAddress);
    toAccountCap.importAllAsset();
    Protocol.Account.Builder toBuilder = toAccountCap.getInstance().toBuilder();
    fromAccountCap.getAssetMapV2().forEach((tokenId, amount) -> {
      toBuilder.putAssetV2(tokenId, toBuilder.getAssetV2Map().getOrDefault(tokenId, 0L) + amount);
      fromBuilder.putAssetV2(tokenId, 0L);
    });

    deposit.putAccountValue(fromAddress, new AccountCapsule(fromBuilder.build()));
    deposit.putAccountValue(toAddress, new AccountCapsule(toBuilder.build()));
  }

  /** Transfers a caller-provided immutable balance snapshot on the historical archive path. */
  public static void transferAllToken(Repository deposit, byte[] fromAddress, byte[] toAddress,
      Map<String, Long> balances) {
    if (!deposit.isHistoricalArchive()) {
      transferAllToken(deposit, fromAddress, toAddress);
      return;
    }
    if (balances == null) {
      throw ArchiveRepositoryAdapter.unsupportedHistoricalOperation(
          "SELFDESTRUCT TRC10 balance enumeration");
    }
    balances.forEach((tokenId, amount) -> {
      if (tokenId == null || tokenId.isEmpty() || amount == null || amount <= 0L) {
        throw ArchiveRepositoryAdapter.unsupportedHistoricalOperation(
            "SELFDESTRUCT TRC10 balance enumeration");
      }
      byte[] tokenIdBytes = tokenId.getBytes(StandardCharsets.US_ASCII);
      deposit.addTokenBalance(toAddress, tokenIdBytes, amount);
      deposit.addTokenBalance(fromAddress, tokenIdBytes, -amount);
    });
  }

  public static void transferToken(Repository deposit, byte[] fromAddress, byte[] toAddress,
      String tokenId, long amount)
      throws ContractValidateException {
    if (0 == amount) {
      return;
    }
    VMUtils.validateForSmartContract(deposit, fromAddress, toAddress, tokenId.getBytes(), amount);
    deposit.addTokenBalance(toAddress, tokenId.getBytes(), amount);
    deposit.addTokenBalance(fromAddress, tokenId.getBytes(), -amount);
  }

  public static boolean isNullOrEmpty(String str) {
    return (str == null) || str.isEmpty();
  }

  public static boolean isNotNullOrEmpty(String str) {
    return !isNullOrEmpty(str);
  }

  public static void checkCPUTime() {
    if (VMConfig.passFork471()) {
      throw new OutOfTimeException("CPU timeout for 0x0a executing");
    }
  }

  public static void checkCPUTimeForCreate2() {
    if (VMConfig.passFork4811()) {
      throw new OutOfTimeException("CPU timeout for create2 executing");
    }
  }

  public static void checkCPUTimeForModExp() {
    if (VMConfig.passFork4811()) {
      throw new OutOfTimeException("CPU timeout for modExp executing");
    }
  }
}
