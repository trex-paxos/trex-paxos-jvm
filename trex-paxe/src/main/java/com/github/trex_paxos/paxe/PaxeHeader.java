package com.github.trex_paxos.paxe;

final class PaxeHeader {
  private static final int FROM_NODE_SHIFT = 48;
  private static final int TO_NODE_SHIFT = 32;
  private static final int CHANNEL_SHIFT = 16;
  private static final int LENGTH_MASK = 0xFFFF;

  static void validate(int fromNode, int toNode, int channel, int length) {
    if (fromNode < 1 || fromNode > 65535) throw new IllegalArgumentException("fromNode must be between 1 and 65535");
    if (toNode < 1 || toNode > 65535) throw new IllegalArgumentException("toNode must be between 1 and 65535");
    if (channel < 1 || channel > 65535) throw new IllegalArgumentException("channel must be between 1 and 65535");
    if (length < 1 || length > 65535) throw new IllegalArgumentException("length must be between 1 and 65535");
  }

  static byte[] toBytes(int fromNode, int toNode, int channel, int length) {
    validate(fromNode, toNode, channel, length);
    long packed = pack(fromNode, toNode, channel, length);
    byte[] bytes = new byte[8];
    bytes[0] = (byte)(packed >>> 56);
    bytes[1] = (byte)(packed >>> 48);
    bytes[2] = (byte)(packed >>> 40);
    bytes[3] = (byte)(packed >>> 32);
    bytes[4] = (byte)(packed >>> 24);
    bytes[5] = (byte)(packed >>> 16);
    bytes[6] = (byte)(packed >>> 8);
    bytes[7] = (byte)packed;
    return bytes;
  }

  static long pack(int fromNode, int toNode, int channel, int length) {
    return ((long)(fromNode & 0xFFFF) << FROM_NODE_SHIFT) |
        ((long)(toNode & 0xFFFF) << TO_NODE_SHIFT) |
        ((long)(channel & 0xFFFF) << CHANNEL_SHIFT) |
        (length & LENGTH_MASK);
  }

  static int fromNode(byte[] bytes) {
    return (int)((((long)bytes[0] & 0xFF) << 8) | ((long)bytes[1] & 0xFF));
  }

  static int toNode(byte[] bytes) {
    return (int)((((long)bytes[2] & 0xFF) << 8) | ((long)bytes[3] & 0xFF));
  }

  static int channel(byte[] bytes) {
    return (int)((((long)bytes[4] & 0xFF) << 8) | ((long)bytes[5] & 0xFF));
  }

  static int length(byte[] bytes) {
    return (int)((((long)bytes[6] & 0xFF) << 8) | ((long)bytes[7] & 0xFF));
  }

  static void unpack(byte[] bytes, int[] values) {
    values[0] = fromNode(bytes);
    values[1] = toNode(bytes);
    values[2] = channel(bytes);
    values[3] = length(bytes);
    validate(values[0], values[1], values[2], values[3]);
  }

  private PaxeHeader() {} // No instantiation
}
