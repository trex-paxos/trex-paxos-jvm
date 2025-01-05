package com.github.trex_paxos;

import com.github.trex_paxos.network.Channel;
import com.github.trex_paxos.network.ClusterMembership;
import com.github.trex_paxos.network.NetworkAddress;
import com.github.trex_paxos.network.TrexNetwork;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings("unused")
public class StackClusterImpl implements StackService {
  private static final Logger LOGGER = Logger.getLogger(StackClusterImpl.class.getName());

  public static void setLogLevel(Level level) {
    Logger root = Logger.getLogger("");
    root.setLevel(level);
    ConsoleHandler handler = new ConsoleHandler();
    handler.setLevel(level);
    root.addHandler(handler);
  }

  private static class InMemoryNetwork implements TrexNetwork {
    private final Map<Channel, Consumer<ByteBuffer>> handlers = new HashMap<>();
    private final LinkedBlockingQueue<NetworkMessage> messageQueue = new LinkedBlockingQueue<>();
    private volatile boolean running = true;
    private final String networkId;
    private long messageCount = 0;

    public InMemoryNetwork(String networkId) {
      this.networkId = networkId;
      LOGGER.fine(() -> "Created InMemoryNetwork: " + networkId);
    }

    private record NetworkMessage(short nodeId, Channel channel, ByteBuffer data) {
    }

    @Override
    public void send(Channel channel, short nodeId, ByteBuffer data) {
      if (running) {
        messageCount++;
        LOGGER.fine(() -> networkId + " sending message #" + messageCount + " on channel " + channel);
        messageQueue.add(new NetworkMessage(nodeId, channel, data));
      }
    }

    @Override
    public void subscribe(Channel channel, Consumer<ByteBuffer> handler) {
      LOGGER.fine(() -> networkId + " subscribing to channel " + channel);
      handlers.put(channel, handler);
      LOGGER.fine(() -> networkId + " has " + handlers.size() + " channel handlers");
    }

    @Override
    public void start() {
      LOGGER.fine(() -> networkId + " network starting");
      Thread.ofVirtual().name("network-" + networkId).start(() -> {
        while (running) {
          try {
            NetworkMessage msg = messageQueue.poll(100, TimeUnit.MILLISECONDS);

            if (msg != null) {
              if (handlers.containsKey(msg.channel)) {
                LOGGER.fine(() -> networkId + " delivering message #" + messageCount + " on channel " + msg.channel + " to " + handlers.size() + " handlers");
                handlers.get(msg.channel).accept(msg.data);
              } else {
                LOGGER.warning(() -> networkId + " has no handler for channel " + msg.channel);
              }
            }
          } catch (InterruptedException e) {
            if (running) {
              LOGGER.warning(networkId + " message processor interrupted: " + e.getMessage());
            }
          }
        }
      });
    }

    @Override
    public void stop() {
      LOGGER.fine(() -> networkId + " network stopping");
      running = false;
    }
  }

  private final List<TrexApp<Value, Response>> nodes = new ArrayList<>();
  private final Stack<String> stack = new Stack<>();
  private volatile boolean running = true;

  public StackClusterImpl() {
    InMemoryNetwork sharedNetwork = new InMemoryNetwork("sharedNetwork");

    for (short i = 1; i <= 2; i++) {
      final var index = i;
      var engine = getTrexEngine(index);
      Pickler<Value> valuePickler = PermitsRecordsPickler.createPickler(Value.class);
      Supplier<ClusterMembership> members = () -> new ClusterMembership(
          Map.of((short) 1, new NetworkAddress.HostName("localhost", 5000),
              (short) 2, new NetworkAddress.HostName("localhost", 5001)));
      var app = new TrexApp<>(
          members,
          engine,
          valuePickler,
          sharedNetwork,
          cmd -> {
            LOGGER.fine(() -> "Node " + index + " processing command: " + cmd.getClass().getSimpleName());
            synchronized (stack) {
              try {
                return switch (cmd) {
                  case Push p -> {
                    LOGGER.fine(() -> "Node " + index + " pushing: " + p.item());
                    stack.push(p.item());
                    yield new Response(Optional.empty());
                  }
                  case Pop _ -> {
                    var item = stack.pop();
                    LOGGER.fine(() -> "Node " + index + " popped: " + item);
                    yield new Response(Optional.of(item));
                  }
                  case Peek _ -> {
                    var item = stack.peek();
                    LOGGER.fine(() -> "Node " + index + " peeked: " + item);
                    yield new Response(Optional.of(item));
                  }
                };
              } catch (EmptyStackException e) {
                LOGGER.warning("Node " + index + " attempted operation on empty stack");
                return new Response(Optional.of("Stack is empty"));
              }
            }
          }
      );
      nodes.add(app);
      app.start();
      LOGGER.fine(() -> "Node " + index + " started successfully");
    }
  }

  private static @NotNull TrexEngine getTrexEngine(short i) {
    var journal = new TransparentJournal(i);
    QuorumStrategy quorum = new SimpleMajority(2);
    var node = new TrexNode(Level.INFO, i, quorum, journal);
    LOGGER.fine(() -> "Creating TrexEngine for node " + i + (i == 1 ? " (leader)" : ""));
    if (i == 1) {
      node.setLeader();
    }

    return new TrexEngine(node) {
      @Override
      protected void setRandomTimeout() {
      }

      @Override
      protected void clearTimeout() {
      }

      @Override
      protected void setNextHeartbeat() {
      }
    };
  }

  @Override
  public Response push(String item) {
    LOGGER.fine(() -> "Push method invoked with: " + item);
    var future = new CompletableFuture<Response>();
    nodes.getFirst().processCommand(new Push(item), future);
    try {
      LOGGER.fine(() -> "Waiting for push response");
      var response = future.get(1, TimeUnit.HOURS);
      LOGGER.fine(() -> "Push response received: " + response);
      return response;
    } catch (Exception e) {
      LOGGER.warning("Push failed: " + e.getMessage());
      return new Response(Optional.of("Error: " + e.getMessage()));
    }
  }

  @Override
  public Response pop() {
    LOGGER.fine("Pop method invoked");
    var future = new CompletableFuture<Response>();
    nodes.getFirst().processCommand(new Pop(), future);
    try {
      LOGGER.fine("Waiting for pop response");
      var response = future.get(1, TimeUnit.SECONDS);
      LOGGER.fine(() -> "Pop response received: " + response);
      return response;
    } catch (Exception e) {
      LOGGER.warning("Pop failed: " + e.getMessage());
      return new Response(Optional.of("Error: " + e.getMessage()));
    }
  }

  @Override
  public Response peek() {
    LOGGER.fine("Peek method invoked");
    var future = new CompletableFuture<Response>();
    nodes.getFirst().processCommand(new Peek(), future);
    try {
      LOGGER.fine("Waiting for peek response");
      var response = future.get(1, TimeUnit.SECONDS);
      LOGGER.fine(() -> "Peek response received: " + response);
      return response;
    } catch (Exception e) {
      LOGGER.warning("Peek failed: " + e.getMessage());
      return new Response(Optional.of("Error: " + e.getMessage()));
    }
  }

  public void shutdown() {
    LOGGER.fine("Shutting down StackClusterImpl");
    running = false;
    nodes.forEach(n -> {
      try {
        n.stop();
      } catch (Exception e) {
        LOGGER.warning("Error shutting down node: " + e.getMessage());
      }
    });
  }

  public static void main(String[] args) {
    setLogLevel(Level.FINE);
    StackClusterImpl cluster = new StackClusterImpl();
    try {
      cluster.push("hello");
      cluster.push("world");
      cluster.peek();
      cluster.peek();
      cluster.pop();
      cluster.pop();
    } finally {
      cluster.shutdown();
    }
  }
}
