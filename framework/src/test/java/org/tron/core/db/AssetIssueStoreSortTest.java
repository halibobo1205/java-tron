package org.tron.core.db;

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Resource;
import org.junit.Test;
import org.tron.common.BaseTest;
import org.tron.common.TestConstants;
import org.tron.core.capsule.AssetIssueCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.store.AssetIssueStore;
import org.tron.protos.contract.AssetIssueContractOuterClass.AssetIssueContract;

/**
 * Sort-behaviour regression test for {@link AssetIssueStore#getAssetIssuesPaginated(long, long)}.
 * Guards the fix that replaced the {@code ByteString} reference-comparison comparator (whose
 * secondary {@code order} branch was dead code) with a name-then-order comparator built from
 * {@code Comparator.comparing(..., unsignedLexicographicalComparator()).thenComparingLong(...)}.
 */
public class AssetIssueStoreSortTest extends BaseTest {

  @Resource
  private AssetIssueStore assetIssueStore;

  static {
    Args.setParam(new String[] {"--output-directory", dbPath()}, TestConstants.TEST_CONF);
  }

  private void put(String id, String name, long order) {
    AssetIssueContract contract = AssetIssueContract.newBuilder()
        .setId(id)
        .setName(ByteString.copyFromUtf8(name))
        .setOrder(order)
        .build();
    assetIssueStore.put(id.getBytes(), new AssetIssueCapsule(contract));
  }

  @Test
  public void paginated_ordersByNameThenOrder() {
    put("2001", "BBB", 0L);
    put("2002", "AAA", 5L); // same name as 2003, larger order -> must sort after it
    put("2003", "AAA", 0L);
    List<String> ordered = assetIssueStore.getAssetIssuesPaginated(0, 100).stream()
        .map(a -> a.getName().toStringUtf8() + "/" + a.getOrder())
        .collect(toList());
    // name ascending, then order ascending within the same name
    assertEquals(Arrays.asList("AAA/0", "AAA/5", "BBB/0"), ordered);
  }
}
