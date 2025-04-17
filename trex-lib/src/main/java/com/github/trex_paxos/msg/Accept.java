// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.msg;

import com.github.trex_paxos.AbstractCommand;
import com.github.trex_paxos.BallotNumber;
import com.github.trex_paxos.SlotTerm;

/// The Accept message is the second message in the Paxos protocol named in the paper Paxos Made Simple by Leslie Lamport.
///
/// @param from see {@link TrexMessage}
/// @param slotTerm  This is the `{S,N}` that identifies the fixed `V`.
/// @param command  The command to be accepted by the acceptor. This may be a NOOP or a true Command.
public record Accept(short from,
                     SlotTerm slotTerm,
                     AbstractCommand command) implements TrexMessage, BroadcastMessage, PaxosMessage {

  public Accept(short from, long logIndex, BallotNumber number, AbstractCommand command) {
    this(from, new SlotTerm(logIndex, number), command);
  }

  public int compareNumbers(Accept accept) {
    return slotTerm.number().compareTo(accept.slotTerm.number());
  }

  public Long slot() {
    return slotTerm().logIndex();
  }

  public BallotNumber number() {
    return slotTerm().number();
  }

  public short era() {
    return slotTerm.era();
  }
}
