package com.github.trex_paxos;

import com.github.trex_paxos.network.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.github.trex_paxos.network.SystemChannel.CONSENSUS;
import static com.github.trex_paxos.network.SystemChannel.PROXY;

public class StackClusterImpl implements StackService {
  static final Logger LOGGER = Logger.getLogger(StackClusterImpl.class.getName());

  private final List<TrexApp<Value, Response>> nodes = new ArrayList<>();
  private final Stack<String> stack = new Stack<>();

  public static void setLogLevel(Level level) {
    // Configure root logger and handler
    Logger root = Logger.getLogger("");
    root.setLevel(level);
    ConsoleHandler handler = new ConsoleHandler();
    handler.setLevel(level);
    root.addHandler(handler);

    // Configure component-specific loggers
    for (String name : Arrays.asList(
        StackClusterImpl.class.getName(),
        TrexApp.class.getName(),
        TrexNode.class.getName(),
        NetworkLayer.class.getName()
    )) {
      Logger logger = Logger.getLogger(name);
      logger.setLevel(level);
      logger.setUseParentHandlers(false);
      logger.addHandler(handler);
      LOGGER.fine(() -> "Configured logger: " + name + " at level " + level);
    }
  }

  public StackClusterImpl() {
    this(new InMemoryNetwork("sharedNetwork"));
  }

  StackClusterImpl(InMemoryNetwork sharedNetwork) {
    LOGGER.fine("Initializing StackClusterImpl");
    for (short i = 1; i <= 2; i++) {
      final var index = i;
      LOGGER.fine(() -> "Creating node " + index);

      final Pickler<Value> valuePickler = PermitsRecordsPickler.createPickler(Value.class);

      // Callback to apply committed results to the stack
      BiFunction<Long, Command, Response> callback = (slot, cmd) -> {
        final var value = valuePickler.deserialize(cmd.operationBytes());
        LOGGER.fine(() -> "Node " + index + " processing command: " + value.getClass().getSimpleName());
        synchronized (stack) {
          try {
            return switch (value) {
              case Push p -> {
                LOGGER.fine(() -> String.format("Node %d pushing: %s, current size: %d",
                    index, p.item(), stack.size()));
                stack.push(p.item());
                LOGGER.fine(() -> String.format("Node %d push complete, new size: %d",
                    index, stack.size()));
                yield new Response(Optional.empty());
              }
              case Pop _ -> {
                if (stack.isEmpty()) {
                  LOGGER.fine(() -> "Node " + index + " attempted pop on empty stack");
                  //yield new Response(Optional.of("Stack is empty"));
                }
                var item = stack.pop();
                LOGGER.fine(() -> String.format("Node %d popped: %s, new size: %d",
                    index, item, stack.size()));
                yield new Response(Optional.of(item));
              }
              case Peek _ -> {
                if (stack.isEmpty()) {
                  LOGGER.warning(() -> "Node " + index + " attempted peek on empty stack");
                  //yield new Response(Optional.of("Stack is empty"));
                }
                var item = stack.peek();
                LOGGER.fine(() -> String.format("Node %d peeked: %s, size: %d",
                    index, item, stack.size()));
                yield new Response(Optional.of(item));
              }
            }
                ;
          } catch (EmptyStackException e) {
            LOGGER.warning(() -> String.format("Node %d slot %d stack operation failed: %s",
                index, slot, e.getMessage()));
            return new Response(Optional.of("Stack is empty"));
          }
        }
      };

      TrexEngine<Response> engine = getTrexEngine(index, callback);
      Supplier<ClusterMembership> members = () -> new ClusterMembership(
          Map.of(new NodeId((short) 1), new NetworkAddress.HostName("localhost", 5000),
              new NodeId((short) 2), new NetworkAddress.HostName("localhost", 5001)));
      NetworkLayer networkLayer = new TestNetworkLayer(engine.nodeId(), sharedNetwork,
          Map.of(CONSENSUS.value(), PickleMsg.instance, PROXY.value(), Pickle.instance)
      );

      var app = new TrexApp<Value, Response>(
          members,
          engine,
          networkLayer,
          valuePickler,
          cmd -> {
            LOGGER.fine(() -> "Node " + index + " processing command: " + cmd.getClass().getSimpleName());
            synchronized (stack) {
              try {
                return switch (cmd) {
                  case Push p -> {
                    LOGGER.fine(() -> String.format("Node %d pushing: %s, current size: %d",
                        index, p.item(), stack.size()));
                    stack.push(p.item());
                    LOGGER.fine(() -> String.format("Node %d push complete, new size: %d",
                        index, stack.size()));
                    yield new Response(Optional.empty());
                  }
                  case Pop _ -> {
                    if (stack.isEmpty()) {
                      LOGGER.fine(() -> "Node " + index + " attempted pop on empty stack");
                      yield new Response(Optional.of("Stack is empty"));
                    }
                    var item = stack.pop();
                    LOGGER.fine(() -> String.format("Node %d popped: %s, new size: %d",
                        index, item, stack.size()));
                    yield new Response(Optional.of(item));
                  }
                  case Peek _ -> {
                    if (stack.isEmpty()) {
                      LOGGER.warning(() -> "Node " + index + " attempted peek on empty stack");
                      yield new Response(Optional.of("Stack is empty"));
                    }
                    var item = stack.peek();
                    LOGGER.fine(() -> String.format("Node %d peeked: %s, size: %d",
                        index, item, stack.size()));
                    yield new Response(Optional.of(item));
                  }
                };
              } catch (EmptyStackException e) {
                LOGGER.warning(() -> String.format("Node %d stack operation failed: %s",
                    index, e.getMessage()));
                return new Response(Optional.of("Stack is empty"));
              }
            }
          }
      );
      nodes.add(app);
      app.setLeader((short) 1);
      app.start();
      LOGGER.fine(() -> "Node " + index + " started successfully");
    }
  }

  public AtomicInteger nodeToggle = new AtomicInteger(0);

  public void toggleNode() {
    int oldNode = nodeToggle.get() % nodes.size();
    int newNode = nodeToggle.incrementAndGet() % nodes.size();
    LOGGER.fine(() -> String.format("Toggling active node: %d -> %d", oldNode, newNode));
  }

  @Override
  public Response push(String item) {
    LOGGER.fine(() -> String.format("Push requested: item=%s, targetNode=%d",
        item, nodeToggle.get() % nodes.size()));
    var future = new CompletableFuture<Response>();
    nodes.get(nodeToggle.get() % nodes.size()).submitValue(new Push(item), future);
    try {
      var response = future.get(1, TimeUnit.SECONDS);
      LOGGER.fine(() -> "Push completed successfully");
      return response;
    } catch (Exception e) {
      LOGGER.warning(() -> String.format("Push failed: %s", e.getMessage()));
      return new Response(Optional.of("Error: " + e.getMessage()));
    }
  }

  @Override
  public Response pop() {
    LOGGER.fine(() -> String.format("Pop requested: targetNode=%d", nodeToggle.get() % nodes.size()));
    var future = new CompletableFuture<Response>();
    nodes.get(nodeToggle.get() % nodes.size()).submitValue(new Pop(), future);
    try {
      var response = future.get(1, TimeUnit.SECONDS);
      LOGGER.fine(() -> String.format("Pop completed: %s", response.value().orElse("empty")));
      return response;
    } catch (Exception e) {
      LOGGER.warning(() -> String.format("Pop failed: %s", e.getMessage()));
      return new Response(Optional.of("Error: " + e.getMessage()));
    }
  }

  @Override
  public Response peek() {
    LOGGER.fine(() -> String.format("Peek requested: targetNode=%d", nodeToggle.get() % nodes.size()));
    var future = new CompletableFuture<Response>();
    nodes.get(nodeToggle.get() % nodes.size()).submitValue(new Peek(), future);
    try {
      var response = future.get(1, TimeUnit.SECONDS);
      LOGGER.fine(() -> String.format("Peek completed: %s", response.value().orElse("empty")));
      return response;
    } catch (Exception e) {
      LOGGER.warning(() -> String.format("Peek failed: %s", e.getMessage()));
      return new Response(Optional.of("Error: " + e.getMessage()));
    }
  }

  public void shutdown() {
    LOGGER.fine("Initiating StackClusterImpl shutdown");
    nodes.forEach(n -> {
      try {
        n.stop();
      } catch (Exception e) {
        LOGGER.warning(() -> "Error during node shutdown: " + e.getMessage());
      }
    });
    LOGGER.fine("StackClusterImpl shutdown complete");
  }

  // Rest of existing implementation remains unchanged
  private static TrexEngine<Response> getTrexEngine(short i, BiFunction<Long, Command, Response> callback) {
    var journal = new TransparentJournal(i);
    QuorumStrategy quorum = new SimpleMajority(2);
    var node = new TrexNode(Level.INFO, i, quorum, journal);
    LOGGER.fine(() -> "Creating TrexEngine for node " + i + (i == 1 ? " (leader)" : ""));
    if (i == 1) {
      node.setLeader();
    }

    return new TrexEngine<Response>(node, callback);
  }

  record ChannelAndSubscriber(Channel channel, NamedSubscriber subscriber) {
  }

  public static void main(String[] args) {
    setLogLevel(Level.INFO);
    StackClusterImpl cluster = new StackClusterImpl();
    cluster.toggleNode();
    try {
      cluster.push("hello");
      cluster.push("world");
      System.out.println(cluster.peek().value().orElseThrow());
      System.out.println(cluster.peek().value().orElseThrow());
      System.out.println(cluster.pop().value().orElseThrow());
      System.out.println(cluster.pop().value().orElseThrow());
    } finally {
      cluster.shutdown();
    }
  }
}
