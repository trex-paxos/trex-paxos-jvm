package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import net.jqwik.api.*;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class PreparePropertyTests {

  enum RoleState {FOLLOW, RECOVER, LEAD}

  enum NodeIdentifierRelation {LESS, EQUAL, GREATER}

  enum PromiseCounterRelation {LESS, EQUAL, GREATER}

  enum FixedSlotRelation {LESS, EQUAL, GREATER}

  enum Value {NULL, NOOP, COMMAND}

  record TestCase(
      RoleState role,
      NodeIdentifierRelation nodeIdentifierRelation,
      PromiseCounterRelation promiseCounterRelation,
      FixedSlotRelation fixedSlotRelation,
      Value value
  ) {
  }

  final QuorumStrategy threeNodeQuorum = new FixedQuorumStrategy(3);

  @Property
  void prepareTests(@ForAll("testCases") TestCase testCase) {
    // Setup node identifiers
    final byte nodeId = 2;
    final byte otherId = switch (testCase.nodeIdentifierRelation) {
      case LESS -> (byte) (nodeId - 1);
      case EQUAL -> nodeId;
      case GREATER -> (byte) (nodeId + 1);
    };

    // Setup ballot numbers
    final int promiseCounter = 100;
    final BallotNumber currentPromise = new BallotNumber(promiseCounter, nodeId);
    final BallotNumber prepareNumber = switch (testCase.promiseCounterRelation) {
      case LESS -> new BallotNumber(promiseCounter - 1, otherId);
      case EQUAL -> new BallotNumber(promiseCounter, otherId);
      case GREATER -> new BallotNumber(promiseCounter + 1, otherId);
    };

    // Setup log indices
    final long fixedIndex = 10;
    final long prepareIndex = switch (testCase.fixedSlotRelation) {
      case LESS -> fixedIndex - 1;
      case EQUAL -> fixedIndex;
      case GREATER -> fixedIndex + 1;
    };

    // Setup command value
    final AbstractCommand cmd = switch (testCase.value) {
      case NULL -> null;
      case NOOP -> NoOperation.NOOP;
      case COMMAND -> new Command("test", "data".getBytes());
    };

    // Setup journal
    final var journal = new FakeJournal(nodeId, currentPromise, fixedIndex) {
      @Override
      public Optional<Accept> readAccept(long logIndex) {
        if (logIndex == prepareIndex && cmd != null) {
          return Optional.of(new Accept(nodeId, logIndex, currentPromise, cmd));
        }
        return Optional.empty();
      }
    };

    // Setup node with role
    final var node = new FakeEngine(nodeId, threeNodeQuorum, journal) {{
      trexNode.role = switch (testCase.role) {
        case FOLLOW -> TrexRole.FOLLOW;
        case RECOVER -> TrexRole.RECOVER;
        case LEAD -> TrexRole.LEAD;
      };
    }};

    // Create prepare message
    final Prepare prepare = new Prepare(otherId, prepareIndex, prepareNumber);

    // Execute
    var result = node.paxos(List.of(prepare));

    // Verify
    if (result instanceof TrexResult(var messages, var commands)) {
      // No commands should be generated from prepare
      assert commands.isEmpty();

      // Should get exactly one response
      assert messages.size() <= 1;

      // Verify response
      if (!messages.isEmpty()) {
        var response = (PrepareResponse) messages.getFirst();
        var vote = response.vote();

        // Verify protocol invariants
        if (prepareIndex <= fixedIndex) {
          // Must reject prepares for fixed slots
          assert !vote.vote();
        } else if (prepareNumber.lessThan(currentPromise)) {
          // Must reject lower ballot numbers
          assert !vote.vote();
        } else {
          // Must accept higher ballot numbers
          assert vote.vote();
          assert vote.from() == nodeId;
          assert vote.to() == otherId;
          assert vote.logIndex() == prepareIndex;
          assert vote.number().equals(prepareNumber);

          // Verify highest accepted value is returned
          if (cmd != null) {
            assert response.journaledAccept().isPresent();
            var accept = response.journaledAccept().get();
            assert accept.command().equals(cmd);
          } else {
            assert response.journaledAccept().isEmpty();
          }
        }
      }

      // Verify role changes
      if (!Objects.equals(node.trexNode().role.toString(), testCase.role.toString())
          && prepare.logIndex() > fixedIndex
          && prepareNumber.greaterThan(currentPromise)) {
        assert node.trexNode().getRole() == TrexRole.FOLLOW;
      }
    }
  }

  @Provide
  Arbitrary<TestCase> testCases() {
    return Combinators.combine(
        Arbitraries.of(RoleState.values()),
        Arbitraries.of(NodeIdentifierRelation.values()),
        Arbitraries.of(PromiseCounterRelation.values()),
        Arbitraries.of(FixedSlotRelation.values()),
        Arbitraries.of(Value.values())
    ).as(TestCase::new);
  }
}
