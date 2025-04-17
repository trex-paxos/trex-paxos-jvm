// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
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
