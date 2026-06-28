package org.tron.core.vm.archive;

import org.tron.common.runtime.ProgramResult;
import org.tron.core.actuator.VMActuator;
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
 * view, so account / code / storage reads come from the target block. The hard-fork / fee config
 * comes from whatever view the caller injects; the current RPC caller supplies a latest-store
 * baseline (see HistoricalEthCallSupport) rather than a fully historical config.
 *
 * <p>The historical config is installed by {@code VMActuator.validate} into a thread-local
 * {@code VMConfig} snapshot; this executor always drops it in a {@code finally} so it cannot leak
 * into a later latest call on the same (pooled) thread.
 */
public final class HistoricalConstantCallExecutor {

  public HistoricalConstantCallResult execute(ArchiveStateReader reader,
      VmDynamicProperties vmProperties, BlockCapsule block, TransactionCapsule trxCap)
      throws ContractValidateException, ContractExeException {
    ArchiveRepositoryAdapter root = new ArchiveRepositoryAdapter(reader, vmProperties);
    TransactionContext context =
        new TransactionContext(block, trxCap, StoreFactory.getInstance(), true, false);
    VMActuator vmActuator = new VMActuator(true);
    vmActuator.setInjectedRootRepository(root);
    vmActuator.setInjectedVmProperties(vmProperties);
    try {
      vmActuator.validate(context);
      vmActuator.execute(context);
    } finally {
      VMConfig.clearLocalSnapshot();
    }

    ProgramResult result = context.getProgramResult();
    if (result.getException() != null) {
      throw new HistoricalVmExecutionException(
          "historical constant call failed: " + result.getException().getMessage(),
          result.getException());
    }
    return HistoricalConstantCallResult.of(result.getHReturn(), result.isRevert(),
        result.getRuntimeError());
  }
}
