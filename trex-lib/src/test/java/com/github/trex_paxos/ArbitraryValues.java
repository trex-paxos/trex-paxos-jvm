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
package com.github.trex_paxos;

public class ArbitraryValues {
  /// Current TrexRole of the node under test when receiving messages
  enum RoleState {FOLLOW, RECOVER, LEAD}

  /// Relationship between node identifier of node under test compared to message node identifier
  enum NodeIdentifierRelation {LESS, EQUAL, GREATER}

  /// Relationship between promise counter of node under test compared to message promise counter
  enum PromiseCounterRelation {LESS, EQUAL, GREATER}

  /// Relationship between fixed slot index of node under test compared to message slot index
  enum FixedSlotRelation {LESS, EQUAL, GREATER}

  /// Types of command values that can exist in the Accept message
  enum Value {NULL, NOOP, COMMAND}

  /// State of the journal at the fixed slot
  enum JournalState {
    EMPTY,              // No id at slot
    MATCHING_NUMBER,    // Has `accept` with matching ballot number
    DIFFERENT_NUMBER   // Has `accept` with different ballot number
  }

  // Catchup alignment state
  enum CatchupAlignmentState {
    CORRECT,   // Accepts are processed
    TOO_LOW,   // Some expected accepts are not processed
    TOO_HIGH   // No accepts are processed
  }

  /// Outcome of the vote collection for either an `accept` or a `prepare`
  enum VoteOutcome {
    WIN,    // Will achieve majority with this vote
    LOSE,    // Will not achieve majority
    WAIT    // Will not achieve majority yet
  }

  /// Whether the leader is seeing out of order responses due to lost messages
  enum OutOfOrder {
    FALSE,  // Accepts are contiguous
    TRUE    // Accepts have gaps
  }
}
