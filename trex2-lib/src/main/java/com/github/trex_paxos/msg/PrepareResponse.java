package com.github.trex_paxos.msg;

import com.github.trex_paxos.Vote;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;

// TODO seems unsafe not to name the Ballot Number. An AcceptResponse transmits such info as progress and I think we should add it here at the very least to log.

/// A PrepareResponse is a response to a Prepare message. It contains the vote and the highest uncommitted log entry if any.
public record PrepareResponse(Vote vote,
                              long highestCommittedIndex,
                              Optional<Accept> highestUncommitted
) implements TrexMessage, DirectMessage {
  public byte from() {
    return vote.from();
  }

  public byte to() {
    return vote.to();
  }

  @Override
  public void writeTo(DataOutputStream dos) throws IOException {
    vote.writeTo(dos);
    dos.writeLong(highestCommittedIndex);
    dos.writeBoolean(highestUncommitted.isPresent());
    if (highestUncommitted.isPresent()) {
      highestUncommitted.get().writeTo(dos);
    }
  }

  public static PrepareResponse readFrom(DataInputStream dis) throws IOException {
    Vote vote = Vote.readFrom(dis);
    long highestCommittedIndex = dis.readLong();
    Optional<Accept> highestUncommitted = dis.readBoolean() ? Optional.of(Accept.readFrom(dis)) : Optional.empty();
    return new PrepareResponse(vote, highestCommittedIndex, highestUncommitted);
  }
}
