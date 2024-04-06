package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record Catchup(byte from, long highestCommittedIndex) implements TrexMessage {
  public static Catchup readFrom(DataInputStream dis) throws IOException {
    return new Catchup(dis.readByte(), dis.readLong());
  }

  public void writeTo(DataOutputStream dos) throws IOException {
    dos.writeByte(from);
    dos.writeLong(highestCommittedIndex);
  }
}
