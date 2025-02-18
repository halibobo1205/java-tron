package org.tron.core.services.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.logsfilter.EventPluginLoader;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.db.Manager;
import org.tron.core.services.event.bo.BlockEvent;

@Slf4j(topic = "event")
@Component
public class HistoryEventService {

  private EventPluginLoader instance = EventPluginLoader.getInstance();

  @Autowired
  private BlockEventGet blockEventGet;

  @Autowired
  private SolidEventService solidEventService;

  @Autowired
  private RealtimeEventService realtimeEventService;

  @Autowired
  private BlockEventLoad blockEventLoad;

  @Autowired
  private Manager manager;

  private volatile boolean isRunning;

  public void init() {
    if (instance.getStartSyncBlockNum() <= 0) {
      initEventService(manager.getChainBaseManager().getHeadBlockId());
      return;
    }

    isRunning = true;

    new Thread(() -> syncEvent()).start();

    logger.info("History event service start.");
  }

  public void close() {
    isRunning = false;
  }

  private void syncEvent() {
    try {
      long tmp = instance.getStartSyncBlockNum();
      long endNum = manager.getDynamicPropertiesStore().getLatestSolidifiedBlockNum();
      while (tmp <= endNum && isRunning) {
        BlockEvent blockEvent = blockEventGet.getBlockEvent(tmp);
        realtimeEventService.flush(blockEvent, false);
        solidEventService.flush(blockEvent);
        tmp++;
        endNum = manager.getDynamicPropertiesStore().getLatestSolidifiedBlockNum();
        Thread.sleep(30);
      }
      initEventService(manager.getChainBaseManager().getBlockIdByNum(endNum));
    } catch (InterruptedException e1) {
      logger.warn("Sync event interrupted.");
      Thread.currentThread().interrupt();
    } catch (Exception e2) {
      logger.error("Sync event failed.", e2);
    }
  }

  private void initEventService(BlockCapsule.BlockId blockId) {
    logger.info("Init event service, {}", blockId.getString());
    BlockEventCache.init(blockId);
    realtimeEventService.init();
    blockEventLoad.init();
    solidEventService.init();
  }
}
