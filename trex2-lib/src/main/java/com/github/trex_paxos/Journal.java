package com.github.trex_paxos;

import com.github.trex_paxos.msg.Accept;
import com.github.trex_paxos.msg.Progress;

import java.util.Optional;

/// The journal is the storage layer of the Paxos Algorithm. It is also used as the replicated log for the state machine.
/// This API is designed to be simple and easy to implement via a NavigableMap interface such as a BTreeMap or MVStore.
/// Yet you could implement it with a SQL database or a distributed key value store that has ordered keys.
///
/// VERY IMPORTANT: The journal must be crash durable. This means that the journal must commit to disk after every write (fsync).
/// This must be done before any messages are sent from the node after processing any input messages.
///
/// Only when an empty node is fist created the journal must have a `NoOperation.NOOP` accept journaled at log index 0.
/// It must also have the nodes progress saved as `new Progress(noteIdentifier)`. When a cold cluster is started up the
/// nodes will time out to and will attempt to prepare the slot 1 which is why it must container a NOOP.
public interface Journal {
  /**
   * Save the progress record to durable storage. This method must force the disk.
   *
   * @param progress The highest promised, committed and accepted operationBytes.
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
   * @param nodeIdentifier The node identifier to load the progress record for. To avoid accidentally loading the wrong
   *                       history when moving nodes between servers we require the node identifier. This is only a safety feature.
   */
  Progress loadProgress(byte nodeIdentifier);

  /**
   * Load any accept record from the log. There may be no accept record for the given log index.
   *
   * @param logIndex The log slot to load the accept record for.
   */
  Optional<Accept> loadAccept(long logIndex);
}
