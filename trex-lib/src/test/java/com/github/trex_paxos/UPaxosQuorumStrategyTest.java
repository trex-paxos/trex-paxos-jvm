package com.github.trex_paxos;

import com.github.trex_paxos.msg.AcceptResponse;
import com.github.trex_paxos.msg.PrepareResponse;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import java.util.stream.IntStream;

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
    IntStream.range(0, 3)
        .forEach(i -> votes.add(new PrepareResponse.Vote((short)i, (short)i, slotTerm, true)));

    // Add 2 negative votes
    IntStream.range(3, 5)
        .forEach(i -> votes.add(new PrepareResponse.Vote((short)i, (short)i, slotTerm, false)));

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

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UPaxosQuorumStrategyTest {

    @Test
    void validateAddOperation_shouldAcceptValidAdd() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 1),
                new VotingWeight((short)2, 0)
        );
        
        UPaxosQuorumStrategy.AddNodeOp validOp = new UPaxosQuorumStrategy.AddNodeOp((short)3, (byte)1);
        
        assertTrue(UPaxosQuorumStrategy.isValidOperation(weights, validOp));
    }

    @Test
    void validateAddOperation_shouldRejectInvalidAdd() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 1),
                new VotingWeight((short)2, 0)
        );
        
        // Node already exists
        UPaxosQuorumStrategy.AddNodeOp invalidOp1 = new UPaxosQuorumStrategy.AddNodeOp((short)1, (byte)1);
        // Weight change too large
        UPaxosQuorumStrategy.AddNodeOp invalidOp2 = new UPaxosQuorumStrategy.AddNodeOp((short)3, (byte)2);
        
        assertFalse(UPaxosQuorumStrategy.isValidOperation(weights, invalidOp1));
        assertFalse(UPaxosQuorumStrategy.isValidOperation(weights, invalidOp2));
    }

    @Test
    void validateDeleteOperation_shouldAcceptValidDelete() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 1),
                new VotingWeight((short)2, 0)
        );
        
        UPaxosQuorumStrategy.DeleteNodeOp validOp = new UPaxosQuorumStrategy.DeleteNodeOp((short)1);
        
        assertTrue(UPaxosQuorumStrategy.isValidOperation(weights, validOp));
    }

    @Test
    void validateDeleteOperation_shouldRejectInvalidDelete() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 1),
                new VotingWeight((short)2, 0)
        );
        
        // Node doesn't exist
        UPaxosQuorumStrategy.DeleteNodeOp invalidOp = new UPaxosQuorumStrategy.DeleteNodeOp((short)3);
        
        assertFalse(UPaxosQuorumStrategy.isValidOperation(weights, invalidOp));
    }

    @Test
    void validateIncrementOperation_shouldAcceptValidIncrement() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 1),
                new VotingWeight((short)2, 0)
        );
        
        UPaxosQuorumStrategy.IncrementNodeOp validOp = new UPaxosQuorumStrategy.IncrementNodeOp((short)1);
        
        assertTrue(UPaxosQuorumStrategy.isValidOperation(weights, validOp));
    }

    @Test
    void validateIncrementOperation_shouldRejectInvalidIncrement() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 1),
                new VotingWeight((short)2, 0)
        );
        
        // Node doesn't exist
        UPaxosQuorumStrategy.IncrementNodeOp invalidOp = new UPaxosQuorumStrategy.IncrementNodeOp((short)3);
        
        assertFalse(UPaxosQuorumStrategy.isValidOperation(weights, invalidOp));
    }

    @Test
    void validateDecrementOperation_shouldAcceptValidDecrement() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 1),
                new VotingWeight((short)2, 0)
        );
        
        UPaxosQuorumStrategy.DecrementNodeOp validOp = new UPaxosQuorumStrategy.DecrementNodeOp((short)1);
        
        assertTrue(UPaxosQuorumStrategy.isValidOperation(weights, validOp));
    }

    @Test
    void validateDecrementOperation_shouldRejectInvalidDecrement() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 1),
                new VotingWeight((short)2, 0)
        );
        
        // Node has weight 0, can't decrement
        UPaxosQuorumStrategy.DecrementNodeOp invalidOp = new UPaxosQuorumStrategy.DecrementNodeOp((short)2);
        
        assertFalse(UPaxosQuorumStrategy.isValidOperation(weights, invalidOp));
    }

    @Test
    void validateDoubleAllOperation_shouldAcceptValidDouble() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 1),
                new VotingWeight((short)2, 0)
        );
        
        UPaxosQuorumStrategy.DoubleAllOp validOp = new UPaxosQuorumStrategy.DoubleAllOp();
        
        assertTrue(UPaxosQuorumStrategy.isValidOperation(weights, validOp));
    }

    @Test
    void validateDoubleAllOperation_shouldRejectInvalidDouble() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 2),
                new VotingWeight((short)2, 0)
        );
        
        // One node has weight 2, can't double
        UPaxosQuorumStrategy.DoubleAllOp invalidOp = new UPaxosQuorumStrategy.DoubleAllOp();
        
        assertFalse(UPaxosQuorumStrategy.isValidOperation(weights, invalidOp));
    }

    @Test
    void validateHalveAllOperation_shouldAcceptValidHalve() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 2),
                new VotingWeight((short)2, 0)
        );
        
        UPaxosQuorumStrategy.HalveAllOp validOp = new UPaxosQuorumStrategy.HalveAllOp();
        
        assertTrue(UPaxosQuorumStrategy.isValidOperation(weights, validOp));
    }

    @Test
    void validateHalveAllOperation_shouldRejectInvalidHalve() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 1),
                new VotingWeight((short)2, 0)
        );
        
        // One node has weight 1, can't halve
        UPaxosQuorumStrategy.HalveAllOp invalidOp = new UPaxosQuorumStrategy.HalveAllOp();
        
        assertFalse(UPaxosQuorumStrategy.isValidOperation(weights, invalidOp));
    }

    @Test
    void applyAddOperation_shouldAddNewNode() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 1),
                new VotingWeight((short)2, 0)
        );
        
        UPaxosQuorumStrategy.AddNodeOp op = new UPaxosQuorumStrategy.AddNodeOp((short)3, (byte)1);
        
        Set<VotingWeight> result = UPaxosQuorumStrategy.applyOperation(weights, op);
        
        assertEquals(3, result.size());
        assertTrue(result.contains(new VotingWeight((short)3, 1)));
    }

    @Test
    void applyDeleteOperation_shouldRemoveNode() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 1),
                new VotingWeight((short)2, 0)
        );
        
        UPaxosQuorumStrategy.DeleteNodeOp op = new UPaxosQuorumStrategy.DeleteNodeOp((short)1);
        
        Set<VotingWeight> result = UPaxosQuorumStrategy.applyOperation(weights, op);
        
        assertEquals(1, result.size());
        assertFalse(result.stream().anyMatch(w -> w.nodeId().id() == 1));
    }

    @Test
    void applyIncrementOperation_shouldIncrementWeight() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 1),
                new VotingWeight((short)2, 0)
        );
        
        UPaxosQuorumStrategy.IncrementNodeOp op = new UPaxosQuorumStrategy.IncrementNodeOp((short)1);
        
        Set<VotingWeight> result = UPaxosQuorumStrategy.applyOperation(weights, op);
        
        assertEquals(2, result.size());
        assertTrue(result.stream()
                .filter(w -> w.nodeId().id() == 1)
                .anyMatch(w -> w.weight() == 2));
    }

    @Test
    void applyDecrementOperation_shouldDecrementWeight() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 1),
                new VotingWeight((short)2, 0)
        );
        
        UPaxosQuorumStrategy.DecrementNodeOp op = new UPaxosQuorumStrategy.DecrementNodeOp((short)1);
        
        Set<VotingWeight> result = UPaxosQuorumStrategy.applyOperation(weights, op);
        
        assertEquals(2, result.size());
        assertTrue(result.stream()
                .filter(w -> w.nodeId().id() == 1)
                .anyMatch(w -> w.weight() == 0));
    }

    @Test
    void applyDoubleAllOperation_shouldDoubleAllWeights() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 1),
                new VotingWeight((short)2, 0)
        );
        
        UPaxosQuorumStrategy.DoubleAllOp op = new UPaxosQuorumStrategy.DoubleAllOp();
        
        Set<VotingWeight> result = UPaxosQuorumStrategy.applyOperation(weights, op);
        
        assertEquals(2, result.size());
        assertTrue(result.stream()
                .filter(w -> w.nodeId().id() == 1)
                .anyMatch(w -> w.weight() == 2));
        assertTrue(result.stream()
                .filter(w -> w.nodeId().id() == 2)
                .anyMatch(w -> w.weight() == 0));
    }

    @Test
    void applyHalveAllOperation_shouldHalveAllWeights() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 2),
                new VotingWeight((short)2, 0)
        );
        
        UPaxosQuorumStrategy.HalveAllOp op = new UPaxosQuorumStrategy.HalveAllOp();
        
        Set<VotingWeight> result = UPaxosQuorumStrategy.applyOperation(weights, op);
        
        assertEquals(2, result.size());
        assertTrue(result.stream()
                .filter(w -> w.nodeId().id() == 1)
                .anyMatch(w -> w.weight() == 1));
        assertTrue(result.stream()
                .filter(w -> w.nodeId().id() == 2)
                .anyMatch(w -> w.weight() == 0));
    }

    @Test
    void applyOperation_shouldThrowExceptionForInvalidOperation() {
        Set<VotingWeight> weights = Set.of(
                new VotingWeight((short)1, 1),
                new VotingWeight((short)2, 0)
        );
        
        // Node doesn't exist
        UPaxosQuorumStrategy.DeleteNodeOp invalidOp = new UPaxosQuorumStrategy.DeleteNodeOp((short)3);
        
        assertThrows(IllegalArgumentException.class, () -> 
                UPaxosQuorumStrategy.applyOperation(weights, invalidOp));
    }
}
