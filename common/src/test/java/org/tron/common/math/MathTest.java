package org.tron.common.math;

import org.junit.Assert;
import org.junit.Test;

public class MathTest {

  @Test
  public void testPow() {
    long x = 29218;
    long q = 4761432;
    double a = 1.0 + (double) x / q;
    double ret = MathWrapper.pow(a, 0.0005);
    double ret2 = MathWrapper.powForExchange(a, 0.0005);
    double ret3 = StrictMathWrapper.pow(a, 0.0005);
    Assert.assertNotEquals(0, Double.compare(ret, ret2));
    Assert.assertNotEquals(0, Double.compare(ret2, ret3));
    Assert.assertEquals(0, Double.compare(PowDataForJdk8.get(a), ret2));
    Assert.assertEquals(0, Double.compare(ret, ret3));
  }
}
