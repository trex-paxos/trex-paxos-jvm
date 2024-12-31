package com.github.trex_paxos.paxe;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
/// The cluster membership information which may be changed at runtime using UPaxos for dynamic membership 
/// changes without stalls. 
public record ClusterMembership(Map<NodeId, NetworkAddress> nodeAddresses) {
  public ClusterMembership {
      nodeAddresses = Map.copyOf(nodeAddresses); // Defensive copy
  }

  public Set<NodeId> otherNodes(NodeId self) {
      return nodeAddresses.keySet().stream()
          .filter(id -> !id.equals(self))
          .collect(Collectors.toUnmodifiableSet());
  }
  
  public Optional<NetworkAddress> addressFor(NodeId node) {
      return Optional.ofNullable(nodeAddresses.get(node));
  }
}