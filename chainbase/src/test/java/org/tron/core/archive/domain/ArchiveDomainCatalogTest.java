package org.tron.core.archive.domain;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.tron.core.archive.codec.CanonicalValueCodec;
import org.tron.protos.Protocol.Proposal;

public class ArchiveDomainCatalogTest {

  private final ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
  private final ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();

  @Test
  public void everyRootDomainHasCodecsAndIsInGlobalRoot() {
    assertEquals(17, catalog.rootDescriptors().size());
    for (ArchiveDomainDescriptor d : catalog.rootDescriptors()) {
      assertEquals(RootPolicy.IN_GLOBAL_ROOT, d.getRootPolicy());
      assertNotNull("key codec for " + d.getDomain(), d.getKeyCodec());
      assertNotNull("value codec for " + d.getDomain(), d.getValueCodec());
      assertFalse(d.getKeyCodec().codecId().isEmpty());
      assertFalse(d.getValueCodec().codecId().isEmpty());
      assertFalse(d.getSourceDbName().isEmpty());
    }
    // ABI is catalogued but HISTORY_ONLY, so 18 descriptors total.
    assertEquals(18, catalog.allDescriptors().size());
    assertEquals(RootPolicy.HISTORY_ONLY,
        catalog.descriptorFor(ArchiveDomain.ABI).getRootPolicy());
  }

  @Test
  public void proposalParametersMapIsCanonicalizedDeterministically() {
    // The decision-4 canary: Proposal.parameters is map<int64,int64>; insertion order must not
    // change the descriptor's canonical bytes.
    CanonicalValueCodec codec = catalog.descriptorFor(ArchiveDomain.PROPOSAL).getValueCodec();
    Proposal a = Proposal.newBuilder().setProposalId(1)
        .putParameters(9, 100).putParameters(1, 200).putParameters(5, 300).build();
    Proposal b = Proposal.newBuilder().setProposalId(1)
        .putParameters(1, 200).putParameters(5, 300).putParameters(9, 100).build();
    assertArrayEquals(
        codec.normalizePut(a.toByteArray()).getValue(),
        codec.normalizePut(b.toByteArray()).getValue());
  }

  @Test
  public void catalogIsConsistentWithRegistry() {
    // Every captured registry binding's domain must have a catalog descriptor whose dbName + root
    // policy agree -- catches drift between the two schemas.
    for (StoreBinding b : registry.allStoreBindings()) {
      if (!b.getDomain().isPresent()) {
        continue;
      }
      ArchiveDomainDescriptor d = catalog.descriptorFor(b.getDomain().get());
      assertNotNull("no descriptor for captured domain " + b.getDomain().get(), d);
      assertEquals("dbName drift for " + b.getDomain().get(), b.getDbName(), d.getSourceDbName());
      assertEquals("root policy drift for " + b.getDomain().get(),
          b.getRootPolicy(), d.getRootPolicy());
    }
  }

  @Test
  public void checksumIsDeterministicAndCoversSchema() {
    byte[] a = catalog.checksum();
    byte[] b = new DefaultArchiveDomainCatalog().checksum();
    assertEquals(32, a.length);
    assertArrayEquals(a, b);
  }
}
