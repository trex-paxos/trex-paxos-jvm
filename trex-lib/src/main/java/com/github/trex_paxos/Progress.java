// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

/**
 * Progress is a record of the highest ballot number promised or seen on an accepted message which must be crash durable
 * (e.g. forced to disk) for Paxos to be correct. We also store the highest fixed index and the highest accepted index.
 *
 * @param nodeIdentifier        The current node identifier. This is here to ensure we do not accidentally use the wrong state.
 * @param highestPromised       The highest ballot number promised or seen on an accepted message.
 * @param highestFixedIndex The highest log index that has been learnt to have been fixed and so not fixed.
 */
public record Progress(
  short nodeIdentifier,
    BallotNumber highestPromised,
    long highestFixedIndex
) {

  /**
   * When an application initializes an empty journal it has to have a NIL id.
   *
   * @param nodeIdentifier The current node identifier.
   */
  public Progress(short nodeIdentifier) {
    this(nodeIdentifier, BallotNumber.MIN, 0);
  }

  // Java may get withers so that we can retire this method.
  public Progress withHighestFixed(long fixedLogIndex) {
    return new Progress(nodeIdentifier, highestPromised, fixedLogIndex);
  }

  public Progress promise(BallotNumber ballotNumber) {
    if (ballotNumber.compareTo(highestPromised) > 0) {
      return new Progress(nodeIdentifier, ballotNumber, highestFixedIndex);
    }
    return this;
  }

  @Override
  public String toString() {
    return "P(p={" + highestPromised + "},c={" + highestFixedIndex + "}";
  }

  public short era() {
    return this.highestPromised.era();
  }
}
