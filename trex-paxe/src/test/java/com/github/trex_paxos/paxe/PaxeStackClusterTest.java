package com.github.trex_paxos.paxe;

import com.github.trex_paxos.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Stack;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;

import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaxeStackClusterTest {
  private static final Duration TEST_TIMEOUT = Duration.ofMillis(200);

  private NetworkTestHarness harness;
  private TrexApp<StackService.Command, StackService.Response> app1;
  private TrexApp<StackService.Command, StackService.Response> app2;
  private final Function<StackService.Command, StackService.Response> processor =
      new StackProcessor();
  private final AtomicInteger nodeToggle = new AtomicInteger(0);

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

    final short leader = 2;
    LOGGER.info(() -> "Creating test network with leader node " + leader);

    PaxeNetwork network1 = harness.createNetwork((short) 1);
    PaxeNetwork network2 = harness.createNetwork((short) 2);

    LOGGER.fine("Waiting for network establishment");
    harness.waitForNetworkEstablishment();
    LOGGER.fine("Network established successfully");

    app1 = createApp(network1, leader);
    app2 = createApp(network2, leader);

    LOGGER.info("Starting applications");
    app1.start();
    app2.start();

    // Allow time for leader election and initialization
    Thread.sleep(50);
    LOGGER.fine("Test setup complete");
  }

  private TrexApp<StackService.Command, StackService.Response> createApp(
      PaxeNetwork network,
      final short leader) {
    LOGGER.fine(() -> String.format("Creating app for node %d, leader=%d",
        network.localNode.id(), leader));

    TrexNode node = createNode(network.localNode.id());
    TrexEngine engine = new TrexEngine(node) {
      @Override
      protected void setRandomTimeout() {
      }

      @Override
      protected void clearTimeout() {
      }

      @Override
      protected void setNextHeartbeat() {
      }

      {
        if (leader == trexNode.nodeIdentifier()) {
          LOGGER.info(() -> "Setting node " + leader + " as leader");
          setLeader();
        }
      }
    };

    return new TrexApp<>(
        network.membership,
        engine,
        network,
        PermitsRecordsPickler.createPickler(StackService.Command.class),
        processor
    ) {{
      setLeader(leader);
    }};
  }

  private TrexNode createNode(short nodeId) {
    QuorumStrategy quorum = new SimpleMajority(2);
    return new TrexNode(Level.INFO, nodeId, quorum, new TransparentJournal(nodeId));
  }

  void toggleNode() {
    int oldNode = nodeToggle.get() % 2;
    int newNode = nodeToggle.incrementAndGet() % 2;
    LOGGER.info(() -> String.format("Toggling active node: %d -> %d", oldNode, newNode));
  }

  @Test
  void testBasicStackOperations() throws Exception {
    LOGGER.info("Testing basic stack operations");

    // Push "first"
    CompletableFuture<StackService.Response> future = new CompletableFuture<>();
    LOGGER.info("Pushing 'first' to stack");
    app1.submitValue(new StackService.Push("first"), future);
    future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

    // Toggle to second node
    toggleNode();

    // Push "second"
    future = new CompletableFuture<>();
    LOGGER.info("Pushing 'second' to stack from alternate node");
    app2.submitValue(new StackService.Push("second"), future);
    future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

    // Peek - should see "second"
    future = new CompletableFuture<>();
    LOGGER.info("Testing peek operation");
    app2.submitValue(new StackService.Peek(), future);
    assertEquals("second", future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).value().orElse(null));

    // Pop twice and verify ordering
    future = new CompletableFuture<>();
    LOGGER.info("Testing first pop operation");
    app2.submitValue(new StackService.Pop(), future);
    assertEquals("second", future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).value().orElse(null));

    future = new CompletableFuture<>();
    LOGGER.info("Testing second pop operation");
    app2.submitValue(new StackService.Pop(), future);
    assertEquals("first", future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).value().orElse(null));

    LOGGER.info("Basic stack operations test completed successfully");
  }

  @Test
  void testNodeFailure() throws Exception {
    LOGGER.info("Testing node failure handling");

    // Push initial value
    CompletableFuture<StackService.Response> future1 = new CompletableFuture<>();
    LOGGER.info("Pushing test value before network failure");
    app1.submitValue(new StackService.Push("persistent"), future1);
    future1.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

    // Close network and verify operations fail
    LOGGER.info("Simulating network failure");
    harness.close();
    toggleNode();

    CompletableFuture<StackService.Response> future2 = new CompletableFuture<>();
    LOGGER.info("Attempting operation on failed network");
    app2.submitValue(new StackService.Push("should-fail"), future2);

    assertThrows(Exception.class, () ->
        future2.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
    LOGGER.info("Node failure test completed successfully");
  }

  @AfterEach
  void tearDown() {
    LOGGER.fine("Test tear down starting");
    if (app1 != null) app1.stop();
    if (app2 != null) app2.stop();
    if (harness != null) harness.close();
    LOGGER.fine("Test tear down complete");
  }

  static class StackProcessor implements Function<StackService.Command, StackService.Response> {
    private final Stack<String> stack = new Stack<>();

    @Override
    public synchronized StackService.Response apply(StackService.Command cmd) {
      return switch (cmd) {
        case StackService.Push p -> {
          LOGGER.info(() -> "Processing push command: " + p.item());
          stack.push(p.item());
          yield new StackService.Response(java.util.Optional.empty());
        }
        case StackService.Pop _ -> {
          LOGGER.info("Processing pop command");
          var result = new StackService.Response(
              java.util.Optional.of(stack.isEmpty() ? "Stack is empty" : stack.pop())
          );
          LOGGER.info(() -> "Pop result: " + result.value().orElse("null"));
          yield result;
        }
        case StackService.Peek _ -> {
          LOGGER.info("Processing peek command");
          var result = new StackService.Response(
              java.util.Optional.of(stack.isEmpty() ? "Stack is empty" : stack.peek())
          );
          LOGGER.info(() -> "Peek result: " + result.value().orElse("null"));
          yield result;
        }
      };
    }
  }
}
