package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record BallotNumber(int counter, byte nodeIdentifier) implements Comparable<BallotNumber> {
  public static final BallotNumber MIN = new BallotNumber(Integer.MIN_VALUE, Byte.MIN_VALUE);

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
        daos.writeByte(nodeIdentifier);
    }

    public static BallotNumber readFrom(DataInputStream dataInputStream) throws IOException {
        return new BallotNumber(dataInputStream.readInt(), dataInputStream.readByte());
    }

    public Boolean lessThan(BallotNumber ballotNumber) {
        return this.compareTo(ballotNumber) < 0;
    }

    public Boolean greaterThan(BallotNumber ballotNumber) {
        return this.compareTo(ballotNumber) > 0;
    }

    public boolean lessThanOrEqualTo(BallotNumber number) {
        return this.compareTo(number) <= 0;
    }
}
