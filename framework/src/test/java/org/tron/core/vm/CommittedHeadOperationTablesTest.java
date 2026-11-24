package org.tron.core.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Test;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.db2.view.HeadLease;
import org.tron.core.db2.view.HeadView;
import org.tron.core.db2.view.HeadViewRegistry;
import org.tron.core.db2.view.QueryViewScope;
import org.tron.core.vm.config.VMConfig;
import org.tron.protos.Protocol.BlockHeader;

public class CommittedHeadOperationTablesTest {

  @After
  public void clearSnapshot() {
    VMConfig.clearLocalSnapshot();
    OperationRegistry.prepareAndGetTable(true);
    OperationRegistry.prepareAndGetTable(false);
  }

  @Test
  public void everyConfigurationMatchesTheHeadAdjustment() throws IllegalAccessException {
    VMConfig.clearLocalSnapshot();
    OperationRegistry.prepareCommittedHeadTables();
    for (int key = 0; key < 32; key++) {
      assertMatchesHeadAdjustment("flags " + key, snapshot(key));
    }
    VMConfig.Snapshot all = new VMConfig.Snapshot();
    for (Field flag : booleanFlags()) {
      VMConfig.Snapshot single = new VMConfig.Snapshot();
      flag.setBoolean(single, true);
      flag.setBoolean(all, true);
      assertMatchesHeadAdjustment(flag.getName(), single);
    }
    assertMatchesHeadAdjustment("all flags", all);
  }

  @Test
  public void committedHeadScopeUsesItsOwnTable() {
    HeadLease lease = lease();
    VMConfig.setLocalSnapshot(snapshot(0));
    JumpTable legacyConstant = OperationRegistry.prepareAndGetTable(true);
    try (QueryViewScope ignored = QueryViewScope.bind(lease)) {
      JumpTable prepared = OperationRegistry.prepareAndGetTable(true);
      assertNotSame(legacyConstant, prepared);
      assertSame(prepared, OperationRegistry.getTable(true));
      assertSame(OperationRegistry.getTable(false), OperationRegistry.prepareAndGetTable(false));
      Operation voteWitness = prepared.get(Op.VOTEWITNESS);

      VMConfig.setLocalSnapshot(snapshot(0xF));
      OperationRegistry.adjustVoteWitness(legacyConstant);
      assertSame(voteWitness, prepared.get(Op.VOTEWITNESS));
    } finally {
      lease.close();
    }
  }

  private static void assertMatchesHeadAdjustment(String label, VMConfig.Snapshot snapshot) {
    VMConfig.setLocalSnapshot(snapshot);
    JumpTable constantCall = OperationRegistry.prepareAndGetTable(true);
    JumpTable transaction = OperationRegistry.prepareAndGetTable(false);
    JumpTable committed = OperationRegistry.CommittedHeadTables.select();
    for (int op = 0; op < 256; op++) {
      String where = label + " op " + op;
      Operation expected = constantCall.get(op);
      Operation actual = committed.get(op);
      if (expected == transaction.get(op)) {
        assertSame(where, expected, actual);
      } else {
        assertNotSame(where, expected, actual);
        assertEquals(where, expected.getOpcode(), actual.getOpcode());
        assertEquals(where, expected.getRequire(), actual.getRequire());
        assertEquals(where, expected.getRet(), actual.getRet());
        assertEquals(where, expected.isEnabled(), actual.isEnabled());
      }
    }
  }

  private static List<Field> booleanFlags() {
    return Arrays.stream(VMConfig.Snapshot.class.getFields())
        .filter(field -> field.getType() == boolean.class)
        .collect(Collectors.toList());
  }

  private static HeadLease lease() {
    HeadViewRegistry registry = new HeadViewRegistry(1);
    long generation = registry.currentGeneration();
    registry.publish(new HeadView(new BlockId(Sha256Hash.ZERO_HASH, 1),
        BlockHeader.getDefaultInstance(), Collections.emptyMap()), generation);
    registry.markReady();
    return registry.acquire();
  }

  private static VMConfig.Snapshot snapshot(int key) {
    VMConfig.Snapshot snapshot = new VMConfig.Snapshot();
    snapshot.allowHigherLimitForMaxCpuTimeOfOneTx = (key & 1) != 0;
    snapshot.allowTvmOsaka = (key & 2) != 0;
    snapshot.allowEnergyAdjustment = (key & 4) != 0;
    snapshot.allowTvmSelfdestructRestriction = (key & 8) != 0;
    snapshot.allowTvmVote = (key & 16) != 0;
    return snapshot;
  }
}
