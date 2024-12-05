package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import net.jqwik.api.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public class FixedPropertyTests {

  /// Current TrexRole of the node under test when receiving messages
  enum RoleState {FOLLOW, RECOVER, LEAD}

  /// Relationship between node identifier of node under test compared to message node identifier
  enum NodeIdentifierRelation {LESS, EQUAL, GREATER}

  /// Relationship between promise counter of node under test compared to message promise counter
  enum PromiseCounterRelation {LESS, EQUAL, GREATER}

  /// Relationship between fixed slot index of node under test compared to message slot index
  enum FixedSlotRelation {LESS, EQUAL, GREATER}

  /// State of the journal at the fixed slot
  enum JournalState {
    EMPTY,              // No value at slot
    MATCHING_NUMBER,    // Has `accept` with matching ballot number
    DIFFERENT_NUMBER   // Has `accept` with different ballot number
  }

  record TestCase(
      RoleState role,
      NodeIdentifierRelation nodeIdentifierRelation,
      FixedSlotRelation fixedSlotRelation,
      PromiseCounterRelation promiseCounterRelation,
      JournalState journalState
  ) {
  }

  final QuorumStrategy threeNodeQuorum = new FixedQuorumStrategy(3);

  @Property(generation = GenerationMode.EXHAUSTIVE)
  void fixedTests(@ForAll("testCases") TestCase testCase) {
    // Set up the identifier of the node under test
    final var thisNodeId = (byte) 2;

    // Set up the identifier of the other node relative to the node under test
    final var otherNodeId = switch (testCase.nodeIdentifierRelation) {
      case LESS -> (byte) (thisNodeId - 1);
      case EQUAL -> thisNodeId;
      case GREATER -> (byte) (thisNodeId + 1);
    };

    // Setup ballot number of the node under test
    final var thisCounter = 100;
    final var thisPromise = new BallotNumber(thisCounter, thisNodeId);

    final var otherNumber = switch (testCase.promiseCounterRelation) {
      case LESS -> new BallotNumber(thisCounter - 1, otherNodeId);
      case EQUAL -> new BallotNumber(thisCounter, otherNodeId);
      case GREATER -> new BallotNumber(thisCounter + 1, otherNodeId);
    };

    // Setup log indices
    final var thisFixed = 10L;
    final var otherIndex = switch (testCase.fixedSlotRelation) {
      case LESS -> thisFixed - 1;
      case EQUAL -> thisFixed;
      case GREATER -> thisFixed + 1;
    };

    // Track journal writes
    final var journaledProgress = new AtomicReference<Progress>();

    // Setup journal
    final var journal = new FakeJournal(thisNodeId, thisPromise, thisFixed) {
      @Override
      public void writeProgress(Progress progress) {
        journaledProgress.set(progress);
      }

      @Override
      public Optional<Accept> readAccept(long logIndex) {
        if (logIndex == otherIndex) {

          final Command test = new Command("test", "data".getBytes());

          return switch (testCase.journalState) {
            case EMPTY -> Optional.empty();
            case MATCHING_NUMBER -> Optional.of(
                new Accept(thisNodeId, logIndex, otherNumber,
                    test));
            case DIFFERENT_NUMBER -> Optional.of(
                new Accept(thisNodeId, logIndex, BallotNumber.MIN,
                    test));
          };
        }
        return Optional.empty();
      }
    };

    // Setup node with role
    final var node = new TrexNode(Level.SEVERE, thisNodeId, threeNodeQuorum, journal) {{
      role = switch (testCase.role) {
        case FOLLOW -> TrexRole.FOLLOW;
        case RECOVER -> TrexRole.RECOVER;
        case LEAD -> TrexRole.LEAD;
      };
    }};

    // Create fixed message
    final var fixed = new Fixed(otherNodeId, otherIndex, otherNumber);

    // Execute
    final var result = node.paxos(fixed);

    // Verify
    if (result instanceof TrexResult(final var messages, final var commands)) {
      if (otherIndex == thisFixed + 1 && testCase.journalState == JournalState.MATCHING_NUMBER) {
        // Should process next sequential slot with matching number
        assert commands.size() == 1;
        assert journaledProgress.get() != null;
        assert journaledProgress.get().highestFixedIndex() == otherIndex;

        // Non-followers should back down
        if (testCase.role != RoleState.FOLLOW) {
          assert node.getRole() == TrexRole.FOLLOW;
        }
      } else if (otherIndex > thisFixed) {
        // Should request catchup for higher slots
        assert messages.size() == 1;
        assert messages.getFirst() instanceof Catchup;
        final var catchup = (Catchup) messages.getFirst();
        assert catchup.from() == thisNodeId;
        assert catchup.to() == otherNodeId;
        assert catchup.highestFixedIndex() == thisFixed;
      } else {
        // Should ignore fixed messages for lower/equal slots
        assert messages.isEmpty();
        assert commands.isEmpty();
      }
    }
  }

  @Provide
  Arbitrary<TestCase> testCases() {
    return Combinators.combine(
        Arbitraries.of(RoleState.values()),
        Arbitraries.of(NodeIdentifierRelation.values()),
        Arbitraries.of(FixedSlotRelation.values()),
        Arbitraries.of(PromiseCounterRelation.values()),
        Arbitraries.of(JournalState.values())
    ).as(TestCase::new);
  }
}
