package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record Accept(Identifier id) implements PaxosMessage, JournalRecord {

    public void writeTo(DataOutputStream dataStream) throws IOException {
        id.writeTo(dataStream);
    }

    public static Accept readFrom(DataInputStream dataInputStream) throws IOException {
        return new Accept(Identifier.readFrom(dataInputStream));
    }

}
