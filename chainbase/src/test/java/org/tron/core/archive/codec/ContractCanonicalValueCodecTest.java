package org.tron.core.archive.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import org.junit.Test;
import org.tron.core.archive.ArchiveException;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract.ABI;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract.ABI.Entry;

public class ContractCanonicalValueCodecTest {

  private final ContractCanonicalValueCodec codec = new ContractCanonicalValueCodec();

  private static ABI abiWith(String... names) {
    ABI.Builder b = ABI.newBuilder();
    for (String n : names) {
      b.addEntrys(Entry.newBuilder().setName(n));
    }
    return b.build();
  }

  @Test
  public void stripsAbiButKeepsOtherFields() throws Exception {
    SmartContract contract = SmartContract.newBuilder()
        .setContractAddress(ByteString.copyFromUtf8("addr"))
        .setBytecode(ByteString.copyFromUtf8("6080"))
        .setAbi(abiWith("transfer"))
        .build();
    assertTrue(contract.hasAbi());
    DomainValue canonical = codec.normalizePut(contract.toByteArray());
    SmartContract back = SmartContract.parseFrom(canonical.getValue());
    assertFalse(back.hasAbi());
    assertEquals(ByteString.copyFromUtf8("6080"), back.getBytecode());
    codec.validate(canonical);
  }

  @Test
  public void abiContentDoesNotAffectCanonicalBytes() {
    SmartContract a = SmartContract.newBuilder()
        .setBytecode(ByteString.copyFromUtf8("AA")).setAbi(abiWith("foo")).build();
    SmartContract b = SmartContract.newBuilder()
        .setBytecode(ByteString.copyFromUtf8("AA")).setAbi(abiWith("bar", "baz")).build();
    assertArrayEquals(
        codec.normalizePut(a.toByteArray()).getValue(),
        codec.normalizePut(b.toByteArray()).getValue());
  }

  @Test
  public void canonicalizationIsIdempotent() {
    SmartContract c = SmartContract.newBuilder().setBytecode(ByteString.copyFromUtf8("CC")).build();
    byte[] once = codec.normalizePut(c.toByteArray()).getValue();
    byte[] twice = codec.normalizePut(once).getValue();
    assertArrayEquals(once, twice);
  }

  @Test
  public void unknownFieldsDoNotAffectCanonicalBytes() {
    SmartContract base = SmartContract.newBuilder()
        .setBytecode(ByteString.copyFromUtf8("CC"))
        .build();
    SmartContract withUnknown = base.toBuilder()
        .setUnknownFields(unknownFields())
        .build();

    assertArrayEquals(
        codec.normalizePut(base.toByteArray()).getValue(),
        codec.normalizePut(withUnknown.toByteArray()).getValue());
    assertThrows(ArchiveException.class,
        () -> codec.validate(DomainValue.present(withUnknown.toByteArray())));
  }

  @Test
  public void validateRejectsContractStillCarryingAbi() {
    SmartContract withAbi = SmartContract.newBuilder().setAbi(abiWith("x")).build();
    DomainValue notCanonical = DomainValue.present(withAbi.toByteArray());
    assertThrows(ArchiveException.class, () -> codec.validate(notCanonical));
  }

  @Test
  public void tombstoneAndRejectsBadInput() {
    assertTrue(codec.normalizeDelete().isDeleted());
    assertThrows(ArchiveException.class, () -> codec.normalizePut(null));
    assertThrows(ArchiveException.class, () -> codec.normalizePut(new byte[] {(byte) 0xff, 0x01}));
  }

  private static UnknownFieldSet unknownFields() {
    return UnknownFieldSet.newBuilder()
        .addField(12345, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }
}
