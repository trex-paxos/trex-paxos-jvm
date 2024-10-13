package com.github.trex_paxos;

/// The roles used by nodes in the paxos algorithm.
public enum TrexRole {
  /// A follower is a node that is not currently leading the paxos algorithm. We may time out on a follower and attempt to become a leader.
  FOLLOW,
  /// If we are wanting to lead we first run rounds of paxos over all known slots to fix the values sent by any prior leader.
  RECOVER,
  /// Only after we have recovered all slots will we become a leader and start proposing new commands.
  LEAD
}
