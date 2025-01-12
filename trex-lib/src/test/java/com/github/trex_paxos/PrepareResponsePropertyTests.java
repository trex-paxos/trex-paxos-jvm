package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import net.jqwik.api.*;

import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public class PrepareResponsePropertyTests {

  enum SlotExpansion {
    LESS_THAN, EQUAL, GREATER_THAN
  }

  enum RangeToRecover {
    ONE, MANY
  }

  record TestCase(
      ArbitraryValues.RoleState role,
      ArbitraryValues.NodeIdentifierRelation nodeIdentifierRelation,
      ArbitraryValues.VoteOutcome voteOutcome,
      SlotExpansion slotExpansion,
      RangeToRecover rangeToRecover
  ) {
  }

  final QuorumStrategy threeNodeQuorum = new SimpleMajority(3);

  @Property(generation = GenerationMode.EXHAUSTIVE)
  void prepareResponseTests(@ForAll("testCases") TestCase testCase) {
    final var thisNodeId = (short) 2;
    final var otherNodeId = switch (testCase.nodeIdentifierRelation) {
      case LESS -> (byte) (thisNodeId - 1);
      case EQUAL -> thisNodeId;
      case GREATER -> (byte) (thisNodeId + 1);
    };

    final var thisCounter = 100;
    final var thisPromise = new BallotNumber(thisCounter, thisNodeId);
    final var thisFixed = 10L;

    //  slotAtomic is used to track test slot number for the prepare response
    final var slotAtomic = new AtomicLong(thisFixed + 1);
    final var journalWritten = new AtomicBoolean(false);

    //  only on a WIN will the algorithm recurse and write to the journal to process the self-accept.
    final var journal = new FakeJournal(thisPromise, thisFixed) {
      @Override
      public void writeProgress(Progress progress) {
        journalWritten.set(true);
      }

      @Override
      public void writeAccept(Accept accept) {
        journalWritten.set(true);
      }
    };

    // we are going to set our own vote to be true of false simply to rig our election
    // in reality we would expect we would expect our self vote to usually be true
    // and that we would need two other votes to every loose an election. however we
    // are not trying to model reality we are trying to explore the state space.
    final var thisVote = switch (testCase.voteOutcome) {
      case WIN, WAIT -> true;
      case LOSE -> false;
    };

    final var node = new TrexNode(Level.INFO, thisNodeId, threeNodeQuorum, journal) {{
      role = switch (testCase.role) {
        case FOLLOW -> TrexRole.FOLLOW;
        case RECOVER -> TrexRole.RECOVER;
        case LEAD -> TrexRole.LEAD;
      };

      if (role != TrexRole.FOLLOW) {
        term = thisPromise;
        final var slot = slotAtomic.get();
        final var responses = new TreeMap<Short, PrepareResponse>();
        final var prepareResponse = createPrepareResponse(slot, thisVote);
        responses.put(thisNodeId, prepareResponse);
        prepareResponsesByLogIndex.put(slot, responses);
      }
    }};

    final var slot = slotAtomic.get();
    final var highestAcceptedIndex = switch (testCase.slotExpansion) {
      case LESS_THAN -> slot - 1;
      case EQUAL -> slot;
      case GREATER_THAN -> slot + 5;
    };

    final var otherVote = switch (testCase.voteOutcome) {
      case WIN -> true;
      case LOSE, WAIT -> false;
    };

    final var vote = new PrepareResponse.Vote(otherNodeId, thisNodeId, slot, otherVote, thisPromise);
    final var prepareResponse = new PrepareResponse(otherNodeId, thisNodeId, vote,
        Optional.of(new Accept(otherNodeId, slot, thisPromise, NoOperation.NOOP)),
        highestAcceptedIndex);

    final var result = node.paxos(prepareResponse);

    if (testCase.role == ArbitraryValues.RoleState.RECOVER) {
      if (result instanceof TrexResult(final var messages, final var commands)) {
        if (prepareResponse.to() != thisNodeId
            || testCase.nodeIdentifierRelation == ArbitraryValues.NodeIdentifierRelation.EQUAL) {
          assert !journalWritten.get();
          assert messages.isEmpty();
          assert commands.isEmpty();
        } else if (testCase.voteOutcome == ArbitraryValues.VoteOutcome.WIN) {
          assert journalWritten.get();
          if (testCase.slotExpansion == SlotExpansion.GREATER_THAN) {
            assert !messages.isEmpty();
            assert messages.stream().allMatch(m -> m instanceof Prepare || m instanceof Accept);
          } else if (testCase.rangeToRecover == RangeToRecover.ONE) {
            assert !messages.isEmpty();
            assert messages.getFirst() instanceof Accept;
            assert node.getRole() == TrexNode.TrexRole.LEAD;
          }
        } else {
          assert !journalWritten.get();
          assert messages.isEmpty();
          assert commands.isEmpty();
          assert testCase.voteOutcome != ArbitraryValues.VoteOutcome.LOSE || node.getRole() == TrexNode.TrexRole.FOLLOW;
        }
      }
    }
  }

  private PrepareResponse createPrepareResponse(long slot, boolean vote) {
    final var v = new PrepareResponse.Vote((short) 2, (short) 2, slot, vote, new BallotNumber(100, (short) 2));
    return new PrepareResponse((short) 2, (short) 2, v, Optional.empty(), slot);
  }

  @Provide
  Arbitrary<TestCase> testCases() {
    return Combinators.combine(
        Arbitraries.of(ArbitraryValues.RoleState.values()),
        Arbitraries.of(ArbitraryValues.NodeIdentifierRelation.values()),
        Arbitraries.of(ArbitraryValues.VoteOutcome.values()),
        Arbitraries.of(SlotExpansion.values()),
        Arbitraries.of(RangeToRecover.values())
    ).as(TestCase::new);
  }
}
