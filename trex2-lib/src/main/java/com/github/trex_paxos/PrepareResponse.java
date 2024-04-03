package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;

record PrepareResponse(Vote vote, Progress progress,
                       long highestAcceptedIndex,
                       Optional<Accept> highestUncommitted) implements TrexMessage {
  byte from() {
    return vote.from();
  }

  Identifier requestId() {
    return vote.identifier();
  }


  long highestCommittedIndex() {
    return progress.highestCommitted().logIndex();
  }

  @Override
  public void writeTo(DataOutputStream dos) throws IOException {
    vote.writeTo(dos);
    progress.writeTo(dos);
    dos.writeLong(highestAcceptedIndex);
    dos.writeBoolean(highestUncommitted.isPresent());
    if (highestUncommitted.isPresent()) {
      highestUncommitted.get().writeTo(dos);
    }
  }

  public static PrepareResponse readFrom(DataInputStream dis) throws IOException {
    Vote vote = Vote.readFrom(dis);
    Progress progress = Progress.readFrom(dis);
    long highestAcceptedIndex = dis.readLong();
    Optional<Accept> highestUncommitted = dis.readBoolean() ? Optional.of(Accept.readFrom(dis)) : Optional.empty();
    return new PrepareResponse(vote, progress, highestAcceptedIndex, highestUncommitted);
  }
}
