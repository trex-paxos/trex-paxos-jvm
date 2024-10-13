package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.logging.Logger;

public abstract class TrexEngine {
  static final Logger LOGGER = Logger.getLogger(TrexEngine.class.getName());

  final TrexNode trexNode;

  TrexNode trexNode() {
    return trexNode;
  }
  /**
   * Create a new TrexEngine which uses virtual threads for timeouts, heartbeats and TODO retries.
   *
   * @param trexNode  The underlying TrexNode.
   */
  public TrexEngine(TrexNode trexNode) {
    this.trexNode = trexNode;
  }

  /**
   * Set a random timeout for the current node. This method is called when the node is not the leader.
   */
  abstract void setRandomTimeout();

  /**
   * Reset the timeout for the current node. This method is called when the node is not the leader when it receives a
   * message from the leader.
   */
  abstract void clearTimeout();

  /**
   * Set the heartbeat for the current node. This method is called when the node is the leader or a recoverer.
   */
  abstract void setHeartbeat();

  /**
   * Process an application command sent from a client. This is only done by a leader. It will return a single accept
   * plus it will also add a commit message to the outbound messages.
   */
  List<TrexMessage> command(Command command) {
    if (trexNode.isLeader()) {
      final var nextExcept = trexNode.nextAcceptMessage(command);
      trexNode.paxos(nextExcept);
      return List.of(nextExcept, trexNode.makeCommitMessage());
    } else {
      return Collections.emptyList();
    }
  }

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
   * @return A list of messages to send out to the cluster and/or a list of selected Commands.
   * @throws AssertionError If the algorithm is in an invalid state.
   */
  public TrexResult paxos(TrexMessage input) {
    // we should ignore messages sent from ourselves which happens in a true broadcast network and simulation unit tests.
    if (input.from() == trexNode.nodeIdentifier()) {
      return TrexResult.noResult();
    }
    try {
      mutex.acquire();
      try {
        return paxosNotThreadSafe(input);
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
   * <p>
   * This method will run our paxos algorithm and set or reset timeouts and heartbeats as required.
   */
  TrexResult paxosNotThreadSafe(TrexMessage input) {
    if (evidenceOfLeader(input)) {
      if (trexNode.getRole() != TrexRole.FOLLOW) trexNode.backdown();
      clearTimeout();
      setRandomTimeout();
    }

    final var oldRole = trexNode.getRole();

    final var result = trexNode.paxos(input);

    final var newRole = trexNode.getRole();

    if (trexNode.isLeader()) {
      // this line says we must always see our own heartbeat to set a new heartbeat.
      clearTimeout();
      setHeartbeat();
      // TODO recoverer should heartbeat prepares until the network is stable.
    } else if (trexNode.isRecover()) {
      setHeartbeat();
    }

    if (oldRole != newRole) {
      if (oldRole == TrexRole.LEAD) {
        setRandomTimeout();
      }
    }

    return result;
  }

  private boolean evidenceOfLeader(TrexMessage input) {
    return switch (input) {
      case Commit commit -> !trexNode.isLeader()
          && commit.from() != trexNode.nodeIdentifier()
          && commit.logIndex() >= trexNode.highestCommitted();
      case Accept accept -> !trexNode.isLeader()
          && accept.from() != trexNode.nodeIdentifier()
          && accept.logIndex() > trexNode.highestCommitted();
      default -> false;
    };
  }

  public void start() {
    setRandomTimeout();
  }

  public Optional<Prepare> timeout() {
    var result = trexNode.timeout();
    if (result.isPresent()) {
      LOGGER.info("timeout:\n\t" + trexNode.nodeIdentifier() + " " + trexNode.getRole());
      setRandomTimeout();
    }
    return result;
  }

  public List<TrexMessage> heartbeat() {
    var result = trexNode.heartbeat();
    if (!result.isEmpty()) {
      setHeartbeat();
    }
    LOGGER.info("heartbeat: " + trexNode.nodeIdentifier() + " " + trexNode.getRole() + " " + result);
    return result;
  }

}
