package org.tron.core.archive.codec;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.tron.core.archive.ArchiveException;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;

/**
 * Canonicalizing value codec for the CONTRACT domain (decision 4). Strips {@code SmartContract.abi}
 * (the ABI is a separate ABI domain backed by AbiStore) and re-serializes deterministically.
 *
 * <p>{@code ContractStore.put} already clears abi before persisting (ContractStore.java:36-38), so
 * on-disk bytes carry no abi; stripping here is idempotent defense-in-depth that also keeps the
 * canonical value identical regardless of the L4 capture point or legacy pre-migration data.
 */
public final class ContractCanonicalValueCodec implements CanonicalValueCodec {

  @Override
  public String codecId() {
    return "contract-capsule-canonical-v1";
  }

  @Override
  public DomainValue normalizePut(byte[] value) {
    if (value == null) {
      throw new ArchiveException(codecId() + ": contract value must not be null");
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
    SmartContract contract = parse(value.getValue());
    if (contract.hasAbi()) {
      throw new ArchiveException(codecId() + ": canonical contract must have abi stripped");
    }
    if (!Arrays.equals(value.getValue(), canonicalize(contract))) {
      throw new ArchiveException(codecId() + ": value is not in canonical form");
    }
  }

  @Override
  public void validateProofBound(DomainValue value) {
    if (value == null) {
      throw new ArchiveException(codecId() + ": DomainValue must not be null");
    }
  }

  private SmartContract parse(byte[] bytes) {
    try {
      return SmartContract.parseFrom(bytes);
    } catch (InvalidProtocolBufferException e) {
      throw new ArchiveException(codecId() + ": value is not a valid SmartContract proto", e);
    }
  }

  private byte[] canonicalize(SmartContract contract) {
    SmartContract stripped = contract.toBuilder()
        .clearAbi()
        .setUnknownFields(UnknownFieldSet.getDefaultInstance())
        .build();
    ByteArrayOutputStream out = new ByteArrayOutputStream(stripped.getSerializedSize());
    CodedOutputStream cos = CodedOutputStream.newInstance(out);
    cos.useDeterministicSerialization();
    try {
      stripped.writeTo(cos);
      cos.flush();
    } catch (IOException e) {
      throw new ArchiveException(codecId() + ": serialization failed", e);
    }
    return out.toByteArray();
  }
}
