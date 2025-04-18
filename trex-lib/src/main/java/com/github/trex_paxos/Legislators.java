// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import java.util.Set;
import java.util.stream.Collectors;

/// In the original Part-time Parliament paxos paper, the legislators are those who vote in ballots.
/// In this context it is the set of nodes that can vote in a consensus round. We allow for voting
/// weights to implement Flexible Paxos and UPaxos. The voting weights are used to determine the quorum size.
/// The cluster can be reconfigured by changing the voting weights. Nodes with a zero weight are pure leaders.
public record Legislators(Set<VotingWeight> weights) {
  public Legislators {
    weights = Set.copyOf(weights); // Defensive copy
  }

  public static Legislators of(VotingWeight... votingWeights) {
    return new Legislators(Set.of(votingWeights));
  }

  public int totalWeight() {
    return weights.stream().mapToInt(VotingWeight::weight).sum();
  }

  public int quorum() {
    return totalWeight() / 2 + 1;
  }
  public Set<NodeId> otherNodes(NodeId self) {
    return weights.stream()
        .map(VotingWeight::nodeId)
        .filter(nodeId -> !nodeId.equals(self))
        .collect(Collectors.toSet());
  }

  public Iterable<NodeId> members() {
    return weights.stream()
        .map(VotingWeight::nodeId)
        .collect(Collectors.toSet());
  }
}
