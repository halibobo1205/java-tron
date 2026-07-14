package org.tron.core.vm.archive;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;
import org.tron.common.math.StrictMathWrapper;
import org.tron.common.runtime.ProgramResult;
import org.tron.core.actuator.VMActuator;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
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
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.contract.SmartContractOuterClass.CreateSmartContract;
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

  private final Supplier<VMActuator> vmActuatorFactory;

  public HistoricalTraceCallExecutor() {
    this(() -> new VMActuator(true));
  }

  HistoricalTraceCallExecutor(Supplier<VMActuator> vmActuatorFactory) {
    this.vmActuatorFactory = Objects.requireNonNull(vmActuatorFactory, "vmActuatorFactory");
  }

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
    QueryContext readerQueryContext = reader.getQueryContext();
    try (QueryContextHolder.Scope ignored =
        QueryContextHolder.attachIfPresent(readerQueryContext)) {
      QueryContext queryContext = QueryContextHolder.current();
      ArchiveRepositoryAdapter root =
          new ArchiveRepositoryAdapter(reader, vmProperties, genesisComplete);
      TransactionContext context =
          new TransactionContext(block, trxCap, StoreFactory.getInstance(), true, false);
      VMActuator vmActuator = Objects.requireNonNull(
          vmActuatorFactory.get(), "vmActuatorFactory result");
      vmActuator.setInjectedRootRepository(root);
      vmActuator.setInjectedVmProperties(vmProperties);
      if (!useConstantEnergyCap) {
        vmActuator.setConstantCallMaxEnergyLimit(
            exactTransactionEnergyLimit(root, vmProperties, trxCap));
      }
      VMConfig.setLocalVmTrace(true);
      boolean completedWithoutVmFailure = false;
      try {
        vmActuator.validate(context);
        vmActuator.execute(context);

        ProgramResult result = context.getProgramResult();
        Program program = vmActuator.getProgram();
        if (program == null) {
          throw new HistoricalVmExecutionException(
              "historical trace call produced no program"
                  + (result.getException() == null
                      ? "" : ": " + result.getException().getMessage()),
              result.getException());
        }
        ProgramTrace trace = program.getTrace();
        boolean failed = result.getException() != null || result.isRevert()
            || (result.getRuntimeError() != null && !result.getRuntimeError().isEmpty());
        HistoricalTraceCallResult traceResult = HistoricalTraceCallResult.of(
            result.getHReturn(), result.getEnergyUsed(), failed,
            result.getRuntimeError(), trace);
        completedWithoutVmFailure = !failed;
        return traceResult;
      } finally {
        VMConfig.clearLocalVmTrace();
        // validate() installs a thread-local config snapshot; drop it like the constant-call path.
        VMConfig.clearLocalSnapshot();
        if (queryContext != null) {
          if (queryContext.getRecordedTerminalException() != null) {
            // VMActuator intentionally catches Throwable; restore an already-recorded budget error.
            throw queryContext.getRecordedTerminalException();
          }
          if (completedWithoutVmFailure) {
            // Sample a newly-expired deadline only when no independent VM/validation failure won.
            queryContext.throwIfTerminated();
          }
        }
      }
    }
  }

  private static long exactTransactionEnergyLimit(ArchiveRepositoryAdapter root,
      VmDynamicProperties vmProperties, TransactionCapsule trxCap) {
    ContractType contractType = trxCap.getInstance().getRawData().getContract(0).getType();
    if (contractType == ContractType.CreateSmartContract) {
      return exactCreateEnergyLimit(root, vmProperties, trxCap);
    }
    if (contractType != ContractType.TriggerSmartContract) {
      throw new HistoricalVmExecutionException(
          "historical debug_traceTransaction requires a VM contract", null);
    }
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
    long energyLimit = balanceLimitedEnergyLimit(caller, feeLimit, contract.getCallValue(),
        energyFee);
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
    return energyLimit;
  }

  private static long exactCreateEnergyLimit(ArchiveRepositoryAdapter root,
      VmDynamicProperties vmProperties, TransactionCapsule trxCap) {
    CreateSmartContract contract =
        ContractCapsule.getSmartContractFromTransaction(trxCap.getInstance());
    if (contract == null) {
      throw new HistoricalVmExecutionException(
          "historical debug_traceTransaction requires CreateSmartContract", null);
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
    long callValue = contract.getNewContract().getCallValue();
    if (callValue < 0) {
      throw new HistoricalVmExecutionException(
          "historical debug_traceTransaction callValue must be non-negative", null);
    }
    return balanceLimitedEnergyLimit(caller, feeLimit, callValue, energyFee);
  }

  private static long balanceLimitedEnergyLimit(AccountCapsule caller, long feeLimit,
      long callValue, long energyFee) {
    long balanceAvailable = caller.getBalance() - callValue;
    if (balanceAvailable <= 0) {
      return 0L;
    }
    return StrictMathWrapper.min(feeLimit, balanceAvailable) / energyFee;
  }
}
