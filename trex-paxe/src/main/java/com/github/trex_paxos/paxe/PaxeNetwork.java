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
    private final DatagramSocket socket;
    private final NodeId localNode;
    private final Map<SessionKeyPair, byte[]> sessionKeys;
    private final Map<Channel, BlockingQueue<EncryptedPaxeMessage>> channelQueues;
    private final BlockingQueue<PaxePacket> outboundQueue;
    private final Thread sender;
    private final Thread receiver;
    private volatile boolean running = true;
    final Supplier<ClusterMembership> membership;

    public PaxeNetwork(
            int port,
            NodeId localNode,
            Supplier<ClusterMembership> membership,
            Map<SessionKeyPair, byte[]> sessionKeys) throws SocketException {
        this.socket = new DatagramSocket(port);
        this.localNode = localNode;
        this.membership = membership;
        this.sessionKeys = Map.copyOf(sessionKeys);
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

                // Queue encrypted packet for decryption by consumer
                var queue = getOrCreateChannelQueue(paxePacket.channel());
                queue.add(new EncryptedPaxeMessage(paxePacket));

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

        // Decrypt on consumer thread
        var keyPair = new SessionKeyPair(
                encrypted.packet().from(), encrypted.packet().to());
        var key = sessionKeys.get(keyPair);
        if (key == null) {
            throw new SecurityException("Unknown sender");
        }

        return PaxePacket.decrypt(encrypted.packet(), key);
    }

    public void encryptAndSend(PaxeMessage message, byte[] key) throws Exception {
        final var pexePacket = PaxePacket.encrypt(message, localNode, key);
        outboundQueue.add(pexePacket);
    }

    @Override
    public void close() throws Exception {
        this.running = false;
        this.receiver.interrupt();
        this.sender.interrupt();
    }
}