package com.github.trex_paxos.paxe;

public record NodeId(short id) implements Comparable<NodeId> {
  public NodeId {
      if (id < 0) throw new IllegalArgumentException("Node ID must be non-negative");
  }
  
  @Override
  public int compareTo(NodeId other) {
    return Short.compare(id, other.id);
  }
}
