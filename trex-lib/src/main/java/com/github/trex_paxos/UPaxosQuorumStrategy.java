package com.github.trex_paxos;

import com.github.trex_paxos.msg.AcceptResponse;
import com.github.trex_paxos.msg.PrepareResponse;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Implementation of QuorumStrategy for U-Paxos.
 * This implementation follows Data Oriented Programming principles:
 * - No instance variables (stateless)
 * - Pure functions with no side effects
 * - Immutable data structures
 */
public class UPaxosQuorumStrategy implements QuorumStrategy {

    /**
     * Validates that all weights are valid (0, 1, or 2 only).
     *
     * @param weights the set of voting weights to validate
     * @return true if all weights are valid, false otherwise
     */
    public static boolean validateWeights(Set<VotingWeight> weights) {
        return weights.stream()
                .allMatch(weight -> weight.weight() >= 0 && weight.weight() <= 2);
    }

    /**
     * Calculates the total weight of a set of voting weights.
     *
     * @param weights the set of voting weights
     * @return the total weight
     */
    public static int calculateTotalWeight(Set<VotingWeight> weights) {
        return weights.stream()
                .mapToInt(VotingWeight::weight)
                .sum();
    }

    /**
     * Assesses if a set of promises constitutes a quorum.
     *
     * @param logIndex the log index being voted on
     * @param votes the set of promise votes
     * @return the quorum outcome (WIN, LOSE, or WAIT)
     */
    @Override
      public QuorumOutcome assessPromises(long logIndex, Set<PrepareResponse.Vote> votes) {
        List<Boolean> voteResults = votes.stream()
                .map(vote -> vote.promised())
                .collect(Collectors.toList());
      
        // Simple majority for now - will be enhanced in future iterations
        return countVotes(voteResults.size() / 2 + 1, voteResults);
    }

    /**
     * Assesses if a set of accepts constitutes a quorum.
     *
     * @param logIndex the log index being voted on
     * @param votes the set of accept votes
     * @return the quorum outcome (WIN, LOSE, or WAIT)
     */
    @Override
    public QuorumOutcome assessAccepts(long logIndex, Set<AcceptResponse.Vote> votes) {
        List<Boolean> voteResults = votes.stream()
                .map(vote -> vote.accepted())
                .collect(Collectors.toList());
        
        // Simple majority for now - will be enhanced in future iterations
        return countVotes(voteResults.size() / 2 + 1, voteResults);
    }
}
