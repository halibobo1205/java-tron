package org.tron.core.vm.trace;

import static org.junit.Assert.assertEquals;
import static org.tron.common.utils.ByteArray.toHexString;

import java.util.Arrays;
import org.junit.Test;

public class OpActionsTest {

  @Test
  public void memoryWriteEncodesRequestedByteCount() {
    OpActions actions = new OpActions();
    actions.addMemoryWrite(0, new byte[] {(byte) 0xab}, 1);

    byte[] word = new byte[32];
    Arrays.fill(word, (byte) 0xcd);
    actions.addMemoryWrite(1, word, word.length);

    assertEquals("ab", actions.getMemory().get(0).getParams().get("data"));
    assertEquals(toHexString(word), actions.getMemory().get(1).getParams().get("data"));
  }
}
