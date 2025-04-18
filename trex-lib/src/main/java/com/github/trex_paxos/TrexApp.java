// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import com.github.trex_paxos.msg.DirectMessage;
import com.github.trex_paxos.msg.Fixed;
import com.github.trex_paxos.msg.TrexMessage;
import com.github.trex_paxos.network.NetworkLayer;
import org.jetbrains.annotations.TestOnly;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static com.github.trex_paxos.TrexLogger.LOGGER;
import static com.github.trex_paxos.network.SystemChannel.CONSENSUS;
import static com.github.trex_paxos.network.SystemChannel.PROXY;

/// Main entry point for applications using Trex Paxos consensus.
///
/// TrexApp manages the lifecycle of a Paxos consensus node, handling network communication,
/// command submission, and result delivery. It coordinates between the network layer and
/// the consensus engine.
///
/// The class is parameterized by:
/// - COMMAND - The application-specific command type that will be submitted for consensus
/// - RESULT - The application-specific result type produced after a command is chosen
///
/// @param <COMMAND> The type of commands submitted to the consensus algorithm
/// @param <RESULT> The type of results produced after commands are chosen
/// TODO the proxying to the leader has no resilience i am in two minds whether that is okay
public class TrexApp<COMMAND, RESULT> {

  protected class LeaderTracker {
    volatile NodeId estimatedLeader = null;

    void updateFromFixed(Fixed msg) {
      var knownLeader = new NodeId(msg.leader());
      if (!Objects.equals(estimatedLeader, knownLeader)) {
        LOGGER.fine(() -> engine.trexNode.nodeIdentifier() + " role changed from " + estimatedLeader + " to " + knownLeader);
        estimatedLeader = knownLeader;
      }
    }

    Optional<NodeId> currentLeader() {
      return Optional.ofNullable(estimatedLeader);
    }
  }

  protected class ResponseTracker<R> {
    private final Map<UUID, CompletableFuture<R>> pending = new ConcurrentHashMap<>();

    void track(UUID id, CompletableFuture<R> future) {
      LOGGER.finer(() -> engine.trexNode.nodeIdentifier() + " tracking response for " + id);
      pending.put(id, future);
    }

    void complete(UUID id, R result) {
      pending.computeIfPresent(id, (_, f) -> {
        f.complete(result);
        LOGGER.fine(() -> engine.trexNode.nodeIdentifier() + " complete " + id);
        return null;
      });
    }

    void fail(UUID id, Throwable ex) {
      pending.computeIfPresent(id, (_, f) -> {
        f.completeExceptionally(ex);
        LOGGER.fine(() -> engine.trexNode.nodeIdentifier() + " fail " + id);
        return null;
      });
    }

    void remove(UUID id) {
      pending.remove(id);
    }
  }

  protected final TrexEngine<RESULT> engine;
  protected final NetworkLayer networkLayer;
  protected final Supplier<Legislators> latestConfiguration;
  protected final LeaderTracker leaderTracker = new LeaderTracker();
  protected final ResponseTracker<RESULT> responseTracker = new ResponseTracker<>();
  protected final Pickler<COMMAND> valuePickler;
  public final NodeId nodeId;

  /// Constructs a new TrexApp instance.
  ///
  /// @param latestConfiguration Supplies the node endpoints for the cluster
  /// @param engine The consensus engine that implements the Paxos algorithm
  /// @param networkLayer The network communication layer
  /// @param valuePickler Serializer/deserializer for commands
  public TrexApp(
      Supplier<Legislators> latestConfiguration,
      TrexEngine<RESULT> engine,
      NetworkLayer networkLayer,
      Pickler<COMMAND> valuePickler) {
    this.engine = engine;
    this.networkLayer = networkLayer;
    this.latestConfiguration = latestConfiguration;
    this.valuePickler = valuePickler;
    this.nodeId = new NodeId(engine.nodeIdentifier());
  }

  // TODO delete this
  public void init() {
    if (engine.isLeader()) {
      // normally a node is started before it can become leader this scenario happens during unit tests
      leaderTracker.updateFromFixed(new Fixed(engine.nodeIdentifier(), 0L, BallotNumber.MIN));
    }
  }

  protected List<TrexMessage> createLeaderMessages(Command cmd) {
    return engine.nextLeaderBatchOfMessages(List.of(cmd));
  }

  /// Handles incoming consensus messages. It processes the message using the Paxos algorithm and transmits any resulting
  /// messages. The host application up-call is triggered for any fixed messages, catchup messages or accept response
  /// messages. Messages are dropped if they are from the current node (to avoid self-loops). If the node gets any
  /// `Fixed` messages from another leader node it updates its estimate of where the leader is to proxy client commands.
  /// Finally it transmits the resulting messages to the network.
  protected void handleConsensusMessage(TrexMessage msg) {
    if (msg == null || msg.from() == engine.nodeIdentifier()) {
      LOGGER.finer(() -> engine.nodeIdentifier() + " is dropping consensus message " + msg);
      return;
    }

    var messages = paxosThenUpCall(List.of(msg));

    LOGGER.finer(() -> engine.nodeIdentifier() + " has processed " + msg + " and is responding with " + messages);
    messages.stream()
        .filter(m -> m instanceof Fixed)
        .map(m -> (Fixed) m)
        .forEach(leaderTracker::updateFromFixed);

    transmitTrexMessages(messages);
  }

  /// Notes that are not the leader will proxy the client commands to the node that they estimate is the leader.
  /// This method handler is called when a node receives a proxied message from another node.
  protected void handleProxyMessage(Command cmd) {
    if (!engine.isLeader()) {
      // TODO: should we add a nack to the proxy message?
      LOGGER.finest(() -> String.format("[Node %d] Not leader, dropping proxy: %s", nodeId.id(), cmd.uuid()));
      return;
    }
    LOGGER.fine(() -> engine.nodeIdentifier() + " leader is has received proxied message " + cmd.uuid());
    try {
      // TODO: should we add an ack to the proxy message and tell them who we think is the leader?
      var messages = createLeaderMessages(cmd);
      transmitTrexMessages(messages);
    } catch (Exception e) {
      LOGGER.severe(() -> engine.nodeIdentifier() + " handleProxyMessage failed: " + e.getMessage());
    }
  }

  public void submitValue(COMMAND value, CompletableFuture<RESULT> future) {
    submitValue(value, future, (byte) 0);
  }

  public void submitValue(COMMAND value, CompletableFuture<RESULT> future, byte type) {
    if (type < 0) {
      throw new IllegalArgumentException("type must be non-negative as it is reserved for internal use");
    }
    startConsensusProtocolOrProxyToLeader(value, future, type);
  }

  /// If the current node is a leader it will transmit accept messages. If it is not the leader it will attempt to proxy
  /// the messages to the current leader.
  /// A timeout on the
  /// future does not mean that the message has not been chosen but rather that the application has not received a
  /// any information about whether the command has been chosen or not.
  ///
  /// @param value The application command to submit for consensus
  /// @param future A future that will be completed with the result when the command is chosen
  /// @param type A byte value representing the command type where a negative type is reserved for internal use.
  /// @see #startConsensusProtocolOrProxyToLeader
  protected void startConsensusProtocolOrProxyToLeader(COMMAND value, CompletableFuture<RESULT> future, byte type) {
    final var uuid = UUIDGenerator.generateUUID();
    try {
      responseTracker.track(uuid, future);

      if (engine.isLeader()) {
        byte[] valueBytes = valuePickler.serialize(value);
        final var cmd = new Command(uuid, valueBytes, type);
        final var messages = createLeaderMessages(cmd);
        LOGGER.fine(() -> engine.nodeIdentifier() + " leader is sending slotTerm messages " + messages);
        transmitTrexMessages(messages);
      } else {
        leaderTracker.currentLeader().ifPresentOrElse(
            leader -> {
              LOGGER.fine(() -> engine.nodeIdentifier() + " " + engine.trexNode.getRole() + " is proxying cmd messages" + value);
              byte[] valueBytes = valuePickler.serialize(value);
              Command cmd = new Command(uuid, valueBytes, type);
              networkLayer.send(PROXY.value(), leader, cmd);
            },
            () -> {
              var ex = new IllegalStateException("No leader available");
              LOGGER.warning(() -> engine.nodeIdentifier() + " " + engine.trexNode.getRole() + " failed to proxy cmd messages " + value + " because: " + ex.getMessage());
              responseTracker.fail(uuid, ex);
              responseTracker.remove(uuid);
            }
        );
      }
    } catch (Exception e) {
      responseTracker.fail(uuid, e);
      responseTracker.remove(uuid);
    }
  }

  /// Runs the Paxos algorithm over a list of messages then transmits any resulting messages.
  /// This method will side effect by updating the journal as necessary and also calling the host application
  /// callback if any commands are fixed.
  /// @param messages The input TrexMessages to process
  /// @return List of outbound TrexMessages to be sent to other nodes
  protected List<TrexMessage> paxosThenUpCall(List<TrexMessage> messages) {
    LOGGER.finer(() -> engine.nodeIdentifier() + " paxosThenUpCall input: " + messages);
    EngineResult<RESULT> result = engine.paxos(messages);
    result.results().forEach(hostResult -> {
      LOGGER.fine(() -> engine.nodeIdentifier() + " paxosThenUpCall completing callback for " + hostResult.uuid());
      responseTracker.complete(hostResult.uuid(), hostResult.result());
    });
    final var response = result.messages();
    LOGGER.finer(() -> engine.nodeIdentifier() + " paxosThenUpCall output: " + response);
    return response;
  }

  /// Transmits a list of Trex messages across the network to their respective destinations.
  /// DirectMessage instances are sent to specific nodes while other messages are broadcast.
  /// Delegates to the NetworkLayer to handle actual transmission and NodeEndpoints for broadcasts.
  ///
  /// @param messages The list of Trex messages to be transmitted
  protected void transmitTrexMessages(List<TrexMessage> messages) {
    messages.forEach(message -> {
      if (message instanceof DirectMessage directMessage) {
        LOGGER.finer(() -> engine.nodeIdentifier() + " sending direct message " + directMessage);
        networkLayer.send(CONSENSUS.value(), new NodeId(directMessage.to()), message);
      } else {
        LOGGER.finer(() -> engine.nodeIdentifier() + " broadcasting message " + message);
        networkLayer.broadcast(latestConfiguration, CONSENSUS.value(), message);
      }
    });
  }

  /// Stops the Paxos node, shutting down the network layer and closing the engine.
  public void stop() {
      engine.close();
  }

  /// This method is called in unit tests to force a node to be leader when the network is started.
  /// It is completely safe to have two nodes attempting to be lead however this method is not expected to be using by
  /// normal application logic.
  @SuppressWarnings("SameParameterValue")
  @TestOnly
  protected void setLeader(short i) {
    if (i == engine.nodeIdentifier()) {
      engine.setLeader();
    }
    leaderTracker.estimatedLeader = new NodeId(i);
  }
}
