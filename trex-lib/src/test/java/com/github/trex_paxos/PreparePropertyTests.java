package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import net.jqwik.api.*;

import java.util.Optional;
import java.util.logging.Level;

/// Property tests that verify the Paxos protocol invariants for prepare messages.
/// Tests cover all combinations of:
/// - Node roles (FOLLOW, RECOVER, LEAD)
/// - Node identifier relationships (LESS, EQUAL, GREATER)
/// - Promise counter relationships (LESS, EQUAL, GREATER)
/// - Fixed slot relationships (LESS, EQUAL, GREATER)
/// - Value types (NULL, NOOP, COMMAND)
public class PreparePropertyTests {

  /// Test case parameters combining all possible relationships between node under test and message properties
  record TestCase(
      ArbitraryValues.RoleState role,
      ArbitraryValues.NodeIdentifierRelation nodeIdentifierRelation,
      ArbitraryValues.PromiseCounterRelation promiseCounterRelation,
      ArbitraryValues.FixedSlotRelation fixedSlotRelation,
      ArbitraryValues.Value value
  ) {
  }

  /// Three node quorum strategy for testing
  final QuorumStrategy threeNodeQuorum = new SimpleMajority(3);

  /// Property test that verifies prepare message handling by testing all combinations
  /// of relationships between the node under test and inbound message properties
  @Property(generation = GenerationMode.EXHAUSTIVE)
  void prepareTests(@ForAll("testCases") TestCase testCase) {

    // Set up the identifier of the node
    final short nodeId = 2;

    // Set up the identifier of the other node relative to the node under test
    final var otherNodeId = switch (testCase.nodeIdentifierRelation) {
      case LESS -> (short) (nodeId - 1);
      case EQUAL -> nodeId;
      case GREATER -> (short) (nodeId + 1);
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

    // Setup command id
    final var cmd = switch (testCase.value) {
      case NULL -> null;
      case NOOP -> NoOperation.NOOP;
      case COMMAND -> new Command("data".getBytes());
    };

    // Setup journal
    final var journal = new FakeJournal(thisPromise, thisFixed) {
      @Override
      public Optional<Accept> readAccept(long logIndex) {
        if (logIndex == otherIndex && cmd != null) {
          return Optional.of(new Accept(nodeId, logIndex, thisPromise, cmd));
        }
        return Optional.empty();
      }
    };

    // Setup node with role with the correct parameters
    final var node = new TrexNode(Level.INFO, nodeId, threeNodeQuorum, journal) {{
      role = switch (testCase.role) {
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
    var result = node.paxos(prepare);

    // Verify that no invariants of our protocol are violated
    if (result instanceof TrexResult(var messages, var commands)) {
      // No commands should be generated from prepare
      assert commands.isEmpty();

      // Should get either nothing or a single prepare response.
      assert messages.size() <= 2;

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

          // Verify that highest accepted id is returned
          if (cmd != null) {
            assert response.journaledAccept().isPresent();
            var accept = response.journaledAccept().get();
            assert accept.command().equals(cmd);
          } else {
            assert response.journaledAccept().isEmpty();
          }
        }

        if( messages.size() == 2 ) {
          assert testCase.fixedSlotRelation == ArbitraryValues.FixedSlotRelation.LESS;
          var fixed = (Fixed) messages.getLast();
          assert fixed.from() == nodeId;
          assert fixed.slot() == otherIndex;
        }
      }

      // Verify role changes
      assert node.getRole() == TrexNode.TrexRole.valueOf(testCase.role.name())
          || prepare.slot() <= thisFixed
          || !otherNumber.greaterThan(thisPromise) || node.getRole() == TrexNode.TrexRole.FOLLOW;
    }
  }

  /// Provides test cases covering all combinations of relationships between
  /// the node under test and the prepare message properties
  @Provide
  @SuppressWarnings("unused")
  Arbitrary<TestCase> testCases() {
    return Combinators.combine(
        Arbitraries.of(ArbitraryValues.RoleState.values()),
        Arbitraries.of(ArbitraryValues.NodeIdentifierRelation.values()),
        Arbitraries.of(ArbitraryValues.PromiseCounterRelation.values()),
        Arbitraries.of(ArbitraryValues.FixedSlotRelation.values()),
        Arbitraries.of(ArbitraryValues.Value.values())
    ).as(TestCase::new);
  }
}
