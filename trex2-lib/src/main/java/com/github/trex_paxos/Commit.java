package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;

// TOTO what if this was not a PaxosMessage?
public record Commit(Identifier identifier, long heartbeat) implements TrexMessage {

    public static Commit readFrom(DataInputStream dis) {
        throw new AssertionError("Not implemented");
    }

    public void writeTo(DataOutputStream dos) {
        throw new AssertionError("Not implemented");
    }
}
