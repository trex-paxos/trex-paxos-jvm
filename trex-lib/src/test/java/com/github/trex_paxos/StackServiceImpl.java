// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import com.github.trex_paxos.network.NetworkLayer;

import java.time.Duration;
import java.util.Arrays;
import java.util.EmptyStackException;
import java.util.Stack;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.github.trex_paxos.TrexLogger.LOGGER;
import static com.github.trex_paxos.network.SystemChannel.CONSENSUS;
import static com.github.trex_paxos.network.SystemChannel.PROXY;
/// A demonstration implementation of a distributed Stack using the Trex Paxos library.
/// See [com.github.trex_paxos] package documentation for detailed information about the core library.
///
/// ```mermaid
/// graph TD
///   Client([Client Application]) -->|"push()/pop()/peek()"| SS([StackServiceImpl])
///   SS -->|implements| SI([StackService])
///   SS -->|"submit(Value)"| TS([TrexService])
///   TS -->|consensus| TN([TrexNode])
///   SS -->|synchronized| Stack([Stack&lt;String&gt;])
///   TS -->|messages| NL([NetworkLayer])
///   NL -->|connects| Cluster([Other Nodes])
///```
///
/// This class demonstrates how to build a simple distributed data structure using Trex Paxos.
/// All operations (push, pop, peek) are replicated to the cluster for fault tolerance.
/// The TrexService ensures that all nodes process commands in the same order.
public class StackServiceImpl implements StackService {
  // Stack data structure - shared state that needs synchronization
  private final Stack<String> stack = new Stack<>();

  // TrexService for consensus
  private final TrexService<Value, Response> service;

  /// Configures logging levels for the stack service and related components.
  /// @param level The logging level to set for all components
  public static void setLogLevel(Level level) {
    // Configure root logger and handler
    Logger root = Logger.getLogger("");
    root.setLevel(level);
    ConsoleHandler handler = new ConsoleHandler();
    handler.setLevel(level);
    root.addHandler(handler);

    // Configure component-specific loggers using streams
    Arrays.asList(
        StackServiceImpl.class.getName(),
        TrexService.class.getName(),
        TrexNode.class.getName(),
        NetworkLayer.class.getName()
    ).forEach(name -> {
      Logger logger = Logger.getLogger(name);
      logger.setLevel(level);
      logger.setUseParentHandlers(false);
      logger.addHandler(handler);
      LOGGER.fine(() -> "Configured logger: " + name + " at level " + level);
    });
  }

  /// Creates a new StackService node that participates in the distributed consensus.
  /// @param nodeId The unique identifier for this node
  /// @param legislatorsSupplier Supplies information about all nodes in the cluster
  /// @param networkLayer Handles network communication between nodes
  public StackServiceImpl(short nodeId, Supplier<Legislators> legislatorsSupplier, NetworkLayer networkLayer) {
    LOGGER.fine(() -> "Creating node " + nodeId);

    // Create pickler for Value objects
    final Pickler<Value> valuePickler = SealedRecordsPickler.createPickler(Value.class);

    // Create command handler function
    BiFunction<Long, Value, Response> commandHandler = (slot, value) -> {
      LOGGER.fine(() -> "Node " + nodeId + " processing command: " + value.getClass().getSimpleName());

      // Synchronize on the stack to ensure thread safety
      synchronized (stack) {
        try {
          // Use exhaustive pattern matching with switch expression
          return switch (value) {
            case Push p -> {
              LOGGER.fine(() -> String.format("Node %d pushing: %s, current size: %d",
                  nodeId, p.item(), stack.size()));
              stack.push(p.item());
              LOGGER.fine(() -> String.format("Node %d push complete, new size: %d",
                  nodeId, stack.size()));
              yield Response.success(null);
            }
            case Pop _ -> {
              if (stack.isEmpty()) {
                LOGGER.fine(() -> "Node " + nodeId + " attempted pop on empty stack");
                yield Response.success("Stack is empty");
              }
              var item = stack.pop();
              LOGGER.fine(() -> String.format("Node %d popped: %s, new size: %d",
                  nodeId, item, stack.size()));
              yield Response.success(item);
            }
            case Peek _ -> {
              if (stack.isEmpty()) {
                LOGGER.fine(() -> "Node " + nodeId + " attempted peek on empty stack");
                yield Response.success("Stack is empty");
              }
              var item = stack.peek();
              LOGGER.fine(() -> String.format("Node %d peeked: %s, size: %d",
                  nodeId, item, stack.size()));
              yield Response.success(item);
            }
          };
        } catch (EmptyStackException e) {
          LOGGER.warning(() -> String.format("Node %d slot %d stack operation failed: %s",
              nodeId, slot, e.getMessage()));
          return Response.success("Stack is empty");
        }
      }
    };

    // Create journal
    Journal journal = new TransparentJournal(nodeId);

    // Create quorum strategy
    QuorumStrategy quorum = new SimpleMajority(2);

    // Build configuration for TrexService
    TrexService.Config<Value, Response> config = TrexService.<Value, Response>config()
        .withNodeId(new NodeId(nodeId))
        .withLegislatorsSupplier(legislatorsSupplier)
        .withQuorumStrategy(quorum)
        .withJournal(journal)
        .withCommandHandler(commandHandler)
        .withNetworkLayer(networkLayer)
        .withPickler(valuePickler)
        .withTiming(Duration.ofMillis(500), Duration.ofSeconds(2));

    // Create TrexService
    this.service = config.build();

    // Set up network listeners
    networkLayer.subscribe(CONSENSUS.value(),
        service::handleConsensusMessage,
        "consensus-" + nodeId);
    networkLayer.subscribe(PROXY.value(),
        service::handleProxyMessage,
        "proxy-" + nodeId);

    // Start the service
    service.start();

    // Set leader for testing purposes
    if (nodeId == 1) {
      ((TrexService.Implementation<Value, Response>) service).setLeader();
    }

    // Start network layer
    networkLayer.start();

    LOGGER.info(() -> "Node " + nodeId + " started successfully");
  }

  /// Pushes an item onto the distributed stack.
  /// Only if the value is fixed by consensus will it be applied locally to complete the future.
  /// @param item The string item to push onto the stack
  /// @return A response indicating success or failure
  @Override
  public Response push(String item) {
    var future = new CompletableFuture<Response>();
    service.submit(new Push(item))
        .thenAccept(future::complete)
        .exceptionally(e -> {
          LOGGER.warning(() -> String.format("Push failed: %s", e.getMessage()));
          future.complete(Response.failure(e));
          return null;
        });

    try {
      var response = future.get(1, TimeUnit.SECONDS);
      LOGGER.fine(() -> "Push completed successfully");
      return response;
    } catch (Exception e) {
      LOGGER.warning(() -> String.format("Push failed: %s", e.getMessage()));
      return Response.failure(e);
    }
  }

  /// Pops an item from the distributed stack.
  /// Only if the value is fixed by consensus will it be applied locally to complete the future.
  /// @return A response indicating success or failure with the popped value
  @Override
  public Response pop() {
    var future = new CompletableFuture<Response>();
    service.submit(new Pop())
        .thenAccept(future::complete)
        .exceptionally(e -> {
          LOGGER.warning(() -> String.format("Pop failed: %s", e.getMessage()));
          future.complete(Response.failure(e));
          return null;
        });

    try {
      var response = future.get(1, TimeUnit.SECONDS);
      LOGGER.fine(() -> String.format("Pop completed: %s", response));
      return response;
    } catch (Exception e) {
      LOGGER.warning(() -> String.format("Pop failed: %s", e.getMessage()));
      return Response.failure(e);
    }
  }

  /// Peeks an item from the distributed stack.
  /// Only if the value is fixed by consensus will it be applied locally to complete the future.
  /// @return A response indicating success or failure with the peeked value
  @Override
  public Response peek() {
    var future = new CompletableFuture<Response>();
    service.submit(new Peek())
        .thenAccept(future::complete)
        .exceptionally(e -> {
          LOGGER.warning(() -> String.format("Peek failed: %s", e.getMessage()));
          future.complete(Response.failure(e));
          return null;
        });

    try {
      var response = future.get(1, TimeUnit.SECONDS);
      LOGGER.fine(() -> String.format("Peek completed: %s", response));
      return response;
    } catch (Exception e) {
      LOGGER.warning(() -> String.format("Peek failed: %s", e.getMessage()));
      return Response.failure(e);
    }
  }

  /// Gets the underlying TrexService for testing and diagnostics.
  /// @return The TrexService instance used by this stack service
  public TrexService<Value, Response> service() {
    return service;
  }

  /// Stops the service and releases resources.
  /// This should be called when the service is no longer needed.
  public void stop() {
    LOGGER.info(() -> "Stopping StackServiceImpl");
    this.service().stop();
  }
}
