/*
 * Copyright 2024 - 2025 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.trex_paxos.paxe;

import com.github.trex_paxos.*;
import com.github.trex_paxos.network.NodeEndpoint;
import com.github.trex_paxos.network.NetworkAddress;
import com.github.trex_paxos.NodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;

import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaxeStackClusterTest {
  private static final Duration TEST_TIMEOUT = Duration.ofMillis(500);

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

    Supplier<NodeEndpoint> members = () -> new NodeEndpoint(
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
    final var peeked =  future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).value().orElse(null);
    assertEquals("second",peeked);

    // Pop twice and verify ordering
    future = new CompletableFuture<>();
    LOGGER.info("Testing first pop operation");
    stackService2.app().submitValue(new StackService.Pop(), future);
    final var popped1 = future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).value().orElse(null);
    assertEquals("second", popped1);

    future = new CompletableFuture<>();
    LOGGER.info("Testing second pop operation");
    stackService2.app().submitValue(new StackService.Pop(), future);
    final var popped2 = future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).value().orElse(null);
    assertEquals("first", popped2);

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
    if (stackService1 != null && stackService1.app() != null) stackService1.app().stop();
    if (stackService2 != null && stackService2.app() != null) stackService2.app().stop();
    if (harness != null) harness.close();
    LOGGER.fine("Test tear down complete");
  }

}
