package com.github.trex_paxos.paxe;

import com.github.trex_paxos.*;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.EmptyStackException;
import java.util.Optional;
import java.util.Stack;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Disabled
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PaxeStackClusterTest {
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(1);

  private NetworkTestHarness harness;
  private TrexApp<StackService.Command, StackService.Response> app1;
  private TrexApp<StackService.Command, StackService.Response> app2;
  private final Function<StackService.Command, StackService.Response> processor =
      new StackProcessor();

  @BeforeAll
  static void setupLogging() {
    ConsoleHandler handler = new ConsoleHandler();
    handler.setLevel(Level.parse(System.getProperty("trex.log.level.paxe", "WARNING")));

    // Configure root logger
    Logger rootLogger = TrexLogger.LOGGER;
    rootLogger.setLevel(handler.getLevel());
    rootLogger.setUseParentHandlers(false);
    rootLogger.addHandler(handler);

    // Configure Paxe logger
    Logger paxeLogger = PaxeLogger.LOGGER;
    paxeLogger.setLevel(handler.getLevel());
    paxeLogger.setUseParentHandlers(false);
    paxeLogger.addHandler(handler);
  }

  @BeforeEach
  void setup() throws Exception {
    harness = new NetworkTestHarness();

    PaxeNetwork network1 = harness.createNetwork((short) 1);
    PaxeNetwork network2 = harness.createNetwork((short) 2);

    harness.waitForNetworkEstablishment();

    app1 = createApp(network1, true);  // Leader
    app2 = createApp(network2, false); // Follower

    app1.start();
    app2.start();

    // Wait for apps to initialize
    Thread.sleep(200);
  }

  private TrexApp<StackService.Command, StackService.Response> createApp(
      PaxeNetwork network,
      boolean isLeader) {

    // FIXME and enable test
    PaxeNetwork networkLayer = null;

    TrexNode node = createNode(network.localNode.id());
    TrexEngine engine = createEngine(node, isLeader);

    return new TrexApp<>(
        network.membership,
        engine,
        networkLayer,
        commandSerde,
        processor
    );
  }

  private TrexNode createNode(short nodeId) {
    return new TrexNode(
        Level.INFO,
        nodeId,
        new SimpleMajority(2),
        new TransparentJournal(nodeId)
    );
  }

  private TrexEngine createEngine(TrexNode node, boolean isLeader) {
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

      {
        if (isLeader) {
          setLeader();
        }
      }
    };
  }

  private final Pickler<StackService.Command> commandSerde = PermitsRecordsPickler.createPickler(StackService.Command.class);

  @Test
  @Order(1)
  void testStackOperations() throws Exception {
    // Push "hello"
    CompletableFuture<StackService.Response> future = new CompletableFuture<>();
    app1.submitValue(new StackService.Push("hello"), future);
    StackService.Response response = future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertEquals(Optional.empty(), response.value());

    // Let consensus complete
    Thread.sleep(1);

    // Push "world"
    future = new CompletableFuture<>();
    app1.submitValue(new StackService.Push("world"), future);
    response = future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertEquals(Optional.empty(), response.value());

    // Peek - should see "world"
    future = new CompletableFuture<>();
    app1.submitValue(new StackService.Peek(), future);
    response = future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertEquals("world", response.value().orElse(null));

    // Pop - should get "world"
    future = new CompletableFuture<>();
    app1.submitValue(new StackService.Pop(), future);
    response = future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertEquals("world", response.value().orElse(null));

    // Pop - should get "hello"
    future = new CompletableFuture<>();
    app1.submitValue(new StackService.Pop(), future);
    response = future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertEquals("hello", response.value().orElse(null));
  }

  @AfterEach
  void stopAll() {
    if (app1 != null) app1.stop();
    if (app2 != null) app2.stop();
    if (harness != null) harness.close();
  }

  private static class StackProcessor implements Function<StackService.Command, StackService.Response> {
    private final Stack<String> stack = new Stack<>();

    @Override
    public synchronized StackService.Response apply(StackService.Command cmd) {
      try {
        return switch (cmd) {
          case StackService.Push p -> {
            stack.push(p.item());
            yield new StackService.Response(Optional.empty());
          }
          case StackService.Pop _ -> new StackService.Response(
              Optional.of(stack.pop())
          );
          case StackService.Peek _ -> new StackService.Response(
              Optional.of(stack.peek())
          );
        };
      } catch (EmptyStackException e) {
        return new StackService.Response(Optional.empty());
      }
    }
  }
}
