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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/// This is a very simple quorum strategy that is typical for three or five node paxos clusters. It can also work for
/// even numbers of nodes yet for various reasons it is not recommended to use odd numbers of nodes. In real world
/// deployments you would want to expand and contract the cluster which requires a more sophisticated strategy such as
/// UPaxos.
public class FixedQuorumStrategy implements QuorumStrategy {
  final int quorumSize;
  final int majority;

  public FixedQuorumStrategy(int quorumSize) {
    this.quorumSize = quorumSize;
    this.majority = (int) Math.floor((quorumSize / 2.0) + 1);
  }

  QuorumOutcome simple(List<Boolean> votes) {
    Map<Boolean, List<Boolean>> voteMap = votes.stream().collect(Collectors.partitioningBy(v -> v));
    if (voteMap.get(true).size() >= majority)
      return QuorumOutcome.WIN;
    else if (voteMap.get(false).size() >= majority)
      return QuorumOutcome.LOSE;
    else
      return QuorumOutcome.WAIT;
  }

  @Override
  public QuorumOutcome assessPromises(long logIndex, Set<PrepareResponse.Vote> promises) {
    return simple(promises.stream().map(PrepareResponse.Vote::vote).collect(Collectors.toList()));
  }

  @Override
  public QuorumOutcome assessAccepts(long logIndex, Set<AcceptResponse.Vote> accepts) {
    return simple(accepts.stream().map(AcceptResponse.Vote::vote).collect(Collectors.toList()));
  }
}
