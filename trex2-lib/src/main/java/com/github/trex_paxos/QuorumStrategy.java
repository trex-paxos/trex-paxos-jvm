package com.github.trex_paxos;

/**
  The interface to provide a strategy for determining whether a quorum has been reached. This requires an understanding
 of the current cluster configuration, so it is not part of the core library.
 */
public interface QuorumStrategy {
  QuorumOutcome assessPromises(Iterable<Vote> promises);

  QuorumOutcome assessAccepts(Iterable<Vote> accepts);
}
