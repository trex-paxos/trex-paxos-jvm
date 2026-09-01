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
import java.util.concurrent.ThreadLocalRandom;

/// Authenticated unicast PAXE datagram: `Prefix(9) | Nonce(12) | Ciphertext | Tag(16)`.
///
/// The nine-byte prefix is `BE16(fromId) || BE16(toId) || BE32(channel) || epoch` and is the
/// exact AES-GCM associated data. `fromId` is authenticated routing metadata; every member holds
/// the same cluster PSK, so it is not a distinct cryptographic node identity.
///
/// Plaintext length is derived from the received UDP datagram length minus {@link #FRAME_OVERHEAD};
/// there is no encoded length field.
public record PaxePacket(
    NodeId from,
    NodeId to,
    Channel channel,
    byte epoch,
    byte[] nonce,
    byte[] ciphertext,
    byte[] authTag) implements PaxeProtocol {

  public static final int NONCE_SIZE = GCM_NONCE_LENGTH;
  public static final int AUTH_TAG_SIZE = GCM_TAG_LENGTH;

  public PaxePacket {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(nonce, "nonce");
    Objects.requireNonNull(ciphertext, "ciphertext");
    Objects.requireNonNull(authTag, "authTag");
    if (nonce.length != NONCE_SIZE) {
      throw new IllegalArgumentException("Invalid nonce size");
    }
    if (authTag.length != AUTH_TAG_SIZE) {
      throw new IllegalArgumentException("Invalid auth tag size");
    }
    if (ciphertext.length > MAX_PLAINTEXT_SIZE) {
      throw new IllegalArgumentException("Ciphertext exceeds maximum UDP payload");
    }
  }

  /// Serializes the nine-byte prefix used as AES-GCM associated data.
  public byte[] prefixBytes() {
    var buffer = ByteBuffer.allocate(PREFIX_SIZE);
    buffer.putShort(from.id());
    buffer.putShort(to.id());
    buffer.putInt(channel.id());
    buffer.put(epoch);
    return buffer.array();
  }

  /// Assembles the full on-wire datagram.
  public byte[] toDatagram() {
    var buffer = ByteBuffer.allocate(FRAME_OVERHEAD + ciphertext.length);
    buffer.put(prefixBytes());
    buffer.put(nonce);
    buffer.put(ciphertext);
    buffer.put(authTag);
    return buffer.array();
  }

  /// Parses a received UDP datagram without decrypting. Rejects undersized datagrams.
  public static PaxePacket fromDatagram(byte[] datagram) {
    if (datagram.length < FRAME_OVERHEAD) {
      throw new SecurityException("Datagram shorter than fixed PAXE overhead");
    }
    var buffer = ByteBuffer.wrap(datagram);
    var from = new NodeId(buffer.getShort());
    var to = new NodeId(buffer.getShort());
    var channel = new Channel(buffer.getInt());
    byte epoch = buffer.get();

    var nonce = new byte[NONCE_SIZE];
    buffer.get(nonce);

    int ciphertextLength = datagram.length - FRAME_OVERHEAD;
    var ciphertext = new byte[ciphertextLength];
    buffer.get(ciphertext);

    var authTag = new byte[AUTH_TAG_SIZE];
    buffer.get(authTag);

    if (buffer.hasRemaining()) {
      throw new SecurityException("Datagram longer than expected for derived ciphertext length");
    }

    return new PaxePacket(from, to, channel, epoch, nonce, ciphertext, authTag);
  }

  public static PaxePacket seal(NodeId from, NodeId to, Channel channel, byte epoch, byte[] plaintext, byte[] clusterKey)
      throws GeneralSecurityException {
    var nonce = new byte[NONCE_SIZE];
    ThreadLocalRandom.current().nextBytes(nonce);

    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(clusterKey, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));

    var prefix = prefixBytes(from, to, channel, epoch);
    cipher.updateAAD(prefix);

    var encrypted = cipher.doFinal(plaintext);
    var authTag = Arrays.copyOfRange(encrypted, encrypted.length - AUTH_TAG_SIZE, encrypted.length);
    var ciphertext = Arrays.copyOf(encrypted, encrypted.length - AUTH_TAG_SIZE);

    return new PaxePacket(from, to, channel, epoch, nonce, ciphertext, authTag);
  }

  public byte[] decrypt(byte[] clusterKey) {
    try {
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(clusterKey, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
      cipher.updateAAD(prefixBytes());

      var combined = new byte[ciphertext.length + AUTH_TAG_SIZE];
      System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
      System.arraycopy(authTag, 0, combined, ciphertext.length, AUTH_TAG_SIZE);
      return cipher.doFinal(combined);
    } catch (GeneralSecurityException e) {
      throw new SecurityException("Decryption failed", e);
    }
  }

  private static byte[] prefixBytes(NodeId from, NodeId to, Channel channel, byte epoch) {
    var buffer = ByteBuffer.allocate(PREFIX_SIZE);
    buffer.putShort(from.id());
    buffer.putShort(to.id());
    buffer.putInt(channel.id());
    buffer.put(epoch);
    return buffer.array();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
  if (!(o instanceof PaxePacket that)) return false;
    return from.equals(that.from)
        && to.equals(that.to)
        && channel.equals(that.channel)
        && epoch == that.epoch
        && Arrays.equals(nonce, that.nonce)
        && Arrays.equals(ciphertext, that.ciphertext)
        && Arrays.equals(authTag, that.authTag);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(from, to, channel, epoch);
    result = 31 * result + Arrays.hashCode(nonce);
    result = 31 * result + Arrays.hashCode(ciphertext);
    result = 31 * result + Arrays.hashCode(authTag);
    return result;
  }
}
