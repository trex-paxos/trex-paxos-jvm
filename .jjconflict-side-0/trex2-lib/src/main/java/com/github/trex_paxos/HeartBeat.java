package com.github.trex_paxos;

import java.io.DataOutputStream;
import java.io.IOException;

public record HeartBeat() implements PaxosMessage {
    @Override
    public void writeTo(DataOutputStream dos) throws IOException {
        throw new AssertionError("Not implemented");
    }
}
