package org.tron.core.archive.temporal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.codec.DomainValue;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.txnum.ArchiveTxPosition;

/**
 * The Erigon prev-value contract requires the in-memory reference store and the RocksDB store to be
 * observationally identical. This drives the SAME change sequence into both and asserts getAsOf at
 * every txNum, latest, and the post-unwind state match -- covering creation, overwrite, delete,
 * mid-chain (pre-coverage) pre-values, prefix-colliding keys and fork unwind.
 */
public class ArchiveTemporalStoreConsistencyTest {

  private static final ArchiveDomain DOMAIN = ArchiveDomain.DYNAMIC_PROPERTIES;
  // K1 is a strict byte-prefix of K2 (the variable-length-key trap).
  private static final byte[] K1 = "ENERGY_FEE".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] K2 = "ENERGY_FEE_HISTORY".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] K3 = "BANDWIDTH".getBytes(StandardCharsets.US_ASCII);

  private InMemoryArchiveTemporalStore mem;
  private RocksDbArchiveTemporalStore rocks;
  private Path dir;

  @Before
  public void setUp() throws IOException {
    mem = new InMemoryArchiveTemporalStore();
    dir = Files.createTempDirectory("archive-temporal-consistency");
    rocks = new RocksDbArchiveTemporalStore(dir.toString());
  }

  @After
  public void tearDown() {
    rocks.close();
    deleteRecursively(dir.toFile());
  }

  private void put(long txNum, byte[] key, DomainValue prev, DomainValue value) {
    ArchiveChangeRecord r = new ArchiveChangeRecord(
        new ArchiveTxPosition(txNum, 1, ArchivePhase.USER_TX, ArchiveSource.NORMAL, 0, null),
        DOMAIN, key, prev, value);
    mem.putChange(r);
    rocks.putChange(r);
  }

  private static DomainValue val(int b) {
    return DomainValue.present(new byte[] {(byte) b});
  }

  /** Assert the two stores return identical getAsOf for a key over a txNum window, plus latest. */
  private void assertParity(byte[] key, long maxTxNum) {
    for (long t = 0; t <= maxTxNum; t++) {
      assertSame("getAsOf(" + new String(key, StandardCharsets.US_ASCII) + ", " + t + ")",
          mem.getAsOf(DOMAIN, key, t), rocks.getAsOf(DOMAIN, key, t));
    }
    assertSame("latest(" + new String(key, StandardCharsets.US_ASCII) + ")",
        mem.latest(DOMAIN, key), rocks.latest(DOMAIN, key));
  }

  private static void assertSame(String what, Optional<DomainValue> a, Optional<DomainValue> b) {
    assertEquals(what + " presence", a.isPresent(), b.isPresent());
    if (a.isPresent()) {
      assertEquals(what + " deleted", a.get().isDeleted(), b.get().isDeleted());
      assertTrue(what + " value", Arrays.equals(a.get().getValue(), b.get().getValue()));
    }
  }

  @Test
  public void inMemoryAndRocksAgreeAcrossCreateOverwriteDeleteMidChainAndUnwind() {
    // K1: created at tx2 (0x0A), 0x0A -> 0x0B at tx5, deleted at tx9.
    put(2, K1, DomainValue.tombstone(), val(0x0A));
    put(5, K1, val(0x0A), val(0x0B));
    put(9, K1, val(0x0B), DomainValue.tombstone());
    // K2 (prefix-colliding): created at tx3 (0x21), 0x21 -> 0x22 at tx7.
    put(3, K2, DomainValue.tombstone(), val(0x21));
    put(7, K2, val(0x21), val(0x22));
    // K3: mid-chain -- existed as 0x30 before coverage; first captured change at tx6 (0x30->0x31).
    put(6, K3, val(0x30), val(0x31));

    for (byte[] k : List.of(K1, K2, K3)) {
      assertParity(k, 12);
    }

    // Fork unwind at tx6: drops K1@9, K2@7, K3@6. K1 reverts to 0x0B (pre-value of tx9), K2 to
    // 0x21 (pre-value of tx7). K3's only canonical history row is dropped, so latest is removed
    // instead of retained as an unanchored latest-only value. Both stores must agree.
    mem.unwind(6);
    rocks.unwind(6);
    for (byte[] k : List.of(K1, K2, K3)) {
      assertParity(k, 12);
    }
    // spot-check the restored values are exactly the anchored pre-values, not the dropped ones.
    assertTrue(Arrays.equals(new byte[] {0x0B}, mem.latest(DOMAIN, K1).get().getValue()));
    assertTrue(Arrays.equals(new byte[] {0x21}, mem.latest(DOMAIN, K2).get().getValue()));
    assertFalse(mem.latest(DOMAIN, K3).isPresent());
  }

  private static void deleteRecursively(File f) {
    File[] children = f.listFiles();
    if (children != null) {
      for (File c : children) {
        deleteRecursively(c);
      }
    }
    f.delete();
  }
}
