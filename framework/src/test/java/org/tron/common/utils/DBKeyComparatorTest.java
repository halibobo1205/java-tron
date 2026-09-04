package org.tron.common.utils;

import java.nio.ByteBuffer;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;
import org.rocksdb.ComparatorOptions;
import org.rocksdb.RocksDB;
import org.tron.core.capsule.utils.MarketUtils;


@Slf4j
public class DBKeyComparatorTest {


  @Test
  public void dbComparing() {
    MarketOrderPriceComparatorForLevelDB comparator = new MarketOrderPriceComparatorForLevelDB();

    byte[] pairPriceKey1 = MarketUtils.createPairPriceKey(
        ByteArray.fromString("100"),
        ByteArray.fromString("200"),
        1000L,
        2000L
    );
    byte[] pairPriceKey2 = MarketUtils.createPairPriceKey(
        ByteArray.fromString("100"),
        ByteArray.fromString("200"),
        1000L,
        2001L
    );
    Assert.assertEquals(-1, comparator.compare(pairPriceKey1, pairPriceKey2));
  }

  @Test
  public void rocksDbComparatorUsesDirectBuffersWithoutChangingTheirPositions() {
    RocksDB.loadLibrary();
    byte[] first = MarketUtils.createPairPriceKey(
        ByteArray.fromString("100"), ByteArray.fromString("200"), 1000L, 2000L);
    byte[] second = MarketUtils.createPairPriceKey(
        ByteArray.fromString("100"), ByteArray.fromString("200"), 1000L, 2001L);
    ByteBuffer firstBuffer = ByteBuffer.allocateDirect(first.length + 17);
    ByteBuffer secondBuffer = ByteBuffer.allocateDirect(second.length + 19);
    firstBuffer.put(first).flip();
    secondBuffer.put(second).flip();
    Assert.assertTrue(firstBuffer.capacity() > firstBuffer.limit());
    Assert.assertTrue(secondBuffer.capacity() > secondBuffer.limit());

    try (ComparatorOptions options = new ComparatorOptions();
         MarketOrderPriceComparatorForRocksDB comparator =
             new MarketOrderPriceComparatorForRocksDB(options)) {
      Assert.assertEquals(-1, comparator.compare(firstBuffer, secondBuffer));
      Assert.assertEquals(0, firstBuffer.position());
      Assert.assertEquals(0, secondBuffer.position());
    }
  }


  @Test
  public void pairKeyIsEqual() {

    byte[] pairPriceKey1 = MarketUtils.createPairPriceKey(
        ByteArray.fromString("100"),
        ByteArray.fromString("200"),
        1000L,
        2000L
    );
    byte[] pairPriceKey2 = MarketUtils.createPairPriceKey(
        ByteArray.fromString("10"),
        ByteArray.fromString("200"),
        1000L,
        2001L
    );

    Assert.assertFalse(MarketUtils.pairKeyIsEqual(pairPriceKey1, pairPriceKey2));
  }



}
