package com.github.trex_paxos.advisory_locks.server;

import com.github.trex_paxos.TrexEngine;
import com.github.trex_paxos.TrexNode;
import com.github.trex_paxos.msg.TrexMessage;
import com.github.trex_paxos.advisory_locks.store.LockStore;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public abstract class LockServerTrexEngine extends TrexEngine {
  private final ScheduledExecutorService scheduler;

  public LockServerTrexEngine(TrexNode node,
                              LockStore lockStore,
                              ScheduledExecutorService scheduler,
                              Consumer<List<TrexMessage>> networkOutboundSockets
  ) {
    super(node, networkOutboundSockets);
    this.scheduler = scheduler;
  }

  @Override
  protected void setRandomTimeout() {
    scheduler.schedule(this::timeout, 500 + (long)(Math.random() * 500), TimeUnit.MILLISECONDS);
  }

  @Override
  protected void clearTimeout() {
    // scheduler cleanup would go here
  }

  @Override
  protected void setHeartbeat() {
    scheduler.schedule(this::heartbeat, 250, TimeUnit.MILLISECONDS);
  }
}
