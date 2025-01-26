/*
 * Copyright 2024 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import com.github.trex_paxos.network.NodeId;
import org.jetbrains.annotations.TestOnly;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

import static com.github.trex_paxos.TrexLogger.LOGGER;

/// The TrexEngine manages the timeout behaviours that surround the core Paxos algorithm.
/// It is closable to use try-with-resources to ensure that the TrexNode is closed properly on exceptions due to bad
/// data or journal write exceptions.
/// Subclasses must implement the timeout and heartbeat methods and can choose to do things like use a failure detection library.
/// The core paxos algorithm is implemented in the TrexNode class that is wrapped by this class.
/// This creates a clear separation between the core algorithm and the implementation of timeouts and shutdown logic.
public abstract class TrexEngine implements AutoCloseable {

  public static final String THREAD_INTERRUPTED = "TrexEngine was interrupted awaiting the mutex probably to shutdown while under load.";

  /// The underlying TrexNode that is the actual Part-time Parliament algorithm implementation guarded by this class.
  final protected TrexNode trexNode;

  final NodeId nodeId;

  /// This is the single most dangerous setting in the entire system. It defines if the host application is managing
  /// transactions. In which case you must catch any exceptions and call {@link #crash()}. You must also not forget
  /// to actually commit the journal before sending out any messages. You need to be aware that a SQL commit is not
  /// a guarantee that the data is disk durable. Using five nodes with them spanning three resilience zones
  /// may or may not be enough. You need to know the specifics of your database and your data lose requirements.
  final public boolean hostManagedTransactions;

  /// Create a new TrexEngine which wraps a TrexNode. This constructor assumes that the host application is not managing
  /// transactions. This is the default and recommended setting. Then {@link Journal#sync()} will be called within {@link #paxos(List)}.
  /// You must ensure that you supply a Journal where the data is crash durable after each call to {@link Journal#sync()}.
  ///
  /// @param trexNode               The underlying TrexNode which must be pre-configured with a Journal and QuorumStrategy.
  public TrexEngine(TrexNode trexNode) {
    this.trexNode = trexNode;
    this.hostManagedTransactions = false;
    this.nodeId = new NodeId(trexNode.nodeIdentifier());
  }

  /// Create a new TrexEngine which wraps a TrexNode. If `hostManagedTransactions=true` the
  /// {@link Journal#sync()} will never be called. You must ensure that you managed the journal transactions such that
  /// you are satisfied that the data is crash durable before any messages returned by {@link #paxos(List)} are sent out
  /// to the network.
  ///
  /// @param trexNode               The underlying TrexNode which must be pre-configured with a Journal and QuorumStrategy.
  /// @param hostManagedTransactions If true the host application will manage transactions and the TrexNode will not call {@link Journal#sync()} the journal.
  @SuppressWarnings("unused") // TODO: maybe do a postgres example
  public TrexEngine(TrexNode trexNode,
                    boolean hostManagedTransactions) {
    this.trexNode = trexNode;
    this.nodeId = new NodeId(trexNode.nodeIdentifier());
    this.hostManagedTransactions = hostManagedTransactions;
    final var clusterSize = trexNode.clusterSize();
    if (clusterSize < 5 && hostManagedTransactions) {
      LOGGER.warning(this.getClass().getSimpleName() +
          " WARNING clusterSize==" + clusterSize + " is less than five and hostManagedTransactions=true are you sure " +
          "any commit on your journal data store or database is truly crash durable?");
    }
  }

  /// This method must schedule a call to the timeout method at some point in the future.
  /// It should first clear any existing timeout by calling clearTimeout.
  /// The timeout should be a random id between a high and low id that is specific to your message latency.
  protected abstract void setRandomTimeout();

  /// Reset the timeout for the current node to call calling the timeout method.
  /// This should cancel any existing timeout that was created by setRandomTimeout.
  protected abstract void clearTimeout();

  /// This method must schedule a call to the heartbeat method at some point in the future.
  /// The heartbeat should be a fixed period which is less than the minimum of the random timeout minimal id.
  protected abstract void setNextHeartbeat();

  /// Create the next accept message for the next selected given command.
  ///
  /// @param command The command to create the next accept message for.
  /// @return The next accept message.
  private TrexMessage command(Command command) {
    // only if we are leader do we create the next accept message into the next log index
    final var nextAcceptMessage = trexNode.nextAcceptMessage(command);
    // we self accept
    final var r = trexNode.paxos(nextAcceptMessage);
    // the result is ignorable a self ack so does not need to be transmitted.
    assert r.commands().isEmpty() && r.messages().size() == 1 && r.messages().getFirst() instanceof AcceptResponse;
    // forward to the cluster the new accept and at the same time heartbeat a fixed message.
    return nextAcceptMessage;
  }

  /// Create the next leader batch of messages for the given set of commands. This should be called by the host application
  /// when {@link #isLeader()} is true.
  public List<TrexMessage> nextLeaderBatchOfMessages(List<Command> command) {
    if (trexNode().isFollow()) {
      LOGGER.fine(() -> "node " + nodeIdentifier() + " is ignoring nextLeaderBatchOfMessages as we are not the leader");
      return List.of();
    }
    // toList is immutable so we concat streams first.
    return Stream.concat(
        command.stream().map(this::command),
        Stream.of(trexNode.currentFixedMessage())
    ).toList();
  }

  /// We want to be friendly to Virtual Threads so we use a semaphore with a single permit to ensure that we only
  /// process one message at a time. This is recommended over using a synchronized blocks on JDK 22.`
  private final Semaphore mutex = new Semaphore(1);

  /// The main entry point for the Trex paxos algorithm. This method is thread safe and allows only one thread at a
  /// time. The following may happen:
  ///
  /// 1. The message may be ignored when safe to do so (e.g. slot higher than known fixed or late vote when vote has been won or lost).
  /// 2. As a side effect the {@link Journal} will be updated. See all the warnings in the Journal interface javadoc.
  /// 3. Internally the {@link TrexNode} will recurse once or twice with the mutex is held when it generates a broadcast message a responses to those messages.
  ///  to send messages to itself. As that happens while holding a mutex it
  /// is an instantaneous exchange of messages with one node in the cluster which is itself.
  ///
  /// This method will recurse without returning when we need to
  /// send a message to ourselves.
  ///
  /// This method uses a mutex to allow only one thread to be processing messages at a time.
  ///
  /// @param trexMessages The batch of messages to process.
  /// @return A list of messages to send out to the cluster and/or a list of selected Commands.
  public TrexResult paxos(List<TrexMessage> trexMessages) {
    try {
      mutex.acquire();
      try {
        // we should ignore messages sent from ourselves which happens in a true broadcast network and simulation unit tests.
        final var results = trexMessages
            .stream()
            .filter(m -> m.from() != trexNode.nodeIdentifier())
            .map(this::paxosNotThreadSafe)
            .toList();
        // if the journal is within the same transactional store as the host application and the host application is
        // managing transactions then the host application must call commit on the journal before we send out any messages.
        // see the java doc on Journal.sync for more details.
        if (!hostManagedTransactions) {
          // we must sync the journal before we send out any messages.
          trexNode.journal.sync();
        }
        // merge all the results into one
        return TrexResult.merge(results);
      } finally {
        mutex.release();
      }
    } catch (InterruptedException e) {
      // This is likely going to happen when we are shutting down the system. So we do not treat outs
      Thread.currentThread().interrupt();
      LOGGER.warning(THREAD_INTERRUPTED);
      trexNode.crash();
      return TrexResult.noResult();
    }
  }

  /// This method is not public as it is not thread safe. It is called from the public paxos method which is guarded
  /// by a mutex. This method manages the timeout behaviours.
  private TrexResult paxosNotThreadSafe(TrexMessage trexMessage) {
    if (evidenceOfLeader(trexMessage)) {
      if (trexNode.getRole() != TrexNode.TrexRole.FOLLOW) {
        trexNode.abdicate();
      }
      setRandomTimeout();
    }

    final var oldRole = trexNode.getRole();

    // This runs the actual algorithm
    final var result = trexNode.paxos(trexMessage);

    final var newRole = trexNode.getRole();

    if (trexNode.isLeader()) {
      setNextHeartbeat();
    } else if (trexNode.isRecover()) {
      setNextHeartbeat();
    }

    if (oldRole != newRole) {
      if (oldRole == TrexNode.TrexRole.LEAD) {
        setRandomTimeout();
      } else if (newRole == TrexNode.TrexRole.LEAD) {
        clearTimeout();
      }
    }
    return result;
  }

  boolean evidenceOfLeader(TrexMessage input) {
    return switch (input) {
      case Fixed fixed -> !trexNode.isLeader()
          && fixed.from() != trexNode.nodeIdentifier()
          && fixed.slot() >= trexNode.highestFixed()
      ;
      case Accept accept -> !trexNode.isLeader()
          && accept.from() != trexNode.nodeIdentifier()
          && (accept.slot() > trexNode.highestAccepted()
          || accept.slot() > trexNode.highestFixed());

      case AcceptResponse acceptResponse -> trexNode.isLeader()
          && acceptResponse.from() != trexNode.nodeIdentifier()
          && acceptResponse.highestFixedIndex() > trexNode.highestFixed();

      default -> false;
    };
  }

  /// This should start the timeout behaviour.
  public void start() {
    setRandomTimeout();
  }

  /// This is called when the node has timed out so that it can start a leader election and the recover protocol.
  protected Optional<Prepare> timeout() {
    var result = trexNode.timeout();
    if (result.isPresent()) {
      LOGGER.fine(() -> "Timeout: " + trexNode.nodeIdentifier() + " " + trexNode.getRole());
      setRandomTimeout();
    }
    return result;
  }

  // FIXME this is too hard to understand and test. Try to come up with something less complex.
  protected List<TrexMessage> createHeartbeatMessagesAndReschedule() {
    var result = trexNode.createHeartbeatMessages();
    if (!result.isEmpty()) {
      setNextHeartbeat();
      LOGGER.finer(() -> "Heartbeat: " + trexNode.nodeIdentifier() + " " + trexNode.getRole() + " " + result);
    }
    return result;
  }

  protected TrexNode trexNode() {
    return trexNode;
  }

  public boolean isLeader() {
    return trexNode.isLeader();
  }

  /// Please use try-with-resources to ensure that the TrexNode is closed properly on exceptions due to bad data or journal write exceptions.
  @Override
  public void close() {
    LOGGER.info("Closing TrexEngine. We are marking TrexNode as stopped.");
    trexNode.close();
  }

  /// Ensure you call this method if you are managing transactions if you have any network exceptions including timeouts.
  @SuppressWarnings("unused")
  public void crash() {
    LOGGER.severe("Crashing TrexEngine. We are marking TrexNode as crashed.");
    trexNode.crash();
  }

  /// Useful for monitoring or alerting.
  @SuppressWarnings("unused")
  public boolean isClosed() {
    return trexNode.isClosed();
  }

  /// Useful for monitoring and alerting. If this returns true then this node is dead and will refuse to do any more work
  /// you should restart the node to have it read the durable state of the journal to then rejoin the cluster.
  @SuppressWarnings("unused")
  public boolean isCrashed() {
    return trexNode.isCrashed();
  }

  public TrexNode.TrexRole getRole() {
    return trexNode.getRole();
  }

  public short nodeIdentifier() {
    return trexNode.nodeIdentifier();
  }

  @TestOnly
  protected void setLeader() {
    this.trexNode().setLeader();
  }

  public NodeId nodeId() {
    return nodeId;
  }
}

