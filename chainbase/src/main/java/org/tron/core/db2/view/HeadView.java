package org.tron.core.db2.view;

import java.util.Map;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.db2.core.Chainbase;
import org.tron.core.db2.core.Snapshot;
import org.tron.core.db2.core.StoreLayers;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.BlockHeader;

public final class HeadView {

  private final BlockId blockId;
  private final Block headerOnlyBlock;
  private volatile Map<Chainbase, StoreLayers> layers;
  private volatile boolean valid = true;
  private long generation;

  public HeadView(BlockId blockId, BlockHeader header, Map<Chainbase, StoreLayers> layers) {
    this.blockId = blockId;
    this.headerOnlyBlock = Block.newBuilder().setBlockHeader(header).build();
    this.layers = layers;
  }

  public BlockId getBlockId() {
    return blockId;
  }

  public long getBlockNum() {
    return blockId.getNum();
  }

  public Block getHeaderOnlyBlock() {
    return headerOnlyBlock;
  }

  public boolean isValid() {
    return valid;
  }

  Map<Chainbase, StoreLayers> layers() {
    return layers;
  }

  long getGeneration() {
    return generation;
  }

  void setGeneration(long generation) {
    this.generation = generation;
  }

  boolean coversFlush(Chainbase db, Snapshot lastFlushed) {
    Map<Chainbase, StoreLayers> current = layers;
    if (current == null) {
      return false;
    }
    StoreLayers storeLayers = current.get(db);
    return storeLayers == null || storeLayers.contains(lastFlushed);
  }

  void invalidate() {
    valid = false;
    layers = null;
  }
}
