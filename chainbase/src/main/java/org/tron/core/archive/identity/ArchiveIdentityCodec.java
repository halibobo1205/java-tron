package org.tron.core.archive.identity;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/** Strict, deterministic JSON codec for archive identity records. */
final class ArchiveIdentityCodec {

  static final String FORMAT = "tron-archive-identity-v1";

  private static final String FIELD_FORMAT = "format";
  private static final String FIELD_STATE = "state";
  private static final String FIELD_UUID = "uuid";
  private static final String FIELD_RESUME_NONCE = "resumeNonce";
  private static final String FIELD_CHAIN_ID = "chainId";
  private static final String FIELD_SCHEMA = "schema";
  private static final String FIELD_LAYOUT = "layout";
  private static final String FIELD_FINAL_PATH = "finalPath";
  private static final String FIELD_FLOOR = "floor";
  private static final Set<String> FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
      FIELD_FORMAT, FIELD_STATE, FIELD_UUID, FIELD_RESUME_NONCE, FIELD_CHAIN_ID, FIELD_SCHEMA,
      FIELD_LAYOUT, FIELD_FINAL_PATH, FIELD_FLOOR)));
  private static final char[] HEX = "0123456789abcdef".toCharArray();
  private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  byte[] encode(ArchiveIdentity identity) throws ArchiveIdentityException {
    ObjectNode root = MAPPER.createObjectNode();
    root.put(FIELD_FORMAT, FORMAT);
    root.put(FIELD_STATE, identity.getState().name());
    root.put(FIELD_UUID, identity.getUuid().toString());
    root.put(FIELD_RESUME_NONCE, encodeHex(identity.getResumeNonce()));
    root.put(FIELD_CHAIN_ID, identity.getChainId());
    root.put(FIELD_SCHEMA, identity.getSchema());
    root.put(FIELD_LAYOUT, identity.getLayout());
    root.put(FIELD_FINAL_PATH, identity.getFinalPath().toString());
    root.put(FIELD_FLOOR, identity.getFloor());
    try {
      return (MAPPER.writeValueAsString(root) + "\n").getBytes(StandardCharsets.UTF_8);
    } catch (JsonProcessingException e) {
      throw new ArchiveIdentityException("could not encode archive identity", e);
    }
  }

  ArchiveIdentity decode(byte[] bytes, Path source) throws ArchiveIdentityException {
    final JsonNode root;
    try {
      root = MAPPER.readTree(bytes);
    } catch (IOException e) {
      throw invalid(source, "invalid JSON", e);
    }
    if (root == null || !root.isObject()) {
      throw invalid(source, "identity must be a JSON object");
    }
    requireExactFields(root, source);

    String format = requireText(root, FIELD_FORMAT, source);
    if (!FORMAT.equals(format)) {
      throw invalid(source, "unsupported format");
    }

    try {
      ArchiveIdentityState state = ArchiveIdentityState.valueOf(
          requireText(root, FIELD_STATE, source));
      UUID uuid = decodeUuid(requireText(root, FIELD_UUID, source), source);
      byte[] nonce = decodeHex(requireText(root, FIELD_RESUME_NONCE, source), source);
      String chainId = requireText(root, FIELD_CHAIN_ID, source);
      String schema = requireText(root, FIELD_SCHEMA, source);
      String layout = requireText(root, FIELD_LAYOUT, source);
      Path finalPath = decodePath(requireText(root, FIELD_FINAL_PATH, source), source);
      long floor = requireLong(root, FIELD_FLOOR, source);
      return new ArchiveIdentity(
          new ArchiveIdentityClaim(uuid, nonce, chainId, schema, layout, finalPath, floor), state);
    } catch (IllegalArgumentException e) {
      throw invalid(source, "invalid identity field", e);
    }
  }

  private static void requireExactFields(JsonNode root, Path source)
      throws ArchiveIdentityException {
    if (root.size() != FIELDS.size()) {
      throw invalid(source, "identity fields are incomplete or unexpected");
    }
    Iterator<String> names = root.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      if (!FIELDS.contains(name)) {
        throw invalid(source, "unexpected field: " + name);
      }
    }
  }

  private static String requireText(JsonNode root, String field, Path source)
      throws ArchiveIdentityException {
    JsonNode value = root.get(field);
    if (value == null || !value.isTextual()) {
      throw invalid(source, field + " must be text");
    }
    return value.textValue();
  }

  private static long requireLong(JsonNode root, String field, Path source)
      throws ArchiveIdentityException {
    JsonNode value = root.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw invalid(source, field + " must be a 64-bit integer");
    }
    return value.longValue();
  }

  private static Path decodePath(String value, Path source) throws ArchiveIdentityException {
    try {
      Path path = Paths.get(value);
      if (!path.isAbsolute() || !path.normalize().equals(path) || !path.toString().equals(value)) {
        throw invalid(source, "finalPath must be absolute and normalized");
      }
      return path;
    } catch (InvalidPathException e) {
      throw invalid(source, "invalid finalPath", e);
    }
  }

  private static UUID decodeUuid(String value, Path source) throws ArchiveIdentityException {
    UUID uuid = UUID.fromString(value);
    if (!uuid.toString().equals(value)) {
      throw invalid(source, "uuid must use canonical lowercase text");
    }
    return uuid;
  }

  private static byte[] decodeHex(String value, Path source) throws ArchiveIdentityException {
    int expectedLength = ArchiveIdentityClaim.RESUME_NONCE_LENGTH * 2;
    if (value.length() != expectedLength) {
      throw invalid(source, "resumeNonce has the wrong length");
    }
    byte[] result = new byte[ArchiveIdentityClaim.RESUME_NONCE_LENGTH];
    for (int index = 0; index < result.length; index++) {
      int high = decodeNibble(value.charAt(index * 2));
      int low = decodeNibble(value.charAt(index * 2 + 1));
      if (high < 0 || low < 0) {
        throw invalid(source, "resumeNonce must be lowercase hexadecimal");
      }
      result[index] = (byte) ((high << 4) | low);
    }
    return result;
  }

  private static int decodeNibble(char value) {
    if (value >= '0' && value <= '9') {
      return value - '0';
    }
    if (value >= 'a' && value <= 'f') {
      return value - 'a' + 10;
    }
    return -1;
  }

  private static String encodeHex(byte[] bytes) {
    char[] result = new char[bytes.length * 2];
    for (int index = 0; index < bytes.length; index++) {
      int value = bytes[index] & 0xff;
      result[index * 2] = HEX[value >>> 4];
      result[index * 2 + 1] = HEX[value & 0x0f];
    }
    return new String(result);
  }

  private static ArchiveIdentityException invalid(Path source, String reason) {
    return new ArchiveIdentityException("invalid archive identity at " + source + ": " + reason);
  }

  private static ArchiveIdentityException invalid(Path source, String reason, Throwable cause) {
    return new ArchiveIdentityException(
        "invalid archive identity at " + source + ": " + reason, cause);
  }
}
