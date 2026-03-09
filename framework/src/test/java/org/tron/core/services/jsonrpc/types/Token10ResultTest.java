package org.tron.core.services.jsonrpc.types;

import org.junit.Assert;
import org.junit.Test;
import org.tron.protos.Protocol;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class Token10ResultTest {

  @Test
  public void testToken10Result() {
    Map<String, Long> assetV2 =  new HashMap<>();
    assetV2.put("1000387", 0L); // 0xf43c3
    assetV2.put("1000491", 300L); // 0xf442b, 0x12c
    assetV2.put("1000542", 100L); // 0xf445e, 0x64
    assetV2.put("1002798", 200L); // 0xf4d2e, 0xc8

    Token10Result token10Result = new Token10Result("1000928", 400L);

    Assert.assertEquals("0xf45e0", token10Result.getKey());
    Assert.assertEquals("0x190", token10Result.getValue());

    Protocol.Account account = Protocol.Account.newBuilder()
        .putAllAssetV2(assetV2)
        .build();
    List<Token10Result>  token10Results = Token10Result.toHex(account.getAssetV2Map());
    Assert.assertEquals(3, token10Results.size());
    Assert.assertFalse(token10Results.stream().anyMatch(e -> e.getKey().equals("0xf43c3")));
    Optional<Token10Result> head = token10Results.stream().findFirst();
    Assert.assertTrue(head.isPresent());
    head.ifPresent(e -> {
      Assert.assertEquals("0xf442b", e.getKey());
      Assert.assertEquals("0x12c", e.getValue());
    });
    Optional<Token10Result> second = token10Results.stream().skip(1).findFirst();
    Assert.assertTrue(second.isPresent());
    second.ifPresent(e -> {
      Assert.assertEquals("0xf445e", e.getKey());
      Assert.assertEquals("0x64", e.getValue());
    });
  Optional<Token10Result> third = token10Results.stream().skip(2).findFirst();
    Assert.assertTrue(third.isPresent());
    third.ifPresent(e -> {
      Assert.assertEquals("0xf4d2e", e.getKey());
      Assert.assertEquals("0xc8", e.getValue());
    });
  }
}
