package org.tron.core.archive.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class ArchiveQueryLimitsTest {

  @Test
  public void defaultConfigurationHasUnlimitedResourcesAndFailFastAcquire() {
    ArchiveQueryLimits limits = ArchiveQueryLimits.unlimited();

    assertSame(limits, ArchiveQueryLimits.unlimited());
    assertEquals(ArchiveQueryLimits.UNLIMITED, limits.getMaxConcurrentQueries());
    assertEquals(ArchiveQueryLimits.UNLIMITED, limits.getMaxPendingQueries());
    assertEquals(ArchiveQueryLimits.UNLIMITED, limits.getMaxOpenSnapshots());
    assertEquals(0, limits.getAcquireTimeoutMs());
    assertEquals(ArchiveQueryLimits.UNLIMITED, limits.getDeadlineMs());
    assertEquals(ArchiveQueryLimits.UNLIMITED, limits.getMaxQueriesPerBatch());
    assertEquals(ArchiveQueryLimits.UNLIMITED, limits.getBatchDeadlineMs());
    assertEquals(ArchiveQueryLimits.UNLIMITED, limits.getMaxLogicalReadsPerRequest());
    assertEquals(ArchiveQueryLimits.UNLIMITED, limits.getMaxBackendReadsPerRequest());
    assertEquals(ArchiveQueryLimits.DEFAULT_MAX_CACHED_ENTRIES, limits.getMaxCachedEntries());
    assertEquals(ArchiveQueryLimits.DEFAULT_MAX_CACHED_BYTES, limits.getMaxCachedBytes());
    assertEquals(ArchiveQueryLimits.UNLIMITED, limits.getMaxVmSteps());
    assertEquals(ArchiveQueryLimits.UNLIMITED, limits.getMaxTraceBytes());
    assertEquals(ArchiveQueryLimits.UNLIMITED, limits.getMaxRetainedTraceBytes());
    assertEquals(ArchiveQueryLimits.UNLIMITED, limits.getMaxResponseBytes());
    assertEquals(limits, new ArchiveQueryLimits());
  }

  @Test
  public void builderAndAliasesPreserveAllValues() {
    ArchiveQueryLimits limits = ArchiveQueryLimits.builder()
        .maxConcurrentQueries(3)
        .maxPendingQueries(4)
        .maxOpenSnapshots(14)
        .acquireTimeoutMillis(5)
        .deadlineMillis(6)
        .maxQueriesPerBatch(15)
        .batchDeadlineMs(16)
        .maxLogicalReads(7)
        .maxBackendReads(8)
        .maxCachedEntries(9)
        .maxCachedBytes(10)
        .maxTraceSteps(11)
        .maxTraceBytes(12)
        .maxRetainedTraceBytes(13)
        .maxTraceResponseBytes(17)
        .build();

    assertEquals(3, limits.getMaxConcurrentQueries());
    assertEquals(4, limits.getMaxPendingQueries());
    assertEquals(14, limits.getMaxOpenSnapshots());
    assertEquals(5, limits.getAcquireTimeoutMs());
    assertEquals(5, limits.getAcquireTimeoutMillis());
    assertEquals(6, limits.getDeadlineMs());
    assertEquals(6, limits.getDeadlineMillis());
    assertEquals(15, limits.getMaxQueriesPerBatch());
    assertEquals(16, limits.getBatchDeadlineMs());
    assertEquals(7, limits.getMaxLogicalReads());
    assertEquals(8, limits.getMaxBackendReads());
    assertEquals(9, limits.getMaxCachedEntries());
    assertEquals(10, limits.getMaxCachedBytes());
    assertEquals(11, limits.getMaxTraceSteps());
    assertEquals(12, limits.getMaxTraceBytes());
    assertEquals(13, limits.getMaxRetainedTraceBytes());
    assertEquals(17, limits.getMaxTraceResponseBytes());
    assertEquals(limits, limits.toBuilder().build());
    assertEquals(limits.hashCode(), limits.toBuilder().build().hashCode());
  }

  @Test
  public void invalidLimitsAreRejectedAtConstruction() {
    assertThrows(IllegalArgumentException.class,
        () -> ArchiveQueryLimits.builder().maxConcurrentQueries(0).build());
    assertThrows(IllegalArgumentException.class,
        () -> ArchiveQueryLimits.builder().maxPendingQueries(-2).build());
    assertThrows(IllegalArgumentException.class,
        () -> ArchiveQueryLimits.builder().maxOpenSnapshots(-2).build());
    assertThrows(IllegalArgumentException.class,
        () -> ArchiveQueryLimits.builder().acquireTimeoutMs(-2).build());
    assertThrows(IllegalArgumentException.class,
        () -> ArchiveQueryLimits.builder().deadlineMs(-2).build());
    assertThrows(IllegalArgumentException.class,
        () -> ArchiveQueryLimits.builder().maxQueriesPerBatch(0).build());
    assertThrows(IllegalArgumentException.class,
        () -> ArchiveQueryLimits.builder().batchDeadlineMs(-2).build());
    assertThrows(IllegalArgumentException.class,
        () -> ArchiveQueryLimits.builder().maxLogicalReads(-2).build());
    assertThrows(IllegalArgumentException.class,
        () -> ArchiveQueryLimits.builder().maxBackendReads(-2).build());
    assertThrows(IllegalArgumentException.class,
        () -> ArchiveQueryLimits.builder().maxCachedEntries(-1).build());
    assertThrows(IllegalArgumentException.class,
        () -> ArchiveQueryLimits.builder().maxCachedBytes(-1).build());
    assertThrows(IllegalArgumentException.class,
        () -> ArchiveQueryLimits.builder().maxVmSteps(-2).build());
    assertThrows(IllegalArgumentException.class,
        () -> ArchiveQueryLimits.builder().maxTraceBytes(-2).build());
    assertThrows(IllegalArgumentException.class,
        () -> ArchiveQueryLimits.builder().maxRetainedTraceBytes(-2).build());
    assertThrows(IllegalArgumentException.class,
        () -> ArchiveQueryLimits.builder().maxResponseBytes(-2).build());
  }
}
