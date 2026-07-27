package org.tron.core.vm.trace;

import java.math.BigInteger;

/** Request-owned nested TVM call-frame collector used by the archive debug API. */
public interface VmCallTraceCollector {

  TraceScope enter(int opCode, byte[] from, byte[] to, byte[] input, long energy,
      BigInteger value, boolean precompile);

  interface TraceScope extends AutoCloseable {

    /**
     * Completes one frame. {@code error} is null for success and a stable debug message for a
     * failed or reverted frame.
     */
    void complete(byte[] output, long energyUsed, boolean reverted, String error);

    @Override
    void close();
  }
}
