/*
 * Copyright 2024 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
    if (this.counter == that.counter) {
      return Byte.compare(this.nodeIdentifier, that.nodeIdentifier);
    }
    return Integer.compare(this.counter, that.counter);
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
