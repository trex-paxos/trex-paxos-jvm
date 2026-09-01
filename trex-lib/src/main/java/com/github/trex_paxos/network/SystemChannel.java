// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.network;

import java.util.List;

/// Well-known Trex channels. All `u32` channel values belong to the host application; these are
/// conventional defaults for Paxos traffic.
public enum SystemChannel {
  CONSENSUS(1),
  PROXY(2);

  final Channel channel;

  public Channel value() {
    return channel;
  }

  SystemChannel(int id) {
    this.channel = new Channel(id);
  }

  public static List<Channel> systemChannels() {
    return List.of(CONSENSUS.channel, PROXY.channel);
  }

  public int id() {
    return channel.id();
  }
}
