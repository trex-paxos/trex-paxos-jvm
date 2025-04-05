/*
 * Copyright 2024 - 2025 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.trex_paxos.advisory_locks.server;

import com.github.trex_paxos.*;
import com.github.trex_paxos.advisory_locks.store.LockStore;
import com.github.trex_paxos.msg.Accept;
import com.github.trex_paxos.msg.BroadcastMessage;
import com.github.trex_paxos.msg.DirectMessage;
import com.github.trex_paxos.msg.TrexMessage;
import org.h2.mvstore.MVStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LockServerSimulation {
  private static final Logger LOGGER = Logger.getLogger(LockServerSimulation.class.getName());

  private final Map<Byte, TestTrexEngine> engines = new HashMap<>();
  private final Map<Byte, RemoteLockService> servers = new HashMap<>();
  private final Map<Byte, LockStore> lockStores = new HashMap<>();

  public LockServerSimulation(MVStore store, ScheduledExecutorService scheduler) {
    // Setup two nodes
    setupNode((byte) 1, store, scheduler);
    setupNode((byte) 2, store, scheduler);
  }

  public RemoteLockService getServer(byte b) {
    return servers.get(b);
  }

  public LockStore getStore(byte b) {
    return lockStores.get(b);
  }


  private void setupNode(byte nodeId, MVStore store, ScheduledExecutorService scheduler) {
    Journal journal = new MVStoreJournal(store) {{
      writeProgress(new Progress(nodeId, BallotNumber.MIN, 0));
      writeAccept(new Accept(nodeId, 0, BallotNumber.MIN, NoOperation.NOOP));
    }};


    LockStore lockStore = new LockStore(store, Duration.ofSeconds(3));
    lockStores.put(nodeId, lockStore);
    TrexNode node = new TrexNode(Level.INFO, nodeId, new SimpleMajority(2), journal);
    TestTrexEngine engine = new TestTrexEngine(
        node
    );
    RemoteLockService remoteLockService = new RemoteLockService(lockStore, engine, this::deliverMessages);
    engines.put(nodeId, engine);
    servers.put(nodeId, remoteLockService);
  }

  public void deliverMessages(List<TrexMessage> messages) {
    List<TrexMessage> newMessages = new ArrayList<>();
    for (TrexMessage msg : messages) {
      if (msg instanceof DirectMessage dm) {
        final var server = servers.get(dm.to());
        LOGGER.info("Delivering direct message: " + dm + " to node " + server.nodeId());
        var outboundMessages = server.paxosThenUpCall(List.of(dm));
        newMessages.addAll(outboundMessages);
      } else if (msg instanceof BroadcastMessage bm) {
        for (RemoteLockService server : servers.values()) {
          if (server.nodeId() == bm.from()) {
            continue;
          }
          LOGGER.info("Delivering broadcast message: " + bm + " to node " + server.nodeId());
          var outboundMessages = server.paxosThenUpCall(List.of(bm));
          newMessages.addAll(outboundMessages);
        }
      }
    }
    if (!newMessages.isEmpty()) {
      deliverMessages(newMessages);
    }
  }

  public TestTrexEngine getEngine(byte nodeId) {
    return engines.get(nodeId);
  }
}
