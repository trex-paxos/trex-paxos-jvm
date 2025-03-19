package com.github.trex_paxos.paxe;

import com.github.trex_paxos.*;
import com.github.trex_paxos.network.ClusterMembership;
import com.github.trex_paxos.network.NetworkAddress;
import com.github.trex_paxos.network.NodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;

import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaxeStackClusterTest {
  private static final Duration TEST_TIMEOUT = Duration.ofMillis(200);

  private NetworkTestHarness harness;
  StackServiceImpl stackService1;
  StackServiceImpl stackService2;

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
    LOGGER.fine("Setting up test harness");
    harness = new NetworkTestHarness();

    NetworkWithTempPort network1 = harness.createNetwork((short) 1);
    NetworkWithTempPort network2 = harness.createNetwork((short) 2);

    LOGGER.fine("Waiting for network establishment");
    harness.waitForNetworkEstablishment();
    LOGGER.fine("Network established successfully");

    Supplier<ClusterMembership> members = () -> new ClusterMembership(
        Map.of(new NodeId((short) 1), new NetworkAddress(network1.port()),
            new NodeId((short) 2), new NetworkAddress(network2.port())));

    stackService1 = new StackServiceImpl((short)1, members, network1.network());
    stackService2 = new StackServiceImpl((short)2, members, network2.network());

    LOGGER.info("Starting applications");

    // Allow time for leader election and initialization
    Thread.sleep(50);
    LOGGER.fine("Test setup complete");
  }

  @Test
  void testBasicStackOperations() throws Exception {
    LOGGER.info("Testing basic stack operations");

    // Push "first"
    CompletableFuture<StackService.Response> future = new CompletableFuture<>();
    LOGGER.info("Pushing 'first' to stack");
    stackService1.app().submitValue(new StackService.Push("first"), future);
    future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

    // Push "second"
    future = new CompletableFuture<>();
    LOGGER.info("Pushing 'second' to stack from alternate node");
    stackService2.app().submitValue(new StackService.Push("second"), future);
    future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

    // Peek - should see "second"
    future = new CompletableFuture<>();
    LOGGER.info("Testing peek operation");
    stackService2.app().submitValue(new StackService.Peek(), future);
    assertEquals("second", future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).value().orElse(null));

    // Pop twice and verify ordering
    future = new CompletableFuture<>();
    LOGGER.info("Testing first pop operation");
    stackService2.app().submitValue(new StackService.Pop(), future);
    assertEquals("second", future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).value().orElse(null));

    future = new CompletableFuture<>();
    LOGGER.info("Testing second pop operation");
    stackService2.app().submitValue(new StackService.Pop(), future);
    assertEquals("first", future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).value().orElse(null));

    LOGGER.info("Basic stack operations test completed successfully");
  }

  @Test
  void testNodeFailure() throws Exception {
    LOGGER.info("Testing node failure handling");

    // Push initial value
    CompletableFuture<StackService.Response> future1 = new CompletableFuture<>();
    LOGGER.info("Pushing test value before network failure");
    stackService1.app().submitValue(new StackService.Push("persistent"), future1);
    future1.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

    // Close network and verify operations fail
    LOGGER.info("Simulating network failure");
    harness.close();

    CompletableFuture<StackService.Response> future2 = new CompletableFuture<>();
    LOGGER.info("Attempting operation on failed network");
    stackService2.app().submitValue(new StackService.Push("should-fail"), future2);

    assertThrows(Exception.class, () ->
        future2.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
    LOGGER.info("Node failure test completed successfully");
  }

  @AfterEach
  void tearDown() {
    LOGGER.fine("Test tear down starting");
    if (stackService1.app() != null) stackService1.app().stop();
    if (stackService2.app() != null) stackService2.app().stop();
    if (harness != null) harness.close();
    LOGGER.fine("Test tear down complete");
  }

}
