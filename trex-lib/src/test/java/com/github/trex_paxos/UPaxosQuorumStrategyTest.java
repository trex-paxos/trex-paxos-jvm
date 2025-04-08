package com.github.trex_paxos;

import com.github.trex_paxos.msg.AcceptResponse;
import com.github.trex_paxos.msg.PrepareResponse;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UPaxosQuorumStrategyTest {

    @Test
    void validateWeights_shouldAcceptValidWeights() {
        // Create a set of valid weights (0, 1, 2)
        Set<VotingWeight> validWeights = Set.of(
                new VotingWeight(1, 0),
                new VotingWeight(2, 1),
                new VotingWeight(3, 2)
        );
        
        assertTrue(UPaxosQuorumStrategy.validateWeights(validWeights));
    }

    @Test
    void validateWeights_shouldRejectInvalidWeights() {
        // Create a set with an invalid weight (3)
        Set<VotingWeight> invalidWeights = Set.of(
                new VotingWeight(1, 0),
                new VotingWeight(2, 3)  // Invalid weight
        );
        
        assertFalse(UPaxosQuorumStrategy.validateWeights(invalidWeights));
    }

    @Test
    void calculateTotalWeight_shouldSumCorrectly() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight(1, 1),
                new VotingWeight(2, 2),
                new VotingWeight(3, 0)
        );
        
        assertEquals(3, UPaxosQuorumStrategy.calculateTotalWeight(weights));
    }

    @Test
    void assessPromises_shouldReturnWinWithMajority() {
        UPaxosQuorumStrategy strategy = new UPaxosQuorumStrategy();
        Set<PrepareResponse.Vote> votes = new HashSet<>();
        
        // Add 3 positive votes
        for (int i = 0; i < 3; i++) {
            votes.add(new PrepareResponse.Vote((short)i, true));
        }
        
        // Add 2 negative votes
        for (int i = 3; i < 5; i++) {
            votes.add(new PrepareResponse.Vote((short)i, false));
        }
        
        assertEquals(QuorumStrategy.QuorumOutcome.WIN, strategy.assessPromises(1L, votes));
    }

    @Test
    void assessAccepts_shouldReturnLoseWithoutMajority() {
        UPaxosQuorumStrategy strategy = new UPaxosQuorumStrategy();
        Set<AcceptResponse.Vote> votes = new HashSet<>();
        
        // Add 2 positive votes
        for (int i = 0; i < 2; i++) {
            votes.add(new AcceptResponse.Vote((short)i, true));
        }
        
        // Add 3 negative votes
        for (int i = 2; i < 5; i++) {
            votes.add(new AcceptResponse.Vote((short)i, false));
        }
        
        assertEquals(QuorumStrategy.QuorumOutcome.LOSE, strategy.assessAccepts(1L, votes));
    }
}
