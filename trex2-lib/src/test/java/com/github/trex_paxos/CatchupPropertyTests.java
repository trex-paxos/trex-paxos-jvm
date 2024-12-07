package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import net.jqwik.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.stream.IntStream;

public class CatchupPropertyTests {

  /// Number of accepts to return from the journal for the range
  enum AcceptCount {
    NONE,           // Return no accepts
    ONE,            // Return single accept
    MULTIPLE       // Return multiple accepts
  }

  record TestCase(
      ArbitraryValues.RoleState role,
      ArbitraryValues.NodeIdentifierRelation nodeIdentifierRelation,
      ArbitraryValues.PromiseCounterRelation promiseCounterRelation,
      ArbitraryValues.FixedSlotRelation fixedSlotRelation,
      AcceptCount acceptCount
  ) {
  }

  final QuorumStrategy threeNodeQuorum = new SimpleMajority(3);

  @Property(generation = GenerationMode.EXHAUSTIVE)
  void catchupTests(@ForAll("testCases") TestCase testCase) {
    final var thisNodeId = (byte) 2;

    final var otherNodeId = switch (testCase.nodeIdentifierRelation) {
      case LESS -> (byte) (thisNodeId - 1);
      case EQUAL -> thisNodeId;
      case GREATER -> (byte) (thisNodeId + 1);
    };

    final var thisCounter = 100;
    final var thisPromise = new BallotNumber(thisCounter, thisNodeId);
    final var otherNumber = switch (testCase.promiseCounterRelation) {
      case LESS -> new BallotNumber(thisCounter - 1, otherNodeId);
      case EQUAL -> new BallotNumber(thisCounter, otherNodeId);
      case GREATER -> new BallotNumber(thisCounter + 1, otherNodeId);
    };

    final var thisFixed = 10L;
    final var otherIndex = switch (testCase.fixedSlotRelation) {
      case LESS -> thisFixed - 1;
      case EQUAL -> thisFixed;
      case GREATER -> thisFixed + 1;
    };

    final var accepts = new ArrayList<Accept>();

    switch (testCase.acceptCount) {
      case NONE -> {
        // Do nothing
      }
      case ONE -> accepts.add(new Accept(thisNodeId, thisFixed, thisPromise, new Command("test", "data".getBytes())));
      case MULTIPLE -> IntStream.range(0, 3).forEach(i ->
          accepts.add(new Accept(thisNodeId, thisFixed + i, thisPromise, new Command("test", "data".getBytes()))));
    }

    final var journaledProgress = new AtomicReference<Progress>();
    final var acceptAtomic = new AtomicReference<List<Accept>>(accepts);

    final var journal = new FakeJournal(thisPromise, thisFixed) {
      @Override
      public void writeProgress(Progress progress) {
        journaledProgress.set(progress);
      }

      @Override
      public Optional<Accept> readAccept(long logIndex) {
        return acceptAtomic.get().stream().filter(a -> a.logIndex() == logIndex).findFirst();
      }
    };

    final var node = new TrexNode(Level.SEVERE, thisNodeId, threeNodeQuorum, journal) {{
      role = switch (testCase.role) {
        case FOLLOW -> TrexRole.FOLLOW;
        case RECOVER -> TrexRole.RECOVER;
        case LEAD -> TrexRole.LEAD;
      };
      if (role == TrexRole.LEAD) {
        term = thisPromise;
      }
    }};

    final var catchup = new Catchup(otherNodeId, thisNodeId, otherIndex, otherNumber);

    final var result = node.paxos(catchup);

    if (result instanceof TrexResult(final var messages, final var commands)) {
      assert commands.isEmpty();

      assert messages.size() <= 1;

      if (!messages.isEmpty()) {
        final var response = (CatchupResponse) messages.getFirst();

        assert response.from() == thisNodeId;
        assert response.to() == otherNodeId;

        if (otherIndex < thisFixed && testCase.acceptCount != AcceptCount.NONE) {
          assert !response.accepts().isEmpty();
          assert response.accepts().getFirst().logIndex() == otherIndex + 1;
          assert response.accepts().getLast().logIndex() == thisFixed;
        } else {
          assert response.accepts().isEmpty();
        }
      }

      // we must not increment our promise outside of the `prepare` or `accept` messages
      assert journaledProgress.get() == null;

      // it is possible to end up in a state where a node is rejecting `accept` messages as it has
      // a higher promise. That node will request catchup and so the leader will learn that the node
      // cannot accept any values. The leader will increase its term so that on the next accept message
      // that itself receives it will make a promise and the other node will accept the message.
      if (testCase.role == ArbitraryValues.RoleState.LEAD &&
          thisPromise.lessThan(otherNumber)) {
        assert node.term.greaterThan(otherNumber);
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
        Arbitraries.of(AcceptCount.values())
    ).as(TestCase::new);
  }
}
