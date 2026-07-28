package org.tron.core.vm.program;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.tron.common.runtime.ProgramResult;
import org.tron.protos.Protocol.Transaction.Result.contractResult;

public class VmResultCodeMapperTest {

  @Test
  public void mapsTerminalOutcomesToExactReceiptCodes() {
    ProgramResult result = new ProgramResult();
    assertEquals(contractResult.SUCCESS, VmResultCodeMapper.resultCodeOf(result));

    result.setRevert();
    assertEquals(contractResult.REVERT, VmResultCodeMapper.resultCodeOf(result));

    result = new ProgramResult();
    result.setException(new Program.OutOfTimeException("timeout"));
    assertEquals(contractResult.OUT_OF_TIME, VmResultCodeMapper.resultCodeOf(result));

    result = new ProgramResult();
    result.setException(new Program.OutOfEnergyException("energy"));
    assertEquals(contractResult.OUT_OF_ENERGY, VmResultCodeMapper.resultCodeOf(result));

    result = new ProgramResult();
    result.setRuntimeError("unclassified");
    assertEquals(contractResult.UNKNOWN, VmResultCodeMapper.resultCodeOf(result));
  }
}
