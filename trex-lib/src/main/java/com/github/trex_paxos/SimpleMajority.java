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
