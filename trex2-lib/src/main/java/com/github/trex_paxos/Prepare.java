package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record Prepare(long logIndex, BallotNumber number) implements TrexMessage {

    public static Prepare readFrom(DataInputStream dataInputStream) throws IOException {
      final long logIndex = dataInputStream.readLong();
      final BallotNumber number = BallotNumber.readFrom(dataInputStream);
      return new Prepare(logIndex, number);
    }

    public void writeTo(DataOutputStream dataOutputStream) throws IOException {
      dataOutputStream.writeLong(logIndex);
      number.writeTo(dataOutputStream);
    }
}
