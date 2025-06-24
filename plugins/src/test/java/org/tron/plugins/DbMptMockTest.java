package org.tron.plugins;

import org.junit.Assert;
import org.junit.Test;
import picocli.CommandLine;

public class DbMptMockTest {

  CommandLine cli = new CommandLine(new Toolkit());


  @Test
  public void testHelp() {
    String[] args = new String[] {"db", "mpt-mock", "-h"};
    Assert.assertEquals(0, cli.execute(args));
  }

  @Test
  public void test() {
    String[] args = new String[] {"db", "mpt-mock", "/Users/lizibo/tron/change-set"};
    Assert.assertEquals(0, cli.execute(args));
  }
}
