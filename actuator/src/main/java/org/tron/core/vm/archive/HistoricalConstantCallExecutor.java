package org.tron.core.vm.archive;

import java.util.Objects;
import java.util.function.Supplier;
import org.tron.common.runtime.ProgramResult;
import org.tron.core.actuator.VMActuator;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.TransactionContext;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.store.StoreFactory;
import org.tron.core.store.VmDynamicProperties;
import org.tron.core.vm.config.VMConfig;

/**
 * Runs a TVM constant call against the archive state at a historical point. It mirrors the latest
 * constant-call path ({@code Wallet.callConstantContract}) but injects an
 * {@link ArchiveRepositoryAdapter} as the VM's root repository and a {@link VmDynamicProperties}
 * view, so account / code / storage reads come from the target block. The config comes from the
 * view the caller injects; the current RPC caller supplies the historical energy price plus a
 * latest-store baseline for the hard-fork flags (see HistoricalEthCallSupport).
 *
 * <p>The historical config is installed by {@code VMActuator.validate} into a thread-local
 * {@code VMConfig} snapshot; this executor always drops it in a {@code finally} so it cannot leak
 * into a later latest call on the same (pooled) thread.
 */
public final class HistoricalConstantCallExecutor {

  private final Supplier<VMActuator> vmActuatorFactory;

  public HistoricalConstantCallExecutor() {
    this(() -> new VMActuator(true));
  }

  HistoricalConstantCallExecutor(Supplier<VMActuator> vmActuatorFactory) {
    this.vmActuatorFactory = Objects.requireNonNull(vmActuatorFactory, "vmActuatorFactory");
  }

  public HistoricalConstantCallResult execute(ArchiveStateReader reader,
      VmDynamicProperties vmProperties, BlockCapsule block, TransactionCapsule trxCap)
      throws ContractValidateException, ContractExeException {
    return execute(reader, vmProperties, block, trxCap, true);
  }

  public HistoricalConstantCallResult execute(ArchiveStateReader reader,
      VmDynamicProperties vmProperties, BlockCapsule block, TransactionCapsule trxCap,
      boolean genesisComplete) throws ContractValidateException, ContractExeException {
    QueryContext readerQueryContext = reader.getQueryContext();
    try (QueryContextHolder.Scope ignored =
            QueryContextHolder.attachIfPresent(readerQueryContext);
        VMConfig.LocalSnapshotScope ignoredVmConfig = VMConfig.preserveLocalSnapshot()) {
      QueryContext queryContext = QueryContextHolder.current();
      ArchiveRepositoryAdapter root =
          new ArchiveRepositoryAdapter(reader, vmProperties, genesisComplete);
      TransactionContext context =
          new TransactionContext(block, trxCap, StoreFactory.getInstance(), true, false);
      VMActuator vmActuator = Objects.requireNonNull(
          vmActuatorFactory.get(), "vmActuatorFactory result");
      vmActuator.setInjectedRootRepository(root);
      vmActuator.setInjectedVmProperties(vmProperties);
      boolean completedSuccessfully = false;
      Error hardFailure = null;
      try {
        vmActuator.validate(context);
        vmActuator.execute(context);

        ProgramResult result = context.getProgramResult();
        if (result.getException() != null) {
          throw new HistoricalVmExecutionException(
              "historical constant call failed: " + result.getException().getMessage(),
              result.getException());
        }
        HistoricalConstantCallResult callResult = HistoricalConstantCallResult.of(
            result.getHReturn(), result.isRevert(),
            result.getRuntimeError());
        completedSuccessfully = true;
        return callResult;
      } catch (Error failure) {
        hardFailure = failure;
        throw failure;
      } finally {
        if (queryContext != null) {
          RuntimeException terminalFailure =
              queryContext.getRecordedExecutionTerminalFailure();
          if (terminalFailure != null && hardFailure != null) {
            hardFailure.addSuppressed(terminalFailure);
          } else if (terminalFailure != null) {
            // Restore the first budget or nested archive failure swallowed by VM execution.
            throw terminalFailure;
          }
          if (hardFailure == null && completedSuccessfully) {
            // Sample a newly-expired deadline only when no independent VM/validation failure won.
            queryContext.throwIfTerminated();
          }
        }
      }
    }
  }
}
