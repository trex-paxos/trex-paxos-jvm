package com.github.trex_paxos;

import java.util.Set;

/**
 * The interface to provide a strategy for determining whether a quorum has been reached. This requires an understanding
 * of the current cluster configuration, so it is not part of the core library. For UPaxos and FPaxos their are different
 * rules for determining overlapping quorums at different parts of the protocol. Hence we have different methods for assessing
 * a legal quorum for promises and accepts.
 */
public interface QuorumStrategy {
  QuorumOutcome assessPromises(long logIndex, Set<Vote> promises);

  QuorumOutcome assessAccepts(long logIndex, Set<Vote> accepts);
}
