package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import com.github.trex_paxos.advisory_locks.server.LockServerTrexEngine;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

public class TestLockServerTrexEngine extends LockServerTrexEngine {

  public TestLockServerTrexEngine(TrexNode node,
                                  ScheduledExecutorService scheduler,
                                  Consumer<List<TrexMessage>> networkOutboundSockets
  ) {
    super(node, scheduler, networkOutboundSockets);
  }

  public Optional<Prepare> timeoutForTest() {
    return timeout();
  }

  public Progress getProgress() {
    return this.trexNode().progress;
  }
}
