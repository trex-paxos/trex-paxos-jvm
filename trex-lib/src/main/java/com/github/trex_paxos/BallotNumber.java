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
package com.github.trex_paxos;
/// A ballot number is the proposal number used in the Paxos algorithm. Here we are using eight bytes.
/// In order to do UPaxos configurations we have an optional era field in the most significant byte.
/// The counter is used to order proposals within an era. The nodeIdentifier is used to break ties so that we can have a total order.
/// The counter is incremented as an integer when a node wishes to become a leader.
public record BallotNumber(short era, int counter, short nodeIdentifier) implements Comparable<BallotNumber> {

  public BallotNumber(int counter, short nodeIdentifier) {
    this((short) 0, counter, nodeIdentifier);
  }

  public static final BallotNumber MIN = new BallotNumber(Integer.MIN_VALUE, Short.MIN_VALUE);

@Override
public int compareTo(BallotNumber that) {
    // First compare by era
    int eraComparison = Short.compare(this.era, that.era);
    if (eraComparison != 0) {
        return eraComparison;
    }
    // Then compare by counter
    int counterComparison = Integer.compare(this.counter, that.counter);
    if (counterComparison != 0) {
        return counterComparison;
    }
    // Finally compare by node identifier
    return Short.compare(this.nodeIdentifier, that.nodeIdentifier);
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
