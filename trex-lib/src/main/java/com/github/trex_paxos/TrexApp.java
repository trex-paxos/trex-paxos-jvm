package com.github.trex_paxos;

import com.github.trex_paxos.msg.TrexMessage;
import com.github.trex_paxos.network.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

public class TrexApp<T, R> implements Network.MessageHandler {
    private static final Logger LOGGER = Logger.getLogger(TrexApp.class.getName());

    protected final TrexEngine engine;
    protected final SerDe<T> serdeCmd;
    protected final Network network;
    protected final ConcurrentNavigableMap<UUID, CompletableFuture<R>> pendingResponses = new ConcurrentSkipListMap<>();
    protected Function<T, R> serverFunction;

    public TrexApp(TrexEngine engine, SerDe<T> serdeCmd, Network network) {
        this.engine = engine;
        this.serdeCmd = serdeCmd;
        this.network = network;
        this.network.subscribe(this);
    }

    public void setServerFunction(Function<T, R> serverFunction) {
        this.serverFunction = serverFunction;
    }

    @Override
    public void onMessage(Network.Message message) {
        try {
            if (message.channel().equals(Channel.CONSENSUS)) {
                handleConsensusMessage(message);
            } else if (message.channel().equals(Channel.PROXY) && engine.isLeader()) {
                handleProxyMessage(message);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error handling message", e);
        }
    }

    private void handleConsensusMessage(Network.Message message) {
        var trexMsg = PickleMsg.unpickle(ByteBuffer.wrap(message.payload()));
        var results = paxosThenUpCall(List.of(trexMsg));
        results.forEach(msg -> network.send(new Network.Message(
            Channel.CONSENSUS,
            new NodeId((short)msg.from()),
            new NodeId((short)(msg.from() == 1 ? 2 : 1)), // TODO: proper routing
            PickleMsg.pickle(msg)
        )));
    }

    private void handleProxyMessage(Network.Message message) {
        Command command = new Command(UUIDGenerator.generateUUID(), message.payload());
        var messages = engine.nextLeaderBatchOfMessages(List.of(command));
        messages.forEach(msg -> network.send(new Network.Message(
            Channel.CONSENSUS,
            new NodeId((short)msg.from()),
            new NodeId((short)(msg.from() == 1 ? 2 : 1)), // TODO: proper routing
            PickleMsg.pickle(msg)
        )));
    }

    public void processCommand(T value, CompletableFuture<R> future) {
        try {
            byte[] valueBytes = serdeCmd.serialize(value);
            UUID uuid = UUIDGenerator.generateUUID();
            pendingResponses.put(uuid, future);
            
            if (engine.isLeader()) {
                Command command = new Command(uuid, valueBytes);
                var messages = engine.nextLeaderBatchOfMessages(List.of(command));
                messages.forEach(msg -> network.send(new Network.Message(
                    Channel.CONSENSUS,
                    new NodeId((short)msg.from()),
                    new NodeId((short)(msg.from() == 1 ? 2 : 1)), // TODO: proper routing
                    PickleMsg.pickle(msg)
                )));
            } else {
                network.send(new Network.Message(
                    Channel.PROXY,
                    new NodeId(engine.nodeIdentifier()),
                    new NodeId((short)1), // TODO: proper leader routing
                    valueBytes
                ));
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    protected void upCall(Long slot, Command cmd) {
        T value = serdeCmd.deserialize(cmd.operationBytes());
        R result = serverFunction.apply(value);
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
                .forEach(entry -> upCall(entry.getKey(), (Command)entry.getValue()));
        }
        return result.messages();
    }
}