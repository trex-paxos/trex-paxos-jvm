// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.network;

public record NetworkAddress(String host, int port) {
  public NetworkAddress {
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("Invalid port: " + port);
    }
  }
  public NetworkAddress(int port) {
    this("localhost", port);
  }
}
