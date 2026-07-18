package org.tron.core.archive.unified;

/** Verifies one immutable journal payload against its independently durable proof. */
public interface UnifiedArchiveJournalVerifier {

  long expectedPayloadBytes();

  void requireMatches(byte[] journalKey, byte[] payload);
}
