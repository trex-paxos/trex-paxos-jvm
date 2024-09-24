package com.github.trex_paxos;

import com.github.trex_paxos.msg.Commit;
import com.github.trex_paxos.msg.Prepare;
import com.github.trex_paxos.msg.TrexMessage;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.logging.Logger;

@SuppressWarnings("unused")
public abstract class TrexEngine {
  static final Logger LOGGER = Logger.getLogger(TrexEngine.class.getName());

  final TrexNode trexNode;

  TrexNode trexNode() {
    return trexNode;
  }
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

  Semaphore mutex = new Semaphore(1);

  /**
   * The main entry point for the Trex paxos algorithm. This method will recurse without returning when we need to
   * send a message to ourselves. As a side effect the progress record will be updated and the journal will be updated.
   * <p>
   * After this method returns the application must first commit both the updated progress and the updated log to durable
   * storage using something like `fsync` or equivalent which is FileDescriptor::sync in Java. Only after the kernel has
   * the durable storage and confirmed that the state survives a crash can we send the returned messages.
   * <p>
   * As an optimisation the leader can choose to prepend a fresh commit message to the outbound messages.
   * <p>
   * This method uses a mutex as we should only process a single Paxos message and update durable storage one at a time.
   *
   * @param input The message to process.
   * @return A list of messages to send out to the cluster. Typically, a single message yet recovery may prepare many slots.
   * @throws AssertionError If the algorithm is in an invalid state.
   */
  public List<TrexMessage> paxos(TrexMessage input) {
    // if we are using broadcast technology we should ignore messages sent from the same node.
    if (input.from() == trexNode.nodeIdentifier()) {
      return Collections.emptyList();
    }
    try {
      mutex.acquire();
      try {
        return paxosSingleThread(input);
      } finally {
        mutex.release();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted");
    }
  }

  /**
   * This method is not public as it is not thread safe. It is called from the public paxos method which is protected
   * by a mutex.
   */
  List<TrexMessage> paxosSingleThread(TrexMessage input) {

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
    var oldRole = trexNode.getRole();
    var result = trexNode.timeout();
    var newRole = trexNode.getRole();
    LOGGER.info(trexNode.nodeIdentifier() + " " + trexNode.getRole());
    return result;
  }

  public List<TrexMessage> receive(TrexMessage p) {
    var oldRole = trexNode.getRole();
    var result = trexNode.paxos(p);
    var newRole = trexNode.getRole();
    if( oldRole != newRole ){
      LOGGER.info(trexNode.nodeIdentifier() + " " + newRole);
    }
    return result;
  }
}
