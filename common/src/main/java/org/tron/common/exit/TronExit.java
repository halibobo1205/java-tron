package org.tron.common.exit;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.tron.core.exception.TronExitException;

@Getter
@AllArgsConstructor
public class TronExit {

  private String msg;
  private TronExitException exception;
}
