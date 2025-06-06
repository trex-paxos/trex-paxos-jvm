// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import com.github.trex_paxos.msg.AcceptResponse;
import com.github.trex_paxos.msg.PrepareResponse;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/// The interface to provide a strategy for determining whether a quorum has been reached.
/// TheFPaxos paper [Flexible Paxos: Quorum intersection revisited](https://arxiv.org/pdf/1608.06696v1) and the UPaxos
/// paper [Unbounded Pipelining in Dynamically Reconfigurable Paxos Clusters](http://tessanddave.com/paxos-reconf-latest.pdf)
/// show that we can be more flexible than countVotes majorities. The papers explain that
/// what we need is that any two quorums must overlap in at least one node. This is trivially the case for when we change
/// voting weights by one.
///
/// We can use different quorum strategies for the prepare and accept phases as long as they overlap. The even node gambit
/// is:
///
/// - Use four servers that are two pairs in two resilience zones `{A1, A2, B1, B2}`.
/// - Set the `prepare` quorum size to be 3
/// - Set the `accept` quorum size to be 2
///
/// If the link between the resilience zones fails then the cluster can still make progress. The leader only needs a
/// single response to know a value is fixed. Yet a split brain cannot occur as the leader takeover protocol needs
/// three out of four nodes to vote.
public interface QuorumStrategy {
  QuorumOutcome assessPromises(long logIndex, Set<PrepareResponse.Vote> promises);

  QuorumOutcome assessAccepts(long logIndex, Set<AcceptResponse.Vote> accepts);

  enum QuorumOutcome {
    WIN, LOSE, WAIT
  }

  default QuorumOutcome countVotes(int quorum, List<Boolean> votes) {
    Map<Boolean, List<Boolean>> voteMap = votes.stream().collect(Collectors.partitioningBy(v -> v));
    if (voteMap.get(true).size() >= quorum)
      return QuorumOutcome.WIN;
    else if (voteMap.get(false).size() >= quorum)
      return QuorumOutcome.LOSE;
    else
      return QuorumOutcome.WAIT;
  }
}
