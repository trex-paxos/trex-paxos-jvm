package com.github.trex_paxos;

/// Each leader must increment the counter in the ballot number each time it attempts to lead. This means that the
/// pairing of a slot with a ballot number is unique. BallotNumbers are also unique to a node as it encodes the nodeIdentifier.
/// Nodes will not reuse the counter for different slots. This means a slot term is associated with a specific value
/// chosen a node attempting to lead. During the leader takeover process the new leader will increment their counter
/// and may choose a value that is returned from a prepare response from the previous leader. This means that
/// the same value may be at the same slot logIndex on different nodes associated with a different ballot number.
public record SlotTerm(long logIndex, BallotNumber number) implements Comparable<SlotTerm> {
  public SlotTerm {
    if (logIndex < 0) {
      throw new IllegalArgumentException("logIndex must be >= 0");
    }
    if (number == null) {
      throw new IllegalArgumentException("number must not be null");
    }
  }

  @Override
  public int compareTo(SlotTerm o) {
    return Long.compare(logIndex, o.logIndex);
  }
}
