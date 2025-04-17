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

import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class UPaxosQuorumStrategyTest {

  @Test
  void validateAddOperation_shouldAcceptValidAdd() {
    Set<VotingWeight> weights = Set.of(
        new VotingWeight((short) 1, 1),
        new VotingWeight((short) 2, 0)
    );

    UPaxosQuorumStrategy.AddNodeOp validOp = new UPaxosQuorumStrategy.AddNodeOp((short) 3, (byte) 1);

    assertTrue(UPaxosQuorumStrategy.isValidOperation(weights, validOp));
  }

  @Test
  void validateAddOperation_shouldRejectInvalidAdd() {
    Set<VotingWeight> weights = Set.of(
        new VotingWeight((short) 1, 1),
        new VotingWeight((short) 2, 0)
    );

    // Node already exists
    UPaxosQuorumStrategy.AddNodeOp invalidOp1 = new UPaxosQuorumStrategy.AddNodeOp((short) 1, (byte) 1);
    // Weight change too large
    UPaxosQuorumStrategy.AddNodeOp invalidOp2 = new UPaxosQuorumStrategy.AddNodeOp((short) 3, (byte) 2);

    assertFalse(UPaxosQuorumStrategy.isValidOperation(weights, invalidOp1));
    assertFalse(UPaxosQuorumStrategy.isValidOperation(weights, invalidOp2));
  }

  @Test
  void validateDeleteOperation_shouldAcceptValidDelete() {
    Set<VotingWeight> weights = Set.of(
        new VotingWeight((short) 1, 1),
        new VotingWeight((short) 2, 1),
        new VotingWeight((short) 3, 1)
    );

    UPaxosQuorumStrategy.DeleteNodeOp validOp = new UPaxosQuorumStrategy.DeleteNodeOp((short) 1);

    assertTrue(UPaxosQuorumStrategy.isValidOperation(weights, validOp));
  }

  @Test
  void validateDeleteOperation_shouldRejectInvalidDeleteNotExist() {
    Set<VotingWeight> weights = Set.of(
        new VotingWeight((short) 1, 1),
        new VotingWeight((short) 2, 0)
    );

    // Node doesn't exist
    UPaxosQuorumStrategy.DeleteNodeOp invalidOp = new UPaxosQuorumStrategy.DeleteNodeOp((short) 3);

    assertFalse(UPaxosQuorumStrategy.isValidOperation(weights, invalidOp));
  }

  @Test
  void validateDeleteOperation_shouldRejectInvalidDeleteNotTooFew() {
    Set<VotingWeight> weights = Set.of(
        new VotingWeight((short) 1, 1),
        new VotingWeight((short) 2, 1)
    );

    // Node doesn't exist
    UPaxosQuorumStrategy.DeleteNodeOp invalidOp = new UPaxosQuorumStrategy.DeleteNodeOp((short) 1);

    assertFalse(UPaxosQuorumStrategy.isValidOperation(weights, invalidOp));
  }

  @Test
  void validateIncrementOperation_shouldAcceptValidIncrement() {
    Set<VotingWeight> weights = Set.of(
        new VotingWeight((short) 1, 1),
        new VotingWeight((short) 2, 0)
    );

    UPaxosQuorumStrategy.IncrementNodeOp validOp = new UPaxosQuorumStrategy.IncrementNodeOp((short) 1);

    assertTrue(UPaxosQuorumStrategy.isValidOperation(weights, validOp));
  }

  @Test
  void validateIncrementOperation_shouldRejectInvalidIncrement() {
    Set<VotingWeight> weights = Set.of(
        new VotingWeight((short) 1, 1),
        new VotingWeight((short) 2, 0)
    );

    // Node doesn't exist
    UPaxosQuorumStrategy.IncrementNodeOp invalidOp = new UPaxosQuorumStrategy.IncrementNodeOp((short) 3);

    assertFalse(UPaxosQuorumStrategy.isValidOperation(weights, invalidOp));
  }

  @Test
  void validateDecrementOperation_shouldAcceptValidDecrement() {
    Set<VotingWeight> weights = Set.of(
        new VotingWeight((short) 1, 2),
        new VotingWeight((short) 2, 1)
    );

    UPaxosQuorumStrategy.DecrementNodeOp validOp = new UPaxosQuorumStrategy.DecrementNodeOp((short) 1);

    assertTrue(UPaxosQuorumStrategy.isValidOperation(weights, validOp));
  }

  @Test
  void validateDecrementOperation_shouldRejectInvalidDecrement() {
    Set<VotingWeight> weights = Set.of(
        new VotingWeight((short) 1, 1),
        new VotingWeight((short) 2, 0)
    );

    // Node has weight 0, can't decrement
    UPaxosQuorumStrategy.DecrementNodeOp invalidOp = new UPaxosQuorumStrategy.DecrementNodeOp((short) 2);

    assertFalse(UPaxosQuorumStrategy.isValidOperation(weights, invalidOp));
  }

  @Test
  void validateDoubleAllOperation_shouldAcceptValidDouble() {
    Set<VotingWeight> weights = Set.of(
        new VotingWeight((short) 1, 1),
        new VotingWeight((short) 2, 0)
    );

    UPaxosQuorumStrategy.DoubleAllOp validOp = new UPaxosQuorumStrategy.DoubleAllOp();

    assertTrue(UPaxosQuorumStrategy.isValidOperation(weights, validOp));
  }

  @Test
  void validateDoubleAllOperation_shouldRejectInvalidDouble() {
    Set<VotingWeight> weights = Set.of(
        new VotingWeight((short) 1, 2),
        new VotingWeight((short) 2, 0)
    );

    // One node has weight 2, can't double
    UPaxosQuorumStrategy.DoubleAllOp invalidOp = new UPaxosQuorumStrategy.DoubleAllOp();

    assertFalse(UPaxosQuorumStrategy.isValidOperation(weights, invalidOp));
  }

  @Test
  void validateHalveAllOperation_shouldAcceptValidHalve() {
    Set<VotingWeight> weights = Set.of(
        new VotingWeight((short) 1, 2),
        new VotingWeight((short) 2, 0)
    );

    UPaxosQuorumStrategy.HalveAllOp validOp = new UPaxosQuorumStrategy.HalveAllOp();

    assertTrue(UPaxosQuorumStrategy.isValidOperation(weights, validOp));
  }

  @Test
  void validateHalveAllOperation_shouldRejectInvalidHalve() {
    Set<VotingWeight> weights = Set.of(
        new VotingWeight((short) 1, 1),
        new VotingWeight((short) 2, 0)
    );

    // One node has weight 1, can't halve
    UPaxosQuorumStrategy.HalveAllOp invalidOp = new UPaxosQuorumStrategy.HalveAllOp();

    assertFalse(UPaxosQuorumStrategy.isValidOperation(weights, invalidOp));
  }

  @Test
  void applyAddOperation_shouldAddNewNode() {
    Set<VotingWeight> weights = Set.of(
        new VotingWeight((short) 1, 1),
        new VotingWeight((short) 2, 0)
    );

    UPaxosQuorumStrategy.AddNodeOp op = new UPaxosQuorumStrategy.AddNodeOp((short) 3, (byte) 1);

    Set<VotingWeight> result = UPaxosQuorumStrategy.applyOperation(weights, op);

    assertEquals(3, result.size());
    assertTrue(result.contains(new VotingWeight((short) 3, 1)));
  }

  @Test
  void applyDeleteOperation_shouldRemoveNode() {
    Set<VotingWeight> weights = Set.of(
        new VotingWeight((short) 1, 1),
        new VotingWeight((short) 2, 1),
        new VotingWeight((short) 3, 1)
    );

    UPaxosQuorumStrategy.DeleteNodeOp op = new UPaxosQuorumStrategy.DeleteNodeOp((short) 1);

    Set<VotingWeight> result = UPaxosQuorumStrategy.applyOperation(weights, op);

    assertEquals(2, result.size());
    assertFalse(result.stream().anyMatch(w -> w.nodeId().id() == 1));
  }

  @Test
  void applyIncrementOperation_shouldIncrementWeight() {
    Set<VotingWeight> weights = Set.of(
        new VotingWeight((short) 1, 1),
        new VotingWeight((short) 2, 0)
    );

    UPaxosQuorumStrategy.IncrementNodeOp op = new UPaxosQuorumStrategy.IncrementNodeOp((short) 1);

    Set<VotingWeight> result = UPaxosQuorumStrategy.applyOperation(weights, op);

    assertEquals(2, result.size());
    assertTrue(result.stream()
        .filter(w -> w.nodeId().id() == 1)
        .anyMatch(w -> w.weight() == 2));
  }

  @Test
  void applyDecrementOperation_shouldDecrementWeight() {
    Set<VotingWeight> weights = Set.of(
        new VotingWeight((short) 1, 2),
        new VotingWeight((short) 2, 1)
    );

    UPaxosQuorumStrategy.DecrementNodeOp op = new UPaxosQuorumStrategy.DecrementNodeOp((short) 1);

    Set<VotingWeight> result = UPaxosQuorumStrategy.applyOperation(weights, op);

    assertEquals(2, result.size());
    assertTrue(result.stream()
        .filter(w -> w.nodeId().id() == 1)
        .anyMatch(w -> w.weight() == 1));
  }

  @Test
  void applyDoubleAllOperation_shouldDoubleAllWeights() {
    Set<VotingWeight> weights = Set.of(
        new VotingWeight((short) 1, 1),
        new VotingWeight((short) 2, 0)
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
        new VotingWeight((short) 1, 2),
        new VotingWeight((short) 2, 0)
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
        new VotingWeight((short) 1, 1),
        new VotingWeight((short) 2, 0)
    );

    // Node doesn't exist
    UPaxosQuorumStrategy.DeleteNodeOp invalidOp = new UPaxosQuorumStrategy.DeleteNodeOp((short) 3);

    assertThrows(IllegalArgumentException.class, () ->
        UPaxosQuorumStrategy.applyOperation(weights, invalidOp));
  }

  @Test
  void testSplitThreeNodesEqualWeights() {
    // Setup voting weights
    Map<Short, VotingWeight> weights = Map.of(
        (short) 1, new VotingWeight((short) 1, 1),
        (short) 2, new VotingWeight((short) 2, 1),
        (short) 3, new VotingWeight((short) 3, 1)
    );

    short leaderId = (short) 1;
    List<Set<Short>> result = UPaxosQuorumStrategy.splitQuorumsWithLeaderCastingVote(leaderId, weights);

    // Verify we got two valid quorums
    assertEquals(2, result.size());

    Set<Short> setA = result.get(0);
    Set<Short> setB = result.get(1);

    // Verify both sets are valid quorums when combined with leader
    int totalWeight = weights.values().stream().mapToInt(VotingWeight::weight).sum();
    int quorumThreshold = (totalWeight / 2) + 1;

    int setAWeight = setA.stream().mapToInt(node -> weights.get(node).weight()).sum();
    int setBWeight = setB.stream().mapToInt(node -> weights.get(node).weight()).sum();

    // Leader's weight is added to both sets
    int leaderWeight = weights.get(leaderId).weight();

    assertTrue(setAWeight + leaderWeight >= quorumThreshold);
    assertTrue(setBWeight + leaderWeight >= quorumThreshold);
  }

  @Test
  void testSplitThreeNodesVaryingWeights() {
    // Setup voting weights
    Map<Short, VotingWeight> weights = Map.of(
        (short) 1, new VotingWeight((short) 1, 2),
        (short) 2, new VotingWeight((short) 2, 1),
        (short) 3, new VotingWeight((short) 3, 1)
    );

    short leaderId = (short) 1;
    List<Set<Short>> result = UPaxosQuorumStrategy.splitQuorumsWithLeaderCastingVote(leaderId, weights);

    assertEquals(2, result.size());

    Set<Short> setA = result.get(0);
    Set<Short> setB = result.get(1);

    int totalWeight = weights.values().stream().mapToInt(VotingWeight::weight).sum();
    int quorumThreshold = (totalWeight / 2) + 1; // = 3;

    int leaderWeight = 2;

    int setAWeight = setA.stream().mapToInt(node -> weights.get(node).weight()).sum();
    int setBWeight = setB.stream().mapToInt(node -> weights.get(node).weight()).sum();

    assertTrue(setAWeight + leaderWeight >= quorumThreshold);
    assertTrue(setBWeight + leaderWeight >= quorumThreshold);
  }

  @Test
  void testComplexWeights() {
    Map<Short, VotingWeight> weights = Map.of(
        (short) 1, new VotingWeight((short) 1, 1),
        (short) 2, new VotingWeight((short) 2, 2),
        (short) 3, new VotingWeight((short) 3, 2),
        (short) 4, new VotingWeight((short) 4, 2),
        (short) 5, new VotingWeight((short) 5, 1),
        (short) 6, new VotingWeight((short) 6, 0));

    short leaderId = weights.values().stream()
        .filter(n -> n.weight() > 1)
        .findFirst()
        .or(() -> weights.values().stream()
            .filter(n -> n.weight() > 0)
            .findFirst())
        .orElseThrow()
        .nodeId().id();

    List<Set<Short>> result = UPaxosQuorumStrategy.splitQuorumsWithLeaderCastingVote(leaderId, weights);

    if (!result.isEmpty() && !result.get(0).isEmpty() && !result.get(1).isEmpty()) {
      int totalWeight = weights.values().stream().mapToInt(VotingWeight::weight).sum();
      int quorumThreshold = (totalWeight / 2) + 1;

      int leaderWeight = weights.get(leaderId).weight();

      int setAWeight = result.get(0).stream()
          .mapToInt(node -> weights.get(node).weight())
          .sum();
      int setBWeight = result.get(1).stream()
          .mapToInt(node -> weights.get(node).weight())
          .sum();

      assertTrue(setAWeight + leaderWeight >= quorumThreshold);
      assertTrue(setBWeight + leaderWeight >= quorumThreshold);
    }
  }

  @Test
  void testSplitSixNodesRandomWeights() {
    // Generate random weights for 6 nodes (0, 1, or 2)
    Random random = new Random(12341234);
    IntStream.range(0, 500).forEach( _ -> {

      Map<Short, VotingWeight> weights = new HashMap<>();

      for (short i = 1; i <= 6; i++) {
        int weight = random.nextInt(3); // 0, 1, or 2
        weights.put(i, new VotingWeight(i, weight));
      }

      short leaderId = weights.values().stream()
          .filter(n -> n.weight() > 1)
          .findFirst()
          .or(() -> weights.values().stream()
              .filter(n -> n.weight() > 0)
              .findFirst())
          .or(() -> weights.values().stream()
              .filter(n -> n.weight() == 0)
              .findFirst())
          .orElseThrow()
          .nodeId().id();

      List<Set<Short>> result = UPaxosQuorumStrategy.splitQuorumsWithLeaderCastingVote(leaderId, weights);

      if (!result.isEmpty() && !result.get(0).isEmpty() && !result.get(1).isEmpty()) {
        int totalWeight = weights.values().stream().mapToInt(VotingWeight::weight).sum();
        int quorumThreshold = (totalWeight / 2) + 1;

        int leaderWeight = weights.get(leaderId).weight();

        int setAWeight = result.get(0).stream()
            .mapToInt(node -> weights.get(node).weight())
            .sum();
        int setBWeight = result.get(1).stream()
            .mapToInt(node -> weights.get(node).weight())
            .sum();

        assertTrue(setAWeight + leaderWeight >= quorumThreshold);
        assertTrue(setBWeight + leaderWeight >= quorumThreshold);
      }
    });
  }

  @Test
  void testNoValidSplit() {
    // Setup where no valid split is possible
    Map<Short, VotingWeight> weights = Map.of(
        (short) 1, new VotingWeight((short) 1, 1),
        (short) 2, new VotingWeight((short) 2, 1)
    );
    short leaderId = (short) 1;

    List<Set<Short>> result = UPaxosQuorumStrategy.splitQuorumsWithLeaderCastingVote(leaderId, weights);

    // Should return empty sets
    assertTrue(result.get(0).isEmpty());
    assertTrue(result.get(1).isEmpty());
  }
}
