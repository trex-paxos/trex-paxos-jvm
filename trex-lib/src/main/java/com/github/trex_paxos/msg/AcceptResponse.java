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

import com.github.trex_paxos.SlotTerm;

import java.util.Objects;

/// An AcceptResponse response back to a {@link Accept} message. We add the highestFixedIndex as more information
/// to cause a leader to abdicate if it is behind. If the leader gets a NO vote it will abdicate.
/// We do not attempt to send any information
/// about promises as we do not want to change our own promise outside the normal prepare/accept flow.
///
/// @param from                  see {@link TrexMessage}
/// @param to                    see {@link DirectMessage}
/// @param era                   the current era of the node.
/// @param vote                  whether wre have voted for or voted against the Prepare message based on our past promises.
/// @param highestFixedIndex additional information about the highest accepted index so that a leader will abdicate if it is behind.
public record AcceptResponse(short from,
                             short to,
                             short era,
                             Vote vote,
                             long highestFixedIndex
) implements TrexMessage, DirectMessage, LearningMessage {
  public AcceptResponse{
    Objects.requireNonNull(vote);
    if (vote.from() != from) {
      throw new IllegalArgumentException("Vote from must be the same as the from field");
    }
    if (vote.to() != to) {
      throw new IllegalArgumentException("Vote to must be the same as the to field");
    }
  }
  public record Vote(
      // spookily intellij says there are no usages of this field, but if I remove it everything breaks
      short from,
      // spookily intellij says there are no usages of this field, but if I remove it everything breaks
      short to,
      SlotTerm slotTerm,
      boolean vote
  ) {
  }
}
