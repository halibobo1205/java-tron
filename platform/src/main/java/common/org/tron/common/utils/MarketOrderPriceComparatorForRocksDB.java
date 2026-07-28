package org.tron.common.utils;

import java.nio.ByteBuffer;
import org.rocksdb.AbstractComparator;
import org.rocksdb.ComparatorOptions;

public class MarketOrderPriceComparatorForRocksDB extends AbstractComparator {

  public MarketOrderPriceComparatorForRocksDB(final ComparatorOptions copt) {
    super(copt);
  }

  @Override
  public String name() {
    return "MarketOrderPriceComparator";
  }

  @Override
  public int compare(final ByteBuffer a, final ByteBuffer b) {
    return MarketComparator.comparePriceKey(convertDataToBytes(a), convertDataToBytes(b));
  }

  /** Copies the remaining bytes without requiring an array-backed buffer. */
  public byte[] convertDataToBytes(ByteBuffer buf) {
    ByteBuffer copy = buf.duplicate();
    byte[] bytes = new byte[copy.remaining()];
    copy.get(bytes);
    return bytes;
  }

}
