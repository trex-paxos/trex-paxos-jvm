package com.github.trex_paxos.paxe;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record ClusterMembership(Map<NodeId, Integer> nodePorts) {
  public ClusterMembership {
    nodePorts = Map.copyOf(nodePorts); // Defensive copy
  }

  public Set<NodeId> otherNodes(NodeId self) {
    return nodePorts.keySet().stream()
        .filter(id -> !id.equals(self))
        .collect(Collectors.toUnmodifiableSet());
  }
}