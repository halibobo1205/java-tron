package org.tron.core.archive.codec;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.tron.core.archive.ArchiveException;
import org.tron.protos.Protocol.Account;

/**
 * Canonicalizing value codec for the ACCOUNT domain (decision 4 - cross-node-reproducible root).
 *
 * <p>Two transforms make the bytes deterministic and node-config-independent:
 * <ol>
 *   <li>Strip {@code asset}(6), {@code assetV2}(56) and {@code asset_optimized}(60): TRC10 balances
 *       are captured in the ACCOUNT_ASSET domain, and {@code asset_optimized} is a local storage
 *       flag - so the canonical value is identical regardless of asset optimization.</li>
 *   <li>Serialize with {@link CodedOutputStream#useDeterministicSerialization()} so every protobuf
 *       map field (parameters, asset time/usage maps, etc.) is written in
 *       sorted-key order, instead of Java's non-deterministic map iteration order.</li>
 * </ol>
 *
 * <p>Note: per-asset time/usage maps are NOT stripped (they survive {@code clearAsset()} and are
 * regime-independent); revisit if they should move into ACCOUNT_ASSET.
 */
public final class AccountCanonicalValueCodec implements CanonicalValueCodec {

  @Override
  public String codecId() {
    return "account-capsule-canonical-v1";
  }

  @Override
  public DomainValue normalizePut(byte[] value) {
    if (value == null) {
      throw new ArchiveException(codecId() + ": account value must not be null");
    }
    return DomainValue.present(canonicalize(parse(value)));
  }

  @Override
  public DomainValue normalizeDelete() {
    return DomainValue.tombstone();
  }

  @Override
  public void validate(DomainValue value) {
    if (value == null) {
      throw new ArchiveException(codecId() + ": DomainValue must not be null");
    }
    if (value.isDeleted()) {
      return;
    }
    Account account = parse(value.getValue());
    if (account.getAssetCount() != 0 || account.getAssetV2Count() != 0
        || account.getAssetOptimized()) {
      throw new ArchiveException(
          codecId() + ": canonical account must have asset/assetV2/asset_optimized stripped");
    }
  }

  private Account parse(byte[] bytes) {
    try {
      return Account.parseFrom(bytes);
    } catch (InvalidProtocolBufferException e) {
      throw new ArchiveException(codecId() + ": value is not a valid Account proto", e);
    }
  }

  private static byte[] canonicalize(Account account) {
    Account stripped = account.toBuilder()
        .clearAsset()
        .clearAssetV2()
        .clearAssetOptimized()
        .build();
    ByteArrayOutputStream out = new ByteArrayOutputStream(stripped.getSerializedSize());
    CodedOutputStream cos = CodedOutputStream.newInstance(out);
    cos.useDeterministicSerialization();
    try {
      stripped.writeTo(cos);
      cos.flush();
    } catch (IOException e) {
      throw new ArchiveException("account-capsule-canonical-v1: serialization failed", e);
    }
    return out.toByteArray();
  }
}
