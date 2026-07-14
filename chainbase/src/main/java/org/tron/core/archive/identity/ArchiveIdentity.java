package org.tron.core.archive.identity;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** One persisted archive identity record. */
public final class ArchiveIdentity {

  private final ArchiveIdentityClaim claim;
  private final ArchiveIdentityState state;

  public ArchiveIdentity(ArchiveIdentityClaim claim, ArchiveIdentityState state) {
    this.claim = Objects.requireNonNull(claim, "claim");
    this.state = Objects.requireNonNull(state, "state");
  }

  public ArchiveIdentityClaim getClaim() {
    return claim;
  }

  public ArchiveIdentityState getState() {
    return state;
  }

  public UUID getUuid() {
    return claim.getUuid();
  }

  public byte[] getResumeNonce() {
    return claim.getResumeNonce();
  }

  public String getChainId() {
    return claim.getChainId();
  }

  public String getSchema() {
    return claim.getSchema();
  }

  public String getLayout() {
    return claim.getLayout();
  }

  public Path getFinalPath() {
    return claim.getFinalPath();
  }

  public long getFloor() {
    return claim.getFloor();
  }

  public ArchiveIdentity withState(ArchiveIdentityState nextState) {
    return new ArchiveIdentity(claim, nextState);
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (!(object instanceof ArchiveIdentity)) {
      return false;
    }
    ArchiveIdentity other = (ArchiveIdentity) object;
    return claim.equals(other.claim) && state == other.state;
  }

  @Override
  public int hashCode() {
    return Objects.hash(claim, state);
  }

  @Override
  public String toString() {
    return "ArchiveIdentity{" + "claim=" + claim + ", state=" + state + '}';
  }
}
