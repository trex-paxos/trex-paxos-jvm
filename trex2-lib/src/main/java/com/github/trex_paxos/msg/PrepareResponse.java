package com.github.trex_paxos.msg;

import com.github.trex_paxos.Vote;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;

public record PrepareResponse(Vote vote,
                              Optional<Accept> highestUncommitted,
                              Optional<CatchupResponse> catchupResponse) implements TrexMessage, DirectMessage {
  public byte from() {
    return vote.from();
  }

  public byte to() {
    return vote.to();
  }

  public Optional<Long> highestCommittedIndex() {
    return catchupResponse.stream().map(CatchupResponse::highestCommittedIndex).findFirst();
  }

  @Override
  public void writeTo(DataOutputStream dos) throws IOException {
    vote.writeTo(dos);
    dos.writeBoolean(highestUncommitted.isPresent());
    if (highestUncommitted.isPresent()) {
      highestUncommitted.get().writeTo(dos);
    }
    dos.writeBoolean(catchupResponse.isPresent());
    if (catchupResponse.isPresent()) {
      catchupResponse.get().writeTo(dos);
    }
  }

  public static PrepareResponse readFrom(DataInputStream dis) throws IOException {
    Vote vote = Vote.readFrom(dis);
    Optional<Accept> highestUncommitted = dis.readBoolean() ? Optional.of(Accept.readFrom(dis)) : Optional.empty();
    Optional<CatchupResponse> catchup = dis.readBoolean() ? Optional.of(CatchupResponse.readFrom(dis)) : Optional.empty();
    return new PrepareResponse(vote, highestUncommitted, catchup);
  }
}
