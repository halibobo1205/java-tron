package org.tron.core.actuator;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.tron.core.exception.TronError;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.contract.BalanceContract.TransferContract;
import org.tron.protos.contract.BalanceContract.WithdrawBalanceContract;

public class TransactionFactoryTest {

  @Test
  public void testRegisterForSameContractType() {
    ContractType type = ContractType.CustomContract;
    TransactionFactory.register(type, FirstActuator.class, TransferContract.class);
    TransactionFactory.register(type, FirstActuator.class, TransferContract.class);

    TronError error = assertThrows(TronError.class,
        () -> TransactionFactory.register(type, SecondActuator.class,
            WithdrawBalanceContract.class));

    assertSame(TronError.ErrCode.ACTUATOR_REGISTER, error.getErrCode());
    assertSame(FirstActuator.class, TransactionFactory.getActuator(type));
    assertSame(TransferContract.class, TransactionFactory.getContract(type));
  }

  private abstract static class FirstActuator implements Actuator {
  }

  private abstract static class SecondActuator implements Actuator {
  }
}
