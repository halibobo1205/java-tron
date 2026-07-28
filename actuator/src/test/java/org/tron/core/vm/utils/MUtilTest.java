package org.tron.core.vm.utils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.Test;
import org.tron.core.vm.repository.Repository;

public class MUtilTest {

  @Test
  public void archiveSelfdestructTokenSweepUsesCompleteRepositoryEnumeration() {
    Repository archive = mock(Repository.class);
    byte[] from = new byte[21];
    byte[] to = new byte[21];
    to[20] = 1;
    byte[] tokenId = "1000001".getBytes(StandardCharsets.US_ASCII);
    when(archive.isHistoricalArchive()).thenReturn(true);
    when(archive.getTokenBalances(from))
        .thenReturn(Collections.singletonMap("1000001", 77L));

    MUtil.transferAllToken(archive, from, to);

    verify(archive).getTokenBalances(from);
    verify(archive).addTokenBalance(to, tokenId, 77L);
    verify(archive).addTokenBalance(from, tokenId, -77L);
    verify(archive, org.mockito.Mockito.never()).getAccount(any(byte[].class));
  }
}
