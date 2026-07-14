package org.tron.core.archive.identity;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ArchiveIdentityCodecTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void codecRoundTripsAllFields() throws Exception {
    Path root = temporaryFolder.getRoot().toPath().resolve("archive");
    ArchiveIdentity identity = identity(root, ArchiveIdentityState.BOUND);
    ArchiveIdentityCodec codec = new ArchiveIdentityCodec();

    byte[] encoded = codec.encode(identity);
    ArchiveIdentity decoded = codec.decode(encoded, root.resolve("archive.identity"));

    assertEquals(identity, decoded);
    String json = new String(encoded, StandardCharsets.UTF_8);
    assertTrue(json.startsWith("{\"format\":\"tron-archive-identity-v1\""));
    assertTrue(json.endsWith("\n"));
  }

  @Test
  public void codecRejectsUnknownDuplicateAndTrailingFields() throws Exception {
    Path source = temporaryFolder.getRoot().toPath().resolve("identity");
    ArchiveIdentityCodec codec = new ArchiveIdentityCodec();
    String valid = new String(codec.encode(
        identity(temporaryFolder.getRoot().toPath().resolve("archive"),
            ArchiveIdentityState.PREPARED)), StandardCharsets.UTF_8).trim();

    String unknown = valid.substring(0, valid.length() - 1) + ",\"unknown\":true}";
    ArchiveIdentityException unknownError = assertThrows(ArchiveIdentityException.class,
        () -> codec.decode(unknown.getBytes(StandardCharsets.UTF_8), source));
    assertTrue(unknownError.getMessage().contains("unexpected"));

    String duplicate = valid.replace("\"state\":\"PREPARED\"",
        "\"state\":\"PREPARED\",\"state\":\"BOUND\"");
    ArchiveIdentityException duplicateError = assertThrows(ArchiveIdentityException.class,
        () -> codec.decode(duplicate.getBytes(StandardCharsets.UTF_8), source));
    assertTrue(duplicateError.getMessage().contains("invalid JSON"));

    ArchiveIdentityException trailingError = assertThrows(ArchiveIdentityException.class,
        () -> codec.decode((valid + "{}").getBytes(StandardCharsets.UTF_8), source));
    assertTrue(trailingError.getMessage().contains("invalid JSON"));
  }

  @Test
  public void codecRejectsNonCanonicalNonceAndPath() throws Exception {
    Path source = temporaryFolder.getRoot().toPath().resolve("identity");
    ArchiveIdentityCodec codec = new ArchiveIdentityCodec();
    ArchiveIdentity identity = identity(
        temporaryFolder.getRoot().toPath().resolve("archive"), ArchiveIdentityState.PREPARED);
    String valid = new String(codec.encode(identity), StandardCharsets.UTF_8).trim();

    int nonceStart = valid.indexOf("\"resumeNonce\":\"") + "\"resumeNonce\":\"".length();
    int nonceEnd = valid.indexOf('"', nonceStart);
    String uppercaseNonce = valid.substring(0, nonceStart)
        + valid.substring(nonceStart, nonceEnd).toUpperCase(Locale.ROOT)
        + valid.substring(nonceEnd);
    ArchiveIdentityException nonceError = assertThrows(ArchiveIdentityException.class,
        () -> codec.decode(uppercaseNonce.getBytes(StandardCharsets.UTF_8), source));
    assertTrue(nonceError.getMessage().contains("lowercase hexadecimal"));

    String path = identity.getFinalPath().toString();
    String nonCanonicalPath = valid.replace(path, path + "/../archive");
    ArchiveIdentityException pathError = assertThrows(ArchiveIdentityException.class,
        () -> codec.decode(nonCanonicalPath.getBytes(StandardCharsets.UTF_8), source));
    assertTrue(pathError.getMessage().contains("absolute and normalized"));
  }

  @Test
  public void fileStoreReplacesAtomicallyAndCleansOnlyOwnedTemporaryFiles() throws Exception {
    Path root = Files.createDirectory(temporaryFolder.getRoot().toPath().resolve("archive"));
    ArchiveIdentity prepared = identity(root, ArchiveIdentityState.PREPARED);
    ArchiveIdentity active = prepared.withState(ArchiveIdentityState.ACTIVE);
    ArchiveIdentityFileStore store = new ArchiveIdentityFileStore();
    Path target = ArchiveIdentityProtocol.rootIdentityPath(root);
    String temporaryPrefix = "." + target.getFileName() + "." + active.getUuid() + ".tmp-";
    Path orphan = Files.createFile(root.resolve(temporaryPrefix + "orphan"));
    Path unrelated = Files.write(root.resolve("payload.data"), new byte[]{9});

    store.write(target, prepared);
    store.write(target, active);

    assertFalse(Files.exists(orphan));
    assertTrue(Files.exists(unrelated));
    assertEquals(active, store.readRequired(target, "root"));
    try (Stream<Path> entries = Files.list(root)) {
      assertFalse(entries.anyMatch(path -> path.getFileName().toString()
          .startsWith(temporaryPrefix)));
    }
  }

  @Test
  public void claimDefensivelyCopiesAndRedactsResumeNonce() {
    Path root = temporaryFolder.getRoot().toPath().resolve("archive");
    byte[] nonce = nonce();
    byte[] original = nonce.clone();
    ArchiveIdentityClaim claim = new ArchiveIdentityClaim(
        UUID.randomUUID(), nonce, "chain", "schema", "layout", root, 0);

    nonce[0] ^= 1;
    byte[] returned = claim.getResumeNonce();
    returned[1] ^= 1;

    assertArrayEquals(original, claim.getResumeNonce());
    assertFalse(claim.toString().contains(toHex(original)));
    assertFalse(new ArchiveIdentity(claim, ArchiveIdentityState.PREPARED)
        .toString().contains(toHex(original)));
  }

  private static ArchiveIdentity identity(Path root, ArchiveIdentityState state) {
    ArchiveIdentityClaim claim = new ArchiveIdentityClaim(
        UUID.fromString("6e63aa9d-37a7-4f9d-844e-b2cb947f64f0"), nonce(),
        "chain", "schema", "LEGACY_V1", root, 42);
    return new ArchiveIdentity(claim, state);
  }

  private static byte[] nonce() {
    byte[] nonce = new byte[ArchiveIdentityClaim.RESUME_NONCE_LENGTH];
    for (int index = 0; index < nonce.length; index++) {
      nonce[index] = (byte) (index + 1);
    }
    return nonce;
  }

  private static String toHex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      result.append(String.format("%02x", value & 0xff));
    }
    return result.toString();
  }
}
