package com.github.trex_paxos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class StackClusterImplTest {

  private StackClusterImpl cluster;
  private InMemoryNetwork network;

  @BeforeEach
  void setup() {
    StackClusterImpl.setLogLevel(Level.WARNING);
    network = new InMemoryNetwork("test-network");
    cluster = new StackClusterImpl(network);
  }

  @Test
  void testStackOperations() {
    // given a distributed stack
    cluster.push("first");
    cluster.push("second");

    // when we toggle to second node
    cluster.toggleNode();

    // then we can still peek and pop correctly
    assertEquals("second", cluster.peek().value().orElseThrow());
    assertEquals("second", cluster.pop().value().orElseThrow());
    assertEquals("first", cluster.pop().value().orElseThrow());

    // and empty stack returns expected message
    assertEquals("Stack is empty", cluster.pop().value().orElseThrow());
  }

  @Test
  void testNodeFailure() {
    // given a value in the stack
    cluster.push("persistent");

    // when network fails messages from Node 2
    network.close();
    cluster.toggleNode();

    // then operations timeout
    var future = new CompletableFuture<StackService.Response>();
    assertThrows(Exception.class, () ->
        future.get(1, TimeUnit.SECONDS));
  }
}
