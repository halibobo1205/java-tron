/*
 * Copyright (C) 2011, FuseSource Corp. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * * Neither the name of FuseSource Corp. nor the names of its contributors may
 *   be used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package org.tron.common.storage.leveldb;

import java.io.File;
import java.io.IOException;
import org.fusesource.leveldbjni.internal.JniDB;
import org.fusesource.leveldbjni.internal.NativeCache;
import org.fusesource.leveldbjni.internal.NativeComparator;
import org.fusesource.leveldbjni.internal.NativeCompressionType;
import org.fusesource.leveldbjni.internal.NativeDB;
import org.fusesource.leveldbjni.internal.NativeLogger;
import org.fusesource.leveldbjni.internal.NativeOptions;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBComparator;
import org.iq80.leveldb.Logger;
import org.iq80.leveldb.Options;

/**
 * Keeps leveldbjni 1.8's option mapping and JniDB ownership while completing Logger deletion.
 * Adapted from JniDBFactory: the original factory also loses its Logger on failed opens.
 */
final class LevelDbFactory {

  private LevelDbFactory() {
  }

  static DB open(File path, Options options) throws IOException {
    NativeResources resources = new NativeResources();
    NativeDB database = null;
    boolean transferred = false;
    try {
      resources.init(options);
      database = NativeDB.open(resources.options, path);
      DB result = new JniDB(database, resources.cache, resources.comparator, resources.logger);
      transferred = true;
      return result;
    } finally {
      if (!transferred) {
        try {
          if (database != null) {
            database.delete();
          }
        } finally {
          resources.close();
        }
      }
    }
  }

  private static final class NativeResources implements AutoCloseable {
    private NativeOptions options;
    private NativeCache cache;
    private NativeComparator comparator;
    private NativeLogger logger;

    private void init(Options value) {
      options = new NativeOptions();
      options.blockRestartInterval(value.blockRestartInterval());
      options.blockSize(value.blockSize());
      options.createIfMissing(value.createIfMissing());
      options.errorIfExists(value.errorIfExists());
      options.maxOpenFiles(value.maxOpenFiles());
      options.paranoidChecks(value.paranoidChecks());
      options.writeBufferSize(value.writeBufferSize());

      switch (value.compressionType()) {
        case NONE:
          options.compression(NativeCompressionType.kNoCompression);
          break;
        case SNAPPY:
          options.compression(NativeCompressionType.kSnappyCompression);
          break;
        default:
          break;
      }

      if (value.cacheSize() > 0) {
        cache = new NativeCache(value.cacheSize());
        options.cache(cache);
      }

      DBComparator userComparator = value.comparator();
      if (userComparator != null) {
        comparator = new NativeComparator() {
          @Override
          public int compare(byte[] key1, byte[] key2) {
            return userComparator.compare(key1, key2);
          }

          @Override
          public String name() {
            return userComparator.name();
          }
        };
        options.comparator(comparator);
      }

      Logger userLogger = value.logger();
      if (userLogger != null) {
        logger = new ManagedNativeLogger(userLogger);
        options.infoLog(logger);
      }
    }

    @Override
    public void close() {
      try {
        if (cache != null) {
          cache.delete();
          cache = null;
        }
      } finally {
        try {
          if (comparator != null) {
            comparator.delete();
            comparator = null;
          }
        } finally {
          if (logger != null) {
            logger.delete();
            logger = null;
          }
        }
      }
    }
  }

  private static final class ManagedNativeLogger extends NativeLogger {
    private final Logger delegate;

    private ManagedNativeLogger(Logger delegate) {
      this.delegate = delegate;
    }

    @Override
    public void log(String message) {
      delegate.log(message);
    }

    @Override
    public synchronized void delete() {
      // The base constructor can call delete() before this subclass is initialized.
      long handle = self;
      if (handle == 0) {
        return;
      }
      self = 0;
      try {
        super.delete();
      } finally {
        // NativeLogger.delete() only clears the JNI global reference in 1.8.
        LoggerJNI.delete(handle);
      }
    }
  }
}
