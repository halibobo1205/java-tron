package org.tron.common.math;

import lombok.extern.slf4j.Slf4j;

@Slf4j(topic = "math")
public final class StrictMathWrapper {

  private StrictMathWrapper() {
    throw new UnsupportedOperationException("StrictMathWrapper should not be instantiated");
  }

  // ********** @see StrictMath **********

  public static double random() {
    return StrictMath.random();
  }

  public static double ceil(double a) {
    return StrictMath.ceil(a);
  }

  public static double exp(double a) {
    return StrictMath.exp(a);
  }

  public static double pow(double a, double b) {
    return StrictMath.pow(a, b);
  }
}
