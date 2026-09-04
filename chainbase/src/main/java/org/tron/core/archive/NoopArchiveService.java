package org.tron.core.archive;

import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;

/**
 * No-op {@link ArchiveService} used when {@code storage.archive.enable = false}. All lifecycle
 * callbacks do nothing, so a disabled archive cannot change node behaviour.
 */
public final class NoopArchiveService implements ArchiveService {

  public static final NoopArchiveService INSTANCE = new NoopArchiveService();

  private NoopArchiveService() {
  }

  @Override
  public boolean isEnabled() {
    return false;
  }

  @Override
  public void beginBlock(BlockCapsule block, ArchiveSource source) {
  }

  @Override
  public void commitBlock(BlockCapsule block) {
  }

  @Override
  public void abortBlock(BlockCapsule block) {
  }

  @Override
  public void unwindBlock(BlockCapsule block) {
  }

  @Override
  public void beginSystemTx(BlockCapsule block, ArchivePhase phase) {
  }

  @Override
  public void beginUserTx(BlockCapsule block, int txIndex, TransactionCapsule tx) {
  }

  @Override
  public void beginUserVmTx() {
  }

  @Override
  public void endTx() {
  }
}
