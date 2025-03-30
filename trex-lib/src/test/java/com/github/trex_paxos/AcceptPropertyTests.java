package com.github.trex_paxos;

import com.github.trex_paxos.msg.Accept;
import com.github.trex_paxos.msg.AcceptResponse;
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

  final QuorumStrategy threeNodeQuorum = new SimpleMajority(3);

  @Property(generation = GenerationMode.EXHAUSTIVE)
  void acceptTests(@ForAll("testCases") TestCase testCase) {
    // Set up the identifier of the node under test
    final var thisNodeId = (short) 2;

    // Set up the identifier of the other node relative to the node under test
    final var otherNodeId = switch (testCase.nodeIdentifierRelation) {
      case LESS -> (short) (thisNodeId - 1);
      case EQUAL -> thisNodeId;
      case GREATER -> (short) (thisNodeId + 1);
    };

    // Setup ballot number of the node under test
    final var thisCounter = 100;
    final var thisPromise = new BallotNumber((short) 0, thisCounter, thisNodeId);

    final var otherNumber = switch (testCase.promiseCounterRelation) {
      case LESS -> new BallotNumber((short) 0, thisCounter - 1, otherNodeId);
      case EQUAL -> new BallotNumber((short) 0, thisCounter, otherNodeId);
      case GREATER -> new BallotNumber((short) 0, thisCounter + 1, otherNodeId);
    };

    // Setup log indices
    final var thisFixed = 10L;
    final var otherIndex = switch (testCase.fixedSlotRelation) {
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

    // Track journal writes
    final var journaledProgress = new AtomicReference<Progress>();
    final var journaledAccept = new AtomicReference<Accept>();

    // Setup journal
    final var journal = new FakeJournal(thisPromise, thisFixed) {
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

    // Create slotTerm message
    final var accept = new Accept(otherNodeId, otherIndex, otherNumber, cmd);

    // Execute
    final var result = node.paxos(accept);

    // Verify
    if (result instanceof TrexResult(final var messages, final var commands)) {
      // No results should be generated from slotTerm
      assert commands.isEmpty();

      assert messages.size() == 1;
      final var response = (AcceptResponse) messages.getFirst();
      if (otherIndex <= thisFixed || otherNumber.lessThan(thisPromise)) {
        // Must reject accepts for fixed slots or lower ballot numbers
        assert !response.vote().vote();
        assert journaledAccept.get() == null;
        assert journaledProgress.get() == null;
      } else {
        // Must slotTerm higher or equal ballot numbers
        assert response.vote().vote();

        // Verify response properties
        assert response.vote().from() == thisNodeId;
        assert response.vote().to() == otherNodeId;
        assert response.vote().slotTerm().equals(accept.slotTerm());

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

  @SuppressWarnings("unused")
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
