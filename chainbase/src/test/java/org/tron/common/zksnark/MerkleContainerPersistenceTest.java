package org.tron.common.zksnark;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.tron.core.capsule.IncrementalMerkleTreeCapsule;
import org.tron.core.exception.ZksnarkException;
import org.tron.core.store.IncrementalMerkleTreeStore;
import org.tron.core.store.TreeBlockIndexStore;

public class MerkleContainerPersistenceTest {

  private static final byte[] LAST_TREE = "LAST_TREE".getBytes(StandardCharsets.US_ASCII);

  private final IncrementalMerkleTreeStore trees = mock(IncrementalMerkleTreeStore.class);
  private final TreeBlockIndexStore index = mock(TreeBlockIndexStore.class);
  private final IncrementalMerkleTreeContainer tree = mock(IncrementalMerkleTreeContainer.class);
  private final IncrementalMerkleTreeCapsule capsule = new IncrementalMerkleTreeCapsule();
  private final MerkleContainer container = spy(MerkleContainer.createInstance(trees, index));
  private final byte[] root = new byte[32];

  @Before
  public void setUp() throws Exception {
    doReturn(tree).when(container).getCurrentMerkle();
    when(tree.getTreeCapsule()).thenReturn(capsule);
    when(tree.getMerkleTreeKey()).thenReturn(root);
  }

  @Test
  public void saveComputesRootOnceAndPreservesWriteOrder() throws Exception {
    container.saveCurrentMerkleTreeAsBestMerkleTree(7L);

    InOrder order = inOrder(trees, tree, index);
    order.verify(trees).put(aryEq(LAST_TREE), same(capsule));
    order.verify(tree).getMerkleTreeKey();
    order.verify(index).put(eq(7L), aryEq(root));
    order.verify(trees).put(aryEq(root), same(capsule));
    verify(tree, times(1)).getMerkleTreeKey();
    verifyNoMoreInteractions(trees, index);
  }

  @Test
  public void saveDoesNotReuseRootAcrossInvocations() throws Exception {
    byte[] nextRoot = new byte[32];
    nextRoot[0] = 1;
    when(tree.getMerkleTreeKey()).thenReturn(root, nextRoot);

    container.saveCurrentMerkleTreeAsBestMerkleTree(7L);
    container.saveCurrentMerkleTreeAsBestMerkleTree(8L);

    verify(tree, times(2)).getMerkleTreeKey();
    verify(index).put(eq(7L), aryEq(root));
    verify(index).put(eq(8L), aryEq(nextRoot));
    verify(trees).put(aryEq(root), same(capsule));
    verify(trees).put(aryEq(nextRoot), same(capsule));
  }

  @Test
  public void standaloneSetBestMerkleDoesNotAddARootEntry() throws Exception {
    container.setBestMerkle(7L, tree);

    InOrder order = inOrder(trees, tree, index);
    order.verify(trees).put(aryEq(LAST_TREE), same(capsule));
    order.verify(tree).getMerkleTreeKey();
    order.verify(index).put(eq(7L), aryEq(root));
    verify(tree, times(1)).getMerkleTreeKey();
    verifyNoMoreInteractions(trees, index);
  }

  @Test
  public void rootFailureKeepsExistingWriteOrdering() throws Exception {
    ZksnarkException failure = new ZksnarkException("injected root failure");
    when(tree.getMerkleTreeKey()).thenThrow(failure);

    assertSame(failure, assertThrows(ZksnarkException.class,
        () -> container.saveCurrentMerkleTreeAsBestMerkleTree(7L)));

    InOrder order = inOrder(trees, tree);
    order.verify(trees).put(aryEq(LAST_TREE), same(capsule));
    order.verify(tree).getMerkleTreeKey();
    verifyNoMoreInteractions(trees);
    verifyNoInteractions(index);
  }

  @Test
  public void lastTreeWriteFailureDoesNotComputeRoot() throws Exception {
    IllegalStateException failure = new IllegalStateException("injected last-tree write failure");
    doThrow(failure).when(trees).put(aryEq(LAST_TREE), same(capsule));

    assertSame(failure, assertThrows(IllegalStateException.class,
        () -> container.saveCurrentMerkleTreeAsBestMerkleTree(7L)));

    verify(tree, never()).getMerkleTreeKey();
    verifyNoInteractions(index);
  }

  @Test
  public void indexWriteFailureDoesNotWriteRootEntry() throws Exception {
    IllegalStateException failure = new IllegalStateException("injected index write failure");
    doThrow(failure).when(index).put(eq(7L), any(byte[].class));

    assertSame(failure, assertThrows(IllegalStateException.class,
        () -> container.saveCurrentMerkleTreeAsBestMerkleTree(7L)));

    verify(tree, times(1)).getMerkleTreeKey();
    verify(trees).put(aryEq(LAST_TREE), same(capsule));
    verifyNoMoreInteractions(trees);
  }
}
