package com.github.trex_paxos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record Progress(BallotNumber highestPromised, Identifier highestCommitted) {
    public static final Progress EMPTY = new Progress(BallotNumber.EMPTY, Identifier.EMPTY);

    public void writeTo(DataOutputStream dos) throws IOException {
        highestPromised.writeTo(dos);
        highestCommitted.writeTo(dos);
    }
    public static Progress readFrom(DataInputStream dis) throws IOException {
        return new Progress(BallotNumber.readFrom(dis), Identifier.readFrom(dis));
    }
    @Override
    public String toString() {
        return String.format("P(p=%s,c=%s)", highestPromised, highestCommitted);
    }


}
