package com.github.trex_paxos;

import java.util.Set;

/**
 * The interface to provide a strategy for determining whether a quorum has been reached. UPaxos Quorums requires an understanding
 * of the current cluster configuration. That will be different at each log index. FPaxos can do the even node gambit. This means that
 * there can be different rules for determining overlapping quorums at different parts of the protocol.
 */
public interface QuorumStrategy {
  QuorumOutcome assessPromises(long logIndex, Set<Vote> promises);

  QuorumOutcome assessAccepts(long logIndex, Set<Vote> accepts);

}
