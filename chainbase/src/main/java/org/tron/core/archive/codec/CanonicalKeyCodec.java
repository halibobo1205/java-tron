package org.tron.core.archive.codec;

/**
 * Normalizes a raw store key into the domain's canonical key bytes (used in archive keys + root).
 * Implementations are pure and immutable; {@link #normalize} returns a fresh array and never
 * mutates its input. {@link #codecId} is part of the registry checksum.
 */
public interface CanonicalKeyCodec {

  String codecId();

  /** Validate and return canonical key bytes (a fresh array). */
  byte[] normalize(byte[] key);

  /** Validate an already-canonical key without mutating it. */
  void validate(byte[] canonicalKey);
}
