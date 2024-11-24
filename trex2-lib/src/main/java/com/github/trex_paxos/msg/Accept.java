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

/// The Accept message is the second message in the Paxos protocol named in the paper Paxos Made Simple by Leslie Lamport.
///
/// @param from     The node identifier of the proposer used to route the message and self-accept.
/// @param logIndex The log index slot in the log of total ordering of fixed commands.
/// @param number   The ballot number of the proposer which will be the term of the node attempting to recover or lead.
/// @param command  The command to be accepted by the acceptor. This may be a NOOP or a client command.
public record Accept(byte from,
                     long logIndex,
                     BallotNumber number,
                     AbstractCommand command) implements TrexMessage, BroadcastMessage, PaxosMessage {

  public int compareTo(Accept accept) {
      if (logIndex < accept.logIndex) {
        return -1;
      } else if (logIndex > accept.logIndex) {
        return 1;
      } else {
        return number.compareTo(accept.number);
      }
    }
}
