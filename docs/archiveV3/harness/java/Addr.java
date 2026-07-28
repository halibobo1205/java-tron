/*
 * Archive fault-injection harness helper.
 *
 * Derives the TRON address of a raw private key, exactly the way the node does
 * (crypto/src/main/java/org/tron/common/crypto/ECKey.java +
 *  common/src/main/java/org/tron/common/utils/StringUtil.java).
 *
 * Usage:
 *   javac -cp FullNode.jar -d classes Addr.java
 *   java  -cp classes:FullNode.jar Addr <privateKeyHex64>
 *
 * Prints one line:  <base58check> <hex41>
 *
 * The harness uses this instead of hard-coding addresses so that a changed key
 * table fails loudly rather than silently funding the wrong account.
 */
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.StringUtil;

public final class Addr {

  private Addr() {
  }

  public static void main(String[] args) {
    if (args.length != 1) {
      System.err.println("usage: Addr <privateKeyHex64>");
      System.exit(2);
    }
    String hex = args[0].trim();
    if (hex.startsWith("0x") || hex.startsWith("0X")) {
      hex = hex.substring(2);
    }
    if (hex.length() != 64) {
      System.err.println("private key must be 64 hex chars, got " + hex.length());
      System.exit(2);
    }
    ECKey key = ECKey.fromPrivate(ByteArray.fromHexString(hex));
    byte[] address = key.getAddress();
    System.out.println(StringUtil.encode58Check(address) + " " + ByteArray.toHexString(address));
  }
}
