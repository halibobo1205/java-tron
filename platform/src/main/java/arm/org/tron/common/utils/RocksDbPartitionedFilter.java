package org.tron.common.utils;

import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.IndexType;

/**
 * Arch-specific RocksDB table tuning — arm64 / RocksDB 9.x implementation.
 *
 * <p>Large stores (e.g. account-asset) reach the 256MB target SST size, so each SST carries a
 * multi-MB full (unpartitioned) bloom filter block. A point lookup that misses the block cache
 * must read+decompress that whole block, and with only L0 pinned those blocks get evicted and
 * re-read constantly. Partitioned index/filter splits the block into small partitions with a
 * tiny pinned top level, so each lookup reads only the relevant small partition.
 *
 * <p>setPartitionFilters / setMetadataBlockSize / setPinTopLevelIndexAndFilter exist only in
 * RocksDB 9.x. The x86 (5.15.10) variant of this class is a no-op — see that file.
 */
public final class RocksDbPartitionedFilter {

  private RocksDbPartitionedFilter() {
  }

  public static void enable(BlockBasedTableConfig tableCfg) {
    tableCfg.setIndexType(IndexType.kTwoLevelIndexSearch);
    tableCfg.setPartitionFilters(true);
    tableCfg.setMetadataBlockSize(4096);
    tableCfg.setPinTopLevelIndexAndFilter(true);
  }
}
