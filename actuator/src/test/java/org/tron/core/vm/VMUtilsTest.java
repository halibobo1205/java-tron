package org.tron.core.vm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.tron.common.utils.ByteUtil;

public class VMUtilsTest {

  @Test
  public void compressShouldReleaseDeflater() throws Exception {
    try (MockedConstruction<Deflater> deflaters = mockConstruction(Deflater.class,
        (deflater, context) -> when(deflater.finished()).thenReturn(true))) {
      VMUtils.compress(new byte[0]);

      assertEquals(1, deflaters.constructed().size());
      verify(deflaters.constructed().get(0)).end();
    }
  }

  @Test
  public void compressShouldPreserveContent() throws Exception {
    byte[] content = "VM trace compression".getBytes(StandardCharsets.UTF_8);

    assertArrayEquals(content, ByteUtil.decompress(VMUtils.compress(content)));
  }
}
