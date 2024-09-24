package com.github.trex_paxos.msg;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record Catchup(byte from, byte to, long highestCommittedIndex) implements TrexMessage, DirectMessage {
  public static Catchup readFrom(DataInputStream dis) throws IOException {
    return new Catchup(dis.readByte(), dis.readByte(), dis.readLong());
  }
  public void writeTo(DataOutputStream dos) throws IOException {
    dos.writeByte(from);
    dos.writeByte(to);
    dos.writeLong(highestCommittedIndex);
  }
}
