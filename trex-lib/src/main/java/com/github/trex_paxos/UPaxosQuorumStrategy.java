/// # UPaxosQuorumStrategy
///
/// Implementation of QuorumStrategy for U-Paxos.
///
/// This implementation follows Data Oriented Programming principles:
/// - No instance variables (stateless)
/// - Pure functions with no side effects
/// - Immutable data structures
///
/// @see FlexiblePaxosQuorum
package com.github.trex_paxos;

import com.github.trex_paxos.msg.AcceptResponse;
import com.github.trex_paxos.msg.PrepareResponse;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class UPaxosQuorumStrategy implements QuorumStrategy {

    /// Validates that all weights are valid (0, 1, or 2 only).
    ///
    /// @param weights the set of voting weights to validate
    /// @return true if all weights are valid, false otherwise
    public static boolean validateWeights(Set<VotingWeight> weights) {
        return weights.stream()
                .allMatch(weight -> weight.weight() >= 0 && weight.weight() <= 2);
    }

    /// Calculates the total weight of a set of voting weights.
    ///
    /// @param weights the set of voting weights
    /// @return the total weight
    public static int calculateTotalWeight(Set<VotingWeight> weights) {
        return weights.stream()
                .mapToInt(VotingWeight::weight)
                .sum();
    }

    /// Assesses if a set of promises constitutes a quorum.
    ///
    /// @param logIndex the log index being voted on
    /// @param votes the set of promise votes
    /// @return the quorum outcome (WIN, LOSE, or WAIT)
    @Override
    public QuorumOutcome assessPromises(long logIndex, Set<PrepareResponse.Vote> votes) {
        List<Boolean> voteResults = votes.stream()
                .map(PrepareResponse.Vote::vote)
                .collect(Collectors.toList());
        
        // Simple majority for now - will be enhanced in future iterations
        return countVotes(voteResults.size() / 2 + 1, voteResults);
    }

    /// Assesses if a set of accepts constitutes a quorum.
    ///
    /// @param logIndex the log index being voted on
    /// @param votes the set of accept votes
    /// @return the quorum outcome (WIN, LOSE, or WAIT)
    @Override
    public QuorumOutcome assessAccepts(long logIndex, Set<AcceptResponse.Vote> votes) {
        List<Boolean> voteResults = votes.stream()
                .map(AcceptResponse.Vote::vote)
                .collect(Collectors.toList());
        
        // Simple majority for now - will be enhanced in future iterations
        return countVotes(voteResults.size() / 2 + 1, voteResults);
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

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implements quorum strategies for UPaxos (Unbounded Paxos).
 */
public class UPaxosQuorumStrategy {

    /**
     * Represents operations that can be performed on a quorum configuration.
     */
    public sealed interface QuorumOperation permits 
        UPaxosQuorumStrategy.AddNodeOp,
        UPaxosQuorumStrategy.DeleteNodeOp, 
        UPaxosQuorumStrategy.IncrementNodeOp,
        UPaxosQuorumStrategy.DecrementNodeOp,
        UPaxosQuorumStrategy.DoubleAllOp,
        UPaxosQuorumStrategy.HalveAllOp {
    }

    /**
     * Operation to add a new node with specified weight.
     */
    public record AddNodeOp(short nodeId, byte weight) implements QuorumOperation {
        public AddNodeOp {
            if (nodeId <= 0) {
                throw new IllegalArgumentException("Node ID must be positive");
            }
            if (weight < 0) {
                throw new IllegalArgumentException("Weight must be non-negative");
            }
        }
    }

    /**
     * Operation to delete an existing node.
     */
    public record DeleteNodeOp(short nodeId) implements QuorumOperation {
        public DeleteNodeOp {
            if (nodeId <= 0) {
                throw new IllegalArgumentException("Node ID must be positive");
            }
        }
    }

    /**
     * Operation to increment a node's weight by 1.
     */
    public record IncrementNodeOp(short nodeId) implements QuorumOperation {
        public IncrementNodeOp {
            if (nodeId <= 0) {
                throw new IllegalArgumentException("Node ID must be positive");
            }
        }
    }

    /**
     * Operation to decrement a node's weight by 1.
     */
    public record DecrementNodeOp(short nodeId) implements QuorumOperation {
        public DecrementNodeOp {
            if (nodeId <= 0) {
                throw new IllegalArgumentException("Node ID must be positive");
            }
        }
    }

    /**
     * Operation to double the weight of all nodes.
     */
    public record DoubleAllOp() implements QuorumOperation {}

    /**
     * Operation to halve the weight of all nodes.
     */
    public record HalveAllOp() implements QuorumOperation {}

    /**
     * Validates if an operation is valid for the given set of weights.
     *
     * @param weights the current set of voting weights
     * @param op the operation to validate
     * @return true if the operation is valid, false otherwise
     */
    public static boolean isValidOperation(Set<VotingWeight> weights, QuorumOperation op) {
        return switch (op) {
            case AddNodeOp addOp -> isValidAddOperation(weights, addOp);
            case DeleteNodeOp deleteOp -> isValidDeleteOperation(weights, deleteOp);
            case IncrementNodeOp incOp -> isValidIncrementOperation(weights, incOp);
            case DecrementNodeOp decOp -> isValidDecrementOperation(weights, decOp);
            case DoubleAllOp doubleOp -> isValidDoubleAllOperation(weights);
            case HalveAllOp halveOp -> isValidHalveAllOperation(weights);
        };
    }

    /**
     * Applies an operation to a set of weights.
     *
     * @param weights the current set of voting weights
     * @param op the operation to apply
     * @return a new set of voting weights after applying the operation
     * @throws IllegalArgumentException if the operation is invalid
     */
    public static Set<VotingWeight> applyOperation(Set<VotingWeight> weights, QuorumOperation op) {
        if (!isValidOperation(weights, op)) {
            throw new IllegalArgumentException("Invalid operation for the given weights");
        }

        return switch (op) {
            case AddNodeOp addOp -> applyAddOperation(weights, addOp);
            case DeleteNodeOp deleteOp -> applyDeleteOperation(weights, deleteOp);
            case IncrementNodeOp incOp -> applyIncrementOperation(weights, incOp);
            case DecrementNodeOp decOp -> applyDecrementOperation(weights, decOp);
            case DoubleAllOp doubleOp -> applyDoubleAllOperation(weights);
            case HalveAllOp halveOp -> applyHalveAllOperation(weights);
        };
    }

    private static boolean isValidAddOperation(Set<VotingWeight> weights, AddNodeOp op) {
        // Check if node already exists
        boolean nodeExists = weights.stream()
                .anyMatch(w -> w.nodeId().id() == op.nodeId());
        
        // Check if weight change is valid (-1, 0, or +1)
        int weightChange = op.weight();
        return !nodeExists && (weightChange >= -1 && weightChange <= 1);
    }

    private static boolean isValidDeleteOperation(Set<VotingWeight> weights, DeleteNodeOp op) {
        // Check if node exists
        VotingWeight nodeToDelete = weights.stream()
                .filter(w -> w.nodeId().id() == op.nodeId())
                .findFirst()
                .orElse(null);
        
        // Check if weight change is valid (-1, 0, or +1)
        return nodeToDelete != null && (nodeToDelete.weight() >= -1 && nodeToDelete.weight() <= 1);
    }

    private static boolean isValidIncrementOperation(Set<VotingWeight> weights, IncrementNodeOp op) {
        // Check if node exists
        boolean nodeExists = weights.stream()
                .anyMatch(w -> w.nodeId().id() == op.nodeId());
        
        return nodeExists;
    }

    private static boolean isValidDecrementOperation(Set<VotingWeight> weights, DecrementNodeOp op) {
        // Check if node exists and has weight > 0
        return weights.stream()
                .filter(w -> w.nodeId().id() == op.nodeId())
                .anyMatch(w -> w.weight() > 0);
    }

    private static boolean isValidDoubleAllOperation(Set<VotingWeight> weights) {
        // Only allowed when all nodes have weights of 0 or 1
        return weights.stream()
                .allMatch(w -> w.weight() == 0 || w.weight() == 1);
    }

    private static boolean isValidHalveAllOperation(Set<VotingWeight> weights) {
        // Only allowed when all nodes have weights of 0 or 2
        return weights.stream()
                .allMatch(w -> w.weight() == 0 || w.weight() == 2);
    }

    private static Set<VotingWeight> applyAddOperation(Set<VotingWeight> weights, AddNodeOp op) {
        Set<VotingWeight> result = new HashSet<>(weights);
        result.add(new VotingWeight(op.nodeId(), op.weight()));
        return Set.copyOf(result);
    }

    private static Set<VotingWeight> applyDeleteOperation(Set<VotingWeight> weights, DeleteNodeOp op) {
        return weights.stream()
                .filter(w -> w.nodeId().id() != op.nodeId())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<VotingWeight> applyIncrementOperation(Set<VotingWeight> weights, IncrementNodeOp op) {
        return weights.stream()
                .map(w -> w.nodeId().id() == op.nodeId() 
                    ? new VotingWeight(w.nodeId(), w.weight() + 1) 
                    : w)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<VotingWeight> applyDecrementOperation(Set<VotingWeight> weights, DecrementNodeOp op) {
        return weights.stream()
                .map(w -> w.nodeId().id() == op.nodeId() 
                    ? new VotingWeight(w.nodeId(), w.weight() - 1) 
                    : w)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<VotingWeight> applyDoubleAllOperation(Set<VotingWeight> weights) {
        return weights.stream()
                .map(w -> new VotingWeight(w.nodeId(), w.weight() * 2))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<VotingWeight> applyHalveAllOperation(Set<VotingWeight> weights) {
        return weights.stream()
                .map(w -> new VotingWeight(w.nodeId(), w.weight() / 2))
                .collect(Collectors.toUnmodifiableSet());
    }
}
