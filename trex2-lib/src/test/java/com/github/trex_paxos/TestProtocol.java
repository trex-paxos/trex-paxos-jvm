package com.github.trex_paxos;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

public class TestProtocol {
  @Test
  public void testSteadyState() {
    // given
    final var node1 = trexNode(1, TrexRole.LEADER, progress());
    final var node2 = trexNode(2, TrexRole.FOLLOWER, progress());

    // when
    node1.append(new Command("1", "command1".getBytes(StandardCharsets.UTF_8)));
  }

  private Progress progress() {
    throw new AssertionError("Implement me!");
  }

  private TrexNode trexNode(int i, TrexRole role, Progress progress) {
    throw new AssertionError("Implement me!");
  }
}
