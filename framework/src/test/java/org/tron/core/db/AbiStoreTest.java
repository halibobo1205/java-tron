package org.tron.core.db;

import static org.tron.common.utils.PublicMethod.jsonStr2Abi;

import com.google.protobuf.ByteString;
import java.util.List;
import javax.annotation.Resource;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Assert;
import org.junit.Test;
import org.tron.common.BaseTest;
import org.tron.common.TestConstants;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.archive.ArchivePhase;
import org.tron.core.archive.ArchiveSource;
import org.tron.core.archive.DefaultArchiveService;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.capture.ArchiveChangeRecord;
import org.tron.core.archive.domain.ArchiveDomain;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.capsule.AbiCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.store.AbiStore;
import org.tron.protos.contract.SmartContractOuterClass;

public class AbiStoreTest extends BaseTest {

  @Resource
  private AbiStore abiStore;

  private static final byte[] contractAddr = Hex.decode(
      "41000000000000000000000000000000000000dEaD");

  private static final SmartContractOuterClass.SmartContract.ABI SOURCE_ABI = jsonStr2Abi(
      "[{\"inputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\""
          + ":\"constructor\"}]");

  static {
    Args.setParam(
        new String[]{
            "--output-directory", dbPath()
        },
        TestConstants.TEST_CONF
    );
  }

  @Test
  public void testPut() {
    abiStore.put(contractAddr, new AbiCapsule(SOURCE_ABI));
    Assert.assertEquals(abiStore.has(contractAddr), Boolean.TRUE);
  }

  @Test
  public void testGet() {
    abiStore.put(contractAddr, new AbiCapsule(SOURCE_ABI));
    AbiCapsule abiCapsule = abiStore.get(contractAddr);
    Assert.assertEquals(abiCapsule.getInstance(), SOURCE_ABI);
  }

  @Test
  public void testGetTotalAbi() {
    abiStore.put(contractAddr, new AbiCapsule(SOURCE_ABI));
    Assert.assertEquals(abiStore.getTotalABIs(), 1);
  }

  @Test
  public void archiveByteArrayPutEmitsAbiRecord() {
    DefaultArchiveService archiveService =
        new DefaultArchiveService(true, new InMemoryArchiveTemporalStore());
    BlockCapsule block = new BlockCapsule(1, Sha256Hash.ZERO_HASH, 1L, ByteString.EMPTY);
    byte[] archiveAddr = contractAddr.clone();
    archiveAddr[20] = 0x66; // keep this test isolated from other AbiStoreTest writes
    try {
      archiveService.beginBlock(block, ArchiveSource.NORMAL);
      archiveService.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
      abiStore.put(archiveAddr, SOURCE_ABI.toByteArray());
      archiveService.endTx();

      List<ArchiveChangeRecord> records = archiveService.getCaptureEngine().records();
      Assert.assertEquals(1L, records.stream()
          .filter(r -> r.getDomain() == ArchiveDomain.ABI)
          .count());
      ArchiveChangeRecord record = records.stream()
          .filter(r -> r.getDomain() == ArchiveDomain.ABI)
          .findFirst()
          .orElseThrow(AssertionError::new);
      Assert.assertTrue(record.getPrevValue().isDeleted());
      Assert.assertArrayEquals(SOURCE_ABI.toByteArray(), record.getValue().getValue());
    } finally {
      archiveService.abortBlock(block);
      archiveService.close();
      ArchiveCaptureHolder.clear();
      abiStore.delete(archiveAddr);
    }
  }
}
