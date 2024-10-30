package com.github.trex_paxos.msg;

import com.github.trex_paxos.Vote;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

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
