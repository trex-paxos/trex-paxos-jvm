package com.github.trex_paxos.paxe;

import java.nio.ByteBuffer;


/// Protocol constants and validation for Paxe secure network communication.
/// Encapsulates wire format knowledge and validation logic.
public final class PaxeProtocol {

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
  // TODO use this
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

}
