package com.github.trex_paxos;

import com.github.trex_paxos.network.NetworkAddress;
import com.github.trex_paxos.network.NetworkLayer;
import lombok.With;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

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
            return this.withHeartbeatInterval(heartbeat).withElectionTimeout(election);
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
}
