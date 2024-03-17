package com.github.trex_paxos;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record BallotNumber(int counter, int nodeIdentifier) implements Comparable<BallotNumber> {
    public static final BallotNumber EMPTY = new BallotNumber(0, 0);

    @Override
    public int compareTo(BallotNumber that) {
        if (this == that) {
            return 0;
        }
        if (this.counter > that.counter) {
            return 1;
        } else if (this.counter < that.counter) {
            return -1;
        } else {
            return Integer.compare(this.nodeIdentifier, that.nodeIdentifier);
        }
    }

    @Override
    public String toString() {
        return String.format("N(c=%d,n=%d)", counter, nodeIdentifier);
    }

    public void writeTo(DataOutputStream daos) throws IOException {
        daos.writeInt(counter);
        daos.writeInt(nodeIdentifier);
    }

    public static BallotNumber readFrom(DataInputStream dataInputStream) throws IOException {
        return new BallotNumber(dataInputStream.readInt(), dataInputStream.readInt());
    }
}