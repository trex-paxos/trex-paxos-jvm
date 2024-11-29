package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import net.jqwik.api.*;

import java.util.NavigableMap;
import java.util.Optional;


public class FollowerPropertyTests {

  enum LogState {EMPTY, CONTIGUOUS, GAP}

  enum Relation {LESS, EQUAL, GREATER}

  record TestCase(
      byte nodeIdentifier,
      byte otherNodeIdentifier,
      LogState logState,
      Relation promiseRelation,
      Relation messageNumberRelation,
      Relation messageLogRelation,
      int fixedIndex
  ) {
  }

  final QuorumStrategy threeNodeQuorum = new FixedQuorumStrategy(3);

  @Property
  void followerAcceptPropertyTests(@ForAll("testCases") TestCase testCase) {
    final var journal = new TransparentJournal(testCase.nodeIdentifier);

    // TODO change the high and low values to be more meaningful
    setupLogState(journal.fakeJournal, testCase.logState());

    TestablePaxosEngine follower = new PropertyPaxosEngine(TrexRole.FOLLOW, testCase.nodeIdentifier, threeNodeQuorum, journal);

    int maxSlot = getMaxSlot(testCase.logState());
    int promise = calculateValue(maxSlot, testCase.promiseRelation());

    int messageNumber = calculateValue(promise, testCase.messageNumberRelation());
    int messageLogIndex = calculateValue(testCase.fixedIndex, testCase.messageLogRelation());

    Accept accept = new Accept(
        testCase.otherNodeIdentifier(),
        messageLogIndex,
        new BallotNumber(messageNumber, testCase.otherNodeIdentifier()),
        new Command("test", "data".getBytes())
    );

    var result = follower.paxos(accept);
    if (result instanceof TrexResult(var messages, var commands)) {
      assert commands.isEmpty();
      messages.forEach(message -> {
        assert message instanceof AcceptResponse;
        AcceptResponse acceptResponse = (AcceptResponse) message;
        Vote vote = acceptResponse.vote();
        // TODO assert vote is true or false based on the properties of the test case
      });
    }
  }

  @Provide
  Arbitrary<TestCase> testCases() {
    return Combinators.combine(
        Arbitraries.bytes().greaterOrEqual((byte) 1).lessOrEqual((byte) 3),
        Arbitraries.bytes().greaterOrEqual((byte) 1).lessOrEqual((byte) 3),
        Arbitraries.of(LogState.values()),
        Arbitraries.of(Relation.values()),
        Arbitraries.of(Relation.values()),
        Arbitraries.of(Relation.values()),
        Arbitraries.integers().between(1, 2)
    ).as((nodeIdentifier,
          otherNodeIdentifier,
          logState,
          promiseRelation,
          messageNumberRelation,
          messageLogRelation,
          fixedIndex) ->
        nodeIdentifier != null ?
            new TestCase(nodeIdentifier,
                Optional.ofNullable(otherNodeIdentifier).orElse((byte) 1),
                logState,
                promiseRelation,
                messageNumberRelation,
                messageLogRelation,
                Optional.ofNullable(fixedIndex).orElse(1)) : null);
  }

  private void setupLogState(NavigableMap<Long, Accept> fakeJournal, LogState state) {
    switch (state) {
      case EMPTY -> fakeJournal.put(0L, new Accept((byte) 0L, 0, BallotNumber.MIN, NoOperation.NOOP));
      case CONTIGUOUS -> {
        fakeJournal.put(0L, new Accept((byte) 0L, 0, BallotNumber.MIN, NoOperation.NOOP));
        fakeJournal.put(1L, new Accept((byte) 1L, 0, BallotNumber.MIN, NoOperation.NOOP));
      }
      case GAP -> {
        fakeJournal.put(0L, new Accept((byte) 0L, 0, BallotNumber.MIN, NoOperation.NOOP));
        fakeJournal.put(1L, new Accept((byte) 1L, 0, BallotNumber.MIN, NoOperation.NOOP));
        fakeJournal.put(3L, new Accept((byte) 3L, 0, BallotNumber.MIN, NoOperation.NOOP));
      }
    }
  }

  private int getMaxSlot(LogState state) {
    return switch (state) {
      case EMPTY -> 1;
      case CONTIGUOUS -> 2;
      case GAP -> 4;
    };
  }

  private int calculateValue(int baseValue, Relation relation) {
    return switch (relation) {
      case LESS -> baseValue - 1;
      case EQUAL -> baseValue;
      case GREATER -> baseValue + 1;
    };
  }

  static class PropertyPaxosEngine extends TestablePaxosEngine {

    public PropertyPaxosEngine(TrexRole role, byte nodeIdentifier, QuorumStrategy quorumStrategy, TransparentJournal journal) {
      super(nodeIdentifier, quorumStrategy, journal);
      trexNode.role = role;
    }

    @Override
    protected void setRandomTimeout() {
    }

    @Override
    protected void clearTimeout() {
    }

    @Override
    protected void setHeartbeat() {
    }
  }

}

