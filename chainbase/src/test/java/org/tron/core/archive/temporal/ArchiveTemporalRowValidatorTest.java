package org.tron.core.archive.temporal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DynamicKeyPolicy;

public class ArchiveTemporalRowValidatorTest {

  @Test
  public void rejectsNullAndEmptyKeysWithArchiveException() {
    DefaultArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
    DynamicKeyPolicy dynamicKeyPolicy = new DynamicKeyPolicy();

    for (byte[] key : new byte[][] {null, new byte[0]}) {
      ArchiveException failure = assertThrows(ArchiveException.class,
          () -> ArchiveTemporalRowValidator.validate(
              catalog, key, null, false, dynamicKeyPolicy));
      assertEquals("archive temporal row key is null or empty", failure.getMessage());
    }
  }
}
