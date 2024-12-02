package com.github.trex_paxos;

import java.util.logging.Level;

public class FakeEngine extends TrexEngine {
  public FakeEngine(byte nodeIdentifier, QuorumStrategy quorumStrategy, Journal journal) {
    super(new TrexNode(Level.INFO, nodeIdentifier, quorumStrategy, journal));
  }

  @Override
  protected void setRandomTimeout() {
  }

  @Override
  protected void clearTimeout() {
  }

  @Override
  protected void setHeartbeat() {
  }
}
