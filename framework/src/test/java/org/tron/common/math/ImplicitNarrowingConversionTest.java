package org.tron.common.math;

import org.junit.Assert;
import org.junit.Test;

/**
 * @see <a
 * href="https://codeql.github.com/codeql-query-help/java/java-implicit-cast-in-compound-assignment"
 * >Implicit narrowing conversion in compound assignment</a>
 *
 */
public class ImplicitNarrowingConversionTest {

  @Test
  public void test() {
    long l = 36714;
    double d = (double) 50 / 64400 * 2210208;
    long l1 = method1(l,d);
    long l2 = method2(l,d);
    long l3 = method3(l,d);
    // l1 = 38429
    // l2 = l3 = 38430
    // d = 1715.9999999999998
    Assert.assertEquals(l2, l3);
    Assert.assertNotEquals(l1, l2);
    Assert.assertNotEquals(l1, l3);
  }

  /**
   * code:
   * <pre>{@code
   *  0: lload_0 // load long l1
   *  1: dload_2 // load double d
   *  2: d2l // convert double d to long ((truncates decimal))
   *  3: ladd // long + long integer addition
   *  4: lreturn // return the result
   * }</pre>
   */
  private long method1(long l1, double d) {
    return l1 + (long) (d);
  }

  /**
   * code:
   * <pre>{@code
   *  0: lload_0 // load long l2
   *  1: l2d // promote long l2 to double
   *  2: dload_2 // load double d
   *  3: dadd // double + double floating-point addition
   *  4: d2l // convert the result to long
   *  5: lstore_0 // store the result back to long l2 (local variable)
   *  6: lload_0 // reload l2 (for return)
   *  7: lreturn // return the result
   * }</pre>
   */
  private long method2(long l2, double d) {
    l2 += d;
    return l2;
  }

  /**
   * code:
   * <pre>{@code
   *  0: lload_0 // load long l3
   *  1: l2d // promote long l3 to double
   *  2: dload_2 // load double d
   *  3: dadd // double + double floating-point addition
   *  4: d2l // convert the result to long
   *  5: lreturn // return the result
   * }</pre>
   */
  private long method3(long l3, double d) {
    return (long) (l3 + d);
  }
}
