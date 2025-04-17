// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.network;

import java.util.List;

/// System channels are used for system messages that are part of the trex paxos and paxe protocols.
/// Channels below 100 are reserved for system channels.
public enum SystemChannel {
  CONSENSUS((short) 1),       // Core paxos consensus
  PROXY((short) 2),          // Forward results to leader
  KEY_EXCHANGE((short) 3);   // Key exchange for secure communication

  final Channel channel;

  public Channel value() {
    return channel;
  }

  SystemChannel(short id) {
    this.channel = new Channel(id);
  }

  public static List<Channel> systemChannels() {
    return List.of(CONSENSUS.channel, PROXY.channel, KEY_EXCHANGE.channel);
  }

  public short id() {
    return channel.id();
  }
}
