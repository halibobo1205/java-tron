package org.tron.core.services;

import static org.tron.core.config.Parameter.ChainConstant.BLOCK_PRODUCED_INTERVAL;

import java.util.Optional;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.tron.common.client.DatabaseGrpcClient;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.db.Manager;
import org.tron.core.net.TronNetDelegate;
import org.tron.protos.Protocol.Block;

@Slf4j(topic = "app")
@Component
public class SolidityService {

  @Autowired
  private Manager dbManager;

  @Autowired
  private ChainBaseManager chainBaseManager;

  private DatabaseGrpcClient databaseGrpcClient;

  @Autowired
  private TronNetDelegate tronNetDelegate;

  private AtomicLong ID;

  private AtomicLong remoteBlockNum;

  private LinkedBlockingDeque<Block> blockQueue;

  private int exceptionSleepTime;

  private volatile boolean flag = true;

  private static Optional<SolidityService> INSTANCE = Optional.empty();

  @PostConstruct
  private void init() {
    if (!CommonParameter.getInstance().isSolidityNode()) {
      return;
    }
    if (ObjectUtils.isEmpty(CommonParameter.getInstance().getTrustNodeAddr())) {
      throw new IllegalArgumentException("Trust node is not set.");
    }
    resolveCompatibilityIssueIfUsingFullNodeDatabase();
    ID = new AtomicLong(chainBaseManager.getDynamicPropertiesStore().getLatestSolidifiedBlockNum());
    databaseGrpcClient = new DatabaseGrpcClient(CommonParameter.getInstance().getTrustNodeAddr());
    remoteBlockNum = new AtomicLong(0);
    blockQueue = new LinkedBlockingDeque<>(100);
    exceptionSleepTime = 1000;
    INSTANCE = Optional.of(this);
  }

  public static void runIfNeed() {
    INSTANCE.ifPresent(SolidityService::start);
  }

  private void start() {
    try {
      remoteBlockNum.set(getLastSolidityBlockNum());
      new Thread(this::getBlock).start();
      new Thread(this::processBlock).start();
      logger.info("Success to start solid node, ID: {}, remoteBlockNum: {}.", ID.get(),
          remoteBlockNum);
    } catch (Exception e) {
      logger.error("Failed to start solid node, address: {}.",
          CommonParameter.getInstance().getTrustNodeAddr());
      System.exit(0);
    }
  }

  private void getBlock() {
    long blockNum = ID.incrementAndGet();
    while (flag) {
      try {
        if (blockNum > remoteBlockNum.get()) {
          sleep(BLOCK_PRODUCED_INTERVAL);
          remoteBlockNum.set(getLastSolidityBlockNum());
          continue;
        }
        Block block = getBlockByNum(blockNum);
        blockQueue.put(block);
        blockNum = ID.incrementAndGet();
      } catch (Exception e) {
        logger.error("Failed to get block {}, reason: {}.", blockNum, e.getMessage());
        sleep(exceptionSleepTime);
      }
    }
  }

  private void processBlock() {
    while (flag) {
      try {
        Block block = blockQueue.take();
        loopProcessBlock(block);
        flag = dbManager.getLatestSolidityNumShutDown() != dbManager.getDynamicPropertiesStore()
            .getLatestBlockHeaderNumberFromDB();
      } catch (Exception e) {
        logger.error(e.getMessage());
        sleep(exceptionSleepTime);
      }
    }
    logger.info("Begin shutdown, currentBlockNum:{}, DbBlockNum:{}, solidifiedBlockNum:{}",
        dbManager.getDynamicPropertiesStore().getLatestBlockHeaderNumber(),
        dbManager.getDynamicPropertiesStore().getLatestBlockHeaderNumberFromDB(),
        dbManager.getDynamicPropertiesStore().getLatestSolidifiedBlockNum());
    tronNetDelegate.markHitDown();
    tronNetDelegate.unparkHitThread();
  }

  private void loopProcessBlock(Block block) {
    while (flag) {
      long blockNum = block.getBlockHeader().getRawData().getNumber();
      try {
        dbManager.pushVerifiedBlock(new BlockCapsule(block));
        chainBaseManager.getDynamicPropertiesStore().saveLatestSolidifiedBlockNum(blockNum);
        logger
            .info("Success to process block: {}, blockQueueSize: {}.", blockNum, blockQueue.size());
        return;
      } catch (Exception e) {
        logger.error("Failed to process block {}.", new BlockCapsule(block), e);
        sleep(exceptionSleepTime);
        block = getBlockByNum(blockNum);
      }
    }
  }

  private Block getBlockByNum(long blockNum) {
    while (true) {
      try {
        long time = System.currentTimeMillis();
        Block block = databaseGrpcClient.getBlock(blockNum);
        long num = block.getBlockHeader().getRawData().getNumber();
        if (num == blockNum) {
          logger.info("Success to get block: {}, cost: {}ms.",
              blockNum, System.currentTimeMillis() - time);
          return block;
        } else {
          logger.warn("Get block id not the same , {}, {}.", num, blockNum);
          sleep(exceptionSleepTime);
        }
      } catch (Exception e) {
        logger.error("Failed to get block: {}, reason: {}.", blockNum, e.getMessage());
        sleep(exceptionSleepTime);
      }
    }
  }

  private long getLastSolidityBlockNum() {
    while (true) {
      try {
        long time = System.currentTimeMillis();
        long blockNum = databaseGrpcClient.getDynamicProperties().getLastSolidityBlockNum();
        logger.info("Get last remote solid blockNum: {}, remoteBlockNum: {}, cost: {}.",
            blockNum, remoteBlockNum, System.currentTimeMillis() - time);
        return blockNum;
      } catch (Exception e) {
        logger.error("Failed to get last solid blockNum: {}, reason: {}.", remoteBlockNum.get(),
            e.getMessage());
        sleep(exceptionSleepTime);
      }
    }
  }

  private void sleep(long time) {
    try {
      Thread.sleep(time);
    } catch (Exception e1) {
      logger.error(e1.getMessage());
    }
  }

  private void resolveCompatibilityIssueIfUsingFullNodeDatabase() {
    long lastSolidityBlockNum =
        chainBaseManager.getDynamicPropertiesStore().getLatestSolidifiedBlockNum();
    long headBlockNum = chainBaseManager.getHeadBlockNum();
    logger.info("headBlockNum:{}, solidityBlockNum:{}, diff:{}",
        headBlockNum, lastSolidityBlockNum, headBlockNum - lastSolidityBlockNum);
    if (lastSolidityBlockNum < headBlockNum) {
      logger.info("use fullNode database, headBlockNum:{}, solidityBlockNum:{}, diff:{}",
          headBlockNum, lastSolidityBlockNum, headBlockNum - lastSolidityBlockNum);
      chainBaseManager.getDynamicPropertiesStore().saveLatestSolidifiedBlockNum(headBlockNum);
    }
  }
}