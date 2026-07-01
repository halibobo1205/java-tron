package org.tron.core.archive.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.UnknownFieldSet;
import org.junit.Test;
import org.tron.core.archive.ArchiveException;
import org.tron.protos.Protocol.Account;

public class AccountCanonicalValueCodecTest {

  private final AccountCanonicalValueCodec codec = new AccountCanonicalValueCodec();

  private static Account parse(DomainValue value) throws InvalidProtocolBufferException {
    return Account.parseFrom(value.getValue());
  }

  @Test
  public void stripsAssetsAndOptimizedFlagButKeepsCoreState() throws Exception {
    Account account = Account.newBuilder()
        .setAddress(ByteString.copyFromUtf8("addr"))
        .setBalance(100)
        .putAsset("1", 5)
        .putAssetV2("1000001", 7)
        .setAssetOptimized(true)
        .build();
    DomainValue canonical = codec.normalizePut(account.toByteArray());
    Account back = parse(canonical);
    assertEquals(0, back.getAssetCount());
    assertEquals(0, back.getAssetV2Count());
    assertFalse(back.getAssetOptimized());
    assertEquals(100, back.getBalance());
    assertEquals(ByteString.copyFromUtf8("addr"), back.getAddress());
    codec.validate(canonical); // canonical form passes validation
  }

  @Test
  public void assetContentDoesNotAffectCanonicalBytes() {
    Account a = Account.newBuilder().setBalance(100).putAsset("1", 5).build();
    Account b = Account.newBuilder().setBalance(100)
        .putAsset("2", 9).putAssetV2("1000001", 1).setAssetOptimized(true).build();
    assertArrayEquals(
        codec.normalizePut(a.toByteArray()).getValue(),
        codec.normalizePut(b.toByteArray()).getValue());
  }

  @Test
  public void canonicalizationIsIdempotent() {
    Account account = Account.newBuilder().setBalance(42).putAsset("1", 5).build();
    byte[] once = codec.normalizePut(account.toByteArray()).getValue();
    byte[] twice = codec.normalizePut(once).getValue();
    assertArrayEquals(once, twice);
  }

  @Test
  public void unknownFieldsDoNotAffectCanonicalBytes() {
    Account base = Account.newBuilder().setBalance(42).build();
    Account withUnknown = base.toBuilder()
        .setUnknownFields(unknownFields())
        .build();

    assertArrayEquals(
        codec.normalizePut(base.toByteArray()).getValue(),
        codec.normalizePut(withUnknown.toByteArray()).getValue());
    assertThrows(ArchiveException.class,
        () -> codec.validate(DomainValue.present(withUnknown.toByteArray())));
  }

  @Test
  public void mapFieldsSerializeDeterministicallyRegardlessOfInsertionOrder() {
    // latest_asset_operation_time is a non-stripped map field; insertion order must not matter.
    Account a = Account.newBuilder().setBalance(1)
        .putLatestAssetOperationTime("zzz", 1)
        .putLatestAssetOperationTime("aaa", 2)
        .putLatestAssetOperationTime("mmm", 3)
        .build();
    Account b = Account.newBuilder().setBalance(1)
        .putLatestAssetOperationTime("aaa", 2)
        .putLatestAssetOperationTime("mmm", 3)
        .putLatestAssetOperationTime("zzz", 1)
        .build();
    assertArrayEquals(
        codec.normalizePut(a.toByteArray()).getValue(),
        codec.normalizePut(b.toByteArray()).getValue());
  }

  @Test
  public void validateRejectsAccountStillCarryingAssets() {
    Account withAssets = Account.newBuilder().setBalance(1).putAsset("1", 5).build();
    DomainValue notCanonical = DomainValue.present(withAssets.toByteArray());
    assertThrows(ArchiveException.class, () -> codec.validate(notCanonical));
  }

  @Test
  public void tombstoneIsDeleteNotEmptyValue() {
    DomainValue tombstone = codec.normalizeDelete();
    assertTrue(tombstone.isDeleted());
    codec.validate(tombstone); // tombstone is always valid
  }

  @Test
  public void rejectsNullAndNonProtoInput() {
    assertThrows(ArchiveException.class, () -> codec.normalizePut(null));
    assertThrows(ArchiveException.class, () -> codec.normalizePut(new byte[] {(byte) 0xff, 0x01}));
  }

  private static UnknownFieldSet unknownFields() {
    return UnknownFieldSet.newBuilder()
        .addField(12345, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }
}
