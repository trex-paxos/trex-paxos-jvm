package com.github.trex_paxos;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ThreeNodeQuorum implements QuorumStrategy {

  @Override
  public QuorumOutcome assessPromises(long logIndex, Set<Vote> promises) {
    return simpleMajority(promises);
  }

  private static QuorumOutcome simpleMajority(Set<Vote> promises) {
    if (promises.size() < 2) {
      return QuorumOutcome.WAIT;
    }
    Map<Boolean, List<Vote>> count = promises.stream().collect(Collectors.partitioningBy(Vote::vote));
    int yesVotes = count.get(true).size();
    int noVotes = count.get(false).size();
    return (yesVotes > noVotes) ? QuorumOutcome.WIN : QuorumOutcome.LOSE;
  }

  @Override
  public QuorumOutcome assessAccepts(long logIndex, Set<Vote> accepts) {
    return simpleMajority(accepts);
  }
}
