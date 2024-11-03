package com.github.trex_paxos.msg;

import java.util.Arrays;

// FIXME remove the highestCommittedIndex as we will not ff anymore
public record Catchup(byte from, byte to, long highestCommittedIndex,
                      long[] slotGaps) implements DirectMessage, TrexMessage {

  @Override
  public String toString() {
    return "Catchup[" +
        "from=" + from +
        ", to=" + to +
        ", logIndex=" + highestCommittedIndex +
        ", slotGaps=" + Arrays.toString(slotGaps) +
        ']';
  }
}
