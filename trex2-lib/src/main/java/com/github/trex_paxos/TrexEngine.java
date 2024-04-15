package com.github.trex_paxos;

import java.security.SecureRandom;
import java.util.List;

@SuppressWarnings("unused")
public class TrexEngine {
  final TrexNode trexNode;

  final long heartbeatPeriod;

  final long minTimeout;

  final long maxTimeout;

  final SecureRandom random = new SecureRandom();

  /**
   * Create a new TrexEngine which uses virtual threads for timeouts, heartbeats and TODO retries.
   *
   * @param trexNode   The underlying TrexNode.
   * @param minTimeout The minimum timeout in milliseconds. This should be greater than 2x time max latency plush 2x disk fsych time.
   * @param maxTimeout The maximum timeout in milliseconds. The actual timeout will be a random value between minTimeout and maxTimeout.
   */
  public TrexEngine(TrexNode trexNode, Long heartbeatPeriod, long minTimeout, long maxTimeout) {
    this.trexNode = trexNode;
    this.heartbeatPeriod = heartbeatPeriod;
    this.minTimeout = minTimeout;
    this.maxTimeout = maxTimeout;
  }

  Thread timeoutThread;

  Thread heartbeatThread;

  public synchronized void start() {
    heartbeatThread = Thread.ofVirtual().start(() -> {
      try {
        Thread.sleep(heartbeatPeriod);
        if (trexNode.isLeader())
          trexNode.hostApplication.heartbeat(trexNode.heartbeatCommit());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    setRandomTimeout();
  }

  private synchronized void setRandomTimeout() {
    timeoutThread = Thread.ofVirtual().start(() -> {
      try {
        Thread.sleep(random.nextInt((int) (maxTimeout - minTimeout)) + minTimeout);
        trexNode.timeout().ifPresent(trexNode.hostApplication::timeout);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
  }

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
   * @return A list of messages to send out to the cluster.
   * @throws AssertionError If the algorithm is in an invalid state.
   */
  public synchronized List<TrexMessage> paxos(TrexMessage input) {
    // check our invariants
    assert input != null;
    assert timeoutThread != null;
    assert heartbeatThread != null && heartbeatThread.isAlive();
    /*
     * If we are not the leader. And we receive a commit message from another node. And the log index is greater than
     * our current progress. We interrupt the timeout thread to stop the timeout and recreate it.
     */
    if (input instanceof Commit(final var from, final var logIndex)
        && !trexNode.isLeader()
        && from != trexNode.nodeIdentifier()
        && logIndex > trexNode.highestCommitted()
    ) {
      timeoutThread.interrupt();
      setRandomTimeout();
    }
    return trexNode.paxos(input);
  }

  public void stop() {
    timeoutThread.interrupt();
    heartbeatThread.interrupt();
  }

  public void join() throws InterruptedException {
    heartbeatThread.join();
  }
}
