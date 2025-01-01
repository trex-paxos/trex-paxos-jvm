package com.github.trex_paxos.paxe;

public record NodeId(short value) implements Comparable<NodeId> {
  public NodeId {
      if (value < 0) throw new IllegalArgumentException("Node ID must be non-negative");
  }
  
  @Override
  public int compareTo(NodeId other) {
      return Short.compare(value, other.value);
  }

  @Override
  public String toString() {
    return "Node-" + value();
  }
}
