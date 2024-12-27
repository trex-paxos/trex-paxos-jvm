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

import com.github.trex_paxos.AbstractCommand;
import com.github.trex_paxos.BallotNumber;
import com.github.trex_paxos.SlotTerm;

/// The Accept message is the second message in the Paxos protocol named in the paper Paxos Made Simple by Leslie Lamport.
///
/// @param from see {@link TrexMessage}
/// @param slotTerm  This is the `{S,N}` that identifies the fixed `V`.
/// @param command  The command to be accepted by the acceptor. This may be a NOOP or a client command.
public record Accept(byte from,
                     SlotTerm slotTerm,
                     AbstractCommand command) implements TrexMessage, BroadcastMessage, PaxosMessage {

  public Accept(byte from, long logIndex, BallotNumber number, AbstractCommand command) {
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
}
