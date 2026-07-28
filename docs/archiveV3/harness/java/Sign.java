/*
 * Archive fault-injection harness helper.
 *
 * Signs a transaction id with a raw private key, mirroring
 * chainbase/src/main/java/org/tron/core/capsule/TransactionCapsule.java#sign:
 *
 *   SignInterface cryptoEngine = SignUtils.fromPrivate(privateKey, isECKeyCryptoEngine);
 *   ByteString sig = ByteString.copyFrom(
 *       cryptoEngine.Base64toBytes(cryptoEngine.signHash(getRawHash().getBytes())));
 *
 * The node has no /wallet/gettransactionsign servlet, so signing must happen
 * client side. The txID returned by /wallet/createtransaction IS the raw hash.
 *
 * Usage:
 *   javac -cp FullNode.jar -d classes Sign.java
 *   java  -cp classes:FullNode.jar Sign <privateKeyHex64> <txIdHex64>
 *
 * Prints the 65-byte signature as lowercase hex on one line.
 */
import org.tron.common.crypto.SignInterface;
import org.tron.common.crypto.SignUtils;
import org.tron.common.utils.ByteArray;

public final class Sign {

  private Sign() {
  }

  public static void main(String[] args) {
    if (args.length != 2) {
      System.err.println("usage: Sign <privateKeyHex64> <txIdHex64>");
      System.exit(2);
    }
    String privHex = strip(args[0]);
    String txIdHex = strip(args[1]);
    if (privHex.length() != 64) {
      System.err.println("private key must be 64 hex chars, got " + privHex.length());
      System.exit(2);
    }
    if (txIdHex.length() != 64) {
      System.err.println("txID must be 64 hex chars, got " + txIdHex.length());
      System.exit(2);
    }
    // true == ECKey crypto engine (the non-SM2 default; see CommonParameter#isECKeyCryptoEngine).
    SignInterface engine = SignUtils.fromPrivate(ByteArray.fromHexString(privHex), true);
    byte[] signature = engine.Base64toBytes(engine.signHash(ByteArray.fromHexString(txIdHex)));
    if (signature == null || signature.length != 65) {
      System.err.println("unexpected signature length: "
          + (signature == null ? "null" : String.valueOf(signature.length)));
      System.exit(2);
    }
    System.out.println(ByteArray.toHexString(signature));
  }

  private static String strip(String value) {
    String out = value.trim();
    if (out.startsWith("0x") || out.startsWith("0X")) {
      out = out.substring(2);
    }
    return out;
  }
}
