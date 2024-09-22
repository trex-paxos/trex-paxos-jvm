package com.github.trex_paxos;

import com.github.trex_paxos.msg.Commit;
import com.github.trex_paxos.msg.Prepare;
import com.github.trex_paxos.msg.TrexMessage;

import java.util.List;
import java.util.Optional;

@SuppressWarnings("unused")
public abstract class TrexEngine {
  final TrexNode trexNode;

  /**
   * Create a new TrexEngine which uses virtual threads for timeouts, heartbeats and TODO retries.
   *
   * @param trexNode   The underlying TrexNode.
   * @param minTimeout The minimum timeout in milliseconds. This should be greater than 2x time max latency plush 2x disk fsych time.
   * @param maxTimeout The maximum timeout in milliseconds. The actual timeout will be a random value between minTimeout and maxTimeout.
   */
  public TrexEngine(TrexNode trexNode) {
    this.trexNode = trexNode;
  }

  abstract void setRandomTimeout();

  abstract void resetTimeout();

  /**
   * The main entry point for the Trex paxos algorithm. This method will recurse without returning when we need to
   * send a message to ourselves. As a side effect the progress record will be updated and the journal will be updated.
   * <p>
   * After this method returns the application must first commit both the updated progress and the updated log to disk
   * using an `fsync` or equivalent which is FileDescriptor::sync in Java. Only after the kernel has flushed any disk
   * buffers and confirmed that the updated state is on disk the application can send the messages out to the cluster.
   * <p>
   * As an optimisation the leader can prepend a fresh commit message to the outbound messages.
   * <p>
   * This method is synchronized as we should only process a single Paxos message. It also recreates the timeout thread.
   *
   * @param input The message to process.
   * @return A list of messages to send out to the cluster. Normally it will be one message yet recovery will prepare many slots.
   * @throws AssertionError If the algorithm is in an invalid state.
   */
  public synchronized List<TrexMessage> paxos(TrexMessage input) {
    /*
     * If we are not the leader. And we receive a commit message from another node. And the log index is greater than
     * our current progress. We interrupt the timeout thread to stop the timeout and recreate it.
     */
    if (input instanceof Commit(final var from, final var logIndex)
        && !trexNode.isLeader()
        && from != trexNode.nodeIdentifier()
        && logIndex > trexNode.highestCommitted()
    ) {
      resetTimeout();
    }
    return trexNode.paxos(input);
  }

  public void start() {
    setRandomTimeout();
  }

  public Optional<Prepare> timeout() {
    return trexNode.timeout();
  }

  public List<TrexMessage> receive(TrexMessage p) {
    return trexNode.paxos(p);
  }
}
