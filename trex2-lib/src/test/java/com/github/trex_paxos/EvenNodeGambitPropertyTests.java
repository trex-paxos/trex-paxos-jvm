package com.github.trex_paxos;

import com.github.trex_paxos.msg.AcceptResponse;
import net.jqwik.api.*;
import org.assertj.core.api.Assertions;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

public class EvenNodeGambitPropertyTests {
  record TestCase(
      byte primaryNode,
      Vote node1Vote,
      Vote node2Vote,
      Vote node3Vote,
      Vote node4Vote
  ) {
  }

  enum Vote {
    YES, NO, NOT_VOTED
  }

  @Property(generation = GenerationMode.EXHAUSTIVE)
  void evenNodeGambitTests(@ForAll("testCases") TestCase testCase) {
    EvenNodeGambit strategy = new EvenNodeGambit(4, testCase.primaryNode);

    List<AcceptResponse.Vote> votes = IntStream.range(0, 4)
        .mapToObj(i -> {
          Vote v = switch (i) {
            case 0 -> testCase.node1Vote;
            case 1 -> testCase.node2Vote;
            case 2 -> testCase.node3Vote;
            default -> testCase.node4Vote;
          };
          return v == Vote.NOT_VOTED ? null :
              new AcceptResponse.Vote((byte) (i + 1), (byte) 0, 0, v == Vote.YES);
        })
        .filter(Objects::nonNull)
        .toList();

    QuorumStrategy.QuorumOutcome result = strategy.assessAccepts(1L, Set.copyOf(votes));

    // Calculate expected outcome
    if (votes.size() < 2) {
      assert QuorumStrategy.QuorumOutcome.WAIT == result;
      return;
    }

    int sum = votes.stream()
        .mapToInt(v -> (v.to() == testCase.primaryNode ? 2 : 1) * (v.vote() ? 1 : -1))
        .sum();

    QuorumStrategy.QuorumOutcome expected = sum > 0 ? QuorumStrategy.QuorumOutcome.WIN :
        sum < 0 ? QuorumStrategy.QuorumOutcome.LOSE :
            votes.stream().anyMatch(v -> v.to() == testCase.primaryNode && v.vote())
                ? QuorumStrategy.QuorumOutcome.WIN : QuorumStrategy.QuorumOutcome.LOSE;


    Assertions.assertThat(result).isEqualTo(expected);
  }

  @Provide
  Arbitrary<TestCase> testCases() {
    return Combinators.combine(
        Arbitraries.bytes().between((byte) 1, (byte) 4),  // primaryNode
        Arbitraries.of(Vote.values()),                  // node1Vote
        Arbitraries.of(Vote.values()),                  // node2Vote
        Arbitraries.of(Vote.values()),                  // node3Vote
        Arbitraries.of(Vote.values())                   // node4Vote
    ).as((primaryNode, node1Vote, node2Vote, node3Vote, node4Vote) -> primaryNode != null ? new TestCase(primaryNode, node1Vote, node2Vote, node3Vote, node4Vote) : null);
  }
}
