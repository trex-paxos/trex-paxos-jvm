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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaxeNetworkTest {
  static {
    System.setProperty(SRPUtils.class.getName() + ".useHash", "SHA3-256");
  }

  @SuppressWarnings("SpellCheckingInspection")
  private static final String N = "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C9C256576D674DF7496EA81D3383B4813D692C6E0E0D5D8E250B98BE48E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B29";
  private static final SRPUtils.Constants CONSTANTS = new SRPUtils.Constants(N, "2");
  private static final int TEST_TIMEOUT_MS = 1000;

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

  @BeforeEach
  void setup() throws Exception {
    LOGGER.fine("Setting up test networks");
    testSelector = Selector.open();
    @SuppressWarnings("resource")
    NetworkTestHarness harness = new NetworkTestHarness(new ClusterId("test"), CONSTANTS);

    network1 = harness.createNetwork((short) 1).network();
    network2 = harness.createNetwork((short) 2).network();

    network1.start();
    network2.start();

    LOGGER.fine("Waiting for network establishment");
    harness.waitForNetworkEstablishment();
    LOGGER.fine("Network establishment complete");
  }

  @Test
  void testSendAndReceiveMessages() throws Exception {
    Channel channel = CONSENSUS.value();
    CountDownLatch latch = new CountDownLatch(2);
    AtomicReference<Fixed> received1 = new AtomicReference<>();
    AtomicReference<Fixed> received2 = new AtomicReference<>();

    LOGGER.fine("Setting up message handlers");
    network1.subscribe(channel, (Fixed msg) -> {
      LOGGER.finest(() -> String.format("Network 1 received message: %s", msg));
      received1.set(msg);
      latch.countDown();
    }, "test1");

    network2.subscribe(channel, (Fixed msg) -> {
      LOGGER.finest(() -> String.format("Network 2 received message: %s", msg));
      received2.set(msg);
      latch.countDown();
    }, "test2");

    Fixed msg1 = new Fixed((short) 1, 1, new BallotNumber((short) 0, 1, (short) 1));
    Fixed msg2 = new Fixed((short) 2, 2, new BallotNumber((short) 0, 2, (short) 2));

    LOGGER.fine(() -> String.format("Sending test messages: msg1=%s, msg2=%s", msg1, msg2));
    network1.send(channel, new NodeId((short) 2), msg1);
    network2.send(channel, new NodeId((short) 1), msg2);

    LOGGER.fine("Waiting for message exchange");
    boolean exchangeComplete = latch.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    LOGGER.fine(() -> String.format("Exchange complete: %s, received1=%s, received2=%s",
        exchangeComplete, received1.get(), received2.get()));

    assertTrue(exchangeComplete, "Message exchange timed out");
    assertEquals(msg2, received1.get(), "Network 1 received wrong message");
    assertEquals(msg1, received2.get(), "Network 2 received wrong message");
  }

  @AfterEach
  void cleanup() throws Exception {
    LOGGER.fine("Cleaning up test resources");
    if (network1 != null) network1.close();
    if (network2 != null) network2.close();
    if (testSelector != null) testSelector.close();
  }
}
