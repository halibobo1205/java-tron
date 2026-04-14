package org.tron.core.db;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import javax.annotation.Resource;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.tron.common.BaseTest;
import org.tron.common.TestConstants;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BytesCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.db.api.MigrateTurkishKeyHelper;
import org.tron.core.store.AccountIdIndexStore;
import org.tron.protos.Protocol.AccountType;

/**
 * Tests for {@link AccountIdIndexStore}, including the Turkish-I locale fix.
 *
 * <p>Background: {@code String.toLowerCase()} without an explicit
 * Locale uses {@code Locale.getDefault()}. On Turkish (tr) / Azerbaijani (az) locales,
 * uppercase 'I' (U+0049) folds to dotless 'ı' (U+0131) instead of 'i' (U+0069).
 * This caused different index keys on tr/az vs other nodes → consensus split.</p>
 *
 * <h3>Test scenario coverage</h3>
 * <pre>
 *  #1  getLowerCaseAccountId uses Locale.ROOT
 *  #2  Turkish locale produces different key
 *  #3  Normal put/get with random bytes
 *  #4  Normal put/has with random bytes
 *  #5  Case-insensitive: mixed/lower/upper lookup
 *  #6  Attack: uppercase put + lowercase has
 *  #7  No 'I': ROOT and Turkish keys identical
 *  #8  Turkish direct key behavior (toLowerCase(TURKISH))
 *  #9  Valid accountId range: only 'I' differs
 *  #10 Migration: mixed 'i'/'I' keys normalized to ROOT
 *  #11 Only lowercase 'i': Turkish key equals ROOT key
 * </pre>
 */
public class AccountIdIndexStoreTest extends BaseTest {

  private static final byte[] ACCOUNT_ADDRESS_ONE = randomBytes(16);
  private static final byte[] ACCOUNT_ADDRESS_TWO = randomBytes(16);
  private static final byte[] ACCOUNT_ADDRESS_THREE = randomBytes(16);
  private static final byte[] ACCOUNT_ADDRESS_FOUR = randomBytes(16);
  private static final byte[] ACCOUNT_NAME_ONE = randomBytes(6);
  private static final byte[] ACCOUNT_NAME_TWO = randomBytes(6);
  private static final byte[] ACCOUNT_NAME_THREE = randomBytes(6);
  private static final byte[] ACCOUNT_NAME_FOUR = randomBytes(6);
  private static final byte[] ACCOUNT_NAME_FIVE = randomBytes(6);
  private static final Locale TURKISH = Locale.forLanguageTag("tr");
  @Resource
  private AccountIdIndexStore accountIdIndexStore;
  private static AccountCapsule accountCapsule1;
  private static AccountCapsule accountCapsule2;
  private static AccountCapsule accountCapsule3;
  private static AccountCapsule accountCapsule4;

  static {
    Args.setParam(new String[]{"--output-directory", dbPath()},
        TestConstants.TEST_CONF);
  }

  @BeforeClass
  public static void init() {
    accountCapsule1 = new AccountCapsule(ByteString.copyFrom(ACCOUNT_ADDRESS_ONE),
        ByteString.copyFrom(ACCOUNT_NAME_ONE), AccountType.Normal);
    accountCapsule1.setAccountId(ByteString.copyFrom(ACCOUNT_NAME_ONE).toByteArray());
    accountCapsule2 = new AccountCapsule(ByteString.copyFrom(ACCOUNT_ADDRESS_TWO),
        ByteString.copyFrom(ACCOUNT_NAME_TWO), AccountType.Normal);
    accountCapsule2.setAccountId(ByteString.copyFrom(ACCOUNT_NAME_TWO).toByteArray());
    accountCapsule3 = new AccountCapsule(ByteString.copyFrom(ACCOUNT_ADDRESS_THREE),
        ByteString.copyFrom(ACCOUNT_NAME_THREE), AccountType.Normal);
    accountCapsule3.setAccountId(ByteString.copyFrom(ACCOUNT_NAME_THREE).toByteArray());
    accountCapsule4 = new AccountCapsule(ByteString.copyFrom(ACCOUNT_ADDRESS_FOUR),
        ByteString.copyFrom(ACCOUNT_NAME_FOUR), AccountType.Normal);
    accountCapsule4.setAccountId(ByteString.copyFrom(ACCOUNT_NAME_FOUR).toByteArray());

  }

  @Before
  public void before() {
    accountIdIndexStore.put(accountCapsule1);
    accountIdIndexStore.put(accountCapsule2);
    accountIdIndexStore.put(accountCapsule3);
    accountIdIndexStore.put(accountCapsule4);
  }

  public static byte[] randomBytes(int length) {
    // generate the random number
    byte[] result = new byte[length];
    new Random().nextBytes(result);
    result[0] = Wallet.getAddressPreFixByte();
    return result;
  }

  /** Scenario #3: normal put/get with random bytes. */
  @Test
  public void putAndGet() {
    byte[] address = accountIdIndexStore.get(ByteString.copyFrom(ACCOUNT_NAME_ONE));
    Assert.assertArrayEquals("putAndGet1", ACCOUNT_ADDRESS_ONE, address);
    address = accountIdIndexStore.get(ByteString.copyFrom(ACCOUNT_NAME_TWO));
    Assert.assertArrayEquals("putAndGet2", ACCOUNT_ADDRESS_TWO, address);
    address = accountIdIndexStore.get(ByteString.copyFrom(ACCOUNT_NAME_THREE));
    Assert.assertArrayEquals("putAndGet3", ACCOUNT_ADDRESS_THREE, address);
    address = accountIdIndexStore.get(ByteString.copyFrom(ACCOUNT_NAME_FOUR));
    Assert.assertArrayEquals("putAndGet4", ACCOUNT_ADDRESS_FOUR, address);
    address = accountIdIndexStore.get(ByteString.copyFrom(ACCOUNT_NAME_FIVE));
    Assert.assertNull("putAndGet4", address);

  }

  /** Scenario #4: normal put/has with random bytes. */
  @Test
  public void putAndHas() {
    boolean result = accountIdIndexStore.has(ACCOUNT_NAME_ONE);
    Assert.assertTrue("putAndGet1", result);
    result = accountIdIndexStore.has(ACCOUNT_NAME_TWO);
    Assert.assertTrue("putAndGet2", result);
    result = accountIdIndexStore.has(ACCOUNT_NAME_THREE);
    Assert.assertTrue("putAndGet3", result);
    result = accountIdIndexStore.has(ACCOUNT_NAME_FOUR);
    Assert.assertTrue("putAndGet4", result);
    result = accountIdIndexStore.has(ACCOUNT_NAME_FIVE);
    Assert.assertFalse("putAndGet4", result);
  }

  /** Scenario #5: case-insensitive lookup with mixed/lower/upper. */
  @Test
  public void testCaseInsensitive() {
    byte[] accountName = "aABbCcDd_ssd1234".getBytes();
    byte[] accountAddress = randomBytes(16);

    AccountCapsule accountCapsule = new AccountCapsule(ByteString.copyFrom(accountAddress),
        ByteString.copyFrom(accountName), AccountType.Normal);
    accountCapsule.setAccountId(ByteString.copyFrom(accountName).toByteArray());
    accountIdIndexStore.put(accountCapsule);

    Boolean result = accountIdIndexStore.has(accountName);
    Assert.assertTrue("fail", result);

    byte[] lowerCase = ByteString
        .copyFromUtf8(ByteString.copyFrom(accountName).toStringUtf8().toLowerCase(Locale.ROOT))
        .toByteArray();
    result = accountIdIndexStore.has(lowerCase);
    Assert.assertTrue("lowerCase fail", result);

    byte[] upperCase = ByteString
        .copyFromUtf8(ByteString.copyFrom(accountName).toStringUtf8().toUpperCase(Locale.ROOT))
        .toByteArray();
    result = accountIdIndexStore.has(upperCase);
    Assert.assertTrue("upperCase fail", result);

    Assert.assertNotNull("getLowerCase fail", accountIdIndexStore.get(upperCase));

  }

  /**
   * Scenario #1 and #2: getLowerCaseAccountId uses Locale.ROOT, and Turkish
   * locale would produce a different key for input containing 'I'.
   */
  @Test
  public void testLocaleIndependentLowerCase() {
    byte[] accountId = "AAAAAAAI".getBytes(StandardCharsets.UTF_8);
    byte[] expected = "aaaaaaai".getBytes(StandardCharsets.UTF_8);

    // #1: getLowerCaseAccountId must always produce standard ASCII lowercase
    byte[] actual = AccountIdIndexStore.getLowerCaseAccountId(accountId);
    Assert.assertArrayEquals(
        "getLowerCaseAccountId must use Locale.ROOT to avoid Turkish-I problem",
        expected, actual);

    // #2: Turkish locale produces a different key (dotless-ı)
    @SuppressWarnings("StringCaseLocaleUsage")
    String turkishLower = new String(accountId, StandardCharsets.UTF_8)
        .toLowerCase(TURKISH);
    byte[] turkishKey = turkishLower.getBytes(StandardCharsets.UTF_8);
    Assert.assertFalse(
        "Turkish locale toLowerCase must differ from Locale.ROOT for input containing 'I'",
        Arrays.equals(expected, turkishKey));
  }

  /**
   * Scenario #6: consensus-split attack — uppercase put, lowercase has/get.
   *
   * <pre>
   * 1. T1(accountId="AAAAAAAI") put → stored key "aaaaaaai"
   * 2. Attacker submits T2(accountId="aaaaaaai")
   * 3. has("aaaaaaai") must return true → reject T2
   * </pre>
   */
  @Test
  public void testDuplicateAccountIdDetection() {
    byte[] upperCaseId = "AAAAAAAI".getBytes(StandardCharsets.UTF_8);
    byte[] lowerCaseId = "aaaaaaai".getBytes(StandardCharsets.UTF_8);
    byte[] address = randomBytes(16);

    AccountCapsule capsule = new AccountCapsule(
        ByteString.copyFrom(address),
        ByteString.copyFrom(upperCaseId), AccountType.Normal);
    capsule.setAccountId(ByteString.copyFrom(upperCaseId).toByteArray());

    accountIdIndexStore.put(capsule);

    Assert.assertTrue(
        "has() must detect duplicate accountId regardless of case",
        accountIdIndexStore.has(lowerCaseId));
    Assert.assertTrue(
        "has() must detect duplicate accountId with original case",
        accountIdIndexStore.has(upperCaseId));

    Assert.assertNotNull(
        "get() must find accountId regardless of case",
        accountIdIndexStore.get(lowerCaseId));
    Assert.assertArrayEquals(
        "get() must return correct address for case-insensitive lookup",
        address, accountIdIndexStore.get(ByteString.copyFrom(lowerCaseId)));
  }

  /**
   * Scenario #7: input without 'I' — ROOT and Turkish keys must be identical.
   */
  @Test
  public void testNoTurkishICharacter() {
    byte[] accountId = "ABCDEFGH".getBytes(StandardCharsets.UTF_8);
    byte[] rootKey = AccountIdIndexStore.getLowerCaseAccountId(accountId);

    // Turkish toLowerCase of a string without 'I' should equal ROOT toLowerCase
    @SuppressWarnings("StringCaseLocaleUsage")
    byte[] turkishKey = ByteString
        .copyFromUtf8(new String(accountId, StandardCharsets.UTF_8).toLowerCase(TURKISH))
        .toByteArray();

    Assert.assertArrayEquals(
        "Without 'I', ROOT and Turkish keys must be identical",
        rootKey, turkishKey);
  }

  /**
   * Scenario #8: Turkish direct key — toLowerCase(TURKISH) produces
   * different results for inputs containing uppercase 'I'.
   *
   * <pre>
   * +-------+----------------+---------------------+
   * | Case  | Input          | toLower(TURKISH)    |
   * +-------+----------------+---------------------+
   * |  #8a  | "AAAAAAAI"     | "aaaaaaaı"          |
   * |  #8b  | "aaaaaaai"     | "aaaaaaai"          |
   * |  #8c  | "AaAaAaAI"     | "aaaaaaaı"          |
   * |  #8d  | "AiBI"         | "aibı"              |
   * +-------+----------------+---------------------+
   * </pre>
   */
  @Test
  @SuppressWarnings("StringCaseLocaleUsage")
  public void testTurkishDirectKey() {
    // #8a: all uppercase with 'I' → 'ı'
    Assert.assertEquals("aaaaaaaı",
        "AAAAAAAI".toLowerCase(TURKISH));
    // #8b: all lowercase — 'i' stays 'i'
    Assert.assertEquals("aaaaaaai",
        "aaaaaaai".toLowerCase(TURKISH));
    // #8c: mixed case with uppercase 'I'
    Assert.assertEquals("aaaaaaaı",
        "AaAaAaAI".toLowerCase(TURKISH));
    // #8d: mixed i/I — each mapped independently
    Assert.assertEquals("aibı",
        "AiBI".toLowerCase(TURKISH));
  }

  /**
   * Scenario #9: verify which characters in the valid accountId range
   * (0x21 '!' to 0x7E '~') differ between ROOT and Turkish toLowerCase.
   * Expected: only 'I' (U+0049) differs.
   *
   * @see org.tron.core.utils.TransactionUtil#validReadableBytes
   */
  @Test
  @SuppressWarnings("StringCaseLocaleUsage")
  public void testTurkishLowerCaseDiffForValidAccountIdRange() {
    StringBuilder diffChars = new StringBuilder();
    // 0x21 ('!') to 0x7E ('~') — the full valid accountId byte range
    for (char c = 0x21; c <= 0x7E; c++) {
      String s = String.valueOf(c);
      String rootLower = s.toLowerCase(Locale.ROOT);
      String turkishLower = s.toLowerCase(TURKISH);
      if (!rootLower.equals(turkishLower)) {
        diffChars.append(String.format(
            "'%c'(0x%02X): ROOT='%s'(U+%04X) vs TR='%s'(U+%04X); ",
            c, (int) c,
            rootLower, (int) rootLower.charAt(0),
            turkishLower, (int) turkishLower.charAt(0)));
      }
    }

    String diff = diffChars.toString().trim();
    // Expect exactly one diff entry: 'I'
    Assert.assertEquals(
        "Only 'I' should differ in valid accountId range. Actual: " + diff,
        "'I'(0x49): ROOT='i'(U+0069) vs TR='"
            + "\u0131'(U+0131);", diff); // ı Turkish dotless-i
  }

  /**
   * Scenario #10: Turkish key migration — mixed 'i' and 'I' (like "AIBi", "AiBI").
   * After {@link MigrateTurkishKeyHelper#doWork()}, all Turkish keys are
   * normalized to ROOT, so all query case variants work.
   *
   * <pre>
   * stored(1): "EEIEEi".toLower(TR) = "eeıeei" → migrated to "eeieei"
   * stored(2): "FFiFFI".toLower(TR) = "ffiffı" → migrated to "ffiffi"
   * </pre>
   */
  @Test
  @SuppressWarnings("StringCaseLocaleUsage")
  public void testMigrateTurkishKeysMixedCase() {
    // --- pattern 1: uppercase I before lowercase i ("EEIEEi") ---
    byte[] accountId1 = "EEIEEi".getBytes(StandardCharsets.UTF_8);
    byte[] address1 = randomBytes(16);
    byte[] legacyKey1 = new String(accountId1, StandardCharsets.UTF_8)
        .toLowerCase(TURKISH).getBytes(StandardCharsets.UTF_8);
    accountIdIndexStore.put(legacyKey1, new BytesCapsule(address1));

    // --- pattern 2: lowercase i before uppercase I ("FFiFFI") ---
    byte[] accountId2 = "FFiFFI".getBytes(StandardCharsets.UTF_8);
    byte[] address2 = randomBytes(16);
    byte[] legacyKey2 = new String(accountId2, StandardCharsets.UTF_8)
        .toLowerCase(TURKISH).getBytes(StandardCharsets.UTF_8);
    accountIdIndexStore.put(legacyKey2, new BytesCapsule(address2));

    // Before migration: ROOT key lookup misses (key contains ı not i)
    Assert.assertFalse("pre-migrate: has(eeieei) should miss",
        accountIdIndexStore.has("eeieei".getBytes(StandardCharsets.UTF_8)));
    Assert.assertFalse("pre-migrate: has(ffiffi) should miss",
        accountIdIndexStore.has("ffiffi".getBytes(StandardCharsets.UTF_8)));

    // Run migration via the standard helper
    new MigrateTurkishKeyHelper(chainBaseManager).doWork();

    // After migration: all queries work via ROOT key
    Assert.assertTrue("post-migrate: has(EEIEEi)",
        accountIdIndexStore.has(accountId1));
    Assert.assertTrue("post-migrate: has(eeieei)",
        accountIdIndexStore.has("eeieei".getBytes(StandardCharsets.UTF_8)));
    Assert.assertArrayEquals("post-migrate: get(eeieei)",
        address1, accountIdIndexStore.get(ByteString.copyFrom(
            "eeieei".getBytes(StandardCharsets.UTF_8))));

    Assert.assertTrue("post-migrate: has(FFiFFI)",
        accountIdIndexStore.has(accountId2));
    Assert.assertTrue("post-migrate: has(ffiffi)",
        accountIdIndexStore.has("ffiffi".getBytes(StandardCharsets.UTF_8)));
    Assert.assertArrayEquals("post-migrate: get(ffiffi)",
        address2, accountIdIndexStore.get(ByteString.copyFrom(
            "ffiffi".getBytes(StandardCharsets.UTF_8))));

    // Verify migration wrote ROOT keys (replace 'ı' → 'i')
    byte[] rootKey1 = new String(legacyKey1, StandardCharsets.UTF_8)
        .replace('\u0131', 'i') // ı Turkish dotless-i
        .getBytes(StandardCharsets.UTF_8);
    byte[] rootKey2 = new String(legacyKey2, StandardCharsets.UTF_8)
        .replace('\u0131', 'i') // ı Turkish dotless-i
        .getBytes(StandardCharsets.UTF_8);
    // ROOT keys must exist and return correct addresses
    Assert.assertArrayEquals("post-migrate: ROOT key1 must exist",
        address1, accountIdIndexStore.get(ByteString.copyFrom(rootKey1)));
    Assert.assertArrayEquals("post-migrate: ROOT key2 must exist",
        address2, accountIdIndexStore.get(ByteString.copyFrom(rootKey2)));
  }

  /**
   * Scenario #11: mainnet data — simulate Turkish locale writes for all 14
   * mainnet accountId keys, run migration, verify all queries succeed.
   *
   * <p>Mainnet keys (already lowercase, as stored in DB):
   * "", "12345678", "543838383", "bittorrent", "converse", "helloworld",
   * "infstonessrwallet", "issrwallet", "justdoit", "justinsun",
   * "justinsuntron", "rtytiturtet", "tronbetfestival", "vena_family"
   *
   * <p>Of these, 10 contain lowercase 'i'. On a Turkish node, the original
   * accountId (which may have uppercase letters) would produce keys with
   * 'ı' instead of 'i'. This test simulates that scenario.
   *
   * <pre>
   * Phase 1: write keys as a Turkish node would (toLowerCase(TURKISH))
   * Phase 2: verify ROOT-based queries miss for keys containing 'I'
   * Phase 3: run MigrateTurkishKeyHelper
   * Phase 4: verify all ROOT-based queries succeed
   * </pre>
   */
  @Test
  @SuppressWarnings("StringCaseLocaleUsage")
  public void testMainnetKeysMigration() {
    // Original accountIds as they might have been submitted (mixed case)
    // The lowercase versions match the 14 mainnet keys observed in production
    String[] mainnetAccountIds = {
        "12345678",       // no letters
        "543838383",      // no letters
        "BitTorrent",     // contains I → Turkish key differs
        "Converse",       // no I
        "HelloWorld",     // no I
        "InfStonesSSRWallet", // contains I → Turkish key differs
        "ISSRWallet",     // contains I → Turkish key differs
        "JustDoIt",       // contains I → Turkish key differs
        "JustinSun",      // no I
        "JustinSunTron",  // no I
        "RtytIturtet",    // contains I → Turkish key differs
        "TronBetFestival",// no I
        "vena_family"     // no I, all lowercase
    };

    byte[][] addresses = new byte[mainnetAccountIds.length][];
    byte[][] turkishKeys = new byte[mainnetAccountIds.length][];

    // Phase 1: simulate Turkish node writes
    for (int i = 0; i < mainnetAccountIds.length; i++) {
      addresses[i] = randomBytes(16);
      String turkishLower = mainnetAccountIds[i].toLowerCase(TURKISH);
      turkishKeys[i] = turkishLower.getBytes(StandardCharsets.UTF_8);
      accountIdIndexStore.put(turkishKeys[i], new BytesCapsule(addresses[i]));
    }

    // Phase 2: verify which keys are findable via ROOT lookup before migration
    for (int i = 0; i < mainnetAccountIds.length; i++) {
      String rootLower = mainnetAccountIds[i].toLowerCase(Locale.ROOT);
      String turkishLower = mainnetAccountIds[i].toLowerCase(TURKISH);
      boolean shouldMiss = !rootLower.equals(turkishLower);
      if (shouldMiss) {
        Assert.assertNull(
            "pre-migrate: ROOT query should miss for " + mainnetAccountIds[i],
            accountIdIndexStore.get(ByteString.copyFrom(
                mainnetAccountIds[i].getBytes(StandardCharsets.UTF_8))));
      } else {
        Assert.assertArrayEquals(
            "pre-migrate: ROOT query should hit for " + mainnetAccountIds[i],
            addresses[i],
            accountIdIndexStore.get(ByteString.copyFrom(
                mainnetAccountIds[i].getBytes(StandardCharsets.UTF_8))));
      }
    }

    // Phase 3: run migration
    new MigrateTurkishKeyHelper(chainBaseManager).doWork();

    // Phase 4: verify ALL queries succeed after migration
    for (int i = 0; i < mainnetAccountIds.length; i++) {
      // Original case query
      Assert.assertArrayEquals(
          "post-migrate: get(" + mainnetAccountIds[i] + ")",
          addresses[i],
          accountIdIndexStore.get(ByteString.copyFrom(
              mainnetAccountIds[i].getBytes(StandardCharsets.UTF_8))));
      // All-lowercase query
      String lower = mainnetAccountIds[i].toLowerCase(Locale.ROOT);
      Assert.assertTrue(
          "post-migrate: has(" + lower + ")",
          accountIdIndexStore.has(lower.getBytes(StandardCharsets.UTF_8)));
      // All-uppercase query
      String upper = mainnetAccountIds[i].toUpperCase(Locale.ROOT);
      Assert.assertTrue(
          "post-migrate: has(" + upper + ")",
          accountIdIndexStore.has(upper.getBytes(StandardCharsets.UTF_8)));
    }
  }

  /**
   * Scenario #12: accountId with only lowercase 'i' (like "Ai", "AiBi").
   * Turkish node stored the same key as ROOT — no migration needed.
   */
  @Test
  @SuppressWarnings("StringCaseLocaleUsage")
  public void testLocaleMigrationOnlyLowerI() {
    byte[] accountId = "GGiGGi".getBytes(StandardCharsets.UTF_8);
    byte[] address = randomBytes(16);

    // Turkish key = ROOT key (lowercase 'i' is unaffected by Turkish locale)
    byte[] legacyKey = new String(accountId, StandardCharsets.UTF_8)
        .toLowerCase(TURKISH).getBytes(StandardCharsets.UTF_8);
    byte[] rootKey = AccountIdIndexStore.getLowerCaseAccountId(accountId);
    Assert.assertArrayEquals("Only lowercase 'i': Turkish key must equal ROOT key",
        rootKey, legacyKey);

    accountIdIndexStore.put(legacyKey, new BytesCapsule(address));

    // Any case variant works via ROOT key
    Assert.assertArrayEquals("get(same-case)", address,
        accountIdIndexStore.get(ByteString.copyFrom(accountId)));
    Assert.assertArrayEquals("get(lowercase)", address,
        accountIdIndexStore.get(ByteString.copyFrom(
            "ggiggi".getBytes(StandardCharsets.UTF_8))));
    Assert.assertArrayEquals("get(uppercase)", address,
        accountIdIndexStore.get(ByteString.copyFrom(
            "GGIGGI".getBytes(StandardCharsets.UTF_8))));
  }
}
