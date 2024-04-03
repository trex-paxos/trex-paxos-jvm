package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record RetransmitRequest(byte from, byte to, long fromIndex) implements TrexMessage {
  @Override
  public void writeTo(DataOutputStream dos) throws IOException {
    dos.writeByte(from);
    dos.writeByte(to);
    dos.writeLong(fromIndex);
  }

  public static RetransmitRequest readFrom(DataInputStream dis) throws IOException {
    byte from = dis.readByte();
    byte to = dis.readByte();
    long fromIndex = dis.readLong();
    return new RetransmitRequest(from, to, fromIndex);
  }
}
