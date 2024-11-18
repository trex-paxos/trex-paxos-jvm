package com.github.trex_paxos.msg;

import com.github.trex_paxos.Vote;

import java.util.Optional;

/// A PrepareResponse is a response to a Prepare message. It contains the vote and the highest uncommitted log entry if any.
public record PrepareResponse(
    Vote vote,
    long highestAcceptedIndex,
    Optional<Accept> highestUncommitted
) implements TrexMessage, DirectMessage {

  public byte from() {
    return vote.from();
  }

  public byte to() {
    return vote.to();
  }
}
