import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.StringUtil;

/** Generates a run-local key table: 27 witnesses, two funded accounts and four recipients. */
public final class HarnessKeys {

  private HarnessKeys() {
  }

  public static void main(String[] args) {
    SecureRandom random = new SecureRandom();
    Set<String> seen = new HashSet<>();
    while (seen.size() < 33) {
      ECKey key = new ECKey(random);
      String secret = ByteArray.toHexString(key.getPrivKeyBytes());
      if (seen.add(secret)) {
        byte[] address = key.getAddress();
        System.out.println(secret + " " + StringUtil.encode58Check(address)
            + " " + ByteArray.toHexString(address));
      }
    }
  }
}
