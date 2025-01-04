package com.github.trex_paxos.paxe;

import com.github.trex_paxos.*;
import com.github.trex_paxos.msg.Accept;
import com.github.trex_paxos.msg.TrexMessage;
import com.github.trex_paxos.network.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaxeStackClusterTest {
  static {
    System.setProperty(SRPUtils.class.getName() + ".useHash", "SHA3-256");
  }

  static final Logger LOGGER = Logger.getLogger(PaxeStackClusterTest.class.getName());

  private final ClusterId clusterId = new ClusterId("test.cluster");
  private TrexApp<StackService.Command, StackService.Response> app1;
  private TrexApp<StackService.Command, StackService.Response> app2;
  private PaxeNetwork network1;
  private PaxeNetwork network2;

  private static final Pickler<StackService.Response> RESPONSE_SERDE = RecordPickler
      .createPickler(StackService.Response.class);

  private final Pickler<StackService.Command> commandSerde = new Pickler<>() {
    private final Pickler<StackService.Push> pushSerde = RecordPickler.createPickler(StackService.Push.class);

    @Override
    public byte[] serialize(StackService.Command cmd) {
      ByteBuffer buf = ByteBuffer.allocate(
          1 + (cmd instanceof StackService.Push ? pushSerde.serialize((StackService.Push) cmd).length : 0));
      switch (cmd) {
        case StackService.Push p -> {
          buf.put((byte) 1);
          buf.put(pushSerde.serialize(p));
        }
        case StackService.Pop _ -> buf.put((byte) 2);
        case StackService.Peek _ -> buf.put((byte) 3);
      }
      return buf.array();
    }

    @Override
    public StackService.Command deserialize(byte[] bytes) {
      ByteBuffer buf = ByteBuffer.wrap(bytes);
      return switch (buf.get()) {
        case 1 -> pushSerde.deserialize(buf.slice().array());
        case 2 -> new StackService.Pop();
        case 3 -> new StackService.Peek();
        default -> throw new IllegalArgumentException("Unknown command type");
      };
    }
  };

  @BeforeAll
  static void setupLogging() {

    final var logLevel = System.getProperty("java.util.logging.ConsoleHandler.level", "FINER");
    final Level level = Level.parse(logLevel);
    // Configure PaxeNetworkTest logger
    LOGGER.setLevel(level);
    ConsoleHandler consoleHandler = new ConsoleHandler();
    consoleHandler.setLevel(level);
    LOGGER.addHandler(consoleHandler);

    // Configure SessionKeyManager logger
    Logger sessionKeyManagerLogger = Logger.getLogger(PaxeNetwork.class.getName());
    sessionKeyManagerLogger.setLevel(level);
    ConsoleHandler skmHandler = new ConsoleHandler();
    skmHandler.setLevel(level);
    sessionKeyManagerLogger.addHandler(skmHandler);

    // Optionally disable parent handlers if needed
    LOGGER.setUseParentHandlers(false);
    sessionKeyManagerLogger.setUseParentHandlers(false);
  }


  final String hexN = "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C" + //
      "9C256576D674DF7496EA81D3383B4813D692C6E0E0D5D8E250B98BE4" + //
      "8E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B29" + //
      "7BCF1885C529F566660E57EC68EDBC3C05726CC02FD4CBF4976EAA9A" + //
      "FD5138FE8376435B9FC61D2FC0EB06E3";

  final String hexG = "2";

  final SRPUtils.Constants srpConstants = new SRPUtils.Constants(hexN, hexG);

  @BeforeEach
  void setup() throws Exception {
    // Get ephemeral ports
    DatagramSocket socket1 = new DatagramSocket(0);
    DatagramSocket socket2 = new DatagramSocket(0);
    int port1 = socket1.getLocalPort();
    int port2 = socket2.getLocalPort();
    socket1.close();
    socket2.close();

    NodeId node1 = new NodeId((byte) 1);
    NodeId node2 = new NodeId((byte) 2);

    ClusterMembership membership = new ClusterMembership(Map.of(
        node1, new NetworkAddress.InetAddress("127.0.0.1", port1),
        node2, new NetworkAddress.InetAddress("127.0.0.1", port2)));

    // Setup SRP authentication
    NodeClientSecret nodeSecret1 = new NodeClientSecret(clusterId, node1, "password1", SRPUtils.generateSalt());
    NodeClientSecret nodeSecret2 = new NodeClientSecret(clusterId, node2, "password2", SRPUtils.generateSalt());

    final var v1 = SRPUtils.generateVerifier(srpConstants, nodeSecret1.srpIdenity(), nodeSecret1.password(),
        nodeSecret1.salt());
    final var v2 = SRPUtils.generateVerifier(srpConstants, nodeSecret2.srpIdenity(), nodeSecret2.password(),
        nodeSecret2.salt());

    final var nv1 = new NodeVerifier(nodeSecret1.srpIdenity(), v1.toString(16));
    final var nv2 = new NodeVerifier(nodeSecret2.srpIdenity(), v2.toString(16));

    final Supplier<Map<NodeId, NodeVerifier>> verifierLookup = () -> Map.of(node1, nv1, node2, nv2);

    SessionKeyManager keyManager1 = new SessionKeyManager(node1, srpConstants, nodeSecret1, verifierLookup);
    SessionKeyManager keyManager2 = new SessionKeyManager(node2, srpConstants, nodeSecret2, verifierLookup);

    network1 = new PaxeNetwork(keyManager1, port1, node1, () -> membership);
    network2 = new PaxeNetwork(keyManager2, port2, node2, () -> membership);

    var engine1 = createEngine(createLeaderNode(node1.id()));
    var engine2 = createEngine(createFollowerNode(node2.id()));

    network1.start();
    network2.start();

    // Wait for key exchange
    Thread.sleep(100);

    app1 = createStackApp(engine1, network1);
    app2 = createStackApp(engine2, network2);
  }

  private TrexNode createLeaderNode(short nodeId) {
    var node = new TrexNode(Level.INFO, nodeId, new SimpleMajority(2), new TransientJournal(nodeId)) {
      {
        this.setLeader();
      }
    };
    return node;
  }

  private TrexNode createFollowerNode(short nodeId) {
    return new TrexNode(Level.INFO, nodeId, new SimpleMajority(2), new TransientJournal(nodeId));
  }

  private TrexEngine createEngine(TrexNode node) {
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

  private TrexApp<StackService.Command, StackService.Response> createStackApp(TrexEngine engine,
                                                                              PaxeNetwork network) {
    return new TrexApp<>(engine, commandSerde, msgs -> {
      for (var msg : msgs) {
        try {
          if (msg instanceof TrexMessage trexMsg) {
            LOGGER.finest(() -> "Node " + engine.nodeIdentifier() + " sending message: " + trexMsg);
            network.encryptAndSend(new PaxeMessage(
                new NodeId(trexMsg.from()),
                new NodeId((short) (trexMsg.from() == 1 ? 2 : 1)),
                Channel.CONSENSUS,
                PickleMsg.pickle(trexMsg)));
          } else {
            throw new IllegalArgumentException("Expected TrexMessage but got: " + msg.getClass());
          }
        } catch (Exception e) {
          LOGGER.severe("Error sending message: " + e);
          throw new RuntimeException(e);
        }
      }
    });
  }

  @AfterEach
  void teardown() throws Exception {
    if (network1 != null)
      network1.close();
    if (network2 != null)
      network2.close();
  }

  @Test
  void testStackOperations() throws Exception {
    // Push "hello"
    var future = new CompletableFuture<StackService.Response>();
    app1.processCommand(new StackService.Push("hello"), future);
    var response = future.get(1, TimeUnit.SECONDS);
    assertEquals(Optional.empty(), response.value());

    // Push "world"
    future = new CompletableFuture<>();
    app1.processCommand(new StackService.Push("world"), future);
    response = future.get(1, TimeUnit.SECONDS);
    assertEquals(Optional.empty(), response.value());

    // First peek
    future = new CompletableFuture<>();
    app1.processCommand(new StackService.Peek(), future);
    response = future.get(1, TimeUnit.SECONDS);
    assertEquals("world", response.value().orElse(null));

    // Second peek
    future = new CompletableFuture<StackService.Response>();
    app1.processCommand(new StackService.Peek(), future);
    response = future.get(1, TimeUnit.SECONDS);
    assertEquals("world", response.value().orElse(null));

    // First pop
    future = new CompletableFuture<>();
    app1.processCommand(new StackService.Pop(), future);
    response = future.get(1, TimeUnit.SECONDS);
    assertEquals("world", response.value().orElse(null));

    // Second pop
    future = new CompletableFuture<>();
    app1.processCommand(new StackService.Pop(), future);
    response = future.get(1, TimeUnit.SECONDS);
    assertEquals("hello", response.value().orElse(null));
  }

}

class TransientJournal implements Journal {
  private Progress progress;
  private final Map<Long, Accept> accepts = new ConcurrentHashMap<>();

  TransientJournal(short nodeId) {
    this.progress = new Progress(nodeId);
    writeAccept(new Accept(nodeId, 0L, new BallotNumber(0, nodeId), NoOperation.NOOP));
  }

  @Override
  public Progress readProgress(short nodeIdentifier) {
    return progress;
  }

  @Override
  public void writeProgress(Progress newProgress) {
    this.progress = newProgress;
  }

  @Override
  public void writeAccept(Accept accept) {
    accepts.put(accept.slot(), accept);
  }

  @Override
  public Optional<Accept> readAccept(long logIndex) {
    return Optional.ofNullable(accepts.get(logIndex));
  }

  @Override
  public void sync() {
    // No-op for in-memory implementation
  }

  @Override
  public long highestLogIndex() {
    return accepts.keySet().stream().max(Long::compareTo).orElse(0L);
  }
}
