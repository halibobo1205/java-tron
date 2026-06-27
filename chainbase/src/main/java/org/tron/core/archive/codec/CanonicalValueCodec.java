package org.tron.core.archive.codec;

/**
 * Normalizes a raw store value into a canonical {@link DomainValue}. L3 codecs treat the value as
 * canonical bytes (length checks, clone, tombstone for delete); they do NOT parse protobuf.
 *
 * <p>Decision 4 (cross-node-reproducible root) requires per-domain CANONICALIZING value codecs for
 * proto-valued domains (e.g. ACCOUNT must strip asset/assetV2/asset_optimized and sort map fields).
 * That canonicalization is a follow-on step layered on this interface; {@link #codecId} versions it.
 */
public interface CanonicalValueCodec {

  String codecId();

  DomainValue normalizePut(byte[] value);

  DomainValue normalizeDelete();

  void validate(DomainValue value);
}
