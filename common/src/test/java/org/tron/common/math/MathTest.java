package org.tron.common.math;

import org.junit.Assert;
import org.junit.Test;

public class MathTest {

  @Test
  public void testPow() {
    long x = 29218;
    long q = 4761432;
    double ret = MathWrapper.pow(1.0 + (double) x / q, 0.0005);
    double ret2 = MathWrapper.powjdk8(1.0 + (double) x / q, 0.0005);
    double ret3 = StrictMathWrapper.pow(1.0 + (double) x / q, 0.0005);
    Assert.assertNotEquals(0, Double.compare(ret, ret2));
    Assert.assertNotEquals(0, Double.compare(ret2, ret3));
    Assert.assertEquals(0, Double.compare(ret, ret3));
  }
}
