package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;

public record AcceptAck(Identifier requestId, int from, Progress progress) implements PaxosMessage {

    public static PaxosMessage readFrom(DataInputStream dis) {
        throw new AssertionError("Not implemented");
    }

    @Override
    public void writeTo(DataOutputStream dos) {
        throw new AssertionError("Not implemented");
    }
}
