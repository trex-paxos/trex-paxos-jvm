package com.github.trex_paxos.network;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/// The cluster membership information which may be changed at runtime using UPaxos for dynamic membership
/// changes without stalls. 
public record ClusterMembership(Map<Short, NetworkAddress> nodeAddresses) {
  public ClusterMembership {
    nodeAddresses = Map.copyOf(nodeAddresses); // Defensive copy
  }

  public Set<Short> otherNodes(Short self) {
    return nodeAddresses.keySet().stream()
        .filter(node -> !node.equals(self))
        .collect(Collectors.toSet());
  }

  public Optional<NetworkAddress> addressFor(NodeId node) {
    return Optional.ofNullable(nodeAddresses.get(node));
  }
}
