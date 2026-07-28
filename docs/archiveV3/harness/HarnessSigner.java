/*
 * Minimal client-side key/signing helper for the archive fault harness.
 *
 * WHY THIS EXISTS
 *   There is no `gettransactionsign` servlet in framework/src/main/java/org/tron/core/services/http,
 *   so a shell harness cannot ask the node to sign for it.  Signing must happen client side.  This
 *   helper mirrors chainbase/src/main/java/org/tron/core/capsule/TransactionCapsule.java:589-595
 *   exactly (sign the txID hash, base64 -> 65 raw bytes, hex encode).
 *
 * USAGE
 *   javac -cp <FullNode.jar> -d <outdir> HarnessSigner.java
 *   java  -cp <FullNode.jar>:<outdir> HarnessSigner addr <privHex>
 *   java  -cp <FullNode.jar>:<outdir> HarnessSigner sign <privHex> <txIdHex>
 *
 *   `addr` prints "<base58>\t<hex41>\t<0x-eth-address>" so a script can pick the encoding each API
 *   needs (base58 with visible:true, hex41 without).
 *   `sign` prints the 65-byte signature as lowercase hex, ready for the transaction's
 *   `signature: ["..."]` array.
 */

import org.tron.common.crypto.ECKey;
import org.tron.common.crypto.SignInterface;
import org.tron.common.crypto.SignUtils;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.StringUtil;

public final class HarnessSigner {

  private HarnessSigner() {
  }

  public static void main(String[] args) {
    if (args.length < 2) {
      System.err.println("usage: HarnessSigner addr <privHex> | sign <privHex> <txIdHex>");
      System.exit(2);
      return;
    }
    try {
      if ("addr".equals(args[0])) {
        printAddress(args[1]);
      } else if ("sign".equals(args[0])) {
        if (args.length < 3) {
          System.err.println("usage: HarnessSigner sign <privHex> <txIdHex>");
          System.exit(2);
          return;
        }
        printSignature(args[1], args[2]);
      } else {
        System.err.println("unknown command: " + args[0]);
        System.exit(2);
      }
    } catch (RuntimeException failure) {
      System.err.println("HarnessSigner failed: " + failure);
      System.exit(1);
    }
  }

  private static void printAddress(String privHex) {
    ECKey key = ECKey.fromPrivate(ByteArray.fromHexString(privHex));
    byte[] address = key.getAddress();
    String hex41 = ByteArray.toHexString(address);
    // A TRON address is 0x41 || 20 ethereum-style bytes; the JSON-RPC surface wants the 20 bytes.
    String eth = "0x" + hex41.substring(2);
    System.out.println(StringUtil.encode58Check(address) + "\t" + hex41 + "\t" + eth);
  }

  private static void printSignature(String privHex, String txIdHex) {
    SignInterface signer = SignUtils.fromPrivate(ByteArray.fromHexString(privHex), true);
    byte[] signature = signer.Base64toBytes(signer.signHash(ByteArray.fromHexString(txIdHex)));
    System.out.println(ByteArray.toHexString(signature));
  }
}
