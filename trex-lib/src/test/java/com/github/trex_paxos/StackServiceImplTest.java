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
package com.github.trex_paxos;

import com.github.trex_paxos.network.*;
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
  void setup() {
    final var logLevel = System.getProperty("java.util.logging.ConsoleHandler.level", "WARNING");

    StackServiceImpl.setLogLevel(Level.parse(logLevel));

    Supplier<NodeEndpoint> members = () -> new NodeEndpoint(
        Map.of(new NodeId((short) 1), new NetworkAddress("localhost", 5000),
            new NodeId((short) 2), new NetworkAddress("localhost", 5001)));

    NetworkLayer networkLayer1 = new TestNetworkLayer(new NodeId((short) 1),
        Map.of(CONSENSUS.value(), PickleMsg.instance, PROXY.value(), Pickle.instance)
    );
    NetworkLayer networkLayer2 = new TestNetworkLayer(new NodeId((short) 1),
        Map.of(CONSENSUS.value(), PickleMsg.instance, PROXY.value(), Pickle.instance)
    );

    stackService1 = new StackServiceImpl((short)1, members, networkLayer1);
    stackService2 = new StackServiceImpl((short)2, members, networkLayer2);
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
