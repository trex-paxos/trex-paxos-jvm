package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record Identifier(byte from, BallotNumber number, long logIndex) implements Comparable<Identifier> {

    public static final Identifier EMPTY = new Identifier((byte) 0, BallotNumber.EMPTY, 0);
    public void writeTo(DataOutputStream daos) throws IOException {
            daos.writeByte(from);
            number.writeTo(daos);
            daos.writeLong(logIndex); // Convert long to int before writing
    }

    @Override
    public String toString() {
        return String.format("I(f=%d,n=%s,s=%d)", from, number, logIndex);
    }
    public static Identifier readFrom(DataInputStream dataInputStream) throws IOException {
        return new Identifier(dataInputStream.readByte(),
        BallotNumber.readFrom(dataInputStream), dataInputStream.readLong());
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


