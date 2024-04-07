package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record Accept(long logIndex, BallotNumber number,
                     AbstractCommand command) implements TrexMessage, JournalRecord {

    final static byte NOOP = 1;
    final static byte COMMAND = 2;

    public void writeTo(DataOutputStream dataStream) throws IOException {
      dataStream.writeLong(logIndex);
      number.writeTo(dataStream);
        if (command instanceof NoOperation) {
            dataStream.writeByte(NOOP);
        } else {
            dataStream.writeByte(COMMAND);
            command.writeTo(dataStream);
        }
    }

    public static Accept readFrom(DataInputStream dataInputStream) throws IOException {
      final long logIndex = dataInputStream.readLong();
      final BallotNumber number = BallotNumber.readFrom(dataInputStream);
        byte type = dataInputStream.readByte();
        if( type == NOOP )
          return new Accept(logIndex, number, NoOperation.NOOP);
        else
          return new Accept(logIndex, number, Command.readFrom(dataInputStream));
    }

    public int compareTo(Accept accept) {
      if (logIndex < accept.logIndex) {
        return -1;
      } else if (logIndex > accept.logIndex) {
        return 1;
      } else {
        return number.compareTo(accept.number);
      }
    }
}
