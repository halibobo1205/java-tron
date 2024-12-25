package org.tron.plugins;

import java.io.IOException;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.iq80.leveldb.DB;
import org.tron.plugins.utils.DBUtils;
import org.tron.plugins.utils.Sha256Hash;
import picocli.CommandLine;

@Slf4j(topic = "miss")
@CommandLine.Command(name = "miss",
    description = "miss data for bench.",
    exitCodeListHeading = "Exit Codes:%n",
    exitCodeList = {
        "0:Successful",
        "1:Internal error: exception occurred,please check toolkit.log"})
public class DbMiss implements Callable<Integer> {

  @CommandLine.Spec
  CommandLine.Model.CommandSpec spec;

  @CommandLine.Option(
      names = {"--data", "-d"},
      description = "parentPath.",
      defaultValue = "output-directory/database")
  private String parentPath;

  @CommandLine.Option(
      names = {"--name"},
      description = "db name.",
      required = true)
  private String dbName;

  @CommandLine.Option(
      names = {"--count", "-cnt"},
      description = "key count.",
      defaultValue = "100000"
  )
  private int count;

  @CommandLine.Option(names = {"--help", "-h"})
  private boolean help;

  @Override
  public Integer call() throws IOException {
    if (help) {
      spec.commandLine().usage(System.out);
      return 0;
    }
    spec.commandLine().getOut().println("generate key start");
    List<byte[]> list = randomKey();
    spec.commandLine().getOut().println("generate key end");
    try (DB db = DBUtils.newLevelDb(Paths.get(parentPath, dbName))) {
      double avg = query(list, db) / 1_000_000;
      spec.commandLine().getOut().println("avg: " + avg + "ms");
      return 0;
    } catch (Exception e) {
      spec.commandLine().getErr().println(spec.commandLine().getColorScheme()
          .errorText(e.getMessage()));
      spec.commandLine().usage(System.out);
      return 1;
    }
  }

  private double query(List<byte[]> list, DB db) {
    return list.stream().mapToDouble(k -> {
      long beg = System.nanoTime();
      db.get(k);
      return System.nanoTime() - beg;
    }).average().orElse(0.0);
  }

  private List<byte[]> randomKey() {
    int length = 64;
    SecureRandom random = new SecureRandom();
    return IntStream.range(0, count).parallel().mapToObj(i -> {
      byte[] randomBytes = new byte[length];
      random.nextBytes(randomBytes);
      return Sha256Hash.of(true, randomBytes);
    }).map(Sha256Hash::getBytes).collect(Collectors.toList());
  }
}