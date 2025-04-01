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
