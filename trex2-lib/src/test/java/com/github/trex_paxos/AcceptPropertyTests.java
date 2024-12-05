package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import net.jqwik.api.*;

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public class AcceptPropertyTests {

  record TestCase(
      ArbitraryValues.RoleState role,
      ArbitraryValues.NodeIdentifierRelation nodeIdentifierRelation,
      ArbitraryValues.PromiseCounterRelation promiseCounterRelation,
      ArbitraryValues.FixedSlotRelation fixedSlotRelation,
      ArbitraryValues.Value value
  ) {
  }

  final QuorumStrategy threeNodeQuorum = new FixedQuorumStrategy(3);

  @Property(generation = GenerationMode.EXHAUSTIVE)
  void acceptTests(@ForAll("testCases") TestCase testCase) {
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

    // Setup command value
    final var cmd = switch (testCase.value) {
      case NULL -> null;
      case NOOP -> NoOperation.NOOP;
      case COMMAND -> new Command("test", "data".getBytes());
    };

    // Track journal writes
    final var journaledProgress = new AtomicReference<Progress>();
    final var journaledAccept = new AtomicReference<Accept>();

    // Setup journal
    final var journal = new FakeJournal(thisNodeId, thisPromise, thisFixed) {
      @Override
      public void writeProgress(Progress progress) {
        journaledProgress.set(progress);
      }

      @Override
      public void writeAccept(Accept accept) {
        journaledAccept.set(accept);
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

    // Create accept message
    final var accept = new Accept(otherNodeId, otherIndex, otherNumber, cmd);

    // Execute
    final var result = node.paxos(accept);

    // Verify
    if (result instanceof TrexResult(final var messages, final var commands)) {
      // No commands should be generated from accept
      assert commands.isEmpty();

      assert messages.size() == 1;
      final var response = (AcceptResponse) messages.getFirst();
      if (otherIndex <= thisFixed || otherNumber.lessThan(thisPromise)) {
        // Must reject accepts for fixed slots or lower ballot numbers
        assert !response.vote().vote();
        assert journaledAccept.get() == null;
        assert journaledProgress.get() == null;
      } else {
        // Must accept higher or equal ballot numbers
        assert response.vote().vote();

        // Verify response properties
        assert response.vote().from() == thisNodeId;
        assert response.vote().to() == otherNodeId;
        assert response.vote().logIndex() == otherIndex;

        // Verify journaling
        assert journaledAccept.get() != null;
        assert journaledAccept.get().equals(accept);

        // Verify promise updates for higher ballot numbers
        if (otherNumber.greaterThan(thisPromise)) {
          assert journaledProgress.get() != null;
          assert journaledProgress.get().highestPromised().equals(otherNumber);
        }
      }
    }
  }

  @Provide
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
