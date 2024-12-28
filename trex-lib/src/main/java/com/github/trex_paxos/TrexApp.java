package com.github.trex_paxos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Consumer;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.github.trex_paxos.msg.TrexMessage;

public class TrexApp<T, R> {
    static final Logger LOGGER = Logger.getLogger("");

    protected final TrexEngine engine;
    protected final SerDe<T> serdeCmd;
    protected final Consumer<List<? extends Message>> network;

    public TrexApp(
            TrexEngine engine,
            SerDe<T> serdeCmd,
            Consumer<List<? extends Message>> network) {
        this.engine = engine;
        this.serdeCmd = serdeCmd;
        this.network = network;
    }

    /// We want to lazily set the server function to seperate the setup of the engien from the point where it is used.  
    Function<T, R> serverFunction;

    void setServerFunction(Function<T, R> serverFunction) {
        this.serverFunction = serverFunction;
    }

    private volatile boolean running = false;

    public void shutdown() {
        running = false;
    }

    private final BlockingQueue<? extends Message> messageQueue = new LinkedBlockingQueue<>();

    public void start() {
        running = true;
        engine.start();
        // Message delivery thread
        Thread messageProcessor = new Thread(() -> {
            LOGGER.info("Message processor thread started");
            while (running) {
                try {
                    var first = messageQueue.poll(1, TimeUnit.SECONDS);
                    if (first != null) {
                        // Create lists to collect the different types
                        List<Message> messages = new ArrayList<>();
                        messages.add(first);
                        messageQueue.drainTo(messages); // Drain remaining messages

                        // Partition the messages by type
                        List<TrexMessage> trexMessages = new ArrayList<>();
                        List<Value> values = new ArrayList<>();

                        for (Message msg : messages) {
                            if (msg instanceof TrexMessage trex) {
                                trexMessages.add(trex);
                            } else if (msg instanceof Value value) {
                                values.add(value);
                            }
                        }

                        if (!values.isEmpty() && engine.isLeader()) {
                            final var leaderMessages = engine.nextLeaderBatchOfMessages(values.stream().map(v -> new Command(v.uuid(), v.bytes())).toList());
                            network.accept(messages);
                        }

                        // Process each type if non-empty
                        if (!trexMessages.isEmpty()) {
                            final var paxosMessages = paxosThenUpCall(trexMessages);
                            network.accept(paxosMessages);
                        }
                    }
                } catch (InterruptedException e) {
                    LOGGER.warning("Message processor interrupted");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            LOGGER.info("Message processor thread stopped");
        });
        messageProcessor.setDaemon(true);
        messageProcessor.start();
    }

    /// This will run the Paxos algorithm on the inbound messages. It will return fixed commands and a list of messages
    /// that should be sent out to the network.
    public List<TrexMessage> paxosThenUpCall(List<@NotNull TrexMessage> dm) {
        final var result = engine.paxos(dm);
        if (!result.commands().isEmpty()) {
            result
                    .commands()
                    .entrySet()
                    .stream()
                    .filter(entry -> entry.getValue() instanceof Command)
                    .forEach(entry -> upCall(entry.getKey(), (Command) entry.getValue()));
        }
        return result.messages();
    }

    protected void upCall(Long slot, Command cmd) {
        // unpickle host application command
        final T t = serdeCmd.deserialize(cmd.operationBytes());
        // Process fixed command
        final R r = this.serverFunction.apply(t);

        // do the final up call if we out wa our jvm who initiated the command
        this.replyToClientFutures
                .computeIfPresent(cmd.uuid(), (_, v) -> {
                    v.complete(r);
                    // here returning null removes the entry from the map
                    return null;
                });
    }

    /// We will use virtual threads process client messages and push them out to the other nodes in the Paxos cluster.
    protected final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /// We will keep a map of futures that we will complete when we have run the command value against the lock store.
    /// We actually want to store the future along with the time sent the command and order by time ascending. Then we
    /// can check for old records to see if they have timed out and return exceptionally. We will also need a timeout thread.
    protected final ConcurrentNavigableMap<UUID, CompletableFuture<R>> replyToClientFutures = new ConcurrentSkipListMap<>();

    public void processCommand(final T value, CompletableFuture<R> future) {
        final byte[] valueBytes = serdeCmd.serialize(value);
        final var uuid = UUIDGenerator.generateUUID();
        LOGGER.info(() -> "processCommand value=" + value + " uuid=" + uuid);
        replyToClientFutures.put(uuid, future);
        executor.submit(() -> {
            try {
                if (engine.isLeader()) {
                    final var command = new Command(UUIDGenerator.generateUUID(), valueBytes);
                    final var messages = engine.nextLeaderBatchOfMessages(List.of(command));
                    network.accept(messages);
                } else {
                    // forward to the leader
                    network.accept(List.of(new Value(uuid, valueBytes)));
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Exception processing command: " + e.getMessage(), e);
                future.completeExceptionally(e);
            }
        });
    }
}
