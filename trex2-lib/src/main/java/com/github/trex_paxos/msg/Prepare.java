package com.github.trex_paxos.msg;

public record Prepare(
    byte from,
    long logIndex,
    BallotNumber number
) implements TrexMessage, BroadcastMessage {
}
