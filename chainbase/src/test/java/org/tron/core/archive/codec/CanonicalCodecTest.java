package org.tron.core.archive.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.tron.core.archive.ArchiveException;

public class CanonicalCodecTest {

  @Test
  public void address21CodecValidatesLengthAndClones() {
    Address21KeyCodec codec = new Address21KeyCodec("tron-address21-v1");
    byte[] key = new byte[21];
    key[0] = 0x41;
    byte[] norm = codec.normalize(key);
    assertArrayEquals(key, norm);
    assertNotSame(key, norm);
    assertThrows(ArchiveException.class, () -> codec.normalize(new byte[20]));
    assertThrows(ArchiveException.class, () -> codec.normalize(null));
  }

  @Test
  public void contractStorageCodecValidatesPhysicalKeyLength() {
    ContractStorageKeyCodec codec = new ContractStorageKeyCodec();
    byte[] key = new byte[ContractStorageKeyCodec.LENGTH];
    assertArrayEquals(key, codec.normalize(key));
    key[ContractStorageKeyCodec.LENGTH - 1] = 2;
    codec.validate(key);
    assertThrows(ArchiveException.class,
        () -> codec.normalize(new byte[ContractStorageKeyCodec.LENGTH - 1]));
  }

  @Test
  public void dynamicPropertyCodecRequiresNonEmptyAscii() {
    DynamicPropertyKeyCodec codec = new DynamicPropertyKeyCodec();
    byte[] key = "ENERGY_FEE".getBytes(StandardCharsets.US_ASCII);
    assertArrayEquals(key, codec.normalize(key));
    assertThrows(ArchiveException.class, () -> codec.normalize(new byte[0]));
    assertThrows(ArchiveException.class, () -> codec.normalize(new byte[] {(byte) 0x80}));
  }

  @Test
  public void bytesValueCodecHandlesPresentAndTombstone() {
    BytesValueCodec codec = new BytesValueCodec("bytecode-bytes-v1", true);
    DomainValue present = codec.normalizePut(new byte[] {1, 2, 3});
    assertFalse(present.isDeleted());
    assertArrayEquals(new byte[] {1, 2, 3}, present.getValue());
    assertTrue(codec.normalizeDelete().isDeleted());
    // allowEmpty=true: empty present value is allowed and is NOT a tombstone.
    DomainValue empty = codec.normalizePut(new byte[0]);
    assertFalse(empty.isDeleted());
    assertEquals(0, empty.getValue().length);
    // allowEmpty=false rejects empty present.
    BytesValueCodec strict = new BytesValueCodec("strict-v1", false);
    assertThrows(ArchiveException.class, () -> strict.normalizePut(new byte[0]));
    assertThrows(ArchiveException.class, () -> codec.normalizePut(null));
  }

  @Test
  public void domainValueIsDefensivelyCopied() {
    byte[] src = {9, 9};
    DomainValue v = DomainValue.present(src);
    src[0] = 0; // mutate source after construction
    assertEquals(9, v.getValue()[0]);
    byte[] out = v.getValue();
    out[1] = 0; // mutate returned copy
    assertEquals(9, v.getValue()[1]);
  }
}
