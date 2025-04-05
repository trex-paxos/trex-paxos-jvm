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
