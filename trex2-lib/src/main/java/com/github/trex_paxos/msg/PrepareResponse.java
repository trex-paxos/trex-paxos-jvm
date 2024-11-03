package com.github.trex_paxos.msg;

import com.github.trex_paxos.Pickle;
import com.github.trex_paxos.Vote;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;

// FIXME seems unsafe or hard to deal with logging if adding in the Ballot Number. An AcceptResponse transmits such info as progress and I think we should add it here at the very least to log.

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

  public static void writeTo(PrepareResponse m, DataOutputStream dos) throws IOException {
    Pickle.write(m.vote(), dos);
    dos.writeLong(m.highestCommittedIndex());
    dos.writeBoolean(m.highestUncommitted().isPresent());
    if (m.highestUncommitted().isPresent()) {
      Pickle.write(m.highestUncommitted().get(), dos);
    }
  }

  public static PrepareResponse readFrom(DataInputStream dis) throws IOException {
    Vote vote = Pickle.readVote(dis);
    long highestCommittedIndex = dis.readLong();
    Optional<Accept> highestUncommitted = dis.readBoolean() ? Optional.of(Pickle.readAccept(dis)) : Optional.empty();
    return new PrepareResponse(vote, highestCommittedIndex, highestUncommitted);
  }
}
