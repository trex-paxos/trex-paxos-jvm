package com.github.trex_paxos.msg;

public enum NoOperation implements AbstractCommand {
  NOOP;

  @Override
  public String toString() {
    return "NOOP";
  }
}
