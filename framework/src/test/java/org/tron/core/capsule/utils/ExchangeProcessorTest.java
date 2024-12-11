package org.tron.core.capsule.utils;

import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.tron.common.BaseTest;
import org.tron.core.Constant;
import org.tron.core.capsule.ExchangeProcessor;
import org.tron.core.config.args.Args;

@Slf4j
public class ExchangeProcessorTest extends BaseTest {

  private static ExchangeProcessor processor;

  static {
    Args.setParam(new String[]{"--output-directory", dbPath()}, Constant.TEST_CONF);
  }

  /**
   * Init data.
   */
  @BeforeClass
  public static void init() {
    long supply = 1_000_000_000_000_000_000L;
    processor = new ExchangeProcessor(supply, false);
  }

  @Test
  public void testExchange() {
    long sellBalance = 100_000_000_000000L;
    long buyBalance = 128L * 1024 * 1024 * 1024;
    long sellQuant = 2_000_000_000_000L; // 2 million trx

    long result = processor.exchange(sellBalance, buyBalance, sellQuant);

    Assert.assertEquals(2694881440L, result);
  }

  @Test
  public void testExchange2() {
    long sellBalance = 100_000_000_000000L;
    long buyBalance = 128L * 1024 * 1024 * 1024;
    long sellQuant = 1_000_000_000_000L; // 2 million trx

    long result = processor.exchange(sellBalance, buyBalance, sellQuant);
    Assert.assertEquals(1360781717L, result);

    sellBalance += sellQuant;
    buyBalance -= result;

    long result2 = processor.exchange(sellBalance, buyBalance, sellQuant);
    Assert.assertEquals(2694881440L - 1360781717L, result2);

  }


  @Test
  public void testSellAndBuy() {
    long sellBalance = 100_000_000_000000L;
    long buyBalance = 128L * 1024 * 1024 * 1024;
    long sellQuant = 2_000_000_000_000L; // 2 million trx

    long result = processor.exchange(sellBalance, buyBalance, sellQuant);
    Assert.assertEquals(2694881440L, result);

    sellBalance += sellQuant;
    buyBalance -= result;

    long result2 = processor.exchange(buyBalance, sellBalance, result);
    Assert.assertEquals(1999999999542L, result2);

  }

  @Test
  public void testSellAndBuy2() {
    long sellBalance = 100_000_000_000000L;
    long buyBalance = 128L * 1024 * 1024 * 1024;
    long sellQuant = 2_000_000_000_000L; // 2 million trx

    long result = processor.exchange(sellBalance, buyBalance, sellQuant);
    Assert.assertEquals(2694881440L, result);

    sellBalance += sellQuant;
    buyBalance -= result;

    long quant1 = 2694881440L - 1360781717L;
    long quant2 = 1360781717L;

    long result1 = processor.exchange(buyBalance, sellBalance, quant1);
    Assert.assertEquals(999999999927L, result1);

    buyBalance += quant1;
    sellBalance -= result1;

    long result2 = processor.exchange(buyBalance, sellBalance, quant2);
    Assert.assertEquals(999999999587L, result2);

  }

  @Test
  public void testInject() {
    long sellBalance = 1_000_000_000000L;
    long buyBalance = 10_000_000L;
    long sellQuant = 10_000_000L; // 10 trx

    long result = processor.exchange(sellBalance, buyBalance, sellQuant);
    Assert.assertEquals(99L, result);

    // inject
    sellBalance += 100_000_000000L;
    buyBalance += 1_000_000L;

    long result2 = processor.exchange(sellBalance, buyBalance, sellQuant);
    Assert.assertEquals(99L, result2);

  }

  @Test
  public void testWithdraw() {
    long sellBalance = 1_000_000_000000L;
    long buyBalance = 10_000_000L;
    long sellQuant = 10_000_000L; // 10 trx

    long result = processor.exchange(sellBalance, buyBalance, sellQuant);
    Assert.assertEquals(99L, result);

    // inject
    sellBalance -= 800_000_000000L;
    buyBalance -= 8_000_000L;

    long result2 = processor.exchange(sellBalance, buyBalance, sellQuant);
    Assert.assertEquals(99L, result2);

  }

  @Test
  public void testStrictMath() {
    long supply = 1_000_000_000_000_000_000L;
    long[][] testData = {
        {4732214L, 2202692725330L, 29218L},
        {5618633L, 556559904655L, 1L},
        {9299554L, 1120271441185L, 7000L},
        {62433133L, 12013267997895L, 100000L},
        {64212664L, 725836766395L, 50000L},
        {64126212L, 2895100109660L, 5000L},
        {56459055L, 3288380567368L, 165000L},
        {21084707L, 1589204008960L, 50000L},
        {24120521L, 1243764649177L, 20000L},
        {836877L, 212532333234L, 5293L},
        {55879741L, 13424854054078L, 250000L},
        {66388882L, 11300012790454L, 300000L},
        {94470955L, 7941038150919L, 2000L},
        // add pow data
    };

    for (long[] data : testData) {
      ExchangeProcessor processor = new ExchangeProcessor(supply, false);
      long anotherTokenQuant = processor.exchange(data[0], data[1], data[2]);
      processor = new ExchangeProcessor(supply, true);
      long result = processor.exchange(data[0], data[1], data[2]);
      Assert.assertEquals(anotherTokenQuant, result);
    }
  }
}
