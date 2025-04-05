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
package com.github.trex_paxos.paxe;

import com.github.trex_paxos.network.Channel;
import com.github.trex_paxos.NodeId;

import java.util.Arrays;
import java.util.Objects;

public record PaxeMessage(
    NodeId from,
    NodeId to,
    Channel channel,
    byte[] payload
) {
  public PaxeMessage {
    Objects.requireNonNull(from, "from cannot be null");
    Objects.requireNonNull(to, "to cannot be null");
    Objects.requireNonNull(channel, "channel cannot be null");
    Objects.requireNonNull(payload, "payload cannot be null");
  }

  public byte[] serialize() {
    return payload;
  }

  public static PaxeMessage deserialize(NodeId from, NodeId to, Channel channel, byte[] payload) {
    return new PaxeMessage(from, to, channel, payload);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    //noinspection DeconstructionCanBeUsed
    if (!(o instanceof PaxeMessage that)) return false;
    return from.equals(that.from)
        && to.equals(that.to)
        && channel.equals(that.channel)
        && Arrays.equals(payload, that.payload);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(from, to, channel);
    result = 31 * result + Arrays.hashCode(payload);
    return result;
  }
}
