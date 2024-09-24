package com.github.trex_paxos.msg;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record Prepare(byte from, long logIndex, BallotNumber number) implements TrexMessage, BroadcastMessage {

    public static Prepare readFrom(DataInputStream dataInputStream) throws IOException {
      final byte from = dataInputStream.readByte();
      final long logIndex = dataInputStream.readLong();
      final BallotNumber number = BallotNumber.readFrom(dataInputStream);
      return new Prepare(from, logIndex, number);
    }

    public void writeTo(DataOutputStream dataOutputStream) throws IOException {
      dataOutputStream.writeByte(from);
      dataOutputStream.writeLong(logIndex);
      number.writeTo(dataOutputStream);
    }

  public byte from() {
    return number.nodeIdentifier();
  }
}
