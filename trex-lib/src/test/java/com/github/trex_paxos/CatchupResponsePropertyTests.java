package com.github.trex_paxos;

import com.github.trex_paxos.msg.Accept;
import com.github.trex_paxos.msg.CatchupResponse;

import net.jqwik.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.stream.IntStream;

public class CatchupResponsePropertyTests {

  record TestCase(
      ArbitraryValues.RoleState role,
      ArbitraryValues.NodeIdentifierRelation nodeIdentifierRelation,
      ArbitraryValues.PromiseCounterRelation promiseCounterRelation,
      ArbitraryValues.CatchupAlignmentState catchUpAlignment,
      int acceptCount
  ) {
  }

  final QuorumStrategy threeNodeQuorum = new SimpleMajority(3);

  @Property(generation = GenerationMode.EXHAUSTIVE)
  void catchupResponseTests(@ForAll("testCases") TestCase testCase) {

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
    final var otherIndex = switch (testCase.catchUpAlignment) {
      case TOO_LOW -> thisFixed - 1;
      case CORRECT -> thisFixed;
      case TOO_HIGH -> thisFixed + 1;
    };

    final var journaledProgress = new AtomicReference<Progress>();
    final var journaledAccepts = new AtomicReference<List<Accept>>(new ArrayList<>());

    final var journal = new FakeJournal(thisPromise, thisFixed) {
      @Override
      public void writeProgress(Progress progress) {
        journaledProgress.set(progress);
      }

      @Override
      public void writeAccept(Accept accept) {
        List<Accept> currentAccepts = journaledAccepts.get();
        currentAccepts.add(accept);
        journaledAccepts.set(currentAccepts);
      }
    };

    final var node = new TrexNode(Level.INFO, thisNodeId, threeNodeQuorum, journal) {{
      role = switch (testCase.role) {
        case FOLLOW -> TrexRole.FOLLOW;
        case RECOVER -> TrexRole.RECOVER;
        case LEAD -> TrexRole.LEAD;
      };
    }};

    final var catchupAccepts = IntStream.range(1, testCase.acceptCount + 1)
        .mapToObj(i -> new Accept(
            otherNodeId,
            otherIndex + i,
            otherNumber,
            new Command( ("data" + i).getBytes())
        ))
        .toList();

    final var catchupResponse = new CatchupResponse(otherNodeId, thisNodeId, catchupAccepts);

    final var result = node.paxos(catchupResponse);

    if (result instanceof TrexResult(final var messages, final var commands)) {
      assert messages.isEmpty();

      // if w ae should have journaled all the accepts
      if (testCase.catchUpAlignment == ArbitraryValues.CatchupAlignmentState.CORRECT) {
        // Verify that accepts are processed
        assert journaledAccepts.get().size() == testCase.acceptCount;
      } else if (testCase.catchUpAlignment == ArbitraryValues.CatchupAlignmentState.TOO_LOW) {
        if (testCase.acceptCount > 0) {
          // Verify that not all accepts are not processed
          assert journaledAccepts.get().size() < testCase.acceptCount;
        } else {
          // Verify that accepts are not processed
          assert journaledAccepts.get().isEmpty();
        }
      } else {
        // Verify that accepts are not processed
        assert journaledAccepts.get().isEmpty();
      }

      // Verify progress update
      if (!catchupAccepts.isEmpty() && testCase.catchUpAlignment == ArbitraryValues.CatchupAlignmentState.CORRECT
          && !otherNumber.lessThan(thisPromise)) {
        assert journaledProgress.get() != null;
        assert journaledProgress.get().highestFixedIndex() >= catchupAccepts.getLast().slot();
      }

      if (testCase.catchUpAlignment == ArbitraryValues.CatchupAlignmentState.CORRECT
          && !otherNumber.lessThan(thisPromise)) {
        // Verify command processing
        assert commands.size() == testCase.acceptCount;

        for (Accept accept : catchupAccepts) {
          assert commands.containsKey(accept.slot());
          assert commands.get(accept.slot()).equals(accept.command());
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
        Arbitraries.of(ArbitraryValues.CatchupAlignmentState.values()),
        Arbitraries.integers().between(0, 2)
    ).as((role,
          nodeIdentifierRelation,
          promiseCounterRelation,
          catchUpAlignment,
          acceptCount) -> role != null ?
        new TestCase(role, nodeIdentifierRelation, promiseCounterRelation, catchUpAlignment, Optional.ofNullable(acceptCount).orElse(0)) : null);
  }
}
