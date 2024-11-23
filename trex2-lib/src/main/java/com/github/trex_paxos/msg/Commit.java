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

/// A leader sends out a Commit when it learns of a new fixed log index. It will also heartbeat this message to keep
/// the followers from timing out. If a node was isolated and rejoins it will learn that it has missed out on some
/// log indexes and will request a Catchup.
///
/// @param from              The node identifier of the leader.
/// @param number            The ballot number of the accepted log entry. The follower must request retransmission if
///                          it does not have the correct accept.
/// @param committedLogIndex The highest contiguous log index that the leader has learnt to have been fixed and so
///                          committed.
public record Commit(
    byte from,
    BallotNumber number,
    long committedLogIndex
) implements TrexMessage, BroadcastMessage {

}
