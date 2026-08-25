/*
 * java-tron is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-tron is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.tron.core.config.args;

import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import com.typesafe.config.Config;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.hyperledger.besu.storage.RocksDBConfigurationBuilder;
import org.iq80.leveldb.Options;
import org.tron.common.cache.CacheStrategies;
import org.tron.common.cache.CacheType;
import org.tron.common.utils.DbOptionalsUtils;
import org.tron.common.utils.FileUtil;
import org.tron.common.utils.Property;
import org.tron.common.utils.Sha256Hash;

/**
 * Custom storage configurations
 *
 * @author haoyouqiang
 * @version 1.0
 * @since 2018/5/25
 */
@Slf4j(topic = "db")
public class Storage {

  /**
   * Keys (names) of state-trie database config, parsed from the raw Config
   * (not bound via StorageConfig bean).
   */
  private static final String STATE_ROOT_SWITCH_KEY = "storage.stateRoot.switch";
  private static final String STATE_DB_MAX_OPEN_FILES_KEY = "storage.stateRoot.db.maxOpenFiles";
  private static final String STATE_DB_WRITE_BUFFER_SIZE_KEY =
          "storage.stateRoot.db.writeBufferSize";
  private static final String STATE_DB_CACHE_CAPACITY_KEY = "storage.stateRoot.db.cacheCapacity";
  private static final String STATE_DB_CACHE_INDEX_AND_FILTER_KEY =
          "storage.stateRoot.db.cacheIndexAndFilter";
  private static final String STATE_GENESIS_DIRECTORY_KEY = "storage.stateGenesis.directory";

  private static final String DEFAULT_INDEX_SWITCH = "on";
  private static final String DEFAULT_STATE_GENESIS_DIRECTORY = "state-genesis";

  // Optional per-tier LevelDB option overrides, read from StorageConfig bean
  private StorageConfig.DbOptionOverride defaultDbOption;
  private StorageConfig.DbOptionOverride defaultMDbOption;
  private StorageConfig.DbOptionOverride defaultLDbOption;

  /**
   * Database storage directory: /path/to/{dbDirectory}
   */
  @Getter
  @Setter
  private String dbDirectory;

  @Getter
  @Setter
  private String dbEngine;

  @Getter
  @Setter
  private boolean dbSync;

  @Getter
  @Setter
  private int maxFlushCount;

  @Getter
  @Setter
  private boolean contractParseSwitch;

  @Getter
  @Setter
  private String transactionHistorySwitch;

  @Getter
  @Setter
  private int checkpointVersion;

  @Getter
  @Setter
  private boolean checkpointSync;

  private Options defaultDbOptions;

  @Getter
  @Setter
  private int estimatedBlockTransactions;

  @Getter
  @Setter
  private boolean txCacheInitOptimization = false;

  // second cache
  private final Map<CacheType, String> cacheStrategies = Maps.newConcurrentMap();

  @Getter
  private final List<String> cacheDbs = CacheStrategies.CACHE_DBS;
  // second cache

  @Getter
  private boolean allowStateRoot;

  @Getter
  private String stateGenesisDirectory = DEFAULT_STATE_GENESIS_DIRECTORY;

  @Getter
  private final RocksDBConfigurationBuilder stateDbConf = new RocksDBConfigurationBuilder();


  /**
   * Key: dbName, Value: Property object of that database
   */
  @Getter
  private Map<String, Property> propertyMap;

  // db root
  private final Map<String, Sha256Hash> dbRoots = Maps.newConcurrentMap();

  /**
   * Accepts raw storage Config sub-tree because cache.strategies has dynamic keys
   * (CacheType enum names) that ConfigBeanFactory cannot bind to fixed bean fields.
   */
  public void setCacheStrategies(Config storageSection) {
    if (storageSection.hasPath("cache.strategies")) {
      storageSection.getConfig("cache.strategies").resolve().entrySet().forEach(c ->
          this.cacheStrategies.put(CacheType.valueOf(c.getKey()),
              c.getValue().unwrapped().toString()));
    }
  }

  public String getCacheStrategy(CacheType dbName) {
    return this.cacheStrategies.getOrDefault(dbName, CacheStrategies.getCacheStrategy(dbName));
  }

  public Sha256Hash getDbRoot(String dbName, Sha256Hash defaultV) {
    return this.dbRoots.getOrDefault(dbName, defaultV);
  }

  /**
   * Accepts raw storage Config sub-tree because merkleRoot has dynamic keys
   * (database names) that ConfigBeanFactory cannot bind to fixed bean fields.
   */
  public void setDbRoots(Config storageSection) {
    if (storageSection.hasPath("merkleRoot")) {
      storageSection.getConfig("merkleRoot").resolve().entrySet().forEach(c ->
          this.dbRoots.put(c.getKey(), Sha256Hash.wrap(
              ByteString.fromHex(c.getValue().unwrapped().toString()))));
    }
  }

  public void setStateConfig(final Config config) {
    if (config.hasPath(STATE_ROOT_SWITCH_KEY)) {
      this.allowStateRoot = config.getBoolean(STATE_ROOT_SWITCH_KEY);
    }
    if (config.hasPath(STATE_GENESIS_DIRECTORY_KEY)) {
      this.stateGenesisDirectory = config.getString(STATE_GENESIS_DIRECTORY_KEY);
    }
    if (config.hasPath(STATE_DB_MAX_OPEN_FILES_KEY)) {
      this.stateDbConf.maxOpenFiles(config.getInt(STATE_DB_MAX_OPEN_FILES_KEY));
    }
    if (config.hasPath(STATE_DB_CACHE_CAPACITY_KEY)) {
      this.stateDbConf.cacheCapacity(config.getLong(STATE_DB_CACHE_CAPACITY_KEY));
    }
    if (config.hasPath(STATE_DB_WRITE_BUFFER_SIZE_KEY)) {
      this.stateDbConf.writeBufferSize(config.getLong(STATE_DB_WRITE_BUFFER_SIZE_KEY));
    }
    if (config.hasPath(STATE_DB_CACHE_INDEX_AND_FILTER_KEY)) {
      this.stateDbConf.isCacheIndexAndFilter(
          config.getBoolean(STATE_DB_CACHE_INDEX_AND_FILTER_KEY));
    }
  }

  /**
   * Create Property from StorageConfig.PropertyConfig bean.
   */
  private Property createPropertyFromBean(StorageConfig.PropertyConfig pc) {
    Property property = new Property();

    if (pc.getName().isEmpty()) {
      throw new IllegalArgumentException("[storage.properties] database name must be set.");
    }
    property.setName(pc.getName());

    if (!pc.getPath().isEmpty()) {
      String path = pc.getPath();
      File file = new File(path);
      if (!file.exists() && !file.mkdirs()) {
        throw new IllegalArgumentException(
            String.format("[storage.properties] can not create storage path: %s", path));
      }
      if (!file.canWrite()) {
        throw new IllegalArgumentException(
            String.format("[storage.properties] permission denied to write to: %s ", path));
      }
      property.setPath(path);
    }

    Options dbOptions = newDefaultDbOptions(property.getName());
    // PropertyConfig is-a DbOptionOverride: apply only user-specified (non-null) overrides
    // so unset fields keep the per-tier defaults already applied by newDefaultDbOptions.
    applyDbOptionOverride(pc, dbOptions);
    property.setDbOptions(dbOptions);
    return property;
  }

  /**
   * Set propertyMap from StorageConfig bean list. No Config parameter needed.
   */
  public void setPropertyMapFromBean(List<StorageConfig.PropertyConfig> props) {
    if (props != null && !props.isEmpty()) {
      propertyMap = props.stream()
          .map(this::createPropertyFromBean)
          .collect(Collectors.toMap(Property::getName, p -> p));
    }
  }

  /**
   * Only for unit test on db
   */
  public void deleteAllStoragePaths() {
    if (propertyMap == null) {
      return;
    }

    for (Property property : propertyMap.values()) {
      String path = property.getPath();
      if (path != null) {
        FileUtil.recursiveDelete(path);
      }
    }
  }

  /**
   * Initialize default LevelDB options and store optional per-tier overrides
   * from StorageConfig bean (no raw Config needed).
   */
  public void setDefaultDbOptions(StorageConfig sc) {
    this.defaultDbOptions = DbOptionalsUtils.createDefaultDbOptions();
    this.defaultDbOption = sc.getDefaultDbOption();
    this.defaultMDbOption = sc.getDefaultMDbOption();
    this.defaultLDbOption = sc.getDefaultLDbOption();
  }

  public Options newDefaultDbOptions(String name) {
    Options options = DbOptionalsUtils.newDefaultDbOptions(name, this.defaultDbOptions);

    if (defaultDbOption != null) {
      applyDbOptionOverride(defaultDbOption, options);
    }
    if (defaultMDbOption != null && DbOptionalsUtils.DB_M.contains(name)) {
      applyDbOptionOverride(defaultMDbOption, options);
    }
    if (defaultLDbOption != null && DbOptionalsUtils.DB_L.contains(name)) {
      applyDbOptionOverride(defaultLDbOption, options);
    }

    return options;
  }

  // Apply only user-specified overrides (non-null fields) to LevelDB Options.
  private static void applyDbOptionOverride(
      StorageConfig.DbOptionOverride o, Options dbOptions) {
    if (o.getBlockSize() != null) {
      dbOptions.blockSize(o.getBlockSize());
    }
    if (o.getWriteBufferSize() != null) {
      dbOptions.writeBufferSize(o.getWriteBufferSize());
    }
    if (o.getCacheSize() != null) {
      dbOptions.cacheSize(o.getCacheSize());
    }
    if (o.getMaxOpenFiles() != null) {
      dbOptions.maxOpenFiles(o.getMaxOpenFiles());
    }
  }
}
