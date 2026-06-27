package org.tron.core.archive.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ArchiveCoverageTest {

  private final ArchiveCoverage coverage = ArchiveCoverage.of(
      new DefaultArchiveDomainRegistry(), new DefaultArchiveDomainCatalog());

  @Test
  public void rootAndHistoryDomainsArePartitionedCorrectly() {
    assertEquals(17, coverage.getRootDomains().size());
    // ABI is the only HISTORY_ONLY domain.
    assertEquals(1, coverage.getHistoryDomains().size());
    assertEquals(ArchiveDomain.ABI, coverage.getHistoryDomains().get(0).getDomain());
  }

  @Test
  public void defaultRegistryHasNoUnknownBindingsAndExplicitExclusions() {
    assertTrue("default registry must classify every known store",
        coverage.getUnknownBindings().isEmpty());
    assertFalse("excluded stores must exist", coverage.getExcludedBindings().isEmpty());
    assertFalse(coverage.getCapturedBindings().isEmpty());
  }

  @Test
  public void exposesRegistryAndCatalogChecksums() {
    assertEquals(32, coverage.getRegistryChecksum().length);
    assertEquals(32, coverage.getCatalogChecksum().length);
  }
}
