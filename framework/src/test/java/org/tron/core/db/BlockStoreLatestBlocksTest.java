package org.tron.core.db;

import static org.junit.Assert.assertEquals;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.db2.ISession;
import org.tron.core.db2.core.Chainbase;
import org.tron.core.db2.core.SnapshotManager;

public class BlockStoreLatestBlocksTest extends BaseMethodTest {

  private SnapshotManager snapshots;
  private BlockStore blocks;

  @Override
  protected void afterInit() {
    snapshots = context.getBean(SnapshotManager.class);
    snapshots.enable();
    while (snapshots.size() != 0) {
      snapshots.pop();
    }
    blocks = chainBaseManager.getBlockStore();
  }

  @Test
  public void latestBlocksStayDistinctWhenTheRootAlreadyHoldsAWalkedLayer() {
    putBlock(1);
    putBlock(2);
    BlockCapsule third = newBlock(3);
    try (ISession session = snapshots.buildSession()) {
      blocks.put(third.getBlockId().getBytes(), third);
      session.commit();
    }
    ((Chainbase) blocks.getRevokingDB()).getHead().getRoot()
        .put(third.getBlockId().getBytes(), third.getData());

    assertEquals(Arrays.asList(1L, 2L, 3L), latestNumbers(3));
  }

  @Test
  public void latestBlocksAreTrimmedToTheRequestedCount() {
    for (long number = 1; number <= 3; number++) {
      putBlock(number);
    }
    for (long number = 4; number <= 5; number++) {
      try (ISession session = snapshots.buildSession()) {
        putBlock(number);
        session.commit();
      }
    }

    assertEquals(Arrays.asList(3L, 4L, 5L), latestNumbers(3));
  }

  private List<Long> latestNumbers(long count) {
    return blocks.getBlockByLatestNum(count).stream()
        .map(BlockCapsule::getNum).collect(Collectors.toList());
  }

  private void putBlock(long number) {
    BlockCapsule block = newBlock(number);
    blocks.put(block.getBlockId().getBytes(), block);
  }

  private static BlockCapsule newBlock(long number) {
    return new BlockCapsule(number, Sha256Hash.ZERO_HASH, number, ByteString.EMPTY);
  }
}
