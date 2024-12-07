/*
 * Copyright 2024 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.trex_paxos;

import com.github.trex_paxos.msg.AcceptResponse;
import com.github.trex_paxos.msg.PrepareResponse;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/// The even node gambit optimizes for even-numbered clusters by requiring exactly
/// half the nodes plus one specific node to form a quorum. This reduces message
/// overhead while maintaining safety. For a cluster size of 4, it requires only
/// 2 specific nodes rather than 3 nodes as in simple majority.
public class EvenNodeGambit implements QuorumStrategy {
  final int clusterSize;
  final int quorumSize;
  final byte primaryNode;

  public EvenNodeGambit(int clusterSize, byte primaryNode) {
    if (clusterSize % 2 != 0) {
      throw new IllegalArgumentException("EvenNodeGambit requires even cluster size");
    }
    this.clusterSize = clusterSize;
    this.quorumSize = (clusterSize / 2);
    this.primaryNode = primaryNode;
  }

  private QuorumOutcome assessVotes(List<Byte> nodeIds, List<Boolean> votes) {
    // if we only have one node we cannot win
    if (votes.size() < quorumSize) {
      return QuorumOutcome.WAIT;
    }

    // Map to weights in one pass (+/-2 for primary, +/-1 for others)
    List<Integer> weights = IntStream.range(0, votes.size())
        .mapToObj(i -> {
          boolean vote = votes.get(i);
          byte nodeId = nodeIds.get(i);
          return (nodeId == primaryNode ? 2 : 1) * (vote ? 1 : -1);
        })
        .toList();

    int sum = weights.stream().mapToInt(Integer::intValue).sum();

    // if it is not a split decision return the outcome
    if (sum != 0) {
      return sum > 0 ? QuorumOutcome.WIN : QuorumOutcome.LOSE;
    }

    // On zero sum, check if any weight has magnitude 2 (primary node) and is positive
    return weights.stream().anyMatch(w -> w == 2)
        ? QuorumOutcome.WIN
        : QuorumOutcome.LOSE;
  }

  @Override
  public QuorumOutcome assessPromises(long logIndex, Set<PrepareResponse.Vote> promises) {
    List<Byte> nodeIds = promises.stream()
        .map(PrepareResponse.Vote::to)
        .collect(Collectors.toList());
    List<Boolean> votes = promises.stream()
        .map(PrepareResponse.Vote::vote)
        .collect(Collectors.toList());
    return assessVotes(nodeIds, votes);
  }

  @Override
  public QuorumOutcome assessAccepts(long logIndex, Set<AcceptResponse.Vote> accepts) {
    List<Byte> nodeIds = accepts.stream()
        .map(AcceptResponse.Vote::to)
        .collect(Collectors.toList());
    List<Boolean> votes = accepts.stream()
        .map(AcceptResponse.Vote::vote)
        .collect(Collectors.toList());
    return assessVotes(nodeIds, votes);
  }
}
