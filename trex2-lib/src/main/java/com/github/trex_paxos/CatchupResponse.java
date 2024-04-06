package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record CatchupResponse(long highestCommittedIndex, List<Accept> catchup) implements TrexMessage {
  public static CatchupResponse readFrom(DataInputStream dis) throws IOException {
    long highestCommittedIndex = dis.readLong();
    int catchupSize = dis.readInt();
    List<Accept> catchup = new ArrayList<>();
    for (var i = 0; i < catchupSize; i++) {
      catchup.add(Accept.readFrom(dis));
    }
    return new CatchupResponse(highestCommittedIndex, catchup);
  }

  public void writeTo(DataOutputStream dos) throws IOException {
    dos.writeLong(highestCommittedIndex);
    dos.writeInt(catchup.size());
    for (Accept accept : catchup) {
      accept.writeTo(dos);
    }
  }
}
