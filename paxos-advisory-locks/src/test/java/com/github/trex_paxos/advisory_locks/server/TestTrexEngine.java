package com.github.trex_paxos.advisory_locks.server;

import com.github.trex_paxos.Progress;
import com.github.trex_paxos.TrexEngine;
import com.github.trex_paxos.TrexNode;
import com.github.trex_paxos.msg.Prepare;

import java.util.Optional;

public class TestTrexEngine extends TrexEngine {
  public Optional<Prepare> timeoutForTest() {
    return timeout();
  }

  public TestTrexEngine(TrexNode trexNode) {
    super(trexNode);
  }

  @Override
  protected void setRandomTimeout() {

  }

  @Override
  protected void clearTimeout() {

  }

  @Override
  protected void setNextHeartbeat() {

  }

  public Progress getProgress() {
    return trexNode().progress();
  }
}
