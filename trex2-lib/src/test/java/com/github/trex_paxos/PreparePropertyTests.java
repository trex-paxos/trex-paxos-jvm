package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import net.jqwik.api.*;

import java.util.List;
import java.util.Optional;

/// Property tests that verify the Paxos protocol invariants for prepare messages.
/// Tests cover all combinations of:
/// - Node roles (FOLLOW, RECOVER, LEAD)
/// - Node identifier relationships (LESS, EQUAL, GREATER)
/// - Promise counter relationships (LESS, EQUAL, GREATER)
/// - Fixed slot relationships (LESS, EQUAL, GREATER)
/// - Value types (NULL, NOOP, COMMAND)
public class PreparePropertyTests {

  /// Current TrexRole of the node under test when receiving messages
  enum RoleState {FOLLOW, RECOVER, LEAD}

  /// Relationship between node identifier of node under test compared to message node identifier
  enum NodeIdentifierRelation {LESS, EQUAL, GREATER}

  /// Relationship between promise counter of node under test compared to message promise counter
  enum PromiseCounterRelation {LESS, EQUAL, GREATER}

  /// Relationship between fixed slot index of node under test compared to message slot index
  enum FixedSlotRelation {LESS, EQUAL, GREATER}

  /// Types of command values that can exist in the journal when reading an Accept
  /// NULL - no command exists in the journal
  /// NOOP - a NoOperation.NOOP exists in the journal
  /// COMMAND - a Command("test", "data".getBytes()) exists in the journal
  enum Value {NULL, NOOP, COMMAND}

  /// Test case parameters combining all possible relationships between node under test and message properties
  record TestCase(
      RoleState role,
      NodeIdentifierRelation nodeIdentifierRelation,
      PromiseCounterRelation promiseCounterRelation,
      FixedSlotRelation fixedSlotRelation,
      Value value
  ) {
  }

  /// Three node quorum strategy for testing
  final QuorumStrategy threeNodeQuorum = new FixedQuorumStrategy(3);

  /// Property test that verifies prepare message handling by testing all combinations
  /// of relationships between the node under test and inbound message properties
  @Property(generation = GenerationMode.EXHAUSTIVE)
  void prepareTests(@ForAll("testCases") TestCase testCase) {

    // Set up the identifier of the node
    final byte nodeId = 2;

    // Set up the identifier of the other node relative to the node under test
    final var otherNodeId = switch (testCase.nodeIdentifierRelation) {
      case LESS -> (byte) (nodeId - 1);
      case EQUAL -> nodeId;
      case GREATER -> (byte) (nodeId + 1);
    };

    // Setup ballot number of the node under test
    final var thisCounter = 100;
    final var thisPromise = new BallotNumber(thisCounter, nodeId);

    // Setup log indices
    final long thisFixed = 10;
    // Calculate the log index of the other node based on the relationship
    final long otherIndex = switch (testCase.fixedSlotRelation) {
      case LESS -> thisFixed - 1;
      case EQUAL -> thisFixed;
      case GREATER -> thisFixed + 1;
    };

    // Setup command value
    final var cmd = switch (testCase.value) {
      case NULL -> null;
      case NOOP -> NoOperation.NOOP;
      case COMMAND -> new Command("test", "data".getBytes());
    };

    // Setup journal
    final var journal = new FakeJournal(nodeId, thisPromise, thisFixed) {
      @Override
      public Optional<Accept> readAccept(long logIndex) {
        if (logIndex == otherIndex && cmd != null) {
          return Optional.of(new Accept(nodeId, logIndex, thisPromise, cmd));
        }
        return Optional.empty();
      }
    };

    // Setup node with role with the correct parameters
    final var node = new FakeEngine(nodeId, threeNodeQuorum, journal) {{
      trexNode.role = switch (testCase.role) {
        case FOLLOW -> TrexRole.FOLLOW;
        case RECOVER -> TrexRole.RECOVER;
        case LEAD -> TrexRole.LEAD;
      };
    }};

    // Setup other ballot number of the inbound message
    final var otherNumber = switch (testCase.promiseCounterRelation) {
      case LESS -> new BallotNumber(thisCounter - 1, otherNodeId);
      case EQUAL -> new BallotNumber(thisCounter, otherNodeId);
      case GREATER -> new BallotNumber(thisCounter + 1, otherNodeId);
    };

    // Create prepare message
    final Prepare prepare = new Prepare(otherNodeId, otherIndex, otherNumber);

    // Execute the algorithm for the current scenario
    var result = node.paxos(List.of(prepare));

    // Verify that no invariants of our protocol are violated
    if (result instanceof TrexResult(var messages, var commands)) {
      // No commands should be generated from prepare
      assert commands.isEmpty();

      // Should get either nothing or a single prepare response.
      assert messages.size() <= 1;

      // Verify response
      if (!messages.isEmpty()) {

        var response = (PrepareResponse) messages.getFirst();
        var vote = response.vote();

        // Verify protocol invariants
        if (otherIndex <= thisFixed) {
          // Must reject prepares for fixed slots
          assert !vote.vote();
        } else if (otherNumber.lessThan(thisPromise)) {
          // Must reject lower ballot numbers
          assert !vote.vote();
        } else {
          // Must accept higher ballot numbers
          assert vote.vote();
          assert vote.from() == nodeId;
          assert vote.to() == otherNodeId;
          assert vote.logIndex() == otherIndex;
          assert vote.number().equals(otherNumber);

          // Verify highest accepted value is returned
          if (cmd != null) {
            assert response.journaledAccept().isPresent();
            var accept = response.journaledAccept().get();
            assert accept.command().equals(cmd);
          } else {
            assert response.journaledAccept().isEmpty();
          }
        }
      }

      // Verify role changes
      if (node.trexNode().getRole() != TrexRole.valueOf(testCase.role.name())
          && prepare.logIndex() > thisFixed
          && otherNumber.greaterThan(thisPromise)) {
        assert node.trexNode().getRole() == TrexRole.FOLLOW;
      }
    }
  }

  /// Provides test cases covering all combinations of relationships between
  /// the node under test and the prepare message properties
  @Provide
  Arbitrary<TestCase> testCases() {
    return Combinators.combine(
        Arbitraries.of(RoleState.values()),
        Arbitraries.of(NodeIdentifierRelation.values()),
        Arbitraries.of(PromiseCounterRelation.values()),
        Arbitraries.of(FixedSlotRelation.values()),
        Arbitraries.of(Value.values())
    ).as(TestCase::new);
  }
}
