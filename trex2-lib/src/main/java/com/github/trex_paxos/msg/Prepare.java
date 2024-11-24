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

/// The Prepare message is the first message in the Paxos protocol named in the paper Paxos Made Simple by Leslie Lamport.
/// Note that the papers very clearly states:
///
/// > A newly chosen leader executes phase 1 for infinitely many instances
/// of the consensus algorithm ... Using the same proposal number for
/// all instances, it can do this by sending a single reasonably short message
/// to the other servers.
///
/// @param from     The node identifier of the proposer used to route the message and self-accept.
/// @param logIndex The log index slot in the log of total ordering of fixed commands.
/// @param number   The ballot number of the proposer which will be the term of the node attempting to recover or lead.
public record Prepare(
    byte from,
    long logIndex,
    BallotNumber number
) implements TrexMessage, BroadcastMessage, PaxosMessage {
}
