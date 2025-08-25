// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

public sealed interface ArbitraryValues permits ArbitraryValues.None {
  enum None implements ArbitraryValues {
    INSTANCE
  }
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
