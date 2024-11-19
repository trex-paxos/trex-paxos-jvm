package com.github.trex_paxos.msg;

/// Catchup is a message sent by a replica to the leader to request missing committed slots.
public record Catchup(byte from,
                      byte to,
                      long highestCommitedIndex) implements DirectMessage, TrexMessage {

  @Override
  public String toString() {
    return "Catchup[" +
        "from=" + from +
        ", to=" + to +
        ']';
  }
}
