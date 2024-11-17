package com.github.trex_paxos.msg;

/// A ballot number is the proposal number used in the Paxos algorithm. Here we are using five bytes. The most significant
/// are incremented as an integer when a node wishes to become a leader. We encode the
/// nodeIdentifier in the least significant fifth byte. This works as long as we make the nodeIdentifier unique within the cluster
/// at any given configuration. It must also be unique across the overlaps of cluster membership reconfigurations. We can use Paxos itself to
/// ensure this uniqueness.
public record BallotNumber(int counter, byte nodeIdentifier) implements Comparable<BallotNumber> {

  public static final BallotNumber MIN = new BallotNumber(Integer.MIN_VALUE, Byte.MIN_VALUE);

  @Override
  public int compareTo(BallotNumber that) {
    if (this == that) {
      return 0;
    }
    if (this.counter > that.counter) {
      return 1;
    } else if (this.counter < that.counter) {
      return -1;
    } else {
      return Integer.compare(this.nodeIdentifier, that.nodeIdentifier);
    }
  }

  @Override
  public String toString() {
    return String.format("N(c=%d,n=%d)", counter, nodeIdentifier);
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
