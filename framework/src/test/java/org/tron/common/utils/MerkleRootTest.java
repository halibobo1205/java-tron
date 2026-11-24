package org.tron.common.utils;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.Assert;
import org.junit.Test;

public class MerkleRootTest {

  private static final Sha256Hash ROOT_OF_10000 = Sha256Hash.wrap(ByteString.fromHex(
      "6cb38b4f493db8bacf26123cd4253bbfc530c708b97b3747e782f64097c3c482"));

  @Test
  public void cancellationCheckDoesNotChangeRoot() {
    AtomicInteger checks = new AtomicInteger();

    Sha256Hash root = MerkleRoot.root(hashes(10000), () -> {
      checks.incrementAndGet();
    });

    Assert.assertEquals(ROOT_OF_10000, root);
    Assert.assertEquals(ROOT_OF_10000, MerkleRoot.root(hashes(10000)));
    Assert.assertTrue(checks.get() > 1);
  }

  @Test
  public void cancellationCheckRunsForEmptyList() {
    AtomicInteger checks = new AtomicInteger();

    Sha256Hash root = MerkleRoot.root(Collections.emptyList(), () -> {
      checks.incrementAndGet();
    });

    Assert.assertEquals(Sha256Hash.ZERO_HASH, root);
    Assert.assertEquals(1, checks.get());
  }

  @Test
  public void cancellationCheckStopsComputation() {
    List<Sha256Hash> hashes = hashes(10000);
    AtomicInteger total = new AtomicInteger();
    MerkleRoot.root(hashes, () -> {
      total.incrementAndGet();
    });

    for (int failAt : new int[] {1, 2, total.get() / 2, total.get()}) {
      AtomicInteger checks = new AtomicInteger();
      Assert.assertThrows(CancellationException.class, () -> MerkleRoot.root(hashes, () -> {
        if (checks.incrementAndGet() == failAt) {
          throw new CancellationException();
        }
      }));
      Assert.assertEquals("computation continued after cancellation", failAt, checks.get());
    }
  }

  private static List<Sha256Hash> hashes(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> Sha256Hash.of(("byte1-" + i).getBytes(StandardCharsets.UTF_8)))
        .collect(Collectors.toList());
  }
}
