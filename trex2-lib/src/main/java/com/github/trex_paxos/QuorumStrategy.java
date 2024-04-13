package com.github.trex_paxos;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The interface to provide a strategy for determining whether a quorum has been reached. UPaxos Quorums requires an understanding
 * of the current cluster configuration. That will be different at each log index. FPaxos can do the even node gambit. This means that
 * there can be different rules for determining overlapping quorums at different parts of the protocol.
 */
public interface QuorumStrategy {
  QuorumOutcome assessPromises(long logIndex, Set<Vote> promises);

  QuorumOutcome assessAccepts(long logIndex, Set<Vote> accepts);

  /**
   * A simple majority quorum strategy suitable for classical three or five node clusters. .
   */
  @SuppressWarnings("unused")
  QuorumStrategy SIMPLE_MAJORITY = new QuorumStrategy() {
    QuorumOutcome simple(Set<Vote> votes) {
      final int majority = (int) Math.floor((votes.size() / 2.0) + 1);
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
  };
}
