package org.tron.core.vm.program;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.tron.common.runtime.vm.DataWord;
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
}
