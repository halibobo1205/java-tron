package org.tron.core.archive;

/** A native archive owner could not confirm release and must remain pinned until restart. */
public final class ArchiveNativeResourceReleaseException extends ArchiveException {

  private static final long serialVersionUID = 1L;
  public ArchiveNativeResourceReleaseException(String message, Throwable cause) {
    super(message, cause);
  }
}
