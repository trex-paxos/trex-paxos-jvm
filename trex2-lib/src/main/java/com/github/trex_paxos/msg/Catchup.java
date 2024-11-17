package com.github.trex_paxos.msg;

import java.util.Arrays;

/// Catchup is a message sent by a replica to the leader to request missing committed slots.
public record Catchup(byte from,
                      byte to,
                      long[] slotGaps,
                      long highestCommitedIndex) implements DirectMessage, TrexMessage {

  @Override
  public String toString() {
    return "Catchup[" +
        "from=" + from +
        ", to=" + to +
        ", slotGaps=" + Arrays.toString(slotGaps) +
        ']';
  }
}
