package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;

public record AcceptAck(Identifier requestId, int from, Progress progress) implements TrexMessage, AcceptResponse {

    public static TrexMessage readFrom(DataInputStream dis) {
        throw new AssertionError("Not implemented");
    }

    @Override
    public void writeTo(DataOutputStream dos) {
        throw new AssertionError("Not implemented");
    }

    @Override
    public Identifier highestCommitted() {
        return progress.highestCommitted();
    }
}
