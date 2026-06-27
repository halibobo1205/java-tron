package org.tron.core.archive.domain;

import org.tron.core.archive.codec.CanonicalKeyCodec;
import org.tron.core.archive.codec.CanonicalValueCodec;

/**
 * Binds an archive {@link ArchiveDomain} to its canonical key/value codecs and its root/history/
 * reader policies. The catalog of descriptors is the schema L4 (capture) and L7 (root) consult to
 * encode each domain deterministically.
 */
public final class ArchiveDomainDescriptor {

  private final ArchiveDomain domain;
  private final String sourceDbName;
  private final CanonicalKeyCodec keyCodec;
  private final CanonicalValueCodec valueCodec;
  private final RootPolicy rootPolicy;
  private final HistoryPolicy historyPolicy;
  private final ReaderPolicy readerPolicy;

  public ArchiveDomainDescriptor(ArchiveDomain domain, String sourceDbName,
      CanonicalKeyCodec keyCodec, CanonicalValueCodec valueCodec,
      RootPolicy rootPolicy, HistoryPolicy historyPolicy, ReaderPolicy readerPolicy) {
    this.domain = domain;
    this.sourceDbName = sourceDbName;
    this.keyCodec = keyCodec;
    this.valueCodec = valueCodec;
    this.rootPolicy = rootPolicy;
    this.historyPolicy = historyPolicy;
    this.readerPolicy = readerPolicy;
  }

  public ArchiveDomain getDomain() {
    return domain;
  }

  public String getSourceDbName() {
    return sourceDbName;
  }

  public CanonicalKeyCodec getKeyCodec() {
    return keyCodec;
  }

  public CanonicalValueCodec getValueCodec() {
    return valueCodec;
  }

  public RootPolicy getRootPolicy() {
    return rootPolicy;
  }

  public HistoryPolicy getHistoryPolicy() {
    return historyPolicy;
  }

  public ReaderPolicy getReaderPolicy() {
    return readerPolicy;
  }
}
