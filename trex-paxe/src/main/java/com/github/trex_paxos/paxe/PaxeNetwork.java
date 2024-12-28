package com.github.trex_paxos.paxe;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class PaxeNetwork implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(PaxeNetwork.class.getName());
    private final DatagramSocket socket;
    private final NodeId localNode;
    private final Map<SessionKeyPair, byte[]> sessionKeys;
    private final Map<Channel, BlockingQueue<EncryptedPaxeMessage>> channelQueues;
    private final BlockingQueue<PaxeMessage> outboundQueue;
    private final Thread sender;
    private final Thread receiver;
    private volatile boolean running = true;
    final ClusterMembership membership;

    public PaxeNetwork(
            int port,
            NodeId localNode,
            ClusterMembership membership,
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

    // Process outbound messages from queue
    private void processSendQueue() {
        while (running) {
            try {
                PaxeMessage message = outboundQueue.take();
                var keyPair = new SessionKeyPair(localNode, message.to());
                var key = sessionKeys.get(keyPair);
                if (key == null) {
                    throw new SecurityException("No session key for " + message.to());
                }
                encryptAndSend(message, key);
            } catch (InterruptedException e) {
                if (running) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (Exception e) {
                if (running) {
                    LOGGER.warning("Send error: " + e.getMessage());
                }
            }
        }
    }

    private BlockingQueue<EncryptedPaxeMessage> getOrCreateChannelQueue(Channel channel) {
        return channelQueues.computeIfAbsent(channel,
                _ -> new LinkedBlockingQueue<>());
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

        return decryptMessage(encrypted.packet(), key);
    }

    private PaxeMessage decryptMessage(PaxePacket packet, byte[] key) throws Exception, NoSuchPaddingException {
        // Real decryption using AES-GCM
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        var gcmSpec = new GCMParameterSpec(
                PaxePacket.AUTH_TAG_SIZE * 8, packet.nonce());
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), gcmSpec);
        cipher.updateAAD(packet.authenticatedData());
        var decrypted = cipher.doFinal(packet.ciphertext());
        return PaxeMessage.deserialize(decrypted);
    }

private void encryptAndSend(PaxeMessage message, byte[] key) throws IOException {
    var nonce = new byte[PaxePacket.NONCE_SIZE];
    ThreadLocalRandom.current().nextBytes(nonce);
    
    try {
        // Real encryption using AES-GCM
        var cipher = Cipher.getInstance("AES/GCM/NoPadding"); 
        var gcmSpec = new GCMParameterSpec(
            PaxePacket.AUTH_TAG_SIZE * 8, nonce);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), gcmSpec);

        var plaintext = message.serialize();
        var authData = buildAuthenticatedData(message);
        cipher.updateAAD(authData);
        
        // Domal result contains both ciphertext and auth tag together
        var ciphertextAndTag = cipher.doFinal(plaintext);
        
        // Split the result into ciphertext and tag
        var ciphertextLength = ciphertextAndTag.length - PaxePacket.AUTH_TAG_SIZE;
        var ciphertext = Arrays.copyOfRange(ciphertextAndTag, 0, ciphertextLength);
        var authTag = Arrays.copyOfRange(ciphertextAndTag, ciphertextLength, ciphertextAndTag.length);

        var packet = new PaxePacket(
                localNode,
                message.to(),
                message.channel(),
                (byte) 0,
                nonce, 
                authTag,
                ciphertext);

        sendPacket(packet);
    } catch (GeneralSecurityException e) {
        throw new IOException("Encryption failed", e);
    }
}
        // Extensions for PaxePacket to support AEAD
    public static byte[] buildAuthenticatedData(PaxeMessage message) {
        // Additional authenticated data includes routing info
        var buffer = ByteBuffer.allocate(3); // from, to, channel
        buffer.put(message.from().value());
        buffer.put(message.to().value()); 
        buffer.put(message.channel().value());
        return buffer.array();
    }

    // Send a packet over UDP
    private void sendPacket(PaxePacket packet) throws IOException {
        var address = membership.addressFor(packet.to())
                .orElseThrow(() -> new IOException("Unknown destination: " + packet.to()));

        byte[] bytes = packet.toBytes();
        var dgPacket = new DatagramPacket(
                bytes,
                bytes.length,
                InetAddress.getByName(address.hostString()),
                address.port());
        socket.send(dgPacket);
    }

    // Queue a message for sending
    public void send(PaxeMessage message) {
        outboundQueue.add(message);
    }

    @Override
    public void close() throws Exception {
        running = false;
        socket.close();
        sender.interrupt();
        receiver.interrupt();
    }
}