package com.github.trex_paxos;

import com.github.trex_paxos.msg.AcceptResponse;
import com.github.trex_paxos.msg.PrepareResponse;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/// # FlexiblePaxosQuorum
///
/// An implementation of `QuorumStrategy` that uses weighted voting with flexible quorum sizes.
///
/// This implementation is based on the [Flexible Paxos](https://arxiv.org/pdf/1608.06696v1) theorem which states that:
/// - Different quorum sizes can be used for the prepare and accept phases
/// - The only requirement is that any prepare quorum must intersect with any accept quorum
/// - Mathematically: |N| > |P| + |A| where:
///   - |N| is the total voting weight
///   - |P| is the prepare quorum size
///   - |A| is the accept quorum size
///
/// ## Usage
///
/// To use the even nodes gambit with pairs of nodes in two reliance zones we can assign the prepare quorum to be 3
/// and the accept quorum to be 2:
///
/// ```
/// Set<VotingWeight> weights = Set.of(
///     new VotingWeight((short)1, 1),
///     new VotingWeight((short)2, 1),
///     new VotingWeight((short)3, 1)
///     new VotingWeight((short)4, 1)
/// );
/// QuorumStrategy quorum = new FlexiblePaxosQuorum(weights, 3, 2);
/// ```
public class FlexiblePaxosQuorum implements QuorumStrategy {

  private final int prepareQuorumSize;
  private final int acceptQuorumSize;
  public final Set<VotingWeight> weights;
  private final @NotNull Map<Integer, Integer> id2Weight;

  public FlexiblePaxosQuorum(Set<VotingWeight> weights, int prepareQuorumSize, int acceptQuorumSize) {
    final var sumWeights = weights.stream().mapToInt(VotingWeight::weight).sum();
    if ( prepareQuorumSize + acceptQuorumSize <= sumWeights) {
      throw new IllegalArgumentException("|N| > |A| + |P| is required yet N="
          + sumWeights + " A=" + acceptQuorumSize + " P=" + prepareQuorumSize);
    }
    this.prepareQuorumSize = prepareQuorumSize;
    this.acceptQuorumSize = acceptQuorumSize;
    this.weights = Set.copyOf(weights);
    this.id2Weight = weights.stream().collect(Collectors.toMap(v -> (int)v.nodeId().id(), VotingWeight::weight));
  }

  @Override
  public QuorumOutcome assessPromises(long logIndex, Set<PrepareResponse.Vote> votes) {
    return assessVotes(votes, PrepareResponse.Vote::vote, v -> (int)v.from(), prepareQuorumSize);
  }

  @Override
  public QuorumOutcome assessAccepts(long logIndex, Set<AcceptResponse.Vote> votes) {
    return assessVotes(votes, AcceptResponse.Vote::vote, v -> (int)v.from(), acceptQuorumSize);
  }

  private <T> QuorumOutcome assessVotes(
      Set<T> votes,
      Predicate<T> voteExtractor,
      ToIntFunction<T> fromExtractor,
      int quorumSize) {

    final @NotNull Map<@NotNull Boolean, @NotNull List<T>> count
        = votes.stream().collect(Collectors.partitioningBy(voteExtractor));

    final var yesCount = count.get(true).stream()
        .mapToInt(v -> id2Weight.get(fromExtractor.applyAsInt(v)))
        .sum();

    if (yesCount >= quorumSize) {
      return QuorumOutcome.WIN;
    } else {
      final var noCount = count.get(false).stream()
          .mapToInt(v -> id2Weight.get(fromExtractor.applyAsInt(v)))
          .sum();

      if (noCount >= quorumSize) {
        return QuorumOutcome.LOSE;
      } else {
        return QuorumOutcome.WAIT;
      }
    }
  }
}
