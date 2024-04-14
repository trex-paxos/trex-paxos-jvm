package com.github.trex_paxos;

import java.util.Optional;

/**
 * The journal is the storage layer of the Paxos Algorithm. It is also used as the replicated log for the state machine.
 * This API is designed to be simple and easy to implement via a NavigableMap interface such as a BTreeMap or MVStore.
 */
public interface Journal {
  /**
   * Save the progress record to durable storage. This method must force the disk.
   *
   * @param progress The highest promised, committed and accepted values.
   */
  void saveProgress(Progress progress);

  /**
   * Save the accept record to the log. This method must force the disk.
   *
   * @param accept An accept that is not yet chosen until the log index is committed.
   */
  void journalAccept(Accept accept);

  /**
   * Load the progress record from durable storage.
   *
   * @param nodeIdentifier The node identifier to load the progress record for.
   */
  Progress loadProgress(byte nodeIdentifier);

  /**
   * Load any accept record from the log. There may be no accept record for the given log index.
   *
   * @param logIndex The log slot to load the accept record for.
   */
  Optional<Accept> loadAccept(long logIndex);

  /**
   * Commit the log up to the given index. This method must force the disk.
   *
   * @param l The log index to commit up to.
   */
  void committed(byte nodeIdentifier, long l);
}
