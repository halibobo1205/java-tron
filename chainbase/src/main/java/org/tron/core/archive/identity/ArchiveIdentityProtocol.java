package org.tron.core.archive.identity;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Crash-recoverable PREPARED -> BOUND -> ACTIVE protocol for archive identities.
 *
 * <p>The external anchor is stored as {@code anchorDirectory/UUID}; the root copy is stored as
 * {@value #ROOT_IDENTITY_FILE_NAME} in the archive root. Normal validation performs no writes and
 * accepts only two matching ACTIVE records.
 */
public final class ArchiveIdentityProtocol {

  public static final String ROOT_IDENTITY_FILE_NAME = "archive.identity";

  private final ArchiveIdentityPayload payload;
  private final ArchiveIdentityFileStore files;
  private final DurableStageListener stageListener;

  /** Package-private identity-only entry point for protocol tests. */
  ArchiveIdentityProtocol() {
    this(new ArchiveIdentityPayload() {
      @Override
      public void bindAndSync(Path archiveRoot, ArchiveIdentity identity) {
        // No additional test payload.
      }

      @Override
      public void verifyForActivation(Path archiveRoot, ArchiveIdentity identity) {
        // No additional test payload.
      }
    });
  }

  /** Creates a protocol whose payload is initialized and verified during resume. */
  public ArchiveIdentityProtocol(ArchiveIdentityPayload payload) {
    this(payload, new ArchiveIdentityFileStore(), stage -> { });
  }

  ArchiveIdentityProtocol(ArchiveIdentityPayload payload, ArchiveIdentityFileStore files,
      DurableStageListener stageListener) {
    this.payload = Objects.requireNonNull(payload, "payload");
    this.files = Objects.requireNonNull(files, "files");
    this.stageListener = Objects.requireNonNull(stageListener, "stageListener");
  }

  /**
   * Durably writes a PREPARED anchor without creating or claiming the archive root.
   *
   * <p>Repeating this call with the same claim is idempotent. Reusing the UUID with a different
   * nonce or fields fails closed. A new claim is rejected if its archive root is non-empty.
   */
  public ArchiveIdentity init(Path anchorDirectory, ArchiveIdentityClaim claim)
      throws IOException {
    Objects.requireNonNull(claim, "claim");
    Path anchors = requirePhysicalCanonicalPath(anchorDirectory, "anchorDirectory");
    Path archiveRoot = requirePhysicalCanonicalPath(claim.getFinalPath(), "finalPath");
    return files.withExclusiveFileLock(rootLockPath(archiveRoot),
        () -> files.withExclusiveFileLock(lockPath(anchors),
            () -> initLocked(anchors, claim, false)));
  }

  /**
   * Durably prepares an explicit claim for a complete pre-identity archive root.
   *
   * <p>The caller is responsible for supplying a payload that fully validates the existing archive
   * before activation. Normal empty-root initialization remains isolated in {@link #init}.
   */
  public ArchiveIdentity adopt(Path anchorDirectory, ArchiveIdentityClaim claim)
      throws IOException {
    Objects.requireNonNull(claim, "claim");
    Path anchors = requirePhysicalCanonicalPath(anchorDirectory, "anchorDirectory");
    Path archiveRoot = requirePhysicalCanonicalPath(claim.getFinalPath(), "finalPath");
    return files.withExclusiveFileLock(rootLockPath(archiveRoot),
        () -> files.withExclusiveFileLock(lockPath(anchors),
            () -> initLocked(anchors, claim, true)));
  }

  private ArchiveIdentity initLocked(Path anchors, ArchiveIdentityClaim claim,
      boolean adoptExistingRoot)
      throws IOException {
    Path anchor = anchorPath(anchors, claim.getUuid());
    Path rootIdentity = rootIdentityPath(claim.getFinalPath());

    Optional<ArchiveIdentity> existing = files.read(anchor);
    if (existing.isPresent()) {
      requireMatches(existing.get(), claim, "anchor");
      Pair pair = loadPair(existing.get(), rootIdentity);
      requireReachablePair(pair);
      if (pair.root == null) {
        requireClaimableRoot(claim, rootIdentity, adoptExistingRoot);
      }
      return existing.get();
    }

    requireInitialRootState(claim, rootIdentity, adoptExistingRoot);
    files.ensureDirectory(anchors);

    // Recheck after directory creation so an idempotent retry cannot overwrite a winner.
    existing = files.read(anchor);
    if (existing.isPresent()) {
      requireMatches(existing.get(), claim, "anchor");
      Pair pair = loadPair(existing.get(), rootIdentity);
      requireReachablePair(pair);
      if (pair.root == null) {
        requireClaimableRoot(claim, rootIdentity, adoptExistingRoot);
      }
      return existing.get();
    }
    requireNoCompetingAnchor(anchors, anchor, claim);
    requireInitialRootState(claim, rootIdentity, adoptExistingRoot);

    ArchiveIdentity prepared = new ArchiveIdentity(claim, ArchiveIdentityState.PREPARED);
    files.write(anchor, prepared);
    stageListener.after(DurableStage.ANCHOR_PREPARED);
    return prepared;
  }

  /**
   * Resumes binding and activation using the nonce and immutable fields in {@code claim}.
   *
   * <p>The payload callback runs only after the root identity is BOUND and must be idempotent and
   * durable. Activation always writes root ACTIVE before anchor ACTIVE.
   */
  public ArchiveIdentity resume(Path anchorDirectory, ArchiveIdentityClaim claim)
      throws IOException {
    Objects.requireNonNull(claim, "claim");
    Path anchors = requirePhysicalCanonicalPath(anchorDirectory, "anchorDirectory");
    Path archiveRoot = requirePhysicalCanonicalPath(claim.getFinalPath(), "finalPath");
    return files.withExclusiveFileLock(rootLockPath(archiveRoot),
        () -> files.withExclusiveFileLock(lockPath(anchors),
            () -> resumeLocked(anchors, claim, false)));
  }

  /** Resumes an explicitly requested legacy adoption after any durable protocol stage. */
  public ArchiveIdentity resumeAdoption(Path anchorDirectory, ArchiveIdentityClaim claim)
      throws IOException {
    Objects.requireNonNull(claim, "claim");
    Path anchors = requirePhysicalCanonicalPath(anchorDirectory, "anchorDirectory");
    Path archiveRoot = requirePhysicalCanonicalPath(claim.getFinalPath(), "finalPath");
    return files.withExclusiveFileLock(rootLockPath(archiveRoot),
        () -> files.withExclusiveFileLock(lockPath(anchors),
            () -> resumeLocked(anchors, claim, true)));
  }

  /**
   * Reconstructs a persisted claim for an explicitly requested init/resume operation.
   *
   * <p>This is deliberately separate from normal-node validation: callers must opt into resume,
   * and every immutable field plus the reachable anchor/root state pair is checked before the
   * nonce-bearing claim is returned.
   */
  public Optional<ArchiveIdentityClaim> findResumableClaim(Path anchorDirectory,
      Path archiveRoot, String expectedChainId, String expectedSchema, String expectedLayout,
      long expectedFloor) throws IOException {
    Path anchors = requirePhysicalCanonicalPath(anchorDirectory, "anchorDirectory");
    Path root = requirePhysicalCanonicalPath(archiveRoot, "archiveRoot");
    return files.withExclusiveFileLock(rootLockPath(root),
        () -> files.withExclusiveFileLock(lockPath(anchors),
            () -> findResumableClaimLocked(anchors, root, expectedChainId, expectedSchema,
                expectedLayout, expectedFloor)));
  }

  private Optional<ArchiveIdentityClaim> findResumableClaimLocked(Path anchors, Path root,
      String expectedChainId, String expectedSchema, String expectedLayout, long expectedFloor)
      throws IOException {
    ArchiveIdentity candidate = null;
    if (Files.exists(anchors, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(anchors)) {
        for (Path path : stream) {
          if (!isCanonicalUuidName(path.getFileName())) {
            continue;
          }
          ArchiveIdentity identity = files.readRequired(path, "anchor");
          if (!identity.getFinalPath().equals(root)) {
            continue;
          }
          if (candidate != null) {
            throw new ArchiveIdentityException(
                "multiple archive identities claim finalPath " + root);
          }
          candidate = identity;
        }
      }
    }

    ArchiveIdentity rootIdentity = files.read(rootIdentityPath(root)).orElse(null);
    if (candidate == null && rootIdentity == null) {
      return Optional.empty();
    }
    if (candidate == null) {
      throw new ArchiveIdentityException(
          "archive root identity has no matching external anchor");
    }
    Pair pair = loadPair(candidate, rootIdentityPath(root));
    requireReachablePair(pair);
    requireExpected(candidate, expectedChainId, expectedSchema, expectedLayout, expectedFloor);
    return Optional.of(candidate.getClaim());
  }

  private ArchiveIdentity resumeLocked(Path anchors, ArchiveIdentityClaim claim,
      boolean adoptExistingRoot)
      throws IOException {
    Path anchorPath = anchorPath(anchors, claim.getUuid());
    Path archiveRoot = claim.getFinalPath();
    Path rootPath = rootIdentityPath(archiveRoot);

    ArchiveIdentity anchor = files.readRequired(anchorPath, "anchor");
    requireMatches(anchor, claim, "anchor");
    Pair pair = loadPair(anchor, rootPath);
    requireReachablePair(pair);

    if (pair.isActive()) {
      return requireActivePair(pair, claim.getUuid(), archiveRoot);
    }

    if (pair.root == null) {
      requireState(pair.anchor, ArchiveIdentityState.PREPARED, "anchor without root");
      requireClaimableRoot(claim, rootPath, adoptExistingRoot);
      if (files.ensureDirectory(archiveRoot)) {
        stageListener.after(DurableStage.ROOT_DIRECTORY_CREATED);
      }
      requireClaimableRoot(claim, rootPath, adoptExistingRoot);
      files.write(rootPath, new ArchiveIdentity(claim, ArchiveIdentityState.BOUND));
      stageListener.after(DurableStage.ROOT_BOUND);
      pair = reloadPair(anchorPath, rootPath, claim);
      requireReachablePair(pair);
    }

    if (pair.anchor.getState() == ArchiveIdentityState.PREPARED) {
      requireState(pair.root, ArchiveIdentityState.BOUND, "root before payload binding");
      payload.bindAndSync(archiveRoot, pair.root);
      pair = reloadPair(anchorPath, rootPath, claim);
      requireStates(pair, ArchiveIdentityState.PREPARED, ArchiveIdentityState.BOUND);
      files.write(anchorPath, pair.anchor.withState(ArchiveIdentityState.BOUND));
      stageListener.after(DurableStage.ANCHOR_BOUND);
      pair = reloadPair(anchorPath, rootPath, claim);
      requireReachablePair(pair);
    }

    boolean verified = false;
    if (pair.root.getState() == ArchiveIdentityState.BOUND) {
      requireStates(pair, ArchiveIdentityState.BOUND, ArchiveIdentityState.BOUND);
      payload.verifyForActivation(archiveRoot, pair.root);
      verified = true;
      pair = reloadPair(anchorPath, rootPath, claim);
      requireStates(pair, ArchiveIdentityState.BOUND, ArchiveIdentityState.BOUND);
      files.write(rootPath, pair.root.withState(ArchiveIdentityState.ACTIVE));
      stageListener.after(DurableStage.ROOT_ACTIVE);
      pair = reloadPair(anchorPath, rootPath, claim);
      requireReachablePair(pair);
    }

    if (pair.anchor.getState() == ArchiveIdentityState.BOUND
        && pair.root.getState() == ArchiveIdentityState.ACTIVE) {
      if (!verified) {
        payload.verifyForActivation(archiveRoot, pair.root);
      }
      pair = reloadPair(anchorPath, rootPath, claim);
      requireStates(pair, ArchiveIdentityState.BOUND, ArchiveIdentityState.ACTIVE);
      files.write(anchorPath, pair.anchor.withState(ArchiveIdentityState.ACTIVE));
      stageListener.after(DurableStage.ANCHOR_ACTIVE);
      pair = reloadPair(anchorPath, rootPath, claim);
    }

    return requireActivePair(pair, claim.getUuid(), archiveRoot);
  }

  /**
   * Read-only validation used by a normal node.
   *
   * <p>Both records must exist, both must be ACTIVE, and every identity field must match.
   */
  public ArchiveIdentity validateActive(Path anchorDirectory, UUID uuid, Path archiveRoot)
      throws IOException {
    Objects.requireNonNull(uuid, "uuid");
    Path anchors = requirePhysicalCanonicalPath(anchorDirectory, "anchorDirectory");
    Path root = requirePhysicalCanonicalPath(archiveRoot, "archiveRoot");
    return files.withSharedFileLock(rootLockPath(root),
        () -> files.withSharedFileLock(lockPath(anchors),
            () -> validateActiveLocked(anchors, uuid, root)));
  }

  private ArchiveIdentity validateActiveLocked(Path anchors, UUID uuid, Path root)
      throws IOException {
    ArchiveIdentity anchor = files.readRequired(anchorPath(anchors, uuid), "anchor");
    Pair pair = loadPair(anchor, rootIdentityPath(root));
    return requireActivePair(pair, uuid, root);
  }

  /** Validates the root-selected UUID and all runtime-expected immutable identity fields. */
  public ArchiveIdentity validateActive(Path anchorDirectory, Path archiveRoot,
      String expectedChainId, String expectedSchema, String expectedLayout, long expectedFloor)
      throws IOException {
    Path anchors = requirePhysicalCanonicalPath(anchorDirectory, "anchorDirectory");
    Path root = requirePhysicalCanonicalPath(archiveRoot, "archiveRoot");
    return files.withSharedFileLock(rootLockPath(root), () -> {
      ArchiveIdentity rootIdentity = files.readRequired(rootIdentityPath(root), "root");
      return files.withSharedFileLock(lockPath(anchors), () -> {
        ArchiveIdentity active = validateActiveLocked(
            anchors, rootIdentity.getUuid(), root);
        if (!active.getChainId().equals(expectedChainId)) {
          throw mismatch("active", "chainId");
        }
        if (!active.getSchema().equals(expectedSchema)) {
          throw mismatch("active", "schema");
        }
        if (!active.getLayout().equals(expectedLayout)) {
          throw mismatch("active", "layout");
        }
        if (active.getFloor() != expectedFloor) {
          throw mismatch("active", "floor");
        }
        return active;
      });
    });
  }

  /**
   * Deletes only matching non-ACTIVE identity metadata, never archive payload files.
   *
   * <p>The root identity is deleted first. A matching anchor remains sufficient proof to resume an
   * interrupted abort. If either copy is ACTIVE, abort is rejected.
   */
  public void abort(Path anchorDirectory, ArchiveIdentityClaim claim)
      throws IOException {
    Objects.requireNonNull(claim, "claim");
    Path anchors = requirePhysicalCanonicalPath(anchorDirectory, "anchorDirectory");
    Path archiveRoot = requirePhysicalCanonicalPath(claim.getFinalPath(), "finalPath");
    files.withExclusiveFileLock(rootLockPath(archiveRoot),
        () -> files.withExclusiveFileLock(lockPath(anchors), () -> {
          abortLocked(anchors, claim);
          return null;
        }));
  }

  private void abortLocked(Path anchors, ArchiveIdentityClaim claim) throws IOException {
    Path anchorPath = anchorPath(anchors, claim.getUuid());
    Path rootPath = rootIdentityPath(claim.getFinalPath());

    ArchiveIdentity anchor = files.readRequired(anchorPath, "anchor");
    requireMatches(anchor, claim, "anchor");
    Optional<ArchiveIdentity> rootRead = files.read(rootPath);
    ArchiveIdentity root = rootRead.orElse(null);
    if (root != null) {
      requireMatches(root, claim, "root");
    }
    if (anchor.getState() == ArchiveIdentityState.ACTIVE
        || root != null && root.getState() == ArchiveIdentityState.ACTIVE) {
      throw new ArchiveIdentityException("ACTIVE archive identity cannot be aborted");
    }
    if (root != null && root.getState() != ArchiveIdentityState.BOUND) {
      throw new ArchiveIdentityException("only a BOUND root identity can be aborted");
    }

    if (root != null) {
      files.delete(rootPath, claim.getUuid());
      stageListener.after(DurableStage.ROOT_IDENTITY_ABORTED);
    } else {
      files.cleanupTemporaryFiles(rootPath, claim.getUuid());
    }

    // Re-read immediately before deleting the remaining proof and reject concurrent activation.
    anchor = files.readRequired(anchorPath, "anchor");
    requireMatches(anchor, claim, "anchor");
    if (anchor.getState() == ArchiveIdentityState.ACTIVE) {
      throw new ArchiveIdentityException("ACTIVE archive identity cannot be aborted");
    }
    files.delete(anchorPath, claim.getUuid());
    stageListener.after(DurableStage.ANCHOR_ABORTED);
  }

  public static Path anchorPath(Path anchorDirectory, UUID uuid) {
    Objects.requireNonNull(uuid, "uuid");
    return requireCanonicalAbsolutePath(anchorDirectory, "anchorDirectory")
        .resolve(uuid.toString());
  }

  public static Path rootIdentityPath(Path archiveRoot) {
    return requireCanonicalAbsolutePath(archiveRoot, "archiveRoot")
        .resolve(ROOT_IDENTITY_FILE_NAME);
  }

  private static Path lockPath(Path anchorDirectory) {
    Path name = anchorDirectory.getFileName();
    if (name == null) {
      throw new IllegalArgumentException("anchorDirectory must have a file name");
    }
    return anchorDirectory.resolveSibling("." + name + ".lock");
  }

  private static Path rootLockPath(Path archiveRoot) {
    Path name = archiveRoot.getFileName();
    if (name == null) {
      throw new IllegalArgumentException("archiveRoot must have a file name");
    }
    return archiveRoot.resolveSibling("." + name + ".identity.lock");
  }

  private Pair reloadPair(Path anchorPath, Path rootPath, ArchiveIdentityClaim claim)
      throws IOException {
    ArchiveIdentity anchor = files.readRequired(anchorPath, "anchor");
    requireMatches(anchor, claim, "anchor");
    Pair pair = loadPair(anchor, rootPath);
    if (pair.root != null) {
      requireMatches(pair.root, claim, "root");
    }
    return pair;
  }

  private Pair loadPair(ArchiveIdentity anchor, Path rootPath) throws IOException {
    Optional<ArchiveIdentity> rootRead = files.read(rootPath);
    ArchiveIdentity root = rootRead.orElse(null);
    if (root != null) {
      requireSameFields(anchor, root);
    }
    return new Pair(anchor, root);
  }

  private static ArchiveIdentity requireActivePair(Pair pair, UUID uuid, Path archiveRoot)
      throws ArchiveIdentityException {
    if (pair.anchor.getState() != ArchiveIdentityState.ACTIVE
        || pair.root == null || pair.root.getState() != ArchiveIdentityState.ACTIVE) {
      throw new ArchiveIdentityException(
          "normal archive validation requires matching ACTIVE anchor and root identities");
    }
    requireSameFields(pair.anchor, pair.root);
    if (!pair.anchor.getUuid().equals(uuid)) {
      throw new ArchiveIdentityException("anchor filename UUID does not match archive identity");
    }
    if (!pair.anchor.getFinalPath().equals(archiveRoot)) {
      throw new ArchiveIdentityException("archive identity finalPath does not match archive root");
    }
    return pair.anchor;
  }

  private static void requireReachablePair(Pair pair) throws ArchiveIdentityException {
    ArchiveIdentityState anchorState = pair.anchor.getState();
    ArchiveIdentityState rootState = pair.root == null ? null : pair.root.getState();
    boolean reachable = (anchorState == ArchiveIdentityState.PREPARED
        && (rootState == null || rootState == ArchiveIdentityState.BOUND))
        || (anchorState == ArchiveIdentityState.BOUND
        && (rootState == ArchiveIdentityState.BOUND || rootState == ArchiveIdentityState.ACTIVE))
        || (anchorState == ArchiveIdentityState.ACTIVE
        && rootState == ArchiveIdentityState.ACTIVE);
    if (!reachable) {
      throw new ArchiveIdentityException(
          "unreachable archive identity state pair: anchor=" + anchorState + ", root=" + rootState);
    }
  }

  private static void requireStates(Pair pair, ArchiveIdentityState anchorState,
      ArchiveIdentityState rootState) throws ArchiveIdentityException {
    requireState(pair.anchor, anchorState, "anchor");
    requireState(pair.root, rootState, "root");
  }

  private static void requireState(ArchiveIdentity identity, ArchiveIdentityState state,
      String role) throws ArchiveIdentityException {
    if (identity == null || identity.getState() != state) {
      throw new ArchiveIdentityException(role + " archive identity must be " + state);
    }
  }

  private static void requireSameFields(ArchiveIdentity anchor, ArchiveIdentity root)
      throws ArchiveIdentityException {
    requireMatches(root, anchor.getClaim(), "root");
  }

  private static void requireMatches(ArchiveIdentity identity, ArchiveIdentityClaim expected,
      String role) throws ArchiveIdentityException {
    if (!identity.getUuid().equals(expected.getUuid())) {
      throw mismatch(role, "uuid");
    }
    if (!MessageDigest.isEqual(identity.getResumeNonce(), expected.getResumeNonce())) {
      throw mismatch(role, "resume nonce");
    }
    if (!identity.getChainId().equals(expected.getChainId())) {
      throw mismatch(role, "chainId");
    }
    if (!identity.getSchema().equals(expected.getSchema())) {
      throw mismatch(role, "schema");
    }
    if (!identity.getLayout().equals(expected.getLayout())) {
      throw mismatch(role, "layout");
    }
    if (!identity.getFinalPath().equals(expected.getFinalPath())) {
      throw mismatch(role, "finalPath");
    }
    if (identity.getFloor() != expected.getFloor()) {
      throw mismatch(role, "floor");
    }
  }

  private static void requireExpected(ArchiveIdentity identity, String chainId, String schema,
      String layout, long floor) throws ArchiveIdentityException {
    if (!identity.getChainId().equals(chainId)) {
      throw mismatch("resume", "chainId");
    }
    if (!identity.getSchema().equals(schema)) {
      throw mismatch("resume", "schema");
    }
    if (!identity.getLayout().equals(layout)) {
      throw mismatch("resume", "layout");
    }
    if (identity.getFloor() != floor) {
      throw mismatch("resume", "floor");
    }
  }

  private void requireNoCompetingAnchor(Path anchors, Path requestedAnchor,
      ArchiveIdentityClaim claim) throws IOException {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(anchors)) {
      for (Path candidate : stream) {
        if (candidate.equals(requestedAnchor) || !isCanonicalUuidName(candidate.getFileName())) {
          continue;
        }
        ArchiveIdentity existing = files.readRequired(candidate, "anchor");
        if (existing.getFinalPath().equals(claim.getFinalPath())) {
          throw new ArchiveIdentityException(
              "archive finalPath is already claimed by UUID " + existing.getUuid());
        }
      }
    }
  }

  private static boolean isCanonicalUuidName(Path name) {
    if (name == null) {
      return false;
    }
    try {
      UUID uuid = UUID.fromString(name.toString());
      return uuid.toString().equals(name.toString());
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private void requireEmptyRootForNewInit(Path archiveRoot) throws IOException {
    try {
      files.requireDirectory(archiveRoot);
    } catch (NoSuchFileException e) {
      return;
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(archiveRoot)) {
      if (stream.iterator().hasNext()) {
        throw new ArchiveIdentityException(
            "refusing to initialize non-empty unclaimed archive root: " + archiveRoot);
      }
    }
  }

  private void requireInitialRootState(ArchiveIdentityClaim claim, Path rootIdentity,
      boolean adoptExistingRoot) throws IOException {
    if (adoptExistingRoot) {
      requireAdoptableRoot(claim, rootIdentity);
    } else {
      requireEmptyRootForNewInit(claim.getFinalPath());
    }
  }

  private void requireClaimableRoot(ArchiveIdentityClaim claim, Path rootIdentity,
      boolean adoptExistingRoot) throws IOException {
    if (adoptExistingRoot) {
      requireAdoptableRoot(claim, rootIdentity);
    } else {
      requireUnclaimedRoot(claim, rootIdentity, true);
    }
  }

  private void requireAdoptableRoot(ArchiveIdentityClaim claim, Path rootIdentity)
      throws IOException {
    Path archiveRoot = claim.getFinalPath();
    files.requireDirectory(archiveRoot);
    boolean hasPayload = false;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(archiveRoot)) {
      for (Path candidate : stream) {
        if (files.isOwnedTemporaryFile(rootIdentity, claim.getUuid(), candidate)) {
          continue;
        }
        if (candidate.equals(rootIdentity)) {
          throw new ArchiveIdentityException(
              "archive root is already claimed by an identity: " + archiveRoot);
        }
        hasPayload = true;
      }
    }
    if (!hasPayload) {
      throw new ArchiveIdentityException(
          "legacy archive adoption requires a non-empty archive root: " + archiveRoot);
    }
  }

  private void requireUnclaimedRoot(ArchiveIdentityClaim claim, Path rootIdentity,
      boolean allowOwnedTemporaryFiles) throws IOException {
    Path archiveRoot = claim.getFinalPath();
    try {
      files.requireDirectory(archiveRoot);
    } catch (NoSuchFileException e) {
      return;
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(archiveRoot)) {
      for (Path candidate : stream) {
        if (allowOwnedTemporaryFiles
            && files.isOwnedTemporaryFile(rootIdentity, claim.getUuid(), candidate)) {
          continue;
        }
        throw new ArchiveIdentityException(
            "refusing to claim non-empty archive root without a matching identity: "
                + archiveRoot);
      }
    }
  }

  private static ArchiveIdentityException mismatch(String role, String field) {
    return new ArchiveIdentityException(role + " archive identity " + field + " mismatch");
  }

  private static Path requireCanonicalAbsolutePath(Path path, String field) {
    Objects.requireNonNull(path, field);
    if (!path.isAbsolute() || !path.normalize().equals(path)) {
      throw new IllegalArgumentException(field + " must be absolute and normalized");
    }
    return path;
  }

  private static Path requirePhysicalCanonicalPath(Path path, String field) throws IOException {
    Path canonical = requireCanonicalAbsolutePath(path, field);
    if (Files.exists(canonical, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        && Files.isSymbolicLink(canonical)) {
      throw new ArchiveIdentityException(field + " must not itself be a symbolic link");
    }
    return canonical;
  }

  enum DurableStage {
    ANCHOR_PREPARED,
    ROOT_DIRECTORY_CREATED,
    ROOT_BOUND,
    ANCHOR_BOUND,
    ROOT_ACTIVE,
    ANCHOR_ACTIVE,
    ROOT_IDENTITY_ABORTED,
    ANCHOR_ABORTED
  }

  interface DurableStageListener {
    void after(DurableStage stage) throws IOException;
  }

  private static final class Pair {
    private final ArchiveIdentity anchor;
    private final ArchiveIdentity root;

    private Pair(ArchiveIdentity anchor, ArchiveIdentity root) {
      this.anchor = Objects.requireNonNull(anchor, "anchor");
      this.root = root;
    }

    private boolean isActive() {
      return anchor.getState() == ArchiveIdentityState.ACTIVE
          && root != null && root.getState() == ArchiveIdentityState.ACTIVE;
    }
  }
}
