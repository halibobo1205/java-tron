package org.tron.core.archive.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tron.core.archive.identity.ArchiveIdentityProtocol.DurableStage;

public class ArchiveIdentityProtocolTest {

  private static final String CHAIN_ID = "mainnet-genesis";
  private static final String SCHEMA = "schema-checksum-v2";
  private static final String LAYOUT = "LEGACY_V1";
  private static final ArchiveIdentityPayload IDENTITY_ONLY = new ArchiveIdentityPayload() {
    @Override
    public void bindAndSync(Path archiveRoot, ArchiveIdentity identity) {
      // No additional payload.
    }

    @Override
    public void verifyForActivation(Path archiveRoot, ArchiveIdentity identity) {
      // No additional payload.
    }
  };

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void initIsIdempotentAndRejectsNonceMismatch() throws Exception {
    Scenario scenario = scenario("init-idempotent");
    ArchiveIdentityProtocol protocol = new ArchiveIdentityProtocol();

    ArchiveIdentity first = protocol.init(scenario.anchors, scenario.claim);
    ArchiveIdentity repeated = protocol.init(scenario.anchors, scenario.claim);

    assertEquals(ArchiveIdentityState.PREPARED, first.getState());
    assertEquals(first, repeated);
    assertTrue(Files.exists(scenario.anchorPath()));
    assertFalse(Files.exists(scenario.root));

    ArchiveIdentityClaim wrongNonce = copyClaim(scenario.claim, scenario.claim.getUuid(),
        nonce(99), CHAIN_ID, SCHEMA, LAYOUT, scenario.root, 12);
    ArchiveIdentityException error = assertThrows(ArchiveIdentityException.class,
        () -> protocol.init(scenario.anchors, wrongNonce));

    assertTrue(error.getMessage().contains("resume nonce mismatch"));
    assertEquals(first, scenario.readAnchor());
    assertFalse(Files.exists(scenario.root));
  }

  @Test
  public void resumeIsIdempotentAndRejectsNonceMismatch() throws Exception {
    Scenario scenario = scenario("resume-idempotent");
    ArchiveIdentityProtocol protocol = new ArchiveIdentityProtocol();
    protocol.init(scenario.anchors, scenario.claim);

    ArchiveIdentityClaim wrongNonce = copyClaim(scenario.claim, scenario.claim.getUuid(),
        nonce(77), CHAIN_ID, SCHEMA, LAYOUT, scenario.root, 12);
    ArchiveIdentityException error = assertThrows(ArchiveIdentityException.class,
        () -> protocol.resume(scenario.anchors, wrongNonce));

    assertTrue(error.getMessage().contains("resume nonce mismatch"));
    assertEquals(ArchiveIdentityState.PREPARED, scenario.readAnchor().getState());
    assertFalse(Files.exists(scenario.root));

    ArchiveIdentity active = protocol.resume(scenario.anchors, scenario.claim);
    assertEquals(ArchiveIdentityState.ACTIVE, active.getState());
    assertEquals(active, protocol.resume(scenario.anchors, scenario.claim));
    assertEquals(active, protocol.init(scenario.anchors, scenario.claim));
  }

  @Test
  public void resumeRecoversAfterEveryDurableIdentityStage() throws Exception {
    for (DurableStage stage : Arrays.asList(
        DurableStage.ANCHOR_PREPARED,
        DurableStage.ROOT_DIRECTORY_CREATED,
        DurableStage.ROOT_BOUND,
        DurableStage.ANCHOR_BOUND,
        DurableStage.ROOT_ACTIVE,
        DurableStage.ANCHOR_ACTIVE)) {
      Scenario scenario = scenario("crash-" + stage.name().toLowerCase(Locale.ROOT));
      ArchiveIdentityProtocol crashing = crashingProtocol(IDENTITY_ONLY, stage);

      if (stage == DurableStage.ANCHOR_PREPARED) {
        assertThrows(SimulatedCrash.class,
            () -> crashing.init(scenario.anchors, scenario.claim));
      } else {
        new ArchiveIdentityProtocol().init(scenario.anchors, scenario.claim);
        assertThrows(SimulatedCrash.class,
            () -> crashing.resume(scenario.anchors, scenario.claim));
      }

      assertStagePersisted(scenario, stage);
      if (stage == DurableStage.ANCHOR_ACTIVE) {
        assertEquals(ArchiveIdentityState.ACTIVE, scenario.validate().getState());
      } else {
        assertThrows(ArchiveIdentityException.class, scenario::validate);
      }

      ArchiveIdentityProtocol restarted = new ArchiveIdentityProtocol();
      ArchiveIdentityClaim persistedClaim = restarted.findResumableClaim(
          scenario.anchors, scenario.root, CHAIN_ID, SCHEMA, LAYOUT, 12L)
          .orElseThrow(() -> new AssertionError("persisted resume claim is missing"));
      assertEquals(scenario.claim, persistedClaim);
      ArchiveIdentity recovered = restarted.resume(scenario.anchors, persistedClaim);
      assertEquals(ArchiveIdentityState.ACTIVE, recovered.getState());
      assertEquals(recovered, scenario.validate());
    }
  }

  @Test
  public void resumeRecoversWhenPayloadBindingIsInterrupted() throws Exception {
    Scenario scenario = scenario("payload-bind-crash");
    Path payloadMarker = scenario.root.resolve("payload.data");
    new ArchiveIdentityProtocol().init(scenario.anchors, scenario.claim);

    ArchiveIdentityPayload interrupted = new ArchiveIdentityPayload() {
      @Override
      public void bindAndSync(Path archiveRoot, ArchiveIdentity identity) throws IOException {
        Files.write(payloadMarker, new byte[]{1});
        throw new SimulatedCrash(DurableStage.ROOT_BOUND);
      }

      @Override
      public void verifyForActivation(Path archiveRoot, ArchiveIdentity identity) {
        throw new AssertionError("verification must not run after failed binding");
      }
    };
    assertThrows(SimulatedCrash.class,
        () -> new ArchiveIdentityProtocol(interrupted)
            .resume(scenario.anchors, scenario.claim));

    assertEquals(ArchiveIdentityState.PREPARED, scenario.readAnchor().getState());
    assertEquals(ArchiveIdentityState.BOUND, scenario.readRoot().get().getState());
    assertTrue(Files.exists(payloadMarker));

    ArchiveIdentity recovered = new ArchiveIdentityProtocol(payloadRequiring(payloadMarker))
        .resume(scenario.anchors, scenario.claim);
    assertEquals(ArchiveIdentityState.ACTIVE, recovered.getState());
  }

  @Test
  public void resumeRecoversWhenActivationVerificationIsInterrupted() throws Exception {
    Scenario scenario = scenario("payload-verify-crash");
    Path payloadMarker = scenario.root.resolve("payload.data");
    new ArchiveIdentityProtocol().init(scenario.anchors, scenario.claim);

    ArchiveIdentityPayload interrupted = new ArchiveIdentityPayload() {
      @Override
      public void bindAndSync(Path archiveRoot, ArchiveIdentity identity) throws IOException {
        Files.write(payloadMarker, new byte[]{1});
      }

      @Override
      public void verifyForActivation(Path archiveRoot, ArchiveIdentity identity)
          throws IOException {
        throw new SimulatedCrash(DurableStage.ANCHOR_BOUND);
      }
    };
    assertThrows(SimulatedCrash.class,
        () -> new ArchiveIdentityProtocol(interrupted)
            .resume(scenario.anchors, scenario.claim));

    assertEquals(ArchiveIdentityState.BOUND, scenario.readAnchor().getState());
    assertEquals(ArchiveIdentityState.BOUND, scenario.readRoot().get().getState());

    ArchiveIdentity recovered = new ArchiveIdentityProtocol(payloadRequiring(payloadMarker))
        .resume(scenario.anchors, scenario.claim);
    assertEquals(ArchiveIdentityState.ACTIVE, recovered.getState());
  }

  @Test
  public void normalValidationRejectsEveryFieldMismatch() throws Exception {
    for (String field : Arrays.asList(
        "uuid", "resume nonce", "chainId", "schema", "layout", "finalPath", "floor")) {
      Scenario scenario = activeScenario("mismatch-" + field.replace(' ', '-'));
      ArchiveIdentityClaim changed = changedClaim(scenario, field);
      scenario.files.write(scenario.rootIdentityPath(),
          new ArchiveIdentity(changed, ArchiveIdentityState.ACTIVE));

      ArchiveIdentityException error = assertThrows(ArchiveIdentityException.class,
          scenario::validate);
      assertTrue(field + ": " + error.getMessage(), error.getMessage().contains(field));
    }
  }

  @Test
  public void normalValidationRequiresBothCopiesToBeActive() throws Exception {
    Scenario scenario = activeScenario("both-active-required");
    ArchiveIdentity root = scenario.readRoot().get();
    scenario.files.write(scenario.rootIdentityPath(), root.withState(ArchiveIdentityState.BOUND));

    ArchiveIdentityException error = assertThrows(ArchiveIdentityException.class,
        scenario::validate);

    assertTrue(error.getMessage().contains("matching ACTIVE"));
  }

  @Test
  public void newInitRefusesNonEmptyLegacyRootWithoutCreatingAnchor() throws Exception {
    Scenario scenario = scenario("legacy-root");
    Files.createDirectory(scenario.root);
    Path legacy = Files.write(scenario.root.resolve("legacy.sst"), new byte[]{7});

    ArchiveIdentityException initError = assertThrows(ArchiveIdentityException.class,
        () -> new ArchiveIdentityProtocol().init(scenario.anchors, scenario.claim));

    assertTrue(initError.getMessage().contains("non-empty unclaimed"));
    assertTrue(Files.exists(legacy));
    assertFalse(Files.exists(scenario.anchors));

    assertThrows(ArchiveIdentityException.class, scenario::validate);
    assertTrue(Files.exists(legacy));
    assertFalse(Files.exists(scenario.anchors));
  }

  @Test
  public void explicitLegacyAdoptionPreservesPayloadAndActivatesMatchingPair() throws Exception {
    Scenario scenario = scenario("legacy-adoption");
    Files.createDirectory(scenario.root);
    Path payload = Files.write(scenario.root.resolve("legacy.data"), new byte[]{7});
    ArchiveIdentityProtocol protocol = new ArchiveIdentityProtocol(IDENTITY_ONLY);

    ArchiveIdentity prepared = protocol.adopt(scenario.anchors, scenario.claim);
    assertEquals(ArchiveIdentityState.PREPARED, prepared.getState());
    assertThrows(ArchiveIdentityException.class,
        () -> protocol.resume(scenario.anchors, scenario.claim));
    ArchiveIdentity active = protocol.resumeAdoption(scenario.anchors, scenario.claim);

    assertEquals(ArchiveIdentityState.ACTIVE, active.getState());
    assertEquals(active, scenario.validate());
    assertTrue(Files.exists(payload));
  }

  @Test
  public void legacyAdoptionRecoversAfterEveryDurableIdentityStage() throws Exception {
    for (DurableStage stage : Arrays.asList(
        DurableStage.ANCHOR_PREPARED,
        DurableStage.ROOT_BOUND,
        DurableStage.ANCHOR_BOUND,
        DurableStage.ROOT_ACTIVE,
        DurableStage.ANCHOR_ACTIVE)) {
      Scenario scenario = scenario(
          "adoption-crash-" + stage.name().toLowerCase(Locale.ROOT));
      Files.createDirectory(scenario.root);
      Path payload = Files.write(scenario.root.resolve("legacy.data"), new byte[]{8});
      ArchiveIdentityProtocol crashing = crashingProtocol(IDENTITY_ONLY, stage);

      if (stage == DurableStage.ANCHOR_PREPARED) {
        assertThrows(SimulatedCrash.class,
            () -> crashing.adopt(scenario.anchors, scenario.claim));
      } else {
        new ArchiveIdentityProtocol(IDENTITY_ONLY).adopt(
            scenario.anchors, scenario.claim);
        assertThrows(SimulatedCrash.class,
            () -> crashing.resumeAdoption(scenario.anchors, scenario.claim));
      }

      assertStagePersisted(scenario, stage);
      ArchiveIdentityProtocol restarted = new ArchiveIdentityProtocol(IDENTITY_ONLY);
      ArchiveIdentityClaim persistedClaim = restarted.findResumableClaim(
          scenario.anchors, scenario.root, CHAIN_ID, SCHEMA, LAYOUT, 12L)
          .orElseThrow(() -> new AssertionError("persisted adoption claim is missing"));
      ArchiveIdentity recovered = restarted.resumeAdoption(
          scenario.anchors, persistedClaim);
      assertEquals(ArchiveIdentityState.ACTIVE, recovered.getState());
      assertEquals(recovered, scenario.validate());
      assertTrue(Files.exists(payload));
    }
  }

  @Test
  public void preparedAnchorCannotClaimRootThatLaterBecomesNonEmpty() throws Exception {
    Scenario scenario = scenario("prepared-then-legacy");
    ArchiveIdentityProtocol protocol = new ArchiveIdentityProtocol();
    protocol.init(scenario.anchors, scenario.claim);
    Files.createDirectory(scenario.root);
    Path legacy = Files.write(scenario.root.resolve("legacy.data"), new byte[]{8});

    ArchiveIdentityException error = assertThrows(ArchiveIdentityException.class,
        () -> protocol.resume(scenario.anchors, scenario.claim));

    assertTrue(error.getMessage().contains("without a matching identity"));
    assertTrue(Files.exists(legacy));
    assertFalse(Files.exists(scenario.rootIdentityPath()));
    assertEquals(ArchiveIdentityState.PREPARED, scenario.readAnchor().getState());
  }

  @Test
  public void differentUuidCannotPrepareSecondAnchorForSameRoot() throws Exception {
    Scenario scenario = scenario("competing-anchor");
    new ArchiveIdentityProtocol().init(scenario.anchors, scenario.claim);
    ArchiveIdentityClaim competitor = copyClaim(scenario.claim, UUID.randomUUID(), nonce(44),
        CHAIN_ID, SCHEMA, LAYOUT, scenario.root, 12);

    ArchiveIdentityException error = assertThrows(ArchiveIdentityException.class,
        () -> new ArchiveIdentityProtocol().init(scenario.anchors, competitor));

    assertTrue(error.getMessage().contains("already claimed"));
    assertFalse(Files.exists(
        ArchiveIdentityProtocol.anchorPath(scenario.anchors, competitor.getUuid())));
  }

  @Test
  public void competingProtocolInstancesSerializeTheSameAnchorDirectory() throws Exception {
    Scenario scenario = scenario("concurrent-anchor");
    ArchiveIdentityClaim competitor = copyClaim(scenario.claim, UUID.randomUUID(), nonce(45),
        CHAIN_ID, SCHEMA, LAYOUT, scenario.root, 12);
    CountDownLatch firstPrepared = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    ArchiveIdentityProtocol firstProtocol = new ArchiveIdentityProtocol(
        IDENTITY_ONLY, new ArchiveIdentityFileStore(), stage -> {
          if (stage == DurableStage.ANCHOR_PREPARED) {
            firstPrepared.countDown();
            try {
              releaseFirst.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IOException("interrupted while holding identity lock", e);
            }
          }
        });
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<ArchiveIdentity> first = executor.submit(
          () -> firstProtocol.init(scenario.anchors, scenario.claim));
      assertTrue(firstPrepared.await(5, TimeUnit.SECONDS));
      Future<ArchiveIdentity> second = executor.submit(() -> {
        secondStarted.countDown();
        return new ArchiveIdentityProtocol().init(scenario.anchors, competitor);
      });
      assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));

      releaseFirst.countDown();
      assertEquals(ArchiveIdentityState.PREPARED,
          first.get(5, TimeUnit.SECONDS).getState());
      ExecutionException failure = assertThrows(ExecutionException.class,
          () -> second.get(5, TimeUnit.SECONDS));
      assertTrue(failure.getCause() instanceof ArchiveIdentityException);
      assertTrue(failure.getCause().getMessage().contains("already claimed"));
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  public void abortRequiresMatchingNonceAndRemovesPreparedAnchor() throws Exception {
    Scenario scenario = scenario("abort-prepared");
    ArchiveIdentityProtocol protocol = new ArchiveIdentityProtocol();
    protocol.init(scenario.anchors, scenario.claim);
    ArchiveIdentityClaim wrongNonce = copyClaim(scenario.claim, scenario.claim.getUuid(),
        nonce(55), CHAIN_ID, SCHEMA, LAYOUT, scenario.root, 12);

    ArchiveIdentityException error = assertThrows(ArchiveIdentityException.class,
        () -> protocol.abort(scenario.anchors, wrongNonce));
    assertTrue(error.getMessage().contains("resume nonce mismatch"));
    assertTrue(Files.exists(scenario.anchorPath()));

    protocol.abort(scenario.anchors, scenario.claim);
    assertFalse(Files.exists(scenario.anchorPath()));
    assertFalse(Files.exists(scenario.root));
  }

  @Test
  public void interruptedAbortRecoversAndNeverDeletesPayload() throws Exception {
    Scenario scenario = scenario("abort-bound");
    Path payloadMarker = scenario.root.resolve("payload.data");
    new ArchiveIdentityProtocol().init(scenario.anchors, scenario.claim);
    assertThrows(SimulatedCrash.class,
        () -> crashingProtocol(payloadCreating(payloadMarker), DurableStage.ANCHOR_BOUND)
            .resume(scenario.anchors, scenario.claim));
    assertTrue(Files.exists(payloadMarker));

    assertThrows(SimulatedCrash.class,
        () -> crashingProtocol(IDENTITY_ONLY, DurableStage.ROOT_IDENTITY_ABORTED)
            .abort(scenario.anchors, scenario.claim));

    assertFalse(Files.exists(scenario.rootIdentityPath()));
    assertTrue(Files.exists(scenario.anchorPath()));
    assertTrue(Files.exists(payloadMarker));

    new ArchiveIdentityProtocol().abort(scenario.anchors, scenario.claim);
    assertFalse(Files.exists(scenario.rootIdentityPath()));
    assertFalse(Files.exists(scenario.anchorPath()));
    assertTrue(Files.exists(payloadMarker));

    ArchiveIdentityClaim replacement = copyClaim(scenario.claim, UUID.randomUUID(), nonce(66),
        CHAIN_ID, SCHEMA, LAYOUT, scenario.root, 12);
    assertThrows(ArchiveIdentityException.class,
        () -> new ArchiveIdentityProtocol().init(scenario.anchors, replacement));
    assertTrue(Files.exists(payloadMarker));
  }

  @Test
  public void abortRejectsFullyActiveIdentity() throws Exception {
    Scenario scenario = activeScenario("abort-active");

    ArchiveIdentityException error = assertThrows(ArchiveIdentityException.class,
        () -> new ArchiveIdentityProtocol().abort(scenario.anchors, scenario.claim));

    assertTrue(error.getMessage().contains("ACTIVE"));
    assertEquals(ArchiveIdentityState.ACTIVE, scenario.readAnchor().getState());
    assertEquals(ArchiveIdentityState.ACTIVE, scenario.readRoot().get().getState());
  }

  @Test
  public void abortRejectsRootActiveBeforeAnchorActive() throws Exception {
    Scenario scenario = scenario("abort-root-active");
    new ArchiveIdentityProtocol().init(scenario.anchors, scenario.claim);
    assertThrows(SimulatedCrash.class,
        () -> crashingProtocol(IDENTITY_ONLY, DurableStage.ROOT_ACTIVE)
            .resume(scenario.anchors, scenario.claim));

    ArchiveIdentityException error = assertThrows(ArchiveIdentityException.class,
        () -> new ArchiveIdentityProtocol().abort(scenario.anchors, scenario.claim));

    assertTrue(error.getMessage().contains("ACTIVE"));
    assertEquals(ArchiveIdentityState.BOUND, scenario.readAnchor().getState());
    assertEquals(ArchiveIdentityState.ACTIVE, scenario.readRoot().get().getState());
    assertEquals(ArchiveIdentityState.ACTIVE,
        new ArchiveIdentityProtocol().resume(scenario.anchors, scenario.claim).getState());
  }

  private Scenario activeScenario(String name) throws Exception {
    Scenario scenario = scenario(name);
    ArchiveIdentityProtocol protocol = new ArchiveIdentityProtocol();
    protocol.init(scenario.anchors, scenario.claim);
    protocol.resume(scenario.anchors, scenario.claim);
    return scenario;
  }

  private Scenario scenario(String name) throws IOException {
    Path base = Files.createDirectory(temporaryFolder.getRoot().toPath().resolve(name));
    Path canonicalOutput = Files.createDirectory(base.resolve("canonical-output"));
    Path anchors = canonicalOutput.resolve("archive-identities");
    Path root = base.resolve("archive-root");
    UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    ArchiveIdentityClaim claim = new ArchiveIdentityClaim(
        uuid, nonce(name.hashCode()), CHAIN_ID, SCHEMA, LAYOUT, root, 12);
    return new Scenario(anchors, root, claim);
  }

  private static ArchiveIdentityProtocol crashingProtocol(ArchiveIdentityPayload payload,
      DurableStage stage) {
    return new ArchiveIdentityProtocol(payload, new ArchiveIdentityFileStore(), persisted -> {
      if (persisted == stage) {
        throw new SimulatedCrash(stage);
      }
    });
  }

  private static void assertStagePersisted(Scenario scenario, DurableStage stage) throws Exception {
    ArchiveIdentity anchor = scenario.readAnchor();
    Optional<ArchiveIdentity> root = scenario.readRoot();
    switch (stage) {
      case ANCHOR_PREPARED:
      case ROOT_DIRECTORY_CREATED:
        assertEquals(ArchiveIdentityState.PREPARED, anchor.getState());
        assertFalse(root.isPresent());
        break;
      case ROOT_BOUND:
        assertEquals(ArchiveIdentityState.PREPARED, anchor.getState());
        assertEquals(ArchiveIdentityState.BOUND, root.get().getState());
        break;
      case ANCHOR_BOUND:
        assertEquals(ArchiveIdentityState.BOUND, anchor.getState());
        assertEquals(ArchiveIdentityState.BOUND, root.get().getState());
        break;
      case ROOT_ACTIVE:
        assertEquals(ArchiveIdentityState.BOUND, anchor.getState());
        assertEquals(ArchiveIdentityState.ACTIVE, root.get().getState());
        break;
      case ANCHOR_ACTIVE:
        assertEquals(ArchiveIdentityState.ACTIVE, anchor.getState());
        assertEquals(ArchiveIdentityState.ACTIVE, root.get().getState());
        break;
      default:
        throw new AssertionError("unexpected resume stage " + stage);
    }
  }

  private static ArchiveIdentityClaim changedClaim(Scenario scenario, String field) {
    ArchiveIdentityClaim claim = scenario.claim;
    UUID uuid = field.equals("uuid") ? UUID.randomUUID() : claim.getUuid();
    byte[] nonce = field.equals("resume nonce") ? nonce(101) : claim.getResumeNonce();
    String chainId = field.equals("chainId") ? CHAIN_ID + "-other" : claim.getChainId();
    String schema = field.equals("schema") ? SCHEMA + "-other" : claim.getSchema();
    String layout = field.equals("layout") ? "UNIFIED_V1" : claim.getLayout();
    Path finalPath = field.equals("finalPath")
        ? scenario.root.getParent().resolve("other-root") : claim.getFinalPath();
    long floor = field.equals("floor") ? claim.getFloor() + 1 : claim.getFloor();
    return copyClaim(claim, uuid, nonce, chainId, schema, layout, finalPath, floor);
  }

  private static ArchiveIdentityClaim copyClaim(ArchiveIdentityClaim ignored, UUID uuid,
      byte[] nonce, String chainId, String schema, String layout, Path finalPath, long floor) {
    return new ArchiveIdentityClaim(uuid, nonce, chainId, schema, layout, finalPath, floor);
  }

  private static byte[] nonce(int seed) {
    byte[] nonce = new byte[ArchiveIdentityClaim.RESUME_NONCE_LENGTH];
    for (int index = 0; index < nonce.length; index++) {
      nonce[index] = (byte) (seed + index);
    }
    return nonce;
  }

  private static ArchiveIdentityPayload payloadCreating(Path marker) {
    return new ArchiveIdentityPayload() {
      @Override
      public void bindAndSync(Path archiveRoot, ArchiveIdentity identity) throws IOException {
        if (!Files.exists(marker)) {
          Files.write(marker, new byte[]{1});
        }
      }

      @Override
      public void verifyForActivation(Path archiveRoot, ArchiveIdentity identity)
          throws IOException {
        if (!Files.isRegularFile(marker)) {
          throw new IOException("payload marker is missing");
        }
      }
    };
  }

  private static ArchiveIdentityPayload payloadRequiring(Path marker) {
    return new ArchiveIdentityPayload() {
      @Override
      public void bindAndSync(Path archiveRoot, ArchiveIdentity identity) throws IOException {
        if (!Files.isRegularFile(marker)) {
          throw new IOException("payload marker is missing during resume");
        }
      }

      @Override
      public void verifyForActivation(Path archiveRoot, ArchiveIdentity identity)
          throws IOException {
        if (!Files.isRegularFile(marker)) {
          throw new IOException("payload marker is missing during verification");
        }
      }
    };
  }

  private static final class Scenario {
    private final Path anchors;
    private final Path root;
    private final ArchiveIdentityClaim claim;
    private final ArchiveIdentityFileStore files = new ArchiveIdentityFileStore();

    private Scenario(Path anchors, Path root, ArchiveIdentityClaim claim) {
      this.anchors = anchors;
      this.root = root;
      this.claim = claim;
    }

    private Path anchorPath() {
      return ArchiveIdentityProtocol.anchorPath(anchors, claim.getUuid());
    }

    private Path rootIdentityPath() {
      return ArchiveIdentityProtocol.rootIdentityPath(root);
    }

    private ArchiveIdentity readAnchor() throws IOException {
      return files.readRequired(anchorPath(), "anchor");
    }

    private Optional<ArchiveIdentity> readRoot() throws IOException {
      return files.read(rootIdentityPath());
    }

    private ArchiveIdentity validate() throws IOException {
      return new ArchiveIdentityProtocol().validateActive(anchors, claim.getUuid(), root);
    }
  }

  private static final class SimulatedCrash extends IOException {
    private SimulatedCrash(DurableStage stage) {
      super("simulated crash after " + stage);
    }
  }
}
