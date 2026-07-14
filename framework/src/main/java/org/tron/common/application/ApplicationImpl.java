package org.tron.common.application;

import java.util.concurrent.CountDownLatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.ChainBaseManager;
import org.tron.core.config.args.Args;
import org.tron.core.consensus.ConsensusService;
import org.tron.core.db.Manager;
import org.tron.core.net.TronNetService;
import org.tron.core.services.event.EventService;
import org.tron.program.SolidityNode;

@Slf4j(topic = "app")
@Component
public class ApplicationImpl implements Application {

  @Autowired
  private ServiceContainer services;

  @Autowired
  private EventService eventService;

  @Autowired
  private TronNetService tronNetService;

  @Autowired
  private Manager dbManager;

  @Autowired
  private ChainBaseManager chainBaseManager;

  @Autowired
  private ConsensusService consensusService;

  @Autowired(required = false)
  private SolidityNode solidityNode;

  private final CountDownLatch shutdown = new CountDownLatch(1);

  /**
   * start up the app.
   */
  public void startup() {
    this.startServices();
    eventService.init();
    consensusService.start();
    if ((!Args.getInstance().isSolidityNode()) && (!Args.getInstance().isP2pDisable())) {
      tronNetService.start();
    }
  }

  @Override
  public void shutdown() {
    Throwable failure = null;
    try {
      failure = runShutdownStage(failure, "services", this::shutdownServices);
      failure = runShutdownStage(failure, "network", () -> {
        if (!Args.getInstance().isSolidityNode() && !Args.getInstance().p2pDisable) {
          tronNetService.close();
        }
      });
      failure = runShutdownStage(failure, "consensus", consensusService::stop);
      failure = runShutdownStage(failure, "events", eventService::close);
      failure = runShutdownStage(failure, "solidity node", () -> {
        if (solidityNode != null) {
          solidityNode.close();
        }
      });
      failure = runShutdownStage(failure, "database manager", dbManager::close);
    } finally {
      shutdown.countDown();
    }
    throwIfShutdownFailed(failure);
  }

  private static Throwable runShutdownStage(
      Throwable failure, String stage, Runnable action) {
    try {
      action.run();
      return failure;
    } catch (RuntimeException | Error next) {
      logger.warn("Application shutdown stage '{}' failed", stage, next);
      if (failure == null) {
        return next;
      }
      failure.addSuppressed(next);
      return failure;
    }
  }

  private static void throwIfShutdownFailed(Throwable failure) {
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
  }

  @Override
  public void startServices() {
    services.start();
  }

  @Override
  public void blockUntilShutdown() {
    try {
      shutdown.await();
    } catch (final InterruptedException e) {
      logger.debug("Interrupted, exiting", e);
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void shutdownServices() {
    services.stop();
  }

  @Override
  public Manager getDbManager() {
    return dbManager;
  }

  @Override
  public ChainBaseManager getChainBaseManager() {
    return chainBaseManager;
  }

}
