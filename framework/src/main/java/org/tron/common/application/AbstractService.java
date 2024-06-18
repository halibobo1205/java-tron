package org.tron.common.application;

import com.google.common.base.Objects;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.tron.core.config.args.Args;


@Slf4j(topic = "service")
public abstract class AbstractService implements Service {

  protected int port;
  @Getter
  protected boolean enable;
  protected final String name = this.getClass().getSimpleName();


  @Override
  public void start() {
    try {
      innerStart();
      logger.info("{} started, listening on {}", name, port);
    } catch (Exception e) {
      logger.error("{}", name, e);
    }
  }

  @Override
  public void stop() {
    logger.info("{} shutdown...", name);
    try {
      innerStop();
    } catch (Exception e) {
      logger.warn("{}", name, e);
    }
    logger.info("{} shutdown complete", name);


  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    AbstractService that = (AbstractService) o;
    return port == that.port;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(name, port);
  }

  public abstract void innerStart() throws Exception;

  public abstract void innerStop() throws Exception;

  protected boolean isFullNode() {
    return !Args.getInstance().isSolidityNode();
  }

}
