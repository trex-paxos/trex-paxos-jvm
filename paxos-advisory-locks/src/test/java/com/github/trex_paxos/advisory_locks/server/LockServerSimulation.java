package com.github.trex_paxos.advisory_locks.server;

import com.github.trex_paxos.*;
import com.github.trex_paxos.msg.*;
import com.github.trex_paxos.advisory_locks.store.LockStore;
import org.h2.mvstore.MVStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LockServerSimulation
    implements Consumer<List<TrexMessage>>
{
  private static final Logger LOGGER = Logger.getLogger(LockServerSimulation.class.getName());

  private final Map<Byte, TestLockServerTrexEngine> engines = new HashMap<>();
  private final Map<Byte, LockServer> servers = new HashMap<>();

  public LockServerSimulation(MVStore store, ScheduledExecutorService scheduler) {
    // Setup two nodes
    setupNode((byte)1, store, scheduler);
    setupNode((byte)2, store, scheduler);
  }

  public LockServer getServer(byte b) {
    return servers.get(b);
  }

  private void setupNode(byte nodeId, MVStore store, ScheduledExecutorService scheduler) {
    Journal journal = new MVStoreJournal(store) {{
      writeProgress(new Progress(nodeId, BallotNumber.MIN, 0));
      writeAccept(new Accept(nodeId, 0, BallotNumber.MIN, NoOperation.NOOP));
    }};


    LockStore lockStore = new LockStore(store);
    LockServer lockServer = new LockServer(lockStore, ()-> engines.get(nodeId), this);
    TrexNode node = new TrexNode(Level.INFO, nodeId, new SimpleMajority(2), journal);
    TestLockServerTrexEngine engine = new TestLockServerTrexEngine(
        node,
        lockStore,
        scheduler,
        this
    );
    engines.put(nodeId, engine);
    servers.put(nodeId, lockServer);
  }

  public void deliverMessages(List<TrexMessage> messages) {
    List<TrexMessage> newMessages = new ArrayList<>();
    for (TrexMessage msg : messages) {
      if (msg instanceof DirectMessage dm) {
        final var e = servers.get(dm.to());
        LOGGER.info("Delivering direct message: " + dm + " to node " + e.nodeId());
        var outboundMessages = e.paxosThenUpCall(List.of(dm));
        newMessages.addAll(outboundMessages);
      } else if (msg instanceof BroadcastMessage bm) {
        for (LockServer engine : servers.values()) {
          if( engine.nodeId() == bm.from() ) {
            continue;
          }
          LOGGER.info("Delivering broadcast message: " + bm + " to node " + engine.nodeId());
          var outboundMessages = engine.paxosThenUpCall(List.of(bm));
          newMessages.addAll(outboundMessages);
        }
      }
    }
    if (!newMessages.isEmpty()) {
      deliverMessages(newMessages);
    }
  }

  public TestLockServerTrexEngine getEngine(byte nodeId) {
    return engines.get(nodeId);
  }

  @Override
  public void accept(List<TrexMessage> messages) {
    deliverMessages(messages);
  }
}
