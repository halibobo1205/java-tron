package org.tron.core.store;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BytesCapsule;
import org.tron.core.db.TronStoreWithRevoking;

@Slf4j(topic = "DB")
@Component
public class AccountIdIndexStore extends TronStoreWithRevoking<BytesCapsule> {

  /**
   * Turkish dotless-ı (U+0131). On Turkish/Azerbaijani locales,
   * {@code 'I'.toLowerCase()} produces this instead of ASCII {@code 'i'}.
   * This is the ONLY ASCII letter that differs between ROOT and Turkish
   * {@code toLowerCase()} — verified by testTurkishLowerCaseDiffForAllAsciiLetters.
   */
  private static final char DOTLESS_I = '\u0131'; // ı Turkish dotless-i
  private static final Locale TURKISH = Locale.forLanguageTag("tr");

  @Autowired
  public AccountIdIndexStore(@Value("accountid-index") String dbName) {
    super(dbName);
  }

  public static byte[] getLowerCaseAccountId(byte[] accountId) {
    return ByteString
        .copyFromUtf8(ByteString.copyFrom(accountId).toStringUtf8().toLowerCase(Locale.ROOT))
        .toByteArray();
  }

  /**
   * Turkish direct key: {@code toLowerCase(TURKISH)} on the original input.
   * Reproduces the exact key a Turkish node stored for the same-case input.
   * Handles lookups where query case matches the original accountId case.
   *
   * <p>Example: input "AiBI" → "aibı" (lowercase 'i' stays, uppercase 'I' → 'ı').
   */
  @SuppressWarnings("StringCaseLocaleUsage")
  private static byte[] getTurkishDirectKey(byte[] accountId) {
    String str = ByteString.copyFrom(accountId).toStringUtf8();
    return ByteString.copyFromUtf8(str.toLowerCase(TURKISH)).toByteArray();
  }

  /**
   * Turkish normalized key: ROOT key with all {@code 'i'} replaced by {@code 'ı'}.
   * Handles cross-case lookups (e.g., lowercase query for an accountId that
   * was originally uppercase on a Turkish node).
   *
   * <p>Example: rootKey "aibi" → "aıbı".
   *
   * @param rootKey the already-computed ROOT-lowered key
   * @return the normalized key, or {@code rootKey} itself if no 'i' is present
   */
  private static byte[] getTurkishNormalizedKey(byte[] rootKey) {
    String str = new String(rootKey, StandardCharsets.UTF_8);
    if (str.indexOf('i') < 0) {
      return rootKey;
    }
    return str.replace('i', DOTLESS_I).getBytes(StandardCharsets.UTF_8);
  }

  public void put(AccountCapsule accountCapsule) {
    byte[] lowerCaseAccountId = getLowerCaseAccountId(accountCapsule.getAccountId().toByteArray());
    super.put(lowerCaseAccountId, new BytesCapsule(accountCapsule.getAddress().toByteArray()));
  }

  public byte[] get(ByteString accountId) {
    BytesCapsule bytesCapsule = get(accountId.toByteArray());
    if (Objects.nonNull(bytesCapsule)) {
      return bytesCapsule.getData();
    }
    return null;
  }

  /**
   * Look up by the standard (Locale.ROOT) accountId first; on miss, fall back to
   * Turkish legacy keys. The fallback covers nodes that previously ran under
   * tr/az locale and wrote keys containing dotless-ı (U+0131).
   *
   * <p>Two fallback probes are used:
   * <ol>
   *   <li><b>Direct</b>: {@code toLowerCase(TURKISH)} — matches when query
   *       case equals original accountId case (handles mixed 'i'/'I').</li>
   *   <li><b>Normalized</b>: ROOT accountId with all 'i' → 'ı' — matches when
   *       query case differs from original (e.g., all-lowercase query for
   *       an all-uppercase stored accountId).</li>
   * </ol>
   *
   * <p>Each probe is skipped when it produces the same accountId as the ROOT accountId
   * (i.e., input contains no 'I' or 'i'). AccountIdIndexStore is a small
   * dataset, so the overhead of up to two extra lookups is negligible.
   */
  @Override
  public BytesCapsule get(byte[] accountId) {
    byte[] value = lookupWithFallback(accountId);
    return ArrayUtils.isEmpty(value) ? null : new BytesCapsule(value);
  }

  /** See {@link #get(byte[])} for fallback strategy. */
  @Override
  public boolean has(byte[] accountId) {
    return !ArrayUtils.isEmpty(lookupWithFallback(accountId));
  }

  private byte[] lookupWithFallback(byte[] accountId) {
    byte[] rootLocaleKey = getLowerCaseAccountId(accountId);
    byte[] value = revokingDB.getUnchecked(rootLocaleKey);
    // Fallback 1: direct Turkish accountId (same-case match).
    // Needed for accountIds containing BOTH 'i' and 'I' (e.g., "AiBI").
    // A Turkish node stored toLowerCase(TURKISH) = "aibı" — only the
    // direct probe reproduces this mixed 'i'/'ı' key correctly.
    // The normalized probe (Fallback 2) would produce "aıbı" instead.
    if (ArrayUtils.isEmpty(value)) {
      byte[] directKey = getTurkishDirectKey(accountId);
      if (!Arrays.equals(rootLocaleKey, directKey)) {
        value = revokingDB.getUnchecked(directKey);
      }
    }
    // Fallback 2: normalized Turkish accountId (cross-case match).
    // Handles queries where case differs from the original accountId,
    // e.g., lowercase "aibi" looking up an entry stored as "AIBI"
    // on a Turkish node (stored key = "aıbı").
    if (ArrayUtils.isEmpty(value)) {
      byte[] normalizedKey = getTurkishNormalizedKey(rootLocaleKey);
      if (!Arrays.equals(rootLocaleKey, normalizedKey)) {
        value = revokingDB.getUnchecked(normalizedKey);
      }
    }
    return value;
  }

}
