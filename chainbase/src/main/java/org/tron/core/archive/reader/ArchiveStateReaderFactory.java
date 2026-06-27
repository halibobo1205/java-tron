package org.tron.core.archive.reader;

/**
 * Opens an {@link ArchiveStateReader} bound to a resolved {@link ArchiveStatePoint}. An interface
 * (not just a constructor) so the JSON-RPC layer and tests can inject a fake reader factory.
 */
public interface ArchiveStateReaderFactory {

  ArchiveStateReader open(ArchiveStatePoint point) throws ArchiveReaderException;
}
