package com.github.trex_paxos;

public record Progress(BallotNumber highestPromised, Identifier highestCommitted) {
    public static final Progress EMPTY = new Progress(BallotNumber.EMPTY, Identifier.EMPTY);

    @Override
    public String toString() {
        return String.format("P(p=%s,c=%s)", highestPromised, highestCommitted);
    }
}