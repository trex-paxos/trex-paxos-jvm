// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

/// A ballot number is the proposal number used in the Paxos algorithm. Here we are using eight bytes.Nodes make
/// promises to not accept any mny protocol messages with a number less than the one they have promised. The [BallotNumber#compareTo(com.github.trex_paxos.BallotNumber)]
///
/// See {@link Progress#promise(BallotNumber)} and {@link com.github.trex_paxos.msg.PaxosMessage}.
///
/// If you can shut down the cluster to reconfigure it then you do not need to increment the era. If you want to
/// reconfigure the cluster while noes are running the `era` should be incremented.
///
/// @param era The cluster configuration number. This is incremented when the cluster configuration changes.
/// @param counter The counter is incremented when a node wishes to take over the leadership and run the leader takeover protocol.
/// @param nodeIdentifier The node identifier is used to break ties and to ensure no node in a cluster generates the same number.
public record BallotNumber(short era, int counter, short nodeIdentifier) implements Comparable<BallotNumber> {

  public static final BallotNumber MIN = new BallotNumber(Short.MIN_VALUE, Integer.MIN_VALUE, Short.MIN_VALUE);

  /// The ballot number is a 64-bit number. The first 16 bits are the era, the next 32 bits are the counter and the last 16 bits are the node identifier.
  /// The comparison is done in the following order:
  /// 1. Compare the era which is incremented for cluster reconfigurations. This will lock out message that have an obsolete cluster configuration.
  /// 2. Compare the counter which is incremented when a follower times out to run the leader takeover protocol.
  /// 3. Finally compare by node identifier which is a tie-breaker which ensures that all nodes in a cluster have unique ballot numbers.
  @Override
  public int compareTo(BallotNumber that) {
    // First compare by era which is incremented for cluster reconfigurations. This will lock out message that have an obsolete cluster configuration.
    int eraComparison = Short.compare(this.era, that.era);
    if (eraComparison != 0) {
      return eraComparison;
    }
    // Then compare by counter which is incremented when a follower times out and is running the leader takeover protocol.
    int counterComparison = Integer.compare(this.counter, that.counter);
    if (counterComparison != 0) {
      return counterComparison;
    }
    // Finally compare by node identifier which is a tie-breaker which ensures that all nodes in a cluster have unique ballot numbers.
    return Short.compare(this.nodeIdentifier, that.nodeIdentifier);
  }

  ///  increment era
  ///  @return a new ballot number with the era incremented by one.
  @SuppressWarnings("unused") // TODO use this in Upaxos reconfigurations
  public BallotNumber incrementEra() {
    return new BallotNumber((short) (era + 1), counter, nodeIdentifier);
  }

  @Override
  public String toString() {
    return String.format("N(e=%d,c=%d,n=%d)", era, counter, nodeIdentifier);
  }

  public Boolean lessThan(BallotNumber ballotNumber) {
    return this.compareTo(ballotNumber) < 0;
  }

  public Boolean greaterThan(BallotNumber ballotNumber) {
    return this.compareTo(ballotNumber) > 0;
  }

  public boolean lessThanOrEqualTo(BallotNumber number) {
    return this.compareTo(number) <= 0;
  }
}
