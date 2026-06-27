package org.tron.core.archive.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.tron.core.archive.ArchiveException;
import org.tron.protos.Protocol.Account;

public class DeterministicProtoValueCodecTest {

  // Account.parser() is just a convenient map-bearing proto; the generic codec does NOT strip.
  private final DeterministicProtoValueCodec codec =
      new DeterministicProtoValueCodec("test-proto-v1", Account.parser());

  @Test
  public void mapOrderingIsDeterministicAndFieldsAreNotStripped() throws Exception {
    Account a = Account.newBuilder().setBalance(1)
        .putAsset("zzz", 1).putAsset("aaa", 2).putAsset("mmm", 3).build();
    Account b = Account.newBuilder().setBalance(1)
        .putAsset("aaa", 2).putAsset("mmm", 3).putAsset("zzz", 1).build();
    byte[] ca = codec.normalizePut(a.toByteArray()).getValue();
    byte[] cb = codec.normalizePut(b.toByteArray()).getValue();
    assertArrayEquals(ca, cb); // insertion order must not matter
    // Unlike AccountCanonicalValueCodec, the generic codec keeps all fields.
    assertEquals(3, Account.parseFrom(ca).getAssetCount());
  }

  @Test
  public void canonicalizationIsIdempotentAndValidates() {
    Account account = Account.newBuilder().setBalance(7).putAsset("1", 5).build();
    DomainValue once = codec.normalizePut(account.toByteArray());
    byte[] twice = codec.normalizePut(once.getValue()).getValue();
    assertArrayEquals(once.getValue(), twice);
    codec.validate(once); // canonical form passes round-trip validation
  }

  @Test
  public void tombstoneIsDeleteAndValidates() {
    DomainValue tombstone = codec.normalizeDelete();
    assertTrue(tombstone.isDeleted());
    codec.validate(tombstone);
  }

  @Test
  public void rejectsNullAndNonProtoInput() {
    assertThrows(ArchiveException.class, () -> codec.normalizePut(null));
    assertThrows(ArchiveException.class, () -> codec.normalizePut(new byte[] {(byte) 0xff, 0x01}));
  }
}
