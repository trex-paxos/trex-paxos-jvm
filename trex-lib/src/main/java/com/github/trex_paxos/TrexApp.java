package com.github.trex_paxos;

import com.github.trex_paxos.msg.DirectMessage;
import com.github.trex_paxos.msg.Fixed;
import com.github.trex_paxos.msg.TrexMessage;
import com.github.trex_paxos.network.Channel;
import com.github.trex_paxos.network.ClusterMembership;
import com.github.trex_paxos.network.PickleMsg;
import com.github.trex_paxos.network.TrexNetwork;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class TrexApp<VALUE, RESULT> {
  private static final Logger LOGGER = Logger.getLogger(TrexApp.class.getName());

  private final TrexEngine engine;
  private final Pickler<VALUE> cmdSerde;
  private final TrexNetwork trexNetwork;
  private final Map<UUID, CompletableFuture<RESULT>> pendingResponses = new ConcurrentHashMap<>();
  private final Function<VALUE, RESULT> serverFunction;
  private final Supplier<ClusterMembership> clusterMembershipSupplier;

  protected volatile Short estimatedLeader = -1;

  public TrexApp(
      Supplier<ClusterMembership> clusterMembershipSupplier,
      TrexEngine engine,
      Pickler<VALUE> cmdSerde,
      TrexNetwork trexNetwork,
      Function<VALUE, RESULT> serverFunction) {
    this.engine = engine;
    this.cmdSerde = cmdSerde;
    this.trexNetwork = trexNetwork;
    this.serverFunction = serverFunction;
    this.clusterMembershipSupplier = clusterMembershipSupplier;
  }

  public void start() {
    // Subscribe to consensus and proxy channels
    trexNetwork.subscribe(Channel.CONSENSUS, this::handleConsensusMessage);
    trexNetwork.subscribe(Channel.PROXY, this::handleProxyMessage);
    trexNetwork.start();
    engine.start();
    if (engine.isLeader()) {
      LOGGER.fine(this.engine.nodeIdentifier() + " start as leader");
      estimatedLeader = engine.nodeIdentifier();
    } else {
      LOGGER.fine(this.engine.nodeIdentifier() + " start as follower");
    }
  }

  protected void handleConsensusMessage(ByteBuffer msg) {
    LOGGER.finer(this.engine.nodeIdentifier() + " handleConsensusMessage");
    try {
      TrexMessage trexMsg = PickleMsg.unpickle(msg);
      if (trexMsg == null || trexMsg.from() == engine.nodeIdentifier()) {
        LOGGER.finer(() -> this.engine.nodeIdentifier() + " handleConsensusMessage Ignoring message from self");
        return;
      }
      LOGGER.finer(this.engine.nodeIdentifier() + " handleConsensusMessage got message " + trexMsg);
      var messages = paxosThenUpCall(List.of(trexMsg));
      LOGGER.finer(() -> String.format(this.engine.nodeIdentifier() + " handleConsensusMessage Generated %d results", messages.size()));
      messages.stream()
          .filter(m -> m instanceof Fixed)
          .findFirst()
          .ifPresent(fixed -> {
            var fixedMsg = (Fixed) fixed;
            var knownLeader = fixedMsg.leader();
            if (!Objects.equals(estimatedLeader, knownLeader)) {
              estimatedLeader = knownLeader;
              LOGGER.fine(() -> String.format(this.engine.nodeIdentifier() + " handleConsensusMessage fixed message new estimatedLeader is %d", estimatedLeader));
            }
          });
      for (TrexMessage message : messages) {
        final var pickled = PickleMsg.pickle(message);
        if (message instanceof DirectMessage directMessage) {
          trexNetwork.send(Channel.CONSENSUS, directMessage.to(), ByteBuffer.wrap(pickled));
        } else {
          final var membership = clusterMembershipSupplier.get();
          for (var node : membership.otherNodes(engine.nodeIdentifier())) {
            trexNetwork.send(Channel.CONSENSUS, node, ByteBuffer.wrap(pickled));
          }
        }
      }
    } catch (Exception e) {
      LOGGER.severe(this.engine.nodeIdentifier() + " handleConsensusMessage " + e.getMessage());
    }
  }

  protected void handleProxyMessage(ByteBuffer msg) {
    if (!engine.isLeader()) {
      LOGGER.finer(this.engine.nodeIdentifier() + " handleProxyMessage Ignoring proxy message - not leader");
      return;
    }
    try {
      UUID uuid = UUIDGenerator.generateUUID();
      Command cmd = new Command(uuid, msg.array());

      var messages = engine.nextLeaderBatchOfMessages(List.of(cmd));
      LOGGER.fine(() -> String.format(this.engine.nodeIdentifier() + " handleProxyMessage Generated %d messages for command uuid=%s", messages.size(), uuid));
      for (TrexMessage message : messages) {
        final var pickled = PickleMsg.pickle(message);
        if (message instanceof DirectMessage directMessage) {
          trexNetwork.send(Channel.CONSENSUS, directMessage.to(), ByteBuffer.wrap(pickled));
        } else {
          final var membership = clusterMembershipSupplier.get();
          for (var node : membership.otherNodes(engine.nodeIdentifier())) {
            trexNetwork.send(Channel.CONSENSUS, node, ByteBuffer.wrap(pickled));
          }
        }
      }
    } catch (Exception e) {
      LOGGER.severe(this.engine.nodeIdentifier() + " handleProxyMessage " + e.getMessage());
    }
  }

  public void processCommand(VALUE value, CompletableFuture<RESULT> future) {
    final var uuid = UUIDGenerator.generateUUID();
    try {
      pendingResponses.put(uuid, future);
      byte[] valueBytes = cmdSerde.serialize(value);
      if (engine.isLeader()) {
        LOGGER.fine(engine.nodeIdentifier() + " processCommand as leader uuid=" + uuid + " valueBytes.length=" + valueBytes.length);
        Command cmd = new Command(uuid, valueBytes);
        var messages = engine.nextLeaderBatchOfMessages(List.of(cmd));
        LOGGER.fine(() -> String.format(this.engine.nodeIdentifier() + " handleProxyMessage Generated %d messages for command uuid=%s", messages.size(), uuid));
        for (TrexMessage message : messages) {
          final var pickled = PickleMsg.pickle(message);
          if (message instanceof DirectMessage directMessage) {
            trexNetwork.send(Channel.CONSENSUS, directMessage.to(), ByteBuffer.wrap(pickled));
          } else {
            final var membership = clusterMembershipSupplier.get();
            for (var node : membership.otherNodes(engine.nodeIdentifier())) {
              trexNetwork.send(Channel.CONSENSUS, node, ByteBuffer.wrap(pickled));
            }
          }
        }
      } else {
        if (estimatedLeader > 0) {
          LOGGER.fine(engine.nodeIdentifier() + " processCommand will proxy with uuid=" + uuid + " valueBytes.length=" + valueBytes.length);
          trexNetwork.send(Channel.PROXY,
              estimatedLeader,
              ByteBuffer.wrap(valueBytes));
        } else {
          LOGGER.warning(engine.nodeIdentifier() + " processCommand no estimated leader to proxy to with uuid=" + uuid + " valueBytes.length=" + valueBytes.length);
        }
      }
    } catch (Exception e) {
      LOGGER.severe(engine.nodeIdentifier() + " processCommand failed to process command: " + e.getMessage());
      pendingResponses.remove(uuid);
      future.completeExceptionally(e);
    }
  }

  protected void upCall(Long slot, Command cmd) {
    try {
      VALUE value = cmdSerde.deserialize(cmd.operationBytes());
      RESULT result = serverFunction.apply(value);

      // Only complete future if this was the originating node
      pendingResponses.computeIfPresent(cmd.uuid(), (_, future) -> {
        future.complete(result);
        return null;
      });
      LOGGER.fine(() -> String.format("%d upCall processed command slot=%d uuid=%s", engine.nodeIdentifier(), slot, cmd.uuid()));
    } catch (Exception e) {
      LOGGER.warning(String.format("%d upCall error processing command slot=%d uuid=%s: %s", engine.nodeIdentifier(), slot, cmd.uuid(), e.getMessage()));
      // Only fail future if this was the originating node
      pendingResponses.computeIfPresent(cmd.uuid(), (_, future) -> {
        future.completeExceptionally(e);
        return null;
      });
    }
  }

  public List<TrexMessage> paxosThenUpCall(List<TrexMessage> messages) {
    var result = engine.paxos(messages);
    if (!result.commands().isEmpty()) {
      result.commands().entrySet().stream()
          .filter(entry -> entry.getValue() instanceof Command)
          .forEach(entry -> upCall(entry.getKey(), (Command) entry.getValue()));
    }
    return result.messages();
  }

  void stop() {
    trexNetwork.stop();
  }
}
