package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import com.github.trex_paxos.advisory_locks.server.LockServerReturnValue;
import com.github.trex_paxos.advisory_locks.server.LockServerTrexEngine;
import com.github.trex_paxos.advisory_locks.store.LockStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class TestLockServerTrexEngine extends LockServerTrexEngine {

  private static final Logger LOGGER = Logger.getLogger(TestLockServerTrexEngine.class.getName());

  private final Map<String, CompletableFuture<LockServerReturnValue>> pendingCommands = new HashMap<>();

  public TestLockServerTrexEngine(TrexNode node,
                                  LockStore lockStore,
                                  ScheduledExecutorService scheduler,
                                  Consumer<List<TrexMessage>> networkOutboundSockets
  ) {
    super(node, lockStore, scheduler, networkOutboundSockets);
  }

  @Override
  protected void setRandomTimeout() {
    // Do nothing - no timeouts needed for single node
  }

  @Override
  protected void clearTimeout() {
    // Do nothing - no timeouts needed for single node
  }

  @Override
  protected void setHeartbeat() {
    // Do nothing - no heartbeat needed for single node
  }

  public Optional<Prepare> timeoutForTest() {
    return timeout();
  }

  public Byte nodeId() {
    return trexNode().nodeIdentifier();
  }
  public Progress getProgress() {
    return this.trexNode().progress;
  }
}
