package com.github.trex_paxos;

import com.github.trex_paxos.msg.TrexMessage;
import com.github.trex_paxos.network.Channel;
import com.github.trex_paxos.network.PickleMsg;
import com.github.trex_paxos.network.TrexNetwork;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

public class TrexApp<VALUE, RESULT> {
  private static final Logger LOGGER = Logger.getLogger(TrexApp.class.getName());

  private final TrexEngine engine;
  private final Pickler<VALUE> cmdSerde;
  private final TrexNetwork trexNetwork;
  private final Map<UUID, CompletableFuture<RESULT>> pendingResponses = new ConcurrentHashMap<>();
  private final Function<VALUE, RESULT> serverFunction;

  public TrexApp(TrexEngine engine,
                 Pickler<VALUE> cmdSerde,
                 TrexNetwork trexNetwork,
                 Function<VALUE, RESULT> serverFunction) {
    this.engine = engine;
    this.cmdSerde = cmdSerde;
    this.trexNetwork = trexNetwork;
    this.serverFunction = serverFunction;
  }

  public void start() {
    // Subscribe to consensus and proxy channels
    trexNetwork.subscribe(Channel.CONSENSUS, this::handleConsensusMessage);
    trexNetwork.subscribe(Channel.PROXY, this::handleProxyMessage);
    trexNetwork.start();
    engine.start();
  }

  private void handleConsensusMessage(ByteBuffer msg) {
    TrexMessage trexMsg = PickleMsg.unpickle(msg);
    var results = paxosThenUpCall(List.of(trexMsg));
    for (TrexMessage result : results) {
      trexNetwork.send(Channel.CONSENSUS,
          ByteBuffer.wrap(PickleMsg.pickle(result)));
    }
  }

  private void handleProxyMessage(ByteBuffer msg) {
    UUID uuid = UUIDGenerator.generateUUID();
    Command cmd = new Command(uuid, msg.array());

    var messages = engine.nextLeaderBatchOfMessages(List.of(cmd));
    for (TrexMessage message : messages) {
      trexNetwork.send(Channel.CONSENSUS,
          ByteBuffer.wrap(PickleMsg.pickle(message)));
    }
  }

  public void processCommand(VALUE value, CompletableFuture<RESULT> future) {
    UUID uuid = null;
    try {
      uuid = UUIDGenerator.generateUUID();
      pendingResponses.put(uuid, future);

      byte[] valueBytes = cmdSerde.serialize(value);

      if (engine.isLeader()) {
        Command command = new Command(uuid, valueBytes);
        var messages = engine.nextLeaderBatchOfMessages(List.of(command));
        for (TrexMessage msg : messages) {
          trexNetwork.send(Channel.CONSENSUS,
              ByteBuffer.wrap(PickleMsg.pickle(msg)));
        }
      } else {
        trexNetwork.send(Channel.PROXY,
            ByteBuffer.wrap(valueBytes));
      }
    } catch (Exception e) {
      pendingResponses.remove(uuid);
      future.completeExceptionally(e);
    }
  }

  protected void upCall(Long slot, Command cmd) {
    VALUE value = cmdSerde.deserialize(cmd.operationBytes());
    RESULT result = serverFunction.apply(value);
    pendingResponses.computeIfPresent(cmd.uuid(), (_, future) -> {
      future.complete(result);
      return null;
    });
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
