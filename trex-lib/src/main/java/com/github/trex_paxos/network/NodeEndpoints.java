// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.network;

import com.github.trex_paxos.NodeId;

import java.util.Map;
import java.util.Optional;

/// This is a mapping to the network addresses of the nodes in the cluster. Moving a node between networks
/// is not considered a reconfiguration from the point of consensus. Until the node is reachable it is simply
/// down. Safety is maintain as long as we do not have two nodes with the same ID on the same network and that
/// they do not forget their promises.
public record NodeEndpoints(Map<NodeId, NetworkAddress> nodeAddresses) {
  public NodeEndpoints {
    nodeAddresses = Map.copyOf(nodeAddresses); // Defensive copy
  }

  public Optional<NetworkAddress> addressFor(NodeId node) {
    return Optional.ofNullable(nodeAddresses.get(node));
  }
}
