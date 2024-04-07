package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

record AcceptResponse(Vote vote, Progress progress) implements TrexMessage {

  /**
   * @return the proposer that sent the request
   */
  byte from() {
    return vote.from();
  }


  @Override
  public void writeTo(DataOutputStream dos) throws IOException {
    vote.writeTo(dos);
    progress.writeTo(dos);
  }

  public static AcceptResponse readFrom(DataInputStream dis) throws IOException {
    Vote vote = Vote.readFrom(dis);
    Progress progress = Progress.readFrom(dis);
    return new AcceptResponse(vote, progress);
  }
}
