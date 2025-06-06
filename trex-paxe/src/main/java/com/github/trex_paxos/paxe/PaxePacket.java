// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe;

import com.github.trex_paxos.NodeId;
import com.github.trex_paxos.network.Channel;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/// Represents a secure network packet for the Paxe protocol, encapsulating both unencrypted and
/// encrypted message formats. Packets include metadata for routing, integrity checks, and optional
/// authenticated encryption using AES-GCM.
///
/// ## Packet Structure
///
/// ### Message Header (8 bytes):
///
/// ```
/// | fromId (2 bytes) | toId (2 bytes) | channel  (2 bytes) | length (2 bytes)
/// ```
///
/// - fromId: Source node identifier
/// - toId: Destination node identifier
/// - channel: Communication channel identifier
/// - length: Payload length
///
/// ### Standard Message Format:
///
/// ```
/// | Header (8 bytes)  | flags (1 byte)  | nonce(12) | Payload  | Auth Tag (16)
/// ```
///
/// - Header: Message header defined above
/// - Flags: Encryption mode and magic bits
/// - Nonce: Unique 12-byte nonce for AES-GCM encryption
/// - Payload: The small message payload encrypted with the peer-to-peer session key.
/// - Auth Tag: 16-byte authentication tag
///
/// ### DEK Message Format
///
/// ```
/// | Header (8)  | flags (1)  | nonce (12) | DEK (16) | Auth Tag (16) | Length (2) | Payload | Auth Tag (16)
/// ```
/// - Header: Message header defined above
/// - Flags: Encryption mode and magic bits
/// - Nonce: Unique 12-byte nonce for AES-GCM encryption
/// - DEK: Data Encryption Key encrypted with the peer-to-peer session key 16 byte (128 bit)
/// - Length: Payload length (2 bytes)
/// - Payload: The large message payload encrypted with the DEK.
/// - Auth Tag: 16-byte authentication tag of the encrypted payload.
///
/// This means tha there is a 71 byte overhead for DEK messages.
///
/// ### Flags Byte Structure
///
/// - Bit 0: DEK flag (0=standard, 1=DEK mode)
/// - Bit 1: Must be 0
/// - Bit 2: Must be 1
/// - Bits 3-7: Reserved
///
/// ## Encryption Details
///
/// Encrypted packets:
///
/// - Use AES-GCM with 16-byte authentication tag appended to ciphertext.
/// - Unique 12-byte nonce for AES-GCM encryption.
/// - Uses SRP6a (RFC5054) for peer-to-peer session key negotiation.
/// - Small message less than 64 bytes are encrypted with the peer-to-peer session key.
/// - Large messages are encrypted with a Data Encryption Key (DEK) shared amongst broadcast messages.
/// - DEK is encrypted with each node pair session key.
///
/// ## Validation Rules
///
/// - Nonce and auth tag must both be present or both absent
/// - Nonce must be exactly 12 bytes when present
/// - Auth tag must be exactly 16 bytes when present
/// - Total packet size cannot exceed 65,535 bytes
/// - Flags byte must have magic bits set correctly
///
/// @see PaxeProtocol PaxeProtocol constants and validation rules
/// @see #decrypt(PaxePacket, byte[]) Packet decryption method
/// @see #encrypt(PaxeMessage, NodeId, byte[]) Packet encryption method
public record PaxePacket(
    NodeId from,
    NodeId to,
    Channel channel,
    Optional<byte[]> nonce,
    Optional<byte[]> authTag,
    byte[] payload) implements PaxeProtocol {

  public static final int HEADER_SIZE = 8; // from(2) + to(2) + channel(2) + length(2)
  public static final int AUTHENTICATED_DATA_SIZE = 6; // from(2) + to(2) + channel(2)
  public static final int NONCE_SIZE = 12;
  public static final int AUTH_TAG_SIZE = 16;
  public static final int MAX_PACKET_LENGTH = 65535;

  public PaxePacket {
    Objects.requireNonNull(from, "from cannot be null");
    Objects.requireNonNull(to, "to cannot be null");
    Objects.requireNonNull(channel, "channel cannot be null");
    Objects.requireNonNull(payload, "payload cannot be null");
    Objects.requireNonNull(nonce, "nonce cannot be null");
    Objects.requireNonNull(authTag, "authTag cannot be null");

    var totalSize = HEADER_SIZE + payload.length;
    if (nonce.isPresent()) {
      totalSize += NONCE_SIZE + AUTH_TAG_SIZE;
    }
    if (totalSize > MAX_PACKET_LENGTH) {
      throw new IllegalArgumentException(
          String.format("Total payload size %d when adding headers exceeds UDP limit of %d as %d", payload.length, MAX_PACKET_LENGTH, totalSize));
    }

    nonce.ifPresent(n -> {
      if (n.length != NONCE_SIZE)
        throw new IllegalArgumentException("Invalid nonce size");
    });

    authTag.ifPresent(t -> {
      if (t.length != AUTH_TAG_SIZE)
        throw new IllegalArgumentException("Invalid auth tag size");
    });

    if (nonce.isPresent() != authTag.isPresent()) {
      throw new IllegalArgumentException("Both nonce and authTag must be present for encrypted packets");
    }
  }

  // Constructor for unencrypted packets
  public PaxePacket(NodeId from, NodeId to, Channel channel, byte[] payload) {
    this(from, to, channel, Optional.empty(), Optional.empty(), payload);
  }

  private static void putLength(ByteBuffer buffer, int length) {
    buffer.put((byte) ((length >>> 8) & 0xFF));
    buffer.put((byte) (length & 0xFF));
  }

  private static int getLength(ByteBuffer buffer) {
    return ((buffer.get() & 0xFF) << 8) | (buffer.get() & 0xFF);
  }

  public byte[] toBytes() {
    var size = HEADER_SIZE +
        (nonce.isPresent() ? NONCE_SIZE + AUTH_TAG_SIZE : 0) +
        payload.length;

    var buffer = ByteBuffer.allocate(size);
    buffer.putShort(from.id());
    buffer.putShort(to.id());
    buffer.putShort(channel.id());
    putLength(buffer, payload.length);

    nonce.ifPresent(buffer::put);
    authTag.ifPresent(buffer::put);
    buffer.put(payload);

    // Copy buffer contents to byte array rather than using array()
    byte[] result = new byte[buffer.position()];
    buffer.flip();
    buffer.get(result);
    return result;
  }

  public static PaxePacket fromBytes(byte[] bytes) {
    var buffer = ByteBuffer.wrap(bytes);
    var from = new NodeId(buffer.getShort());
    var to = new NodeId(buffer.getShort());
    var channel = new Channel(buffer.getShort());
    var payloadLength = getLength(buffer);

    var remaining = buffer.remaining();
    var isEncrypted = remaining > payloadLength;

    Optional<byte[]> nonce = Optional.empty();
    Optional<byte[]> authTag = Optional.empty();

    if (isEncrypted) {
      var n = new byte[NONCE_SIZE];
      buffer.get(n);
      nonce = Optional.of(n);

      var t = new byte[AUTH_TAG_SIZE];
      buffer.get(t);
      authTag = Optional.of(t);
    }

    var payload = new byte[payloadLength];
    buffer.get(payload);

    return new PaxePacket(from, to, channel, nonce, authTag, payload);
  }

  public byte[] authenticatedData() {
    var buffer = ByteBuffer.allocate(AUTHENTICATED_DATA_SIZE);
    buffer.putShort(from.id());
    buffer.putShort(to.id());
    buffer.putShort(channel.id());

    // Copy buffer contents to byte array rather than using array()
    byte[] result = new byte[buffer.position()];
    buffer.flip();
    buffer.get(result);
    return result;
  }

  public static PaxeMessage decrypt(PaxePacket packet, byte[] key) {
    if (packet.nonce.isEmpty() || packet.authTag.isEmpty()) {
      throw new SecurityException("Cannot decrypt unencrypted packet");
    }

    try {
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      var gcmSpec = new GCMParameterSpec(AUTH_TAG_SIZE * 8, packet.nonce.get());
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), gcmSpec);
      cipher.updateAAD(packet.authenticatedData());

      var combined = new byte[packet.payload.length + AUTH_TAG_SIZE];
      System.arraycopy(packet.payload, 0, combined, 0, packet.payload.length);
      System.arraycopy(packet.authTag.get(), 0, combined, packet.payload.length, AUTH_TAG_SIZE);

      var decrypted = cipher.doFinal(combined);
      return PaxeMessage.deserialize(packet.from, packet.to, packet.channel, decrypted);
    } catch (GeneralSecurityException e) {
      throw new SecurityException("Decryption failed", e);
    }
  }

  public static PaxePacket encrypt(PaxeMessage message, NodeId from, byte[] key) throws GeneralSecurityException {
    var nonce = new byte[NONCE_SIZE];
    ThreadLocalRandom.current().nextBytes(nonce);

    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    var gcmSpec = new GCMParameterSpec(AUTH_TAG_SIZE * 8, nonce);
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), gcmSpec);

    var tempPacket = new PaxePacket(from, message.to(), message.channel(), message.serialize());
    cipher.updateAAD(tempPacket.authenticatedData());

    var ciphertext = cipher.doFinal(message.serialize());

    var authTag = new byte[AUTH_TAG_SIZE];
    System.arraycopy(ciphertext, ciphertext.length - AUTH_TAG_SIZE, authTag, 0, AUTH_TAG_SIZE);

    var actualCiphertext = new byte[ciphertext.length - AUTH_TAG_SIZE];
    System.arraycopy(ciphertext, 0, actualCiphertext, 0, ciphertext.length - AUTH_TAG_SIZE);

    return new PaxePacket(
        from,
        message.to(),
        message.channel(),
        Optional.of(nonce),
        Optional.of(authTag),
        actualCiphertext);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    //noinspection DeconstructionCanBeUsed
    if (!(o instanceof PaxePacket that)) return false;
    return from.equals(that.from)
        && to.equals(that.to)
        && channel.equals(that.channel)
        && Arrays.equals(nonce.orElse(null), that.nonce.orElse(null))
        && Arrays.equals(authTag.orElse(null), that.authTag.orElse(null))
        && Arrays.equals(payload, that.payload);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(from, to, channel);
    result = 31 * result + Arrays.hashCode(nonce.orElse(null));
    result = 31 * result + Arrays.hashCode(authTag.orElse(null));
    result = 31 * result + Arrays.hashCode(payload);
    return result;
  }
}

