package org.tron.common.runtime;

import java.util.List;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.tron.core.actuator.Actuator;
import org.tron.core.actuator.Actuator2;
import org.tron.core.actuator.ActuatorCreator;
import org.tron.core.actuator.VMActuator;
import org.tron.core.db.TransactionContext;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.vm.program.VmResultCodeMapper;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;

@Slf4j(topic = "VM")
public class RuntimeImpl implements Runtime {

  TransactionContext context;
  private List<Actuator> actuatorList = null;

  @Getter
  private Actuator2 actuator2 = null;

  @Override
  public void execute(TransactionContext context)
      throws ContractValidateException, ContractExeException {
    this.context = context;

    ContractType contractType = context.getTrxCap().getInstance().getRawData().getContract(0)
        .getType();
    switch (contractType.getNumber()) {
      case ContractType.TriggerSmartContract_VALUE:
      case ContractType.CreateSmartContract_VALUE:
        actuator2 = new VMActuator(context.isStatic());
        break;
      default:
        actuatorList = ActuatorCreator.getINSTANCE().createActuator(context.getTrxCap());
    }
    if (actuator2 != null) {
      actuator2.validate(context);
      actuator2.execute(context);
    } else {
      for (Actuator act : actuatorList) {
        act.validate();
        act.execute(context.getProgramResult().getRet());
      }
    }

    context.getProgramResult().setResultCode(
        VmResultCodeMapper.resultCodeOf(context.getProgramResult()));

  }

  @Override
  public ProgramResult getResult() {
    return context.getProgramResult();
  }

  @Override
  public String getRuntimeError() {
    return context.getProgramResult().getRuntimeError();
  }
}
