package org.tron.core.capsule;

import java.nio.ByteBuffer;
import java.util.Optional;

import com.google.common.primitives.Bytes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.math.Maths;
import org.tron.core.store.BancorStore;

@Component
@Slf4j(topic = "capsule")
public class ExchangeProcessor {

  private static final long SUPPLY = 1_000_000_000_000_000_000L;

  private static Optional<BancorStore> bancorStore = Optional.empty();

  @Autowired
  public ExchangeProcessor(@Autowired BancorStore bancorStore) {
    ExchangeProcessor.bancorStore = Optional.ofNullable(bancorStore);
  }


  private static long exchangeToSupply(long balance, long quant, boolean useStrictMath) {
    logger.debug("balance: " + balance);
    long newBalance = balance + quant;
    logger.debug("balance + quant: " + newBalance);

    double issuedSupply = -SUPPLY * (1.0
        - Maths.pow(1.0 + (double) quant / newBalance, 0.0005, useStrictMath));
    logger.debug("issuedSupply: " + issuedSupply);
    return (long) issuedSupply;
  }

  private static long exchangeFromSupply(long balance, long supplyQuant, boolean useStrictMath) {

    double exchangeBalance = balance
        * (Maths.pow(1.0 + (double) supplyQuant / SUPPLY, 2000.0, useStrictMath) - 1.0);
    logger.debug("exchangeBalance: " + exchangeBalance);

    return (long) exchangeBalance;
  }

  public static long exchange(long sellTokenBalance, long buyTokenBalance, long sellTokenQuant,
                              boolean useStrictMath) {
    long relay = exchangeToSupply(sellTokenBalance, sellTokenQuant, useStrictMath);
    long ret = exchangeFromSupply(buyTokenBalance, relay, useStrictMath);
    if (!useStrictMath) {
      byte[] key = Bytes.concat(longToBytes(sellTokenBalance), longToBytes(buyTokenBalance),
          longToBytes(sellTokenQuant));
      byte[] result = Bytes.concat(longToBytes(relay), longToBytes(ret));
      bancorStore.ifPresent(s -> s.put(key, result));
      logger.debug("{}, {}, {}, {}, {}", sellTokenBalance, buyTokenBalance, sellTokenQuant, relay,
          ret);
    }
    return ret;
  }

  private static byte[] longToBytes(long value) {
    ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
    buffer.putLong(value);
    return buffer.array();
  }

}
