package org.tron.plugins;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.roaringbitmap.longlong.Roaring64Bitmap;
import picocli.CommandLine;

public class DbChangeSetTest {

  CommandLine cli = new CommandLine(new Toolkit());


  @Test
  public void testHelp() {
    String[] args = new String[] {"db", "change-set-mock", "-h"};
    Assert.assertEquals(0, cli.execute(args));
  }

  @Test
  @Ignore
  public void test() {
    String[] args = new String[] {"db", "change-set-mock", "tron/change-set"};
   Assert.assertEquals(0, cli.execute(args));
  }

  @Test
  public void query() {
    String[] args = new String[] {"db", "change-set-query", "tron/change-set", "-db", "account", "-k", "0x41c686c48436aec3a1dfce4ac5a4526c39366985ba", "-b", "72642958"};
    Assert.assertEquals(0, cli.execute(args));
  }

  @Test
  public void testRoaringMap() {
    Roaring64Bitmap bitmap = new Roaring64Bitmap();
    bitmap.addLong(72613733L);
    bitmap.addLong(72613753L);
    Assert.assertEquals(72613733L, ceiling(bitmap, 72613732L));
    Assert.assertEquals(72613733L, ceiling(bitmap, 72613733L));
    Assert.assertEquals(72613753L, ceiling(bitmap, 72613734L));
    Assert.assertEquals(72613753L, ceiling(bitmap, 72613753L));
    Assert.assertEquals(-1, ceiling(bitmap, 72613754L));

  }

  public long ceiling(Roaring64Bitmap bitmap, long target) {
    if (bitmap.isEmpty()) {
      return -1;
    }
    if (bitmap.contains(target)) {
      return target;
    }
    long rank = bitmap.rankLong(target);
    if (rank == bitmap.getLongCardinality()) {
      return -1;
    }
    return bitmap.select(rank);
  }

}
