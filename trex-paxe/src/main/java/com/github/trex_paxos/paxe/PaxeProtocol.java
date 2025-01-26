package com.github.trex_paxos.paxe;

import java.nio.ByteBuffer;
import java.util.logging.Logger;

/// Protocol constants and validation for Paxe secure network communication.
/// Encapsulates wire format knowledge and validation logic.
public final class PaxeProtocol {
  // FIXME do not use a separate logger use one static init logger of the main protocol class
  static final Logger LOGGER = Logger.getLogger(PaxeProtocol.class.getName());

  // Header structure (8 bytes)
  public static final int FROM_OFFSET = 0;
  public static final int TO_OFFSET = 2;
  public static final int CHANNEL_OFFSET = 4;
  public static final int LENGTH_OFFSET = 6;
  public static final int HEADER_SIZE = 8;

  // Flags byte structure
  public static final int FLAGS_OFFSET = HEADER_SIZE;
  public static final byte FLAG_DEK = 0x01;        // bit 0: DEK encryption
  public static final byte FLAG_MAGIC_0 = 0x02;    // bit 1: must be 0
  public static final byte FLAG_MAGIC_1 = 0x04;    // bit 2: must be 1

  // GCM parameters
  public static final int GCM_NONCE_LENGTH = 12;
  public static final int GCM_TAG_LENGTH = 16;
  public static final int GCM_TAG_LENGTH_BITS = 128;

  // Protocol sizing
  public static final int MAX_UDP_SIZE = 65507;
  public static final int MIN_MESSAGE_SIZE = HEADER_SIZE + 1; // Header + flags
  public static final int DEK_THRESHOLD = 64;
  public static final int DEK_KEY_SIZE = 16;
  public static final int DEK_SECTION_SIZE = DEK_KEY_SIZE + GCM_NONCE_LENGTH + GCM_TAG_LENGTH + 2;

  private PaxeProtocol() {}

  // Add to PaxeProtocol.java - flag utility method
  public static boolean isValidFlags(byte flags) {
    // Standard encryption: Only MAGIC_1 set
    if ((flags & FLAG_DEK) == 0) {
      return (flags & (FLAG_MAGIC_0 | FLAG_MAGIC_1)) == FLAG_MAGIC_1;
    }
    // DEK encryption: DEK and MAGIC_1 set
    return (flags & (FLAG_DEK | FLAG_MAGIC_0 | FLAG_MAGIC_1)) == (FLAG_DEK | FLAG_MAGIC_1);
  }

  public static boolean validateStructure(ByteBuffer buffer) {
    if (buffer.remaining() < MIN_MESSAGE_SIZE) {
      return false;
    }

    buffer.position(FLAGS_OFFSET);
    byte flags = buffer.get();

    return (flags & FLAG_MAGIC_1) != 0 && (flags & FLAG_MAGIC_0) == 0;
  }

  /// Extract header fields from a message buffer
  ///
  /// @param buffer Buffer positioned at start of message
  /// @return Array containing [fromId, toId, channelId, length]
  public static Header extractHeader(ByteBuffer buffer) {
    if (buffer.remaining() < HEADER_SIZE) {
      throw new IllegalArgumentException("Buffer too small for header");
    }

    short fromId = buffer.getShort(buffer.position() + FROM_OFFSET);
    short toId = buffer.getShort(buffer.position() + TO_OFFSET);
    short channelId = buffer.getShort(buffer.position() + CHANNEL_OFFSET);
    short length = buffer.getShort(buffer.position() + LENGTH_OFFSET);

    LOGGER.finest(() -> String.format("Extracted header: from=%d to=%d channel=%d length=%d",
        fromId, toId, channelId, length));

    return new Header(fromId, toId, channelId, length);
  }

  /// Read envelope structure from buffer
  ///
  /// @param buffer Buffer containing envelope
  /// @return Envelope contents
  public static Envelope readEnvelope(ByteBuffer buffer) {
    if (buffer.remaining() < DEK_SECTION_SIZE) {
      throw new SecurityException("Buffer too small for envelope"); // Changed from IllegalArgumentException
    }

    byte[] dekKey = new byte[DEK_KEY_SIZE];
    byte[] dekNonce = new byte[GCM_NONCE_LENGTH];

    buffer.get(dekKey);
    buffer.get(dekNonce);
    short payloadLength = buffer.getShort();

    LOGGER.finest(() -> String.format("Read envelope: payload_length=%d", payloadLength));
    return new Envelope(dekKey, dekNonce, payloadLength);
  }

  public record Header(short fromId, short toId, short channelId, short length) {}
  public record Envelope(byte[] dekKey, byte[] dekNonce, short payloadLength) {}
}
