package com.github.trex_paxos.msg;

import com.github.trex_paxos.Vote;

public record AcceptResponse(Vote vote, Progress progress) implements TrexMessage, DirectMessage {
  /**
   * @return the proposer that sent the request
   */
  public byte from() {
    return vote.from();
  }

  public byte to() {
    return vote.to();
  }

}
