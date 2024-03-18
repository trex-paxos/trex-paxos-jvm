package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record AcceptNack(Identifier requestId, int from, Progress progress) implements PaxosMessage {
    @Override
    public void writeTo(DataOutputStream dos) throws IOException {
        requestId.writeTo(dos);
        dos.writeInt(from);
        progress.writeTo(dos);
    }

    public static AcceptNack readFrom(DataInputStream dis) throws IOException {
        return new AcceptNack(Identifier.readFrom(dis), dis.readInt(), Progress.readFrom(dis));
    }

}
