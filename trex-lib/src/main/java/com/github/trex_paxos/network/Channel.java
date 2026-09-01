// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.network;

/// Unsigned 32-bit channel identifier for multiplexing logical streams.
/// Values are serialized as big-endian `u32` on the wire; Java stores the bit pattern in a signed `int`.
public record Channel(int id) {

  /// Returns the unsigned 32-bit value as a `long` for display or comparison.
  public long unsignedValue() {
    return Integer.toUnsignedLong(id);
  }
}
