// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe;

import com.github.trex_paxos.Pickler;
import com.github.trex_paxos.NodeId;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;
import static com.github.trex_paxos.paxe.SRPUtils.*;

record SRPKeyPair(String publicKey, String privateKey) {
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
    LOGGER.finest(() -> String.format("Node %d initiating handshake with %d",
        nodeId.id(), peerId.id()));

    if (!verifierLookup.get().containsKey(peerId)) {
      LOGGER.warning("No verifier for peer: " + peerId);
      return Optional.empty();
    }

    // only if we have never tried to handshake with this peer will we create a new  key
    activeHandshakes.computeIfAbsent(peerId, this::generateKeyPair);

    final var keyPair = activeHandshakes.get(peerId);

    LOGGER.finest(() -> nodeId + " initiating handshake with peer: " + peerId + " using public key: "
        + keyPair.publicKey());

    return Optional.of(new KeyMessage.KeyHandshakeRequest(
        nodeId,
        fromHex(keyPair.publicKey())));
  }

  public void handleMessage(KeyMessage msg) {
    LOGGER.finest(() -> String.format("Node %d handling key message type %s from %d",
        nodeId.id(), msg.getClass().getSimpleName(), msg.from().id()));
    try {
      switch (msg) {
        case KeyMessage.KeyHandshakeRequest req -> handleRequest(req);
        case KeyMessage.KeyHandshakeResponse resp -> handleResponse(resp);
      }
    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "Key exchange failed", e);
    }
  }

  private void handleRequest(KeyMessage.KeyHandshakeRequest msg) {
    NodeId peerId = new NodeId(msg.from().id());

    // only if we have never tried to handshake with this peer will we create a new
    // key
    activeHandshakes.computeIfAbsent(peerId, this::generateKeyPair);
    SRPKeyPair keyPair = activeHandshakes.get(peerId);

    byte[] sessionKey = computeSessionKey(peerId, toHex(msg.publicKey()), keyPair);
    sessionKeys.put(peerId, sessionKey);

    new KeyMessage.KeyHandshakeResponse(nodeId, fromHex(keyPair.publicKey()));
  }

  private void handleResponse(KeyMessage.KeyHandshakeResponse msg) {
    NodeId peerId = msg.from();
    SRPKeyPair keyPair = activeHandshakes.get(peerId);
    if (keyPair != null) {
      byte[] sessionKey = computeSessionKey(peerId, toHex(msg.publicKey()), keyPair);
      sessionKeys.put(peerId, sessionKey);
      activeHandshakes.remove(peerId);
    }
  }

  private byte[] computeSessionKey(NodeId peerId, String peerPublicKey, SRPKeyPair localKeys) {
    LOGGER.finest(() -> "\nKey computation parameters:\n" +
        "N: " + srpConstants.N() + "\n" +
        "g: " + srpConstants.g() + "\n" +
        "k: " + srpConstants.k() + "\n" +
        "Local role: " + (nodeId.id() < peerId.id() ? "client" : "server") + "\n" +
        "Local public key: " + localKeys.publicKey() + "\n" +
        "Peer public key: " + peerPublicKey + "\n");

    if (nodeId.id() < peerId.id()) {
      final var I = localSecret.srpIdentity();
      final var a = localKeys.privateKey();
      final var A = localKeys.publicKey();
      //noinspection UnnecessaryLocalVariable
      final var B = peerPublicKey;
      final var s = toHex(localSecret.salt());
      final var P = localSecret.password();
      var key = clientS(srpConstants, A, B, s, I, a, P);
      LOGGER.finer(() -> "Client premaster fingerprint: " + key.chars().asLongStream().sum());
      return hashedSecret(srpConstants.N(), key);
    } else {
      //noinspection UnnecessaryLocalVariable
      final var A = peerPublicKey;
      final var b = localKeys.privateKey();
      final var B = localKeys.publicKey();
      final var v = verifierLookup.get().get(peerId).verifier();
      var key = serverS(srpConstants, v, A, B, b);
      LOGGER.finer(() -> "Server premaster fingerprint: " + key.chars().asLongStream().sum());
      return hashedSecret(srpConstants.N(), key);
    }
  }

  sealed public interface KeyMessage {
    record KeyHandshakeRequest(NodeId from, byte[] publicKey) implements KeyMessage {
    }

    record KeyHandshakeResponse(NodeId from, byte[] publicKey) implements KeyMessage {
    }

    NodeId from();

    byte[] publicKey();
  }
}

class PickleHandshake {

  public static Pickler<SessionKeyManager.KeyMessage> instance = new Pickler<>() {
    @Override
    public byte[] serialize(SessionKeyManager.KeyMessage msg) {
      return PickleHandshake.pickle(msg);
    }

    @Override
    public SessionKeyManager.KeyMessage deserialize(byte[] bytes) {
      return PickleHandshake.unpickle(bytes);
    }
  };

  static byte[] pickle(SessionKeyManager.KeyMessage msg) {
    ByteBuffer buffer = ByteBuffer.allocate(calculateSize(msg));
    buffer.put(toByte(msg));
    buffer.putShort(msg.from().id());
    buffer.putInt(msg.publicKey().length);
    buffer.put(msg.publicKey());
    return buffer.array();
  }

  static SessionKeyManager.KeyMessage unpickle(byte[] bytes) {
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    byte type = buffer.get();
    NodeId from = new NodeId(buffer.getShort());
    int keyLength = buffer.getInt();
    byte[] publicKey = new byte[keyLength];
    buffer.get(publicKey);

    return switch (type) {
      case 1 -> new SessionKeyManager.KeyMessage.KeyHandshakeRequest(from, publicKey);
      case 2 -> new SessionKeyManager.KeyMessage.KeyHandshakeResponse(from, publicKey);
      default -> throw new IllegalArgumentException("Unknown type: " + type);
    };
  }

  private static int calculateSize(SessionKeyManager.KeyMessage msg) {
    return 1 + // type
        2 + // from id
        4 + // key length
        msg.publicKey().length;
  }

  private static byte toByte(SessionKeyManager.KeyMessage msg) {
    return switch (msg) {
      case SessionKeyManager.KeyMessage.KeyHandshakeRequest _ -> 1;
      case SessionKeyManager.KeyMessage.KeyHandshakeResponse _ -> 2;
    };
  }
}
