package org.tron.plugins;

import lombok.extern.slf4j.Slf4j;
import org.tron.common.arch.Arch;

/*
  ARM platform only supports RocksDB, this command is not supported but retained for compatibility.
 */
@Slf4j(topic = "archive")
public class ArchiveManifest  {

  public static void main(String[] args) {
    int exitCode = run(args);
    System.exit(exitCode);
  }

  public static int run(String[] args) {
    String tips = String.format("This tool is not supported on %s architecture.",
        Arch.getOsArch());
    System.out.println(tips);
    logger.warn(tips);
    return 0;
  }

}
