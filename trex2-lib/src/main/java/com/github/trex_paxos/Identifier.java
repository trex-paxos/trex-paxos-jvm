package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

// FIXME why is from needed as it is encoded into the BallotNumber and nack/ack say who it is from
public record Identifier(BallotNumber number, long logIndex) implements Comparable<Identifier> {

    public static final Identifier EMPTY = new Identifier(BallotNumber.EMPTY, 0);
    public void writeTo(DataOutputStream daos) throws IOException {
            number.writeTo(daos);
            daos.writeLong(logIndex); // Convert long to int before writing
    }

    @Override
    public String toString() {
        return String.format("I(n=%s,s=%d)", number, logIndex);
    }
    public static Identifier readFrom(DataInputStream dataInputStream) throws IOException {
        return new Identifier(BallotNumber.readFrom(dataInputStream), dataInputStream.readLong());
    }

    @Override
    public int compareTo(Identifier o) {
        if (this.logIndex() == o.logIndex()) {
            return this.number().compareTo(o.number());
        } else if (this.logIndex() >= o.logIndex()) {
            return 1;
        } else {
            return -1;
        }
    }
}


