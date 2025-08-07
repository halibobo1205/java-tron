package org.tron.plugins;

import lombok.extern.slf4j.Slf4j;
import org.tron.common.arch.Arch;

/*
  ARM platforms support RocksDB only; this command is not needed but retained for compatibility.
 */
@Slf4j(topic = "archive")
public class ArchiveManifest  {

  public static void main(String[] args) {
    String tips = String.format("This tool is not supported on %s architecture.",
        Arch.getOsArch());
    System.err.println(tips);
    logger.error(tips);
  }

}
