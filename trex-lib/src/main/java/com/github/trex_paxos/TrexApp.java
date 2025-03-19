package com.github.trex_paxos;

import com.github.trex_paxos.msg.DirectMessage;
import com.github.trex_paxos.msg.Fixed;
import com.github.trex_paxos.msg.TrexMessage;
import com.github.trex_paxos.network.ClusterMembership;
import com.github.trex_paxos.network.NetworkLayer;
import com.github.trex_paxos.network.NodeId;
import org.jetbrains.annotations.TestOnly;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static com.github.trex_paxos.TrexLogger.LOGGER;
import static com.github.trex_paxos.network.SystemChannel.CONSENSUS;
import static com.github.trex_paxos.network.SystemChannel.PROXY;

public class TrexApp<COMMAND, RESULT> {

  protected class LeaderTracker {
    volatile NodeId estimatedLeader = null;

    void updateFromFixed(Fixed msg) {
      var knownLeader = new NodeId(msg.leader());
      if (!Objects.equals(estimatedLeader, knownLeader)) {
        LOGGER.fine(() -> engine.trexNode.nodeIdentifier() + " role changed from " + estimatedLeader + " to " + knownLeader);
        estimatedLeader = knownLeader;
      }
    }

    Optional<NodeId> currentLeader() {
      return Optional.ofNullable(estimatedLeader);
    }
  }

  private class ResponseTracker<R> {
    private final Map<UUID, CompletableFuture<R>> pending = new ConcurrentHashMap<>();

    void track(UUID id, CompletableFuture<R> future) {
      LOGGER.finer(() -> engine.trexNode.nodeIdentifier() + " tracking response for " + id);
      pending.put(id, future);
    }

    void complete(UUID id, R result) {
      pending.computeIfPresent(id, (_, f) -> {
        f.complete(result);
        LOGGER.fine(() -> engine.trexNode.nodeIdentifier() + " complete " + id);
        return null;
      });
    }

    void fail(UUID id, Throwable ex) {
      pending.computeIfPresent(id, (_, f) -> {
        f.completeExceptionally(ex);
        LOGGER.fine(() -> engine.trexNode.nodeIdentifier() + " fail " + id);
        return null;
      });
    }

    void remove(UUID id) {
      pending.remove(id);
    }
  }

  protected final TrexEngine<RESULT> engine;
  protected final NetworkLayer networkLayer;
  protected final Supplier<ClusterMembership> clusterMembershipSupplier;
  final protected LeaderTracker leaderTracker = new LeaderTracker();
  final ResponseTracker<RESULT> responseTracker = new ResponseTracker<>();
  protected final Pickler<COMMAND> valuePickler;
  public final NodeId nodeId;

  public TrexApp(
      Supplier<ClusterMembership> clusterMembershipSupplier,
      TrexEngine<RESULT> engine,
      NetworkLayer networkLayer,
      Pickler<COMMAND> valuePickler) {
    this.engine = engine;
    this.networkLayer = networkLayer;
    this.clusterMembershipSupplier = clusterMembershipSupplier;
    this.valuePickler = valuePickler;
    this.nodeId = new NodeId(engine.nodeIdentifier());
  }

  public void start() {
    if (engine.isLeader()) {
      leaderTracker.updateFromFixed(new Fixed(engine.nodeIdentifier(), 0L, BallotNumber.MIN));
    }
    networkLayer.subscribe(CONSENSUS.value(), this::handleConsensusMessage, "consensus-" + engine.nodeIdentifier());
    networkLayer.subscribe(PROXY.value(), this::handleProxyMessage, "proxy-" + engine.nodeIdentifier());
    networkLayer.start();
  }

  private List<TrexMessage> createLeaderMessages(Command cmd) {
    return engine.nextLeaderBatchOfMessages(List.of(cmd));
  }

  void handleConsensusMessage(TrexMessage msg) {
    if (msg == null || msg.from() == engine.nodeIdentifier()) {
      LOGGER.finer(() -> engine.nodeIdentifier() + " is dropping consensus message " + msg);
      return;
    }

    var messages = paxosThenUpCall(List.of(msg));
    LOGGER.finer(() -> engine.nodeIdentifier() + " has processed " + msg + " and is responding with " + messages);
    messages.stream()
        .filter(m -> m instanceof Fixed)
        .map(m -> (Fixed) m)
        .forEach(leaderTracker::updateFromFixed);

    transmitTrexMessages(messages);
  }

  void handleProxyMessage(Command cmd) {
    if (!engine.isLeader()) {
      LOGGER.finest(() -> String.format("[Node %d] Not leader, dropping proxy: %s", nodeId.id(), cmd.uuid()));
      return;
    }
    LOGGER.fine(() -> engine.nodeIdentifier() + " leader is has received proxied message " + cmd.uuid());
    try {
      var messages = createLeaderMessages(cmd);
      transmitTrexMessages(messages);
    } catch (Exception e) {
      LOGGER.severe(() -> engine.nodeIdentifier() + " handleProxyMessage failed: " + e.getMessage());
    }
  }

  public void submitValue(COMMAND value, CompletableFuture<RESULT> future) {
    final var uuid = UUIDGenerator.generateUUID();
    try {
      responseTracker.track(uuid, future);

      if (engine.isLeader()) {
        byte[] valueBytes = valuePickler.serialize(value);
        final var cmd = new Command(uuid, valueBytes);
        final var messages = createLeaderMessages(cmd);
        LOGGER.fine(() -> engine.nodeIdentifier() + " leader is sending accept messages " + messages);
        transmitTrexMessages(messages);
      } else {
        leaderTracker.currentLeader().ifPresentOrElse(
            leader -> {
              LOGGER.fine(() -> engine.nodeIdentifier() + " " + engine.trexNode.getRole() + " is proxying cmd messages" + value);
              byte[] valueBytes = valuePickler.serialize(value);
              Command cmd = new Command(uuid, valueBytes);
              networkLayer.send(PROXY.value(), leader, cmd);
            },
            () -> {
              var ex = new IllegalStateException("No leader available");
              LOGGER.warning(() -> engine.nodeIdentifier() + " " + engine.trexNode.getRole() + " failed to proxy cmd messages " + value + " because: " + ex.getMessage());
              responseTracker.fail(uuid, ex);
              responseTracker.remove(uuid);
            }
        );
      }
    } catch (Exception e) {
      responseTracker.fail(uuid, e);
      responseTracker.remove(uuid);
    }
  }

  /// Runs the Paxos algorithm over a list of messages then transmits any resulting messages.
  /// This method will side effect by updating the journal as necessary and also calling the host application
  /// callback if any commands are fixed.
  /// @param messages The input TrexMessages to process
  /// @return List of outbound TrexMessages to be sent to other nodes
  List<TrexMessage> paxosThenUpCall(List<TrexMessage> messages) {
    LOGGER.finer(() -> engine.nodeIdentifier() + " paxosThenUpCall input: " + messages);
    EngineResult<RESULT> result = engine.paxos(messages);
    result.results().forEach(hostResult -> {
      LOGGER.fine(() -> engine.nodeIdentifier() + " paxosThenUpCall completing callback for " + hostResult.uuid());
      responseTracker.complete(hostResult.uuid(), hostResult.result());
    });
    final var response = result.messages();
    LOGGER.finer(() -> engine.nodeIdentifier() + " paxosThenUpCall output: " + response);
    return response;
  }

  /// Transmits a list of Trex messages across the network to their respective destinations.
  /// DirectMessage instances are sent to specific nodes while other messages are broadcast.
  /// Delegates to the NetworkLayer to handle actual transmission and ClusterMembership for broadcasts.
  ///
  /// @param messages The list of Trex messages to be transmitted
  private void transmitTrexMessages(List<TrexMessage> messages) {
    messages.forEach(message -> {
      if (message instanceof DirectMessage directMessage) {
        LOGGER.finer(() -> engine.nodeIdentifier() + " sending direct message " + directMessage);
        networkLayer.send(CONSENSUS.value(), new NodeId(directMessage.to()), message);
      } else {
        LOGGER.finer(() -> engine.nodeIdentifier() + " broadcasting message " + message);
        networkLayer.broadcast(clusterMembershipSupplier, CONSENSUS.value(), message);
      }
    });
  }

  public void stop() {
    try {
      networkLayer.close();
    } catch (Exception e) {
      // ignore
    } finally {
      engine.close();
    }
  }

  @SuppressWarnings("SameParameterValue")
  @TestOnly
  protected void setLeader(short i) {
    if (i == engine.nodeIdentifier()) {
      engine.setLeader();
    }
    leaderTracker.estimatedLeader = new NodeId(i);
  }
}
