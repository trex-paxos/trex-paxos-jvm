package com.github.trex_paxos.paxe;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

import com.github.trex_paxos.network.NodeId;
import com.github.trex_paxos.paxe.SRPUtils.Constants;

import static com.github.trex_paxos.paxe.SRPUtils.*;
import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;

record SRPKeyPair(String publicKey, String privateKey) {
}

sealed interface KeyMessage {
    record KeyHandshakeRequest(NodeId from, byte[] publicKey) implements KeyMessage {
    }

    record KeyHandshakeResponse(NodeId from, byte[] publicKey) implements KeyMessage {
    }

    NodeId from();

    byte[] publicKey();
}

public class SessionKeyManager {

    private final NodeId nodeId;
    private final NodeClientSecret localSecret;
    private final Supplier<Map<NodeId, NodeVerifier>> verifierLookup;
    private final Constants srpConstants;

    private final Map<NodeId, SRPKeyPair> activeHandshakes = new ConcurrentHashMap<>();

    /// This is package private as it is used by the network to send messages
    final Map<NodeId, byte[]> sessionKeys = new ConcurrentHashMap<>();

    public SessionKeyManager(
            NodeId nodeId,
            Constants srpConstants,
            NodeClientSecret localSecret,
            Supplier<Map<NodeId, NodeVerifier>> verifierLookup) {
        this.nodeId = nodeId;
        this.localSecret = localSecret;
        this.verifierLookup = verifierLookup;
        this.srpConstants = srpConstants;
    }

    private SRPKeyPair generateKeyPair(NodeId peerId) {
        String privateKey = generatedPrivateKey(srpConstants.N()).toUpperCase();
        LOGGER.finest(() -> String.format("Generated private key: %s for peer: %d",
                privateKey, peerId.id()));
        String publicKey;

        if (nodeId.id() < peerId.id()) {
            publicKey = A(integer(privateKey),
                    integer(srpConstants.g()),
                    integer(srpConstants.N())).toString(16).toUpperCase();
            LOGGER.finest(() -> "Generated client public key: " + publicKey);
        } else {
            final var v = verifierLookup.get().get(peerId).verifier();
            LOGGER.finest(() -> "Generating server public key B for peer " + peerId + " using v " + v);
            publicKey = B(integer(privateKey),
                    integer(v),
                    integer(srpConstants.k()),
                    integer(srpConstants.g()),
                    integer(srpConstants.N())).toString(16).toUpperCase();

            LOGGER.finest(() -> "Generated server public key: " + publicKey);
        }

        return new SRPKeyPair(publicKey, privateKey);
    }

    public Optional<KeyMessage> initiateHandshake(NodeId peerId) {
        if (!verifierLookup.get().containsKey(peerId)) {
            LOGGER.warning("No verifier for peer: " + peerId);
            return Optional.empty();
        }

        // only if we have never tried to handshake with this peer will we create a new
        // key
        activeHandshakes.computeIfAbsent(peerId, (key) -> generateKeyPair(key));

        final var keyPair = activeHandshakes.get(peerId);

        LOGGER.finest(() -> nodeId + " initiating handshake with peer: " + peerId + " using public key: "
                + keyPair.publicKey());

        return Optional.of(new KeyMessage.KeyHandshakeRequest(
                nodeId,
                fromHex(keyPair.publicKey())));
    }

    public Optional<KeyMessage> handleMessage(KeyMessage msg) {
        try {
            return switch (msg) {
                case KeyMessage.KeyHandshakeRequest req -> handleRequest(req);
                case KeyMessage.KeyHandshakeResponse resp -> handleResponse(resp);
            };
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Key exchange failed", e);
        }
        return Optional.empty();
    }

    private Optional<KeyMessage> handleRequest(KeyMessage.KeyHandshakeRequest msg) {
        NodeId peerId = new NodeId(msg.from().id());

        // only if we have never tried to handshake with this peer will we create a new
        // key
        activeHandshakes.computeIfAbsent(peerId, (key) -> generateKeyPair(key));
        SRPKeyPair keyPair = activeHandshakes.get(peerId);

        byte[] sessionKey = computeSessionKey(peerId, toHex(msg.publicKey()), keyPair);
        sessionKeys.put(peerId, sessionKey);

        return Optional.of(
                new KeyMessage.KeyHandshakeResponse(nodeId, fromHex(keyPair.publicKey())));
    }

    private Optional<KeyMessage> handleResponse(KeyMessage.KeyHandshakeResponse msg) {
        NodeId peerId = msg.from();
        SRPKeyPair keyPair = activeHandshakes.get(peerId);
        if (keyPair != null) {
            byte[] sessionKey = computeSessionKey(peerId, toHex(msg.publicKey()), keyPair);
            sessionKeys.put(peerId, sessionKey);
            activeHandshakes.remove(peerId);
        }
        return Optional.empty();
    }

    private byte[] computeSessionKey(NodeId peerId, String peerPublicKey, SRPKeyPair localKeys) {
        LOGGER.finest(() -> {
            var sb = new StringBuilder();
            sb.append("\nKey computation parameters:\n");
            sb.append("N: ").append(srpConstants.N()).append("\n");
            sb.append("g: ").append(srpConstants.g()).append("\n");
            sb.append("k: ").append(srpConstants.k()).append("\n");
            sb.append("Local role: ").append(nodeId.id() < peerId.id() ? "client" : "server").append("\n");
            sb.append("Local public key: ").append(localKeys.publicKey()).append("\n");
            sb.append("Peer public key: ").append(peerPublicKey).append("\n");
            return sb.toString();
        });

        if (nodeId.id() < peerId.id()) {
            final var I = localSecret.srpIdenity();
            final var a = localKeys.privateKey();
            final var A = localKeys.publicKey();
            final var B = peerPublicKey;
            final var s = toHex(localSecret.salt());
            final var P = localSecret.password();
            var key = clientS(srpConstants, A, B, s, I, a, P);
            LOGGER.finer(() -> "Client premaster fingerprint: " + key.chars().asLongStream().sum());
            return hashedSecret(srpConstants.N(), key);
        } else {
            final var A = peerPublicKey;
            final var b = localKeys.privateKey();
            final var B = localKeys.publicKey();
            final var v = verifierLookup.get().get(peerId).verifier();
            var key = serverS(srpConstants, v, A, B, b);
            LOGGER.finer(() -> "Server premaster fingerprint: " + key.chars().asLongStream().sum());
            return hashedSecret(srpConstants.N(), key);
        }
    }

    public Optional<byte[]> getSessionKey(NodeId peerId) {
        return Optional.ofNullable(sessionKeys.get(peerId));
    }
}

// Package-private serialization class inside SessionKeyManager.java
class PickleHandshake {
    static byte[] pickle(KeyMessage msg) {
        ByteBuffer buffer = ByteBuffer.allocate(calculateSize(msg));
        buffer.put(toByte(msg));
        buffer.putShort(msg.from().id());
        buffer.putInt(msg.publicKey().length);
        buffer.put(msg.publicKey());
        return buffer.array();
    }

    static KeyMessage unpickle(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        byte type = buffer.get();
        NodeId from = new NodeId(buffer.getShort());
        int keyLength = buffer.getInt();
        byte[] publicKey = new byte[keyLength];
        buffer.get(publicKey);

        return switch (type) {
            case 1 -> new KeyMessage.KeyHandshakeRequest(from, publicKey);
            case 2 -> new KeyMessage.KeyHandshakeResponse(from, publicKey);
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        };
    }

    private static int calculateSize(KeyMessage msg) {
        return 1 + // type
                2 + // from id
                4 + // key length
                msg.publicKey().length;
    }

    private static byte toByte(KeyMessage msg) {
        return switch (msg) {
            case KeyMessage.KeyHandshakeRequest _ -> 1;
            case KeyMessage.KeyHandshakeResponse _ -> 2;
        };
    }
}
