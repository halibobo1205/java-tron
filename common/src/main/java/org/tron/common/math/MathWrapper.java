package org.tron.common.math;

import com.google.common.base.Preconditions;
import lombok.extern.slf4j.Slf4j;

@Slf4j(topic = "math")
public final class MathWrapper {

  private MathWrapper() {
    throw new UnsupportedOperationException("MathWrapper should not be instantiated");
  }

  public static int abs(int a) {
    return Math.abs(a);
  }

  public static long abs(long a) {
    return Math.abs(a);
  }

  public static float abs(float a) {
    return Math.abs(a);
  }

  public static double abs(double a) {
    return Math.abs(a);
  }

  public static int max(int a, int b) {
    return Math.max(a, b);
  }

  public static long max(long a, long b) {
    return Math.max(a, b);
  }

  public static float max(float a, float b) {
    return Math.max(a, b);
  }

  public static double max(double a, double b) {
    return Math.max(a, b);
  }

  public static int min(int a, int b) {
    return Math.min(a, b);
  }

  public static long min(long a, long b) {
    return Math.min(a, b);
  }

  public static float min(float a, float b) {
    return Math.min(a, b);
  }

  public static double min(double a, double b) {
    return Math.min(a, b);
  }

  public static double signum(double d) {
    return Math.signum(d);
  }

  public static float signum(float f) {
    return Math.signum(f);
  }

  public static double ceil(double a) {
    return Math.ceil(a);
  }

  public static int round(float a) {
    return Math.round(a);
  }

  public static long round(double a) {
    return Math.round(a);
  }

  public static double random() {
    return Math.random();
  }

  public static int addExact(int x, int y) {
    return Math.addExact(x, y);
  }

  public static long addExact(long x, long y) {
    return Math.addExact(x, y);
  }

  public static int subtractExact(int x, int y) {
    return Math.subtractExact(x, y);
  }

  public static long subtractExact(long x, long y) {
    return Math.subtractExact(x, y);
  }


  public static int floorDiv(int x, int y) {
    return Math.floorDiv(x, y);
  }

  public static long floorDiv(long x, int y) {
    return floorDiv(x, (long) y);
  }

  public static long floorDiv(long x, long y) {
    return Math.floorDiv(x, y);
  }

  public static int multiplyExact(int x, int y) {
    return Math.multiplyExact(x, y);
  }

  public static long multiplyExact(long x, int y) {
    return multiplyExact(x, (long) y);
  }

  public static long multiplyExact(long x, long y) {
    return Math.multiplyExact(x, y);
  }


  public static double exp(double a) {
    return Math.exp(a);
  }

  public static double pow(double a, double b) {
    return Math.pow(a, b);
  }

  /**
   * NOTE: This is a partial implementation, only operates on 1<a<2 -1<b<1.
   *  only used in ExchangeProcessor.java
   * @see org.tron.core.capsule.ExchangeProcessor#exchangeToSupply(long, long) 
   */
  public static double powForExchange(double a, double b) {
    Preconditions.checkArgument(b > -1 && b < 1, "b must be -1<b<1");
    Preconditions.checkArgument(a > 1 && a < 2, "a must be 1<a<2");
    Double ret = PowDataForJdk8.get(a);
    if (ret != null) {
      logger.info("get from x87, a: {}, ret: {}", PowDataForJdk8.doubleToHex(a),
          PowDataForJdk8.doubleToHex(ret));
      return ret;
    }
    return pow(a, b);
  }
}
