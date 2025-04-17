// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

/// We can optionally use voting weights to evaluate quoums.
/// FPaxos (Flexible Paxos) and UPaxos (Unbounded Paxos) use 
/// voting weights. 
public record VotingWeight(NodeId nodeId, int weight) {
    public VotingWeight {
        if (weight < 0) throw new IllegalArgumentException("Voting weight must be non-negative");
    }
    public VotingWeight(short id, int weight) {
        this(new NodeId(id), weight);
    }
}
