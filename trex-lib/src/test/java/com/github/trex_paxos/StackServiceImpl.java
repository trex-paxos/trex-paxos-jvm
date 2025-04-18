/*
 * Copyright 2024 - 2025 Simon Massey
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

import static com.github.trex_paxos.network.SystemChannel.CONSENSUS;
import static com.github.trex_paxos.network.SystemChannel.PROXY;

public class StackServiceImpl implements StackService {
  // This is public as we will use it in jshell to demo the stack service
  public static final Logger LOGGER = Logger.getLogger(StackServiceImpl.class.getName());

  // Stack data structure - shared state that needs synchronization
  private final Stack<String> stack = new Stack<>();

  // TrexService for consensus
  private final TrexService<Value, Response> service;

  /**
   * Configure logging levels for the stack service and related components
   */
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

  /**
   * Get the underlying TrexService for testing
   */
  public TrexService<Value, Response> service() {
    return service;
  }

  /**
   * Stop the service
   */
  public void stop() {
    service.stop();
  }
}
