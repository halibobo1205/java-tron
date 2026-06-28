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
import org.tron.core.vm.program.Program;
import org.tron.core.vm.trace.ProgramTrace;

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
    ArchiveRepositoryAdapter root = new ArchiveRepositoryAdapter(reader, vmProperties);
    TransactionContext context =
        new TransactionContext(block, trxCap, StoreFactory.getInstance(), true, false);
    VMActuator vmActuator = new VMActuator(true);
    vmActuator.setInjectedRootRepository(root);
    vmActuator.setInjectedVmProperties(vmProperties);
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
    boolean failed = result.getException() != null || result.isRevert()
        || (result.getRuntimeError() != null && !result.getRuntimeError().isEmpty());
    return HistoricalTraceCallResult.of(result.getHReturn(), result.getEnergyUsed(), failed,
        result.getRuntimeError(), trace);
  }
}
