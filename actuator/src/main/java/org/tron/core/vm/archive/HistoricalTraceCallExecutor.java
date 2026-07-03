package org.tron.core.vm.archive;

import java.util.Arrays;
import org.tron.common.runtime.ProgramResult;
import org.tron.core.actuator.VMActuator;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.TransactionContext;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.store.StoreFactory;
import org.tron.core.store.VmDynamicProperties;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.trace.ProgramTrace;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;

/**
 * Replays a TVM constant call against archived state with the native opcode tracer enabled, so the
 * historical {@code debug_traceCall} path can reconstruct a Geth/Besu structLogs trace. It reuses
 * the exact injection setup of {@link HistoricalConstantCallExecutor} (an
 * {@link ArchiveRepositoryAdapter} root repository plus a historical {@link VmDynamicProperties}
 * view), and additionally:
 *
 * <ul>
 *   <li>enables the tracer on THIS thread only via {@link VMConfig#setLocalVmTrace(boolean)},
 *       always dropping it in {@code finally} so concurrent consensus is never traced; and</li>
 *   <li>retrieves the in-memory {@link ProgramTrace} from {@code VMActuator.getProgram()} after
 *       execute (no debug file is written: {@code VMActuator} suppresses the trace-file write on
 *       the injected/archive path).</li>
 * </ul>
 *
 * <p>A reverting / erroring call is not thrown here; the result carries {@code failed} and the
 * trace, mirroring Geth which traces failed calls. A hard VM error with a null program (no trace
 * captured) is still surfaced as a {@link HistoricalVmExecutionException}.
 */
public final class HistoricalTraceCallExecutor {

  public HistoricalTraceCallResult execute(ArchiveStateReader reader,
      VmDynamicProperties vmProperties, BlockCapsule block, TransactionCapsule trxCap)
      throws ContractValidateException, ContractExeException {
    return execute(reader, vmProperties, block, trxCap, true);
  }

  public HistoricalTraceCallResult execute(ArchiveStateReader reader,
      VmDynamicProperties vmProperties, BlockCapsule block, TransactionCapsule trxCap,
      boolean genesisComplete) throws ContractValidateException, ContractExeException {
    return execute(reader, vmProperties, block, trxCap, genesisComplete, true);
  }

  public HistoricalTraceCallResult execute(ArchiveStateReader reader,
      VmDynamicProperties vmProperties, BlockCapsule block, TransactionCapsule trxCap,
      boolean genesisComplete, boolean useConstantEnergyCap)
      throws ContractValidateException, ContractExeException {
    ArchiveRepositoryAdapter root =
        new ArchiveRepositoryAdapter(reader, vmProperties, genesisComplete);
    TransactionContext context =
        new TransactionContext(block, trxCap, StoreFactory.getInstance(), true, false);
    VMActuator vmActuator = new VMActuator(true);
    vmActuator.setInjectedRootRepository(root);
    vmActuator.setInjectedVmProperties(vmProperties);
    if (!useConstantEnergyCap) {
      vmActuator.setConstantCallMaxEnergyLimit(
          exactTransactionEnergyLimit(root, vmProperties, trxCap));
    }
    VMConfig.setLocalVmTrace(true);
    try {
      vmActuator.validate(context);
      vmActuator.execute(context);
    } finally {
      VMConfig.clearLocalVmTrace();
      // validate() installs a thread-local config snapshot; drop it like the constant-call path.
      VMConfig.clearLocalSnapshot();
    }

    ProgramResult result = context.getProgramResult();
    Program program = vmActuator.getProgram();
    if (program == null) {
      throw new HistoricalVmExecutionException(
          "historical trace call produced no program"
              + (result.getException() == null ? "" : ": " + result.getException().getMessage()),
          result.getException());
    }
    ProgramTrace trace = program.getTrace();
    if (!useConstantEnergyCap && result.getException() != null) {
      throw new HistoricalVmExecutionException(
          "historical debug_traceTransaction cannot replay VM exception with exact "
              + "non-constant energy accounting",
          result.getException());
    }
    boolean failed = result.getException() != null || result.isRevert()
        || (result.getRuntimeError() != null && !result.getRuntimeError().isEmpty());
    return HistoricalTraceCallResult.of(result.getHReturn(), result.getEnergyUsed(), failed,
        result.getRuntimeError(), trace);
  }

  private static long exactTransactionEnergyLimit(ArchiveRepositoryAdapter root,
      VmDynamicProperties vmProperties, TransactionCapsule trxCap) {
    TriggerSmartContract contract =
        ContractCapsule.getTriggerContractFromTransaction(trxCap.getInstance());
    if (contract == null) {
      throw new HistoricalVmExecutionException(
          "historical debug_traceTransaction requires TriggerSmartContract", null);
    }
    long feeLimit = trxCap.getInstance().getRawData().getFeeLimit();
    if (feeLimit <= 0) {
      return 0L;
    }
    long energyFee = vmProperties.getEnergyFee();
    if (energyFee <= 0) {
      throw new HistoricalVmExecutionException("historical energy fee must be positive", null);
    }
    byte[] callerAddress = contract.getOwnerAddress().toByteArray();
    AccountCapsule caller = root.getAccount(callerAddress);
    if (caller == null) {
      throw new HistoricalVmExecutionException(
          "historical debug_traceTransaction caller account is missing", null);
    }
    if (caller.getAllFrozenBalanceForEnergy() > 0) {
      throw new HistoricalVmExecutionException(
          "historical debug_traceTransaction requires archived frozen-energy accounting", null);
    }
    if (contract.getCallValue() < 0) {
      throw new HistoricalVmExecutionException(
          "historical debug_traceTransaction callValue must be non-negative", null);
    }
    long balanceAvailable = caller.getBalance() - contract.getCallValue();
    if (balanceAvailable < feeLimit) {
      throw new HistoricalVmExecutionException(
          "historical debug_traceTransaction requires exact balance-limited energy accounting",
          null);
    }
    ContractCapsule deployed = root.getContract(contract.getContractAddress().toByteArray());
    if (deployed == null) {
      throw new HistoricalVmExecutionException(
          "historical debug_traceTransaction contract is missing", null);
    }
    byte[] originAddress = deployed.getInstance().getOriginAddress().toByteArray();
    if (!Arrays.equals(originAddress, callerAddress)
        && deployed.getConsumeUserResourcePercent(false) < 100L) {
      throw new HistoricalVmExecutionException(
          "historical debug_traceTransaction requires exact creator-energy accounting", null);
    }
    return feeLimit / energyFee;
  }
}
