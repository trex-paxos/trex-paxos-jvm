// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import com.github.trex_paxos.msg.DirectMessage;
import com.github.trex_paxos.msg.Fixed;
import com.github.trex_paxos.msg.TrexMessage;
import com.github.trex_paxos.network.NetworkLayer;
import lombok.With;
import org.jetbrains.annotations.TestOnly;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.Level;

import static com.github.trex_paxos.TrexLogger.LOGGER;
import static com.github.trex_paxos.network.SystemChannel.CONSENSUS;
import static com.github.trex_paxos.network.SystemChannel.PROXY;

/// TrexService is an interface that defines the core functionality of a Paxos consensus service.
///
/// The class is parameterized by:
/// - COMMAND - The application-specific command type that will be submitted for consensus
/// - RESULT - The application-specific result type produced after a command is chosen
///
/// @param <COMMAND> The type of commands submitted to the consensus algorithm
/// @param <RESULT> The type of results produced after commands are chosen
/// TODO the proxying to the leader has no resilience i am in two minds whether that is okay
public sealed interface TrexService<COMMAND, RESULT> permits TrexService.Implementation {
  /// Submit a command for consensus processing
  ///
  /// @param command The command to process
  /// @return A future that will be completed with the result
  CompletableFuture<RESULT> submit(COMMAND command);

  /// Start the service
  void start();

  /// Stop the service
  void stop();

  /// Handle incoming consensus messages
  void handleConsensusMessage(TrexMessage msg);

  /// Handle incoming proxy messages
  void handleProxyMessage(Command cmd);

  /// Get the pickler used by this service
  ///
  /// @return The pickler
  Pickler<COMMAND> pickler();

  /// Create a new configuration builder with default settings
  static <C, R> Config<C, R> config() {
    return new Config<>(
        null,                  // nodeId
        null,                  // legislatorsSupplier
        null,                  // quorumStrategy
        null,                  // journal
        null,                  // commandHandler
        null,                  // networkLayer
        null,                  // pickler
        Duration.ofMillis(500), // heartbeatInterval
        Duration.ofSeconds(3),  // electionTimeout
        false                  // applicationManagesTransactions
    );
  }

  /// Immutable configuration for building a TrexService instance
  @With
  record Config<C, R>(
      // Core cluster configuration
      NodeId nodeId,
      Supplier<Legislators> legislatorsSupplier,
      QuorumStrategy quorumStrategy,

      // Integration points
      Journal journal,
      BiFunction<Long, C, R> commandHandler,

      // Network and serialization
      NetworkLayer networkLayer,
      Pickler<C> pickler,

      // Timing configuration
      Duration heartbeatInterval,
      Duration electionTimeout,

      // Advanced settings
      boolean applicationManagesTransactions
  ) {

    /// Convenience method to set both timing parameters at once
    public Config<C, R> withTiming(Duration heartbeat, Duration election) {
      return new Config<>(
          nodeId, legislatorsSupplier, quorumStrategy, journal, commandHandler,
          networkLayer, pickler, heartbeat, election, applicationManagesTransactions
      );
    }

    /// Builds the TrexService from the configuration
    public TrexService<C, R> build() {
      // Validate configuration
      if (nodeId == null) throw new IllegalStateException("NodeId must be specified");
      if (legislatorsSupplier == null) throw new IllegalStateException("Legislators must be specified");
      if (quorumStrategy == null) throw new IllegalStateException("QuorumStrategy must be specified");
      if (journal == null) throw new IllegalStateException("Journal must be specified");
      if (commandHandler == null) throw new IllegalStateException("CommandHandler must be specified");
      if (networkLayer == null) throw new IllegalStateException("NetworkLayer must be specified");
      if (pickler == null) throw new IllegalStateException("Pickler must be specified");

      final BiFunction<Long, C, R> commandHandler = (slot, cmd) -> {
        return null;
      };

      final BiFunction<Long, Command, R> stuff = (slot, cmd) -> {
        return commandHandler.apply(slot, pickler.deserialize(cmd.operationBytes()));
      };

      // Create and return the service implementation
      return new Implementation<>(this);
    }
  }

  /// Implementation of TrexService that manages the consensus process
  final class Implementation<C, R> implements TrexService<C, R> {
    // Configuration
    final Config<C, R> config;

    // Core components
    TrexEngine<R> engine;
    TrexNode node;

    // Trackers
    final LeaderTracker leaderTracker;
    final ResponseTracker<R> responseTracker;

    // Scheduling
    ScheduledFuture<?> heartbeatTask;
    ScheduledFuture<?> electionTask;

    // State
    volatile boolean running;

    Implementation(Config<C, R> config) {
      this.config = config;
      this.leaderTracker = new LeaderTracker();
      this.responseTracker = new ResponseTracker<>();
      this.running = false;

      // Initialize the node and engine
      this.node = new TrexNode(
          Level.INFO,
          config.nodeId().id(),
          config.quorumStrategy(),
          config.journal()
      );

      final var commandHandler = (BiFunction<Long, Command, R>) (slot, cmd) -> {
        // Deserialize the command
        final var value = config.pickler().deserialize(cmd.operationBytes());
        // Call the application command handler
        return config.commandHandler().apply(slot, value);
      };

      this.engine = new TrexEngine<>(node, commandHandler);
    }

    /// The host application value that we will run consensus over to have it chosen.
    @Override
    public CompletableFuture<R> submit(C value) {
      if (!running) {
        CompletableFuture<R> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException("Service not running"));
        return future;
      }

      // Generate a unique ID for this command
      UUID uuid = UUIDGenerator.generateUUID();
      String commandId = uuid.toString();

      // Create a future to track the response
      CompletableFuture<R> responseFuture = new CompletableFuture<>();
      responseTracker.register(commandId, responseFuture);

      // If we're the leader, process directly
      if (leaderTracker.isLeader(config.nodeId())) {
        try {
          // Serialize the command
          byte[] serializedCommand = config.pickler().serialize(value);

          // Create a Command object
          Command cmd = new Command(uuid, serializedCommand, (byte) 0);

          // Generate consensus messages and transmit them
          List<TrexMessage> messages = engine.nextLeaderBatchOfMessages(List.of(cmd));
          transmitMessages(messages);
        } catch (Exception e) {
          responseTracker.completeExceptionally(commandId, e);
        }
      } else {
        // Forward to the leader if we know who it is
        NodeId leaderId = leaderTracker.getLeaderId();
        if (leaderId != null) {
          try {
            // Serialize command
            byte[] serializedCommand = config.pickler().serialize(value);

            // Create a Command object to send to the leader
            Command cmd = new Command(uuid, serializedCommand, (byte) 0);

            // Send to leader via proxy channel
            config.networkLayer().send(PROXY.value(), leaderId, cmd);

          } catch (Exception e) {
            responseTracker.completeExceptionally(commandId, e);
          }
        } else {
          responseTracker.completeExceptionally(commandId,
              new IllegalStateException("No leader available"));
        }
      }

      return responseFuture;
    }

    @Override
    public void handleConsensusMessage(TrexMessage msg) {
      if (msg == null || msg.from() == engine.nodeIdentifier()) {
        LOGGER.finer(() -> engine.nodeIdentifier() + " dropping consensus message " + msg);
        return;
      }

      var messages = paxosThenUpCall(List.of(msg));

      LOGGER.finer(() -> engine.nodeIdentifier() + " processed " + msg + " responding with " + messages);
      messages.stream()
          .filter(m -> m instanceof Fixed)
          .map(m -> (Fixed) m)
          .forEach(leaderTracker::updateFromFixed);

      transmitMessages(messages);
    }

    @Override
    public void handleProxyMessage(Command cmd) {
      if (!engine.isLeader()) {
        LOGGER.finest(() -> String.format("[Node %d] Not leader, dropping proxy: %s",
            config.nodeId().id(), cmd.uuid()));
        return;
      }
      LOGGER.fine(() -> engine.nodeIdentifier() + " leader received proxied message " + cmd.uuid());
      try {
        var messages = engine.nextLeaderBatchOfMessages(List.of(cmd));
        transmitMessages(messages);
      } catch (Exception e) {
        LOGGER.severe(() -> engine.nodeIdentifier() + " handleProxyMessage failed: " + e.getMessage());
      }
    }

    // Helper method to transmit TrexMessages
    private void transmitMessages(List<TrexMessage> messages) {
      for (TrexMessage message : messages) {
        if (message instanceof DirectMessage directMessage) {
          config.networkLayer().send(CONSENSUS.value(), new NodeId(directMessage.to()), message);
        } else {
          // Broadcast to all nodes
          config.legislatorsSupplier.get().otherNodes(config.nodeId()).forEach(nodeId ->
              config.networkLayer().send(CONSENSUS.value(), nodeId, message));
        }
      }
    }

    /// Processes messages through Paxos and handles results.
    private List<TrexMessage> paxosThenUpCall(List<TrexMessage> messages) {
      LOGGER.finer(() -> engine.nodeIdentifier() + " paxosThenUpCall input: " + messages);
      EngineResult<R> result = engine.paxos(messages);
      result.results().forEach(hostResult -> {
        LOGGER.fine(() -> engine.nodeIdentifier() + " completing callback for " + hostResult.uuid());
        responseTracker.complete(hostResult.uuid().toString(), hostResult.result());
      });
      final var response = result.messages();
      LOGGER.finer(() -> engine.nodeIdentifier() + " paxosThenUpCall output: " + response);
      return response;
    }

    @Override
    public void start() {
      if (running) {
        return;
      }

      try {
        running = true;

        LOGGER.info("TrexService started with node ID: " +
            config.nodeId().id());
      } catch (Exception e) {
        LOGGER.severe(() -> "Failed to start TrexService " + e.getMessage());
        stop();
        throw new RuntimeException("Failed to start TrexService", e);
      }
    }

    @Override
    public void stop() {

      if (!running) {
        return;
      }

      running = false;

      // Cancel scheduled tasks
      if (heartbeatTask != null) {
        heartbeatTask.cancel(true);
      }

      if (electionTask != null) {
        electionTask.cancel(true);
      }

      // Close the engine
      if (engine != null) {
        try {
          engine.close();
        } catch (Exception e) {
          LOGGER.warning(() -> "Error closing TrexEngine: " + e);
        }
      }

      // Complete any pending responses with exceptions
      responseTracker.completeAllExceptionally(
          new IllegalStateException("Service stopped"));

      LOGGER.info("TrexService stopped");
    }

    @Override
    public Pickler<C> pickler() {
      return config.pickler();
    }

    /// Set this node as the leader (for testing)
    public void setLeader() {
      engine.setLeader();
      leaderTracker.setLeader(config.nodeId());
    }

    /// Get the leader tracker (for testing)
    @TestOnly
    public LeaderTracker getLeaderTracker() {
      return leaderTracker;
    }
  }

  /// Tracks the current leader in the cluster
  class LeaderTracker {
    final AtomicReference<NodeId> currentLeaderId = new AtomicReference<>(null);

    void updateFromFixed(Fixed msg) {
      setLeader(new NodeId(msg.leader()));
    }

    @TestOnly
    public void setLeader(NodeId nodeId) {
      currentLeaderId.set(nodeId);
    }

    NodeId getLeaderId() {
      return currentLeaderId.get();
    }

    boolean isLeader(NodeId thisId) {
      NodeId leaderId = currentLeaderId.get();
      return leaderId != null && leaderId.equals(thisId);
    }
  }

  /// Tracks responses to submitted commands
  class ResponseTracker<R> {
    private final ConcurrentHashMap<String, CompletableFuture<R>> pendingResponses =
        new ConcurrentHashMap<>();

    void register(String commandId, CompletableFuture<R> future) {
      pendingResponses.put(commandId, future);
    }

    void complete(String commandId, R result) {
      CompletableFuture<R> future = pendingResponses.remove(commandId);
      if (future != null) {
        future.complete(result);
      }
    }

    void completeExceptionally(String commandId, Throwable exception) {
      CompletableFuture<R> future = pendingResponses.remove(commandId);
      if (future != null) {
        future.completeExceptionally(exception);
      }
    }

    void completeAllExceptionally(Throwable exception) {
      for (CompletableFuture<R> future : pendingResponses.values()) {
        future.completeExceptionally(exception);
      }
      pendingResponses.clear();
    }
  }
}
