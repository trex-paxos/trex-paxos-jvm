package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record Commit(byte from, long logIndex) implements TrexMessage {

  public void writeTo(DataOutputStream dos) throws IOException {
    dos.writeByte(from);
    dos.writeLong(logIndex);
  }

  public static Commit readFrom(DataInputStream dis)
    throws java.io.IOException {
    final byte from = dis.readByte();
    return new Commit(from, dis.readLong());
  }
}
