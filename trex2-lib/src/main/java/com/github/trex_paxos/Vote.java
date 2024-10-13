package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/// Nodes in a cluster vote for whether an accept message is chosen or not. This object tracks such votes.
public record Vote(byte from, byte to, long logIndex, boolean vote) {
  public static Vote readFrom(DataInputStream dis) throws IOException {
    byte from = dis.readByte();
    byte to = dis.readByte();
    long logIndex = dis.readLong();
    boolean vote = dis.readBoolean();
    return new Vote(from, to, logIndex, vote);
  }

  public void writeTo(DataOutputStream dos) throws IOException {
    dos.writeByte(from);
    dos.writeByte(to);
    dos.writeLong(logIndex);
    dos.writeBoolean(vote);
  }
}
