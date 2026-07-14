package org.tron.core.archive.unified;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Logical column families owned by a {@link UnifiedArchiveDb}. */
public enum UnifiedArchiveColumnFamily {
  META("meta"),
  INFLIGHT("inflight"),
  INDEX("index"),
  LATEST("latest"),
  HISTORY("history"),
  CHANGESET("changeset"),
  BLOCK_MARKER("block-marker"),
  COMMITMENT("commitment");

  private final String name;
  private final byte[] nameBytes;

  UnifiedArchiveColumnFamily(String name) {
    this.name = name;
    this.nameBytes = name.getBytes(StandardCharsets.US_ASCII);
  }

  public String getName() {
    return name;
  }

  byte[] nameBytes() {
    return Arrays.copyOf(nameBytes, nameBytes.length);
  }
}
