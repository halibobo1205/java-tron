package org.tron.core.services.jsonrpc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Test;
import org.mockito.Answers;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.runtime.InternalTransaction;
import org.tron.common.runtime.ProgramResult;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.StorageUtils;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.query.QueryContext;
import org.tron.core.archive.query.QueryContextHolder;
import org.tron.core.archive.reader.ArchiveReadResult;
import org.tron.core.archive.reader.ArchiveStatePoint;
import org.tron.core.archive.reader.ArchiveStateReader;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.vm.OperationRegistry;
import org.tron.core.vm.VM;
import org.tron.core.vm.archive.ArchiveRepositoryAdapter;
import org.tron.core.vm.config.ConfigLoader;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.invoke.ProgramInvokeImpl;
import org.tron.core.vm.repository.Repository;
import org.tron.protos.Protocol;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract;

public class HistoricalStorageForkBoundaryTest {

  private static final long FORK_BLOCK = 4_727_890L;
  private static final long ENERGY_FEE = 100L;
  private static final long INITIAL_VALUE = 7L;
  private static final byte[] PARENT = Hex.decode("410000000000000000000000000000000000000042");
  private static final byte[] CHILD = Hex.decode("410000000000000000000000000000000000000043");
  // Warm slot zero, DELEGATECALL the reverting writer, then return the parent's slot zero.
  private static final byte[] PARENT_CODE = Hex.decode(
      "600054506000600060006000730000000000000000000000000000000000000043"
          + "620186a0f45060005460005260206000f3");
  private static final byte[] CHILD_CODE = Hex.decode("600160005560006000fd");

  @Test
  public void blockEndBeforeForkSharesRevertedDelegateStorage() throws Exception {
    assertBoundary(ArchiveStatePoint.blockEnd(FORK_BLOCK - 1L, new byte[32], 1L),
        FORK_BLOCK - 1L, false, 1L);
  }

  @Test
  public void blockEndAtForkDiscardsRevertedDelegateStorage() throws Exception {
    assertBoundary(ArchiveStatePoint.blockEnd(FORK_BLOCK, new byte[32], 1L),
        FORK_BLOCK, true, 7L);
  }

  @Test
  public void txBeforeAtForkStillSharesRevertedDelegateStorage() throws Exception {
    assertBoundary(ArchiveStatePoint.txBefore(FORK_BLOCK, new byte[32], 1L),
        FORK_BLOCK - 1L, false, 1L);
  }

  @Test
  public void txBeforeAfterForkDiscardsRevertedDelegateStorage() throws Exception {
    assertBoundary(ArchiveStatePoint.txBefore(FORK_BLOCK + 1L, new byte[32], 1L),
        FORK_BLOCK, true, 7L);
  }

  private static void assertBoundary(ArchiveStatePoint point, long expectedHeader,
      boolean expectedFork, long expectedValue) throws Exception {
    CommonParameter parameters = CommonParameter.getInstance();
    long savedForkBlock = parameters.getBlockNumForEnergyLimit();
    boolean savedLegacyFork = CommonParameter.ENERGY_LIMIT_HARD_FORK;
    boolean savedDisable = ConfigLoader.disable;
    boolean savedTrace = VMConfig.vmTrace();
    Field globalField = VMConfig.class.getDeclaredField("globalSnapshot");
    globalField.setAccessible(true);
    VMConfig.Snapshot savedGlobal = (VMConfig.Snapshot) globalField.get(null);
    boolean savedGlobalFork = savedGlobal.energyLimitHardFork;
    try (VMConfig.LocalSnapshotScope outer = VMConfig.preserveLocalSnapshot()) {
      try {
        parameters.setBlockNumForEnergyLimit(FORK_BLOCK);
        ConfigLoader.disable = false;
        long liveBlock = expectedFork ? FORK_BLOCK - 1L : FORK_BLOCK;
        ArchiveStateReader liveReader = readerAt(
            ArchiveStatePoint.blockEnd(liveBlock, new byte[32], 1L));
        ConfigLoader.load(propertiesOf(liveReader), false);
        assertEquals(!expectedFork, VMConfig.getEnergyLimitHardFork());
        assertEquals(!expectedFork, StorageUtils.getEnergyLimitHardFork());

        ArchiveStateReader reader = readerAt(point);
        HistoricalArchiveVmDynamicProperties properties = propertiesOf(reader);
        assertEquals(expectedHeader, properties.getLatestBlockHeaderNumber());
        assertTrue(properties.supportVM());
        try (VMConfig.LocalSnapshotScope historical = VMConfig.preserveLocalSnapshot()) {
          ConfigLoader.load(properties, true);
          assertEquals(expectedFork, VMConfig.getEnergyLimitHardFork());
          assertEquals(!expectedFork, StorageUtils.getEnergyLimitHardFork());
          assertExecution(reader, properties, point.getBlockNum(), expectedValue);
          assertEquals(!expectedFork, StorageUtils.getEnergyLimitHardFork());
        }
        assertEquals("historical snapshot must not replace the live snapshot",
            !expectedFork, VMConfig.getEnergyLimitHardFork());
      } finally {
        // ConfigLoader's non-isolated load also mutates the previous global fork field.
        savedGlobal.energyLimitHardFork = savedGlobalFork;
        VMConfig.setGlobalSnapshot(savedGlobal);
        VMConfig.setVmTrace(savedTrace);
        CommonParameter.ENERGY_LIMIT_HARD_FORK = savedLegacyFork;
        parameters.setBlockNumForEnergyLimit(savedForkBlock);
        ConfigLoader.disable = savedDisable;
      }
    }
  }

  private static void assertExecution(ArchiveStateReader reader,
      HistoricalArchiveVmDynamicProperties properties, long blockNum, long expectedValue)
      throws Exception {
    QueryContext query = new QueryContext(ArchiveQueryLimits.builder()
        .maxVmSteps(1_000L).maxVmOverlayBytes(1_048_576L).build());
    try (QueryContextHolder.Scope ignored = QueryContextHolder.attach(query)) {
      Repository repository = new ArchiveRepositoryAdapter(reader, properties);
      ProgramInvokeImpl invoke = new ProgramInvokeImpl(PARENT, PARENT, PARENT,
          repository.getBalance(PARENT), 0L, 0L, 0L, new byte[0], new byte[32], PARENT,
          1_500_000_000L, blockNum, repository, 0L, Long.MAX_VALUE, 1_000_000L);
      invoke.setConstantCall();
      Program program = new Program(PARENT_CODE, PARENT, invoke, new InternalTransaction(
          Protocol.Transaction.getDefaultInstance(), InternalTransaction.TrxType.TRX_UNKNOWN_TYPE));
      program.setRootTransactionId(new byte[32]);
      VM.play(program, OperationRegistry.getTable());

      ProgramResult result = program.getResult();
      query.throwIfTerminated();
      assertNull(result.getException());
      assertFalse(result.isRevert());
      assertEquals(1, result.getInternalTransactions().size());
      assertTrue("the delegated writer must execute and revert",
          result.getInternalTransactions().get(0).isRejected());
      assertArrayEquals(PARENT,
          result.getInternalTransactions().get(0).getTransferToAddress());
      assertTrue("REVERT must refund unused delegated energy instead of consuming it all",
          result.getEnergyUsed() < 100_000L);
      assertArrayEquals("independent fork-specific return oracle",
          word(expectedValue), result.getHReturn());
      assertEquals(new DataWord(expectedValue),
          repository.getStorageValue(PARENT, DataWord.ZERO()));
      assertTrue(query.getVmSteps() > 0L);
      assertTrue(query.getVmOverlayBytes() > 0L);
      assertArrayEquals(word(INITIAL_VALUE), reader.getStorage(PARENT, word(0L)).getValue());
      assertEquals("a fresh request must not inherit reverted writes", new DataWord(INITIAL_VALUE),
          new ArchiveRepositoryAdapter(reader, properties)
              .getStorageValue(PARENT, DataWord.ZERO()));
    }
  }

  private static HistoricalArchiveVmDynamicProperties propertiesOf(ArchiveStateReader reader)
      throws Exception {
    return new HistoricalArchiveVmDynamicProperties(
        HistoricalArchiveVmDynamicProperties.resolveEnergyFee(reader, true), reader, true);
  }

  private static ArchiveStateReader readerAt(ArchiveStatePoint point) throws Exception {
    ArchiveStateReader reader = mock(ArchiveStateReader.class, invocation ->
        invocation.getMethod().getReturnType() == ArchiveReadResult.class
            ? ArchiveReadResult.missing() : Answers.RETURNS_DEFAULTS.answer(invocation));
    when(reader.getPoint()).thenReturn(point);
    for (String key : HistoricalArchiveVmDynamicProperties.STRICT_GENESIS_LONG_KEYS) {
      when(reader.getDynamicProperty(key.getBytes(StandardCharsets.US_ASCII)))
          .thenReturn(ArchiveReadResult.present(ByteArray.fromLong(0L)));
    }
    putProperty(reader, "MAINTENANCE_TIME_INTERVAL", 21_600_000L);
    putProperty(reader, "ALLOW_CREATION_OF_CONTRACTS", 1L);
    putProperty(reader, "ENERGY_FEE", ENERGY_FEE);
    seedContract(reader, PARENT, PARENT_CODE);
    seedContract(reader, CHILD, CHILD_CODE);
    when(reader.getStorage(any(byte[].class), any(byte[].class)))
        .thenReturn(ArchiveReadResult.missing());
    when(reader.getStorage(PARENT, word(0L)))
        .thenAnswer(invocation -> ArchiveReadResult.present(word(INITIAL_VALUE)));
    return reader;
  }

  private static void putProperty(ArchiveStateReader reader, String key, long value)
      throws Exception {
    when(reader.getDynamicProperty(key.getBytes(StandardCharsets.US_ASCII)))
        .thenReturn(ArchiveReadResult.present(ByteArray.fromLong(value)));
  }

  private static void seedContract(ArchiveStateReader reader, byte[] address, byte[] code)
      throws Exception {
    Protocol.Account account = Protocol.Account.newBuilder()
        .setAddress(ByteString.copyFrom(address)).setType(Protocol.AccountType.Contract)
        .setBalance(1_000_000L).build();
    SmartContract contract = SmartContract.newBuilder()
        .setContractAddress(ByteString.copyFrom(address)).build();
    when(reader.getAccount(address))
        .thenAnswer(invocation -> ArchiveReadResult.present(new AccountCapsule(account)));
    when(reader.getContract(address))
        .thenAnswer(invocation -> ArchiveReadResult.present(new ContractCapsule(contract)));
    when(reader.getCode(address))
        .thenAnswer(invocation -> ArchiveReadResult.present(code.clone()));
  }

  private static byte[] word(long value) {
    return new DataWord(value).getData();
  }
}
