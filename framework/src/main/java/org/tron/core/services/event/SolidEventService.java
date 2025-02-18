package org.tron.core.services.event;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.es.ExecutorServiceManager;
import org.tron.common.logsfilter.EventPluginLoader;
import org.tron.common.logsfilter.trigger.Trigger;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.db.Manager;
import org.tron.core.services.event.bo.BlockEvent;

@Slf4j(topic = "event")
@Component
public class SolidEventService {

  private EventPluginLoader instance = EventPluginLoader.getInstance();

  @Autowired
  private Manager manager;

  private final ScheduledExecutorService executor = ExecutorServiceManager
      .newSingleThreadScheduledExecutor("solid-event");

  public void init() {
    executor.scheduleWithFixedDelay(() -> {
      try {
        work();
      } catch (Exception exception) {
        logger.error("Spread thread error", exception);
      }
    }, 1, 1, TimeUnit.SECONDS);
    logger.info("Solid event service start.");
  }

  public void close() {
    executor.shutdown();
    logger.info("Solid event service close.");
  }

  public void work() {
    BlockCapsule.BlockId solidId = BlockEventCache.getSolidId();
    if (solidId.getNum() <= BlockEventCache.getSolidNum()) {
      return;
    }

    List<BlockEvent> blockEvents = BlockEventCache.getSolidBlockEvents(solidId);

    blockEvents.forEach(v -> flush(v));

    BlockEventCache.remove(solidId);
  }

  public void flush(BlockEvent blockEvent) {
    logger.info("Flush solid event {}", blockEvent.getBlockId().getString());

    if (instance.isBlockLogTriggerEnable() && instance.isBlockLogTriggerSolidified()) {
      if (blockEvent.getBlockLogTriggerCapsule() == null) {
        logger.warn("BlockLogTrigger is null. {}", blockEvent.getBlockId());
      } else {
        manager.getTriggerCapsuleQueue().offer(blockEvent.getBlockLogTriggerCapsule());
      }
    }

    if (instance.isTransactionLogTriggerEnable() && instance.isTransactionLogTriggerSolidified()) {
      if (blockEvent.getTransactionLogTriggerCapsules() == null) {
        logger.info("TransactionLogTrigger is null. {}", blockEvent.getBlockId());
      } else {
        blockEvent.getTransactionLogTriggerCapsules().forEach(v ->
            manager.getTriggerCapsuleQueue().offer(v));
      }
    }

    if (instance.isSolidityEventTriggerEnable()) {
      if (blockEvent.getSmartContractTrigger() == null) {
        logger.info("SmartContractTrigger is null. {}", blockEvent.getBlockId());
      } else {
        blockEvent.getSmartContractTrigger().getContractEventTriggers().forEach(v -> {
          v.setTriggerName(Trigger.SOLIDITYEVENT_TRIGGER_NAME);
          EventPluginLoader.getInstance().postSolidityEventTrigger(v);
        });
      }
    }

    if (instance.isSolidityLogTriggerEnable() && blockEvent.getSmartContractTrigger() != null) {
      blockEvent.getSmartContractTrigger().getContractLogTriggers().forEach(v -> {
        v.setTriggerName(Trigger.SOLIDITYLOG_TRIGGER_NAME);
        EventPluginLoader.getInstance().postSolidityLogTrigger(v);
      });
      if (instance.isSolidityLogTriggerRedundancy()) {
        blockEvent.getSmartContractTrigger().getRedundancies().forEach(v -> {
          v.setTriggerName(Trigger.SOLIDITYLOG_TRIGGER_NAME);
          EventPluginLoader.getInstance().postSolidityLogTrigger(v);
        });
      }
    }

    if (instance.isSolidityTriggerEnable()) {
      if (blockEvent.getSolidityTriggerCapsule() == null) {
        logger.info("SolidityTrigger is null. {}", blockEvent.getBlockId());
      } else {
        manager.getTriggerCapsuleQueue().offer(blockEvent.getSolidityTriggerCapsule());
      }
    }
  }

}
