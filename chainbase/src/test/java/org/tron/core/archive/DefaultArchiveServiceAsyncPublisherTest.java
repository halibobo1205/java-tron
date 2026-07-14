package org.tron.core.archive;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.archive.capture.ArchiveCaptureHolder;
import org.tron.core.archive.domain.DefaultArchiveDomainCatalog;
import org.tron.core.archive.domain.DefaultArchiveDomainRegistry;
import org.tron.core.archive.query.ArchiveQueryLimits;
import org.tron.core.archive.reader.ArchiveReadThrough;
import org.tron.core.archive.temporal.InMemoryArchiveTemporalStore;
import org.tron.core.archive.txnum.InMemoryArchiveTxNumIndex;
import org.tron.core.capsule.BlockCapsule;

public class DefaultArchiveServiceAsyncPublisherTest {

  @After
  public void clearCaptureHolder() {
    ArchiveCaptureHolder.clear();
  }

  @Test
  public void asyncRequestPublishesDurableJournalOneBlockAtATime() throws Exception {
    DefaultArchiveService service = service(8, 16, 1_000);
    BlockCapsule block = block(1);
    journalEmptyBlock(service, block);

    try (ArchiveMutationLease mutation = service.acquireMutationReadLease()) {
      service.requestPublishSolidifiedBlocks(1, block.getBlockId().getBytes());
    }

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!service.hasCommittedBlock(1) && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertTrue(service.hasCommittedBlock(1));
    service.close();
  }

  @Test
  public void hardWatermarkFailsBeforeNextWriterLease() {
    DefaultArchiveService service = service(1, 1, 0);
    journalEmptyBlock(service, block(1));

    ArchiveException failure = assertThrows(ArchiveException.class, service::awaitWriterCapacity);
    assertTrue(failure.getMessage().contains("hard watermark"));
    assertThrows(ArchiveException.class, service::acquireWriterLease);
    service.close();
  }

  private static DefaultArchiveService service(int softLimit, int hardLimit,
      long timeoutMs) {
    return new DefaultArchiveService(true, new InMemoryArchiveTxNumIndex(),
        ArchiveExecutionContextHolder.get(), new InMemoryArchiveTemporalStore(),
        new InMemoryArchiveInFlightStore(), new DefaultArchiveDomainRegistry(),
        new DefaultArchiveDomainCatalog(), ArchiveReadThrough.NONE,
        ArchiveLifecycle.Phase.RUNNING, ArchiveQueryLimits.unlimited(),
        new ArchivePublisherConfig(true, true, softLimit, hardLimit, timeoutMs), () -> {
        });
  }

  private static void journalEmptyBlock(DefaultArchiveService service, BlockCapsule block) {
    service.beginBlock(block, ArchiveSource.NORMAL);
    service.beginSystemTx(block, ArchivePhase.BLOCK_PREPARE);
    service.endTx();
    service.beginSystemTx(block, ArchivePhase.BLOCK_FINALIZE);
    service.endTx();
    service.commitBlock(block, 0);
  }

  private static BlockCapsule block(long number) {
    return new BlockCapsule(number, Sha256Hash.ZERO_HASH, number, ByteString.EMPTY);
  }
}
