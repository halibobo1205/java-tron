package org.tron.core.block;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;
import org.tron.common.crypto.SignUtils;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.TransactionCapsule;

@Slf4j
public class TestVerifySignature {

  @Test
  public void testVerifySignature() {
    ByteString signature = ByteString.copyFrom(ByteArray.fromHexString(
         "6f9ef9d226dc87bceb571c859614fa7dcdbe0be6e1dfea54fb99cb997"
             + "0fa09af91a945e3b0eb1eea559c89cc4bd16932bfabcf0e63a0e0848fbb7fa0db4dcfd600"));
    Sha256Hash hash = Sha256Hash.wrap(ByteArray.fromHexString(
        "73350db08350056f128734ec26444ea549299256ea99e0aaab7f5ad60d0d552a"));
    for (int i = 0; i < 10; i++) {
      try {
        long sign = System.currentTimeMillis();
        String base64 = TransactionCapsule.getBase64FromByteString(signature);
        byte[] puk = SignUtils.signatureToAddress(hash.getBytes(), base64, true);
        long end = System.currentTimeMillis();
        String addr = StringUtil.encode58Check(puk);
        System.out.println(addr + " : " + hash + " : " + (end - sign) + "ms");
      } catch (Exception e) {
        Assert.fail("Failed to verify signature: " + e.getMessage());
      }
    }

  }

}
