/*
 * // SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
 * // SPDX-License-Identifier: Apache-2.0
 */
package com.github.trex_paxos;

import com.github.trex_paxos.network.*;
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

public class StackServiceImpl2Test {

  StackServiceImpl2 stackService1;
  StackServiceImpl2 stackService2;

  @BeforeEach
  void setup() {
    final var logLevel = System.getProperty("java.util.logging.ConsoleHandler.level", "WARNING");

    StackServiceImpl2.setLogLevel(Level.parse(logLevel));

    Supplier<NodeEndpoint> members = () -> new NodeEndpoint(
        Map.of(new NodeId((short) 1), new NetworkAddress("localhost", 5000),
            new NodeId((short) 2), new NetworkAddress("localhost", 5001)));

    TestNetworkLayer networkLayer1 = new TestNetworkLayer(new NodeId((short) 1),
        Map.of(CONSENSUS.value(), PickleMsg.instance, PROXY.value(), Pickle.instance)
    );
    TestNetworkLayer networkLayer2 = new TestNetworkLayer(new NodeId((short) 1),
        Map.of(CONSENSUS.value(), PickleMsg.instance, PROXY.value(), Pickle.instance)
    );

    stackService1 = StackServiceImpl2.create((short)1, members, networkLayer1);
    stackService2 = StackServiceImpl2.create((short)2, members, networkLayer2);
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
    assertEquals("second", stackService2.peek().value().orElseThrow());
    assertEquals("second", stackService2.pop().value().orElseThrow());
    assertEquals("first", stackService2.pop().value().orElseThrow());

    // and empty stack returns expected message
    assertEquals("Stack is empty", stackService2.pop().value().orElseThrow());
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
