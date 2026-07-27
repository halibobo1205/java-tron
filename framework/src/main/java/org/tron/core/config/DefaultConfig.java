package org.tron.core.config;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDB;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.StorageUtils;
import org.tron.core.ChainBaseManager;
import org.tron.core.Wallet;
import org.tron.core.archive.ArchiveException;
import org.tron.core.archive.ArchivePathResolver;
import org.tron.core.archive.ArchiveService;
import org.tron.core.archive.ArchiveServiceFactory;
import org.tron.core.config.args.Args;
import org.tron.core.config.args.StorageConfig;
import org.tron.core.db.RevokingDatabase;
import org.tron.core.db2.core.SnapshotManager;
import org.tron.core.exception.TronError;
import org.tron.core.services.interfaceOnPBFT.RpcApiServiceOnPBFT;
import org.tron.core.services.interfaceOnPBFT.http.PBFT.HttpApiOnPBFTService;
import org.tron.core.services.interfaceOnSolidity.RpcApiServiceOnSolidity;
import org.tron.core.services.interfaceOnSolidity.http.solidity.HttpApiOnSolidityService;
import org.tron.core.services.jsonrpc.ArchiveJsonRpcExecutor;
import org.tron.core.services.jsonrpc.ArchiveJsonRpcStateAdapter;
import org.tron.core.services.jsonrpc.HistoricalDebugTraceSupport;
import org.tron.core.services.jsonrpc.HistoricalEthCallSupport;

@Slf4j(topic = "app")
@Configuration
@Import(CommonConfig.class)
public class DefaultConfig {

  static {
    RocksDB.loadLibrary();
  }

  @Autowired
  public ApplicationContext appCtx;

  @Autowired
  public CommonConfig commonConfig;

  public DefaultConfig() {
  }

  @Bean(destroyMethod = "close")
  public ArchiveService archiveService(ChainBaseManager chainBaseManager) {
    CommonParameter parameter = CommonParameter.getInstance();
    StorageConfig.ArchiveConfig archive = parameter.getStorage().getArchive();
    if (archive == null || !archive.isEnable()) {
      return ArchiveServiceFactory.create(archive); // disabled: no db path to resolve
    }
    try {
      return createEnabledArchiveService(chainBaseManager, parameter, archive);
    } catch (RuntimeException failure) {
      throw new TronError(
          "fatal archive sidecar initialization failure",
          failure, TronError.ErrCode.ARCHIVE_RUNTIME);
    }
  }

  private ArchiveService createEnabledArchiveService(ChainBaseManager chainBaseManager,
      CommonParameter parameter, StorageConfig.ArchiveConfig archive) {
    if (Args.getInstance().isSolidityNode()) {
      throw new ArchiveException("storage.archive.enable is not supported on SolidityNode");
    }
    Path outputRoot = Paths.get(parameter.getOutputDirectory()).toAbsolutePath().normalize();
    Path canonicalDbRoot = outputRoot.resolve(parameter.getStorage().getDbDirectory()).normalize();
    Path configuredArchivePath = Paths.get(archive.getDb().getDirectory());
    Path identityAnchors = outputRoot.resolve("archive-identities");
    List<Path> protectedPaths = new ArrayList<>();
    protectedPaths.add(identityAnchors);
    // Relative archive paths historically live below the canonical DB root. Only an absolute
    // override must be checked against the whole canonical root; rejecting descendants here would
    // make the compatible default (<output>/<db-directory>/archive) impossible.
    if (configuredArchivePath.isAbsolute()) {
      protectedPaths.add(canonicalDbRoot);
    }
    if (parameter.getStorage().getPropertyMap() != null) {
      parameter.getStorage().getPropertyMap().values().forEach(property -> {
        if (property.getPath() != null && !property.getPath().trim().isEmpty()) {
          protectedPaths.add(Paths.get(property.getPath()));
        }
      });
    }
    try {
      Path archiveDbPath = ArchivePathResolver.resolveAndValidate(
          configuredArchivePath, canonicalDbRoot, protectedPaths);
      return ArchiveServiceFactory.create(
          archive, archiveDbPath.toString(), chainBaseManager, identityAnchors);
    } catch (IOException | IllegalArgumentException e) {
      throw new ArchiveException("invalid archive database path", e);
    }
  }

  @Bean
  public ArchiveJsonRpcStateAdapter archiveJsonRpcStateAdapter(Wallet wallet,
      ArchiveService archiveService) {
    return new ArchiveJsonRpcStateAdapter(wallet, archiveService);
  }

  @Bean
  public HistoricalEthCallSupport historicalEthCallSupport(Wallet wallet,
      ArchiveService archiveService) {
    return new HistoricalEthCallSupport(wallet, archiveService);
  }

  @Bean
  public HistoricalDebugTraceSupport historicalDebugTraceSupport(Wallet wallet,
      ArchiveService archiveService) {
    StorageConfig.ArchiveConfig archive =
        CommonParameter.getInstance().getStorage().getArchive();
    return new HistoricalDebugTraceSupport(wallet, archiveService, archive.getDebug());
  }

  @Bean(destroyMethod = "close")
  public ArchiveJsonRpcExecutor archiveJsonRpcExecutor(ArchiveService archiveService) {
    if (!archiveService.isEnabled()) {
      return ArchiveJsonRpcExecutor.disabled();
    }
    StorageConfig.ArchiveConfig.QueryConfig query = CommonParameter.getInstance()
        .getStorage().getArchive().getQuery();
    StorageConfig.ArchiveConfig.DebugConfig debug = CommonParameter.getInstance()
        .getStorage().getArchive().getDebug();
    return new ArchiveJsonRpcExecutor(
        query.getJsonRpcWorkerThreads(), query.getDeadlineMs(),
        debug.isEnable() ? debug.getMaxConcurrentTraces() : 0,
        debug.isEnable() ? debug.getMaxPendingTraces() : 0);
  }

  @Bean(destroyMethod = "")
  public RevokingDatabase revokingDatabase() {
    try {
      return new SnapshotManager(
          StorageUtils.getOutputDirectoryByDbName("block"));
    } finally {
      logger.info("key-value data source created.");
    }
  }


  @Bean
  public RpcApiServiceOnSolidity getRpcApiServiceOnSolidity() {
    boolean isSolidityNode = Args.getInstance().isSolidityNode();
    if (!isSolidityNode) {
      return new RpcApiServiceOnSolidity();
    }

    return null;
  }

  @Bean
  public HttpApiOnSolidityService getHttpApiOnSolidityService() {
    boolean isSolidityNode = Args.getInstance().isSolidityNode();
    if (!isSolidityNode) {
      return new HttpApiOnSolidityService();
    }

    return null;
  }

  @Bean
  public RpcApiServiceOnPBFT getRpcApiServiceOnPBFT() {
    boolean isSolidityNode = Args.getInstance().isSolidityNode();
    if (!isSolidityNode) {
      return new RpcApiServiceOnPBFT();
    }

    return null;
  }

  @Bean
  public HttpApiOnPBFTService getHttpApiOnPBFTService() {
    boolean isSolidityNode = Args.getInstance().isSolidityNode();
    if (!isSolidityNode) {
      return new HttpApiOnPBFTService();
    }

    return null;
  }

}
