package com.github.trex_paxos;

import com.github.trex_paxos.msg.Prepare;
import com.github.trex_paxos.msg.TrexMessage;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class TestLockServerTrexEngine extends TrexEngine {

  public TestLockServerTrexEngine(TrexNode node,
                                  Consumer<List<TrexMessage>> networkOutboundSockets
  ) {
    super(node, networkOutboundSockets);
  }

  public Optional<Prepare> timeoutForTest() {
    return timeout();
  }

  public Progress getProgress() {
    return this.trexNode().progress;
  }

  @Override
  protected void setRandomTimeout() {
    // no-op
  }

  @Override
  protected void clearTimeout() {
    // no-op
  }

  @Override
  protected void setHeartbeat() {
    // no-op
  }
}
