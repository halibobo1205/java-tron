package org.tron.core.vm.program;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import org.tron.common.runtime.vm.DataWord;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.vm.program.invoke.ProgramInvoke;
import org.tron.core.vm.repository.Repository;

public class ContractStateArchiveTest {

  @Test
  public void historicalArchiveMarkerDelegatesToWrappedRepository() {
    Repository repository = mock(Repository.class);
    when(repository.isHistoricalArchive()).thenReturn(true);
    ProgramInvoke invoke = mock(ProgramInvoke.class);
    when(invoke.getContractAddress()).thenReturn(DataWord.ZERO);
    when(invoke.getDeposit()).thenReturn(repository);

    ContractState state = new ContractState(invoke);

    assertTrue(state.isHistoricalArchive());
  }

  @Test
  public void historicalArchiveExtensionsDelegateToWrappedRepository() {
    byte[] address = new byte[]{0x41, 0x01};
    Map<String, Long> assets = Collections.singletonMap("1000001", 7L);
    BigInteger witnessVi = BigInteger.valueOf(123L);
    AccountCapsule owner = mock(AccountCapsule.class);
    AccountCapsule inheritor = mock(AccountCapsule.class);
    Repository repository = mock(Repository.class);
    when(repository.getTokenBalances(address)).thenReturn(assets);
    when(repository.getWitnessVi(9L, address)).thenReturn(witnessVi);
    ProgramInvoke invoke = mock(ProgramInvoke.class);
    when(invoke.getContractAddress()).thenReturn(DataWord.ZERO);
    when(invoke.getDeposit()).thenReturn(repository);
    ContractState state = new ContractState(invoke);

    assertEquals(assets, state.getTokenBalances(address));
    assertEquals(witnessVi, state.getWitnessVi(9L, address));
    state.transferFrozenV2UsageForSelfDestruct(owner, inheritor, 11L);

    verify(repository).transferFrozenV2UsageForSelfDestruct(owner, inheritor, 11L);
  }
}
