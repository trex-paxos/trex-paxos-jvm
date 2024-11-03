package com.github.trex_paxos.msg;

public record Accept(byte from,
                     long logIndex,
                     BallotNumber number,
                     AbstractCommand command) implements TrexMessage, BroadcastMessage {

  public int compareTo(Accept accept) {
      if (logIndex < accept.logIndex) {
        return -1;
      } else if (logIndex > accept.logIndex) {
        return 1;
      } else {
        return number.compareTo(accept.number);
      }
    }
}
