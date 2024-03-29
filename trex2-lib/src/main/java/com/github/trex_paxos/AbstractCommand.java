package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public sealed interface AbstractCommand extends PaxosMessage permits NoOperation, Command {

    void writeTo(DataOutputStream dataStream) throws IOException;

    static Command readFrom(DataInputStream dataInputStream) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'readFrom'");
    }

}
