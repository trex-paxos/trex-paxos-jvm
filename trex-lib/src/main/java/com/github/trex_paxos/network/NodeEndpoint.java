// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.network;

import com.github.trex_paxos.NodeId;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/// The cluster membership information which may be changed at runtime using UPaxos for dynamic membership
/// changes without stalls. 
public record NodeEndpoint(Map<NodeId, NetworkAddress> nodeAddresses) {
  public NodeEndpoint {
    nodeAddresses = Map.copyOf(nodeAddresses); // Defensive copy
  }

  public Set<NodeId> otherNodes(NodeId self) {
    return nodeAddresses.keySet().stream()
        .filter(node -> !node.equals(self))
        .collect(Collectors.toSet());
  }

  public Optional<NetworkAddress> addressFor(NodeId node) {
    return Optional.ofNullable(nodeAddresses.get(node));
  }
}
