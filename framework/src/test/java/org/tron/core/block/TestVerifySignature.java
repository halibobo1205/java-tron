package org.tron.core.block;

import java.math.BigInteger;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

@Slf4j
public class TestVerifySignature {

  @Test
  public void testVerifySignature() {
    BigInteger r = new BigInteger(
        "50487612229499292742090038413173090030170352987505971188636357058678836824495");
    BigInteger N = new BigInteger(
        "115792089237316195423570985008687907852837564279074904382605163141518161494337");
    for (int i = 0; i < 10; i++) {
      long sign = System.currentTimeMillis();
      r.modInverse(N);
      long end = System.currentTimeMillis();
      System.out.println((end - sign) + "ms");
    }
  }
}
