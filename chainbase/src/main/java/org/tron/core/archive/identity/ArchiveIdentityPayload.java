package org.tron.core.archive.identity;

import java.io.IOException;
import java.nio.file.Path;

/** Payload work ordered between the BOUND and ACTIVE identity transitions. */
public interface ArchiveIdentityPayload {

  /**
   * Creates or resumes the archive payload after the root identity is BOUND.
   *
   * <p>The implementation must be idempotent and must durably sync its ledger and database before
   * returning. A successful return permits the protocol to make the anchor BOUND.
   */
  void bindAndSync(Path archiveRoot, ArchiveIdentity identity) throws IOException;

  /**
   * Performs a read-only, complete verification before activation progresses.
   *
   * <p>After a crash between the two ACTIVE writes, this can be called again with an ACTIVE root
   * and a BOUND anchor.
   */
  void verifyForActivation(Path archiveRoot, ArchiveIdentity identity) throws IOException;
}
