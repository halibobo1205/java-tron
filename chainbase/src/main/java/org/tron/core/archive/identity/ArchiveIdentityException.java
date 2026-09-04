package org.tron.core.archive.identity;

import java.io.IOException;

/** Fail-closed error raised for an invalid or inconsistent archive identity. */
public class ArchiveIdentityException extends IOException {

  public ArchiveIdentityException(String message) {
    super(message);
  }

  public ArchiveIdentityException(String message, Throwable cause) {
    super(message, cause);
  }
}
