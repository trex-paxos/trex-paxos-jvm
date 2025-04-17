// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import com.github.trex_paxos.msg.AcceptResponse;
import com.github.trex_paxos.msg.PrepareResponse;

import java.util.Set;
import java.util.stream.Collectors;

/// This is the majority strategy from the paper Paxos Made Simple.
public class SimpleMajority implements QuorumStrategy {
  final int clusterSize;
  final int quorum;

  public SimpleMajority(int clusterSize) {
    if (clusterSize < 2) {
      throw new IllegalArgumentException("clusterSize must be at least 2");
    }
    this.clusterSize = clusterSize;
    this.quorum = (int) Math.floor((clusterSize / 2.0) + 1);
  }

  @Override
  public QuorumOutcome assessPromises(long logIndex, Set<PrepareResponse.Vote> promises) {
    return countVotes(quorum, promises.stream().map(PrepareResponse.Vote::vote).collect(Collectors.toList()));
  }

  @Override
  public QuorumOutcome assessAccepts(long logIndex, Set<AcceptResponse.Vote> accepts) {
    return countVotes(quorum, accepts.stream().map(AcceptResponse.Vote::vote).collect(Collectors.toList()));
  }
}
