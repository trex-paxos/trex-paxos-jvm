/*
 * Copyright 2024 - 2025 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
