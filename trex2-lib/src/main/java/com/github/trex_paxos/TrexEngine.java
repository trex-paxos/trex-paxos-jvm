package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.logging.Logger;

/// The TrexEngine manages the timeout behaviours and aggregates strategies that surround the core Paxos algorithm.
/// The core paxos algorithm is implemented in the TrexNode class that is wrapped by this class. A TrexEngine is
/// abstract. Subclasses must override methods to manage the timeout and heartbeat mechanisms. This is because
/// we do not use threads or wall clock time in the unit tests.
public abstract class TrexEngine {
  static final Logger LOGGER = Logger.getLogger(TrexEngine.class.getName());

  /// The underlying TrexNode that is the actual Part-time Parliament algorithm implementation guarded by this class.
  final TrexNode trexNode;

  /// Create a new TrexEngine which wraps a TrexNode.
  ///
  /// @param trexNode The underlying TrexNode which must be pre-configured with a concrete Journal and QuorumStrategy.
  public TrexEngine(TrexNode trexNode) {
    this.trexNode = trexNode;
  }

  /// Set a random timeout for the current node. This method is called when the node is not the leader. It must first
  /// rest any existing timeout by calling clearTimeout. It must then set a new timeout.
  abstract void setRandomTimeout();

  /// Reset the timeout for the current node. This method is called when the node is not the leader when it receives a
  /// message from the leader.
  abstract void clearTimeout();

  /// Set the heartbeat for the current node. This method is called when the node is the leader or a recoverer.
  abstract void setHeartbeat();

  /// Process an application command sent from a client. This is only done by a leader. It will return a single accept
  /// then append a commit message as it is very cheap for another node to filter out commits it has already seen.
  List<TrexMessage> command(Command command) {
    if (trexNode.isLeader()) {
      // only if we are leader do we create the next accept message into the next log index
      final var nextAcceptMessage = trexNode.nextAcceptMessage(command);
      // we self accept
      final var r = trexNode.paxos(nextAcceptMessage);
      // the result is ignorable a self ack so does not need to be transmitted.
      assert r.commands().isEmpty() && r.messages().size() == 1 && r.messages().getFirst() instanceof AcceptResponse;
      // forward to the cluster the new accept and at the same time heatbeat a commit message.
      return List.of(nextAcceptMessage, trexNode.currentCommitMessage());
    } else {
      return Collections.emptyList();
    }
  }

  /// We want to be friendly to Virtual Threads so we use a semaphore with a single permit to ensure that we only
  /// process one message at a time. This is recommended over using a synchronized blocks.
  Semaphore mutex = new Semaphore(1);

  /**
   * The main entry point for the Trex paxos algorithm. This method will recurse without returning when we need to
   * send a message to ourselves. As a side effect the progress record will be updated and the journal will be updated.
   * <p>
   * After this method returns the application must first commit both the updated progress and the updated log to durable
   * storage using something like `fsync` or equivalent which is FileDescriptor::sync in Java. Only after the kernel has
   * the durable storage and confirmed that the state survives a crash can we send the returned messages.
   * <p>
   * As an optimization the leader can choose to prepend a fresh commit message to the outbound messages.
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
        final var r = paxosNotThreadSafe(input);
        trexNode.journal.sync();
        return r;
      } finally {
        mutex.release();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("TrexEngine was interrupted awaiting the mutex probably to shutdown while under load.", e);
    }
  }

  /// This method is not public as it is not thread safe. It is called from the public paxos method which is protected
  /// by a mutex.
  ///
  /// This method will run our paxos algorithm ask to reset timeouts and heartbeats as required. Subclasses must provide
  /// the timeout and heartbeat methods.
  TrexResult paxosNotThreadSafe(TrexMessage input) {
    if (evidenceOfLeader(input)) {
      if (trexNode.getRole() != TrexRole.FOLLOW)
        trexNode.backdown();
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
          && commit.committedLogIndex() >= trexNode.highestCommitted()
      ;
      case Accept accept -> !trexNode.isLeader()
          && accept.from() != trexNode.nodeIdentifier()
          && (accept.logIndex() > trexNode.highestAccepted()
          || accept.logIndex() > trexNode.highestCommitted());

      case AcceptResponse acceptResponse -> trexNode.isLeader()
          && acceptResponse.from() != trexNode.nodeIdentifier()
          && acceptResponse.progress().highestCommittedIndex() > trexNode.highestCommitted();
      
      default -> false;
    };
  }

  public void start() {
    setRandomTimeout();
  }

  public Optional<Prepare> timeout() {
    var result = trexNode.timeout();
    if (result.isPresent()) {
      LOGGER.fine("timeout:\n\t" + trexNode.nodeIdentifier() + " " + trexNode.getRole());
      setRandomTimeout();
    }
    return result;
  }

  public List<TrexMessage> heartbeat() {
    var result = trexNode.heartbeat();
    if (!result.isEmpty()) {
      setHeartbeat();
      LOGGER.fine(() -> "heartbeat: " + trexNode.nodeIdentifier() + " " + trexNode.getRole() + " " + result);
    }
    return result;
  }

  TrexNode trexNode() {
    return trexNode;
  }
}
