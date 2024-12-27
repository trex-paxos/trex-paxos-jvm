package com.github.trex_paxos.advisory_locks;

public record NodeId(byte id) {
  @Override
  public String toString() {
    return "Node-" + id;
  }
}
