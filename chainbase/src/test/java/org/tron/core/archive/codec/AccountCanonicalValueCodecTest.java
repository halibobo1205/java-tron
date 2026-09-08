package org.tron.core.archive.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

  @Test
  public void matchesOriginalBytesForEveryStrippingCombination() throws Exception {
    Random random = new Random(73921L);
    for (int sample = 0; sample < 32; sample++) {
      Account base = accountWithRetainedFields(random, sample);
      for (int mask = 0; mask < 16; mask++) {
        Account.Builder builder = base.toBuilder();
        if ((mask & 1) != 0) {
          builder.putAsset("legacy", 17L);
        }
        if ((mask & 2) != 0) {
          builder.putAssetV2("1000001", 0L);
        }
        if ((mask & 4) != 0) {
          builder.setAssetOptimized(true);
        }
        if ((mask & 8) != 0) {
          builder.setUnknownFields(unknownFields());
        }
        Account account = builder.build();
        byte[] input = account.toByteArray();
        DomainValue canonical = codec.normalizePut(input);

        assertArrayEquals("sample=" + sample + ", mask=" + mask,
            originalCanonicalBytes(account), canonical.getValue());
        assertArrayEquals(account.toByteArray(), input);
        assertEquals(base.getAccountResource(), parse(canonical).getAccountResource());
        assertArrayEquals(canonical.getValue(),
            codec.normalizePut(canonical.getValue()).getValue());
        codec.validate(canonical);
      }
    }
  }

  @Test
  public void emptyAndLargeValuesPreserveOriginalBytesAndBufferOwnership() throws Exception {
    assertArrayEquals(new byte[0], codec.normalizePut(new byte[0]).getValue());
    codec.validate(codec.normalizePut(new byte[0]));
    byte[] name = new byte[128 * 1024];
    Arrays.fill(name, (byte) 'a');
    Account.Builder builder = Account.newBuilder().setAccountName(ByteString.copyFrom(name));
    for (int i = 2048; i >= 0; i--) {
      builder.putLatestAssetOperationTimeV2("asset-" + i, i);
    }
    Account account = builder.build();
    byte[] expected = originalCanonicalBytes(account);
    byte[] input = account.toByteArray();
    DomainValue actual = codec.normalizePut(input);
    Arrays.fill(input, (byte) 0);
    byte[] returned = actual.getValue();
    assertArrayEquals(expected, returned);
    Arrays.fill(returned, (byte) 0);
    assertArrayEquals(expected, actual.getValue());
    codec.validate(actual);
  }

  @Test
  public void concurrentNormalizationDoesNotShareOutputBuffers() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(4);
    try {
      List<Future<byte[]>> futures = new ArrayList<>();
      List<byte[]> expected = new ArrayList<>();
      Random random = new Random(871L);
      for (int i = 0; i < 64; i++) {
        Account.Builder builder = accountWithRetainedFields(random, i).toBuilder();
        if ((i & 1) != 0) {
          builder.putAssetV2("1000001", i).setAssetOptimized(true)
              .setUnknownFields(unknownFields());
        }
        Account account = builder.build();
        expected.add(originalCanonicalBytes(account));
        byte[] input = account.toByteArray();
        futures.add(executor.submit(() -> codec.normalizePut(input).getValue()));
      }
      for (int i = 0; i < futures.size(); i++) {
        assertArrayEquals(expected.get(i), futures.get(i).get(10L, TimeUnit.SECONDS));
      }
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10L, TimeUnit.SECONDS));
    }
  }

  private static Account accountWithRetainedFields(Random random, int sample) {
    Account.Builder builder = Account.newBuilder()
        .setBalance(random.nextLong())
        .setAllowance(random.nextLong())
        .setAccountResource(Account.AccountResource.newBuilder()
            .setEnergyUsage(random.nextLong())
            .setUnknownFields(unknownFields()))
        .addFrozen(Account.Frozen.newBuilder().setFrozenBalance(random.nextLong()))
        .setTypeValue(123);
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < sample; i++) {
      keys.add("key-" + i);
    }
    Collections.shuffle(keys, random);
    for (String key : keys) {
      builder.putLatestAssetOperationTime(key, random.nextLong())
          .putLatestAssetOperationTimeV2(key, random.nextLong())
          .putFreeAssetNetUsage(key, random.nextLong())
          .putFreeAssetNetUsageV2(key, random.nextLong());
    }
    return builder.build();
  }

  // Keep the pre-optimization implementation as a byte-for-byte oracle.
  private static byte[] originalCanonicalBytes(Account account) throws IOException {
    Account stripped = account.toBuilder()
        .clearAsset()
        .clearAssetV2()
        .clearAssetOptimized()
        .setUnknownFields(UnknownFieldSet.getDefaultInstance())
        .build();
    ByteArrayOutputStream out = new ByteArrayOutputStream(stripped.getSerializedSize());
    CodedOutputStream output = CodedOutputStream.newInstance(out);
    output.useDeterministicSerialization();
    stripped.writeTo(output);
    output.flush();
    return out.toByteArray();
  }

  private static UnknownFieldSet unknownFields() {
    return UnknownFieldSet.newBuilder()
        .addField(12345, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }
}
