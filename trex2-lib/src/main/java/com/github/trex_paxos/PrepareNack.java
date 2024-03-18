package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record PrepareNack(Identifier requestId,
                          int from,
                          Progress progress,
                          long highestAcceptedIndex,
                          long leaderHeartbeat) implements PaxosMessage {

    public void writeTo(DataOutputStream dos) throws IOException {
        requestId.writeTo(dos);
        dos.writeInt(from);
        progress.writeTo(dos);
        dos.writeLong(highestAcceptedIndex);
        dos.writeLong(leaderHeartbeat);
    }

    public static PrepareNack readFrom(DataInputStream dataInputStream) throws IOException {
        return new PrepareNack(Identifier.readFrom(dataInputStream),
                dataInputStream.readInt(),
                Progress.readFrom(dataInputStream),
                dataInputStream.readLong(),
                dataInputStream.readLong());
    }
}
