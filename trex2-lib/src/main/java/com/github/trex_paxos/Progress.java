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

import com.github.trex_paxos.msg.BallotNumber;

/**
 * Progress is a record of the highest ballot number promised or seen on an accepted message which must be crash durable
 * (e.g. forced to disk) for Paxos to be correct. We also store the highest fixed index and the highest accepted index.
 *
 * @param nodeIdentifier        The current node identifier. This is here to ensure we do not accidentally use the wrong state.
 * @param highestPromised       The highest ballot number promised or seen on an accepted message.
 * @param highestFixedIndex The highest log index that has been learnt to have been fixed and so not fixed.
 */
public record Progress(
    byte nodeIdentifier,
    BallotNumber highestPromised,
    long highestFixedIndex
) {

  /**
   * When an application initializes an empty journal it has to have a NIL value.
   *
   * @param nodeIdentifier The current node identifier.
   */
  public Progress(byte nodeIdentifier) {
    this(nodeIdentifier, BallotNumber.MIN, 0);
  }

  // Java may get `with` so that we can retire this method.
  public Progress withHighestFixed(long fixedLogIndex) {
    return new Progress(nodeIdentifier, highestPromised, fixedLogIndex);
  }

  // Java may get `with` so that we can retire this method.
  public Progress withHighestPromised(BallotNumber p) {
    return new Progress(nodeIdentifier, p, highestFixedIndex);
  }

  @Override
  public String toString() {
    return "P(p={" + highestPromised + "},c={" + highestFixedIndex + "}";
  }
}
