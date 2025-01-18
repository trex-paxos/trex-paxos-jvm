package com.github.trex_paxos.paxe;

import com.github.trex_paxos.Pickler;

import java.nio.ByteBuffer;

/**
 * Zero-copy serialization interface that writes directly to pre-allocated buffers.
 * For use in high-performance network paths to avoid intermediate allocations.
 */
public interface DirectPickler<T> extends Pickler<T> {
  /**
   * Serialize id directly into provided buffer.
   * Buffer position will be advanced by the written bytes.
   * @param value The id to serialize
   * @param buffer Pre-allocated buffer to write into
   * @return Number of bytes written
   */
  int serialize(T value, ByteBuffer buffer);

  /**
   * Deserialize id directly from provided buffer.
   * Buffer position will be advanced by the read bytes.
   * @param buffer Buffer containing serialized data
   * @return Deserialized id
   */
  T deserialize(ByteBuffer buffer);

  /**
   * Default implementation of byte[] serialize uses an intermediate buffer.
   * Implementors should override for more efficient handling if possible.
   */
  @Override
  default byte[] serialize(T value) {
    ByteBuffer buffer = ByteBuffer.allocate(65535);
    int written = serialize(value, buffer);
    byte[] result = new byte[written];
    buffer.flip();
    buffer.get(result);
    return result;
  }

  /**
   * Default implementation of byte[] deserialize wraps in ByteBuffer.
   * Implementors should override for more efficient handling if possible.
   */
  @Override
  default T deserialize(byte[] bytes) {
    return deserialize(ByteBuffer.wrap(bytes));
  }
}
