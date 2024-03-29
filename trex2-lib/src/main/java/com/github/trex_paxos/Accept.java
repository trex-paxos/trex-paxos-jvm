package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record Accept(Identifier id, AbstractCommand command) implements PaxosMessage, JournalRecord {

    final static byte NOOP = 1;
    final static byte COMMAND = 2;

    public void writeTo(DataOutputStream dataStream) throws IOException {
        id.writeTo(dataStream);
        if (command instanceof NoOperation) {
            dataStream.writeByte(NOOP);
        } else {
            dataStream.writeByte(COMMAND);
            command.writeTo(dataStream);
        }
    }

    public static Accept readFrom(DataInputStream dataInputStream) throws IOException {
        Identifier id = Identifier.readFrom(dataInputStream);
        byte type = dataInputStream.readByte();
        if( type == NOOP )
            return new Accept(id, NoOperation.NOOP);
        else
            return new Accept(id, Command.readFrom(dataInputStream));
    }

}
