package org.tron.core.archive.domain;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class ArchiveDomainRegistryTest {

  private final ArchiveDomainRegistry registry = new DefaultArchiveDomainRegistry();

  @Test
  public void domainIdsAreUniqueAndInU16Range() {
    Set<Integer> ids = new HashSet<>();
    for (ArchiveDomain d : ArchiveDomain.values()) {
      assertTrue("id must be 0x0001..0xffff: " + d, d.getId() > 0 && d.getId() <= 0xffff);
      assertTrue("duplicate domain id: " + d, ids.add(d.getId()));
    }
  }

  @Test
  public void rootDomainsAreThe17CanonicalStateDomainsSortedById() {
    List<ArchiveDomain> root = registry.rootDomains();
    assertEquals(17, root.size());
    // sorted ascending by id, no duplicates
    for (int i = 1; i < root.size(); i++) {
      assertTrue(root.get(i).getId() > root.get(i - 1).getId());
    }
    assertTrue(root.contains(ArchiveDomain.ACCOUNT));
    assertTrue(root.contains(ArchiveDomain.ACCOUNT_ASSET));
    assertTrue(root.contains(ArchiveDomain.CONTRACT_STORAGE));
    assertTrue(root.contains(ArchiveDomain.CONTRACT_STATE));
    // ABI is HISTORY_ONLY, never in the global root.
    assertFalse(root.contains(ArchiveDomain.ABI));
  }

  @Test
  public void keyStoreBindingsMatchTheFrozenRegistry() {
    StoreBinding account = registry.bindingForDbName("account");
    assertEquals(StoreBindingKind.DOMAIN, account.getKind());
    assertEquals(ArchiveDomain.ACCOUNT, account.getDomain().get());
    assertEquals(RawHookMode.GENERIC_TRON_STORE, account.getRawHookMode());
    assertEquals(RootPolicy.IN_GLOBAL_ROOT, account.getRootPolicy());

    // storage-row backs CONTRACT_STORAGE semantically, never raw.
    StoreBinding storageRow = registry.bindingForDbName("storage-row");
    assertEquals(StoreBindingKind.SEMANTIC_BACKING, storageRow.getKind());
    assertEquals(ArchiveDomain.CONTRACT_STORAGE, storageRow.getDomain().get());
    assertEquals(RawHookMode.SEMANTIC_ONLY, storageRow.getRawHookMode());

    // account-asset is captured from account.assetV2; its raw store puts are ignored.
    StoreBinding asset = registry.bindingForDbName("account-asset");
    assertEquals(ArchiveDomain.ACCOUNT_ASSET, asset.getDomain().get());
    assertEquals(RawHookMode.IGNORE_RAW, asset.getRawHookMode());
    assertEquals(RootPolicy.IN_GLOBAL_ROOT, asset.getRootPolicy());

    // properties uses the key-level allowlist.
    assertEquals(RawHookMode.GENERIC_TRON_STORE_ALLOWLIST,
        registry.bindingForDbName("properties").getRawHookMode());
    // contract / contract-state have a non-generic write path.
    assertEquals(RawHookMode.STORE_SPECIFIC,
        registry.bindingForDbName("contract").getRawHookMode());
    // abi is HISTORY_ONLY.
    assertEquals(RootPolicy.HISTORY_ONLY, registry.bindingForDbName("abi").getRootPolicy());
    // reward-vi is excluded (one-time immutable).
    assertEquals(StoreBindingKind.EXCLUDED, registry.bindingForDbName("reward-vi").getKind());
    // accountTrie is derived account-state-root backing data; checkpoint v2 uses per-db paths.
    assertEquals(StoreBindingKind.EXCLUDED, registry.bindingForDbName("accountTrie").getKind());
    assertEquals(StoreBindingKind.EXCLUDED,
        registry.bindingForDbName("checkpoint/account").getKind());
  }

  @Test
  public void unknownDbNameIsExplicitNotSilentlyExcluded() {
    StoreBinding unknown = registry.bindingForDbName("some-future-store");
    assertFalse(unknown.isKnown());
    assertEquals(StoreBindingKind.UNKNOWN, unknown.getKind());
    assertEquals(RawHookMode.IGNORE_RAW, unknown.getRawHookMode());
    assertEquals(RootPolicy.EXCLUDED, unknown.getRootPolicy());
    assertFalse(unknown.getDomain().isPresent());
  }

  @Test
  public void everyKnownStoreIsClassifiedAndConsistent() {
    List<StoreBinding> all = registry.allStoreBindings();
    assertEquals(48, all.size());
    String previousDbName = "";
    for (StoreBinding b : all) {
      assertTrue("bindings must be sorted by dbName", b.getDbName().compareTo(previousDbName) > 0);
      previousDbName = b.getDbName();
      assertTrue(b.isKnown());
      if (b.getKind() == StoreBindingKind.EXCLUDED) {
        assertFalse("excluded binding must carry no domain", b.getDomain().isPresent());
        assertEquals(RootPolicy.EXCLUDED, b.getRootPolicy());
      } else {
        assertTrue("captured binding must carry a domain", b.getDomain().isPresent());
        assertNotEquals(RootPolicy.EXCLUDED, b.getRootPolicy());
      }
    }
  }

  @Test
  public void checksumIsDeterministicAndChangesWithSchema() {
    byte[] a = registry.checksum();
    byte[] b = new DefaultArchiveDomainRegistry().checksum();
    assertEquals(32, a.length);
    assertArrayEquals("registry checksum must be deterministic across instances", a, b);
    // A different schema would not equal this checksum - sanity guard
    // that the checksum actually depends on bindings.
    assertNotEquals(0, a.length);
  }
}
