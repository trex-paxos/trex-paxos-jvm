package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record Vote(byte from, Identifier identifier, boolean vote) {
  public static Vote readFrom(DataInputStream dis) throws IOException {
    byte from = dis.readByte();
    Identifier identifier = Identifier.readFrom(dis);
    boolean vote = dis.readBoolean();
    return new Vote(from, identifier, vote);
  }

  public void writeTo(DataOutputStream dos) throws IOException {
    dos.writeByte(from);
    identifier.writeTo(dos);
    dos.writeBoolean(vote);
  }
}
