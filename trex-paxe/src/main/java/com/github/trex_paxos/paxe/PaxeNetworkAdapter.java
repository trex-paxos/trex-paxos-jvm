package com.github.trex_paxos.paxe;

import com.github.trex_paxos.network.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class PaxeNetworkAdapter implements Network {
    private static final Logger LOGGER = Logger.getLogger(PaxeNetworkAdapter.class.getName());
    
    private final PaxeNetwork paxeNetwork;
    private final Map<Channel, Consumer<Message>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    public PaxeNetworkAdapter(PaxeNetwork paxeNetwork) {
        this.paxeNetwork = paxeNetwork;
        startReceiver();
    }
    
    private void startReceiver() {
        executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Handle messages from both channels
                    handleChannel(Network.CONSENSUS);
                    handleChannel(Network.FORWARD);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOGGER.warning("Error receiving message: " + e);
                }
            }
        });
    }
    
    private void handleChannel(Channel channel) throws Exception {
        PaxeMessage msg = paxeNetwork.receive(channel);
        Consumer<Message> handler = handlers.get(channel);
        if (handler != null) {
            handler.accept(new Message(msg.from(), msg.to(), msg.payload()));
        }
    }
    
    @Override
    public void send(Channel channel, NodeId to, byte[] payload) {
        try {
            paxeNetwork.encryptAndSend(new PaxeMessage(
                paxeNetwork.localNode,
                to,
                channel,
                payload
            ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to send message", e);
        }
    }
    
    @Override
    public void subscribe(Channel channel, Consumer<Message> handler) {
        handlers.put(channel, handler);
    }
    
    @Override
    public void close() {
        executor.shutdownNow();
    }
}