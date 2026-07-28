package org.tron.core.vm.archive;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.tron.common.parameter.CommonParameter;
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
import org.tron.core.vm.program.Program;
import org.tron.core.vm.trace.VmCallTraceCollector;
import org.tron.core.vm.trace.VmStructuredTraceListener;

/** Replays one historical TVM execution with request-owned, in-memory trace collectors. */
public final class HistoricalDebugTraceExecutor {

  private final Function<Boolean, VMActuator> vmActuatorFactory;

  public HistoricalDebugTraceExecutor() {
    this(VMActuator::new);
  }

  HistoricalDebugTraceExecutor(Supplier<VMActuator> vmActuatorFactory) {
    Objects.requireNonNull(vmActuatorFactory, "vmActuatorFactory");
    this.vmActuatorFactory = ignored -> vmActuatorFactory.get();
  }

  private HistoricalDebugTraceExecutor(Function<Boolean, VMActuator> vmActuatorFactory) {
    this.vmActuatorFactory = Objects.requireNonNull(vmActuatorFactory, "vmActuatorFactory");
  }

  public HistoricalDebugTraceResult execute(ArchiveStateReader reader,
      VmDynamicProperties vmProperties, BlockCapsule block, TransactionCapsule trxCap,
      boolean genesisComplete, boolean constantCall,
      VmStructuredTraceListener structuredTraceListener,
      VmCallTraceCollector callTraceCollector, HistoricalCallTraceSpec callTraceSpec)
      throws ContractValidateException, ContractExeException {
    return execute(reader, vmProperties, block, trxCap, genesisComplete, constantCall,
        structuredTraceListener, callTraceCollector, callTraceSpec, null);
  }

  public HistoricalDebugTraceResult execute(ArchiveStateReader reader,
      VmDynamicProperties vmProperties, BlockCapsule block, TransactionCapsule trxCap,
      boolean genesisComplete, boolean constantCall,
      VmStructuredTraceListener structuredTraceListener,
      VmCallTraceCollector callTraceCollector, HistoricalCallTraceSpec callTraceSpec,
      Long constantCallEnergyLimit)
      throws ContractValidateException, ContractExeException {
    if (callTraceCollector != null && callTraceSpec == null) {
      throw new NullPointerException("callTraceSpec");
    }
    if (constantCallEnergyLimit != null
        && (!constantCall || constantCallEnergyLimit < 0L)) {
      throw new IllegalArgumentException(
          "constant-call energy limit requires a non-negative constant call");
    }

    QueryContext readerQueryContext = reader.getQueryContext();
    try (QueryContextHolder.Scope ignored =
            QueryContextHolder.attachIfPresent(readerQueryContext);
        VMConfig.LocalSnapshotScope ignoredVmConfig = VMConfig.preserveLocalSnapshot()) {
      QueryContext queryContext = QueryContextHolder.current();
      StoreFactory storeFactory = StoreFactory.getInstance();
      ArchiveRepositoryAdapter root =
          new ArchiveRepositoryAdapter(reader, vmProperties, genesisComplete,
              HistoricalArchiveChainIdentity.blackHoleAddress(storeFactory));
      TransactionContext context =
          new TransactionContext(block, trxCap, storeFactory, constantCall, false);
      VMActuator vmActuator = Objects.requireNonNull(
          vmActuatorFactory.apply(constantCall), "vmActuatorFactory result");
      vmActuator.setInjectedRootRepository(root);
      vmActuator.setUseQueryDeadlineForVm(true);
      if (constantCallEnergyLimit != null) {
        long configuredLimit = CommonParameter.getInstance().maxEnergyLimitForConstant;
        vmActuator.setConstantCallMaxEnergyLimit(
            constantCallEnergyLimit < configuredLimit
                ? constantCallEnergyLimit : configuredLimit);
      }
      VmCallTraceCollector.TraceScope rootTraceScope = null;
      Throwable executionFailure = null;
      boolean completedSuccessfully = false;
      try {
        vmActuator.validate(context);
        Program program = vmActuator.getProgram();
        if (program == null) {
          throw new HistoricalVmExecutionException(
              "historical debug trace produced no TVM program", null);
        }
        if (structuredTraceListener != null) {
          program.setStructuredTraceListener(structuredTraceListener);
        }
        if (callTraceCollector != null) {
          program.setCallTraceCollector(callTraceCollector);
          rootTraceScope = callTraceCollector.enter(
              callTraceSpec.getOpCode(), callTraceSpec.getFrom(), callTraceSpec.getTo(),
              callTraceSpec.getInput(), program.getInitialEnergyLimit(),
              callTraceSpec.getValue(), false);
        }

        vmActuator.execute(context);
        ProgramResult result = context.getProgramResult();
        HistoricalDebugTraceResult traceResult = toTraceResult(result);
        completedSuccessfully = true;
        return traceResult;
      } catch (ContractValidateException | ContractExeException failure) {
        executionFailure = failure;
        throw failure;
      } catch (RuntimeException | Error failure) {
        executionFailure = failure;
        throw failure;
      } finally {
        Throwable finalizationFailure = null;
        if (rootTraceScope != null) {
          try {
            completeRootTrace(rootTraceScope, context.getProgramResult(), executionFailure);
          } catch (RuntimeException | Error failure) {
            finalizationFailure = collectFailure(finalizationFailure, failure);
          }
        }
        if (queryContext != null) {
          RuntimeException terminalFailure =
              queryContext.getRecordedExecutionTerminalFailure();
          if (terminalFailure != null && executionFailure instanceof Error) {
            addSuppressedSafely(executionFailure, terminalFailure);
          } else if (terminalFailure != null) {
            if (executionFailure != null) {
              addSuppressedSafely(terminalFailure, executionFailure);
              executionFailure = null;
            }
            if (finalizationFailure != null) {
              addSuppressedSafely(terminalFailure, finalizationFailure);
            }
            finalizationFailure = terminalFailure;
          }
          if (terminalFailure == null && executionFailure == null && completedSuccessfully) {
            try {
              queryContext.throwIfTerminated();
            } catch (RuntimeException | Error failure) {
              finalizationFailure = collectFailure(finalizationFailure, failure);
            }
          }
        }
        if (executionFailure != null && finalizationFailure != null) {
          addSuppressedSafely(executionFailure, finalizationFailure);
        } else {
          rethrowFinalizationFailure(finalizationFailure);
        }
      }
    }
  }

  private static HistoricalDebugTraceResult toTraceResult(ProgramResult result) {
    boolean failed = traceFailed(result);
    return HistoricalDebugTraceResult.of(
        result.getHReturn(), result.getEnergyUsed(), failed, result.isRevert());
  }

  private static void completeRootTrace(VmCallTraceCollector.TraceScope scope,
      ProgramResult result, Throwable executionFailure) {
    Throwable finalizationFailure = null;
    try {
      if (result == null) {
        String error = executionFailure == null
            ? "execution failed" : failureMessage(executionFailure);
        scope.complete(new byte[0], 0L, false, error);
      } else {
        String error = executionFailure == null
            ? traceError(result) : failureMessage(executionFailure);
        byte[] output = executionFailure == null
            && (!traceFailed(result) || result.isRevert())
            ? result.getHReturn() : new byte[0];
        scope.complete(output, result.getEnergyUsed(), result.isRevert(), error);
      }
    } catch (RuntimeException | Error failure) {
      finalizationFailure = failure;
    }
    try {
      scope.close();
    } catch (RuntimeException | Error failure) {
      finalizationFailure = collectFailure(finalizationFailure, failure);
    }
    rethrowFinalizationFailure(finalizationFailure);
  }

  private static String traceError(ProgramResult result) {
    if (result.getException() != null) {
      return failureMessage(result.getException());
    }
    if (result.isRevert()) {
      return "execution reverted";
    }
    return result.getRuntimeError() == null || result.getRuntimeError().isEmpty()
        ? null : result.getRuntimeError();
  }

  private static boolean traceFailed(ProgramResult result) {
    return result.getException() != null || result.isRevert()
        || result.getRuntimeError() != null && !result.getRuntimeError().isEmpty();
  }

  private static String failureMessage(Throwable failure) {
    return failure.getMessage() == null || failure.getMessage().isEmpty()
        ? failure.getClass().getSimpleName() : failure.getMessage();
  }

  private static Throwable collectFailure(Throwable primary, Throwable candidate) {
    if (primary == null) {
      return candidate;
    }
    if (primary != candidate) {
      addSuppressedSafely(primary, candidate);
    }
    return primary;
  }

  private static void addSuppressedSafely(Throwable primary, Throwable candidate) {
    if (primary == candidate) {
      return;
    }
    try {
      primary.addSuppressed(candidate);
    } catch (Throwable ignored) {
      // Preserve the first execution failure when suppression itself cannot complete.
    }
  }

  private static void rethrowFinalizationFailure(Throwable failure) {
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    if (failure != null) {
      throw new IllegalStateException("historical debug trace finalization failed", failure);
    }
  }
}
