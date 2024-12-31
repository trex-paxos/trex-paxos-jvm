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

import com.github.trex_paxos.BallotNumber;

/// Catchup is a message sent by a replica to the leader to request retransmission of lost `Accept` messages
/// that have been fixed above the last slot the node has previously learnt to be fixed.
///
/// @param from                 see {@link TrexMessage}
/// @param to                   see {@link DirectMessage}
/// @param highestFixedIndex the highest index that the replica has fixed.
/// @param highestPromised      the highest ballot number that the replica has promised.
public record Catchup(short from,
    short to,
    long highestFixedIndex,
    BallotNumber highestPromised) implements DirectMessage, TrexMessage {

  @Override
  public String toString() {
    return "Catchup{" +
        "from=" + from +
        ", to=" + to +
        ", highestFixedIndex=" + highestFixedIndex +
        ", highestPromised=" + highestPromised +
        '}';
  }
}
