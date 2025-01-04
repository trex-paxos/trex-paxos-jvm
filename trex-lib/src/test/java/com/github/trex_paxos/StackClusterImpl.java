package com.github.trex_paxos;

import com.github.trex_paxos.network.Channel;
import com.github.trex_paxos.network.TrexNetwork;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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

    private record NetworkMessage(Channel channel, ByteBuffer data) {
    }

    @Override
    public void send(Channel channel, ByteBuffer data) {
      if (running) {
        messageCount++;
        LOGGER.fine(() -> networkId + " sending message #" + messageCount + " on channel " + channel);
        messageQueue.add(new NetworkMessage(channel, data));
      }
    }

    @Override
    public void send(Channel channel, ByteBuffer data, Consumer<ByteBuffer> responseHandler) {
      send(channel, data);
    }

    @Override
    public void subscribe(Channel channel, Consumer<ByteBuffer> handler) {
      LOGGER.fine(() -> networkId + " subscribing to channel " + channel);
      handlers.put(channel, handler);
    }

    @Override
    public void start() {
      LOGGER.fine(() -> networkId + " network starting");
      Thread.ofVirtual().name("network-" + networkId).start(() -> {
        while (running) {
          try {
            NetworkMessage msg = messageQueue.poll(100, TimeUnit.MILLISECONDS);
            if (msg != null && handlers.containsKey(msg.channel)) {
              LOGGER.fine(() -> networkId + " delivering message on channel " + msg.channel);
              handlers.get(msg.channel).accept(msg.data);
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
    LOGGER.fine("Initializing StackClusterImpl");
    InMemoryNetwork network1 = new InMemoryNetwork("network1");
    InMemoryNetwork network2 = new InMemoryNetwork("network2");

    for (short i = 1; i <= 2; i++) {
      final var index = i;
      LOGGER.fine(() -> "Creating node " + index);
      var engine = getTrexEngine(index);
      LOGGER.fine(() -> "Node " + index + " engine role: " + engine.getRole());
      var nodeNetwork = index == 1 ? network1 : network2;
      Pickler<Value> valuePickler = PermitsRecordsPickler.createPickler(Value.class);

      LOGGER.fine(() -> "Node " + index + " using network: " + nodeNetwork.networkId);
      var app = new TrexApp<>(
          engine,
          valuePickler,
          nodeNetwork,
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
      nodeNetwork.start();
      LOGGER.fine(() -> "Starting node " + index);
      app.start();
    }
    LOGGER.fine("StackClusterImpl initialization complete");
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
    LOGGER.fine(() -> "Push request received: " + item);
    var future = new CompletableFuture<Response>();
    nodes.getFirst().processCommand(new Push(item), future);
    try {
      LOGGER.fine(() -> "Waiting for push response");
      var response = future.get(5, TimeUnit.SECONDS);
      LOGGER.fine(() -> "Push response received: " + response);
      return response;
    } catch (Exception e) {
      LOGGER.warning("Push failed: " + e.getMessage());
      return new Response(Optional.of("Error: " + e.getMessage()));
    }
  }

  @Override
  public Response pop() {
    LOGGER.fine("Pop request received");
    var future = new CompletableFuture<Response>();
    nodes.getFirst().processCommand(new Pop(), future);
    try {
      LOGGER.fine("Waiting for pop response");
      var response = future.get(5, TimeUnit.SECONDS);
      LOGGER.fine(() -> "Pop response received: " + response);
      return response;
    } catch (Exception e) {
      LOGGER.warning("Pop failed: " + e.getMessage());
      return new Response(Optional.of("Error: " + e.getMessage()));
    }
  }

  @Override
  public Response peek() {
    LOGGER.fine("Peek request received");
    var future = new CompletableFuture<Response>();
    nodes.getFirst().processCommand(new Peek(), future);
    try {
      LOGGER.fine("Waiting for peek response");
      var response = future.get(5, TimeUnit.SECONDS);
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
}
