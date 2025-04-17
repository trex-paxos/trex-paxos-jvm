// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.msg;

import com.github.trex_paxos.BallotNumber;
import com.github.trex_paxos.SlotTerm;

/// The Prepare message is the first message in the Paxos protocol named in the paper Paxos Made Simple by Leslie Lamport.
///
/// @param from     The node identifier of the proposer used to route the message and self-accept.
/// @param slotTerm  This is the `{S,N}` where a successful leader will select the highest `V`.
public record Prepare(
  short from,
    SlotTerm slotTerm
) implements TrexMessage, BroadcastMessage, PaxosMessage {
  public Prepare(short from, long logIndex, BallotNumber number) {
    this(from, new SlotTerm(logIndex, number));
  }

  public long slot() {
    return slotTerm().logIndex();
  }

  public BallotNumber number() {
    return slotTerm().number();
  }

  public short era() {
    return this.slotTerm.number().era();
  }
}
