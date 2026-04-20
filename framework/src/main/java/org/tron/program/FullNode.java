package org.tron.program;

import java.math.BigInteger;
import java.security.SignatureException;
import lombok.extern.slf4j.Slf4j;

@Slf4j(topic = "app")
public class FullNode {

  /**
   * Start the FullNode.
   */
  public static void main(String[] args) throws SignatureException, InterruptedException {
    BigInteger r = new BigInteger(
        "50487612229499292742090038413173090030170352987505971188636357058678836824495");
    BigInteger N = new BigInteger(
        "115792089237316195423570985008687907852837564279074904382605163141518161494337");
    while (true) {
      r.modInverse(N);
      Thread.sleep(100);
    }
  }
}
