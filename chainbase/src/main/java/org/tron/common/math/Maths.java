package org.tron.common.math;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.store.DynamicPropertiesStore;

@Component
@Slf4j(topic = "math")
public class Maths {

  private static DynamicPropertiesStore dynamicPropertiesStore;

  @Autowired
  public Maths(@Autowired DynamicPropertiesStore dynamicPropertiesStore) {
    Maths.dynamicPropertiesStore = dynamicPropertiesStore;
  }

  /**
   * Returns the value of the first argument raised to the power of the second argument.
   * Note dynamicPropertiesStore must be inited before calling this method.
   * @param a the base.
   * @param b the exponent.
   * @return the value {@code a}<sup>{@code b}</sup>.
   */
  public static double pow(double a, double b) {
    boolean useStrictMath = dynamicPropertiesStore.allowStrictMath();
    return useStrictMath ? StrictMathWrapper.pow(a, b) : MathWrapper.pow(a, b);
  }
}
