package com.github.trex_paxos;

import com.github.trex_paxos.msg.DirectMessage;
import com.github.trex_paxos.msg.Fixed;
import com.github.trex_paxos.msg.TrexMessage;
import com.github.trex_paxos.network.Channel;
import com.github.trex_paxos.network.ClusterMembership;
import com.github.trex_paxos.network.NodeId;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import static com.github.trex_paxos.TrexLogger.LOGGER;

public class TrexApp<VALUE, RESULT> {

  private class LeaderTracker {
    private volatile Short estimatedLeader = -1;

    void updateFromFixed(Fixed msg) {
      var knownLeader = msg.leader();
      if (!Objects.equals(estimatedLeader, knownLeader)) {
        LOGGER.fine(() -> engine.trexNode.nodeIdentifier() +  " role changed from " + estimatedLeader + " to " + knownLeader);
        estimatedLeader = knownLeader;
      }
    }

    Optional<Short> currentLeader() {
      return estimatedLeader > 0 ? Optional.of(estimatedLeader) : Optional.empty();
    }
  }

  private class ResponseTracker<R> {
    private final Map<UUID, CompletableFuture<R>> pending = new ConcurrentHashMap<>();

    void track(UUID id, CompletableFuture<R> future) {
      LOGGER.finer(() -> engine.trexNode.nodeIdentifier() +  " tracking response for " + id);
      pending.put(id, future);
    }

    void complete(UUID id, R result) {
      pending.computeIfPresent(id, (_, f) -> {
        f.complete(result);
        LOGGER.fine(() -> engine.trexNode.nodeIdentifier() +  " complete " + id);
        return null;
      });
    }

    void fail(UUID id, Throwable ex) {
      pending.computeIfPresent(id, (_, f) -> {
        f.completeExceptionally(ex);
        LOGGER.fine(() -> engine.trexNode.nodeIdentifier() +  " fail " + id);
        return null;
      });
    }

    void remove(UUID id) {
      pending.remove(id);
    }
  }

  private final TrexEngine engine;
  private final NetworkLayer networkLayer;
  private final Function<VALUE, RESULT> serverFunction;
  private final Supplier<ClusterMembership> clusterMembershipSupplier;
  private final LeaderTracker leaderTracker = new LeaderTracker();
  private final ResponseTracker<RESULT> responseTracker = new ResponseTracker<>();
  private final Pickler<VALUE> valuePickler;
  public final NodeId nodeId;

  public TrexApp(
      Supplier<ClusterMembership> clusterMembershipSupplier,
      TrexEngine engine,
      NetworkLayer networkLayer,
      Pickler<VALUE> valuePickler,
      Function<VALUE, RESULT> serverFunction) {
    this.engine = engine;
    this.networkLayer = networkLayer;
    this.serverFunction = serverFunction;
    this.clusterMembershipSupplier = clusterMembershipSupplier;
    this.valuePickler = valuePickler;
    this.nodeId = new NodeId(engine.nodeIdentifier());
  }

  public void start() {
    if (engine.isLeader()) {
      leaderTracker.updateFromFixed(new Fixed(engine.nodeIdentifier(), 0L, BallotNumber.MIN));
    }
    engine.start();
    networkLayer.subscribe(Channel.CONSENSUS, this::handleConsensusMessage, "consensus-"+ engine.nodeIdentifier());
    networkLayer.subscribe(Channel.PROXY, this::handleProxyMessage, "proxy-"+ engine.nodeIdentifier());
    networkLayer.start();

  }

  private List<TrexMessage> createLeaderMessages(VALUE value, UUID uuid) {
    byte[] valueBytes = valuePickler.serialize(value);
    Command cmd = new Command(uuid, valueBytes);
    return engine.nextLeaderBatchOfMessages(List.of(cmd));
  }

  void handleConsensusMessage(TrexMessage msg) {
    if (msg == null || msg.from() == engine.nodeIdentifier()) {
      LOGGER.finer(() -> engine.nodeIdentifier() + " is dropping consensus message " + msg);
      return;
    }

    var messages = paxosThenUpCall(List.of(msg));
    LOGGER.finer(() -> engine.nodeIdentifier() + " has processed "+msg+" and is responding with " + messages);
    messages.stream()
        .filter(m -> m instanceof Fixed)
        .map(m -> (Fixed) m)
        .forEach(leaderTracker::updateFromFixed);

    transmitTrexMessages(messages);
  }

  void handleProxyMessage(VALUE value) {
    if (!engine.isLeader()) {
      LOGGER.finer(() -> engine.nodeIdentifier() + " is not leader so is dropping is has received proxied message: " + value);
      return;
    }
    LOGGER.fine(() -> engine.nodeIdentifier() + " leader is has received proxied message " + value);
    try {
      UUID uuid = UUIDGenerator.generateUUID();
      var messages = createLeaderMessages(value, uuid);
      transmitTrexMessages(messages);
    } catch (Exception e) {
      LOGGER.severe(() -> engine.nodeIdentifier() + " handleProxyMessage failed: " + e.getMessage());
    }
  }

  public void submitValue(VALUE value, CompletableFuture<RESULT> future) {
    final var uuid = UUIDGenerator.generateUUID();
    try {
      responseTracker.track(uuid, future);

      if (engine.isLeader()) {
        var messages = createLeaderMessages(value, uuid);
        LOGGER.fine(() -> engine.nodeIdentifier() + " leader is sending accept messages " + messages);
        transmitTrexMessages(messages);
      } else {
        leaderTracker.currentLeader().ifPresentOrElse(
            leader ->{
              LOGGER.fine(() -> engine.nodeIdentifier() + " "+ engine.trexNode.getRole() + " is proxying cmd messages" + value);
              networkLayer.send(Channel.PROXY, leader, value);
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

  void upCall(@SuppressWarnings("unused") Long slot, Command cmd) {
    try {
      VALUE value = valuePickler.deserialize(cmd.operationBytes());
      RESULT result = serverFunction.apply(value);
      responseTracker.complete(cmd.uuid(), result);
    } catch (Exception e) {
      responseTracker.fail(cmd.uuid(), e);
    }
  }

  List<TrexMessage> paxosThenUpCall(List<TrexMessage> messages) {
    LOGGER.fine(() -> engine.nodeIdentifier() + " paxosThenUpCall input: " + messages);
    var result = engine.paxos(messages);
    if (!result.commands().isEmpty()) {
      LOGGER.fine(() -> engine.nodeIdentifier() + " fixed " + result.commands());
      result.commands().entrySet().stream()
          .filter(entry -> entry.getValue() instanceof Command)
          .forEach(entry -> upCall(entry.getKey(), (Command) entry.getValue()));
    }
    final var response = result.messages();
    LOGGER.fine(() -> engine.nodeIdentifier() + " paxosThenUpCall output: " + response);
    return response;
  }

  private void transmitTrexMessages(List<TrexMessage> messages) {
    messages.forEach(message -> {
      if (message instanceof DirectMessage directMessage) {
        LOGGER.finer(() -> engine.nodeIdentifier() + " sending direct message " + directMessage);
        networkLayer.send(Channel.CONSENSUS, directMessage.to(), message);
      } else {
        var others = clusterMembershipSupplier.get().otherNodes(nodeId).stream().map(NodeId::id).collect(Collectors.toSet());
        LOGGER.finer(() -> engine.nodeIdentifier() + " broadcasting message " + message + " to " + others);
        networkLayer.broadcast(Channel.CONSENSUS, message, others);
      }
    });
  }

  public void stop() {
    try {
      networkLayer.stop();
    }
    catch (Exception e) {
      // ignore
    }
    finally {
      engine.close();
    }
  }
}
