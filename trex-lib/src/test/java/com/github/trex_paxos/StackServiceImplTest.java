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

    Supplier<ClusterMembership> members = () -> new ClusterMembership(
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

    // when we toggle to second node
    stackService1.toggleNode();

    // then we can still peek and pop correctly
    assertEquals("second", stackService1.peek().value().orElseThrow());
    assertEquals("second", stackService1.pop().value().orElseThrow());
    assertEquals("first", stackService1.pop().value().orElseThrow());

    // and empty stack returns expected message
    assertEquals("Stack is empty", stackService1.pop().value().orElseThrow());
  }

  @Test
  void testNodeFailure() {
    // given a value in the stack
    stackService1.push("persistent");

    // when network fails messages from Node 2
    TestNetworkLayer.network.close();
    stackService1.toggleNode();

    // then operations timeout
    var future = new CompletableFuture<StackService.Response>();
    assertThrows(Exception.class, () ->
        future.get(1, TimeUnit.SECONDS));
  }
}
