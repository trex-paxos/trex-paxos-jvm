package com.github.trex_paxos;

import com.github.trex_paxos.msg.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class StackClusterImpl implements StackService {
    private static final Logger LOGGER = Logger.getLogger(StackClusterImpl.class.getName());
    public static void setLogLevel(Level level) {
        Logger root = Logger.getLogger("");
        root.setLevel(level);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(level);
        root.addHandler(handler);
    }

    private final QuorumStrategy quorum = new SimpleMajority(2);
    private final List<PaxosService<StackService.Command, StackService.Response>> nodes = new ArrayList<>();
    private final BlockingQueue<TrexMessage> messageQueue = new LinkedBlockingQueue<>();
    private volatile boolean running = true;
    private final Stack<String> stack = new Stack<>();

    private final SerDe<Command> commandSerde = new SerDe<>() {
        @Override
        public byte[] serialize(Command cmd) {
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject(cmd);
                return bos.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Command deserialize(byte[] bytes) {
            try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                    ObjectInputStream ois = new ObjectInputStream(bis)) {
                return (Command) ois.readObject();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    };

    private final SerDe<Response> responseSerde = new SerDe<>() {
        @Override
        public byte[] serialize(Response response) {
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject(response);
                return bos.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Response deserialize(byte[] bytes) {
            try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                    ObjectInputStream ois = new ObjectInputStream(bis)) {
                return (Response) ois.readObject();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    };

    public StackClusterImpl() {
        // Set up two nodes with the first one pre-selected as leader
        for (byte i = 1; i <= 2; i++) {
            var journal = new TransparentJournal(i);
            var node = new TrexNode(java.util.logging.Level.INFO, i, quorum, journal);
            if (i == 1) {
                // Force first node to be leader
                node.setLeader();
            }

            var engine = new TrexEngine(node) {
                @Override
                public void clearTimeout() {
                    // No-op
                }

                @Override
                public void setNextHeartbeat() {
                    // No-op
                }

                @Override
                public void setRandomTimeout() {
                    // No-op
                }
            };

            BiFunction<Long, Command, Response> processor = (slot, command) -> {
                LOGGER.finest(String.format("Processing command at slot %d: %s", slot, command));
                synchronized (stack) {
                    try {
                        var response = switch (command) {
                            case Push p -> {
                                LOGGER.finest(() -> "Pushing: " + p.item());
                                stack.push(p.item());
                                yield new Response(Optional.empty());
                            }
                            case Pop _ -> {
                                var item = stack.pop();
                                LOGGER.finest(() -> "Popped: " + item);
                                yield new Response(Optional.of(item));
                            }
                            case Peek _ -> {
                                var item = stack.peek();
                                LOGGER.finest(() -> "Peeked: " + item);
                                yield new Response(Optional.of(item));
                            }
                        };
                        LOGGER.finest(() -> "Command processed with response: " + response);
                        return response;
                    } catch (EmptyStackException e) {
                        LOGGER.warning("Attempted operation on empty stack");
                        return new Response(Optional.of("Stack is empty"));
                    }
                }
            };

            var paxos = new PaxosService<>(engine, processor, commandSerde, responseSerde,
                    messages -> {
                        LOGGER.finest(() -> "Outbound messages: " + messages);
                        messageQueue.addAll(messages);
                    });
            nodes.add(paxos);
            engine.start();
        }

        // Message delivery thread
        Thread messageProcessor = new Thread(() -> {
            LOGGER.info("Message processor thread started");
            while (running) {
                try {
                    TrexMessage msg = messageQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (msg != null) {
                        LOGGER.finest(() -> "Processing message: " + msg);
                        for (var node : nodes) {
                            LOGGER.finest(() -> "Delivering to node " + node);
                            var responses = node.paxosThenUpCall(List.of(msg));
                            if (!responses.isEmpty()) {
                                LOGGER.finest(String.format("Node %d responded with: %s",
                                    node.nodeId(), responses));
                                messageQueue.addAll(responses);
                            }
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

    @Override
    public Response push(String item) {
        LOGGER.finest(() -> "Push requested: " + item);
        var future = new CompletableFuture<Response>();
        nodes.get(0).processCommand(new Push(item), future);
        try {
            var response = future.get(5, TimeUnit.SECONDS);
            LOGGER.finest(() -> "Push completed with response: " + response);
            return response;
        } catch (Exception e) {
            // Add proper exception handling with cause
            String errorMsg = String.format("Error: %s (Type: %s)",
                    e.getMessage() != null ? e.getMessage() : "No message",
                    e.getClass().getSimpleName());
            return new Response(Optional.of(errorMsg));
        }
    }

    @Override
    public Response pop() {
        var future = new CompletableFuture<Response>();
        nodes.get(0).processCommand(new Pop(), future);
        try {
            var response = future.get(5, TimeUnit.SECONDS);
            LOGGER.finest(() -> "Pop completed with response: " + response);
            return response;
        } catch (Exception e) {
            return new Response(Optional.of("Error: " + e.getMessage()));
        }
    }

    @Override
    public Response peek() {
        var future = new CompletableFuture<Response>();
        nodes.get(0).processCommand(new Peek(), future);
        try {
            var response = future.get(5, TimeUnit.SECONDS);
            LOGGER.finest(() -> "Peek completed with response: " + response);
            return response;
        } catch (Exception e) {
            return new Response(Optional.of("Error: " + e.getMessage()));
        }
    }

    public void shutdown() {
        running = false;
        nodes.forEach(n -> {
            try {
                n.close();
            } catch (Exception e) {
                LOGGER.warning("Error shutting down node: " + e.getMessage());
            }
        });
    }
}