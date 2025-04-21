// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import java.nio.ByteBuffer;

public interface Pickler<T> {
  void serialize(T object, ByteBuffer buffer);
  
  T deserialize(ByteBuffer buffer);
  
  /**
   * Calculate the size in bytes that would be required to serialize the given object.
   * This allows for pre-allocating buffers of the correct size without having to
   * serialize the object first.
   *
   * @param value The object to calculate the size for
   * @return The number of bytes required to serialize the object
   */
  int sizeOf(T value);
}
