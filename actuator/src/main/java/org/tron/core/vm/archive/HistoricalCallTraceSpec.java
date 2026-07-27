package org.tron.core.vm.archive;

import java.math.BigInteger;
import java.util.Arrays;

/** Immutable top-level call-frame metadata supplied by the historical RPC layer. */
public final class HistoricalCallTraceSpec {

  private final int opCode;
  private final byte[] from;
  private final byte[] to;
  private final byte[] input;
  private final BigInteger value;

  public HistoricalCallTraceSpec(int opCode, byte[] from, byte[] to, byte[] input,
      BigInteger value) {
    this.opCode = opCode;
    this.from = copy(from);
    this.to = copy(to);
    this.input = copy(input);
    this.value = value == null ? BigInteger.ZERO : value;
  }

  public int getOpCode() {
    return opCode;
  }

  public byte[] getFrom() {
    return copy(from);
  }

  public byte[] getTo() {
    return copy(to);
  }

  public byte[] getInput() {
    return copy(input);
  }

  public BigInteger getValue() {
    return value;
  }

  private static byte[] copy(byte[] value) {
    return value == null ? new byte[0] : Arrays.copyOf(value, value.length);
  }
}
