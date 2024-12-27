package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import net.jqwik.api.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public class FixedPropertyTests {

  record TestCase(
      ArbitraryValues.RoleState role,
      ArbitraryValues.NodeIdentifierRelation nodeIdentifierRelation,
      ArbitraryValues.FixedSlotRelation fixedSlotRelation,
      ArbitraryValues.PromiseCounterRelation promiseCounterRelation,
      ArbitraryValues.JournalState journalState
  ) {
  }

  final QuorumStrategy threeNodeQuorum = new SimpleMajority(3);

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
    final var journal = new FakeJournal(thisPromise, thisFixed) {
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
    final var node = new TrexNode(Level.INFO, thisNodeId, threeNodeQuorum, journal) {{
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
      if (otherIndex == thisFixed + 1 && testCase.journalState == ArbitraryValues.JournalState.MATCHING_NUMBER) {
        // Should process next sequential slot with matching number
        assert commands.size() == 1;
        assert journaledProgress.get() != null;
        assert journaledProgress.get().highestFixedIndex() == otherIndex;

        // Non-followers should back down
        if (testCase.role != ArbitraryValues.RoleState.FOLLOW) {
          assert node.getRole() == TrexNode.TrexRole.FOLLOW;
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
        Arbitraries.of(ArbitraryValues.RoleState.values()),
        Arbitraries.of(ArbitraryValues.NodeIdentifierRelation.values()),
        Arbitraries.of(ArbitraryValues.FixedSlotRelation.values()),
        Arbitraries.of(ArbitraryValues.PromiseCounterRelation.values()),
        Arbitraries.of(ArbitraryValues.JournalState.values())
    ).as(TestCase::new);
  }
}
