package com.github.trex_paxos.paxe;

public record NodeId(byte id) {
  @Override
  public String toString() {
    return "Node-" + id;
  }
}
