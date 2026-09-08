package org.tron.core.zksnark;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.application.ApplicationFactory;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.zksnark.IncrementalMerkleTreeContainer;
import org.tron.common.zksnark.MerkleContainer;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.IncrementalMerkleTreeCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;
import org.tron.core.db2.ISession;
import org.tron.core.db2.core.SnapshotManager;
import org.tron.core.exception.ItemNotFoundException;
import org.tron.core.store.IncrementalMerkleTreeStore;
import org.tron.core.store.TreeBlockIndexStore;
import org.tron.protos.contract.ShieldContract.PedersenHash;

public class MerklePersistenceTest extends BaseMethodTest {

  @Override
  protected void beforeContext() {
    Args.getInstance().allowShieldedTransactionApi = true;
  }

  @Test
  public void savedRootsSurviveAbortReorgAndDatabaseRestart() throws Exception {
    MerkleContainer container = dbManager.getMerkleContainer();
    SnapshotManager snapshots = context.getBean(SnapshotManager.class);
    snapshots.enable();
    assertEquals(0, snapshots.size());

    IncrementalMerkleTreeCapsule empty = new IncrementalMerkleTreeCapsule();
    byte[] emptyRoot = empty.toMerkleTreeContainer().getMerkleTreeKey();
    assertArrayEquals(IncrementalMerkleTreeContainer.emptyRoot().getContent().toByteArray(),
        emptyRoot);
    container.setCurrentMerkle(empty.toMerkleTreeContainer());
    container.saveCurrentMerkleTreeAsBestMerkleTree(0L);
    assertPersisted(0L, empty, emptyRoot);

    IncrementalMerkleTreeCapsule first = empty.deepCopy();
    first.toMerkleTreeContainer().append(leaf(17));
    byte[] firstRoot = first.toMerkleTreeContainer().getMerkleTreeKey();
    assertFalse(Arrays.equals(emptyRoot, firstRoot));
    try (ISession session = snapshots.buildSession()) {
      container.setCurrentMerkle(first.toMerkleTreeContainer());
      container.saveCurrentMerkleTreeAsBestMerkleTree(1L);
      session.commitToRoot();
    }
    assertPersisted(1L, first, firstRoot);

    IncrementalMerkleTreeCapsule orphan = first.deepCopy();
    orphan.toMerkleTreeContainer().append(leaf(18));
    byte[] orphanRoot = orphan.toMerkleTreeContainer().getMerkleTreeKey();
    try (ISession ignored = snapshots.buildSession()) {
      container.setCurrentMerkle(orphan.toMerkleTreeContainer());
      container.saveCurrentMerkleTreeAsBestMerkleTree(2L);
      assertPersisted(2L, orphan, orphanRoot);
    }
    assertPersisted(1L, first, firstRoot);
    assertAbsent(2L, orphanRoot);

    try (ISession session = snapshots.buildSession()) {
      container.setCurrentMerkle(orphan.toMerkleTreeContainer());
      container.saveCurrentMerkleTreeAsBestMerkleTree(2L);
      session.commit();
    }
    snapshots.pop();
    assertPersisted(1L, first, firstRoot);
    assertAbsent(2L, orphanRoot);

    IncrementalMerkleTreeCapsule replacement = first.deepCopy();
    replacement.toMerkleTreeContainer().append(leaf(19));
    byte[] replacementRoot = replacement.toMerkleTreeContainer().getMerkleTreeKey();
    assertFalse(Arrays.equals(orphanRoot, replacementRoot));
    try (ISession session = snapshots.buildSession()) {
      container.setCurrentMerkle(replacement.toMerkleTreeContainer());
      container.saveCurrentMerkleTreeAsBestMerkleTree(2L);
      session.commitToRoot();
    }
    assertPersisted(2L, replacement, replacementRoot);

    context.close();
    context = null;
    context = new TronApplicationContext(DefaultConfig.class);
    appT = ApplicationFactory.create(context);
    dbManager = context.getBean(Manager.class);
    chainBaseManager = context.getBean(ChainBaseManager.class);

    assertPersisted(2L, replacement, replacementRoot);
    assertArrayEquals(firstRoot, context.getBean(TreeBlockIndexStore.class).get(1L));
    assertNull(context.getBean(IncrementalMerkleTreeStore.class)
        .getRevokingDB().getUnchecked(orphanRoot));
  }

  private void assertPersisted(long height, IncrementalMerkleTreeCapsule expected, byte[] root)
      throws Exception {
    MerkleContainer container = dbManager.getMerkleContainer();
    assertArrayEquals(expected.getData(), container.getCurrentMerkle().getTreeCapsule().getData());
    assertArrayEquals(expected.getData(), container.getBestMerkle().getTreeCapsule().getData());
    assertArrayEquals(root, context.getBean(TreeBlockIndexStore.class).get(height));
    assertArrayEquals(expected.getData(), context.getBean(IncrementalMerkleTreeStore.class)
        .getRevokingDB().getUnchecked(root));
  }

  private void assertAbsent(long height, byte[] root) {
    ItemNotFoundException failure = assertThrows(ItemNotFoundException.class,
        () -> context.getBean(TreeBlockIndexStore.class).get(height));
    assertTrue(failure.getMessage().contains(Long.toString(height)));
    assertNull(context.getBean(IncrementalMerkleTreeStore.class)
        .getRevokingDB().getUnchecked(root));
  }

  private static PedersenHash leaf(int value) {
    byte[] bytes = new byte[32];
    bytes[0] = (byte) value;
    return PedersenHash.newBuilder().setContent(ByteString.copyFrom(bytes)).build();
  }
}
