package org.tron.common.application;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import org.junit.Test;
import org.mockito.InOrder;
import org.tron.common.utils.ReflectUtils;
import org.tron.core.ChainBaseManager;
import org.tron.core.config.args.Args;
import org.tron.core.consensus.ConsensusService;
import org.tron.core.db.Manager;
import org.tron.core.net.TronNetService;
import org.tron.core.services.event.EventService;

public class ApplicationImplTest {

  @Test
  public void shutdownStopsBlockSourcesBeforeServicesAndDatabase() {
    boolean previousP2pDisable = Args.getInstance().isP2pDisable();
    boolean previousSolidityNode = Args.getInstance().isSolidityNode();
    try {
      Args.getInstance().setP2pDisable(false);
      Args.getInstance().setSolidityNode(false);

      ApplicationImpl application = new ApplicationImpl();
      ServiceContainer services = mock(ServiceContainer.class);
      EventService eventService = mock(EventService.class);
      TronNetService tronNetService = mock(TronNetService.class);
      Manager dbManager = mock(Manager.class);
      ConsensusService consensusService = mock(ConsensusService.class);

      ReflectUtils.setFieldValue(application, "services", services);
      ReflectUtils.setFieldValue(application, "eventService", eventService);
      ReflectUtils.setFieldValue(application, "tronNetService", tronNetService);
      ReflectUtils.setFieldValue(application, "dbManager", dbManager);
      ReflectUtils.setFieldValue(application, "chainBaseManager", mock(ChainBaseManager.class));
      ReflectUtils.setFieldValue(application, "consensusService", consensusService);

      application.shutdown();

      InOrder order = inOrder(
          consensusService, tronNetService, services, eventService, dbManager);
      order.verify(consensusService).stop();
      order.verify(tronNetService).close();
      order.verify(services).stop();
      order.verify(eventService).close();
      order.verify(dbManager).close();
    } finally {
      Args.getInstance().setP2pDisable(previousP2pDisable);
      Args.getInstance().setSolidityNode(previousSolidityNode);
    }
  }
}
