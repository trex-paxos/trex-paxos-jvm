// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe;

import com.github.trex_paxos.*;
import com.github.trex_paxos.network.NetworkAddress;
import com.github.trex_paxos.network.NodeEndpoints;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;

import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    Supplier<NodeEndpoints> eps = () -> new NodeEndpoints(
        Map.of(new NodeId((short) 1), new NetworkAddress(network1.port()),
            new NodeId((short) 2), new NetworkAddress(network2.port())));

    Supplier<Legislators> members = () -> Legislators.of(
        new VotingWeight(new NodeId((short) 1), 1),
        new VotingWeight(new NodeId((short) 2), 1),
        new VotingWeight(new NodeId((short) 3), 1)
    );

    stackService1 = new StackServiceImpl((short)1, members, network1.network());
    stackService2 = new StackServiceImpl((short)2, members, network2.network());

    LOGGER.info("Starting applications");

    // Explicitly set up the leader
    // Set node 1 as the leader
    ((TrexService.TrexServiceImpl<StackService.Value, StackService.Response>)
        stackService1.service()).setLeader();

    // Make sure node 2 knows that node 1 is the leader
    ((TrexService.TrexServiceImpl<StackService.Value, StackService.Response>)
        stackService2.service()).getLeaderTracker().setLeader(new NodeId((short) 1));

    // Allow time for initialization - increased from 50ms
    Thread.sleep(100);
    LOGGER.fine("Test setup complete");
  }
  @Test
  void testBasicStackOperations() throws Exception {
    LOGGER.info("Testing basic stack operations");

    // Push "first"
    LOGGER.info("Pushing 'first' to stack");
    StackService.Response response1 = stackService1.push("first");

    // Push "second"
    LOGGER.info("Pushing 'second' to stack from alternate node");
    StackService.Response response2 = stackService2.push("second");

    // Peek - should see "second"
    LOGGER.info("Testing peek operation");
    StackService.Response peekResponse = stackService2.peek();
    final var peeked = peekResponse.payload();
    assertEquals("second", peeked);

    // Pop twice and verify ordering
    LOGGER.info("Testing first pop operation");
    StackService.Response popResponse1 = stackService2.pop();
    final var popped1 = popResponse1.payload();
    assertEquals("second", popped1);

    LOGGER.info("Testing second pop operation");
    StackService.Response popResponse2 = stackService2.pop();
    final var popped2 = popResponse2.payload();
    assertEquals("first", popped2);

    LOGGER.info("Basic stack operations test completed successfully");
  }

  @Test
  void testNodeFailure() throws Exception {
    LOGGER.info("Testing node failure handling");

    // Push initial value
    LOGGER.info("Pushing test value before network failure");
    stackService1.push("persistent");

    // Close network and verify operations fail
    LOGGER.info("Simulating network failure");
    harness.close();

    // Should throw exception due to network failure
    LOGGER.info("Attempting operation on failed network");
    final var error = stackService2.push("should-fail");
    assertThat(error).isInstanceOf(StackService.Failure.class);

    LOGGER.info("Node failure test completed successfully");
  }

  @AfterEach
  void tearDown() {
    LOGGER.fine("Test tear down starting");
    if (stackService1 != null) stackService1.stop();
    if (stackService2 != null) stackService2.stop();
    if (harness != null) harness.close();
    LOGGER.fine("Test tear down complete");
  }
}
