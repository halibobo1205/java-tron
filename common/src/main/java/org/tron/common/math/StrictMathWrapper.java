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


  // ********** @see Math **********

  /**
   * @see Math#abs(int)
   */
  public static int abs(int a) {
    return StrictMath.abs(a);
  }

  /**
   * @see Math#abs(long)
   */
  public static long abs(long a) {
    return StrictMath.abs(a);
  }

  /**
   * @see Math#abs(float)
   */
  public static float abs(float a) {
    return StrictMath.abs(a);
  }

  /**
   * @see Math#abs(double)
   */
  public static double abs(double a) {
    return StrictMath.abs(a);
  }

  /**
   * @see Math#max(int, int)
   */
  public static int max(int a, int b) {
    return StrictMath.max(a, b);
  }

  /**
   * @see Math#max(long, long)
   */
  public static long max(long a, long b) {
    return StrictMath.max(a, b);
  }

  /**
   * @see Math#max(double, double)
   */
  public static float max(float a, float b) {
    return StrictMath.max(a, b);
  }

  /**
   * @see Math#max(double, double)
   */
  public static double max(double a, double b) {
    return StrictMath.max(a, b);
  }

  /**
   * @see Math#min(int, int)
   */
  public static int min(int a, int b) {
    return StrictMath.min(a, b);
  }

  /**
   * @see Math#min(long, long)
   */
  public static long min(long a, long b) {
    return StrictMath.min(a, b);
  }

  /**
   * @see Math#min(float, float)
   */
  public static float min(float a, float b) {
    return StrictMath.min(a, b);
  }

  /**
   * @see Math#min(double, double)
   */

  public static double min(double a, double b) {
    return StrictMath.min(a, b);
  }

  /**
   * @see Math#signum(double)
   */
  public static double signum(double d) {
    return StrictMath.signum(d);
  }

  /**
   * @see Math#signum(float)
   */
  public static float signum(float f) {
    return StrictMath.signum(f);
  }

  /**
   * @see Math#round(float)
   */
  public static int round(float a) {
    return StrictMath.round(a);
  }

  /**
   * @see Math#round(double)
   */
  public static long round(double a) {
    return StrictMath.round(a);
  }

  /**
   * @see Math#addExact(int, int)
   */

  public static int addExact(int x, int y) {
    return StrictMath.addExact(x, y);
  }

  /**
   * @see Math#addExact(long, long)
   */

  public static long addExact(long x, long y) {
    return StrictMath.addExact(x, y);
  }

  /**
   * @see Math#subtractExact(int, int)
   */
  public static int subtractExact(int x, int y) {
    return StrictMath.subtractExact(x, y);
  }

  /**
   * @see Math#subtractExact(long, long)
   */

  public static long subtractExact(long x, long y) {
    return StrictMath.subtractExact(x, y);
  }

  /**
   * @see Math#floorDiv(int, int)
   */

  public static int floorDiv(int x, int y) {
    return StrictMath.floorDiv(x, y);
  }

  /**
   * @see Math#floorDiv(long, int)
   */
  public static long floorDiv(long x, int y) {
    return StrictMath.floorDiv(x, y);
  }

  /**
   * @see Math#floorDiv(long, long)
   */
  public static long floorDiv(long x, long y) {
    return StrictMath.floorDiv(x, y);
  }

  /**
   * @see Math#multiplyExact(int, int)
   */

  public static int multiplyExact(int x, int y) {
    return StrictMath.multiplyExact(x, y);
  }

  /**
   * @see Math#multiplyExact(long, int)
   */

  public static long multiplyExact(long x, int y) {
    return StrictMath.multiplyExact(x, y);
  }

  /**
   * @see Math#multiplyExact(long, long)
   */
  public static long multiplyExact(long x, long y) {
    return StrictMath.multiplyExact(x, y);
  }

}
