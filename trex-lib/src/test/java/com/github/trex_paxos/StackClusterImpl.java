package com.github.trex_paxos;

import com.github.trex_paxos.network.*;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    // TrexApp logger setup
    Logger trexLogger = Logger.getLogger(TrexApp.class.getName());
    trexLogger.setLevel(level);
    // NetworkLayer logger setup
    Logger networkLayerLogger = Logger.getLogger(NetworkLayer.class.getName());
    networkLayerLogger.setLevel(level);
  }

  record ChannelAndSubscriber(Channel channel, TrexNetwork.NamedSubscriber subscriber) {
  }

  private static class InMemoryNetwork implements TrexNetwork {
    private final List<ChannelAndSubscriber> handlers = new ArrayList<>();
    private final LinkedBlockingQueue<NetworkMessage> messageQueue = new LinkedBlockingQueue<>();
    private volatile boolean running = true;
    private final String networkId;

    public InMemoryNetwork(String networkId) {
      this.networkId = networkId;
      LOGGER.fine(() -> "Created InMemoryNetwork: " + networkId);
    }

    private record NetworkMessage(short nodeId, Channel channel, ByteBuffer data) {
    }

    @Override
    public void send(Channel channel, short nodeId, ByteBuffer data) {
      if (running) {
        messageQueue.add(new NetworkMessage(nodeId, channel, data));
      }
    }

    @Override
    public void subscribe(Channel channel, NamedSubscriber handler) {
      ChannelAndSubscriber channelAndSubscriber = new ChannelAndSubscriber(channel, handler);
      handlers.add(channelAndSubscriber);
    }

    @Override
    public void start() {
      Thread.ofVirtual().name("network-" + networkId).start(() -> {
        while (running) {
          try {
            NetworkMessage msg = messageQueue.poll(100, TimeUnit.MILLISECONDS);

            if (msg != null) {
              handlers.forEach(h -> {
                if (h.channel().equals(msg.channel)) {
                  LOGGER.fine(() -> networkId + " received message on channel " + msg.channel + " from " + msg.nodeId + " delivering to " + h.subscriber().name());
                  h.subscriber().accept(msg.data);
                }
              });
            }
          } catch (InterruptedException e) {
            if (running) {
              LOGGER.warning(networkId + " message processor interrupted: " + e.getMessage());
            }
          }
        }
      });
      LOGGER.info(() -> networkId + " network started with subscribers " + handlers);
    }

    @Override
    public void close() {
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
      final var engine = getTrexEngine(i);
      final NodeId nodeId = engine.nodeId();
      Pickler<Value> valuePickler = PermitsRecordsPickler.createPickler(Value.class);
      Supplier<ClusterMembership> members = () -> new ClusterMembership(
          Map.of(new NodeId((short) 1), new NetworkAddress.HostName("localhost", 5000),
              new NodeId((short) 2), new NetworkAddress.HostName("localhost", 5001)));
      NetworkLayer networkLayer = new TestNetworkLayer(nodeId, sharedNetwork,
          Map.of(Channel.CONSENSUS, PickleMsg.instance, Channel.PROXY, valuePickler)
      );

      var app = new TrexApp<>(
          members,
          engine,
          networkLayer,
          valuePickler,
          cmd -> {
            LOGGER.fine(() -> "Node " + nodeId + " processing command: " + cmd.getClass().getSimpleName());
            synchronized (stack) {
              try {
                return switch (cmd) {
                  case Push p -> {
                    LOGGER.fine(() -> "Node " + nodeId + " pushing: " + p.item());
                    stack.push(p.item());
                    yield new Response(Optional.empty());
                  }
                  case Pop _ -> {
                    var item = stack.pop();
                    LOGGER.fine(() -> "Node " + nodeId + " popped: " + item);
                    yield new Response(Optional.of(item));
                  }
                  case Peek _ -> {
                    var item = stack.peek();
                    LOGGER.fine(() -> "Node " + nodeId + " peeked: " + item);
                    yield new Response(Optional.of(item));
                  }
                };
              } catch (EmptyStackException e) {
                LOGGER.warning("Node " + nodeId + " attempted operation on empty stack");
                return new Response(Optional.of("Stack is empty"));
              }
            }
          }
      );
      nodes.add(app);
      app.setLeader((short)1);
      app.start();
      LOGGER.fine(() -> "Node " + nodeId + " started successfully");
    }
  }

  private static @NotNull TrexEngine getTrexEngine(short nodeId) {
    var journal = new TransparentJournal(nodeId);
    QuorumStrategy quorum = new SimpleMajority(2);
    var node = new TrexNode(Level.INFO, nodeId, quorum, journal);
    LOGGER.fine(() -> "Creating TrexEngine for node " + nodeId + (nodeId == 1 ? " (leader)" : ""));

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

  public AtomicInteger nodeToggle = new AtomicInteger(0);

  public void toggleNode() {
    nodeToggle.getAndIncrement();
  }

  @Override
  public Response push(String item) {
    LOGGER.fine(() -> "Push method invoked with: " + item);
    var future = new CompletableFuture<Response>();
    nodes.get(nodeToggle.get()%nodes.size()).submitValue(new Push(item), future);
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
    nodes.get(nodeToggle.get()%nodes.size()).submitValue(new Pop(), future);
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
    nodes.get(nodeToggle.get()%nodes.size()).submitValue(new Peek(), future);
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
    setLogLevel(Level.FINEST);
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
