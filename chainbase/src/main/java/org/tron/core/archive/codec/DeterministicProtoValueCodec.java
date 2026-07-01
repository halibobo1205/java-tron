package org.tron.core.archive.codec;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.tron.core.archive.ArchiveException;

/**
 * Canonicalizing value codec for a protobuf-valued domain that needs cross-node-reproducible bytes
 * (decision 4). Parses with the supplied parser, clears unknown fields, and re-serializes with
 * {@link CodedOutputStream#useDeterministicSerialization()} so any map field is written in
 * sorted-key order instead of Java's non-deterministic map iteration order.
 *
 * <p>ACCOUNT, which also strips asset fields, uses {@link AccountCanonicalValueCodec} instead.
 * A proto with no map fields could use raw bytes, but routing it through this codec is harmless and
 * future-proof against a map field being added later.
 */
public final class DeterministicProtoValueCodec implements CanonicalValueCodec {

  private final String codecId;
  private final Parser<? extends Message> parser;

  public DeterministicProtoValueCodec(String codecId, Parser<? extends Message> parser) {
    this.codecId = codecId;
    this.parser = parser;
  }

  @Override
  public String codecId() {
    return codecId;
  }

  @Override
  public DomainValue normalizePut(byte[] value) {
    if (value == null) {
      throw new ArchiveException(codecId + ": value must not be null (use delete for absence)");
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
      throw new ArchiveException(codecId + ": DomainValue must not be null");
    }
    if (value.isDeleted()) {
      return;
    }
    byte[] bytes = value.getValue();
    if (!Arrays.equals(bytes, canonicalize(parse(bytes)))) {
      throw new ArchiveException(codecId + ": value is not in canonical (deterministic) form");
    }
  }

  private Message parse(byte[] bytes) {
    try {
      return parser.parseFrom(bytes);
    } catch (InvalidProtocolBufferException e) {
      throw new ArchiveException(codecId + ": value is not a valid proto for this domain", e);
    }
  }

  private byte[] canonicalize(Message message) {
    Message canonical = message.toBuilder()
        .setUnknownFields(UnknownFieldSet.getDefaultInstance())
        .build();
    ByteArrayOutputStream out = new ByteArrayOutputStream(message.getSerializedSize());
    CodedOutputStream cos = CodedOutputStream.newInstance(out);
    cos.useDeterministicSerialization();
    try {
      canonical.writeTo(cos);
      cos.flush();
    } catch (IOException e) {
      throw new ArchiveException(codecId + ": serialization failed", e);
    }
    return out.toByteArray();
  }
}
