// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos;

public interface Pickler<T> {
  byte[] serialize(T cmd);

  T deserialize(byte[] bytes);
}
