
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

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    /// Operation to add a new node with specified weight.
    /// 
    /// @param nodeId the ID of the node to add
    /// @param weight the initial weight of the node
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

    /// Operation to delete an existing node.
    /// 
    /// @param nodeId the ID of the node to delete
    public record DeleteNodeOp(short nodeId) implements QuorumOperation {
        public DeleteNodeOp {
            if (nodeId <= 0) {
                throw new IllegalArgumentException("Node ID must be positive");
            }
        }
    }

    /// Operation to increment a node's weight by 1.
    /// 
    /// @param nodeId the ID of the node to increment
    public record IncrementNodeOp(short nodeId) implements QuorumOperation {
        public IncrementNodeOp {
            if (nodeId <= 0) {
                throw new IllegalArgumentException("Node ID must be positive");
            }
        }
    }

    /// Operation to decrement a node's weight by 1.
    /// 
    /// @param nodeId the ID of the node to decrement
    public record DecrementNodeOp(short nodeId) implements QuorumOperation {
        public DecrementNodeOp {
            if (nodeId <= 0) {
                throw new IllegalArgumentException("Node ID must be positive");
            }
        }
    }

    /// Operation to double the weight of all nodes.
    public record DoubleAllOp() implements QuorumOperation {}

    /// Operation to halve the weight of all nodes.
    public record HalveAllOp() implements QuorumOperation {}

    /// Validates if an operation is valid for the given set of weights.
    /// 
    /// @param weights the current set of voting weights
    /// @param op the operation to validate
    /// @return true if the operation is valid, false otherwise
    public static boolean isValidOperation(Set<VotingWeight> weights, QuorumOperation op) {
        return switch (op) {
            case AddNodeOp addOp -> isValidAddOperation(weights, addOp);
            case DeleteNodeOp deleteOp -> isValidDeleteOperation(weights, deleteOp);
            case IncrementNodeOp incOp -> isValidIncrementOperation(weights, incOp);
            case DecrementNodeOp decOp -> isValidDecrementOperation(weights, decOp);
            // IntelliJ will warn about unused cases, but it is wrong the tests cover them
            //noinspection unused
            case DoubleAllOp doubleOp -> isValidDoubleAllOperation(weights);
            //noinspection unused
            case HalveAllOp halveOp -> isValidHalveAllOperation(weights);
        };
    }

    /// Applies an operation to a set of weights.
    /// 
    /// @param weights the current set of voting weights
    /// @param op the operation to apply
    /// @return a new set of voting weights after applying the operation
    /// @throws IllegalArgumentException if the operation is invalid
    public static Set<VotingWeight> applyOperation(Set<VotingWeight> weights, QuorumOperation op) {
        if (!isValidOperation(weights, op)) {
            throw new IllegalArgumentException("Invalid operation for the given weights");
        }

        return switch (op) {
            case AddNodeOp addOp -> applyAddOperation(weights, addOp);
            case DeleteNodeOp deleteOp -> applyDeleteOperation(weights, deleteOp);
            case IncrementNodeOp incOp -> applyIncrementOperation(weights, incOp);
            case DecrementNodeOp decOp -> applyDecrementOperation(weights, decOp);
            // IntelliJ will warn about unused cases, but it is wrong the tests cover them
            //noinspection unused
            case DoubleAllOp doubleOp -> applyDoubleAllOperation(weights);
            //noinspection unused
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

      return weights.stream()
              .anyMatch(w -> w.nodeId().id() == op.nodeId());
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

  public static List<Set<Short>> splitQuorumsWithLeaderCastingVote(short leaderId,
                                                                   Map<Short, VotingWeight> votingWeights) {
    var filteredWeights = votingWeights.entrySet().stream()
        .filter(entry -> entry.getValue().weight() > 0 && entry.getKey() != leaderId)
        .collect(Collectors.toUnmodifiableMap(
            Map.Entry::getKey,
            Map.Entry::getValue
        ));

    if (filteredWeights.isEmpty()) {
      return List.of(Set.of(), Set.of());
    }

    var totalWeight = filteredWeights.values().stream()
        .mapToInt(VotingWeight::weight)
        .sum();

    var quorumThreshold = (totalWeight / 2) + 1;

    var nodes = filteredWeights.keySet();

    // Pass leaderId to the method
    var splitOpt = QuorumSplitGenerator.generateSplit(nodes, quorumThreshold, filteredWeights, votingWeights.get(leaderId));

    if (splitOpt.isPresent()) {
      var splitResult = splitOpt.get();
      return List.of(splitResult.setA(), splitResult.setB());
    }

    return List.of(Set.of(), Set.of());
  }
}

class QuorumSplitGenerator {
  private QuorumSplitGenerator() {} // Package-private constructor

  static Optional<SplitResult> generateSplit(Set<Short> nodes,
                                             int quorumThreshold,
                                             Map<Short, VotingWeight> votingWeights,
                                             VotingWeight laderVote) {
    // For small node sets, try all possible partitions
    List<Short> nodesList = new ArrayList<>(nodes);
    int n = nodesList.size();

    // This outer loop iterates through all binary numbers from 0 to 2^n - 1.
    // 	•	`1 << n` is a bit shift operation that calculates 2^n
    // 	•	For example, if n=3, this loop runs from 0 to 7 (binary: 000, 001, 010, 011, 100, 101, 110, 111)
    for (int i = 0; i < (1 << n); i++) {
      Set<Short> setA = new HashSet<>();
      Set<Short> setB = new HashSet<>();

      // Assign nodes to sets based on bits
      // `(i & (1 << j)) != 0` checks if the j-th bit of i is set (equals 1)
      for (int j = 0; j < n; j++) {
        if ((i & (1 << j)) != 0) {
          setA.add(nodesList.get(j));
        } else {
          setB.add(nodesList.get(j));
        }
      }

      // Calculate weights
      int setAWeight = setA.stream()
          .mapToInt(node -> votingWeights.get(node).weight())
          .sum();

      int setBWeight = setB.stream()
          .mapToInt(node -> votingWeights.get(node).weight())
          .sum();

      // Check if both sets form valid quorums with leader
      if (setAWeight + laderVote.weight() >= quorumThreshold &&
          setBWeight + laderVote.weight() >= quorumThreshold) {
        return Optional.of(new SplitResult(
            Set.copyOf(setA),
            Set.copyOf(setB)
        ));
      }
    }

    return Optional.empty();
  }
}

record SplitResult(Set<Short> setA, Set<Short> setB) {
}

