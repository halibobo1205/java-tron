package org.tron.common.math;

import com.google.common.primitives.Bytes;
import java.nio.ByteBuffer;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.context.GlobalContext;
import org.tron.core.store.MathStore;
import org.tron.core.store.StrictMathStore;

/**
 * This class is deprecated and should not be used in new code,
 * for cross-platform consistency, please use {@link StrictMathWrapper} instead,
 * especially for floating-point calculations.
 */
@Deprecated
@Component
@Slf4j(topic = "math")
public class Maths {

  private static Optional<MathStore> mathStore = Optional.empty();
  private static Optional<StrictMathStore> strictMathStore = Optional.empty();

  @Autowired
  public Maths(@Autowired MathStore mathStore, @Autowired StrictMathStore strictMathStore) {
    Maths.mathStore = Optional.ofNullable(mathStore);
    Maths.strictMathStore = Optional.ofNullable(strictMathStore);
  }

  private enum Op {

    POW((byte) 0x01);

    private final byte code;

    Op(byte code) {
      this.code = code;
    }
  }

  /**
   * Returns the value of the first argument raised to the power of the second argument.
   * @param a the base.
   * @param b the exponent.
   * @return the value {@code a}<sup>{@code b}</sup>.
   */
  public static double pow(double a, double b, boolean useStrictMath) {
    double result = MathWrapper.pow(a, b);
    double strictResult = StrictMathWrapper.pow(a, b);
    if (useStrictMath) {
      return strictResult;
    }
    final boolean isNoStrict = Double.compare(result, strictResult) != 0;
    Optional<Long> header = GlobalContext.getHeader();
    header.ifPresent(h -> {
      byte[] key = Bytes.concat(longToBytes(h), new byte[]{Op.POW.code},
          doubleToBytes(a), doubleToBytes(b));
      if (isNoStrict) {
        logger.info("{}\t{}\t{}\t{}\t{}\t{}", h, Op.POW.code, doubleToHex(a), doubleToHex(b),
            doubleToHex(result), doubleToHex(strictResult));
      }
      mathStore.ifPresent(s -> s.put(key, doubleToBytes(result)));
      strictMathStore.ifPresent(s -> s.put(key, doubleToBytes(strictResult)));
    });
    return result;
  }

  static String doubleToHex(double input) {
    // Convert the starting value to the equivalent value in a long
    long doubleAsLong = Double.doubleToRawLongBits(input);
    // and then convert the long to a hex string
    return Long.toHexString(doubleAsLong);
  }

  private static byte[] doubleToBytes(double value) {
    ByteBuffer buffer = ByteBuffer.allocate(Double.BYTES);
    buffer.putDouble(value);
    return buffer.array();
  }

  private static byte[] longToBytes(long value) {
    ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
    buffer.putLong(value);
    return buffer.array();
  }
}
