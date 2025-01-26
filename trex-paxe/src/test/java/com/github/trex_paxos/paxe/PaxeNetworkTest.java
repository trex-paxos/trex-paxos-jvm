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

import static com.github.trex_paxos.network.SystemChannel.CONSENSUS;
import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;
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
    ConsoleHandler handler = new ConsoleHandler();
    handler.setLevel(level);
    LOGGER.addHandler(handler);
    LOGGER.setLevel(level);
    LOGGER.setUseParentHandlers(false);
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

    network1.send(channel, new NodeId((short) 2), msg1);
    network2.send(channel, new NodeId((short) 1), msg2);

    assertTrue(latch.await(1, TimeUnit.SECONDS), "Message exchange timed out");
    assertEquals(msg2, received1.get(), "Network 1 received wrong message");
    assertEquals(msg1, received2.get(), "Network 2 received wrong message");
  }

  @AfterEach
  void cleanup() throws Exception {
    if (network1 != null) network1.close();
    if (network2 != null) network2.close();
    if (testSelector != null) testSelector.close();
  }

}
