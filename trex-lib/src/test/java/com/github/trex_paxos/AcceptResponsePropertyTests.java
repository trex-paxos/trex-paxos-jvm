/*
 * Copyright 2024 - 2025 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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

// FIXME: Add era as a parameter to the test case
public class AcceptResponsePropertyTests {


  record TestCase(
      ArbitraryValues.RoleState role,
      ArbitraryValues.NodeIdentifierRelation nodeIdentifierRelation,
      ArbitraryValues.VoteOutcome voteOutcome,
      ArbitraryValues.OutOfOrder outOfOrder
  ) {
  }

  final QuorumStrategy threeNodeQuorum = new SimpleMajority(3);

  @Property(generation = GenerationMode.EXHAUSTIVE)
  void acceptResponseTests(@ForAll("testCases") TestCase testCase) {
    final var thisNodeId = (short) 2;

    final var otherNodeId = switch (testCase.nodeIdentifierRelation) {
      case LESS -> (short) (thisNodeId - 1);
      case EQUAL -> thisNodeId;
      case GREATER -> (short) (thisNodeId + 1);
    };

    final var thisCounter = 100;
    final var thisPromise = new BallotNumber((short) 0, thisCounter, thisNodeId);

    final var thisFixed = 10L;

    final var journaledAccepts = new AtomicReference<Map<Long, Accept>>(new HashMap<>());
    // Setup journal with accepts
    final var journal = new FakeJournal(thisPromise, thisFixed) {

      @Override
      public Optional<Accept> readAccept(long logIndex) {
        return journaledAccepts.get().containsKey(logIndex) ?
            Optional.of(journaledAccepts.get().get(logIndex)) :
            Optional.empty();
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

    final var slotAtomic = new AtomicLong(thisFixed + 1);

    // Setup node with role and the rigged acceptVotes that will trigger either a win or a loose
    final var node = new TrexNode(Level.INFO, thisNodeId, threeNodeQuorum, journal) {
      {
        role = switch (testCase.role) {
          case FOLLOW -> TrexRole.FOLLOW;
          case RECOVER -> TrexRole.RECOVER;
          case LEAD -> TrexRole.LEAD;
        };

        if (role != TrexRole.FOLLOW) {
          term = thisPromise;
        }

        // if we have a ga
        if (testCase.outOfOrder == ArbitraryValues.OutOfOrder.TRUE) {
          // drop a rigged vote in at the first slot
          final var s = slotAtomic.getAndIncrement();
          final var v = createAcceptVotes(s);
          // Setup gap scenario we first add chosen `accept` before gap
          acceptVotesByLogIndex.put(v.accept().slotTerm().logIndex(), v.votes());
          // we need to put it into the journal
          journaledAccepts.get().put(s, v.accept());
          // then increment the slot counter without adding an `accept`
          slotAtomic.getAndIncrement();
        }

        // now we add the rigged vote for at least one slot which may be the only slot else after a gap
        final var s = slotAtomic.get();
        final var v = createAcceptVotes(s);
        // Setup gap scenario we first add chosen `slotTerm` before gap
        acceptVotesByLogIndex.put(v.accept().slotTerm().logIndex(), v.votes());
        // we need to put it into the journal
        journaledAccepts.get().put(s, v.accept());
      }

      record CreatedData(Accept accept, AcceptVotes votes) {
      }

      ///  Set up an `accept` and `acceptVotes` for a given slot
      private CreatedData createAcceptVotes(long s) {
        final var a = new Accept(thisNodeId, s, thisPromise, NoOperation.NOOP);
        final Map<Short, AcceptResponse> responses = new TreeMap<>();
        responses.put(thisNodeId, new AcceptResponse(thisNodeId, thisNodeId, a.era(),
            new AcceptResponse.Vote(thisNodeId, thisNodeId, a.slotTerm(), thisVote), s));
        AcceptVotes votes = new AcceptVotes(a.slotTerm(), responses, false);
        return new CreatedData(a, votes);
      }
    };

    //  if the election is rigged top `WIN` or `LOSE` we need a vote
    //  if we want to rig for a `WAIT` then our vote is opposite to the self vote
    final var otherVote = switch (testCase.voteOutcome) {
      case WIN -> true;
      case LOSE, WAIT -> false;
    };

    // Create accept response for the next slot
    final var slot = slotAtomic.get();
    final SlotTerm slotTerm = new SlotTerm(slot, thisPromise);
    final var vote = new AcceptResponse.Vote(otherNodeId, thisNodeId, slotTerm, otherVote);
    final var acceptResponse = new AcceptResponse(otherNodeId, thisNodeId, slotTerm.era(), vote,
        slot);

    // now that we have set up based on our role, the other nodeIdentifier relation, the vote outcome
    // we can run the algorithm to see if we issue the fixed message or not
    final var result = node.paxos(acceptResponse);

    if (result instanceof TrexResult(final var messages, final var commands)) {
      // both followers and revolvers will process accept responses yet followers ignore them
      // nodes ignore responses not sent to them
      //  ignore responses sent to ourself
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
        assert node.getRole() == TrexNode.TrexRole.FOLLOW;
        assert messages.isEmpty();
      } else if (testCase.voteOutcome == ArbitraryValues.VoteOutcome.WIN &&
          testCase.outOfOrder == ArbitraryValues.OutOfOrder.FALSE) {
        // Should fix id and send Fixed message for contiguous slots
        assert !messages.isEmpty();
        assert messages.getFirst() instanceof Fixed;
        assert !commands.isEmpty();
      } else if (testCase.voteOutcome == ArbitraryValues.VoteOutcome.WIN &&
          testCase.outOfOrder == ArbitraryValues.OutOfOrder.TRUE) {
        // Should not fix id or send Fixed message when gaps exist
        assert messages.isEmpty();
        assert commands.isEmpty();
      } else {
        assert testCase.voteOutcome == ArbitraryValues.VoteOutcome.LOSE
            || testCase.voteOutcome == ArbitraryValues.VoteOutcome.WAIT;
        // No majority yet
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
        Arbitraries.of(ArbitraryValues.VoteOutcome.values()),
        Arbitraries.of(ArbitraryValues.OutOfOrder.values())
    ).as(TestCase::new);
  }
}
