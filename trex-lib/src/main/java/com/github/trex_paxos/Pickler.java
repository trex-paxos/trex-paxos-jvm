// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

import java.nio.ByteBuffer;

/// Interface for serializing and deserializing objects to ByteBuffers
public interface Pickler<T> {
  /// Serializes the given object into the provided ByteBuffer
  ///
  /// @param object The object to serialize
  /// @param buffer The ByteBuffer to write to
  void serialize(T object, ByteBuffer buffer);

  /// Deserializes an object from the provided ByteBuffer
  ///
  /// @param buffer The ByteBuffer to read from
  /// @return The deserialized object
  T deserialize(ByteBuffer buffer);

  /// Calculates the size in bytes required to serialize the given object
  ///
  /// This allows for pre-allocating buffers of the correct size without
  /// having to serialize the object first.
  ///
  /// @param value The object to calculate the size for
  /// @return The number of bytes required to serialize the object
  int sizeOf(T value);
}
