package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record Prepare(Identifier id) implements PaxosMessage{

    public static Prepare readFrom(DataInputStream dataInputStream) throws IOException {
       return new Prepare(Identifier.readFrom(dataInputStream));
    }

    public void writeTo(DataOutputStream dataOutputStream) throws IOException {
        id.writeTo(dataOutputStream);
    }
}
