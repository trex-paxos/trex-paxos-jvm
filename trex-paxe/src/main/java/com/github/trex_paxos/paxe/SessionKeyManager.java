package com.github.trex_paxos.paxe;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.trex_paxos.paxe.SRPUtils.Constants;

import static com.github.trex_paxos.paxe.SRPUtils.*;

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
    private static final Logger LOGGER = Logger.getLogger(SessionKeyManager.class.getName());

    private final NodeId nodeId;
    private final ClusterId clusterId;
    private final NodeClientSecret localSecret;
    private final Supplier<Map<NodeId, NodeVerifier>> verifierLookup;
    private final Constants srpConstants;

    private final Map<NodeId, SRPKeyPair> activeHandshakes = new ConcurrentHashMap<>();

    /// This is package private as it is used by the network to send messages
    final Map<NodeId, byte[]> sessionKeys = new ConcurrentHashMap<>();

    public SessionKeyManager(
            Constants srpConstants,
            NodeClientSecret localSecret,
            Supplier<Map<NodeId, NodeVerifier>> verifierLookup) {
        this.nodeId = localSecret.id();
        this.clusterId = localSecret.clusterId();
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
            publicKey = generateB(peerId, privateKey).toString(16).toUpperCase();
            LOGGER.finest(() -> "Generated server public key: " + publicKey);
        }

        return new SRPKeyPair(publicKey, privateKey);
    }

    public Optional<KeyMessage> initiateHandshake(NodeId peerId) {
        if (!verifierLookup.get().containsKey(peerId)) {
            LOGGER.warning("No verifier for peer: " + peerId);
            return Optional.empty();
        }

        // only if we have never tried to handshake with this peer will we create a new key
        activeHandshakes.computeIfAbsent(peerId, (key)->generateKeyPair(key));

        final var keyPair = activeHandshakes.get(peerId);

        LOGGER.finest(() -> localSecret.id() + " initiating handshake with peer: " + peerId + " using public key: " + keyPair.publicKey());

        return Optional.of(new KeyMessage.KeyHandshakeRequest(
                localSecret.id(),
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

        // only if we have never tried to handshake with this peer will we create a new key
        activeHandshakes.computeIfAbsent(peerId, (key)->generateKeyPair(key));
        SRPKeyPair keyPair = activeHandshakes.get(peerId);

        byte[] sessionKey = computeSessionKey(peerId, toHex(msg.publicKey()), keyPair);
        sessionKeys.put(peerId, sessionKey);

        return Optional.of(
                new KeyMessage.KeyHandshakeResponse(localSecret.id(), fromHex(keyPair.publicKey())));
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

    String identity() {
        return clusterId.id() + "@" + nodeId.id();
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
            // if we are client, local public key is A and peer public key is B
            var u = u(srpConstants.N(), localKeys.publicKey(), peerPublicKey);
            LOGGER.finest(() -> "Client u: " + u);
            var key = clientS(srpConstants, peerPublicKey, localKeys.publicKey(), toHex(localSecret.salt()),
                    identity(), localKeys.privateKey(), localSecret.password());
            LOGGER.finest(() -> "Client premaster: " + key);
            return hashedSecret(srpConstants.N(), key);
        } else {
            // if we are server, local public key is B and peer public key is A
            var u = u(srpConstants.N(), peerPublicKey, localKeys.publicKey());
            LOGGER.finest(() -> "Server u: " + u);
            var key = serverS(srpConstants, verifierLookup.get().get(peerId).verifier(),
                    peerPublicKey, localKeys.publicKey(), localKeys.privateKey());
            LOGGER.finest(() -> "Server premaster: " + key);
            return hashedSecret(srpConstants.N(), key);
        }
    }

    private BigInteger generateB(NodeId peerId, String privateKey) {
        return B(integer(privateKey),
                integer(verifierLookup.get().get(peerId).verifier()),
                integer(srpConstants.k()),
                integer(srpConstants.g()),
                integer(srpConstants.N()));
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
            default -> throw new IllegalStateException("Unknown type: " + type);
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