package org.tron.common.utils;

import org.rocksdb.BlockBasedTableConfig;

/**
 * Arch-specific RocksDB table tuning — x86 / RocksDB 5.15.10 variant.
 *
 * <p>RocksDB 5.15.10's {@link BlockBasedTableConfig} has no setPartitionFilters /
 * setMetadataBlockSize / setPinTopLevelIndexAndFilter, and the x86 build does not exhibit the
 * full-filter re-read issue, so this is intentionally a no-op. See the arm64 variant for the
 * RocksDB 9.x implementation.
 */
public final class RocksDbPartitionedFilter {

  private RocksDbPartitionedFilter() {
  }

  public static void enable(BlockBasedTableConfig tableCfg) {
    // No-op: partitioned-filter APIs are unavailable in RocksDB 5.15.10.
  }
}
