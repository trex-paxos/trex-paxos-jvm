package com.github.trex_paxos.msg;

import com.github.trex_paxos.Vote;

// TODO feels a bit risky not to give the Ballot Number we are responding to
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
