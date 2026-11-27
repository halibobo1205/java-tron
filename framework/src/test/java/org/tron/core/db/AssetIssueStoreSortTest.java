package org.tron.core.db;

import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.core.capsule.AssetIssueCapsule;
import org.tron.core.db2.ISession;
import org.tron.core.db2.core.Chainbase;
import org.tron.core.db2.core.Snapshot;
import org.tron.core.store.AssetIssueV2Store;
import org.tron.protos.contract.AssetIssueContractOuterClass.AssetIssueContract;

public class AssetIssueStoreSortTest extends BaseMethodTest {

  private AssetIssueV2Store assetIssueV2Store;

  @Override
  protected void afterInit() {
    assetIssueV2Store = chainBaseManager.getAssetIssueV2Store();
  }

  private void put(String id, String name, long order) {
    AssetIssueCapsule asset = new AssetIssueCapsule(AssetIssueContract.newBuilder()
        .setId(id)
        .setName(ByteString.copyFromUtf8(name))
        .setOrder(order)
        .build());
    assetIssueV2Store.put(asset.createDbV2Key(), asset);
  }

  private List<String> pageIds(long offset, long limit) {
    return assetIssueV2Store.getAssetIssuesPaginated(offset, limit).stream()
        .map(AssetIssueCapsule::getId)
        .collect(toList());
  }

  @Test
  public void paginated_ordersByNameThenLexicographicId() {
    put("1000000", "BBB", 0L);
    put("9999999", "AAA", 0L);
    put("10000000", "AAA", 5L);

    assertEquals(Arrays.asList("10000000", "9999999", "1000000"), pageIds(0, 3));
  }

  @Test
  public void paginated_keepsSameNamePagesAcrossSnapshotFlush() {
    Chainbase db = (Chainbase) assetIssueV2Store.getRevokingDB();
    Snapshot root = db.getHead().getRoot();
    assertSame(root, db.getHead());
    put("1000001", "AAA", 0L);

    try (ISession ignored = dbManager.getRevokingStore().buildSession()) {
      assertNotSame(root, db.getHead());
      put("1000002", "AAA", 0L);

      assertEquals(singletonList("1000001"), pageIds(0, 1));
      assertEquals(singletonList("1000002"), pageIds(1, 1));
      root.merge(db.getHead());
    }

    assertSame(root, db.getHead());
    assertEquals(singletonList("1000001"), pageIds(0, 1));
    assertEquals(singletonList("1000002"), pageIds(1, 1));
  }
}
