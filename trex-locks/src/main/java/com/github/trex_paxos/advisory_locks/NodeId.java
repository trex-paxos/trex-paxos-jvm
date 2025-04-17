// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.advisory_locks;

public record NodeId(byte id) {
  @Override
  public String toString() {
    return "Node-" + id;
  }
}
