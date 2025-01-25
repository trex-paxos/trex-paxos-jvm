package com.github.trex_paxos.paxe;

import java.nio.ByteBuffer;
import java.util.logging.Logger;

/// Zero-copy buffer manipulation utilities optimized for direct buffers
public final class BufferUtils {
  // FIXME do not use a separate logger use one static init logger of the main protocol class
  private static final Logger LOGGER = Logger.getLogger(BufferUtils.class.getName());

  private BufferUtils() {}

  /// Create a new slice of specified size, checking bounds
  ///
  /// @param buffer Source buffer
  /// @param offset Offset from current position
  /// @param length Length of slice
  /// @return New buffer slice
  public static ByteBuffer safeSlice(ByteBuffer buffer, int offset, int length) {
    if (buffer.remaining() < offset + length) {
      throw new IllegalArgumentException(String.format(
          "Buffer too small: remaining=%d required=%d",
          buffer.remaining(), offset + length));
    }

    LOGGER.finest(() -> String.format(
        "Creating slice: position=%d offset=%d length=%d",
        buffer.position(), offset, length));

    return buffer.slice(buffer.position() + offset, length);
  }

  /// Efficiently copy between buffers maintaining positions
  ///
  /// @param src Source buffer
  /// @param dst Destination buffer
  /// @param length Bytes to copy
  public static void copyBuffer(ByteBuffer src, ByteBuffer dst, int length) {
    if (src.remaining() < length || dst.remaining() < length) {
      throw new IllegalArgumentException(String.format(
          "Buffer too small: src.remaining=%d dst.remaining=%d required=%d",
          src.remaining(), dst.remaining(), length));
    }

    if (src.isDirect() && dst.isDirect()) {
      // Zero-copy for direct buffers
      ByteBuffer slice = src.slice(src.position(), length);
      dst.put(slice);
      src.position(src.position() + length);
    } else {
      // Fallback for heap buffers
      byte[] temp = new byte[length];
      src.get(temp);
      dst.put(temp);
    }

    LOGGER.finest(() -> String.format("Copied %d bytes between buffers", length));
  }

  /// Compact a direct buffer efficiently
  ///
  /// @param buffer Buffer to compact
  /// @return Same buffer for chaining
  public static ByteBuffer compactDirect(ByteBuffer buffer) {
    if (!buffer.isDirect()) {
      buffer.compact();
      return buffer;
    }

    int remaining = buffer.remaining();
    if (remaining == 0) {
      buffer.clear();
      return buffer;
    }

    if (buffer.position() > 0) {
      ByteBuffer slice = buffer.slice();
      buffer.clear();
      buffer.put(slice);
    }

    LOGGER.finest(() -> String.format(
        "Compacted direct buffer: original remaining=%d new position=%d",
        remaining, buffer.position()));

    return buffer;
  }

  /// Zero a buffer's contents for security
  ///
  /// @param buffer Buffer to zero
  public static void zeroBuffer(ByteBuffer buffer) {
    int position = buffer.position();
    buffer.clear();
    while (buffer.hasRemaining()) {
      buffer.put((byte) 0);
    }
    buffer.position(position);
    LOGGER.finest(() -> "Zeroed buffer contents");
  }

  /// Create a ByteBuffer from byte array minimizing copies
  ///
  /// @param data Source data
  /// @param direct If true, create direct buffer
  /// @return New buffer containing data
  public static ByteBuffer wrapBytes(byte[] data, boolean direct) {
    ByteBuffer buffer = direct ?
        ByteBuffer.allocateDirect(data.length) :
        ByteBuffer.allocate(data.length);
    buffer.put(data).flip();
    return buffer;
  }
}
