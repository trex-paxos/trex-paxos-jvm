package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record AcceptNack(Identifier requestId, int from, Progress progress) implements PaxosMessage, AcceptResponse {
    @Override
    public void writeTo(DataOutputStream dos) throws IOException {
        requestId.writeTo(dos);
        dos.writeInt(from);
        progress.writeTo(dos);
    }

    public static AcceptNack readFrom(DataInputStream dis) throws IOException {
        return new AcceptNack(Identifier.readFrom(dis), dis.readInt(), Progress.readFrom(dis));
    }

    @Override
    public Identifier highestCommitted() {
        return progress.highestCommitted();
    }
}
