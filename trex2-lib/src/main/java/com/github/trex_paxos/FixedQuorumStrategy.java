package com.github.trex_paxos;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class FixedQuorumStrategy implements QuorumStrategy {
  final int quorumSize;
  final int majority;

  public FixedQuorumStrategy(int quorumSize) {
    this.quorumSize = quorumSize;
    this.majority = (int) Math.floor((quorumSize / 2.0) + 1);
  }

  QuorumOutcome simple(Set<Vote> votes) {
    Map<Boolean, List<Vote>> voteMap = votes.stream().collect(Collectors.partitioningBy(Vote::vote));
    if (voteMap.get(true).size() >= majority)
      return QuorumOutcome.WIN;
    else if (voteMap.get(false).size() >= majority)
      return QuorumOutcome.LOSE;
    else
      return QuorumOutcome.WAIT;
  }

  @Override
  public QuorumOutcome assessPromises(long logIndex, Set<Vote> promises) {
    return simple(promises);
  }

  @Override
  public QuorumOutcome assessAccepts(long logIndex, Set<Vote> accepts) {
    return simple(accepts);
  }

}
