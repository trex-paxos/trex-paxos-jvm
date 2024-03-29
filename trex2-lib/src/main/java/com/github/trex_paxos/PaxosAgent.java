package com.github.trex_paxos;

public record PaxosAgent(byte nodeUniqueId, PaxosRole role, PaxosData data, QuorumStrategy quorumStrategy) {
    public Prepare minPrepare() {
        return new Prepare(new Identifier(nodeUniqueId, new BallotNumber(0, (byte) 0), 0));
    }
}
