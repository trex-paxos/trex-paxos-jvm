package com.github.trex_paxos.paxe;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import static com.github.trex_paxos.paxe.SRPUtils.*;

record SRPKeyPair(String publicKey, String privateKey) {}

sealed interface KeyMessage {
    record KeyHandshakeRequest(NodeId from, byte[] publicKey) implements KeyMessage {}
    record KeyHandshakeResponse(NodeId from, byte[] publicKey) implements KeyMessage {}
    NodeId from();
    byte[] publicKey();
}

public class SessionKeyManager {
    private static final Logger LOGGER = Logger.getLogger(SessionKeyManager.class.getName());

    private final NodeId nodeId;
    private final ClusterId clusterId;
    private final NodeClientSecret localSecret;
    private final Supplier<Map<NodeId,NodeVerifier>> verifierLookup;
    private final Constants srpConstants;
    
    private final Map<NodeId,byte[]> sessionKeys = new ConcurrentHashMap<>();
    private final Map<NodeId,SRPKeyPair> activeHandshakes = new ConcurrentHashMap<>();

    public SessionKeyManager(
            NodeClientSecret localSecret, 
            Supplier<Map<NodeId,NodeVerifier>> verifierLookup,
            Constants srpConstants) {
        this.nodeId = localSecret.identity();
        this.clusterId = localSecret.clusterId();
        this.localSecret = localSecret;
        this.verifierLookup = verifierLookup;
        this.srpConstants = srpConstants;
    }

    private SRPKeyPair generateKeyPair(NodeId peerId) {
        String privateKey = generatedPrivateKey(srpConstants.N());
        String publicKey;
        
        if(nodeId.id() < peerId.id()) {
            publicKey = A(integer(privateKey), 
                       integer(srpConstants.g()),
                       integer(srpConstants.N())).toString(16);
        } else {
            publicKey = generateB(peerId, privateKey).toString(16);
        }

        return new SRPKeyPair(publicKey, privateKey);
    }

    public Optional<KeyMessage> initiateHandshake(NodeId peerId) {
        if(!verifierLookup.get().containsKey(peerId)) {
            LOGGER.warning("No verifier for peer: " + peerId);
            return Optional.empty();
        }

        SRPKeyPair keyPair = generateKeyPair(peerId);
        activeHandshakes.put(peerId, keyPair);
        
        return Optional.of(new KeyMessage.KeyHandshakeRequest(
            localSecret.identity(), 
            fromHex(keyPair.publicKey())
        ));
    }

    public void handleMessage(KeyMessage msg) {
        try {
            switch(msg) {
                case KeyMessage.KeyHandshakeRequest req -> handleRequest(req);
                case KeyMessage.KeyHandshakeResponse resp -> handleResponse(resp);
            }
        } catch(Exception e) {
            LOGGER.log(Level.SEVERE, "Key exchange failed", e);
        }
    }

    private void handleRequest(KeyMessage.KeyHandshakeRequest msg) {
        NodeId peerId = new NodeId(msg.from().id());
        SRPKeyPair keyPair = generateKeyPair(peerId);
        activeHandshakes.put(peerId, keyPair);

        byte[] sessionKey = computeSessionKey(peerId, toHex(msg.publicKey()), keyPair);
        sessionKeys.put(peerId, sessionKey);

        // TODO: Return response or use callback
        new KeyMessage.KeyHandshakeResponse(localSecret.identity(), fromHex(keyPair.publicKey()));
    }

    private void handleResponse(KeyMessage.KeyHandshakeResponse msg) {
        NodeId peerId = msg.from();
        SRPKeyPair keyPair = activeHandshakes.get(peerId);
        if(keyPair != null) {
            byte[] sessionKey = computeSessionKey(peerId, toHex(msg.publicKey()), keyPair);
            sessionKeys.put(peerId, sessionKey);
            activeHandshakes.remove(peerId);
        }
    }

    String identity() {
        return clusterId.id() + "@" + nodeId.id();
    }

    private byte[] computeSessionKey(NodeId peerId, String peerPublicKey, SRPKeyPair localKeys) {
        if(nodeId.id() < peerId.id()) {
            return hashedSecret(srpConstants.N(),
                clientS(srpConstants, peerPublicKey, peerPublicKey,
                       toHex(localSecret.salt()),
                       identity(), 
                       localKeys.privateKey(),
                       localSecret.password().toString()));
        } else {
            return hashedSecret(srpConstants.N(),
                serverS(srpConstants,
                       verifierLookup.get().get(peerId).verifier(),
                       peerPublicKey, localKeys.publicKey(), 
                       localKeys.privateKey()));
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
      
      return switch(type) {
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
      return switch(msg) {
        case KeyMessage.KeyHandshakeRequest _ -> 1;
        case KeyMessage.KeyHandshakeResponse _ -> 2;
      };
    }
}