package org.tron.core.archive.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tron.common.utils.ByteArray;
import org.tron.core.archive.domain.ArchiveDomainCatalog;
import org.tron.core.archive.domain.ArchiveDomainRegistry;
import org.tron.core.archive.domain.ArchiveSchemaChecksum;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;

public class UnifiedArchiveIdentityPayloadTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void protocolInitializesValidatesAndResumesUnifiedPayload() throws Exception {
    Path base = temporaryFolder.getRoot().toPath();
    Path archiveRoot = base.resolve("archive");
    Path anchors = base.resolve("anchors");
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
    byte[] checksum = ArchiveSchemaChecksum.of(registry, catalog);
    UnifiedArchiveIdentityPayload payload =
        new UnifiedArchiveIdentityPayload(catalog, checksum);
    ArchiveIdentityProtocol protocol = new ArchiveIdentityProtocol(payload);
    ArchiveIdentityClaim claim = ArchiveIdentityClaim.create(
        "test-chain", ByteArray.toHexString(checksum), "UNIFIED_V1", archiveRoot, 0L);

    protocol.init(anchors, claim);
    ArchiveIdentity active = protocol.resume(anchors, claim);

    assertEquals(ArchiveIdentityState.ACTIVE, active.getState());
    assertTrue(java.nio.file.Files.isDirectory(
        UnifiedArchiveIdentityPayload.databasePath(archiveRoot)));
    assertEquals(0L, UnifiedArchiveIdentityPayload.inspectFloor(
        archiveRoot, catalog, checksum));
    assertEquals(active, protocol.validateActive(
        anchors, archiveRoot, "test-chain", ByteArray.toHexString(checksum),
        "UNIFIED_V1", 0L));
    assertEquals(active, protocol.resume(anchors, claim));
  }

  @Test
  public void newUnifiedPayloadRejectsNonZeroFloor() {
    Path archiveRoot = temporaryFolder.getRoot().toPath().resolve("wrong-floor");
    ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();
    ArchiveDomainCatalog catalog = new DefaultArchiveDomainCatalog();
    byte[] checksum = ArchiveSchemaChecksum.of(registry, catalog);
    UnifiedArchiveIdentityPayload payload =
        new UnifiedArchiveIdentityPayload(catalog, checksum);
    ArchiveIdentityClaim claim = ArchiveIdentityClaim.create(
        "test-chain", ByteArray.toHexString(checksum), "UNIFIED_V1", archiveRoot, 7L);

    assertThrows(ArchiveIdentityException.class,
        () -> payload.bindAndSync(
            archiveRoot, new ArchiveIdentity(claim, ArchiveIdentityState.BOUND)));
  }
}
