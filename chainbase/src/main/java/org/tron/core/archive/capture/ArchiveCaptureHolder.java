package org.tron.core.archive.capture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.core.archive.domain.ArchiveDomain;

/**
 * Process-wide bridge from the Store path to the active {@link ArchiveCaptureEngine}, mirroring
 * {@code ArchiveExecutionContextHolder}. When no engine is set (archive disabled) every call is a
 * cheap no-op.
 *
 * <p>Critically, capture failures are swallowed here: archive is a non-consensus sidecar,
 * so a bad value / codec error must NEVER propagate into block apply and break consensus.
 */
public final class ArchiveCaptureHolder {

  private static final Logger logger = LoggerFactory.getLogger("archive");

  private static volatile ArchiveCaptureEngine engine;

  private ArchiveCaptureHolder() {
  }

  public static void set(ArchiveCaptureEngine captureEngine) {
    engine = captureEngine;
  }

  public static void clear() {
    engine = null;
  }

  public static boolean isActive() {
    return engine != null;
  }

  public static void capturePut(String dbName, byte[] key, byte[] value) {
    ArchiveCaptureEngine active = engine;
    if (active == null) {
      return;
    }
    try {
      active.capturePut(dbName, key, value);
    } catch (Exception e) {
      logger.warn("archive capture(put) failed for store {} (dropped): {}", dbName, e.getMessage());
    }
  }

  public static void captureDelete(String dbName, byte[] key) {
    ArchiveCaptureEngine active = engine;
    if (active == null) {
      return;
    }
    try {
      active.captureDelete(dbName, key);
    } catch (Exception e) {
      logger.warn("archive capture(delete) failed for {} (dropped): {}", dbName, e.getMessage());
    }
  }

  /** Derive ACCOUNT_ASSET (TRC10) records from an account write; isolated like capturePut. */
  public static void captureAccountAsset(byte[] addressKey, byte[] oldAccount, byte[] newAccount) {
    ArchiveCaptureEngine active = engine;
    if (active == null) {
      return;
    }
    try {
      active.captureAccountAsset(addressKey, oldAccount, newAccount);
    } catch (Exception e) {
      logger.warn("archive account-asset capture failed (dropped): {}", e.getMessage());
    }
  }

  /** Capture a SEMANTIC domain put (e.g. CONTRACT_STORAGE); no-op + isolated like capturePut. */
  public static void captureSemanticPut(ArchiveDomain domain, byte[] canonicalKey, byte[] value) {
    ArchiveCaptureEngine active = engine;
    if (active == null) {
      return;
    }
    try {
      active.captureSemanticPut(domain, canonicalKey, value);
    } catch (Exception e) {
      logger.warn("archive semantic capture(put) failed for {} (dropped): {}",
          domain, e.getMessage());
    }
  }

  /** Capture a SEMANTIC domain delete (e.g. zeroed storage slot); no-op + isolated. */
  public static void captureSemanticDelete(ArchiveDomain domain, byte[] canonicalKey) {
    ArchiveCaptureEngine active = engine;
    if (active == null) {
      return;
    }
    try {
      active.captureSemanticDelete(domain, canonicalKey);
    } catch (Exception e) {
      logger.warn("archive semantic capture(delete) failed for {} (dropped): {}", domain,
          e.getMessage());
    }
  }
}
