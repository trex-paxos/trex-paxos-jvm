package com.github.trex_paxos;

import com.github.trex_paxos.msg.DirectMessage;
import com.github.trex_paxos.msg.TrexMessage;
import com.github.trex_paxos.network.NetworkAddress;
import com.github.trex_paxos.network.NetworkLayer;
import lombok.With;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import static com.github.trex_paxos.TrexLogger.LOGGER;
import static com.github.trex_paxos.network.SystemChannel.CONSENSUS;
import static com.github.trex_paxos.network.SystemChannel.PROXY;

public interface TrexService<C, R> {
    CompletableFuture<R> submit(C command);
    void start();
    void stop();

    /**
     * Create a new configuration builder with default settings
     */
    static <C, R> Config<C, R> config() {
        return Config.defaults();
    }

    /**
     * Immutable configuration for building a TrexService instance
     */
    @With
    record Config<C, R>(
        // Core cluster configuration
        NodeId nodeId,
        Map<NodeId, NetworkAddress> endpoints,
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
        /**
         * Creates a default configuration with sensible defaults where possible
         */
        public static <C, R> Config<C, R> defaults() {
            return new Config<>(
                null,                  // nodeId
                Map.of(),              // endpoints
                null,                  // quorumStrategy
                null,                  // journal
                null,                  // commandHandler
                null,                  // networkLayer
                null,                  // pickler
                Duration.ofMillis(500), // heartbeatInterval
                Duration.ofSeconds(2),  // electionTimeout
                false                  // applicationManagesTransactions
            );
        }

        /**
         * Convenience method to set both timing parameters at once
         */
        public Config<C, R> withTiming(Duration heartbeat, Duration election) {
            return new Config<>(
                nodeId, endpoints, quorumStrategy, journal, commandHandler,
                networkLayer, pickler, heartbeat, election, applicationManagesTransactions
            );
        }

        /**
         * Builds the TrexService from the configuration
         */
        public TrexService<C, R> build() {
            // Validate configuration
            if (nodeId == null) throw new IllegalStateException("NodeId must be specified");
            if (endpoints.isEmpty()) throw new IllegalStateException("Endpoints must be specified");
            if (quorumStrategy == null) throw new IllegalStateException("QuorumStrategy must be specified");
            if (journal == null) throw new IllegalStateException("Journal must be specified");
            if (commandHandler == null) throw new IllegalStateException("CommandHandler must be specified");
            if (networkLayer == null) throw new IllegalStateException("NetworkLayer must be specified");
            if (pickler == null) throw new IllegalStateException("Pickler must be specified");

            // Create and return the service implementation
            return new TrexServiceImpl<>(this);
        }
    }

    /**
     * Implementation of TrexService that manages the consensus process
     */
    class TrexServiceImpl<C, R> implements TrexService<C, R> {
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
        
        TrexServiceImpl(Config<C, R> config) {
            this.config = config;
            this.leaderTracker = new LeaderTracker();
            this.responseTracker = new ResponseTracker<>();
            this.running = false;
        }
        
        @Override
        public CompletableFuture<R> submit(C command) {
            if (!running) {
                CompletableFuture<R> future = new CompletableFuture<>();
                future.completeExceptionally(new IllegalStateException("Service not running"));
                return future;
            }
            
            // Generate a unique ID for this command
            String commandId = UUID.randomUUID().toString();
            UUID uuid = UUID.fromString(commandId);
            
            // Create a future to track the response
            CompletableFuture<R> responseFuture = new CompletableFuture<>();
            responseTracker.register(commandId, responseFuture);
            
            // If we're the leader, process directly
            if (leaderTracker.isLeader(this.engine.nodeId())) {
                try {
                    // Serialize the command
                    byte[] serializedCommand = config.pickler().serialize(command);
                    
                    // Create a Command object
                    Command cmd = new Command(uuid, serializedCommand, (byte)0);
                    
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
                        // Forward the command to the leader
                        NetworkAddress leaderAddress = config.endpoints().get(leaderId);
                        if (leaderAddress != null) {
                            // Serialize command
                            byte[] serializedCommand = config.pickler().serialize(command);
                            
                            // Create a Command object to send to the leader
                            Command cmd = new Command(uuid, serializedCommand, (byte)0);
                            
                            // Send to leader via proxy channel
                            config.networkLayer().send(PROXY.value(), leaderId, cmd);
                        } else {
                            responseTracker.completeExceptionally(commandId, 
                                new IllegalStateException("Leader address not found"));
                        }
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
        
        // Helper method to transmit TrexMessages
        private void transmitMessages(List<TrexMessage> messages) {
            // Implementation similar to TrexApp.transmitTrexMessages
            for (TrexMessage message : messages) {
                if (message instanceof DirectMessage directMessage) {
                    config.networkLayer().send(CONSENSUS.value(), new NodeId(directMessage.to()), message);
                } else {
                    // Broadcast to all nodes
                    config.endpoints().forEach((nodeId, _) ->
                        config.networkLayer().send(CONSENSUS.value(), nodeId, message));
                }
            }
        }
        
        @Override
        public void start() {
            if (running) {
                return;
            }
            
            try {
                // Initialize the node and engine
                node = createTrexNode();
                engine = createTrexEngine();
                
                // Set up network listeners
                setupNetworkListeners();
                
                // Schedule heartbeats and election timeouts
                scheduleHeartbeat();
                scheduleElection();
                
                running = true;
                
                LOGGER.info("TrexService started with node ID: " +
                    config.nodeId().id());
            } catch (Exception e) {
                LOGGER.severe(() -> "Failed to start TrexService "+ e.getMessage());
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
                    LOGGER.warning(()->"Error closing TrexEngine: "+e);
                }
            }
            
            // Complete any pending responses with exceptions
            responseTracker.completeAllExceptionally(
                new IllegalStateException("Service stopped"));
            
            LOGGER.info("TrexService stopped");
        }
        
        private TrexNode createTrexNode() {
            // Create and configure the TrexNode based on config
            // This would include setting up the node with the proper
            // configuration, journal, etc.
            return null; // Placeholder
        }
        
        private TrexEngine<R> createTrexEngine() {
            // Create and configure the TrexEngine based on config
            // This would include setting up the engine with the node,
            // command handler, etc.
            return null; // Placeholder
        }
        
        private void setupNetworkListeners() {
            // Set up listeners for network messages
            // This would include registering handlers for different
            // message types with the network layer
        }
        
        private void scheduleHeartbeat() {
            // Schedule heartbeat messages to be sent periodically
            // This would use the configured heartbeat interval
        }
        
        private void scheduleElection() {
            // Schedule election timeout checks
            // This would use the configured election timeout
        }
        
        private void handleMessage(Object message) {
            // Handle different types of messages
            // This would include handling consensus messages,
            // forwarded commands, etc.
        }
    }
    
    /**
     * Tracks the current leader in the cluster
     */
    class LeaderTracker {
        final AtomicReference<NodeId> currentLeaderId = new AtomicReference<>(null);
        
        void setLeader(NodeId nodeId) {
            currentLeaderId.set(nodeId);
        }
        
        void clearLeader() {
            currentLeaderId.set(null);
        }
        
        NodeId getLeaderId() {
            return currentLeaderId.get();
        }
        
        boolean isLeader(NodeId thisId) {
            NodeId leaderId = currentLeaderId.get();
            return leaderId != null && leaderId.equals(thisId);
        }
    }
    
    /**
     * Tracks responses to submitted commands
     */
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
