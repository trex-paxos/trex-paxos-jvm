/*
 * Copyright 2024 - 2025 Simon Massey
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

/// A leader sends out a Fixed when it learns of a new fixed log index. It will also heartbeat this message to keep
/// the followers from timing out. This message type is one of the three [LearningMessage] types where the progress
/// of the node in terms of fixing slots and making an up-call to the host is called.
///
/// @param from          see {@link TrexMessage}
/// @param slotTerm  This is the `{S,N}` that identifies the fixed `V`. `
public record Fixed(
    short from,
    SlotTerm slotTerm
) implements TrexMessage, BroadcastMessage, LearningMessage {
  public Fixed(short from, long logIndex, BallotNumber number) {
    this(from, new SlotTerm(logIndex, number));
  }

  public long slot() {
    return slotTerm().logIndex();
  }

  public Short leader() {
    return from();
  }
}
