package com.github.trex_paxos.paxe;

import com.github.trex_paxos.network.*;
import org.junit.jupiter.api.*;

import java.net.DatagramSocket;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PaxeNetworkTest {
  static {
    System.setProperty(SRPUtils.class.getName() + ".useHash", "SHA3-256");
  }

  final static String hexN = "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C" + //
      "9C256576D674DF7496EA81D3383B4813D692C6E0E0D5D8E250B98BE4" + //
      "8E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B29" + //
      "7BCF1885C529F566660E57EC68EDBC3C05726CC02FD4CBF4976EAA9A" + //
      "FD5138FE8376435B9FC61D2FC0EB06E3";

  final static String hexG = "2";

  final static SRPUtils.Constants constants = new SRPUtils.Constants(hexN, hexG);

  private PaxeNetwork network1;
  private PaxeNetwork network2;

  @BeforeAll
  static void setupLogging() {
    final var logLevel = System.getProperty("java.util.logging.ConsoleHandler.level", "WARNING");
    final Level level = Level.parse(logLevel);
    ConsoleHandler handler = new ConsoleHandler();
    handler.setLevel(level);
    PaxeLogger.LOGGER.setLevel(level);
    PaxeLogger.LOGGER.addHandler(handler);
    PaxeLogger.LOGGER.setUseParentHandlers(false);
  }

  @BeforeEach
  public void setup() throws Exception {
    DatagramSocket socket1 = new DatagramSocket(0);
    DatagramSocket socket2 = new DatagramSocket(0);

    int port1 = socket1.getLocalPort();
    int port2 = socket2.getLocalPort();

    socket1.close();
    socket2.close();

    NodeId node1 = new NodeId((short) 1);
    NodeId node2 = new NodeId((short) 2);

    ClusterId clusterId = new ClusterId("test.cluster");
    ClusterMembership membership = new ClusterMembership(Map.of(
        node1, new NetworkAddress.InetAddress("127.0.0.1", port1),
        node2, new NetworkAddress.InetAddress("127.0.0.1", port2)));

    NodeClientSecret nodeClientSecret1 = createNodeSecret(clusterId, node1, "blahblah");
    NodeVerifier nv1 = createVerifier(nodeClientSecret1);

    NodeClientSecret nodeClientSecret2 = createNodeSecret(clusterId, node2, "moreblahblah");
    NodeVerifier nv2 = createVerifier(nodeClientSecret2);

    Supplier<Map<NodeId, NodeVerifier>> verifierLookup = () -> Map.of(node1, nv1, node2, nv2);

    SessionKeyManager keyManager1 = new SessionKeyManager(node1, constants, nodeClientSecret1, verifierLookup);
    SessionKeyManager keyManager2 = new SessionKeyManager(node2, constants, nodeClientSecret2, verifierLookup);

    network1 = new PaxeNetwork(keyManager1, port1, node1, () -> membership);
    network2 = new PaxeNetwork(keyManager2, port2, node2, () -> membership);

    network1.start();
    network2.start();

    // Allow time for handshake to complete
    Thread.sleep(100);
  }

  @Test
  @Order(1)
  public void testStartup() throws Exception {
    // Verify session keys were exchanged
    assertTrue(network1.keyManager.sessionKeys.containsKey(new NodeId((short) 2)));
    assertTrue(network2.keyManager.sessionKeys.containsKey(new NodeId((short) 1)));

    final var key1 = network1.keyManager.sessionKeys.get(new NodeId((short) 2));
    final var key2 = network2.keyManager.sessionKeys.get(new NodeId((short) 1));
    assertArrayEquals(key1, key2);
  }

  @Test
  @Order(2)
  public void testSendAndReceiveMessages() throws Exception {
    Channel channel = new Channel((short) 3);
    CountDownLatch latch1 = new CountDownLatch(1);
    CountDownLatch latch2 = new CountDownLatch(1);
    AtomicReference<byte[]> received1 = new AtomicReference<>();
    AtomicReference<byte[]> received2 = new AtomicReference<>();

    network1.subscribe(channel, (byte[] msg) -> {
      received1.set(msg);
      latch1.countDown();
    }, "test1");

    network2.subscribe(channel, (byte[] msg) -> {
      received2.set(msg);
      latch2.countDown();
    }, "test2");

    byte[] msg1 = "Hello from Node 1".getBytes();
    byte[] msg2 = "Hello from Node 2".getBytes();

    network1.send(channel, new NodeId((short) 2), msg1);
    network2.send(channel, new NodeId((short) 1), msg2);

    assertTrue(latch1.await(1, TimeUnit.SECONDS));
    assertTrue(latch2.await(1, TimeUnit.SECONDS));

    assertArrayEquals(msg2, received1.get());
    assertArrayEquals(msg1, received2.get());
  }

  @Test
  public void testThreadInitialization() {
    assertTrue(network1.running);
  }

  @Test
  public void testCleanShutdown() {
    network1.close();
    assertFalse(network1.running);
  }

  @Test
  public void testChannelIsolation() throws Exception {
    Channel channel1 = Channel.CONSENSUS;
    Channel channel2 = Channel.PROXY;
    CountDownLatch latch = new CountDownLatch(2);
    AtomicReference<byte[]> received1 = new AtomicReference<>();
    AtomicReference<byte[]> received2 = new AtomicReference<>();

    network2.subscribe(channel1, (byte[] msg) -> {
      received1.set(msg);
      latch.countDown();
    }, "test1");

    network2.subscribe(channel2, (byte[] msg) -> {
      received2.set(msg);
      latch.countDown();
    }, "test2");

    byte[] msg1 = "msg1".getBytes();
    byte[] msg2 = "msg2".getBytes();

    network1.send(channel1, new NodeId((short) 2), msg1);
    network1.send(channel2, new NodeId((short) 2), msg2);

    assertTrue(latch.await(1, TimeUnit.SECONDS));
    assertArrayEquals(msg1, received1.get());
    assertArrayEquals(msg2, received2.get());
  }

  private NodeClientSecret createNodeSecret(ClusterId clusterId, NodeId id, String password) {
    return new NodeClientSecret(clusterId, id, password, SRPUtils.generateSalt());
  }

  private NodeVerifier createVerifier(NodeClientSecret secret) {
    final var verifier = SRPUtils.generateVerifier(
        constants,
        secret.srpIdentity(),
        secret.password(),
        secret.salt()
    );
    return new NodeVerifier(secret.srpIdentity(), verifier.toString(16));
  }

  @AfterEach
  void cleanup() {
    if (network1 != null) network1.close();
    if (network2 != null) network2.close();
  }
}
