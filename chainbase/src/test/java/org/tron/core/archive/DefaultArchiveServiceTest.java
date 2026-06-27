package org.tron.core.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import org.junit.Test;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.archive.txnum.ArchiveBlockRange;
import org.tron.core.archive.txnum.ArchiveTxPosition;
import org.tron.core.archive.txnum.InMemoryArchiveTxNumIndex;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.protos.Protocol.Transaction;

public class DefaultArchiveServiceTest {

  private static BlockCapsule block(long num) {
    return new BlockCapsule(num, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY);
  }

  @Test
  public void disabledServiceIsNoOp() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(false, index, context);
    assertFalse(service.isEnabled());

    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    // Disabled: nothing allocated, no context entered.
    assertFalse(context.current().isPresent());
    assertFalse(index.getBlockRange(5).isPresent());
    service.endTx();
    service.commitBlock(b);
    assertFalse(index.getBlockRange(5).isPresent());
  }

  @Test
  public void enabledEmptyBlockLifecycleCommitsRange() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    BlockCapsule b = block(5); // no transactions
    service.beginBlock(b, ArchiveSource.NORMAL);

    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    ArchiveTxPosition prepare = context.current().orElseThrow(AssertionError::new);
    assertEquals(0, prepare.getTxNum());
    assertEquals(ArchivePhase.BLOCK_PREPARE, prepare.getPhase());
    service.endTx();
    assertFalse(context.current().isPresent());

    service.beginSystemTx(b, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();

    service.commitBlock(b); // userTxCount = block.getTransactions().size() = 0
    ArchiveBlockRange range = index.getBlockRange(5).orElseThrow(AssertionError::new);
    assertEquals(0, range.getFirstTxNum());
    assertEquals(1, range.getLastTxNum());
    assertEquals(0, range.getUserTxCount());
    assertEquals(ArchiveSource.NORMAL, range.getSource());
  }

  @Test
  public void enabledUserTxEntersContextWithTxId() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    BlockCapsule b = block(7);
    TransactionCapsule tx = new TransactionCapsule(Transaction.getDefaultInstance());
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    service.endTx();

    service.beginUserTx(b, 0, tx);
    ArchiveTxPosition pos = context.current().orElseThrow(AssertionError::new);
    assertEquals(ArchivePhase.USER_TX, pos.getPhase());
    assertEquals(0, pos.getTxIndex());
    assertArrayEquals(tx.getTransactionId().getBytes(), pos.getTxId());
    service.endTx();
    assertFalse(context.current().isPresent());
  }

  @Test
  public void abortClearsContextAndDiscardsPending() {
    InMemoryArchiveTxNumIndex index = new InMemoryArchiveTxNumIndex();
    ArchiveExecutionContext context = new ArchiveExecutionContext();
    DefaultArchiveService service = new DefaultArchiveService(true, index, context);

    BlockCapsule b = block(5);
    service.beginBlock(b, ArchiveSource.NORMAL);
    service.beginSystemTx(b, ArchivePhase.BLOCK_PREPARE);
    assertTrue(context.current().isPresent());

    service.abortBlock(b);
    assertFalse(context.current().isPresent());
    assertFalse(index.getBlockRange(5).isPresent());
  }
}
