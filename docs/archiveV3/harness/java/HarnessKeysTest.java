import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.StringUtil;

/** Standalone regression for private key-table permissions and rendered genesis identities. */
public final class HarnessKeysTest {

  private HarnessKeysTest() {
  }

  public static void main(String[] args) throws Exception {
    Path table = Paths.get(args[0]);
    require(Files.getPosixFilePermissions(table).equals(
        PosixFilePermissions.fromString("rw-------")), "key table permissions");
    List<String> keys = new ArrayList<>();
    List<String> addresses = new ArrayList<>();
    for (String row : Files.readAllLines(table)) {
      String[] fields = row.split(" ");
      require(fields.length == 3 && fields[0].length() == 64, "table row encoding");
      ECKey key = ECKey.fromPrivate(ByteArray.fromHexString(fields[0]));
      require(fields[1].equals(StringUtil.encode58Check(key.getAddress())), "base58 binding");
      require(fields[2].equals(ByteArray.toHexString(key.getAddress())), "hex binding");
      keys.add(fields[0]);
      addresses.add(fields[1]);
    }
    require(keys.size() == 33 && new HashSet<>(keys).size() == 33, "unique key count");
    require(new HashSet<>(addresses).size() == 33, "unique address count");
    if (args.length > 1) {
      Config config = ConfigFactory.parseFile(Paths.get(args[1]).toFile());
      int witnesses = Integer.parseInt(args[2]);
      int first = Integer.parseInt(args[3]);
      int last = Integer.parseInt(args[4]);
      List<String> local = config.getStringList("localwitness");
      require(local.equals(keys.subList(first - 1, last)), "local witness identity/order");
      List<? extends Config> genesis = config.getConfigList("genesis.block.witnesses");
      require(genesis.size() == witnesses, "genesis witness count");
      for (int i = 0; i < witnesses; i++) {
        require(genesis.get(i).getString("address").equals(addresses.get(i)), "genesis binding");
      }
      List<? extends Config> assets = config.getConfigList("genesis.block.assets");
      require(assets.get(0).getString("address").equals(addresses.get(27)), "funding account");
      require(assets.get(1).getString("address").equals(addresses.get(28)), "receiving account");
      require(config.getStringList("seed.node.ip.list").isEmpty(), "public seed isolation");
    }
    System.out.println("KEY_CONFIG_OK");
  }

  private static void require(boolean condition, String detail) {
    if (!condition) {
      throw new AssertionError(detail);
    }
  }
}
