package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;

public record PrepareAck(Identifier requestId,
                         byte from,
                         Progress progress,
                         long highestAcceptedIndex,
                         long leaderHeartbeat,
                         Optional<Accept> highestUncommitted) implements PaxosMessage, PrepareResponse {





    public static PrepareAck readFrom(DataInputStream dis) throws IOException {
        Identifier requestId = Identifier.readFrom(dis);
        byte from = dis.readByte();
        Progress progress = Progress.readFrom(dis);
        long highestAcceptedIndex = dis.readLong();
        long leaderHeartbeat = dis.readLong();
        Optional<Accept> highestUncommitted = dis.readBoolean() ? Optional.of(Accept.readFrom(dis)) : Optional.empty();
        return new PrepareAck(requestId, from, progress, highestAcceptedIndex, leaderHeartbeat, highestUncommitted);
    }

    public void writeTo(DataOutputStream dos) throws IOException {
        requestId.writeTo(dos);
        dos.writeByte(from);
        progress.writeTo(dos);
        dos.writeLong(highestAcceptedIndex);
        dos.writeLong(leaderHeartbeat);
        dos.writeBoolean(highestUncommitted.isPresent());
        if( highestUncommitted.isPresent() ) {
            highestUncommitted.get().writeTo(dos);
        }
    }

}
