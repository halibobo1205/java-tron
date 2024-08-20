package org.tron.common.math;

import org.junit.Assert;
import org.junit.Test;

public class MathsTest {

  @Test
  public void testPow() {
    boolean isJava8 = "1.8".equals(System.getProperty("java.specification.version"));
    boolean isARM = "aarch64".equals(System.getProperty("os.arch"));
    boolean isX87 = isJava8 && !isARM;
    Maths.powData.forEach((key, value) -> {
      double result = Maths.pow(key.a, key.b);
      double result2 = StrictMath.pow(key.a, key.b);
      double result3 = Math.pow(key.a, key.b);
      Assert.assertEquals(Double.compare(result, value), 0);
      Assert.assertNotEquals(Double.compare(result2, value), 0);
      if (isX87) {
        Assert.assertNotEquals(Double.compare(result3, result2), 0);
        Assert.assertEquals(Double.compare(result3, value), 0);
      } else {
        Assert.assertEquals(Double.compare(result3, result2), 0);
        Assert.assertNotEquals(Double.compare(result3, value), 0);
      }
    });
  }
}
