// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import com.github.trex_paxos.network.PickleMsg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;

import static com.github.trex_paxos.network.SystemChannel.CONSENSUS;
import static com.github.trex_paxos.network.SystemChannel.PROXY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class StackServiceImplTest {

  StackServiceImpl stackService1;
  StackServiceImpl stackService2;

  @BeforeEach
  void setup() throws Exception {
    final var logLevel = System.getProperty("java.util.logging.ConsoleHandler.level", "WARNING");

    StackServiceImpl.setLogLevel(Level.parse(logLevel));

    Supplier<Legislators> members = () -> Legislators.of(
        new VotingWeight(new NodeId((short) 1), 1),
        new VotingWeight(new NodeId((short) 2), 1),
        new VotingWeight(new NodeId((short) 3), 1)
    );

    TestNetworkLayer networkLayer1 = new TestNetworkLayer(new NodeId((short) 1),
        Map.of(CONSENSUS.value(), PickleMsg.instance, PROXY.value(), CommandPickler.instance)
    );
    TestNetworkLayer networkLayer2 = new TestNetworkLayer(new NodeId((short) 1),
        Map.of(CONSENSUS.value(), PickleMsg.instance, PROXY.value(), CommandPickler.instance)
    );

    stackService1 = new StackServiceImpl((short) 1, members, networkLayer1);
    stackService2 = new StackServiceImpl((short) 2, members, networkLayer2);

    // Make sure stackService2 knows that node 1 is the leader
    // This simulates stackService2 receiving a Fixed message with node 1 as leader
    ((TrexService.Implementation<StackService.Value, StackService.Response>)
        stackService2.service()).getLeaderTracker().setLeader(new NodeId((short)1));
  }

  @AfterEach
  void tearDown() {
    if (stackService1 != null) stackService1.stop();
    if (stackService2 != null) stackService2.stop();
  }

  @Test
  void testStackOperations() {
    // given a distributed stack
    stackService1.push("first");
    stackService1.push("second");

    // then we can still peek and pop correctly from the second node
    assertEquals("second", stackService2.peek().payload());
    assertEquals("second", stackService2.pop().payload());
    assertEquals("first", stackService2.pop().payload());

    // and empty stack returns expected message
    assertEquals("Stack is empty", stackService2.pop().payload());
  }

  @Test
  void testNodeFailure() {
    // given a value in the stack
    stackService1.push("persistent");

    // when network fails messages from Node 2
    TestNetworkLayer.network.close();

    // then operations timeout
    var future = new CompletableFuture<StackService.Response>();
    assertThrows(Exception.class, () ->
        future.get(1, TimeUnit.SECONDS));
  }
}

