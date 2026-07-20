package org.tron.core.archive.temporal;

import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainDescriptor;
import org.tron.core.archive.domain.DynamicKeyPolicy;
import org.tron.core.archive.domain.HistoryPolicy;

/** Validates decoded temporal rows independently of any physical RocksDB layout. */
final class ArchiveTemporalRowValidator {

  private ArchiveTemporalRowValidator() {
  }

  static void validate(ArchiveDomainCatalog catalog, byte[] key, byte[] value,
      boolean validateValue, DynamicKeyPolicy dynamicKeyPolicy) {
    if (key == null || key.length == 0) {
      throw new ArchiveException("archive temporal row key is null or empty");
    }
    ArchiveDomain domain;
    byte[] canonicalKey;
    switch (key[0]) {
      case ArchiveTemporalCodec.LATEST_PREFIX:
        domain = ArchiveTemporalCodec.domainOfLatestKey(key);
        canonicalKey = ArchiveTemporalCodec.canonicalKeyOfLatestKey(key);
        break;
      case ArchiveTemporalCodec.HISTORY_PREFIX:
        domain = ArchiveTemporalCodec.domainOfHistoryKey(key);
        canonicalKey = ArchiveTemporalCodec.canonicalKeyOfHistoryKey(key);
        break;
      case ArchiveTemporalCodec.CHANGESET_PREFIX:
        domain = ArchiveTemporalCodec.domainOfChangesetKey(key);
        canonicalKey = ArchiveTemporalCodec.canonicalKeyOfChangesetKey(key);
        break;
      default:
        throw new ArchiveException("archive temporal store has unknown key prefix " + key[0]);
    }
    ArchiveDomainDescriptor descriptor = catalog.descriptorFor(domain);
    if (descriptor == null) {
      throw new ArchiveException("archive temporal catalog missing descriptor for " + domain);
    }
    descriptor.getKeyCodec().validate(canonicalKey);
    validateKeyPolicy(domain, canonicalKey, dynamicKeyPolicy);
    if (validateValue) {
      descriptor.getValueCodec().validate(ArchiveTemporalCodec.decodeValue(value));
    }
  }

  private static void validateKeyPolicy(ArchiveDomain domain, byte[] canonicalKey,
      DynamicKeyPolicy dynamicKeyPolicy) {
    if (domain == ArchiveDomain.DYNAMIC_PROPERTIES
        && dynamicKeyPolicy.decision(canonicalKey).getHistoryPolicy() == HistoryPolicy.NO_ARCHIVE) {
      throw new ArchiveException("archive temporal dynamic property is not archived");
    }
  }
}
