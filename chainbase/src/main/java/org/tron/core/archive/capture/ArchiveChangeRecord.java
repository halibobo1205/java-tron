package org.tron.core.archive.capture;

import java.util.Arrays;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.txnum.ArchiveTxPosition;

/**
 * One captured domain state change: the canonical key/value plus the {@link ArchiveTxPosition}
 * (txNum + block/phase/tx) it occurred at. This is the raw, append-only capture stream the L5
 * temporal store will turn into per-domain latest/history/changesets.
 */
public final class ArchiveChangeRecord {

  private final ArchiveTxPosition position;
  private final ArchiveDomain domain;
  private final byte[] canonicalKey;
  private final DomainValue value;

  public ArchiveChangeRecord(ArchiveTxPosition position, ArchiveDomain domain,
      byte[] canonicalKey, DomainValue value) {
    this.position = position;
    this.domain = domain;
    this.canonicalKey = Arrays.copyOf(canonicalKey, canonicalKey.length);
    this.value = value;
  }

  public ArchiveTxPosition getPosition() {
    return position;
  }

  public long getTxNum() {
    return position.getTxNum();
  }

  public ArchiveDomain getDomain() {
    return domain;
  }

  public byte[] getCanonicalKey() {
    return Arrays.copyOf(canonicalKey, canonicalKey.length);
  }

  public DomainValue getValue() {
    return value;
  }
}
