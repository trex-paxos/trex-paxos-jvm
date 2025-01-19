package com.github.trex_paxos.paxe;

import com.github.trex_paxos.BallotNumber;
import com.github.trex_paxos.msg.Fixed;
import com.github.trex_paxos.network.Channel;
import com.github.trex_paxos.network.NodeId;
import org.junit.jupiter.api.*;

import java.nio.channels.Selector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.github.trex_paxos.network.SystemChannel.CONSENSUS;
import static com.github.trex_paxos.network.SystemChannel.PROXY;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PaxeNetworkTest {
  static {
    System.setProperty(SRPUtils.class.getName() + ".useHash", "SHA3-256");
  }

  public static final String N = "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C9C256576D674DF7496EA81D3383B4813D692C6E0E0D5D8E250B98BE48E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B29";
  final static SRPUtils.Constants constants = new SRPUtils.Constants(
      N,
      "2"
  );

  private PaxeNetwork network1;
  private PaxeNetwork network2;
  private Selector testSelector;


  @BeforeAll
  static void setupLogging() {
    final var logLevel = System.getProperty("java.util.logging.ConsoleHandler.level", "WARNING");
    final Level level = Level.parse(logLevel);

    // Configure PaxeLogger
    PaxeLogger.LOGGER.setLevel(level);
    ConsoleHandler consoleHandler = new ConsoleHandler();
    consoleHandler.setLevel(level);
    PaxeLogger.LOGGER.addHandler(consoleHandler);
    PaxeLogger.LOGGER.setUseParentHandlers(false);

    // Configure SessionKeyManager logger
    Logger sessionKeyManagerLogger = Logger.getLogger(SessionKeyManager.class.getName());
    sessionKeyManagerLogger.setLevel(level);
    ConsoleHandler skmHandler = new ConsoleHandler();
    skmHandler.setLevel(level);
    sessionKeyManagerLogger.addHandler(skmHandler);
    sessionKeyManagerLogger.setUseParentHandlers(false);
  }

  @SuppressWarnings("resource")
  @BeforeEach
  public void setup() throws Exception {
    testSelector = Selector.open();
    NetworkTestHarness harness = new NetworkTestHarness(new ClusterId("test"), constants);

    network1 = harness.createNetwork((short) 1);
    network2 = harness.createNetwork((short) 2);

    network1.start();
    network2.start();

    // Wait for key exchange
    harness.waitForNetworkEstablishment();
  }

  @Test
  @Order(1)
  public void testStartup() {
    assertTrue(network1.keyManager.sessionKeys.containsKey(new NodeId((short) 2)), "Network 1 missing session key");
    assertTrue(network2.keyManager.sessionKeys.containsKey(new NodeId((short) 1)), "Network 2 missing session key");

    byte[] key1 = network1.keyManager.sessionKeys.get(new NodeId((short) 2));
    byte[] key2 = network2.keyManager.sessionKeys.get(new NodeId((short) 1));
    assertNotNull(key1, "Key 1 is null");
    assertNotNull(key2, "Key 2 is null");
    assertArrayEquals(key1, key2, "Session keys don't match");
  }

  @Test
  @Order(2)
  public void testSendAndReceiveMessages() throws Exception {
    Channel channel = CONSENSUS.value();
    CountDownLatch latch = new CountDownLatch(2);
    AtomicReference<Fixed> received1 = new AtomicReference<>();
    AtomicReference<Fixed> received2 = new AtomicReference<>();

    network1.subscribe(channel, (Fixed msg) -> {
      received1.set(msg);
      latch.countDown();
    }, "test1");

    network2.subscribe(channel, (Fixed msg) -> {
      received2.set(msg);
      latch.countDown();
    }, "test2");

    Fixed msg1 = new Fixed((short) 1, 1, new BallotNumber(1, (short) 1));
    Fixed msg2 = new Fixed((short) 2, 2, new BallotNumber(2, (short) 2));

    network1.send(channel, new NodeId((short) 1), msg1);
    network2.send(channel, new NodeId((short) 2), msg2);

    assertTrue(latch.await(1, TimeUnit.SECONDS), "Message exchange timed out");
    assertEquals(msg1, received1.get(), "Network 1 received wrong message");
    assertEquals(msg2, received2.get(), "Network 2 received wrong message");
  }

  @Test
  public void testThreadInitialization() {
    assertTrue(network1.running, "Network should be running");
    assertNotNull(network1.channel, "Channel should be initialized");
    assertTrue(network1.selector.isOpen(), "Selector should be open");
  }

  @Test
  public void testCleanShutdown() {
    network1.start();
    network1.close();

    assertFalse(network1.running, "Network should not be running");
    assertFalse(network1.selector.isOpen(), "Selector should be closed");
    assertFalse(network1.channel.isOpen(), "Channel should be closed");
  }

  @Test
  public void testChannelIsolation() throws Exception {
    Channel channel1 = CONSENSUS.value();
    Channel channel2 = PROXY.value();
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

    assertTrue(latch.await(1, TimeUnit.SECONDS), "Message exchange timed out");
    assertArrayEquals(msg1, received1.get(), "Wrong message on channel 1");
    assertArrayEquals(msg2, received2.get(), "Wrong message on channel 2");
  }

  @AfterEach
  void cleanup() throws Exception {
    if (network1 != null) network1.close();
    if (network2 != null) network2.close();
    if (testSelector != null) testSelector.close();
  }

}
