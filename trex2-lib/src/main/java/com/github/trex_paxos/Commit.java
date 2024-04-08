package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

// FIXME commit should say who is the leader to make proxying easier.
public record Commit(long logIndex) implements TrexMessage {

  public void writeTo(DataOutputStream dos) throws IOException {
    dos.writeLong(logIndex);
  }

  public static Commit readFrom(DataInputStream dis)
    throws java.io.IOException {
    return new Commit(dis.readLong());
  }
}
