package org.tron.program;

import com.google.protobuf.ByteString;
import java.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.crypto.SignUtils;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.capsule.TransactionCapsule;

@Slf4j(topic = "app")
public class FullNode {

  /**
   * Start the FullNode.
   */
  public static void main(String[] args) throws SignatureException, InterruptedException {
    ByteString signature = ByteString.copyFrom(ByteArray.fromHexString(
        "6f9ef9d226dc87bceb571c859614fa7dcdbe0be6e1dfea54fb99cb997"
            + "0fa09af91a945e3b0eb1eea559c89cc4bd16932bfabcf0e63a0e0848fbb7fa0db4dcfd600"));
    Sha256Hash hash = Sha256Hash.wrap(ByteArray.fromHexString(
        "73350db08350056f128734ec26444ea549299256ea99e0aaab7f5ad60d0d552a"));
    while (true) {
      String base64 = TransactionCapsule.getBase64FromByteString(signature);
      SignUtils.signatureToAddress(hash.getBytes(), base64, true);
      Thread.sleep(100);
    }
  }
}
