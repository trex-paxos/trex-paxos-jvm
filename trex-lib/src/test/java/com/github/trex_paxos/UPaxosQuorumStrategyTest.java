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
                new VotingWeight((short)1, 0),
                new VotingWeight((short)2, 1),
                new VotingWeight((short)3, 2)
        );
        
        assertTrue(UPaxosQuorumStrategy.validateWeights(validWeights));
    }

    @Test
    void validateWeights_shouldRejectInvalidWeights() {
        // Create a set with an invalid weight (3)
        Set<VotingWeight> invalidWeights = Set.of(
                new VotingWeight((short)1, 0),
                new VotingWeight((short)2, 3)  // Invalid weight
        );
        
        assertFalse(UPaxosQuorumStrategy.validateWeights(invalidWeights));
    }

    @Test
    void calculateTotalWeight_shouldSumCorrectly() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 1),
                new VotingWeight((short)2, 2),
                new VotingWeight((short)3, 0)
        );
        
        assertEquals(3, UPaxosQuorumStrategy.calculateTotalWeight(weights));
    }

    @Test
    void assessPromises_shouldReturnWinWithMajority() {
        UPaxosQuorumStrategy strategy = new UPaxosQuorumStrategy();
        Set<PrepareResponse.Vote> votes = new HashSet<>();
        
        // Create a SlotTerm for the votes
        SlotTerm slotTerm = new SlotTerm(1L, new BallotNumber((short)1, 1, (short)1));
        
        // Add 3 positive votes
        for (short i = 0; i < 3; i++) {
            votes.add(new PrepareResponse.Vote(i, i, slotTerm, true));
        }
        
        // Add 2 negative votes
        for (short i = 3; i < 5; i++) {
            votes.add(new PrepareResponse.Vote(i, i, slotTerm, false));
        }
        
        assertEquals(QuorumStrategy.QuorumOutcome.WIN, strategy.assessPromises(1L, votes));
    }

    @Test
    void assessAccepts_shouldReturnLoseWithoutMajority() {
        UPaxosQuorumStrategy strategy = new UPaxosQuorumStrategy();
        Set<AcceptResponse.Vote> votes = new HashSet<>();
        
        // Create a SlotTerm for the votes
        SlotTerm slotTerm = new SlotTerm(1L, new BallotNumber((short)1, 1, (short)1));
        
        // Add 2 positive votes
        for (short i = 0; i < 2; i++) {
            votes.add(new AcceptResponse.Vote(i, i, slotTerm, true));
        }
        
        // Add 3 negative votes
        for (short i = 2; i < 5; i++) {
            votes.add(new AcceptResponse.Vote(i, i, slotTerm, false));
        }
        
        assertEquals(QuorumStrategy.QuorumOutcome.LOSE, strategy.assessAccepts(1L, votes));
    }
}
