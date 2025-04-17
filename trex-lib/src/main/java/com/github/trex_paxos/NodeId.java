// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

public record NodeId(short id) implements Comparable<NodeId> {
  public NodeId {
      if (id < 0) throw new IllegalArgumentException("Node ID must be non-negative");
  }
  
  @Override
  public int compareTo(NodeId other) {
    return Short.compare(id, other.id);
  }
}
