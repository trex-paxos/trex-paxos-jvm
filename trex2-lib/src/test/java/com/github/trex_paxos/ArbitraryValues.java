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
    EMPTY,              // No value at slot
    MATCHING_NUMBER,    // Has `accept` with matching ballot number
    DIFFERENT_NUMBER   // Has `accept` with different ballot number
  }

  // Catchup alignment state
  enum CatchupAlignmentState {
    CORRECT,   // Accepts are processed
    TOO_LOW,   // Some expected accepts are not processed
    TOO_HIGH   // No accepts are processed
  }
}
