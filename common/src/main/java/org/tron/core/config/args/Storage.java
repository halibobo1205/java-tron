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
import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;
import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.Options;
import org.tron.common.utils.DbOptionalsUtils;
import org.tron.common.utils.FileUtil;
import org.tron.common.utils.Property;

/**
 * Custom storage configurations
 *
 * @author haoyouqiang
 * @version 1.0
 * @since 2018/5/25
 */
public class Storage {

  /**
   * Keys (names) of database config
   */
  private static final String DB_DIRECTORY_CONFIG_KEY = "storage.db.directory";
  private static final String DB_VERSION_CONFIG_KEY = "storage.db.version";
  private static final String DB_ENGINE_CONFIG_KEY = "storage.db.engine";
  private static final String DB_SYNC_CONFIG_KEY = "storage.db.sync";
  private static final String INDEX_DIRECTORY_CONFIG_KEY = "storage.index.directory";
  private static final String INDEX_SWITCH_CONFIG_KEY = "storage.index.switch";
  private static final String TRANSACTIONHISTORY_SWITCH_CONFIG_KEY = "storage.transHistory.switch";
  private static final String ESTIMATED_TRANSACTIONS_CONFIG_KEY =
      "storage.txCache.estimatedTransactions";
  private static final String PROPERTIES_CONFIG_KEY = "storage.properties";
  private static final String PROPERTIES_CONFIG_DB_KEY = "storage";
  private static final String PROPERTIES_CONFIG_DEFAULT_KEY = "default";
  private static final String PROPERTIES_CONFIG_DEFAULT_M_KEY = "defaultM";
  private static final String PROPERTIES_CONFIG_DEFAULT_L_KEY = "defaultL";
  private static final String DEFAULT_TRANSACTIONHISTORY_SWITCH = "on";

  private static final String NAME_CONFIG_KEY = "name";
  private static final String PATH_CONFIG_KEY = "path";
  private static final String CREATE_IF_MISSING_CONFIG_KEY = "createIfMissing";
  private static final String PARANOID_CHECKS_CONFIG_KEY = "paranoidChecks";
  private static final String VERITY_CHECK_SUMS_CONFIG_KEY = "verifyChecksums";
  private static final String COMPRESSION_TYPE_CONFIG_KEY = "compressionType";
  private static final String BLOCK_SIZE_CONFIG_KEY = "blockSize";
  private static final String WRITE_BUFFER_SIZE_CONFIG_KEY = "writeBufferSize";
  private static final String CACHE_SIZE_CONFIG_KEY = "cacheSize";
  private static final String MAX_OPEN_FILES_CONFIG_KEY = "maxOpenFiles";
  private static final String EVENT_SUBSCRIBE_CONTRACT_PARSE = "event.subscribe.contractParse";

  private static final String CACHE_STRATEGIES = "storage.cache.strategies";
  private static final String CACHE_STRATEGY = "storage.cache.strategy";
  private static final String CACHE_STRATEGY_DEFAULT =
      "initialCapacity=500,maximumSize=1000,expireAfterAccess=30s";

  private static final String CACHE_STRATEGY_SMALL_DEFAULT =
      "initialCapacity=10,maximumSize=100,expireAfterAccess=5m";
  private static final List<String> CACHE_SMALL_DBS = Arrays.asList("recent-block", "witness",
      "witness_schedule", "DelegatedResource", "DelegatedResourceAccountIndex",
      "votes", "abi");

  private static final String CACHE_STRATEGY_NORMAL_DEFAULT =
      "initialCapacity=100,maximumSize=500,expireAfterAccess=5m";
  private static final List<String> CACHE_NORMAL_DBS = Arrays.asList("code", "contract",
      "asset-issue-v2");

  private static final String CACHE_STRATEGY_BIG_DEFAULT =
      "initialCapacity=1000,maximumSize=10000,expireAfterAccess=1m";
  public static final List<String> CACHE_BIG_DBS = Collections.singletonList("delegation");

  private static final String CACHE_STRATEGY_HUGE_DEFAULT =
      "initialCapacity=1000,maximumSize=20000,expireAfterAccess=2m";
  private static final List<String> CACHE_HUGE_DBS = Arrays.asList("storage-row", "account");

  private static final List<String> CACHE_DBS = Stream.of(CACHE_SMALL_DBS, CACHE_NORMAL_DBS,
      CACHE_BIG_DBS, CACHE_HUGE_DBS).flatMap(Collection::stream).collect(Collectors.toList());

  /**
   * Default values of directory
   */
  private static final int DEFAULT_DB_VERSION = 2;
  private static final String DEFAULT_DB_ENGINE = "LEVELDB";
  private static final boolean DEFAULT_DB_SYNC = false;
  private static final boolean DEFAULT_EVENT_SUBSCRIBE_CONTRACT_PARSE = true;
  private static final String DEFAULT_DB_DIRECTORY = "database";
  private static final String DEFAULT_INDEX_DIRECTORY = "index";
  private static final String DEFAULT_INDEX_SWITCH = "on";
  private static final int DEFAULT_ESTIMATED_TRANSACTIONS = 1000;
  private Config storage;

  /**
   * Database storage directory: /path/to/{dbDirectory}
   */
  @Getter
  @Setter
  private String dbDirectory;

  @Getter
  @Setter
  private int dbVersion;

  @Getter
  @Setter
  private String dbEngine;

  @Getter
  @Setter
  private boolean dbSync;

  /**
   * Index storage directory: /path/to/{indexDirectory}
   */
  @Getter
  @Setter
  private String indexDirectory;

  @Getter
  @Setter
  private String indexSwitch;

  @Getter
  @Setter
  private boolean contractParseSwitch;

  @Getter
  @Setter
  private String transactionHistorySwitch;

  private Options defaultDbOptions;

  @Getter
  @Setter
  private int estimatedBlockTransactions;

  // second cache
  private String cacheStrategy = CACHE_STRATEGY_DEFAULT;

  private final Map<String, String> cacheStrategies = Maps.newConcurrentMap();

  @Getter
  private final List<String> cacheDbs = CACHE_DBS;
  // second cache

  /**
   * Key: dbName, Value: Property object of that database
   */
  @Getter
  private Map<String, Property> propertyMap;

  public static int getDbVersionFromConfig(final Config config) {
    return config.hasPath(DB_VERSION_CONFIG_KEY)
        ? config.getInt(DB_VERSION_CONFIG_KEY) : DEFAULT_DB_VERSION;
  }

  public static String getDbEngineFromConfig(final Config config) {
    return config.hasPath(DB_ENGINE_CONFIG_KEY)
        ? config.getString(DB_ENGINE_CONFIG_KEY) : DEFAULT_DB_ENGINE;
  }

  public static Boolean getDbVersionSyncFromConfig(final Config config) {
    return config.hasPath(DB_SYNC_CONFIG_KEY)
        ? config.getBoolean(DB_SYNC_CONFIG_KEY) : DEFAULT_DB_SYNC;
  }

  public static Boolean getContractParseSwitchFromConfig(final Config config) {
    return config.hasPath(EVENT_SUBSCRIBE_CONTRACT_PARSE)
        ? config.getBoolean(EVENT_SUBSCRIBE_CONTRACT_PARSE)
        : DEFAULT_EVENT_SUBSCRIBE_CONTRACT_PARSE;
  }

  public static String getDbDirectoryFromConfig(final Config config) {
    return config.hasPath(DB_DIRECTORY_CONFIG_KEY)
        ? config.getString(DB_DIRECTORY_CONFIG_KEY) : DEFAULT_DB_DIRECTORY;
  }

  public static String getIndexDirectoryFromConfig(final Config config) {
    return config.hasPath(INDEX_DIRECTORY_CONFIG_KEY)
        ? config.getString(INDEX_DIRECTORY_CONFIG_KEY) : DEFAULT_INDEX_DIRECTORY;
  }

  public static String getIndexSwitchFromConfig(final Config config) {
    return config.hasPath(INDEX_SWITCH_CONFIG_KEY)
        && StringUtils.isNotEmpty(config.getString(INDEX_SWITCH_CONFIG_KEY))
        ? config.getString(INDEX_SWITCH_CONFIG_KEY) : DEFAULT_INDEX_SWITCH;
  }

  public static String getTransactionHistorySwitchFromConfig(final Config config) {
    return config.hasPath(TRANSACTIONHISTORY_SWITCH_CONFIG_KEY)
        ? config.getString(TRANSACTIONHISTORY_SWITCH_CONFIG_KEY)
        : DEFAULT_TRANSACTIONHISTORY_SWITCH;
  }

  public static int getEstimatedTransactionsFromConfig(final Config config) {
    if (!config.hasPath(ESTIMATED_TRANSACTIONS_CONFIG_KEY)) {
      return DEFAULT_ESTIMATED_TRANSACTIONS;
    }
    int estimatedTransactions = config.getInt(ESTIMATED_TRANSACTIONS_CONFIG_KEY);
    if (estimatedTransactions > 10000) {
      estimatedTransactions = 10000;
    } else if (estimatedTransactions < 100) {
      estimatedTransactions = 100;
    }
    return estimatedTransactions;
  }


  public  void setCacheStrategies(Config config) {
    if (config.hasPath(CACHE_STRATEGY)) {
      this.cacheStrategy = config.getString(CACHE_STRATEGY);
    }
    if (config.hasPath(CACHE_STRATEGIES)) {
      config.getConfig(CACHE_STRATEGIES).resolve().entrySet().forEach(c ->
          this.cacheStrategies.put(c.getKey(),  c.getValue().unwrapped().toString()));
    }
  }

  public String getCacheStrategy(String dbName) {
    String defaultStrategy = this.cacheStrategy;
    if (CACHE_SMALL_DBS.contains(dbName)) {
      defaultStrategy = CACHE_STRATEGY_SMALL_DEFAULT;
    }
    if (CACHE_NORMAL_DBS.contains(dbName)) {
      defaultStrategy = CACHE_STRATEGY_NORMAL_DEFAULT;
    }
    if (CACHE_BIG_DBS.contains(dbName)) {
      defaultStrategy = CACHE_STRATEGY_BIG_DEFAULT;
    }
    if (CACHE_HUGE_DBS.contains(dbName)) {
      defaultStrategy = CACHE_STRATEGY_HUGE_DEFAULT;
    }
    return this.cacheStrategies.getOrDefault(dbName, defaultStrategy);
  }

  private  Property createProperty(final ConfigObject conf) {

    Property property = new Property();

    // Database name must be set
    if (!conf.containsKey(NAME_CONFIG_KEY)) {
      throw new IllegalArgumentException("[storage.properties] database name must be set.");
    }
    property.setName(conf.get(NAME_CONFIG_KEY).unwrapped().toString());

    // Check writable permission of path
    if (conf.containsKey(PATH_CONFIG_KEY)) {
      String path = conf.get(PATH_CONFIG_KEY).unwrapped().toString();

      File file = new File(path);
      if (!file.exists() && !file.mkdirs()) {
        throw new IllegalArgumentException(
            "[storage.properties] can not create storage path: " + path);
      }

      if (!file.canWrite()) {
        throw new IllegalArgumentException(
            "[storage.properties] permission denied to write to: " + path);
      }

      property.setPath(path);
    }

    // Check, get and set fields of Options
    Options dbOptions = newDefaultDbOptions(property.getName());

    setIfNeeded(conf, dbOptions);

    property.setDbOptions(dbOptions);
    return property;
  }

  private static void setIfNeeded(ConfigObject conf, Options dbOptions) {
    if (conf.containsKey(CREATE_IF_MISSING_CONFIG_KEY)) {
      dbOptions.createIfMissing(
          Boolean.parseBoolean(
              conf.get(CREATE_IF_MISSING_CONFIG_KEY).unwrapped().toString()
          )
      );
    }

    if (conf.containsKey(PARANOID_CHECKS_CONFIG_KEY)) {
      dbOptions.paranoidChecks(
          Boolean.parseBoolean(
              conf.get(PARANOID_CHECKS_CONFIG_KEY).unwrapped().toString()
          )
      );
    }

    if (conf.containsKey(VERITY_CHECK_SUMS_CONFIG_KEY)) {
      dbOptions.verifyChecksums(
          Boolean.parseBoolean(
              conf.get(VERITY_CHECK_SUMS_CONFIG_KEY).unwrapped().toString()
          )
      );
    }

    if (conf.containsKey(COMPRESSION_TYPE_CONFIG_KEY)) {
      try {
        dbOptions.compressionType(
            CompressionType.getCompressionTypeByPersistentId(
                Integer.parseInt(
                    conf.get(COMPRESSION_TYPE_CONFIG_KEY).unwrapped().toString()
                )
            )
        );
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "[storage.properties] compressionType must be Integer type.");
      }
    }

    if (conf.containsKey(BLOCK_SIZE_CONFIG_KEY)) {
      try {
        dbOptions.blockSize(
            Integer.parseInt(
                conf.get(BLOCK_SIZE_CONFIG_KEY).unwrapped().toString()
            )
        );
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("[storage.properties] blockSize must be Integer type.");
      }
    }

    if (conf.containsKey(WRITE_BUFFER_SIZE_CONFIG_KEY)) {
      try {
        dbOptions.writeBufferSize(
            Integer.parseInt(
                conf.get(WRITE_BUFFER_SIZE_CONFIG_KEY).unwrapped().toString()
            )
        );
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "[storage.properties] writeBufferSize must be Integer type.");
      }
    }

    if (conf.containsKey(CACHE_SIZE_CONFIG_KEY)) {
      try {
        dbOptions.cacheSize(
            Long.parseLong(
                conf.get(CACHE_SIZE_CONFIG_KEY).unwrapped().toString()
            )
        );
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("[storage.properties] cacheSize must be Long type.");
      }
    }

    if (conf.containsKey(MAX_OPEN_FILES_CONFIG_KEY)) {
      try {
        dbOptions.maxOpenFiles(
            Integer.parseInt(
                conf.get(MAX_OPEN_FILES_CONFIG_KEY).unwrapped().toString()
            )
        );
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "[storage.properties] maxOpenFiles must be Integer type.");
      }
    }
  }

  /**
   * Set propertyMap of Storage object from Config
   *
   * @param config Config object from "config.conf" file
   */
  public void setPropertyMapFromConfig(final Config config) {
    if (config.hasPath(PROPERTIES_CONFIG_KEY)) {
      propertyMap = config.getObjectList(PROPERTIES_CONFIG_KEY).stream()
          .map(this::createProperty)
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

  public void setDefaultDbOptions(final Config config) {
    this.defaultDbOptions = DbOptionalsUtils.createDefaultDbOptions();
    storage = config.getConfig(PROPERTIES_CONFIG_DB_KEY);
  }

  public Options newDefaultDbOptions(String name ) {
    // first fetch origin default
    Options options =  DbOptionalsUtils.newDefaultDbOptions(name, this.defaultDbOptions);

    // then fetch from config for default
    if (storage.hasPath(PROPERTIES_CONFIG_DEFAULT_KEY)) {
      setIfNeeded(storage.getObject(PROPERTIES_CONFIG_DEFAULT_KEY), options);
    }

    // check if has middle config
    if (storage.hasPath(PROPERTIES_CONFIG_DEFAULT_M_KEY) && DbOptionalsUtils.DB_M.contains(name)) {
      setIfNeeded(storage.getObject(PROPERTIES_CONFIG_DEFAULT_M_KEY), options);

    }
    // check if has large config
    if (storage.hasPath(PROPERTIES_CONFIG_DEFAULT_L_KEY) && DbOptionalsUtils.DB_L.contains(name)) {
      setIfNeeded(storage.getObject(PROPERTIES_CONFIG_DEFAULT_L_KEY), options);
    }

    return options;
  }
}
