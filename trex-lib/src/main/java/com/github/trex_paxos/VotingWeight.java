package com.github.trex_paxos;

public record VotingWeight(NodeId nodeId, int weight) {
    public VotingWeight {
        if (weight < 0) throw new IllegalArgumentException("Voting weight must be non-negative");
    }
    public VotingWeight(short id, int weight) {
        this(new NodeId(id), weight);
    }
}
