package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record PrepareNack(Identifier requestId,
                          byte from,
                          Progress progress,
                          long highestAcceptedIndex,
                          long leaderHeartbeat) implements TrexMessage, PrepareResponse {

  public void writeTo(DataOutputStream dos) throws IOException {
    requestId.writeTo(dos);
    dos.writeByte(from);
    progress.writeTo(dos);
    dos.writeLong(highestAcceptedIndex);
    dos.writeLong(leaderHeartbeat);
  }

  public static PrepareNack readFrom(DataInputStream dataInputStream) throws IOException {
    return new PrepareNack(Identifier.readFrom(dataInputStream),
      dataInputStream.readByte(),
      Progress.readFrom(dataInputStream),
      dataInputStream.readLong(),
      dataInputStream.readLong());
  }

  @Override
  public long highestCommittedIndex() {
    return progress().highestCommitted().logIndex();
  }
}
