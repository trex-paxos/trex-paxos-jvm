package com.github.trex_paxos;

import com.github.trex_paxos.msg.Command;
import com.github.trex_paxos.msg.Progress;

import java.nio.charset.StandardCharsets;

public class AlgorithmTests {
  //@Test
  public void testSteadyState() {
    // given
    final var node1 = trexNode(1, TrexRole.LEAD, progress());
    final var node2 = trexNode(2, TrexRole.FOLLOW, progress());

    // when
    final var accept = node1.startAppendToLog(new Command("1", "command1".getBytes(StandardCharsets.UTF_8)));

  }

  private Progress progress() {
    throw new AssertionError("Implement me!");
  }

  private TrexNode trexNode(int i, TrexRole role, Progress progress) {
    throw new AssertionError("Implement me!");
  }
}
