package com.github.trex_paxos.paxe;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class PaxeNetwork implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(PaxeNetwork.class.getName());

    // Buffer of one entry of any messages that would not be sent due to not yet
    // having a session key
    private final Map<NodeId, PaxeMessage> pendingMessages = new ConcurrentHashMap<>();

    /// Key manager for session key management which must have access to SRP verifiers. 
    private final SessionKeyManager keyManager;

    private final DatagramSocket socket;
    private final NodeId localNode;
    private final Map<Channel, BlockingQueue<EncryptedPaxeMessage>> channelQueues;

    private final BlockingQueue<PaxePacket> outboundQueue;
    private final Thread sender;
    private final Thread receiver;
    private volatile boolean running = true;

    final Supplier<ClusterMembership> membership;

    public PaxeNetwork(
            SessionKeyManager keyManager,
            int port,
            NodeId localNode,
            Supplier<ClusterMembership> membership) throws SocketException {
        this.keyManager = keyManager;
        this.socket = new DatagramSocket(port);
        this.localNode = localNode;
        this.membership = membership;
        this.channelQueues = new ConcurrentHashMap<>();
        this.outboundQueue = new LinkedBlockingQueue<>();

        // Platform thread for network reading
        this.receiver = Thread.ofPlatform().name("receiver")
                .start(this::receiveLoop);

        // Virtual thread for sending
        this.sender = Thread.ofVirtual().name("sender")
                .start(this::processSendQueue);
    }

    private BlockingQueue<EncryptedPaxeMessage> getOrCreateChannelQueue(Channel channel) {
        return channelQueues.computeIfAbsent(channel,
                _ -> new LinkedBlockingQueue<>());
    }

    private void processSendQueue() {
        while (running) {
            try {
                // Take the next packet from the outbound queue
                PaxePacket packet = outboundQueue.take();

                // Resolve destination address using ClusterMembership
                NodeId destinationNode = packet.to();
                Optional<NetworkAddress> addressOpt = membership.get().addressFor(destinationNode);

                if (addressOpt.isEmpty()) {
                    LOGGER.warning("Unknown destination: " + destinationNode);
                    continue; // Skip this packet
                }

                NetworkAddress address = addressOpt.get();
                InetAddress inetAddress = InetAddress.getByName(address.hostString());
                int port = address.port();

                // Serialize packet to bytes
                byte[] data = packet.toBytes();

                // Create and send UDP datagram
                DatagramPacket datagram = new DatagramPacket(data, data.length, inetAddress, port);
                socket.send(datagram);

            } catch (InterruptedException e) {
                if (!running)
                    break; // Graceful shutdown
            } catch (IOException e) {
                LOGGER.warning("Failed to send packet: " + e.getMessage());
            }
        }
    }

    private void receiveLoop() {
        var buffer = new byte[65535];
        var packet = new DatagramPacket(buffer, buffer.length);

        while (running) {
            try {
                socket.receive(packet);
                var paxePacket = PaxePacket.fromBytes(
                        Arrays.copyOf(packet.getData(), packet.getLength()));

                if (!paxePacket.to().equals(localNode)) {
                    continue;
                }

                if (paxePacket.channel().equals(Channel.KEY_EXCHANGE_CHANNEL)) {
                    KeyMessage keyMsg = PickleHandshake.unpickle(paxePacket.payload());
                    keyManager.handleMessage(keyMsg);
                } else {
                    var queue = getOrCreateChannelQueue(paxePacket.channel());
                    queue.add(new EncryptedPaxeMessage(paxePacket));
                }

            } catch (IOException e) {
                if (running) {
                    LOGGER.warning("receiveLoop error: " + e.getMessage());
                }
            }
        }
    }

    public record EncryptedPaxeMessage(PaxePacket packet) {
    }

    // Called by consumers to get messages from a channel
    public PaxeMessage receive(Channel channel) throws Exception {
        var queue = getOrCreateChannelQueue(channel);
        var encrypted = queue.take();

        var key = keyManager.sessionKeys.get(encrypted.packet().from());
        if (key == null) {
            throw new SecurityException("Unknown sender");
        }

        return PaxePacket.decrypt(encrypted.packet(), key);
    }

    public void encryptAndSend(PaxeMessage message) throws Exception {
        final var key = keyManager.sessionKeys.get(message.to());
        if (key == null) {
            pendingMessages.put(message.to(), message);
            keyManager.initiateHandshake(message.to())
                    .ifPresent(keyMsg -> sendHandshake(message.to(), keyMsg));
        } else {
            final var pexePacket = PaxePacket.encrypt(message, localNode, key);
            outboundQueue.add(pexePacket);
        }
    }

    void sendHandshake(NodeId to, KeyMessage msg) {
        Optional<NetworkAddress> addressOpt = membership.get().addressFor(to);

        if (addressOpt.isEmpty()) {
            LOGGER.warning("Unknown destination: " + to);
            return; // Skip this packet
        }

        byte[] payload = PickleHandshake.pickle(msg);
        
        NetworkAddress address = addressOpt.get();
        try {
            InetAddress inetAddress = InetAddress.getByName(address.hostString());
            int port = address.port();
    
            final var handshake  = new PaxePacket(
                localNode, 
                to, 
                Channel.KEY_EXCHANGE_CHANNEL, 
                (byte) 0, 
                new byte[PaxePacket.NONCE_SIZE], 
                new byte[PaxePacket.AUTH_TAG_SIZE], 
                payload);

            final var data = handshake.toBytes();

            // Create and send UDP datagram
            DatagramPacket datagram = new DatagramPacket(data, data.length, inetAddress, port);
            socket.send(datagram);
        }
        catch (IOException e) {
            LOGGER.severe("Failed to send handshake message: " + e.getMessage());
        }
    }

    @Override
    public void close() throws Exception {
        this.running = false;
        this.receiver.interrupt();
        this.sender.interrupt();
    }
}