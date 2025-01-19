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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaxeStackClusterTest {
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(2);

  private NetworkTestHarness harness;
  private TrexApp<StackService.Command, StackService.Response> app1;
  private TrexApp<StackService.Command, StackService.Response> app2;
  private final Function<StackService.Command, StackService.Response> processor =
      new StackProcessor();
  private final AtomicInteger nodeToggle = new AtomicInteger(0);

  @BeforeAll
  static void setupLogging() {
    ConsoleHandler handler = new ConsoleHandler();
    handler.setLevel(Level.parse(System.getProperty("java.util.logging.ConsoleHandler.level", "WARNING")));

    Logger rootLogger = Logger.getLogger("");
    rootLogger.setLevel(handler.getLevel());
    rootLogger.setUseParentHandlers(false);
    rootLogger.addHandler(handler);

    Logger paxeLogger = PaxeLogger.LOGGER;
    paxeLogger.setLevel(handler.getLevel());
    paxeLogger.setUseParentHandlers(false);
    paxeLogger.addHandler(handler);
  }

  @BeforeEach
  void setup() throws Exception {
    harness = new NetworkTestHarness();

    final short leader = 2;

    PaxeNetwork network1 = harness.createNetwork((short) 1);
    PaxeNetwork network2 = harness.createNetwork((short) 2);

    harness.waitForNetworkEstablishment();

    app1 = createApp(network1, leader);  // Leader
    app2 = createApp(network2, leader); // Follower

    app1.start();
    app2.start();

    // Wait for apps to initialize
    Thread.sleep(200);
  }

  @SuppressWarnings("SameParameterValue")
  private TrexApp<StackService.Command, StackService.Response> createApp(
      PaxeNetwork network,
      final short leader) {

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
        if (leader == trexNode.nodeIdentifier()) setLeader();
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
    PaxeLogger.LOGGER.fine(() -> String.format("Toggling active node: %d -> %d", oldNode, newNode));
  }

  @Test
  void testBasicStackOperations() throws Exception {
    // Push "first"
    CompletableFuture<StackService.Response> future = new CompletableFuture<>();
    app1.submitValue(new StackService.Push("first"), future);
    future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

    // Toggle to second node
    toggleNode();

    // Push "second"
    future = new CompletableFuture<>();
    app2.submitValue(new StackService.Push("second"), future);
    future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

    // Peek - should see "second"
    future = new CompletableFuture<>();
    app2.submitValue(new StackService.Peek(), future);
    assertEquals("second", future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).value().orElse(null));

    // Pop twice and verify ordering
    future = new CompletableFuture<>();
    app2.submitValue(new StackService.Pop(), future);
    assertEquals("second", future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).value().orElse(null));

    future = new CompletableFuture<>();
    app2.submitValue(new StackService.Pop(), future);
    assertEquals("first", future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).value().orElse(null));
  }

  @Test
  void testLargeValueDekEncryption() throws Exception {
    // Create string larger than PAYLOAD_THRESHOLD to trigger DEK
    StringBuilder largeValue = new StringBuilder();
    largeValue.append("X".repeat(PaxeCrypto.PAYLOAD_THRESHOLD + 10));

    // Push large value through node 1
    CompletableFuture<StackService.Response> future = new CompletableFuture<>();
    app1.submitValue(new StackService.Push(largeValue.toString()), future);
    future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

    // Toggle to node 2 and verify
    toggleNode();

    future = new CompletableFuture<>();
    app2.submitValue(new StackService.Pop(), future);
    assertEquals(largeValue.toString(),
        future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).value().orElse(null));
  }

  @Test
  void testNodeFailure() throws Exception {
    // Push initial value
    CompletableFuture<StackService.Response> future1 = new CompletableFuture<>();
    app1.submitValue(new StackService.Push("persistent"), future1);
    future1.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

    // Close network and verify operations fail
    harness.close();
    toggleNode();

    CompletableFuture<StackService.Response> future2 = new CompletableFuture<>();
    app2.submitValue(new StackService.Push("should-fail"), future2);
    assertThrows(Exception.class, () ->
        future2.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
  }

  @AfterEach
  void tearDown() {
    if (app1 != null) app1.stop();
    if (app2 != null) app2.stop();
    if (harness != null) harness.close();
  }

  static class StackProcessor implements Function<StackService.Command, StackService.Response> {
    private final Stack<String> stack = new Stack<>();

    @Override
    public synchronized StackService.Response apply(StackService.Command cmd) {
      return switch (cmd) {
        case StackService.Push p -> {
          stack.push(p.item());
          yield new StackService.Response(java.util.Optional.empty());
        }
        case StackService.Pop _ -> new StackService.Response(
            java.util.Optional.of(stack.isEmpty() ? "Stack is empty" : stack.pop())
        );
        case StackService.Peek _ -> new StackService.Response(
            java.util.Optional.of(stack.isEmpty() ? "Stack is empty" : stack.peek())
        );
      };
    }
  }
}
