package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;

public record Commit(Identifier identifier) implements TrexMessage {

    public static Commit readFrom(DataInputStream dis) {
        throw new AssertionError("Not implemented");
    }

    public void writeTo(DataOutputStream dos) {
        throw new AssertionError("Not implemented");
    }
}
