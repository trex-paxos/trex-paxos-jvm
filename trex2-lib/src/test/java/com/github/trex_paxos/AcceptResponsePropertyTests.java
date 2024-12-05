package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import net.jqwik.api.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public class AcceptResponsePropertyTests {

  /// Outcome of the vote collection for the accept
  enum VoteOutcome {
    WIN,    // Will achieve majority with this vote
    LOSE    // Will not achieve majority
  }

  /// Whether accepts are contiguous or have gaps
  enum OutOfOrder {
    FALSE,  // Accepts are contiguous
    TRUE    // Accepts have gaps
  }

  record TestCase(
      ArbitraryValues.RoleState role,
      ArbitraryValues.NodeIdentifierRelation nodeIdentifierRelation,
      VoteOutcome voteOutcome,
      OutOfOrder outOfOrder
  ) {
  }

  final QuorumStrategy threeNodeQuorum = new FixedQuorumStrategy(3);

  @Property(generation = GenerationMode.EXHAUSTIVE)
  void acceptResponseTests(@ForAll("testCases") TestCase testCase) {
    final var thisNodeId = (byte) 2;

    final var otherNodeId = switch (testCase.nodeIdentifierRelation) {
      case LESS -> (byte) (thisNodeId - 1);
      case EQUAL -> thisNodeId;
      case GREATER -> (byte) (thisNodeId + 1);
    };

    final var thisCounter = 100;
    final var thisPromise = new BallotNumber(thisCounter, thisNodeId);

    final var thisFixed = 10L;

    final var journaledProgress = new AtomicReference<Progress>();
    final var journaledAccepts = new AtomicReference<Map<Long, Accept>>(new HashMap<>());
    // Setup journal with accepts
    final var journal = new FakeJournal(thisNodeId, thisPromise, thisFixed) {
      @Override
      public void writeProgress(Progress progress) {
        journaledProgress.set(progress);
      }

      @Override
      public Optional<Accept> readAccept(long logIndex) {
        return journaledAccepts.get().containsKey(logIndex) ?
            Optional.of(journaledAccepts.get().get(logIndex)) :
            Optional.empty();
      }
    };

    final var outcomeVote = switch (testCase.voteOutcome) {
      case WIN -> true;
      case LOSE -> false;
    };

    final var slot = new AtomicLong(thisFixed + 1);

    // Setup node with role and acceptVotes
    final var node = new TrexNode(Level.INFO, thisNodeId, threeNodeQuorum, journal) {
      {
        role = switch (testCase.role) {
          case FOLLOW -> TrexRole.FOLLOW;
          case RECOVER -> TrexRole.RECOVER;
          case LEAD -> TrexRole.LEAD;
        };

        if (testCase.outOfOrder == OutOfOrder.TRUE) {
          final var s = slot.getAndIncrement();
          final var v = createAcceptVotes(s);
          // Setup gap scenario we first add chosen `accept` before gap
          acceptVotesByLogIndex.put(s, v.votes());
          // we need to put it into the journal also
          journaledAccepts.get().put(s, v.accept());
          // Then we create a gap
          slot.getAndIncrement();
        }

        final var s = slot.get();
        final var v = createAcceptVotes(s);
        // Setup gap scenario we first add chosen `accept` before gap
        acceptVotesByLogIndex.put(s, v.votes());
        // we need to put it into the journal also
        journaledAccepts.get().put(s, v.accept());
      }

      record CreatedData(Accept accept, AcceptVotes votes) {
      }

      private CreatedData createAcceptVotes(long s) {
        final var a = new Accept(thisNodeId, s, thisPromise, NoOperation.NOOP);
        final Map<Byte, AcceptResponse> responses = new TreeMap<>();
        responses.put(thisNodeId, new AcceptResponse(thisNodeId, thisNodeId,
            new AcceptResponse.Vote(thisNodeId, thisNodeId, s, outcomeVote), s));
        AcceptVotes votes = new AcceptVotes(a.slotTerm(), responses, false);
        return new CreatedData(a, votes);
      }
    };

    final var s = slot.get();

    // Create accept response
    final var vote = new AcceptResponse.Vote(otherNodeId, thisNodeId, s, true);
    final var acceptResponse = new AcceptResponse(otherNodeId, thisNodeId, vote,
        s);

    final var result = node.paxos(acceptResponse);

    if (result instanceof TrexResult(final var messages, final var commands)) {
      if (testCase.role == ArbitraryValues.RoleState.FOLLOW
          || acceptResponse.to() != thisNodeId
          || testCase.nodeIdentifierRelation == ArbitraryValues.NodeIdentifierRelation.EQUAL
      ) {
        // Followers ignore accept responses
        assert messages.isEmpty();
        assert commands.isEmpty();
      } else if (testCase.role == ArbitraryValues.RoleState.LEAD &&
          acceptResponse.highestFixedIndex() > thisFixed) {
        // Leader must back down if other node has higher fixed index
        assert node.getRole() == TrexRole.FOLLOW;
        assert messages.isEmpty();
      } else if (testCase.voteOutcome == VoteOutcome.WIN && testCase.outOfOrder == OutOfOrder.FALSE) {
        // Should fix value and send Fixed message for contiguous slots
        assert !messages.isEmpty();
        assert messages.getFirst() instanceof Fixed;
        assert !commands.isEmpty();
      } else if (testCase.voteOutcome == VoteOutcome.WIN &&
          testCase.outOfOrder == OutOfOrder.TRUE) {
        // Should not fix value or send Fixed message when gaps exist
        assert messages.isEmpty();
        assert commands.isEmpty();
      } else {
        // No majority yet
        assert messages.isEmpty();
        assert commands.isEmpty();
      }
    }
    // FIXME: TEST FOR BACKING DOWN
  }

  @Provide
  Arbitrary<TestCase> testCases() {
    return Combinators.combine(
        Arbitraries.of(ArbitraryValues.RoleState.values()),
        Arbitraries.of(ArbitraryValues.NodeIdentifierRelation.values()),
        Arbitraries.of(VoteOutcome.values()),
        Arbitraries.of(OutOfOrder.values())
    ).as(TestCase::new);
  }
}
